package org.dldyou.rovenfall.administration;

import static org.dldyou.rovenfall.PersistenceTestHarness.roundTrip;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.electronwill.nightconfig.core.CommentedConfig;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import org.junit.jupiter.api.Test;

final class EconomyServiceTest {
    @Test
    void configSpecCorrectsAnInitialBalanceAboveTheMaximumDuringLoading() {
        CommentedConfig config = CommentedConfig.inMemory();
        EconomyConfig.SPEC.correct(config);
        config.set("economy.initial_balance", 100L);
        config.set("economy.maximum_balance", 10L);

        assertFalse(EconomyConfig.SPEC.isCorrect(config));
        EconomyConfig.SPEC.correct(config);

        assertTrue(EconomyConfig.SPEC.isCorrect(config));
        assertEquals(EconomyConfig.DEFAULT_INITIAL_BALANCE, config.getLong("economy.initial_balance"));
        assertEquals(10L, config.getLong("economy.maximum_balance"));
        assertFalse(EconomyConfig.isValid(100, 10));
    }

    @Test
    void configurableInitialBalanceCreatesOnePersistentIdempotentAccount() {
        assertEquals(0, EconomyConfig.DEFAULT_INITIAL_BALANCE);
        PlatformSavedData state = new PlatformSavedData();
        UUID playerId = id(1);
        UUID transactionId = id(101);

        var created = EconomyService.createAccount(state, playerId, 25, 100, 1_000, transactionId);

        assertEquals(EconomyService.TransactionStatus.SUCCESS, created.status());
        assertEquals(25, created.balance());
        assertTrue(created.auditRecorded());
        assertEquals(25, state.economyBalance(playerId).orElseThrow());
        assertEquals(1, state.economyAccountCount());
        assertTrue(state.isDirty());
        assertAudit(state, "rovenfall:economy_account_create", playerId, "none", "25", "initial_balance", transactionId);

        int auditCount = state.auditCount();
        assertEquals(EconomyService.TransactionStatus.DUPLICATE_TRANSACTION,
                EconomyService.createAccount(state, playerId, 25, 100, 2_000, transactionId).status());
        assertEquals(EconomyService.TransactionStatus.ACCOUNT_EXISTS,
                EconomyService.createAccount(state, playerId, 25, 100, 3_000, id(102)).status());
        assertEquals(auditCount, state.auditCount());

        PlatformSavedData loaded = roundTrip(PlatformSavedData.CODEC, state);
        assertEquals(25, loaded.economyBalance(playerId).orElseThrow());
        assertEquals(EconomyService.TransactionStatus.TRANSACTION_ID_CONFLICT,
                EconomyService.award(loaded, playerId, 10, "retry", 4_000, transactionId, 25, 100).status());
        assertEquals(25, loaded.economyBalance(playerId).orElseThrow());
        UUID otherPlayer = id(2);
        assertEquals(EconomyService.TransactionStatus.TRANSACTION_ID_CONFLICT,
                EconomyService.createAccount(
                        loaded, otherPlayer, 25, 100,
                        1_001 + PlatformSavedData.ECONOMY_TRANSACTION_RETENTION_MILLIS, transactionId).status());
        assertTrue(loaded.economyBalance(otherPlayer).isEmpty());
    }

    @Test
    void onlyEconomyManagerOwnerAndNativeOwnerOverrideCanGrant() {
        for (AdminRole role : AdminRole.values()) {
            PlatformSavedData state = new PlatformSavedData();
            UUID actor = id(10 + role.ordinal());
            UUID playerId = id(20 + role.ordinal());
            bootstrap(state, actor, role);
            int auditCount = state.auditCount();

            var result = EconomyService.adminGrant(
                    state, actor, false, playerId, 10, "role test", 3_000,
                    id(200 + role.ordinal()), 0, 100);

            boolean allowed = role == AdminRole.ECONOMY_MANAGER || role == AdminRole.OWNER;
            assertEquals(allowed ? EconomyService.TransactionStatus.SUCCESS : EconomyService.TransactionStatus.UNAUTHORIZED,
                    result.status(), role.getSerializedName());
            assertEquals(allowed, state.economyBalance(playerId).isPresent(), role.getSerializedName());
            assertEquals(auditCount + 1, state.auditCount(), role.getSerializedName());
        }

        PlatformSavedData state = new PlatformSavedData();
        var result = EconomyService.adminGrant(
                state, AdministrationService.SYSTEM_ACTOR, true, id(30), 5, "console grant",
                2_000, id(230), 0, 100);
        assertEquals(EconomyService.TransactionStatus.SUCCESS, result.status());
        assertEquals(5, state.economyBalance(id(30)).orElseThrow());
    }

