package org.dldyou.rovenfall.administration;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.resources.Identifier;
import org.dldyou.rovenfall.Rovenfall;
import org.dldyou.rovenfall.activities.ActivityTrack;
import org.dldyou.rovenfall.careers.CareerCatalog;
import org.dldyou.rovenfall.careers.CareerDefinition;
import org.dldyou.rovenfall.careers.CareerPromotionReceipt;
import org.dldyou.rovenfall.careers.PlayerCareerState;

public final class CareerPromotionService {
    private static final UUID ZERO_UUID = new UUID(0L, 0L);
    private static final long DENIED_AUDIT_INTERVAL_MILLIS = 1_000L;
    private static final Identifier PROMOTION = action("career_promotion");
    private static final Identifier PROMOTION_DENIED = action("career_promotion_denied");

    private CareerPromotionService() {
    }

    public static PromotionEvaluation evaluate(
            PlatformSavedData state,
            CareerCatalog catalog,
            UUID playerId,
            Identifier targetCareer,
            Map<ActivityTrack, Integer> activityLevels) {
        if (state == null || catalog == null || playerId == null || targetCareer == null
                || activityLevels == null) {
            return evaluation(Status.INVALID_REQUEST, targetCareer, Optional.empty(), Optional.empty(),
                    List.of(), List.of(), Set.of(), 0, 0, false, false);
        }
        PlayerCareerState careers = state.playerCareerState(playerId);
        Optional<Identifier> active = careers.activeCareer();
        Optional<CareerDefinition> retainedDefinition = catalog.definition(targetCareer);
        if (!state.isWritable()) {
            return evaluation(Status.READ_ONLY_SCHEMA, targetCareer, retainedDefinition, active,
                    List.of(), List.of(), Set.of(), 0,
                    state.economyBalance(playerId).orElse(0L), careers.learnedCareers().contains(targetCareer), false);
        }
        if (retainedDefinition.isEmpty()) {
            return evaluation(Status.CAREER_NOT_FOUND, targetCareer, Optional.empty(), active,
                    List.of(), List.of(), Set.of(), 0,
                    state.economyBalance(playerId).orElse(0L), false, false);
        }
        CareerDefinition definition = retainedDefinition.orElseThrow();
        List<ParentRequirement> parents = definition.parents().stream()
                .map(parent -> new ParentRequirement(parent, careers.learnedCareers().contains(parent)))
                .toList();
        List<ActivityRequirement> requirements = definition.activityLevelRequirements().entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparingInt(Enum::ordinal)))
                .map(entry -> new ActivityRequirement(
                        entry.getKey(), entry.getValue(), activityLevels.getOrDefault(entry.getKey(), 0)))
                .toList();
        Set<Identifier> resetCareers = catalog.conflictingLearnedCareers(
                targetCareer, careers.learnedCareers());
        long balance = state.economyBalance(playerId).orElse(0L);
        boolean learned = careers.learnedCareers().contains(targetCareer);
        Status status;
        if (active.equals(Optional.of(targetCareer))) {
            status = Status.ALREADY_ACTIVE;
        } else if (parents.stream().anyMatch(requirement -> !requirement.met())) {
            status = Status.PARENT_NOT_LEARNED;
        } else if (requirements.stream().anyMatch(requirement -> !requirement.met())) {
            status = Status.ACTIVITY_REQUIREMENT_NOT_MET;
        } else if (definition.promotionCost() > 0 && state.economyBalance(playerId).isEmpty()) {
            status = Status.ACCOUNT_NOT_FOUND;
        } else if (definition.promotionCost() > balance) {
            status = Status.INSUFFICIENT_FUNDS;
        } else {
            status = Status.SUCCESS;
        }
        return evaluation(status, targetCareer, retainedDefinition, active, parents, requirements, resetCareers,
                definition.promotionCost(), balance, learned, !resetCareers.isEmpty());
    }

    public static PromotionResult promote(
            PlatformSavedData state,
            CareerCatalog catalog,
            UUID playerId,
            Identifier targetCareer,
            Map<ActivityTrack, Integer> activityLevels,
            long timestampEpochMillis,
            UUID transactionId) {
        if (state == null || catalog == null || playerId == null || targetCareer == null
                || activityLevels == null || timestampEpochMillis < 0) {
            return result(Status.INVALID_REQUEST, null, transactionId, false);
        }
        if (!validTransactionId(transactionId)) {
            PromotionEvaluation evaluation = evaluate(state, catalog, playerId, targetCareer, activityLevels);
            return denied(state, playerId, targetCareer, Status.INVALID_TRANSACTION,
                    evaluation, timestampEpochMillis, transactionId);
        }
        Optional<CareerPromotionReceipt> retained = state.careerPromotionReceipt(transactionId);
        if (retained.isPresent()) {
            PromotionEvaluation evaluation = evaluate(state, catalog, playerId, targetCareer, activityLevels);
            return retained.orElseThrow().matches(playerId, targetCareer)
                    ? result(Status.DUPLICATE_TRANSACTION, evaluation, transactionId, false)
                    : denied(state, playerId, targetCareer, Status.TRANSACTION_ID_CONFLICT,
                            evaluation, timestampEpochMillis, transactionId);
        }
        if (state.economyReceipt(transactionId).isPresent()
                || state.hasEconomyTransaction(transactionId, timestampEpochMillis)) {
            return denied(state, playerId, targetCareer, Status.TRANSACTION_ID_CONFLICT,
                    evaluate(state, catalog, playerId, targetCareer, activityLevels),
                    timestampEpochMillis, transactionId);
        }
        PromotionEvaluation evaluation = evaluate(state, catalog, playerId, targetCareer, activityLevels);
        if (!evaluation.allowed()) {
            if (evaluation.status() == Status.READ_ONLY_SCHEMA) {
                return result(Status.READ_ONLY_SCHEMA, evaluation, transactionId, false);
            }
            return denied(state, playerId, targetCareer, evaluation.status(), evaluation,
                    timestampEpochMillis, transactionId);
        }
        if (!state.canCommitCareerPromotionTransaction(playerId, transactionId, timestampEpochMillis)) {
            return denied(state, playerId, targetCareer, Status.TRANSACTION_LEDGER_FULL, evaluation,
                    timestampEpochMillis, transactionId);
        }

        PlayerCareerState before = state.playerCareerState(playerId);
        int promotionSkillPoints = before.learnedCareers().contains(targetCareer)
                ? 0
                : evaluation.definition().orElseThrow().promotionSkillPoints();
        PlayerCareerState after;
        try {
            after = before.promote(
                    targetCareer,
                    evaluation.resetCareers(),
                    promotionSkillPoints);
        } catch (IllegalStateException exception) {
            return denied(state, playerId, targetCareer, Status.CAREER_CAP_REACHED, evaluation,
                    timestampEpochMillis, transactionId);
        }
        long afterBalance = Math.subtractExact(evaluation.balance(), evaluation.promotionCost());
        var economyReceipt = new EconomyTransactionReceipt(
                timestampEpochMillis,
                playerId,
                playerId,
                EconomyTransactionReceipt.Kind.CAREER_PROMOTION,
                evaluation.promotionCost(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), 0,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                EconomyTransactionReceipt.CompensationDecision.NONE);
        List<EconomyAlert> alerts = EconomyMonitoringService.evaluate(
                state, transactionId, economyReceipt, EconomyConfig.alertThresholds());
        var careerReceipt = new CareerPromotionReceipt(
                timestampEpochMillis,
                transactionId,
                playerId,
                targetCareer,
                evaluation.promotionCost(),
                promotionSkillPoints,
                before.activeCareer(),
                evaluation.resetCareers());
        state.commitCareerPromotion(
                playerId,
                afterBalance,
                evaluation.promotionCost() > 0,
                after,
                transactionId,
                timestampEpochMillis,
                economyReceipt,
                careerReceipt,
                alerts,
                new AuditEntry(
                        timestampEpochMillis,
                        playerId,
                        PROMOTION,
                        targetCareer.toString(),
                        Optional.empty(),
                        Optional.empty(),
                        auditState(before, evaluation.balance()),
                        auditState(after, afterBalance),
                        "career_promotion",
                        transactionId));
        EconomyMonitoringService.publish(alerts);
        return result(Status.SUCCESS, evaluation, transactionId, true);
    }

    private static PromotionResult denied(
            PlatformSavedData state,
            UUID playerId,
            Identifier targetCareer,
            Status status,
            PromotionEvaluation evaluation,
            long timestampEpochMillis,
            UUID transactionId) {
        if (!state.isWritable()) {
            return result(status, evaluation, transactionId, false);
        }
        UUID auditId = validTransactionId(transactionId) ? transactionId : UUID.randomUUID();
        PlayerCareerState careers = state.playerCareerState(playerId);
        long balance = state.economyBalance(playerId).orElse(0L);
        boolean audited = state.appendDeniedAudit(new AuditEntry(
                timestampEpochMillis,
                playerId,
                PROMOTION_DENIED,
                targetCareer.toString(),
                Optional.empty(),
                Optional.empty(),
                auditState(careers, balance),
                auditState(careers, balance),
                status.id(),
                auditId), DENIED_AUDIT_INTERVAL_MILLIS);
        return result(status, evaluation, transactionId, audited);
    }

    private static String auditState(PlayerCareerState state, long balance) {
        return "active=" + state.activeCareer().map(Identifier::toString).orElse("none")
                + ";learned=" + state.learnedCareers().size()
                + ";balance=" + balance;
    }

    private static boolean validTransactionId(UUID transactionId) {
        return transactionId != null && !ZERO_UUID.equals(transactionId);
    }

    private static Identifier action(String path) {
        return Identifier.fromNamespaceAndPath(Rovenfall.MOD_ID, path);
    }

    private static PromotionEvaluation evaluation(
            Status status,
            Identifier targetCareer,
            Optional<CareerDefinition> definition,
            Optional<Identifier> activeCareer,
            List<ParentRequirement> parentRequirements,
            List<ActivityRequirement> activityRequirements,
            Set<Identifier> resetCareers,
            long promotionCost,
            long balance,
            boolean learned,
            boolean requiresBranchReset) {
        return new PromotionEvaluation(
                status, targetCareer, definition, activeCareer, parentRequirements, activityRequirements,
                resetCareers, promotionCost, balance, learned, requiresBranchReset);
    }

    private static PromotionResult result(
            Status status,
            PromotionEvaluation evaluation,
            UUID transactionId,
            boolean auditRecorded) {
        return new PromotionResult(status, Optional.ofNullable(evaluation), transactionId, auditRecorded);
    }

    public enum Status {
        SUCCESS,
        DUPLICATE_TRANSACTION,
        TRANSACTION_ID_CONFLICT,
        INVALID_REQUEST,
        INVALID_TRANSACTION,
        READ_ONLY_SCHEMA,
        CAREER_NOT_FOUND,
        ALREADY_ACTIVE,
        PARENT_NOT_LEARNED,
        ACTIVITY_REQUIREMENT_NOT_MET,
        ACCOUNT_NOT_FOUND,
        INSUFFICIENT_FUNDS,
        CAREER_CAP_REACHED,
        TRANSACTION_LEDGER_FULL;

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        public String evaluationTranslationKey() {
            return "career_promotion_evaluation.rovenfall." + id();
        }
    }

    public record ParentRequirement(Identifier careerId, boolean met) {
    }

    public record ActivityRequirement(
            ActivityTrack track,
            int requiredLevel,
            int currentLevel) {
        public boolean met() {
            return currentLevel >= requiredLevel;
        }
    }

    public record PromotionEvaluation(
            Status status,
            Identifier targetCareer,
            Optional<CareerDefinition> definition,
            Optional<Identifier> activeCareer,
            List<ParentRequirement> parentRequirements,
            List<ActivityRequirement> activityRequirements,
            Set<Identifier> resetCareers,
            long promotionCost,
            long balance,
            boolean learned,
            boolean requiresBranchReset) {
        public PromotionEvaluation {
            definition = definition == null ? Optional.empty() : definition;
            activeCareer = activeCareer == null ? Optional.empty() : activeCareer;
            parentRequirements = parentRequirements == null ? List.of() : List.copyOf(parentRequirements);
            activityRequirements = activityRequirements == null ? List.of() : List.copyOf(activityRequirements);
            resetCareers = resetCareers == null ? Set.of() : Set.copyOf(resetCareers);
        }

        public boolean allowed() {
            return status == Status.SUCCESS;
        }
    }

    public record PromotionResult(
            Status status,
            Optional<PromotionEvaluation> evaluation,
            UUID transactionId,
            boolean auditRecorded) {
        public PromotionResult {
            evaluation = evaluation == null ? Optional.empty() : evaluation;
        }
    }
}
