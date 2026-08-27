package org.dldyou.rovenfall.rpg;

import static org.dldyou.rovenfall.PersistenceTestHarness.roundTrip;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.Identifier;
import org.dldyou.rovenfall.administration.EconomyService;
import org.dldyou.rovenfall.administration.PlatformSavedData;
import org.dldyou.rovenfall.administration.RpgSkillOperation;
import org.dldyou.rovenfall.administration.RpgSkillPaymentService;
import org.junit.jupiter.api.Test;

/** Milestone gate joining progression, skills, payment, persistence, reload, and recovery. */
final class RpgMilestoneWorkflowTest {
    private static final UUID PLAYER = uuid(1);
    private static final Identifier COMBAT = id("combat");
    private static final Identifier NOVICE = id("novice");
    private static final Identifier WARRIOR = id("warrior");
    private static final Identifier GUARDIAN = id("guardian");
    private static final Identifier BERSERKER = id("berserker");
    private static final Identifier STURDY_BODY = id("sturdy_body");
    private static final Identifier POWER_STRIKE = id("power_strike");
    private static final Identifier DIMENSION = Identifier.withDefaultNamespace("overworld");
    private static final long RESET_COST = 25;

    @Test
    void progressionSurvivesPaymentPersistenceReloadAndRecoveryAsOneWorkflow() {
        RpgDefinitionStore store = new RpgDefinitionStore();
        Sources initial = sources(false);
        RpgDefinitionSnapshot definitions = store.replace(
                initial.activities(), initial.careers(), initial.skills());
        PlatformSavedData platform = new PlatformSavedData();
        RpgPlayerSavedData rpg = new RpgPlayerSavedData();

        assertEquals(CareerProgressionService.Status.SUCCESS,
                CareerProgressionService.promote(
                        rpg, definitions, PLAYER, NOVICE, 1_000, uuid(10), "milestone:novice").status());
        assertAward(rpg, definitions, 10, 2_000, uuid(11), "milestone:novice_rank");
        assertEquals(2, rpg.state(PLAYER).careers().get(NOVICE).skillPoints());
        assertEquals(RpgSkillService.Status.SUCCESS,
                RpgSkillService.learn(
                        rpg, definitions, PLAYER, STURDY_BODY,
                        3_000, uuid(12), "milestone:passive").status());

        assertEquals(CareerProgressionService.Status.SUCCESS,
                CareerProgressionService.promote(
                        rpg, definitions, PLAYER, WARRIOR, 4_000, uuid(13), "milestone:warrior").status());
        assertAward(rpg, definitions, 10, 6_000, uuid(14), "milestone:warrior_rank");
        assertEquals(RpgSkillService.Status.SUCCESS,
                RpgSkillService.learn(
                        rpg, definitions, PLAYER, POWER_STRIKE,
                        7_000, uuid(15), "milestone:active").status());

        assertEquals(CareerProgressionService.Status.SUCCESS,
                CareerProgressionService.promote(
                        rpg, definitions, PLAYER, GUARDIAN, 8_000, uuid(16), "milestone:guardian").status());
        assertEquals(CareerProgressionService.Status.SUCCESS,
                CareerProgressionService.promote(
                        rpg, definitions, PLAYER, BERSERKER, 9_000, uuid(17), "milestone:berserker").status());
        assertTrue(rpg.state(PLAYER).careers().keySet().containsAll(
                List.of(NOVICE, WARRIOR, GUARDIAN, BERSERKER)));
        assertEquals(Optional.of(BERSERKER), rpg.state(PLAYER).activeCareer());
        assertEquals(11F, RpgPassiveSkillService.modifyDamage(
                definitions, rpg.state(PLAYER), RpgPlayerState.EMPTY, 10F), 0.001F);

        assertEquals(RpgActiveSkillService.Status.SUCCESS,
                RpgActiveSkillService.assignSlot(
                        rpg, definitions, PLAYER, 0, Optional.of(POWER_STRIKE), 4,
                        10_000, uuid(18), "milestone:slot").status());
        RecordingGateway gateway = new RecordingGateway();
        var activation = RpgActiveSkillService.activate(
                rpg, definitions, store.revision(), PLAYER,
                new RpgActiveSkillService.ActivationRequest(store.revision(), 1, 0, DIMENSION, 42),
                4, 100, gateway);
        assertEquals(RpgActiveSkillService.Status.SUCCESS, activation.status());
        assertEquals(1, gateway.applications);
        assertEquals(120, rpg.state(PLAYER).cooldowns().get(POWER_STRIKE));

        assertEquals(EconomyService.TransactionStatus.SUCCESS,
                EconomyService.award(
                        platform, PLAYER, 100, "milestone seed", 11_000, uuid(19), 0, 1_000).status());
        UUID completedReset = uuid(20);
        var reset = RpgSkillResetCoordinator.reset(
                platform, rpg, definitions, PLAYER, SkillResetPlan.Mode.BRANCH, STURDY_BODY,
                RESET_COST, 12_000, completedReset, 0, 1_000);
        assertEquals(RpgSkillResetCoordinator.Status.SUCCESS, reset.status());
        assertEquals(75, platform.economyBalance(PLAYER).orElseThrow());
        assertSkillsAndRuntimeCleared(rpg);
        assertEquals(RpgSkillOperation.Phase.COMPLETED,
                platform.rpgSkillOperation(completedReset).orElseThrow().phase());

        platform = roundTrip(PlatformSavedData.CODEC, platform);
        rpg = roundTrip(RpgPlayerSavedData.CODEC, rpg);
        var replay = RpgSkillResetCoordinator.reset(
                platform, rpg, definitions, PLAYER, SkillResetPlan.Mode.BRANCH, STURDY_BODY,
                RESET_COST, 13_000, completedReset, 0, 1_000);
        assertEquals(RpgSkillResetCoordinator.Status.SUCCESS, replay.status());
        assertEquals(75, platform.economyBalance(PLAYER).orElseThrow());

        assertEquals(RpgSkillService.Status.SUCCESS,
                RpgSkillService.learn(
                        rpg, definitions, PLAYER, STURDY_BODY,
                        14_000, uuid(21), "milestone:relearn_passive").status());
        assertEquals(RpgSkillService.Status.SUCCESS,
                RpgSkillService.learn(
                        rpg, definitions, PLAYER, POWER_STRIKE,
                        15_000, uuid(22), "milestone:relearn_active").status());
        assertEquals(RpgActiveSkillService.Status.SUCCESS,
                RpgActiveSkillService.assignSlot(
                        rpg, definitions, PLAYER, 0, Optional.of(POWER_STRIKE), 4,
                        15_500, uuid(23), "milestone:rebind").status());
        assertEquals(RpgActiveSkillService.Status.SUCCESS,
                RpgActiveSkillService.activate(
                        rpg, definitions, store.revision(), PLAYER,
                        new RpgActiveSkillService.ActivationRequest(
                                store.revision(), 2, 0, DIMENSION, 42),
                        4, 200, gateway).status());
        SkillResetPlan pendingPlan = RpgSkillService.prepareReset(
                rpg, definitions, PLAYER, SkillResetPlan.Mode.BRANCH, STURDY_BODY).plan().orElseThrow();
        UUID pendingReset = uuid(24);
        assertEquals(RpgSkillPaymentService.Status.SUCCESS,
                RpgSkillPaymentService.begin(
                        platform, PLAYER, pendingPlan, RESET_COST,
                        16_000, pendingReset, 0, 1_000).status());
        assertEquals(50, platform.economyBalance(PLAYER).orElseThrow());
        assertEquals(RpgSkillOperation.Phase.PENDING,
                platform.rpgSkillOperation(pendingReset).orElseThrow().phase());
        assertSkillsAndRuntimePresent(rpg);

        platform = roundTrip(PlatformSavedData.CODEC, platform);
        rpg = roundTrip(RpgPlayerSavedData.CODEC, rpg);
        assertEquals(50, platform.economyBalance(PLAYER).orElseThrow());
        assertEquals(RpgSkillOperation.Phase.PENDING,
                platform.rpgSkillOperation(pendingReset).orElseThrow().phase());
        assertSkillsAndRuntimePresent(rpg);
        Sources extended = sources(true);
        RpgDefinitionSnapshot reloaded = store.replace(
                extended.activities(), extended.careers(), extended.skills());
        assertEquals(2, store.revision());
        assertTrue(reloaded.activity(id("mining")).isPresent());
        assertTrue(reloaded.skill(POWER_STRIKE).isPresent());

        RpgDefinitionSnapshot lastGood = store.current();
        assertThrows(RpgDefinitionSnapshot.ValidationException.class, () -> store.replace(
                List.of(activity("combat", List.of(5L, 10L, 20L))),
                List.of(career("broken", 2, List.of(id("missing")), List.of(), List.of(5L))),
                List.of()));
        assertSame(lastGood, store.current());
        assertEquals(2, store.revision());

        RpgSkillResetCoordinator.recoverPlayer(
                platform, rpg, store.current(), PLAYER, 17_000, 0, 1_000);
        assertEquals(50, platform.economyBalance(PLAYER).orElseThrow());
        assertSkillsAndRuntimeCleared(rpg);
        assertEquals(RpgSkillOperation.Phase.COMPLETED,
                platform.rpgSkillOperation(pendingReset).orElseThrow().phase());

        RpgSkillResetCoordinator.recoverPlayer(
                platform, rpg, store.current(), PLAYER, 18_000, 0, 1_000);
        assertEquals(50, platform.economyBalance(PLAYER).orElseThrow());
        PlatformSavedData persistedPlatform = roundTrip(PlatformSavedData.CODEC, platform);
        RpgPlayerSavedData persistedRpg = roundTrip(RpgPlayerSavedData.CODEC, rpg);
        assertEquals(RpgSkillOperation.Phase.COMPLETED,
                persistedPlatform.rpgSkillOperation(pendingReset).orElseThrow().phase());
        assertEquals(rpg.state(PLAYER), persistedRpg.state(PLAYER));
    }

