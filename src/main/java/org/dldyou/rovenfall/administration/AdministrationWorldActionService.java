package org.dldyou.rovenfall.administration;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import org.dldyou.rovenfall.Rovenfall;
import org.dldyou.rovenfall.claims.Claim;
import org.dldyou.rovenfall.claims.ClaimKey;
import org.dldyou.rovenfall.claims.ClaimRole;
import org.dldyou.rovenfall.claims.ClaimSettings;
import org.dldyou.rovenfall.world.PortalDefinition;
import org.dldyou.rovenfall.world.ProtectedRegion;

/** Executes snapshot-bound world administration confirmations through the owning domain services. */
final class AdministrationWorldActionService {
    private static final long STALE_AUDIT_INTERVAL_MILLIS = 1_000L;

    private AdministrationWorldActionService() {
    }

    static Result execute(ServerPlayer actor, PendingAction action) {
        if (actor == null || action == null || actor.level().getServer() == null
                || !actor.level().getServer().isSameThread()) {
            return new Result(Status.FAILED, "invalid_request", action == null ? null : action.transactionId());
        }
        var server = actor.level().getServer();
        PlatformSavedData state = PlatformSavedData.get(server);
        AdminRole role = AdministrationControlCenterMenu.resolveRole(actor).orElse(null);
        if (!allowed(role, action)) {
            auditRejected(state, actor.getUUID(), action, "unauthorized");
            return new Result(Status.UNAUTHORIZED, "unauthorized", action.transactionId());
        }
        if (!fresh(state, action)) {
            auditRejected(state, actor.getUUID(), action, "stale_confirmation");
            return new Result(Status.STALE_CONFIRMATION, "stale_confirmation", action.transactionId());
        }

        boolean override = state.roleOf(actor.getUUID()).isEmpty();
        long now = Instant.now().toEpochMilli();
        if (action instanceof ClaimRoleAction value) {
            ClaimManagementService.Result result = value.remove()
                    ? ClaimManagementService.removeRole(
                            state, actor.getUUID(), override, value.key(), value.playerId(), value.reason(), now,
                            value.transactionId())
                    : ClaimManagementService.setRole(
                            state, actor.getUUID(), override, value.key(), value.playerId(), value.role(),
                            value.reason(), now, value.transactionId());
            return fromClaim(result);
        }
        if (action instanceof ClaimSettingsAction value) {
            return fromClaim(ClaimManagementService.setSettings(
                    state, actor.getUUID(), override, value.key(), value.settings(), value.reason(), now,
                    value.transactionId()));
        }
        if (action instanceof ClaimReclaimAction value) {
            return fromClaim(ClaimManagementService.reclaim(
                    state, actor.getUUID(), override, value.key(), value.reason(), now, value.transactionId()));
        }
        if (action instanceof RegionCreateAction value) {
            return fromRegion(ProtectedRegionService.create(
                    state, actor.getUUID(), override, value.regionId(), value.region(), value.reason(), now,
                    value.transactionId()));
        }
        if (action instanceof RegionEditAction value) {
            return fromRegion(ProtectedRegionService.edit(
                    state, actor.getUUID(), override, value.regionId(), value.region(), value.reason(), now,
                    value.transactionId()));
        }
        if (action instanceof RegionDeleteAction value) {
            return fromRegion(ProtectedRegionService.delete(
                    state, actor.getUUID(), override, value.regionId(), value.reason(), now,
                    value.transactionId()));
        }

        Predicate<PortalDefinition.Endpoint> endpointAvailable = endpoint -> {
            var level = server.getLevel(endpoint.dimension());
            return level != null && level.isInWorldBounds(endpoint.position())
                    && level.getWorldBorder().isWithinBounds(endpoint.position());
        };
        if (action instanceof PortalCreateAction value) {
            return fromPortal(PortalService.create(
                    state, actor.getUUID(), override, value.portalId(), value.definition(), endpointAvailable,
                    value.reason(), now, value.transactionId()));
        }
        if (action instanceof PortalEditAction value) {
            return fromPortal(PortalService.edit(
                    state, actor.getUUID(), override, value.portalId(), value.definition(), endpointAvailable,
                    value.reason(), now, value.transactionId()));
        }
        if (action instanceof PortalDeleteAction value) {
            return fromPortal(PortalService.delete(
                    state, actor.getUUID(), override, value.portalId(), value.reason(), now,
                    value.transactionId()));
        }
        if (action instanceof WildernessWarnAction value) {
            WildernessResetService.Result result = WildernessResetService.warn(
                    state, actor.getUUID(), override, value.reason(), now, value.transactionId());
            if (result.status() == WildernessResetService.Status.SUCCESS) {
                server.getPlayerList().broadcastSystemMessage(
                        Component.translatable("wilderness.rovenfall.reset.warning"), false);
            }
            return fromWilderness(result);
        }
        if (action instanceof WildernessResetAction value) {
            WildernessResetService.Result result = WildernessResetService.reset(
                    server, actor.getUUID(), override, value.warningId(), value.reason(), now,
                    value.transactionId());
            if (result.status() == WildernessResetService.Status.SUCCESS) {
                server.getPlayerList().broadcastSystemMessage(
                        Component.translatable("wilderness.rovenfall.reset.shutdown"), false);
            }
            return fromWilderness(result);
        }
        if (action instanceof WildernessRestoreAction value) {
            WildernessResetService.Result result = WildernessResetService.restore(
                    server, actor.getUUID(), override, value.snapshotId(), value.reason(), now,
                    value.transactionId());
            if (result.status() == WildernessResetService.Status.SUCCESS) {
                server.getPlayerList().broadcastSystemMessage(
                        Component.translatable("wilderness.rovenfall.restore.shutdown"), false);
            }
            return fromWilderness(result);
        }
        return new Result(Status.FAILED, "unsupported_action", action.transactionId());
    }

