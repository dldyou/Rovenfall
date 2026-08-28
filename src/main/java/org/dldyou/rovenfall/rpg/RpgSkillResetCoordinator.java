package org.dldyou.rovenfall.rpg;

import com.mojang.logging.LogUtils;
import java.util.Locale;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import org.dldyou.rovenfall.administration.EconomyConfig;
import org.dldyou.rovenfall.administration.PlatformSavedData;
import org.dldyou.rovenfall.administration.RpgSkillOperation;
import org.dldyou.rovenfall.administration.RpgSkillPaymentService;
import org.slf4j.Logger;

/** Coordinates an exact reset plan across independently persisted economy and RPG roots. */
public final class RpgSkillResetCoordinator {
    private static final Logger LOGGER = LogUtils.getLogger();

    public enum Status {
        SUCCESS,
        PREPARATION_FAILED,
        PAYMENT_FAILED,
        ITEM_PAYMENT_FAILED,
        RPG_FAILED,
        COMPLETION_FAILED
    }

    public record Result(
            Status status,
            RpgSkillService.Status rpgStatus,
            Optional<RpgSkillPaymentService.Status> paymentStatus,
            long cost,
            long balance,
            UUID transactionId) {
        public Result {
            paymentStatus = paymentStatus == null ? Optional.empty() : paymentStatus;
        }
    }

    private RpgSkillResetCoordinator() {
    }

    public static Result reset(
            MinecraftServer server,
            UUID playerId,
            SkillResetPlan.Mode mode,
            Identifier target,
            long timestampEpochMillis,
            UUID transactionId) {
        return reset(server, null, playerId, mode, target, timestampEpochMillis, transactionId);
    }

    public static Result reset(
            ServerPlayer player,
            SkillResetPlan.Mode mode,
            Identifier target,
            long timestampEpochMillis,
            UUID transactionId) {
        return reset(player.level().getServer(), player, player.getUUID(), mode, target,
                timestampEpochMillis, transactionId);
    }

    private static Result reset(
            MinecraftServer server,
            ServerPlayer player,
            UUID playerId,
            SkillResetPlan.Mode mode,
            Identifier target,
            long timestampEpochMillis,
            UUID transactionId) {
        return reset(
                PlatformSavedData.get(server), RpgPlayerSavedData.get(server),
                RpgDefinitionReloadListener.snapshot(server), player, playerId, mode, target,
                ActivityXpConfig.skillResetCost(mode), timestampEpochMillis, transactionId,
                EconomyConfig.initialBalance(), EconomyConfig.maximumBalance());
    }

    static Result reset(
            PlatformSavedData platform,
            RpgPlayerSavedData rpg,
            RpgDefinitionSnapshot definitions,
            UUID playerId,
            SkillResetPlan.Mode mode,
            Identifier target,
            long cost,
            long timestampEpochMillis,
            UUID transactionId,
            long initialBalance,
            long maximumBalance) {
        return reset(platform, rpg, definitions, null, playerId, mode, target, cost,
                timestampEpochMillis, transactionId, initialBalance, maximumBalance);
    }