    private static void assertAward(
            RpgPlayerSavedData rpg,
            RpgDefinitionSnapshot definitions,
            long amount,
            long timestamp,
            UUID transactionId,
            String source) {
        assertEquals(ActivityXpAwardService.Status.SUCCESS,
                ActivityXpAwardService.award(
                        rpg, definitions, PLAYER, COMBAT, amount,
                        timestamp, transactionId, source).status());
    }

    private static void assertSkillsAndRuntimeCleared(RpgPlayerSavedData rpg) {
        assertFalse(rpg.state(PLAYER).careers().get(NOVICE).learnedSkills().containsKey(STURDY_BODY));
        assertFalse(rpg.state(PLAYER).careers().get(WARRIOR).learnedSkills().containsKey(POWER_STRIKE));
        assertTrue(rpg.state(PLAYER).activeSkillSlots().isEmpty());
        assertTrue(rpg.state(PLAYER).cooldowns().isEmpty());
    }

    private static void assertSkillsAndRuntimePresent(RpgPlayerSavedData rpg) {
        assertEquals(1, rpg.state(PLAYER).careers().get(NOVICE).learnedSkills().get(STURDY_BODY));
        assertEquals(1, rpg.state(PLAYER).careers().get(WARRIOR).learnedSkills().get(POWER_STRIKE));
        assertEquals(POWER_STRIKE, rpg.state(PLAYER).activeSkillSlots().get(0));
        assertEquals(220, rpg.state(PLAYER).cooldowns().get(POWER_STRIKE));
    }

