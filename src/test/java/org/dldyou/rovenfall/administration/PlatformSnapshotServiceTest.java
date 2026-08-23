package org.dldyou.rovenfall.administration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class PlatformSnapshotServiceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void ownerCreatesSnapshotThatRoundTripsThroughCompressedNbt() throws Exception {
        PlatformSavedData state = new PlatformSavedData();
        UUID owner = id(1);
        bootstrap(state, owner, AdminRole.OWNER);
        PlatformSnapshotStore store = store("create");
        UUID snapshotId = id(101);

        var result = AdministrationService.createSnapshot(
                state, store, owner, false, "manual backup", 2_000, id(201), snapshotId);

        assertEquals(AdministrationService.SnapshotCreateStatus.SUCCESS, result.status());
        assertTrue(Files.isRegularFile(snapshotPath("create", snapshotId)));
        PlatformSavedData loaded = store.read(snapshotId);
        assertEquals(AdminRole.OWNER, loaded.roleOf(owner).orElseThrow());
        assertEquals(1, loaded.auditCount());
        assertEquals(2, state.auditCount());
        assertAudit(state, "rovenfall:platform_snapshot_create", "none", "snapshot:" + snapshotId, id(201));

        var duplicate = AdministrationService.createSnapshot(
                state, store, owner, false, "duplicate id", 4_000, id(203), snapshotId);
        assertEquals(AdministrationService.SnapshotCreateStatus.STORAGE_ERROR, duplicate.status());
        assertEquals(AdminRole.OWNER, store.read(snapshotId).roleOf(owner).orElseThrow());

        UUID invalidReasonId = id(103);
        var invalidReason = AdministrationService.createSnapshot(
                state, store, owner, false, " ", 6_000, id(204), invalidReasonId);
        assertEquals(AdministrationService.SnapshotCreateStatus.INVALID_REASON, invalidReason.status());
        assertFalse(Files.exists(snapshotPath("create", invalidReasonId)));

        PlatformSavedData consoleState = new PlatformSavedData();
        var consoleResult = AdministrationService.createSnapshot(
                consoleState, store("console"), AdministrationService.SYSTEM_ACTOR, true,
                "recovery backup", 3_000, id(202), id(102));
        assertEquals(AdministrationService.SnapshotCreateStatus.SUCCESS, consoleResult.status());
    }

    @Test
    void everyNonOwnerRoleIsDeniedWithoutWritingOrRestoringSnapshot() throws Exception {
        for (AdminRole role : AdminRole.values()) {
            if (role == AdminRole.OWNER) {
                continue;
            }

            PlatformSavedData state = new PlatformSavedData();
            UUID actor = id(10 + role.ordinal());
            bootstrap(state, actor, role);
            PlatformSnapshotStore store = store(role.getSerializedName());
            UUID snapshotId = id(110 + role.ordinal());
            int auditCount = state.auditCount();

            var result = AdministrationService.createSnapshot(
                    state, store, actor, false, "unauthorized backup", 2_000, id(210 + role.ordinal()), snapshotId);

            assertEquals(AdministrationService.SnapshotCreateStatus.UNAUTHORIZED, result.status());
            assertFalse(Files.exists(snapshotPath(role.getSerializedName(), snapshotId)));
            assertEquals(auditCount + 1, state.auditCount());

            UUID sourceSnapshotId = id(120 + role.ordinal());
            UUID safetySnapshotId = id(130 + role.ordinal());
            store.write(sourceSnapshotId, state);
            int restoreAuditCount = state.auditCount();
            var restoreResult = AdministrationService.restoreSnapshot(
                    state, store, actor, false, sourceSnapshotId, "unauthorized restore",
                    4_000, id(230 + role.ordinal()), safetySnapshotId);

            assertEquals(AdministrationService.SnapshotRestoreStatus.UNAUTHORIZED, restoreResult.status());
            assertEquals(role, state.roleOf(actor).orElseThrow());
            assertFalse(Files.exists(snapshotPath(role.getSerializedName(), safetySnapshotId)));
            assertEquals(restoreAuditCount + 1, state.auditCount());
        }
    }

    @Test
    void restoreCreatesSafetySnapshotAndPreservesLiveAuditHistory() throws Exception {
        PlatformSavedData state = new PlatformSavedData();
        UUID owner = id(20);
        UUID target = id(21);
        bootstrap(state, owner, AdminRole.OWNER);
        change(state, owner, target, AdminRole.VIEWER, 2_000, 301);
        PlatformSnapshotStore store = store("restore");
        UUID sourceSnapshotId = id(120);
        store.write(sourceSnapshotId, state);

        change(state, owner, target, AdminRole.MODERATOR, 3_000, 302);
        int auditCount = state.auditCount();
        UUID safetySnapshotId = id(121);
        UUID transactionId = id(303);

        var result = AdministrationService.restoreSnapshot(
                state, store, owner, false, sourceSnapshotId, "undo role change",
                4_000, transactionId, safetySnapshotId);

        assertEquals(AdministrationService.SnapshotRestoreStatus.SUCCESS, result.status());
        assertEquals(AdminRole.VIEWER, state.roleOf(target).orElseThrow());
        assertEquals(auditCount + 1, state.auditCount());
        assertEquals(AdminRole.MODERATOR, store.read(safetySnapshotId).roleOf(target).orElseThrow());
        assertAudit(state, "rovenfall:platform_snapshot_restore",
                "snapshot:" + safetySnapshotId, "snapshot:" + sourceSnapshotId, transactionId);
    }

    @Test
    void missingAndCorruptSnapshotsDoNotMutateLiveState() throws Exception {
        PlatformSavedData state = stateWithModerator();
        UUID owner = id(30);
        UUID target = id(31);
        PlatformSnapshotStore store = store("invalid");
        UUID corruptId = id(131);
        Files.createDirectories(temporaryDirectory.resolve("invalid"));
        Files.writeString(snapshotPath("invalid", corruptId), "not nbt");

        for (UUID snapshotId : new UUID[]{id(130), corruptId}) {
            int auditCount = state.auditCount();
            UUID safetySnapshotId = UUID.randomUUID();
            var result = AdministrationService.restoreSnapshot(
                    state, store, owner, false, snapshotId, "invalid restore",
                    5_000L + auditCount * 1_000L, UUID.randomUUID(), safetySnapshotId);

            assertEquals(AdministrationService.SnapshotRestoreStatus.SNAPSHOT_UNAVAILABLE, result.status());
            assertEquals(AdminRole.MODERATOR, state.roleOf(target).orElseThrow());
            assertFalse(Files.exists(snapshotPath("invalid", safetySnapshotId)));
            assertEquals(auditCount + 1, state.auditCount());
        }
    }

    @Test
    void safetySnapshotFailurePreventsRestore() throws Exception {
        PlatformSavedData state = new PlatformSavedData();
        UUID owner = id(40);
        UUID target = id(41);
        bootstrap(state, owner, AdminRole.OWNER);
        change(state, owner, target, AdminRole.VIEWER, 2_000, 401);
        PlatformSnapshotStore store = store("safety-failure");
        UUID sourceSnapshotId = id(140);
        store.write(sourceSnapshotId, state);
        change(state, owner, target, AdminRole.MODERATOR, 3_000, 402);

        UUID occupiedSafetyId = id(141);
        store.write(occupiedSafetyId, state);
        int auditCount = state.auditCount();
        var result = AdministrationService.restoreSnapshot(
                state, store, owner, false, sourceSnapshotId, "collision test",
                4_000, id(403), occupiedSafetyId);

        assertEquals(AdministrationService.SnapshotRestoreStatus.SAFETY_SNAPSHOT_FAILED, result.status());
        assertEquals(AdminRole.MODERATOR, state.roleOf(target).orElseThrow());
        assertEquals(auditCount + 1, state.auditCount());
    }

    private PlatformSavedData stateWithModerator() {
        PlatformSavedData state = new PlatformSavedData();
        bootstrap(state, id(30), AdminRole.OWNER);
        change(state, id(30), id(31), AdminRole.MODERATOR, 2_000, 501);
        return state;
    }

    private PlatformSnapshotStore store(String directory) {
        return new PlatformSnapshotStore(temporaryDirectory.resolve(directory));
    }

    private Path snapshotPath(String directory, UUID snapshotId) {
        return temporaryDirectory.resolve(directory).resolve(snapshotId + ".nbt");
    }

    private static void bootstrap(PlatformSavedData state, UUID actor, AdminRole role) {
        AdministrationService.changeRole(
                state, AdministrationService.SYSTEM_ACTOR, true, actor, role.getSerializedName(),
                "bootstrap", 1_000, UUID.randomUUID());
    }

    private static void change(
            PlatformSavedData state,
            UUID owner,
            UUID target,
            AdminRole role,
            long timestamp,
            long transactionSeed) {
        AdministrationService.changeRole(
                state, owner, false, target, role.getSerializedName(), "test change", timestamp, id(transactionSeed));
    }

    private static void assertAudit(
            PlatformSavedData state,
            String action,
            String before,
            String after,
            UUID transactionId) {
        AuditEntry entry = state.auditPage(0, 1).entries().getFirst();
        assertEquals(action, entry.actionType().toString());
        assertEquals("platform", entry.target());
        assertEquals(before, entry.beforeValue());
        assertEquals(after, entry.afterValue());
        assertEquals(transactionId, entry.transactionId());
    }

    private static UUID id(long value) {
        return new UUID(0L, value);
    }
}
