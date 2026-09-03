package org.dldyou.rovenfall.administration;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.Identifier;
import org.dldyou.rovenfall.Rovenfall;
import org.dldyou.rovenfall.careers.CareerCatalog;
import org.dldyou.rovenfall.careers.CareerDefinition;
import org.dldyou.rovenfall.careers.CareerProgress;
import org.dldyou.rovenfall.careers.PlayerCareerState;
import org.dldyou.rovenfall.careers.SkillMutationReceipt;

public final class CareerSkillService {
    private static final UUID ZERO_UUID = new UUID(0L, 0L);
    private static final long DENIED_AUDIT_INTERVAL_MILLIS = 1_000L;
    private static final Identifier UNLOCK = action("career_skill_unlock");
    private static final Identifier RESET = action("career_skill_reset");
    private static final Identifier DENIED = action("career_skill_denied");

    private CareerSkillService() {
    }

    public static Evaluation evaluateUnlock(
            PlatformSavedData state,
            CareerCatalog catalog,
            UUID playerId,
            Identifier skillId) {
        if (state == null || catalog == null || playerId == null || skillId == null) {
            return evaluation(Status.INVALID_REQUEST, SkillMutationReceipt.Operation.UNLOCK,
                    null, skillId, Optional.empty(), Optional.empty(), List.of(), 0, 0, 0, 0, 0, 0, 0);
        }
        Optional<CareerCatalog.SkillBinding> retainedBinding = catalog.skill(skillId);
        Identifier careerId = retainedBinding.map(CareerCatalog.SkillBinding::careerId).orElse(null);
        PlayerCareerState careers = state.playerCareerState(playerId);
        long balance = state.economyBalance(playerId).orElse(0L);
        if (!state.isWritable()) {
            return evaluation(Status.READ_ONLY_SCHEMA, SkillMutationReceipt.Operation.UNLOCK,
                    careerId, skillId, retainedBinding,
                    careerId == null ? Optional.empty() : catalog.definition(careerId), List.of(),
                    0, 0, 0, 0, 0, 0, balance);
        }
        if (retainedBinding.isEmpty()) {
            return evaluation(Status.SKILL_NOT_FOUND, SkillMutationReceipt.Operation.UNLOCK,
                    null, skillId, Optional.empty(), Optional.empty(), List.of(),
                    0, 0, 0, 0, 0, 0, balance);
        }
        CareerCatalog.SkillBinding binding = retainedBinding.orElseThrow();
        Optional<CareerDefinition> retainedCareer = catalog.definition(binding.careerId());
        if (retainedCareer.isEmpty()) {
            return evaluation(Status.CAREER_NOT_FOUND, SkillMutationReceipt.Operation.UNLOCK,
                    binding.careerId(), skillId, retainedBinding, Optional.empty(), List.of(),
                    0, 0, 0, 0, 0, 0, balance);
        }
        if (!careers.learnedCareers().contains(binding.careerId())) {
            return evaluation(Status.CAREER_NOT_LEARNED, SkillMutationReceipt.Operation.UNLOCK,
                    binding.careerId(), skillId, retainedBinding, retainedCareer, List.of(),
                    0, binding.definition().maximumRank(), 0, 0, 0,
                    binding.definition().pointCostPerRank(), balance);
        }
        CareerProgress progress = careers.progress(binding.careerId());
        int earned = progress.earnedSkillPoints(retainedCareer.orElseThrow());
        int available = progress.availableSkillPoints(retainedCareer.orElseThrow());
        int rank = progress.skillRank(skillId);
        List<PrerequisiteRequirement> prerequisites = binding.definition().prerequisites().stream()
                .map(required -> new PrerequisiteRequirement(required, progress.skillRank(required)))
                .toList();
        Status status;
        if (!isActiveLineage(catalog, careers, binding.careerId())) {
            status = Status.CAREER_NOT_ACTIVE_LINEAGE;
        } else if (rank >= binding.definition().maximumRank()) {
            status = Status.MAX_RANK_REACHED;
        } else if (prerequisites.stream().anyMatch(requirement -> !requirement.met())) {
            status = Status.PREREQUISITE_NOT_MET;
        } else if (available < binding.definition().pointCostPerRank()) {
            status = Status.INSUFFICIENT_SKILL_POINTS;
        } else {
            status = Status.SUCCESS;
        }
        return evaluation(status, SkillMutationReceipt.Operation.UNLOCK,
                binding.careerId(), skillId, retainedBinding, retainedCareer, prerequisites,
                rank, binding.definition().maximumRank(), earned, available, progress.spentSkillPoints(),
                binding.definition().pointCostPerRank(), balance);
    }

