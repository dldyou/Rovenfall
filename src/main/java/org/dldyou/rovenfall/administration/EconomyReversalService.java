package org.dldyou.rovenfall.administration;

import java.util.AbstractMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.dldyou.rovenfall.Rovenfall;
import org.dldyou.rovenfall.economy.ShopInstance;

/** Server-thread-only inverse transaction boundary. */
public final class EconomyReversalService {
    private static final UUID ZERO_UUID = new UUID(0L, 0L);
    private static final long DENIED_AUDIT_INTERVAL_MILLIS = 1_000L;

    private EconomyReversalService() {
    }

    public static Result reverse(
            PlatformSavedData state,
            ServerPlayer target,
            UUID actorId,
            boolean authorizationOverride,
            UUID originalTransactionId,
            EconomyTransactionReceipt.CompensationDecision decision,
            String reason,
            long timestampEpochMillis,
            UUID reversalTransactionId) {
        if (target == null || target.level().getServer() == null || !target.level().getServer().isSameThread()) {
            return new Result(Status.INVALID_REQUEST, reversalTransactionId, false);
        }
        Result result = reverse(
                state, target.getUUID(), target.getInventory().getNonEquipmentItems(), actorId,
                authorizationOverride, originalTransactionId, decision, reason, timestampEpochMillis,
                reversalTransactionId, EconomyConfig.maximumBalance());
        if (result.status() == Status.SUCCESS) {
            target.getInventory().setChanged();
        }
        return result;
    }

