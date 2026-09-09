package org.dldyou.rovenfall.exploration;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.dldyou.rovenfall.world.WorldTopology;

/** Position-free player projection for the exploration journal. */
public final class ExplorationJournalView {
    public static final int MAX_PAGE_SIZE = 28;

    private final List<Row> entries;
    private final Filter filter;
    private final int page;
    private final int totalEntries;
    private final int totalPages;
    private final int discoveredEntries;
    private final int catalogEntries;

    private ExplorationJournalView(
            List<Row> entries, Filter filter, int page, int totalEntries, int totalPages,
            int discoveredEntries, int catalogEntries) {
        this.entries = List.copyOf(entries);
        this.filter = filter;
        this.page = page;
        this.totalEntries = totalEntries;
        this.totalPages = totalPages;
        this.discoveredEntries = discoveredEntries;
        this.catalogEntries = catalogEntries;
    }

    public static ExplorationJournalView create(
            ExplorationDefinitionSnapshot definitions,
            ExplorationPlayerState state,
            Filter filter,
            int requestedPage,
            int pageSize) {
        Objects.requireNonNull(definitions, "definitions");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(filter, "filter");
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("Exploration journal page size must be between 1 and "
                    + MAX_PAGE_SIZE);
        }
        List<Row> filtered = definitions.definitions().entrySet().stream()
                .sorted(java.util.Map.Entry.comparingByKey())
                .filter(entry -> filter.includes(entry.getValue().dimension()))
                .map(entry -> project(entry.getKey(), entry.getValue(), state))
                .sorted(java.util.Comparator
                        .comparing((Row row) -> row.status() == Status.HIDDEN)
                        .thenComparing(row -> row.id().map(Identifier::toString).orElse("")))
                .toList();
        int discoveredEntries = (int) filtered.stream()
                .filter(row -> row.status() == Status.DISCOVERED)
                .count();
        int totalPages = filtered.isEmpty() ? 0 : (filtered.size() + pageSize - 1) / pageSize;
        int page = totalPages == 0 ? 0 : Math.clamp(requestedPage, 0, totalPages - 1);
        int from = Math.min(filtered.size(), page * pageSize);
        int to = Math.min(filtered.size(), from + pageSize);
        return new ExplorationJournalView(
                filtered.subList(from, to), filter, page, filtered.size(), totalPages,
                discoveredEntries, filtered.size());
    }

    private static Row project(
            Identifier id, ExplorationDefinition definition, ExplorationPlayerState state) {
        Optional<ExplorationPlayerState.DiscoveryReceipt> receipt = state.discovery(id);
        boolean currentDiscovery = receipt
                .filter(found -> found.definitionVersion() == definition.version())
                .isPresent();
        if (!definition.publicGuidance() && !currentDiscovery) {
            return Row.hidden();
        }
        Status status = currentDiscovery
                ? Status.DISCOVERED
                : receipt.isPresent() ? Status.DEFINITION_CHANGED : Status.UNDISCOVERED;
        World world = WorldTopology.isHub(definition.dimension()) ? World.HUB : World.WILDERNESS;
        return new Row(
                Optional.of(id),
                Optional.of(definition.titleTranslationKey()),
                Optional.of(definition.descriptionTranslationKey()),
                Optional.of(world),
                status,
                definition.publicGuidance() || currentDiscovery);
    }

    /** Resolves sensitive coordinates only after exact server revision and saved-state revalidation. */
    public static Optional<GuidanceTarget> resolveGuidance(
            ExplorationDefinitionSnapshot currentDefinitions,
            long currentRevision,
            ExplorationPlayerState currentState,
            long expectedRevision,
            ExplorationPlayerState expectedState,
            Row selected) {
        if (currentDefinitions == null || currentState == null || expectedState == null || selected == null
                || currentRevision != expectedRevision || !currentState.equals(expectedState)
                || selected.id().isEmpty()) {
            return Optional.empty();
        }
        Identifier id = selected.id().orElseThrow();
        return currentDefinitions.definition(id).filter(definition ->
                        definition.publicGuidance() || currentState.discovery(id)
                                .filter(receipt -> receipt.definitionVersion() == definition.version())
                                .isPresent())
                .map(definition -> new GuidanceTarget(definition.dimension(), definition.position()));
    }

    public List<Row> entries() {
        return entries;
    }

    public Filter filter() {
        return filter;
    }

    public int page() {
        return page;
    }

    public int totalEntries() {
        return totalEntries;
    }

    public int totalPages() {
        return totalPages;
    }

    public int discoveredEntries() {
        return discoveredEntries;
    }

    public int catalogEntries() {
        return catalogEntries;
    }

    public enum Filter {
        ALL,
        HUB,
        WILDERNESS;

        private boolean includes(ResourceKey<Level> dimension) {
            return this == ALL || this == HUB && WorldTopology.isHub(dimension)
                    || this == WILDERNESS && WorldTopology.isWilderness(dimension);
        }
    }

    public enum World {
        HUB,
        WILDERNESS
    }

    public enum Status {
        HIDDEN,
        UNDISCOVERED,
        DISCOVERED,
        DEFINITION_CHANGED
    }

    public record Row(
            Optional<Identifier> id,
            Optional<String> titleTranslationKey,
            Optional<String> descriptionTranslationKey,
            Optional<World> world,
            Status status,
            boolean guidanceAvailable) {
        public Row {
            id = id == null ? Optional.empty() : id;
            titleTranslationKey = titleTranslationKey == null ? Optional.empty() : titleTranslationKey;
            descriptionTranslationKey = descriptionTranslationKey == null
                    ? Optional.empty() : descriptionTranslationKey;
            world = world == null ? Optional.empty() : world;
            Objects.requireNonNull(status, "status");
            if (status == Status.HIDDEN && (id.isPresent() || titleTranslationKey.isPresent()
                    || descriptionTranslationKey.isPresent() || world.isPresent() || guidanceAvailable)) {
                throw new IllegalArgumentException("Hidden exploration rows cannot expose definition data");
            }
        }

        private static Row hidden() {
            return new Row(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    Status.HIDDEN, false);
        }
    }

    /** Server-only navigation result; never stored in a journal row or sent by a custom packet. */
    public record GuidanceTarget(ResourceKey<Level> dimension, BlockPos position) {
        public GuidanceTarget {
            Objects.requireNonNull(dimension, "dimension");
            Objects.requireNonNull(position, "position");
        }
    }
}
