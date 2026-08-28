package org.dldyou.rovenfall.administration;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import org.dldyou.rovenfall.rpg.RpgPlayerSavedData;

/** Bounded, immutable projections used by the administration control center. */
public final class AdministrationReadViewService {
    public static final int MAX_QUERY_LENGTH = 64;
    public static final int MAX_PAGE_SIZE = 36;
    public static final int MAX_SCANNED_ROWS = 1_000;
    private static final int SOURCE_PAGE_SIZE = 50;

    private AdministrationReadViewService() {
    }

    public static Page query(
            MinecraftServer server,
            UUID actorId,
            boolean authorizationOverride,
            AdminRole role,
            Domain domain,
            Filter filter,
            String query,
            int page,
            int pageSize,
            long generatedAtEpochMillis) {
        if (server == null || actorId == null || role == null || domain == null || filter == null
                || !domain.allowedFor(role) || query == null || query.length() > MAX_QUERY_LENGTH
                || page < 0 || pageSize < 1 || pageSize > MAX_PAGE_SIZE || generatedAtEpochMillis < 0) {
            return Page.invalid(page);
        }
        PlatformSavedData platform = PlatformSavedData.get(server);
        if (!authorizationOverride && platform.roleOf(actorId).filter(role::equals).isEmpty()) {
            return Page.unauthorized(page);
        }

        SourceRows source = rows(server, platform, actorId, authorizationOverride, domain, generatedAtEpochMillis);
        if (source.unauthorized()) {
            return Page.unauthorized(page);
        }
        return filterAndPage(source.rows(), source.truncated(), filter, query, page, pageSize);
    }

    static Page filterAndPage(
            List<Row> rows,
            boolean truncated,
            Filter filter,
            String query,
            int page,
            int pageSize) {
        if (rows == null || filter == null || query == null || query.length() > MAX_QUERY_LENGTH
                || page < 0 || pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            return Page.invalid(page);
        }
        String needle = query.strip().toLowerCase(Locale.ROOT);
        List<Row> matches = rows.stream()
                .filter(row -> filter == Filter.ALL || row.attention())
                .filter(row -> needle.isEmpty() || row.searchText().contains(needle))
                .toList();
        int totalPages = matches.isEmpty() ? 0 : (matches.size() + pageSize - 1) / pageSize;
        long offset = (long) page * pageSize;
        List<Row> entries = offset >= matches.size()
                ? List.of()
                : List.copyOf(matches.subList((int) offset, Math.min(matches.size(), (int) offset + pageSize)));
        return new Page(Status.SUCCESS, page, totalPages, matches.size(), entries, truncated);
    }

    private static SourceRows rows(
            MinecraftServer server,
            PlatformSavedData platform,
            UUID actorId,
            boolean authorizationOverride,
            Domain domain,
            long generatedAtEpochMillis) {
        return switch (domain) {
            case PLAYERS -> players(server, platform);
            case CLAIMS -> claims(platform);
            case SHOPS -> shops(platform, actorId, authorizationOverride);
            case PORTALS -> portals(platform);
            case RPG -> rpg(server);
            case ENCOUNTERS -> encounters(server);
            case AUDIT -> audit(platform);
            case ALERTS -> alerts(platform, actorId, authorizationOverride);
            case METRICS -> metrics(server, actorId, authorizationOverride, generatedAtEpochMillis);
        };
    }

    private static SourceRows players(MinecraftServer server, PlatformSavedData platform) {
        var source = platform.playerRecords(MAX_SCANNED_ROWS);
        List<Row> rows = source.stream().map(entry -> {
            UUID playerId = entry.getKey();
            PlayerRecord record = entry.getValue();
            var online = server.getPlayerList().getPlayer(playerId);
            String name = online == null ? "" : online.getGameProfile().name();
            String role = platform.roleOf(playerId).map(AdminRole::getSerializedName).orElse("none");
            String balance = platform.economyBalance(playerId).map(String::valueOf).orElse("none");
            return new Row(
                    name.isBlank() ? playerId.toString() : name,
                    "uuid=" + playerId + " role=" + role + " balance=" + balance
                            + " claims=" + platform.claimCount(playerId)
                            + " first=" + record.firstSeenEpochMillis() + " last=" + record.lastSeenEpochMillis(),
                    false);
        }).toList();
        return new SourceRows(rows, platform.playerRecordCount() > MAX_SCANNED_ROWS, false);
    }

