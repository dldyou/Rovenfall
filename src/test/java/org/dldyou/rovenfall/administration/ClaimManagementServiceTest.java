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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.dldyou.rovenfall.claims.ClaimKey;
import org.dldyou.rovenfall.claims.ClaimMutationReceipt;
import org.dldyou.rovenfall.claims.ClaimRole;
import org.dldyou.rovenfall.claims.ClaimSettings;
import org.junit.jupiter.api.Test;

final class ClaimManagementServiceTest {
    private static final ClaimKey KEY = ClaimKey.at(Level.OVERWORLD, new BlockPos(16, 70, 16));
    private static final ClaimKey SECOND_KEY = ClaimKey.at(Level.OVERWORLD, new BlockPos(32, 70, 32));

    @Test
    void ownerAndManagerManageRolesAndSettingsWhileLowerRolesAreDenied() {
        UUID owner = id(1);
        UUID manager = id(2);
        UUID builder = id(3);
        UUID user = id(4);
        UUID visitor = id(5);
        PlatformSavedData state = claimed(owner, KEY, 10_000, 1);

        assertEquals(ClaimManagementService.Status.SUCCESS,
                setRole(state, owner, KEY, manager, ClaimRole.MANAGER, 10, id(10)).status());
        assertEquals(ClaimManagementService.Status.SUCCESS,
                setRole(state, manager, KEY, builder, ClaimRole.BUILDER, 1_020, id(11)).status());
        assertEquals(ClaimManagementService.Status.SUCCESS,
                setRole(state, manager, KEY, user, ClaimRole.USER, 2_030, id(12)).status());
        assertEquals(ClaimManagementService.Status.SUCCESS,
                setRole(state, manager, KEY, visitor, ClaimRole.VISITOR, 3_040, id(13)).status());

        assertDenied(setRole(state, builder, KEY, id(6), ClaimRole.USER, 4_050, id(14)));
        assertDenied(setRole(state, user, KEY, id(7), ClaimRole.USER, 5_060, id(15)));
        assertDenied(setRole(state, visitor, KEY, id(8), ClaimRole.USER, 6_070, id(16)));
        assertEquals(4, state.claim(KEY).orElseThrow().trustedRoles().size());
        assertTrue(state.auditPage(0, 50).entries().stream().anyMatch(entry ->
                entry.actionType().toString().equals("rovenfall:claim_mutation_denied")
                        && entry.beforeValue().contains("player=" + id(6))
                        && entry.beforeValue().contains("role=user")));

        ClaimSettings settings = new ClaimSettings(true, true);
        assertEquals(ClaimManagementService.Status.SUCCESS, ClaimManagementService.setSettings(
                state, manager, false, KEY, settings, "manager settings", 7_080, id(17)).status());
        assertEquals(settings, state.claim(KEY).orElseThrow().settings());
        assertEquals(ClaimManagementService.Status.SUCCESS, ClaimManagementService.removeRole(
                state, manager, false, KEY, builder, "manager remove", 8_090, id(18)).status());
        assertEquals(ClaimRole.VISITOR, state.claim(KEY).orElseThrow().roleOf(builder));

        PlatformSavedData loaded = roundTrip(state);
        assertEquals(ClaimRole.MANAGER, loaded.claim(KEY).orElseThrow().roleOf(manager));
        assertEquals(settings, loaded.claim(KEY).orElseThrow().settings());
        assertEquals(ClaimMutationReceipt.Kind.SETTINGS_SET,
                loaded.claimReceipt(id(17)).orElseThrow().kind());
    }

    @Test
    void mutationRetriesAreIdempotentAndCrossPayloadReuseConflicts() {
        UUID owner = id(20);
        UUID target = id(21);
        PlatformSavedData state = claimed(owner, KEY, 5_000, 20);
        UUID transactionId = id(22);

        assertEquals(ClaimManagementService.Status.SUCCESS,
                setRole(state, owner, KEY, target, ClaimRole.USER, 21, transactionId).status());
        assertEquals(ClaimManagementService.Status.DUPLICATE_TRANSACTION,
                setRole(state, owner, KEY, target, ClaimRole.USER, 22, transactionId).status());
        assertEquals(ClaimManagementService.Status.TRANSACTION_ID_CONFLICT,
                setRole(state, owner, KEY, target, ClaimRole.BUILDER, 1_023, transactionId).status());
        assertEquals(ClaimRole.USER, state.claim(KEY).orElseThrow().roleOf(target));

        UUID noChangeId = id(23);
        assertEquals(ClaimManagementService.Status.NO_CHANGE,
                setRole(state, owner, KEY, target, ClaimRole.USER, 2_100, noChangeId).status());
        assertEquals(ClaimManagementService.Status.SUCCESS,
                setRole(state, owner, KEY, target, ClaimRole.BUILDER, 3_200, id(24)).status());
        assertEquals(ClaimManagementService.Status.DUPLICATE_TRANSACTION,
                setRole(state, owner, KEY, target, ClaimRole.USER, 4_300, noChangeId).status());
        assertEquals(ClaimRole.BUILDER, state.claim(KEY).orElseThrow().roleOf(target));
        assertTrue(state.auditPage(0, 50).entries().stream().anyMatch(entry ->
                entry.afterValue().contains("player=" + target)
                        && entry.afterValue().contains("role=builder")));
    }

