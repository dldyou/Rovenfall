package org.dldyou.rovenfall.administration;

import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.dldyou.rovenfall.Rovenfall;

public final class WorldCombatService {
    public static final ResourceKey<Level> WILDERNESS_DIMENSION = ResourceKey.create(
            Registries.DIMENSION,
            Identifier.fromNamespaceAndPath(Rovenfall.MOD_ID, "wilderness"));
    private static final long DENIED_AUDIT_INTERVAL_MILLIS = 1_000L;

    private WorldCombatService() {
    }

    public static Decision evaluate(
            ResourceKey<Level> hubDimension,
            ResourceKey<Level> wildernessDimension,
            ResourceKey<Level> currentDimension,
            boolean hubPvpEnabled,
            boolean wildernessPvpEnabled) {
        if (hubDimension == null || wildernessDimension == null || currentDimension == null
                || hubDimension.equals(wildernessDimension)) {
            return new Decision(false, Reason.INVALID_REQUEST);
        }
        if (currentDimension.equals(hubDimension)) {
            return hubPvpEnabled
                    ? new Decision(true, Reason.PVP_ENABLED)
                    : new Decision(false, Reason.HUB_PVP_DISABLED);
        }
        if (currentDimension.equals(wildernessDimension)) {
            return wildernessPvpEnabled
                    ? new Decision(true, Reason.PVP_ENABLED)
                    : new Decision(false, Reason.WILDERNESS_PVP_DISABLED);
        }
        return new Decision(true, Reason.UNMANAGED_DIMENSION);
    }

    public static boolean auditDenied(
            PlatformSavedData state,
            UUID actorId,
            UUID targetId,
            ResourceKey<Level> dimension,
            BlockPos position,
            Decision decision,
            long timestampEpochMillis) {
        if (state == null || actorId == null || targetId == null || actorId.equals(targetId)
                || dimension == null || position == null || decision == null
                || decision.allowed() || timestampEpochMillis < 0) {
            return false;
        }
        String evidence = "reason=" + decision.reason().id + ";target=" + targetId;
        return state.appendDeniedAudit(new AuditEntry(
                timestampEpochMillis,
                actorId,
                Identifier.fromNamespaceAndPath(Rovenfall.MOD_ID, "pvp_denied"),
                targetId.toString(),
                Optional.of(dimension.identifier()),
                Optional.of(position.immutable()),
                evidence,
                evidence,
                decision.reason().id,
                UUID.randomUUID()), DENIED_AUDIT_INTERVAL_MILLIS);
    }

    public enum Reason {
        INVALID_REQUEST("invalid_request"),
        HUB_PVP_DISABLED("hub_pvp_disabled"),
        WILDERNESS_PVP_DISABLED("wilderness_pvp_disabled"),
        PVP_ENABLED("pvp_enabled"),
        UNMANAGED_DIMENSION("unmanaged_dimension");

        private final String id;

        Reason(String id) {
            this.id = id;
        }
    }

    public record Decision(boolean allowed, Reason reason) {
    }
}