    private static SourceRows claims(PlatformSavedData platform) {
        var source = platform.claims(MAX_SCANNED_ROWS);
        List<Row> rows = source.stream().map(entry -> new Row(
                entry.getKey().auditTarget(),
                "owner=" + entry.getValue().ownerId()
                        + " trusted=" + entry.getValue().trustedRoles().size()
                        + " price=" + entry.getValue().purchasePrice()
                        + " pending_transfer=" + entry.getValue().pendingTransferTo().map(UUID::toString).orElse("none"),
                entry.getValue().pendingTransferTo().isPresent())).toList();
        return new SourceRows(rows, platform.claimCount() > MAX_SCANNED_ROWS, false);
    }

    private static SourceRows shops(
            PlatformSavedData platform, UUID actorId, boolean authorizationOverride) {
        var source = EconomyObservabilityService.boundedShops(
                platform, actorId, authorizationOverride, MAX_SCANNED_ROWS);
        if (!source.authorized()) {
            return SourceRows.denied();
        }
        List<Row> rows = source.entries().stream().map(entry -> {
            var stock = entry.stock();
            return new Row(
                    entry.shopId() + "/" + entry.offerId(),
                    "item=" + BuiltInRegistries.ITEM.getKey(entry.item().getItem())
                            + " stock=" + (stock.unlimited() ? "unlimited" : stock.current() + "/" + stock.maximum()),
                    !stock.unlimited() && stock.current() == 0);
        }).toList();
        return new SourceRows(rows, source.truncated(), false);
    }

    private static SourceRows portals(PlatformSavedData platform) {
        var source = platform.portalDefinitions(MAX_SCANNED_ROWS);
        List<Row> rows = source.stream().map(entry -> new Row(
                entry.getKey().toString(),
                "origin=" + entry.getValue().origin().auditSummary()
                        + " destination=" + entry.getValue().destination().auditSummary()
                        + " cooldown_ms=" + entry.getValue().cooldownMillis()
                        + " safe=" + entry.getValue().safeArrivalPolicy().getSerializedName()
                        + " combat=" + entry.getValue().allowCombat(),
                !entry.getValue().isValid())).toList();
        return new SourceRows(rows, platform.portalDefinitionCount() > MAX_SCANNED_ROWS, false);
    }

    private static SourceRows rpg(MinecraftServer server) {
        RpgPlayerSavedData savedData = RpgPlayerSavedData.get(server);
        var source = savedData.players(MAX_SCANNED_ROWS + 1);
        List<Row> rows = source.stream().limit(MAX_SCANNED_ROWS).map(entry -> {
            var state = entry.getValue();
            return new Row(
                    entry.getKey().toString(),
                    "active=" + state.activeCareer().map(Object::toString).orElse("none")
                            + " careers=" + state.careers().size()
                            + " activities=" + state.activityXp().size()
                            + " skills=" + state.activeSkillSlots().size()
                            + " cooldowns=" + state.cooldowns().size(),
                    state.activeCareer().isEmpty());
        }).toList();
        return new SourceRows(rows, source.size() > MAX_SCANNED_ROWS, false);
    }

    private static SourceRows encounters(MinecraftServer server) {
        List<Row> rows = new ArrayList<>();
        boolean truncated = false;
        for (int page = 0; rows.size() < MAX_SCANNED_ROWS; page++) {
            var source = BossAdministrationViewService.encounters(server, page, SOURCE_PAGE_SIZE);
            for (var entry : source.entries()) {
                rows.add(new Row(
                        entry.encounterId().toString(),
                        "boss=" + entry.bossId() + " stage=" + entry.stage()
                                + " dimension=" + entry.dimension() + " participants=" + entry.participantCount()
                                + " arena_protected=" + entry.arenaProtected(),
                        !entry.arenaProtected()));
                if (rows.size() == MAX_SCANNED_ROWS) {
                    break;
                }
            }
            if (page + 1 >= source.totalPages()) {
                break;
            }
            truncated = source.totalEntries() > MAX_SCANNED_ROWS || source.truncated();
        }
        return new SourceRows(rows, truncated, false);
    }

    private static SourceRows audit(PlatformSavedData platform) {
        List<Row> rows = platform.recentAuditEntries(MAX_SCANNED_ROWS).stream().map(entry -> {
            String action = entry.actionType().toString();
            String reason = entry.reason();
            boolean attention = action.endsWith("_denied")
                    || reason.toLowerCase(Locale.ROOT).contains("invalid")
                    || reason.toLowerCase(Locale.ROOT).contains("malformed");
            return new Row(
                    action,
                    "target=" + entry.target() + " actor=" + entry.actorId()
                            + " transaction=" + entry.transactionId() + " reason=" + reason
                            + " timestamp=" + entry.timestampEpochMillis(),
                    attention);
        }).toList();
        return new SourceRows(rows, platform.auditCount() > MAX_SCANNED_ROWS, false);
    }

