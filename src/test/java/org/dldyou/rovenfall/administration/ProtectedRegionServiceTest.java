package org.dldyou.rovenfall.administration;

import static org.dldyou.rovenfall.PersistenceTestHarness.roundTrip;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.Identifier;
import org.dldyou.rovenfall.claims.ClaimKey;
import org.dldyou.rovenfall.world.ProtectedRegion;
import org.dldyou.rovenfall.world.WorldTopology;
import org.junit.jupiter.api.Test;

final class ProtectedRegionServiceTest {
    @Test
    void ownerMutationIsAtomicAuditedIndexedAndPersistent() {
        PlatformSavedData state = new PlatformSavedData();
        UUID owner = id(1);
        UUID moderator = id(2);
        AdministrationService.changeRole(
                state, AdministrationService.SYSTEM_ACTOR, true, owner, "owner", "bootstrap", 1_000, id(101));
        AdministrationService.changeRole(
                state, owner, false, moderator, "moderator", "support", 2_000, id(102));
        Identifier regionId = Identifier.fromNamespaceAndPath("rovenfall", "portal_ring");
        ProtectedRegion hubRegion = new ProtectedRegion(owner, WorldTopology.HUB, 40, 50, 41, 51);

        int auditsBeforeDenied = state.auditCount();
        var denied = ProtectedRegionService.create(
                state, moderator, false, regionId, hubRegion, "attempt", 4_000, id(103));
        assertEquals(ProtectedRegionService.Status.UNAUTHORIZED, denied.status());
        assertTrue(denied.auditRecorded());
        assertEquals(auditsBeforeDenied + 1, state.auditCount());
        assertTrue(state.protectedRegion(regionId).isEmpty());

        var created = ProtectedRegionService.create(
                state, owner, false, regionId, hubRegion, "portal arrival", 6_000, id(104));
        assertEquals(ProtectedRegionService.Status.SUCCESS, created.status());
        assertTrue(state.isProtectedRegion(new ClaimKey(WorldTopology.HUB, 40, 50)));
        assertFalse(state.isProtectedRegion(new ClaimKey(WorldTopology.HUB, 42, 50)));
        assertEquals(4, state.protectedRegionsAt(new ClaimKey(WorldTopology.HUB, 40, 50)).size()
                + state.protectedRegionsAt(new ClaimKey(WorldTopology.HUB, 41, 50)).size()
                + state.protectedRegionsAt(new ClaimKey(WorldTopology.HUB, 40, 51)).size()
                + state.protectedRegionsAt(new ClaimKey(WorldTopology.HUB, 41, 51)).size());

        int auditsBeforeNullCreate = state.auditCount();
        var nullCreate = ProtectedRegionService.create(
                state, owner, false, Identifier.fromNamespaceAndPath("rovenfall", "null_create"),
                null, "invalid", 6_500, id(1041));
        assertEquals(ProtectedRegionService.Status.INVALID_REQUEST, nullCreate.status());
        assertFalse(nullCreate.auditRecorded());
        assertEquals(auditsBeforeNullCreate, state.auditCount());
        assertEquals(hubRegion, state.protectedRegion(regionId).orElseThrow());

        int auditsBeforeNullEdit = state.auditCount();
        var nullEdit = ProtectedRegionService.edit(
                state, owner, false, regionId, null, "invalid", 6_600, id(1042));
        assertEquals(ProtectedRegionService.Status.INVALID_REQUEST, nullEdit.status());
        assertFalse(nullEdit.auditRecorded());
        assertEquals(auditsBeforeNullEdit, state.auditCount());
        assertEquals(hubRegion, state.protectedRegion(regionId).orElseThrow());

        ProtectedRegion invalid = new ProtectedRegion(owner, WorldTopology.WILDERNESS, 0, 0, 32, 0);
        var rejected = ProtectedRegionService.edit(
                state, owner, false, regionId, invalid, "too wide", 7_000, id(105));
        assertEquals(ProtectedRegionService.Status.LIMIT_EXCEEDED, rejected.status());
        assertEquals(hubRegion, state.protectedRegion(regionId).orElseThrow());

        ProtectedRegion wildernessRegion = new ProtectedRegion(owner, WorldTopology.WILDERNESS, -2, -2, 2, 2);
        var edited = ProtectedRegionService.edit(
                state, owner, false, regionId, wildernessRegion, "move portal", 9_000, id(106));
        assertEquals(ProtectedRegionService.Status.SUCCESS, edited.status());
        assertFalse(state.isProtectedRegion(new ClaimKey(WorldTopology.HUB, 40, 50)));
        assertTrue(state.isProtectedRegion(new ClaimKey(WorldTopology.WILDERNESS, 0, 0)));

        PlatformSavedData loaded = roundTrip(PlatformSavedData.CODEC, state);
        assertEquals(PlatformSavedData.CURRENT_SCHEMA_VERSION, loaded.schemaVersion());
        assertEquals(wildernessRegion, loaded.protectedRegion(regionId).orElseThrow());
        assertTrue(loaded.isProtectedRegion(new ClaimKey(WorldTopology.WILDERNESS, 0, 0)));
        AuditEntry latest = loaded.auditPage(0, 1).entries().getFirst();
        assertEquals(Identifier.fromNamespaceAndPath("rovenfall", "protected_region_edit"), latest.actionType());
        assertEquals(regionId.toString(), latest.target());
        assertEquals(id(106), latest.transactionId());

        var deleted = ProtectedRegionService.delete(
                loaded, owner, false, regionId, "retire portal", 11_000, id(107));
        assertEquals(ProtectedRegionService.Status.SUCCESS, deleted.status());
        assertTrue(loaded.protectedRegion(regionId).isEmpty());
        assertFalse(loaded.isProtectedRegion(new ClaimKey(WorldTopology.WILDERNESS, 0, 0)));
    }

