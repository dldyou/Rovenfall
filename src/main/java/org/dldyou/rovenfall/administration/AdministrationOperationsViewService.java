package org.dldyou.rovenfall.administration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import net.minecraft.server.MinecraftServer;

/** Bounded typed projections for audit, alert, metrics, and platform snapshot administration. */
final class AdministrationOperationsViewService {
    static final int PAGE_SIZE = AdministrationReadViewService.MAX_PAGE_SIZE;
    static final int MAX_SCANNED_ROWS = AdministrationReadViewService.MAX_SCANNED_ROWS;

    private AdministrationOperationsViewService() {
    }

    static Page<AuditRow> audit(
            MinecraftServer server,
            UUID actorId,
            boolean authorizationOverride,
            AuditQuery query,
            boolean attentionOnly,
            int page) {
        if (!authorized(server, actorId, authorizationOverride, AdministrationReadViewService.Domain.AUDIT)
                || query == null || page < 0) {
            return Page.denied(page);
        }
        PlatformSavedData.AuditPage source = PlatformSavedData.get(server).auditPage(query, 0,
                PlatformSavedData.MAX_AUDIT_PAGE_SIZE);
        if (!attentionOnly) {
            int resolvedPage = clampPage(page, source.totalPages());
            PlatformSavedData.AuditPage requested = PlatformSavedData.get(server)
                    .auditPage(query, resolvedPage, PAGE_SIZE);
            return new Page<>(Status.SUCCESS, requested.page(), requested.totalPages(), requested.totalEntries(),
                    requested.entries().stream().map(AdministrationOperationsViewService::auditRow).toList(), false);
        }
        List<AuditRow> matches = new ArrayList<>();
        int sourcePage = 0;
        int scanned = 0;
        while (sourcePage < source.totalPages() && scanned < MAX_SCANNED_ROWS) {
            PlatformSavedData.AuditPage batch = sourcePage == 0
                    ? source : PlatformSavedData.get(server).auditPage(query, sourcePage,
                            PlatformSavedData.MAX_AUDIT_PAGE_SIZE);
            batch.entries().stream().map(AdministrationOperationsViewService::auditRow)
                    .filter(AuditRow::attention).forEach(matches::add);
            scanned += batch.entries().size();
            sourcePage++;
        }
        boolean truncated = sourcePage < source.totalPages();
        return page(matches, page, truncated, Function.identity());
    }

    static Page<AlertRow> alerts(
            MinecraftServer server,
            UUID actorId,
            boolean authorizationOverride,
            AlertFilter filter,
            String query,
            int page) {
        if (!authorized(server, actorId, authorizationOverride, AdministrationReadViewService.Domain.ALERTS)
                || filter == null) {
            return Page.denied(page);
        }
        var source = EconomyObservabilityService.boundedAlerts(
                PlatformSavedData.get(server), actorId, authorizationOverride, MAX_SCANNED_ROWS);
        if (!source.authorized()) {
            return Page.denied(page);
        }
        List<AlertRow> rows = source.entries().stream()
                .filter(alert -> filter == AlertFilter.ALL || alert.type() == filter.type().orElseThrow())
                .map(AlertRow::new)
                .toList();
        return filterAndPage(rows, query, page, source.truncated(), row -> {
            EconomyAlert alert = row.alert();
            return alert.type().getSerializedName() + " " + alert.playerId() + " " + alert.transactionId();
        });
    }

    static Optional<OperationsMetricsService.Result> metrics(
            MinecraftServer server,
            UUID actorId,
            boolean authorizationOverride,
            long generatedAtEpochMillis,
            long windowMillis) {
        if (!authorized(server, actorId, authorizationOverride, AdministrationReadViewService.Domain.METRICS)) {
            return Optional.empty();
        }
        OperationsMetricsService.Result result = OperationsMetricsService.snapshot(
                server, actorId, authorizationOverride, generatedAtEpochMillis, windowMillis);
        return result.status() == OperationsMetricsService.Status.SUCCESS ? Optional.of(result) : Optional.empty();
    }

    static AuditQuery alertEvidenceQuery(EconomyAlert alert) {
        if (alert == null) {
            throw new IllegalArgumentException("Alert is required");
        }
        long until = alert.timestampEpochMillis();
        return new AuditQuery(Math.max(0L, until - AuditQuery.MAX_WINDOW_MILLIS), until,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(alert.transactionId()));
    }

