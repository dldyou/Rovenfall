package org.dldyou.rovenfall.exploration;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.util.FakePlayer;
import org.dldyou.rovenfall.rpg.ActivityXpAwardService;
import org.dldyou.rovenfall.rpg.RpgDefinitionReloadListener;
import org.dldyou.rovenfall.rpg.RpgDefinitionSnapshot;
import org.dldyou.rovenfall.rpg.RpgPlayerSavedData;

/** Server-authoritative discovery and recoverable reward boundary. */
public final class ExplorationDiscoveryService {
    public static final long FUTURE_TIMESTAMP_SKEW_MILLIS = 5 * 60_000L;
    public static final int MAX_RECOVERY_STEPS = 8;
    public static final Identifier EXPLORATION_ACTIVITY =
            Identifier.fromNamespaceAndPath("rovenfall", "exploration");
    private static final UUID ZERO_UUID = new UUID(0L, 0L);

    private ExplorationDiscoveryService() {
    }

    /** Production entry point; location and all reward inputs come from the real server player. */
    public static ObservationResult observe(
            ServerPlayer player, ExplorationDefinitionSnapshot definitions, long timestampEpochMillis) {
        if (player == null || player instanceof FakePlayer || definitions == null
                || player.level().getServer() == null || !player.level().getServer().isSameThread()) {
            return ObservationResult.invalid();
        }
        var server = player.level().getServer();
        return observe(
                ExplorationPlayerSavedData.get(server), definitions,
                RpgPlayerSavedData.get(server), RpgDefinitionReloadListener.snapshot(server),
                player.getUUID(), player.level().dimension(), player.blockPosition(),
                timestampEpochMillis, System.currentTimeMillis());
    }

    /** Synthetic-friendly seam with an explicit authority clock. */
    public static ObservationResult observe(
            ExplorationPlayerSavedData state,
            ExplorationDefinitionSnapshot definitions,
            RpgPlayerSavedData rpg,
            RpgDefinitionSnapshot rpgDefinitions,
            UUID playerId,
            ResourceKey<Level> dimension,
            BlockPos position,
            long timestampEpochMillis,
            long authorityNowEpochMillis) {
        if (state == null || definitions == null || rpg == null || rpgDefinitions == null
                || playerId == null || ZERO_UUID.equals(playerId) || dimension == null || position == null
                || !Level.isInSpawnableBounds(position)
                || !validTimestamp(timestampEpochMillis, authorityNowEpochMillis)) {
            return ObservationResult.invalid();
        }
        ExplorationPlayerState current = state.state(playerId);
        Map<Identifier, ExplorationPlayerState.DiscoveryReceipt> updated =
                new LinkedHashMap<>(current.discoveries());
        int discovered = 0;
        int refreshed = 0;
        for (ExplorationDefinitionSnapshot.Entry entry : definitions.entries(dimension)) {
            ExplorationDefinition definition = entry.definition();
            if (!inside(position, definition.position(), definition.radius())) {
                continue;
            }
            ExplorationPlayerState.DiscoveryReceipt existing = updated.get(entry.id());
            if (existing != null && existing.definitionVersion() == definition.version()) {
                continue;
            }
            if (existing != null) {
                updated.put(entry.id(), existing.atVersion(definition.version()));
                refreshed++;
                continue;
            }
            UUID transactionId = transactionId(playerId, entry.id());
            Optional<ExplorationPlayerState.RewardOperation> reward = definition.activityXp().map(amount ->
                    new ExplorationPlayerState.RewardOperation(
                            transactionId, amount, timestampEpochMillis,
                            ExplorationPlayerState.RewardOperation.Phase.CAPTURED));
            updated.put(entry.id(), new ExplorationPlayerState.DiscoveryReceipt(
                    definition.version(), timestampEpochMillis, transactionId, reward));
            discovered++;
        }
        if (discovered == 0 && refreshed == 0) {
            return new ObservationResult(Status.NO_CHANGE, 0, 0, 0, false);
        }
        if (!state.isWritable()) {
            return new ObservationResult(Status.READ_ONLY, 0, 0, 0, false);
        }
        if (updated.size() > ExplorationPlayerState.MAX_DISCOVERIES) {
            return new ObservationResult(Status.STATE_FULL, 0, 0, 0, false);
        }
        ExplorationPlayerState candidate = new ExplorationPlayerState(updated);
        if (!state.commit(playerId, current, candidate)) {
            return new ObservationResult(Status.CONCURRENT_CHANGE, 0, 0, 0, false);
        }
        RecoveryResult recovery = recover(
                state, rpg, rpgDefinitions, playerId, authorityNowEpochMillis, MAX_RECOVERY_STEPS);
        return new ObservationResult(
                recovery.pending() == 0 ? Status.SUCCESS : Status.REWARD_PENDING,
                discovered, refreshed, recovery.applied(), true);
    }

    public static RecoveryResult recover(
            ExplorationPlayerSavedData state,
            RpgPlayerSavedData rpg,
            RpgDefinitionSnapshot rpgDefinitions,
            UUID playerId,
            long authorityNowEpochMillis) {
        return recover(state, rpg, rpgDefinitions, playerId, authorityNowEpochMillis, MAX_RECOVERY_STEPS);
    }