    static Result reverse(
            PlatformSavedData state,
            UUID playerId,
            List<ItemStack> inventory,
            UUID actorId,
            boolean authorizationOverride,
            UUID originalTransactionId,
            EconomyTransactionReceipt.CompensationDecision decision,
            String reason,
            long timestampEpochMillis,
            UUID reversalTransactionId,
            long maximumBalance) {
        if (state == null || !state.isWritable()) {
            return result(state == null ? Status.INVALID_REQUEST : Status.READ_ONLY_SCHEMA, reversalTransactionId, false);
        }
        if (!EconomyService.canManageEconomy(state, actorId, authorizationOverride)) {
            return denied(state, actorId, originalTransactionId, reversalTransactionId,
                    Status.UNAUTHORIZED, "unauthorized", timestampEpochMillis);
        }
        if (playerId == null || inventory == null || inventory.size() != Inventory.INVENTORY_SIZE
                || inventory.stream().anyMatch(stack -> stack == null) || actorId == null
                || originalTransactionId == null || decision == null || timestampEpochMillis < 0
                || maximumBalance < 0) {
            return result(Status.INVALID_REQUEST, reversalTransactionId, false);
        }
        if (!validTransactionId(reversalTransactionId)) {
            return denied(state, actorId, originalTransactionId, reversalTransactionId,
                    Status.INVALID_TRANSACTION, "invalid_transaction", timestampEpochMillis);
        }
        EconomyTransactionReceipt existing = state.economyReceipt(reversalTransactionId).orElse(null);
        if (existing != null) {
            if (existing.kind() == EconomyTransactionReceipt.Kind.REVERSAL
                    && existing.playerId().equals(playerId)
                    && existing.originalTransactionId().equals(Optional.of(originalTransactionId))) {
                return result(Status.DUPLICATE_TRANSACTION, reversalTransactionId, false);
            }
            return denied(state, actorId, originalTransactionId, reversalTransactionId,
                    Status.TRANSACTION_ID_CONFLICT, "transaction_id_conflict", timestampEpochMillis);
        }
        if (state.hasTransaction(reversalTransactionId, timestampEpochMillis)) {
            return denied(state, actorId, originalTransactionId, reversalTransactionId,
                    Status.TRANSACTION_ID_CONFLICT, "transaction_id_conflict", timestampEpochMillis);
        }
        Optional<String> validReason = validReason(reason);
        if (validReason.isEmpty()) {
            return denied(state, actorId, originalTransactionId, reversalTransactionId,
                    Status.INVALID_REASON, "invalid_reason", timestampEpochMillis);
        }
        EconomyTransactionReceipt original = state.economyReceipt(originalTransactionId).orElse(null);
        if (original == null || !state.hasTransaction(originalTransactionId, timestampEpochMillis)
                || original.kind() == EconomyTransactionReceipt.Kind.ACCOUNT_CREATE
                || original.kind() == EconomyTransactionReceipt.Kind.REVERSAL
                || original.invalidatedByRestore().isPresent()) {
            return denied(state, actorId, originalTransactionId, reversalTransactionId,
                    Status.ORIGINAL_NOT_REVERSIBLE, "original_not_reversible", timestampEpochMillis);
        }
        if (!original.playerId().equals(playerId)) {
            return denied(state, actorId, originalTransactionId, reversalTransactionId,
                    Status.TARGET_MISMATCH, "target_mismatch", timestampEpochMillis);
        }
        if (original.kind() != EconomyTransactionReceipt.Kind.PURCHASE
                && decision != EconomyTransactionReceipt.CompensationDecision.NONE) {
            return denied(state, actorId, originalTransactionId, reversalTransactionId,
                    Status.INVALID_REQUEST, "invalid_compensation_decision", timestampEpochMillis);
        }
        if (original.reversedBy().isPresent()) {
            return denied(state, actorId, originalTransactionId, reversalTransactionId,
                    Status.ALREADY_REVERSED, "already_reversed", timestampEpochMillis);
        }
        if (!state.canCommitReversalTransaction(
                reversalTransactionId, originalTransactionId, timestampEpochMillis)) {
            return denied(state, actorId, originalTransactionId, reversalTransactionId,
                    Status.TRANSACTION_LEDGER_FULL, "transaction_ledger_full", timestampEpochMillis);
        }

        boolean shopExists = original.isTrade()
                && state.shopInstance(original.shopId().orElseThrow()).isPresent();
        Optional<ShopInstanceService.DependencyLease> lease = shopExists
                ? ShopInstanceService.tryAcquireDependencyLock(state, original.shopId().orElseThrow())
                : Optional.empty();
        if (shopExists && lease.isEmpty()) {
            return denied(state, actorId, originalTransactionId, reversalTransactionId,
                    Status.DEPENDENCY_LOCKED, "dependency_locked", timestampEpochMillis);
        }
        try (var ignored = lease.orElse(null)) {

        long beforeBalance = state.economyBalance(playerId).orElse(-1L);
        if (beforeBalance < 0) {
            return denied(state, actorId, originalTransactionId, reversalTransactionId,
                    Status.ACCOUNT_NOT_FOUND, "account_not_found", timestampEpochMillis);
        }
        boolean originalCredited = switch (original.kind()) {
            case ADMIN_GRANT, AWARD, SALE -> true;
            case ADMIN_DEBIT, DEBIT, PURCHASE -> false;
            default -> throw new IllegalStateException("Unsupported reversal kind " + original.kind());
        };
        long afterBalance;
        List<EconomyAlert> alerts;
        try {
            afterBalance = originalCredited
                    ? Math.subtractExact(beforeBalance, original.amount())
                    : Math.addExact(beforeBalance, original.amount());
        } catch (ArithmeticException exception) {
            return denied(state, actorId, originalTransactionId, reversalTransactionId,
                    Status.OVERFLOW, "overflow", timestampEpochMillis);
        }
        if (afterBalance < 0) {
            return denied(state, actorId, originalTransactionId, reversalTransactionId,
                    Status.INSUFFICIENT_FUNDS, "insufficient_funds", timestampEpochMillis);
        }
        if (afterBalance > maximumBalance) {
            return denied(state, actorId, originalTransactionId, reversalTransactionId,
                    Status.MAXIMUM_BALANCE_EXCEEDED, "maximum_balance_exceeded", timestampEpochMillis);
        }

        List<ItemStack> beforeInventory = ShopTradeService.copyInventory(inventory);
        List<ItemStack> afterInventory = ShopTradeService.copyInventory(inventory);
        Optional<Map.Entry<Identifier, ShopInstance>> changedShop = Optional.empty();
        EconomyTransactionReceipt.CompensationDecision recordedDecision = EconomyTransactionReceipt.CompensationDecision.NONE;
        String legs = "balance";
        if (original.isTrade()) {
            TradeInverse inverse = prepareTradeInverse(state, original, afterInventory);
            if (inverse.status != Status.SUCCESS) {
                if (original.kind() != EconomyTransactionReceipt.Kind.PURCHASE
                        || decision != EconomyTransactionReceipt.CompensationDecision.REFUND_WITHOUT_ITEMS_OR_STOCK) {
                    Status failure = original.kind() == EconomyTransactionReceipt.Kind.PURCHASE
                            ? Status.COMPENSATION_REQUIRED : inverse.status;
                    return denied(state, actorId, originalTransactionId, reversalTransactionId, failure,
                            failure.name().toLowerCase(Locale.ROOT), timestampEpochMillis);
                }
                recordedDecision = decision;
                legs = "balance;skipped=items,stock;cause=" + inverse.status.name().toLowerCase(Locale.ROOT);
            } else {
                changedShop = Optional.of(new AbstractMap.SimpleImmutableEntry<>(
                        original.shopId().orElseThrow(), inverse.shop));
                legs = "balance,items,stock";
            }
        }

        if (!ShopTradeService.replaceInventory(inventory, beforeInventory, afterInventory)) {
            return denied(state, actorId, originalTransactionId, reversalTransactionId,
                    Status.INVENTORY_UPDATE_FAILED, "inventory_update_failed", timestampEpochMillis);
        }
        EconomyTransactionReceipt receipt = new EconomyTransactionReceipt(
                timestampEpochMillis, actorId, playerId, EconomyTransactionReceipt.Kind.REVERSAL, original.amount(),
                Optional.empty(), Optional.empty(), Optional.empty(), 0, Optional.empty(), Optional.empty(),
                Optional.of(originalTransactionId), Optional.empty(), Optional.empty(), recordedDecision);
        try {
            Optional<ShopInstance.Binding> binding = changedShop.flatMap(entry -> entry.getValue().binding());
            alerts = EconomyMonitoringService.evaluate(
                    state, reversalTransactionId, receipt, EconomyConfig.alertThresholds());
            state.commitEconomyReversal(
                    playerId, afterBalance, changedShop, originalTransactionId, reversalTransactionId,
                    timestampEpochMillis, receipt,
                    alerts,
                    new AuditEntry(
                            timestampEpochMillis, actorId, action("economy_transaction_reversal"),
                            "transaction:" + originalTransactionId,
                            binding.map(value -> value.dimension().identifier()),
                            binding.map(ShopInstance.Binding::position),
                            "balance=" + beforeBalance + ";reversed_by=none",
                            "balance=" + afterBalance + ";reversed_by=" + reversalTransactionId + ";legs=" + legs,
                            validReason.orElseThrow(), reversalTransactionId));
        } catch (RuntimeException exception) {
            ShopTradeService.restoreInventory(inventory, beforeInventory);
            throw exception;
        }
        EconomyMonitoringService.publish(alerts);
        return result(Status.SUCCESS, reversalTransactionId, true);
        }
    }

