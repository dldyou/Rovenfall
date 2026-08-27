package org.dldyou.rovenfall.administration;

import java.time.Instant;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.dldyou.rovenfall.claims.ClaimConfig;
import org.dldyou.rovenfall.claims.ClaimKey;
import org.dldyou.rovenfall.claims.ClaimRole;
import org.dldyou.rovenfall.world.WorldTopology;

public final class ClaimProtectionHooks {
    private ClaimProtectionHooks() {
    }

    public static boolean environmentMayModify(ServerLevel level, BlockPos source, BlockPos target) {
        return environmentMayModify(
                level,
                ClaimKey.at(level.dimension(), source),
                ClaimKey.at(level.dimension(), target));
    }

    static boolean environmentMayModify(ServerLevel level, ClaimKey source, ClaimKey target) {
        var hub = level.getServer().overworld();
        return ClaimProtectionService.environmentMayModify(
                PlatformSavedData.get(level.getServer()),
                WorldTopology.HUB,
                hub.getRespawnData().pos(),
                ClaimConfig.protectedSpawnRadiusChunks(),
                source,
                target);
    }

    public static boolean systemMayModify(ServerLevel level, BlockPos target) {
        return systemMayModify(level, ClaimKey.at(level.dimension(), target));
    }

    static boolean systemMayModify(ServerLevel level, ClaimKey target) {
        var hub = level.getServer().overworld();
        return ClaimProtectionService.evaluate(
                PlatformSavedData.get(level.getServer()),
                AdministrationService.SYSTEM_ACTOR,
                false,
                WorldTopology.HUB,
                hub.getRespawnData().pos(),
                ClaimConfig.protectedSpawnRadiusChunks(),
                target,
                ClaimProtectionService.Action.BUILD).allowed();
    }

    public static void auditEnvironmentDenied(
            ServerLevel level,
            BlockPos target,
            ClaimProtectionService.Action action) {
        auditEnvironmentDenied(level, ClaimKey.at(level.dimension(), target), action);
    }

    static void auditEnvironmentDenied(
            ServerLevel level,
            ClaimKey key,
            ClaimProtectionService.Action action) {
        PlatformSavedData state = PlatformSavedData.get(level.getServer());
        ClaimProtectionService.auditDenied(
                state,
                AdministrationService.SYSTEM_ACTOR,
                key,
                action,
                new ClaimProtectionService.Decision(
                        false,
                        ClaimProtectionService.Reason.ENVIRONMENT_BOUNDARY,
                        ClaimRole.VISITOR,
                        state.claim(key)),
                Instant.now().toEpochMilli());
    }
}
