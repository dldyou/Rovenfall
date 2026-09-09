package org.dldyou.rovenfall.activities;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

public record ActivityBlockKey(ResourceKey<Level> dimension, BlockPos position) {
    public static final Codec<ActivityBlockKey> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Level.RESOURCE_KEY_CODEC.fieldOf("dimension").forGetter(ActivityBlockKey::dimension),
            BlockPos.CODEC.fieldOf("position").forGetter(ActivityBlockKey::position)
    ).apply(instance, ActivityBlockKey::new));

    public ActivityBlockKey {
        if (dimension == null || position == null) {
            throw new IllegalArgumentException("Activity block key is incomplete");
        }
        position = position.immutable();
    }
}
