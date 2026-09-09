package org.dldyou.rovenfall.administration;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalDouble;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.dldyou.rovenfall.world.PortalDefinition;
import org.dldyou.rovenfall.world.WorldTopology;

/** Immutable bounded portal projection built without loading worlds or chunks. */
public record PlayerPortalView(
        String query,
        int page,
        int totalPages,
        int totalEntries,
        List<Row> entries) {
    public static final int PAGE_SIZE = 36;
    public static final int MAX_QUERY_LENGTH = 64;
    static final int MAX_SCANNED_DEFINITIONS = PortalState.MAX_DEFINITIONS;
    private static final Comparator<Row> ROW_ORDER = Comparator
            .comparing(Row::currentDimension).reversed()
            .thenComparingDouble(row -> row.distanceBlocks().orElse(Double.POSITIVE_INFINITY))
            .thenComparing(Row::portalId);

    public PlayerPortalView {
        entries = List.copyOf(entries);
    }

    public static PlayerPortalView create(
            PlatformSavedData state,
            ResourceKey<Level> currentDimension,
            Vec3 currentPosition,
            String query,
            int requestedPage) {
        if (state == null || currentDimension == null || currentPosition == null || query == null
                || requestedPage < 0 || !finite(currentPosition)
                || query.length() > MAX_QUERY_LENGTH || query.indexOf('\n') >= 0 || query.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("invalid portal explorer request");
        }
        String retainedQuery = query.strip();
        String normalizedQuery = retainedQuery.toLowerCase(Locale.ROOT);
        List<Row> matches = state.portalDefinitions(MAX_SCANNED_DEFINITIONS).stream()
                .map(entry -> row(entry, currentDimension, currentPosition))
                .filter(row -> normalizedQuery.isEmpty()
                        || searchText(row).toLowerCase(Locale.ROOT).contains(normalizedQuery))
                .sorted(ROW_ORDER)
                .toList();
        int totalPages = matches.isEmpty() ? 0 : (matches.size() + PAGE_SIZE - 1) / PAGE_SIZE;
        int page = totalPages == 0 ? 0 : Math.min(requestedPage, totalPages - 1);
        int from = page * PAGE_SIZE;
        int to = Math.min(from + PAGE_SIZE, matches.size());
        return new PlayerPortalView(retainedQuery, page, totalPages, matches.size(), matches.subList(from, to));
    }

    private static Row row(
            Map.Entry<Identifier, PortalDefinition> entry,
            ResourceKey<Level> currentDimension,
            Vec3 currentPosition) {
        PortalDefinition definition = entry.getValue();
        boolean current = currentDimension.equals(definition.origin().dimension());
        OptionalDouble distance = current
                ? OptionalDouble.of(currentPosition.distanceTo(Vec3.atCenterOf(definition.origin().position())))
                : OptionalDouble.empty();
        return new Row(
                entry.getKey(), definition, definition.origin(), definition.destination(), distance, current,
                distance.isPresent() && distance.orElseThrow() <= PortalDefinition.MAX_USE_DISTANCE);
    }

    private static String searchText(Row row) {
        return endpointText(row.origin()) + " " + endpointText(row.destination());
    }

    private static String endpointText(PortalDefinition.Endpoint endpoint) {
        return dimensionSearchText(endpoint.dimension()) + " "
                + endpoint.position().getX() + " " + endpoint.position().getY() + " " + endpoint.position().getZ();
    }

    private static String dimensionSearchText(ResourceKey<Level> dimension) {
        String aliases = WorldTopology.isHub(dimension)
                ? "hub 허브 ハブ"
                : WorldTopology.isWilderness(dimension)
                        ? "wilderness wild 야생 荒野"
                        : Level.NETHER.equals(dimension)
                                ? "nether 네더 ネザー"
                                : Level.END.equals(dimension)
                                        ? "end 엔드 ジ・エンド"
                                        : "";
        return dimension.identifier() + " " + aliases;
    }

    private static boolean finite(Vec3 position) {
        return Double.isFinite(position.x) && Double.isFinite(position.y) && Double.isFinite(position.z);
    }

    public record Row(
            Identifier portalId,
            PortalDefinition expectedDefinition,
            PortalDefinition.Endpoint origin,
            PortalDefinition.Endpoint destination,
            OptionalDouble distanceBlocks,
            boolean currentDimension,
            boolean withinUseDistance) {
        public Row {
            if (portalId == null || expectedDefinition == null || origin == null || destination == null
                    || distanceBlocks == null) {
                throw new IllegalArgumentException("invalid portal row");
            }
        }

        public boolean fresh(PlatformSavedData state) {
            return state != null && state.portalDefinition(portalId).filter(expectedDefinition::equals).isPresent();
        }
    }
}
