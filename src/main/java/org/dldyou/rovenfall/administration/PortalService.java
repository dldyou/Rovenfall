package org.dldyou.rovenfall.administration;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import org.dldyou.rovenfall.Rovenfall;
import org.dldyou.rovenfall.claims.ClaimKey;
import org.dldyou.rovenfall.world.PortalDefinition;
import org.dldyou.rovenfall.world.ProtectedRegion;

public final class PortalService {
    private static final UUID ZERO_UUID = new UUID(0L, 0L);
    private static final long DENIED_AUDIT_INTERVAL_MILLIS = 1_000L;
    private static final Identifier CREATE = action("portal_create");
    private static final Identifier EDIT = action("portal_edit");
    private static final Identifier DELETE = action("portal_delete");
    private static final Identifier DENIED = action("portal_mutation_denied");

    private PortalService() {
    }

    public static MutationResult create(
            PlatformSavedData state,
            UUID actorId,
            boolean authorizationOverride,
            Identifier portalId,
            PortalDefinition definition,
            Predicate<PortalDefinition.Endpoint> endpointAvailable,
            String reason,
            long timestampEpochMillis,
            UUID transactionId) {
        if (definition == null) {
            return new MutationResult(Status.INVALID_REQUEST, transactionId, false);
        }
        return mutate(state, actorId, authorizationOverride, portalId, Optional.ofNullable(definition),
                endpointAvailable, true, reason, timestampEpochMillis, transactionId);
    }

    public static MutationResult edit(
            PlatformSavedData state,
            UUID actorId,
            boolean authorizationOverride,
            Identifier portalId,
            PortalDefinition definition,
            Predicate<PortalDefinition.Endpoint> endpointAvailable,
            String reason,
            long timestampEpochMillis,
            UUID transactionId) {
        if (definition == null) {
            return new MutationResult(Status.INVALID_REQUEST, transactionId, false);
        }
        return mutate(state, actorId, authorizationOverride, portalId, Optional.ofNullable(definition),
                endpointAvailable, false, reason, timestampEpochMillis, transactionId);
    }

    public static MutationResult delete(
            PlatformSavedData state,
            UUID actorId,
            boolean authorizationOverride,
            Identifier portalId,
            String reason,
            long timestampEpochMillis,
            UUID transactionId) {
        return mutate(state, actorId, authorizationOverride, portalId, Optional.empty(),
                ignored -> true, false, reason, timestampEpochMillis, transactionId);
    }

    private static MutationResult mutate(
            PlatformSavedData state,
            UUID actorId,
            boolean authorizationOverride,
            Identifier portalId,
            Optional<PortalDefinition> requested,
            Predicate<PortalDefinition.Endpoint> endpointAvailable,
            boolean create,
            String reason,
            long timestampEpochMillis,
            UUID transactionId) {
        if (state == null || actorId == null || portalId == null || requested == null
                || endpointAvailable == null || timestampEpochMillis < 0) {
            return new MutationResult(Status.INVALID_REQUEST, transactionId, false);
        }
        if (!state.isWritable()) {
            return new MutationResult(Status.READ_ONLY_SCHEMA, transactionId, false);
        }
        if (transactionId == null || ZERO_UUID.equals(transactionId)) {
            return denied(state, actorId, portalId, requested, Status.INVALID_TRANSACTION, "invalid_transaction",
                    timestampEpochMillis, transactionId);
        }
        String normalizedReason = reason == null ? "" : reason.strip();
        if (normalizedReason.isEmpty() || normalizedReason.length() > AdministrationService.MAX_REASON_LENGTH) {
            return denied(state, actorId, portalId, requested, Status.INVALID_REASON, "invalid_reason",
                    timestampEpochMillis, transactionId);
        }
        AdminRole role = state.roleOf(actorId).orElse(null);
        if (!authorizationOverride && role != AdminRole.CONTENT_MANAGER && role != AdminRole.OWNER) {
            return denied(state, actorId, portalId, requested, Status.UNAUTHORIZED, "unauthorized",
                    timestampEpochMillis, transactionId);
        }
        if (create && requested.isEmpty() || requested.isPresent() && !requested.orElseThrow().isValid()) {
            return denied(state, actorId, portalId, requested, Status.INVALID_REQUEST, "invalid_definition",
                    timestampEpochMillis, transactionId);
        }
        if (state.hasAuditTransaction(transactionId)) {
            return new MutationResult(Status.DUPLICATE_TRANSACTION, transactionId, false);
        }
        if (requested.isPresent() && !actorId.equals(requested.orElseThrow().administratorId())) {
            return denied(state, actorId, portalId, requested, Status.INVALID_REQUEST, "administrator_mismatch",
                    timestampEpochMillis, transactionId);
        }

        Optional<PortalDefinition> previous = state.portalDefinition(portalId);
        if (create && previous.isPresent()) {
            return denied(state, actorId, portalId, requested, Status.ALREADY_EXISTS, "already_exists",
                    timestampEpochMillis, transactionId);
        }
        if (!create && previous.isEmpty()) {
            return denied(state, actorId, portalId, requested, Status.NOT_FOUND, "not_found",
                    timestampEpochMillis, transactionId);
        }
        if (requested.isPresent()) {
            PortalDefinition definition = requested.orElseThrow();
            if (!endpointAvailable.test(definition.origin()) || !endpointAvailable.test(definition.destination())) {
                return denied(state, actorId, portalId, requested, Status.ENDPOINT_UNAVAILABLE,
                        "endpoint_unavailable", timestampEpochMillis, transactionId);
            }
            Identifier existingOrigin = state.portalAt(definition.origin()).orElse(null);
            if (existingOrigin != null && !existingOrigin.equals(portalId)) {
                return denied(state, actorId, portalId, requested, Status.ORIGIN_CONFLICT,
                        "origin_conflict", timestampEpochMillis, transactionId);
            }
            Status protectionStatus = validateProtection(state, portalId, definition, previous.isPresent());
            if (protectionStatus != Status.SUCCESS) {
                return denied(state, actorId, portalId, requested, protectionStatus,
                        protectionStatus.name().toLowerCase(java.util.Locale.ROOT), timestampEpochMillis, transactionId);
            }
        }

        Identifier auditAction = requested.isEmpty() ? DELETE : create ? CREATE : EDIT;
        state.commitPortalMutation(portalId, requested, audit(
                timestampEpochMillis, actorId, auditAction, portalId, previous, requested,
                normalizedReason, transactionId));
        return new MutationResult(Status.SUCCESS, transactionId, true);
    }

