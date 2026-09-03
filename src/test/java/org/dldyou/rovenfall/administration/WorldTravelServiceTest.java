package org.dldyou.rovenfall.administration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.dldyou.rovenfall.PersistenceTestHarness;
import org.junit.jupiter.api.Test;

final class WorldTravelServiceTest {
    private static final ResourceKey<Level> OTHER_DIMENSION = ResourceKey.create(
            Registries.DIMENSION, Identifier.fromNamespaceAndPath("test", "other"));

    @Test
    void routesOnlyBetweenHubAndWilderness() {
        assertEquals(
                WorldCombatService.WILDERNESS_DIMENSION,
                WorldTravelService.destinationFor(
                        Level.OVERWORLD, Level.OVERWORLD, WorldCombatService.WILDERNESS_DIMENSION)
                        .orElseThrow());
        assertEquals(
                Level.OVERWORLD,
                WorldTravelService.destinationFor(
                        WorldCombatService.WILDERNESS_DIMENSION,
                        Level.OVERWORLD,
                        WorldCombatService.WILDERNESS_DIMENSION).orElseThrow());
        assertTrue(WorldTravelService.destinationFor(
                OTHER_DIMENSION, Level.OVERWORLD, WorldCombatService.WILDERNESS_DIMENSION).isEmpty());
        assertTrue(WorldTravelService.destinationFor(
                Level.OVERWORLD, Level.OVERWORLD, Level.OVERWORLD).isEmpty());
    }

    @Test
    void portalRingDistanceUsesHorizontalPlayerPosition() {
        BlockPos center = new BlockPos(10, 64, -10);
        assertTrue(WorldTravelService.insideRing(new Vec3(10.5, 300, -9.5), center, 8));
        assertTrue(WorldTravelService.insideRing(new Vec3(18.5, -60, -9.5), center, 8));
        assertFalse(WorldTravelService.insideRing(new Vec3(18.51, 64, -9.5), center, 8));
        assertFalse(WorldTravelService.insideRing(null, center, 8));
    }

    @Test
    void successfulTravelAuditSurvivesPersistence() {
        PlatformSavedData state = new PlatformSavedData();
        var actor = java.util.UUID.fromString("00000000-0000-0000-0000-000000000010");
        var transaction = java.util.UUID.fromString("00000000-0000-0000-0000-000000000011");
        BlockPos origin = new BlockPos(1, 70, 2);
        BlockPos destination = new BlockPos(4, 80, 5);

        assertTrue(WorldTravelService.recordSuccessfulTransit(
                state,
                actor,
                Level.OVERWORLD,
                origin,
                WorldCombatService.WILDERNESS_DIMENSION,
                destination,
                1_000L,
                transaction));

        PlatformSavedData restored = PersistenceTestHarness.roundTrip(PlatformSavedData.CODEC, state);
        AuditEntry audit = restored.auditPage(0, 1).entries().getFirst();
        assertEquals(Identifier.fromNamespaceAndPath("rovenfall", "portal_travel"), audit.actionType());
        assertEquals("rovenfall:wilderness", audit.target());
        assertEquals(Level.OVERWORLD.identifier(), audit.dimension().orElseThrow());
        assertEquals(origin, audit.position().orElseThrow());
        assertEquals("spawn_ring", audit.reason());
        assertEquals(transaction, audit.transactionId());
    }

    @Test
    void namedPortalAuditUsesStableIdAndAllowsSameDimensionRoutes() {
        PlatformSavedData state = new PlatformSavedData();
        var actor = java.util.UUID.fromString("00000000-0000-0000-0000-000000000020");
        var transaction = java.util.UUID.fromString("00000000-0000-0000-0000-000000000021");
        Identifier portalId = Identifier.fromNamespaceAndPath("rovenfall", "market_gate");

        assertTrue(WorldTravelService.recordSuccessfulTransit(
                state,
                actor,
                portalId,
                Level.OVERWORLD,
                new BlockPos(10, 70, 10),
                Level.OVERWORLD,
                new BlockPos(100, 70, 100),
                2_000L,
                transaction));

        AuditEntry audit = state.auditPage(0, 1).entries().getFirst();
        assertEquals(portalId.toString(), audit.target());
        assertEquals("portal", audit.reason());
        assertEquals(transaction, audit.transactionId());
    }
}