    @Test
    void moderatorAndOwnerAdminsCanMutateButViewerCannot() {
        UUID owner = id(30);
        UUID moderator = id(31);
        UUID platformOwner = id(32);
        UUID viewer = id(33);
        UUID target = id(34);
        PlatformSavedData state = claimed(owner, KEY, 5_000, 30);
        role(state, moderator, AdminRole.MODERATOR, 31);
        role(state, platformOwner, AdminRole.OWNER, 32);
        role(state, viewer, AdminRole.VIEWER, 33);

        assertEquals(ClaimManagementService.Status.SUCCESS, ClaimManagementService.setRole(
                state, moderator, false, KEY, target, ClaimRole.USER, "moderation", 10_000, id(35)).status());
        assertEquals(ClaimManagementService.Status.SUCCESS, ClaimManagementService.setSettings(
                state, platformOwner, false, KEY, new ClaimSettings(true, false), "owner moderation",
                11_100, id(36)).status());
        assertEquals(ClaimManagementService.Status.UNAUTHORIZED, ClaimManagementService.removeRole(
                state, viewer, false, KEY, target, "viewer denied", 12_200, id(37)).status());
        assertEquals(ClaimRole.USER, state.claim(KEY).orElseThrow().roleOf(target));
    }

    @Test
    void moderatorReclaimIsAtomicAuditedIdempotentAndDoesNotRefund() {
        UUID claimOwner = id(90);
        UUID moderator = id(91);
        UUID viewer = id(92);
        PlatformSavedData state = claimed(claimOwner, KEY, 5_000, 90);
        role(state, moderator, AdminRole.MODERATOR, 91);
        role(state, viewer, AdminRole.VIEWER, 92);
        long ownerBalance = state.economyBalance(claimOwner).orElseThrow();
        UUID transactionId = id(93);

        assertEquals(ClaimManagementService.Status.UNAUTHORIZED, ClaimManagementService.reclaim(
                state, viewer, false, KEY, "viewer denied", 10_000, id(94)).status());
        assertTrue(state.claim(KEY).isPresent());
        assertEquals(ClaimManagementService.Status.SUCCESS, ClaimManagementService.reclaim(
                state, moderator, false, KEY, "abandoned claim", 11_100, transactionId).status());

        assertTrue(state.claim(KEY).isEmpty());
        assertEquals(0, state.claimCount(claimOwner));
        assertEquals(ownerBalance, state.economyBalance(claimOwner).orElseThrow());
        assertEquals(ClaimManagementService.Status.DUPLICATE_TRANSACTION, ClaimManagementService.reclaim(
                state, moderator, false, KEY, "retry", 12_200, transactionId).status());
        assertEquals(ClaimManagementService.Status.TRANSACTION_ID_CONFLICT, ClaimManagementService.setSettings(
                state, moderator, false, KEY, ClaimSettings.defaults(), "conflict", 13_300, transactionId).status());

        PlatformSavedData loaded = roundTrip(state);
        assertTrue(loaded.claim(KEY).isEmpty());
        assertEquals(ClaimMutationReceipt.Kind.RECLAIM, loaded.claimReceipt(transactionId).orElseThrow().kind());
        assertTrue(loaded.auditTransaction(transactionId).orElseThrow().beforeValue().contains("owner=" + claimOwner));
        assertEquals("unowned;operation=reclaim=admin",
                loaded.auditTransaction(transactionId).orElseThrow().afterValue());
    }

