package org.dldyou.rovenfall.administration;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.dldyou.rovenfall.Rovenfall;
import org.dldyou.rovenfall.economy.ShopInstance;

/** Server-thread-only transaction boundary for administrator-shop purchases and sales. */
public final class ShopTradeService {
    public static final int MAX_TRADE_QUANTITY = 10_000;
    private static final UUID ZERO_UUID = new UUID(0L, 0L);
    private static final long DENIED_AUDIT_INTERVAL_MILLIS = 1_000L;

    private ShopTradeService() {
    }

    public static TradeResult trade(
            PlatformSavedData state,
            ServerPlayer player,
            TradeRequest request,
            long gameTime,
            long timestampEpochMillis) {
        if (player == null || player.level().getServer() == null
                || !player.level().getServer().isSameThread()) {
            return result(Status.INVALID_REQUEST, request, false);
        }
        TradeResult result = trade(
                state,
                player.getUUID(),
                player.level().dimension(),
                player.position(),
                player.getInventory().getNonEquipmentItems(),
                request,
                gameTime,
                timestampEpochMillis,
                EconomyConfig.maximumBalance());
        if (result.status() == Status.SUCCESS) {
            player.getInventory().setChanged();
        }
        return result;
    }

    static TradeResult trade(
            PlatformSavedData state,
            UUID playerId,
            ResourceKey<Level> playerDimension,
            Vec3 playerPosition,
            List<ItemStack> inventory,
            TradeRequest request,
            long gameTime,
            long timestampEpochMillis,
            long maximumBalance) {
        if (state == null || !state.isWritable()) {
            return result(state == null ? Status.INVALID_REQUEST : Status.READ_ONLY_SCHEMA, request, false);
        }
        if (!validRequest(playerId, playerDimension, playerPosition, inventory, request, gameTime,
                timestampEpochMillis, maximumBalance)) {
            return denied(state, playerId, request, Status.INVALID_REQUEST, timestampEpochMillis);
        }
        if (!validTransactionId(request.transactionId())) {
            return denied(state, playerId, request, Status.INVALID_TRANSACTION, timestampEpochMillis);
        }
        Optional<EconomyTransactionReceipt> retainedReceipt = state.economyReceipt(request.transactionId());
        if (retainedReceipt.isPresent()) {
            return matchingTradeRetry(retainedReceipt.orElseThrow(), playerId, request)
                    ? result(Status.DUPLICATE_TRANSACTION, request, false)
                    : denied(state, playerId, request, Status.TRANSACTION_ID_CONFLICT, timestampEpochMillis);
        }
        if (state.hasTransaction(request.transactionId(), timestampEpochMillis)) {
            return denied(state, playerId, request, Status.TRANSACTION_ID_CONFLICT, timestampEpochMillis);
        }
        if (!state.canCommitReceiptTransaction(request.transactionId(), timestampEpochMillis)) {
            return denied(state, playerId, request, Status.TRANSACTION_LEDGER_FULL, timestampEpochMillis);
        }
        Optional<ShopInstance> existing = state.shopInstance(request.shopId());
        if (existing.isEmpty()) {
            return denied(state, playerId, request, Status.SHOP_NOT_FOUND, timestampEpochMillis);
        }

        try (var ignored = ShopInstanceService.tryAcquireDependencyLock(state, request.shopId()).orElse(null)) {
            if (ignored == null) {
                return denied(state, playerId, request, Status.DEPENDENCY_LOCKED, timestampEpochMillis);
            }
            ShopInstance shop = state.shopInstance(request.shopId()).orElseThrow();
            if (!canAccess(shop, playerDimension, playerPosition)) {
                return denied(state, playerId, request, Status.ACCESS_DENIED, timestampEpochMillis);
            }
            ShopInstance.Offer offer = shop.offers().get(request.offerId());
            if (offer == null) {
                return denied(state, playerId, request, Status.OFFER_NOT_FOUND, timestampEpochMillis);
            }
            Optional<Long> price = request.direction() == Direction.BUY ? offer.buyPrice() : offer.sellPrice();
            if (price.isEmpty()) {
                return denied(state, playerId, request, Status.OFFER_UNAVAILABLE, timestampEpochMillis);
            }
            if (!ItemStack.matches(request.expectedItem(), offer.item())
                    || request.expectedUnitPrice() != price.orElseThrow()) {
                return denied(state, playerId, request, Status.STALE_OFFER, timestampEpochMillis);
            }

            long total;
            int itemCount;
            try {
                total = Math.multiplyExact(price.orElseThrow(), request.quantity());
                itemCount = Math.multiplyExact(offer.item().getCount(), request.quantity());
            } catch (ArithmeticException exception) {
                return denied(state, playerId, request, Status.OVERFLOW, timestampEpochMillis);
            }
            Optional<Long> balance = state.economyBalance(playerId);
            if (balance.isEmpty()) {
                return denied(state, playerId, request, Status.ACCOUNT_NOT_FOUND, timestampEpochMillis);
            }
            long beforeBalance = balance.orElseThrow();
            long afterBalance;
            try {
                afterBalance = request.direction() == Direction.BUY
                        ? Math.subtractExact(beforeBalance, total)
                        : Math.addExact(beforeBalance, total);
            } catch (ArithmeticException exception) {
                return denied(state, playerId, request, Status.OVERFLOW, timestampEpochMillis);
            }
            if (afterBalance < 0) {
                return denied(state, playerId, request, Status.INSUFFICIENT_FUNDS, timestampEpochMillis);
            }
            if (afterBalance > maximumBalance) {
                return denied(state, playerId, request, Status.MAXIMUM_BALANCE_EXCEEDED, timestampEpochMillis);
            }

            Optional<ShopInstance.Stock> restocked = restock(offer.stock(), gameTime);
            if (restocked.isEmpty()) {
                return denied(state, playerId, request, Status.OVERFLOW, timestampEpochMillis);
            }
            ShopInstance.Stock availableStock = restocked.orElseThrow();
            Optional<ShopInstance.Stock> changedStock = changeStock(availableStock, request.direction(), request.quantity());
            if (changedStock.isEmpty()) {
                Status status = request.direction() == Direction.BUY
                        ? Status.INSUFFICIENT_STOCK
                        : Status.STOCK_CAPACITY_EXCEEDED;
                return denied(state, playerId, request, status, timestampEpochMillis);
            }

            List<ItemStack> beforeInventory = copyInventory(inventory);
            List<ItemStack> afterInventory = copyInventory(inventory);
            boolean inventoryReady = request.direction() == Direction.BUY
                    ? addExact(afterInventory, offer.item(), itemCount)
                    : removeExact(afterInventory, offer.item(), itemCount);
            if (!inventoryReady) {
                Status status = request.direction() == Direction.BUY
                        ? Status.INSUFFICIENT_SPACE
                        : Status.INSUFFICIENT_ITEMS;
                return denied(state, playerId, request, status, timestampEpochMillis);
            }

            ShopInstance.Offer changedOffer = new ShopInstance.Offer(
                    offer.item(), offer.buyPrice(), offer.sellPrice(), changedStock.orElseThrow());
            ShopInstance changedShop = shop.withOffer(request.offerId(), changedOffer);
            long beforeExactItems = countExact(beforeInventory, offer.item());
            long afterExactItems = countExact(afterInventory, offer.item());
            String beforeEvidence = evidence(
                    beforeBalance, offer.stock(), beforeExactItems, offer, request, price.orElseThrow(), total);
            String afterEvidence = evidence(
                    afterBalance, changedStock.orElseThrow(), afterExactItems, offer, request, price.orElseThrow(), total);

            if (!replaceInventory(inventory, beforeInventory, afterInventory)) {
                return denied(state, playerId, request, Status.INVENTORY_UPDATE_FAILED, timestampEpochMillis);
            }
            List<EconomyAlert> alerts;
            try {
                Optional<ShopInstance.Binding> binding = shop.binding();
                EconomyTransactionReceipt receipt = new EconomyTransactionReceipt(
                        timestampEpochMillis,
                        playerId,
                        playerId,
                        request.direction() == Direction.BUY
                                ? EconomyTransactionReceipt.Kind.PURCHASE
                                : EconomyTransactionReceipt.Kind.SALE,
                        total,
                        Optional.empty(),
                        Optional.of(request.shopId()),
                        Optional.of(request.offerId()),
                        Optional.of(offer.item()),
                        request.quantity(),
                        Optional.of(availableStock),
                        Optional.of(changedStock.orElseThrow()),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        EconomyTransactionReceipt.CompensationDecision.NONE);
                alerts = EconomyMonitoringService.evaluate(
                        state, request.transactionId(), receipt, EconomyConfig.alertThresholds());
                state.commitShopTrade(
                        playerId,
                        afterBalance,
                        request.shopId(),
                        changedShop,
                        request.transactionId(),
                        timestampEpochMillis,
                        receipt,
                        alerts,
                        new AuditEntry(
                                timestampEpochMillis,
                                playerId,
                                action(request.direction() == Direction.BUY ? "shop_purchase" : "shop_sale"),
                                request.shopId() + "/" + request.offerId() + "/" + playerId,
                                binding.map(value -> value.dimension().identifier()),
                                binding.map(ShopInstance.Binding::position),
                                beforeEvidence,
                                afterEvidence,
                                request.direction() == Direction.BUY ? "purchase" : "sale",
                                request.transactionId()));
            } catch (RuntimeException exception) {
                restoreInventory(inventory, beforeInventory);
                throw exception;
            }
            EconomyMonitoringService.publish(alerts);
            return result(Status.SUCCESS, request, true);
        }
    }