    private static Result reset(
            PlatformSavedData platform,
            RpgPlayerSavedData rpg,
            RpgDefinitionSnapshot definitions,
            ServerPlayer player,
            UUID playerId,
            SkillResetPlan.Mode mode,
            Identifier target,
            long cost,
            long timestampEpochMillis,
            UUID transactionId,
            long initialBalance,
            long maximumBalance) {
        RpgSkillOperation existing = platform.rpgSkillOperation(transactionId).orElse(null);
        long operationTimestamp = existing == null ? timestampEpochMillis : existing.timestampEpochMillis();
        List<RpgItemCost> itemCosts = existing == null
                ? resetItemCosts(definitions, mode, target)
                : existing.itemCosts();
        List<Long> itemCountsBefore = existing == null ? List.of() : existing.itemCountsBefore();
        List<Long> itemCountsAfter = existing == null ? List.of() : existing.itemCountsAfter();
        SkillResetPlan plan;
        if (existing != null) {
            if (existing.kind() != RpgSkillOperation.Kind.SKILL_RESET
                    || !existing.playerId().equals(playerId) || existing.mode() != mode
                    || !existing.target().equals(target) || existing.cost() != cost
                    || !existing.itemCosts().equals(itemCosts)) {
                return new Result(Status.PAYMENT_FAILED, RpgSkillService.Status.STATE_CONFLICT,
                        Optional.of(RpgSkillPaymentService.Status.TRANSACTION_CONFLICT), cost,
                        platform.economyBalance(playerId).orElse(0L), transactionId);
            }
            plan = existing.plan().orElse(null);
            if (plan == null) {
                return new Result(Status.RPG_FAILED, RpgSkillService.Status.STATE_CONFLICT,
                        Optional.of(RpgSkillPaymentService.Status.DUPLICATE_COMPLETED), cost,
                        platform.economyBalance(playerId).orElse(0L), transactionId);
            }
            if (existing.phase() == RpgSkillOperation.Phase.COMPLETED
                    && RpgSkillService.hasResetEvidence(
                            rpg.state(playerId), plan, cost, itemCosts,
                            itemCountsBefore, itemCountsAfter, transactionId)) {
                if (player != null && !itemCosts.isEmpty()) {
                    if (RpgItemPayment.prepare(player, transactionId, existing).status()
                            != RpgItemPayment.Status.SUCCESS) {
                        return new Result(Status.ITEM_PAYMENT_FAILED, RpgSkillService.Status.STATE_CONFLICT,
                                Optional.empty(), cost, platform.economyBalance(playerId).orElse(0L), transactionId);
                    }
                    RpgItemPayment.complete(player, transactionId, operationTimestamp);
                }
                return new Result(Status.SUCCESS, RpgSkillService.Status.DUPLICATE,
                        Optional.of(RpgSkillPaymentService.Status.DUPLICATE_COMPLETED), cost,
                        platform.economyBalance(playerId).orElse(0L), transactionId);
            }
        } else {
            RpgSkillService.ResetPreparation preparation = RpgSkillService.prepareReset(
                    rpg, definitions, playerId, mode, target);
            if (preparation.status() != RpgSkillService.Status.SUCCESS) {
                return new Result(Status.PREPARATION_FAILED, preparation.status(), Optional.empty(), cost,
                        platform.economyBalance(playerId).orElse(0L), transactionId);
            }
            plan = preparation.plan().orElseThrow();
        }
        RpgSkillOperation requestedOperation = new RpgSkillOperation(
                playerId, mode, target, cost, operationTimestamp, Optional.of(plan),
                itemCosts.isEmpty() ? RpgSkillOperation.Phase.PENDING
                        : RpgSkillOperation.Phase.ITEMS_CONSUMED,
                RpgSkillOperation.Kind.SKILL_RESET, itemCosts, itemCountsBefore, itemCountsAfter);
        if (!itemCosts.isEmpty()) {
            RpgItemPayment.Result prepared = player == null
                    ? new RpgItemPayment.Result(RpgItemPayment.Status.CONFLICT, Optional.empty())
                    : RpgItemPayment.prepare(player, transactionId, requestedOperation);
            if (prepared.status() != RpgItemPayment.Status.SUCCESS) {
                return new Result(Status.ITEM_PAYMENT_FAILED, RpgSkillService.Status.STATE_CONFLICT,
                        Optional.empty(), cost, platform.economyBalance(playerId).orElse(0L), transactionId);
            }
            if (prepared.marker().isPresent()) {
                itemCountsBefore = prepared.marker().orElseThrow().countsBefore();
                itemCountsAfter = prepared.marker().orElseThrow().countsAfter();
                requestedOperation = new RpgSkillOperation(
                        playerId, mode, target, cost, operationTimestamp, Optional.of(plan),
                        RpgSkillOperation.Phase.ITEMS_CONSUMED, RpgSkillOperation.Kind.SKILL_RESET,
                        itemCosts, itemCountsBefore, itemCountsAfter);
            }
            if (!requestedOperation.hasInventoryEvidence()) {
                return new Result(Status.ITEM_PAYMENT_FAILED, RpgSkillService.Status.STATE_CONFLICT,
                        Optional.empty(), cost, platform.economyBalance(playerId).orElse(0L), transactionId);
            }
        }
        RpgSkillPaymentService.Result payment = RpgSkillPaymentService.begin(
                platform, playerId, plan, cost, itemCosts, itemCountsBefore, itemCountsAfter,
                operationTimestamp, transactionId,
                initialBalance, maximumBalance);
        if (!paymentAccepted(payment.status())) {
            if (player != null && !itemCosts.isEmpty()) {
                RpgItemPayment.rollback(player, transactionId);
            }
            return new Result(Status.PAYMENT_FAILED, RpgSkillService.Status.SUCCESS,
                    Optional.of(payment.status()), cost, payment.balance(), transactionId);
        }
        SkillResetPlan paidPlan = payment.operation().flatMap(RpgSkillOperation::plan).orElse(plan);
        RpgSkillService.Result applied = RpgSkillService.applyReset(
                rpg, definitions, playerId, paidPlan, cost, itemCosts,
                itemCountsBefore, itemCountsAfter, operationTimestamp, transactionId);
        boolean exactDuplicate = applied.status() == RpgSkillService.Status.DUPLICATE
                && RpgSkillService.hasResetEvidence(
                        rpg.state(playerId), paidPlan, cost, itemCosts,
                        itemCountsBefore, itemCountsAfter, transactionId);
        if (applied.status() != RpgSkillService.Status.SUCCESS && !exactDuplicate) {
            return new Result(Status.RPG_FAILED, applied.status(), Optional.of(payment.status()),
                    cost, payment.balance(), transactionId);
        }
        RpgSkillPaymentService.Result completed = RpgSkillPaymentService.complete(
                platform, playerId, transactionId, timestampEpochMillis);
        if (completed.status() != RpgSkillPaymentService.Status.SUCCESS
                && completed.status() != RpgSkillPaymentService.Status.DUPLICATE_COMPLETED) {
            return new Result(Status.COMPLETION_FAILED, applied.status(), Optional.of(completed.status()),
                    cost, completed.balance(), transactionId);
        }
        if (player != null && !itemCosts.isEmpty()) {
            RpgItemPayment.complete(player, transactionId, operationTimestamp);
        }
        return new Result(Status.SUCCESS, applied.status(), Optional.of(completed.status()),
                cost, completed.balance(), transactionId);
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
            long timestampEpochMillis,
            long initialBalance,
            long maximumBalance) {
        recoverPlayer(platform, rpg, definitions, null, playerId, timestampEpochMillis,
                initialBalance, maximumBalance);
    }

