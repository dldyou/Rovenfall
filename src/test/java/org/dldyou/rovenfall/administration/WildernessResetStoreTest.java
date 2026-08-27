package org.dldyou.rovenfall.administration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.world.level.Level;
import org.dldyou.rovenfall.rpg.ActivityWorldSavedData;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class WildernessResetStoreTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void resetAndRestoreKeepVerifiedRecoveryEvidence() throws Exception {
        Path wilderness = temporaryDirectory.resolve("dimensions/rovenfall/wilderness");
        Files.createDirectories(wilderness.resolve("region"));
        Files.writeString(wilderness.resolve("region/r.0.0.mca"), "original-wilderness");
        WildernessResetStore store = new WildernessResetStore(temporaryDirectory.resolve("operations"));
        UUID snapshotId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        var snapshot = store.createSnapshot(snapshotId, wilderness);
        var reset = operation(
                WildernessResetState.Kind.RESET, snapshotId, snapshotId, actorId, snapshot, snapshot);

        store.prepareReset(reset);
        store.writePending(reset);
        var appliedReset = store.applyPending(wilderness).orElseThrow();

        assertTrue(appliedReset.succeeded());
        assertFalse(Files.exists(wilderness.resolve("region/r.0.0.mca")));
        assertEquals(snapshot, store.snapshotEvidence(snapshotId));
        assertEquals(reset, store.lifecycleResult().orElseThrow().operation());
        store.acknowledgeLifecycleResult(true);

        UUID recoveryId = UUID.randomUUID();
        var emptyRecovery = store.createSnapshot(recoveryId, wilderness);
        var restore = operation(
                WildernessResetState.Kind.RESTORE, snapshotId, recoveryId, actorId, snapshot, emptyRecovery);
        store.prepareRestore(restore);
        store.writePending(restore);
        var appliedRestore = store.applyPending(wilderness).orElseThrow();

        assertTrue(appliedRestore.succeeded());
        assertEquals("original-wilderness", Files.readString(wilderness.resolve("region/r.0.0.mca")));
        assertEquals(restore, store.lifecycleResult().orElseThrow().operation());
    }

    @Test
    void snapshotEvidenceIncludesRestorableActivityMarkers() throws Exception {
        Path wilderness = temporaryDirectory.resolve("dimensions/rovenfall/wilderness");
        Files.createDirectories(wilderness);
        Files.writeString(wilderness.resolve("world.txt"), "world");
        WildernessResetStore store = new WildernessResetStore(temporaryDirectory.resolve("operations"));
        UUID snapshotId = UUID.randomUUID();
        var markers = new ActivityWorldSavedData.DimensionSnapshot(
                Level.NETHER, Set.of(new net.minecraft.core.BlockPos(4, 32, 8).asLong()));

        var evidence = store.createSnapshot(snapshotId, wilderness, markers);

        assertEquals(markers, store.activityMarkers(snapshotId));
        assertEquals(evidence, store.snapshotEvidence(snapshotId));
        assertEquals(2L, evidence.fileCount());
    }

    @Test
    void missingStagingRollsBackWithoutReplacingAuthoritativeWorld() throws Exception {
        Path wilderness = temporaryDirectory.resolve("dimensions/rovenfall/wilderness");
        Files.createDirectories(wilderness);
        Path marker = wilderness.resolve("authoritative.txt");
        Files.writeString(marker, "keep");
        WildernessResetStore store = new WildernessResetStore(temporaryDirectory.resolve("operations"));
        UUID snapshotId = UUID.randomUUID();
        var snapshot = store.createSnapshot(snapshotId, wilderness);
        var operation = operation(
                WildernessResetState.Kind.RESET, snapshotId, snapshotId, UUID.randomUUID(), snapshot, snapshot);
        store.prepareReset(operation);
        store.writePending(operation);
        store.cleanupStaging(operation.transactionId());

        var failed = store.applyPending(wilderness).orElseThrow();

        assertFalse(failed.succeeded());
        assertEquals("keep", Files.readString(marker));
        assertTrue(store.lifecycleResult().orElseThrow().detail().contains("failed"));
    }

    @Test
    void tamperedRestoreStagingFailsBeforeReplacingAuthoritativeWorld() throws Exception {
        Path wilderness = temporaryDirectory.resolve("dimensions/rovenfall/wilderness");
        Files.createDirectories(wilderness);
        Path authoritative = wilderness.resolve("authoritative.txt");
        Files.writeString(authoritative, "current");
        Path operations = temporaryDirectory.resolve("operations");
        WildernessResetStore store = new WildernessResetStore(operations);
        UUID targetId = UUID.randomUUID();
        var target = store.createSnapshot(targetId, wilderness);
        UUID recoveryId = UUID.randomUUID();
        var recovery = store.createSnapshot(recoveryId, wilderness);
        var restore = operation(
                WildernessResetState.Kind.RESTORE, targetId, recoveryId, UUID.randomUUID(), target, recovery);
        store.prepareRestore(restore);
        store.writePending(restore);
        Files.writeString(
                operations.resolve("staging").resolve(restore.transactionId().toString())
                        .resolve("world/injected.txt"),
                "tampered");

        var result = store.applyPending(wilderness).orElseThrow();

        assertFalse(result.succeeded());
        assertEquals("current", Files.readString(authoritative));
        assertFalse(Files.exists(wilderness.resolve("injected.txt")));
    }

    @Test
    void missingRecoverySnapshotPreventsRestoreFromBeingArmed() throws Exception {
        Path wilderness = temporaryDirectory.resolve("dimensions/rovenfall/wilderness");
        Files.createDirectories(wilderness);
        Files.writeString(wilderness.resolve("world.txt"), "current");
        WildernessResetStore store = new WildernessResetStore(temporaryDirectory.resolve("operations"));
        UUID targetId = UUID.randomUUID();
        var target = store.createSnapshot(targetId, wilderness);
        UUID recoveryId = UUID.randomUUID();
        var recovery = store.createSnapshot(recoveryId, wilderness);
        var restore = operation(
                WildernessResetState.Kind.RESTORE, targetId, recoveryId, UUID.randomUUID(), target, recovery);
        store.prepareRestore(restore);
        store.discardSnapshot(recoveryId);

        assertThrows(WildernessResetStore.StoreException.class, () -> store.writePending(restore));
        assertFalse(store.hasPending());
    }

    @Test
    void lifecycleResultAndPendingManifestAreCrashIdempotent() throws Exception {
        Path wilderness = temporaryDirectory.resolve("dimensions/rovenfall/wilderness");
        Files.createDirectories(wilderness);
        Files.writeString(wilderness.resolve("authoritative.txt"), "old");
        Path operations = temporaryDirectory.resolve("operations");
        WildernessResetStore store = new WildernessResetStore(operations);
        UUID snapshotId = UUID.randomUUID();
        var snapshot = store.createSnapshot(snapshotId, wilderness);
        var operation = operation(
                WildernessResetState.Kind.RESET, snapshotId, snapshotId, UUID.randomUUID(), snapshot, snapshot);
        store.prepareReset(operation);
        store.writePending(operation);
        assertTrue(store.applyPending(wilderness).orElseThrow().succeeded());

        Files.copy(operations.resolve("applied.nbt"), operations.resolve("pending.nbt"),
                StandardCopyOption.COPY_ATTRIBUTES);
        var retried = store.applyPending(wilderness).orElseThrow();

        assertEquals(operation, retried.operation());
        assertTrue(retried.succeeded());
        assertFalse(Files.exists(operations.resolve("pending.nbt")));
        assertFalse(Files.exists(wilderness.resolve("authoritative.txt")));
    }

    @Test
    void startupCleanupRemovesOnlyUnrecordedUuidSnapshots() throws Exception {
        Path wilderness = temporaryDirectory.resolve("dimensions/rovenfall/wilderness");
        Files.createDirectories(wilderness);
        Files.writeString(wilderness.resolve("marker.txt"), "world");
        Path operations = temporaryDirectory.resolve("operations");
        WildernessResetStore store = new WildernessResetStore(operations);
        UUID retained = UUID.randomUUID();
        UUID orphan = UUID.randomUUID();
        store.createSnapshot(retained, wilderness);
        store.createSnapshot(orphan, wilderness);

        store.discardUnrecordedSnapshots(Set.of(retained));

        assertEquals("world", Files.readString(
                operations.resolve("snapshots").resolve(retained.toString()).resolve("world/marker.txt")));
        assertFalse(Files.exists(operations.resolve("snapshots").resolve(orphan.toString())));
    }

    @Test
    void corruptOrConflictingLifecycleManifestsFailClosed() throws Exception {
        Path operations = temporaryDirectory.resolve("operations");
        Files.createDirectories(operations);
        WildernessResetStore store = new WildernessResetStore(operations);
        Files.writeString(operations.resolve("applied.nbt"), "not-nbt");
        assertThrows(WildernessResetStore.StoreException.class, store::lifecycleResult);

        Files.delete(operations.resolve("applied.nbt"));
        Files.writeString(operations.resolve("applied.nbt"), "not-nbt");
        Files.writeString(operations.resolve("failed.nbt"), "not-nbt");
        assertThrows(WildernessResetStore.StoreException.class, store::lifecycleResult);
    }

    @Test
    void semanticallyInvalidPendingManifestFailsClosed() throws Exception {
        Path wilderness = temporaryDirectory.resolve("dimensions/rovenfall/wilderness");
        Files.createDirectories(wilderness);
        Path operations = temporaryDirectory.resolve("operations");
        Files.createDirectories(operations);
        WildernessResetStore store = new WildernessResetStore(operations);
        UUID snapshotId = UUID.randomUUID();
        var snapshot = store.createSnapshot(snapshotId, wilderness);
        var operation = operation(
                WildernessResetState.Kind.RESET, snapshotId, snapshotId, UUID.randomUUID(), snapshot, snapshot);
        CompoundTag manifest = (CompoundTag) WildernessResetState.Operation.CODEC
                .encodeStart(NbtOps.INSTANCE, operation).getOrThrow();
        manifest.putLong("file_count", -1L);
        NbtIo.writeCompressed(manifest, operations.resolve("pending.nbt"));

        assertThrows(WildernessResetStore.StoreException.class, () -> store.applyPending(wilderness));
    }

    @Test
    void inconsistentResetRecoveryEvidenceFailsManifestValidation() throws Exception {
        Path wilderness = temporaryDirectory.resolve("dimensions/rovenfall/wilderness");
        Files.createDirectories(wilderness);
        Path operations = temporaryDirectory.resolve("operations");
        Files.createDirectories(operations);
        WildernessResetStore store = new WildernessResetStore(operations);
        UUID snapshotId = UUID.randomUUID();
        var snapshot = store.createSnapshot(snapshotId, wilderness);
        var operation = operation(
                WildernessResetState.Kind.RESET, snapshotId, snapshotId, UUID.randomUUID(), snapshot, snapshot);
        CompoundTag manifest = (CompoundTag) WildernessResetState.Operation.CODEC
                .encodeStart(NbtOps.INSTANCE, operation).getOrThrow();
        manifest.putLong("recovery_file_count", snapshot.fileCount() + 1L);
        NbtIo.writeCompressed(manifest, operations.resolve("pending.nbt"));

        assertThrows(WildernessResetStore.StoreException.class, () -> store.applyPending(wilderness));
    }

    @Test
    void symlinkedWorldAncestorIsRejectedBeforeSnapshotIO() throws Exception {
        Path outside = temporaryDirectory.resolve("outside");
        Files.createDirectories(outside.resolve("wilderness"));
        Path linkedDimensions = temporaryDirectory.resolve("dimensions");
        try {
            if (System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("windows")) {
                Process junction = new ProcessBuilder(
                        System.getenv().getOrDefault("ComSpec", "cmd.exe"), "/c", "mklink", "/J",
                        linkedDimensions.toString(), outside.toString()).start();
                int exitCode = junction.waitFor();
                Assumptions.assumeTrue(exitCode == 0, "Windows junction creation failed");
            } else {
                Files.createSymbolicLink(linkedDimensions, outside);
            }
        } catch (IOException | UnsupportedOperationException exception) {
            Assumptions.assumeTrue(false, "Symbolic links are unavailable: " + exception.getMessage());
        }
        WildernessResetStore store = new WildernessResetStore(temporaryDirectory.resolve("operations"));

        assertThrows(WildernessResetStore.StoreException.class,
                () -> store.createSnapshot(UUID.randomUUID(), linkedDimensions.resolve("wilderness")));
    }

    private static WildernessResetState.Operation operation(
            WildernessResetState.Kind kind,
            UUID snapshotId,
            UUID recoverySnapshotId,
            UUID actorId,
            WildernessResetStore.SnapshotEvidence target,
            WildernessResetStore.SnapshotEvidence recovery) {
        return new WildernessResetState.Operation(
                kind, UUID.randomUUID(), snapshotId, recoverySnapshotId, actorId, 1_000L, "test operation",
                target.fileCount(), target.byteCount(), target.sha256(),
                recovery.fileCount(), recovery.byteCount(), recovery.sha256());
    }
}
