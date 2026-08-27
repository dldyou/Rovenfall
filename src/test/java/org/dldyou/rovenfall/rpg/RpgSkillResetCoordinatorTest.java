package org.dldyou.rovenfall.rpg;

import static org.dldyou.rovenfall.PersistenceTestHarness.roundTrip;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

final class RpgSkillResetCoordinatorTest {
    private static final UUID PLAYER = uuid(1);
    private static final Identifier WARRIOR = id("warrior");
    private static final Identifier STRIKE = id("strike");
    private static final long COST = 500;

    @Test
    void recoversPersistedPaymentBeforeRpgMutationExactlyOnce() {
        PlatformSavedData platform = fundedPlatform();
        RpgPlayerSavedData rpg = learnedState(1);
        SkillResetPlan plan = plan(rpg);
        UUID transaction = uuid(100);
        assertEquals(RpgSkillPaymentService.Status.SUCCESS,
                RpgSkillPaymentService.begin(
                        platform, PLAYER, plan, COST, 2_000, transaction, 0, 10_000).status());
        platform = roundTrip(PlatformSavedData.CODEC, platform);
        rpg = roundTrip(RpgPlayerSavedData.CODEC, rpg);

        RpgSkillResetCoordinator.recoverPlayer(
                platform, rpg, definitions(), PLAYER, 3_000, 0, 10_000);

        assertTrue(rpg.state(PLAYER).careers().get(WARRIOR).learnedSkills().isEmpty());
        assertEquals(1, rpg.state(PLAYER).careers().get(WARRIOR).skillPoints());
        assertEquals(1_500, platform.economyBalance(PLAYER).orElseThrow());
        assertEquals(RpgSkillOperation.Phase.COMPLETED,
                platform.rpgSkillOperation(transaction).orElseThrow().phase());
        RpgSkillResetCoordinator.recoverPlayer(
                platform, rpg, definitions(), PLAYER, 4_000, 0, 10_000);
        assertEquals(1_500, platform.economyBalance(PLAYER).orElseThrow());
        assertEquals(1, rpg.state(PLAYER).careers().get(WARRIOR).skillPoints());
    }

    @Test
    void completesPendingPlatformOperationWhenRpgMutationAlreadyPersisted() {
        PlatformSavedData platform = fundedPlatform();
        RpgPlayerSavedData rpg = learnedState(1);
        SkillResetPlan plan = plan(rpg);
        UUID transaction = uuid(200);
        assertEquals(RpgSkillPaymentService.Status.SUCCESS,
                RpgSkillPaymentService.begin(
                        platform, PLAYER, plan, COST, 2_000, transaction, 0, 10_000).status());
        assertEquals(RpgSkillService.Status.SUCCESS,
                RpgSkillService.applyReset(
                        rpg, definitions(), PLAYER, plan, COST, 2_000, transaction).status());
        platform = roundTrip(PlatformSavedData.CODEC, platform);
        rpg = roundTrip(RpgPlayerSavedData.CODEC, rpg);

        RpgSkillResetCoordinator.recoverPlayer(
                platform, rpg, definitions(), PLAYER, 3_000, 0, 10_000);

        assertEquals(RpgSkillOperation.Phase.COMPLETED,
                platform.rpgSkillOperation(transaction).orElseThrow().phase());
        assertEquals(1_500, platform.economyBalance(PLAYER).orElseThrow());
        assertEquals(1, rpg.state(PLAYER).careers().get(WARRIOR).skillPoints());
    }

    @Test
    void recoversPaymentWhenRpgRootReachedDiskFirst() {
        PlatformSavedData platform = fundedPlatform();
        RpgPlayerSavedData rpg = learnedState(1);
        SkillResetPlan plan = plan(rpg);
        UUID transaction = uuid(300);
        assertEquals(RpgSkillService.Status.SUCCESS,
                RpgSkillService.applyReset(
                        rpg, definitions(), PLAYER, plan, COST, 2_000, transaction).status());

        RpgSkillResetCoordinator.recoverPlayer(
                platform, rpg, definitions(), PLAYER, 3_000, 0, 10_000);

        assertEquals(1_500, platform.economyBalance(PLAYER).orElseThrow());
        RpgSkillOperation operation = platform.rpgSkillOperation(transaction).orElseThrow();
        assertEquals(RpgSkillOperation.Phase.COMPLETED, operation.phase());
        assertTrue(operation.plan().isEmpty());
    }