    private static TradeInverse prepareTradeInverse(
            PlatformSavedData state, EconomyTransactionReceipt original, List<ItemStack> inventory) {
        ShopInstance shop = state.shopInstance(original.shopId().orElseThrow()).orElse(null);
        if (shop == null) {
            return new TradeInverse(Status.SHOP_MISMATCH, null);
        }
        ShopInstance.Offer offer = shop.offers().get(original.offerId().orElseThrow());
        ItemStack exact = original.item().orElseThrow();
        if (offer == null || !ItemStack.matches(offer.item(), exact)
                || !sameStockPolicy(offer.stock(), original.stockAfter().orElseThrow())) {
            return new TradeInverse(Status.SHOP_MISMATCH, null);
        }
        int itemCount;
        try {
            itemCount = Math.multiplyExact(exact.getCount(), original.quantity());
        } catch (ArithmeticException exception) {
            return new TradeInverse(Status.OVERFLOW, null);
        }
        ShopInstance.Stock stock = offer.stock();
        ShopInstance.Stock changedStock;
        if (stock.unlimited()) {
            changedStock = stock;
        } else {
            long current;
            try {
                current = original.kind() == EconomyTransactionReceipt.Kind.PURCHASE
                        ? Math.addExact(stock.current(), original.quantity())
                        : Math.subtractExact(stock.current(), original.quantity());
            } catch (ArithmeticException exception) {
                return new TradeInverse(Status.OVERFLOW, null);
            }
            if (current < 0 || current > stock.maximum()) {
                return new TradeInverse(Status.STOCK_INVERSE_UNAVAILABLE, null);
            }
            changedStock = new ShopInstance.Stock(
                    false, current, stock.maximum(), stock.restockAmount(), stock.restockIntervalTicks(),
                    stock.nextRestockGameTime());
        }
        boolean inventoryReady = original.kind() == EconomyTransactionReceipt.Kind.PURCHASE
                ? ShopTradeService.removeExact(inventory, exact, itemCount)
                : ShopTradeService.addExact(inventory, exact, itemCount);
        if (!inventoryReady) {
            return new TradeInverse(original.kind() == EconomyTransactionReceipt.Kind.PURCHASE
                    ? Status.EXACT_ITEMS_UNAVAILABLE : Status.INSUFFICIENT_SPACE, null);
        }
        ShopInstance changedShop = shop.withOffer(original.offerId().orElseThrow(), new ShopInstance.Offer(
                offer.item(), offer.buyPrice(), offer.sellPrice(), changedStock));
        return new TradeInverse(Status.SUCCESS, changedShop);
    }

