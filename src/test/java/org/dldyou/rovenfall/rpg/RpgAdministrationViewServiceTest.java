package org.dldyou.rovenfall.rpg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

final class RpgAdministrationViewServiceTest {
    private static final UUID PLAYER = new UUID(0L, 1L);
    private static final Identifier COMBAT = id("combat");
    private static final Identifier FARMING = id("farming");

    @Test
    void progressionIsStableBoundedAndKeepsUnknownDefinitionIdsVisible() {
        RpgPlayerSavedData state = new RpgPlayerSavedData();
        assertTrue(state.commit(PLAYER, new RpgPlayerState(
                Map.of(COMBAT, 15L, id("legacy_activity"), 7L),
                Map.of(id("novice"), new RpgPlayerState.CareerProgress(
                        30, 2, 1, Map.of(id("legacy_skill"), 1))),
                Optional.of(id("novice")), Map.of(0, id("legacy_skill")),
                Map.of(id("legacy_skill"), 100L), List.of())));
        RpgDefinitionSnapshot definitions = RpgDefinitionSnapshot.compile(
                List.of(new RpgDefinitionSnapshot.ActivitySource(
                        id("activities/combat"), "test", COMBAT,
                        new ActivityDefinition("activity.rovenfall.combat", List.of(10L, 20L)))),
                List.of(), List.of());

        var first = RpgAdministrationViewService.progression(state, definitions, PLAYER, 0, 3);
        var second = RpgAdministrationViewService.progression(state, definitions, PLAYER, 1, 3);
        assertEquals(6, first.totalEntries());
        assertEquals(2, first.totalPages());
        assertEquals(3, first.entries().size());
        assertEquals(3, second.entries().size());
        assertTrue(java.util.stream.Stream.concat(first.entries().stream(), second.entries().stream())
                .anyMatch(entry -> entry.id().equals(id("legacy_activity")) && entry.rank() == -1));
    }

    @Test
    void suspiciousAwardViewFlagsEveryServerLimitInvariant() {
        RpgPlayerSavedData state = new RpgPlayerSavedData();
        var first = new RpgPlayerState.ProgressionProvenance(
                RpgPlayerState.ProgressionProvenance.Kind.ACTIVITY_XP,
                COMBAT, 8, 1_000, uuid(10), "combat:target");
        var second = new RpgPlayerState.ProgressionProvenance(
                RpgPlayerState.ProgressionProvenance.Kind.ACTIVITY_XP,
                COMBAT, 12, 1_500, uuid(11), "combat:target");
        assertTrue(state.commit(PLAYER, new RpgPlayerState(
                Map.of(COMBAT, 20L), Map.of(), Optional.empty(), Map.of(), Map.of(), List.of(first, second))));
        var config = new ActivityXpConfig.ConfigSnapshot(10, 1, 60_000, 1_000, 10,
                500, 1_000, 4, 2);

        var page = RpgAdministrationViewService.awardHistory(
                state, PLAYER, Optional.empty(), true, 0, 10, config);
        assertEquals(1, page.totalEntries());
        var flags = page.entries().getFirst().suspicions();
        assertTrue(flags.contains(RpgAdministrationViewService.Suspicion.AWARD_TOO_LARGE));
        assertTrue(flags.contains(RpgAdministrationViewService.Suspicion.SOURCE_COOLDOWN));
        assertTrue(flags.contains(RpgAdministrationViewService.Suspicion.WINDOW_RATE));
        assertTrue(flags.contains(RpgAdministrationViewService.Suspicion.COMBAT_SOURCE_CAP));
    }

    @Test
    void activityFilterStillUsesTheGlobalAwardWindow() {
        RpgPlayerSavedData state = new RpgPlayerSavedData();
        var farming = new RpgPlayerState.ProgressionProvenance(
                RpgPlayerState.ProgressionProvenance.Kind.ACTIVITY_XP,
                FARMING, 1, 1_000, uuid(20), "farm:plot");
        var combat = new RpgPlayerState.ProgressionProvenance(
                RpgPlayerState.ProgressionProvenance.Kind.ACTIVITY_XP,
                COMBAT, 1, 1_500, uuid(21), "combat:target");
        assertTrue(state.commit(PLAYER, new RpgPlayerState(
                Map.of(COMBAT, 1L, FARMING, 1L), Map.of(), Optional.empty(),
                Map.of(), Map.of(), List.of(farming, combat))));
        var config = new ActivityXpConfig.ConfigSnapshot(10, 1, 60_000, 1_000, 100,
                500, 1_000, 4, 2);

        var page = RpgAdministrationViewService.awardHistory(
                state, PLAYER, Optional.of(COMBAT), true, 0, 10, config);

        assertEquals(1, page.totalEntries());
        assertEquals(COMBAT, page.entries().getFirst().evidence().target());
        assertTrue(page.entries().getFirst().suspicions()
                .contains(RpgAdministrationViewService.Suspicion.WINDOW_RATE));
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("rovenfall", path);
    }

    private static UUID uuid(long value) {
        return new UUID(0L, value);
    }
}
