package org.dldyou.rovenfall.administration;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import org.dldyou.rovenfall.Rovenfall;
import org.dldyou.rovenfall.worlds.SafeArrivalResolver;
import org.dldyou.rovenfall.worlds.WorldConfig;
import org.slf4j.Logger;

/** Owns the restart-assisted, recoverable Wilderness reset workflow. */
public final class RestartWildernessResetService {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int OPERATION_SCHEMA_VERSION = 1;
    private static final int MAX_EVACUATED_PLAYERS = 10_000;
    private static final long MAX_OPERATION_BYTES = 64L * 1024L;
    private static final long DENIED_AUDIT_INTERVAL_MILLIS = 1_000L;
    private static final UUID ZERO_UUID = new UUID(0L, 0L);
    private static final Identifier RESET = action("wilderness_reset");
    private static final Identifier RESET_DENIED = action("wilderness_reset_denied");
    private static final Identifier RESET_FAILED = action("wilderness_reset_failed");

    private RestartWildernessResetService() {
    }

    public static void register(IEventBus eventBus) {
        eventBus.addListener(RestartWildernessResetService::onServerAboutToStart);
        eventBus.addListener(RestartWildernessResetService::onServerStarted);
    }

    public static ScheduleResult schedule(
            MinecraftServer server,
            UUID actorId,
            boolean authorizationOverride,
            Component actorName,
            String reason,
            long timestampEpochMillis,
            UUID operationId,
            UUID snapshotId) {
        if (server == null) {
            return result(Status.INVALID_REQUEST, operationId, snapshotId, 0, false);
        }
        PlatformSavedData state = PlatformSavedData.get(server);
        Path worldRoot = worldRoot(server);
        Status rejection = precheck(
                state, worldRoot, actorId, authorizationOverride, reason,
                timestampEpochMillis, operationId, snapshotId);
        if (rejection != null) {
            return rejected(state, actorId, operationId, snapshotId, reason,
                    timestampEpochMillis, rejection, RESET_DENIED);
        }

        ServerLevel hub = server.overworld();
        ServerLevel wilderness = server.getLevel(WorldCombatService.WILDERNESS_DIMENSION);
        if (hub == null || wilderness == null) {
            return rejected(state, actorId, operationId, snapshotId, reason,
                    timestampEpochMillis, Status.DIMENSION_UNAVAILABLE, RESET_FAILED);
        }
        var arrival = SafeArrivalResolver.resolve(
                hub,
                hub.getRespawnData().pos(),
                WorldConfig.portalSearchRadius(),
                ignored -> true);
        if (arrival.status() != SafeArrivalResolver.Status.FOUND) {
            return rejected(state, actorId, operationId, snapshotId, reason,
                    timestampEpochMillis, Status.NO_SAFE_HUB_ARRIVAL, RESET_FAILED);
        }

        Component displayName = actorName == null ? Component.literal(actorId.toString()) : actorName;
        String normalizedReason = AdministrationService.validReason(reason).orElseThrow();
        server.getPlayerList().broadcastSystemMessage(Component.translatable(
                "message.rovenfall.wilderness.reset.warning", displayName, normalizedReason), false);

        List<ServerPlayer> wildernessPlayers = server.getPlayerList().getPlayers().stream()
                .filter(player -> player.level().dimension().equals(WorldCombatService.WILDERNESS_DIMENSION))
                .toList();
        wildernessPlayers.forEach(player -> {
            player.stopRiding();
            player.ejectPassengers();
        });
        BlockPos safeHubPosition = arrival.position().orElseThrow();
        int evacuated = evacuate(wildernessPlayers, hub, safeHubPosition);
        if (evacuated != wildernessPlayers.size()
                || wildernessPlayers.stream().anyMatch(player ->
                        player.level().dimension().equals(WorldCombatService.WILDERNESS_DIMENSION))) {
            return rejected(state, actorId, operationId, snapshotId, normalizedReason,
                    timestampEpochMillis, Status.EVACUATION_FAILED, RESET_FAILED);
        }
        if (!server.saveEverything(false, true, true)) {
            return rejected(state, actorId, operationId, snapshotId, normalizedReason,
                    timestampEpochMillis, Status.SAVE_FAILED, RESET_FAILED);
        }

        try {
            PlatformSnapshotStore.forServer(server).write(snapshotId, state);
        } catch (PlatformSnapshotStore.SnapshotException exception) {
            LOGGER.error("Wilderness reset {} could not write platform snapshot {}", operationId, snapshotId, exception);
            return rejected(state, actorId, operationId, snapshotId, normalizedReason,
                    timestampEpochMillis, Status.SNAPSHOT_FAILED, RESET_FAILED);
        }

        Operation operation = new Operation(
                OPERATION_SCHEMA_VERSION,
                operationId,
                snapshotId,
                actorId,
                normalizedReason,
                timestampEpochMillis,
                -1L,
                evacuated,
                Phase.READY,
                "");
        try {
            writePending(worldRoot, operation, false);
        } catch (StorageException exception) {
            LOGGER.error("Wilderness reset {} could not persist its operation record", operationId, exception);
            return rejected(state, actorId, operationId, snapshotId, normalizedReason,
                    timestampEpochMillis, Status.STORAGE_ERROR, RESET_FAILED);
        }

        server.getPlayerList().broadcastSystemMessage(Component.translatable(
                "message.rovenfall.wilderness.reset.restart",
                evacuated,
                operationId.toString(),
                snapshotId.toString()), false);
        server.execute(() -> server.halt(false));
        return result(Status.SUCCESS, operationId, snapshotId, evacuated, false);
    }

