package org.dldyou.rovenfall.administration;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.dldyou.rovenfall.Rovenfall;
import org.dldyou.rovenfall.worlds.Portal;

/** Server-thread-only mutation boundary for persisted administrator portals. */
public final class ManagedPortalService {
    private static final UUID ZERO_UUID = new UUID(0L, 0L);
    private static final long DENIED_AUDIT_INTERVAL_MILLIS = 1_000L;

    private ManagedPortalService() {
    }

    public static MutationResult create(
            PlatformSavedData state,
            UUID actorId,
            boolean authorizationOverride,
            Identifier portalId,
            Portal portal,
            Predicate<ResourceKey<Level>> dimensionExists,
            String reason,
            long timestampEpochMillis,
            UUID transactionId) {
        MutationResult rejected = precheck(
                state, actorId, authorizationOverride, portalId, reason, timestampEpochMillis, transactionId);
        if (rejected != null) {
            return rejected;
        }
        if (portal == null || dimensionExists == null || Portal.validate(portal).error().isPresent()) {
            return denied(state, actorId, portalId, Status.INVALID_REQUEST, "invalid_request",
                    timestampEpochMillis, transactionId);
        }
        if (!dimensionExists.test(portal.originDimension())
                || !dimensionExists.test(portal.destinationDimension())) {
            return denied(state, actorId, portalId, Status.DIMENSION_UNAVAILABLE, "dimension_unavailable",
                    timestampEpochMillis, transactionId);
        }
        if (state.portal(portalId).isPresent()) {
            return denied(state, actorId, portalId, Status.PORTAL_EXISTS, "portal_exists",
                    timestampEpochMillis, transactionId);
        }
        if (state.portalCount() >= Portal.MAX_PORTALS) {
            return denied(state, actorId, portalId, Status.PORTAL_LIMIT_REACHED, "portal_limit_reached",
                    timestampEpochMillis, transactionId);
        }
        return commit(state, actorId, portalId, Optional.empty(), Optional.of(portal), reason,
                timestampEpochMillis, transactionId, "portal_create");
    }

    public static MutationResult delete(
            PlatformSavedData state,
            UUID actorId,
            boolean authorizationOverride,
            Identifier portalId,
            String reason,
            long timestampEpochMillis,
            UUID transactionId) {
        MutationResult rejected = precheck(
                state, actorId, authorizationOverride, portalId, reason, timestampEpochMillis, transactionId);
        if (rejected != null) {
            return rejected;
        }
        Optional<Portal> before = state.portal(portalId);
        if (before.isEmpty()) {
            return denied(state, actorId, portalId, Status.PORTAL_NOT_FOUND, "portal_not_found",
                    timestampEpochMillis, transactionId);
        }
        return commit(state, actorId, portalId, before, Optional.empty(), reason,
                timestampEpochMillis, transactionId, "portal_delete");
    }

    public static boolean canManagePortals(
            PlatformSavedData state, UUID actorId, boolean authorizationOverride) {
        AdminRole role = state.roleOf(actorId).orElse(null);
        return authorizationOverride || role == AdminRole.CONTENT_MANAGER || role == AdminRole.OWNER;
    }

    private static MutationResult precheck(
            PlatformSavedData state,
            UUID actorId,
            boolean authorizationOverride,
            Identifier portalId,
            String reason,
            long timestampEpochMillis,
            UUID transactionId) {
        if (!state.isWritable()) {
            return result(Status.READ_ONLY_SCHEMA, transactionId, false);
        }
        if (actorId == null || portalId == null || timestampEpochMillis < 0) {
            return result(Status.INVALID_REQUEST, transactionId, false);
        }
        if (!canManagePortals(state, actorId, authorizationOverride)) {
            return denied(state, actorId, portalId, Status.UNAUTHORIZED, "unauthorized",
                    timestampEpochMillis, transactionId);
        }
        if (!validTransactionId(transactionId)) {
            return denied(state, actorId, portalId, Status.INVALID_TRANSACTION, "invalid_transaction",
                    timestampEpochMillis, transactionId);
        }
        if (state.hasTransaction(transactionId, timestampEpochMillis)) {
            return result(Status.DUPLICATE_TRANSACTION, transactionId, false);
        }
        if (validReason(reason).isEmpty()) {
            return denied(state, actorId, portalId, Status.INVALID_REASON, "invalid_reason",
                    timestampEpochMillis, transactionId);
        }
        if (!state.canCommitTransaction(transactionId, timestampEpochMillis)) {
            return denied(state, actorId, portalId, Status.TRANSACTION_LEDGER_FULL, "transaction_ledger_full",
                    timestampEpochMillis, transactionId);
        }
        return null;
    }

