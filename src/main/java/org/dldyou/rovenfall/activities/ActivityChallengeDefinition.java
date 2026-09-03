package org.dldyou.rovenfall.activities;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Map;

public record ActivityChallengeDefinition(
        String translationKey,
        String descriptionTranslationKey,
        Map<ActivityTrack, Integer> activityLevelRequirements,
        long currencyReward) {
    public static final int MAX_DEFINITION_ID_LENGTH = 200;
    public static final int MAX_REQUIRED_LEVEL = 1_000;
    public static final long MAX_CURRENCY_REWARD = 1_000_000L;
    private static final Codec<Map<ActivityTrack, Integer>> REQUIREMENTS_CODEC = Codec.unboundedMap(
            ActivityTrack.CODEC, Codec.intRange(1, MAX_REQUIRED_LEVEL));
    private static final Codec<Long> REWARD_CODEC = Codec.LONG.validate(value ->
            value < 1 || value > MAX_CURRENCY_REWARD
                    ? DataResult.error(() -> "activity challenge currency reward is invalid")
                    : DataResult.success(value));
    public static final Codec<ActivityChallengeDefinition> CODEC = RecordCodecBuilder
            .<ActivityChallengeDefinition>create(instance -> instance.group(
                    Codec.string(1, 160).fieldOf("translation_key")
                            .forGetter(ActivityChallengeDefinition::translationKey),
                    Codec.string(1, 160).fieldOf("description_translation_key")
                            .forGetter(ActivityChallengeDefinition::descriptionTranslationKey),
                    REQUIREMENTS_CODEC.fieldOf("activity_levels")
                            .forGetter(ActivityChallengeDefinition::activityLevelRequirements),
                    REWARD_CODEC.fieldOf("currency_reward")
                            .forGetter(ActivityChallengeDefinition::currencyReward)
            ).apply(instance, ActivityChallengeDefinition::new))
            .validate(ActivityChallengeDefinition::validate);

    public ActivityChallengeDefinition {
        translationKey = translationKey == null ? "" : translationKey.strip();
        descriptionTranslationKey = descriptionTranslationKey == null
                ? ""
                : descriptionTranslationKey.strip();
        activityLevelRequirements = activityLevelRequirements == null
                ? Map.of()
                : Map.copyOf(activityLevelRequirements);
    }

    public static DataResult<ActivityChallengeDefinition> validate(ActivityChallengeDefinition definition) {
        if (definition == null
                || !validTranslationKey(definition.translationKey, "activity_challenge.rovenfall.")
                || !validTranslationKey(
                        definition.descriptionTranslationKey, "activity_challenge_description.rovenfall.")
                || definition.activityLevelRequirements.isEmpty()
                || definition.activityLevelRequirements.size() > ActivityTrack.values().length
                || definition.activityLevelRequirements.entrySet().stream().anyMatch(entry ->
                        entry.getKey() == null || entry.getValue() == null
                                || entry.getValue() < 1 || entry.getValue() > MAX_REQUIRED_LEVEL)
                || definition.currencyReward < 1
                || definition.currencyReward > MAX_CURRENCY_REWARD) {
            return DataResult.error(() -> "activity challenge definition is incomplete or invalid");
        }
        return DataResult.success(definition);
    }

    private static boolean validTranslationKey(String value, String prefix) {
        return value != null
                && value.startsWith(prefix)
                && value.length() <= 160
                && value.matches("[a-z0-9_.-]+");
    }
}