    public static Evaluation evaluateReset(
            PlatformSavedData state,
            CareerCatalog catalog,
            UUID playerId,
            Identifier careerId) {
        if (state == null || catalog == null || playerId == null || careerId == null) {
            return evaluation(Status.INVALID_REQUEST, SkillMutationReceipt.Operation.RESET,
                    careerId, null, Optional.empty(), Optional.empty(), List.of(),
                    0, 0, 0, 0, 0, 0, 0);
        }
        Optional<CareerDefinition> retainedCareer = catalog.definition(careerId);
        PlayerCareerState careers = state.playerCareerState(playerId);
        long balance = state.economyBalance(playerId).orElse(0L);
        if (!state.isWritable()) {
            return evaluation(Status.READ_ONLY_SCHEMA, SkillMutationReceipt.Operation.RESET,
                    careerId, null, Optional.empty(), retainedCareer, List.of(),
                    0, 0, 0, 0, 0,
                    retainedCareer.map(CareerDefinition::skillResetCost).orElse(0L), balance);
        }
        if (retainedCareer.isEmpty()) {
            return evaluation(Status.CAREER_NOT_FOUND, SkillMutationReceipt.Operation.RESET,
                    careerId, null, Optional.empty(), Optional.empty(), List.of(),
                    0, 0, 0, 0, 0, 0, balance);
        }
        if (!careers.learnedCareers().contains(careerId)) {
            return evaluation(Status.CAREER_NOT_LEARNED, SkillMutationReceipt.Operation.RESET,
                    careerId, null, Optional.empty(), retainedCareer, List.of(),
                    0, 0, 0, 0, 0, retainedCareer.orElseThrow().skillResetCost(), balance);
        }
        CareerProgress progress = careers.progress(careerId);
        int earned = progress.earnedSkillPoints(retainedCareer.orElseThrow());
        int available = progress.availableSkillPoints(retainedCareer.orElseThrow());
        long cost = retainedCareer.orElseThrow().skillResetCost();
        Status status;
        if (progress.skillRanks().isEmpty() || progress.spentSkillPoints() == 0) {
            status = Status.NO_SKILLS_UNLOCKED;
        } else if (cost > 0 && state.economyBalance(playerId).isEmpty()) {
            status = Status.ACCOUNT_NOT_FOUND;
        } else if (cost > balance) {
            status = Status.INSUFFICIENT_FUNDS;
        } else {
            status = Status.SUCCESS;
        }
        return evaluation(status, SkillMutationReceipt.Operation.RESET,
                careerId, null, Optional.empty(), retainedCareer, List.of(),
                0, 0, earned, available, progress.spentSkillPoints(), cost, balance);
    }

    public static MutationResult unlock(
            PlatformSavedData state,
            CareerCatalog catalog,
            UUID playerId,
            Identifier skillId,
            long timestampEpochMillis,
            UUID transactionId) {
        return mutate(state, catalog, playerId, null, skillId, SkillMutationReceipt.Operation.UNLOCK,
                timestampEpochMillis, transactionId);
    }

    public static MutationResult reset(
            PlatformSavedData state,
            CareerCatalog catalog,
            UUID playerId,
            Identifier careerId,
            long timestampEpochMillis,
            UUID transactionId) {
        return mutate(state, catalog, playerId, careerId, null, SkillMutationReceipt.Operation.RESET,
                timestampEpochMillis, transactionId);
    }

