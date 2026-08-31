package org.dldyou.rovenfall.administration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.dldyou.rovenfall.world.PortalDefinition;
import org.dldyou.rovenfall.world.WorldTopology;
import org.junit.jupiter.api.Test;

final class PlayerPortalViewTest {
    private static final UUID ADMINISTRATOR = id(1);

    @Test
    void projectsNaturalRouteDistanceAndUseStatusWithoutMutation() {
        PlatformSavedData state = new PlatformSavedData();
        PortalDefinition definition = definition(
                WorldTopology.HUB, new BlockPos(8, 64, 8),
                WorldTopology.WILDERNESS, new BlockPos(120, 70, -40));
        create(state, portalId("hub_to_wilderness"), definition, 10);
        int auditsBefore = state.auditCount();

        PlayerPortalView view = PlayerPortalView.create(
                state, WorldTopology.HUB, new Vec3(8.5, 64.5, 8.5), "", 0);
        PlayerPortalView.Row row = view.entries().getFirst();

        assertEquals(1, view.totalEntries());
        assertEquals(1, view.totalPages());
        assertEquals(definition.origin(), row.origin());
        assertEquals(definition.destination(), row.destination());
        assertEquals(0.0D, row.distanceBlocks().orElseThrow());
        assertTrue(row.currentDimension());
        assertTrue(row.withinUseDistance());
        assertTrue(row.fresh(state));
        assertEquals(auditsBefore, state.auditCount());
    }

    @Test
    void searchesNaturalEndpointsAndRejectsMalformedQueries() {
        PlatformSavedData state = new PlatformSavedData();
        create(state, portalId("first"), definition(
                WorldTopology.HUB, new BlockPos(8, 64, 8),
                WorldTopology.WILDERNESS, new BlockPos(100, 70, 100)), 20);
        create(state, portalId("second"), definition(
                WorldTopology.HUB, new BlockPos(40, 64, 8),
                Level.NETHER, new BlockPos(-200, 70, 300)), 21);

        PlayerPortalView wilderness = PlayerPortalView.create(
                state, WorldTopology.HUB, Vec3.ZERO, "  야생 ", 0);
        PlayerPortalView coordinate = PlayerPortalView.create(
                state, WorldTopology.HUB, Vec3.ZERO, "-200 70 300", 0);

        assertEquals("야생", wilderness.query());
        assertEquals(List.of(portalId("first")), portalIds(wilderness));
        assertEquals(List.of(portalId("second")), portalIds(coordinate));
        assertThrows(IllegalArgumentException.class, () -> PlayerPortalView.create(
                state, WorldTopology.HUB, Vec3.ZERO, "bad\nquery", 0));
        assertThrows(IllegalArgumentException.class, () -> PlayerPortalView.create(
                state, WorldTopology.HUB, Vec3.ZERO,
                "x".repeat(PlayerPortalView.MAX_QUERY_LENGTH + 1), 0));
    }

    @Test
    void ordersCurrentDimensionThenDistanceThenStableIdAndMarksWrongDimension() {
        PlatformSavedData state = new PlatformSavedData();
        create(state, portalId("b_same_distance"), definition(
                WorldTopology.HUB, new BlockPos(32, 0, 0),
                WorldTopology.WILDERNESS, new BlockPos(0, 70, 0)), 30);
        create(state, portalId("a_same_distance"), definition(
                WorldTopology.HUB, new BlockPos(-32, 0, 0),
                WorldTopology.WILDERNESS, new BlockPos(20, 70, 0)), 31);
        create(state, portalId("near"), definition(
                WorldTopology.HUB, new BlockPos(16, 0, 0),
                WorldTopology.WILDERNESS, new BlockPos(40, 70, 0)), 32);
        create(state, portalId("wrong_dimension"), definition(
                WorldTopology.WILDERNESS, new BlockPos(96, 0, 0),
                WorldTopology.HUB, new BlockPos(60, 70, 0)), 33);

        PlayerPortalView view = PlayerPortalView.create(
                state, WorldTopology.HUB, Vec3.atCenterOf(BlockPos.ZERO), "", 0);

        assertEquals(List.of("near", "a_same_distance", "b_same_distance", "wrong_dimension"),
                view.entries().stream().map(row -> row.portalId().getPath()).toList());
        PlayerPortalView.Row wrong = view.entries().getLast();
        assertFalse(wrong.currentDimension());
        assertTrue(wrong.distanceBlocks().isEmpty());
        assertFalse(wrong.withinUseDistance());
    }

