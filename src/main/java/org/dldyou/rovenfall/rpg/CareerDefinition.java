package org.dldyou.rovenfall.rpg;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import net.minecraft.resources.Identifier;

public record CareerDefinition(
        String translationKey,
        int tier,
        List<Identifier> parents,
        List<Long> levelXp,
        long promotionCost,
        List<ActivityRequirement> requiredActivities,
        int careerXpMultiplier) {
    public static final int MAX_TIER = 1_000;
    public static final int MAX_LEVELS = 1_000;
    public static final int MAX_PARENTS = 16;
    public static final int MAX_REQUIREMENTS = 32;
    public static final int MAX_CAREER_XP_MULTIPLIER = 100;
    public static final long MAX_XP = ActivityDefinition.MAX_XP;
    public static final long MAX_PROMOTION_COST = 1_000_000_000_000L;
    private static final Codec<Long> PROMOTION_COST_CODEC = Codec.LONG.validate(value ->
            value >= 0 && value <= MAX_PROMOTION_COST
                    ? DataResult.success(value)
                    : DataResult.error(() -> "promotion cost must be between 0 and " + MAX_PROMOTION_COST));

    public static final Codec<CareerDefinition> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.string(1, 160).fieldOf("translation_key").forGetter(CareerDefinition::translationKey),
            Codec.intRange(1, MAX_TIER).fieldOf("tier").forGetter(CareerDefinition::tier),
            Identifier.CODEC.listOf(0, MAX_PARENTS).optionalFieldOf("parents", List.of()).forGetter(CareerDefinition::parents),
            ActivityDefinition.XP_CODEC.listOf(1, MAX_LEVELS).fieldOf("level_xp").forGetter(CareerDefinition::levelXp),
            PROMOTION_COST_CODEC.optionalFieldOf("promotion_cost", 0L).forGetter(CareerDefinition::promotionCost),
            ActivityRequirement.CODEC.listOf(0, MAX_REQUIREMENTS).optionalFieldOf("required_activities", List.of())
                    .forGetter(CareerDefinition::requiredActivities),
            Codec.intRange(1, MAX_CAREER_XP_MULTIPLIER).optionalFieldOf("career_xp_multiplier", 1)
                    .forGetter(CareerDefinition::careerXpMultiplier)
    ).apply(instance, CareerDefinition::new));

    public CareerDefinition(
            String translationKey,
            int tier,
            List<Identifier> parents,
            List<Long> levelXp,
            long promotionCost,
            List<ActivityRequirement> requiredActivities) {
        this(translationKey, tier, parents, levelXp, promotionCost, requiredActivities, 1);
    }

    public CareerDefinition {
        parents = List.copyOf(parents);
        levelXp = List.copyOf(levelXp);
        requiredActivities = List.copyOf(requiredActivities);
    }

    public record ActivityRequirement(Identifier activity, int level) {
        public static final Codec<ActivityRequirement> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Identifier.CODEC.fieldOf("activity").forGetter(ActivityRequirement::activity),
                Codec.intRange(1, ActivityDefinition.MAX_LEVELS).fieldOf("level").forGetter(ActivityRequirement::level)
        ).apply(instance, ActivityRequirement::new));
    }
}
