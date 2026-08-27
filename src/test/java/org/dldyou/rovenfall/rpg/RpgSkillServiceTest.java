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

final class RpgSkillServiceTest {
    private static final UUID PLAYER = uuid(1);
    private static final Identifier NOVICE = id("novice");
    private static final Identifier WARRIOR = id("warrior");
    private static final Identifier BERSERKER = id("berserker");
    private static final Identifier GUARDIAN = id("guardian");
    private static final Identifier FOUNDATION = id("foundation");
    private static final Identifier STRIKE = id("strike");
    private static final Identifier FURY = id("fury");
    private static final Identifier WARD = id("ward");

    @Test
    void spendsPointsOnlyAfterEveryPrerequisiteAndIsIdempotent() {
        RpgDefinitionSnapshot definitions = definitions();
        RpgPlayerSavedData state = state(Map.of(
                NOVICE, progress(3, Map.of()),
                WARRIOR, progress(2, Map.of())));

        var blocked = RpgSkillService.learn(
                state, definitions, PLAYER, STRIKE, 1, uuid(10), "test");
        assertEquals(RpgSkillService.Status.PREREQUISITE_NOT_MET, blocked.status());
        assertEquals(Optional.of(FOUNDATION), blocked.blocker());
        assertEquals(2, blocked.requiredRank());
        assertEquals(0, blocked.actualRank());

        assertEquals(RpgSkillService.Status.SUCCESS,
                RpgSkillService.learn(state, definitions, PLAYER, FOUNDATION, 2, uuid(11), "test").status());
        assertEquals(RpgSkillService.Status.SUCCESS,
                RpgSkillService.learn(state, definitions, PLAYER, FOUNDATION, 3, uuid(12), "test").status());
        assertEquals(RpgSkillService.Status.DUPLICATE,
                RpgSkillService.learn(state, definitions, PLAYER, FOUNDATION, 4, uuid(12), "replay").status());
        assertEquals(RpgSkillService.Status.SUCCESS,
                RpgSkillService.learn(state, definitions, PLAYER, STRIKE, 5, uuid(13), "test").status());

        RpgPlayerState player = state.state(PLAYER);
        assertEquals(2, player.careers().get(NOVICE).learnedSkills().get(FOUNDATION));
        assertEquals(1, player.careers().get(NOVICE).skillPoints());
        assertEquals(1, player.careers().get(WARRIOR).learnedSkills().get(STRIKE));
        assertEquals(0, player.careers().get(WARRIOR).skillPoints());
        assertEquals(player, roundTrip(RpgPlayerSavedData.CODEC, state).state(PLAYER));
    }

    @Test
    void rejectsInsufficientPointsAndMaximumRankWithoutMutation() {
        RpgPlayerSavedData state = state(Map.of(NOVICE, progress(1, Map.of())));
        assertEquals(RpgSkillService.Status.SUCCESS,
                RpgSkillService.learn(state, definitions(), PLAYER, FOUNDATION, 1, uuid(20), "test").status());
        RpgPlayerState rankOne = state.state(PLAYER);
        assertEquals(RpgSkillService.Status.INSUFFICIENT_POINTS,
                RpgSkillService.learn(state, definitions(), PLAYER, FOUNDATION, 2, uuid(21), "test").status());
        assertEquals(rankOne, state.state(PLAYER));

        state = state(Map.of(NOVICE, progress(0, Map.of(FOUNDATION, 2))));
        RpgPlayerState maxed = state.state(PLAYER);
        assertEquals(RpgSkillService.Status.MAX_RANK,
                RpgSkillService.learn(state, definitions(), PLAYER, FOUNDATION, 3, uuid(22), "test").status());
        assertEquals(maxed, state.state(PLAYER));
    }

