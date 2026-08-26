package org.dldyou.rovenfall.world;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.dldyou.rovenfall.claims.ClaimKey;

public record ProtectedRegion(
        UUID administratorId,
        ResourceKey<Level> dimension,
        int minChunkX,
        int minChunkZ,
        int maxChunkX,
        int maxChunkZ) {
    public static final int MAX_SIDE_CHUNKS = 32;
    public static final int MAX_AREA_CHUNKS = 1_024;
    public static final int MAX_ABSOLUTE_CHUNK = 1_875_000;
    public static final Codec<ProtectedRegion> CODEC = RecordCodecBuilder.<ProtectedRegion>create(instance -> instance.group(
            UUIDUtil.STRING_CODEC.fieldOf("administrator").forGetter(ProtectedRegion::administratorId),
            Level.RESOURCE_KEY_CODEC.fieldOf("dimension").forGetter(ProtectedRegion::dimension),
            Codec.INT.fieldOf("min_chunk_x").forGetter(ProtectedRegion::minChunkX),
            Codec.INT.fieldOf("min_chunk_z").forGetter(ProtectedRegion::minChunkZ),
            Codec.INT.fieldOf("max_chunk_x").forGetter(ProtectedRegion::maxChunkX),
            Codec.INT.fieldOf("max_chunk_z").forGetter(ProtectedRegion::maxChunkZ)
    ).apply(instance, ProtectedRegion::new)).validate(ProtectedRegion::validate);

    public boolean contains(ClaimKey key) {
        return key != null && dimension.equals(key.dimension())
                && key.chunkX() >= minChunkX && key.chunkX() <= maxChunkX
                && key.chunkZ() >= minChunkZ && key.chunkZ() <= maxChunkZ;
    }

    public int areaChunks() {
        return (int) (((long) maxChunkX - minChunkX + 1L) * ((long) maxChunkZ - minChunkZ + 1L));
    }

    public boolean hasValidBounds() {
        long width = (long) maxChunkX - minChunkX + 1L;
        long height = (long) maxChunkZ - minChunkZ + 1L;
        return Math.abs((long) minChunkX) <= MAX_ABSOLUTE_CHUNK
                && Math.abs((long) minChunkZ) <= MAX_ABSOLUTE_CHUNK
                && Math.abs((long) maxChunkX) <= MAX_ABSOLUTE_CHUNK
                && Math.abs((long) maxChunkZ) <= MAX_ABSOLUTE_CHUNK
                && width >= 1 && height >= 1 && width <= MAX_SIDE_CHUNKS && height <= MAX_SIDE_CHUNKS
                && width * height <= MAX_AREA_CHUNKS;
    }

    public boolean isValid() {
        return administratorId != null && dimension != null && hasValidBounds();
    }

    public String auditSummary() {
        return "administrator=" + administratorId + ";dimension=" + dimension.identifier()
                + ";chunks=" + minChunkX + "," + minChunkZ + ".." + maxChunkX + "," + maxChunkZ;
    }

    private static DataResult<ProtectedRegion> validate(ProtectedRegion region) {
        if (region == null || region.administratorId == null || region.dimension == null) {
            return DataResult.error(() -> "Protected region identity is missing");
        }
        if (!region.isValid()) {
            return DataResult.error(() -> "Protected region bounds exceed the configured limits");
        }
        return DataResult.success(region);
    }
}
