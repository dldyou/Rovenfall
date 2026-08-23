package org.dldyou.rovenfall.administration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.Holder;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.dldyou.rovenfall.economy.ShopInstance;
import org.junit.jupiter.api.Test;

final class EconomyReversalServiceTest {
    private static final Holder<Item> BREAD = Holder.direct(
            Items.BREAD, DataComponentMap.builder().set(DataComponents.MAX_STACK_SIZE, 64).build());
    private static final Holder<Item> DIAMOND = Holder.direct(
            Items.DIAMOND, DataComponentMap.builder().set(DataComponents.MAX_STACK_SIZE, 64).build());
    private static final Identifier SHOP = id("reversal_market");
    private static final Identifier OFFER = id("bread");
    private static final UUID PLAYER = uuid(1);
    private static final UUID MANAGER = uuid(2);

    @Test
    void purchaseReversalReclaimsExactItemsAndRestoresBalanceStockWithDirectDuplicateLink() {
        PlatformSavedData state = state();
        NonNullList<ItemStack> inventory = emptyInventory();
        UUID purchaseId = purchase(state, inventory, uuid(100));
        UUID reversalId = uuid(101);

        var result = reverse(state, inventory, purchaseId, reversalId,
                EconomyTransactionReceipt.CompensationDecision.NONE, 3_000);

        assertEquals(EconomyReversalService.Status.SUCCESS, result.status());
        assertEquals(100, state.economyBalance(PLAYER).orElseThrow());
        assertEquals(10, stock(state).current());
        assertEquals(0, ShopTradeService.countExact(inventory, exactBread(4)));
        assertEquals(Optional.of(reversalId), state.economyReceipt(purchaseId).orElseThrow().reversedBy());
        assertEquals(Optional.of(purchaseId), state.economyReceipt(reversalId).orElseThrow().originalTransactionId());
        assertEquals(id("economy_transaction_reversal"), state.auditPage(0, 1).entries().getFirst().actionType());

        int audits = state.auditCount();
        assertEquals(EconomyReversalService.Status.DUPLICATE_TRANSACTION,
                reverse(state, inventory, purchaseId, reversalId,
                        EconomyTransactionReceipt.CompensationDecision.NONE, 4_000).status());
        assertEquals(EconomyReversalService.Status.ALREADY_REVERSED,
                reverse(state, inventory, purchaseId, uuid(102),
                        EconomyTransactionReceipt.CompensationDecision.NONE, 5_000).status());
        assertEquals(audits + 1, state.auditCount());
        assertEquals(id("economy_transaction_reversal_denied"),
                state.auditPage(0, 1).entries().getFirst().actionType());
    }

    @Test
    void unavailablePurchaseItemsRequireAndRecordExplicitBalanceOnlyCompensation() {
        PlatformSavedData state = state();
        NonNullList<ItemStack> inventory = emptyInventory();
        UUID purchaseId = purchase(state, inventory, uuid(200));
        for (int index = 0; index < inventory.size(); index++) {
            inventory.set(index, ItemStack.EMPTY);
        }
        int audits = state.auditCount();

        assertEquals(EconomyReversalService.Status.COMPENSATION_REQUIRED,
                reverse(state, inventory, purchaseId, uuid(201),
                        EconomyTransactionReceipt.CompensationDecision.NONE, 3_000).status());
        assertEquals(88, state.economyBalance(PLAYER).orElseThrow());
        assertEquals(9, stock(state).current());
        assertFalse(state.hasTransaction(uuid(201), 3_000));
        assertTrue(state.auditCount() > audits);

        UUID compensationId = uuid(202);
        assertEquals(EconomyReversalService.Status.SUCCESS,
                reverse(state, inventory, purchaseId, compensationId,
                        EconomyTransactionReceipt.CompensationDecision.REFUND_WITHOUT_ITEMS_OR_STOCK, 5_000).status());
        assertEquals(100, state.economyBalance(PLAYER).orElseThrow());
        assertEquals(9, stock(state).current());
        assertEquals(EconomyTransactionReceipt.CompensationDecision.REFUND_WITHOUT_ITEMS_OR_STOCK,
                state.economyReceipt(compensationId).orElseThrow().compensationDecision());
        assertTrue(state.auditPage(0, 1).entries().getFirst().afterValue()
                .contains("skipped=items,stock;cause=exact_items_unavailable"));
    }