    @Test
    void stalePendingPlanFailsClosedAndRemainsRecoverable() {
        PlatformSavedData platform = fundedPlatform();
        RpgPlayerSavedData rpg = learnedState(1);
        SkillResetPlan plan = plan(rpg);
        UUID transaction = uuid(400);
        assertEquals(RpgSkillPaymentService.Status.SUCCESS,
                RpgSkillPaymentService.begin(
                        platform, PLAYER, plan, COST, 2_000, transaction, 0, 10_000).status());
        assertTrue(rpg.commit(PLAYER, playerState(2)));

        RpgSkillResetCoordinator.recoverPlayer(
                platform, rpg, definitions(), PLAYER, 3_000, 0, 10_000);

        assertEquals(Map.of(STRIKE, 2), rpg.state(PLAYER).careers().get(WARRIOR).learnedSkills());
        assertEquals(1_500, platform.economyBalance(PLAYER).orElseThrow());
        assertEquals(RpgSkillOperation.Phase.PENDING,
                platform.rpgSkillOperation(transaction).orElseThrow().phase());
    }

    @Test
    void completedTransactionRetryConvergesWithoutPreparingOrChargingAgain() {
        PlatformSavedData platform = fundedPlatform();
        RpgPlayerSavedData rpg = learnedState(1);
        UUID transaction = uuid(500);

        assertEquals(RpgSkillResetCoordinator.Status.SUCCESS,
                reset(platform, rpg, transaction).status());
        assertEquals(RpgSkillResetCoordinator.Status.SUCCESS,
                reset(platform, rpg, transaction).status());
        assertEquals(1_500, platform.economyBalance(PLAYER).orElseThrow());
        assertEquals(1, rpg.state(PLAYER).careers().get(WARRIOR).skillPoints());
    }

    private static RpgSkillResetCoordinator.Result reset(
            PlatformSavedData platform, RpgPlayerSavedData rpg, UUID transaction) {
        return RpgSkillResetCoordinator.reset(
                platform, rpg, definitions(), PLAYER, SkillResetPlan.Mode.BRANCH, STRIKE,
                COST, 2_000, transaction, 0, 10_000);
    }

    private static SkillResetPlan plan(RpgPlayerSavedData state) {
        return RpgSkillService.prepareReset(
                state, definitions(), PLAYER, SkillResetPlan.Mode.BRANCH, STRIKE).plan().orElseThrow();
    }

    private static PlatformSavedData fundedPlatform() {
        PlatformSavedData state = new PlatformSavedData();
        assertEquals(EconomyService.TransactionStatus.SUCCESS,
                EconomyService.award(
                        state, PLAYER, 2_000, "seed", 1_000, uuid(10), 0, 10_000).status());
        return state;
    }

    private static RpgPlayerSavedData learnedState(int rank) {
        RpgPlayerSavedData state = new RpgPlayerSavedData();
        assertTrue(state.commit(PLAYER, playerState(rank)));
        return state;
    }

    private static RpgPlayerState playerState(int rank) {
        RpgPlayerState.EMPTY.isValid();
        return new RpgPlayerState(
                Map.of(), Map.of(WARRIOR,
                new RpgPlayerState.CareerProgress(100, 1, 0, Map.of(STRIKE, rank))),
                Optional.of(WARRIOR), Map.of(0, STRIKE), Map.of(STRIKE, 100L), List.of());
    }

    private static RpgDefinitionSnapshot definitions() {
        return RpgDefinitionSnapshot.compile(
                List.of(),
                List.of(new RpgDefinitionSnapshot.CareerSource(
                        id("careers/warrior"), "test", WARRIOR,
                        new CareerDefinition("career.rovenfall.warrior", 1, List.of(),
                                List.of(100L), 0, List.of(), 1))),
                List.of(new RpgDefinitionSnapshot.SkillSource(
                        id("skills/strike"), "test", STRIKE,
                        new SkillDefinition("skill.rovenfall.strike", WARRIOR,
                                SkillDefinition.Kind.ACTIVE, 3, 1, List.of(), Optional.of(20), Optional.empty(),
                                Optional.of(new SkillDefinition.ActiveEffect(
                                        SkillDefinition.EffectType.DAMAGE_DEALT,
                                        SkillDefinition.TargetType.LIVING_ENTITY,
                                        100,
                                        20,
                                        4.0))))));
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("rovenfall", path);
    }

    private static UUID uuid(long least) {
        return new UUID(0L, least);
    }
}
