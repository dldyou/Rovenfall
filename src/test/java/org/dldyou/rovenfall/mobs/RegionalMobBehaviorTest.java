package org.dldyou.rovenfall.mobs;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class RegionalMobBehaviorTest {
    @Test
    void riftHexRequiresAReadyNearbyLivingTarget() {
        assertTrue(RiftAcolyte.canStartHex(0, 0, true, 64));
        assertFalse(RiftAcolyte.canStartHex(1, 0, true, 16));
        assertFalse(RiftAcolyte.canStartHex(0, 1, true, 16));
        assertFalse(RiftAcolyte.canStartHex(0, 0, false, 16));
        assertFalse(RiftAcolyte.canStartHex(0, 0, true, 65));
    }

    @Test
    void thornbackPounceUsesAReadableMiddleRange() {
        assertTrue(ThornbackStalker.canPounce(0, true, 9));
        assertTrue(ThornbackStalker.canPounce(0, true, 64));
        assertFalse(ThornbackStalker.canPounce(1, true, 16));
        assertFalse(ThornbackStalker.canPounce(0, false, 16));
        assertFalse(ThornbackStalker.canPounce(0, true, 8));
    }

    @Test
    void graveboundGuardTriggersOnlyOnceWoundedAndEngaged() {
        assertTrue(GraveboundKnight.shouldGuard(0, 20, 40, true));
        assertFalse(GraveboundKnight.shouldGuard(1, 20, 40, true));
        assertFalse(GraveboundKnight.shouldGuard(0, 21, 40, true));
        assertFalse(GraveboundKnight.shouldGuard(0, 20, 40, false));
        assertFalse(GraveboundKnight.shouldGuard(0, 0, 40, true));
    }
}
