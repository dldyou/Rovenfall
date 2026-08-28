package org.dldyou.rovenfall.administration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.dldyou.rovenfall.claims.ClaimKey;
import org.junit.jupiter.api.Test;

final class AdministrationWorldViewServiceTest {
    @Test
    void claimsAreTypedSearchableAndRoleBounded() {
        PlatformSavedData state = new PlatformSavedData();
        UUID owner = id(1);
        UUID viewer = id(2);
        ClaimKey key = ClaimKey.at(Level.OVERWORLD, new BlockPos(32, 70, 48));
        EconomyService.award(state, owner, 5_000, "seed", 1_000, id(100), 0, Long.MAX_VALUE);
        ClaimPurchaseService.purchase(
                state, owner, key.dimension(), key.dimension(), key.auditPosition(),
                ignored -> true, ignored -> false, 1_000, 0, 64, 2_000, id(101));
        AdministrationService.changeRole(
                state, AdministrationService.SYSTEM_ACTOR, true, viewer, "viewer",
                "bootstrap", 3_000, id(102));

        var page = AdministrationWorldViewService.claims(
                state, viewer, false, owner.toString(), 0);

        assertEquals(AdministrationWorldViewService.Status.SUCCESS, page.status());
        assertEquals(1, page.totalEntries());
        assertEquals(key, page.entries().getFirst().key());
        assertTrue(AdministrationWorldViewService.claims(
                state, id(9), false, "", 0).entries().isEmpty());
    }

    private static UUID id(long value) {
        return new UUID(0L, value);
    }
}