    @Test
    void deletedPurchaseShopStillAllowsExplicitBalanceOnlyCompensation() {
        PlatformSavedData state = state();
        NonNullList<ItemStack> inventory = emptyInventory();
        UUID purchaseId = purchase(state, inventory, uuid(250));
        for (int index = 0; index < inventory.size(); index++) {
            inventory.set(index, ItemStack.EMPTY);
        }
        state.commitShopMutation(SHOP, Optional.empty(), uuid(251), 2_500, audit(uuid(251)));

        assertEquals(EconomyReversalService.Status.COMPENSATION_REQUIRED,
                reverse(state, inventory, purchaseId, uuid(252),
                        EconomyTransactionReceipt.CompensationDecision.NONE, 3_000).status());
        UUID compensationId = uuid(253);
        assertEquals(EconomyReversalService.Status.SUCCESS,
                reverse(state, inventory, purchaseId, compensationId,
                        EconomyTransactionReceipt.CompensationDecision.REFUND_WITHOUT_ITEMS_OR_STOCK, 5_000).status());
        assertEquals(100, state.economyBalance(PLAYER).orElseThrow());
        assertTrue(state.shopInstance(SHOP).isEmpty());
        assertEquals(EconomyTransactionReceipt.CompensationDecision.REFUND_WITHOUT_ITEMS_OR_STOCK,
                state.economyReceipt(compensationId).orElseThrow().compensationDecision());
        assertTrue(state.auditPage(0, 1).entries().getFirst().afterValue()
                .contains("skipped=items,stock;cause=shop_mismatch"));
    }

    @Test
    void authorizationAndShopLeaseRejectWithoutPartialMutation() {
        PlatformSavedData state = state();
        NonNullList<ItemStack> inventory = emptyInventory();
        UUID purchaseId = purchase(state, inventory, uuid(300));

        assertEquals(EconomyReversalService.Status.UNAUTHORIZED,
                EconomyReversalService.reverse(
                        state, PLAYER, inventory, uuid(999), false, purchaseId,
                        EconomyTransactionReceipt.CompensationDecision.NONE, "no", 3_000, uuid(301), Long.MAX_VALUE)
                        .status());
        try (var ignored = ShopInstanceService.tryAcquireDependencyLock(state, SHOP).orElseThrow()) {
            assertEquals(EconomyReversalService.Status.DEPENDENCY_LOCKED,
                    reverse(state, inventory, purchaseId, uuid(302),
                            EconomyTransactionReceipt.CompensationDecision.NONE, 5_000).status());
        }
        assertEquals(88, state.economyBalance(PLAYER).orElseThrow());
        assertEquals(9, stock(state).current());
        assertEquals(4, ShopTradeService.countExact(inventory, exactBread(4)));
        assertTrue(state.economyReceipt(purchaseId).orElseThrow().reversedBy().isEmpty());
    }

    @Test
    void expiredOriginalReceiptRemainsObservableButCannotBeReversed() {
        PlatformSavedData state = state();
        NonNullList<ItemStack> inventory = emptyInventory();
        UUID purchaseId = purchase(state, inventory, uuid(350));
        List<ItemStack> before = ShopTradeService.copyInventory(inventory);
        long expiredAt = 2_000 + PlatformSavedData.ECONOMY_TRANSACTION_RETENTION_MILLIS + 1;
        UUID reversalId = uuid(351);

        assertEquals(EconomyReversalService.Status.ORIGINAL_NOT_REVERSIBLE,
                reverse(state, inventory, purchaseId, reversalId,
                        EconomyTransactionReceipt.CompensationDecision.NONE, expiredAt).status());
        assertEquals(88, state.economyBalance(PLAYER).orElseThrow());
        assertEquals(9, stock(state).current());
        assertTrue(ItemStack.matches(before.getFirst(), inventory.getFirst()));
        assertTrue(state.economyReceipt(purchaseId).isPresent());
        assertTrue(state.economyReceipt(reversalId).isEmpty());
        assertFalse(state.hasTransaction(reversalId, expiredAt));
    }