    public static RecoveryResult recover(
            ExplorationPlayerSavedData state,
            RpgPlayerSavedData rpg,
            RpgDefinitionSnapshot rpgDefinitions,
            UUID playerId,
            long authorityNowEpochMillis,
            int maximumSteps) {
        if (state == null || rpg == null || rpgDefinitions == null || playerId == null
                || ZERO_UUID.equals(playerId) || authorityNowEpochMillis <= 0
                || maximumSteps < 1 || maximumSteps > MAX_RECOVERY_STEPS) {
            return new RecoveryResult(RecoveryStatus.INVALID, 0, 0, 0);
        }
        if (!state.isWritable()) {
            int pending = pendingRewards(state.state(playerId));
            return new RecoveryResult(pending == 0 ? RecoveryStatus.COMPLETE : RecoveryStatus.PENDING,
                    0, 0, pending);
        }
        int attempted = 0;
        int applied = 0;
        ExplorationPlayerState snapshot = state.state(playerId);
        for (Map.Entry<Identifier, ExplorationPlayerState.DiscoveryReceipt> entry
                : snapshot.discoveries().entrySet()) {
            Optional<ExplorationPlayerState.RewardOperation> captured = entry.getValue().rewardOperation()
                    .filter(operation -> operation.phase() == ExplorationPlayerState.RewardOperation.Phase.CAPTURED);
            if (captured.isEmpty() || attempted >= maximumSteps) {
                continue;
            }
            attempted++;
            ExplorationPlayerState.RewardOperation operation = captured.orElseThrow();
            if (!validTimestamp(operation.startedAtEpochMillis(), authorityNowEpochMillis)) {
                continue;
            }
            ActivityXpAwardService.AwardResult result = ActivityXpAwardService.awardQuestReward(
                    rpg, rpgDefinitions, playerId, EXPLORATION_ACTIVITY, operation.amount(),
                    operation.startedAtEpochMillis(), operation.transactionId(), rewardSource(operation.transactionId()));
            if (result.status() != ActivityXpAwardService.Status.SUCCESS
                    && result.status() != ActivityXpAwardService.Status.DUPLICATE) {
                continue;
            }
            if (markApplied(state, playerId, entry.getKey(), operation)) {
                applied++;
            }
        }
        int pending = pendingRewards(state.state(playerId));
        RecoveryStatus status = pending == 0 ? RecoveryStatus.COMPLETE
                : applied > 0 ? RecoveryStatus.PARTIAL : RecoveryStatus.PENDING;
        return new RecoveryResult(status, attempted, applied, pending);
    }

    /** Resolves coordinates only after current server state revalidates guidance access. */
    public static Optional<Target> waypoint(
            ExplorationDefinitionSnapshot definitions,
            ExplorationPlayerState state,
            Identifier discoveryId) {
        if (definitions == null || state == null || discoveryId == null) {
            return Optional.empty();
        }
        return definitions.definition(discoveryId).filter(definition ->
                definition.publicGuidance() || state.discovery(discoveryId)
                        .filter(receipt -> receipt.definitionVersion() == definition.version()).isPresent())
                .map(definition -> new Target(discoveryId, definition.dimension(), definition.position()));
    }

    public static UUID transactionId(UUID playerId, Identifier discoveryId) {
        if (playerId == null || ZERO_UUID.equals(playerId) || discoveryId == null) {
            return ZERO_UUID;
        }
        return UUID.nameUUIDFromBytes(("rovenfall:exploration:" + playerId + ":" + discoveryId)
                .getBytes(StandardCharsets.UTF_8));
    }

    public static String rewardSource(UUID transactionId) {
        return "quest_reward:exploration/" + transactionId;
    }

    private static boolean markApplied(
            ExplorationPlayerSavedData state,
            UUID playerId,
            Identifier discoveryId,
            ExplorationPlayerState.RewardOperation expectedReward) {
        ExplorationPlayerState current = state.state(playerId);
        ExplorationPlayerState.DiscoveryReceipt receipt = current.discovery(discoveryId).orElse(null);
        if (receipt == null || receipt.rewardOperation().filter(expectedReward::equals).isEmpty()) {
            return false;
        }
        Map<Identifier, ExplorationPlayerState.DiscoveryReceipt> updated =
                new LinkedHashMap<>(current.discoveries());
        updated.put(discoveryId, receipt.withReward(expectedReward.applied()));
        return state.commit(playerId, current, new ExplorationPlayerState(updated));
    }

    private static int pendingRewards(ExplorationPlayerState state) {
        return Math.toIntExact(state.discoveries().values().stream()
                .flatMap(receipt -> receipt.rewardOperation().stream())
                .filter(operation -> operation.phase() == ExplorationPlayerState.RewardOperation.Phase.CAPTURED)
                .count());
    }

    private static boolean inside(BlockPos observed, BlockPos center, int radius) {
        long x = (long) observed.getX() - center.getX();
        long y = (long) observed.getY() - center.getY();
        long z = (long) observed.getZ() - center.getZ();
        long radiusSquared = (long) radius * radius;
        return x * x + y * y + z * z <= radiusSquared;
    }

    private static boolean validTimestamp(long timestamp, long now) {
        if (timestamp <= 0 || now <= 0) {
            return false;
        }
        long latest = now > Long.MAX_VALUE - FUTURE_TIMESTAMP_SKEW_MILLIS
                ? Long.MAX_VALUE : now + FUTURE_TIMESTAMP_SKEW_MILLIS;
        return timestamp <= latest;
    }

    public enum Status {
        SUCCESS, REWARD_PENDING, NO_CHANGE, INVALID, READ_ONLY, STATE_FULL, CONCURRENT_CHANGE
    }

    public enum RecoveryStatus {
        COMPLETE, PARTIAL, PENDING, INVALID
    }

    public record ObservationResult(
            Status status, int discovered, int refreshed, int rewardsApplied, boolean committed) {
        private static ObservationResult invalid() {
            return new ObservationResult(Status.INVALID, 0, 0, 0, false);
        }
    }

    public record RecoveryResult(
            RecoveryStatus status, int attempted, int applied, int pending) {
    }

    public record Target(Identifier discoveryId, ResourceKey<Level> dimension, BlockPos position) {
    }
}
