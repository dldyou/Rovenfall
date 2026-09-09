package org.dldyou.rovenfall.careers;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.Identifier;
import org.dldyou.rovenfall.activities.ActivityProgress;
import org.dldyou.rovenfall.activities.ActivityTrack;

public record CareerDefinition(
        String translationKey,
        int tier,
        List<Identifier> parents,
        Map<ActivityTrack, Integer> activityLevelRequirements,
        List<ActivityTrack> experienceTracks,
        List<Long> levelThresholds,
        long promotionCost,
        Map<Identifier, CareerSkillDefinition> skills,
        int promotionSkillPoints,
        long skillResetCost) {
    public static final int MAX_TIER = 1_000;
    public static final int MAX_PARENTS = 16;
    public static final int MAX_LEVEL = 1_000;
    public static final int MAX_SKILLS = 256;
    public static final int MAX_PROMOTION_SKILL_POINTS = 1_000;
    public static final long MAX_PROMOTION_COST = 9_000_000_000_000_000L;
    private static final Codec<Long> THRESHOLD_CODEC = Codec.LONG.validate(value ->
            value < 0 || value > ActivityProgress.MAX_EXPERIENCE
                    ? DataResult.error(() -> "career level threshold is outside the supported range")
                    : DataResult.success(value));
    private static final Codec<Long> COST_CODEC = Codec.LONG.validate(value ->
            value < 0 || value > MAX_PROMOTION_COST
                    ? DataResult.error(() -> "career promotion cost is outside the supported range")
                    : DataResult.success(value));
    private static final Codec<Map<ActivityTrack, Integer>> REQUIREMENTS_CODEC = Codec.unboundedMap(
            ActivityTrack.CODEC, Codec.intRange(0, MAX_LEVEL));
    private static final Codec<Map<Identifier, CareerSkillDefinition>> SKILLS_CODEC = Codec.unboundedMap(
            Identifier.CODEC, CareerSkillDefinition.CODEC).validate(values ->
                    values.size() > MAX_SKILLS
                            ? DataResult.error(() -> "career skill count exceeds " + MAX_SKILLS)
                            : DataResult.success(values));
    public static final Codec<CareerDefinition> CODEC = RecordCodecBuilder
            .<CareerDefinition>create(instance -> instance.group(
                    Codec.string(1, 128).fieldOf("translation_key").forGetter(CareerDefinition::translationKey),
                    Codec.intRange(1, MAX_TIER).fieldOf("tier").forGetter(CareerDefinition::tier),
                    Identifier.CODEC.listOf(0, MAX_PARENTS).optionalFieldOf("parents", List.of())
                            .forGetter(CareerDefinition::parents),
                    REQUIREMENTS_CODEC.optionalFieldOf("activity_levels", Map.of())
                            .forGetter(CareerDefinition::activityLevelRequirements),
                    ActivityTrack.CODEC.listOf(1, ActivityTrack.values().length)
                            .fieldOf("experience_tracks").forGetter(CareerDefinition::experienceTracks),
                    THRESHOLD_CODEC.listOf(1, MAX_LEVEL + 1)
                            .fieldOf("level_thresholds").forGetter(CareerDefinition::levelThresholds),
                    COST_CODEC.optionalFieldOf("promotion_cost", 0L).forGetter(CareerDefinition::promotionCost),
                    SKILLS_CODEC.optionalFieldOf("skills", Map.of()).forGetter(CareerDefinition::skills),
                    Codec.intRange(0, MAX_PROMOTION_SKILL_POINTS).optionalFieldOf("promotion_skill_points", 0)
                            .forGetter(CareerDefinition::promotionSkillPoints),
                    COST_CODEC.optionalFieldOf("skill_reset_cost", 0L)
                            .forGetter(CareerDefinition::skillResetCost)
            ).apply(instance, CareerDefinition::new))
            .validate(CareerDefinition::validate);

    public CareerDefinition {
        parents = parents == null ? List.of() : List.copyOf(parents);
        activityLevelRequirements = activityLevelRequirements == null
                ? Map.of()
                : Map.copyOf(activityLevelRequirements);
        experienceTracks = experienceTracks == null ? List.of() : List.copyOf(experienceTracks);
        levelThresholds = levelThresholds == null ? List.of() : List.copyOf(levelThresholds);
        skills = skills == null ? Map.of() : Map.copyOf(skills);
    }

    public static DataResult<CareerDefinition> validate(CareerDefinition definition) {
        if (definition == null || definition.translationKey == null || definition.translationKey.isBlank()
                || definition.translationKey.chars().anyMatch(value -> value < 0x20 || value == 0x7f)
                || definition.tier < 1 || definition.tier > MAX_TIER
                || definition.parents.size() > MAX_PARENTS
                || definition.experienceTracks.isEmpty()
                || definition.levelThresholds.isEmpty()
                || definition.promotionCost < 0 || definition.promotionCost > MAX_PROMOTION_COST
                || definition.skills.size() > MAX_SKILLS
                || definition.promotionSkillPoints < 0
                || definition.promotionSkillPoints > MAX_PROMOTION_SKILL_POINTS
                || definition.skillResetCost < 0 || definition.skillResetCost > MAX_PROMOTION_COST) {
            return DataResult.error(() -> "career definition is incomplete or outside supported bounds");
        }
        if (definition.skills.values().stream().anyMatch(skill ->
                CareerSkillDefinition.validate(skill).error().isPresent())) {
            return DataResult.error(() -> "career definition contains an invalid skill");
        }
        if (new HashSet<>(definition.parents).size() != definition.parents.size()) {
            return DataResult.error(() -> "career definition contains duplicate parents");
        }
        if (new HashSet<>(definition.experienceTracks).size() != definition.experienceTracks.size()) {
            return DataResult.error(() -> "career definition contains duplicate experience tracks");
        }
        if (definition.levelThresholds.getFirst() != 0) {
            return DataResult.error(() -> "career level thresholds must begin at zero");
        }
        for (int index = 1; index < definition.levelThresholds.size(); index++) {
            if (definition.levelThresholds.get(index) <= definition.levelThresholds.get(index - 1)) {
                return DataResult.error(() -> "career level thresholds must be strictly increasing");
            }
        }
        return DataResult.success(definition);
    }

    public int level(long experience) {
        if (experience < 0 || experience > ActivityProgress.MAX_EXPERIENCE) {
            throw new IllegalArgumentException("Career experience is outside the supported range");
        }
        int level = 0;
        int low = 0;
        int high = levelThresholds.size() - 1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            if (levelThresholds.get(middle) <= experience) {
                level = middle;
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }
        return level;
    }
}
