package org.dldyou.rovenfall.administration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.waypoints.WaypointStyleAssets;
import org.dldyou.rovenfall.world.PortalDefinition;
import org.dldyou.rovenfall.world.ProtectedRegion;
import org.dldyou.rovenfall.world.WorldTopology;
import org.junit.jupiter.api.Test;

final class PlayerPortalMenuTest {
    private static final UUID ADMINISTRATOR = id(1);
    private static final UUID PLAYER = id(2);
    private static final Identifier PORTAL = Identifier.fromNamespaceAndPath("rovenfall", "test_route");

    @Test
    void mapsOnlyDeclaredListAndDetailActionsAndBoundsQueries() {
        assertEquals(PlayerPortalMenu.Action.SELECT,
                PlayerPortalMenu.actionAt(PlayerPortalMenu.Page.LIST, 9));
        assertEquals(PlayerPortalMenu.Action.SELECT,
                PlayerPortalMenu.actionAt(PlayerPortalMenu.Page.LIST, 44));
        assertEquals(PlayerPortalMenu.Action.BACK,
                PlayerPortalMenu.actionAt(PlayerPortalMenu.Page.LIST, 45));
        assertEquals(PlayerPortalMenu.Action.PREVIOUS,
                PlayerPortalMenu.actionAt(PlayerPortalMenu.Page.LIST, 48));
        assertEquals(PlayerPortalMenu.Action.CLEAR_NAVIGATION,
                PlayerPortalMenu.actionAt(PlayerPortalMenu.Page.LIST, 49));
        assertEquals(PlayerPortalMenu.Action.NEXT,
                PlayerPortalMenu.actionAt(PlayerPortalMenu.Page.LIST, 50));
        assertEquals(PlayerPortalMenu.Action.NAVIGATE,
                PlayerPortalMenu.actionAt(PlayerPortalMenu.Page.DETAIL, 49));
        assertEquals(PlayerPortalMenu.Action.TRAVEL,
                PlayerPortalMenu.actionAt(PlayerPortalMenu.Page.DETAIL, 50));
        assertEquals(PlayerPortalMenu.Action.NONE,
                PlayerPortalMenu.actionAt(PlayerPortalMenu.Page.DETAIL, 9));

        assertTrue(PlayerPortalMenu.validQuery(""));
        assertTrue(PlayerPortalMenu.validQuery("가".repeat(PlayerPortalView.MAX_QUERY_LENGTH)));
        assertFalse(PlayerPortalMenu.validQuery("x".repeat(PlayerPortalView.MAX_QUERY_LENGTH + 1)));
        assertFalse(PlayerPortalMenu.validQuery("bad\nquery"));
        assertFalse(PlayerPortalMenu.validQuery("bad\rquery"));
        assertFalse(PlayerPortalMenu.validQuery(null));
    }

    @Test
    void derivesPreviewAvailabilityFromServerEvidenceWithoutMutating() {
        PlatformSavedData state = new PlatformSavedData();
        PortalDefinition definition = definition(false);
        create(state, definition);
        int auditsBefore = state.auditCount();
        long now = 10_000L;

        PlayerPortalView.Row near = PlayerPortalView.create(
                state, WorldTopology.HUB, Vec3.atCenterOf(definition.origin().position()), "", 0)
                .entries().getFirst();
        assertEquals(PortalTravelService.Status.SUCCESS,
                PlayerPortalMenu.availability(state, PLAYER, near, now).status());

        PlayerPortalView.Row far = PlayerPortalView.create(
                state, WorldTopology.HUB, new Vec3(1_000, 64, 1_000), "", 0)
                .entries().getFirst();
        assertEquals(PortalTravelService.Status.TOO_FAR,
                PlayerPortalMenu.availability(state, PLAYER, far, now).status());

        PlayerPortalView.Row otherWorld = PlayerPortalView.create(
                state, WorldTopology.WILDERNESS, Vec3.ZERO, "", 0)
                .entries().getFirst();
        assertEquals(PortalTravelService.Status.WRONG_DIMENSION,
                PlayerPortalMenu.availability(state, PLAYER, otherWorld, now).status());

        state.recordPortalCombat(PLAYER, now);
        assertEquals(PortalTravelService.Status.COMBAT_LOCKED,
                PlayerPortalMenu.availability(state, PLAYER, near, now + 1).status());

        UUID otherPlayer = id(3);
        long cooldownUntil = now + 30_000L;
        assertTrue(state.reservePortalTravel(
                otherPlayer, PORTAL, cooldownUntil, id(30), now, definition.destination()).isPresent());
        PlayerPortalMenu.Availability cooldown =
                PlayerPortalMenu.availability(state, otherPlayer, near, now + 1);
        assertEquals(PortalTravelService.Status.COOLDOWN, cooldown.status());
        assertEquals(cooldownUntil, cooldown.retryAt());
        assertEquals(auditsBefore, state.auditCount());
    }