    private static Sources sources(boolean extended) {
        List<RpgDefinitionSnapshot.ActivitySource> activities = new ArrayList<>();
        activities.add(activity("combat", List.of(5L, 10L, 20L)));
        if (extended) {
            activities.add(activity("mining", List.of(5L)));
        }
        List<RpgDefinitionSnapshot.CareerSource> careers = List.of(
                career("novice", 1, List.of(), List.of(), List.of(5L, 10L)),
                career("warrior", 2, List.of(NOVICE), List.of(requirement(COMBAT, 2)), List.of(5L, 10L)),
                career("guardian", 3, List.of(WARRIOR), List.of(requirement(COMBAT, 3)), List.of(5L)),
                career("berserker", 3, List.of(WARRIOR), List.of(requirement(COMBAT, 3)), List.of(5L)));
        List<RpgDefinitionSnapshot.SkillSource> skills = List.of(
                new RpgDefinitionSnapshot.SkillSource(
                        file("skills/sturdy_body"), "milestone", STURDY_BODY,
                        new SkillDefinition(
                                "skill.rovenfall.sturdy_body", NOVICE, SkillDefinition.Kind.PASSIVE,
                                1, 1, List.of(), Optional.empty(),
                                Optional.of(new SkillDefinition.PassiveEffect(
                                        SkillDefinition.EffectType.DAMAGE_DEALT, 1_000)), Optional.empty())),
                new RpgDefinitionSnapshot.SkillSource(
                        file("skills/power_strike"), "milestone", POWER_STRIKE,
                        new SkillDefinition(
                                "skill.rovenfall.power_strike", WARRIOR, SkillDefinition.Kind.ACTIVE,
                                1, 2, List.of(new SkillDefinition.Prerequisite(STURDY_BODY, 1)),
                                Optional.of(20), Optional.empty(),
                                Optional.of(new SkillDefinition.ActiveEffect(
                                        SkillDefinition.EffectType.DAMAGE_DEALT,
                                        SkillDefinition.TargetType.LIVING_ENTITY,
                                        2_500, 10, 6.0)))));
        return new Sources(List.copyOf(activities), careers, skills);
    }

