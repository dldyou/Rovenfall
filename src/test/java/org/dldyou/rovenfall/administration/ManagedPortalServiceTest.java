package org.dldyou.rovenfall.administration;

import static org.dldyou.rovenfall.PersistenceTestHarness.roundTrip;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import org.dldyou.rovenfall.claims.ClaimKey;
import org.dldyou.rovenfall.worlds.Portal;
import org.junit.jupiter.api.Test;

final class ManagedPortalServiceTest {
    private static final Identifier PORTAL_ID = identifier("north_gate");
    private static final Portal PORTAL = new Portal(
            Level.OVERWORLD,
            new BlockPos(16, 70, 16),
            WorldCombatService.WILDERNESS_DIMENSION,
            new BlockPos(120, 80, -40),
            8,
            24,
            7);

    @Test
    void contentManagerCreatesPersistsProtectsAndDeletesPortalAtomically() {
        PlatformSavedData state = new PlatformSavedData();
        UUID actor = id(1);
        role(state, actor, AdminRole.CONTENT_MANAGER, 1_000);
        UUID createTransaction = id(10);

        var created = ManagedPortalService.create(
                state, actor, false, PORTAL_ID, PORTAL, ignored -> true,
                "open north gate", 2_000, createTransaction);

        assertEquals(ManagedPortalService.Status.SUCCESS, created.status());
        assertEquals(PORTAL, state.portal(PORTAL_ID).orElseThrow());
        assertTrue(state.hasTransaction(createTransaction, 2_000));
        assertTrue(state.isPortalProtected(Level.OVERWORLD, new BlockPos(16, 300, 16)));
        assertTrue(state.isPortalProtected(Level.OVERWORLD, new BlockPos(24, -40, 16)));
        assertFalse(state.isPortalProtected(Level.OVERWORLD, new BlockPos(25, 70, 16)));
        assertFalse(state.isPortalProtected(Level.NETHER, PORTAL.origin()));
        assertTrue(state.isPortalProtected(new ClaimKey(Level.OVERWORLD, 0, 0)));
        assertTrue(state.isPortalProtected(new ClaimKey(Level.OVERWORLD, 1, 1)));
        assertFalse(state.isPortalProtected(new ClaimKey(Level.OVERWORLD, 2, 2)));

        AuditEntry createAudit = state.auditPage(0, 1).entries().getFirst();
        assertEquals(identifier("portal_create"), createAudit.actionType());
        assertEquals(PORTAL_ID.toString(), createAudit.target());
        assertEquals(PORTAL.origin(), createAudit.position().orElseThrow());
        assertTrue(createAudit.afterValue().contains("destination=rovenfall:wilderness@120, 80, -40"));

        PlatformSavedData restored = roundTrip(PlatformSavedData.CODEC, state);
        assertEquals(PORTAL, restored.portal(PORTAL_ID).orElseThrow());
        assertTrue(restored.isPortalProtected(Level.OVERWORLD, PORTAL.origin()));

        UUID deleteTransaction = id(11);
        assertEquals(ManagedPortalService.Status.SUCCESS, ManagedPortalService.delete(
                restored, actor, false, PORTAL_ID, "close gate", 3_000, deleteTransaction).status());
        assertTrue(restored.portal(PORTAL_ID).isEmpty());
        assertFalse(restored.isPortalProtected(Level.OVERWORLD, PORTAL.origin()));
        assertEquals(ManagedPortalService.Status.DUPLICATE_TRANSACTION, ManagedPortalService.delete(
                restored, actor, false, PORTAL_ID, "retry", 4_000, deleteTransaction).status());
        assertEquals(identifier("portal_delete"), restored.auditPage(0, 1).entries().getFirst().actionType());
    }

    @Test
    void onlyContentManagersOwnersAndExplicitBootstrapOverrideCanMutatePortals() {
        PlatformSavedData state = new PlatformSavedData();
        UUID viewer = id(20);
        UUID moderator = id(21);
        UUID economyManager = id(22);
        UUID contentManager = id(23);
        UUID owner = id(24);
        role(state, viewer, AdminRole.VIEWER, 1_000);
        role(state, moderator, AdminRole.MODERATOR, 2_000);
        role(state, economyManager, AdminRole.ECONOMY_MANAGER, 3_000);
        role(state, contentManager, AdminRole.CONTENT_MANAGER, 4_000);
        role(state, owner, AdminRole.OWNER, 5_000);

        assertFalse(ManagedPortalService.canManagePortals(state, viewer, false));
        assertFalse(ManagedPortalService.canManagePortals(state, moderator, false));
        assertFalse(ManagedPortalService.canManagePortals(state, economyManager, false));
        assertTrue(ManagedPortalService.canManagePortals(state, contentManager, false));
        assertTrue(ManagedPortalService.canManagePortals(state, owner, false));
        assertTrue(ManagedPortalService.canManagePortals(state, viewer, true));

        for (UUID denied : new UUID[]{viewer, moderator, economyManager}) {
            UUID transaction = UUID.randomUUID();
            assertEquals(ManagedPortalService.Status.UNAUTHORIZED, ManagedPortalService.create(
                    state, denied, false, PORTAL_ID, PORTAL, ignored -> true,
                    "denied", 10_000 + denied.getLeastSignificantBits() * 2_000, transaction).status());
            assertFalse(state.hasTransaction(transaction, 20_000));
            assertTrue(state.portal(PORTAL_ID).isEmpty());
        }
    }

