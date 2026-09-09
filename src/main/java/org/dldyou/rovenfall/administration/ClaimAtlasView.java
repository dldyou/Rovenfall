package org.dldyou.rovenfall.administration;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import org.dldyou.rovenfall.claims.Claim;
import org.dldyou.rovenfall.claims.ClaimKey;
import org.dldyou.rovenfall.world.WorldTopology;

/** Immutable privacy-filtered land atlas projection built without loading chunks. */
public record ClaimAtlasView(
        ClaimKey origin,
        Section section,
        String query,
        int page,
        int totalPages,
        int totalEntries,
        boolean truncated,
        List<Row> entries) {
    public static final int PAGE_SIZE = 36;
    public static final int MAX_QUERY_LENGTH = 64;
    public static final int NEARBY_RADIUS = 8;
    static final int MAX_SCANNED_CLAIMS = 4_096;
    private static final Comparator<ClaimKey> CLAIM_KEY_ORDER = Comparator.comparing(ClaimKey::auditTarget);
    private static final Comparator<Row> ROW_ORDER = Comparator
            .comparingInt((Row row) -> row.distanceChunks() < 0 ? Integer.MAX_VALUE : row.distanceChunks())
            .thenComparing(Row::key, CLAIM_KEY_ORDER);

    public ClaimAtlasView {
        entries = List.copyOf(entries);
    }

    public static ClaimAtlasView create(
            PlatformSavedData state,
            ClaimKey origin,
            Section section,
            UUID viewerId,
            String query,
            int requestedPage,
            Predicate<ClaimKey> protectedAt,
            Function<UUID, Optional<String>> displayNameLookup) {
        if (state == null || origin == null || section == null || viewerId == null || query == null
                || requestedPage < 0 || protectedAt == null || displayNameLookup == null
                || query.length() > MAX_QUERY_LENGTH || query.indexOf('\n') >= 0 || query.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("invalid claim atlas request");
        }
        String retainedQuery = query.strip();
        if (section == Section.AVAILABLE && !retainedQuery.isEmpty()) {
            throw new IllegalArgumentException("available atlas does not support search");
        }
        String normalizedQuery = retainedQuery.toLowerCase(Locale.ROOT);
        CandidatePage candidates = switch (section) {
            case OWNED -> owned(state, origin, viewerId);
            case NEARBY -> nearby(state, origin, viewerId);
            case AVAILABLE -> available(state, origin, protectedAt);
        };
        List<Row> matches = candidates.rows().stream()
                .map(row -> withOwnerName(row, displayNameLookup))
                .filter(row -> normalizedQuery.isEmpty()
                        || row.ownerName().map(name -> name.toLowerCase(Locale.ROOT).contains(normalizedQuery))
                                .orElse(false))
                .sorted(ROW_ORDER)
                .toList();
        int totalPages = matches.isEmpty() ? 0 : (matches.size() + PAGE_SIZE - 1) / PAGE_SIZE;
        int page = totalPages == 0 ? 0 : Math.min(requestedPage, totalPages - 1);
        int from = page * PAGE_SIZE;
        int to = Math.min(from + PAGE_SIZE, matches.size());
        return new ClaimAtlasView(
                origin, section, retainedQuery, page, totalPages, matches.size(), candidates.truncated(),
                matches.subList(from, to));
    }

    private static CandidatePage owned(PlatformSavedData state, ClaimKey origin, UUID viewerId) {
        List<Map.Entry<ClaimKey, Claim>> owned = state.claimsOwnedBy(viewerId, MAX_SCANNED_CLAIMS + 1);
        boolean truncated = owned.size() > MAX_SCANNED_CLAIMS;
        int limit = Math.min(owned.size(), MAX_SCANNED_CLAIMS);
        List<Row> rows = new ArrayList<>(limit);
        for (int index = 0; index < limit; index++) {
            Map.Entry<ClaimKey, Claim> entry = owned.get(index);
            rows.add(claimedRow(state, origin, viewerId, entry.getKey(), entry.getValue()));
        }
        return new CandidatePage(rows, truncated);
    }

    private static CandidatePage nearby(PlatformSavedData state, ClaimKey origin, UUID viewerId) {
        List<Row> rows = new ArrayList<>((NEARBY_RADIUS * 2 + 1) * (NEARBY_RADIUS * 2 + 1) - 1);
        for (int offsetX = -NEARBY_RADIUS; offsetX <= NEARBY_RADIUS; offsetX++) {
            for (int offsetZ = -NEARBY_RADIUS; offsetZ <= NEARBY_RADIUS; offsetZ++) {
                if (offsetX == 0 && offsetZ == 0) {
                    continue;
                }
                ClaimKey key = offset(origin, offsetX, offsetZ);
                Claim claim = state.claim(key).orElse(null);
                if (claim == null || claim.settings().entryRestricted()
                        && !claim.ownerId().equals(viewerId)
                        && !claim.trustedRoles().containsKey(viewerId)
                        && claim.pendingTransferTo().filter(viewerId::equals).isEmpty()
                        && !ClaimManagementService.canManage(state, claim, viewerId, false)) {
                    continue;
                }
                rows.add(claimedRow(state, origin, viewerId, key, claim));
            }
        }
        return new CandidatePage(rows, false);
    }

    private static CandidatePage available(
            PlatformSavedData state, ClaimKey origin, Predicate<ClaimKey> protectedAt) {
        if (!WorldTopology.allowsClaims(origin.dimension())) {
            return new CandidatePage(List.of(), false);
        }
        List<Row> rows = new ArrayList<>((NEARBY_RADIUS * 2 + 1) * (NEARBY_RADIUS * 2 + 1));
        for (int offsetX = -NEARBY_RADIUS; offsetX <= NEARBY_RADIUS; offsetX++) {
            for (int offsetZ = -NEARBY_RADIUS; offsetZ <= NEARBY_RADIUS; offsetZ++) {
                ClaimKey key = offset(origin, offsetX, offsetZ);
                if (state.claim(key).isPresent() || protectedAt.test(key)) {
                    continue;
                }
                rows.add(new Row(
                        key, Optional.empty(), Relation.AVAILABLE, Optional.empty(), distance(origin, key),
                        direction(origin, key), key.equals(origin), key.equals(origin)));
            }
        }
        return new CandidatePage(rows, false);
    }

    private static Row claimedRow(
            PlatformSavedData state, ClaimKey origin, UUID viewerId, ClaimKey key, Claim claim) {
        boolean canManage = ClaimManagementService.canManage(state, claim, viewerId, false);
        Relation relation = claim.ownerId().equals(viewerId)
                ? Relation.OWNER
                : claim.pendingTransferTo().filter(viewerId::equals).isPresent()
                        ? Relation.TRANSFER_PENDING
                        : claim.trustedRoles().containsKey(viewerId)
                                ? Relation.TRUSTED
                                : canManage ? Relation.MODERATED : Relation.PUBLIC;
        boolean actionable = relation == Relation.OWNER
                || relation == Relation.TRANSFER_PENDING
                || canManage;
        return new Row(
                key, Optional.of(claim), relation, Optional.empty(), distance(origin, key), direction(origin, key),
                key.equals(origin), actionable);
    }

    private static Row withOwnerName(Row row, Function<UUID, Optional<String>> displayNameLookup) {
        if (row.expectedClaim().isEmpty()) {
            return row;
        }
        Optional<String> ownerName = displayNameLookup.apply(row.expectedClaim().orElseThrow().ownerId());
        return new Row(
                row.key(), row.expectedClaim(), row.relation(), ownerName == null ? Optional.empty() : ownerName,
                row.distanceChunks(), row.direction(), row.current(), row.actionable());
    }

    private static ClaimKey offset(ClaimKey origin, int offsetX, int offsetZ) {
        return new ClaimKey(origin.dimension(), origin.chunkX() + offsetX, origin.chunkZ() + offsetZ);
    }

    private static int distance(ClaimKey origin, ClaimKey key) {
        if (!origin.dimension().equals(key.dimension())) {
            return -1;
        }
        return Math.max(Math.abs(origin.chunkX() - key.chunkX()), Math.abs(origin.chunkZ() - key.chunkZ()));
    }

    private static Optional<Direction> direction(ClaimKey origin, ClaimKey key) {
        if (!origin.dimension().equals(key.dimension()) || origin.equals(key)) {
            return Optional.empty();
        }
        int east = Integer.compare(key.chunkX(), origin.chunkX());
        int south = Integer.compare(key.chunkZ(), origin.chunkZ());
        return Optional.of(switch (south) {
            case -1 -> east < 0 ? Direction.NORTH_WEST : east > 0 ? Direction.NORTH_EAST : Direction.NORTH;
            case 1 -> east < 0 ? Direction.SOUTH_WEST : east > 0 ? Direction.SOUTH_EAST : Direction.SOUTH;
            default -> east < 0 ? Direction.WEST : Direction.EAST;
        });
    }

    public record Row(
            ClaimKey key,
            Optional<Claim> expectedClaim,
            Relation relation,
            Optional<String> ownerName,
            int distanceChunks,
            Optional<Direction> direction,
            boolean current,
            boolean actionable) {
        public Row {
            expectedClaim = expectedClaim == null ? Optional.empty() : expectedClaim;
            ownerName = ownerName == null ? Optional.empty() : ownerName;
            direction = direction == null ? Optional.empty() : direction;
        }
    }

    public enum Section {
        OWNED,
        NEARBY,
        AVAILABLE
    }

    public enum Relation {
        OWNER,
        TRUSTED,
        TRANSFER_PENDING,
        MODERATED,
        PUBLIC,
        AVAILABLE
    }

    public enum Direction {
        NORTH,
        NORTH_EAST,
        EAST,
        SOUTH_EAST,
        SOUTH,
        SOUTH_WEST,
        WEST,
        NORTH_WEST
    }

    private record CandidatePage(List<Row> rows, boolean truncated) {
        private CandidatePage {
            rows = List.copyOf(rows);
        }
    }
}
