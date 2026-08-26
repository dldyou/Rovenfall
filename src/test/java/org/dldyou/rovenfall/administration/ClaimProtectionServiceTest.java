package org.dldyou.rovenfall.administration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import org.dldyou.rovenfall.claims.ClaimKey;
import org.dldyou.rovenfall.claims.ClaimRole;
import org.dldyou.rovenfall.claims.ClaimSettings;
import org.dldyou.rovenfall.world.ProtectedRegion;
import org.dldyou.rovenfall.world.WorldTopology;
import org.junit.jupiter.api.Test;

final class ClaimProtectionServiceTest {
    private static final BlockPos SPAWN = BlockPos.ZERO;
    private static final ClaimKey CLAIM = new ClaimKey(Level.OVERWORLD, 10, 10);

    @Test
    void roleMatrixSeparatesBuildingInteractionAndEntry() {
        UUID owner = id(1);
        UUID manager = id(2);
        UUID builder = id(3);
        UUID user = id(4);
        UUID visitor = id(5);
        PlatformSavedData state = claimed(owner, CLAIM, 1);
        setRole(state, owner, manager, ClaimRole.MANAGER, 10);
        setRole(state, owner, builder, ClaimRole.BUILDER, 20);
        setRole(state, owner, user, ClaimRole.USER, 30);
        setRole(state, owner, visitor, ClaimRole.VISITOR, 40);
        ClaimManagementService.setSettings(
                state, owner, false, CLAIM, new ClaimSettings(true, false),
                "restrict entry", 50, id(50));

        assertAllowed(state, owner, ClaimProtectionService.Action.BUILD);
        assertAllowed(state, manager, ClaimProtectionService.Action.BUILD);
        assertAllowed(state, builder, ClaimProtectionService.Action.BUILD);
        assertDenied(state, user, ClaimProtectionService.Action.BUILD);
        assertDenied(state, visitor, ClaimProtectionService.Action.BUILD);

        assertAllowed(state, owner, ClaimProtectionService.Action.INTERACT);
        assertAllowed(state, manager, ClaimProtectionService.Action.INTERACT);
        assertAllowed(state, builder, ClaimProtectionService.Action.INTERACT);
        assertAllowed(state, user, ClaimProtectionService.Action.INTERACT);
        assertDenied(state, visitor, ClaimProtectionService.Action.INTERACT);

        assertAllowed(state, user, ClaimProtectionService.Action.ENTRY);
        assertDenied(state, visitor, ClaimProtectionService.Action.ENTRY);
    }

    @Test
    void publicFlagsUnownedHubProtectedRegionsAndAdminOverrideFollowPrecedence() {
        UUID owner = id(100);
        UUID visitor = id(101);
        UUID moderator = id(102);
        PlatformSavedData state = claimed(owner, CLAIM, 100);
        ClaimManagementService.setSettings(
                state, owner, false, CLAIM, new ClaimSettings(true, true),
                "public use", 110, id(110));
        role(state, moderator, AdminRole.MODERATOR, 120);

        assertAllowed(state, visitor, ClaimProtectionService.Action.INTERACT);
        assertDenied(state, visitor, ClaimProtectionService.Action.ENTRY);
        assertAllowed(state, moderator, ClaimProtectionService.Action.BUILD);

        ClaimKey unowned = new ClaimKey(Level.OVERWORLD, 20, 20);
        assertFalse(decision(state, visitor, unowned, ClaimProtectionService.Action.BUILD, false).allowed());
        assertTrue(decision(state, visitor, unowned, ClaimProtectionService.Action.INTERACT, false).allowed());

        ClaimKey protectedSpawn = new ClaimKey(Level.OVERWORLD, 0, 0);
        assertFalse(decision(state, visitor, protectedSpawn, ClaimProtectionService.Action.BUILD, false).allowed());
        assertTrue(decision(state, visitor, protectedSpawn, ClaimProtectionService.Action.ENTRY, false).allowed());
        assertTrue(decision(state, visitor, protectedSpawn, ClaimProtectionService.Action.BUILD, true).allowed());
        assertTrue(decision(
                state, visitor, new ClaimKey(Level.NETHER, 0, 0),
                ClaimProtectionService.Action.BUILD, false).allowed());
    }

