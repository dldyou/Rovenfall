package org.dldyou.rovenfall.administration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import org.dldyou.rovenfall.claims.Claim;
import org.dldyou.rovenfall.claims.ClaimKey;
import org.dldyou.rovenfall.claims.ClaimSettings;
import org.dldyou.rovenfall.world.ProtectedRegion;
import org.junit.jupiter.api.Test;

final class AdministrationWorldActionServiceTest {
    private static final ClaimKey KEY = ClaimKey.at(Level.OVERWORLD, new BlockPos(16, 70, 16));

    @Test
    void exactDomainSnapshotsRejectConcurrentChanges() {
        PlatformSavedData state = claimed(id(1));
        Claim original = state.claim(KEY).orElseThrow();
        var claimAction = new AdministrationWorldActionService.ClaimSettingsAction(
                id(10), KEY, Optional.of(original), new ClaimSettings(true, true), "preview");
        assertTrue(AdministrationWorldActionService.fresh(state, claimAction));

        ClaimManagementService.setSettings(
                state, id(1), false, KEY, new ClaimSettings(true, false), "concurrent", 5_000, id(11));
        assertFalse(AdministrationWorldActionService.fresh(state, claimAction));

        Identifier regionId = Identifier.fromNamespaceAndPath("rovenfall", "test_region");
        ProtectedRegion region = new ProtectedRegion(id(2), Level.OVERWORLD, 10, 10, 11, 11);
        var create = new AdministrationWorldActionService.RegionCreateAction(
                id(12), regionId, region, "preview");
        assertTrue(AdministrationWorldActionService.fresh(state, create));
        ProtectedRegionService.create(state, id(2), true, regionId, region, "concurrent", 6_000, id(13));
        assertFalse(AdministrationWorldActionService.fresh(state, create));

        WildernessResetState before = state.wildernessResetState();
        var warning = new AdministrationWorldActionService.WildernessWarnAction(id(14), before, "preview");
        assertTrue(AdministrationWorldActionService.fresh(state, warning));
        WildernessResetService.warn(state, id(3), true, "concurrent", 7_000, id(15));
        assertFalse(AdministrationWorldActionService.fresh(state, warning));
    }

    @Test
    void rolesMatchTheOwningDomainServiceBoundaries() {
        PlatformSavedData state = claimed(id(20));
        var claim = new AdministrationWorldActionService.ClaimReclaimAction(
                id(21), KEY, state.claim(KEY), "reclaim");
        var region = new AdministrationWorldActionService.RegionCreateAction(
                id(22), Identifier.fromNamespaceAndPath("rovenfall", "region"),
                new ProtectedRegion(id(20), Level.OVERWORLD, 0, 0, 0, 0), "region");

        assertTrue(AdministrationWorldActionService.allowed(AdminRole.MODERATOR, claim));
        assertTrue(AdministrationWorldActionService.allowed(AdminRole.OWNER, claim));
        assertFalse(AdministrationWorldActionService.allowed(AdminRole.VIEWER, claim));
        assertFalse(AdministrationWorldActionService.allowed(AdminRole.MODERATOR, region));
        assertTrue(AdministrationWorldActionService.allowed(AdminRole.OWNER, region));
    }

    private static PlatformSavedData claimed(UUID owner) {
        PlatformSavedData state = new PlatformSavedData();
        EconomyService.award(state, owner, 5_000, "seed", 1_000, id(100), 0, Long.MAX_VALUE);
        ClaimPurchaseService.purchase(
                state, owner, KEY.dimension(), KEY.dimension(), KEY.auditPosition(),
                ignored -> true, ignored -> false, 1_000, 0, 64, 2_000, id(101));
        return state;
    }

    private static UUID id(long value) {
        return new UUID(0L, value);
    }
}
