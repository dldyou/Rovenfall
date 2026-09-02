package org.dldyou.rovenfall.activities;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.Identifier;

public record ActivityRewardDefinition(
        ActivityTrack track,
        ActivityKind kind,
        Identifier targetId,
        long experience,
        long windowMillis,
        long targetWindowCap,
        long playerWindowCap) {
    public static final long MAX_REWARD_EXPERIENCE = 1_000_000_000L;
    public static final long MAX_WINDOW_MILLIS = 7L * 24 * 60 * 60 * 1_000;
    private static final Codec<Long> EXPERIENCE_CODEC = Codec.LONG.validate(value ->
            value < 1 || value > MAX_REWARD_EXPERIENCE
                    ? DataResult.error(() -> "activity reward experience is outside the supported range")
                    : DataResult.success(value));
    private static final Codec<Long> WINDOW_CODEC = Codec.LONG.validate(value ->
            value < 1_000 || value > MAX_WINDOW_MILLIS
                    ? DataResult.error(() -> "activity reward window is outside the supported range")
                    : DataResult.success(value));
    public static final Codec<ActivityRewardDefinition> CODEC =
            RecordCodecBuilder.<ActivityRewardDefinition>create(instance -> instance.group(
                    ActivityTrack.CODEC.fieldOf("track").forGetter(ActivityRewardDefinition::track),
                    ActivityKind.CODEC.fieldOf("kind").forGetter(ActivityRewardDefinition::kind),
                    Identifier.CODEC.fieldOf("target").forGetter(ActivityRewardDefinition::targetId),
                    EXPERIENCE_CODEC.fieldOf("experience").forGetter(ActivityRewardDefinition::experience),
                    WINDOW_CODEC.fieldOf("window_millis").forGetter(ActivityRewardDefinition::windowMillis),
                    EXPERIENCE_CODEC.fieldOf("target_window_cap").forGetter(ActivityRewardDefinition::targetWindowCap),
                    EXPERIENCE_CODEC.fieldOf("player_window_cap").forGetter(ActivityRewardDefinition::playerWindowCap)
            ).apply(instance, ActivityRewardDefinition::new)).validate(ActivityRewardDefinition::validate);

    public static DataResult<ActivityRewardDefinition> validate(ActivityRewardDefinition definition) {
        if (definition == null || definition.track == null || definition.kind == null || definition.targetId == null) {
            return DataResult.error(() -> "activity reward has missing identity fields");
        }
        if (definition.kind.track() != definition.track) {
            return DataResult.error(() -> "activity reward kind does not belong to its track");
        }
        if (definition.experience < 1 || definition.experience > MAX_REWARD_EXPERIENCE
                || definition.windowMillis < 1_000 || definition.windowMillis > MAX_WINDOW_MILLIS
                || definition.targetWindowCap < definition.experience
                || definition.playerWindowCap < definition.experience
                || definition.targetWindowCap > MAX_REWARD_EXPERIENCE
                || definition.playerWindowCap > MAX_REWARD_EXPERIENCE) {
            return DataResult.error(() -> "activity reward amount, window, or cap is invalid");
        }
        return DataResult.success(definition);
    }

}