    private static MutationResult mutate(
            PlatformSavedData state,
            CareerCatalog catalog,
            UUID playerId,
            Identifier requestedCareer,
            Identifier skillId,
            SkillMutationReceipt.Operation operation,
            long timestampEpochMillis,
            UUID transactionId) {
        if (state == null || catalog == null || playerId == null || operation == null
                || operation == SkillMutationReceipt.Operation.UNLOCK && skillId == null
                || operation == SkillMutationReceipt.Operation.RESET && requestedCareer == null
                || timestampEpochMillis < 0) {
            return result(Status.INVALID_REQUEST, Optional.empty(), transactionId, false);
        }
        Identifier careerId = operation == SkillMutationReceipt.Operation.UNLOCK
                ? catalog.skill(skillId).map(CareerCatalog.SkillBinding::careerId).orElse(null)
                : requestedCareer;
        Optional<Identifier> requestedSkill = Optional.ofNullable(skillId);
        if (!validTransactionId(transactionId)) {
            Evaluation evaluation = evaluate(state, catalog, playerId, requestedCareer, skillId, operation);
            return denied(state, playerId, careerId, skillId, Status.INVALID_TRANSACTION,
                    evaluation, timestampEpochMillis, transactionId);
        }
        Optional<SkillMutationReceipt> retained = state.skillMutationReceipt(transactionId);
        if (retained.isPresent()) {
            SkillMutationReceipt receipt = retained.orElseThrow();
            Evaluation evaluation = evaluate(state, catalog, playerId, requestedCareer, skillId, operation);
            return receipt.matches(playerId, careerId, requestedSkill, operation)
                    ? result(Status.DUPLICATE_TRANSACTION, Optional.of(evaluation), transactionId, false)
                    : denied(state, playerId, careerId, skillId, Status.TRANSACTION_ID_CONFLICT,
                            evaluation, timestampEpochMillis, transactionId);
        }
        if (state.economyReceipt(transactionId).isPresent()
                || state.hasEconomyTransaction(transactionId, timestampEpochMillis)) {
            Evaluation evaluation = evaluate(state, catalog, playerId, requestedCareer, skillId, operation);
            return denied(state, playerId, careerId, skillId, Status.TRANSACTION_ID_CONFLICT,
                    evaluation, timestampEpochMillis, transactionId);
        }
        Evaluation evaluation = evaluate(state, catalog, playerId, requestedCareer, skillId, operation);
        if (!evaluation.allowed()) {
            if (evaluation.status() == Status.READ_ONLY_SCHEMA) {
                return result(Status.READ_ONLY_SCHEMA, Optional.of(evaluation), transactionId, false);
            }
            return denied(state, playerId, careerId, skillId, evaluation.status(),
                    evaluation, timestampEpochMillis, transactionId);
        }
        if (!state.canCommitSkillMutationTransaction(transactionId, timestampEpochMillis)) {
            return denied(state, playerId, careerId, skillId, Status.TRANSACTION_LEDGER_FULL,
                    evaluation, timestampEpochMillis, transactionId);
        }

        PlayerCareerState before = state.playerCareerState(playerId);
        CareerProgress beforeProgress = before.progress(evaluation.careerId());
        PlayerCareerState after;
        int rankBefore = 0;
        int rankAfter = 0;
        try {
            if (operation == SkillMutationReceipt.Operation.UNLOCK) {
                rankBefore = beforeProgress.skillRank(skillId);
                after = before.unlockSkill(evaluation.careerId(), skillId, evaluation.pointOrCurrencyCostAsInt());
                rankAfter = after.progress(evaluation.careerId()).skillRank(skillId);
            } else {
                after = before.resetSkills(evaluation.careerId());
            }
        } catch (IllegalStateException exception) {
            return denied(state, playerId, careerId, skillId, Status.SKILL_CAP_REACHED,
                    evaluation, timestampEpochMillis, transactionId);
        }
        long afterBalance = Math.subtractExact(evaluation.balance(), evaluation.currencyCost());
        EconomyTransactionReceipt.Kind kind = operation == SkillMutationReceipt.Operation.UNLOCK
                ? EconomyTransactionReceipt.Kind.SKILL_UNLOCK
                : EconomyTransactionReceipt.Kind.SKILL_RESET;
        EconomyTransactionReceipt economyReceipt = new EconomyTransactionReceipt(
                timestampEpochMillis,
                playerId,
                playerId,
                kind,
                evaluation.currencyCost(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), 0,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                EconomyTransactionReceipt.CompensationDecision.NONE);
        List<EconomyAlert> alerts = EconomyMonitoringService.evaluate(
                state, transactionId, economyReceipt, EconomyConfig.alertThresholds());
        CareerProgress afterProgress = after.progress(evaluation.careerId());
        SkillMutationReceipt receipt = new SkillMutationReceipt(
                timestampEpochMillis,
                transactionId,
                playerId,
                evaluation.careerId(),
                requestedSkill,
                operation,
                rankBefore,
                rankAfter,
                beforeProgress.spentSkillPoints(),
                afterProgress.spentSkillPoints(),
                evaluation.currencyCost());
        Identifier action = operation == SkillMutationReceipt.Operation.UNLOCK ? UNLOCK : RESET;
        state.commitSkillMutation(
                playerId,
                afterBalance,
                evaluation.currencyCost() > 0,
                after,
                transactionId,
                timestampEpochMillis,
                economyReceipt,
                receipt,
                alerts,
                new AuditEntry(
                        timestampEpochMillis,
                        playerId,
                        action,
                        operation == SkillMutationReceipt.Operation.UNLOCK
                                ? skillId.toString()
                                : evaluation.careerId().toString(),
                        Optional.empty(),
                        Optional.empty(),
                        auditState(evaluation.careerId(), beforeProgress, evaluation.balance()),
                        auditState(evaluation.careerId(), afterProgress, afterBalance),
                        operation.getSerializedName(),
                        transactionId));
        EconomyMonitoringService.publish(alerts);
        return result(Status.SUCCESS, Optional.of(evaluation), transactionId, true);
    }