    private static MutationResult commit(
            PlatformSavedData state,
            UUID actorId,
            Identifier portalId,
            Optional<Portal> before,
            Optional<Portal> after,
            String reason,
            long timestampEpochMillis,
            UUID transactionId,
            String action) {
        Portal location = after.or(() -> before).orElseThrow();
        state.commitPortalMutation(portalId, after, transactionId, timestampEpochMillis, new AuditEntry(
                timestampEpochMillis,
                actorId,
                action(action),
                portalId.toString(),
                Optional.of(location.originDimension().identifier()),
                Optional.of(location.origin()),
                summary(before),
                summary(after),
                validReason(reason).orElseThrow(),
                transactionId));
        return result(Status.SUCCESS, transactionId, true);
    }

    private static MutationResult denied(
            PlatformSavedData state,
            UUID actorId,
            Identifier portalId,
            Status status,
            String reason,
            long timestampEpochMillis,
            UUID transactionId) {
        if (actorId == null || portalId == null || timestampEpochMillis < 0) {
            return result(status, transactionId, false);
        }
        UUID evidenceId = validTransactionId(transactionId) ? transactionId : UUID.randomUUID();
        Optional<Portal> existing = state.portal(portalId);
        boolean audited = state.appendDeniedAudit(new AuditEntry(
                timestampEpochMillis,
                actorId,
                action("portal_mutation_denied"),
                portalId.toString(),
                existing.map(portal -> portal.originDimension().identifier()),
                existing.map(Portal::origin),
                summary(existing),
                summary(existing),
                reason,
                evidenceId), DENIED_AUDIT_INTERVAL_MILLIS);
        return result(status, transactionId, audited);
    }

    private static String summary(Optional<Portal> portal) {
        return portal.map(value -> "origin=" + value.originDimension().identifier() + "@"
                + value.origin().toShortString() + ";destination="
                + value.destinationDimension().identifier() + "@" + value.destination().toShortString()
                + ";protection=" + value.protectionRadius() + ";search=" + value.safeSearchRadius()
                + ";cooldown=" + value.cooldownSeconds()).orElse("none");
    }

    private static Optional<String> validReason(String reason) {
        String normalized = reason == null ? "" : reason.strip();
        return normalized.isEmpty() || normalized.length() > AdministrationService.MAX_REASON_LENGTH
                ? Optional.empty()
                : Optional.of(normalized);
    }

    private static boolean validTransactionId(UUID transactionId) {
        return transactionId != null && !ZERO_UUID.equals(transactionId);
    }

    private static MutationResult result(Status status, UUID transactionId, boolean audited) {
        return new MutationResult(status, transactionId, audited);
    }

    private static Identifier action(String path) {
        return Identifier.fromNamespaceAndPath(Rovenfall.MOD_ID, path);
    }

    public enum Status {
        SUCCESS,
        DUPLICATE_TRANSACTION,
        UNAUTHORIZED,
        INVALID_REQUEST,
        INVALID_TRANSACTION,
        INVALID_REASON,
        READ_ONLY_SCHEMA,
        TRANSACTION_LEDGER_FULL,
        PORTAL_LIMIT_REACHED,
        PORTAL_EXISTS,
        PORTAL_NOT_FOUND,
        DIMENSION_UNAVAILABLE
    }

    public record MutationResult(Status status, UUID transactionId, boolean auditRecorded) {
    }
}