    @Test
    void branchResetCascadesDependentsRefundsEachCareerAndClearsRuntimeState() {
        RpgPlayerSavedData state = state(Map.of(
                NOVICE, progress(0, Map.of(FOUNDATION, 2)),
                WARRIOR, progress(0, Map.of(STRIKE, 1)),
                BERSERKER, progress(0, Map.of(FURY, 1)),
                GUARDIAN, progress(0, Map.of(WARD, 1))),
                Map.of(0, STRIKE, 1, WARD), Map.of(STRIKE, 500L, FURY, 600L, WARD, 700L));

        var preparation = RpgSkillService.prepareReset(
                state, definitions(), PLAYER, SkillResetPlan.Mode.BRANCH, STRIKE);
        assertEquals(RpgSkillService.Status.SUCCESS, preparation.status());
        SkillResetPlan plan = preparation.plan().orElseThrow();
        assertEquals(List.of(FURY, STRIKE), plan.removedSkills().stream()
                .map(SkillResetPlan.RemovedSkill::skill).toList());
        assertEquals(plan, roundTrip(SkillResetPlan.CODEC, plan));

        assertEquals(RpgSkillService.Status.SUCCESS,
                RpgSkillService.applyReset(state, definitions(), PLAYER, plan, 50, 10, uuid(30)).status());
        RpgPlayerState reset = state.state(PLAYER);
        assertEquals(Map.of(FOUNDATION, 2), reset.careers().get(NOVICE).learnedSkills());
        assertTrue(reset.careers().get(WARRIOR).learnedSkills().isEmpty());
        assertTrue(reset.careers().get(BERSERKER).learnedSkills().isEmpty());
        assertEquals(Map.of(WARD, 1), reset.careers().get(GUARDIAN).learnedSkills());
        assertEquals(2, reset.careers().get(WARRIOR).skillPoints());
        assertEquals(3, reset.careers().get(BERSERKER).skillPoints());
        assertEquals(Map.of(1, WARD), reset.activeSkillSlots());
        assertEquals(Map.of(WARD, 700L), reset.cooldowns());
        assertEquals(RpgPlayerState.ProgressionProvenance.Kind.SKILL_RESET,
                reset.careerProvenance().getLast().kind());
        assertEquals(50, reset.careerProvenance().getLast().amount());
        assertEquals(RpgSkillService.Status.DUPLICATE,
                RpgSkillService.applyReset(state, definitions(), PLAYER, plan, 50, 11, uuid(30)).status());
    }

    @Test
    void fullResetAlsoRemovesEveryLearnedDependent() {
        RpgPlayerSavedData state = state(Map.of(
                NOVICE, progress(0, Map.of(FOUNDATION, 2)),
                WARRIOR, progress(0, Map.of(STRIKE, 1)),
                BERSERKER, progress(0, Map.of(FURY, 1)),
                GUARDIAN, progress(0, Map.of(WARD, 1))));

        SkillResetPlan plan = RpgSkillService.prepareReset(
                state, definitions(), PLAYER, SkillResetPlan.Mode.FULL, NOVICE).plan().orElseThrow();
        assertEquals(List.of(FOUNDATION, FURY, STRIKE), plan.removedSkills().stream()
                .map(SkillResetPlan.RemovedSkill::skill).toList());
        assertFalse(plan.removedSkills().stream().anyMatch(skill -> skill.skill().equals(WARD)));
    }

    @Test
    void persistedResetPlanMustMatchAuthoritativeClosureAndRefunds() {
        RpgPlayerSavedData state = state(Map.of(
                WARRIOR, progress(0, Map.of(STRIKE, 1)),
                BERSERKER, progress(0, Map.of(FURY, 1))));
        RpgPlayerState before = state.state(PLAYER);
        SkillResetPlan valid = RpgSkillService.prepareReset(
                state, definitions(), PLAYER, SkillResetPlan.Mode.BRANCH, STRIKE).plan().orElseThrow();
        SkillResetPlan incomplete = new SkillResetPlan(
                valid.mode(), valid.target(), List.of(valid.removedSkills().getLast()));
        SkillResetPlan.RemovedSkill strike = valid.removedSkills().getLast();
        SkillResetPlan inflated = new SkillResetPlan(
                valid.mode(), valid.target(), List.of(
                        valid.removedSkills().getFirst(),
                        new SkillResetPlan.RemovedSkill(
                                strike.skill(), strike.career(), strike.rank(), strike.refundedPoints() + 1)));

        assertEquals(RpgSkillService.Status.STATE_CONFLICT,
                RpgSkillService.applyReset(
                        state, definitions(), PLAYER, incomplete, 50, 10, uuid(40)).status());
        assertEquals(before, state.state(PLAYER));
        assertEquals(RpgSkillService.Status.STATE_CONFLICT,
                RpgSkillService.applyReset(
                        state, definitions(), PLAYER, inflated, 50, 11, uuid(41)).status());
        assertEquals(before, state.state(PLAYER));
    }

    @Test
    void passivesApplyOnlyFromTheActiveCareerLineage() {
        RpgPlayerState attacker = playerState(Map.of(
                NOVICE, progress(0, Map.of()),
                WARRIOR, progress(0, Map.of()),
                BERSERKER, progress(0, Map.of(FURY, 2)),
                GUARDIAN, progress(0, Map.of(WARD, 1))), Optional.of(BERSERKER), Map.of(), Map.of());
        RpgPlayerState target = playerState(
                Map.of(NOVICE, progress(0, Map.of(FOUNDATION, 2))), Optional.of(NOVICE), Map.of(), Map.of());

        assertEquals(9.9F, RpgPassiveSkillService.modifyDamage(definitions(), attacker, target, 10F), 0.0001F);
        assertEquals(1_000, RpgPassiveSkillService.passiveBasisPoints(
                attacker, definitions(), SkillDefinition.EffectType.DAMAGE_DEALT));

        RpgPlayerState invalidRank = playerState(
                Map.of(BERSERKER, progress(0, Map.of(FURY, 4))), Optional.of(BERSERKER), Map.of(), Map.of());
        assertEquals(0, RpgPassiveSkillService.passiveBasisPoints(
                invalidRank, definitions(), SkillDefinition.EffectType.DAMAGE_DEALT));
    }