    @Test
    void unrelatedExistingTransactionIdIsConflictAndDoesNotReverseOriginal() {
        PlatformSavedData state = state();
        NonNullList<ItemStack> inventory = emptyInventory();
        UUID purchaseId = purchase(state, inventory, uuid(360));
        UUID unrelatedAwardId = uuid(9_002);

        assertEquals(EconomyReversalService.Status.TRANSACTION_ID_CONFLICT,
                reverse(state, inventory, purchaseId, unrelatedAwardId,
                        EconomyTransactionReceipt.CompensationDecision.NONE, 3_000).status());

        assertEquals(88, state.economyBalance(PLAYER).orElseThrow());
        assertEquals(9, stock(state).current());
        assertEquals(4, ShopTradeService.countExact(inventory, exactBread(4)));
        assertTrue(state.economyReceipt(purchaseId).orElseThrow().reversedBy().isEmpty());
        assertEquals(EconomyTransactionReceipt.Kind.AWARD,
                state.economyReceipt(unrelatedAwardId).orElseThrow().kind());
        assertEquals("transaction_id_conflict", state.auditPage(0, 1).entries().getFirst().reason());
        AuditEntry denied = state.auditPage(0, 1).entries().getFirst();
        assertEquals(id("economy_transaction_reversal_denied"), denied.actionType());
        assertEquals("transaction_id_conflict", denied.reason());
    }

    @Test
    void retainedReversalReceiptPreventsExpiredIdReuseWithoutReversingNewPurchase() {
        PlatformSavedData state = state();
        NonNullList<ItemStack> inventory = emptyInventory();
        UUID firstPurchaseId = purchase(state, inventory, uuid(370));
        UUID retainedReversalId = uuid(371);
        assertEquals(EconomyReversalService.Status.SUCCESS,
                reverse(state, inventory, firstPurchaseId, retainedReversalId,
                        EconomyTransactionReceipt.CompensationDecision.NONE, 3_000).status());

        long later = PlatformSavedData.ECONOMY_TRANSACTION_RETENTION_MILLIS + 5_000;
        UUID secondPurchaseId = uuid(372);
        assertEquals(ShopTradeService.Status.SUCCESS, ShopTradeService.trade(
                state, PLAYER, Level.OVERWORLD, Vec3.ZERO, inventory,
                new ShopTradeService.TradeRequest(
                        SHOP, OFFER, ShopTradeService.Direction.BUY, 1, exactBread(4), 12, secondPurchaseId),
                0, later, Long.MAX_VALUE).status());
        int audits = state.auditCount();

        assertEquals(EconomyReversalService.Status.TRANSACTION_ID_CONFLICT,
                reverse(state, inventory, secondPurchaseId, retainedReversalId,
                        EconomyTransactionReceipt.CompensationDecision.NONE, later + 1).status());

        assertEquals(88, state.economyBalance(PLAYER).orElseThrow());
        assertEquals(9, stock(state).current());
        assertEquals(4, ShopTradeService.countExact(inventory, exactBread(4)));
        assertTrue(state.economyReceipt(secondPurchaseId).orElseThrow().reversedBy().isEmpty());
        assertEquals(Optional.of(firstPurchaseId),
                state.economyReceipt(retainedReversalId).orElseThrow().originalTransactionId());
        assertEquals(audits + 1, state.auditCount());
    }

