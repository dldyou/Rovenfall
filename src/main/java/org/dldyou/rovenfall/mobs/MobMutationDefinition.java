package org.dldyou.rovenfall.mobs;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;

public record MobMutationDefinition(
        List<Identifier> eligibleEntityTypes,
        double spawnChance,
        String translationKey,
        boolean glowing,
        List<AttributeChange> attributes,
        AiModifier aiModifier,
        long currencyReward) {
    public static final int MAX_ELIGIBLE_TYPES = 64;
    public static final int MAX_ATTRIBUTE_CHANGES = 8;
    public static final double MAX_SPAWN_CHANCE = 0.25;
    public static final long MAX_CURRENCY_REWARD = 1_000_000L;

    private static final Codec<Double> CHANCE_CODEC = Codec.DOUBLE.validate(value ->
            !Double.isFinite(value) || value <= 0 || value > MAX_SPAWN_CHANCE
                    ? DataResult.error(() -> "mob mutation spawn chance is invalid")
                    : DataResult.success(value));
    private static final Codec<Long> REWARD_CODEC = Codec.LONG.validate(value ->
            value < 1 || value > MAX_CURRENCY_REWARD
                    ? DataResult.error(() -> "mob mutation currency reward is invalid")
                    : DataResult.success(value));
    public static final Codec<MobMutationDefinition> CODEC =
            RecordCodecBuilder.<MobMutationDefinition>create(instance -> instance.group(
                    Identifier.CODEC.listOf(1, MAX_ELIGIBLE_TYPES)
                            .fieldOf("eligible_entity_types")
                            .forGetter(MobMutationDefinition::eligibleEntityTypes),
                    CHANCE_CODEC.fieldOf("spawn_chance").forGetter(MobMutationDefinition::spawnChance),
                    Codec.STRING.fieldOf("translation_key").forGetter(MobMutationDefinition::translationKey),
                    Codec.BOOL.fieldOf("glowing").forGetter(MobMutationDefinition::glowing),
                    AttributeChange.CODEC.listOf(1, MAX_ATTRIBUTE_CHANGES)
                            .fieldOf("attributes")
                            .forGetter(MobMutationDefinition::attributes),
                    AiModifier.CODEC.fieldOf("ai_modifier").forGetter(MobMutationDefinition::aiModifier),
                    REWARD_CODEC.fieldOf("currency_reward").forGetter(MobMutationDefinition::currencyReward)
            ).apply(instance, MobMutationDefinition::new)).validate(MobMutationDefinition::validate);

    public MobMutationDefinition {
        eligibleEntityTypes = eligibleEntityTypes == null ? List.of() : List.copyOf(eligibleEntityTypes);
        attributes = attributes == null ? List.of() : List.copyOf(attributes);
        translationKey = translationKey == null ? "" : translationKey.strip();
    }

    public static DataResult<MobMutationDefinition> validate(MobMutationDefinition definition) {
        if (definition == null || definition.aiModifier == null
                || definition.eligibleEntityTypes.isEmpty() || definition.attributes.isEmpty()
                || !definition.glowing || definition.translationKey.isEmpty()
                || definition.translationKey.length() > 160
                || !definition.translationKey.matches("[a-z0-9_.-]+")) {
            return DataResult.error(() -> "mob mutation identity, visible marker, or effects are invalid");
        }
        if (new HashSet<>(definition.eligibleEntityTypes).size() != definition.eligibleEntityTypes.size()) {
            return DataResult.error(() -> "mob mutation repeats an eligible entity type");
        }
        if (definition.attributes.stream().map(AttributeChange::attributeId).distinct().count()
                != definition.attributes.size()) {
            return DataResult.error(() -> "mob mutation repeats an attribute");
        }
        return DataResult.success(definition);
    }

    public record AttributeChange(
            Identifier attributeId,
            double amount,
            AttributeModifier.Operation operation) {
        private static final Codec<Double> AMOUNT_CODEC = Codec.DOUBLE.validate(value ->
                !Double.isFinite(value) || value < -0.95 || value > 1_024
                        ? DataResult.error(() -> "mob mutation attribute amount is invalid")
                        : DataResult.success(value));
        public static final Codec<AttributeChange> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Identifier.CODEC.fieldOf("attribute").forGetter(AttributeChange::attributeId),
                AMOUNT_CODEC.fieldOf("amount").forGetter(AttributeChange::amount),
                AttributeModifier.Operation.CODEC.fieldOf("operation").forGetter(AttributeChange::operation)
        ).apply(instance, AttributeChange::new));
    }

    public enum AiModifier {
        LEAP,
        RELENTLESS;

        public static final Codec<AiModifier> CODEC = Codec.STRING.comapFlatMap(value -> {
            try {
                return DataResult.success(valueOf(value.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException exception) {
                return DataResult.error(() -> "unknown mob mutation AI modifier " + value);
            }
        }, value -> value.name().toLowerCase(Locale.ROOT));
    }
}