    @Test
    void transferRequiresOfferRecipientCapacityAndUnprotectedChunk() {
        UUID owner = id(40);
        UUID recipient = id(41);
        UUID stranger = id(42);
        PlatformSavedData state = claimed(owner, KEY, 5_000, 40);
        setRole(state, owner, KEY, id(43), ClaimRole.MANAGER, 50, id(44));
        ClaimManagementService.setSettings(
                state, owner, false, KEY, new ClaimSettings(true, true), "owner settings", 1_100, id(45));

        assertEquals(ClaimManagementService.Status.UNAUTHORIZED, ClaimManagementService.offerTransfer(
                state, id(43), KEY, recipient, "manager cannot transfer", 2_200, id(46)).status());
        assertEquals(ClaimManagementService.Status.SUCCESS, ClaimManagementService.offerTransfer(
                state, owner, KEY, recipient, "offer transfer", 3_300, id(47)).status());
        assertEquals(ClaimManagementService.Status.SUCCESS, ClaimManagementService.cancelTransfer(
                state, owner, KEY, "cancel transfer", 4_000, id(53)).status());
        assertEquals(ClaimManagementService.Status.TRANSFER_NOT_PENDING, ClaimManagementService.acceptTransfer(
                state, recipient, KEY, ignored -> false, 4, "cancelled", 4_100, id(54)).status());
        assertEquals(ClaimManagementService.Status.SUCCESS, ClaimManagementService.offerTransfer(
                state, owner, KEY, recipient, "offer again", 4_200, id(55)).status());
        assertEquals(ClaimManagementService.Status.TRANSFER_NOT_PENDING, ClaimManagementService.acceptTransfer(
                state, stranger, KEY, ignored -> false, 4, "wrong recipient", 4_400, id(48)).status());
        assertEquals(ClaimManagementService.Status.PROTECTED_CHUNK, ClaimManagementService.acceptTransfer(
                state, recipient, KEY, ignored -> true, 4, "protected", 5_500, id(49)).status());

        claimed(state, recipient, SECOND_KEY, 6_600, id(50));
        assertEquals(ClaimManagementService.Status.OWNERSHIP_CAP_REACHED, ClaimManagementService.acceptTransfer(
                state, recipient, KEY, ignored -> false, 1, "at cap", 7_700, id(51)).status());
        assertEquals(ClaimManagementService.Status.SUCCESS, ClaimManagementService.acceptTransfer(
                state, recipient, KEY, ignored -> false, 2, "accept", 8_800, id(52)).status());

        var transferred = state.claim(KEY).orElseThrow();
        assertEquals(recipient, transferred.ownerId());
        assertTrue(transferred.trustedRoles().isEmpty());
        assertEquals(ClaimSettings.defaults(), transferred.settings());
        assertTrue(transferred.pendingTransferTo().isEmpty());
        assertEquals(0, state.claimCount(owner));
        assertEquals(2, state.claimCount(recipient));
    }

    @Test
    void saleCreditsConfiguredRefundAtomicallyAndCannotUseGenericReversal() {
        UUID owner = id(60);
        PlatformSavedData state = claimed(owner, KEY, 5_000, 60);
        UUID saleId = id(61);

        var sale = ClaimManagementService.sell(
                state, owner, KEY, 50, Long.MAX_VALUE, "sell claim", 2_000, saleId);
        assertEquals(ClaimManagementService.Status.SUCCESS, sale.status());
        assertEquals(500, sale.amount());
        assertEquals(4_500, sale.balance());
        assertTrue(state.claim(KEY).isEmpty());
        assertEquals(Optional.of(KEY), state.economyReceipt(saleId).orElseThrow().claim());

        assertEquals(ClaimManagementService.Status.DUPLICATE_TRANSACTION, ClaimManagementService.sell(
                state, owner, KEY, 75, Long.MAX_VALUE, "retry", 3_000, saleId).status());
        assertEquals(4_500, state.economyBalance(owner).orElseThrow());
        assertEquals(EconomyReversalService.Status.ORIGINAL_NOT_REVERSIBLE, EconomyReversalService.reverse(
                state, owner, NonNullList.withSize(36, ItemStack.EMPTY), AdministrationService.SYSTEM_ACTOR,
                true, saleId, EconomyTransactionReceipt.CompensationDecision.NONE,
                "unsafe sale reversal", 4_000, id(62), Long.MAX_VALUE).status());
        assertEquals(4_500, state.economyBalance(owner).orElseThrow());
        assertTrue(state.claim(KEY).isEmpty());
    }

    @Test
    void saleRejectsNonOwnerPendingTransferAndBalanceOverflowWithoutMutation() {
        UUID owner = id(70);
        UUID other = id(71);
        PlatformSavedData state = claimed(owner, KEY, 5_000, 70);
        assertEquals(ClaimManagementService.Status.UNAUTHORIZED, ClaimManagementService.sell(
                state, other, KEY, 50, Long.MAX_VALUE, "not owner", 2_000, id(72)).status());
        ClaimManagementService.offerTransfer(
                state, owner, KEY, other, "pending", 3_100, id(73));
        assertEquals(ClaimManagementService.Status.TRANSFER_PENDING, ClaimManagementService.sell(
                state, owner, KEY, 50, Long.MAX_VALUE, "pending sale", 4_200, id(74)).status());
        assertTrue(state.claim(KEY).isPresent());
        assertEquals(4_000, state.economyBalance(owner).orElseThrow());

        PlatformSavedData rich = claimed(owner, SECOND_KEY, Long.MAX_VALUE, 80);
        assertEquals(ClaimManagementService.Status.MAXIMUM_BALANCE_EXCEEDED, ClaimManagementService.sell(
                rich, owner, SECOND_KEY, 100, Long.MAX_VALUE - 1, "overflow max", 5_000, id(75)).status());
        assertTrue(rich.claim(SECOND_KEY).isPresent());
        assertEquals(Long.MAX_VALUE - 1_000, rich.economyBalance(owner).orElseThrow());
    }

