package org.dldyou.rovenfall.administration;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.Identifier;
import org.dldyou.rovenfall.activities.DailyContractDefinition;

public final class DailyContractService {
    public static final long PERIOD_MILLIS = Duration.ofDays(1).toMillis();

    private DailyContractService() {
    }

    public static Evaluation evaluate(
            PlatformSavedData state,
            UUID playerId,
            Identifier contractId,
            DailyContractDefinition definition,
            long timestampEpochMillis) {
        if (state == null || playerId == null || contractId == null
                || contractId.toString().length() > DailyContractDefinition.MAX_DEFINITION_ID_LENGTH
                || DailyContractDefinition.validate(definition).error().isPresent()
                || timestampEpochMillis < 0
                || timestampEpochMillis > Long.MAX_VALUE - PERIOD_MILLIS) {
            return new Evaluation(Status.INVALID_REQUEST, new UUID(0L, 0L), 0, 0, 0, 0);
        }
        long periodStart = periodStart(timestampEpochMillis);
        long nextReset = periodStart + PERIOD_MILLIS;
        UUID transactionId = transactionId(playerId, contractId, periodStart);
        long progress = state.activityAwardedExperienceInDimension(
                playerId,
                WorldCombatService.WILDERNESS_DIMENSION,
                definition.kind().track(),
                definition.kind(),
                definition.targetId(),
                timestampEpochMillis,
                timestampEpochMillis - periodStart);
        Optional<EconomyTransactionReceipt> retained = state.economyReceipt(transactionId);
        if (retained.isPresent()) {
            return new Evaluation(
                    receiptMatches(retained.orElseThrow(), playerId)
                            ? Status.ALREADY_CLAIMED
                            : Status.TRANSACTION_CONFLICT,
                    transactionId, progress, definition.requiredExperience(), periodStart, nextReset);
        }
        if (!state.isWritable()) {
            return new Evaluation(Status.READ_ONLY_SCHEMA, transactionId,
                    progress, definition.requiredExperience(), periodStart, nextReset);
        }
        return new Evaluation(
                progress >= definition.requiredExperience() ? Status.CLAIMABLE : Status.IN_PROGRESS,
                transactionId, progress, definition.requiredExperience(), periodStart, nextReset);
    }

    public static ClaimResult claim(
            PlatformSavedData state,
            UUID playerId,
            Identifier contractId,
            DailyContractDefinition definition,
            long timestampEpochMillis,
            long initialBalance,
            long maximumBalance) {
        Evaluation evaluation = evaluate(state, playerId, contractId, definition, timestampEpochMillis);
        long currentBalance = state == null || playerId == null
                ? 0
                : state.economyBalance(playerId).orElse(Math.max(0, initialBalance));
        if (evaluation.status() != Status.CLAIMABLE
                && evaluation.status() != Status.TRANSACTION_CONFLICT) {
            return new ClaimResult(
                    evaluation.status(), evaluation, 0, currentBalance, false, Optional.empty());
        }
        EconomyService.TransactionResult economy = EconomyService.award(
                state,
                playerId,
                definition.currencyReward(),
                "daily contract " + contractId + " period " + evaluation.periodStartEpochMillis(),
                timestampEpochMillis,
                evaluation.transactionId(),
                initialBalance,
                maximumBalance);
        Status status = switch (economy.status()) {
            case SUCCESS -> Status.SUCCESS;
            case DUPLICATE_TRANSACTION -> Status.ALREADY_CLAIMED;
            case TRANSACTION_ID_CONFLICT -> Status.TRANSACTION_CONFLICT;
            case READ_ONLY_SCHEMA -> Status.READ_ONLY_SCHEMA;
            default -> Status.REWARD_FAILED;
        };
        return new ClaimResult(
                status,
                evaluation,
                status == Status.SUCCESS ? definition.currencyReward() : 0,
                economy.balance(),
                economy.auditRecorded(),
                Optional.of(economy.status()));
    }

    public static long periodStart(long timestampEpochMillis) {
        if (timestampEpochMillis < 0) {
            throw new IllegalArgumentException("Daily contract timestamp must be non-negative");
        }
        return timestampEpochMillis - timestampEpochMillis % PERIOD_MILLIS;
    }

    public static UUID transactionId(UUID playerId, Identifier contractId, long periodStartEpochMillis) {
        if (playerId == null || contractId == null || periodStartEpochMillis < 0
                || periodStartEpochMillis % PERIOD_MILLIS != 0) {
            return new UUID(0L, 0L);
        }
        return UUID.nameUUIDFromBytes(("rovenfall:daily_contract:" + playerId + ":"
                + contractId + ":" + periodStartEpochMillis).getBytes(StandardCharsets.UTF_8));
    }

    public static boolean claimedForPeriod(
            PlatformSavedData state,
            UUID playerId,
            Identifier contractId,
            long periodStartEpochMillis) {
        if (state == null || playerId == null || contractId == null
                || contractId.toString().length() > DailyContractDefinition.MAX_DEFINITION_ID_LENGTH
                || periodStartEpochMillis < 0
                || periodStartEpochMillis % PERIOD_MILLIS != 0) {
            return false;
        }
        return state.economyReceipt(transactionId(playerId, contractId, periodStartEpochMillis))
                .filter(receipt -> receiptMatches(receipt, playerId))
                .isPresent();
    }

    private static boolean receiptMatches(EconomyTransactionReceipt receipt, UUID playerId) {
        return receipt.actorId().equals(AdministrationService.SYSTEM_ACTOR)
                && receipt.playerId().equals(playerId)
                && receipt.kind() == EconomyTransactionReceipt.Kind.AWARD;
    }

    public enum Status {
        SUCCESS,
        CLAIMABLE,
        ALREADY_CLAIMED,
        IN_PROGRESS,
        INVALID_REQUEST,
        READ_ONLY_SCHEMA,
        TRANSACTION_CONFLICT,
        REWARD_FAILED;

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        public String translationKey() {
            return "daily_contract_status.rovenfall." + id();
        }
    }

    public record Evaluation(
            Status status,
            UUID transactionId,
            long progressExperience,
            long requiredExperience,
            long periodStartEpochMillis,
            long nextResetEpochMillis) {
        public boolean complete() {
            return requiredExperience > 0 && progressExperience >= requiredExperience;
        }
    }

    public record ClaimResult(
            Status status,
            Evaluation evaluation,
            long awardedCurrency,
            long balance,
            boolean auditRecorded,
            Optional<EconomyService.TransactionStatus> economyStatus) {
        public ClaimResult {
            economyStatus = economyStatus == null ? Optional.empty() : economyStatus;
        }
    }
}