    private static Evaluation evaluate(
            PlatformSavedData state,
            CareerCatalog catalog,
            UUID playerId,
            Identifier careerId,
            Identifier skillId,
            SkillMutationReceipt.Operation operation) {
        return operation == SkillMutationReceipt.Operation.UNLOCK
                ? evaluateUnlock(state, catalog, playerId, skillId)
                : evaluateReset(state, catalog, playerId, careerId);
    }

    private static MutationResult denied(
            PlatformSavedData state,
            UUID playerId,
            Identifier careerId,
            Identifier skillId,
            Status status,
            Evaluation evaluation,
            long timestampEpochMillis,
            UUID transactionId) {
        if (!state.isWritable()) {
            return result(status, Optional.ofNullable(evaluation), transactionId, false);
        }
        UUID auditId = validTransactionId(transactionId) ? transactionId : UUID.randomUUID();
        String target = skillId == null
                ? String.valueOf(careerId)
                : skillId.toString();
        boolean audited = state.appendDeniedAudit(new AuditEntry(
                timestampEpochMillis,
                playerId,
                DENIED,
                target,
                Optional.empty(),
                Optional.empty(),
                "unchanged",
                "unchanged",
                status.id(),
                auditId), DENIED_AUDIT_INTERVAL_MILLIS);
        return result(status, Optional.ofNullable(evaluation), transactionId, audited);
    }

    private static boolean isActiveLineage(
            CareerCatalog catalog,
            PlayerCareerState careers,
            Identifier careerId) {
        return careers.activeCareer().filter(active ->
                active.equals(careerId) || catalog.ancestors(active).contains(careerId)).isPresent();
    }