    @Test
    void reversalReceiptAndStatusSurvivePersistence() {
        PlatformSavedData state = economyOnlyState();
        NonNullList<ItemStack> inventory = emptyInventory();
        UUID purchaseId = uuid(400);
        assertEquals(EconomyService.TransactionStatus.SUCCESS, EconomyService.adminGrant(
                state, MANAGER, false, PLAYER, 25, "grant", 2_000, purchaseId, 0, Long.MAX_VALUE).status());
        UUID reversalId = uuid(401);
        assertEquals(EconomyReversalService.Status.SUCCESS,
                reverse(state, inventory, purchaseId, reversalId,
                        EconomyTransactionReceipt.CompensationDecision.NONE, 3_000).status());

        var encoded = PlatformSavedData.CODEC.encodeStart(NbtOps.INSTANCE, state).getOrThrow();
        PlatformSavedData decoded = PlatformSavedData.CODEC.parse(NbtOps.INSTANCE, encoded).getOrThrow();

        assertEquals(Optional.of(reversalId), decoded.economyReceipt(purchaseId).orElseThrow().reversedBy());
        assertEquals(Optional.of(purchaseId), decoded.economyReceipt(reversalId).orElseThrow().originalTransactionId());
        assertEquals(EconomyReversalService.Status.ALREADY_REVERSED,
                reverse(decoded, emptyInventory(), purchaseId, uuid(402),
                        EconomyTransactionReceipt.CompensationDecision.NONE, 4_000).status());
    }

    @Test
    void saleReversalDebitsCreditRestoresExactItemsAndDecrementsFiniteStock() {
        PlatformSavedData state = state(BREAD, 5, 10);
        NonNullList<ItemStack> inventory = emptyInventory();
        inventory.set(0, exactBread(4));
        UUID saleId = sale(state, inventory, uuid(500));

        assertEquals(106, state.economyBalance(PLAYER).orElseThrow());
        assertEquals(6, stock(state).current());
        assertEquals(0, ShopTradeService.countExact(inventory, exactBread(4)));
        UUID reversalId = uuid(501);
        assertEquals(EconomyReversalService.Status.SUCCESS,
                reverse(state, inventory, saleId, reversalId,
                        EconomyTransactionReceipt.CompensationDecision.NONE, 3_000).status());

        assertEquals(100, state.economyBalance(PLAYER).orElseThrow());
        assertEquals(5, stock(state).current());
        assertEquals(4, ShopTradeService.countExact(inventory, exactBread(4)));
        assertEquals(Optional.of(reversalId), state.economyReceipt(saleId).orElseThrow().reversedBy());
    }

    @Test
    void saleReversalWithFullInventoryIsAtomic() {
        PlatformSavedData state = state(BREAD, 5, 10);
        NonNullList<ItemStack> inventory = emptyInventory();
        inventory.set(0, exactBread(4));
        UUID saleId = sale(state, inventory, uuid(510));
        for (int index = 0; index < inventory.size(); index++) {
            inventory.set(index, new ItemStack(DIAMOND, 64));
        }
        List<ItemStack> before = ShopTradeService.copyInventory(inventory);
        UUID reversalId = uuid(511);

        assertEquals(EconomyReversalService.Status.INSUFFICIENT_SPACE,
                reverse(state, inventory, saleId, reversalId,
                        EconomyTransactionReceipt.CompensationDecision.NONE, 3_000).status());
        assertEquals(106, state.economyBalance(PLAYER).orElseThrow());
        assertEquals(6, stock(state).current());
        for (int index = 0; index < inventory.size(); index++) {
            assertTrue(ItemStack.matches(before.get(index), inventory.get(index)));
        }
        assertTrue(state.economyReceipt(saleId).orElseThrow().reversedBy().isEmpty());
        assertTrue(state.economyReceipt(reversalId).isEmpty());
        assertFalse(state.hasTransaction(reversalId, 3_000));
    }

    private static PlatformSavedData state() {
        return state(BREAD);
    }

    private static PlatformSavedData economyOnlyState() {
        PlatformSavedData state = new PlatformSavedData();
        AdministrationService.changeRole(
                state, AdministrationService.SYSTEM_ACTOR, true, MANAGER, "economy_manager", "test",
                100, uuid(9_100));
        assertEquals(EconomyService.TransactionStatus.SUCCESS,
                EconomyService.award(state, PLAYER, 100, "seed", 300, uuid(9_101), 0, Long.MAX_VALUE).status());
        return state;
    }