    private static boolean validRequest(
            UUID playerId,
            ResourceKey<Level> playerDimension,
            Vec3 playerPosition,
            List<ItemStack> inventory,
            TradeRequest request,
            long gameTime,
            long timestampEpochMillis,
            long maximumBalance) {
        if (playerId == null || playerDimension == null || playerPosition == null || inventory == null
                || request == null || request.shopId() == null || request.offerId() == null
                || request.direction() == null || request.expectedItem() == null
                || request.quantity() < 1 || request.quantity() > MAX_TRADE_QUANTITY
                || gameTime < 0 || timestampEpochMillis < 0
                || maximumBalance < 0 || inventory.size() != Inventory.INVENTORY_SIZE) {
            return false;
        }
        if (!Double.isFinite(playerPosition.x) || !Double.isFinite(playerPosition.y)
                || !Double.isFinite(playerPosition.z)) {
            return false;
        }
        return inventory.stream().allMatch(stack -> stack != null);
    }

    private static boolean validTransactionId(UUID transactionId) {
        return transactionId != null && !ZERO_UUID.equals(transactionId);
    }

    private static boolean matchingTradeRetry(
            EconomyTransactionReceipt receipt, UUID playerId, TradeRequest request) {
        long total;
        try {
            total = Math.multiplyExact(request.expectedUnitPrice(), request.quantity());
        } catch (ArithmeticException exception) {
            return false;
        }
        EconomyTransactionReceipt.Kind kind = request.direction() == Direction.BUY
                ? EconomyTransactionReceipt.Kind.PURCHASE
                : EconomyTransactionReceipt.Kind.SALE;
        return receipt.actorId().equals(playerId)
                && receipt.playerId().equals(playerId)
                && receipt.kind() == kind
                && receipt.amount() == total
                && receipt.shopId().equals(Optional.of(request.shopId()))
                && receipt.offerId().equals(Optional.of(request.offerId()))
                && receipt.item().filter(item -> ItemStack.matches(item, request.expectedItem())).isPresent()
                && receipt.quantity() == request.quantity();
    }

