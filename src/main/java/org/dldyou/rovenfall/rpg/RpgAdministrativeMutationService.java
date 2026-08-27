package org.dldyou.rovenfall.rpg;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.Identifier;

/** Idempotent RPG-root mutations used only by the role-checked administration coordinator. */
public final class RpgAdministrativeMutationService {
    private static final UUID ZERO_UUID = new UUID(0L, 0L);

    public enum Status {
        SUCCESS,
        INVALID_REQUEST,
        READ_ONLY,
        DUPLICATE,
        UNKNOWN_ACTIVITY,
        UNKNOWN_CAREER,
        ALREADY_PROMOTED,
        MISSING_PARENT,
        PARENT_RANK_TOO_LOW,
        NOTHING_TO_RESET,
        STATE_CONFLICT,
        OVERFLOW,
        STATE_FULL
    }

    public record Result(Status status, long beforeAmount, long afterAmount, boolean committed) {
    }

    private RpgAdministrativeMutationService() {
    }

    public static Result adjustActivityXp(
            RpgPlayerSavedData state,
            RpgDefinitionSnapshot definitions,
            UUID playerId,
            Identifier activityId,
            long delta,
            long expectedBefore,
            long timestamp,
            UUID transactionId,
            String source) {
        if (!validRequest(state, definitions, playerId, activityId, timestamp, transactionId, source)
                || delta == 0 || expectedBefore < 0 || expectedBefore > RpgPlayerState.MAX_XP) {
            return result(Status.INVALID_REQUEST, expectedBefore, expectedBefore);
        }
        if (definitions.activity(activityId).isEmpty()) {
            return result(Status.UNKNOWN_ACTIVITY, expectedBefore, expectedBefore);
        }
        if (!state.isWritable()) {
            return result(Status.READ_ONLY, expectedBefore, expectedBefore);
        }
        RpgPlayerState current = state.state(playerId);
        long actualBefore = current.activityXp().getOrDefault(activityId, 0L);
        if (RpgSkillService.hasTransaction(current, transactionId)) {
            return result(Status.DUPLICATE, actualBefore, actualBefore);
        }
        if (actualBefore != expectedBefore) {
            return result(Status.STATE_CONFLICT, actualBefore, actualBefore);
        }
        final long after;
        final long evidenceAmount;
        try {
            after = Math.addExact(actualBefore, delta);
            evidenceAmount = Math.absExact(delta);
        } catch (ArithmeticException exception) {
            return result(Status.OVERFLOW, actualBefore, actualBefore);
        }
        if (after < 0 || after > RpgPlayerState.MAX_XP) {
            return result(Status.OVERFLOW, actualBefore, actualBefore);
        }

        Map<Identifier, Long> activityXp = new HashMap<>(current.activityXp());
        if (after == 0) {
            activityXp.remove(activityId);
        } else {
            activityXp.put(activityId, after);
        }
        var evidence = new RpgPlayerState.ProgressionProvenance(
                RpgPlayerState.ProgressionProvenance.Kind.ADMIN_ACTIVITY_XP,
                activityId,
                evidenceAmount,
                timestamp,
                transactionId,
                source);
        List<RpgPlayerState.ProgressionProvenance> provenance = CareerProgressionService.appendEvidence(
                CareerProgressionService.activityEvidence(current), evidence);
        RpgPlayerState candidate = new RpgPlayerState(
                activityXp,
                current.careers(),
                current.activeCareer(),
                current.activeSkillSlots(),
                current.cooldowns(),
                current.explorationDiscoveries(),
                provenance,
                CareerProgressionService.appendCareerEvidence(current),
                current.lastActiveSkillRequestId());
        boolean committed = state.commit(playerId, candidate);
        return new Result(committed ? Status.SUCCESS : Status.STATE_FULL,
                actualBefore, committed ? after : actualBefore, committed);
    }

    public static Result recoverPromotion(
            RpgPlayerSavedData state,
            RpgDefinitionSnapshot definitions,
            UUID playerId,
            Identifier careerId,
            long timestamp,
            UUID transactionId,
            String source) {
        if (!validRequest(state, definitions, playerId, careerId, timestamp, transactionId, source)) {
            return result(Status.INVALID_REQUEST, 0, 0);
        }
        CareerDefinition definition = definitions.career(careerId).orElse(null);
        if (definition == null) {
            return result(Status.UNKNOWN_CAREER, 0, 0);
        }
        if (!state.isWritable()) {
            return result(Status.READ_ONLY, 0, 0);
        }
        RpgPlayerState current = state.state(playerId);
        if (RpgSkillService.hasTransaction(current, transactionId)) {
            return result(Status.DUPLICATE, 0, 0);
        }
        Status validation = validatePromotionRecovery(definitions, current, careerId);
        if (validation != Status.SUCCESS) {
            return result(validation, 0, 0);
        }

        Map<Identifier, RpgPlayerState.CareerProgress> careers = new HashMap<>(current.careers());
        careers.put(careerId, new RpgPlayerState.CareerProgress(0, 0, 0, Map.of()));
        List<RpgPlayerState.ProgressionProvenance> careerEvidence = CareerProgressionService.appendCareerEvidence(
                current,
                new RpgPlayerState.ProgressionProvenance(
                        RpgPlayerState.ProgressionProvenance.Kind.ADMIN_PROMOTION,
                        careerId,
                        definition.tier(),
                        timestamp,
                        transactionId,
                        source,
                        current.activeCareer()));
        RpgPlayerState candidate = new RpgPlayerState(
                current.activityXp(),
                careers,
                Optional.of(careerId),
                current.activeSkillSlots(),
                current.cooldowns(),
                current.explorationDiscoveries(),
                CareerProgressionService.activityEvidence(current),
                careerEvidence,
                current.lastActiveSkillRequestId());
        boolean committed = state.commit(playerId, candidate);
        if (committed) {
            RpgActiveSkillRuntime.clear(playerId);
        }
        return new Result(committed ? Status.SUCCESS : Status.STATE_FULL, 0, 0, committed);
    }

