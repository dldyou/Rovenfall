package org.dldyou.rovenfall.administration;

import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.dldyou.rovenfall.Rovenfall;
import org.dldyou.rovenfall.claims.Claim;
import org.dldyou.rovenfall.claims.ClaimKey;
import org.dldyou.rovenfall.claims.ClaimRegionPolicy;
import org.dldyou.rovenfall.claims.ClaimRole;

public final class ClaimProtectionService {
    private static final long DENIED_AUDIT_INTERVAL_MILLIS = 1_000L;

    private ClaimProtectionService() {
    }

    public static Decision evaluate(
            PlatformSavedData state,
            UUID actorId,
            boolean administratorOverride,
            ResourceKey<Level> hubDimension,
            BlockPos hubSpawn,
            int protectedSpawnRadiusChunks,
            ClaimKey key,
            Action action) {
        if (state == null || actorId == null || hubDimension == null || hubSpawn == null || key == null
                || action == null || protectedSpawnRadiusChunks < 0 || protectedSpawnRadiusChunks > 64) {
            return new Decision(false, Reason.INVALID_REQUEST, ClaimRole.VISITOR, Optional.empty());
        }
        Optional<Claim> retained = state.claim(key);
        ClaimRole role = retained.map(claim -> claim.roleOf(actorId)).orElse(ClaimRole.VISITOR);
        if (administratorOverride || hasClaimAdministratorRole(state, actorId)) {
            return new Decision(true, Reason.ADMINISTRATOR_OVERRIDE, role, retained);
        }
        if (state.isProtectedRegion(key)) {
            return action == Action.ENTRY
                    ? new Decision(true, Reason.PROTECTED_PUBLIC_ENTRY, role, retained)
                    : new Decision(false, Reason.PROTECTED_REGION, role, retained);
        }
        if (!key.dimension().equals(hubDimension)) {
            return new Decision(true, Reason.OUTSIDE_HUB, ClaimRole.VISITOR, Optional.empty());
        }
        if (ClaimRegionPolicy.isProtectedHubRegion(
                key, hubDimension, hubSpawn, protectedSpawnRadiusChunks)) {
            return action == Action.ENTRY
                    ? new Decision(true, Reason.PROTECTED_PUBLIC_ENTRY, role, retained)
                    : new Decision(false, Reason.PROTECTED_REGION, role, retained);
        }
        if (retained.isEmpty()) {
            return action == Action.BUILD
                    ? new Decision(false, Reason.UNCLAIMED_HUB_BUILD, role, Optional.empty())
                    : new Decision(true, Reason.UNCLAIMED_HUB_PUBLIC_USE, role, Optional.empty());
        }
        Claim claim = retained.orElseThrow();
        return switch (action) {
            case BUILD -> role.atLeast(ClaimRole.BUILDER)
                    ? new Decision(true, Reason.ROLE_ALLOWED, role, retained)
                    : new Decision(false, Reason.ROLE_REQUIRED, role, retained);
            case INTERACT, ENTITY -> role.atLeast(ClaimRole.USER) || claim.settings().publicInteractions()
                    ? new Decision(true,
                            role.atLeast(ClaimRole.USER) ? Reason.ROLE_ALLOWED : Reason.PUBLIC_INTERACTION,
                            role, retained)
                    : new Decision(false, Reason.ROLE_REQUIRED, role, retained);
            case ENTRY -> !claim.settings().entryRestricted() || role.atLeast(ClaimRole.USER)
                    ? new Decision(true,
                            claim.settings().entryRestricted() ? Reason.ROLE_ALLOWED : Reason.PUBLIC_ENTRY,
                            role, retained)
                    : new Decision(false, Reason.ENTRY_RESTRICTED, role, retained);
        };
    }

    public static boolean environmentMayModify(
            PlatformSavedData state,
            ResourceKey<Level> hubDimension,
            BlockPos hubSpawn,
            int protectedSpawnRadiusChunks,
            ClaimKey source,
            ClaimKey target) {
        if (state == null || hubDimension == null || hubSpawn == null || source == null || target == null
                || protectedSpawnRadiusChunks < 0 || protectedSpawnRadiusChunks > 64) {
            return false;
        }
        if (state.isProtectedRegion(target)) {
            return false;
        }
        if (!target.dimension().equals(hubDimension)) {
            return true;
        }
        if (ClaimRegionPolicy.isProtectedHubRegion(
                target, hubDimension, hubSpawn, protectedSpawnRadiusChunks)) {
            return false;
        }
        Claim targetClaim = state.claim(target).orElse(null);
        if (targetClaim == null) {
            return false;
        }
        Claim sourceClaim = state.claim(source).orElse(null);
        return sourceClaim != null && sourceClaim.ownerId().equals(targetClaim.ownerId());
    }

    public static boolean auditDenied(
            PlatformSavedData state,
            UUID actorId,
            ClaimKey key,
            Action action,
            Decision decision,
            long timestampEpochMillis) {
        if (state == null || actorId == null || key == null || action == null || decision == null
                || timestampEpochMillis < 0 || decision.allowed()) {
            return false;
        }
        String evidence = "action=" + action.id + ";reason=" + decision.reason().id
                + ";role=" + decision.role().getSerializedName();
        return state.appendDeniedAudit(new AuditEntry(
                timestampEpochMillis,
                actorId,
                Identifier.fromNamespaceAndPath(Rovenfall.MOD_ID, "claim_action_denied"),
                key.auditTarget(),
                Optional.of(key.dimension().identifier()),
                Optional.of(key.auditPosition()),
                evidence,
                evidence,
                decision.reason().id,
                UUID.randomUUID()), DENIED_AUDIT_INTERVAL_MILLIS);
    }

    private static boolean hasClaimAdministratorRole(PlatformSavedData state, UUID actorId) {
        AdminRole role = state.roleOf(actorId).orElse(null);
        return role == AdminRole.MODERATOR || role == AdminRole.OWNER;
    }

    public enum Action {
        BUILD("build"),
        INTERACT("interact"),
        ENTITY("entity"),
        ENTRY("entry");

        private final String id;

        Action(String id) {
            this.id = id;
        }

        public String denialTranslationKey() {
            return "message.rovenfall.claim.denied." + id;
        }
    }

    public enum Reason {
        INVALID_REQUEST("invalid_request"),
        OUTSIDE_HUB("outside_hub"),
        ADMINISTRATOR_OVERRIDE("administrator_override"),
        PROTECTED_PUBLIC_ENTRY("protected_public_entry"),
        PROTECTED_REGION("protected_region"),
        UNCLAIMED_HUB_BUILD("unclaimed_hub_build"),
        UNCLAIMED_HUB_PUBLIC_USE("unclaimed_hub_public_use"),
        ROLE_ALLOWED("role_allowed"),
        ROLE_REQUIRED("role_required"),
        PUBLIC_INTERACTION("public_interaction"),
        PUBLIC_ENTRY("public_entry"),
        ENTRY_RESTRICTED("entry_restricted"),
        ENVIRONMENT_BOUNDARY("environment_boundary");

        private final String id;

        Reason(String id) {
            this.id = id;
        }
    }

    public record Decision(
            boolean allowed,
            Reason reason,
            ClaimRole role,
            Optional<Claim> claim) {
        public Decision {
            claim = claim == null ? Optional.empty() : claim;
        }
    }
}
