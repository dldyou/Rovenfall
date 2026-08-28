package org.dldyou.rovenfall.rpg;

import static org.dldyou.rovenfall.PersistenceTestHarness.roundTrip;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.resources.Identifier;
import org.dldyou.rovenfall.administration.CareerPromotionPaymentService;
import org.dldyou.rovenfall.administration.EconomyService;
import org.dldyou.rovenfall.administration.PlatformSavedData;
import org.dldyou.rovenfall.administration.RpgSkillOperation;
import org.junit.jupiter.api.Test;

final class PlayerCareerPromotionServiceTest {
    private static final UUID PLAYER = new UUID(0L, 1L);
    private static final Identifier CAREER = Identifier.fromNamespaceAndPath("rovenfall", "warrior");
    private static final long COST = 500;

    @Test
    void promotionPaymentIdentityIsStableForRecoveryAndScopedPerCareer() {
        UUID player = UUID.randomUUID();
        Identifier warrior = Identifier.fromNamespaceAndPath("rovenfall", "warrior");
        Identifier guardian = Identifier.fromNamespaceAndPath("rovenfall", "guardian");

        assertEquals(
                PlayerCareerPromotionService.transactionId(player, warrior),
                PlayerCareerPromotionService.transactionId(player, warrior));
        assertNotEquals(
                PlayerCareerPromotionService.transactionId(player, warrior),
                PlayerCareerPromotionService.transactionId(player, guardian));
        assertNotEquals(
                PlayerCareerPromotionService.transactionId(player, warrior),
                PlayerCareerPromotionService.transactionId(UUID.randomUUID(), warrior));
    }

    @Test
    void paidPromotionEvidenceCarriesABoundedRecoveryCost() {
        assertEquals(250L, PlayerCareerPromotionService.paidCost(
                PlayerCareerPromotionService.paidSource(250)).orElseThrow());
        assertTrue(PlayerCareerPromotionService.paidCost("player_gui").isEmpty());
        assertTrue(PlayerCareerPromotionService.paidCost("player_paid_promotion:-1").isEmpty());
        assertTrue(PlayerCareerPromotionService.paidCost("player_paid_promotion:not-a-number").isEmpty());
    }

    @Test
    void pendingPaymentRecoversPromotionAndCompletesExactlyOnce() {
        PlatformSavedData platform = fundedPlatform();
        RpgPlayerSavedData rpg = new RpgPlayerSavedData();
        UUID transaction = PlayerCareerPromotionService.transactionId(PLAYER, CAREER);
        assertEquals(CareerPromotionPaymentService.Status.SUCCESS,
                CareerPromotionPaymentService.begin(
                        platform, PLAYER, CAREER, COST, 2_000, transaction, 0, 10_000).status());
        platform = roundTrip(PlatformSavedData.CODEC, platform);
        rpg = roundTrip(RpgPlayerSavedData.CODEC, rpg);

        PlayerCareerPromotionService.recoverPlayer(
                platform, rpg, definitions(), PLAYER, 3_000, 0, 10_000);

        assertTrue(rpg.state(PLAYER).careers().containsKey(CAREER));
        assertEquals(1_500, platform.economyBalance(PLAYER).orElseThrow());
        assertEquals(RpgSkillOperation.Phase.COMPLETED,
                platform.rpgSkillOperation(transaction).orElseThrow().phase());
        PlayerCareerPromotionService.recoverPlayer(
                platform, rpg, definitions(), PLAYER, 4_000, 0, 10_000);
        assertEquals(1_500, platform.economyBalance(PLAYER).orElseThrow());
    }

    @Test
    void promotionEvidenceRecoversMissingPlatformPayment() {
        PlatformSavedData platform = fundedPlatform();
        RpgPlayerSavedData rpg = new RpgPlayerSavedData();
        UUID transaction = PlayerCareerPromotionService.transactionId(PLAYER, CAREER);
        assertEquals(CareerProgressionService.Status.SUCCESS,
                CareerProgressionService.promote(
                        rpg, definitions(), PLAYER, CAREER, 2_000, transaction,
                        PlayerCareerPromotionService.paidSource(COST)).status());

        PlayerCareerPromotionService.recoverPlayer(
                platform, rpg, definitions(), PLAYER, 3_000, 0, 10_000);

        assertEquals(1_500, platform.economyBalance(PLAYER).orElseThrow());
        RpgSkillOperation operation = platform.rpgSkillOperation(transaction).orElseThrow();
        assertEquals(RpgSkillOperation.Kind.CAREER_PROMOTION, operation.kind());
        assertEquals(RpgSkillOperation.Phase.COMPLETED, operation.phase());
    }