    @Test
    void topologyUsesStableDimensionIdentitiesAndOnlyHubAllowsClaims() {
        assertEquals("minecraft:overworld", WorldTopology.HUB.identifier().toString());
        assertEquals("rovenfall:wilderness", WorldTopology.WILDERNESS.identifier().toString());
        assertEquals("rovenfall:wilderness", WorldTopology.WILDERNESS_STEM.identifier().toString());
        assertTrue(WorldTopology.isHub(WorldTopology.HUB));
        assertTrue(WorldTopology.isWilderness(WorldTopology.WILDERNESS));
        assertTrue(WorldTopology.allowsClaims(WorldTopology.HUB));
        assertFalse(WorldTopology.allowsClaims(WorldTopology.WILDERNESS));
        assertFalse(WorldTopology.allowsOrdinaryBuilding(WorldTopology.HUB));
        assertTrue(WorldTopology.allowsOrdinaryBuilding(WorldTopology.WILDERNESS));

        PlatformSavedData state = new PlatformSavedData();
        UUID player = id(500);
        assertEquals(EconomyService.TransactionStatus.SUCCESS, EconomyService.award(
                state, player, 2_000, "seed", 1_000, id(501), 0, Long.MAX_VALUE).status());
        assertEquals(ClaimPurchaseService.Status.NOT_IN_HUB, ClaimPurchaseService.purchase(
                state,
                player,
                WorldTopology.WILDERNESS,
                WorldTopology.WILDERNESS,
                new ClaimKey(WorldTopology.WILDERNESS, 10, 10).auditPosition(),
                ignored -> true,
                ignored -> false,
                1_000,
                0,
                64,
                2_000,
                id(502)).status());
        assertEquals(0, state.claimCount());
    }

    @Test
    void schemaSevenMigratesToAnEmptyProtectedRegionIndex() {
        CompoundTag schemaSeven = (CompoundTag) PlatformSavedData.CODEC.encodeStart(
                NbtOps.INSTANCE, new PlatformSavedData()).getOrThrow();
        schemaSeven.putInt("schema_version", 7);
        schemaSeven.remove("protected_regions");

        PlatformSavedData migrated = PlatformSavedData.CODEC.parse(NbtOps.INSTANCE, schemaSeven).getOrThrow();
        assertEquals(PlatformSavedData.CURRENT_SCHEMA_VERSION, migrated.schemaVersion());
        assertTrue(migrated.isWritable());
        assertEquals(0, migrated.protectedRegionCount());
    }

    private static UUID id(long value) {
        return new UUID(0L, value);
    }
}
