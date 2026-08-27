package org.dldyou.rovenfall.rpg;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import org.dldyou.rovenfall.Rovenfall;

/** Persistent provenance for placed resources that must never award natural-resource XP. */
public final class ActivityWorldSavedData extends SavedData {
    public static final int CURRENT_SCHEMA_VERSION = 1;
    public static final int MAX_SYNTHETIC_RESOURCES = 250_000;

    private static final Codec<Set<ResourcePosition>> SYNTHETIC_RESOURCES_CODEC =
            ResourcePosition.CODEC.listOf(0, MAX_SYNTHETIC_RESOURCES)
                    .flatXmap(ActivityWorldSavedData::positionsFromEntries, ActivityWorldSavedData::positionsToEntries);

    public static final Codec<ActivityWorldSavedData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.optionalFieldOf("schema_version", 0).forGetter(data -> data.schemaVersion),
            SYNTHETIC_RESOURCES_CODEC.optionalFieldOf("synthetic_resources", Set.of())
                    .forGetter(data -> data.syntheticResources),
            Codec.BOOL.optionalFieldOf("saturated", false).forGetter(data -> data.saturated)
    ).apply(instance, ActivityWorldSavedData::decode));

    public static final SavedDataType<ActivityWorldSavedData> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(Rovenfall.MOD_ID, "activity_world_state"),
            ActivityWorldSavedData::new,
            CODEC);

    private static final java.util.Map<Integer, UnaryOperator<PersistedState>> MIGRATIONS = java.util.Map.of(
            0, state -> state.atVersion(1));

    private final int schemaVersion;
    private final boolean writable;
    private final Set<ResourcePosition> syntheticResources;
    private boolean saturated;

    public ActivityWorldSavedData() {
        this(CURRENT_SCHEMA_VERSION, Set.of(), false, true);
    }

    private ActivityWorldSavedData(
            int schemaVersion,
            Collection<ResourcePosition> syntheticResources,
            boolean saturated,
            boolean writable) {
        this.schemaVersion = schemaVersion;
        this.syntheticResources = new LinkedHashSet<>(syntheticResources);
        this.saturated = saturated;
        this.writable = writable;
    }

    private static ActivityWorldSavedData decode(
            int schemaVersion,
            Set<ResourcePosition> syntheticResources,
            boolean saturated) {
        PersistedState original = new PersistedState(schemaVersion, syntheticResources, saturated);
        if (schemaVersion < 0 || schemaVersion > CURRENT_SCHEMA_VERSION) {
            return new ActivityWorldSavedData(schemaVersion, syntheticResources, saturated, false);
        }
        PersistedState candidate = original;
        while (candidate.schemaVersion() < CURRENT_SCHEMA_VERSION) {
            UnaryOperator<PersistedState> migration = MIGRATIONS.get(candidate.schemaVersion());
            if (migration == null) {
                return new ActivityWorldSavedData(
                        original.schemaVersion(), original.syntheticResources(), original.saturated(), false);
            }
            int expected = candidate.schemaVersion() + 1;
            candidate = migration.apply(candidate);
            if (candidate.schemaVersion() != expected) {
                return new ActivityWorldSavedData(
                        original.schemaVersion(), original.syntheticResources(), original.saturated(), false);
            }
        }
        return new ActivityWorldSavedData(
                candidate.schemaVersion(), candidate.syntheticResources(), candidate.saturated(), true);
    }

    public static ActivityWorldSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    public boolean isWritable() {
        return writable;
    }

    public int schemaVersion() {
        return schemaVersion;
    }

    public boolean isSaturated() {
        return saturated;
    }

    public int syntheticResourceCount() {
        return syntheticResources.size();
    }

    /** Captures the provenance markers that belong to one resettable dimension. */
    public DimensionSnapshot snapshotDimension(ResourceKey<Level> dimension) {
        if (dimension == null) {
            throw new IllegalArgumentException("dimension");
        }
        Set<Long> positions = new LinkedHashSet<>();
        syntheticResources.stream()
                .filter(position -> position.dimension().equals(dimension))
                .map(ResourcePosition::position)
                .sorted()
                .forEach(positions::add);
        return new DimensionSnapshot(dimension, positions);
    }

    /** Replaces only one dimension's provenance after a verified world restore. */
    public boolean replaceDimension(DimensionSnapshot snapshot) {
        if (!writable || saturated || snapshot == null || !snapshot.isValid()) {
            return false;
        }
        long retained = syntheticResources.stream()
                .filter(position -> !position.dimension().equals(snapshot.dimension()))
                .count();
        if (retained + snapshot.positions().size() > MAX_SYNTHETIC_RESOURCES) {
            saturated = true;
            setDirty();
            return false;
        }
        Set<ResourcePosition> replacements = new LinkedHashSet<>();
        snapshot.positions().stream()
                .map(BlockPos::of)
                .map(position -> ResourcePosition.at(snapshot.dimension(), position))
                .forEach(replacements::add);
        boolean changed = syntheticResources.removeIf(position ->
                position.dimension().equals(snapshot.dimension()));
        changed |= syntheticResources.addAll(replacements);
        if (changed) {
            setDirty();
        }
        return true;
    }

    /** Records an entity-placed resource. Saturation disables natural-resource awards fail-closed. */
    public boolean markSynthetic(ResourceKey<Level> dimension, BlockPos position) {
        if (!writable || dimension == null || position == null || saturated) {
            return false;
        }
        ResourcePosition key = ResourcePosition.at(dimension, position);
        if (syntheticResources.contains(key)) {
            return true;
        }
        if (syntheticResources.size() >= MAX_SYNTHETIC_RESOURCES) {
            saturated = true;
            setDirty();
            return false;
        }
        syntheticResources.add(key);
        setDirty();
        return true;
    }

    /**
     * Consumes a placed-resource marker after a completed break. Read-only or saturated state is treated as
     * synthetic so corrupted/future data can never turn placed resources into natural resources.
     */
    public boolean consumeSynthetic(ResourceKey<Level> dimension, BlockPos position) {
        if (dimension == null || position == null || !writable || saturated) {
            return true;
        }
        boolean removed = syntheticResources.remove(ResourcePosition.at(dimension, position));
        if (removed) {
            setDirty();
        }
        return removed;
    }

    /** Conservatively propagates markers before a validated piston move; source markers remain fail-safe. */
    public void propagatePistonMove(
            ResourceKey<Level> dimension,
            Collection<BlockPos> sources,
            Direction movement) {
        if (!writable || saturated || dimension == null || sources == null || movement == null) {
            return;
        }
        List<BlockPos> markedSources = sources.stream()
                .filter(position -> position != null
                        && syntheticResources.contains(ResourcePosition.at(dimension, position)))
                .toList();
        for (BlockPos source : markedSources) {
            markSynthetic(dimension, source.relative(movement));
        }
    }

    /** Removes known markers when a dimension is deliberately regenerated. */
    public int clearDimension(ResourceKey<Level> dimension) {
        if (!writable || dimension == null) {
            return 0;
        }
        int before = syntheticResources.size();
        syntheticResources.removeIf(position -> position.dimension().equals(dimension));
        int removed = before - syntheticResources.size();
        if (removed > 0) {
            setDirty();
        }
        return removed;
    }

    private static DataResult<Set<ResourcePosition>> positionsFromEntries(List<ResourcePosition> entries) {
        Set<ResourcePosition> result = new LinkedHashSet<>();
        for (ResourcePosition entry : entries) {
            if (!result.add(entry)) {
                return DataResult.error(() -> "Duplicate synthetic resource position " + entry);
            }
        }
        return DataResult.success(Set.copyOf(result));
    }

    private static DataResult<List<ResourcePosition>> positionsToEntries(Set<ResourcePosition> positions) {
        return DataResult.success(positions.stream()
                .sorted(Comparator.comparing((ResourcePosition position) -> position.dimension().identifier())
                        .thenComparingLong(ResourcePosition::position))
                .toList());
    }

    public record ResourcePosition(ResourceKey<Level> dimension, long position) {
        private static final Codec<ResourcePosition> CODEC = RecordCodecBuilder.<ResourcePosition>create(instance ->
                instance.group(
                        Level.RESOURCE_KEY_CODEC.fieldOf("dimension").forGetter(ResourcePosition::dimension),
                        Codec.LONG.fieldOf("position").forGetter(ResourcePosition::position)
                ).apply(instance, ResourcePosition::new)).validate(ResourcePosition::validate);

        public static ResourcePosition at(ResourceKey<Level> dimension, BlockPos position) {
            return new ResourcePosition(dimension, position.asLong());
        }

        private static DataResult<ResourcePosition> validate(ResourcePosition position) {
            return position == null || position.dimension() == null
                    ? DataResult.error(() -> "Synthetic resource dimension is missing")
                    : DataResult.success(position);
        }
    }

    public record DimensionSnapshot(ResourceKey<Level> dimension, Set<Long> positions) {
        private static final Codec<Set<Long>> POSITIONS_CODEC = Codec.LONG.listOf(0, MAX_SYNTHETIC_RESOURCES)
                .flatXmap(DimensionSnapshot::positionsFromEntries, DimensionSnapshot::positionsToEntries);

        public static final Codec<DimensionSnapshot> CODEC = RecordCodecBuilder
                .<DimensionSnapshot>create(instance -> instance.group(
                        Level.RESOURCE_KEY_CODEC.fieldOf("dimension").forGetter(DimensionSnapshot::dimension),
                        POSITIONS_CODEC.fieldOf("positions").forGetter(DimensionSnapshot::positions)
                ).apply(instance, DimensionSnapshot::new)).validate(snapshot -> snapshot.isValid()
                        ? DataResult.success(snapshot)
                        : DataResult.error(() -> "Invalid activity dimension snapshot"));

        public DimensionSnapshot {
            positions = positions == null ? Set.of() : Set.copyOf(positions);
        }

        public static DimensionSnapshot empty(ResourceKey<Level> dimension) {
            return new DimensionSnapshot(dimension, Set.of());
        }

        private boolean isValid() {
            return dimension != null && positions.size() <= MAX_SYNTHETIC_RESOURCES;
        }

        private static DataResult<Set<Long>> positionsFromEntries(List<Long> entries) {
            Set<Long> result = new LinkedHashSet<>();
            for (Long entry : entries) {
                if (entry == null || !result.add(entry)) {
                    return DataResult.error(() -> "Duplicate or missing activity snapshot position");
                }
            }
            return DataResult.success(Set.copyOf(result));
        }

        private static DataResult<List<Long>> positionsToEntries(Set<Long> positions) {
            return DataResult.success(positions.stream().sorted().toList());
        }
    }

    private record PersistedState(
            int schemaVersion,
            Set<ResourcePosition> syntheticResources,
            boolean saturated) {
        private PersistedState {
            syntheticResources = Set.copyOf(syntheticResources);
        }

        private PersistedState atVersion(int version) {
            return new PersistedState(version, syntheticResources, saturated);
        }
    }
}