    static Status precheck(
            PlatformSavedData state,
            Path worldRoot,
            UUID actorId,
            boolean authorizationOverride,
            String reason,
            long timestampEpochMillis,
            UUID operationId,
            UUID snapshotId) {
        if (state == null || worldRoot == null || actorId == null || timestampEpochMillis < 0) {
            return Status.INVALID_REQUEST;
        }
        if (!validId(operationId) || !validId(snapshotId) || operationId.equals(snapshotId)) {
            return Status.INVALID_TRANSACTION;
        }
        if (!state.isWritable()) {
            return Status.READ_ONLY_SCHEMA;
        }
        if (!AdministrationService.isOwner(state, actorId, authorizationOverride)) {
            return Status.UNAUTHORIZED;
        }
        if (AdministrationService.validReason(reason).isEmpty()) {
            return Status.INVALID_REASON;
        }
        if (state.hasTransaction(operationId, timestampEpochMillis)) {
            return Status.DUPLICATE_TRANSACTION;
        }
        if (!state.canCommitTransaction(operationId, timestampEpochMillis)) {
            return Status.TRANSACTION_LEDGER_FULL;
        }
        try {
            if (readPending(worldRoot).isPresent()) {
                return Status.RESET_PENDING;
            }
        } catch (StorageException exception) {
            return Status.STORAGE_ERROR;
        }
        return null;
    }

    public static boolean isResetPending(MinecraftServer server) {
        if (server == null) {
            return false;
        }
        try {
            return readPending(worldRoot(server))
                    .filter(operation -> operation.phase() != Phase.FAILED)
                    .isPresent();
        } catch (StorageException exception) {
            return true;
        }
    }

    static Optional<Operation> pendingOperation(MinecraftServer server) throws StorageException {
        return readPending(worldRoot(server));
    }

    static Optional<Operation> operation(MinecraftServer server, UUID operationId) throws StorageException {
        return operation(worldRoot(server), operationId);
    }

    static Optional<Operation> operation(Path worldRoot, UUID operationId) throws StorageException {
        Optional<Operation> pending = readPending(worldRoot);
        if (pending.filter(value -> value.operationId().equals(operationId)).isPresent()) {
            return pending;
        }
        return readOperation(receiptPath(worldRoot, operationId));
    }

    static String backupRelativePath(UUID snapshotId) {
        return "rovenfall/snapshots/wilderness/" + snapshotId + "/dimension";
    }

