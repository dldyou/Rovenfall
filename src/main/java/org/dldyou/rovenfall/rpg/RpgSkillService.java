package org.dldyou.rovenfall.rpg;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.resources.Identifier;

/** Server-authoritative point spending and deterministic skill reset boundary. */
public final class RpgSkillService {
    private static final UUID ZERO_UUID = new UUID(0L, 0L);

    public enum Status {
        SUCCESS,
        INVALID_REQUEST,
        READ_ONLY,
        UNKNOWN_SKILL,
        UNKNOWN_CAREER,
        CAREER_NOT_PROMOTED,
        PREREQUISITE_NOT_MET,
        INSUFFICIENT_POINTS,
        MAX_RANK,
        DUPLICATE,
        NOTHING_TO_RESET,
        STATE_CONFLICT,
        OVERFLOW,
        STATE_FULL
    }

    public record Result(
            Status status,
            Identifier target,
            Optional<Identifier> blocker,
            int requiredRank,
            int actualRank,
            int skillRank,
            int remainingPoints,
            boolean committed) {
        public Result {
            blocker = blocker == null ? Optional.empty() : blocker;
        }
    }

    public record ResetPreparation(Status status, Optional<SkillResetPlan> plan) {
        public ResetPreparation {
            plan = plan == null ? Optional.empty() : plan;
        }
    }

    private RpgSkillService() {
    }

    public static Result learn(
            RpgPlayerSavedData state,
            RpgDefinitionSnapshot definitions,
            UUID playerId,
            Identifier skillId,
            long timestamp,
            UUID transactionId,
            String source) {
        if (!validRequest(state, definitions, playerId, skillId, timestamp, transactionId, source)) {
            return result(Status.INVALID_REQUEST, skillId);
        }
        if (!state.isWritable()) {
            return result(Status.READ_ONLY, skillId);
        }
        SkillDefinition definition = definitions.skill(skillId).orElse(null);
        if (definition == null) {
            return result(Status.UNKNOWN_SKILL, skillId);
        }
        RpgPlayerState current = state.state(playerId);
        if (hasTransaction(current, transactionId)) {
            return result(Status.DUPLICATE, skillId);
        }
        RpgPlayerState.CareerProgress progress = current.careers().get(definition.career());
        if (progress == null) {
            return result(Status.CAREER_NOT_PROMOTED, skillId);
        }
        int currentRank = progress.learnedSkills().getOrDefault(skillId, 0);
        if (currentRank >= definition.maxRank()) {
            return new Result(Status.MAX_RANK, skillId, Optional.empty(), 0, 0,
                    currentRank, progress.skillPoints(), false);
        }
        for (SkillDefinition.Prerequisite prerequisite : definition.prerequisites()) {
            SkillDefinition requiredDefinition = definitions.skill(prerequisite.skill()).orElse(null);
            if (requiredDefinition == null) {
                return blocked(Status.UNKNOWN_SKILL, skillId, prerequisite.skill(),
                        prerequisite.rank(), 0, currentRank, progress.skillPoints());
            }
            RpgPlayerState.CareerProgress requiredCareer = current.careers().get(requiredDefinition.career());
            int actualRank = requiredCareer == null
                    ? 0
                    : requiredCareer.learnedSkills().getOrDefault(prerequisite.skill(), 0);
            if (actualRank < prerequisite.rank()) {
                return blocked(Status.PREREQUISITE_NOT_MET, skillId, prerequisite.skill(),
                        prerequisite.rank(), actualRank, currentRank, progress.skillPoints());
            }
        }
        if (progress.skillPoints() < definition.pointCost()) {
            return new Result(Status.INSUFFICIENT_POINTS, skillId, Optional.empty(), 0, 0,
                    currentRank, progress.skillPoints(), false);
        }

        Map<Identifier, Integer> learned = new HashMap<>(progress.learnedSkills());
        learned.put(skillId, currentRank + 1);
        Map<Identifier, RpgPlayerState.CareerProgress> careers = new HashMap<>(current.careers());
        careers.put(definition.career(), new RpgPlayerState.CareerProgress(
                progress.experience(), progress.rank(), progress.skillPoints() - definition.pointCost(), learned));
        List<RpgPlayerState.ProgressionProvenance> careerEvidence = CareerProgressionService.appendCareerEvidence(
                current, new RpgPlayerState.ProgressionProvenance(
                        RpgPlayerState.ProgressionProvenance.Kind.SKILL_UNLOCK,
                        skillId, definition.pointCost(), timestamp, transactionId, source));
        RpgPlayerState candidate = new RpgPlayerState(
                current.activityXp(), careers, current.activeCareer(), current.activeSkillSlots(), current.cooldowns(),
                current.explorationDiscoveries(), CareerProgressionService.activityEvidence(current), careerEvidence);
        boolean committed = state.commit(playerId, candidate);
        return new Result(committed ? Status.SUCCESS : Status.STATE_FULL, skillId, Optional.empty(), 0, 0,
                committed ? currentRank + 1 : currentRank,
                committed ? progress.skillPoints() - definition.pointCost() : progress.skillPoints(), committed);
    }

