package org.dldyou.rovenfall.activities;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Map;
import net.minecraft.resources.Identifier;

public record WeeklyExpeditionDefinition(
        String translationKey,
        String descriptionTranslationKey,
        Map<Identifier, Integer> dailyContractRequirements,
        long currencyReward) {
    public static final int MAX_DEFINITION_ID_LENGTH = 200;
    public static final int MAX_REQUIREMENTS = 64;
    public static final int MAX_REQUIRED_COMPLETIONS = 7;
    public static final long MAX_CURRENCY_REWARD = 1_000_000L;
    private static final Codec<Map<Identifier, Integer>> REQUIREMENTS_CODEC = Codec.unboundedMap(
            Identifier.CODEC, Codec.intRange(1, MAX_REQUIRED_COMPLETIONS));
    private static final Codec<Long> REWARD_CODEC = Codec.LONG.validate(value ->
            value < 1 || value > MAX_CURRENCY_REWARD
                    ? DataResult.error(() -> "weekly expedition currency reward is invalid")
                    : DataResult.success(value));
    public static final Codec<WeeklyExpeditionDefinition> CODEC = RecordCodecBuilder
            .<WeeklyExpeditionDefinition>create(instance -> instance.group(
                    Codec.string(1, 160).fieldOf("translation_key")
                            .forGetter(WeeklyExpeditionDefinition::translationKey),
                    Codec.string(1, 160).fieldOf("description_translation_key")
                            .forGetter(WeeklyExpeditionDefinition::descriptionTranslationKey),
                    REQUIREMENTS_CODEC.fieldOf("daily_contracts")
                            .forGetter(WeeklyExpeditionDefinition::dailyContractRequirements),
                    REWARD_CODEC.fieldOf("currency_reward")
                            .forGetter(WeeklyExpeditionDefinition::currencyReward)
            ).apply(instance, WeeklyExpeditionDefinition::new))
            .validate(WeeklyExpeditionDefinition::validate);

    public WeeklyExpeditionDefinition {
        translationKey = translationKey == null ? "" : translationKey.strip();
        descriptionTranslationKey = descriptionTranslationKey == null
                ? ""
                : descriptionTranslationKey.strip();
        dailyContractRequirements = dailyContractRequirements == null
                ? Map.of()
                : Map.copyOf(dailyContractRequirements);
    }

    public static DataResult<WeeklyExpeditionDefinition> validate(WeeklyExpeditionDefinition definition) {
        if (definition == null
                || !validTranslationKey(definition.translationKey, "weekly_expedition.rovenfall.")
                || !validTranslationKey(
                        definition.descriptionTranslationKey, "weekly_expedition_description.rovenfall.")
                || definition.dailyContractRequirements.isEmpty()
                || definition.dailyContractRequirements.size() > MAX_REQUIREMENTS
                || definition.dailyContractRequirements.entrySet().stream().anyMatch(entry ->
                        entry.getKey() == null
                                || entry.getKey().toString().length() > DailyContractDefinition.MAX_DEFINITION_ID_LENGTH
                                || entry.getValue() == null
                                || entry.getValue() < 1
                                || entry.getValue() > MAX_REQUIRED_COMPLETIONS)
                || definition.currencyReward < 1
                || definition.currencyReward > MAX_CURRENCY_REWARD) {
            return DataResult.error(() -> "weekly expedition definition is incomplete or invalid");
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