    @Test
    void pagesAtThirtySixAndNeverScansPastTheDefinitionLimit() {
        PlatformSavedData state = new PlatformSavedData();
        for (int index = 0; index < PortalState.MAX_DEFINITIONS; index++) {
            create(state, portalId(String.format("route_%02d", index)), definition(
                    WorldTopology.HUB, new BlockPos(index * 32, 64, 0),
                    WorldTopology.WILDERNESS, new BlockPos(index * 32, 70, 1_000)), 100 + index);
        }

        PlayerPortalView first = PlayerPortalView.create(
                state, WorldTopology.HUB, Vec3.ZERO, "", 0);
        PlayerPortalView last = PlayerPortalView.create(
                state, WorldTopology.HUB, Vec3.ZERO, "", Integer.MAX_VALUE);

        assertEquals(PortalState.MAX_DEFINITIONS, first.totalEntries());
        assertEquals(PortalState.MAX_DEFINITIONS, PlayerPortalView.MAX_SCANNED_DEFINITIONS);
        assertEquals(36, first.entries().size());
        assertEquals(2, first.totalPages());
        assertEquals(1, last.page());
        assertEquals(28, last.entries().size());
        assertThrows(UnsupportedOperationException.class, first.entries()::clear);
        assertThrows(IllegalArgumentException.class, () -> PlayerPortalView.create(
                state, WorldTopology.HUB, Vec3.ZERO, "", -1));
    }

    @Test
    void exactSnapshotDetectsEditedAndDeletedSelections() {
        PlatformSavedData state = new PlatformSavedData();
        Identifier editedId = portalId("edited");
        Identifier deletedId = portalId("deleted");
        PortalDefinition original = definition(
                WorldTopology.HUB, new BlockPos(8, 64, 8),
                WorldTopology.WILDERNESS, new BlockPos(100, 70, 100));
        create(state, editedId, original, 200);
        create(state, deletedId, definition(
                WorldTopology.HUB, new BlockPos(40, 64, 8),
                WorldTopology.WILDERNESS, new BlockPos(140, 70, 100)), 201);
        PlayerPortalView view = PlayerPortalView.create(
                state, WorldTopology.HUB, Vec3.ZERO, "", 0);
        PlayerPortalView.Row edited = row(view, editedId);
        PlayerPortalView.Row deleted = row(view, deletedId);

        PortalDefinition replacement = definition(
                WorldTopology.HUB, new BlockPos(72, 64, 8),
                WorldTopology.WILDERNESS, new BlockPos(172, 70, 100));
        assertEquals(PortalService.Status.SUCCESS, PortalService.edit(
                state, ADMINISTRATOR, true, editedId, replacement, ignored -> true,
                "move route", 300, id(300)).status());
        assertEquals(PortalService.Status.SUCCESS, PortalService.delete(
                state, ADMINISTRATOR, true, deletedId, "remove route", 301, id(301)).status());

        assertFalse(edited.fresh(state));
        assertFalse(deleted.fresh(state));
        assertEquals(original, edited.expectedDefinition());
    }

    private static List<Identifier> portalIds(PlayerPortalView view) {
        return view.entries().stream().map(PlayerPortalView.Row::portalId).toList();
    }

    private static PlayerPortalView.Row row(PlayerPortalView view, Identifier portalId) {
        return view.entries().stream().filter(row -> row.portalId().equals(portalId)).findFirst().orElseThrow();
    }

    private static void create(
            PlatformSavedData state, Identifier portalId, PortalDefinition definition, long seed) {
        assertEquals(PortalService.Status.SUCCESS, PortalService.create(
                state, ADMINISTRATOR, true, portalId, definition, ignored -> true,
                "test route", seed, id(seed)).status());
    }

    private static PortalDefinition definition(
            ResourceKey<Level> originDimension,
            BlockPos origin,
            ResourceKey<Level> destinationDimension,
            BlockPos destination) {
        return new PortalDefinition(
                ADMINISTRATOR,
                new PortalDefinition.Endpoint(originDimension, origin),
                new PortalDefinition.Endpoint(destinationDimension, destination),
                0,
                0,
                PortalDefinition.SafeArrivalPolicy.NEAREST_SAFE,
                true);
    }

    private static Identifier portalId(String path) {
        return Identifier.fromNamespaceAndPath("rovenfall", path);
    }

    private static UUID id(long value) {
        return new UUID(0, value);
    }
}
