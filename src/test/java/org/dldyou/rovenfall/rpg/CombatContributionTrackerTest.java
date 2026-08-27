package org.dldyou.rovenfall.rpg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

final class CombatContributionTrackerTest {
    private static final UUID TARGET = uuid(100);
    private static final UUID PLAYER_ONE = uuid(1);
    private static final UUID PLAYER_TWO = uuid(2);

    @Test
    void accumulatesDamageContributionForOnePlayerAndTarget() {
        CombatContributionTracker tracker = new CombatContributionTracker();

        for (int index = 0; index < 11; index++) {
            assertTrue(tracker.record(TARGET, PLAYER_ONE, 1, index));
        }

        assertEquals(java.util.List.of(PLAYER_ONE), tracker.consumeHuntingCredit(TARGET, 21));
    }

    @Test
    void huntingCreditUsesServerRecordedParticipationInsteadOfLastHit() {
        CombatContributionTracker tracker = new CombatContributionTracker();
        tracker.record(TARGET, PLAYER_ONE, 90, 1_000);
        tracker.record(TARGET, PLAYER_TWO, 10, 1_001);

        assertEquals(java.util.List.of(PLAYER_ONE, PLAYER_TWO),
                tracker.consumeHuntingCredit(TARGET, 1_002));
        assertTrue(tracker.consumeHuntingCredit(TARGET, 1_003).isEmpty());
    }

    @Test
    void deathBeforeFinalDamagePostRetainsAllContributors() {
        CombatContributionTracker tracker = new CombatContributionTracker();
        tracker.record(TARGET, PLAYER_ONE, 9, 1_000);

        assertTrue(tracker.markDeath(TARGET, 1_001));
        assertTrue(tracker.record(TARGET, PLAYER_TWO, 1, 1_002));

        assertEquals(java.util.List.of(PLAYER_ONE, PLAYER_TWO),
                tracker.consumeHuntingCredit(TARGET, 1_003));
        assertTrue(tracker.drainPendingDeaths(1_004).isEmpty());
    }

    @Test
    void pendingDeathFallbackIsBoundedByTrackedTargetsAndDrainsOnce() {
        CombatContributionTracker tracker = new CombatContributionTracker();
        tracker.record(TARGET, PLAYER_ONE, 1, 1_000);
        assertTrue(tracker.markDeath(TARGET, 1_001));

        assertEquals(java.util.List.of(
                        new CombatContributionTracker.HuntingCredit(TARGET, java.util.List.of(PLAYER_ONE))),
                tracker.drainPendingDeaths(1_002));
        assertTrue(tracker.drainPendingDeaths(1_003).isEmpty());
        assertEquals(0, tracker.trackedTargetCount());
    }

    @Test
    void firstLethalHitCanBeMarkedBeforeItsFinalDamagePost() {
        CombatContributionTracker tracker = new CombatContributionTracker();

        assertTrue(tracker.markDeath(TARGET, 1_000));
        assertTrue(tracker.record(TARGET, PLAYER_ONE, 2, 1_001));

        assertEquals(java.util.List.of(PLAYER_ONE),
                tracker.consumePendingHuntingCredit(TARGET, 1_002));
    }

    @Test
    void damageWithoutConfirmedDeathCannotConsumeHuntingCredit() {
        CombatContributionTracker tracker = new CombatContributionTracker();
        tracker.record(TARGET, PLAYER_ONE, 2, 1_000);

        assertTrue(tracker.consumePendingHuntingCredit(TARGET, 1_001).isEmpty());
        assertEquals(java.util.List.of(PLAYER_ONE), tracker.consumeHuntingCredit(TARGET, 1_002));
    }

    @Test
    void rejectsInvalidDamageAndExpiresAbandonedTargets() {
        CombatContributionTracker tracker = new CombatContributionTracker();
        assertFalse(tracker.record(TARGET, PLAYER_ONE, Double.NaN, 0));
        assertFalse(tracker.record(TARGET, PLAYER_ONE, 0, 0));
        assertTrue(tracker.record(TARGET, PLAYER_ONE, 1, 0));
        assertTrue(tracker.record(uuid(101), PLAYER_TWO, 1,
                CombatContributionTracker.TARGET_TTL_MILLIS + 1));

        assertEquals(1, tracker.trackedTargetCount());
        assertTrue(tracker.consumeHuntingCredit(TARGET,
                CombatContributionTracker.TARGET_TTL_MILLIS + 2).isEmpty());
    }

    @Test
    void expirationUsesLastActivityTimeEvenWhenTimestampsArriveOutOfOrder() {
        CombatContributionTracker tracker = new CombatContributionTracker();
        UUID newerTarget = uuid(101);
        tracker.record(TARGET, PLAYER_ONE, 1, 0);
        tracker.record(newerTarget, PLAYER_TWO, 1, CombatContributionTracker.TARGET_TTL_MILLIS);
        tracker.record(TARGET, PLAYER_ONE, 1, 1);

        tracker.record(uuid(102), PLAYER_ONE, 1, CombatContributionTracker.TARGET_TTL_MILLIS + 2);

        assertTrue(tracker.consumeHuntingCredit(TARGET,
                CombatContributionTracker.TARGET_TTL_MILLIS + 2).isEmpty());
        assertEquals(java.util.List.of(PLAYER_TWO), tracker.consumeHuntingCredit(
                newerTarget, CombatContributionTracker.TARGET_TTL_MILLIS + 2));
    }

    private static UUID uuid(long least) {
        return new UUID(0, least);
    }
}
