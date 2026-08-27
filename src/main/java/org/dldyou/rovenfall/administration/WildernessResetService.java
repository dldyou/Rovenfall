package org.dldyou.rovenfall.administration;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.dldyou.rovenfall.Rovenfall;
import org.dldyou.rovenfall.world.WorldTopology;

public final class WildernessResetService {
    static final long WARNING_TTL_MILLIS = Duration.ofMinutes(10).toMillis();
    private static final UUID ZERO_UUID = new UUID(0L, 0L);
    private static final long DENIED_AUDIT_INTERVAL_MILLIS = 1_000L;
    private static final Identifier WARNING = action("wilderness_reset_warning");
    private static final Identifier STAGED = action("wilderness_operation_staged");
    private static final Identifier RESET = action("wilderness_reset");
    private static final Identifier RESTORE = action("wilderness_restore");
    private static final Identifier DENIED = action("wilderness_operation_denied");

    private WildernessResetService() {
    }

    public static Result warn(
            PlatformSavedData state,
            UUID actorId,
            boolean nativeOwnerPermission,
            String reason,
            long timestampEpochMillis,
            UUID warningId) {
        Status validation = validateMutation(state, actorId, nativeOwnerPermission, reason, timestampEpochMillis, warningId);
        if (validation != Status.SUCCESS) {
            return denied(state, actorId, validation, reason, timestampEpochMillis, warningId);
        }
        if (state.isWildernessOperationLocked()) {
            return denied(state, actorId, Status.LOCKED, reason, timestampEpochMillis, warningId);
        }
        if (state.hasAuditTransaction(warningId)) {
            return new Result(Status.DUPLICATE_TRANSACTION, warningId, null, false);
        }
        String normalizedReason = reason.strip();
        long expiresAt;
        try {
            expiresAt = Math.addExact(timestampEpochMillis, WARNING_TTL_MILLIS);
        } catch (ArithmeticException exception) {
            return denied(state, actorId, Status.INVALID_REQUEST, normalizedReason, timestampEpochMillis, warningId);
        }
        var warning = new WildernessResetState.Warning(
                warningId, actorId, timestampEpochMillis, expiresAt, normalizedReason);
        state.commitWildernessWarning(warning, audit(
                warningId, actorId, WARNING, "wilderness", "none", "warning_active",
                normalizedReason, timestampEpochMillis));
        return new Result(Status.SUCCESS, warningId, null, true);
    }

    public static Result reset(
            MinecraftServer server,
            UUID actorId,
            boolean nativeOwnerPermission,
            UUID warningId,
            String reason,
            long timestampEpochMillis,
            UUID transactionId) {
        return stage(server, actorId, nativeOwnerPermission, Optional.ofNullable(warningId), Optional.empty(), reason,
                timestampEpochMillis, transactionId, WildernessResetState.Kind.RESET);
    }

    public static Result restore(
            MinecraftServer server,
            UUID actorId,
            boolean nativeOwnerPermission,
            UUID snapshotId,
            String reason,
            long timestampEpochMillis,
            UUID transactionId) {
        return stage(server, actorId, nativeOwnerPermission, Optional.empty(), Optional.ofNullable(snapshotId), reason,
                timestampEpochMillis, transactionId, WildernessResetState.Kind.RESTORE);
    }

