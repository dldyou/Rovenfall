package org.dldyou.rovenfall.rpg;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import org.dldyou.rovenfall.administration.EconomyConfig;
import org.dldyou.rovenfall.administration.EconomyService;
import org.dldyou.rovenfall.administration.PlatformSavedData;

/** Server-owned player promotion coordinator; the client never supplies costs or transaction IDs. */
public final class PlayerCareerPromotionService {
    public enum Status {
        SUCCESS,
        STALE_OR_LOCKED,
        PAYMENT_FAILED,
        PROMOTION_FAILED
    }

    public record Result(
            Status status,
            CareerProgressionService.Result promotion,
            Optional<EconomyService.TransactionStatus> paymentStatus,
            long cost,
            long balance,
            UUID transactionId) {
        public Result {
            paymentStatus = paymentStatus == null ? Optional.empty() : paymentStatus;
        }
    }

    private PlayerCareerPromotionService() {
    }

    public static Result promote(MinecraftServer server, UUID playerId, Identifier careerId, long timestamp) {
        RpgPlayerSavedData rpg = RpgPlayerSavedData.get(server);
        RpgDefinitionSnapshot definitions = RpgDefinitionReloadListener.snapshot(server);
        PlatformSavedData platform = PlatformSavedData.get(server);
        UUID transactionId = transactionId(playerId, careerId);
        CareerDefinition definition = definitions.career(careerId).orElse(null);
        long balance = platform.economyBalance(playerId).orElse(EconomyConfig.initialBalance());
        if (definition == null) {
            CareerProgressionService.Result result = CareerProgressionService.promote(
                    rpg, definitions, playerId, careerId, timestamp, transactionId, "player_gui");
            return new Result(Status.PROMOTION_FAILED, result, Optional.empty(), 0, balance, transactionId);
        }

        PlayerRpgView.CareerRow preview = PlayerRpgView.create(
                        definitions, rpg.state(playerId), RpgDefinitionReloadListener.revision(server),
                        balance, server.overworld().getGameTime())
                .careers().stream().filter(row -> row.id().equals(careerId)).findFirst().orElseThrow();
        if (preview.promoted() || preview.lock().locked()
                && preview.lock().reason() != PlayerRpgView.LockReason.INSUFFICIENT_FUNDS) {
            return new Result(Status.STALE_OR_LOCKED, locked(preview, transactionId), Optional.empty(),
                    definition.promotionCost(), balance, transactionId);
        }

        Optional<EconomyService.TransactionStatus> paymentStatus = Optional.empty();
        if (definition.promotionCost() > 0) {
            EconomyService.TransactionResult payment = EconomyService.payCareerPromotion(
                    platform, playerId, definition.promotionCost(), "career_promotion:" + careerId,
                    timestamp, transactionId, EconomyConfig.initialBalance(), EconomyConfig.maximumBalance());
            paymentStatus = Optional.of(payment.status());
            balance = payment.balance();
            if (payment.status() != EconomyService.TransactionStatus.SUCCESS
                    && payment.status() != EconomyService.TransactionStatus.DUPLICATE_TRANSACTION) {
                return new Result(Status.PAYMENT_FAILED, failed(careerId, transactionId), paymentStatus,
                        definition.promotionCost(), balance, transactionId);
            }
        }

        CareerProgressionService.Result promotion = CareerProgressionService.promote(
                rpg, definitions, playerId, careerId, timestamp, transactionId, "player_gui");
        boolean accepted = promotion.status() == CareerProgressionService.Status.SUCCESS
                || promotion.status() == CareerProgressionService.Status.DUPLICATE;
        return new Result(accepted ? Status.SUCCESS : Status.PROMOTION_FAILED,
                promotion, paymentStatus, definition.promotionCost(), balance, transactionId);
    }

    static UUID transactionId(UUID playerId, Identifier careerId) {
        return UUID.nameUUIDFromBytes(
                ("rovenfall:career_promotion:" + playerId + ":" + careerId)
                        .getBytes(StandardCharsets.UTF_8));
    }

    private static CareerProgressionService.Result failed(Identifier careerId, UUID transactionId) {
        return new CareerProgressionService.Result(
                CareerProgressionService.Status.INVALID_REQUEST, careerId, Optional.empty(),
                0, 0, transactionId, false);
    }

    private static CareerProgressionService.Result locked(
            PlayerRpgView.CareerRow preview, UUID transactionId) {
        CareerProgressionService.Status status = preview.promoted()
                ? CareerProgressionService.Status.ALREADY_PROMOTED
                : switch (preview.lock().reason()) {
                    case MISSING_PARENT -> CareerProgressionService.Status.MISSING_PARENT;
                    case PARENT_RANK -> CareerProgressionService.Status.PARENT_RANK_TOO_LOW;
                    case ACTIVITY_LEVEL -> CareerProgressionService.Status.ACTIVITY_LEVEL_TOO_LOW;
                    case UNRESOLVED -> CareerProgressionService.Status.UNKNOWN_CAREER;
                    default -> CareerProgressionService.Status.INVALID_REQUEST;
                };
        return new CareerProgressionService.Result(
                status, preview.id(), preview.lock().blocker(),
                (int) Math.min(Integer.MAX_VALUE, preview.lock().required()),
                (int) Math.min(Integer.MAX_VALUE, preview.lock().actual()),
                transactionId, false);
    }
}
