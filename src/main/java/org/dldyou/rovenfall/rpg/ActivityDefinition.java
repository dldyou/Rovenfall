package org.dldyou.rovenfall.rpg;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;

public record ActivityDefinition(String translationKey, List<Long> levelXp) {
    public static final int MAX_LEVELS = 1_000;
    public static final long MAX_XP = 1_000_000_000_000_000L;
    static final Codec<Long> XP_CODEC = Codec.LONG.validate(value -> value >= 1 && value <= MAX_XP
            ? DataResult.success(value)
            : DataResult.error(() -> "XP must be between 1 and " + MAX_XP));
    public static final Codec<ActivityDefinition> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.string(1, 160).fieldOf("translation_key").forGetter(ActivityDefinition::translationKey),
            XP_CODEC.listOf(1, MAX_LEVELS).fieldOf("level_xp").forGetter(ActivityDefinition::levelXp)
    ).apply(instance, ActivityDefinition::new));

    public ActivityDefinition {
        levelXp = List.copyOf(levelXp);
    }
}
