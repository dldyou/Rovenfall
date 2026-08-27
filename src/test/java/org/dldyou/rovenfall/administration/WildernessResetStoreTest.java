package org.dldyou.rovenfall.administration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
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
