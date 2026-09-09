package org.dldyou.rovenfall.administration;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.Identifier;
import org.dldyou.rovenfall.activities.WeeklyExpeditionDefinition;

public final class WeeklyExpeditionService {
    public static final long PERIOD_MILLIS = 7 * DailyContractService.PERIOD_MILLIS;
    private static final long MONDAY_SHIFT_MILLIS = 3 * DailyContractService.PERIOD_MILLIS;

    private WeeklyExpeditionService() {
    }

    public static Evaluation evaluate(
            PlatformSavedData state,
            UUID playerId,
            Identifier expeditionId,
            WeeklyExpeditionDefinition definition,
            long timestampEpochMillis) {
        if (state == null || playerId == null || expeditionId == null
                || expeditionId.toString().length() > WeeklyExpeditionDefinition.MAX_DEFINITION_ID_LENGTH
                || WeeklyExpeditionDefinition.validate(definition).error().isPresent()
                || timestampEpochMillis < 0
                || timestampEpochMillis > Long.MAX_VALUE - PERIOD_MILLIS) {
            return new Evaluation(
                    Status.INVALID_REQUEST, new UUID(0L, 0L), List.of(), 0, 0);
        }
        long periodStart = periodStart(timestampEpochMillis);
        long nextReset = periodStart + PERIOD_MILLIS;
        UUID transactionId = transactionId(playerId, expeditionId, periodStart);
        List<Requirement> requirements = definition.dailyContractRequirements().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new Requirement(
                        entry.getKey(),
                        entry.getValue(),
                        claimedDays(state, playerId, entry.getKey(), periodStart, timestampEpochMillis)))
                .toList();
        Optional<EconomyTransactionReceipt> retained = state.economyReceipt(transactionId);
        if (retained.isPresent()) {
            return new Evaluation(
                    receiptMatches(retained.orElseThrow(), playerId)
                            ? Status.ALREADY_CLAIMED
                            : Status.TRANSACTION_CONFLICT,
                    transactionId,
                    requirements,
                    periodStart,
                    nextReset);
        }
        if (!state.isWritable()) {
            return new Evaluation(
                    Status.READ_ONLY_SCHEMA, transactionId, requirements, periodStart, nextReset);
        }
        return new Evaluation(
                requirements.stream().allMatch(Requirement::met)
                        ? Status.CLAIMABLE
                        : Status.IN_PROGRESS,
                transactionId,
                requirements,
                periodStart,
                nextReset);
    }

    public static ClaimResult claim(
            PlatformSavedData state,
            UUID playerId,
            Identifier expeditionId,
            WeeklyExpeditionDefinition definition,
            long timestampEpochMillis,
            long initialBalance,
            long maximumBalance) {
        Evaluation evaluation = evaluate(
                state, playerId, expeditionId, definition, timestampEpochMillis);
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
                "weekly expedition " + expeditionId + " period " + evaluation.periodStartEpochMillis(),
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
        if (timestampEpochMillis < 0
                || timestampEpochMillis > Long.MAX_VALUE - MONDAY_SHIFT_MILLIS) {
            throw new IllegalArgumentException("Weekly expedition timestamp is out of range");
        }
        long shifted = timestampEpochMillis + MONDAY_SHIFT_MILLIS;
        return shifted - shifted % PERIOD_MILLIS - MONDAY_SHIFT_MILLIS;
    }

    public static UUID transactionId(
            UUID playerId, Identifier expeditionId, long periodStartEpochMillis) {
        if (playerId == null || expeditionId == null
                || expeditionId.toString().length() > WeeklyExpeditionDefinition.MAX_DEFINITION_ID_LENGTH
                || periodStartEpochMillis < -MONDAY_SHIFT_MILLIS
                || periodStartEpochMillis > Long.MAX_VALUE - MONDAY_SHIFT_MILLIS
                || (periodStartEpochMillis + MONDAY_SHIFT_MILLIS) % PERIOD_MILLIS != 0) {
            return new UUID(0L, 0L);
        }
        return UUID.nameUUIDFromBytes(("rovenfall:weekly_expedition:" + playerId + ":"
                + expeditionId + ":" + periodStartEpochMillis).getBytes(StandardCharsets.UTF_8));
    }

    private static int claimedDays(
            PlatformSavedData state,
            UUID playerId,
            Identifier contractId,
            long periodStartEpochMillis,
            long timestampEpochMillis) {
        int claimed = 0;
        for (int day = 0; day < 7; day++) {
            long dayStart = periodStartEpochMillis + day * DailyContractService.PERIOD_MILLIS;
            if (dayStart < 0) {
                continue;
            }
            if (dayStart > timestampEpochMillis) {
                break;
            }
            if (DailyContractService.claimedForPeriod(state, playerId, contractId, dayStart)) {
                claimed++;
            }
        }
        return claimed;
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
            return "weekly_expedition_status.rovenfall." + id();
        }
    }

    public record Requirement(
            Identifier dailyContractId,
            int requiredCompletions,
            int currentCompletions) {
        public boolean met() {
            return currentCompletions >= requiredCompletions;
        }
    }

    public record Evaluation(
            Status status,
            UUID transactionId,
            List<Requirement> requirements,
            long periodStartEpochMillis,
            long nextResetEpochMillis) {
        public Evaluation {
            requirements = requirements == null ? List.of() : List.copyOf(requirements);
        }

        public boolean complete() {
            return !requirements.isEmpty() && requirements.stream().allMatch(Requirement::met);
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