    private static RpgDefinitionSnapshot definitions() {
        return RpgDefinitionSnapshot.compile(List.of(), List.of(
                career(NOVICE, 1, List.of()),
                career(WARRIOR, 2, List.of(NOVICE)),
                career(BERSERKER, 3, List.of(WARRIOR)),
                career(GUARDIAN, 3, List.of(WARRIOR))), List.of(
                passive(FOUNDATION, NOVICE, 2, 1, List.of(),
                        SkillDefinition.EffectType.DAMAGE_TAKEN_REDUCTION),
                active(STRIKE, WARRIOR, 3, 2, List.of(new SkillDefinition.Prerequisite(FOUNDATION, 2))),
                passive(FURY, BERSERKER, 3, 3, List.of(new SkillDefinition.Prerequisite(STRIKE, 1)),
                        SkillDefinition.EffectType.DAMAGE_DEALT),
                passive(WARD, GUARDIAN, 1, 4, List.of(),
                        SkillDefinition.EffectType.DAMAGE_TAKEN_REDUCTION)));
    }

    private static RpgDefinitionSnapshot.CareerSource career(
            Identifier id, int tier, List<Identifier> parents) {
        return new RpgDefinitionSnapshot.CareerSource(
                Identifier.fromNamespaceAndPath("rovenfall", "careers/" + id.getPath()), "test", id,
                new CareerDefinition("career.rovenfall." + id.getPath(), tier, parents,
                        List.of(10L), 0, List.of(), 1));
    }

    private static RpgDefinitionSnapshot.SkillSource passive(
            Identifier id,
            Identifier career,
            int maxRank,
            int cost,
            List<SkillDefinition.Prerequisite> prerequisites,
            SkillDefinition.EffectType effect) {
        return skill(id, new SkillDefinition(
                "skill.rovenfall." + id.getPath(), career, SkillDefinition.Kind.PASSIVE,
                maxRank, cost, prerequisites, Optional.empty(),
                Optional.of(new SkillDefinition.PassiveEffect(effect, 500))));
    }

    private static RpgDefinitionSnapshot.SkillSource active(
            Identifier id,
            Identifier career,
            int maxRank,
            int cost,
            List<SkillDefinition.Prerequisite> prerequisites) {
        return skill(id, new SkillDefinition(
                "skill.rovenfall." + id.getPath(), career, SkillDefinition.Kind.ACTIVE,
                maxRank, cost, prerequisites, Optional.of(100), Optional.empty(),
                Optional.of(new SkillDefinition.ActiveEffect(
                        SkillDefinition.EffectType.DAMAGE_DEALT,
                        SkillDefinition.TargetType.LIVING_ENTITY,
                        100,
                        20,
                        4.0))));
    }

    private static RpgDefinitionSnapshot.SkillSource skill(Identifier id, SkillDefinition definition) {
        return new RpgDefinitionSnapshot.SkillSource(
                Identifier.fromNamespaceAndPath("rovenfall", "skills/" + id.getPath()), "test", id, definition);
    }

    private static RpgPlayerSavedData state(Map<Identifier, RpgPlayerState.CareerProgress> careers) {
        return state(careers, Map.of(), Map.of());
    }

    private static RpgPlayerSavedData state(
            Map<Identifier, RpgPlayerState.CareerProgress> careers,
            Map<Integer, Identifier> slots,
            Map<Identifier, Long> cooldowns) {
        RpgPlayerSavedData state = new RpgPlayerSavedData();
        Identifier active = careers.containsKey(WARRIOR) ? WARRIOR : careers.keySet().iterator().next();
        assertTrue(state.commit(PLAYER, playerState(careers, Optional.of(active), slots, cooldowns)));
        return state;
    }

    private static RpgPlayerState playerState(
            Map<Identifier, RpgPlayerState.CareerProgress> careers,
            Optional<Identifier> active,
            Map<Integer, Identifier> slots,
            Map<Identifier, Long> cooldowns) {
        return new RpgPlayerState(Map.of(), careers, active, slots, cooldowns, List.of());
    }

    private static RpgPlayerState.CareerProgress progress(
            int points, Map<Identifier, Integer> learned) {
        RpgPlayerState.EMPTY.isValid();
        return new RpgPlayerState.CareerProgress(100, 1, points, learned);
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("rovenfall", path);
    }

    private static UUID uuid(long least) {
        return new UUID(0, least);
    }
}