    private static RpgDefinitionSnapshot.ActivitySource activity(String path, List<Long> thresholds) {
        return new RpgDefinitionSnapshot.ActivitySource(
                file("activities/" + path), "milestone", id(path),
                new ActivityDefinition("activity.rovenfall." + path, thresholds));
    }

    private static RpgDefinitionSnapshot.CareerSource career(
            String path,
            int tier,
            List<Identifier> parents,
            List<CareerDefinition.ActivityRequirement> requirements,
            List<Long> thresholds) {
        return new RpgDefinitionSnapshot.CareerSource(
                file("careers/" + path), "milestone", id(path),
                new CareerDefinition(
                        "career.rovenfall." + path, tier, parents, thresholds,
                        0, requirements, 1));
    }

    private static CareerDefinition.ActivityRequirement requirement(Identifier activity, int level) {
        return new CareerDefinition.ActivityRequirement(activity, level);
    }

    private static Identifier file(String path) {
        return Identifier.fromNamespaceAndPath("rovenfall", "rovenfall/" + path + ".json");
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("rovenfall", path);
    }

    private static UUID uuid(long least) {
        return new UUID(0L, least);
    }

    private record Sources(
            List<RpgDefinitionSnapshot.ActivitySource> activities,
            List<RpgDefinitionSnapshot.CareerSource> careers,
            List<RpgDefinitionSnapshot.SkillSource> skills) {
    }

    private static final class RecordingGateway implements RpgActiveSkillService.EffectGateway {
        private int applications;

        @Override
        public Identifier dimension() {
            return DIMENSION;
        }

        @Override
        public boolean validate(SkillDefinition.ActiveEffect effect, int targetEntityId) {
            return targetEntityId == 42;
        }

        @Override
        public void apply(
                Identifier skillId,
                int rank,
                SkillDefinition.ActiveEffect effect,
                int targetEntityId,
                long gameTime) {
            applications++;
        }
    }
}
