package org.dldyou.rovenfall.administration;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

final class EconomyObservabilityService {
    static final int MAX_PAGE_SIZE = 50;

    private EconomyObservabilityService() {
    }

    static Page<BalanceRow> balances(
            PlatformSavedData state, UUID actorId, boolean authorizationOverride, int page, int pageSize) {
        if (!authorized(state, actorId, authorizationOverride)) {
            return Page.unauthorized(page);
        }
        return paginate(state.economyBalancesView().entrySet().stream()
                .sorted(java.util.Map.Entry.comparingByKey())
                .map(entry -> new BalanceRow(entry.getKey(), entry.getValue()))
                .toList(), page, pageSize);
    }

    static Page<TransactionRow> transactions(
            PlatformSavedData state, UUID actorId, boolean authorizationOverride, int page, int pageSize) {
        if (!authorized(state, actorId, authorizationOverride)) {
            return Page.unauthorized(page);
        }
        return paginate(state.economyReceiptsView().entrySet().stream()
                .sorted(Comparator.<java.util.Map.Entry<UUID, EconomyTransactionReceipt>>comparingLong(
                                entry -> entry.getValue().timestampEpochMillis())
                        .reversed().thenComparing(java.util.Map.Entry::getKey))
                .map(entry -> new TransactionRow(entry.getKey(), entry.getValue()))
                .toList(), page, pageSize);
    }

    static Page<ShopRow> shops(
            PlatformSavedData state, UUID actorId, boolean authorizationOverride, int page, int pageSize) {
        if (!authorized(state, actorId, authorizationOverride)) {
            return Page.unauthorized(page);
        }
        List<ShopRow> rows = new ArrayList<>();
        state.shopInstancesView().entrySet().stream()
                .sorted(java.util.Map.Entry.comparingByKey())
                .forEach(shop -> shop.getValue().offers().entrySet().stream()
                        .sorted(java.util.Map.Entry.comparingByKey())
                        .forEach(offer -> rows.add(new ShopRow(
                                shop.getKey(), offer.getKey(), offer.getValue().item(), offer.getValue().stock()))));
        return paginate(rows, page, pageSize);
    }

    static Page<EconomyAlert> alerts(
            PlatformSavedData state, UUID actorId, boolean authorizationOverride, int page, int pageSize) {
        if (!authorized(state, actorId, authorizationOverride)) {
            return Page.unauthorized(page);
        }
        return paginate(state.economyAlertsView().stream()
                .sorted(Comparator.comparingLong(EconomyAlert::timestampEpochMillis).reversed()
                        .thenComparing(EconomyAlert::transactionId)
                        .thenComparing(alert -> alert.type().getSerializedName()))
                .toList(), page, pageSize);
    }

    private static boolean authorized(PlatformSavedData state, UUID actorId, boolean authorizationOverride) {
        return state != null && actorId != null && (authorizationOverride || state.hasAdminRole(actorId));
    }

    private static <T> Page<T> paginate(List<T> values, int page, int pageSize) {
        if (page < 0 || pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            return new Page<>(Status.INVALID_PAGE, page, 0, values.size(), List.of());
        }
        int totalPages = values.isEmpty() ? 0 : (values.size() + pageSize - 1) / pageSize;
        long start = (long) page * pageSize;
        if (start >= values.size()) {
            return new Page<>(Status.SUCCESS, page, totalPages, values.size(), List.of());
        }
        int from = (int) start;
        return new Page<>(Status.SUCCESS, page, totalPages, values.size(),
                values.subList(from, Math.min(values.size(), from + pageSize)));
    }

    enum Status {
        SUCCESS,
        UNAUTHORIZED,
        INVALID_PAGE
    }

    record Page<T>(Status status, int page, int totalPages, int totalEntries, List<T> entries) {
        Page {
            entries = List.copyOf(entries);
        }

        static <T> Page<T> unauthorized(int page) {
            return new Page<>(Status.UNAUTHORIZED, page, 0, 0, List.of());
        }
    }

    record BalanceRow(UUID playerId, long balance) {
    }

    record TransactionRow(UUID transactionId, EconomyTransactionReceipt receipt) {
    }

    record ShopRow(Identifier shopId, Identifier offerId, ItemStack item, org.dldyou.rovenfall.economy.ShopInstance.Stock stock) {
        ShopRow {
            item = item.copy();
        }

        @Override
        public ItemStack item() {
            return item.copy();
        }
    }
}
