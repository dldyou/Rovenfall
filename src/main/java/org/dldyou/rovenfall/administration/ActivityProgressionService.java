package org.dldyou.rovenfall.administration;

import java.math.BigInteger;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.Identifier;
import org.dldyou.rovenfall.Rovenfall;
import org.dldyou.rovenfall.activities.ActivityEvidence;
import org.dldyou.rovenfall.activities.ActivityKind;
import org.dldyou.rovenfall.activities.ActivityObservation;
import org.dldyou.rovenfall.activities.ActivityProgress;
import org.dldyou.rovenfall.activities.ActivityRewardDefinition;
import org.dldyou.rovenfall.activities.ActivityRewardReloadListener.ResolvedReward;
import org.dldyou.rovenfall.careers.CareerCatalog;
import org.dldyou.rovenfall.careers.PlayerCareerState;

public final class ActivityProgressionService {
    private static final UUID ZERO_UUID = new UUID(0L, 0L);
    private static final long DENIED_AUDIT_INTERVAL_MILLIS = 1_000L;
    private static final Identifier EVIDENCE_DENIED =
            Identifier.fromNamespaceAndPath(Rovenfall.MOD_ID, "activity_evidence_denied");

    private ActivityProgressionService() {
    }

    public static AwardResult award(
            PlatformSavedData state,
            ActivityObservation observation,
            ResolvedReward reward) {
        return award(state, observation, reward, null);
    }

    public static AwardResult award(
            PlatformSavedData state,
            ActivityObservation observation,
            ResolvedReward reward,
            CareerCatalog careerCatalog) {
        if (state == null) {
            return result(Status.INVALID_OBSERVATION, observation, 0, 0, false);
        }
        if (!state.isWritable()) {
            return result(Status.READ_ONLY_SCHEMA, observation, 0, currentExperience(state, observation), false);
        }
        Optional<String> observationError = observation == null
                ? Optional.of("activity observation is missing")
                : observation.validationError();
        if (observationError.isPresent()) {
            return denied(state, observation, Status.INVALID_OBSERVATION, observationError.orElseThrow());
        }
        if (!rewardMatches(observation, reward)) {
            return denied(state, observation, Status.REWARD_MISMATCH, "reward_mismatch");
        }

        Optional<ActivityEvidence> retained = state.retainedActivityEvidence(
                observation.evidenceId(), observation.observedAtEpochMillis());
        if (retained.isPresent()) {
            ActivityEvidence evidence = retained.orElseThrow();
            if (evidence.matches(observation, reward.id())) {
                return result(Status.DUPLICATE_EVIDENCE, observation, 0,
                        state.activityExperience(observation.playerId(), observation.track()), false);
            }
            return denied(state, observation, Status.EVIDENCE_ID_CONFLICT, "evidence_id_conflict");
        }

        ActivityRewardDefinition definition = reward.definition();
        long requestedExperience;
        try {
            long baseExperience = Math.multiplyExact(definition.experience(), observation.contribution());
            int bonusBasisPoints = careerCatalog == null
                    ? 0
                    : careerCatalog.activityExperienceBonusBasisPoints(
                            state.playerCareerState(observation.playerId()), observation.track());
            long bonusExperience = BigInteger.valueOf(baseExperience)
                    .multiply(BigInteger.valueOf(bonusBasisPoints))
                    .divide(BigInteger.valueOf(10_000))
                    .longValueExact();
            requestedExperience = Math.addExact(baseExperience, bonusExperience);
        } catch (ArithmeticException exception) {
            return denied(state, observation, Status.EXPERIENCE_OVERFLOW, "experience_overflow");
        }
        if (requestedExperience > ActivityProgress.MAX_EXPERIENCE) {
            return denied(state, observation, Status.EXPERIENCE_OVERFLOW, "experience_overflow");
        }
        String discoveryKey = observation.kind() == ActivityKind.EXPLORATION_DISCOVERY
                ? observation.discoveryKey()
                : null;
        if (discoveryKey != null && state.hasActivityDiscovery(observation.playerId(), discoveryKey)) {
            return result(Status.ALREADY_DISCOVERED, observation, 0,
                    state.activityExperience(observation.playerId(), observation.track()), false);
        }

        long targetUsed = state.activityAwardedExperience(
                observation.playerId(), observation.track(), observation.kind(), observation.targetId(),
                observation.observedAtEpochMillis(), definition.windowMillis());
        long playerUsed = state.activityAwardedExperience(
                observation.playerId(), observation.track(), null, null,
                observation.observedAtEpochMillis(), definition.windowMillis());
        long remainingTarget = remaining(definition.targetWindowCap(), targetUsed);
        long remainingPlayer = remaining(definition.playerWindowCap(), playerUsed);
        long awardedExperience = Math.min(requestedExperience, Math.min(remainingTarget, remainingPlayer));
        if (awardedExperience < 1) {
            return denied(state, observation, Status.RATE_LIMITED, "rate_limited");
        }

        ActivityProgress current = state.activityProgress(observation.playerId());
        ActivityProgress updated;
        try {
            updated = current.award(observation.track(), awardedExperience, discoveryKey);
        } catch (ArithmeticException exception) {
            return denied(state, observation, Status.EXPERIENCE_OVERFLOW, "experience_overflow");
        } catch (IllegalStateException exception) {
            return denied(state, observation, Status.DISCOVERY_LIMIT_REACHED, "discovery_limit_reached");
        }
        if (!state.canCommitActivityProgress(observation.playerId())
                || !state.canCommitActivityEvidence(
                        observation.evidenceId(), observation.observedAtEpochMillis())) {
            return denied(state, observation, Status.EVIDENCE_LEDGER_FULL, "evidence_ledger_full");
        }

        CareerAwardPlan careerAward = careerAward(
                state, observation, awardedExperience, careerCatalog);
        ActivityEvidence evidence = ActivityEvidence.recorded(
                observation,
                reward.id(),
                requestedExperience,
                awardedExperience,
                careerAward.evidence());
        try {
            state.commitActivityAward(evidence, updated, careerAward.updatedState());
        } catch (IllegalStateException exception) {
            return denied(state, observation, Status.EVIDENCE_LEDGER_FULL, "evidence_ledger_full");
        }
        Status status = awardedExperience == requestedExperience ? Status.SUCCESS : Status.CAPPED_SUCCESS;
        return result(
                status,
                observation,
                awardedExperience,
                updated.experience(observation.track()),
                careerAward.careerId(),
                careerAward.awardedExperience(),
                careerAward.totalExperience(),
                false);
    }