    static boolean fresh(PlatformSavedData state, PendingAction action) {
        if (state == null || action == null) {
            return false;
        }
        if (action instanceof ClaimAction value) {
            return state.claim(value.key()).equals(value.expectedClaim());
        }
        if (action instanceof RegionCreateAction value) {
            return state.protectedRegion(value.regionId()).isEmpty();
        }
        if (action instanceof RegionAction value) {
            return state.protectedRegion(value.regionId()).equals(value.expectedRegion());
        }
        if (action instanceof PortalCreateAction value) {
            return state.portalDefinition(value.portalId()).isEmpty();
        }
        if (action instanceof PortalAction value) {
            return state.portalDefinition(value.portalId()).equals(value.expectedPortal());
        }
        if (action instanceof WildernessAction value) {
            return state.wildernessResetState().equals(value.expectedState());
        }
        return false;
    }

    static boolean allowed(AdminRole role, PendingAction action) {
        if (role == null || action == null) {
            return false;
        }
        if (action instanceof ClaimAction) {
            return role == AdminRole.MODERATOR || role == AdminRole.OWNER;
        }
        if (action instanceof RegionCreateAction || action instanceof RegionAction || action instanceof WildernessAction) {
            return role == AdminRole.OWNER;
        }
        if (action instanceof PortalCreateAction || action instanceof PortalAction) {
            return role == AdminRole.CONTENT_MANAGER || role == AdminRole.OWNER;
        }
        return false;
    }

    private static Result fromClaim(ClaimManagementService.Result result) {
        return switch (result.status()) {
            case SUCCESS -> new Result(Status.SUCCESS, "success", result.transactionId());
            case DUPLICATE_TRANSACTION -> new Result(Status.DUPLICATE, "duplicate_transaction", result.transactionId());
            case NO_CHANGE -> new Result(Status.NO_CHANGE, "no_change", result.transactionId());
            case UNAUTHORIZED -> new Result(Status.UNAUTHORIZED, "unauthorized", result.transactionId());
            default -> new Result(Status.FAILED, result.status().name().toLowerCase(java.util.Locale.ROOT),
                    result.transactionId());
        };
    }

    private static Result fromRegion(ProtectedRegionService.MutationResult result) {
        return switch (result.status()) {
            case SUCCESS -> new Result(Status.SUCCESS, "success", result.transactionId());
            case DUPLICATE_TRANSACTION -> new Result(Status.DUPLICATE, "duplicate_transaction", result.transactionId());
            case UNAUTHORIZED -> new Result(Status.UNAUTHORIZED, "unauthorized", result.transactionId());
            default -> new Result(Status.FAILED, result.status().name().toLowerCase(java.util.Locale.ROOT),
                    result.transactionId());
        };
    }

    private static Result fromPortal(PortalService.MutationResult result) {
        return switch (result.status()) {
            case SUCCESS -> new Result(Status.SUCCESS, "success", result.transactionId());
            case DUPLICATE_TRANSACTION -> new Result(Status.DUPLICATE, "duplicate_transaction", result.transactionId());
            case UNAUTHORIZED -> new Result(Status.UNAUTHORIZED, "unauthorized", result.transactionId());
            default -> new Result(Status.FAILED, result.status().name().toLowerCase(java.util.Locale.ROOT),
                    result.transactionId());
        };
    }

    private static Result fromWilderness(WildernessResetService.Result result) {
        return switch (result.status()) {
            case SUCCESS -> new Result(Status.SUCCESS, "success", result.transactionId());
            case DUPLICATE_TRANSACTION -> new Result(Status.DUPLICATE, "duplicate_transaction", result.transactionId());
            case UNAUTHORIZED -> new Result(Status.UNAUTHORIZED, "unauthorized", result.transactionId());
            default -> new Result(Status.FAILED, result.status().name().toLowerCase(java.util.Locale.ROOT),
                    result.transactionId());
        };
    }

    private static void auditRejected(
            PlatformSavedData state, UUID actorId, PendingAction action, String reason) {
        if (!state.isWritable()) {
            return;
        }
        long now = Instant.now().toEpochMilli();
        state.appendDeniedAudit(new AuditEntry(
                now, actorId, Identifier.fromNamespaceAndPath(Rovenfall.MOD_ID, "admin_gui_" + reason + "_denied"),
                action.target(), Optional.empty(), Optional.empty(), "unchanged", "unchanged",
                reason, action.transactionId()), STALE_AUDIT_INTERVAL_MILLIS);
    }

