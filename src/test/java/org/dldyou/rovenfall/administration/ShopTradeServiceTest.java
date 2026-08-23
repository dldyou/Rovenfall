package org.dldyou.rovenfall.administration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.AbstractList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.dldyou.rovenfall.economy.ShopInstance;
import org.junit.jupiter.api.Test;

final class ShopTradeServiceTest {
    private static final Holder<Item> BREAD = Holder.direct(
            Items.BREAD,
            DataComponentMap.builder().set(DataComponents.MAX_STACK_SIZE, 64).build());
    private static final Holder<Item> DIAMOND = Holder.direct(
            Items.DIAMOND,
            DataComponentMap.builder().set(DataComponents.MAX_STACK_SIZE, 64).build());
    private static final Identifier SHOP = id("market");
    private static final Identifier OFFER = id("bread");
    private static final UUID PLAYER = uuid(1);

    @Test
    void purchaseLazilyRestocksAndCommitsBalanceStockInventoryAuditAndRetryIdTogether() {
        PlatformSavedData state = stateWith(
                offer(exactBread(4), 12, 6, restockingStock(3, 10, 2, 10, 100)), Optional.empty(), 100);
        NonNullList<ItemStack> inventory = emptyInventory();
        UUID transactionId = uuid(100);

        var result = trade(state, inventory, request(ShopTradeService.Direction.BUY, 2, transactionId), 125, 2_000);

        assertEquals(ShopTradeService.Status.SUCCESS, result.status());
        assertEquals(76, state.economyBalance(PLAYER).orElseThrow());
        assertEquals(7, stock(state).current());
        assertEquals(130, stock(state).nextRestockGameTime());
        assertEquals(8, exactCount(inventory, exactBread(4)));
        assertTrue(state.hasTransaction(transactionId, 2_000));
        AuditEntry audit = state.auditPage(0, 1).entries().getFirst();
        assertEquals(id("shop_purchase"), audit.actionType());
        assertTrue(audit.beforeValue().contains("balance=100;stock=3;next_restock=100;exact_items=0"));
        assertTrue(audit.afterValue().contains("balance=76;stock=7;next_restock=130;exact_items=8"));
        assertTrue(audit.afterValue().contains("units=2;unit_price=12;total=24;item=minecraft:breadx4;fingerprint="));
        assertFalse(audit.afterValue().contains("custom_name"));

        int auditCount = state.auditCount();
        var retry = trade(state, inventory, request(ShopTradeService.Direction.BUY, 2, transactionId), 200, 3_000);
        assertEquals(ShopTradeService.Status.DUPLICATE_TRANSACTION, retry.status());
        assertEquals(76, state.economyBalance(PLAYER).orElseThrow());
        assertEquals(7, stock(state).current());
        assertEquals(8, exactCount(inventory, exactBread(4)));
        assertEquals(auditCount, state.auditCount());
    }

    @Test
    void retainedReceiptPreventsExpiredTradeIdReuseWithoutMutatingTradeState() {
        PlatformSavedData state = stateWith(
                offer(exactBread(4), 12, 6, ShopInstance.Stock.finite(10, 10)), Optional.empty(), 100);
        NonNullList<ItemStack> inventory = emptyInventory();
        UUID transactionId = uuid(150);
        assertEquals(ShopTradeService.Status.SUCCESS,
                trade(state, inventory, request(ShopTradeService.Direction.BUY, 1, transactionId), 0, 2_000)
                        .status());
        EconomyTransactionReceipt retained = state.economyReceipt(transactionId).orElseThrow();
        long expiredAt = 2_001 + PlatformSavedData.ECONOMY_TRANSACTION_RETENTION_MILLIS;

        assertEquals(ShopTradeService.Status.TRANSACTION_ID_CONFLICT,
                trade(state, inventory, request(ShopTradeService.Direction.BUY, 2, transactionId), 0, expiredAt)
                        .status());

        assertEquals(88, state.economyBalance(PLAYER).orElseThrow());
        assertEquals(9, stock(state).current());
        assertEquals(4, exactCount(inventory, exactBread(4)));
        assertEquals(1, state.economyReceipt(transactionId).orElseThrow().quantity());
        assertEquals(retained.timestampEpochMillis(),
                state.economyReceipt(transactionId).orElseThrow().timestampEpochMillis());
        assertEquals("transaction_id_conflict", state.auditPage(0, 1).entries().getFirst().reason());
    }

