package org.dldyou.rovenfall.administration;

import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.Identifier;
import org.dldyou.rovenfall.Rovenfall;

public final class AdministrationService {
    public static final UUID SYSTEM_ACTOR = new UUID(0L, 0L);
    public static final int MAX_REASON_LENGTH = 256;
    private static final long DENIED_AUDIT_INTERVAL_MILLIS = 1_000L;
    private static final Identifier ROLE_SET = action("admin_role_set");
    private static final Identifier ROLE_SET_DENIED = action("admin_role_set_denied");
    private static final Identifier ROLE_SET_NO_CHANGE = action("admin_role_set_no_change");
    private static final Identifier SNAPSHOT_CREATE = action("platform_snapshot_create");
    private static final Identifier SNAPSHOT_CREATE_DENIED = action("platform_snapshot_create_denied");
    private static final Identifier SNAPSHOT_CREATE_FAILED = action("platform_snapshot_create_failed");
    private static final Identifier SNAPSHOT_RESTORE = action("platform_snapshot_restore");
    private static final Identifier SNAPSHOT_RESTORE_DENIED = action("platform_snapshot_restore_denied");
    private static final Identifier SNAPSHOT_RESTORE_FAILED = action("platform_snapshot_restore_failed");
    private static final String PLATFORM_TARGET = "platform";

    private AdministrationService() {
    }

    public static RoleChangeResult changeRole(
            PlatformSavedData state,
            UUID actorId,
            boolean authorizationOverride,
            UUID targetId,
            String requestedRole,
            String reason,
            long timestampEpochMillis,
            UUID transactionId) {
        if (!state.isWritable()) {
            return new RoleChangeResult(RoleChangeStatus.READ_ONLY_SCHEMA, transactionId, false);
        }

        if (!isOwner(state, actorId, authorizationOverride)) {
            return denied(state, actorId, targetId, safeRoleId(requestedRole), "unauthorized", timestampEpochMillis, transactionId,
                    RoleChangeStatus.UNAUTHORIZED);
        }

        Optional<AdminRole> parsedRole = AdminRole.parse(requestedRole);
        if (parsedRole.isEmpty()) {
            return denied(state, actorId, targetId, safeRoleId(requestedRole), "invalid_role", timestampEpochMillis, transactionId,
                    RoleChangeStatus.INVALID_ROLE);
        }

        Optional<String> validReason = validReason(reason);
        if (validReason.isEmpty()) {
            return denied(state, actorId, targetId, parsedRole.get().getSerializedName(), "invalid_reason", timestampEpochMillis, transactionId,
                    RoleChangeStatus.INVALID_REASON);
        }
        String normalizedReason = validReason.get();

        AdminRole role = parsedRole.get();
        Optional<AdminRole> previousRole = state.roleOf(targetId);
        if (previousRole.orElse(null) == role) {
            AuditEntry auditEntry = auditEntry(
                    timestampEpochMillis,
                    actorId,
                    ROLE_SET_NO_CHANGE,
                    targetId.toString(),
                    roleId(previousRole),
                    role.getSerializedName(),
                    normalizedReason,
                    transactionId
            );
            boolean audited = state.appendDeniedAudit(auditEntry, DENIED_AUDIT_INTERVAL_MILLIS);
            return new RoleChangeResult(RoleChangeStatus.NO_CHANGE, transactionId, audited);
        }

        AuditEntry auditEntry = auditEntry(
                timestampEpochMillis,
                actorId,
                ROLE_SET,
                targetId.toString(),
                roleId(previousRole),
                role.getSerializedName(),
                normalizedReason,
                transactionId
        );
        state.commitRoleChange(targetId, role, auditEntry);
        return new RoleChangeResult(RoleChangeStatus.SUCCESS, transactionId, true);
    }