    @Test
    void environmentalEffectsStayWithinOneOwnerBoundaryAndDeniedAuditIsRateLimited() {
        UUID owner = id(200);
        UUID otherOwner = id(201);
        UUID visitor = id(202);
        PlatformSavedData state = claimed(owner, CLAIM, 200);
        ClaimKey sameOwner = new ClaimKey(Level.OVERWORLD, 11, 10);
        ClaimKey other = new ClaimKey(Level.OVERWORLD, 12, 10);
        purchase(state, owner, sameOwner, 210);
        if (state.economyBalance(otherOwner).isEmpty()) {
            EconomyService.award(state, otherOwner, 5_000, "seed", 220, id(220), 0, Long.MAX_VALUE);
        }
        purchase(state, otherOwner, other, 230);

        assertTrue(ClaimProtectionService.environmentMayModify(
                state, Level.OVERWORLD, SPAWN, 2, CLAIM, sameOwner));
        assertFalse(ClaimProtectionService.environmentMayModify(
                state, Level.OVERWORLD, SPAWN, 2, sameOwner, other));
        assertFalse(ClaimProtectionService.environmentMayModify(
                state, Level.OVERWORLD, SPAWN, 2, sameOwner,
                new ClaimKey(Level.OVERWORLD, 30, 30)));
        assertFalse(ClaimProtectionService.environmentMayModify(
                state, Level.OVERWORLD, SPAWN, 2, sameOwner,
                new ClaimKey(Level.OVERWORLD, 0, 0)));

        var denied = decision(state, visitor, CLAIM, ClaimProtectionService.Action.BUILD, false);
        assertTrue(ClaimProtectionService.auditDenied(state, visitor, CLAIM,
                ClaimProtectionService.Action.BUILD, denied, 1_000));
        assertFalse(ClaimProtectionService.auditDenied(state, visitor, CLAIM,
                ClaimProtectionService.Action.BUILD, denied, 1_500));
        assertTrue(ClaimProtectionService.auditDenied(state, visitor, CLAIM,
                ClaimProtectionService.Action.BUILD, denied, 2_000));
    }

    @Test
    void wildernessIsMutableExceptForIndexedAdministratorRegions() {
        PlatformSavedData state = new PlatformSavedData();
        UUID owner = id(250);
        UUID visitor = id(251);
        Identifier portal = Identifier.fromNamespaceAndPath("rovenfall", "wilderness_portal_ring");
        assertEquals(ProtectedRegionService.Status.SUCCESS, ProtectedRegionService.create(
                state,
                owner,
                true,
                portal,
                new ProtectedRegion(owner, WorldTopology.WILDERNESS, 5, 5, 6, 6),
                "protect arrival",
                1_000,
                id(252)).status());

        ClaimKey ordinary = new ClaimKey(WorldTopology.WILDERNESS, 4, 5);
        ClaimKey protectedPortal = new ClaimKey(WorldTopology.WILDERNESS, 5, 5);
        assertTrue(decision(state, visitor, ordinary, ClaimProtectionService.Action.BUILD, false).allowed());
        assertFalse(decision(state, visitor, protectedPortal, ClaimProtectionService.Action.BUILD, false).allowed());
        assertTrue(decision(state, visitor, protectedPortal, ClaimProtectionService.Action.ENTRY, false).allowed());
        assertTrue(decision(state, owner, protectedPortal, ClaimProtectionService.Action.BUILD, true).allowed());
        assertTrue(ClaimProtectionService.environmentMayModify(
                state, WorldTopology.HUB, SPAWN, 2, ordinary, ordinary));
        assertFalse(ClaimProtectionService.environmentMayModify(
                state, WorldTopology.HUB, SPAWN, 2, ordinary, protectedPortal));
    }

    @Test
    void administratorRegionOverridesAnExistingPlayerClaim() {
        UUID claimOwner = id(270);
        UUID administrator = id(271);
        PlatformSavedData state = claimed(claimOwner, CLAIM, 270);
        assertTrue(decision(state, claimOwner, CLAIM, ClaimProtectionService.Action.BUILD, false).allowed());
        assertEquals(ProtectedRegionService.Status.SUCCESS, ProtectedRegionService.create(
                state,
                administrator,
                true,
                Identifier.fromNamespaceAndPath("rovenfall", "claimed_road"),
                new ProtectedRegion(administrator, CLAIM.dimension(),
                        CLAIM.chunkX(), CLAIM.chunkZ(), CLAIM.chunkX(), CLAIM.chunkZ()),
                "reserve road",
                10_000,
                id(272)).status());

        assertFalse(decision(state, claimOwner, CLAIM, ClaimProtectionService.Action.BUILD, false).allowed());
        assertTrue(decision(state, administrator, CLAIM, ClaimProtectionService.Action.BUILD, true).allowed());
    }