    @Test
    void saleConsumesOnlyExactComponentsAndFiniteStockWhileUnlimitedStockNeverChanges() {
        PlatformSavedData state = stateWith(
                offer(exactBread(4), 12, 6, ShopInstance.Stock.finite(3, 10)), Optional.empty(), 10);
        NonNullList<ItemStack> inventory = emptyInventory();
        inventory.set(0, exactBread(8));
        inventory.set(1, new ItemStack(BREAD, 8));

        assertEquals(ShopTradeService.Status.SUCCESS,
                trade(state, inventory, request(ShopTradeService.Direction.SELL, 2, uuid(200)), 50, 2_000).status());
        assertEquals(22, state.economyBalance(PLAYER).orElseThrow());
        assertEquals(5, stock(state).current());
        assertEquals(0, exactCount(inventory, exactBread(4)));
        assertEquals(8, inventory.get(1).getCount());

        PlatformSavedData unlimited = stateWith(
                offer(exactBread(4), 12, 6, ShopInstance.Stock.unlimitedStock()), Optional.empty(), 100);
        NonNullList<ItemStack> unlimitedInventory = emptyInventory();
        assertEquals(ShopTradeService.Status.SUCCESS,
                trade(unlimited, unlimitedInventory,
                        request(ShopTradeService.Direction.BUY, 1, uuid(201)), 50, 2_000).status());
        assertTrue(stock(unlimited).unlimited());
    }

    @Test
    void everyRejectedBuyLeavesBalanceStockInventoryAndTransactionUntouched() {
        assertRejectedUnchanged(
                stateWith(offer(exactBread(4), 12, 6, ShopInstance.Stock.finite(10, 10)), Optional.empty(), 10),
                emptyInventory(), request(ShopTradeService.Direction.BUY, 1, uuid(300)), 0,
                ShopTradeService.Status.INSUFFICIENT_FUNDS);
        assertRejectedUnchanged(
                stateWith(offer(exactBread(4), 12, 6, ShopInstance.Stock.finite(1, 10)), Optional.empty(), 100),
                emptyInventory(), request(ShopTradeService.Direction.BUY, 2, uuid(301)), 0,
                ShopTradeService.Status.INSUFFICIENT_STOCK);

        NonNullList<ItemStack> full = emptyInventory();
        for (int index = 0; index < full.size(); index++) {
            full.set(index, new ItemStack(DIAMOND, 64));
        }
        assertRejectedUnchanged(
                stateWith(offer(exactBread(4), 12, 6, ShopInstance.Stock.finite(10, 10)), Optional.empty(), 100),
                full, request(ShopTradeService.Direction.BUY, 1, uuid(302)), 0,
                ShopTradeService.Status.INSUFFICIENT_SPACE);

        var staleItem = request(ShopTradeService.Direction.BUY, 1, uuid(303));
        staleItem = new ShopTradeService.TradeRequest(
                SHOP, OFFER, staleItem.direction(), staleItem.quantity(), new ItemStack(BREAD, 4), 12, staleItem.transactionId());
        assertRejectedUnchanged(
                stateWith(offer(exactBread(4), 12, 6, ShopInstance.Stock.finite(10, 10)), Optional.empty(), 100),
                emptyInventory(), staleItem, 0, ShopTradeService.Status.STALE_OFFER);

        var stalePrice = new ShopTradeService.TradeRequest(
                SHOP, OFFER, ShopTradeService.Direction.BUY, 1, exactBread(4), 11, uuid(304));
        assertRejectedUnchanged(
                stateWith(offer(exactBread(4), 12, 6, ShopInstance.Stock.finite(10, 10)), Optional.empty(), 100),
                emptyInventory(), stalePrice, 0, ShopTradeService.Status.STALE_OFFER);

        for (int quantity : List.of(0, ShopTradeService.MAX_TRADE_QUANTITY + 1)) {
            var malformed = new ShopTradeService.TradeRequest(
                    SHOP, OFFER, ShopTradeService.Direction.BUY, quantity, exactBread(4), 12, uuid(305 + quantity));
            assertRejectedUnchanged(
                    stateWith(offer(exactBread(4), 12, 6, ShopInstance.Stock.finite(10, 10)), Optional.empty(), 100),
                    emptyInventory(), malformed, 0, ShopTradeService.Status.INVALID_REQUEST);
        }
    }

