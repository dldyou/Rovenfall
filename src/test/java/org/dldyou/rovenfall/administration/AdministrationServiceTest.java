package org.dldyou.rovenfall.administration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.dldyou.rovenfall.PersistenceTestHarness.roundTrip;

import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import org.junit.jupiter.api.Test;

final class AdministrationServiceTest {
    @Test
    void ownerChangesRoleAndStateRoundTripsThroughNbt() {
        PlatformSavedData state = new PlatformSavedData();
        UUID owner = id(1);
        UUID target = id(2);

        assertEquals(AdministrationService.RoleChangeStatus.SUCCESS,
                change(state, AdministrationService.SYSTEM_ACTOR, true, owner, "owner", "bootstrap", 1_000, 101).status());
        assertEquals(AdministrationService.RoleChangeStatus.SUCCESS,
                change(state, owner, false, target, "viewer", "support access", 2_000, 102).status());

        PlatformSavedData loaded = roundTrip(PlatformSavedData.CODEC, state);
        assertEquals(AdminRole.OWNER, loaded.roleOf(owner).orElseThrow());
        assertEquals(AdminRole.VIEWER, loaded.roleOf(target).orElseThrow());
        assertEquals(2, loaded.auditCount());
        assertEquals(PlatformSavedData.CURRENT_SCHEMA_VERSION, loaded.schemaVersion());
        assertTrue(loaded.isWritable());
    }

    @Test
    void everyNonOwnerRoleIsDeniedWithoutChangingTheTarget() {
        for (AdminRole actorRole : AdminRole.values()) {
            if (actorRole == AdminRole.OWNER) {
                continue;
            }

            PlatformSavedData state = new PlatformSavedData();
            UUID actor = id(10 + actorRole.ordinal());
            UUID target = id(20 + actorRole.ordinal());
            change(state, AdministrationService.SYSTEM_ACTOR, true, actor, actorRole.getSerializedName(), "bootstrap", 1_000, 200 + actorRole.ordinal());
            int auditCountBefore = state.auditCount();

            var result = change(state, actor, false, target, "owner", "escalation", 2_000, 300 + actorRole.ordinal());

            assertEquals(AdministrationService.RoleChangeStatus.UNAUTHORIZED, result.status());
            assertTrue(result.auditRecorded());
            assertTrue(state.roleOf(target).isEmpty());
            assertEquals(auditCountBefore + 1, state.auditCount());
        }
    }

    @Test
    void invalidInputIsDeniedAndRepeatedDenialsAreRateLimited() {
        PlatformSavedData state = new PlatformSavedData();
        UUID owner = id(30);
        UUID target = id(31);
        change(state, AdministrationService.SYSTEM_ACTOR, true, owner, "owner", "bootstrap", 1_000, 401);

        var first = change(state, owner, false, target, "unknown", "reason", 2_000, 402);
        var second = change(state, owner, false, target, "unknown", "reason", 2_500, 403);
        var blankReason = change(state, owner, false, target, "viewer", " ", 3_000, 404);

        assertEquals(AdministrationService.RoleChangeStatus.INVALID_ROLE, first.status());
        assertTrue(first.auditRecorded());
        assertEquals(AdministrationService.RoleChangeStatus.INVALID_ROLE, second.status());
        assertFalse(second.auditRecorded());
        assertEquals(AdministrationService.RoleChangeStatus.INVALID_REASON, blankReason.status());
        assertTrue(blankReason.auditRecorded());
        assertTrue(state.roleOf(target).isEmpty());
        assertEquals(3, state.auditCount());
    }

    @Test
    void versionZeroMigrationPreservesState() {
        PlatformSavedData original = new PlatformSavedData();
        UUID owner = id(40);
        UUID viewer = id(41);
        change(original, AdministrationService.SYSTEM_ACTOR, true, owner, "owner", "bootstrap", 1_000, 501);
        change(original, owner, false, viewer, "viewer", "support access", 2_000, 502);

        CompoundTag versionZero = (CompoundTag) PlatformSavedData.CODEC.encodeStart(NbtOps.INSTANCE, original).getOrThrow();
        versionZero.remove("schema_version");
        PlatformSavedData migrated = PlatformSavedData.CODEC.parse(NbtOps.INSTANCE, versionZero).getOrThrow();

        assertEquals(PlatformSavedData.CURRENT_SCHEMA_VERSION, migrated.schemaVersion());
        assertTrue(migrated.isWritable());
        assertEquals(AdminRole.OWNER, migrated.roleOf(owner).orElseThrow());
        assertEquals(AdminRole.VIEWER, migrated.roleOf(viewer).orElseThrow());
        assertEquals(2, migrated.auditCount());
    }

    @Test
    void unsupportedPastAndFutureSchemasBlockWrites() {
        for (int schemaVersion : new int[]{-1, PlatformSavedData.CURRENT_SCHEMA_VERSION + 1}) {
            CompoundTag tag = new CompoundTag();
            tag.putInt("schema_version", schemaVersion);
            PlatformSavedData state = PlatformSavedData.CODEC.parse(NbtOps.INSTANCE, tag).getOrThrow();
            var result = change(state, AdministrationService.SYSTEM_ACTOR, true, id(42), "owner", "bootstrap", 1_000, 503);

            assertEquals(schemaVersion, state.schemaVersion());
            assertFalse(state.isWritable());
            assertEquals(AdministrationService.RoleChangeStatus.READ_ONLY_SCHEMA, result.status());
            assertFalse(result.auditRecorded());
            assertTrue(state.roleOf(id(42)).isEmpty());
            assertFalse(state.isDirty());
        }
    }

    @Test
    void auditQueriesAreNewestFirstAndPaginated() {
        PlatformSavedData state = new PlatformSavedData();
        UUID owner = id(50);
        change(state, AdministrationService.SYSTEM_ACTOR, true, owner, "owner", "bootstrap", 1_000, 601);

        for (int index = 0; index < 12; index++) {
            change(state, owner, false, id(100 + index), "viewer", "page test", 2_000 + index, 700 + index);
        }

        PlatformSavedData.AuditPage first = state.auditPage(0, 10);
        PlatformSavedData.AuditPage second = state.auditPage(1, 10);
        assertEquals(13, first.totalEntries());
        assertEquals(2, first.totalPages());
        assertEquals(10, first.entries().size());
        assertEquals(id(111).toString(), first.entries().getFirst().target());
        assertEquals(3, second.entries().size());
        assertEquals(owner.toString(), second.entries().getLast().target());
    }

    private static AdministrationService.RoleChangeResult change(
            PlatformSavedData state,
            UUID actor,
            boolean override,
            UUID target,
            String role,
            String reason,
            long timestamp,
            long transactionSeed) {
        return AdministrationService.changeRole(state, actor, override, target, role, reason, timestamp, id(transactionSeed));
    }

    private static UUID id(long value) {
        return new UUID(0L, value);
    }
}
