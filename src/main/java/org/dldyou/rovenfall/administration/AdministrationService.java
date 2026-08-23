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

    private AdministrationService() {
    }

    static RoleChangeResult changeRole(
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

        if (!authorizationOverride && state.roleOf(actorId).orElse(null) != AdminRole.OWNER) {
            return denied(state, actorId, targetId, safeRoleId(requestedRole), "unauthorized", timestampEpochMillis, transactionId,
                    RoleChangeStatus.UNAUTHORIZED);
        }

        Optional<AdminRole> parsedRole = AdminRole.parse(requestedRole);
        if (parsedRole.isEmpty()) {
            return denied(state, actorId, targetId, safeRoleId(requestedRole), "invalid_role", timestampEpochMillis, transactionId,
                    RoleChangeStatus.INVALID_ROLE);
        }

        String normalizedReason = reason == null ? "" : reason.strip();
        if (normalizedReason.isEmpty() || normalizedReason.length() > MAX_REASON_LENGTH) {
            return denied(state, actorId, targetId, parsedRole.get().getSerializedName(), "invalid_reason", timestampEpochMillis, transactionId,
                    RoleChangeStatus.INVALID_REASON);
        }

        AdminRole role = parsedRole.get();
        Optional<AdminRole> previousRole = state.roleOf(targetId);
        if (previousRole.orElse(null) == role) {
            AuditEntry auditEntry = auditEntry(
                    timestampEpochMillis,
                    actorId,
                    ROLE_SET_NO_CHANGE,
                    targetId,
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
                targetId,
                roleId(previousRole),
                role.getSerializedName(),
                normalizedReason,
                transactionId
        );
        state.commitRoleChange(targetId, role, auditEntry);
        return new RoleChangeResult(RoleChangeStatus.SUCCESS, transactionId, true);
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
                targetId,
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
            UUID targetId,
            String beforeValue,
            String afterValue,
            String reason,
            UUID transactionId) {
        return new AuditEntry(
                timestampEpochMillis,
                actorId,
                action,
                targetId.toString(),
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
}
