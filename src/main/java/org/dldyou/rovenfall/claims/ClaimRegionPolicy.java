package org.dldyou.rovenfall.claims;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

public final class ClaimRegionPolicy {
    private ClaimRegionPolicy() {
    }

    public static boolean isProtectedHubRegion(
            ClaimKey key,
            ResourceKey<Level> hubDimension,
            BlockPos hubSpawn,
            int protectedRadiusChunks) {
        if (key == null || hubDimension == null || hubSpawn == null
                || protectedRadiusChunks < 0 || protectedRadiusChunks > 64) {
            return true;
        }
        if (!key.dimension().equals(hubDimension)) {
            return true;
        }
        int spawnChunkX = hubSpawn.getX() >> 4;
        int spawnChunkZ = hubSpawn.getZ() >> 4;
        long distanceX = Math.abs((long) key.chunkX() - spawnChunkX);
        long distanceZ = Math.abs((long) key.chunkZ() - spawnChunkZ);
        return Math.max(distanceX, distanceZ) <= protectedRadiusChunks;
    }
}
