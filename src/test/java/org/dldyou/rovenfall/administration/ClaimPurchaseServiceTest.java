package org.dldyou.rovenfall.administration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.world.level.Level;
import net.minecraft.world.item.ItemStack;
import org.dldyou.rovenfall.claims.ClaimKey;
import org.junit.jupiter.api.Test;

final class ClaimPurchaseServiceTest {
    private static final BlockPos FIRST_POS = new BlockPos(31, 70, -1);
    private static final BlockPos SECOND_POS = new BlockPos(32, 70, 0);

    @Test
    void purchasesEscalatingClaimsAtomicallyAndPersistsEvidence() {
        UUID player = id(1);
        PlatformSavedData state = funded(player, 5_000, 1);

        var first = purchase(state, player, FIRST_POS, id(11), 1_000, 250, 4, 2);
        ClaimKey firstKey = new ClaimKey(Level.OVERWORLD, 1, -1);
        assertEquals(ClaimPurchaseService.Status.SUCCESS, first.status());
        assertEquals(firstKey, first.claim().orElseThrow());
        assertEquals(1_000, first.price());
        assertEquals(4_000, first.balance());
        assertEquals(player, state.claim(firstKey).orElseThrow().ownerId());
        assertEquals(1, state.claimCount(player));
        assertEquals(Optional.of(firstKey), state.economyReceipt(id(11)).orElseThrow().claim());

        var second = purchase(state, player, SECOND_POS, id(12), 1_000, 250, 4, 3);
        assertEquals(ClaimPurchaseService.Status.SUCCESS, second.status());
        assertEquals(1_250, second.price());
        assertEquals(2_750, state.economyBalance(player).orElseThrow());
        assertEquals(2, state.claimCount(player));

        PlatformSavedData loaded = roundTrip(state);
        assertEquals(player, loaded.claim(firstKey).orElseThrow().ownerId());
        assertEquals(2, loaded.claimCount(player));
        assertEquals(2_750, loaded.economyBalance(player).orElseThrow());
        assertEquals(Optional.of(firstKey), loaded.economyReceipt(id(11)).orElseThrow().claim());
        AuditEntry audit = loaded.auditPage(0, 10).entries().getFirst();
        assertEquals("rovenfall:claim_purchase", audit.actionType().toString());
        assertEquals(Optional.of(Level.OVERWORLD.identifier()), audit.dimension());
        assertEquals(Optional.of(SECOND_POS), audit.position());
    }

    @Test
    void retriesAreIdempotentAndCrossPayloadReuseIsRejected() {
        UUID player = id(2);
        PlatformSavedData state = funded(player, 3_000, 20);
        UUID transactionId = id(21);
        assertEquals(ClaimPurchaseService.Status.SUCCESS,
                purchase(state, player, FIRST_POS, transactionId, 1_000, 0, 4, 21).status());

        var retry = purchase(state, player, FIRST_POS, transactionId, 9_999, 9_999, 4, 22);
        assertEquals(ClaimPurchaseService.Status.DUPLICATE_TRANSACTION, retry.status());
        assertEquals(2_000, retry.balance());
        assertEquals(1, state.claimCount());

        var conflict = purchase(state, player, SECOND_POS, transactionId, 1_000, 0, 4, 23);
        assertEquals(ClaimPurchaseService.Status.TRANSACTION_ID_CONFLICT, conflict.status());
        assertEquals(2_000, state.economyBalance(player).orElseThrow());
        assertEquals(1, state.claimCount());
    }