    public static ResetPreparation prepareReset(
            RpgPlayerSavedData state,
            RpgDefinitionSnapshot definitions,
            UUID playerId,
            SkillResetPlan.Mode mode,
            Identifier target) {
        if (state == null || definitions == null || playerId == null || ZERO_UUID.equals(playerId)
                || mode == null || target == null) {
            return preparation(Status.INVALID_REQUEST);
        }
        if (!state.isWritable()) {
            return preparation(Status.READ_ONLY);
        }
        RpgPlayerState current = state.state(playerId);
        Map<Identifier, LearnedSkill> learned = learnedSkills(current, definitions);
        if (learned == null) {
            return preparation(Status.STATE_CONFLICT);
        }
        Set<Identifier> removed = new HashSet<>();
        if (mode == SkillResetPlan.Mode.FULL) {
            if (definitions.career(target).isEmpty()) {
                return preparation(Status.UNKNOWN_CAREER);
            }
            learned.forEach((skillId, value) -> {
                if (value.definition().career().equals(target)) {
                    removed.add(skillId);
                }
            });
        } else {
            if (definitions.skill(target).isEmpty()) {
                return preparation(Status.UNKNOWN_SKILL);
            }
            if (!learned.containsKey(target)) {
                return preparation(Status.NOTHING_TO_RESET);
            }
            removed.add(target);
        }
        if (removed.isEmpty()) {
            return preparation(Status.NOTHING_TO_RESET);
        }

        boolean changed;
        do {
            changed = false;
            for (Map.Entry<Identifier, LearnedSkill> entry : learned.entrySet()) {
                if (!removed.contains(entry.getKey()) && entry.getValue().definition().prerequisites().stream()
                        .anyMatch(prerequisite -> removed.contains(prerequisite.skill()))) {
                    changed |= removed.add(entry.getKey());
                }
            }
        } while (changed);

        List<SkillResetPlan.RemovedSkill> plan = new ArrayList<>();
        for (Identifier skillId : removed.stream().sorted().toList()) {
            LearnedSkill value = learned.get(skillId);
            final int refund;
            try {
                refund = Math.multiplyExact(value.rank(), value.definition().pointCost());
            } catch (ArithmeticException exception) {
                return preparation(Status.OVERFLOW);
            }
            if (refund < 1 || refund > RpgPlayerState.MAX_SKILL_POINTS) {
                return preparation(Status.OVERFLOW);
            }
            plan.add(new SkillResetPlan.RemovedSkill(
                    skillId, value.definition().career(), value.rank(), refund));
        }
        SkillResetPlan resetPlan = new SkillResetPlan(mode, target, plan);
        return applyPlan(current, resetPlan).isEmpty()
                ? preparation(Status.OVERFLOW)
                : new ResetPreparation(Status.SUCCESS, Optional.of(resetPlan));
    }

    public static Result applyReset(
            RpgPlayerSavedData state,
            RpgDefinitionSnapshot definitions,
            UUID playerId,
            SkillResetPlan plan,
            long paymentCost,
            long timestamp,
            UUID transactionId) {
        if (state == null || definitions == null || playerId == null || ZERO_UUID.equals(playerId) || plan == null
                || paymentCost < 1 || paymentCost > RpgPlayerState.MAX_XP || timestamp < 0
                || transactionId == null || ZERO_UUID.equals(transactionId)) {
            return result(Status.INVALID_REQUEST, plan == null ? null : plan.target());
        }
        if (!state.isWritable()) {
            return result(Status.READ_ONLY, plan.target());
        }
        RpgPlayerState current = state.state(playerId);
        if (hasTransaction(current, transactionId)) {
            return result(Status.DUPLICATE, plan.target());
        }
        ResetPreparation authoritative = prepareReset(
                state, definitions, playerId, plan.mode(), plan.target());
        if (authoritative.status() != Status.SUCCESS
                || !authoritative.plan().orElseThrow().equals(plan)) {
            return result(Status.STATE_CONFLICT, plan.target());
        }
        Optional<RpgPlayerState> reset = applyPlan(current, plan);
        if (reset.isEmpty()) {
            return result(Status.STATE_CONFLICT, plan.target());
        }
        RpgPlayerState changed = reset.orElseThrow();
        List<RpgPlayerState.ProgressionProvenance> careerEvidence = CareerProgressionService.appendCareerEvidence(
                changed, new RpgPlayerState.ProgressionProvenance(
                        RpgPlayerState.ProgressionProvenance.Kind.SKILL_RESET,
                        plan.target(), paymentCost, timestamp, transactionId,
                        "skill_reset:" + plan.mode().getSerializedName()));
        RpgPlayerState candidate = new RpgPlayerState(
                changed.activityXp(), changed.careers(), changed.activeCareer(), changed.activeSkillSlots(),
                changed.cooldowns(), changed.explorationDiscoveries(),
                CareerProgressionService.activityEvidence(changed), careerEvidence);
        boolean committed = state.commit(playerId, candidate);
        return new Result(committed ? Status.SUCCESS : Status.STATE_FULL, plan.target(), Optional.empty(),
                0, 0, 0, 0, committed);
    }