    @Test
    void auditLedgerEvictsOldestEntriesAtItsHardLimit() {
        PlatformSavedData state = new PlatformSavedData();
        UUID actor = id(300);
        Identifier action = Identifier.fromNamespaceAndPath("rovenfall", "test_audit");
        for (int index = 0; index <= PlatformSavedData.MAX_AUDIT_ENTRIES; index++) {
            state.commitAudit(new AuditEntry(
                    index,
                    actor,
                    action,
                    "target",
                    Optional.empty(),
                    Optional.empty(),
                    "before",
                    "after",
                    "test",
                    id(10_000L + index)));
        }

        assertEquals(PlatformSavedData.MAX_AUDIT_ENTRIES, state.auditCount());
        assertEquals(PlatformSavedData.MAX_AUDIT_ENTRIES,
                state.auditPage(0, 1).entries().getFirst().timestampEpochMillis());
    }

    @Test
    void deniedAuditRateLimitIndexEvictsLeastRecentlyUsedActors() {
        PlatformSavedData state = new PlatformSavedData();
        Identifier action = Identifier.fromNamespaceAndPath("rovenfall", "test_denied_audit");
        for (int index = 0; index <= PlatformSavedData.MAX_DENIED_AUDIT_ACTORS; index++) {
            UUID actor = id(20_000L + index);
            assertTrue(state.appendDeniedAudit(new AuditEntry(
                    0,
                    actor,
                    action,
                    "target",
                    Optional.empty(),
                    Optional.empty(),
                    "before",
                    "after",
                    "test",
                    id(40_000L + index)), 1_000));
        }

        UUID evictedActor = id(20_000L);
        assertTrue(state.appendDeniedAudit(new AuditEntry(
                1,
                evictedActor,
                action,
                "target",
                Optional.empty(),
                Optional.empty(),
                "before",
                "after",
                "test",
                id(60_000L)), 1_000));
    }

    private static void assertAllowed(
            PlatformSavedData state, UUID actor, ClaimProtectionService.Action action) {
        assertTrue(decision(state, actor, CLAIM, action, false).allowed());
    }

    private static void assertDenied(
            PlatformSavedData state, UUID actor, ClaimProtectionService.Action action) {
        assertFalse(decision(state, actor, CLAIM, action, false).allowed());
    }

    private static ClaimProtectionService.Decision decision(
            PlatformSavedData state,
            UUID actor,
            ClaimKey key,
            ClaimProtectionService.Action action,
            boolean administratorOverride) {
        return ClaimProtectionService.evaluate(
                state, actor, administratorOverride, Level.OVERWORLD, SPAWN, 2, key, action);
    }

    private static PlatformSavedData claimed(UUID owner, ClaimKey key, long seed) {
        PlatformSavedData state = new PlatformSavedData();
        EconomyService.award(state, owner, 10_000, "seed", seed, id(seed), 0, Long.MAX_VALUE);
        purchase(state, owner, key, seed + 1);
        return state;
    }

    private static void purchase(PlatformSavedData state, UUID owner, ClaimKey key, long seed) {
        var result = ClaimPurchaseService.purchase(
                state, owner, key.dimension(), key.dimension(), key.auditPosition(),
                ignored -> true, ignored -> false, 1_000, 0, 64, seed, id(seed + 1_000));
        if (result.status() != ClaimPurchaseService.Status.SUCCESS) {
            throw new AssertionError(result.status());
        }
    }

    private static void setRole(
            PlatformSavedData state, UUID owner, UUID target, ClaimRole role, long seed) {
        var result = ClaimManagementService.setRole(
                state, owner, false, CLAIM, target, role, "test role", seed, id(seed + 2_000));
        if (result.status() != ClaimManagementService.Status.SUCCESS) {
            throw new AssertionError(result.status());
        }
    }

    private static void role(PlatformSavedData state, UUID target, AdminRole role, long seed) {
        var result = AdministrationService.changeRole(
                state, AdministrationService.SYSTEM_ACTOR, true, target, role.getSerializedName(),
                "test admin", seed, id(seed + 3_000));
        if (result.status() != AdministrationService.RoleChangeStatus.SUCCESS) {
            throw new AssertionError(result.status());
        }
    }

    private static UUID id(long value) {
        return new UUID(0, value);
    }
}