    private static PlatformSavedData state(Holder<Item> bread) {
        return state(bread, 10, 10);
    }

    private static PlatformSavedData state(Holder<Item> bread, long currentStock, long maximumStock) {
        PlatformSavedData state = new PlatformSavedData();
        AdministrationService.changeRole(
                state, AdministrationService.SYSTEM_ACTOR, true, MANAGER, "economy_manager", "test",
                100, uuid(9_000));
        ShopInstance shop = new ShopInstance(
                id("template"), Optional.empty(), ShopInstance.AccessPolicy.publicAccess(), Map.of(OFFER,
                new ShopInstance.Offer(exactBread(bread, 4), Optional.of(12L), Optional.of(6L),
                        ShopInstance.Stock.finite(currentStock, maximumStock))));
        state.commitShopMutation(SHOP, Optional.of(shop), uuid(9_001), 200, audit(uuid(9_001)));
        assertEquals(EconomyService.TransactionStatus.SUCCESS,
                EconomyService.award(state, PLAYER, 100, "seed", 300, uuid(9_002), 0, Long.MAX_VALUE).status());
        return state;
    }

    private static UUID purchase(PlatformSavedData state, List<ItemStack> inventory, UUID transactionId) {
        return purchase(state, inventory, transactionId, exactBread(4));
    }

    private static UUID sale(PlatformSavedData state, List<ItemStack> inventory, UUID transactionId) {
        assertEquals(ShopTradeService.Status.SUCCESS, ShopTradeService.trade(
                state, PLAYER, Level.OVERWORLD, Vec3.ZERO, inventory,
                new ShopTradeService.TradeRequest(
                        SHOP, OFFER, ShopTradeService.Direction.SELL, 1, exactBread(4), 6, transactionId),
                0, 2_000, Long.MAX_VALUE).status());
        return transactionId;
    }

    private static UUID purchase(
            PlatformSavedData state, List<ItemStack> inventory, UUID transactionId, ItemStack exact) {
        assertEquals(ShopTradeService.Status.SUCCESS, ShopTradeService.trade(
                state, PLAYER, Level.OVERWORLD, Vec3.ZERO, inventory,
                new ShopTradeService.TradeRequest(
                        SHOP, OFFER, ShopTradeService.Direction.BUY, 1, exact, 12, transactionId),
                0, 2_000, Long.MAX_VALUE).status());
        return transactionId;
    }

    private static EconomyReversalService.Result reverse(
            PlatformSavedData state,
            List<ItemStack> inventory,
            UUID original,
            UUID reversal,
            EconomyTransactionReceipt.CompensationDecision decision,
            long timestamp) {
        return EconomyReversalService.reverse(
                state, PLAYER, inventory, MANAGER, false, original, decision, "operator decision",
                timestamp, reversal, Long.MAX_VALUE);
    }

    private static ShopInstance.Stock stock(PlatformSavedData state) {
        return state.shopInstance(SHOP).orElseThrow().offers().get(OFFER).stock();
    }

    private static ItemStack exactBread(int count) {
        return exactBread(BREAD, count);
    }

    private static ItemStack exactBread(Holder<Item> bread, int count) {
        ItemStack stack = new ItemStack(bread, count);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("Exact reversal bread"));
        return stack;
    }

    private static NonNullList<ItemStack> emptyInventory() {
        return NonNullList.withSize(36, ItemStack.EMPTY);
    }

    private static AuditEntry audit(UUID transactionId) {
        return new AuditEntry(
                200, AdministrationService.SYSTEM_ACTOR, id("seed_shop"), SHOP.toString(),
                Optional.empty(), Optional.empty(), "none", "shop", "test", transactionId);
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("rovenfall", path);
    }

    private static UUID uuid(long value) {
        return new UUID(0L, value);
    }

}
