package org.dldyou.rovenfall.definition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.util.List;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

final class DefinitionStoreTest {
    @Test
    void publishesACompleteImmutableSnapshot() {
        var store = new DefinitionStore();

        DefinitionSnapshot snapshot = store.replace(List.of(
                source("root", 1),
                source("child", 2, id("root"))
        ));

        assertSame(snapshot, store.current());
        assertEquals(2, snapshot.size());
        assertEquals(2, snapshot.get(id("child")).orElseThrow().value());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.definitions().clear());
    }

    @Test
    void invalidReplacementPreservesThePreviousSnapshot() {
        var store = new DefinitionStore();
        DefinitionSnapshot previous = store.replace(List.of(source("root", 1)));

        var error = assertThrows(DefinitionSnapshot.ValidationException.class, () -> store.replace(List.of(
                source("broken", 2, id("missing"))
        )));

        assertSame(previous, store.current());
        assertEquals(id("broken"), error.problems().getFirst().definitionId());
        assertTrue(error.problems().getFirst().cause().contains("missing reference"));
    }

    @Test
    void rejectsDuplicateDefinitionIdsWithSourceEvidence() {
        var error = assertThrows(DefinitionSnapshot.ValidationException.class, () -> DefinitionSnapshot.compile(List.of(
                source("one", "shared", 1),
                source("two", "shared", 2)
        )));

        assertEquals(id("shared"), error.problems().getFirst().definitionId());
        assertTrue(error.getMessage().contains("one.json"));
        assertTrue(error.getMessage().contains("two.json"));
        assertTrue(error.getMessage().contains("duplicate definition ID"));
    }

    @Test
    void rejectsDependencyCycles() {
        var error = assertThrows(DefinitionSnapshot.ValidationException.class, () -> DefinitionSnapshot.compile(List.of(
                source("one", 1, id("two")),
                source("two", 2, id("one"))
        )));

        assertEquals(2, error.problems().size());
        assertTrue(error.problems().stream().allMatch(problem -> problem.cause().equals("dependency cycle")));
    }

    @Test
    void rejectsMalformedTranslationKeys() {
        var candidate = new DefinitionSnapshot.Source(
                file("broken"), "test-pack", id("broken"), new TestDefinition("Invalid Key", 1, List.of()));

        var error = assertThrows(
                DefinitionSnapshot.ValidationException.class,
                () -> DefinitionSnapshot.compile(List.of(candidate)));

        assertTrue(error.problems().stream().anyMatch(problem -> problem.cause().contains("invalid translation key")));
    }

    @Test
    void codecRejectsOutOfRangeValuesAndReferenceLists() {
        var invalidValue = TestDefinition.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("""
                {"translation_key":"definition.rovenfall.invalid","value":1000001}
                """));
        String references = java.util.stream.IntStream.range(0, TestDefinition.MAX_REFERENCES + 1)
                .mapToObj(index -> "\"rovenfall:ref_" + index + "\"")
                .collect(java.util.stream.Collectors.joining(","));
        var tooManyReferences = TestDefinition.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(
                "{\"translation_key\":\"definition.rovenfall.invalid\",\"value\":1,\"requires\":[" + references + "]}"));

        assertFalse(invalidValue.isSuccess());
        assertFalse(tooManyReferences.isSuccess());
    }

    private static DefinitionSnapshot.Source source(String path, int value, Identifier... requires) {
        return source(path, path, value, requires);
    }

    private static DefinitionSnapshot.Source source(String filePath, String definitionPath, int value, Identifier... requires) {
        return new DefinitionSnapshot.Source(
                file(filePath),
                "test-pack",
                id(definitionPath),
                new TestDefinition("definition.rovenfall." + definitionPath, value, List.of(requires)));
    }

    private static Identifier file(String path) {
        return Identifier.fromNamespaceAndPath("rovenfall", "rovenfall/test_definitions/" + path + ".json");
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("rovenfall", path);
    }
}
