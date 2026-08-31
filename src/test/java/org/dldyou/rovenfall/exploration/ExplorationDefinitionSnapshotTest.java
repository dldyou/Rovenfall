package org.dldyou.rovenfall.exploration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import org.dldyou.rovenfall.world.WorldTopology;
import org.junit.jupiter.api.Test;

final class ExplorationDefinitionSnapshotTest {
    @Test
    void compilesDeterministicPerDimensionEntries() {
        var snapshot = ExplorationDefinitionSnapshot.compile(List.of(
                source("zeta", definition(WorldTopology.WILDERNESS, new BlockPos(2, 70, 2), 2)),
                source("beta", definition(WorldTopology.HUB, new BlockPos(1, 70, 1), 1)),
                source("alpha", definition(WorldTopology.HUB, BlockPos.ZERO, 1))));

        assertEquals(List.of(id("alpha"), id("beta")),
                snapshot.entries(WorldTopology.HUB).stream().map(ExplorationDefinitionSnapshot.Entry::id).toList());
        assertEquals(List.of(id("zeta")), snapshot.entries(WorldTopology.WILDERNESS).stream()
                .map(ExplorationDefinitionSnapshot.Entry::id).toList());
        assertTrue(snapshot.entries(Level.NETHER).isEmpty());
    }

    @Test
    void rejectsDuplicateTranslationWorldAndRangeFailuresAtomically() {
        ExplorationDefinitionStore store = new ExplorationDefinitionStore();
        var initial = store.replace(List.of(source("kept", definition(WorldTopology.HUB, BlockPos.ZERO, 1))));
        List<ExplorationDefinitionSnapshot.Source> invalid = List.of(
                source("same", definition(WorldTopology.HUB, BlockPos.ZERO, 1)),
                new ExplorationDefinitionSnapshot.Source(id("other_file"), "pack_b", id("same"),
                        definition(WorldTopology.HUB, BlockPos.ZERO, 1)),
                source("bad_translation", new ExplorationDefinition(
                        "Discovery Bad", "discovery.rovenfall.bad.description", 1,
                        WorldTopology.HUB, BlockPos.ZERO, 1, false, Optional.empty())),
                source("bad_world", new ExplorationDefinition(
                        "discovery.rovenfall.bad", "discovery.rovenfall.bad.description", 1,
                        Level.NETHER, BlockPos.ZERO, 1, false, Optional.empty())),
                source("bad_radius", new ExplorationDefinition(
                        "discovery.rovenfall.bad_radius", "discovery.rovenfall.bad_radius.description", 1,
                        WorldTopology.HUB, BlockPos.ZERO, 0, false, Optional.empty())));

        var exception = assertThrows(ExplorationDefinitionSnapshot.ValidationException.class,
                () -> store.replace(invalid));

        assertTrue(exception.problems().stream().anyMatch(problem -> problem.cause().contains("duplicate")));
        assertTrue(exception.problems().stream().anyMatch(problem -> problem.cause().contains("translation")));
        assertTrue(exception.problems().stream().anyMatch(problem -> problem.cause().contains("world")));
        assertEquals(initial.definitions(), store.current().definitions());
        assertEquals(1, store.versioned().revision());
    }

    @Test
    void rejectsCatalogAboveHardBound() {
        List<ExplorationDefinitionSnapshot.Source> sources = new ArrayList<>();
        for (int index = 0; index <= ExplorationDefinitionSnapshot.MAX_DEFINITIONS; index++) {
            sources.add(source("entry_" + index, definition(WorldTopology.HUB, new BlockPos(index, 64, 0), 1)));
        }

        assertThrows(ExplorationDefinitionSnapshot.ValidationException.class,
                () -> ExplorationDefinitionSnapshot.compile(sources));
    }

    @Test
    void nullSourceIsReportedAsValidationProblem() {
        List<ExplorationDefinitionSnapshot.Source> sources = new ArrayList<>();
        sources.add(null);

        var exception = assertThrows(ExplorationDefinitionSnapshot.ValidationException.class,
                () -> ExplorationDefinitionSnapshot.compile(sources));

        assertTrue(exception.problems().stream()
                .anyMatch(problem -> problem.cause().contains("source is invalid")));
    }

    private static ExplorationDefinition definition(
            net.minecraft.resources.ResourceKey<Level> dimension, BlockPos position, int version) {
        return new ExplorationDefinition(
                "discovery.rovenfall.test", "discovery.rovenfall.test.description",
                version, dimension, position, 8, false, Optional.of(25L));
    }

    private static ExplorationDefinitionSnapshot.Source source(String path, ExplorationDefinition definition) {
        return new ExplorationDefinitionSnapshot.Source(
                id("rovenfall/discoveries/" + path + ".json"), "test", id(path), definition);
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("rovenfall", path);
    }
}
