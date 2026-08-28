package org.dldyou.rovenfall.administration;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Function;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import org.dldyou.rovenfall.claims.Claim;
import org.dldyou.rovenfall.claims.ClaimKey;
import org.dldyou.rovenfall.mobs.BossEncounterSavedData;
import org.dldyou.rovenfall.world.PortalDefinition;
import org.dldyou.rovenfall.world.ProtectedRegion;
import org.dldyou.rovenfall.world.WorldTopology;

/** Bounded typed projections for claim, portal, protected-region, and Wilderness administration. */
final class AdministrationWorldViewService {
    static final int MAX_SCANNED_ROWS = AdministrationReadViewService.MAX_SCANNED_ROWS;
    static final int PAGE_SIZE = AdministrationReadViewService.MAX_PAGE_SIZE;

    private AdministrationWorldViewService() {
    }

    static Page<ClaimRow> claims(
            PlatformSavedData state, UUID actorId, boolean authorizationOverride, String query, int page) {
        if (!authorized(state, actorId, authorizationOverride, AdministrationReadViewService.Domain.CLAIMS)) {
            return Page.denied(page);
        }
        List<ClaimRow> source = state.claims(MAX_SCANNED_ROWS).stream()
                .map(entry -> new ClaimRow(entry.getKey(), entry.getValue()))
                .toList();
        return filterAndPage(source, query, page, state.claimCount() > MAX_SCANNED_ROWS,
                row -> row.key().auditTarget() + " " + row.claim().ownerId() + " "
                        + row.claim().pendingTransferTo().map(UUID::toString).orElse(""));
    }

    static Page<RegionRow> regions(
            PlatformSavedData state, UUID actorId, boolean authorizationOverride, String query, int page) {
        if (!authorized(state, actorId, authorizationOverride, AdministrationReadViewService.Domain.CLAIMS)) {
            return Page.denied(page);
        }
        List<RegionRow> source = state.protectedRegions().stream()
                .map(entry -> new RegionRow(entry.getKey(), entry.getValue()))
                .toList();
        return filterAndPage(source, query, page, false,
                row -> row.regionId() + " " + row.region().auditSummary());
    }

    static Page<PortalRow> portals(
            PlatformSavedData state, UUID actorId, boolean authorizationOverride, String query, int page) {
        if (!authorized(state, actorId, authorizationOverride, AdministrationReadViewService.Domain.PORTALS)) {
            return Page.denied(page);
        }
        List<PortalRow> source = state.portalDefinitions(MAX_SCANNED_ROWS).stream()
                .map(entry -> new PortalRow(entry.getKey(), entry.getValue()))
                .toList();
        return filterAndPage(source, query, page, state.portalDefinitionCount() > MAX_SCANNED_ROWS,
                row -> row.portalId() + " " + row.definition().auditSummary());
    }

    static Page<EvidenceRow> evidence(
            PlatformSavedData state, UUID actorId, boolean authorizationOverride, String query, int page) {
        if (!authorized(state, actorId, authorizationOverride, AdministrationReadViewService.Domain.PORTALS)) {
            return Page.denied(page);
        }
        List<EvidenceRow> source = state.wildernessResetState().evidence().stream()
                .map(EvidenceRow::new)
                .sorted(java.util.Comparator.comparingLong(
                        (EvidenceRow row) -> row.evidence().completedAtEpochMillis()).reversed())
                .toList();
        return filterAndPage(source, query, page, false, row -> {
            WildernessResetState.Operation operation = row.evidence().operation();
            return operation.transactionId() + " " + operation.snapshotId() + " "
                    + operation.recoverySnapshotId() + " " + operation.kind().getSerializedName() + " "
                    + row.evidence().result().getSerializedName() + " " + row.evidence().detail();
        });
    }

    static WildernessView wilderness(
            MinecraftServer server, UUID actorId, boolean authorizationOverride) {
        if (server == null) {
            return WildernessView.invalid();
        }
        PlatformSavedData state = PlatformSavedData.get(server);
        if (!authorized(state, actorId, authorizationOverride, AdministrationReadViewService.Domain.PORTALS)) {
            return WildernessView.denied();
        }
        boolean hubLoaded = server.getLevel(WorldTopology.HUB) != null;
        boolean wildernessLoaded = server.getLevel(WorldTopology.WILDERNESS) != null;
        boolean safeArrival = hubLoaded && WildernessResetService.findSafeHubArrival(server.overworld()).isPresent();
        int wildernessPlayers = (int) server.getPlayerList().getPlayers().stream()
                .filter(player -> WorldTopology.WILDERNESS.equals(player.level().dimension()))
                .count();
        boolean encounterLocked = BossEncounterSavedData.get(server).activeCount() > 0;
        boolean lifecyclePending = WildernessResetStore.forServer(server).hasPending();
        return new WildernessView(
                Status.SUCCESS, state.wildernessResetState(), hubLoaded, wildernessLoaded, safeArrival,
                wildernessPlayers, encounterLocked, lifecyclePending);
    }

    private static boolean authorized(
            PlatformSavedData state,
            UUID actorId,
            boolean authorizationOverride,
            AdministrationReadViewService.Domain domain) {
        return state != null && actorId != null && domain != null
                && (authorizationOverride || state.roleOf(actorId).filter(domain::allowedFor).isPresent());
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

    record ClaimRow(ClaimKey key, Claim claim) {
    }

    record RegionRow(Identifier regionId, ProtectedRegion region) {
    }

    record PortalRow(Identifier portalId, PortalDefinition definition) {
    }

    record EvidenceRow(WildernessResetState.Evidence evidence) {
    }

    record WildernessView(
            Status status,
            WildernessResetState resetState,
            boolean hubLoaded,
            boolean wildernessLoaded,
            boolean safeHubArrival,
            int wildernessPlayers,
            boolean encounterLocked,
            boolean lifecyclePending) {
        static WildernessView denied() {
            return new WildernessView(
                    Status.UNAUTHORIZED, WildernessResetState.EMPTY, false, false, false, 0, false, false);
        }

        static WildernessView invalid() {
            return new WildernessView(
                    Status.INVALID_REQUEST, WildernessResetState.EMPTY, false, false, false, 0, false, false);
        }

        boolean ready() {
            return status == Status.SUCCESS && hubLoaded && wildernessLoaded && safeHubArrival
                    && !encounterLocked && !lifecyclePending && resetState.activeOperation().isEmpty();
        }
    }
}
