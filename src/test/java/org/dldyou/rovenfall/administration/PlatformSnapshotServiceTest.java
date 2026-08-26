package org.dldyou.rovenfall.administration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.dldyou.rovenfall.claims.ClaimKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class PlatformSnapshotServiceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void restoreReturnsClaimsToSnapshotWhileRetainingLaterReceiptEvidence() throws Exception {
        PlatformSavedData state = new PlatformSavedData();
        UUID owner = id(940);
        bootstrap(state, owner, AdminRole.OWNER);
        EconomyService.award(state, owner, 5_000, "claim seed", 1_100, id(941), 0, Long.MAX_VALUE);
        BlockPos firstPosition = new BlockPos(16, 70, 16);
        UUID firstPurchaseId = id(942);
        assertEquals(ClaimPurchaseService.Status.SUCCESS, ClaimPurchaseService.purchase(
                state, owner, Level.OVERWORLD, Level.OVERWORLD, firstPosition,
                ignored -> true, ignored -> false, 1_000, 0, 4, 1_200, firstPurchaseId).status());
        ClaimKey firstKey = ClaimKey.at(Level.OVERWORLD, firstPosition);

        PlatformSnapshotStore store = store("claim-restore");
        UUID snapshotId = id(943);
        assertEquals(AdministrationService.SnapshotCreateStatus.SUCCESS, AdministrationService.createSnapshot(
                state, store, owner, false, "before second claim", 1_300, id(944), snapshotId).status());

        BlockPos secondPosition = new BlockPos(32, 70, 32);
        UUID secondPurchaseId = id(945);
        assertEquals(ClaimPurchaseService.Status.SUCCESS, ClaimPurchaseService.purchase(
                state, owner, Level.OVERWORLD, Level.OVERWORLD, secondPosition,
                ignored -> true, ignored -> false, 1_000, 0, 4, 1_400, secondPurchaseId).status());
        ClaimKey secondKey = ClaimKey.at(Level.OVERWORLD, secondPosition);
        assertEquals(2, state.claimCount(owner));

        UUID restoreId = id(946);
        assertEquals(AdministrationService.SnapshotRestoreStatus.SUCCESS, AdministrationService.restoreSnapshot(
                state, store, owner, false, snapshotId, "restore claim state", 2_000,
                restoreId, id(947)).status());

        assertTrue(state.claim(firstKey).isPresent());
        assertTrue(state.claim(secondKey).isEmpty());
        assertEquals(1, state.claimCount(owner));
        assertEquals(4_000, state.economyBalance(owner).orElseThrow());
        assertEquals(Optional.of(restoreId),
                state.economyReceipt(secondPurchaseId).orElseThrow().invalidatedByRestore());
        assertEquals(Optional.of(secondKey), state.economyReceipt(secondPurchaseId).orElseThrow().claim());
    }

    @Test
    void restoreRetainsPostSnapshotReceiptsAsInvalidatedEvidence() throws Exception {
        PlatformSavedData state = new PlatformSavedData();
        UUID owner = id(900);
        bootstrap(state, owner, AdminRole.OWNER);
        EconomyService.award(state, owner, 100, "seed", 1_100, id(901), 0, Long.MAX_VALUE);
        PlatformSnapshotStore store = store("receipt-restore");
        UUID snapshotId = id(902);
        AdministrationService.createSnapshot(
                state, store, owner, false, "before adjustment", 1_200, id(903), snapshotId);

        UUID grantId = id(904);
        EconomyService.adminGrant(
                state, owner, false, owner, 25, "temporary grant", 1_300, grantId, 0, Long.MAX_VALUE);
        UUID reversalId = id(905);
        assertEquals(EconomyReversalService.Status.SUCCESS, EconomyReversalService.reverse(
                state, owner, NonNullList.withSize(36, ItemStack.EMPTY), owner, false, grantId,
                EconomyTransactionReceipt.CompensationDecision.NONE, "undo temporary grant", 1_400,
                reversalId, Long.MAX_VALUE).status());

        UUID restoreId = id(906);
        assertEquals(AdministrationService.SnapshotRestoreStatus.SUCCESS, AdministrationService.restoreSnapshot(
                state, store, owner, false, snapshotId, "restore authoritative state", 2_000,
                restoreId, id(907)).status());

        assertEquals(100, state.economyBalance(owner).orElseThrow());
        assertEquals(Optional.of(restoreId), state.economyReceipt(grantId).orElseThrow().invalidatedByRestore());
        assertEquals(Optional.of(restoreId), state.economyReceipt(reversalId).orElseThrow().invalidatedByRestore());
        assertEquals(EconomyReversalService.Status.ORIGINAL_NOT_REVERSIBLE, EconomyReversalService.reverse(
                state, owner, NonNullList.withSize(36, ItemStack.EMPTY), owner, false, grantId,
                EconomyTransactionReceipt.CompensationDecision.NONE, "must remain invalid", 3_000,
                id(908), Long.MAX_VALUE).status());
    }

    @Test
    void restoreKeepsPostSnapshotReversalAsNonAuthoritativeEvidence() throws Exception {
        PlatformSavedData state = new PlatformSavedData();
        UUID owner = id(920);
        bootstrap(state, owner, AdminRole.OWNER);
        UUID originalId = id(921);
        assertEquals(EconomyService.TransactionStatus.SUCCESS,
                EconomyService.award(state, owner, 100, "snapshot original", 1_100, originalId, 0, Long.MAX_VALUE)
                        .status());
        PlatformSnapshotStore store = store("post-snapshot-reversal");
        UUID snapshotId = id(922);
        AdministrationService.createSnapshot(
                state, store, owner, false, "before reversal", 1_200, id(923), snapshotId);

        UUID oldReversalId = id(924);
        assertEquals(EconomyReversalService.Status.SUCCESS, EconomyReversalService.reverse(
                state, owner, NonNullList.withSize(36, ItemStack.EMPTY), owner, false, originalId,
                EconomyTransactionReceipt.CompensationDecision.NONE, "temporary reversal", 1_300,
                oldReversalId, Long.MAX_VALUE).status());
        UUID restoreId = id(925);
        assertEquals(AdministrationService.SnapshotRestoreStatus.SUCCESS, AdministrationService.restoreSnapshot(
                state, store, owner, false, snapshotId, "restore original authority", 2_000,
                restoreId, id(926)).status());

        assertEquals(100, state.economyBalance(owner).orElseThrow());
        assertTrue(state.economyReceipt(originalId).orElseThrow().reversedBy().isEmpty());
        assertEquals(Optional.of(restoreId),
                state.economyReceipt(oldReversalId).orElseThrow().invalidatedByRestore());
        PlatformSavedData persisted = PlatformSavedData.CODEC.parse(
                net.minecraft.nbt.NbtOps.INSTANCE,
                PlatformSavedData.CODEC.encodeStart(net.minecraft.nbt.NbtOps.INSTANCE, state).getOrThrow())
                .getOrThrow();

        UUID authoritativeReversalId = id(927);
        assertEquals(EconomyReversalService.Status.SUCCESS, EconomyReversalService.reverse(
                persisted, owner, NonNullList.withSize(36, ItemStack.EMPTY), owner, false, originalId,
                EconomyTransactionReceipt.CompensationDecision.NONE, "authoritative reversal", 3_000,
                authoritativeReversalId, Long.MAX_VALUE).status());
        assertEquals(Optional.of(authoritativeReversalId),
                persisted.economyReceipt(originalId).orElseThrow().reversedBy());
        PlatformSavedData.CODEC.parse(
                net.minecraft.nbt.NbtOps.INSTANCE,
                PlatformSavedData.CODEC.encodeStart(net.minecraft.nbt.NbtOps.INSTANCE, persisted).getOrThrow())
                .getOrThrow();
    }

    @Test
    void ownerCreatesSnapshotThatRoundTripsThroughCompressedNbt() throws Exception {
        PlatformSavedData state = new PlatformSavedData();
        UUID owner = id(1);
        bootstrap(state, owner, AdminRole.OWNER);
        PlayerRecordService.observeLogin(state, owner, 1_500);
        UUID economyTransaction = id(100);
        EconomyService.award(state, owner, 50, "snapshot balance", 1_750, economyTransaction, 0, 100);
        PlatformSnapshotStore store = store("create");
        UUID snapshotId = id(101);

        var result = AdministrationService.createSnapshot(
                state, store, owner, false, "manual backup", 2_000, id(201), snapshotId);

        assertEquals(AdministrationService.SnapshotCreateStatus.SUCCESS, result.status());
        assertTrue(Files.isRegularFile(snapshotPath("create", snapshotId)));
        PlatformSavedData loaded = store.read(snapshotId);
        assertEquals(AdminRole.OWNER, loaded.roleOf(owner).orElseThrow());
        assertEquals(new PlayerRecord(1_500, 1_500), loaded.playerRecord(owner).orElseThrow());
        assertEquals(50, loaded.economyBalance(owner).orElseThrow());
        assertTrue(loaded.hasEconomyTransaction(economyTransaction, 2_000));
        assertEquals(2, loaded.auditCount());
        assertEquals(3, state.auditCount());
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
        PlayerRecordService.observeLogin(state, target, 2_500);
        UUID sourceEconomyTransaction = id(300);
        EconomyService.award(state, target, 10, "source balance", 2_750, sourceEconomyTransaction, 0, 100);
        PlatformSnapshotStore store = store("restore");
        UUID sourceSnapshotId = id(120);
        store.write(sourceSnapshotId, state);

        change(state, owner, target, AdminRole.MODERATOR, 3_000, 302);
        PlayerRecordService.observeLogin(state, target, 3_500);
        UUID liveEconomyTransaction = id(304);
        EconomyService.award(state, target, 10, "live balance", 3_750, liveEconomyTransaction, 0, 100);
        int auditCount = state.auditCount();
        UUID safetySnapshotId = id(121);
        UUID transactionId = id(303);

        var result = AdministrationService.restoreSnapshot(
                state, store, owner, false, sourceSnapshotId, "undo role change",
                4_000, transactionId, safetySnapshotId);

        assertEquals(AdministrationService.SnapshotRestoreStatus.SUCCESS, result.status());
        assertEquals(AdminRole.VIEWER, state.roleOf(target).orElseThrow());
        assertEquals(new PlayerRecord(2_500, 2_500), state.playerRecord(target).orElseThrow());
        assertEquals(10, state.economyBalance(target).orElseThrow());
        assertTrue(state.hasEconomyTransaction(sourceEconomyTransaction, 4_000));
        assertTrue(state.hasEconomyTransaction(liveEconomyTransaction, 4_000));
        assertTrue(state.hasTransaction(transactionId, 4_000));
        assertEquals(auditCount + 1, state.auditCount());
        PlatformSavedData safetySnapshot = store.read(safetySnapshotId);
        assertEquals(AdminRole.MODERATOR, safetySnapshot.roleOf(target).orElseThrow());
        assertEquals(new PlayerRecord(2_500, 3_500),
                safetySnapshot.playerRecord(target).orElseThrow());
        assertEquals(20, safetySnapshot.economyBalance(target).orElseThrow());
        assertTrue(safetySnapshot.hasEconomyTransaction(liveEconomyTransaction, 4_000));
        assertAudit(state, "rovenfall:platform_snapshot_restore",
                "snapshot:" + safetySnapshotId, "snapshot:" + sourceSnapshotId, transactionId);

        change(state, owner, target, AdminRole.MODERATOR, 5_000, 305);
        int retryAuditCount = state.auditCount();
        UUID retrySafetySnapshotId = id(122);
        var retry = AdministrationService.restoreSnapshot(
                state, store, owner, false, sourceSnapshotId, "retry restore",
                6_000, transactionId, retrySafetySnapshotId);
        assertEquals(AdministrationService.SnapshotRestoreStatus.DUPLICATE_TRANSACTION, retry.status());
        assertEquals(AdminRole.MODERATOR, state.roleOf(target).orElseThrow());
        assertEquals(retryAuditCount, state.auditCount());
        assertFalse(Files.exists(snapshotPath("restore", retrySafetySnapshotId)));
    }

    @Test
    void restoreRejectsZeroTransactionBeforeWritingSafetySnapshot() throws Exception {
        PlatformSavedData state = new PlatformSavedData();
        UUID owner = id(25);
        bootstrap(state, owner, AdminRole.OWNER);
        PlatformSnapshotStore store = store("invalid-transaction");
        UUID sourceSnapshotId = id(125);
        UUID safetySnapshotId = id(126);
        store.write(sourceSnapshotId, state);
        int auditCount = state.auditCount();

        var result = AdministrationService.restoreSnapshot(
                state, store, owner, false, sourceSnapshotId, "invalid transaction",
                2_000, new UUID(0, 0), safetySnapshotId);

        assertEquals(AdministrationService.SnapshotRestoreStatus.INVALID_TRANSACTION, result.status());
        assertEquals(auditCount + 1, state.auditCount());
        assertFalse(Files.exists(snapshotPath("invalid-transaction", safetySnapshotId)));
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
