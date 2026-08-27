package org.dldyou.rovenfall.rpg;

import static org.dldyou.rovenfall.PersistenceTestHarness.roundTrip;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

final class RpgAdministrativeMutationServiceTest {
    private static final UUID PLAYER = uuid(1);
    private static final Identifier COMBAT = id("combat");
    private static final Identifier NOVICE = id("novice");
    private static final Identifier WARRIOR = id("warrior");
    private static final Identifier FOUNDATION = id("foundation");
    private static final Identifier STRIKE = id("strike");

    @Test
    void adjustsActivityXpIdempotentlyWithoutRewritingCareerProgress() {
        var career = new RpgPlayerState.CareerProgress(300, 2, 1, Map.of());
        RpgPlayerSavedData state = state(new RpgPlayerState(
                Map.of(COMBAT, 10L), Map.of(NOVICE, career), Optional.of(NOVICE),
                Map.of(), Map.of(), Set.of(), List.of(), List.of(), 4));

        var added = RpgAdministrativeMutationService.adjustActivityXp(
                state, definitions(), PLAYER, COMBAT, 5, 10, 100, uuid(10), "admin:" + uuid(9));
        assertEquals(RpgAdministrativeMutationService.Status.SUCCESS, added.status());
        assertEquals(15, state.state(PLAYER).activityXp().get(COMBAT));
        assertEquals(career, state.state(PLAYER).careers().get(NOVICE));
        assertEquals(RpgPlayerState.ProgressionProvenance.Kind.ADMIN_ACTIVITY_XP,
                state.state(PLAYER).provenance().getLast().kind());
        assertEquals(4, state.state(PLAYER).lastActiveSkillRequestId());

        var replay = RpgAdministrativeMutationService.adjustActivityXp(
                state, definitions(), PLAYER, COMBAT, 5, 10, 100, uuid(10), "admin:" + uuid(9));
        assertEquals(RpgAdministrativeMutationService.Status.DUPLICATE, replay.status());
        assertEquals(15, state.state(PLAYER).activityXp().get(COMBAT));

        assertEquals(RpgAdministrativeMutationService.Status.OVERFLOW,
                RpgAdministrativeMutationService.adjustActivityXp(
                        state, definitions(), PLAYER, COMBAT, -20, 15, 101, uuid(11), "admin:" + uuid(9)).status());
        assertEquals(RpgAdministrativeMutationService.Status.STATE_CONFLICT,
                RpgAdministrativeMutationService.adjustActivityXp(
                        state, definitions(), PLAYER, COMBAT, 1, 10, 102, uuid(12), "admin:" + uuid(9)).status());

        var removed = RpgAdministrativeMutationService.adjustActivityXp(
                state, definitions(), PLAYER, COMBAT, -15, 15, 103, uuid(13), "admin:" + uuid(9));
        assertEquals(RpgAdministrativeMutationService.Status.SUCCESS, removed.status());
        assertFalse(state.state(PLAYER).activityXp().containsKey(COMBAT));
        assertEquals(state.state(PLAYER), roundTrip(RpgPlayerSavedData.CODEC, state).state(PLAYER));
    }

    @Test
    void promotionRecoveryRequiresMaxRankedParentsButBypassesActivityThresholds() {
        RpgPlayerSavedData missingParent = state(RpgPlayerState.EMPTY);
        assertEquals(RpgAdministrativeMutationService.Status.MISSING_PARENT,
                RpgAdministrativeMutationService.recoverPromotion(
                        missingParent, definitions(), PLAYER, WARRIOR, 100, uuid(20), "admin:" + uuid(9)).status());

        RpgPlayerSavedData lowRankParent = state(new RpgPlayerState(
                Map.of(),
                Map.of(NOVICE, new RpgPlayerState.CareerProgress(0, 0, 0, Map.of())),
                Optional.of(NOVICE), Map.of(), Map.of(), List.of()));
        assertEquals(RpgAdministrativeMutationService.Status.PARENT_RANK_TOO_LOW,
                RpgAdministrativeMutationService.recoverPromotion(
                        lowRankParent, definitions(), PLAYER, WARRIOR,
                        101, uuid(21), "admin:" + uuid(9)).status());

        RpgPlayerSavedData state = state(new RpgPlayerState(
                Map.of(),
                Map.of(NOVICE, new RpgPlayerState.CareerProgress(10, 1, 0, Map.of())),
                Optional.of(NOVICE), Map.of(), Map.of(), List.of()));
        var recovered = RpgAdministrativeMutationService.recoverPromotion(
                state, definitions(), PLAYER, WARRIOR, 102, uuid(22), "admin:" + uuid(9));
        assertEquals(RpgAdministrativeMutationService.Status.SUCCESS, recovered.status());
        assertTrue(state.state(PLAYER).careers().containsKey(WARRIOR));
        assertEquals(Optional.of(WARRIOR), state.state(PLAYER).activeCareer());
        assertEquals(RpgPlayerState.ProgressionProvenance.Kind.ADMIN_PROMOTION,
                state.state(PLAYER).careerProvenance().getLast().kind());
        assertEquals(RpgAdministrativeMutationService.Status.DUPLICATE,
                RpgAdministrativeMutationService.recoverPromotion(
                        state, definitions(), PLAYER, WARRIOR, 102, uuid(22), "admin:" + uuid(9)).status());
    }