    @Test
    void unauthorizedDenialsAreRateLimitedWithoutCreatingAnAccount() {
        PlatformSavedData state = new PlatformSavedData();
        UUID actor = id(40);
        UUID playerId = id(41);
        bootstrap(state, actor, AdminRole.VIEWER);
        int auditCount = state.auditCount();

        var first = EconomyService.adminGrant(
                state, actor, false, playerId, 10, "denied", 3_000, id(301), 0, 100);
        var second = EconomyService.adminGrant(
                state, actor, false, playerId, 10, "denied", 3_500, id(302), 0, 100);
        var third = EconomyService.adminGrant(
                state, actor, false, playerId, 10, "denied", 4_500, id(303), 0, 100);

        assertEquals(EconomyService.TransactionStatus.UNAUTHORIZED, first.status());
        assertTrue(first.auditRecorded());
        assertFalse(second.auditRecorded());
        assertTrue(third.auditRecorded());
        assertTrue(state.economyBalance(playerId).isEmpty());
        assertEquals(auditCount + 2, state.auditCount());
        assertAudit(state, "rovenfall:economy_admin_grant_denied", playerId,
                "0", "0", "unauthorized", id(303));
    }

    @Test
    void debitIsAtomicAndSuccessfulTransactionRetriesDoNotChargeTwice() {
        PlatformSavedData state = new PlatformSavedData();
        UUID owner = id(50);
        UUID playerId = id(51);
        bootstrap(state, owner, AdminRole.OWNER);
        EconomyService.award(state, playerId, 100, "seed", 2_000, id(401), 0, 1_000);

        UUID debitId = id(402);
        var debit = EconomyService.adminDebit(
                state, owner, false, playerId, 40, "correction", 3_000, debitId, 0, 1_000);
        int auditCount = state.auditCount();
        var retry = EconomyService.adminDebit(
                state, owner, false, playerId, 40, "correction", 4_000, debitId, 0, 1_000);
        var insufficient = EconomyService.adminDebit(
                state, owner, false, playerId, 61, "too much", 5_000, id(403), 0, 1_000);

        assertEquals(EconomyService.TransactionStatus.SUCCESS, debit.status());
        assertEquals(60, debit.balance());
        assertEquals(EconomyService.TransactionStatus.DUPLICATE_TRANSACTION, retry.status());
        assertEquals(EconomyService.TransactionStatus.INSUFFICIENT_FUNDS, insufficient.status());
        assertEquals(auditCount + 1, state.auditCount());
        assertEquals(60, state.economyBalance(playerId).orElseThrow());
        assertFalse(state.hasEconomyTransaction(id(403), 5_000));
        assertAudit(state, "rovenfall:economy_admin_debit_denied", playerId,
                "60", "60", "insufficient_funds", id(403));
    }

    @Test
    void internalAwardAndDebitUseTheSameAuditedBoundary() {
        PlatformSavedData state = new PlatformSavedData();
        UUID playerId = id(60);

        var award = EconomyService.award(state, playerId, 15, "mob reward", 1_000, id(501), 10, 100);
        assertEquals(EconomyService.TransactionStatus.SUCCESS, award.status());
        assertEquals(25, award.balance());
        assertAudit(state, "rovenfall:economy_award", playerId, "10", "25", "mob reward", id(501));

        var debit = EconomyService.debit(state, playerId, 5, "claim purchase", 2_000, id(502), 10, 100);
        assertEquals(EconomyService.TransactionStatus.SUCCESS, debit.status());
        assertEquals(20, debit.balance());
        assertAudit(state, "rovenfall:economy_debit", playerId, "25", "20", "claim purchase", id(502));
    }

