package org.dldyou.rovenfall.administration;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.minecraft.resources.Identifier;
import org.dldyou.rovenfall.economy.ShopInstance;

/** Bounded typed projections for the interactive economy administration menu. */
final class AdministrationEconomyViewService {
    static final int MAX_SCANNED_ROWS = AdministrationReadViewService.MAX_SCANNED_ROWS;
    static final int PAGE_SIZE = AdministrationReadViewService.MAX_PAGE_SIZE;

    private AdministrationEconomyViewService() {
    }

    static Page<PlayerRow> players(
            PlatformSavedData state, UUID actorId, boolean authorizationOverride, String query, int page) {
        if (!authorized(state, actorId, authorizationOverride)) {
            return Page.denied(page);
        }
        UUID exactPlayerId = parseUuid(query);
        if (exactPlayerId != null) {
            List<PlayerRow> exact = state.playerRecord(exactPlayerId).stream()
                    .map(record -> new PlayerRow(
                            exactPlayerId, record.displayName().orElse(""), state.economyBalance(exactPlayerId),
                            record.firstSeenEpochMillis(), record.lastSeenEpochMillis()))
                    .toList();
            return filterAndPage(exact, query, page, false, row -> row.displayName() + " " + row.playerId());
        }
        List<PlayerRow> source = state.playerRecords(MAX_SCANNED_ROWS).stream()
                .map(entry -> new PlayerRow(
                        entry.getKey(), entry.getValue().displayName().orElse(""),
                        state.economyBalance(entry.getKey()),
                        entry.getValue().firstSeenEpochMillis(), entry.getValue().lastSeenEpochMillis()))
                .sorted(Comparator.comparing(PlayerRow::displayName, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(PlayerRow::playerId))
                .toList();
        return filterAndPage(source, query, page, state.playerRecordCount() > MAX_SCANNED_ROWS,
                row -> row.displayName() + " " + row.playerId());
    }

    static Page<ShopRow> shops(
            PlatformSavedData state, UUID actorId, boolean authorizationOverride, String query, int page) {
        if (!authorized(state, actorId, authorizationOverride)) {
            return Page.denied(page);
        }
        List<ShopRow> source = state.shopInstances(MAX_SCANNED_ROWS).stream()
                .map(entry -> new ShopRow(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparing(ShopRow::shopId))
                .toList();
        return filterAndPage(source, query, page, state.shopInstanceCount() > MAX_SCANNED_ROWS,
                row -> row.shopId() + " " + row.shop().templateId());
    }

    static Page<ReceiptRow> receipts(
            PlatformSavedData state,
            UUID actorId,
            boolean authorizationOverride,
            UUID playerFilter,
            String query,
            int page) {
        if (!authorized(state, actorId, authorizationOverride)) {
            return Page.denied(page);
        }
        UUID exactTransactionId = parseUuid(query);
        if (exactTransactionId != null) {
            List<ReceiptRow> exact = state.economyReceipt(exactTransactionId).stream()
                    .map(receipt -> new ReceiptRow(exactTransactionId, receipt))
                    .filter(row -> playerFilter == null || row.receipt().playerId().equals(playerFilter))
                    .toList();
            return filterAndPage(exact, query, page, false,
                    row -> row.transactionId() + " " + row.receipt().playerId() + " "
                            + row.receipt().kind().getSerializedName());
        }
        List<ReceiptRow> source = state.economyReceipts(MAX_SCANNED_ROWS).stream()
                .map(entry -> new ReceiptRow(entry.getKey(), entry.getValue()))
                .filter(row -> playerFilter == null || row.receipt().playerId().equals(playerFilter))
                .toList();
        return filterAndPage(source, query, page, state.economyReceiptCount() > MAX_SCANNED_ROWS,
                row -> row.transactionId() + " " + row.receipt().playerId() + " "
                        + row.receipt().kind().getSerializedName());
    }

    static boolean authorized(PlatformSavedData state, UUID actorId, boolean authorizationOverride) {
        return state != null && actorId != null && (authorizationOverride || state.hasAdminRole(actorId));
    }

    private static UUID parseUuid(String query) {
        if (query == null) {
            return null;
        }
        try {
            return UUID.fromString(query.strip());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static <T> Page<T> filterAndPage(
            List<T> source,
            String query,
            int page,
            boolean truncated,
            java.util.function.Function<T, String> searchText) {
        if (query == null || query.length() > AdministrationReadViewService.MAX_QUERY_LENGTH || page < 0) {
            return Page.invalid(page);
        }
        String needle = query.strip().toLowerCase(Locale.ROOT);
        List<T> matches = source.stream()
                .filter(value -> needle.isEmpty()
                        || searchText.apply(value).toLowerCase(Locale.ROOT).contains(needle))
                .toList();
        int totalPages = matches.isEmpty() ? 0 : (matches.size() + PAGE_SIZE - 1) / PAGE_SIZE;
        long offset = (long) page * PAGE_SIZE;
        List<T> entries = offset >= matches.size()
                ? List.of()
                : matches.subList((int) offset, Math.min(matches.size(), (int) offset + PAGE_SIZE));
        return new Page<>(Status.SUCCESS, page, totalPages, matches.size(), entries, truncated);
    }

    enum Status {
        SUCCESS,
        UNAUTHORIZED,
        INVALID_REQUEST
    }

    record Page<T>(Status status, int page, int totalPages, int totalEntries, List<T> entries, boolean truncated) {
        Page {
            entries = List.copyOf(entries);
        }

        static <T> Page<T> denied(int page) {
            return new Page<>(Status.UNAUTHORIZED, page, 0, 0, List.of(), false);
        }

        static <T> Page<T> invalid(int page) {
            return new Page<>(Status.INVALID_REQUEST, page, 0, 0, List.of(), false);
        }
    }

    record PlayerRow(UUID playerId, String displayName, java.util.Optional<Long> balance, long firstSeen, long lastSeen) {
        PlayerRow {
            displayName = displayName == null ? "" : displayName;
            balance = balance == null ? java.util.Optional.empty() : balance;
        }
    }

    record ShopRow(Identifier shopId, ShopInstance shop) {
    }

    record ReceiptRow(UUID transactionId, EconomyTransactionReceipt receipt) {
    }
}