    @Test
    void completedPlatformPaymentRecoversAnOlderRpgRoot() {
        PlatformSavedData platform = fundedPlatform();
        RpgPlayerSavedData applied = new RpgPlayerSavedData();
        UUID transaction = PlayerCareerPromotionService.transactionId(PLAYER, CAREER);
        assertEquals(CareerPromotionPaymentService.Status.SUCCESS,
                CareerPromotionPaymentService.begin(
                        platform, PLAYER, CAREER, COST, 2_000, transaction, 0, 10_000).status());
        assertEquals(CareerProgressionService.Status.SUCCESS,
                CareerProgressionService.promote(
                        applied, definitions(), PLAYER, CAREER, 2_000, transaction,
                        PlayerCareerPromotionService.paidSource(COST)).status());
        assertEquals(CareerPromotionPaymentService.Status.SUCCESS,
                CareerPromotionPaymentService.complete(platform, PLAYER, transaction, 2_100).status());
        platform = roundTrip(PlatformSavedData.CODEC, platform);
        RpgPlayerSavedData olderRpgRoot = roundTrip(
                RpgPlayerSavedData.CODEC, new RpgPlayerSavedData());

        PlayerCareerPromotionService.recoverPlayer(
                platform, olderRpgRoot, definitions(), PLAYER, 3_000, 0, 10_000);

        assertTrue(olderRpgRoot.state(PLAYER).careers().containsKey(CAREER));
        assertEquals(1_500, platform.economyBalance(PLAYER).orElseThrow());
        assertEquals(RpgSkillOperation.Phase.COMPLETED,
                platform.rpgSkillOperation(transaction).orElseThrow().phase());
    }

    @Test
    void expiredPromotionEvidenceCannotRecreateADeletedPayment() {
        PlatformSavedData platform = fundedPlatform();
        RpgPlayerSavedData rpg = new RpgPlayerSavedData();
        UUID transaction = PlayerCareerPromotionService.transactionId(PLAYER, CAREER);
        assertEquals(CareerProgressionService.Status.SUCCESS,
                CareerProgressionService.promote(
                        rpg, definitions(), PLAYER, CAREER, 2_000, transaction,
                        PlayerCareerPromotionService.paidSource(COST)).status());

        PlayerCareerPromotionService.recoverPlayer(
                platform, rpg, definitions(), PLAYER, 2_000 + 31L * 24 * 60 * 60 * 1_000, 0, 10_000);

        assertEquals(2_000, platform.economyBalance(PLAYER).orElseThrow());
        assertTrue(platform.rpgSkillOperation(transaction).isEmpty());
    }

    @Test
    void unrelatedProvenanceWithThePromotionUuidCannotCompletePayment() {
        PlatformSavedData platform = fundedPlatform();
        RpgPlayerSavedData rpg = new RpgPlayerSavedData();
        UUID transaction = PlayerCareerPromotionService.transactionId(PLAYER, CAREER);
        assertEquals(CareerPromotionPaymentService.Status.SUCCESS,
                CareerPromotionPaymentService.begin(
                        platform, PLAYER, CAREER, COST, 2_000, transaction, 0, 10_000).status());
        RpgPlayerState.ProgressionProvenance unrelated = new RpgPlayerState.ProgressionProvenance(
                RpgPlayerState.ProgressionProvenance.Kind.CAREER_SWITCH,
                CAREER, 0, 2_000, transaction, "crafted_collision");
        assertTrue(rpg.commit(PLAYER, new RpgPlayerState(
                Map.of(), Map.of(), Optional.empty(), Map.of(), Map.of(), Set.of(),
                List.of(), List.of(unrelated))));

        PlayerCareerPromotionService.recoverPlayer(
                platform, rpg, definitions(), PLAYER, 3_000, 0, 10_000);

        assertTrue(rpg.state(PLAYER).careers().isEmpty());
        assertEquals(RpgSkillOperation.Phase.PENDING,
                platform.rpgSkillOperation(transaction).orElseThrow().phase());
    }

    private static PlatformSavedData fundedPlatform() {
        PlatformSavedData state = new PlatformSavedData();
        assertEquals(EconomyService.TransactionStatus.SUCCESS,
                EconomyService.award(
                        state, PLAYER, 2_000, "seed", 1_000, new UUID(0L, 10L), 0, 10_000).status());
        return state;
    }

    private static RpgDefinitionSnapshot definitions() {
        return RpgDefinitionSnapshot.compile(
                List.of(),
                List.of(new RpgDefinitionSnapshot.CareerSource(
                        Identifier.fromNamespaceAndPath("rovenfall", "careers/warrior"), "test", CAREER,
                        new CareerDefinition(
                                "career.rovenfall.warrior", 1, List.of(), List.of(100L), COST, List.of(), 1))),
                List.of());
    }
}