    @Test
    void bossRewardPreviewAndCommitUseASeparateIdempotentReceipt() {
        PlatformSavedData state = new PlatformSavedData();
        UUID playerId = id(63);
        UUID transactionId = id(503);

        assertEquals(EconomyService.TransactionStatus.SUCCESS,
                EconomyService.previewBossReward(
                        state, playerId, 25, "boss reward rovenfall:test", 1_000,
                        transactionId, 10, 100));
        assertTrue(state.economyBalance(playerId).isEmpty());
        assertEquals(EconomyService.TransactionStatus.SUCCESS,
                EconomyService.awardBossReward(
                        state, playerId, 25, "boss reward rovenfall:test", 1_000,
                        transactionId, 10, 100).status());
        assertEquals(EconomyTransactionReceipt.Kind.BOSS_REWARD,
                state.economyReceipt(transactionId).orElseThrow().kind());
        assertEquals(EconomyService.TransactionStatus.DUPLICATE_TRANSACTION,
                EconomyService.previewBossReward(
                        state, playerId, 25, "boss reward rovenfall:test", 1_001,
                        transactionId, 10, 100));
        assertEquals(35, state.economyBalance(playerId).orElseThrow());
    }

    @Test
    void invalidBoundsAmountsReasonsAndTransactionsNeverChangeBalance() {
        PlatformSavedData overflow = new PlatformSavedData();
        UUID overflowPlayer = id(70);
        EconomyService.createAccount(overflow, overflowPlayer, Long.MAX_VALUE, Long.MAX_VALUE, 1_000, id(601));
        assertFailureUnchanged(overflow, overflowPlayer, Long.MAX_VALUE, EconomyService.TransactionStatus.OVERFLOW,
                EconomyService.award(overflow, overflowPlayer, 1, "overflow", 2_000, id(602), 0, Long.MAX_VALUE));

        PlatformSavedData bounded = new PlatformSavedData();
        UUID boundedPlayer = id(71);
        EconomyService.createAccount(bounded, boundedPlayer, 90, 100, 1_000, id(603));
        assertFailureUnchanged(bounded, boundedPlayer, 90, EconomyService.TransactionStatus.MAXIMUM_EXCEEDED,
                EconomyService.award(bounded, boundedPlayer, 11, "limit", 2_000, id(604), 0, 100));
        assertFailureUnchanged(bounded, boundedPlayer, 90, EconomyService.TransactionStatus.INSUFFICIENT_FUNDS,
                EconomyService.debit(bounded, boundedPlayer, 91, "underflow", 3_500, id(605), 0, 100));
        assertFailureUnchanged(bounded, boundedPlayer, 90, EconomyService.TransactionStatus.INVALID_AMOUNT,
                EconomyService.award(bounded, boundedPlayer, 0, "zero", 5_000, id(606), 0, 100));
        assertFailureUnchanged(bounded, boundedPlayer, 90, EconomyService.TransactionStatus.INVALID_AMOUNT,
                EconomyService.award(bounded, boundedPlayer, -1, "negative", 6_500, id(607), 0, 100));
        assertFailureUnchanged(bounded, boundedPlayer, 90, EconomyService.TransactionStatus.INVALID_REASON,
                EconomyService.award(bounded, boundedPlayer, 1, " ", 8_000, id(608), 0, 100));
        assertFailureUnchanged(bounded, boundedPlayer, 90, EconomyService.TransactionStatus.INVALID_REASON,
                EconomyService.award(bounded, boundedPlayer, 1,
                        "x".repeat(AdministrationService.MAX_REASON_LENGTH + 1), 9_500, id(609), 0, 100));
        assertFailureUnchanged(bounded, boundedPlayer, 90, EconomyService.TransactionStatus.INVALID_TRANSACTION,
                EconomyService.award(bounded, boundedPlayer, 1, "bad id", 11_000, new UUID(0, 0), 0, 100));

        PlatformSavedData invalidConfiguration = new PlatformSavedData();
        assertEquals(EconomyService.TransactionStatus.INVALID_CONFIGURATION,
                EconomyService.award(invalidConfiguration, id(72), 1, "bad config", 1_000, id(610), 10, 9).status());
        assertTrue(invalidConfiguration.economyBalance(id(72)).isEmpty());
    }

