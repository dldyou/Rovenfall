package org.dldyou.rovenfall.exploration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import org.dldyou.rovenfall.world.WorldTopology;
import org.junit.jupiter.api.Test;

final class ExplorationJournalViewTest {
    private static final Identifier PUBLIC = id("public_place");
    private static final Identifier PRIVATE = id("private_place");

    @Test
    void privateUndiscoveredRowsContainNoDefinitionOrLocationData() {
        ExplorationJournalView.Row row = rowWithStatus(
                view(ExplorationPlayerState.EMPTY), ExplorationJournalView.Status.HIDDEN);

        assertEquals(ExplorationJournalView.Status.HIDDEN, row.status());
        assertTrue(row.id().isEmpty());
        assertTrue(row.titleTranslationKey().isEmpty());
        assertTrue(row.descriptionTranslationKey().isEmpty());
        assertTrue(row.world().isEmpty());
        assertFalse(row.guidanceAvailable());
        assertTrue(Arrays.stream(ExplorationJournalView.Row.class.getRecordComponents())
                .noneMatch(component -> component.getName().matches("position|radius|version|reward|dimension")
                        || BlockPos.class.isAssignableFrom(component.getType())));
    }

    @Test
    void publicAndCurrentVersionDiscoveriesAreVisibleButStalePrivateReceiptsStayHidden() {
        ExplorationJournalView.Row publicRow = rowWithId(view(ExplorationPlayerState.EMPTY), PUBLIC);
        assertEquals(ExplorationJournalView.Status.UNDISCOVERED, publicRow.status());
        assertEquals(Optional.of(PUBLIC), publicRow.id());
        assertTrue(publicRow.guidanceAvailable());

        ExplorationPlayerState current = state(PRIVATE, 2);
        ExplorationJournalView.Row discovered = rowWithId(view(current), PRIVATE);
        assertEquals(ExplorationJournalView.Status.DISCOVERED, discovered.status());
        assertEquals(Optional.of(PRIVATE), discovered.id());

        ExplorationJournalView.Row stale = rowWithStatus(
                view(state(PRIVATE, 1)), ExplorationJournalView.Status.HIDDEN);
        assertEquals(ExplorationJournalView.Status.HIDDEN, stale.status());
        assertTrue(stale.id().isEmpty());
    }

    @Test
    void worldFiltersNeverClassifyHiddenRows() {
        ExplorationDefinitionSnapshot definitions = definitions();
        ExplorationJournalView hub = ExplorationJournalView.create(
                definitions, ExplorationPlayerState.EMPTY, ExplorationJournalView.Filter.HUB, 0, 28);
        ExplorationJournalView wilderness = ExplorationJournalView.create(
                definitions, ExplorationPlayerState.EMPTY, ExplorationJournalView.Filter.WILDERNESS, 0, 28);

        assertEquals(List.of(PUBLIC), hub.entries().stream().flatMap(row -> row.id().stream()).toList());
        assertEquals(1, wilderness.entries().size());
        assertEquals(ExplorationJournalView.Status.HIDDEN, wilderness.entries().getFirst().status());
        assertTrue(wilderness.entries().getFirst().world().isEmpty());
    }

    @Test
    void guidanceRequiresExactRevisionStateAndServerOwnedEligibility() {
        ExplorationDefinitionSnapshot definitions = definitions();
        ExplorationPlayerState rendered = ExplorationPlayerState.EMPTY;
        ExplorationJournalView.Row publicRow = rowWithId(view(rendered), PUBLIC);
        ExplorationJournalView.Row hiddenRow = rowWithStatus(
                view(rendered), ExplorationJournalView.Status.HIDDEN);

        assertTrue(ExplorationJournalView.resolveGuidance(
                definitions, 7, rendered, 7, rendered, publicRow).isPresent());
        assertTrue(ExplorationJournalView.resolveGuidance(
                definitions, 8, rendered, 7, rendered, publicRow).isEmpty());
        assertTrue(ExplorationJournalView.resolveGuidance(
                definitions, 7, state(PRIVATE, 2), 7, rendered, publicRow).isEmpty());
        assertTrue(ExplorationJournalView.resolveGuidance(
                definitions, 7, rendered, 7, rendered, hiddenRow).isEmpty());
    }

    @Test
    void paginationIsBounded() {
        ExplorationJournalView view = ExplorationJournalView.create(
                definitions(), ExplorationPlayerState.EMPTY, ExplorationJournalView.Filter.ALL, 99, 1);

        assertEquals(1, view.page());
        assertEquals(2, view.totalEntries());
        assertEquals(2, view.totalPages());
        assertEquals(1, view.entries().size());
        assertEquals(0, view.discoveredEntries());
        assertEquals(2, view.catalogEntries());
    }

    private static ExplorationJournalView view(ExplorationPlayerState state) {
        return ExplorationJournalView.create(
                definitions(), state, ExplorationJournalView.Filter.ALL, 0, 28);
    }

    private static ExplorationJournalView.Row rowWithId(
            ExplorationJournalView view, Identifier id) {
        return view.entries().stream().filter(row -> row.id().filter(id::equals).isPresent())
                .findFirst().orElseThrow();
    }

    private static ExplorationJournalView.Row rowWithStatus(
            ExplorationJournalView view, ExplorationJournalView.Status status) {
        return view.entries().stream().filter(row -> row.status() == status)
                .findFirst().orElseThrow();
    }

    private static ExplorationDefinitionSnapshot definitions() {
        return ExplorationDefinitionSnapshot.compile(List.of(
                source(PUBLIC, new ExplorationDefinition(
                        "discovery.rovenfall.public_place", "discovery.rovenfall.public_place.description",
                        1, WorldTopology.HUB, new BlockPos(10, 64, 10), 16, true, Optional.empty())),
                source(PRIVATE, new ExplorationDefinition(
                        "discovery.rovenfall.private_place", "discovery.rovenfall.private_place.description",
                        2, WorldTopology.WILDERNESS, new BlockPos(20, 64, 20), 16, false, Optional.empty()))));
    }

    private static ExplorationDefinitionSnapshot.Source source(
            Identifier id, ExplorationDefinition definition) {
        return new ExplorationDefinitionSnapshot.Source(id, "test", id, definition);
    }

    private static ExplorationPlayerState state(Identifier id, int version) {
        return new ExplorationPlayerState(Map.of(id, new ExplorationPlayerState.DiscoveryReceipt(
                version, 1_700_000_000_000L, UUID.fromString("f8dcaa06-42b7-4e58-bcf6-8155a983b6f1"),
                Optional.empty())));
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("rovenfall", path);
    }
}