    private static boolean canAccess(
            ShopInstance shop, ResourceKey<Level> playerDimension, Vec3 playerPosition) {
        if (shop.binding().isEmpty()) {
            return true;
        }
        ShopInstance.Binding binding = shop.binding().orElseThrow();
        double maximumDistance = shop.accessPolicy().maxDistance();
        return binding.dimension().equals(playerDimension)
                && playerPosition.distanceToSqr(Vec3.atCenterOf(binding.position()))
                <= maximumDistance * maximumDistance;
    }

    static List<Identifier> accessibleShopIds(
            PlatformSavedData state, ResourceKey<Level> playerDimension, Vec3 playerPosition) {
        if (state == null || playerDimension == null || playerPosition == null
                || !Double.isFinite(playerPosition.x)
                || !Double.isFinite(playerPosition.y)
                || !Double.isFinite(playerPosition.z)) {
            return List.of();
        }
        return state.shopInstancesView().entrySet().stream()
                .filter(entry -> canAccess(entry.getValue(), playerDimension, playerPosition))
                .map(java.util.Map.Entry::getKey)
                .sorted()
                .toList();
    }

    private static Optional<ShopInstance.Stock> restock(ShopInstance.Stock stock, long gameTime) {
        if (stock.unlimited() || stock.restockAmount().isEmpty() || gameTime < stock.nextRestockGameTime()) {
            return Optional.of(stock);
        }
        long amount = stock.restockAmount().orElseThrow();
        long interval = stock.restockIntervalTicks().orElseThrow();
        try {
            long cycles = Math.addExact(Math.floorDiv(gameTime - stock.nextRestockGameTime(), interval), 1L);
            long room = stock.maximum() - stock.current();
            long cyclesToFill = room == 0 ? 0 : Math.addExact(Math.floorDiv(room - 1, amount), 1L);
            long added = cycles >= cyclesToFill ? room : Math.multiplyExact(cycles, amount);
            long next = Math.addExact(stock.nextRestockGameTime(), Math.multiplyExact(cycles, interval));
            return Optional.of(new ShopInstance.Stock(
                    false,
                    Math.addExact(stock.current(), added),
                    stock.maximum(),
                    stock.restockAmount(),
                    stock.restockIntervalTicks(),
                    next));
        } catch (ArithmeticException exception) {
            return Optional.empty();
        }
    }

