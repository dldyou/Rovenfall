package org.dldyou.rovenfall.rpg;

import com.mojang.logging.LogUtils;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import org.dldyou.rovenfall.administration.CareerPromotionPaymentService;
import org.dldyou.rovenfall.administration.EconomyConfig;
import org.dldyou.rovenfall.administration.PlatformSavedData;
import org.dldyou.rovenfall.administration.RpgSkillOperation;
import org.slf4j.Logger;

/** Server-owned, restart-recoverable coordinator for career promotion and its payment. */
public final class PlayerCareerPromotionService {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String PAID_SOURCE_PREFIX = "player_paid_promotion:";

    public enum Status {
        SUCCESS,
        STALE_OR_LOCKED,
        PAYMENT_FAILED,
        PROMOTION_FAILED,
        COMPLETION_FAILED
    }

    public record Result(
            Status status,
            CareerProgressionService.Result promotion,
            Optional<CareerPromotionPaymentService.Status> paymentStatus,
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

        RpgPlayerState current = rpg.state(playerId);
        boolean newCareer = !current.careers().containsKey(careerId);
        if (!rpg.isWritable()
                || newCareer && (current.careers().size() >= RpgPlayerState.MAX_CAREERS
                || rpg.player(playerId).isEmpty() && rpg.playerCount() >= RpgPlayerSavedData.MAX_PLAYERS)) {
            CareerProgressionService.Result result = CareerProgressionService.promote(
                    rpg, definitions, playerId, careerId, timestamp, transactionId, "player_gui");
            return new Result(Status.PROMOTION_FAILED, result, Optional.empty(),
                    definition.promotionCost(), balance, transactionId);
        }

        RpgSkillOperation existing = platform.rpgSkillOperation(transactionId).orElse(null);
        Optional<RpgPlayerState.ProgressionProvenance> evidence = promotionEvidence(
                current, careerId, transactionId);
        boolean replay = evidence.isPresent();
        long cost = existing != null
                ? existing.cost()
                : evidence.flatMap(entry -> paidCost(entry.source())).orElse(replay ? 0L : definition.promotionCost());
        if (existing != null && !existing.matchesPromotion(playerId, careerId, existing.cost())) {
            return new Result(Status.PAYMENT_FAILED, failed(careerId, transactionId),
                    Optional.of(CareerPromotionPaymentService.Status.TRANSACTION_CONFLICT),
                    cost, balance, transactionId);
        }

        PlayerRpgView.CareerRow preview = PlayerRpgView.create(
                        definitions, rpg.state(playerId), RpgDefinitionReloadListener.revision(server),
                        balance, server.overworld().getGameTime())
                .careers().stream().filter(row -> row.id().equals(careerId)).findFirst().orElseThrow();
        if (preview.promoted() && !replay) {
            return new Result(Status.STALE_OR_LOCKED, locked(preview, transactionId), Optional.empty(),
                    cost, balance, transactionId);
        }
        if (!preview.promoted() && preview.lock().locked()
                && preview.lock().reason() != PlayerRpgView.LockReason.INSUFFICIENT_FUNDS) {
            return new Result(Status.STALE_OR_LOCKED, locked(preview, transactionId), Optional.empty(),
                    cost, balance, transactionId);
        }
        if (replay && existing == null
                && !PlatformSavedData.isEconomyRecoveryWindow(evidence.orElseThrow().timestamp(), timestamp)) {
            CareerProgressionService.Result duplicate = CareerProgressionService.promote(
                    rpg, definitions, playerId, careerId, timestamp, transactionId,
                    cost > 0 ? paidSource(cost) : "player_gui");
            return new Result(
                    duplicate.status() == CareerProgressionService.Status.DUPLICATE
                            && hasPromotionEvidence(rpg.state(playerId), careerId, transactionId, cost)
                            ? Status.SUCCESS : Status.PROMOTION_FAILED,
                    duplicate, Optional.empty(), cost, balance, transactionId);
        }

        Optional<CareerPromotionPaymentService.Status> paymentStatus = Optional.empty();
        if (cost > 0) {
            CareerPromotionPaymentService.Result payment = CareerPromotionPaymentService.begin(
                    platform, playerId, careerId, cost, timestamp, transactionId,
                    EconomyConfig.initialBalance(), EconomyConfig.maximumBalance());
            paymentStatus = Optional.of(payment.status());
            balance = payment.balance();
            if (!paymentAccepted(payment.status())) {
                return new Result(Status.PAYMENT_FAILED, failed(careerId, transactionId), paymentStatus,
                        cost, balance, transactionId);
            }
        }

        CareerProgressionService.Result promotion = CareerProgressionService.promote(
                rpg, definitions, playerId, careerId, timestamp, transactionId,
                cost > 0 ? paidSource(cost) : "player_gui");
        boolean accepted = promotion.status() == CareerProgressionService.Status.SUCCESS
                || promotion.status() == CareerProgressionService.Status.DUPLICATE
                && hasPromotionEvidence(rpg.state(playerId), careerId, transactionId, cost);
        if (!accepted) {
            return new Result(Status.PROMOTION_FAILED, promotion, paymentStatus,
                    cost, balance, transactionId);
        }
        if (cost > 0) {
            CareerPromotionPaymentService.Result completed = CareerPromotionPaymentService.complete(
                    platform, playerId, transactionId, timestamp);
            paymentStatus = Optional.of(completed.status());
            balance = completed.balance();
            if (completed.status() != CareerPromotionPaymentService.Status.SUCCESS
                    && completed.status() != CareerPromotionPaymentService.Status.DUPLICATE_COMPLETED) {
                return new Result(Status.COMPLETION_FAILED, promotion, paymentStatus,
                        cost, balance, transactionId);
            }
        }
        return new Result(Status.SUCCESS, promotion, paymentStatus,
                cost, balance, transactionId);
    }

    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        MinecraftServer server = player.level().getServer();
        UUID playerId = player.getUUID();
        server.execute(() -> recoverPlayer(server, playerId));
    }

    static void recoverPlayer(MinecraftServer server, UUID playerId) {
        recoverPlayer(
                PlatformSavedData.get(server), RpgPlayerSavedData.get(server),
                RpgDefinitionReloadListener.snapshot(server), playerId, System.currentTimeMillis(),
                EconomyConfig.initialBalance(), EconomyConfig.maximumBalance());
    }

    static void recoverPlayer(
            PlatformSavedData platform,
            RpgPlayerSavedData rpg,
            RpgDefinitionSnapshot definitions,
            UUID playerId,
            long timestamp,
            long initialBalance,
            long maximumBalance) {
        if (!platform.isWritable() || !rpg.isWritable()) {
            LOGGER.error("Career promotion recovery is disabled because persistence is read-only");
            return;
        }
        for (var entry : platform.rpgSkillOperations(playerId)) {
            RpgSkillOperation operation = entry.getValue();
            if (operation.kind() != RpgSkillOperation.Kind.CAREER_PROMOTION) {
                continue;
            }
            if (operation.phase() == RpgSkillOperation.Phase.COMPLETED
                    && hasPromotionEvidence(
                    rpg.state(playerId), operation.target(), entry.getKey(), operation.cost())) {
                continue;
            }
            CareerProgressionService.Result applied = CareerProgressionService.promote(
                    rpg, definitions, playerId, operation.target(), operation.timestampEpochMillis(),
                    entry.getKey(), paidSource(operation.cost()));
            boolean exactDuplicate = applied.status() == CareerProgressionService.Status.DUPLICATE
                    && hasPromotionEvidence(
                    rpg.state(playerId), operation.target(), entry.getKey(), operation.cost());
            if (applied.status() == CareerProgressionService.Status.SUCCESS || exactDuplicate) {
                if (operation.phase() == RpgSkillOperation.Phase.PENDING) {
                    CareerPromotionPaymentService.Result completed = CareerPromotionPaymentService.complete(
                            platform, playerId, entry.getKey(), timestamp);
                    if (completed.status() != CareerPromotionPaymentService.Status.SUCCESS
                            && completed.status() != CareerPromotionPaymentService.Status.DUPLICATE_COMPLETED) {
                        LOGGER.error("Could not complete recovered career promotion {} ({})",
                                entry.getKey(), completed.status());
                    }
                }
            } else {
                LOGGER.error("Could not apply recovered career promotion {} ({})", entry.getKey(), applied.status());
            }
        }

        for (RpgPlayerState.ProgressionProvenance evidence : rpg.state(playerId).careerProvenance()) {
            if (evidence.kind() != RpgPlayerState.ProgressionProvenance.Kind.CAREER_PROMOTION
                    || platform.rpgSkillOperation(evidence.transactionId()).isPresent()) {
                continue;
            }
            Optional<Long> cost = paidCost(evidence.source());
            if (cost.isEmpty() || !PlatformSavedData.isEconomyRecoveryWindow(evidence.timestamp(), timestamp)) {
                continue;
            }
            CareerPromotionPaymentService.Result recovered = CareerPromotionPaymentService.recoverCompleted(
                    platform, playerId, evidence.target(), cost.orElseThrow(), evidence.timestamp(),
                    evidence.transactionId(), initialBalance, maximumBalance);
            if (recovered.status() != CareerPromotionPaymentService.Status.SUCCESS
                    && recovered.status() != CareerPromotionPaymentService.Status.DUPLICATE_COMPLETED) {
                LOGGER.error("Could not recover career promotion payment {} ({})",
                        evidence.transactionId(), recovered.status());
            }
        }
    }

    static UUID transactionId(UUID playerId, Identifier careerId) {
        return UUID.nameUUIDFromBytes(
                ("rovenfall:career_promotion:" + playerId + ":" + careerId)
                        .getBytes(StandardCharsets.UTF_8));
    }

    static String paidSource(long cost) {
        return PAID_SOURCE_PREFIX + cost;
    }

    static Optional<Long> paidCost(String source) {
        if (source == null || !source.startsWith(PAID_SOURCE_PREFIX)) {
            return Optional.empty();
        }
        try {
            long value = Long.parseLong(source.substring(PAID_SOURCE_PREFIX.length()));
            return value > 0 && value <= CareerDefinition.MAX_PROMOTION_COST
                    ? Optional.of(value) : Optional.empty();
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
    }

    private static boolean paymentAccepted(CareerPromotionPaymentService.Status status) {
        return status == CareerPromotionPaymentService.Status.SUCCESS
                || status == CareerPromotionPaymentService.Status.DUPLICATE_PENDING
                || status == CareerPromotionPaymentService.Status.DUPLICATE_COMPLETED;
    }

    private static Optional<RpgPlayerState.ProgressionProvenance> promotionEvidence(
            RpgPlayerState state, Identifier careerId, UUID transactionId) {
        return state.careerProvenance().stream()
                .filter(entry -> entry.kind() == RpgPlayerState.ProgressionProvenance.Kind.CAREER_PROMOTION)
                .filter(entry -> entry.target().equals(careerId))
                .filter(entry -> entry.transactionId().equals(transactionId))
                .findFirst();
    }

    private static boolean hasPromotionEvidence(
            RpgPlayerState state,
            Identifier careerId,
            UUID transactionId,
            long cost) {
        return promotionEvidence(state, careerId, transactionId)
                .filter(entry -> cost > 0
                        ? paidCost(entry.source()).filter(value -> value == cost).isPresent()
                        : paidCost(entry.source()).isEmpty())
                .isPresent();
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