    @Test
    void genericEconomyReversalCannotDetachClaimOwnershipFromItsDebit() {
        UUID player = id(9);
        PlatformSavedData state = funded(player, 3_000, 90);
        UUID purchaseId = id(91);
        assertEquals(ClaimPurchaseService.Status.SUCCESS,
                purchase(state, player, FIRST_POS, purchaseId, 1_000, 0, 4, 91).status());
        ClaimKey key = ClaimKey.at(Level.OVERWORLD, FIRST_POS);

        var reversal = EconomyReversalService.reverse(
                state, player, NonNullList.withSize(36, ItemStack.EMPTY), AdministrationService.SYSTEM_ACTOR,
                true, purchaseId, EconomyTransactionReceipt.CompensationDecision.NONE,
                "unsafe generic reversal", 2_000, id(92), Long.MAX_VALUE);

        assertEquals(EconomyReversalService.Status.ORIGINAL_NOT_REVERSIBLE, reversal.status());
        assertEquals(2_000, state.economyBalance(player).orElseThrow());
        assertEquals(player, state.claim(key).orElseThrow().ownerId());
    }

    @Test
    void rejectsWorldPolicyOwnershipCapAndFundsWithoutPartialMutation() {
        UUID player = id(3);
        PlatformSavedData state = funded(player, 3_000, 30);

        assertRejected(state, player, ClaimPurchaseService.purchase(
                state, player, Level.OVERWORLD, Level.NETHER, FIRST_POS, ignored -> true, ignored -> false,
                1_000, 0, 4, 31, id(31)), ClaimPurchaseService.Status.NOT_IN_HUB, 3_000, 0);
        assertRejected(state, player, ClaimPurchaseService.purchase(
                state, player, Level.OVERWORLD, Level.OVERWORLD, FIRST_POS, ignored -> false, ignored -> false,
                1_000, 0, 4, 1_032, id(32)), ClaimPurchaseService.Status.INELIGIBLE_CHUNK, 3_000, 0);
        assertRejected(state, player, ClaimPurchaseService.purchase(
                state, player, Level.OVERWORLD, Level.OVERWORLD, FIRST_POS, ignored -> false, ignored -> true,
                1_000, 0, 4, 2_033, id(33)), ClaimPurchaseService.Status.PROTECTED_CHUNK, 3_000, 0);

        assertEquals(ClaimPurchaseService.Status.SUCCESS,
                purchase(state, player, FIRST_POS, id(34), 1_000, 0, 1, 3_034).status());
        assertRejected(state, player,
                purchase(state, player, SECOND_POS, id(35), 1_000, 0, 1, 4_035),
                ClaimPurchaseService.Status.OWNERSHIP_CAP_REACHED, 2_000, 1);

        UUID other = id(4);
        EconomyService.award(state, other, 2_000, "test", 5_000, id(40), 0, Long.MAX_VALUE);
        assertRejected(state, other,
                purchase(state, other, FIRST_POS, id(41), 1_000, 0, 4, 5_041),
                ClaimPurchaseService.Status.ALREADY_CLAIMED, 2_000, 1);

        UUID poor = id(5);
        EconomyService.award(state, poor, 100, "test", 6_000, id(50), 0, Long.MAX_VALUE);
        assertRejected(state, poor,
                purchase(state, poor, SECOND_POS, id(51), 1_000, 0, 4, 6_051),
                ClaimPurchaseService.Status.INSUFFICIENT_FUNDS, 100, 1);

        UUID missing = id(6);
        assertRejected(state, missing,
                purchase(state, missing, SECOND_POS, id(61), 1_000, 0, 4, 7_061),
                ClaimPurchaseService.Status.ACCOUNT_NOT_FOUND, 0, 1);
    }

