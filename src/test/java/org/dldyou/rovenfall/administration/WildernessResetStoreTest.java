package org.dldyou.rovenfall.administration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.world.level.Level;
import org.dldyou.rovenfall.rpg.ActivityWorldSavedData;
import org.dldyou.rovenfall.world.WorldTopology;
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
                WorldTopology.WILDERNESS, Set.of(new BlockPos(4, 32, 8).asLong()));

        var evidence = store.createSnapshot(snapshotId, wilderness, markers);

        assertEquals(markers, store.activityMarkers(snapshotId));
        assertEquals(evidence, store.snapshotEvidence(snapshotId));
        assertEquals(2L, evidence.fileCount());
    }

    @Test
    void maximumActivityMarkerSnapshotRoundTripsBeyondOperationManifestLimit() throws Exception {
        Path wilderness = temporaryDirectory.resolve("dimensions/rovenfall/wilderness");
        Files.createDirectories(wilderness);
        Path operations = temporaryDirectory.resolve("operations");
        WildernessResetStore store = new WildernessResetStore(operations);
        Set<Long> positions = new HashSet<>(ActivityWorldSavedData.MAX_SYNTHETIC_RESOURCES * 2);
        for (int index = 0; index < ActivityWorldSavedData.MAX_SYNTHETIC_RESOURCES; index++) {
            positions.add(index * 0x9E3779B97F4A7C15L);
        }
        UUID snapshotId = UUID.randomUUID();
        var markers = new ActivityWorldSavedData.DimensionSnapshot(WorldTopology.WILDERNESS, positions);

        store.createSnapshot(snapshotId, wilderness, markers);

        Path markerFile = operations.resolve("snapshots").resolve(snapshotId.toString())
                .resolve("activity-markers.nbt");
        assertTrue(Files.size(markerFile) > 1024L * 1024L);
        assertEquals(markers, store.activityMarkers(snapshotId));
    }

    @Test
    void legacyWorldOnlySnapshotMigratesIdempotentlyAfterHashValidation() throws Exception {
        Path wilderness = temporaryDirectory.resolve("dimensions/rovenfall/wilderness");
        Files.createDirectories(wilderness);
        Files.writeString(wilderness.resolve("legacy.txt"), "legacy-world");
        Path operations = temporaryDirectory.resolve("operations");
        WildernessResetStore store = new WildernessResetStore(operations);
        UUID snapshotId = UUID.randomUUID();
        store.createSnapshot(snapshotId, wilderness);
        Path snapshotDirectory = operations.resolve("snapshots").resolve(snapshotId.toString());
        Files.delete(snapshotDirectory.resolve("activity-markers.nbt"));
        var legacyEvidence = evidence(snapshotDirectory.resolve("world"));

        var migrated = store.validateOrMigrateSnapshot(snapshotId, legacyEvidence);

        assertEquals(ActivityWorldSavedData.DimensionSnapshot.empty(WorldTopology.WILDERNESS),
                store.activityMarkers(snapshotId));
        assertEquals(migrated, store.snapshotEvidence(snapshotId));
        assertEquals(migrated, store.validateOrMigrateSnapshot(snapshotId, legacyEvidence));
    }

    @Test
    void pendingLegacyRestoreMigratesBothSnapshotsBeforeSwap() throws Exception {
        Path wilderness = temporaryDirectory.resolve("dimensions/rovenfall/wilderness");
        Files.createDirectories(wilderness);
        Path worldFile = wilderness.resolve("state.txt");
        Files.writeString(worldFile, "restore-target");
        Path operations = temporaryDirectory.resolve("operations");
        WildernessResetStore store = new WildernessResetStore(operations);
        UUID targetId = UUID.randomUUID();
        store.createSnapshot(targetId, wilderness);
        Files.writeString(worldFile, "current-world");
        UUID recoveryId = UUID.randomUUID();
        store.createSnapshot(recoveryId, wilderness);
        Path targetDirectory = operations.resolve("snapshots").resolve(targetId.toString());
        Path recoveryDirectory = operations.resolve("snapshots").resolve(recoveryId.toString());
        Files.delete(targetDirectory.resolve("activity-markers.nbt"));
        Files.delete(recoveryDirectory.resolve("activity-markers.nbt"));
        var targetEvidence = evidence(targetDirectory.resolve("world"));
        var recoveryEvidence = evidence(recoveryDirectory.resolve("world"));
        var restore = operation(
                WildernessResetState.Kind.RESTORE, targetId, recoveryId, UUID.randomUUID(),
                targetEvidence, recoveryEvidence);
        store.prepareRestore(restore);
        Files.delete(targetDirectory.resolve("activity-markers.nbt"));
        writeOperationManifest(operations.resolve("pending.nbt"), restore);

        var result = store.applyPending(wilderness).orElseThrow();

        assertTrue(result.succeeded());
        assertEquals("restore-target", Files.readString(worldFile));
        assertEquals(ActivityWorldSavedData.DimensionSnapshot.empty(WorldTopology.WILDERNESS),
                store.activityMarkers(targetId));
        assertEquals(ActivityWorldSavedData.DimensionSnapshot.empty(WorldTopology.WILDERNESS),
                store.activityMarkers(recoveryId));
    }

    @Test
    void appliedLegacyRestoreMigratesBeforeMarkerReconciliation() throws Exception {
        Path wilderness = temporaryDirectory.resolve("dimensions/rovenfall/wilderness");
        Files.createDirectories(wilderness);
        Files.writeString(wilderness.resolve("world.txt"), "legacy");
        Path operations = temporaryDirectory.resolve("operations");
        WildernessResetStore store = new WildernessResetStore(operations);
        UUID targetId = UUID.randomUUID();
        store.createSnapshot(targetId, wilderness);
        UUID recoveryId = UUID.randomUUID();
        store.createSnapshot(recoveryId, wilderness);
        Path targetDirectory = operations.resolve("snapshots").resolve(targetId.toString());
        Path recoveryDirectory = operations.resolve("snapshots").resolve(recoveryId.toString());
        Files.delete(targetDirectory.resolve("activity-markers.nbt"));
        Files.delete(recoveryDirectory.resolve("activity-markers.nbt"));
        var restore = operation(
                WildernessResetState.Kind.RESTORE, targetId, recoveryId, UUID.randomUUID(),
                evidence(targetDirectory.resolve("world")), evidence(recoveryDirectory.resolve("world")));
        writeOperationManifest(operations.resolve("applied.nbt"), restore);
        var retained = store.lifecycleResult().orElseThrow();
        ActivityWorldSavedData activityState = new ActivityWorldSavedData();
        BlockPos oldWildernessMarker = new BlockPos(5, 50, 5);
        BlockPos retainedMarker = new BlockPos(6, 60, 6);
        activityState.markSynthetic(WorldTopology.WILDERNESS, oldWildernessMarker);
        activityState.markSynthetic(Level.OVERWORLD, retainedMarker);

        WildernessResetService.reconcileActivityMarkers(activityState, store, retained.operation());

        assertFalse(activityState.consumeSynthetic(WorldTopology.WILDERNESS, oldWildernessMarker));
        assertTrue(activityState.consumeSynthetic(Level.OVERWORLD, retainedMarker));
        assertTrue(Files.isRegularFile(targetDirectory.resolve("activity-markers.nbt")));
        assertTrue(Files.isRegularFile(recoveryDirectory.resolve("activity-markers.nbt")));
    }

    @Test
    void lifecycleReconciliationRestoresAndClearsOnlyWildernessMarkers() throws Exception {
        Path wilderness = temporaryDirectory.resolve("dimensions/rovenfall/wilderness");
        Files.createDirectories(wilderness);
        WildernessResetStore store = new WildernessResetStore(temporaryDirectory.resolve("operations"));
        BlockPos oldMarker = new BlockPos(1, 20, 1);
        BlockPos restoredMarker = new BlockPos(2, 30, 2);
        BlockPos retainedMarker = new BlockPos(3, 40, 3);
        UUID targetId = UUID.randomUUID();
        var targetMarkers = new ActivityWorldSavedData.DimensionSnapshot(
                WorldTopology.WILDERNESS, Set.of(restoredMarker.asLong()));
        var target = store.createSnapshot(targetId, wilderness, targetMarkers);
        UUID recoveryId = UUID.randomUUID();
        var recovery = store.createSnapshot(recoveryId, wilderness);
        var restore = operation(
                WildernessResetState.Kind.RESTORE, targetId, recoveryId, UUID.randomUUID(), target, recovery);
        ActivityWorldSavedData activityState = new ActivityWorldSavedData();
        activityState.markSynthetic(WorldTopology.WILDERNESS, oldMarker);
        activityState.markSynthetic(Level.OVERWORLD, retainedMarker);

        WildernessResetService.reconcileActivityMarkers(activityState, store, restore);
        WildernessResetService.reconcileActivityMarkers(activityState, store, restore);

        assertFalse(activityState.consumeSynthetic(WorldTopology.WILDERNESS, oldMarker));
        assertTrue(activityState.consumeSynthetic(WorldTopology.WILDERNESS, restoredMarker));
        assertTrue(activityState.consumeSynthetic(Level.OVERWORLD, retainedMarker));

        activityState.markSynthetic(WorldTopology.WILDERNESS, oldMarker);
        UUID resetId = UUID.randomUUID();
        var resetSnapshot = store.createSnapshot(resetId, wilderness,
                activityState.snapshotDimension(WorldTopology.WILDERNESS));
        var reset = operation(
                WildernessResetState.Kind.RESET, resetId, resetId, UUID.randomUUID(),
                resetSnapshot, resetSnapshot);
        WildernessResetService.reconcileActivityMarkers(activityState, store, reset);

        assertFalse(activityState.consumeSynthetic(WorldTopology.WILDERNESS, oldMarker));
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

    private static WildernessResetStore.SnapshotEvidence evidence(Path source) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long files = 0L;
        long bytes = 0L;
        try (var paths = Files.walk(source)) {
            for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
                files++;
                digest.update(source.relativize(path).toString().replace('\\', '/')
                        .getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                try (InputStream input = Files.newInputStream(path)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = input.read(buffer)) >= 0) {
                        if (read > 0) {
                            digest.update(buffer, 0, read);
                            bytes += read;
                        }
                    }
                }
            }
        }
        return new WildernessResetStore.SnapshotEvidence(
                files, bytes, HexFormat.of().formatHex(digest.digest()));
    }

    private static void writeOperationManifest(Path target, WildernessResetState.Operation operation)
            throws IOException {
        Files.createDirectories(target.getParent());
        CompoundTag manifest = (CompoundTag) WildernessResetState.Operation.CODEC
                .encodeStart(NbtOps.INSTANCE, operation).getOrThrow();
        NbtIo.writeCompressed(manifest, target);
    }
}