    private static String auditState(Identifier careerId, CareerProgress progress, long balance) {
        return "career=" + careerId
                + ";skills=" + progress.skillRanks().size()
                + ";spent=" + progress.spentSkillPoints()
                + ";balance=" + balance;
    }

    private static boolean validTransactionId(UUID transactionId) {
        return transactionId != null && !ZERO_UUID.equals(transactionId);
    }

    private static Identifier action(String path) {
        return Identifier.fromNamespaceAndPath(Rovenfall.MOD_ID, path);
    }

    private static Evaluation evaluation(
            Status status,
            SkillMutationReceipt.Operation operation,
            Identifier careerId,
            Identifier skillId,
            Optional<CareerCatalog.SkillBinding> binding,
            Optional<CareerDefinition> careerDefinition,
            List<PrerequisiteRequirement> prerequisites,
            int rank,
            int maximumRank,
            int earnedPoints,
            int availablePoints,
            int spentPoints,
            long cost,
            long balance) {
        long pointCost = operation == SkillMutationReceipt.Operation.UNLOCK ? cost : 0;
        long currencyCost = operation == SkillMutationReceipt.Operation.RESET ? cost : 0;
        return new Evaluation(
                status, operation, careerId, Optional.ofNullable(skillId), binding, careerDefinition,
                prerequisites, rank, maximumRank, earnedPoints, availablePoints, spentPoints,
                pointCost, currencyCost, balance);
    }

    private static MutationResult result(
            Status status,
            Optional<Evaluation> evaluation,
            UUID transactionId,
            boolean auditRecorded) {
        return new MutationResult(status, evaluation, transactionId, auditRecorded);
    }

    public enum Status {
        SUCCESS,
        DUPLICATE_TRANSACTION,
        TRANSACTION_ID_CONFLICT,
        INVALID_REQUEST,
        INVALID_TRANSACTION,
        READ_ONLY_SCHEMA,
        SKILL_NOT_FOUND,
        CAREER_NOT_FOUND,
        CAREER_NOT_LEARNED,
        CAREER_NOT_ACTIVE_LINEAGE,
        PREREQUISITE_NOT_MET,
        MAX_RANK_REACHED,
        INSUFFICIENT_SKILL_POINTS,
        NO_SKILLS_UNLOCKED,
        ACCOUNT_NOT_FOUND,
        INSUFFICIENT_FUNDS,
        SKILL_CAP_REACHED,
        TRANSACTION_LEDGER_FULL;

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        public String evaluationTranslationKey() {
            return "career_skill_evaluation.rovenfall." + id();
        }
    }

    public record PrerequisiteRequirement(Identifier skillId, int currentRank) {
        public boolean met() {
            return currentRank > 0;
        }
    }

    public record Evaluation(
            Status status,
            SkillMutationReceipt.Operation operation,
            Identifier careerId,
            Optional<Identifier> skillId,
            Optional<CareerCatalog.SkillBinding> binding,
            Optional<CareerDefinition> careerDefinition,
            List<PrerequisiteRequirement> prerequisites,
            int rank,
            int maximumRank,
            int earnedPoints,
            int availablePoints,
            int spentPoints,
            long pointCost,
            long currencyCost,
            long balance) {
        public Evaluation {
            skillId = skillId == null ? Optional.empty() : skillId;
            binding = binding == null ? Optional.empty() : binding;
            careerDefinition = careerDefinition == null ? Optional.empty() : careerDefinition;
            prerequisites = prerequisites == null ? List.of() : List.copyOf(prerequisites);
        }

        public boolean allowed() {
            return status == Status.SUCCESS;
        }

        int pointOrCurrencyCostAsInt() {
            return Math.toIntExact(pointCost);
        }
    }

    public record MutationResult(
            Status status,
            Optional<Evaluation> evaluation,
            UUID transactionId,
            boolean auditRecorded) {
        public MutationResult {
            evaluation = evaluation == null ? Optional.empty() : evaluation;
        }
    }
}