    private static Status validateProtection(
            PlatformSavedData state,
            Identifier portalId,
            PortalDefinition definition,
            boolean editing) {
        if (!editing && state.portalDefinitions().size() >= PortalState.MAX_DEFINITIONS) {
            return Status.LIMIT_EXCEEDED;
        }
        Identifier originId = PortalDefinition.originProtectionId(portalId);
        Identifier destinationId = PortalDefinition.destinationProtectionId(portalId);
        if (!editing && (state.protectedRegion(originId).isPresent()
                || state.protectedRegion(destinationId).isPresent())) {
            return Status.PROTECTION_CONFLICT;
        }
        Set<Identifier> replaced = editing ? Set.of(originId, destinationId) : Set.of();
        List<ProtectedRegion> regions = List.of(
                definition.protectedRegion(definition.origin()),
                definition.protectedRegion(definition.destination()));
        long retainedCount = state.protectedRegions().stream()
                .filter(entry -> !replaced.contains(entry.getKey())).count();
        long retainedArea = state.protectedRegions().stream()
                .filter(entry -> !replaced.contains(entry.getKey()))
                .mapToLong(entry -> entry.getValue().areaChunks()).sum();
        long requestedArea = regions.stream().mapToLong(ProtectedRegion::areaChunks).sum();
        if (retainedCount + regions.size() > PlatformSavedData.MAX_PROTECTED_REGIONS
                || retainedArea + requestedArea > PlatformSavedData.MAX_INDEXED_PROTECTED_CHUNKS) {
            return Status.LIMIT_EXCEEDED;
        }
        for (ProtectedRegion region : regions) {
            for (int chunkX = region.minChunkX(); chunkX <= region.maxChunkX(); chunkX++) {
                for (int chunkZ = region.minChunkZ(); chunkZ <= region.maxChunkZ(); chunkZ++) {
                    ClaimKey key = new ClaimKey(region.dimension(), chunkX, chunkZ);
                    if (state.claim(key).isPresent()) {
                        return Status.CLAIM_CONFLICT;
                    }
                    if (state.protectedRegionsAt(key).stream().anyMatch(regionId -> !replaced.contains(regionId))) {
                        return Status.PROTECTION_CONFLICT;
                    }
                }
            }
        }
        return Status.SUCCESS;
    }

    private static MutationResult denied(
            PlatformSavedData state,
            UUID actorId,
            Identifier portalId,
            Optional<PortalDefinition> requested,
            Status status,
            String reason,
            long timestampEpochMillis,
            UUID transactionId) {
        UUID evidenceId = transactionId == null || ZERO_UUID.equals(transactionId) ? UUID.randomUUID() : transactionId;
        boolean recorded = state.appendDeniedAudit(audit(
                timestampEpochMillis, actorId, DENIED, portalId, state.portalDefinition(portalId), requested,
                reason, evidenceId), DENIED_AUDIT_INTERVAL_MILLIS);
        return new MutationResult(status, transactionId, recorded);
    }

    private static AuditEntry audit(
            long timestampEpochMillis,
            UUID actorId,
            Identifier action,
            Identifier portalId,
            Optional<PortalDefinition> before,
            Optional<PortalDefinition> after,
            String reason,
            UUID transactionId) {
        PortalDefinition location = after.orElseGet(() -> before.orElse(null));
        return new AuditEntry(
                timestampEpochMillis,
                actorId,
                action,
                portalId.toString(),
                location == null ? Optional.empty() : Optional.of(location.origin().dimension().identifier()),
                location == null ? Optional.empty() : Optional.of(location.origin().position()),
                before.map(PortalDefinition::auditSummary).orElse("absent"),
                after.map(PortalDefinition::auditSummary).orElse("absent"),
                reason,
                transactionId);
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
        ENDPOINT_UNAVAILABLE,
        ORIGIN_CONFLICT,
        CLAIM_CONFLICT,
        PROTECTION_CONFLICT,
        LIMIT_EXCEEDED
    }

    public record MutationResult(Status status, UUID transactionId, boolean auditRecorded) {
    }
}