    public static Status validatePromotionRecovery(
            RpgDefinitionSnapshot definitions,
            RpgPlayerState current,
            Identifier careerId) {
        if (definitions == null || current == null || careerId == null) {
            return Status.INVALID_REQUEST;
        }
        CareerDefinition definition = definitions.career(careerId).orElse(null);
        if (definition == null) {
            return Status.UNKNOWN_CAREER;
        }
        if (current.careers().containsKey(careerId)) {
            return Status.ALREADY_PROMOTED;
        }
        if (current.careers().size() >= RpgPlayerState.MAX_CAREERS) {
            return Status.STATE_FULL;
        }
        for (Identifier parentId : definition.parents()) {
            RpgPlayerState.CareerProgress progress = current.careers().get(parentId);
            CareerDefinition parent = definitions.career(parentId).orElse(null);
            if (progress == null || parent == null) {
                return Status.MISSING_PARENT;
            }
            int actualRank = Math.min(progress.rank(),
                    CareerProgressionService.levelForXp(progress.experience(), parent.levelXp()));
            if (actualRank < parent.levelXp().size()) {
                return Status.PARENT_RANK_TOO_LOW;
            }
        }
        return Status.SUCCESS;
    }

    public static Result applySkillReset(
            RpgPlayerSavedData state,
            RpgDefinitionSnapshot definitions,
            UUID playerId,
            SkillResetPlan plan,
            long timestamp,
            UUID transactionId,
            String source) {
        Identifier target = plan == null ? null : plan.target();
        if (!validRequest(state, definitions, playerId, target, timestamp, transactionId, source)
                || plan == null) {
            return result(Status.INVALID_REQUEST, 0, 0);
        }
        if (!state.isWritable()) {
            return result(Status.READ_ONLY, 0, 0);
        }
        RpgPlayerState current = state.state(playerId);
        if (RpgSkillService.hasTransaction(current, transactionId)) {
            return result(Status.DUPLICATE, 0, 0);
        }
        RpgSkillService.ResetPreparation authoritative = RpgSkillService.prepareReset(
                state, definitions, playerId, plan.mode(), plan.target());
        if (authoritative.status() == RpgSkillService.Status.NOTHING_TO_RESET) {
            return result(Status.NOTHING_TO_RESET, 0, 0);
        }
        if (authoritative.status() != RpgSkillService.Status.SUCCESS
                || !authoritative.plan().orElseThrow().equals(plan)) {
            return result(Status.STATE_CONFLICT, 0, 0);
        }
        Optional<RpgPlayerState> reset = RpgSkillService.applyPlan(current, plan);
        if (reset.isEmpty()) {
            return result(Status.STATE_CONFLICT, 0, 0);
        }
        final long refunded;
        try {
            refunded = plan.removedSkills().stream().mapToLong(SkillResetPlan.RemovedSkill::refundedPoints)
                    .reduce(0L, Math::addExact);
        } catch (ArithmeticException exception) {
            return result(Status.OVERFLOW, 0, 0);
        }
        RpgPlayerState changed = reset.orElseThrow();
        List<RpgPlayerState.ProgressionProvenance> careerEvidence = CareerProgressionService.appendCareerEvidence(
                changed,
                new RpgPlayerState.ProgressionProvenance(
                        RpgPlayerState.ProgressionProvenance.Kind.ADMIN_SKILL_RESET,
                        plan.target(),
                        refunded,
                        timestamp,
                        transactionId,
                        source));
        RpgPlayerState candidate = new RpgPlayerState(
                changed.activityXp(),
                changed.careers(),
                changed.activeCareer(),
                changed.activeSkillSlots(),
                changed.cooldowns(),
                changed.explorationDiscoveries(),
                CareerProgressionService.activityEvidence(changed),
                careerEvidence,
                changed.lastActiveSkillRequestId());
        boolean committed = state.commit(playerId, candidate);
        if (committed) {
            RpgActiveSkillRuntime.clear(playerId);
        }
        return new Result(committed ? Status.SUCCESS : Status.STATE_FULL, refunded, refunded, committed);
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

    private static Result result(Status status, long before, long after) {
        return new Result(status, before, after, false);
    }
}