    sealed interface PendingAction permits ClaimAction, RegionCreateAction, RegionAction,
            PortalCreateAction, PortalAction, WildernessAction {
        UUID transactionId();

        String reason();

        String target();
    }

    sealed interface ClaimAction extends PendingAction permits ClaimRoleAction, ClaimSettingsAction,
            ClaimReclaimAction {
        ClaimKey key();

        Optional<Claim> expectedClaim();

        @Override
        default String target() {
            return key().auditTarget();
        }
    }

    record ClaimRoleAction(
            UUID transactionId,
            ClaimKey key,
            Optional<Claim> expectedClaim,
            UUID playerId,
            ClaimRole role,
            boolean remove,
            String reason) implements ClaimAction {
        ClaimRoleAction {
            expectedClaim = expectedClaim == null ? Optional.empty() : expectedClaim;
        }
    }

    record ClaimSettingsAction(
            UUID transactionId,
            ClaimKey key,
            Optional<Claim> expectedClaim,
            ClaimSettings settings,
            String reason) implements ClaimAction {
        ClaimSettingsAction {
            expectedClaim = expectedClaim == null ? Optional.empty() : expectedClaim;
        }
    }

    record ClaimReclaimAction(
            UUID transactionId,
            ClaimKey key,
            Optional<Claim> expectedClaim,
            String reason) implements ClaimAction {
        ClaimReclaimAction {
            expectedClaim = expectedClaim == null ? Optional.empty() : expectedClaim;
        }
    }

    record RegionCreateAction(
            UUID transactionId,
            Identifier regionId,
            ProtectedRegion region,
            String reason) implements PendingAction {
        @Override
        public String target() {
            return regionId.toString();
        }
    }

    sealed interface RegionAction extends PendingAction permits RegionEditAction, RegionDeleteAction {
        Identifier regionId();

        Optional<ProtectedRegion> expectedRegion();

        @Override
        default String target() {
            return regionId().toString();
        }
    }

    record RegionEditAction(
            UUID transactionId,
            Identifier regionId,
            Optional<ProtectedRegion> expectedRegion,
            ProtectedRegion region,
            String reason) implements RegionAction {
        RegionEditAction {
            expectedRegion = expectedRegion == null ? Optional.empty() : expectedRegion;
        }
    }

    record RegionDeleteAction(
            UUID transactionId,
            Identifier regionId,
            Optional<ProtectedRegion> expectedRegion,
            String reason) implements RegionAction {
        RegionDeleteAction {
            expectedRegion = expectedRegion == null ? Optional.empty() : expectedRegion;
        }
    }

    record PortalCreateAction(
            UUID transactionId,
            Identifier portalId,
            PortalDefinition definition,
            String reason) implements PendingAction {
        @Override
        public String target() {
            return portalId.toString();
        }
    }

    sealed interface PortalAction extends PendingAction permits PortalEditAction, PortalDeleteAction {
        Identifier portalId();

        Optional<PortalDefinition> expectedPortal();

        @Override
        default String target() {
            return portalId().toString();
        }
    }

    record PortalEditAction(
            UUID transactionId,
            Identifier portalId,
            Optional<PortalDefinition> expectedPortal,
            PortalDefinition definition,
            String reason) implements PortalAction {
        PortalEditAction {
            expectedPortal = expectedPortal == null ? Optional.empty() : expectedPortal;
        }
    }

    record PortalDeleteAction(
            UUID transactionId,
            Identifier portalId,
            Optional<PortalDefinition> expectedPortal,
            String reason) implements PortalAction {
        PortalDeleteAction {
            expectedPortal = expectedPortal == null ? Optional.empty() : expectedPortal;
        }
    }

    sealed interface WildernessAction extends PendingAction permits WildernessWarnAction, WildernessResetAction,
            WildernessRestoreAction {
        WildernessResetState expectedState();

        @Override
        default String target() {
            return "wilderness";
        }
    }

    record WildernessWarnAction(
            UUID transactionId,
            WildernessResetState expectedState,
            String reason) implements WildernessAction {
    }

    record WildernessResetAction(
            UUID transactionId,
            WildernessResetState expectedState,
            UUID warningId,
            String reason) implements WildernessAction {
    }

    record WildernessRestoreAction(
            UUID transactionId,
            WildernessResetState expectedState,
            UUID snapshotId,
            String reason) implements WildernessAction {
    }

    enum Status {
        SUCCESS,
        DUPLICATE,
        NO_CHANGE,
        STALE_CONFIRMATION,
        UNAUTHORIZED,
        FAILED
    }

    record Result(Status status, String detail, UUID transactionId) {
        boolean succeeded() {
            return status == Status.SUCCESS || status == Status.DUPLICATE || status == Status.NO_CHANGE;
        }
    }
}