    private static void recoverPlayer(
            PlatformSavedData platform,
            RpgPlayerSavedData rpg,
            RpgDefinitionSnapshot definitions,
            ServerPlayer player,
            UUID playerId,
            long timestampEpochMillis,
            long initialBalance,
            long maximumBalance) {
        if (!platform.isWritable() || !rpg.isWritable()) {
            LOGGER.error("RPG skill reset recovery is disabled because persistence is read-only");
            return;
        }
        reconcileOrphanItemPayment(platform, rpg, player, playerId, timestampEpochMillis);
        for (var entry : platform.rpgSkillOperations(playerId)) {
            RpgSkillOperation operation = entry.getValue();
            if (operation.kind() != RpgSkillOperation.Kind.SKILL_RESET) {
                continue;
            }
            SkillResetPlan plan = operation.plan().orElse(null);
            if (plan == null) {
                LOGGER.error("RPG skill reset {} cannot be recovered because its exact plan is absent", entry.getKey());
                continue;
            }
            if (operation.phase() == RpgSkillOperation.Phase.COMPLETED
                    && RpgSkillService.hasResetEvidence(
                    rpg.state(playerId), plan, operation.cost(), operation.itemCosts(),
                    operation.itemCountsBefore(), operation.itemCountsAfter(), entry.getKey())) {
                if (player != null && !operation.itemCosts().isEmpty()) {
                    if (RpgItemPayment.prepare(player, entry.getKey(), operation).status()
                            != RpgItemPayment.Status.SUCCESS) {
                        LOGGER.error("Could not reconcile completed RPG skill reset item payment {}", entry.getKey());
                        continue;
                    }
                    RpgItemPayment.complete(player, entry.getKey(), operation.timestampEpochMillis());
                }
                continue;
            }
            if (!operation.itemCosts().isEmpty()
                    && (player == null || RpgItemPayment.prepare(player, entry.getKey(), operation).status()
                            != RpgItemPayment.Status.SUCCESS)) {
                LOGGER.error("Could not recover RPG skill reset {} because its item payment is unavailable",
                        entry.getKey());
                continue;
            }
            RpgSkillService.Result applied = RpgSkillService.applyReset(
                    rpg, definitions, playerId, plan,
                    operation.cost(), operation.itemCosts(), operation.itemCountsBefore(),
                    operation.itemCountsAfter(), operation.timestampEpochMillis(), entry.getKey());
            boolean exactDuplicate = applied.status() == RpgSkillService.Status.DUPLICATE
                    && RpgSkillService.hasResetEvidence(
                    rpg.state(playerId), plan, operation.cost(), operation.itemCosts(),
                    operation.itemCountsBefore(), operation.itemCountsAfter(), entry.getKey());
            if (applied.status() == RpgSkillService.Status.SUCCESS || exactDuplicate) {
                if (operation.phase() != RpgSkillOperation.Phase.COMPLETED) {
                    RpgSkillPaymentService.Result completed = RpgSkillPaymentService.complete(
                            platform, playerId, entry.getKey(), timestampEpochMillis);
                    if (completed.status() != RpgSkillPaymentService.Status.SUCCESS
                            && completed.status() != RpgSkillPaymentService.Status.DUPLICATE_COMPLETED) {
                        LOGGER.error("Could not complete recovered RPG skill reset {} ({})",
                                entry.getKey(), completed.status());
                    } else if (player != null && !operation.itemCosts().isEmpty()) {
                        RpgItemPayment.complete(player, entry.getKey(), operation.timestampEpochMillis());
                    }
                }
            } else {
                LOGGER.error("Could not apply recovered RPG skill reset {} ({})", entry.getKey(), applied.status());
            }
        }

        for (RpgPlayerState.ProgressionProvenance evidence : rpg.state(playerId).careerProvenance()) {
            if (evidence.kind() != RpgPlayerState.ProgressionProvenance.Kind.SKILL_RESET
                    || platform.rpgSkillOperation(evidence.transactionId()).isPresent()) {
                continue;
            }
            SkillResetPlan.Mode mode = parseMode(evidence.source()).orElse(null);
            if (mode == null) {
                LOGGER.error("RPG skill reset {} has invalid recovery evidence", evidence.transactionId());
                continue;
            }
            if (!PlatformSavedData.isEconomyRecoveryWindow(evidence.timestamp(), timestampEpochMillis)) {
                if (!hasMatchingItemMarker(player, evidence, mode)) {
                    LOGGER.error("RPG skill reset payment {} requires manual reconciliation because its recovery "
                            + "window expired", evidence.transactionId());
                }
                continue;
            }
            List<RpgItemCost> itemCosts = evidence.itemCosts();
            SkillResetPlan recoveryPlan = evidence.resetPlan().orElse(null);
            if (!itemCosts.isEmpty()) {
                if (recoveryPlan == null) {
                    LOGGER.error("RPG skill reset {} has no exact recovery plan", evidence.transactionId());
                    continue;
                }
                RpgSkillOperation recoveredOperation = new RpgSkillOperation(
                        playerId, mode, evidence.target(), evidence.amount(), evidence.timestamp(),
                        Optional.of(recoveryPlan), RpgSkillOperation.Phase.COMPLETED,
                        RpgSkillOperation.Kind.SKILL_RESET, itemCosts,
                        evidence.itemCountsBefore(), evidence.itemCountsAfter());
                if (player == null || RpgItemPayment.prepare(
                        player, evidence.transactionId(), recoveredOperation).status()
                        != RpgItemPayment.Status.SUCCESS) {
                    LOGGER.error("Could not recover RPG skill reset item payment {}", evidence.transactionId());
                    continue;
                }
            }
            RpgSkillPaymentService.Result recovered = recoveryPlan == null
                    ? RpgSkillPaymentService.recoverCompleted(
                            platform, playerId, mode, evidence.target(), evidence.amount(), itemCosts,
                            evidence.itemCountsBefore(), evidence.itemCountsAfter(), evidence.timestamp(),
                            evidence.transactionId(), initialBalance, maximumBalance)
                    : RpgSkillPaymentService.recoverCompleted(
                            platform, playerId, recoveryPlan, evidence.amount(), itemCosts,
                            evidence.itemCountsBefore(), evidence.itemCountsAfter(), evidence.timestamp(),
                            evidence.transactionId(), initialBalance, maximumBalance);
            if (recovered.status() != RpgSkillPaymentService.Status.SUCCESS
                    && recovered.status() != RpgSkillPaymentService.Status.DUPLICATE_COMPLETED) {
                LOGGER.error("Could not recover RPG skill reset payment {} ({})",
                        evidence.transactionId(), recovered.status());
            } else if (player != null && !itemCosts.isEmpty()) {
                RpgItemPayment.complete(player, evidence.transactionId(), evidence.timestamp());
            }
        }
    }