    private static boolean sameStockPolicy(ShopInstance.Stock current, ShopInstance.Stock original) {
        return current.unlimited() == original.unlimited()
                && current.maximum() == original.maximum()
                && current.restockAmount().equals(original.restockAmount())
                && current.restockIntervalTicks().equals(original.restockIntervalTicks());
    }

    private static Result denied(
            PlatformSavedData state,
            UUID actorId,
            UUID originalTransactionId,
            UUID reversalTransactionId,
            Status status,
            String reason,
            long timestampEpochMillis) {
        if (actorId == null || originalTransactionId == null || timestampEpochMillis < 0) {
            return result(status, reversalTransactionId, false);
        }
        UUID auditId = validTransactionId(reversalTransactionId) ? reversalTransactionId : UUID.randomUUID();
        boolean audited = state.appendDeniedAudit(new AuditEntry(
                timestampEpochMillis, actorId, action("economy_transaction_reversal_denied"),
                "transaction:" + originalTransactionId, Optional.empty(), Optional.empty(),
                "unchanged", "unchanged", reason, auditId), DENIED_AUDIT_INTERVAL_MILLIS);
        return result(status, reversalTransactionId, audited);
    }

    private static boolean validTransactionId(UUID transactionId) {
        return transactionId != null && !ZERO_UUID.equals(transactionId);
    }

    private static Optional<String> validReason(String reason) {
        String normalized = reason == null ? "" : reason.strip();
        return normalized.isEmpty() || normalized.length() > AdministrationService.MAX_REASON_LENGTH
                ? Optional.empty() : Optional.of(normalized);
    }

    private static Result result(Status status, UUID transactionId, boolean auditRecorded) {
        return new Result(status, transactionId, auditRecorded);
    }

    private static Identifier action(String path) {
        return Identifier.fromNamespaceAndPath(Rovenfall.MOD_ID, path);
    }

    private record TradeInverse(Status status, ShopInstance shop) {
    }

    public enum Status {
        SUCCESS,
        DUPLICATE_TRANSACTION,
        TRANSACTION_ID_CONFLICT,
        ALREADY_REVERSED,
        COMPENSATION_REQUIRED,
        UNAUTHORIZED,
        INVALID_REQUEST,
        INVALID_TRANSACTION,
        INVALID_REASON,
        READ_ONLY_SCHEMA,
        TRANSACTION_LEDGER_FULL,
        DEPENDENCY_LOCKED,
        ORIGINAL_NOT_REVERSIBLE,
        TARGET_MISMATCH,
        ACCOUNT_NOT_FOUND,
        OVERFLOW,
        INSUFFICIENT_FUNDS,
        MAXIMUM_BALANCE_EXCEEDED,
        SHOP_MISMATCH,
        EXACT_ITEMS_UNAVAILABLE,
        STOCK_INVERSE_UNAVAILABLE,
        INSUFFICIENT_SPACE,
        INVENTORY_UPDATE_FAILED
    }

    public record Result(Status status, UUID transactionId, boolean auditRecorded) {
    }
}