    static SnapshotCreateResult createSnapshot(
            PlatformSavedData state,
            PlatformSnapshotStore store,
            UUID actorId,
            boolean authorizationOverride,
            String reason,
            long timestampEpochMillis,
            UUID transactionId,
            UUID snapshotId) {
        if (!state.isWritable()) {
            return new SnapshotCreateResult(SnapshotCreateStatus.READ_ONLY_SCHEMA, snapshotId, transactionId, false);
        }
        if (!isOwner(state, actorId, authorizationOverride)) {
            boolean audited = state.appendDeniedAudit(auditEntry(
                    timestampEpochMillis, actorId, SNAPSHOT_CREATE_DENIED, PLATFORM_TARGET,
                    "none", snapshotValue(snapshotId), "unauthorized", transactionId), DENIED_AUDIT_INTERVAL_MILLIS);
            return new SnapshotCreateResult(SnapshotCreateStatus.UNAUTHORIZED, snapshotId, transactionId, audited);
        }

        Optional<String> validReason = validReason(reason);
        if (validReason.isEmpty()) {
            boolean audited = state.appendDeniedAudit(auditEntry(
                    timestampEpochMillis, actorId, SNAPSHOT_CREATE_DENIED, PLATFORM_TARGET,
                    "none", snapshotValue(snapshotId), "invalid_reason", transactionId), DENIED_AUDIT_INTERVAL_MILLIS);
            return new SnapshotCreateResult(SnapshotCreateStatus.INVALID_REASON, snapshotId, transactionId, audited);
        }

        try {
            store.write(snapshotId, state);
        } catch (PlatformSnapshotStore.SnapshotException exception) {
            boolean audited = state.appendDeniedAudit(auditEntry(
                    timestampEpochMillis, actorId, SNAPSHOT_CREATE_FAILED, PLATFORM_TARGET,
                    "none", "write_failed", validReason.get(), transactionId), DENIED_AUDIT_INTERVAL_MILLIS);
            return new SnapshotCreateResult(SnapshotCreateStatus.STORAGE_ERROR, snapshotId, transactionId, audited);
        }

        state.commitAudit(auditEntry(
                timestampEpochMillis, actorId, SNAPSHOT_CREATE, PLATFORM_TARGET,
                "none", snapshotValue(snapshotId), validReason.get(), transactionId));
        return new SnapshotCreateResult(SnapshotCreateStatus.SUCCESS, snapshotId, transactionId, true);
    }

    static SnapshotRestoreResult restoreSnapshot(
            PlatformSavedData state,
            PlatformSnapshotStore store,
            UUID actorId,
            boolean authorizationOverride,
            UUID snapshotId,
            String reason,
            long timestampEpochMillis,
            UUID transactionId,
            UUID safetySnapshotId) {
        return restoreSnapshot(
                state, store, actorId, authorizationOverride, snapshotId, reason,
                timestampEpochMillis, transactionId, safetySnapshotId, null);
    }

