package org.dldyou.rovenfall.administration;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.Identifier;
import org.dldyou.rovenfall.Rovenfall;
import org.dldyou.rovenfall.rpg.CareerDefinition;
import org.dldyou.rovenfall.rpg.RpgItemCost;

/** Durable economy half of a paid career promotion. */
public final class CareerPromotionPaymentService {
    private static final UUID ZERO_UUID = new UUID(0L, 0L);
    private static final Identifier PAYMENT = action("career_promotion_payment");
    private static final Identifier PAYMENT_RECOVERED = action("career_promotion_payment_recovered");
    private static final Identifier COMPLETED = action("career_promotion_completed");
    private static final Identifier PAYMENT_DENIED = action("career_promotion_payment_denied");

    public enum Status {
        SUCCESS,
        INVALID_REQUEST,
        READ_ONLY,
        ACCOUNT_NOT_FOUND,
        INSUFFICIENT_FUNDS,
        INVALID_CONFIGURATION,
        DUPLICATE_PENDING,
        DUPLICATE_COMPLETED,
        TRANSACTION_CONFLICT,
        LEDGER_FULL,
        STATE_CONFLICT
    }

    public record Result(
            Status status,
            long beforeBalance,
            long balance,
            Optional<RpgSkillOperation> operation,
            boolean committed) {
        public Result {
            operation = operation == null ? Optional.empty() : operation;
        }
    }

    private CareerPromotionPaymentService() {
    }

    public static Result begin(
            PlatformSavedData state,
            UUID playerId,
            Identifier careerId,
            long cost,
            long timestampEpochMillis,
            UUID transactionId,
            long initialBalance,
            long maximumBalance) {
        return begin(state, playerId, careerId, cost, List.of(), List.of(), List.of(), timestampEpochMillis,
                transactionId, initialBalance, maximumBalance);
    }

    public static Result begin(
            PlatformSavedData state,
            UUID playerId,
            Identifier careerId,
            long cost,
            List<RpgItemCost> itemCosts,
            List<Long> itemCountsBefore,
            List<Long> itemCountsAfter,
            long timestampEpochMillis,
            UUID transactionId,
            long initialBalance,
            long maximumBalance) {
        return pay(state, RpgSkillOperation.careerPromotion(
                        playerId, careerId, cost, itemCosts, itemCountsBefore, itemCountsAfter, timestampEpochMillis,
                        itemCosts.isEmpty() ? RpgSkillOperation.Phase.PENDING
                                : RpgSkillOperation.Phase.ITEMS_CONSUMED),
                transactionId, initialBalance, maximumBalance, false);
    }

    public static Result recoverCompleted(
            PlatformSavedData state,
            UUID playerId,
            Identifier careerId,
            long cost,
            long timestampEpochMillis,
            UUID transactionId,
            long initialBalance,
            long maximumBalance) {
        return recoverCompleted(state, playerId, careerId, cost, List.of(), List.of(), List.of(),
                timestampEpochMillis,
                transactionId, initialBalance, maximumBalance);
    }

    public static Result recoverCompleted(
            PlatformSavedData state,
            UUID playerId,
            Identifier careerId,
            long cost,
            List<RpgItemCost> itemCosts,
            List<Long> itemCountsBefore,
            List<Long> itemCountsAfter,
            long timestampEpochMillis,
            UUID transactionId,
            long initialBalance,
            long maximumBalance) {
        return pay(state, RpgSkillOperation.careerPromotion(
                        playerId, careerId, cost, itemCosts, itemCountsBefore, itemCountsAfter,
                        timestampEpochMillis, RpgSkillOperation.Phase.COMPLETED),
                transactionId, initialBalance, maximumBalance, true);
    }

    public static Result complete(
            PlatformSavedData state,
            UUID playerId,
            UUID transactionId,
            long timestampEpochMillis) {
        if (state == null || playerId == null || ZERO_UUID.equals(playerId)
                || transactionId == null || ZERO_UUID.equals(transactionId) || timestampEpochMillis < 0) {
            return result(Status.INVALID_REQUEST, 0, 0, null, false);
        }
        if (!state.isWritable()) {
            return result(Status.READ_ONLY, 0, 0, null, false);
        }
        RpgSkillOperation operation = state.rpgSkillOperation(transactionId).orElse(null);
        long balance = state.economyBalance(playerId).orElse(0L);
        if (operation == null || operation.kind() != RpgSkillOperation.Kind.CAREER_PROMOTION
                || !operation.playerId().equals(playerId)) {
            return result(Status.STATE_CONFLICT, balance, balance, operation, false);
        }
        if (operation.phase() == RpgSkillOperation.Phase.COMPLETED) {
            return result(Status.DUPLICATE_COMPLETED, balance, balance, operation, false);
        }
        if (!receiptMatches(state, transactionId, operation)) {
            return result(Status.STATE_CONFLICT, balance, balance, operation, false);
        }
        state.completeRpgSkillOperation(transactionId, operation, audit(
                timestampEpochMillis, COMPLETED, operation, balance, balance,
                "rpg_evidence_committed", transactionId));
        return result(Status.SUCCESS, balance, balance, operation.completed(), true);
    }