    @Test
    void usesASeparateStableNativeMarkerForPortalGuidance() {
        PortalDefinition.Endpoint origin =
                new PortalDefinition.Endpoint(WorldTopology.HUB, new BlockPos(40, 70, -17));
        var navigation = PlayerPortalMenu.navigationPacket(origin);

        assertEquals(PlayerPortalMenu.NAVIGATION_MARKER_ID,
                navigation.waypoint().id().left().orElseThrow());
        assertNotEquals(PlayerClaimMenu.NAVIGATION_MARKER_ID, PlayerPortalMenu.NAVIGATION_MARKER_ID);
        assertEquals(WaypointStyleAssets.BOWTIE, navigation.waypoint().icon().style);
        assertEquals(Optional.of(0x6CC4FF), navigation.waypoint().icon().color);

        var clear = PlayerPortalMenu.clearNavigationPacket();
        assertEquals(PlayerPortalMenu.NAVIGATION_MARKER_ID,
                clear.waypoint().id().left().orElseThrow());
    }

    @Test
    void rejectsASelectionWhenItsPortalProtectionChanges() {
        PlatformSavedData state = new PlatformSavedData();
        PortalDefinition definition = definition(false);
        create(state, definition);
        PlayerPortalView.Row row = PlayerPortalView.create(
                state, WorldTopology.HUB, Vec3.atCenterOf(definition.origin().position()), "", 0)
                .entries().getFirst();

        assertTrue(PlayerPortalMenu.selectionFresh(state, row));
        state.commitProtectedRegionMutation(
                PortalDefinition.originProtectionId(PORTAL),
                Optional.<ProtectedRegion>empty(),
                new AuditEntry(
                        2_000L,
                        ADMINISTRATOR,
                        Identifier.fromNamespaceAndPath("rovenfall", "test_protection_change"),
                        PORTAL.toString(),
                        Optional.empty(),
                        Optional.empty(),
                        "present",
                        "absent",
                        "test protection change",
                        id(40)));

        assertFalse(PlayerPortalMenu.selectionFresh(state, row));
        assertEquals(PortalTravelService.Status.PROTECTION_UNAVAILABLE,
                PlayerPortalMenu.availability(state, PLAYER, row, 2_001L).status());
    }

    private static void create(PlatformSavedData state, PortalDefinition definition) {
        assertEquals(PortalService.Status.SUCCESS, PortalService.create(
                state, ADMINISTRATOR, true, PORTAL, definition, ignored -> true,
                "portal menu test", 1_000, id(10)).status());
    }

    private static PortalDefinition definition(boolean allowCombat) {
        return new PortalDefinition(
                ADMINISTRATOR,
                new PortalDefinition.Endpoint(WorldTopology.HUB, new BlockPos(8, 64, 8)),
                new PortalDefinition.Endpoint(WorldTopology.WILDERNESS, new BlockPos(80, 70, 80)),
                0,
                30_000L,
                PortalDefinition.SafeArrivalPolicy.NEAREST_SAFE,
                allowCombat);
    }

    private static UUID id(long value) {
        return new UUID(0, value);
    }
}
