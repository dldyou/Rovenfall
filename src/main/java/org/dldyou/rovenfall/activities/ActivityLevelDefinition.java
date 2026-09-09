package org.dldyou.rovenfall.activities;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;

public record ActivityLevelDefinition(ActivityTrack track, List<Long> thresholds) {
    public static final int MAX_LEVEL = 1_000;
    private static final Codec<Long> THRESHOLD_CODEC = Codec.LONG.validate(value ->
            value < 0 || value > ActivityProgress.MAX_EXPERIENCE
                    ? DataResult.error(() -> "activity level threshold is outside the supported range")
                    : DataResult.success(value));
    public static final Codec<ActivityLevelDefinition> CODEC = RecordCodecBuilder
            .<ActivityLevelDefinition>create(instance -> instance.group(
                    ActivityTrack.CODEC.fieldOf("track").forGetter(ActivityLevelDefinition::track),
                    THRESHOLD_CODEC.listOf(1, MAX_LEVEL + 1)
                            .fieldOf("thresholds").forGetter(ActivityLevelDefinition::thresholds)
            ).apply(instance, ActivityLevelDefinition::new))
            .validate(ActivityLevelDefinition::validate);

    public ActivityLevelDefinition {
        thresholds = thresholds == null ? List.of() : List.copyOf(thresholds);
    }

    public static DataResult<ActivityLevelDefinition> validate(ActivityLevelDefinition definition) {
        if (definition == null || definition.track == null || definition.thresholds.isEmpty()) {
            return DataResult.error(() -> "activity level definition is incomplete");
        }
        if (definition.thresholds.getFirst() != 0) {
            return DataResult.error(() -> "activity level thresholds must begin at zero");
        }
        for (int index = 1; index < definition.thresholds.size(); index++) {
            if (definition.thresholds.get(index) <= definition.thresholds.get(index - 1)) {
                return DataResult.error(() -> "activity level thresholds must be strictly increasing");
            }
        }
        return DataResult.success(definition);
    }

    public ActivityLevelProgress progress(long experience) {
        if (experience < 0 || experience > ActivityProgress.MAX_EXPERIENCE) {
            throw new IllegalArgumentException("Activity experience is outside the supported range");
        }
        int level = 0;
        int low = 0;
        int high = thresholds.size() - 1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            if (thresholds.get(middle) <= experience) {
                level = middle;
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }
        long currentThreshold = thresholds.get(level);
        boolean maximum = level == thresholds.size() - 1;
        long nextThreshold = maximum ? currentThreshold : thresholds.get(level + 1);
        return new ActivityLevelProgress(
                level,
                thresholds.size() - 1,
                experience,
                experience - currentThreshold,
                maximum ? 0 : nextThreshold - currentThreshold,
                maximum);
    }

    public record ActivityLevelProgress(
            int level,
            int maximumLevel,
            long totalExperience,
            long experienceIntoLevel,
            long experienceForNextLevel,
            boolean maximum) {
    }
}
