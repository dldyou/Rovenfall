package org.dldyou.rovenfall.administration;

import static org.dldyou.rovenfall.PersistenceTestHarness.roundTrip;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RestartWildernessResetServiceTest {
    @TempDir
    Path worldRoot;

    @Test
    void onlyOwnerAndExplicitBootstrapOverridePassPrecheckAndDenialsAreAudited() {
        UUID operationId = id(100);
        UUID snapshotId = id(101);
        PlatformSavedData ownerState = stateWithRole(id(1), AdminRole.OWNER);
        assertNull(RestartWildernessResetService.precheck(
                ownerState, worldRoot, id(1), false, "season reset", 2_000, operationId, snapshotId));

        for (AdminRole role : AdminRole.values()) {
            if (role == AdminRole.OWNER) {
                continue;
            }
            UUID actor = id(10 + role.ordinal());
            PlatformSavedData state = stateWithRole(actor, role);
            UUID deniedOperation = id(200 + role.ordinal());
            UUID deniedSnapshot = id(300 + role.ordinal());
            assertEquals(RestartWildernessResetService.Status.UNAUTHORIZED, RestartWildernessResetService.precheck(
                    state, worldRoot.resolve(role.getSerializedName()), actor, false,
                    "denied reset", 4_000, deniedOperation, deniedSnapshot));
            int auditCount = state.auditCount();
            var denied = RestartWildernessResetService.rejected(
                    state, actor, deniedOperation, deniedSnapshot, "denied reset", 4_000,
                    RestartWildernessResetService.Status.UNAUTHORIZED,
                    net.minecraft.resources.Identifier.fromNamespaceAndPath("rovenfall", "wilderness_reset_denied"));
            assertEquals(RestartWildernessResetService.Status.UNAUTHORIZED, denied.status());
            assertTrue(denied.auditRecorded());
            assertEquals(auditCount + 1, state.auditCount());
            AuditEntry audit = state.auditPage(0, 1).entries().getFirst();
            assertEquals("rovenfall:wilderness_reset_denied", audit.actionType().toString());
            assertEquals(deniedOperation, audit.transactionId());
            assertFalse(state.hasTransaction(deniedOperation, 4_000));
        }

        PlatformSavedData overrideState = stateWithRole(id(50), AdminRole.VIEWER);
        assertNull(RestartWildernessResetService.precheck(
                overrideState, worldRoot.resolve("override"), id(50), true,
                "bootstrap reset", 5_000, id(500), id(501)));
        assertEquals(RestartWildernessResetService.Status.INVALID_REASON, RestartWildernessResetService.precheck(
                ownerState, worldRoot, id(1), false, " ", 6_000, id(600), id(601)));
        assertEquals(RestartWildernessResetService.Status.INVALID_TRANSACTION, RestartWildernessResetService.precheck(
                ownerState, worldRoot, id(1), false, "bad id", 7_000, id(700), id(700)));
    }

    @Test
    void versionedOperationRoundTripsArchivesAndRejectsUnknownSchema() throws Exception {
        RestartWildernessResetService.Operation ready = readyOperation(1_000, id(20), id(21), id(22), 3);

        RestartWildernessResetService.writePending(worldRoot, ready, false);

        assertEquals(ready, RestartWildernessResetService.readPending(worldRoot).orElseThrow());
        assertEquals(ready, RestartWildernessResetService.operation(worldRoot, ready.operationId()).orElseThrow());
        assertThrows(RestartWildernessResetService.StorageException.class,
                () -> RestartWildernessResetService.writePending(worldRoot, ready, false));

        RestartWildernessResetService.Operation completed = ready.withPhase(
                RestartWildernessResetService.Phase.COMPLETED, "", 2_000);
        RestartWildernessResetService.archivePending(worldRoot, completed);
        assertTrue(RestartWildernessResetService.readPending(worldRoot).isEmpty());
        assertEquals(completed,
                RestartWildernessResetService.operation(worldRoot, completed.operationId()).orElseThrow());
        assertTrue(Files.isRegularFile(worldRoot.resolve("rovenfall/wilderness-resets/receipts/")
                .resolve(completed.operationId() + ".nbt")));

        CompoundTag future = (CompoundTag) RestartWildernessResetService.Operation.CODEC
                .encodeStart(NbtOps.INSTANCE, ready).getOrThrow();
        future.putInt("schema_version", ready.schemaVersion() + 1);
        assertTrue(RestartWildernessResetService.Operation.CODEC.parse(NbtOps.INSTANCE, future).error().isPresent());
    }

    @Test
    void resetMovesTheOldDimensionKeepsRecoveryEvidenceAndPreservesGlobalState() throws Exception {
        Path oldWilderness = wildernessPath();
        Path sentinel = oldWilderness.resolve("region/reset-sentinel.txt");
        Files.createDirectories(sentinel.getParent());
        Files.writeString(sentinel, "old wilderness");
        RestartWildernessResetService.Operation ready = readyOperation(1_000, id(30), id(31), id(32), 2);
        RestartWildernessResetService.writePending(worldRoot, ready, false);

        RestartWildernessResetService.ApplyResult applied = RestartWildernessResetService.applyPendingReset(worldRoot);

        assertEquals(RestartWildernessResetService.ApplyStatus.APPLIED, applied.status());
        RestartWildernessResetService.Operation moved = applied.operation().orElseThrow();
        assertEquals(RestartWildernessResetService.Phase.MOVED, moved.phase());
        assertFalse(Files.exists(oldWilderness));
        Path backupSentinel = backupPath(ready.snapshotId()).resolve("region/reset-sentinel.txt");
        assertEquals("old wilderness", Files.readString(backupSentinel));
        assertEquals(moved, RestartWildernessResetService.readPending(worldRoot).orElseThrow());
        assertEquals(RestartWildernessResetService.ApplyStatus.NOTHING_TO_DO,
                RestartWildernessResetService.applyPendingReset(worldRoot).status());

        PlatformSavedData state = stateWithRole(ready.actorId(), AdminRole.OWNER);
        assertEquals(EconomyService.TransactionStatus.SUCCESS, EconomyService.award(
                state, ready.actorId(), 50, "global balance", 1_500, id(33), 0, 100).status());
        int auditCount = state.auditCount();
        assertEquals(RestartWildernessResetService.CompletionStatus.SUCCESS,
                RestartWildernessResetService.commitSuccess(state, moved, 2_000));
        assertEquals(50, state.economyBalance(ready.actorId()).orElseThrow());
        assertTrue(state.hasTransaction(ready.operationId(), 2_000));
        assertEquals(auditCount + 1, state.auditCount());
        AuditEntry audit = state.auditPage(0, 1).entries().getFirst();
        assertEquals("rovenfall:wilderness_reset", audit.actionType().toString());
        assertEquals(ready.operationId(), audit.transactionId());
        assertTrue(audit.beforeValue().contains("snapshot=" + ready.snapshotId()));
        assertTrue(audit.afterValue().contains(RestartWildernessResetService.backupRelativePath(ready.snapshotId())));
        assertEquals(RestartWildernessResetService.CompletionStatus.DUPLICATE_TRANSACTION,
                RestartWildernessResetService.commitSuccess(state, moved, 3_000));
        assertEquals(auditCount + 1, state.auditCount());

        PlatformSavedData restored = roundTrip(PlatformSavedData.CODEC, state);
        assertEquals(50, restored.economyBalance(ready.actorId()).orElseThrow());
        assertTrue(restored.hasTransaction(ready.operationId(), 3_000));
    }

    @Test
    void restartRecoversWhenTheDirectoryMovedBeforeThePhaseReceiptWasFlushed() throws Exception {
        Path source = wildernessPath();
        Path backup = backupPath(id(41));
        Files.createDirectories(source.resolve("region"));
        Files.writeString(source.resolve("region/sentinel.txt"), "recover me");
        Files.createDirectories(backup.getParent());
        Files.move(source, backup, StandardCopyOption.ATOMIC_MOVE);
        RestartWildernessResetService.Operation ready = readyOperation(1_000, id(40), id(41), id(42), 0);
        RestartWildernessResetService.writePending(worldRoot, ready, false);

        RestartWildernessResetService.ApplyResult recovered = RestartWildernessResetService.applyPendingReset(worldRoot);

        assertEquals(RestartWildernessResetService.ApplyStatus.RECOVERED_AFTER_MOVE, recovered.status());
        assertEquals(RestartWildernessResetService.Phase.MOVED, recovered.operation().orElseThrow().phase());
        assertEquals("recover me", Files.readString(backup.resolve("region/sentinel.txt")));
    }

    @Test
    void conflictingBackupFailsWithoutDeletingEitherDirectory() throws Exception {
        Path source = wildernessPath();
        Path backup = backupPath(id(51));
        Files.createDirectories(source);
        Files.createDirectories(backup);
        Files.writeString(source.resolve("source.txt"), "source");
        Files.writeString(backup.resolve("backup.txt"), "backup");
        RestartWildernessResetService.Operation ready = readyOperation(1_000, id(50), id(51), id(52), 0);
        RestartWildernessResetService.writePending(worldRoot, ready, false);

        RestartWildernessResetService.ApplyResult failed = RestartWildernessResetService.applyPendingReset(worldRoot);

        assertEquals(RestartWildernessResetService.ApplyStatus.FAILED, failed.status());
        RestartWildernessResetService.Operation receipt = failed.operation().orElseThrow();
        assertEquals(RestartWildernessResetService.Phase.FAILED, receipt.phase());
        assertEquals("wilderness_backup_conflict", receipt.failureCode());
        assertEquals("source", Files.readString(source.resolve("source.txt")));
        assertEquals("backup", Files.readString(backup.resolve("backup.txt")));
    }

    @Test
    void corruptPendingReceiptBlocksASecondResetInsteadOfOverwritingEvidence() throws Exception {
        Path pending = worldRoot.resolve("rovenfall/wilderness-resets/pending.nbt");
        Files.createDirectories(pending.getParent());
        Files.writeString(pending, "not nbt");
        PlatformSavedData state = stateWithRole(id(60), AdminRole.OWNER);

        assertEquals(RestartWildernessResetService.Status.STORAGE_ERROR, RestartWildernessResetService.precheck(
                state, worldRoot, id(60), false, "do not overwrite", 2_000, id(61), id(62)));
        assertThrows(RestartWildernessResetService.StorageException.class,
                () -> RestartWildernessResetService.readPending(worldRoot));
        assertEquals("not nbt", Files.readString(pending));
    }

    private PlatformSavedData stateWithRole(UUID actor, AdminRole role) {
        PlatformSavedData state = new PlatformSavedData();
        assertEquals(AdministrationService.RoleChangeStatus.SUCCESS, AdministrationService.changeRole(
                state,
                AdministrationService.SYSTEM_ACTOR,
                true,
                actor,
                role.getSerializedName(),
                "bootstrap",
                100,
                UUID.randomUUID()).status());
        return state;
    }

    private static RestartWildernessResetService.Operation readyOperation(
            long timestamp, UUID operationId, UUID snapshotId, UUID actorId, int evacuated) {
        return new RestartWildernessResetService.Operation(
                1,
                operationId,
                snapshotId,
                actorId,
                "season reset",
                timestamp,
                -1,
                evacuated,
                RestartWildernessResetService.Phase.READY,
                "");
    }

    private Path wildernessPath() {
        return worldRoot.resolve("dimensions/rovenfall/wilderness");
    }

    private Path backupPath(UUID snapshotId) {
        return worldRoot.resolve("rovenfall/snapshots/wilderness")
                .resolve(snapshotId.toString())
                .resolve("dimension");
    }

    private static UUID id(long value) {
        return new UUID(0L, value);
    }
}