    @Test
    void codecRejectsNegativeBalancesAndSchemaTwoMigratesExistingPlatformState() {
        CompoundTag invalid = new CompoundTag();
        invalid.putInt("schema_version", PlatformSavedData.CURRENT_SCHEMA_VERSION);
        CompoundTag balances = new CompoundTag();
        balances.putLong(id(80).toString(), -1);
        invalid.put("economy_balances", balances);
        assertTrue(PlatformSavedData.CODEC.parse(NbtOps.INSTANCE, invalid).error().isPresent());

        PlatformSavedData original = new PlatformSavedData();
        UUID owner = id(81);
        bootstrap(original, owner, AdminRole.OWNER);
        PlayerRecordService.observeLogin(original, owner, 2_000);
        CompoundTag versionTwo = (CompoundTag) PlatformSavedData.CODEC
                .encodeStart(NbtOps.INSTANCE, original).getOrThrow();
        versionTwo.putInt("schema_version", 2);
        versionTwo.remove("economy_balances");
        versionTwo.remove("economy_transactions");

        PlatformSavedData migrated = PlatformSavedData.CODEC.parse(NbtOps.INSTANCE, versionTwo).getOrThrow();
        assertEquals(PlatformSavedData.CURRENT_SCHEMA_VERSION, migrated.schemaVersion());
        assertTrue(migrated.isWritable());
        assertEquals(AdminRole.OWNER, migrated.roleOf(owner).orElseThrow());
        assertTrue(migrated.playerRecord(owner).isPresent());
        assertEquals(1, migrated.auditCount());
        assertEquals(0, migrated.economyAccountCount());
    }

    @Test
    void futureSchemaRetainsEconomyStateReadOnly() {
        PlatformSavedData original = new PlatformSavedData();
        UUID playerId = id(90);
        UUID transactionId = id(701);
        EconomyService.award(original, playerId, 10, "seed", 1_000, transactionId, 0, 100);
        CompoundTag future = (CompoundTag) PlatformSavedData.CODEC
                .encodeStart(NbtOps.INSTANCE, original).getOrThrow();
        future.putInt("schema_version", PlatformSavedData.CURRENT_SCHEMA_VERSION + 1);

        PlatformSavedData loaded = PlatformSavedData.CODEC.parse(NbtOps.INSTANCE, future).getOrThrow();
        assertFalse(loaded.isWritable());
        assertEquals(10, loaded.economyBalance(playerId).orElseThrow());
        assertTrue(loaded.hasEconomyTransaction(transactionId, 2_000));
        assertEquals(EconomyService.TransactionStatus.READ_ONLY_SCHEMA,
                EconomyService.award(loaded, playerId, 1, "blocked", 2_000, id(702), 0, 100).status());
        assertEquals(10, loaded.economyBalance(playerId).orElseThrow());
        assertFalse(loaded.isDirty());
    }

    private static void assertFailureUnchanged(
            PlatformSavedData state,
            UUID playerId,
            long expectedBalance,
            EconomyService.TransactionStatus expectedStatus,
            EconomyService.TransactionResult result) {
        assertEquals(expectedStatus, result.status());
        assertEquals(expectedBalance, state.economyBalance(playerId).orElseThrow());
        assertFalse(state.hasEconomyTransaction(result.transactionId(), 0));
    }