    static SnapshotRestoreResult restoreSnapshot(
            PlatformSavedData state,
            PlatformSnapshotStore store,
            UUID actorId,
            boolean authorizationOverride,
            UUID snapshotId,
            String reason,
            long timestampEpochMillis,
            UUID transactionId,
            UUID safetySnapshotId,
            PlatformSnapshotStore.Evidence expectedSnapshotEvidence) {
        if (!state.isWritable()) {
            return new SnapshotRestoreResult(
                    SnapshotRestoreStatus.READ_ONLY_SCHEMA, snapshotId, safetySnapshotId, transactionId, false);
        }
        if (!isOwner(state, actorId, authorizationOverride)) {
            boolean audited = state.appendDeniedAudit(auditEntry(
                    timestampEpochMillis, actorId, SNAPSHOT_RESTORE_DENIED, PLATFORM_TARGET,
                    "unchanged", snapshotValue(snapshotId), "unauthorized", transactionId), DENIED_AUDIT_INTERVAL_MILLIS);
            return new SnapshotRestoreResult(
                    SnapshotRestoreStatus.UNAUTHORIZED, snapshotId, safetySnapshotId, transactionId, audited);
        }

        if (transactionId == null || SYSTEM_ACTOR.equals(transactionId)) {
            boolean audited = state.appendDeniedAudit(auditEntry(
                    timestampEpochMillis, actorId, SNAPSHOT_RESTORE_DENIED, PLATFORM_TARGET,
                    "unchanged", snapshotValue(snapshotId), "invalid_transaction",
                    UUID.randomUUID()), DENIED_AUDIT_INTERVAL_MILLIS);
            return new SnapshotRestoreResult(
                    SnapshotRestoreStatus.INVALID_TRANSACTION, snapshotId, safetySnapshotId, transactionId, audited);
        }
        if (state.hasTransaction(transactionId, timestampEpochMillis)) {
            return new SnapshotRestoreResult(
                    SnapshotRestoreStatus.DUPLICATE_TRANSACTION, snapshotId, safetySnapshotId, transactionId, false);
        }

        Optional<String> validReason = validReason(reason);
        if (validReason.isEmpty()) {
            boolean audited = state.appendDeniedAudit(auditEntry(
                    timestampEpochMillis, actorId, SNAPSHOT_RESTORE_DENIED, PLATFORM_TARGET,
                    "unchanged", snapshotValue(snapshotId), "invalid_reason", transactionId), DENIED_AUDIT_INTERVAL_MILLIS);
            return new SnapshotRestoreResult(
                    SnapshotRestoreStatus.INVALID_REASON, snapshotId, safetySnapshotId, transactionId, audited);
        }

        if (state.hasShopLocks()) {
            boolean audited = state.appendDeniedAudit(auditEntry(
                    timestampEpochMillis, actorId, SNAPSHOT_RESTORE_DENIED, PLATFORM_TARGET,
                    "unchanged", "dependency_locked", validReason.get(), transactionId), DENIED_AUDIT_INTERVAL_MILLIS);
            return new SnapshotRestoreResult(
                    SnapshotRestoreStatus.DEPENDENCY_LOCKED, snapshotId, safetySnapshotId, transactionId, audited);
        }

        PlatformSnapshotStore.ValidatedSnapshot validatedSnapshot;
        try {
            validatedSnapshot = store.readValidated(snapshotId);
        } catch (PlatformSnapshotStore.SnapshotException exception) {
            boolean audited = state.appendDeniedAudit(auditEntry(
                    timestampEpochMillis, actorId, SNAPSHOT_RESTORE_FAILED, PLATFORM_TARGET,
                    "unchanged", "snapshot_unavailable", validReason.get(), transactionId), DENIED_AUDIT_INTERVAL_MILLIS);
            return new SnapshotRestoreResult(
                    SnapshotRestoreStatus.SNAPSHOT_UNAVAILABLE, snapshotId, safetySnapshotId, transactionId, audited);
        }
        if (expectedSnapshotEvidence != null
                && !expectedSnapshotEvidence.equals(validatedSnapshot.evidence())) {
            boolean audited = state.appendDeniedAudit(auditEntry(
                    timestampEpochMillis, actorId, SNAPSHOT_RESTORE_DENIED, PLATFORM_TARGET,
                    "sha256:" + expectedSnapshotEvidence.sha256(),
                    "sha256:" + validatedSnapshot.evidence().sha256(),
                    "stale_confirmation", transactionId), DENIED_AUDIT_INTERVAL_MILLIS);
            return new SnapshotRestoreResult(
                    SnapshotRestoreStatus.STALE_SNAPSHOT, snapshotId, safetySnapshotId, transactionId, audited);
        }
        PlatformSavedData snapshot = validatedSnapshot.state();

        if (snapshot.hasTransaction(transactionId, timestampEpochMillis)) {
            return new SnapshotRestoreResult(
                    SnapshotRestoreStatus.DUPLICATE_TRANSACTION, snapshotId, safetySnapshotId, transactionId, false);
        }

        PlatformSavedData.RestorePreparation restoredTransactions =
                state.prepareTransactionRestore(snapshot, transactionId, timestampEpochMillis);
        if (restoredTransactions.status() != PlatformSavedData.RestorePreparationStatus.SUCCESS) {
            String failure = restoredTransactions.status() == PlatformSavedData.RestorePreparationStatus.LEDGER_FULL
                    ? "transaction_ledger_full" : "transaction_evidence_conflict";
            boolean audited = state.appendDeniedAudit(auditEntry(
                    timestampEpochMillis, actorId, SNAPSHOT_RESTORE_FAILED, PLATFORM_TARGET,
                    "unchanged", failure, validReason.get(), transactionId),
                    DENIED_AUDIT_INTERVAL_MILLIS);
            return new SnapshotRestoreResult(
                    restoredTransactions.status() == PlatformSavedData.RestorePreparationStatus.LEDGER_FULL
                            ? SnapshotRestoreStatus.TRANSACTION_LEDGER_FULL
                            : SnapshotRestoreStatus.TRANSACTION_EVIDENCE_CONFLICT,
                    snapshotId,
                    safetySnapshotId,
                    transactionId,
                    audited);
        }

        try {
            store.write(safetySnapshotId, state);
        } catch (PlatformSnapshotStore.SnapshotException exception) {
            boolean audited = state.appendDeniedAudit(auditEntry(
                    timestampEpochMillis, actorId, SNAPSHOT_RESTORE_FAILED, PLATFORM_TARGET,
                    "unchanged", "safety_snapshot_failed", validReason.get(), transactionId), DENIED_AUDIT_INTERVAL_MILLIS);
            return new SnapshotRestoreResult(
                    SnapshotRestoreStatus.SAFETY_SNAPSHOT_FAILED, snapshotId, safetySnapshotId, transactionId, audited);
        }

        state.commitRestore(snapshot, restoredTransactions.evidence().orElseThrow(), auditEntry(
                timestampEpochMillis, actorId, SNAPSHOT_RESTORE, PLATFORM_TARGET,
                snapshotValue(safetySnapshotId), snapshotValue(snapshotId), validReason.get(), transactionId));
        return new SnapshotRestoreResult(
                SnapshotRestoreStatus.SUCCESS, snapshotId, safetySnapshotId, transactionId, true);
    }