    static ApplyResult applyPendingReset(Path worldRoot) {
        Operation operation;
        try {
            operation = readPending(worldRoot).orElse(null);
        } catch (StorageException exception) {
            return new ApplyResult(ApplyStatus.STORAGE_ERROR, Optional.empty());
        }
        if (operation == null || operation.phase() != Phase.READY) {
            return new ApplyResult(ApplyStatus.NOTHING_TO_DO, Optional.ofNullable(operation));
        }

        Path source = wildernessPath(worldRoot);
        Path backup = backupPath(worldRoot, operation.snapshotId());
        try {
            boolean sourceExists = Files.exists(source, LinkOption.NOFOLLOW_LINKS);
            boolean backupExists = Files.exists(backup, LinkOption.NOFOLLOW_LINKS);
            if (!sourceExists && backupExists && Files.isDirectory(backup, LinkOption.NOFOLLOW_LINKS)) {
                Operation moved = operation.withPhase(Phase.MOVED, "", -1L);
                writePending(worldRoot, moved, true);
                return new ApplyResult(ApplyStatus.RECOVERED_AFTER_MOVE, Optional.of(moved));
            }
            if (!sourceExists || !Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(source)) {
                return failPending(worldRoot, operation, "wilderness_source_missing");
            }
            if (backupExists) {
                return failPending(worldRoot, operation, "wilderness_backup_conflict");
            }

            Files.createDirectories(backup.getParent());
            Files.move(source, backup, StandardCopyOption.ATOMIC_MOVE);
            Operation moved = operation.withPhase(Phase.MOVED, "", -1L);
            writePending(worldRoot, moved, true);
            return new ApplyResult(ApplyStatus.APPLIED, Optional.of(moved));
        } catch (IOException | StorageException exception) {
            LOGGER.error("Wilderness reset {} failed while moving the dimension", operation.operationId(), exception);
            if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)
                    && Files.isDirectory(backup, LinkOption.NOFOLLOW_LINKS)) {
                return new ApplyResult(ApplyStatus.STORAGE_ERROR, Optional.of(operation));
            }
            return failPending(worldRoot, operation, "dimension_move_failed");
        }
    }

    static Optional<Operation> readPending(Path worldRoot) throws StorageException {
        return readOperation(pendingPath(worldRoot));
    }

    static void writePending(Path worldRoot, Operation operation, boolean replace) throws StorageException {
        writeOperation(pendingPath(worldRoot), operation, replace);
    }

    static void archivePending(Path worldRoot, Operation operation) throws StorageException {
        Operation retained = readPending(worldRoot).orElseThrow(() -> new StorageException("Pending operation is missing"));
        if (!retained.operationId().equals(operation.operationId())) {
            throw new StorageException("Pending operation changed");
        }
        writePending(worldRoot, operation, true);
        Path receipt = receiptPath(worldRoot, operation.operationId());
        try {
            Files.createDirectories(receipt.getParent());
            if (Files.exists(receipt, LinkOption.NOFOLLOW_LINKS)) {
                throw new StorageException("Operation receipt already exists");
            }
            Files.move(pendingPath(worldRoot), receipt, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException exception) {
            throw new StorageException(exception);
        }
    }

    static CompletionStatus commitSuccess(
            PlatformSavedData state, Operation operation, long timestampEpochMillis) {
        if (state == null || operation == null || timestampEpochMillis < 0
                || (operation.phase() != Phase.MOVED && operation.phase() != Phase.COMPLETED)) {
            return CompletionStatus.INVALID_REQUEST;
        }
        if (!state.isWritable()) {
            return CompletionStatus.READ_ONLY_SCHEMA;
        }
        if (state.hasTransaction(operation.operationId(), timestampEpochMillis)) {
            return CompletionStatus.DUPLICATE_TRANSACTION;
        }
        if (!state.canCommitTransaction(operation.operationId(), timestampEpochMillis)) {
            return CompletionStatus.TRANSACTION_LEDGER_FULL;
        }
        String before = "snapshot=" + operation.snapshotId()
                + ";requested_at=" + operation.requestedAtEpochMillis()
                + ";evacuated=" + operation.evacuatedPlayers();
        String after = "backup=" + backupRelativePath(operation.snapshotId())
                + ";fresh=" + WorldCombatService.WILDERNESS_DIMENSION.identifier();
        state.commitAdministrativeTransaction(
                operation.operationId(),
                timestampEpochMillis,
                new AuditEntry(
                        timestampEpochMillis,
                        operation.actorId(),
                        RESET,
                        WorldCombatService.WILDERNESS_DIMENSION.identifier().toString(),
                        Optional.of(WorldCombatService.WILDERNESS_DIMENSION.identifier()),
                        Optional.empty(),
                        before,
                        after,
                        operation.reason(),
                        operation.operationId()));
        return CompletionStatus.SUCCESS;
    }

    private static int evacuate(List<ServerPlayer> players, ServerLevel hub, BlockPos target) {
        int evacuated = 0;
        for (ServerPlayer player : players) {
            var transition = new TeleportTransition(
                    hub,
                    Vec3.atBottomCenterOf(target),
                    Vec3.ZERO,
                    player.getYRot(),
                    player.getXRot(),
                    Set.of(),
                    TeleportTransition.PLAY_PORTAL_SOUND);
            if (player.teleport(transition) != null) {
                evacuated++;
            }
        }
        return evacuated;
    }

    private static void onServerAboutToStart(ServerAboutToStartEvent event) {
        ApplyResult result = applyPendingReset(worldRoot(event.getServer()));
        if (result.status() == ApplyStatus.APPLIED || result.status() == ApplyStatus.RECOVERED_AFTER_MOVE) {
            LOGGER.info("Wilderness reset {} moved the previous dimension to {}",
                    result.operation().orElseThrow().operationId(),
                    backupRelativePath(result.operation().orElseThrow().snapshotId()));
        } else if (result.status() == ApplyStatus.FAILED || result.status() == ApplyStatus.STORAGE_ERROR) {
            LOGGER.error("A pending Wilderness reset could not be applied ({})", result.status());
        }
    }

    private static void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        Path root = worldRoot(server);
        Operation operation;
        try {
            operation = readPending(root).orElse(null);
        } catch (StorageException exception) {
            LOGGER.error("Could not read the pending Wilderness reset after server start", exception);
            return;
        }
        if (operation == null) {
            return;
        }
        long now = Instant.now().toEpochMilli();
        if (operation.phase() == Phase.READY) {
            if (freshDimensionVerified(server, root, operation)) {
                operation = operation.withPhase(Phase.MOVED, "", -1L);
                try {
                    writePending(root, operation, true);
                } catch (StorageException exception) {
                    LOGGER.error("Wilderness reset {} could not recover its moved phase", operation.operationId(), exception);
                    return;
                }
            } else {
                operation = operation.withPhase(Phase.FAILED, "preload_step_not_applied", now);
                persistPendingFailure(root, operation);
            }
        } else if (operation.phase() == Phase.MOVED && !freshDimensionVerified(server, root, operation)) {
            operation = operation.withPhase(Phase.FAILED, "fresh_dimension_verification_failed", now);
            persistPendingFailure(root, operation);
        }

        if (operation.phase() == Phase.FAILED) {
            finishFailure(server, root, operation, now);
            return;
        }
        if (operation.phase() != Phase.MOVED && operation.phase() != Phase.COMPLETED) {
            return;
        }
        finishSuccess(server, root, operation, now);
    }

    private static void finishSuccess(MinecraftServer server, Path root, Operation operation, long timestamp) {
        PlatformSavedData state = PlatformSavedData.get(server);
        CompletionStatus completion = commitSuccess(state, operation, timestamp);
        if (completion != CompletionStatus.SUCCESS && completion != CompletionStatus.DUPLICATE_TRANSACTION) {
            LOGGER.error("Wilderness reset {} could not commit its completion evidence ({})",
                    operation.operationId(), completion);
            return;
        }
        state.clearActivityResourcePlacements(WorldCombatService.WILDERNESS_DIMENSION);
        if (!server.saveEverything(true, true, true)) {
            LOGGER.error("Wilderness reset {} completed but its audit or activity provenance could not be flushed",
                    operation.operationId());
            return;
        }

        Operation completed = operation.withPhase(Phase.COMPLETED, "", timestamp);
        try {
            archivePending(root, completed);
        } catch (StorageException exception) {
            LOGGER.error("Wilderness reset {} completed but its receipt could not be archived", operation.operationId(), exception);
            return;
        }
        server.getPlayerList().broadcastSystemMessage(Component.translatable(
                "message.rovenfall.wilderness.reset.complete",
                operation.operationId().toString(),
                operation.snapshotId().toString(),
                backupRelativePath(operation.snapshotId())), false);
    }

    private static void finishFailure(MinecraftServer server, Path root, Operation operation, long timestamp) {
        PlatformSavedData state = PlatformSavedData.get(server);
        if (!state.hasTransaction(operation.operationId(), timestamp)) {
            if (!state.canCommitTransaction(operation.operationId(), timestamp)) {
                LOGGER.error("Failed Wilderness reset {} could not reserve audit transaction capacity", operation.operationId());
                return;
            }
            String evidence = "snapshot=" + operation.snapshotId()
                    + ";failure=" + operation.failureCode()
                    + ";backup=" + backupRelativePath(operation.snapshotId());
            state.commitAdministrativeTransaction(
                    operation.operationId(),
                    timestamp,
                    new AuditEntry(
                            timestamp,
                            operation.actorId(),
                            RESET_FAILED,
                            WorldCombatService.WILDERNESS_DIMENSION.identifier().toString(),
                            Optional.of(WorldCombatService.WILDERNESS_DIMENSION.identifier()),
                            Optional.empty(),
                            evidence,
                            evidence,
                            operation.reason(),
                            operation.operationId()));
            if (!server.saveEverything(true, true, true)) {
                return;
            }
        }
        try {
            archivePending(root, operation);
        } catch (StorageException exception) {
            LOGGER.error("Failed Wilderness reset {} could not archive its receipt", operation.operationId(), exception);
            return;
        }
        server.getPlayerList().broadcastSystemMessage(Component.translatable(
                "message.rovenfall.wilderness.reset.failed",
                operation.operationId().toString(), operation.failureCode()), false);
    }

    private static boolean freshDimensionVerified(MinecraftServer server, Path root, Operation operation) {
        return server.getLevel(WorldCombatService.WILDERNESS_DIMENSION) != null
                && Files.isDirectory(wildernessPath(root), LinkOption.NOFOLLOW_LINKS)
                && Files.isDirectory(backupPath(root, operation.snapshotId()), LinkOption.NOFOLLOW_LINKS);
    }

    private static void persistPendingFailure(Path root, Operation failed) {
        try {
            writePending(root, failed, true);
        } catch (StorageException exception) {
            LOGGER.error("Wilderness reset {} could not persist its failure state", failed.operationId(), exception);
        }
    }

    private static ApplyResult failPending(Path root, Operation operation, String failureCode) {
        Operation failed = operation.withPhase(
                Phase.FAILED, failureCode, Instant.now().toEpochMilli());
        persistPendingFailure(root, failed);
        return new ApplyResult(ApplyStatus.FAILED, Optional.of(failed));
    }

    static ScheduleResult rejected(
            PlatformSavedData state,
            UUID actorId,
            UUID operationId,
            UUID snapshotId,
            String requestedReason,
            long timestamp,
            Status status,
            Identifier action) {
        if (state == null || actorId == null || timestamp < 0) {
            return result(status, operationId, snapshotId, 0, false);
        }
        UUID auditId = validId(operationId) ? operationId : UUID.randomUUID();
        String evidence = "status=" + status.id
                + ";snapshot=" + (snapshotId == null ? "none" : snapshotId)
                + ";requested_reason=" + safeReason(requestedReason);
        boolean audited = state.appendDeniedAudit(new AuditEntry(
                timestamp,
                actorId,
                action,
                WorldCombatService.WILDERNESS_DIMENSION.identifier().toString(),
                Optional.of(WorldCombatService.WILDERNESS_DIMENSION.identifier()),
                Optional.empty(),
                evidence,
                evidence,
                status.id,
                auditId), DENIED_AUDIT_INTERVAL_MILLIS);
        return result(status, operationId, snapshotId, 0, audited);
    }

    private static String safeReason(String reason) {
        if (reason == null) {
            return "none";
        }
        String normalized = reason.strip();
        return normalized.length() <= AdministrationService.MAX_REASON_LENGTH ? normalized : "invalid";
    }

    private static ScheduleResult result(
            Status status, UUID operationId, UUID snapshotId, int evacuatedPlayers, boolean audited) {
        return new ScheduleResult(status, operationId, snapshotId, evacuatedPlayers, audited);
    }

    private static Path worldRoot(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
    }

    private static Path wildernessPath(Path worldRoot) {
        return checked(worldRoot, DimensionType.getStorageFolder(
                WorldCombatService.WILDERNESS_DIMENSION, normalizedRoot(worldRoot)));
    }

    private static Path backupPath(Path worldRoot, UUID snapshotId) {
        return checked(worldRoot, normalizedRoot(worldRoot)
                .resolve("rovenfall")
                .resolve("snapshots")
                .resolve("wilderness")
                .resolve(snapshotId.toString())
                .resolve("dimension"));
    }

    private static Path pendingPath(Path worldRoot) {
        return checked(worldRoot, normalizedRoot(worldRoot)
                .resolve("rovenfall")
                .resolve("wilderness-resets")
                .resolve("pending.nbt"));
    }

    private static Path receiptPath(Path worldRoot, UUID operationId) {
        return checked(worldRoot, normalizedRoot(worldRoot)
                .resolve("rovenfall")
                .resolve("wilderness-resets")
                .resolve("receipts")
                .resolve(operationId + ".nbt"));
    }

    private static Path normalizedRoot(Path worldRoot) {
        if (worldRoot == null) {
            throw new IllegalArgumentException("World root is required");
        }
        return worldRoot.toAbsolutePath().normalize();
    }

    private static Path checked(Path worldRoot, Path candidate) {
        Path root = normalizedRoot(worldRoot);
        Path normalized = candidate.toAbsolutePath().normalize();
        if (!normalized.startsWith(root) || normalized.equals(root)) {
            throw new IllegalArgumentException("Wilderness reset path escapes the world root");
        }
        return normalized;
    }

    private static Optional<Operation> readOperation(Path source) throws StorageException {
        try {
            if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
                return Optional.empty();
            }
            if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(source)
                    || Files.size(source) > MAX_OPERATION_BYTES) {
                throw new StorageException("Operation record is invalid");
            }
            CompoundTag tag = NbtIo.readCompressed(source, NbtAccounter.create(MAX_OPERATION_BYTES));
            return Optional.of(Operation.CODEC.parse(NbtOps.INSTANCE, tag).getOrThrow());
        } catch (IOException | RuntimeException exception) {
            throw new StorageException(exception);
        }
    }

    private static void writeOperation(Path target, Operation operation, boolean replace) throws StorageException {
        Path temporary = null;
        try {
            Files.createDirectories(target.getParent());
            if (!replace && Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new StorageException("Operation record already exists");
            }
            var encoded = Operation.CODEC.encodeStart(NbtOps.INSTANCE, operation).getOrThrow();
            if (!(encoded instanceof CompoundTag tag)) {
                throw new StorageException("Operation root is not a compound tag");
            }
            temporary = Files.createTempFile(target.getParent(), operation.operationId() + ".", ".tmp");
            NbtIo.writeCompressed(tag, temporary);
            if (Files.size(temporary) > MAX_OPERATION_BYTES) {
                throw new StorageException("Operation record is too large");
            }
            Operation verified = Operation.CODEC.parse(
                    NbtOps.INSTANCE,
                    NbtIo.readCompressed(temporary, NbtAccounter.create(MAX_OPERATION_BYTES))).getOrThrow();
            if (!verified.equals(operation)) {
                throw new StorageException("Operation verification failed");
            }
            if (replace) {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            }
            temporary = null;
        } catch (IOException | RuntimeException exception) {
            throw new StorageException(exception);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static boolean validId(UUID id) {
        return id != null && !ZERO_UUID.equals(id);
    }

    private static Identifier action(String path) {
        return Identifier.fromNamespaceAndPath(Rovenfall.MOD_ID, path);
    }

    public enum Status {
        SUCCESS("success"),
        UNAUTHORIZED("unauthorized"),
        INVALID_REQUEST("invalid_request"),
        INVALID_REASON("invalid_reason"),
        INVALID_TRANSACTION("invalid_transaction"),
        DUPLICATE_TRANSACTION("duplicate_transaction"),
        READ_ONLY_SCHEMA("read_only_schema"),
        TRANSACTION_LEDGER_FULL("transaction_ledger_full"),
        RESET_PENDING("reset_pending"),
        DIMENSION_UNAVAILABLE("dimension_unavailable"),
        NO_SAFE_HUB_ARRIVAL("no_safe_hub_arrival"),
        EVACUATION_FAILED("evacuation_failed"),
        SAVE_FAILED("save_failed"),
        SNAPSHOT_FAILED("snapshot_failed"),
        STORAGE_ERROR("storage_error");

        private final String id;

        Status(String id) {
            this.id = id;
        }
    }

    public record ScheduleResult(
            Status status,
            UUID operationId,
            UUID snapshotId,
            int evacuatedPlayers,
            boolean auditRecorded) {
    }

    enum ApplyStatus {
        NOTHING_TO_DO,
        APPLIED,
        RECOVERED_AFTER_MOVE,
        FAILED,
        STORAGE_ERROR
    }

    enum CompletionStatus {
        SUCCESS,
        DUPLICATE_TRANSACTION,
        INVALID_REQUEST,
        READ_ONLY_SCHEMA,
        TRANSACTION_LEDGER_FULL
    }

    record ApplyResult(ApplyStatus status, Optional<Operation> operation) {
        ApplyResult {
            operation = operation == null ? Optional.empty() : operation;
        }
    }

    enum Phase implements StringRepresentable {
        READY("ready"),
        MOVED("moved"),
        COMPLETED("completed"),
        FAILED("failed");

        static final Codec<Phase> CODEC = StringRepresentable.fromEnum(Phase::values);
        private final String id;

        Phase(String id) {
            this.id = id;
        }

        String translationKey() {
            return "wilderness_reset_phase.rovenfall." + id;
        }

        @Override
        public String getSerializedName() {
            return id;
        }
    }

    record Operation(
            int schemaVersion,
            UUID operationId,
            UUID snapshotId,
            UUID actorId,
            String reason,
            long requestedAtEpochMillis,
            long completedAtEpochMillis,
            int evacuatedPlayers,
            Phase phase,
            String failureCode) {
        static final Codec<Operation> CODEC = RecordCodecBuilder.<Operation>create(instance -> instance.group(
                Codec.INT.fieldOf("schema_version").forGetter(Operation::schemaVersion),
                UUIDUtil.STRING_CODEC.fieldOf("operation_id").forGetter(Operation::operationId),
                UUIDUtil.STRING_CODEC.fieldOf("snapshot_id").forGetter(Operation::snapshotId),
                UUIDUtil.STRING_CODEC.fieldOf("actor_id").forGetter(Operation::actorId),
                Codec.STRING.fieldOf("reason").forGetter(Operation::reason),
                Codec.LONG.fieldOf("requested_at").forGetter(Operation::requestedAtEpochMillis),
                Codec.LONG.optionalFieldOf("completed_at", -1L).forGetter(Operation::completedAtEpochMillis),
                Codec.INT.fieldOf("evacuated_players").forGetter(Operation::evacuatedPlayers),
                Phase.CODEC.fieldOf("phase").forGetter(Operation::phase),
                Codec.STRING.optionalFieldOf("failure_code", "").forGetter(Operation::failureCode)
        ).apply(instance, Operation::new)).validate(Operation::validate);

        Operation {
            reason = reason == null ? "" : reason.strip();
            failureCode = failureCode == null ? "" : failureCode;
        }

        Operation withPhase(Phase updatedPhase, String updatedFailureCode, long completedAt) {
            return new Operation(
                    schemaVersion,
                    operationId,
                    snapshotId,
                    actorId,
                    reason,
                    requestedAtEpochMillis,
                    completedAt,
                    evacuatedPlayers,
                    updatedPhase,
                    updatedFailureCode);
        }

        private static DataResult<Operation> validate(Operation operation) {
            if (operation.schemaVersion != OPERATION_SCHEMA_VERSION
                    || !validId(operation.operationId)
                    || !validId(operation.snapshotId)
                    || operation.operationId.equals(operation.snapshotId)
                    || operation.actorId == null
                    || AdministrationService.validReason(operation.reason).isEmpty()
                    || operation.requestedAtEpochMillis < 0
                    || operation.evacuatedPlayers < 0
                    || operation.evacuatedPlayers > MAX_EVACUATED_PLAYERS
                    || operation.phase == null
                    || operation.failureCode.length() > 128) {
                return DataResult.error(() -> "Invalid Wilderness reset operation");
            }
            boolean terminal = operation.phase == Phase.COMPLETED || operation.phase == Phase.FAILED;
            if (terminal != (operation.completedAtEpochMillis >= operation.requestedAtEpochMillis)
                    || (operation.phase == Phase.FAILED) != !operation.failureCode.isEmpty()) {
                return DataResult.error(() -> "Invalid Wilderness reset phase evidence");
            }
            return DataResult.success(operation);
        }
    }

    static final class StorageException extends Exception {
        StorageException(String message) {
            super(message);
        }

        StorageException(Throwable cause) {
            super(cause);
        }
    }
}