    private static Optional<ShopInstance.Stock> changeStock(
            ShopInstance.Stock stock, Direction direction, long quantity) {
        if (stock.unlimited()) {
            return Optional.of(stock);
        }
        try {
            long current = direction == Direction.BUY
                    ? Math.subtractExact(stock.current(), quantity)
                    : Math.addExact(stock.current(), quantity);
            if (current < 0 || current > stock.maximum()) {
                return Optional.empty();
            }
            return Optional.of(new ShopInstance.Stock(
                    false,
                    current,
                    stock.maximum(),
                    stock.restockAmount(),
                    stock.restockIntervalTicks(),
                    stock.nextRestockGameTime()));
        } catch (ArithmeticException exception) {
            return Optional.empty();
        }
    }

    static List<ItemStack> copyInventory(List<ItemStack> inventory) {
        return inventory.stream().map(ItemStack::copy).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }

    static boolean addExact(List<ItemStack> inventory, ItemStack exact, int amount) {
        int remaining = amount;
        for (ItemStack slot : inventory) {
            if (ItemStack.isSameItemSameComponents(slot, exact) && slot.getCount() < slot.getMaxStackSize()) {
                int added = Math.min(remaining, slot.getMaxStackSize() - slot.getCount());
                slot.grow(added);
                remaining -= added;
                if (remaining == 0) {
                    return true;
                }
            }
        }
        for (int index = 0; index < inventory.size() && remaining > 0; index++) {
            if (inventory.get(index).isEmpty()) {
                int added = Math.min(remaining, exact.getMaxStackSize());
                inventory.set(index, exact.copyWithCount(added));
                remaining -= added;
            }
        }
        return remaining == 0;
    }

    static boolean removeExact(List<ItemStack> inventory, ItemStack exact, int amount) {
        if (countExact(inventory, exact) < amount) {
            return false;
        }
        int remaining = amount;
        for (int index = 0; index < inventory.size() && remaining > 0; index++) {
            ItemStack slot = inventory.get(index);
            if (ItemStack.isSameItemSameComponents(slot, exact)) {
                int removed = Math.min(remaining, slot.getCount());
                slot.shrink(removed);
                if (slot.isEmpty()) {
                    inventory.set(index, ItemStack.EMPTY);
                }
                remaining -= removed;
            }
        }
        return true;
    }

    static long countExact(List<ItemStack> inventory, ItemStack exact) {
        return inventory.stream()
                .filter(stack -> ItemStack.isSameItemSameComponents(stack, exact))
                .mapToLong(ItemStack::getCount)
                .sum();
    }

    static boolean replaceInventory(
            List<ItemStack> inventory, List<ItemStack> before, List<ItemStack> after) {
        try {
            for (int index = 0; index < inventory.size(); index++) {
                if (!ItemStack.matches(before.get(index), after.get(index))) {
                    inventory.set(index, after.get(index).copy());
                }
            }
            return true;
        } catch (RuntimeException exception) {
            restoreInventory(inventory, before);
            return false;
        }
    }