    private static RoleChangeResult denied(
            PlatformSavedData state,
            UUID actorId,
            UUID targetId,
            String requestedRole,
            String denialReason,
            long timestampEpochMillis,
            UUID transactionId,
            RoleChangeStatus status) {
        AuditEntry auditEntry = auditEntry(
                timestampEpochMillis,
                actorId,
                ROLE_SET_DENIED,
                targetId.toString(),
                roleId(state.roleOf(targetId)),
                requestedRole,
                denialReason,
                transactionId
        );
        boolean audited = state.appendDeniedAudit(auditEntry, DENIED_AUDIT_INTERVAL_MILLIS);
        return new RoleChangeResult(status, transactionId, audited);
    }

    private static AuditEntry auditEntry(
            long timestampEpochMillis,
            UUID actorId,
            Identifier action,
            String target,
            String beforeValue,
            String afterValue,
            String reason,
            UUID transactionId) {
        return new AuditEntry(
                timestampEpochMillis,
                actorId,
                action,
                target,
                Optional.empty(),
                Optional.empty(),
                beforeValue,
                afterValue,
                reason,
                transactionId
        );
    }

    private static String roleId(Optional<AdminRole> role) {
        return role.map(AdminRole::getSerializedName).orElse("none");
    }

    private static String safeRoleId(String requestedRole) {
        if (requestedRole == null || requestedRole.length() > 64 || !requestedRole.matches("[a-z0-9_]+")) {
            return "invalid";
        }
        return requestedRole;
    }

    static boolean isOwner(PlatformSavedData state, UUID actorId, boolean authorizationOverride) {
        return authorizationOverride || state.roleOf(actorId).orElse(null) == AdminRole.OWNER;
    }

    static Optional<String> validReason(String reason) {
        String normalized = reason == null ? "" : reason.strip();
        return normalized.isEmpty() || normalized.length() > MAX_REASON_LENGTH
                ? Optional.empty()
                : Optional.of(normalized);
    }

    private static String snapshotValue(UUID snapshotId) {
        return "snapshot:" + snapshotId;
    }

    private static Identifier action(String path) {
        return Identifier.fromNamespaceAndPath(Rovenfall.MOD_ID, path);
    }

    public enum RoleChangeStatus {
        SUCCESS,
        UNAUTHORIZED,
        INVALID_ROLE,
        INVALID_REASON,
        NO_CHANGE,
        READ_ONLY_SCHEMA
    }

    public record RoleChangeResult(RoleChangeStatus status, UUID transactionId, boolean auditRecorded) {
    }

    public enum SnapshotCreateStatus {
        SUCCESS,
        UNAUTHORIZED,
        INVALID_REASON,
        READ_ONLY_SCHEMA,
        STORAGE_ERROR
    }

    public record SnapshotCreateResult(
            SnapshotCreateStatus status,
            UUID snapshotId,
            UUID transactionId,
            boolean auditRecorded) {
    }

    public enum SnapshotRestoreStatus {
        SUCCESS,
        DUPLICATE_TRANSACTION,
        UNAUTHORIZED,
        INVALID_TRANSACTION,
        INVALID_REASON,
        READ_ONLY_SCHEMA,
        SNAPSHOT_UNAVAILABLE,
        STALE_SNAPSHOT,
        TRANSACTION_LEDGER_FULL,
        TRANSACTION_EVIDENCE_CONFLICT,
        DEPENDENCY_LOCKED,
        SAFETY_SNAPSHOT_FAILED
    }

    public record SnapshotRestoreResult(
            SnapshotRestoreStatus status,
            UUID snapshotId,
            UUID safetySnapshotId,
            UUID transactionId,
            boolean auditRecorded) {
    }
}
