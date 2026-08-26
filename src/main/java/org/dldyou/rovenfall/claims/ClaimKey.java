package org.dldyou.rovenfall.claims;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

public record ClaimKey(ResourceKey<Level> dimension, int chunkX, int chunkZ) {
    public static final Codec<ClaimKey> CODEC = RecordCodecBuilder.<ClaimKey>create(instance -> instance.group(
            Level.RESOURCE_KEY_CODEC.fieldOf("dimension").forGetter(ClaimKey::dimension),
            Codec.INT.fieldOf("chunk_x").forGetter(ClaimKey::chunkX),
            Codec.INT.fieldOf("chunk_z").forGetter(ClaimKey::chunkZ)
    ).apply(instance, ClaimKey::new)).validate(ClaimKey::validate);

    public static ClaimKey at(ResourceKey<Level> dimension, BlockPos position) {
        if (dimension == null || position == null) {
            throw new IllegalArgumentException("Claim location is missing");
        }
        return new ClaimKey(dimension, position.getX() >> 4, position.getZ() >> 4);
    }

    public String auditTarget() {
        return dimension.identifier() + "@" + chunkX + "," + chunkZ;
    }

    private static DataResult<ClaimKey> validate(ClaimKey key) {
        return key == null || key.dimension == null
                ? DataResult.error(() -> "Claim key is missing a dimension")
                : DataResult.success(key);
    }
}
