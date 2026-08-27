package org.dldyou.rovenfall.mobs;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import org.dldyou.rovenfall.Rovenfall;

public final class BossEncounterSavedData extends SavedData {
    public static final int CURRENT_SCHEMA_VERSION = 1;
    public static final int MAX_ACTIVE_ENCOUNTERS = 32;

    private static final Codec<Map<UUID, BossEncounterState>> ENCOUNTERS_CODEC =
            EncounterEntry.CODEC.listOf(0, MAX_ACTIVE_ENCOUNTERS)
                    .flatXmap(BossEncounterSavedData::encountersFromEntries,
                            BossEncounterSavedData::encounterEntries);

    public static final Codec<BossEncounterSavedData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.optionalFieldOf("schema_version", 0).forGetter(data -> data.schemaVersion),
            ENCOUNTERS_CODEC.optionalFieldOf("active_encounters", Map.of())
                    .forGetter(data -> data.encounters)
    ).apply(instance, BossEncounterSavedData::decode));

    public static final SavedDataType<BossEncounterSavedData> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(Rovenfall.MOD_ID, "boss_encounter_state"),
            BossEncounterSavedData::new,
            CODEC);

    private static final Map<Integer, UnaryOperator<PersistedState>> MIGRATIONS = Map.of(
            0, state -> state.atVersion(1));

    private final int schemaVersion;
    private final boolean writable;
    private final Map<UUID, BossEncounterState> encounters;

    public BossEncounterSavedData() {
        this(CURRENT_SCHEMA_VERSION, Map.of(), true);
    }

    private BossEncounterSavedData(
            int schemaVersion, Map<UUID, BossEncounterState> encounters, boolean writable) {
        this.schemaVersion = schemaVersion;
        this.encounters = new LinkedHashMap<>(encounters);
        this.writable = writable;
    }

    private static BossEncounterSavedData decode(
            int schemaVersion, Map<UUID, BossEncounterState> encounters) {
        PersistedState original = new PersistedState(schemaVersion, encounters);
        if (schemaVersion < 0 || schemaVersion > CURRENT_SCHEMA_VERSION) {
            return new BossEncounterSavedData(schemaVersion, encounters, false);
        }
        PersistedState candidate = original;
        while (candidate.schemaVersion() < CURRENT_SCHEMA_VERSION) {
            UnaryOperator<PersistedState> migration = MIGRATIONS.get(candidate.schemaVersion());
            if (migration == null) {
                return new BossEncounterSavedData(original.schemaVersion(), original.encounters(), false);
            }
            int expected = candidate.schemaVersion() + 1;
            candidate = migration.apply(candidate);
            if (candidate.schemaVersion() != expected) {
                return new BossEncounterSavedData(original.schemaVersion(), original.encounters(), false);
            }
        }
        return new BossEncounterSavedData(candidate.schemaVersion(), candidate.encounters(), true);
    }

    public static BossEncounterSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    public int schemaVersion() {
        return schemaVersion;
    }

    public boolean isWritable() {
        return writable;
    }

    public int activeCount() {
        return encounters.size();
    }

    public List<BossEncounterState> activeEncounters() {
        return encounters.values().stream()
                .sorted(Comparator.comparing(BossEncounterState::encounterId))
                .toList();
    }

    public Optional<BossEncounterState> encounter(UUID encounterId) {
        return Optional.ofNullable(encounters.get(encounterId));
    }

    public Optional<BossEncounterState> encounterByEntity(UUID entityId) {
        if (entityId == null) {
            return Optional.empty();
        }
        return encounters.values().stream()
                .filter(encounter -> encounter.entityId().equals(entityId))
                .findFirst();
    }

    public boolean put(BossEncounterState encounter) {
        if (!writable || encounter == null || !encounter.isValid()) {
            return false;
        }
        BossEncounterState previous = encounters.get(encounter.encounterId());
        if (previous == null && encounters.size() >= MAX_ACTIVE_ENCOUNTERS) {
            return false;
        }
        boolean entityOwnedByAnotherEncounter = encounters.values().stream().anyMatch(candidate ->
                !candidate.encounterId().equals(encounter.encounterId())
                        && candidate.entityId().equals(encounter.entityId()));
        if (entityOwnedByAnotherEncounter || encounter.equals(previous)) {
            return false;
        }
        encounters.put(encounter.encounterId(), encounter);
        setDirty();
        return true;
    }

    public boolean remove(UUID encounterId) {
        if (!writable || encounterId == null || encounters.remove(encounterId) == null) {
            return false;
        }
        setDirty();
        return true;
    }

    private static DataResult<Map<UUID, BossEncounterState>> encountersFromEntries(
            List<EncounterEntry> entries) {
        Map<UUID, BossEncounterState> result = new LinkedHashMap<>();
        java.util.Set<UUID> entities = new java.util.HashSet<>();
        for (EncounterEntry entry : entries) {
            if (entry == null || entry.encounter() == null
                    || !entry.id().equals(entry.encounter().encounterId())
                    || !entry.encounter().isValid()
                    || result.putIfAbsent(entry.id(), entry.encounter()) != null
                    || !entities.add(entry.encounter().entityId())) {
                return DataResult.error(() -> "Duplicate or invalid boss encounter");
            }
        }
        return DataResult.success(Map.copyOf(result));
    }

    private static DataResult<List<EncounterEntry>> encounterEntries(
            Map<UUID, BossEncounterState> encounters) {
        return DataResult.success(encounters.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new EncounterEntry(entry.getKey(), entry.getValue()))
                .toList());
    }

    private record EncounterEntry(UUID id, BossEncounterState encounter) {
        private static final Codec<EncounterEntry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                UUIDUtil.STRING_CODEC.fieldOf("id").forGetter(EncounterEntry::id),
                BossEncounterState.CODEC.fieldOf("encounter").forGetter(EncounterEntry::encounter)
        ).apply(instance, EncounterEntry::new));
    }

    private record PersistedState(int schemaVersion, Map<UUID, BossEncounterState> encounters) {
        private PersistedState atVersion(int version) {
            return new PersistedState(version, encounters);
        }
    }
}