    private static CareerAwardPlan careerAward(
            PlatformSavedData state,
            ActivityObservation observation,
            long awardedExperience,
            CareerCatalog catalog) {
        if (catalog == null) {
            return CareerAwardPlan.none();
        }
        PlayerCareerState current = state.playerCareerState(observation.playerId());
        Optional<Identifier> active = current.activeCareer();
        if (active.isEmpty()) {
            return CareerAwardPlan.none();
        }
        Identifier careerId = active.orElseThrow();
        var definition = catalog.definition(careerId);
        if (definition.isEmpty() || !definition.orElseThrow().experienceTracks().contains(observation.track())) {
            return CareerAwardPlan.none();
        }
        long currentExperience = current.experience(careerId);
        long careerExperience = Math.min(
                awardedExperience,
                ActivityProgress.MAX_EXPERIENCE - currentExperience);
        if (careerExperience < 1) {
            return CareerAwardPlan.none();
        }
        PlayerCareerState updated = current.awardActive(careerId, careerExperience);
        return new CareerAwardPlan(
                Optional.of(new ActivityEvidence.CareerAward(careerId, careerExperience)),
                Optional.of(updated),
                Optional.of(careerId),
                careerExperience,
                updated.experience(careerId));
    }

    private static boolean rewardMatches(ActivityObservation observation, ResolvedReward reward) {
        if (reward == null || reward.id() == null || reward.definition() == null
                || ActivityRewardDefinition.validate(reward.definition()).result().isEmpty()) {
            return false;
        }
        ActivityRewardDefinition definition = reward.definition();
        return observation.track() == definition.track()
                && observation.kind() == definition.kind()
                && observation.targetId().equals(definition.targetId());
    }

