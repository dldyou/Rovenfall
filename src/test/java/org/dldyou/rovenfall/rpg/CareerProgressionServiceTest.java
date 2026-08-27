package org.dldyou.rovenfall.rpg;

import static org.dldyou.rovenfall.PersistenceTestHarness.roundTrip;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

final class CareerProgressionServiceTest {
    private static final UUID PLAYER = uuid(1);
    private static final Identifier COMBAT = id("combat");

    @Test
    void promotesRootAndPersistsTheAtomicAuditEvidence() {
        RpgPlayerSavedData state = new RpgPlayerSavedData();
        RpgDefinitionSnapshot definitions = definitions(
                career("novice", 1, List.of(), List.of(100L), List.of(), 1));

        var result = CareerProgressionService.promote(
                state, definitions, PLAYER, id("novice"), 10, uuid(10), "player_command");

        assertEquals(CareerProgressionService.Status.SUCCESS, result.status());
        assertTrue(result.committed());
        assertEquals(Optional.of(id("novice")), state.state(PLAYER).activeCareer());
        assertEquals(0, state.state(PLAYER).careers().get(id("novice")).experience());
        assertEquals(RpgPlayerState.ProgressionProvenance.Kind.CAREER_PROMOTION,
                state.state(PLAYER).careerProvenance().getLast().kind());
        assertEquals(state.state(PLAYER), roundTrip(RpgPlayerSavedData.CODEC, state).state(PLAYER));
    }

    @Test
    void arbitraryTierPromotionRequiresEveryParentAndActivityLevel() {
        Identifier alpha = id("alpha");
        Identifier beta = id("beta");
        Identifier tierSeventyThree = id("tier_seventy_three");
        RpgDefinitionSnapshot definitions = definitions(
                career("alpha", 1, List.of(), List.of(10L), List.of(), 1),
                career("beta", 1, List.of(), List.of(10L), List.of(), 1),
                career("tier_seventy_three", 73, List.of(alpha, beta), List.of(100L),
                        List.of(new CareerDefinition.ActivityRequirement(COMBAT, 2)), 4));
        RpgPlayerSavedData state = stateWith(Map.of(
                alpha, progress(10, 1)), Map.of(COMBAT, 20L), Optional.of(alpha));

        var missing = CareerProgressionService.promote(
                state, definitions, PLAYER, tierSeventyThree, 20, uuid(20), "test");
        assertEquals(CareerProgressionService.Status.MISSING_PARENT, missing.status());
        assertEquals(Optional.of(beta), missing.blocker());

        state = stateWith(Map.of(alpha, progress(10, 1), beta, progress(0, 0)),
                Map.of(COMBAT, 20L), Optional.of(alpha));
        var lowParent = CareerProgressionService.promote(
                state, definitions, PLAYER, tierSeventyThree, 21, uuid(21), "test");
        assertEquals(CareerProgressionService.Status.PARENT_RANK_TOO_LOW, lowParent.status());
        assertEquals(1, lowParent.requiredLevel());
        assertEquals(0, lowParent.actualLevel());

        state = stateWith(Map.of(alpha, progress(10, 1), beta, progress(10, 1)),
                Map.of(COMBAT, 19L), Optional.of(alpha));
        var lowActivity = CareerProgressionService.promote(
                state, definitions, PLAYER, tierSeventyThree, 22, uuid(22), "test");
        assertEquals(CareerProgressionService.Status.ACTIVITY_LEVEL_TOO_LOW, lowActivity.status());
        assertEquals(Optional.of(COMBAT), lowActivity.blocker());

        state = stateWith(Map.of(alpha, progress(10, 1), beta, progress(10, 1)),
                Map.of(COMBAT, 20L), Optional.of(alpha));
        assertEquals(CareerProgressionService.Status.SUCCESS,
                CareerProgressionService.promote(
                        state, definitions, PLAYER, tierSeventyThree, 23, uuid(23), "test").status());
        assertEquals(Optional.of(tierSeventyThree), state.state(PLAYER).activeCareer());
    }

