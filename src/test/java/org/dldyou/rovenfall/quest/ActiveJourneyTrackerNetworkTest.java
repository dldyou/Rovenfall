package org.dldyou.rovenfall.quest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;
import org.junit.jupiter.api.Test;

final class ActiveJourneyTrackerNetworkTest {
    @Test
    void onlineSyncBatchIsBoundedAndWrapsItsCursor() {
        assertArrayEquals(new int[0], ActiveJourneyTrackerNetwork.batchIndexes(0, 0));
        assertArrayEquals(new int[]{3, 4, 0, 1, 2},
                ActiveJourneyTrackerNetwork.batchIndexes(5, 3));
        assertEquals(ActiveJourneyTrackerNetwork.MAX_PLAYERS_PER_TICK,
                ActiveJourneyTrackerNetwork.batchIndexes(50, 45).length);
        assertArrayEquals(new int[]{49, 0, 1},
                java.util.Arrays.copyOf(
                        ActiveJourneyTrackerNetwork.batchIndexes(50, 49), 3));
    }

    @Test
    void privacySafeViewProjectionDropsDefinitionRevisionAndOptionalTarget() {
        var view = new ActiveJourneyView(
                9_001L,
                true,
                Optional.of(new ActiveJourneyView.Entry(
                        ActiveJourneyView.Kind.WEEKLY,
                        "quest.rovenfall.contract.test",
                        ActiveJourneyView.Status.IN_PROGRESS,
                        QuestDefinition.Kind.BOSS_DEFEAT,
                        Optional.empty(),
                        2,
                        5)));
        var snapshot = ActiveJourneyTrackerNetwork.snapshot(view);
        assertEquals(ActiveJourneyTrackerPayloads.PACKET_REVISION, snapshot.packetRevision());
        assertEquals(ActiveJourneyTrackerPayloads.JourneyKind.WEEKLY, snapshot.journeyKind());
        assertEquals(ActiveJourneyTrackerPayloads.ObjectiveKind.BOSS_DEFEAT, snapshot.objectiveKind());
        assertEquals("", snapshot.activityTargetTranslationKey());
        assertEquals(2, snapshot.progress());
        assertEquals(5, snapshot.requiredCount());
        assertEquals(ActiveJourneyTrackerPayloads.Snapshot.inactive(),
                ActiveJourneyTrackerNetwork.snapshot(new ActiveJourneyView(77, false, Optional.empty())));
    }
}