    private static Result pay(
            PlatformSavedData state,
            RpgSkillOperation operation,
            UUID transactionId,
            long initialBalance,
            long maximumBalance,
            boolean recovered) {
        if (state == null || operation.kind() != RpgSkillOperation.Kind.CAREER_PROMOTION
                || operation.playerId() == null || ZERO_UUID.equals(operation.playerId())
                || operation.target() == null || operation.cost() < 0
                || operation.cost() > CareerDefinition.MAX_PROMOTION_COST
                || operation.cost() == 0 && operation.itemCosts().isEmpty()
                || !operation.hasInventoryEvidence()
                || operation.timestampEpochMillis() < 0 || transactionId == null
                || ZERO_UUID.equals(transactionId)) {
            return result(Status.INVALID_REQUEST, 0, 0, null, false);
        }
        if (!state.isWritable()) {
            return result(Status.READ_ONLY, 0, 0, null, false);
        }
        long before = state.economyBalance(operation.playerId()).orElse(Math.max(0, initialBalance));
        RpgSkillOperation existing = state.rpgSkillOperation(transactionId).orElse(null);
        if (existing != null) {
            boolean matches = existing.matchesPromotion(
                    operation.playerId(), operation.target(), operation.cost(), operation.itemCosts());
            matches = matches && existing.itemCountsBefore().equals(operation.itemCountsBefore())
                    && existing.itemCountsAfter().equals(operation.itemCountsAfter());
            if (!matches || !receiptMatches(state, transactionId, existing)) {
                return denied(state, operation, transactionId, Status.TRANSACTION_CONFLICT, before);
            }
            Status duplicate = existing.phase() == RpgSkillOperation.Phase.COMPLETED
                    ? Status.DUPLICATE_COMPLETED : Status.DUPLICATE_PENDING;
            return result(duplicate, before, before, existing, false);
        }
        if (!state.pendingRpgSkillOperations(operation.playerId()).isEmpty()) {
            return denied(state, operation, transactionId, Status.STATE_CONFLICT, before);
        }
        if (state.economyReceipt(transactionId).isPresent()
                || state.hasEconomyTransaction(transactionId, operation.timestampEpochMillis())) {
            return denied(state, operation, transactionId, Status.TRANSACTION_CONFLICT, before);
        }
        if (state.economyBalance(operation.playerId()).isEmpty()) {
            return denied(state, operation, transactionId, Status.ACCOUNT_NOT_FOUND, before);
        }
        if (!EconomyConfig.isValid(initialBalance, maximumBalance)) {
            return denied(state, operation, transactionId, Status.INVALID_CONFIGURATION, before);
        }
        if (operation.cost() > before) {
            return denied(state, operation, transactionId, Status.INSUFFICIENT_FUNDS, before);
        }
        if (!state.canCommitRpgSkillPayment(transactionId, operation.timestampEpochMillis())) {
            return denied(state, operation, transactionId, Status.LEDGER_FULL, before);
        }
        long after = before - operation.cost();
        EconomyTransactionReceipt receipt = new EconomyTransactionReceipt(
                operation.timestampEpochMillis(), AdministrationService.SYSTEM_ACTOR, operation.playerId(),
                EconomyTransactionReceipt.Kind.CAREER_PROMOTION_PAYMENT, operation.cost(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), 0, Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(),
                EconomyTransactionReceipt.CompensationDecision.NONE);
        List<EconomyAlert> alerts = EconomyMonitoringService.evaluate(
                state, transactionId, receipt, EconomyConfig.alertThresholds());
        state.commitRpgSkillPayment(
                operation.playerId(), after, transactionId, operation.timestampEpochMillis(), receipt, alerts,
                operation, audit(operation.timestampEpochMillis(), recovered ? PAYMENT_RECOVERED : PAYMENT,
                        operation, before, after, recovered ? "rpg_evidence_recovery" : "paid_career_promotion",
                        transactionId));
        EconomyMonitoringService.publish(alerts);
        return result(Status.SUCCESS, before, after, operation, true);
    }

    private static boolean receiptMatches(
            PlatformSavedData state, UUID transactionId, RpgSkillOperation operation) {
        EconomyTransactionReceipt receipt = state.economyReceipt(transactionId).orElse(null);
        return receipt != null
                && operation.kind() == RpgSkillOperation.Kind.CAREER_PROMOTION
                && receipt.actorId().equals(AdministrationService.SYSTEM_ACTOR)
                && receipt.playerId().equals(operation.playerId())
                && receipt.kind() == EconomyTransactionReceipt.Kind.CAREER_PROMOTION_PAYMENT
                && receipt.amount() == operation.cost();
    }

    private static AuditEntry audit(
            long timestamp,
            Identifier action,
            RpgSkillOperation operation,
            long before,
            long after,
            String reason,
            UUID transactionId) {
        return new AuditEntry(
                timestamp, AdministrationService.SYSTEM_ACTOR, action,
                operation.playerId() + ":" + operation.target(), Optional.empty(), Optional.empty(),
                Long.toString(before), Long.toString(after), reason, transactionId);
    }

    private static Result result(
            Status status, long before, long after, RpgSkillOperation operation, boolean committed) {
        return new Result(status, before, after, Optional.ofNullable(operation), committed);
    }

    private static Result denied(
            PlatformSavedData state,
            RpgSkillOperation operation,
            UUID transactionId,
            Status status,
            long balance) {
        state.appendDeniedAudit(audit(
                operation.timestampEpochMillis(), PAYMENT_DENIED, operation, balance, balance,
                status.name().toLowerCase(java.util.Locale.ROOT), transactionId), 1_000L);
        return result(status, balance, balance, null, false);
    }

    private static Identifier action(String path) {
        return Identifier.fromNamespaceAndPath(Rovenfall.MOD_ID, path);
    }
}
