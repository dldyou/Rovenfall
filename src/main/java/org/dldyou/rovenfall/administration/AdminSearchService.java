package org.dldyou.rovenfall.administration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import org.dldyou.rovenfall.activities.ActivityProgress;
import org.dldyou.rovenfall.claims.Claim;
import org.dldyou.rovenfall.claims.ClaimKey;
import org.dldyou.rovenfall.careers.PlayerCareerState;
import org.dldyou.rovenfall.economy.ShopInstance;

/** Read-only, bounded administrator search assembled outside gameplay hot paths. */
final class AdminSearchService {
    static final int MAX_PAGE_SIZE = 50;
    static final int MAX_QUERY_LENGTH = 128;

    private AdminSearchService() {
    }

    static Page search(
            PlatformSavedData state,
            UUID actorId,
            boolean authorizationOverride,
            Scope scope,
            String query,
            int page,
            int pageSize) {
        if (state == null || actorId == null || !(authorizationOverride || state.hasAdminRole(actorId))) {
            return Page.failure(Status.UNAUTHORIZED, scope, query, page);
        }
        if (scope == null) {
            return Page.failure(Status.INVALID_SCOPE, null, query, page);
        }
        String normalized = normalize(query);
        if (normalized == null) {
            return Page.failure(Status.INVALID_QUERY, scope, query, page);
        }
        if (page < 0 || pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            return Page.failure(Status.INVALID_PAGE, scope, query, page);
        }

        List<Row> rows = switch (scope) {
            case PLAYERS -> players(state);
            case BALANCES -> balances(state);
            case TRANSACTIONS -> transactions(state);
            case CLAIMS -> claims(state);
            case SHOPS -> shops(state);
            case DENIED -> denied(state);
            case ALERTS -> alerts(state);
        };
        List<Row> matching = normalized.equals("*")
                ? rows
                : rows.stream().filter(row -> matches(state, row, normalized)).toList();
        int totalEntries = matching.size();
        int totalPages = totalEntries == 0 ? 0 : (totalEntries + pageSize - 1) / pageSize;
        long offset = (long) page * pageSize;
        List<Row> entries = offset >= totalEntries
                ? List.of()
                : matching.subList((int) offset, Math.min(totalEntries, (int) offset + pageSize));
        return new Page(Status.SUCCESS, scope, query.strip(), page, totalPages, totalEntries, entries);
    }

    private static List<Row> players(PlatformSavedData state) {
        Map<UUID, AdminRole> roles = state.adminRolesView();
        Map<UUID, PlayerRecord> records = state.playerRecordsView();
        Map<UUID, Long> balances = state.economyBalancesView();
        Map<UUID, ActivityProgress> activities = state.activityProgressView();
        Map<UUID, PlayerCareerState> careers = state.playerCareersView();
        Map<ClaimKey, Claim> claims = state.claimsView();
        TreeSet<UUID> ids = new TreeSet<>();
        ids.addAll(roles.keySet());
        ids.addAll(records.keySet());
        ids.addAll(balances.keySet());
        ids.addAll(activities.keySet());
        ids.addAll(careers.keySet());
        claims.values().forEach(claim -> {
            ids.add(claim.ownerId());
            ids.addAll(claim.trustedRoles().keySet());
            claim.pendingTransferTo().ifPresent(ids::add);
        });
        return ids.stream().<Row>map(id -> {
            ActivityProgress activity = activities.getOrDefault(id, ActivityProgress.empty());
            long totalActivity = activity.experience().values().stream().mapToLong(Long::longValue).sum();
            PlayerCareerState career = careers.getOrDefault(id, PlayerCareerState.empty());
            return new PlayerRow(
                    id,
                    Optional.ofNullable(roles.get(id)),
                    Optional.ofNullable(records.get(id)),
                    Optional.ofNullable(balances.get(id)),
                    totalActivity,
                    career.activeCareer(),
                    career.learnedCareers().size(),
                    state.claimCount(id));
        }).toList();
    }

    private static List<Row> balances(PlatformSavedData state) {
        return state.economyBalancesView().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .<Row>map(entry -> new BalanceRow(entry.getKey(), entry.getValue()))
                .toList();
    }

