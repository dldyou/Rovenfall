package org.dldyou.rovenfall.administration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class AdministrationOperationsActionServiceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void ownerRoleIsTheOnlyOperationsRole() {
        assertTrue(AdministrationOperationsActionService.allowed(AdminRole.OWNER));
        assertFalse(AdministrationOperationsActionService.allowed(AdminRole.VIEWER));
        assertFalse(AdministrationOperationsActionService.allowed(AdminRole.MODERATOR));
        assertFalse(AdministrationOperationsActionService.allowed(AdminRole.ECONOMY_MANAGER));
        assertFalse(AdministrationOperationsActionService.allowed(AdminRole.CONTENT_MANAGER));
    }

    @Test
    void exportActionRejectsChangedSelectedAuditEntries() {
        PlatformSavedData state = new PlatformSavedData();
        AuditQuery query = AuditQuery.parse("since=0 until=100", 0, 0, true);
        PlatformSavedData.AuditSelection selection = state.selectAudit(query, AuditExportService.MAX_EXPORT_ROWS);
        var action = new AdministrationOperationsActionService.ExportAction(id(1), query, selection, "review");
        PlatformSnapshotStore store = new PlatformSnapshotStore(temporaryDirectory.resolve("snapshots"));

        assertTrue(AdministrationOperationsActionService.fresh(state, store, action));
        state.commitAudit(entry(50, id(2)));
        assertFalse(AdministrationOperationsActionService.fresh(state, store, action));
    }

    @Test
    void snapshotActionsRequireExactLiveAndValidatedTargetEvidence() throws Exception {
        PlatformSavedData state = new PlatformSavedData();
        PlatformSnapshotStore store = new PlatformSnapshotStore(temporaryDirectory.resolve("snapshots"));
        UUID snapshotId = id(10);
        store.write(snapshotId, state);
        PlatformSnapshotStore.Evidence live = store.liveEvidence(state);
        PlatformSnapshotStore.Evidence target = store.snapshotEvidence(snapshotId);
        var create = new AdministrationOperationsActionService.SnapshotCreateAction(id(11), id(12), live, "backup");
        var restore = new AdministrationOperationsActionService.SnapshotRestoreAction(
                id(13), snapshotId, id(14), live, target, "restore");

        assertTrue(AdministrationOperationsActionService.fresh(state, store, create));
        assertTrue(AdministrationOperationsActionService.fresh(state, store, restore));

        state.commitAudit(entry(50, id(15)));
        assertFalse(AdministrationOperationsActionService.fresh(state, store, create));
        assertFalse(AdministrationOperationsActionService.fresh(state, store, restore));

        PlatformSavedData unchangedLive = new PlatformSavedData();
        PlatformSnapshotStore targetStore = new PlatformSnapshotStore(temporaryDirectory.resolve("target"));
        targetStore.write(snapshotId, unchangedLive);
        PlatformSnapshotStore.Evidence unchanged = targetStore.liveEvidence(unchangedLive);
        var targetAction = new AdministrationOperationsActionService.SnapshotRestoreAction(
                id(16), snapshotId, id(17), unchanged, targetStore.snapshotEvidence(snapshotId), "restore");
        assertTrue(AdministrationOperationsActionService.fresh(unchangedLive, targetStore, targetAction));
        Files.writeString(temporaryDirectory.resolve("target").resolve(snapshotId + ".nbt"), "tampered");
        assertFalse(AdministrationOperationsActionService.fresh(unchangedLive, targetStore, targetAction));
    }

    private static AuditEntry entry(long timestamp, UUID transactionId) {
        return new AuditEntry(timestamp, id(99), Identifier.fromNamespaceAndPath("rovenfall", "test"), "test",
                Optional.empty(), Optional.empty(), "before", "after", "reason", transactionId);
    }

    private static UUID id(long value) {
        return new UUID(0L, value);
    }
}
