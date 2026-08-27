package org.dldyou.rovenfall.administration;

import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import org.dldyou.rovenfall.Rovenfall;
import org.dldyou.rovenfall.world.ProtectedRegion;

public final class ProtectedRegionService {
    private static final UUID ZERO_UUID = new UUID(0L, 0L);
    private static final long DENIED_AUDIT_INTERVAL_MILLIS = 1_000L;
    private static final Identifier CREATE = action("protected_region_create");
    private static final Identifier EDIT = action("protected_region_edit");
    private static final Identifier DELETE = action("protected_region_delete");
    private static final Identifier DENIED = action("protected_region_denied");

    private ProtectedRegionService() {
    }

    public static MutationResult create(
            PlatformSavedData state,
            UUID actorId,
            boolean authorizationOverride,
            Identifier regionId,
            ProtectedRegion region,
            String reason,
            long timestampEpochMillis,
            UUID transactionId) {
        if (region == null) {
            return new MutationResult(Status.INVALID_REQUEST, transactionId, false);
        }
        return mutate(state, actorId, authorizationOverride, regionId, Optional.ofNullable(region), true,
                reason, timestampEpochMillis, transactionId);
    }

    public static MutationResult edit(
            PlatformSavedData state,
            UUID actorId,
            boolean authorizationOverride,
            Identifier regionId,
            ProtectedRegion region,
            String reason,
            long timestampEpochMillis,
            UUID transactionId) {
        if (region == null) {
            return new MutationResult(Status.INVALID_REQUEST, transactionId, false);
        }
        return mutate(state, actorId, authorizationOverride, regionId, Optional.ofNullable(region), false,
                reason, timestampEpochMillis, transactionId);
    }

    public static MutationResult delete(
            PlatformSavedData state,
            UUID actorId,
            boolean authorizationOverride,
            Identifier regionId,
            String reason,
            long timestampEpochMillis,
            UUID transactionId) {
        return mutate(state, actorId, authorizationOverride, regionId, Optional.empty(), false,
                reason, timestampEpochMillis, transactionId);
    }

    private static MutationResult mutate(
            PlatformSavedData state,
            UUID actorId,
            boolean authorizationOverride,
            Identifier regionId,
            Optional<ProtectedRegion> requested,
            boolean create,
            String reason,
            long timestampEpochMillis,
            UUID transactionId) {
        if (state == null || actorId == null || regionId == null || requested == null || timestampEpochMillis < 0) {
            return new MutationResult(Status.INVALID_REQUEST, transactionId, false);
        }
        if (!state.isWritable()) {
            return new MutationResult(Status.READ_ONLY_SCHEMA, transactionId, false);
        }
        if (transactionId == null || ZERO_UUID.equals(transactionId)) {
            return denied(state, actorId, regionId, requested, Status.INVALID_TRANSACTION, "invalid_transaction",
                    timestampEpochMillis, transactionId);
        }
        String normalizedReason = reason == null ? "" : reason.trim();
        if (normalizedReason.isEmpty() || normalizedReason.length() > AdministrationService.MAX_REASON_LENGTH) {
            return denied(state, actorId, regionId, requested, Status.INVALID_REASON, "invalid_reason",
                    timestampEpochMillis, transactionId);
        }
        if (!authorized(state, actorId, authorizationOverride)) {
            return denied(state, actorId, regionId, requested, Status.UNAUTHORIZED, "unauthorized",
                    timestampEpochMillis, transactionId);
        }
        if (requested.isPresent() && !actorId.equals(requested.orElseThrow().administratorId())) {
            return denied(state, actorId, regionId, requested, Status.INVALID_REQUEST, "administrator_mismatch",
                    timestampEpochMillis, transactionId);
        }
        if (state.hasAuditTransaction(transactionId)) {
            return new MutationResult(Status.DUPLICATE_TRANSACTION, transactionId, false);
        }

        Optional<ProtectedRegion> previous = state.protectedRegion(regionId);
        if (create && previous.isPresent()) {
            return denied(state, actorId, regionId, requested, Status.ALREADY_EXISTS, "already_exists",
                    timestampEpochMillis, transactionId);
        }
        if (!create && previous.isEmpty()) {
            return denied(state, actorId, regionId, requested, Status.NOT_FOUND, "not_found",
                    timestampEpochMillis, transactionId);
        }
        if (requested.isPresent() && !state.canStoreProtectedRegion(regionId, requested.orElseThrow())) {
            return denied(state, actorId, regionId, requested, Status.LIMIT_EXCEEDED, "limit_exceeded",
                    timestampEpochMillis, transactionId);
        }

        Identifier action = requested.isEmpty() ? DELETE : create ? CREATE : EDIT;
        state.commitProtectedRegionMutation(
                regionId,
                requested,
                audit(timestampEpochMillis, actorId, action, regionId, previous, requested,
                        normalizedReason, transactionId));
        return new MutationResult(Status.SUCCESS, transactionId, true);
    }

    private static MutationResult denied(
            PlatformSavedData state,
            UUID actorId,
            Identifier regionId,
            Optional<ProtectedRegion> requested,
            Status status,
            String reason,
            long timestampEpochMillis,
            UUID transactionId) {
        UUID auditId = transactionId == null || ZERO_UUID.equals(transactionId) ? UUID.randomUUID() : transactionId;
        boolean recorded = state.appendDeniedAudit(audit(
                timestampEpochMillis, actorId, DENIED, regionId, state.protectedRegion(regionId), requested,
                reason, auditId), DENIED_AUDIT_INTERVAL_MILLIS);
        return new MutationResult(status, transactionId, recorded);
    }

    private static AuditEntry audit(
            long timestampEpochMillis,
            UUID actorId,
            Identifier action,
            Identifier regionId,
            Optional<ProtectedRegion> before,
            Optional<ProtectedRegion> after,
            String reason,
            UUID transactionId) {
        ProtectedRegion location = after.orElseGet(() -> before.orElse(null));
        return new AuditEntry(
                timestampEpochMillis,
                actorId,
                action,
                regionId.toString(),
                location == null ? Optional.empty() : Optional.of(location.dimension().identifier()),
                location == null ? Optional.empty() : Optional.of(new BlockPos(
                        (location.minChunkX() << 4) + 8, 0, (location.minChunkZ() << 4) + 8)),
                before.map(ProtectedRegion::auditSummary).orElse("absent"),
                after.map(ProtectedRegion::auditSummary).orElse("absent"),
                reason,
                transactionId);
    }

    private static boolean authorized(PlatformSavedData state, UUID actorId, boolean override) {
        return override || state.roleOf(actorId).orElse(null) == AdminRole.OWNER;
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
        ALREADY_EXISTS,
        NOT_FOUND,
        LIMIT_EXCEEDED
    }

    public record MutationResult(Status status, UUID transactionId, boolean auditRecorded) {
    }
}
