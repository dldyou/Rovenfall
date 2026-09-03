package org.dldyou.rovenfall.administration;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.Identifier;
import org.dldyou.rovenfall.activities.ActivityChallengeDefinition;
import org.dldyou.rovenfall.activities.ActivityTrack;

public final class ActivityChallengeService {
    private ActivityChallengeService() {
    }

    public static Evaluation evaluate(
            PlatformSavedData state,
            UUID playerId,
            Identifier challengeId,
            ActivityChallengeDefinition definition,
            Map<ActivityTrack, Integer> activityLevels) {
        UUID transactionId = transactionId(playerId, challengeId);
        if (state == null || playerId == null || challengeId == null
                || challengeId.toString().length() > ActivityChallengeDefinition.MAX_DEFINITION_ID_LENGTH
                || ActivityChallengeDefinition.validate(definition).error().isPresent()
                || activityLevels == null
                || activityLevels.values().stream().anyMatch(level -> level == null || level < 0)) {
            return new Evaluation(Status.INVALID_REQUEST, transactionId, List.of());
        }

        List<Requirement> requirements = definition.activityLevelRequirements().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new Requirement(
                        entry.getKey(), entry.getValue(), activityLevels.getOrDefault(entry.getKey(), 0)))
                .toList();
        Optional<EconomyTransactionReceipt> retained = state.economyReceipt(transactionId);
        if (retained.isPresent()) {
            return new Evaluation(
                    receiptMatches(retained.orElseThrow(), playerId)
                            ? Status.ALREADY_CLAIMED
                            : Status.TRANSACTION_CONFLICT,
                    transactionId,
                    requirements);
        }
        if (!state.isWritable()) {
            return new Evaluation(Status.READ_ONLY_SCHEMA, transactionId, requirements);
        }
        return new Evaluation(
                requirements.stream().allMatch(Requirement::met)
                        ? Status.CLAIMABLE
                        : Status.REQUIREMENTS_NOT_MET,
                transactionId,
                requirements);
    }

    public static ClaimResult claim(
            PlatformSavedData state,
            UUID playerId,
            Identifier challengeId,
            ActivityChallengeDefinition definition,
            Map<ActivityTrack, Integer> activityLevels,
            long timestampEpochMillis,
            long initialBalance,
            long maximumBalance) {
        Evaluation evaluation = evaluate(state, playerId, challengeId, definition, activityLevels);
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
                "activity challenge " + challengeId,
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

    public static UUID transactionId(UUID playerId, Identifier challengeId) {
        if (playerId == null || challengeId == null) {
            return new UUID(0L, 0L);
        }
        return UUID.nameUUIDFromBytes(("rovenfall:activity_challenge:" + playerId + ":" + challengeId)
                .getBytes(StandardCharsets.UTF_8));
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
        REQUIREMENTS_NOT_MET,
        INVALID_REQUEST,
        READ_ONLY_SCHEMA,
        TRANSACTION_CONFLICT,
        REWARD_FAILED;

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        public String translationKey() {
            return "activity_challenge_status.rovenfall." + id();
        }
    }

    public record Requirement(ActivityTrack track, int requiredLevel, int currentLevel) {
        public boolean met() {
            return currentLevel >= requiredLevel;
        }
    }

    public record Evaluation(Status status, UUID transactionId, List<Requirement> requirements) {
        public Evaluation {
            requirements = requirements == null ? List.of() : List.copyOf(requirements);
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
