package org.dldyou.rovenfall.administration;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.dldyou.rovenfall.Rovenfall;

/** Executes server-prepared audit export and platform recovery confirmations. */
final class AdministrationOperationsActionService {
    private AdministrationOperationsActionService() {
    }

    static Optional<PendingAction> prepareExport(
            MinecraftServer server, UUID transactionId, AuditQuery query, String reason) {
        if (server == null || transactionId == null || query == null) {
            return Optional.empty();
        }
        PlatformSavedData state = PlatformSavedData.get(server);
        return Optional.of(new ExportAction(
                transactionId, query, state.selectAudit(query, AuditExportService.MAX_EXPORT_ROWS), reason));
    }

    static Optional<PendingAction> prepareSnapshotCreate(
            MinecraftServer server, UUID transactionId, UUID snapshotId, String reason) {
        if (server == null || transactionId == null || snapshotId == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(new SnapshotCreateAction(
                    transactionId, snapshotId,
                    PlatformSnapshotStore.forServer(server).liveEvidence(PlatformSavedData.get(server)), reason));
        } catch (PlatformSnapshotStore.SnapshotException exception) {
            return Optional.empty();
        }
    }

    static Optional<PendingAction> prepareSnapshotRestore(
            MinecraftServer server, UUID transactionId, UUID snapshotId, UUID safetySnapshotId, String reason) {
        if (server == null || transactionId == null || snapshotId == null || safetySnapshotId == null) {
            return Optional.empty();
        }
        try {
            PlatformSnapshotStore store = PlatformSnapshotStore.forServer(server);
            return Optional.of(new SnapshotRestoreAction(
                    transactionId, snapshotId, safetySnapshotId,
                    store.liveEvidence(PlatformSavedData.get(server)), store.snapshotEvidence(snapshotId), reason));
        } catch (PlatformSnapshotStore.SnapshotException exception) {
            return Optional.empty();
        }
    }

    static Result execute(ServerPlayer actor, PendingAction action) {
        if (actor == null || action == null || actor.level().getServer() == null
                || !actor.level().getServer().isSameThread()) {
            return new Result(Status.FAILED, "invalid_request", action == null ? null : action.transactionId());
        }
        MinecraftServer server = actor.level().getServer();
        PlatformSavedData state = PlatformSavedData.get(server);
        PlatformSnapshotStore snapshots = PlatformSnapshotStore.forServer(server);
        if (!allowed(AdministrationControlCenterMenu.resolveRole(actor).orElse(null))) {
            return executeOwned(state, server, snapshots, actor.getUUID(), false, action, Instant.now().toEpochMilli());
        }
        if (!fresh(state, snapshots, action)) {
            auditRejected(state, actor.getUUID(), action, "stale_confirmation");
            return new Result(Status.STALE_CONFIRMATION, "stale_confirmation", action.transactionId());
        }
        return executeOwned(state, server, snapshots, actor.getUUID(), state.roleOf(actor.getUUID()).isEmpty(), action,
                Instant.now().toEpochMilli());
    }

    private static Result executeOwned(
            PlatformSavedData state,
            MinecraftServer server,
            PlatformSnapshotStore snapshots,
            UUID actorId,
            boolean nativeOwnerPermission,
            PendingAction action,
            long now) {
        if (action instanceof ExportAction value) {
            return fromExport(AuditExportService.export(
                    state, AuditExportStore.forServer(server), actorId, nativeOwnerPermission,
                    value.query(), value.reason(), now, value.transactionId()));
        }
        if (action instanceof SnapshotCreateAction value) {
            return fromCreate(AdministrationService.createSnapshot(
                    state, snapshots, actorId, nativeOwnerPermission, value.reason(), now,
                    value.transactionId(), value.snapshotId()));
        }
        if (action instanceof SnapshotRestoreAction value) {
            return fromRestore(AdministrationService.restoreSnapshot(
                    state, snapshots, actorId, nativeOwnerPermission, value.snapshotId(), value.reason(), now,
                    value.transactionId(), value.safetySnapshotId(), value.expectedTargetEvidence()));
        }
        return new Result(Status.FAILED, "unsupported_action", action.transactionId());
    }

