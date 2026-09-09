package org.dldyou.rovenfall.activities;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

public record ActivityEvidence(
        UUID evidenceId,
        long observedAtEpochMillis,
        UUID playerId,
        ActivityTrack track,
        ActivityKind kind,
        ResourceKey<Level> dimension,
        int chunkX,
        int chunkZ,
        Identifier targetId,
        String subjectKey,
        long contribution,
        ActivityProvenance provenance,
        Identifier rewardDefinitionId,
        long requestedExperience,
        long awardedExperience,
        Optional<CareerAward> careerAward) {
    private static final Codec<Long> TIMESTAMP_CODEC = Codec.LONG.validate(value -> value < 0
            ? DataResult.error(() -> "activity evidence timestamp must be non-negative")
            : DataResult.success(value));
    private static final Codec<Long> CONTRIBUTION_CODEC = Codec.LONG.validate(value ->
            value < 1 || value > ActivityObservation.MAX_CONTRIBUTION
                    ? DataResult.error(() -> "activity contribution is outside the supported range")
                    : DataResult.success(value));
    private static final Codec<Long> EXPERIENCE_CODEC = Codec.LONG.validate(value ->
            value < 1 || value > ActivityProgress.MAX_EXPERIENCE
                    ? DataResult.error(() -> "activity experience is outside the supported range")
                    : DataResult.success(value));
    public static final Codec<ActivityEvidence> CODEC = RecordCodecBuilder.<ActivityEvidence>create(instance ->
            instance.group(
                    UUIDUtil.STRING_CODEC.fieldOf("evidence_id").forGetter(ActivityEvidence::evidenceId),
                    TIMESTAMP_CODEC.fieldOf("observed_at").forGetter(ActivityEvidence::observedAtEpochMillis),
                    UUIDUtil.STRING_CODEC.fieldOf("player_id").forGetter(ActivityEvidence::playerId),
                    ActivityTrack.CODEC.fieldOf("track").forGetter(ActivityEvidence::track),
                    ActivityKind.CODEC.fieldOf("kind").forGetter(ActivityEvidence::kind),
                    Level.RESOURCE_KEY_CODEC.fieldOf("dimension").forGetter(ActivityEvidence::dimension),
                    Codec.INT.fieldOf("chunk_x").forGetter(ActivityEvidence::chunkX),
                    Codec.INT.fieldOf("chunk_z").forGetter(ActivityEvidence::chunkZ),
                    Identifier.CODEC.fieldOf("target_id").forGetter(ActivityEvidence::targetId),
                    Codec.string(1, ActivityObservation.MAX_SUBJECT_KEY_LENGTH)
                            .fieldOf("subject_key").forGetter(ActivityEvidence::subjectKey),
                    CONTRIBUTION_CODEC.fieldOf("contribution").forGetter(ActivityEvidence::contribution),
                    ActivityProvenance.CODEC.fieldOf("provenance").forGetter(ActivityEvidence::provenance),
                    Identifier.CODEC.fieldOf("reward_definition").forGetter(ActivityEvidence::rewardDefinitionId),
                    EXPERIENCE_CODEC.fieldOf("requested_experience").forGetter(ActivityEvidence::requestedExperience),
                    EXPERIENCE_CODEC.fieldOf("awarded_experience").forGetter(ActivityEvidence::awardedExperience),
                    CareerAward.CODEC.optionalFieldOf("career_award").forGetter(ActivityEvidence::careerAward)
            ).apply(instance, ActivityEvidence::new)).validate(ActivityEvidence::validate);

    public ActivityEvidence {
        careerAward = careerAward == null ? Optional.empty() : careerAward;
    }

    public static ActivityEvidence recorded(
            ActivityObservation observation,
            Identifier rewardDefinitionId,
            long requestedExperience,
            long awardedExperience) {
        return recorded(observation, rewardDefinitionId, requestedExperience, awardedExperience, Optional.empty());
    }

    public static ActivityEvidence recorded(
            ActivityObservation observation,
            Identifier rewardDefinitionId,
            long requestedExperience,
            long awardedExperience,
            Optional<CareerAward> careerAward) {
        return new ActivityEvidence(
                observation.evidenceId(), observation.observedAtEpochMillis(), observation.playerId(),
                observation.track(), observation.kind(), observation.dimension(), observation.chunkX(),
                observation.chunkZ(), observation.targetId(), observation.subjectKey(), observation.contribution(),
                observation.provenance(), rewardDefinitionId, requestedExperience, awardedExperience, careerAward);
    }

    public boolean matches(
            ActivityObservation observation,
            Identifier requestedRewardDefinitionId) {
        return observation != null
                && evidenceId.equals(observation.evidenceId())
                && observedAtEpochMillis == observation.observedAtEpochMillis()
                && playerId.equals(observation.playerId())
                && track == observation.track()
                && kind == observation.kind()
                && dimension.equals(observation.dimension())
                && chunkX == observation.chunkX()
                && chunkZ == observation.chunkZ()
                && targetId.equals(observation.targetId())
                && subjectKey.equals(observation.subjectKey())
                && contribution == observation.contribution()
                && provenance.equals(observation.provenance())
                && rewardDefinitionId.equals(requestedRewardDefinitionId);
    }

    private static DataResult<ActivityEvidence> validate(ActivityEvidence evidence) {
        if (evidence == null) {
            return DataResult.error(() -> "activity evidence is missing");
        }
        ActivityObservation observation = new ActivityObservation(
                evidence.evidenceId, evidence.observedAtEpochMillis, evidence.playerId,
                evidence.track, evidence.kind, evidence.dimension, evidence.chunkX, evidence.chunkZ,
                evidence.targetId, evidence.subjectKey, evidence.contribution, evidence.provenance);
        var error = observation.validationError();
        if (error.isPresent()) {
            return DataResult.error(error::orElseThrow);
        }
        if (evidence.rewardDefinitionId == null || evidence.requestedExperience < 1
                || evidence.awardedExperience < 1 || evidence.awardedExperience > evidence.requestedExperience) {
            return DataResult.error(() -> "activity reward evidence is invalid");
        }
        if (evidence.careerAward.isPresent()) {
            CareerAward award = evidence.careerAward.orElseThrow();
            if (award.careerId == null || award.awardedExperience < 1
                    || award.awardedExperience > evidence.awardedExperience) {
                return DataResult.error(() -> "activity career award evidence is invalid");
            }
        }
        return DataResult.success(evidence);
    }

    public record CareerAward(Identifier careerId, long awardedExperience) {
        public static final Codec<CareerAward> CODEC = RecordCodecBuilder
                .<CareerAward>create(instance -> instance.group(
                        Identifier.CODEC.fieldOf("career").forGetter(CareerAward::careerId),
                        EXPERIENCE_CODEC.fieldOf("awarded_experience").forGetter(CareerAward::awardedExperience)
                ).apply(instance, CareerAward::new));
    }
}