    private static List<Row> transactions(PlatformSavedData state) {
        return state.economyReceiptsView().entrySet().stream()
                .sorted(Comparator.<Map.Entry<UUID, EconomyTransactionReceipt>>comparingLong(
                                entry -> entry.getValue().timestampEpochMillis())
                        .reversed().thenComparing(Map.Entry::getKey))
                .<Row>map(entry -> new TransactionRow(entry.getKey(), entry.getValue()))
                .toList();
    }

    private static List<Row> claims(PlatformSavedData state) {
        return state.claimsView().entrySet().stream()
                .sorted(Comparator.comparing(entry -> entry.getKey().auditTarget()))
                .<Row>map(entry -> new ClaimRow(entry.getKey(), entry.getValue()))
                .toList();
    }

    private static List<Row> shops(PlatformSavedData state) {
        return state.shopInstancesView().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .<Row>map(entry -> new ShopRow(entry.getKey(), entry.getValue()))
                .toList();
    }

    private static List<Row> denied(PlatformSavedData state) {
        return state.auditEntriesView().stream()
                .filter(entry -> entry.actionType().toString().toLowerCase(Locale.ROOT).contains("denied"))
                .sorted(Comparator.comparingLong(AuditEntry::timestampEpochMillis).reversed()
                        .thenComparing(AuditEntry::transactionId))
                .<Row>map(DeniedRow::new)
                .toList();
    }

    private static List<Row> alerts(PlatformSavedData state) {
        return state.economyAlertsView().stream()
                .sorted(Comparator.comparingLong(EconomyAlert::timestampEpochMillis).reversed()
                        .thenComparing(EconomyAlert::transactionId)
                        .thenComparing(alert -> alert.type().getSerializedName()))
                .<Row>map(AlertRow::new)
                .toList();
    }

    private static String normalize(String query) {
        if (query == null) {
            return null;
        }
        String normalized = query.strip().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() || normalized.length() > MAX_QUERY_LENGTH ? null : normalized;
    }

    private static boolean matches(PlatformSavedData state, Row row, String normalized) {
        if (row.searchText().contains(normalized)) {
            return true;
        }
        return relatedPlayerIds(row).stream()
                .map(state::playerRecord)
                .flatMap(Optional::stream)
                .map(PlayerRecord::lastKnownName)
                .map(name -> name.toLowerCase(Locale.ROOT))
                .anyMatch(name -> name.contains(normalized));
    }

    private static List<UUID> relatedPlayerIds(Row row) {
        if (row instanceof PlayerRow value) {
            return List.of(value.playerId());
        }
        if (row instanceof BalanceRow value) {
            return List.of(value.playerId());
        }
        if (row instanceof TransactionRow value) {
            return List.of(value.receipt().actorId(), value.receipt().playerId());
        }
        if (row instanceof ClaimRow value) {
            List<UUID> ids = new ArrayList<>();
            ids.add(value.claim().ownerId());
            ids.addAll(value.claim().trustedRoles().keySet());
            value.claim().pendingTransferTo().ifPresent(ids::add);
            return ids;
        }
        if (row instanceof DeniedRow value) {
            return List.of(value.entry().actorId());
        }
        if (row instanceof AlertRow value) {
            return List.of(value.alert().playerId());
        }
        return List.of();
    }