    @Test
    void rejectsInvalidConfigurationOverflowAndReadOnlyState() {
        UUID player = id(7);
        PlatformSavedData state = funded(player, Long.MAX_VALUE, 70);

        assertRejected(state, player,
                purchase(state, player, FIRST_POS, id(71), 0, 0, 1, 71),
                ClaimPurchaseService.Status.INVALID_CONFIGURATION, Long.MAX_VALUE, 0);
        assertEquals(ClaimPurchaseService.Status.INVALID_TRANSACTION,
                purchase(state, player, FIRST_POS, new UUID(0, 0), 1, 0, 1, 1_072).status());
        assertEquals(ClaimPurchaseService.Status.SUCCESS,
                purchase(state, player, FIRST_POS, id(73), 1, 0, 2, 2_073).status());
        assertRejected(state, player,
                purchase(state, player, SECOND_POS, id(74), Long.MAX_VALUE, 1, 2, 3_074),
                ClaimPurchaseService.Status.PRICE_OVERFLOW, Long.MAX_VALUE - 1, 1);

        CompoundTag future = encoded(state);
        future.putInt("schema_version", PlatformSavedData.CURRENT_SCHEMA_VERSION + 1);
        PlatformSavedData readOnly = PlatformSavedData.CODEC.parse(NbtOps.INSTANCE, future).getOrThrow();
        assertFalse(readOnly.isWritable());
        assertEquals(ClaimPurchaseService.Status.READ_ONLY_SCHEMA,
                purchase(readOnly, player, SECOND_POS, id(75), 1, 0, 2, 4_075).status());
        assertEquals(1, readOnly.claimCount());
        assertEquals(Long.MAX_VALUE - 1, readOnly.economyBalance(player).orElseThrow());
    }

    @Test
    void migratesSchemaFiveWithoutClaimsAndRejectsDuplicateClaimKeys() {
        UUID player = id(8);
        PlatformSavedData state = funded(player, 2_000, 80);
        assertEquals(ClaimPurchaseService.Status.SUCCESS,
                purchase(state, player, FIRST_POS, id(81), 1_000, 0, 4, 81).status());

        CompoundTag schemaFive = encoded(state);
        schemaFive.putInt("schema_version", 5);
        schemaFive.remove("claims");
        PlatformSavedData migrated = PlatformSavedData.CODEC.parse(NbtOps.INSTANCE, schemaFive).getOrThrow();
        assertTrue(migrated.isWritable());
        assertEquals(PlatformSavedData.CURRENT_SCHEMA_VERSION, migrated.schemaVersion());
        assertEquals(0, migrated.claimCount());
        assertEquals(1_000, migrated.economyBalance(player).orElseThrow());

        CompoundTag duplicate = encoded(state);
        ListTag claims = duplicate.getListOrEmpty("claims");
        claims.add(claims.getFirst().copy());
        assertTrue(PlatformSavedData.CODEC.parse(NbtOps.INSTANCE, duplicate).error().isPresent());
    }

    private static PlatformSavedData funded(UUID player, long amount, long timestamp) {
        PlatformSavedData state = new PlatformSavedData();
        assertEquals(EconomyService.TransactionStatus.SUCCESS,
                EconomyService.award(state, player, amount, "test", timestamp, id(timestamp + 1_000),
                        0, Long.MAX_VALUE).status());
        return state;
    }

    private static ClaimPurchaseService.PurchaseResult purchase(
            PlatformSavedData state,
            UUID player,
            BlockPos position,
            UUID transactionId,
            long basePrice,
            long priceIncrease,
            int cap,
            long timestamp) {
        return ClaimPurchaseService.purchase(
                state, player, Level.OVERWORLD, Level.OVERWORLD, position,
                ignored -> true, ignored -> false, basePrice, priceIncrease, cap, timestamp, transactionId);
    }

    private static void assertRejected(
            PlatformSavedData state,
            UUID player,
            ClaimPurchaseService.PurchaseResult result,
            ClaimPurchaseService.Status status,
            long balance,
            int claimCount) {
        assertEquals(status, result.status());
        assertEquals(balance, state.economyBalance(player).orElse(0L));
        assertEquals(claimCount, state.claimCount());
        AuditEntry audit = state.auditPage(0, 1).entries().getFirst();
        assertEquals("rovenfall:claim_purchase_denied", audit.actionType().toString());
    }

    private static PlatformSavedData roundTrip(PlatformSavedData state) {
        return PlatformSavedData.CODEC.parse(NbtOps.INSTANCE, encoded(state)).getOrThrow();
    }

    private static CompoundTag encoded(PlatformSavedData state) {
        return (CompoundTag) PlatformSavedData.CODEC.encodeStart(NbtOps.INSTANCE, state).getOrThrow();
    }

    private static UUID id(long value) {
        return new UUID(0, value);
    }
}