    static Page<SnapshotRow> snapshots(
            MinecraftServer server,
            UUID actorId,
            boolean authorizationOverride,
            String query,
            int page) {
        if (!authorized(server, actorId, authorizationOverride, AdministrationReadViewService.Domain.AUDIT)) {
            return Page.denied(page);
        }
        PlatformSavedData state = PlatformSavedData.get(server);
        LinkedHashMap<UUID, SnapshotRow> rows = new LinkedHashMap<>();
        for (AuditEntry entry : state.recentAuditEntries(MAX_SCANNED_ROWS)) {
            String action = entry.actionType().getPath();
            if (action.equals("platform_snapshot_create")) {
                snapshotId(entry.afterValue()).ifPresent(id -> rows.putIfAbsent(id,
                        new SnapshotRow(id, SnapshotKind.CREATED, entry.timestampEpochMillis(), entry.transactionId())));
            } else if (action.equals("platform_snapshot_restore")) {
                snapshotId(entry.afterValue()).ifPresent(id -> rows.putIfAbsent(id,
                        new SnapshotRow(id, SnapshotKind.RESTORE_TARGET,
                                entry.timestampEpochMillis(), entry.transactionId())));
                snapshotId(entry.beforeValue()).ifPresent(id -> rows.putIfAbsent(id,
                        new SnapshotRow(id, SnapshotKind.SAFETY,
                                entry.timestampEpochMillis(), entry.transactionId())));
            }
        }
        return filterAndPage(List.copyOf(rows.values()), query, page,
                state.auditCount() > MAX_SCANNED_ROWS,
                row -> row.snapshotId() + " " + row.kind().name() + " " + row.auditTransactionId());
    }

    private static AuditRow auditRow(AuditEntry entry) {
        String action = entry.actionType().getPath();
        String reason = entry.reason().toLowerCase(Locale.ROOT);
        return new AuditRow(entry, action.endsWith("_denied") || action.endsWith("_failed")
                || reason.contains("invalid") || reason.contains("malformed"));
    }

    private static Optional<UUID> snapshotId(String value) {
        if (value == null || !value.startsWith("snapshot:")) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(value.substring("snapshot:".length())));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private static boolean authorized(
            MinecraftServer server,
            UUID actorId,
            boolean authorizationOverride,
            AdministrationReadViewService.Domain domain) {
        if (server == null || actorId == null || domain == null) {
            return false;
        }
        PlatformSavedData state = PlatformSavedData.get(server);
        return authorizationOverride || state.roleOf(actorId).filter(domain::allowedFor).isPresent();
    }

    private static <T> Page<T> filterAndPage(
            List<T> source,
            String query,
            int page,
            boolean truncated,
            Function<T, String> searchText) {
        if (query == null || query.length() > AdministrationReadViewService.MAX_QUERY_LENGTH || page < 0) {
            return Page.invalid(page);
        }
        String needle = query.strip().toLowerCase(Locale.ROOT);
        List<T> matches = source.stream()
                .filter(value -> needle.isEmpty()
                        || searchText.apply(value).toLowerCase(Locale.ROOT).contains(needle))
                .toList();
        return page(matches, page, truncated, Function.identity());
    }

    private static <T, R> Page<R> page(
            List<T> source, int page, boolean truncated, Function<T, R> mapper) {
        int totalPages = source.isEmpty() ? 0 : (source.size() + PAGE_SIZE - 1) / PAGE_SIZE;
        int resolvedPage = clampPage(page, totalPages);
        long offset = (long) resolvedPage * PAGE_SIZE;
        List<R> entries = offset >= source.size()
                ? List.of()
                : source.subList((int) offset, Math.min(source.size(), (int) offset + PAGE_SIZE)).stream()
                        .map(mapper).toList();
        return new Page<>(Status.SUCCESS, resolvedPage, totalPages, source.size(), entries, truncated);
    }

    static int clampPage(int page, int totalPages) {
        if (page < 0 || totalPages < 0) {
            throw new IllegalArgumentException("Invalid page bounds");
        }
        return totalPages == 0 ? 0 : Math.min(page, totalPages - 1);
    }

    enum Status {
        SUCCESS,
        UNAUTHORIZED,
        INVALID_REQUEST
    }

    enum AlertFilter {
        ALL,
        AMOUNT,
        RATE;

        Optional<EconomyAlert.Type> type() {
            return this == ALL ? Optional.empty() : Optional.of(EconomyAlert.Type.valueOf(name()));
        }

        AlertFilter next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    enum SnapshotKind {
        CREATED,
        RESTORE_TARGET,
        SAFETY
    }

    record AuditRow(AuditEntry entry, boolean attention) {
    }

    record AlertRow(EconomyAlert alert) {
    }

    record SnapshotRow(
            UUID snapshotId,
            SnapshotKind kind,
            long recordedAtEpochMillis,
            UUID auditTransactionId) {
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
}