    private static Result stage(
            MinecraftServer server,
            UUID actorId,
            boolean nativeOwnerPermission,
            Optional<UUID> warningId,
            Optional<UUID> restoreSnapshotId,
            String reason,
            long timestampEpochMillis,
            UUID transactionId,
            WildernessResetState.Kind kind) {
        if (server == null) {
            return new Result(Status.INVALID_REQUEST, transactionId, null, false);
        }
        PlatformSavedData state = PlatformSavedData.get(server);
        Status validation = validateMutation(
                state, actorId, nativeOwnerPermission, reason, timestampEpochMillis, transactionId);
        if (validation != Status.SUCCESS) {
            return denied(state, actorId, validation, reason, timestampEpochMillis, transactionId);
        }
        String normalizedReason = reason.strip();
        if (state.isWildernessOperationLocked()) {
            return denied(state, actorId, Status.LOCKED, normalizedReason, timestampEpochMillis, transactionId);
        }
        if (state.hasAuditTransaction(transactionId)) {
            return new Result(Status.DUPLICATE_TRANSACTION, transactionId, null, false);
        }
        if (kind == WildernessResetState.Kind.RESET
                && !validWarning(state, actorId, warningId.orElse(null), timestampEpochMillis)) {
            return denied(state, actorId, Status.WARNING_REQUIRED, normalizedReason, timestampEpochMillis, transactionId);
        }
        ServerLevel wilderness = server.getLevel(WorldTopology.WILDERNESS);
        ServerLevel hub = server.getLevel(WorldTopology.HUB);
        Optional<BlockPos> hubDestination = findSafeHubArrival(hub);
        if (wilderness == null || hub == null || hubDestination.isEmpty()) {
            return denied(state, actorId, Status.TOPOLOGY_UNAVAILABLE, normalizedReason,
                    timestampEpochMillis, transactionId);
        }
        WildernessResetStore store = WildernessResetStore.forServer(server);
        if (store.hasPending()) {
            return denied(state, actorId, Status.LOCKED, normalizedReason, timestampEpochMillis, transactionId);
        }

        UUID recoverySnapshotId = kind == WildernessResetState.Kind.RESET ? transactionId : UUID.randomUUID();
        WildernessResetStore.SnapshotEvidence recoveryEvidence;
        WildernessResetStore.SnapshotEvidence targetEvidence;
        UUID targetSnapshotId;
        boolean recoverySnapshotCreated = false;
        try {
            server.saveEverything(false, true, true);
            recoveryEvidence = store.createSnapshot(recoverySnapshotId, WorldTopology.wildernessPath(server));
            recoverySnapshotCreated = true;
            if (kind == WildernessResetState.Kind.RESET) {
                targetSnapshotId = recoverySnapshotId;
                targetEvidence = recoveryEvidence;
            } else {
                targetSnapshotId = restoreSnapshotId.orElse(null);
                Optional<WildernessResetStore.SnapshotEvidence> recorded = recordedSnapshot(state, targetSnapshotId);
                if (recorded.isEmpty()) {
                    throw new WildernessResetStore.StoreException("snapshot_not_recorded");
                }
                targetEvidence = store.snapshotEvidence(targetSnapshotId);
                if (!targetEvidence.equals(recorded.orElseThrow())) {
                    throw new WildernessResetStore.StoreException("snapshot_evidence_mismatch");
                }
            }
        } catch (WildernessResetStore.StoreException | RuntimeException exception) {
            if (recoverySnapshotCreated) {
                store.discardSnapshot(recoverySnapshotId);
            }
            return denied(state, actorId, kind == WildernessResetState.Kind.RESTORE
                            ? Status.SNAPSHOT_NOT_FOUND : Status.SNAPSHOT_FAILED,
                    normalizedReason, timestampEpochMillis, transactionId);
        }

        var operation = new WildernessResetState.Operation(
                kind, transactionId, targetSnapshotId, recoverySnapshotId, actorId, timestampEpochMillis,
                normalizedReason, targetEvidence.fileCount(), targetEvidence.byteCount(), targetEvidence.sha256(),
                recoveryEvidence.fileCount(), recoveryEvidence.byteCount(), recoveryEvidence.sha256());
        List<Evacuation> evacuated = new ArrayList<>();
        try {
            if (kind == WildernessResetState.Kind.RESET) {
                store.prepareReset(operation);
            } else {
                store.prepareRestore(operation);
            }
            evacuate(server, hub, hubDestination.orElseThrow(), evacuated);
            store.writePending(operation);
        } catch (WildernessResetStore.StoreException | EvacuationException exception) {
            rollbackEvacuation(evacuated);
            store.cleanupStaging(transactionId);
            store.discardSnapshot(recoverySnapshotId);
            return denied(state, actorId, exception instanceof EvacuationException
                            ? Status.EVACUATION_FAILED : Status.PRECOMMIT_FAILED,
                    normalizedReason, timestampEpochMillis, transactionId);
        }

        state.commitWildernessOperation(operation, audit(
                transactionId, actorId, STAGED, "wilderness", "unlocked",
                kind.getSerializedName() + ":locked:snapshot=" + recoverySnapshotId,
                normalizedReason, timestampEpochMillis));
        try {
            server.saveEverything(false, true, true);
        } finally {
            server.halt(false);
        }
        return new Result(Status.SUCCESS, transactionId, recoverySnapshotId, true);
    }

