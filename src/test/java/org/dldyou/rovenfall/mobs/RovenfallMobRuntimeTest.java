package org.dldyou.rovenfall.mobs;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import net.minecraft.world.level.Level;
import org.dldyou.rovenfall.administration.EconomyService;
import org.dldyou.rovenfall.administration.PlatformSavedData;
import org.dldyou.rovenfall.world.WorldTopology;
import org.junit.jupiter.api.Test;

final class RovenfallMobRuntimeTest {
    private static final MobContentCatalog.SpawnCondition SPAWN = new MobContentCatalog.SpawnCondition(
            WorldTopology.WILDERNESS, 100_000, -32, 96);

    @Test
    void spawnPolicyFailsClosedOutsideEligibleWildernessRegions() {
        assertTrue(RovenfallMobRuntime.allows(SPAWN, WorldTopology.WILDERNESS, -32, false, false));
        assertTrue(RovenfallMobRuntime.allows(SPAWN, WorldTopology.WILDERNESS, 96, false, false));

        assertFalse(RovenfallMobRuntime.allows(SPAWN, Level.OVERWORLD, 64, false, false));
        assertFalse(RovenfallMobRuntime.allows(SPAWN, WorldTopology.WILDERNESS, -33, false, false));
        assertFalse(RovenfallMobRuntime.allows(SPAWN, WorldTopology.WILDERNESS, 97, false, false));
        assertFalse(RovenfallMobRuntime.allows(SPAWN, WorldTopology.WILDERNESS, 64, true, false));
        assertFalse(RovenfallMobRuntime.allows(SPAWN, WorldTopology.WILDERNESS, 64, false, true));
    }

    @Test
    void rewardTransactionsAreStablePerEntityAndPlayer() {
        UUID entityId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();

        assertTrue(RovenfallMobRuntime.rewardTransactionId(entityId, playerId)
                .equals(RovenfallMobRuntime.rewardTransactionId(entityId, playerId)));
        assertNotEquals(
                RovenfallMobRuntime.rewardTransactionId(entityId, playerId),
                RovenfallMobRuntime.rewardTransactionId(entityId, UUID.randomUUID()));
    }

    @Test
    void currencyRewardIsIdempotentForTheSameMobDeath() {
        PlatformSavedData state = new PlatformSavedData();
        UUID entityId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();

        var first = RovenfallMobRuntime.awardCurrency(
                state, playerId, 8, RovenfallMobEntities.GROVE_STALKER_ID,
                1_000, entityId, 100, 10_000);
        var duplicate = RovenfallMobRuntime.awardCurrency(
                state, playerId, 8, RovenfallMobEntities.GROVE_STALKER_ID,
                1_001, entityId, 100, 10_000);

        assertEquals(EconomyService.TransactionStatus.SUCCESS, first.status());
        assertEquals(EconomyService.TransactionStatus.DUPLICATE_TRANSACTION, duplicate.status());
        assertEquals(108, state.economyBalance(playerId).orElseThrow());
    }
}