    @Test
    void rejectedSalesAndOverflowLeaveEverythingUntouched() {
        PlatformSavedData missingItems = stateWith(
                offer(exactBread(4), 12, 6, ShopInstance.Stock.finite(3, 10)), Optional.empty(), 10);
        NonNullList<ItemStack> tooFew = emptyInventory();
        tooFew.set(0, exactBread(4));
        assertRejectedUnchanged(
                missingItems, tooFew, request(ShopTradeService.Direction.SELL, 2, uuid(400)), 0,
                ShopTradeService.Status.INSUFFICIENT_ITEMS);

        PlatformSavedData fullStock = stateWith(
                offer(exactBread(4), 12, 6, ShopInstance.Stock.finite(9, 10)), Optional.empty(), 10);
        NonNullList<ItemStack> sellTwo = emptyInventory();
        sellTwo.set(0, exactBread(8));
        assertRejectedUnchanged(
                fullStock, sellTwo, request(ShopTradeService.Direction.SELL, 2, uuid(401)), 0,
                ShopTradeService.Status.STOCK_CAPACITY_EXCEEDED);

        PlatformSavedData overflow = stateWith(
                offer(exactBread(4), 12, 6, ShopInstance.Stock.unlimitedStock()), Optional.empty(), Long.MAX_VALUE - 1);
        NonNullList<ItemStack> sellOne = emptyInventory();
        sellOne.set(0, exactBread(4));
        assertRejectedUnchanged(
                overflow, sellOne, request(ShopTradeService.Direction.SELL, 1, uuid(402)), Long.MAX_VALUE,
                ShopTradeService.Status.OVERFLOW);

        PlatformSavedData maximumExceeded = stateWith(
                offer(exactBread(4), 12, 6, ShopInstance.Stock.unlimitedStock()), Optional.empty(), 10);
        NonNullList<ItemStack> maximumInventory = emptyInventory();
        maximumInventory.set(0, exactBread(4));
        assertRejectedUnchanged(
                maximumExceeded,
                maximumInventory,
                request(ShopTradeService.Direction.SELL, 1, uuid(403)),
                15,
                ShopTradeService.Status.MAXIMUM_BALANCE_EXCEEDED);
    }

    @Test
    void unavailableOfferDirectionAndMissingAccountAreServerDerivedAndAtomic() {
        ShopInstance.Stock unlimited = ShopInstance.Stock.unlimitedStock();
        PlatformSavedData sellOnly = stateWith(
                new ShopInstance.Offer(exactBread(4), Optional.empty(), Optional.of(6L), unlimited),
                Optional.empty(),
                100);
        var unavailableBuy = new ShopTradeService.TradeRequest(
                SHOP, OFFER, ShopTradeService.Direction.BUY, 1, exactBread(4), -1, uuid(450));
        assertRejectedUnchanged(
                sellOnly, emptyInventory(), unavailableBuy, 0, ShopTradeService.Status.OFFER_UNAVAILABLE);

        PlatformSavedData buyOnly = stateWith(
                new ShopInstance.Offer(exactBread(4), Optional.of(12L), Optional.empty(), unlimited),
                Optional.empty(),
                100);
        NonNullList<ItemStack> sellInventory = emptyInventory();
        sellInventory.set(0, exactBread(4));
        var unavailableSell = new ShopTradeService.TradeRequest(
                SHOP, OFFER, ShopTradeService.Direction.SELL, 1, exactBread(4), -1, uuid(451));
        assertRejectedUnchanged(
                buyOnly, sellInventory, unavailableSell, 0, ShopTradeService.Status.OFFER_UNAVAILABLE);

        PlatformSavedData noAccount = shopState(
                offer(exactBread(4), 12, 6, ShopInstance.Stock.finite(10, 10)), Optional.empty());
        NonNullList<ItemStack> empty = emptyInventory();
        var missing = request(ShopTradeService.Direction.BUY, 1, uuid(452));
        var result = trade(noAccount, empty, missing, 0, 2_000);
        assertEquals(ShopTradeService.Status.ACCOUNT_NOT_FOUND, result.status());
        assertTrue(noAccount.economyBalance(PLAYER).isEmpty());
        assertEquals(10, stock(noAccount).current());
        assertTrue(empty.stream().allMatch(ItemStack::isEmpty));
        assertFalse(noAccount.hasTransaction(missing.transactionId(), 2_000));
    }