    private static SourceRows alerts(
            PlatformSavedData platform, UUID actorId, boolean authorizationOverride) {
        var source = EconomyObservabilityService.boundedAlerts(
                platform, actorId, authorizationOverride, MAX_SCANNED_ROWS);
        if (!source.authorized()) {
            return SourceRows.denied();
        }
        List<Row> rows = source.entries().stream().map(entry -> new Row(
                entry.type().getSerializedName(),
                "player=" + entry.playerId() + " transaction=" + entry.transactionId()
                        + " observed=" + entry.observedValue() + " threshold=" + entry.threshold()
                        + " timestamp=" + entry.timestampEpochMillis(),
                true)).toList();
        return new SourceRows(rows, source.truncated(), false);
    }

    private static SourceRows metrics(
            MinecraftServer server, UUID actorId, boolean authorizationOverride, long generatedAtEpochMillis) {
        OperationsMetricsService.Result result = OperationsMetricsService.snapshot(
                server, actorId, authorizationOverride, generatedAtEpochMillis,
                OperationsMetricsService.DEFAULT_WINDOW_MILLIS);
        if (result.status() == OperationsMetricsService.Status.UNAUTHORIZED) {
            return SourceRows.denied();
        }
        if (result.status() != OperationsMetricsService.Status.SUCCESS) {
            return new SourceRows(List.of(), false, false);
        }
        return new SourceRows(List.of(new Row(
                "operations",
                "economy=" + result.economyTransactionCount()
                        + " amount_alerts=" + result.amountAlertCount()
                        + " rate_alerts=" + result.rateAlertCount()
                        + " denied=" + result.deniedRequestCount()
                        + " malformed=" + result.malformedRequestCount()
                        + " suspicious_rpg=" + result.suspiciousRpgAwardCount()
                        + " encounters=" + result.activeEncounterCount()
                        + " pending_rewards=" + result.pendingRewardCount()
                        + " pending_recovery=" + result.pendingRecoveryCount(),
                result.hasAnomaly())), result.rpgTruncated(), false);
    }

    public enum Domain {
        PLAYERS(EnumSet.allOf(AdminRole.class)),
        CLAIMS(EnumSet.of(AdminRole.VIEWER, AdminRole.MODERATOR, AdminRole.OWNER)),
        SHOPS(EnumSet.of(AdminRole.VIEWER, AdminRole.ECONOMY_MANAGER, AdminRole.OWNER)),
        PORTALS(EnumSet.of(AdminRole.VIEWER, AdminRole.CONTENT_MANAGER, AdminRole.OWNER)),
        RPG(EnumSet.of(AdminRole.VIEWER, AdminRole.MODERATOR, AdminRole.CONTENT_MANAGER, AdminRole.OWNER)),
        ENCOUNTERS(EnumSet.of(AdminRole.VIEWER, AdminRole.CONTENT_MANAGER, AdminRole.OWNER)),
        AUDIT(EnumSet.allOf(AdminRole.class)),
        ALERTS(EnumSet.of(AdminRole.VIEWER, AdminRole.ECONOMY_MANAGER, AdminRole.OWNER)),
        METRICS(EnumSet.allOf(AdminRole.class));

        private final Set<AdminRole> roles;

        Domain(Set<AdminRole> roles) {
            this.roles = Set.copyOf(roles);
        }

        public boolean allowedFor(AdminRole role) {
            return role != null && roles.contains(role);
        }

        public static List<Domain> allowedForRole(AdminRole role) {
            return java.util.Arrays.stream(values()).filter(domain -> domain.allowedFor(role)).toList();
        }
    }

    public enum Filter {
        ALL,
        ATTENTION;

        public Filter next() {
            return this == ALL ? ATTENTION : ALL;
        }
    }

    public enum Status {
        SUCCESS,
        UNAUTHORIZED,
        INVALID_REQUEST
    }

    public record Row(String title, String detail, boolean attention) {
        public Row {
            title = title == null ? "" : title;
            detail = detail == null ? "" : detail;
        }

        String searchText() {
            return (title + " " + detail).toLowerCase(Locale.ROOT);
        }
    }

    public record Page(
            Status status,
            int page,
            int totalPages,
            int totalEntries,
            List<Row> entries,
            boolean truncated) {
        public Page {
            entries = List.copyOf(entries);
        }

        static Page unauthorized(int page) {
            return new Page(Status.UNAUTHORIZED, page, 0, 0, List.of(), false);
        }

        static Page invalid(int page) {
            return new Page(Status.INVALID_REQUEST, page, 0, 0, List.of(), false);
        }
    }

    private record SourceRows(List<Row> rows, boolean truncated, boolean unauthorized) {
        private SourceRows {
            rows = List.copyOf(rows);
        }

        static SourceRows denied() {
            return new SourceRows(List.of(), false, true);
        }
    }
}