    @Test
    void administrativeResetUsesTheExactDependencyPlanWithoutChargingCurrency() {
        var novice = new RpgPlayerState.CareerProgress(100, 1, 0, Map.of(FOUNDATION, 1));
        var warrior = new RpgPlayerState.CareerProgress(100, 1, 0, Map.of(STRIKE, 1));
        RpgPlayerSavedData state = state(new RpgPlayerState(
                Map.of(), Map.of(NOVICE, novice, WARRIOR, warrior), Optional.of(WARRIOR),
                Map.of(0, STRIKE), Map.of(STRIKE, 999L), List.of()));
        SkillResetPlan plan = RpgSkillService.prepareReset(
                state, definitions(), PLAYER, SkillResetPlan.Mode.FULL, NOVICE).plan().orElseThrow();

        var reset = RpgAdministrativeMutationService.applySkillReset(
                state, definitions(), PLAYER, plan, 200, uuid(30), "admin:" + uuid(9));
        assertEquals(RpgAdministrativeMutationService.Status.SUCCESS, reset.status());
        assertTrue(state.state(PLAYER).careers().get(NOVICE).learnedSkills().isEmpty());
        assertTrue(state.state(PLAYER).careers().get(WARRIOR).learnedSkills().isEmpty());
        assertTrue(state.state(PLAYER).activeSkillSlots().isEmpty());
        assertTrue(state.state(PLAYER).cooldowns().isEmpty());
        assertEquals(3, state.state(PLAYER).careers().get(NOVICE).skillPoints());
        assertEquals(2, state.state(PLAYER).careers().get(WARRIOR).skillPoints());
        assertEquals(RpgPlayerState.ProgressionProvenance.Kind.ADMIN_SKILL_RESET,
                state.state(PLAYER).careerProvenance().getLast().kind());
        assertEquals(RpgAdministrativeMutationService.Status.DUPLICATE,
                RpgAdministrativeMutationService.applySkillReset(
                        state, definitions(), PLAYER, plan, 200, uuid(30), "admin:" + uuid(9)).status());
    }

    private static RpgPlayerSavedData state(RpgPlayerState player) {
        RpgPlayerSavedData state = new RpgPlayerSavedData();
        assertTrue(state.commit(PLAYER, player));
        return state;
    }

    private static RpgDefinitionSnapshot definitions() {
        return RpgDefinitionSnapshot.compile(
                List.of(new RpgDefinitionSnapshot.ActivitySource(
                        id("activities/combat"), "test", COMBAT,
                        new ActivityDefinition("activity.rovenfall.combat", List.of(10L, 20L)))),
                List.of(
                        new RpgDefinitionSnapshot.CareerSource(
                                id("careers/novice"), "test", NOVICE,
                                new CareerDefinition("career.rovenfall.novice", 1, List.of(),
                                        List.of(10L), 0, List.of(), 1)),
                        new RpgDefinitionSnapshot.CareerSource(
                                id("careers/warrior"), "test", WARRIOR,
                                new CareerDefinition("career.rovenfall.warrior", 2, List.of(NOVICE),
                                        List.of(10L), 0,
                                        List.of(new CareerDefinition.ActivityRequirement(COMBAT, 2)), 1))),
                List.of(
                        new RpgDefinitionSnapshot.SkillSource(
                                id("skills/foundation"), "test", FOUNDATION,
                                new SkillDefinition(
                                        "skill.rovenfall.foundation", NOVICE, SkillDefinition.Kind.PASSIVE,
                                        1, 3, List.of(), Optional.empty(),
                                        Optional.of(new SkillDefinition.PassiveEffect(
                                                SkillDefinition.EffectType.DAMAGE_DEALT, 100)), Optional.empty())),
                        new RpgDefinitionSnapshot.SkillSource(
                                id("skills/strike"), "test", STRIKE,
                                new SkillDefinition(
                                        "skill.rovenfall.strike", WARRIOR, SkillDefinition.Kind.ACTIVE,
                                        1, 2, List.of(new SkillDefinition.Prerequisite(FOUNDATION, 1)),
                                        Optional.of(20), Optional.empty(),
                                        Optional.of(new SkillDefinition.ActiveEffect(
                                                SkillDefinition.EffectType.DAMAGE_DEALT,
                                                SkillDefinition.TargetType.LIVING_ENTITY,
                                                100, 20, 4.0))))));
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("rovenfall", path);
    }

    private static UUID uuid(long value) {
        return new UUID(0L, value);
    }
}