    @Test
    void promotedBranchesSwitchWithoutLosingProgressAndAuditInTheSameCommit() {
        Identifier novice = id("novice");
        Identifier guardian = id("guardian");
        Identifier berserker = id("berserker");
        RpgDefinitionSnapshot definitions = definitions(
                career("novice", 1, List.of(), List.of(10L), List.of(), 1),
                career("guardian", 2, List.of(novice), List.of(100L), List.of(), 2),
                career("berserker", 2, List.of(novice), List.of(100L), List.of(), 3));
        RpgPlayerSavedData state = stateWith(
                Map.of(novice, progress(10, 1)), Map.of(), Optional.of(novice));

        assertEquals(CareerProgressionService.Status.SUCCESS,
                CareerProgressionService.promote(
                        state, definitions, PLAYER, guardian, 30, uuid(30), "test").status());
        assertEquals(CareerProgressionService.Status.SUCCESS,
                CareerProgressionService.promote(
                        state, definitions, PLAYER, berserker, 31, uuid(31), "test").status());
        assertEquals(CareerProgressionService.Status.SUCCESS,
                CareerProgressionService.switchActive(
                        state, definitions, PLAYER, guardian, 32, uuid(32), "player_command").status());

        RpgPlayerState switched = state.state(PLAYER);
        assertEquals(Optional.of(guardian), switched.activeCareer());
        assertTrue(switched.careers().containsKey(berserker));
        assertEquals(RpgPlayerState.ProgressionProvenance.Kind.CAREER_SWITCH,
                switched.careerProvenance().getLast().kind());
        assertEquals(Optional.of(berserker), switched.careerProvenance().getLast().previousTarget());
        assertEquals(uuid(32), switched.careerProvenance().getLast().transactionId());
        assertEquals(switched, roundTrip(RpgPlayerSavedData.CODEC, state).state(PLAYER));
        var history = CareerProgressionService.history(state, PLAYER, Optional.empty(), 0, 10);
        assertEquals(3, history.totalEntries());
        assertEquals(uuid(32), history.entries().getFirst().transactionId());

        RpgPlayerState beforeReplay = state.state(PLAYER);
        assertEquals(CareerProgressionService.Status.DUPLICATE,
                CareerProgressionService.switchActive(
                        state, definitions, PLAYER, berserker, 33, uuid(32), "replay").status());
        assertEquals(beforeReplay, state.state(PLAYER));
    }

    @Test
    void duplicateAndRejectedSwitchesLeaveStateUnchanged() {
        Identifier novice = id("novice");
        Identifier locked = id("locked");
        RpgDefinitionSnapshot definitions = definitions(
                career("novice", 1, List.of(), List.of(10L), List.of(), 1),
                career("locked", 2, List.of(novice), List.of(10L), List.of(), 1));
        RpgPlayerSavedData state = stateWith(
                Map.of(novice, progress(10, 1)), Map.of(), Optional.of(novice));
        RpgPlayerState before = state.state(PLAYER);

        assertEquals(CareerProgressionService.Status.CAREER_NOT_PROMOTED,
                CareerProgressionService.switchActive(
                        state, definitions, PLAYER, locked, 40, uuid(40), "test").status());
        assertEquals(CareerProgressionService.Status.ALREADY_ACTIVE,
                CareerProgressionService.switchActive(
                        state, definitions, PLAYER, novice, 41, uuid(41), "test").status());
        assertFalse(state.state(PLAYER).provenance().stream()
                .anyMatch(entry -> entry.transactionId().equals(uuid(40))));
        assertTrue(state.state(PLAYER).careerProvenance().isEmpty());
        assertEquals(before, state.state(PLAYER));
    }

    @Test
    void anyPreviouslyPromotedCareerCanBeReactivated() {
        Identifier novice = id("novice");
        Identifier artisan = id("artisan");
        RpgDefinitionSnapshot definitions = definitions(
                career("novice", 1, List.of(), List.of(10L), List.of(), 1),
                career("artisan", 1, List.of(), List.of(10L), List.of(), 2));
        RpgPlayerSavedData state = stateWith(
                Map.of(novice, progress(10, 1), artisan, progress(25, 1)),
                Map.of(), Optional.of(novice));

        assertEquals(CareerProgressionService.Status.SUCCESS,
                CareerProgressionService.switchActive(
                        state, definitions, PLAYER, artisan, 50, uuid(50), "test").status());
        assertEquals(Optional.of(artisan), state.state(PLAYER).activeCareer());
        assertEquals(25, state.state(PLAYER).careers().get(artisan).experience());
    }

    private static RpgPlayerSavedData stateWith(
            Map<Identifier, RpgPlayerState.CareerProgress> careers,
            Map<Identifier, Long> activityXp,
            Optional<Identifier> active) {
        RpgPlayerSavedData state = new RpgPlayerSavedData();
        assertTrue(state.commit(PLAYER, new RpgPlayerState(
                activityXp, careers, active, Map.of(), Map.of(), List.of())));
        return state;
    }

    private static RpgPlayerState.CareerProgress progress(long experience, int rank) {
        return new RpgPlayerState.CareerProgress(experience, rank, 0, Map.of());
    }

    private static RpgDefinitionSnapshot definitions(RpgDefinitionSnapshot.CareerSource... careers) {
        return RpgDefinitionSnapshot.compile(
                List.of(new RpgDefinitionSnapshot.ActivitySource(
                        id("activities/combat"), "test", COMBAT,
                        new ActivityDefinition("activity.rovenfall.combat", List.of(10L, 20L)))),
                List.of(careers), List.of());
    }

    private static RpgDefinitionSnapshot.CareerSource career(
            String path,
            int tier,
            List<Identifier> parents,
            List<Long> levelXp,
            List<CareerDefinition.ActivityRequirement> requirements,
            int multiplier) {
        return new RpgDefinitionSnapshot.CareerSource(
                id("careers/" + path), "test", id(path),
                new CareerDefinition("career.rovenfall." + path, tier, parents, levelXp, 0, requirements, multiplier));
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("rovenfall", path);
    }

    private static UUID uuid(long least) {
        return new UUID(0, least);
    }
}