    static void applyPendingBeforeLevels(MinecraftServer server) throws WildernessResetStore.StoreException {
        WildernessResetStore.forServer(server).applyPending(WorldTopology.wildernessPath(server));
    }

    static void finishLifecycle(MinecraftServer server, long timestampEpochMillis) {
        PlatformSavedData state = PlatformSavedData.get(server);
        WildernessResetStore store = WildernessResetStore.forServer(server);
        try {
            Optional<WildernessResetStore.LifecycleResult> retained = store.lifecycleResult();
            if (retained.isPresent()) {
                WildernessResetStore.LifecycleResult result = retained.orElseThrow();
                WildernessResetState.Operation operation = result.operation();
                if (state.wildernessResetState().evidence().stream().anyMatch(entry ->
                        entry.operation().transactionId().equals(operation.transactionId()))) {
                    if (result.succeeded()) {
                        store.cleanupCommittedSwap(operation.transactionId());
                    }
                    store.acknowledgeLifecycleResult(result.succeeded());
                    return;
                }
                WildernessResetState.Result evidenceResult = result.succeeded()
                        ? WildernessResetState.Result.COMPLETED : WildernessResetState.Result.FAILED;
                var evidence = new WildernessResetState.Evidence(
                        operation, evidenceResult, timestampEpochMillis, result.detail());
                Identifier action = result.succeeded()
                        ? operation.kind() == WildernessResetState.Kind.RESET ? RESET : RESTORE
                        : DENIED;
                state.completeWildernessOperation(evidence, audit(
                        operation.transactionId(), operation.actorId(), action, "wilderness",
                        operation.kind().getSerializedName() + ":locked",
                        operation.kind().getSerializedName() + ":" + result.detail()
                                + ":snapshot=" + operation.snapshotId()
                                + ":recovery=" + operation.recoverySnapshotId(),
                        operation.reason(), timestampEpochMillis));
                server.getPlayerList().broadcastSystemMessage(Component.translatable(
                        result.succeeded()
                                ? operation.kind() == WildernessResetState.Kind.RESET
                                        ? "wilderness.rovenfall.reset.completed"
                                        : "wilderness.rovenfall.restore.completed"
                                : "wilderness.rovenfall.operation.failed"), false);
                server.saveEverything(true, true, true);
                if (result.succeeded()) {
                    store.cleanupCommittedSwap(operation.transactionId());
                }
                store.acknowledgeLifecycleResult(result.succeeded());
                return;
            }
        } catch (WildernessResetStore.StoreException ignored) {
        }
        if (state.isWildernessOperationLocked()) {
            WildernessResetState.Operation operation = state.wildernessResetState().activeOperation().orElseThrow();
            state.abortWildernessOperation(audit(
                    operation.transactionId(), operation.actorId(), DENIED, "wilderness",
                    operation.kind().getSerializedName() + ":locked", "unlocked:recovery_evidence_missing",
                    operation.reason(), timestampEpochMillis));
        }
    }