    static boolean fresh(PlatformSavedData state, PlatformSnapshotStore snapshots, PendingAction action) {
        if (state == null || snapshots == null || action == null) {
            return false;
        }
        try {
            if (action instanceof ExportAction value) {
                return state.selectAudit(value.query(), AuditExportService.MAX_EXPORT_ROWS)
                        .equals(value.expectedSelection());
            }
            if (action instanceof SnapshotCreateAction value) {
                return snapshots.liveEvidence(state).equals(value.expectedLiveEvidence());
            }
            if (action instanceof SnapshotRestoreAction value) {
                return snapshots.liveEvidence(state).equals(value.expectedLiveEvidence())
                        && snapshots.snapshotEvidence(value.snapshotId()).equals(value.expectedTargetEvidence());
            }
        } catch (PlatformSnapshotStore.SnapshotException exception) {
            return false;
        }
        return false;
    }

    static boolean allowed(AdminRole role) {
        return role == AdminRole.OWNER;
    }

    private static void auditRejected(
            PlatformSavedData state, UUID actorId, PendingAction action, String reason) {
        if (!state.isWritable()) {
            return;
        }
        long now = Instant.now().toEpochMilli();
        state.appendDeniedAudit(new AuditEntry(
                now, actorId, Identifier.fromNamespaceAndPath(Rovenfall.MOD_ID, "admin_gui_" + reason + "_denied"),
                action.targetText(), Optional.empty(), Optional.empty(), "unchanged", "unchanged",
                reason, action.transactionId()), 1_000L);
    }

    private static Result fromExport(AuditExportService.Result result) {
        return switch (result.status()) {
            case SUCCESS -> new Result(Status.SUCCESS, "success", result.transactionId());
            case DUPLICATE -> new Result(Status.DUPLICATE, "duplicate_transaction", result.transactionId());
            case UNAUTHORIZED -> new Result(Status.UNAUTHORIZED, "unauthorized", result.transactionId());
            default -> new Result(Status.FAILED, result.status().name().toLowerCase(java.util.Locale.ROOT),
                    result.transactionId());
        };
    }

    private static Result fromCreate(AdministrationService.SnapshotCreateResult result) {
        return switch (result.status()) {
            case SUCCESS -> new Result(Status.SUCCESS, "success", result.transactionId());
            case UNAUTHORIZED -> new Result(Status.UNAUTHORIZED, "unauthorized", result.transactionId());
            default -> new Result(Status.FAILED, result.status().name().toLowerCase(java.util.Locale.ROOT),
                    result.transactionId());
        };
    }

    private static Result fromRestore(AdministrationService.SnapshotRestoreResult result) {
        return switch (result.status()) {
            case SUCCESS -> new Result(Status.SUCCESS, "success", result.transactionId());
            case DUPLICATE_TRANSACTION -> new Result(Status.DUPLICATE, "duplicate_transaction", result.transactionId());
            case STALE_SNAPSHOT -> new Result(Status.STALE_CONFIRMATION, "stale_confirmation", result.transactionId());
            case UNAUTHORIZED -> new Result(Status.UNAUTHORIZED, "unauthorized", result.transactionId());
            default -> new Result(Status.FAILED, result.status().name().toLowerCase(java.util.Locale.ROOT),
                    result.transactionId());
        };
    }

    sealed interface PendingAction permits ExportAction, SnapshotCreateAction, SnapshotRestoreAction {
        UUID transactionId();

        String reason();

        String targetText();
    }

    record ExportAction(
            UUID transactionId, AuditQuery query, PlatformSavedData.AuditSelection expectedSelection,
            String reason) implements PendingAction {
        ExportAction {
            expectedSelection = expectedSelection == null
                    ? new PlatformSavedData.AuditSelection(0, List.of())
                    : new PlatformSavedData.AuditSelection(expectedSelection.totalEntries(), expectedSelection.entries());
        }

        @Override
        public String targetText() {
            return "audit_export";
        }
    }

    record SnapshotCreateAction(
            UUID transactionId, UUID snapshotId, PlatformSnapshotStore.Evidence expectedLiveEvidence,
            String reason) implements PendingAction {
        @Override
        public String targetText() {
            return "snapshot:" + snapshotId;
        }
    }

    record SnapshotRestoreAction(
            UUID transactionId, UUID snapshotId, UUID safetySnapshotId,
            PlatformSnapshotStore.Evidence expectedLiveEvidence,
            PlatformSnapshotStore.Evidence expectedTargetEvidence,
            String reason) implements PendingAction {
        @Override
        public String targetText() {
            return "snapshot:" + snapshotId;
        }
    }

    enum Status {
        SUCCESS,
        DUPLICATE,
        STALE_CONFIRMATION,
        UNAUTHORIZED,
        FAILED
    }

    record Result(Status status, String detail, UUID transactionId) {
        boolean succeeded() {
            return status == Status.SUCCESS || status == Status.DUPLICATE;
        }
    }
}