    @Test
    void transactionLedgerCodecAndRetryHorizonAreBounded() {
        Map<UUID, Long> encoded = Map.of(id(100), 1_000L, id(101), 2_000L);
        assertTrue(PlatformSavedData.boundedTransactionLedgerCodec(1)
                .encodeStart(NbtOps.INSTANCE, encoded).error().isPresent());
        assertTrue(PlatformSavedData.boundedTransactionLedgerCodec(1)
                .encodeStart(NbtOps.INSTANCE, Map.of(new UUID(0, 0), 1_000L)).error().isPresent());

        long horizon = PlatformSavedData.ECONOMY_TRANSACTION_RETENTION_MILLIS;
        Map<UUID, Long> ledger = new HashMap<>(encoded);
        long now = horizon + 1_001;
        assertFalse(PlatformSavedData.ledgerContains(ledger, id(100), now, horizon));
        assertTrue(PlatformSavedData.ledgerContains(ledger, id(101), now, horizon));
        assertTrue(PlatformSavedData.ledgerHasCapacity(ledger, id(102), now, horizon, 2));

        PlatformSavedData.trimExpiredEconomyTransactions(ledger, now, horizon);
        assertEquals(Map.of(id(101), 2_000L), ledger);
        ledger.put(id(102), now);
        assertFalse(PlatformSavedData.ledgerHasCapacity(ledger, id(103), now, horizon, 2));
    }

    @Test
    void retainedReceiptPreventsExpiredTransactionIdReuseAndPreservesEvidence() {
        PlatformSavedData state = new PlatformSavedData();
        UUID playerId = id(105);
        UUID transactionId = id(106);
        long horizon = PlatformSavedData.ECONOMY_TRANSACTION_RETENTION_MILLIS;

        assertEquals(EconomyService.TransactionStatus.SUCCESS,
                EconomyService.award(state, playerId, 1, "first", 1_000, transactionId, 0, 100).status());
        assertEquals(EconomyService.TransactionStatus.DUPLICATE_TRANSACTION,
                EconomyService.award(state, playerId, 1, "retry", 1_001 + horizon, transactionId, 0, 100).status());
        assertEquals(EconomyService.TransactionStatus.TRANSACTION_ID_CONFLICT,
                EconomyService.award(state, playerId, 2, "conflict", 1_002 + horizon, transactionId, 0, 100).status());
        assertEquals(1, state.economyBalance(playerId).orElseThrow());
        EconomyTransactionReceipt retained = state.economyReceipt(transactionId).orElseThrow();
        assertEquals(1, retained.amount());
        assertEquals(1_000, retained.timestampEpochMillis());
    }

    @Test
    void restoreLedgerMergeRejectsRecentOverflowWithoutMutatingInputs() {
        long horizon = PlatformSavedData.ECONOMY_TRANSACTION_RETENTION_MILLIS;
        long now = horizon + 10_000;
        Map<UUID, Long> current = new HashMap<>(Map.of(id(120), now - 2, id(121), now - 1));
        Map<UUID, Long> snapshot = new HashMap<>(Map.of(id(122), now));

        assertTrue(PlatformSavedData.mergeEconomyTransactions(current, snapshot, now, horizon, 2).isEmpty());
        assertEquals(Map.of(id(120), now - 2, id(121), now - 1), current);
        assertEquals(Map.of(id(122), now), snapshot);
    }

    @Test
    void restoreLedgerMergePrunesExpiredIdsAndKeepsNewestDuplicateTimestamp() {
        long horizon = PlatformSavedData.ECONOMY_TRANSACTION_RETENTION_MILLIS;
        long now = horizon + 10_000;
        Map<UUID, Long> current = Map.of(id(130), 1L, id(131), now - 2);
        Map<UUID, Long> snapshot = Map.of(id(131), now - 1, id(132), now);

        Map<UUID, Long> merged = PlatformSavedData
                .mergeEconomyTransactions(current, snapshot, now, horizon, 2)
                .orElseThrow();

        assertEquals(Map.of(id(131), now - 1, id(132), now), merged);
    }

