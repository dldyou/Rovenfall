package org.dldyou.rovenfall.quest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class ActiveJourneyTrackerHudTest {
    @Test
    void topRightCardClampsToWideAndNarrowScreens() {
        var wide = ActiveJourneyTrackerHud.layout(640, 500, 4, 9);
        assertEquals(ActiveJourneyTrackerHud.MAX_CARD_WIDTH, wide.width());
        assertEquals(ActiveJourneyTrackerHud.SCREEN_MARGIN, wide.y());
        assertEquals(640 - ActiveJourneyTrackerHud.SCREEN_MARGIN, wide.right());

        var narrow = ActiveJourneyTrackerHud.layout(120, 500, 4, 9);
        assertEquals(120 - ActiveJourneyTrackerHud.SCREEN_MARGIN * 2, narrow.width());
        assertTrue(narrow.x() >= 0);
        assertTrue(narrow.right() <= 120);
        assertTrue(narrow.bottom() > narrow.y());
    }

    @Test
    void contractTrackerGetsARefreshLineWhileStoryDoesNot() {
        assertEquals(4, ActiveJourneyTrackerHud.lines(active(
                ActiveJourneyTrackerPayloads.JourneyKind.DAILY)).size());
        assertEquals(4, ActiveJourneyTrackerHud.lines(active(
                ActiveJourneyTrackerPayloads.JourneyKind.WEEKLY)).size());
        assertEquals(3, ActiveJourneyTrackerHud.lines(active(
                ActiveJourneyTrackerPayloads.JourneyKind.STORY)).size());
        assertThrows(IllegalArgumentException.class,
                () -> ActiveJourneyTrackerHud.layout(0, 10, 3, 9));
    }

    @Test
    void compassUsesMinecraftYawInEightReadableDirections() {
        assertEquals("hud.rovenfall.direction.south", ActiveJourneyTrackerHud.directionKey(0));
        assertEquals("hud.rovenfall.direction.west", ActiveJourneyTrackerHud.directionKey(90));
        assertEquals("hud.rovenfall.direction.north", ActiveJourneyTrackerHud.directionKey(180));
        assertEquals("hud.rovenfall.direction.east", ActiveJourneyTrackerHud.directionKey(-90));
        assertEquals("hud.rovenfall.direction.south_east", ActiveJourneyTrackerHud.directionKey(-45));
    }

    private static ActiveJourneyTrackerPayloads.Snapshot active(
            ActiveJourneyTrackerPayloads.JourneyKind kind) {
        return new ActiveJourneyTrackerPayloads.Snapshot(
                ActiveJourneyTrackerPayloads.PACKET_REVISION,
                true,
                kind,
                "quest.rovenfall.test",
                ActiveJourneyTrackerPayloads.JourneyStatus.IN_PROGRESS,
                ActiveJourneyTrackerPayloads.ObjectiveKind.CLAIM_PURCHASE,
                "",
                2,
                3);
    }
}