    @Test
    void schemaSixClaimsMigrateWithSafeManagementDefaults() {
        UUID owner = id(80);
        PlatformSavedData state = claimed(owner, KEY, 5_000, 80);
        CompoundTag schemaSix = encoded(state);
        schemaSix.putInt("schema_version", 6);
        schemaSix.remove("claim_receipts");
        ListTag claims = schemaSix.getListOrEmpty("claims");
        CompoundTag claim = claims.getCompoundOrEmpty(0).getCompoundOrEmpty("claim");
        claim.remove("purchase_price");
        claim.remove("trusted_roles");
        claim.remove("settings");
        claim.remove("pending_transfer_to");

        PlatformSavedData migrated = PlatformSavedData.CODEC.parse(NbtOps.INSTANCE, schemaSix).getOrThrow();
        assertEquals(PlatformSavedData.CURRENT_SCHEMA_VERSION, migrated.schemaVersion());
        assertEquals(1_000, migrated.claim(KEY).orElseThrow().purchasePrice());
        assertTrue(migrated.claim(KEY).orElseThrow().trustedRoles().isEmpty());
        assertEquals(ClaimSettings.defaults(), migrated.claim(KEY).orElseThrow().settings());
        assertTrue(migrated.claim(KEY).orElseThrow().pendingTransferTo().isEmpty());

        CompoundTag withoutEvidence = schemaSix.copy();
        withoutEvidence.remove("economy_receipts");
        PlatformSavedData unknownPrice = PlatformSavedData.CODEC.parse(NbtOps.INSTANCE, withoutEvidence).getOrThrow();
        assertEquals(0, unknownPrice.claim(KEY).orElseThrow().purchasePrice());
        assertEquals(ClaimManagementService.Status.PURCHASE_PRICE_UNAVAILABLE, ClaimManagementService.sell(
                unknownPrice, owner, KEY, 50, Long.MAX_VALUE, "unsafe legacy sale", 20_000, id(81)).status());
        assertTrue(unknownPrice.claim(KEY).isPresent());
    }

    private static PlatformSavedData claimed(UUID owner, ClaimKey key, long funds, long timestamp) {
        PlatformSavedData state = new PlatformSavedData();
        EconomyService.award(state, owner, funds, "seed", timestamp, id(timestamp + 1_000), 0, Long.MAX_VALUE);
        claimed(state, owner, key, timestamp + 1, id(timestamp + 2_000));
        return state;
    }

    private static void claimed(
            PlatformSavedData state, UUID owner, ClaimKey key, long timestamp, UUID transactionId) {
        if (state.economyBalance(owner).isEmpty()) {
            EconomyService.award(state, owner, 5_000, "seed", timestamp - 1, id(timestamp + 3_000),
                    0, Long.MAX_VALUE);
        }
        var result = ClaimPurchaseService.purchase(
                state, owner, key.dimension(), key.dimension(), key.auditPosition(),
                ignored -> true, ignored -> false, 1_000, 0, 64, timestamp, transactionId);
        assertEquals(ClaimPurchaseService.Status.SUCCESS, result.status());
    }

    private static ClaimManagementService.Result setRole(
            PlatformSavedData state,
            UUID actor,
            ClaimKey key,
            UUID target,
            ClaimRole role,
            long timestamp,
            UUID transactionId) {
        return ClaimManagementService.setRole(
                state, actor, false, key, target, role, "test role", timestamp, transactionId);
    }

    private static void assertDenied(ClaimManagementService.Result result) {
        assertEquals(ClaimManagementService.Status.UNAUTHORIZED, result.status());
        assertTrue(result.auditRecorded());
    }

    private static void role(PlatformSavedData state, UUID target, AdminRole role, long seed) {
        assertEquals(AdministrationService.RoleChangeStatus.SUCCESS, AdministrationService.changeRole(
                state, AdministrationService.SYSTEM_ACTOR, true, target, role.getSerializedName(),
                "test role", seed, id(seed + 10_000)).status());
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
