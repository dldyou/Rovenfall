package org.dldyou.rovenfall.quest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class ActiveJourneyTrackerClientTest {
    @Test
    void storesOnlyValidLatestSnapshotsAndReportsOnlyRealChanges() {
        var state = new ActiveJourneyTrackerClient.State();
        var first = active(0, ActiveJourneyTrackerPayloads.JourneyStatus.AVAILABLE);
        assertEquals(ActiveJourneyTrackerClient.Change.UPDATED, state.accept(first));
        assertEquals(first, state.current().orElseThrow());
        assertEquals(ActiveJourneyTrackerClient.Change.NONE, state.accept(first));
        var narration = ActiveJourneyTrackerClient.narration(first);
        var expectedLines = ActiveJourneyTrackerHud.lines(first);
        assertEquals(expectedLines.size() * 2 - 1, narration.getSiblings().size());
        assertEquals(
                net.minecraft.network.chat.Component.translatable(
                        "hud.rovenfall.journey.tracker.changed", expectedLines.getFirst()),
                narration.getSiblings().getFirst());
        assertEquals(expectedLines.getLast(), narration.getSiblings().getLast());

        var progressed = active(1, ActiveJourneyTrackerPayloads.JourneyStatus.IN_PROGRESS);
        assertEquals(ActiveJourneyTrackerClient.Change.UPDATED, state.accept(progressed));
        assertEquals(progressed, state.current().orElseThrow());
        assertEquals(ActiveJourneyTrackerClient.Change.CLEARED,
                state.accept(ActiveJourneyTrackerPayloads.Snapshot.inactive()));
        state.accept(progressed);
        state.clear();
        assertTrue(state.current().isEmpty());

        assertEquals(ActiveJourneyTrackerClient.Change.NONE, state.accept(new ActiveJourneyTrackerPayloads.Snapshot(
                0, false,
                ActiveJourneyTrackerPayloads.JourneyKind.STORY, "",
                ActiveJourneyTrackerPayloads.JourneyStatus.AVAILABLE,
                ActiveJourneyTrackerPayloads.ObjectiveKind.ACTIVITY, "", 0, 0)));
        assertTrue(state.current().isEmpty());
    }

    @Test
    void adventureHudModeCyclesThroughEveryReadableState() {
        var full = ActiveJourneyTrackerClient.DisplayMode.FULL;
        assertEquals(ActiveJourneyTrackerClient.DisplayMode.QUEST_ONLY, full.next());
        assertEquals(ActiveJourneyTrackerClient.DisplayMode.HIDDEN, full.next().next());
        assertEquals(full, full.next().next().next());
        assertEquals("hud.rovenfall.mode.full", full.translationKey());
    }

    private static ActiveJourneyTrackerPayloads.Snapshot active(
            long progress, ActiveJourneyTrackerPayloads.JourneyStatus status) {
        return new ActiveJourneyTrackerPayloads.Snapshot(
                ActiveJourneyTrackerPayloads.PACKET_REVISION,
                true,
                ActiveJourneyTrackerPayloads.JourneyKind.DAILY,
                "quest.rovenfall.contract.test",
                status,
                ActiveJourneyTrackerPayloads.ObjectiveKind.ACTIVITY,
                "activity.rovenfall.mining",
                progress,
                10);
    }
}