    private static String searchable(Object... values) {
        return Arrays.stream(values)
                .filter(java.util.Objects::nonNull)
                .map(Object::toString)
                .map(value -> value.toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    enum Scope {
        PLAYERS("players"),
        BALANCES("balances"),
        TRANSACTIONS("transactions"),
        CLAIMS("claims"),
        SHOPS("shops"),
        DENIED("denied"),
        ALERTS("alerts");

        private final String id;

        Scope(String id) {
            this.id = id;
        }

        String id() {
            return id;
        }

        String translationKey() {
            return "admin_search_scope.rovenfall." + id;
        }

        static Optional<Scope> parse(String id) {
            if (id == null) {
                return Optional.empty();
            }
            return Arrays.stream(values())
                    .filter(scope -> scope.id.equals(id.toLowerCase(Locale.ROOT)))
                    .findFirst();
        }

        static String[] ids() {
            return Arrays.stream(values()).map(Scope::id).toArray(String[]::new);
        }
    }

    enum Status {
        SUCCESS,
        UNAUTHORIZED,
        INVALID_SCOPE,
        INVALID_QUERY,
        INVALID_PAGE
    }

    record Page(
            Status status,
            Scope scope,
            String query,
            int page,
            int totalPages,
            int totalEntries,
            List<Row> entries) {
        Page {
            query = query == null ? "" : query;
            entries = entries == null ? List.of() : List.copyOf(entries);
        }

        static Page failure(Status status, Scope scope, String query, int page) {
            return new Page(status, scope, query, page, 0, 0, List.of());
        }
    }

    interface Row {
        String searchText();
    }

    record PlayerRow(
            UUID playerId,
            Optional<AdminRole> adminRole,
            Optional<PlayerRecord> record,
            Optional<Long> balance,
            long totalActivityExperience,
            Optional<Identifier> activeCareer,
            int learnedCareers,
            int claims) implements Row {
        @Override
        public String searchText() {
            return searchable(playerId, record.map(PlayerRecord::lastKnownName).orElse(""),
                    adminRole.map(AdminRole::getSerializedName).orElse(""),
                    activeCareer.map(Identifier::toString).orElse(""));
        }
    }

    record BalanceRow(UUID playerId, long balance) implements Row {
        @Override
        public String searchText() {
            return searchable(playerId, balance);
        }
    }

    record TransactionRow(UUID transactionId, EconomyTransactionReceipt receipt) implements Row {
        @Override
        public String searchText() {
            return searchable(
                    transactionId,
                    receipt.actorId(),
                    receipt.playerId(),
                    receipt.kind().getSerializedName(),
                    receipt.amount(),
                    receipt.claim().map(ClaimKey::auditTarget).orElse(""),
                    receipt.shopId().map(Identifier::toString).orElse(""),
                    receipt.offerId().map(Identifier::toString).orElse(""),
                    receipt.item().map(stack -> BuiltInRegistries.ITEM.getKey(stack.getItem())).orElse(null),
                    receipt.originalTransactionId().map(UUID::toString).orElse(""),
                    receipt.reversedBy().map(UUID::toString).orElse(""));
        }
    }

    record ClaimRow(ClaimKey key, Claim claim) implements Row {
        @Override
        public String searchText() {
            List<Object> values = new ArrayList<>();
            values.add(key.auditTarget());
            values.add(claim.ownerId());
            values.add(claim.purchasePrice());
            values.addAll(claim.trustedRoles().keySet());
            values.addAll(claim.trustedRoles().values());
            claim.pendingTransferTo().ifPresent(values::add);
            return searchable(values.toArray());
        }
    }

    record ShopRow(Identifier shopId, ShopInstance shop) implements Row {
        @Override
        public String searchText() {
            List<Object> values = new ArrayList<>();
            values.add(shopId);
            values.add(shop.templateId());
            shop.binding().ifPresent(binding -> {
                values.add(binding.dimension().identifier());
                values.add(binding.position().toShortString());
            });
            shop.offers().forEach((offerId, offer) -> {
                values.add(offerId);
                values.add(BuiltInRegistries.ITEM.getKey(offer.item().getItem()));
            });
            return searchable(values.toArray());
        }
    }

    record DeniedRow(AuditEntry entry) implements Row {
        @Override
        public String searchText() {
            return searchable(entry.timestampEpochMillis(), entry.actionType(), entry.target(), entry.actorId(),
                    entry.transactionId(), entry.reason(),
                    entry.dimension().map(Identifier::toString).orElse(""),
                    entry.position().map(position -> position.toShortString()).orElse(""));
        }
    }

    record AlertRow(EconomyAlert alert) implements Row {
        @Override
        public String searchText() {
            return searchable(alert.timestampEpochMillis(), alert.type().getSerializedName(), alert.playerId(),
                    alert.transactionId(), alert.observedValue(), alert.threshold());
        }
    }
}