    private static void reconcileOrphanItemPayment(
            PlatformSavedData platform,
            RpgPlayerSavedData rpg,
            ServerPlayer player,
            UUID playerId,
            long timestampEpochMillis) {
        if (player == null) {
            return;
        }
        RpgItemPayment.Result loaded = RpgItemPayment.read(player);
        if (loaded.status() != RpgItemPayment.Status.SUCCESS) {
            LOGGER.error("RPG skill reset item journal is malformed for {}", playerId);
            return;
        }
        RpgItemPayment.Marker marker = loaded.marker().orElse(null);
        if (marker == null || marker.kind() != RpgSkillOperation.Kind.SKILL_RESET
                || platform.rpgSkillOperation(marker.transactionId()).isPresent()) {
            return;
        }
        SkillResetPlan plan = marker.plan().orElse(null);
        boolean rpgCommitted = plan != null && RpgSkillService.hasResetEvidence(
                rpg.state(playerId), plan, marker.currencyCost(), marker.itemCosts(),
                marker.countsBefore(), marker.countsAfter(), marker.transactionId());
        if (rpgCommitted) {
            return;
        }
        if (!rpgCommitted && RpgItemPayment.rollback(player, marker.transactionId())
                != RpgItemPayment.Status.SUCCESS) {
            LOGGER.error("Could not roll back orphaned RPG skill reset item payment {}", marker.transactionId());
        }
    }