    @Test
    void restoreLedgerPreflightPrunesThenAtomicallyReservesRestoreTransaction() {
        long horizon = PlatformSavedData.ECONOMY_TRANSACTION_RETENTION_MILLIS;
        long now = horizon + 10_000;
        UUID restoreTransaction = id(143);

        Map<UUID, Long> prepared = PlatformSavedData.mergeRestoreTransactions(
                Map.of(id(140), 1L), Map.of(id(141), now), restoreTransaction, now, horizon, 2).orElseThrow();
        assertEquals(Map.of(id(141), now, restoreTransaction, now), prepared);
        assertTrue(PlatformSavedData.mergeRestoreTransactions(
                Map.of(id(140), now), Map.of(id(141), now), restoreTransaction, now, horizon, 2).isEmpty());
    }

    @Test
    void malformedTransactionIdsUseUniqueSafeAuditEvidence() {
        PlatformSavedData state = new PlatformSavedData();
        UUID playerId = id(110);

        var nullId = EconomyService.award(state, playerId, 1, "malformed", 1_000, null, 0, 100);
        UUID firstEvidence = state.auditPage(0, 1).entries().getFirst().transactionId();
        var zeroId = EconomyService.award(
                state, playerId, 1, "malformed", 2_500, new UUID(0, 0), 0, 100);
        UUID secondEvidence = state.auditPage(0, 1).entries().getFirst().transactionId();

        assertEquals(EconomyService.TransactionStatus.INVALID_TRANSACTION, nullId.status());
        assertEquals(EconomyService.TransactionStatus.INVALID_TRANSACTION, zeroId.status());
        assertTrue(nullId.auditRecorded());
        assertTrue(zeroId.auditRecorded());
        assertFalse(firstEvidence.equals(new UUID(0, 0)));
        assertFalse(secondEvidence.equals(new UUID(0, 0)));
        assertFalse(firstEvidence.equals(secondEvidence));
        assertTrue(state.economyBalance(playerId).isEmpty());
    }

    @Test
    void loginReportsEachNonBenignFailureTypeOnlyOnce() {
        var reported = EnumSet.noneOf(EconomyService.TransactionStatus.class);

        assertFalse(EconomyService.shouldReportLoginFailure(EconomyService.TransactionStatus.SUCCESS, reported));
        assertFalse(EconomyService.shouldReportLoginFailure(
                EconomyService.TransactionStatus.ACCOUNT_EXISTS, reported));
        assertFalse(EconomyService.shouldReportLoginFailure(
                EconomyService.TransactionStatus.DUPLICATE_TRANSACTION, reported));
        assertTrue(EconomyService.shouldReportLoginFailure(
                EconomyService.TransactionStatus.INVALID_CONFIGURATION, reported));
        assertFalse(EconomyService.shouldReportLoginFailure(
                EconomyService.TransactionStatus.INVALID_CONFIGURATION, reported));
        assertTrue(EconomyService.shouldReportLoginFailure(
                EconomyService.TransactionStatus.READ_ONLY_SCHEMA, reported));
    }

    private static void bootstrap(PlatformSavedData state, UUID actor, AdminRole role) {
        AdministrationService.changeRole(
                state, AdministrationService.SYSTEM_ACTOR, true, actor, role.getSerializedName(),
                "bootstrap", 1_000, UUID.randomUUID());
    }

    private static void assertAudit(
            PlatformSavedData state,
            String action,
            UUID playerId,
            String before,
            String after,
            String reason,
            UUID transactionId) {
        AuditEntry entry = state.auditPage(0, 1).entries().getFirst();
        assertEquals(action, entry.actionType().toString());
        assertEquals(playerId.toString(), entry.target());
        assertEquals(before, entry.beforeValue());
        assertEquals(after, entry.afterValue());
        assertEquals(reason, entry.reason());
        assertEquals(transactionId, entry.transactionId());
    }

    private static UUID id(long value) {
        return new UUID(0L, value);
    }
}