    static void restoreInventory(List<ItemStack> inventory, List<ItemStack> before) {
        for (int index = 0; index < inventory.size(); index++) {
            inventory.set(index, before.get(index).copy());
        }
    }

    private static String evidence(
            long balance,
            ShopInstance.Stock stock,
            long exactItems,
            ShopInstance.Offer offer,
            TradeRequest request,
            long serverUnitPrice,
            long total) {
        ItemStack item = offer.item();
        String stockValue = stock.unlimited() ? "unlimited" : Long.toString(stock.current());
        return "balance=" + balance + ";stock=" + stockValue + ";next_restock=" + stock.nextRestockGameTime()
                + ";exact_items=" + exactItems + ";units=" + request.quantity()
                + ";unit_price=" + serverUnitPrice + ";total=" + total
                + ";item=" + BuiltInRegistries.ITEM.getKey(item.getItem()) + "x" + item.getCount()
                + ";fingerprint=" + Integer.toUnsignedString(ItemStack.hashItemAndComponents(item), 16);
    }

    private static TradeResult denied(
            PlatformSavedData state,
            UUID playerId,
            TradeRequest request,
            Status status,
            long timestampEpochMillis) {
        if (playerId == null || request == null || request.shopId() == null || request.offerId() == null
                || timestampEpochMillis < 0) {
            return result(status, request, false);
        }
        UUID evidenceId = validTransactionId(request.transactionId())
                ? request.transactionId()
                : UUID.randomUUID();
        Optional<ShopInstance> shop = state.shopInstance(request.shopId());
        Optional<ShopInstance.Binding> binding = shop.flatMap(ShopInstance::binding);
        String snapshot = shop.map(value -> "balance=" + state.economyBalance(playerId).map(String::valueOf).orElse("none")
                        + ";offer=" + (value.offers().containsKey(request.offerId()) ? "present" : "missing"))
                .orElse("shop=missing");
        boolean audited = state.appendDeniedAudit(new AuditEntry(
                timestampEpochMillis,
                playerId,
                action("shop_trade_denied"),
                request.shopId() + "/" + request.offerId() + "/" + playerId,
                binding.map(value -> value.dimension().identifier()),
                binding.map(ShopInstance.Binding::position),
                snapshot,
                snapshot,
                status.name().toLowerCase(java.util.Locale.ROOT),
                evidenceId), DENIED_AUDIT_INTERVAL_MILLIS);
        return result(status, request, audited);
    }

    private static TradeResult result(Status status, TradeRequest request, boolean auditRecorded) {
        return new TradeResult(status, request == null ? null : request.transactionId(), auditRecorded);
    }

    private static Identifier action(String path) {
        return Identifier.fromNamespaceAndPath(Rovenfall.MOD_ID, path);
    }

    public enum Direction {
        BUY,
        SELL
    }

    public enum Status {
        SUCCESS,
        DUPLICATE_TRANSACTION,
        TRANSACTION_ID_CONFLICT,
        INVALID_REQUEST,
        INVALID_TRANSACTION,
        READ_ONLY_SCHEMA,
        TRANSACTION_LEDGER_FULL,
        SHOP_NOT_FOUND,
        DEPENDENCY_LOCKED,
        ACCESS_DENIED,
        OFFER_NOT_FOUND,
        OFFER_UNAVAILABLE,
        STALE_OFFER,
        ACCOUNT_NOT_FOUND,
        OVERFLOW,
        MAXIMUM_BALANCE_EXCEEDED,
        INSUFFICIENT_FUNDS,
        INSUFFICIENT_STOCK,
        STOCK_CAPACITY_EXCEEDED,
        INSUFFICIENT_ITEMS,
        INSUFFICIENT_SPACE,
        INVENTORY_UPDATE_FAILED
    }

    public record TradeRequest(
            Identifier shopId,
            Identifier offerId,
            Direction direction,
            int quantity,
            ItemStack expectedItem,
            long expectedUnitPrice,
            UUID transactionId) {
        public TradeRequest {
            expectedItem = expectedItem == null ? null : expectedItem.copy();
        }

        @Override
        public ItemStack expectedItem() {
            return expectedItem == null ? null : expectedItem.copy();
        }
    }

    public record TradeResult(Status status, UUID transactionId, boolean auditRecorded) {
    }
}