    @Test
    void bindingAndDependencyLockAreServerValidatedBeforeMutation() {
        ShopInstance.Binding binding = new ShopInstance.Binding(Level.OVERWORLD, new BlockPos(10, 64, 10));
        PlatformSavedData state = stateWith(
                offer(exactBread(4), 12, 6, ShopInstance.Stock.finite(10, 10)), Optional.of(binding), 100);
        NonNullList<ItemStack> inventory = emptyInventory();
        var request = request(ShopTradeService.Direction.BUY, 1, uuid(500));

        assertEquals(ShopTradeService.Status.ACCESS_DENIED,
                ShopTradeService.trade(
                        state, PLAYER, Level.NETHER, Vec3.atCenterOf(binding.position()), inventory,
                        request, 0, 2_000, Long.MAX_VALUE).status());
        assertEquals(ShopTradeService.Status.ACCESS_DENIED,
                ShopTradeService.trade(
                        state, PLAYER, Level.OVERWORLD, new Vec3(100, 64, 100), inventory,
                        request, 0, 3_500, Long.MAX_VALUE).status());

        try (var ignored = ShopInstanceService.tryAcquireDependencyLock(state, SHOP).orElseThrow()) {
            assertEquals(ShopTradeService.Status.DEPENDENCY_LOCKED,
                    ShopTradeService.trade(
                            state, PLAYER, Level.OVERWORLD, Vec3.atCenterOf(binding.position()), inventory,
                            request, 0, 5_000, Long.MAX_VALUE).status());
        }
        assertEquals(100, state.economyBalance(PLAYER).orElseThrow());
        assertEquals(10, stock(state).current());
        assertTrue(inventory.stream().allMatch(ItemStack::isEmpty));
        assertFalse(state.hasTransaction(request.transactionId(), 5_000));
    }

    @Test
    void injectedInventoryFailureRestoresEveryChangedSlotAndDoesNotCommitStateOrRetryId() {
        ItemStack stack = exactBread(64);
        PlatformSavedData state = stateWith(
                offer(stack, 1, 1, ShopInstance.Stock.finite(10, 10)), Optional.empty(), 100);
        NonNullList<ItemStack> delegate = emptyInventory();
        FailingInventory inventory = new FailingInventory(delegate, 1);
        var request = new ShopTradeService.TradeRequest(
                SHOP, OFFER, ShopTradeService.Direction.BUY, 2, stack, 1, uuid(600));

        var result = ShopTradeService.trade(
                state, PLAYER, Level.OVERWORLD, Vec3.ZERO, inventory, request, 0, 2_000, Long.MAX_VALUE);

        assertEquals(ShopTradeService.Status.INVENTORY_UPDATE_FAILED, result.status());
        assertTrue(delegate.stream().allMatch(ItemStack::isEmpty));
        assertEquals(100, state.economyBalance(PLAYER).orElseThrow());
        assertEquals(10, stock(state).current());
        assertFalse(state.hasTransaction(request.transactionId(), 2_000));
        assertEquals("inventory_update_failed", state.auditPage(0, 1).entries().getFirst().reason());
    }

    private static void assertRejectedUnchanged(
            PlatformSavedData state,
            List<ItemStack> inventory,
            ShopTradeService.TradeRequest request,
            long maximumBalance,
            ShopTradeService.Status expected) {
        long balance = state.economyBalance(PLAYER).orElseThrow();
        ShopInstance.Stock stock = stock(state);
        List<ItemStack> beforeInventory = inventory.stream().map(ItemStack::copy).toList();

        var result = ShopTradeService.trade(
                state, PLAYER, Level.OVERWORLD, Vec3.ZERO, inventory, request,
                0, 2_000, maximumBalance == 0 ? Long.MAX_VALUE : maximumBalance);

        assertEquals(expected, result.status());
        assertEquals(balance, state.economyBalance(PLAYER).orElseThrow());
        assertEquals(stock, stock(state));
        for (int index = 0; index < inventory.size(); index++) {
            assertTrue(ItemStack.matches(beforeInventory.get(index), inventory.get(index)), "slot " + index);
        }
        assertFalse(state.hasTransaction(request.transactionId(), 2_000));
    }

