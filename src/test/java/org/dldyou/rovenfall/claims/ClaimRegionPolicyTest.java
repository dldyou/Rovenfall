package org.dldyou.rovenfall.claims;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

final class ClaimRegionPolicyTest {
    @Test
    void protectsConfiguredSpawnRadiusAndRejectsNonHubDimensions() {
        BlockPos spawn = new BlockPos(8, 70, 8);

        assertTrue(ClaimRegionPolicy.isProtectedHubRegion(
                new ClaimKey(Level.OVERWORLD, 0, 0), Level.OVERWORLD, spawn, 2));
        assertTrue(ClaimRegionPolicy.isProtectedHubRegion(
                new ClaimKey(Level.OVERWORLD, -2, 2), Level.OVERWORLD, spawn, 2));
        assertFalse(ClaimRegionPolicy.isProtectedHubRegion(
                new ClaimKey(Level.OVERWORLD, 3, 0), Level.OVERWORLD, spawn, 2));
        assertTrue(ClaimRegionPolicy.isProtectedHubRegion(
                new ClaimKey(Level.NETHER, 3, 0), Level.OVERWORLD, spawn, 2));
    }

    @Test
    void failsClosedForInvalidPolicyInputs() {
        ClaimKey key = new ClaimKey(Level.OVERWORLD, 10, 10);

        assertTrue(ClaimRegionPolicy.isProtectedHubRegion(null, Level.OVERWORLD, BlockPos.ZERO, 2));
        assertTrue(ClaimRegionPolicy.isProtectedHubRegion(key, null, BlockPos.ZERO, 2));
        assertTrue(ClaimRegionPolicy.isProtectedHubRegion(key, Level.OVERWORLD, null, 2));
        assertTrue(ClaimRegionPolicy.isProtectedHubRegion(key, Level.OVERWORLD, BlockPos.ZERO, -1));
        assertTrue(ClaimRegionPolicy.isProtectedHubRegion(key, Level.OVERWORLD, BlockPos.ZERO, 65));
    }
}