    public static Optional<BlockPos> findSafeHubArrival(ServerLevel hub) {
        if (hub == null || !WorldTopology.isHub(hub.dimension())) {
            return Optional.empty();
        }
        BlockPos spawn = hub.getRespawnData().pos();
        for (int radius = 0; radius <= 8; radius++) {
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    if (radius > 0 && Math.max(Math.abs(x), Math.abs(z)) != radius) {
                        continue;
                    }
                    int surface = hub.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                            spawn.getX() + x, spawn.getZ() + z);
                    BlockPos candidate = new BlockPos(spawn.getX() + x, surface, spawn.getZ() + z);
                    if (safe(hub, candidate)) {
                        return Optional.of(candidate);
                    }
                }
            }
        }
        return Optional.empty();
    }

    private static boolean safe(ServerLevel level, BlockPos position) {
        BlockPos floor = position.below();
        BlockPos head = position.above();
        if (!level.isInWorldBounds(floor) || !level.isInWorldBounds(head)
                || !level.getWorldBorder().isWithinBounds(position)) {
            return false;
        }
        BlockState floorState = level.getBlockState(floor);
        BlockState feetState = level.getBlockState(position);
        BlockState headState = level.getBlockState(head);
        return floorState.isFaceSturdy(level, floor, net.minecraft.core.Direction.UP)
                && feetState.getCollisionShape(level, position).isEmpty()
                && headState.getCollisionShape(level, head).isEmpty()
                && feetState.getFluidState().isEmpty()
                && headState.getFluidState().isEmpty()
                && !hazard(floorState) && !hazard(feetState) && !hazard(headState);
    }

    private static boolean hazard(BlockState state) {
        return state.is(BlockTags.FIRE) || state.getFluidState().is(FluidTags.LAVA)
                || state.is(Blocks.CACTUS) || state.is(Blocks.MAGMA_BLOCK)
                || state.is(Blocks.CAMPFIRE) || state.is(Blocks.SOUL_CAMPFIRE)
                || state.is(Blocks.SWEET_BERRY_BUSH) || state.is(Blocks.POWDER_SNOW)
                || state.is(Blocks.WITHER_ROSE);
    }

    private static void evacuate(
            MinecraftServer server, ServerLevel hub, BlockPos destination, List<Evacuation> evacuated)
            throws EvacuationException {
        for (ServerPlayer player : List.copyOf(server.getPlayerList().getPlayers())) {
            if (!WorldTopology.isWilderness(player.level().dimension())) {
                continue;
            }
            Evacuation prior = new Evacuation(
                    player, (ServerLevel) player.level(), player.getX(), player.getY(), player.getZ(),
                    player.getYRot(), player.getXRot());
            boolean moved = player.teleportTo(
                    hub, destination.getX() + 0.5D, destination.getY(), destination.getZ() + 0.5D,
                    Set.<Relative>of(), player.getYRot(), player.getXRot(), false);
            if (!moved) {
                throw new EvacuationException();
            }
            evacuated.add(prior);
            if (player.connection != null) {
                player.sendSystemMessage(Component.translatable("wilderness.rovenfall.reset.evacuated"));
            }
        }
    }

    private static void rollbackEvacuation(List<Evacuation> evacuated) {
        for (int index = evacuated.size() - 1; index >= 0; index--) {
            Evacuation prior = evacuated.get(index);
            prior.player.teleportTo(prior.level, prior.x, prior.y, prior.z, Set.<Relative>of(),
                    prior.yRot, prior.xRot, false);
        }
    }

    private static Optional<WildernessResetStore.SnapshotEvidence> recordedSnapshot(
            PlatformSavedData state, UUID snapshotId) {
        if (snapshotId == null) {
            return Optional.empty();
        }
        for (int index = state.wildernessResetState().evidence().size() - 1; index >= 0; index--) {
            WildernessResetState.Operation operation = state.wildernessResetState().evidence().get(index).operation();
            if (snapshotId.equals(operation.snapshotId())) {
                return Optional.of(new WildernessResetStore.SnapshotEvidence(
                        operation.fileCount(), operation.byteCount(), operation.sha256()));
            }
            if (snapshotId.equals(operation.recoverySnapshotId())) {
                return Optional.of(new WildernessResetStore.SnapshotEvidence(
                        operation.recoveryFileCount(), operation.recoveryByteCount(), operation.recoverySha256()));
            }
        }
        return Optional.empty();
    }

    private static boolean validWarning(
            PlatformSavedData state, UUID actorId, UUID warningId, long timestampEpochMillis) {
        WildernessResetState.Warning warning = state.wildernessResetState().warning().orElse(null);
        return warning != null && warning.warningId().equals(warningId) && warning.actorId().equals(actorId)
                && timestampEpochMillis >= warning.issuedAtEpochMillis()
                && timestampEpochMillis <= warning.expiresAtEpochMillis();
    }

    private static Status validateMutation(
            PlatformSavedData state,
            UUID actorId,
            boolean nativeOwnerPermission,
            String reason,
            long timestampEpochMillis,
            UUID transactionId) {
        if (state == null || actorId == null || timestampEpochMillis < 0) {
            return Status.INVALID_REQUEST;
        }
        if (!state.isWritable()) {
            return Status.READ_ONLY_SCHEMA;
        }
        if (transactionId == null || ZERO_UUID.equals(transactionId)) {
            return Status.INVALID_TRANSACTION;
        }
        String normalizedReason = reason == null ? "" : reason.strip();
        if (normalizedReason.isEmpty() || normalizedReason.length() > AdministrationService.MAX_REASON_LENGTH) {
            return Status.INVALID_REASON;
        }
        return nativeOwnerPermission || state.roleOf(actorId).orElse(null) == AdminRole.OWNER
                ? Status.SUCCESS : Status.UNAUTHORIZED;
    }

    private static Result denied(
            PlatformSavedData state,
            UUID actorId,
            Status status,
            String reason,
            long timestampEpochMillis,
            UUID transactionId) {
        if (state == null || actorId == null || timestampEpochMillis < 0) {
            return new Result(status, transactionId, null, false);
        }
        UUID evidenceId = transactionId == null || ZERO_UUID.equals(transactionId) ? UUID.randomUUID() : transactionId;
        String detail = status.name().toLowerCase(java.util.Locale.ROOT);
        String safeReason = reason == null || reason.isBlank() ? detail : reason.strip();
        if (safeReason.length() > AdministrationService.MAX_REASON_LENGTH) {
            safeReason = detail;
        }
        boolean recorded = state.appendDeniedAudit(audit(
                evidenceId, actorId, DENIED, "wilderness", "unchanged", "unchanged:" + detail,
                safeReason, timestampEpochMillis), DENIED_AUDIT_INTERVAL_MILLIS);
        return new Result(status, transactionId, null, recorded);
    }

    private static AuditEntry audit(
            UUID transactionId,
            UUID actorId,
            Identifier action,
            String target,
            String before,
            String after,
            String reason,
            long timestampEpochMillis) {
        return new AuditEntry(timestampEpochMillis, actorId, action, target,
                Optional.of(WorldTopology.WILDERNESS.identifier()), Optional.empty(),
                before, after, reason, transactionId);
    }

    private static Identifier action(String path) {
        return Identifier.fromNamespaceAndPath(Rovenfall.MOD_ID, path);
    }

    public enum Status {
        SUCCESS,
        INVALID_REQUEST,
        INVALID_TRANSACTION,
        DUPLICATE_TRANSACTION,
        INVALID_REASON,
        READ_ONLY_SCHEMA,
        UNAUTHORIZED,
        WARNING_REQUIRED,
        LOCKED,
        TOPOLOGY_UNAVAILABLE,
        SNAPSHOT_NOT_FOUND,
        SNAPSHOT_FAILED,
        EVACUATION_FAILED,
        PRECOMMIT_FAILED
    }

    public record Result(Status status, UUID transactionId, UUID snapshotId, boolean auditRecorded) {
    }

    private record Evacuation(
            ServerPlayer player, ServerLevel level, double x, double y, double z, float yRot, float xRot) {
    }

    private static final class EvacuationException extends Exception {
    }
}