    private static ShopTradeService.TradeResult trade(
            PlatformSavedData state,
            List<ItemStack> inventory,
            ShopTradeService.TradeRequest request,
            long gameTime,
            long timestamp) {
        return ShopTradeService.trade(
                state, PLAYER, Level.OVERWORLD, Vec3.ZERO, inventory, request, gameTime, timestamp, Long.MAX_VALUE);
    }

    private static PlatformSavedData stateWith(
            ShopInstance.Offer offer, Optional<ShopInstance.Binding> binding, long balance) {
        PlatformSavedData state = shopState(offer, binding);
        assertEquals(EconomyService.TransactionStatus.SUCCESS,
                EconomyService.award(state, PLAYER, balance, "seed", 1_001, uuid(9_001), 0, Long.MAX_VALUE).status());
        return state;
    }

    private static PlatformSavedData shopState(
            ShopInstance.Offer offer, Optional<ShopInstance.Binding> binding) {
        PlatformSavedData state = new PlatformSavedData();
        ShopInstance shop = new ShopInstance(
                id("template"), binding, new ShopInstance.AccessPolicy(8), Map.of(OFFER, offer));
        state.commitShopMutation(
                SHOP,
                Optional.of(shop),
                uuid(9_000),
                1_000,
                audit(uuid(9_000), "seed_shop"));
        return state;
    }

    private static ShopInstance.Offer offer(
            ItemStack item, long buyPrice, long sellPrice, ShopInstance.Stock stock) {
        return new ShopInstance.Offer(item, Optional.of(buyPrice), Optional.of(sellPrice), stock);
    }

    private static ShopInstance.Stock restockingStock(
            long current, long maximum, long amount, long interval, long next) {
        return new ShopInstance.Stock(
                false, current, maximum, Optional.of(amount), Optional.of(interval), next);
    }

    private static ShopTradeService.TradeRequest request(
            ShopTradeService.Direction direction, int quantity, UUID transactionId) {
        return new ShopTradeService.TradeRequest(
                SHOP, OFFER, direction, quantity, exactBread(4), direction == ShopTradeService.Direction.BUY ? 12 : 6,
                transactionId);
    }

    private static ShopInstance.Stock stock(PlatformSavedData state) {
        return state.shopInstance(SHOP).orElseThrow().offers().get(OFFER).stock();
    }

    private static ItemStack exactBread(int count) {
        ItemStack stack = new ItemStack(BREAD, count);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("Exact bread"));
        return stack;
    }

    private static long exactCount(List<ItemStack> inventory, ItemStack exact) {
        return inventory.stream()
                .filter(stack -> ItemStack.isSameItemSameComponents(stack, exact))
                .mapToLong(ItemStack::getCount)
                .sum();
    }

    private static NonNullList<ItemStack> emptyInventory() {
        return NonNullList.withSize(36, ItemStack.EMPTY);
    }

    private static AuditEntry audit(UUID transactionId, String action) {
        return new AuditEntry(
                1_000,
                AdministrationService.SYSTEM_ACTOR,
                id(action),
                SHOP.toString(),
                Optional.empty(),
                Optional.empty(),
                "none",
                "seed",
                "test",
                transactionId);
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("rovenfall", path);
    }

    private static UUID uuid(long value) {
        return new UUID(0L, value);
    }

    private static final class FailingInventory extends AbstractList<ItemStack> {
        private final List<ItemStack> delegate;
        private final int failingIndex;
        private boolean failed;

        private FailingInventory(List<ItemStack> delegate, int failingIndex) {
            this.delegate = delegate;
            this.failingIndex = failingIndex;
        }

        @Override
        public ItemStack get(int index) {
            return delegate.get(index);
        }

        @Override
        public int size() {
            return delegate.size();
        }

        @Override
        public ItemStack set(int index, ItemStack element) {
            if (!failed && index == failingIndex) {
                failed = true;
                throw new IllegalStateException("injected inventory failure");
            }
            return delegate.set(index, element);
        }
    }
}
