package org.dldyou.rovenfall.careers;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.Identifier;

public record CareerActiveSkillDefinition(
        Identifier effectId,
        int durationTicks,
        int amplifier,
        int cooldownSeconds) {
    public static final int MAX_DURATION_TICKS = 12_000;
    public static final int MAX_AMPLIFIER = 4;
    public static final int MAX_COOLDOWN_SECONDS = 3_600;
    public static final Codec<CareerActiveSkillDefinition> CODEC = RecordCodecBuilder
            .<CareerActiveSkillDefinition>create(instance -> instance.group(
                    Identifier.CODEC.fieldOf("effect").forGetter(CareerActiveSkillDefinition::effectId),
                    Codec.intRange(1, MAX_DURATION_TICKS).fieldOf("duration_ticks")
                            .forGetter(CareerActiveSkillDefinition::durationTicks),
                    Codec.intRange(0, MAX_AMPLIFIER).optionalFieldOf("amplifier", 0)
                            .forGetter(CareerActiveSkillDefinition::amplifier),
                    Codec.intRange(1, MAX_COOLDOWN_SECONDS).fieldOf("cooldown_seconds")
                            .forGetter(CareerActiveSkillDefinition::cooldownSeconds)
            ).apply(instance, CareerActiveSkillDefinition::new))
            .validate(CareerActiveSkillDefinition::validate);

    public static DataResult<CareerActiveSkillDefinition> validate(CareerActiveSkillDefinition definition) {
        if (definition == null || definition.effectId == null
                || definition.durationTicks < 1 || definition.durationTicks > MAX_DURATION_TICKS
                || definition.amplifier < 0 || definition.amplifier > MAX_AMPLIFIER
                || definition.cooldownSeconds < 1
                || definition.cooldownSeconds > MAX_COOLDOWN_SECONDS) {
            return DataResult.error(() -> "career active skill definition is invalid");
        }
        return DataResult.success(definition);
    }
}
