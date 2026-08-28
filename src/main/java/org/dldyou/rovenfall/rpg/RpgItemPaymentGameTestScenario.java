package org.dldyou.rovenfall.rpg;

import java.util.List;
import java.util.UUID;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import org.dldyou.rovenfall.administration.CareerPromotionPaymentService;
import org.dldyou.rovenfall.administration.EconomyConfig;
import org.dldyou.rovenfall.administration.PlatformSavedData;
import org.dldyou.rovenfall.administration.RpgSkillOperation;

/** Package-local journal scenario exposed only to the mod's registered GameTests. */
public final class RpgItemPaymentGameTestScenario {
    private RpgItemPaymentGameTestScenario() {
    }

    public static boolean platformRootSavedFirst(
            ServerPlayer player,
            Identifier target,
            Identifier item,
            int count,
            long timestampEpochMillis) {
        PlatformSavedData platform = PlatformSavedData.get(player.level().getServer());
        UUID transactionId = PlayerCareerPromotionService.transactionId(player.getUUID(), target);
        long before = RpgItemPayment.owned(player, item);
        List<RpgItemCost> costs = List.of(new RpgItemCost(item, count));
        CareerPromotionPaymentService.Result payment = CareerPromotionPaymentService.begin(
                platform, player.getUUID(), target, 0, costs, List.of(before), List.of(before - count),
                timestampEpochMillis, transactionId, EconomyConfig.initialBalance(), EconomyConfig.maximumBalance());
        if (payment.status() != CareerPromotionPaymentService.Status.SUCCESS) {
            return false;
        }
        PlayerCareerPromotionService.recoverPlayer(player.level().getServer(), player);
        return platform.rpgSkillOperation(transactionId)
                .filter(operation -> operation.phase() == RpgSkillOperation.Phase.COMPLETED).isPresent()
                && RpgItemPayment.owned(player, item) == before - count;
    }

    public static boolean rpgRootSavedFirst(
            ServerPlayer player,
            Identifier target,
            Identifier item,
            int count,
            long timestampEpochMillis) {
        RpgPlayerSavedData rpg = RpgPlayerSavedData.get(player.level().getServer());
        PlatformSavedData platform = PlatformSavedData.get(player.level().getServer());
        UUID transactionId = PlayerCareerPromotionService.transactionId(player.getUUID(), target);
        long before = RpgItemPayment.owned(player, item);
        RpgPlayerState current = rpg.state(player.getUUID());
        List<RpgPlayerState.ProgressionProvenance> evidence = CareerProgressionService.appendCareerEvidence(
                current, new RpgPlayerState.ProgressionProvenance(
                        RpgPlayerState.ProgressionProvenance.Kind.CAREER_PROMOTION,
                        target, 1, timestampEpochMillis, transactionId, "player_gui",
                        current.activeCareer(), List.of(new RpgItemCost(item, count)),
                        List.of(before), List.of(before - count), java.util.Optional.empty()));
        RpgPlayerState candidate = new RpgPlayerState(
                current.activityXp(), current.careers(), current.activeCareer(), current.activeSkillSlots(),
                current.cooldowns(), current.explorationDiscoveries(),
                CareerProgressionService.activityEvidence(current), evidence,
                current.lastActiveSkillRequestId());
        if (!rpg.commit(player.getUUID(), candidate)) {
            return false;
        }
        PlayerCareerPromotionService.recoverPlayer(player.level().getServer(), player);
        return platform.rpgSkillOperation(transactionId)
                .filter(operation -> operation.phase() == RpgSkillOperation.Phase.COMPLETED).isPresent()
                && RpgItemPayment.owned(player, item) == before - count;
    }

    public static boolean expiredRpgRootPreservesMarkerForManualRecovery(
            ServerPlayer player,
            Identifier target,
            Identifier item,
            int count,
            long timestampEpochMillis) {
        RpgPlayerSavedData rpg = RpgPlayerSavedData.get(player.level().getServer());
        PlatformSavedData platform = PlatformSavedData.get(player.level().getServer());
        UUID transactionId = PlayerCareerPromotionService.transactionId(player.getUUID(), target);
        long before = RpgItemPayment.owned(player, item);
        long beforeBalance = platform.economyBalance(player.getUUID()).orElseThrow();
        long currencyCost = 75;
        List<RpgItemCost> costs = List.of(new RpgItemCost(item, count));
        RpgSkillOperation operation = RpgSkillOperation.careerPromotion(
                player.getUUID(), target, currencyCost, costs, timestampEpochMillis,
                RpgSkillOperation.Phase.COMPLETED);
        if (CareerProgressionService.promote(
                rpg, RpgDefinitionReloadListener.snapshot(player.level().getServer()), player.getUUID(), target,
                timestampEpochMillis, transactionId, PlayerCareerPromotionService.paidSource(currencyCost), costs,
                List.of(before), List.of(before - count)).status() != CareerProgressionService.Status.SUCCESS
                || RpgItemPayment.prepare(player, transactionId, operation).status()
                        != RpgItemPayment.Status.SUCCESS) {
            return false;
        }
        PlayerCareerPromotionService.recoverPlayer(player.level().getServer(), player);
        PlayerCareerPromotionService.recoverPlayer(player.level().getServer(), player);
        PlayerCareerPromotionService.Result replay = PlayerCareerPromotionService.promote(
                player, target, System.currentTimeMillis());
        return replay.status() == PlayerCareerPromotionService.Status.PAYMENT_FAILED
                && platform.rpgSkillOperation(transactionId).isEmpty()
                && platform.economyReceipt(transactionId).isEmpty()
                && platform.economyBalance(player.getUUID()).orElseThrow() == beforeBalance
                && RpgItemPayment.read(player).marker()
                        .filter(marker -> marker.transactionId().equals(transactionId)
                                && marker.currencyCost() == currencyCost)
                        .isPresent()
                && RpgItemPayment.owned(player, item) == before - count;
    }

    public static boolean orphanPromotionRollsBack(
            ServerPlayer player,
            Identifier target,
            Identifier item,
            int count,
            long timestampEpochMillis) {
        UUID transactionId = UUID.randomUUID();
        long before = RpgItemPayment.owned(player, item);
        RpgSkillOperation operation = RpgSkillOperation.careerPromotion(
                player.getUUID(), target, 0, List.of(new RpgItemCost(item, count)),
                timestampEpochMillis, RpgSkillOperation.Phase.ITEMS_CONSUMED);
        if (RpgItemPayment.prepare(player, transactionId, operation).status()
                != RpgItemPayment.Status.SUCCESS) {
            return false;
        }
        PlayerCareerPromotionService.recoverPlayer(player.level().getServer(), player);
        return PlatformSavedData.get(player.level().getServer()).rpgSkillOperation(transactionId).isEmpty()
                && RpgItemPayment.read(player).marker().isEmpty()
                && RpgItemPayment.owned(player, item) == before;
    }
}
