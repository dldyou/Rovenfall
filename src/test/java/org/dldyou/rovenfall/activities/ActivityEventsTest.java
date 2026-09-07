package org.dldyou.rovenfall.activities;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class ActivityEventsTest {
    @Test
    void levelUpPresentationEscalatesAtFiveAndAtMastery() {
        assertEquals(ActivityEvents.LevelUpPresentation.STANDARD,
                ActivityEvents.levelUpPresentation(0, 1, 10));
        assertEquals(ActivityEvents.LevelUpPresentation.MILESTONE,
                ActivityEvents.levelUpPresentation(4, 5, 10));
        assertEquals(ActivityEvents.LevelUpPresentation.MILESTONE,
                ActivityEvents.levelUpPresentation(2, 7, 10));
        assertEquals(ActivityEvents.LevelUpPresentation.MASTERY,
                ActivityEvents.levelUpPresentation(9, 10, 10));
        assertEquals(ActivityEvents.LevelUpPresentation.MASTERY,
                ActivityEvents.levelUpPresentation(2, 3, 3));
    }

    @Test
    void levelUpPresentationRejectsNonProgressAndImpossibleLevels() {
        assertEquals(ActivityEvents.LevelUpPresentation.NONE,
                ActivityEvents.levelUpPresentation(2, 2, 10));
        assertEquals(ActivityEvents.LevelUpPresentation.NONE,
                ActivityEvents.levelUpPresentation(2, 11, 10));
        assertEquals(ActivityEvents.LevelUpPresentation.NONE,
                ActivityEvents.levelUpPresentation(-1, 1, 10));
    }
}
