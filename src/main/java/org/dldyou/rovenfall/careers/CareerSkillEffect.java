package org.dldyou.rovenfall.careers;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;
import net.minecraft.util.StringRepresentable;
import org.dldyou.rovenfall.activities.ActivityTrack;

public record CareerSkillEffect(
        Type type,
        Optional<ActivityTrack> track,
        int magnitudePerRankBasisPoints) {
    public static final int MAX_MAGNITUDE_PER_RANK_BASIS_POINTS = 5_000;
    public static final int MAX_TOTAL_ACTIVITY_BONUS_BASIS_POINTS = 10_000;
    public static final Codec<CareerSkillEffect> CODEC = RecordCodecBuilder
            .<CareerSkillEffect>create(instance -> instance.group(
                    Type.CODEC.fieldOf("type").forGetter(CareerSkillEffect::type),
                    ActivityTrack.CODEC.optionalFieldOf("track").forGetter(CareerSkillEffect::track),
                    Codec.intRange(1, MAX_MAGNITUDE_PER_RANK_BASIS_POINTS)
                            .fieldOf("magnitude_per_rank_bps")
                            .forGetter(CareerSkillEffect::magnitudePerRankBasisPoints)
            ).apply(instance, CareerSkillEffect::new))
            .validate(CareerSkillEffect::validate);

    public CareerSkillEffect {
        track = track == null ? Optional.empty() : track;
    }

    public static DataResult<CareerSkillEffect> validate(CareerSkillEffect effect) {
        if (effect == null || effect.type == null || effect.track == null
                || effect.magnitudePerRankBasisPoints < 1
                || effect.magnitudePerRankBasisPoints > MAX_MAGNITUDE_PER_RANK_BASIS_POINTS) {
            return DataResult.error(() -> "career skill effect is invalid");
        }
        return DataResult.success(effect);
    }

    public boolean appliesTo(ActivityTrack requestedTrack) {
        return type == Type.ACTIVITY_EXPERIENCE_BONUS
                && requestedTrack != null
                && (track.isEmpty() || track.equals(Optional.of(requestedTrack)));
    }

    public enum Type implements StringRepresentable {
        ACTIVITY_EXPERIENCE_BONUS("activity_experience_bonus");

        private static final Codec<Type> CODEC = StringRepresentable.fromEnum(Type::values);
        private final String id;

        Type(String id) {
            this.id = id;
        }

        @Override
        public String getSerializedName() {
            return id;
        }
    }
}