    private static long remaining(long cap, long used) {
        return cap - Math.min(cap, used);
    }

    private static AwardResult denied(
            PlatformSavedData state,
            ActivityObservation observation,
            Status status,
            String reason) {
        long total = currentExperience(state, observation);
        if (observation == null || observation.playerId() == null) {
            return result(status, observation, 0, total, false);
        }
        UUID auditId = observation.evidenceId() == null || ZERO_UUID.equals(observation.evidenceId())
                ? UUID.randomUUID()
                : observation.evidenceId();
        boolean audited = state.appendDeniedAudit(new AuditEntry(
                Math.max(0, observation.observedAtEpochMillis()),
                observation.playerId(),
                EVIDENCE_DENIED,
                auditTarget(observation),
                observation.dimension() == null
                        ? Optional.empty()
                        : Optional.of(observation.dimension().identifier()),
                Optional.empty(),
                Long.toString(total),
                Long.toString(total),
                reason,
                auditId), DENIED_AUDIT_INTERVAL_MILLIS);
        return result(status, observation, 0, total, audited);
    }

    private static String auditTarget(ActivityObservation observation) {
        String kind = observation.kind() == null ? "unknown" : observation.kind().getSerializedName();
        String target = observation.targetId() == null ? "unknown" : observation.targetId().toString();
        return observation.playerId() + ";kind=" + kind + ";target=" + target;
    }

    private static long currentExperience(PlatformSavedData state, ActivityObservation observation) {
        return observation == null || observation.playerId() == null || observation.track() == null
                ? 0
                : state.activityExperience(observation.playerId(), observation.track());
    }

    private static AwardResult result(
            Status status,
            ActivityObservation observation,
            long awardedExperience,
            long totalExperience,
            boolean auditRecorded) {
        return result(
                status,
                observation,
                awardedExperience,
                totalExperience,
                Optional.empty(),
                0,
                0,
                auditRecorded);
    }

    private static AwardResult result(
            Status status,
            ActivityObservation observation,
            long awardedExperience,
            long totalExperience,
            Optional<Identifier> careerId,
            long awardedCareerExperience,
            long totalCareerExperience,
            boolean auditRecorded) {
        return new AwardResult(
                status,
                awardedExperience,
                totalExperience,
                careerId,
                awardedCareerExperience,
                totalCareerExperience,
                observation == null ? null : observation.evidenceId(),
                auditRecorded);
    }

    public enum Status {
        SUCCESS,
        CAPPED_SUCCESS,
        DUPLICATE_EVIDENCE,
        EVIDENCE_ID_CONFLICT,
        ALREADY_DISCOVERED,
        RATE_LIMITED,
        INVALID_OBSERVATION,
        REWARD_MISMATCH,
        READ_ONLY_SCHEMA,
        EXPERIENCE_OVERFLOW,
        DISCOVERY_LIMIT_REACHED,
        EVIDENCE_LEDGER_FULL;

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public record AwardResult(
            Status status,
            long awardedExperience,
            long totalExperience,
            Optional<Identifier> careerId,
            long awardedCareerExperience,
            long totalCareerExperience,
            UUID evidenceId,
            boolean auditRecorded) {
        public AwardResult {
            careerId = careerId == null ? Optional.empty() : careerId;
        }

        public boolean awarded() {
            return status == Status.SUCCESS || status == Status.CAPPED_SUCCESS;
        }
    }

    private record CareerAwardPlan(
            Optional<ActivityEvidence.CareerAward> evidence,
            Optional<PlayerCareerState> updatedState,
            Optional<Identifier> careerId,
            long awardedExperience,
            long totalExperience) {
        private static CareerAwardPlan none() {
            return new CareerAwardPlan(Optional.empty(), Optional.empty(), Optional.empty(), 0, 0);
        }
    }
}
