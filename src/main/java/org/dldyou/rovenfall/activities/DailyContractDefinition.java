package org.dldyou.rovenfall.activities;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.Identifier;

public record DailyContractDefinition(
        String translationKey,
        String descriptionTranslationKey,
        ActivityKind kind,
        Identifier targetId,
        long requiredExperience,
        long currencyReward) {
    public static final int MAX_DEFINITION_ID_LENGTH = 200;
    public static final long MAX_REQUIRED_EXPERIENCE = 1_000_000L;
    public static final long MAX_CURRENCY_REWARD = 1_000_000L;
    private static final Codec<Long> REQUIRED_EXPERIENCE_CODEC = Codec.LONG.validate(value ->
            value < 1 || value > MAX_REQUIRED_EXPERIENCE
                    ? DataResult.error(() -> "daily contract required experience is invalid")
                    : DataResult.success(value));
    private static final Codec<Long> REWARD_CODEC = Codec.LONG.validate(value ->
            value < 1 || value > MAX_CURRENCY_REWARD
                    ? DataResult.error(() -> "daily contract currency reward is invalid")
                    : DataResult.success(value));
    public static final Codec<DailyContractDefinition> CODEC = RecordCodecBuilder
            .<DailyContractDefinition>create(instance -> instance.group(
                    Codec.string(1, 160).fieldOf("translation_key")
                            .forGetter(DailyContractDefinition::translationKey),
                    Codec.string(1, 160).fieldOf("description_translation_key")
                            .forGetter(DailyContractDefinition::descriptionTranslationKey),
                    ActivityKind.CODEC.fieldOf("kind").forGetter(DailyContractDefinition::kind),
                    Identifier.CODEC.fieldOf("target").forGetter(DailyContractDefinition::targetId),
                    REQUIRED_EXPERIENCE_CODEC.fieldOf("required_experience")
                            .forGetter(DailyContractDefinition::requiredExperience),
                    REWARD_CODEC.fieldOf("currency_reward")
                            .forGetter(DailyContractDefinition::currencyReward)
            ).apply(instance, DailyContractDefinition::new))
            .validate(DailyContractDefinition::validate);

    public DailyContractDefinition {
        translationKey = translationKey == null ? "" : translationKey.strip();
        descriptionTranslationKey = descriptionTranslationKey == null
                ? ""
                : descriptionTranslationKey.strip();
    }

    public static DataResult<DailyContractDefinition> validate(DailyContractDefinition definition) {
        if (definition == null
                || !validTranslationKey(definition.translationKey, "daily_contract.rovenfall.")
                || !validTranslationKey(
                        definition.descriptionTranslationKey, "daily_contract_description.rovenfall.")
                || definition.kind == null
                || definition.kind == ActivityKind.EXPLORATION_DISCOVERY
                || definition.targetId == null
                || definition.requiredExperience < 1
                || definition.requiredExperience > MAX_REQUIRED_EXPERIENCE
                || definition.currencyReward < 1
                || definition.currencyReward > MAX_CURRENCY_REWARD) {
            return DataResult.error(() -> "daily contract definition is incomplete or invalid");
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