    private static boolean hasMatchingItemMarker(
            ServerPlayer player,
            RpgPlayerState.ProgressionProvenance evidence,
            SkillResetPlan.Mode mode) {
        SkillResetPlan plan = evidence.resetPlan().orElse(null);
        if (player == null || evidence.itemCosts().isEmpty() || plan == null) {
            return false;
        }
        RpgSkillOperation operation = new RpgSkillOperation(
                player.getUUID(), mode, evidence.target(), evidence.amount(), evidence.timestamp(),
                Optional.of(plan), RpgSkillOperation.Phase.COMPLETED,
                RpgSkillOperation.Kind.SKILL_RESET, evidence.itemCosts(),
                evidence.itemCountsBefore(), evidence.itemCountsAfter());
        RpgItemPayment.Result loaded = RpgItemPayment.read(player);
        return loaded.status() == RpgItemPayment.Status.SUCCESS
                && loaded.marker().filter(marker -> marker.matches(operation, evidence.transactionId())).isPresent();
    }

    private static boolean paymentAccepted(RpgSkillPaymentService.Status status) {
        return status == RpgSkillPaymentService.Status.SUCCESS
                || status == RpgSkillPaymentService.Status.DUPLICATE_PENDING
                || status == RpgSkillPaymentService.Status.DUPLICATE_COMPLETED;
    }

    public static List<RpgItemCost> resetItemCosts(
            RpgDefinitionSnapshot definitions, SkillResetPlan.Mode mode, Identifier target) {
        if (definitions == null || mode == null || target == null) {
            return List.of();
        }
        return switch (mode) {
            case BRANCH -> definitions.skill(target).map(SkillDefinition::branchResetItems).orElse(List.of());
            case FULL -> definitions.career(target).map(CareerDefinition::fullResetItems).orElse(List.of());
        };
    }

    private static Optional<SkillResetPlan.Mode> parseMode(String source) {
        if (source == null || !source.startsWith("skill_reset:")) {
            return Optional.empty();
        }
        String value = source.substring("skill_reset:".length());
        int fingerprintSeparator = value.indexOf(':');
        if (fingerprintSeparator >= 0) {
            value = value.substring(0, fingerprintSeparator);
        }
        value = value.toUpperCase(Locale.ROOT);
        try {
            return Optional.of(SkillResetPlan.Mode.valueOf(value));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private static Result result(
            Status status,
            RpgSkillService.Status rpgStatus,
            RpgSkillPaymentService.Status paymentStatus,
            SkillResetPlan.Mode mode,
            long balance,
            UUID transactionId) {
        return new Result(status, rpgStatus, Optional.ofNullable(paymentStatus),
                mode == null ? 0 : ActivityXpConfig.skillResetCost(mode), balance, transactionId);
    }
}