    private static Optional<RpgPlayerState> applyPlan(RpgPlayerState current, SkillResetPlan plan) {
        Map<Identifier, RpgPlayerState.CareerProgress> careers = new HashMap<>(current.careers());
        Map<Identifier, Integer> refunds = new HashMap<>();
        for (SkillResetPlan.RemovedSkill removed : plan.removedSkills()) {
            RpgPlayerState.CareerProgress progress = careers.get(removed.career());
            if (progress == null || progress.learnedSkills().getOrDefault(removed.skill(), 0) != removed.rank()) {
                return Optional.empty();
            }
            Map<Identifier, Integer> learned = new HashMap<>(progress.learnedSkills());
            learned.remove(removed.skill());
            careers.put(removed.career(), new RpgPlayerState.CareerProgress(
                    progress.experience(), progress.rank(), progress.skillPoints(), learned));
            try {
                refunds.merge(removed.career(), removed.refundedPoints(), Math::addExact);
            } catch (ArithmeticException exception) {
                return Optional.empty();
            }
        }
        for (Map.Entry<Identifier, Integer> refund : refunds.entrySet()) {
            RpgPlayerState.CareerProgress progress = careers.get(refund.getKey());
            final int points;
            try {
                points = Math.addExact(progress.skillPoints(), refund.getValue());
            } catch (ArithmeticException exception) {
                return Optional.empty();
            }
            if (points > RpgPlayerState.MAX_SKILL_POINTS) {
                return Optional.empty();
            }
            careers.put(refund.getKey(), new RpgPlayerState.CareerProgress(
                    progress.experience(), progress.rank(), points, progress.learnedSkills()));
        }
        Set<Identifier> removedIds = plan.removedSkills().stream()
                .map(SkillResetPlan.RemovedSkill::skill).collect(java.util.stream.Collectors.toSet());
        Map<Integer, Identifier> slots = new HashMap<>(current.activeSkillSlots());
        slots.entrySet().removeIf(entry -> removedIds.contains(entry.getValue()));
        Map<Identifier, Long> cooldowns = new HashMap<>(current.cooldowns());
        cooldowns.keySet().removeAll(removedIds);
        return Optional.of(new RpgPlayerState(
                current.activityXp(), careers, current.activeCareer(), slots, cooldowns,
                current.explorationDiscoveries(), CareerProgressionService.activityEvidence(current),
                CareerProgressionService.appendCareerEvidence(current)));
    }

    private static Map<Identifier, LearnedSkill> learnedSkills(
            RpgPlayerState state, RpgDefinitionSnapshot definitions) {
        Map<Identifier, LearnedSkill> result = new HashMap<>();
        for (Map.Entry<Identifier, RpgPlayerState.CareerProgress> career : state.careers().entrySet()) {
            for (Map.Entry<Identifier, Integer> learned : career.getValue().learnedSkills().entrySet()) {
                SkillDefinition definition = definitions.skill(learned.getKey()).orElse(null);
                if (definition == null || !definition.career().equals(career.getKey())
                        || learned.getValue() > definition.maxRank()
                        || result.putIfAbsent(learned.getKey(), new LearnedSkill(
                                learned.getValue(), definition)) != null) {
                    return null;
                }
            }
        }
        return result;
    }

    private static boolean validRequest(
            RpgPlayerSavedData state,
            RpgDefinitionSnapshot definitions,
            UUID playerId,
            Identifier target,
            long timestamp,
            UUID transactionId,
            String source) {
        return state != null && definitions != null && playerId != null && !ZERO_UUID.equals(playerId)
                && target != null && timestamp >= 0 && transactionId != null && !ZERO_UUID.equals(transactionId)
                && source != null && !source.isBlank() && source.length() <= 160;
    }

    static boolean hasTransaction(RpgPlayerState state, UUID transactionId) {
        return java.util.stream.Stream.concat(state.provenance().stream(), state.careerProvenance().stream())
                .anyMatch(entry -> entry.transactionId().equals(transactionId));
    }

    private static ResetPreparation preparation(Status status) {
        return new ResetPreparation(status, Optional.empty());
    }

    private static Result blocked(
            Status status,
            Identifier target,
            Identifier blocker,
            int requiredRank,
            int actualRank,
            int skillRank,
            int remainingPoints) {
        return new Result(status, target, Optional.of(blocker), requiredRank, actualRank,
                skillRank, remainingPoints, false);
    }

    private static Result result(Status status, Identifier target) {
        return new Result(status, target, Optional.empty(), 0, 0, 0, 0, false);
    }

    private record LearnedSkill(int rank, SkillDefinition definition) {
    }
}
