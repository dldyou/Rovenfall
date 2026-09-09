package org.dldyou.rovenfall.mobs;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

public record BossEncounter(
        UUID encounterId,
        UUID bossId,
        ResourceKey<Level> dimension,
        BlockPos origin,
        int radius,
        Status status,
        int phase,
        long startedAtEpochMillis,
        long updatedAtEpochMillis,
        Map<UUID, Double> contributions,
        Set<UUID> settledPlayers) {
    public static final int MIN_RADIUS = 8;
    public static final int MAX_RADIUS = 64;
    public static final int MAX_CONTRIBUTORS = 64;
    public static final double MAX_CONTRIBUTION_PER_PLAYER = 500.0;
    private static final Codec<Map<UUID, Double>> CONTRIBUTIONS_CODEC = Codec.unboundedMap(
            UUIDUtil.STRING_CODEC,
            Codec.DOUBLE.validate(value -> !Double.isFinite(value) || value <= 0
                    || value > MAX_CONTRIBUTION_PER_PLAYER
                    ? DataResult.error(() -> "boss contribution is invalid")
                    : DataResult.success(value)))
            .validate(values -> values.size() > MAX_CONTRIBUTORS
                    ? DataResult.error(() -> "boss contributor count exceeds " + MAX_CONTRIBUTORS)
                    : DataResult.success(values));
    private static final Codec<Set<UUID>> SETTLED_PLAYERS_CODEC = UUIDUtil.STRING_CODEC
            .listOf(0, MAX_CONTRIBUTORS)
            .flatXmap(BossEncounter::settledFromList, BossEncounter::settledToList);
    public static final Codec<BossEncounter> CODEC = RecordCodecBuilder.<BossEncounter>create(instance -> instance.group(
            UUIDUtil.STRING_CODEC.fieldOf("encounter_id").forGetter(BossEncounter::encounterId),
            UUIDUtil.STRING_CODEC.fieldOf("boss_id").forGetter(BossEncounter::bossId),
            Level.RESOURCE_KEY_CODEC.fieldOf("dimension").forGetter(BossEncounter::dimension),
            BlockPos.CODEC.fieldOf("origin").forGetter(BossEncounter::origin),
            Codec.INT.fieldOf("radius").forGetter(BossEncounter::radius),
            Status.CODEC.fieldOf("status").forGetter(BossEncounter::status),
            Codec.INT.fieldOf("phase").forGetter(BossEncounter::phase),
            Codec.LONG.fieldOf("started_at").forGetter(BossEncounter::startedAtEpochMillis),
            Codec.LONG.fieldOf("updated_at").forGetter(BossEncounter::updatedAtEpochMillis),
            CONTRIBUTIONS_CODEC.optionalFieldOf("contributions", Map.of()).forGetter(BossEncounter::contributions),
            SETTLED_PLAYERS_CODEC.optionalFieldOf("settled_players", Set.of()).forGetter(BossEncounter::settledPlayers)
    ).apply(instance, BossEncounter::new)).validate(BossEncounter::validate);

    public BossEncounter {
        origin = origin == null ? null : origin.immutable();
        contributions = contributions == null ? Map.of() : Map.copyOf(contributions);
        settledPlayers = settledPlayers == null ? Set.of() : Set.copyOf(settledPlayers);
    }

    public boolean active() {
        return status == Status.INTRO || status == Status.ACTIVE || status == Status.REWARDING;
    }

    public boolean protects(ResourceKey<Level> candidateDimension, BlockPos position) {
        if (candidateDimension == null || position == null || !dimension.equals(candidateDimension)) {
            return false;
        }
        long x = (long) position.getX() - origin.getX();
        long z = (long) position.getZ() - origin.getZ();
        return x * x + z * z <= (long) radius * radius;
    }

    public double totalContribution() {
        return contributions.values().stream().mapToDouble(Double::doubleValue).sum();
    }

    public BossEncounter withStatus(Status next, int nextPhase, long timestamp) {
        return new BossEncounter(
                encounterId, bossId, dimension, origin, radius, next, nextPhase,
                startedAtEpochMillis, timestamp, contributions, settledPlayers);
    }

    public BossEncounter withContribution(UUID playerId, double amount, long timestamp) {
        Map<UUID, Double> updated = new HashMap<>(contributions);
        double previous = updated.getOrDefault(playerId, 0.0);
        updated.put(playerId, Math.min(MAX_CONTRIBUTION_PER_PLAYER, previous + amount));
        return new BossEncounter(
                encounterId, bossId, dimension, origin, radius, status, phase,
                startedAtEpochMillis, timestamp, updated, settledPlayers);
    }

    public BossEncounter settle(UUID playerId, long timestamp) {
        Set<UUID> updated = new HashSet<>(settledPlayers);
        updated.add(playerId);
        return new BossEncounter(
                encounterId, bossId, dimension, origin, radius, status, phase,
                startedAtEpochMillis, timestamp, contributions, updated);
    }

    public static DataResult<BossEncounter> validate(BossEncounter encounter) {
        if (encounter == null || encounter.encounterId == null || encounter.bossId == null
                || encounter.dimension == null || encounter.origin == null || encounter.status == null
                || encounter.encounterId.equals(new UUID(0, 0)) || encounter.bossId.equals(new UUID(0, 0))
                || encounter.encounterId.equals(encounter.bossId)
                || !Level.isInSpawnableBounds(encounter.origin)
                || encounter.radius < MIN_RADIUS || encounter.radius > MAX_RADIUS
                || encounter.phase < 0 || encounter.phase > 3
                || encounter.startedAtEpochMillis < 0
                || encounter.updatedAtEpochMillis < encounter.startedAtEpochMillis
                || encounter.contributions.size() > MAX_CONTRIBUTORS
                || !encounter.contributions.keySet().containsAll(encounter.settledPlayers)) {
            return DataResult.error(() -> "boss encounter is invalid");
        }
        return DataResult.success(encounter);
    }

    private static DataResult<Set<UUID>> settledFromList(List<UUID> values) {
        Set<UUID> settled = new HashSet<>(values);
        return settled.size() == values.size()
                ? DataResult.success(Set.copyOf(settled))
                : DataResult.error(() -> "boss encounter repeats a settled player");
    }

    private static DataResult<List<UUID>> settledToList(Set<UUID> values) {
        List<UUID> sorted = new ArrayList<>(values);
        sorted.sort(Comparator.naturalOrder());
        return DataResult.success(List.copyOf(sorted));
    }

    public enum Status {
        INTRO,
        ACTIVE,
        REWARDING,
        DEFEATED,
        FAILED;

        public static final Codec<Status> CODEC = Codec.STRING.comapFlatMap(value -> {
            try {
                return DataResult.success(valueOf(value.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException exception) {
                return DataResult.error(() -> "unknown boss encounter status " + value);
            }
        }, value -> value.name().toLowerCase(Locale.ROOT));
    }
}
