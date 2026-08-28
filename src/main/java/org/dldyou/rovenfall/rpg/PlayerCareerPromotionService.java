package org.dldyou.rovenfall.rpg;

import com.mojang.logging.LogUtils;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.List;
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
        ITEM_PAYMENT_FAILED,
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
        return promote(server, null, playerId, careerId, timestamp);
    }

    public static Result promote(ServerPlayer player, Identifier careerId, long timestamp) {
        return promote(player.level().getServer(), player, player.getUUID(), careerId, timestamp);
    }

    private static Result promote(
            MinecraftServer server,
            ServerPlayer player,
            UUID playerId,
            Identifier careerId,
            long timestamp) {
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
        long operationTimestamp = existing != null
                ? existing.timestampEpochMillis()
                : evidence.map(RpgPlayerState.ProgressionProvenance::timestamp).orElse(timestamp);
        long cost = existing != null
                ? existing.cost()
                : evidence.flatMap(entry -> paidCost(entry.source())).orElse(replay ? 0L : definition.promotionCost());
        List<RpgItemCost> itemCosts = existing != null
                ? existing.itemCosts()
                : evidence.map(RpgPlayerState.ProgressionProvenance::itemCosts)
                        .orElse(replay ? List.of() : definition.promotionItems());
        List<Long> itemCountsBefore = existing != null
                ? existing.itemCountsBefore()
                : evidence.map(RpgPlayerState.ProgressionProvenance::itemCountsBefore).orElse(List.of());
        List<Long> itemCountsAfter = existing != null
                ? existing.itemCountsAfter()
                : evidence.map(RpgPlayerState.ProgressionProvenance::itemCountsAfter).orElse(List.of());
        if (existing != null && !existing.matchesPromotion(
                playerId, careerId, cost, itemCosts, itemCountsBefore, itemCountsAfter)) {
            return new Result(Status.PAYMENT_FAILED, failed(careerId, transactionId),
                    Optional.of(CareerPromotionPaymentService.Status.TRANSACTION_CONFLICT),
                    cost, balance, transactionId);
        }
        if (existing != null && existing.phase() == RpgSkillOperation.Phase.COMPLETED
                && hasPromotionEvidence(current, careerId, transactionId, cost, itemCosts,
                        itemCountsBefore, itemCountsAfter)) {
            if (player != null && !itemCosts.isEmpty()) {
                if (RpgItemPayment.prepare(player, transactionId, existing).status()
                        != RpgItemPayment.Status.SUCCESS) {
                    return new Result(Status.ITEM_PAYMENT_FAILED, failed(careerId, transactionId),
                            Optional.empty(), cost, balance, transactionId);
                }
                RpgItemPayment.complete(player, transactionId, existing.timestampEpochMillis());
            }
            CareerProgressionService.Result duplicate = CareerProgressionService.promote(
                    rpg, definitions, playerId, careerId, timestamp, transactionId,
                    cost > 0 ? paidSource(cost) : "player_gui", itemCosts,
                    itemCountsBefore, itemCountsAfter);
            return new Result(Status.SUCCESS, duplicate,
                    Optional.of(CareerPromotionPaymentService.Status.DUPLICATE_COMPLETED),
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
            return new Result(Status.PAYMENT_FAILED, failed(careerId, transactionId),
                    Optional.of(CareerPromotionPaymentService.Status.TRANSACTION_CONFLICT),
                    cost, balance, transactionId);
        }

        Optional<CareerPromotionPaymentService.Status> paymentStatus = Optional.empty();
        RpgSkillOperation requestedOperation = RpgSkillOperation.careerPromotion(
                playerId, careerId, cost, itemCosts, itemCountsBefore, itemCountsAfter, operationTimestamp,
                itemCosts.isEmpty() ? RpgSkillOperation.Phase.PENDING
                        : RpgSkillOperation.Phase.ITEMS_CONSUMED);
        if (!itemCosts.isEmpty()) {
            RpgItemPayment.Result prepared = player == null
                    ? new RpgItemPayment.Result(RpgItemPayment.Status.CONFLICT, Optional.empty())
                    : RpgItemPayment.prepare(player, transactionId, requestedOperation);
            if (prepared.status() != RpgItemPayment.Status.SUCCESS) {
                return new Result(Status.ITEM_PAYMENT_FAILED, failed(careerId, transactionId), Optional.empty(),
                        cost, balance, transactionId);
            }
            if (prepared.marker().isPresent()) {
                itemCountsBefore = prepared.marker().orElseThrow().countsBefore();
                itemCountsAfter = prepared.marker().orElseThrow().countsAfter();
                requestedOperation = RpgSkillOperation.careerPromotion(
                        playerId, careerId, cost, itemCosts, itemCountsBefore, itemCountsAfter, operationTimestamp,
                        RpgSkillOperation.Phase.ITEMS_CONSUMED);
            }
            if (!requestedOperation.hasInventoryEvidence()) {
                return new Result(Status.ITEM_PAYMENT_FAILED, failed(careerId, transactionId), Optional.empty(),
                        cost, balance, transactionId);
            }
        }
        if (cost > 0 || !itemCosts.isEmpty()) {
            CareerPromotionPaymentService.Result payment = CareerPromotionPaymentService.begin(
                    platform, playerId, careerId, cost, itemCosts, itemCountsBefore, itemCountsAfter,
                    operationTimestamp, transactionId,
                    EconomyConfig.initialBalance(), EconomyConfig.maximumBalance());
            paymentStatus = Optional.of(payment.status());
            balance = payment.balance();
            if (!paymentAccepted(payment.status())) {
                if (player != null && !itemCosts.isEmpty()) {
                    RpgItemPayment.rollback(player, transactionId);
                }
                return new Result(Status.PAYMENT_FAILED, failed(careerId, transactionId), paymentStatus,
                        cost, balance, transactionId);
            }
        }

        CareerProgressionService.Result promotion = CareerProgressionService.promote(
                rpg, definitions, playerId, careerId, operationTimestamp, transactionId,
                cost > 0 ? paidSource(cost) : "player_gui", itemCosts,
                itemCountsBefore, itemCountsAfter);
        boolean accepted = promotion.status() == CareerProgressionService.Status.SUCCESS
                || promotion.status() == CareerProgressionService.Status.DUPLICATE
                && hasPromotionEvidence(rpg.state(playerId), careerId, transactionId, cost, itemCosts,
                        itemCountsBefore, itemCountsAfter);
        if (!accepted) {
            return new Result(Status.PROMOTION_FAILED, promotion, paymentStatus,
                    cost, balance, transactionId);
        }
        if (cost > 0 || !itemCosts.isEmpty()) {
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
        if (player != null && !itemCosts.isEmpty()) {
            RpgItemPayment.complete(player, transactionId, operationTimestamp);
        }
        return new Result(Status.SUCCESS, promotion, paymentStatus,
                cost, balance, transactionId);
    }

    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        MinecraftServer server = player.level().getServer();
        server.execute(() -> recoverPlayer(server, player));
    }

    static void recoverPlayer(MinecraftServer server, UUID playerId) {
        recoverPlayer(
                PlatformSavedData.get(server), RpgPlayerSavedData.get(server),
                RpgDefinitionReloadListener.snapshot(server), null, playerId, System.currentTimeMillis(),
                EconomyConfig.initialBalance(), EconomyConfig.maximumBalance());
    }

    static void recoverPlayer(MinecraftServer server, ServerPlayer player) {
        recoverPlayer(
                PlatformSavedData.get(server), RpgPlayerSavedData.get(server),
                RpgDefinitionReloadListener.snapshot(server), player, player.getUUID(), System.currentTimeMillis(),
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
        recoverPlayer(platform, rpg, definitions, null, playerId, timestamp, initialBalance, maximumBalance);
    }

    private static void recoverPlayer(
            PlatformSavedData platform,
            RpgPlayerSavedData rpg,
            RpgDefinitionSnapshot definitions,
            ServerPlayer player,
            UUID playerId,
            long timestamp,
            long initialBalance,
            long maximumBalance) {
        if (!platform.isWritable() || !rpg.isWritable()) {
            LOGGER.error("Career promotion recovery is disabled because persistence is read-only");
            return;
        }
        reconcileOrphanItemPayment(platform, rpg, player, playerId, timestamp);
        for (var entry : platform.rpgSkillOperations(playerId)) {
            RpgSkillOperation operation = entry.getValue();
            if (operation.kind() != RpgSkillOperation.Kind.CAREER_PROMOTION) {
                continue;
            }
            if (operation.phase() == RpgSkillOperation.Phase.COMPLETED
                    && hasPromotionEvidence(
                    rpg.state(playerId), operation.target(), entry.getKey(), operation.cost(), operation.itemCosts(),
                    operation.itemCountsBefore(), operation.itemCountsAfter())) {
                if (player != null && !operation.itemCosts().isEmpty()) {
                    if (RpgItemPayment.prepare(player, entry.getKey(), operation).status()
                            != RpgItemPayment.Status.SUCCESS) {
                        LOGGER.error("Could not reconcile completed career promotion item payment {}", entry.getKey());
                        continue;
                    }
                    RpgItemPayment.complete(player, entry.getKey(), operation.timestampEpochMillis());
                }
                continue;
            }
            if (!operation.itemCosts().isEmpty()
                    && (player == null || RpgItemPayment.prepare(player, entry.getKey(), operation).status()
                            != RpgItemPayment.Status.SUCCESS)) {
                LOGGER.error("Could not recover career promotion {} because its item payment is unavailable",
                        entry.getKey());
                continue;
            }
            CareerProgressionService.Result applied = CareerProgressionService.promote(
                    rpg, definitions, playerId, operation.target(), operation.timestampEpochMillis(),
                    entry.getKey(), operation.cost() > 0 ? paidSource(operation.cost()) : "player_gui",
                    operation.itemCosts(), operation.itemCountsBefore(), operation.itemCountsAfter());
            boolean exactDuplicate = applied.status() == CareerProgressionService.Status.DUPLICATE
                    && hasPromotionEvidence(
                    rpg.state(playerId), operation.target(), entry.getKey(), operation.cost(), operation.itemCosts(),
                    operation.itemCountsBefore(), operation.itemCountsAfter());
            if (applied.status() == CareerProgressionService.Status.SUCCESS || exactDuplicate) {
                if (operation.phase() != RpgSkillOperation.Phase.COMPLETED) {
                    CareerPromotionPaymentService.Result completed = CareerPromotionPaymentService.complete(
                            platform, playerId, entry.getKey(), timestamp);
                    if (completed.status() != CareerPromotionPaymentService.Status.SUCCESS
                            && completed.status() != CareerPromotionPaymentService.Status.DUPLICATE_COMPLETED) {
                        LOGGER.error("Could not complete recovered career promotion {} ({})",
                                entry.getKey(), completed.status());
                    } else if (player != null && !operation.itemCosts().isEmpty()) {
                        RpgItemPayment.complete(player, entry.getKey(), operation.timestampEpochMillis());
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
            if (cost.isEmpty() && evidence.itemCosts().isEmpty()) {
                continue;
            }
            if (!PlatformSavedData.isEconomyRecoveryWindow(evidence.timestamp(), timestamp)) {
                if (!hasMatchingItemMarker(player, evidence, paidCost(evidence.source()).orElse(0L))) {
                    LOGGER.error("Career promotion payment {} requires manual reconciliation because its recovery "
                            + "window expired", evidence.transactionId());
                }
                continue;
            }
            long recoveredCost = cost.orElse(0L);
            if (!evidence.itemCosts().isEmpty()) {
                RpgSkillOperation recoveredOperation = RpgSkillOperation.careerPromotion(
                        playerId, evidence.target(), recoveredCost, evidence.itemCosts(),
                        evidence.itemCountsBefore(), evidence.itemCountsAfter(), evidence.timestamp(),
                        RpgSkillOperation.Phase.COMPLETED);
                if (player == null || RpgItemPayment.prepare(
                        player, evidence.transactionId(), recoveredOperation).status() != RpgItemPayment.Status.SUCCESS) {
                    LOGGER.error("Could not recover career promotion item payment {}", evidence.transactionId());
                    continue;
                }
            }
            CareerPromotionPaymentService.Result recovered = CareerPromotionPaymentService.recoverCompleted(
                    platform, playerId, evidence.target(), recoveredCost, evidence.itemCosts(),
                    evidence.itemCountsBefore(), evidence.itemCountsAfter(), evidence.timestamp(),
                    evidence.transactionId(), initialBalance, maximumBalance);
            if (recovered.status() != CareerPromotionPaymentService.Status.SUCCESS
                    && recovered.status() != CareerPromotionPaymentService.Status.DUPLICATE_COMPLETED) {
                LOGGER.error("Could not recover career promotion payment {} ({})",
                        evidence.transactionId(), recovered.status());
            } else if (player != null && !evidence.itemCosts().isEmpty()) {
                RpgItemPayment.complete(player, evidence.transactionId(), evidence.timestamp());
            }
        }
    }

    private static void reconcileOrphanItemPayment(
            PlatformSavedData platform,
            RpgPlayerSavedData rpg,
            ServerPlayer player,
            UUID playerId,
            long timestamp) {
        if (player == null) {
            return;
        }
        RpgItemPayment.Result loaded = RpgItemPayment.read(player);
        if (loaded.status() != RpgItemPayment.Status.SUCCESS) {
            LOGGER.error("Career promotion item journal is malformed for {}", playerId);
            return;
        }
        RpgItemPayment.Marker marker = loaded.marker().orElse(null);
        if (marker == null || marker.kind() != RpgSkillOperation.Kind.CAREER_PROMOTION
                || platform.rpgSkillOperation(marker.transactionId()).isPresent()) {
            return;
        }
        boolean rpgCommitted = hasPromotionEvidence(
                rpg.state(playerId), marker.target(), marker.transactionId(), marker.currencyCost(),
                marker.itemCosts(), marker.countsBefore(), marker.countsAfter());
        if (rpgCommitted) {
            return;
        }
        if (!rpgCommitted && RpgItemPayment.rollback(player, marker.transactionId())
                != RpgItemPayment.Status.SUCCESS) {
            LOGGER.error("Could not roll back orphaned career promotion item payment {}", marker.transactionId());
        }
    }

    private static boolean hasMatchingItemMarker(
            ServerPlayer player,
            RpgPlayerState.ProgressionProvenance evidence,
            long cost) {
        if (player == null || evidence.itemCosts().isEmpty()) {
            return false;
        }
        RpgSkillOperation operation = RpgSkillOperation.careerPromotion(
                player.getUUID(), evidence.target(), cost, evidence.itemCosts(),
                evidence.itemCountsBefore(), evidence.itemCountsAfter(), evidence.timestamp(),
                RpgSkillOperation.Phase.COMPLETED);
        RpgItemPayment.Result loaded = RpgItemPayment.read(player);
        return loaded.status() == RpgItemPayment.Status.SUCCESS
                && loaded.marker().filter(marker -> marker.matches(operation, evidence.transactionId())).isPresent();
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
        return hasPromotionEvidence(state, careerId, transactionId, cost, List.of());
    }

    private static boolean hasPromotionEvidence(
            RpgPlayerState state,
            Identifier careerId,
            UUID transactionId,
            long cost,
            List<RpgItemCost> itemCosts) {
        return hasPromotionEvidence(state, careerId, transactionId, cost, itemCosts, List.of(), List.of());
    }

    private static boolean hasPromotionEvidence(
            RpgPlayerState state,
            Identifier careerId,
            UUID transactionId,
            long cost,
            List<RpgItemCost> itemCosts,
            List<Long> itemCountsBefore,
            List<Long> itemCountsAfter) {
        return promotionEvidence(state, careerId, transactionId)
                .filter(entry -> cost > 0
                        ? paidCost(entry.source()).filter(value -> value == cost).isPresent()
                        : paidCost(entry.source()).isEmpty())
                .filter(entry -> entry.itemCosts().equals(itemCosts))
                .filter(entry -> entry.itemCountsBefore().equals(itemCountsBefore))
                .filter(entry -> entry.itemCountsAfter().equals(itemCountsAfter))
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
