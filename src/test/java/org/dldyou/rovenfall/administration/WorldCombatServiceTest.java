package org.dldyou.rovenfall.administration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.dldyou.rovenfall.PersistenceTestHarness;
import org.dldyou.rovenfall.worlds.WorldConfig;
import org.junit.jupiter.api.Test;

final class WorldCombatServiceTest {
    private static final ResourceKey<Level> OTHER_DIMENSION = ResourceKey.create(
            Registries.DIMENSION, Identifier.fromNamespaceAndPath("test", "other"));

    @Test
    void appliesSafeHubAndDangerousWildernessDefaults() {
        var hub = decision(Level.OVERWORLD, WorldConfig.DEFAULT_HUB_PVP_ENABLED,
                WorldConfig.DEFAULT_WILDERNESS_PVP_ENABLED);
        var wilderness = decision(WorldCombatService.WILDERNESS_DIMENSION,
                WorldConfig.DEFAULT_HUB_PVP_ENABLED, WorldConfig.DEFAULT_WILDERNESS_PVP_ENABLED);
        var unmanaged = decision(OTHER_DIMENSION, WorldConfig.DEFAULT_HUB_PVP_ENABLED,
                WorldConfig.DEFAULT_WILDERNESS_PVP_ENABLED);

        assertFalse(hub.allowed());
        assertEquals(WorldCombatService.Reason.HUB_PVP_DISABLED, hub.reason());
        assertTrue(wilderness.allowed());
        assertEquals(WorldCombatService.Reason.PVP_ENABLED, wilderness.reason());
        assertTrue(unmanaged.allowed());
        assertEquals(WorldCombatService.Reason.UNMANAGED_DIMENSION, unmanaged.reason());
    }

    @Test
    void honorsPerWorldOverrides() {
        assertTrue(decision(Level.OVERWORLD, true, false).allowed());
        var wilderness = decision(WorldCombatService.WILDERNESS_DIMENSION, true, false);
        assertFalse(wilderness.allowed());
        assertEquals(WorldCombatService.Reason.WILDERNESS_PVP_DISABLED, wilderness.reason());
    }

    @Test
    void invalidWorldTopologyFailsClosed() {
        var decision = WorldCombatService.evaluate(
                Level.OVERWORLD, Level.OVERWORLD, Level.OVERWORLD, true, true);
        assertFalse(decision.allowed());
        assertEquals(WorldCombatService.Reason.INVALID_REQUEST, decision.reason());
    }

    @Test
    void deniedPvpAuditIsRateLimitedAndPersistent() {
        PlatformSavedData state = new PlatformSavedData();
        UUID attacker = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID target = UUID.fromString("00000000-0000-0000-0000-000000000002");
        BlockPos position = new BlockPos(12, 70, -4);
        var denied = decision(Level.OVERWORLD, false, true);

        assertTrue(WorldCombatService.auditDenied(
                state, attacker, target, Level.OVERWORLD, position, denied, 10_000L));
        assertFalse(WorldCombatService.auditDenied(
                state, attacker, target, Level.OVERWORLD, position, denied, 10_999L));
        assertEquals(1, state.auditCount());

        PlatformSavedData restored = PersistenceTestHarness.roundTrip(PlatformSavedData.CODEC, state);
        AuditEntry audit = restored.auditPage(0, 10).entries().getFirst();
        assertEquals(Identifier.fromNamespaceAndPath("rovenfall", "pvp_denied"), audit.actionType());
        assertEquals(target.toString(), audit.target());
        assertEquals("hub_pvp_disabled", audit.reason());
        assertEquals(Level.OVERWORLD.identifier(), audit.dimension().orElseThrow());
        assertEquals(position, audit.position().orElseThrow());
    }

    private static WorldCombatService.Decision decision(
            ResourceKey<Level> currentDimension,
            boolean hubPvpEnabled,
            boolean wildernessPvpEnabled) {
        return WorldCombatService.evaluate(
                Level.OVERWORLD,
                WorldCombatService.WILDERNESS_DIMENSION,
                currentDimension,
                hubPvpEnabled,
                wildernessPvpEnabled);
    }
}