    @Test
    void claimProtectionUsesExactCircleChunkIntersectionAfterCandidateLookup() {
        PlatformSavedData state = new PlatformSavedData();
        UUID owner = id(25);
        role(state, owner, AdminRole.OWNER, 1_000);
        Portal cornerPortal = new Portal(
                Level.OVERWORLD,
                new BlockPos(15, 70, 15),
                Level.NETHER,
                BlockPos.ZERO,
                1,
                1,
                0);
        assertEquals(ManagedPortalService.Status.SUCCESS, ManagedPortalService.create(
                state, owner, false, identifier("corner"), cornerPortal, ignored -> true,
                "corner intersection", 2_000, id(26)).status());

        assertTrue(state.isPortalProtected(new ClaimKey(Level.OVERWORLD, 0, 0)));
        assertTrue(state.isPortalProtected(new ClaimKey(Level.OVERWORLD, 1, 0)));
        assertTrue(state.isPortalProtected(new ClaimKey(Level.OVERWORLD, 0, 1)));
        assertFalse(state.isPortalProtected(new ClaimKey(Level.OVERWORLD, 1, 1)));
    }

    @Test
    void invalidDimensionsPayloadsReasonsAndTransactionsNeverPartiallyMutate() {
        PlatformSavedData state = new PlatformSavedData();
        UUID owner = id(30);
        role(state, owner, AdminRole.OWNER, 1_000);
        Portal invalid = new Portal(
                Level.OVERWORLD, BlockPos.ZERO, Level.NETHER, BlockPos.ZERO,
                Portal.MAX_PROTECTION_RADIUS + 1, 1, 1);

        assertRejectedUncommitted(state, owner, invalid, ignored -> true, "invalid", 2_000, id(31),
                ManagedPortalService.Status.INVALID_REQUEST);
        assertRejectedUncommitted(
                state, owner, PORTAL, key -> !key.equals(WorldCombatService.WILDERNESS_DIMENSION),
                "missing world", 4_000, id(32), ManagedPortalService.Status.DIMENSION_UNAVAILABLE);
        assertRejectedUncommitted(state, owner, PORTAL, ignored -> true, "   ", 6_000, id(33),
                ManagedPortalService.Status.INVALID_REASON);
        assertEquals(ManagedPortalService.Status.INVALID_TRANSACTION, ManagedPortalService.create(
                state, owner, false, PORTAL_ID, PORTAL, ignored -> true,
                "zero transaction", 8_000, new UUID(0L, 0L)).status());
        assertTrue(state.portal(PORTAL_ID).isEmpty());
    }

    @Test
    void schemaSevenMigratesEmptyPortalStateAndCodecRejectsDuplicatesAndBounds() {
        CompoundTag schemaSeven = (CompoundTag) PlatformSavedData.CODEC
                .encodeStart(NbtOps.INSTANCE, new PlatformSavedData()).getOrThrow();
        schemaSeven.putInt("schema_version", 7);
        schemaSeven.remove("portals");

        PlatformSavedData migrated = PlatformSavedData.CODEC.parse(NbtOps.INSTANCE, schemaSeven).getOrThrow();
        assertEquals(PlatformSavedData.CURRENT_SCHEMA_VERSION, migrated.schemaVersion());
        assertTrue(migrated.isWritable());
        assertEquals(0, migrated.portalCount());

        var onePortalCodec = PlatformSavedData.boundedPortalsCodec(1);
        Map<Identifier, Portal> onePortal = Map.of(PORTAL_ID, PORTAL);
        var encoded = onePortalCodec.encodeStart(NbtOps.INSTANCE, onePortal).getOrThrow();
        assertEquals(onePortal, onePortalCodec.parse(NbtOps.INSTANCE, encoded).getOrThrow());
        assertTrue(onePortalCodec.encodeStart(NbtOps.INSTANCE, Map.of(
                PORTAL_ID, PORTAL, identifier("other"), PORTAL)).error().isPresent());

        ListTag duplicate = ((ListTag) encoded).copy();
        duplicate.add(encoded.asList().orElseThrow().getFirst().copy());
        assertTrue(PlatformSavedData.boundedPortalsCodec(2)
                .parse(NbtOps.INSTANCE, duplicate).error().isPresent());
    }

    private static void assertRejectedUncommitted(
            PlatformSavedData state,
            UUID actor,
            Portal portal,
            java.util.function.Predicate<net.minecraft.resources.ResourceKey<Level>> dimensionExists,
            String reason,
            long timestamp,
            UUID transactionId,
            ManagedPortalService.Status expected) {
        assertEquals(expected, ManagedPortalService.create(
                state, actor, false, PORTAL_ID, portal, dimensionExists,
                reason, timestamp, transactionId).status());
        assertFalse(state.hasTransaction(transactionId, timestamp));
        assertTrue(state.portal(PORTAL_ID).isEmpty());
    }

    private static void role(PlatformSavedData state, UUID actor, AdminRole role, long timestamp) {
        assertEquals(AdministrationService.RoleChangeStatus.SUCCESS, AdministrationService.changeRole(
                state,
                AdministrationService.SYSTEM_ACTOR,
                true,
                actor,
                role.getSerializedName(),
                "bootstrap",
                timestamp,
                UUID.randomUUID()).status());
    }

    private static Identifier identifier(String path) {
        return Identifier.fromNamespaceAndPath("rovenfall", path);
    }

    private static UUID id(long value) {
        return new UUID(0L, value);
    }
}
