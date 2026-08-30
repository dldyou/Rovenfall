package org.dldyou.rovenfall.quest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.MetadataSectionType;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.junit.jupiter.api.Test;

class QuestDefinitionReloadListenerTest {
    @Test
    void failedPrepareRetainsSnapshotRevisionAndStructuredProblemsUntilNextAttempt() {
        var listener = new QuestDefinitionReloadListener();
        ResourceManager valid = resourceManager(Optional.of("rovenfall:mining"));
        listener.apply(listener.prepare(valid, null), valid, null);
        QuestDefinitionSnapshot previous = listener.snapshot();
        long revision = listener.revision();

        var error = assertThrows(QuestDefinitionSnapshot.ValidationException.class,
                () -> listener.prepare(resourceManager(Optional.empty()), null));

        assertSame(previous, listener.snapshot());
        assertEquals(revision, listener.revision());
        assertTrue(error.problems().stream().anyMatch(problem -> problem.file().equals(questFile())
                && problem.definitionId().equals(id("first_steps"))
                && problem.cause().contains("requires target")));
        assertEquals(error.problems(), listener.lastProblems());

        listener.beginValidationAttempt();

        assertTrue(listener.lastProblems().isEmpty());
        assertSame(previous, listener.snapshot());
        assertEquals(revision, listener.revision());
    }

    private static ResourceManager resourceManager(Optional<String> target) {
        String targetField = target.map(value -> ",\"target\":\"" + value + "\"").orElse("");
        Map<Identifier, Resource> resources = Map.of(questFile(), resource("""
                {"translation_key":"quest.rovenfall.first_steps",
                 "description_translation_key":"quest.rovenfall.first_steps.description",
                 "version":1,
                 "objectives":[{"id":"rovenfall:first_steps/activity","kind":"activity",
                                "required_count":1%s}]}
                """.formatted(targetField)));
        return new ResourceManager() {
            @Override
            public Set<String> getNamespaces() {
                return Set.of("rovenfall");
            }

            @Override
            public Optional<Resource> getResource(Identifier location) {
                return Optional.ofNullable(resources.get(location));
            }

            @Override
            public List<Resource> getResourceStack(Identifier location) {
                return resources.containsKey(location) ? List.of(resources.get(location)) : List.of();
            }

            @Override
            public Map<Identifier, Resource> listResources(String directory, Predicate<Identifier> filter) {
                return resources.entrySet().stream().filter(entry -> inDirectory(entry.getKey(), directory))
                        .filter(entry -> filter.test(entry.getKey()))
                        .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
            }

            @Override
            public Map<Identifier, List<Resource>> listResourceStacks(
                    String directory, Predicate<Identifier> filter) {
                return resources.entrySet().stream().filter(entry -> inDirectory(entry.getKey(), directory))
                        .filter(entry -> filter.test(entry.getKey()))
                        .collect(java.util.stream.Collectors.toMap(
                                Map.Entry::getKey, entry -> List.of(entry.getValue())));
            }

            @Override
            public Stream<PackResources> listPacks() {
                return Stream.of(TEST_PACK);
            }
        };
    }

    private static boolean inDirectory(Identifier file, String directory) {
        return file.getPath().startsWith(directory + "/");
    }

    private static Resource resource(String json) {
        return new Resource(TEST_PACK, () -> new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
    }

    private static Identifier questFile() {
        return Identifier.fromNamespaceAndPath("rovenfall", "rovenfall/quests/first_steps.json");
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("rovenfall", path);
    }

    private static final PackResources TEST_PACK = new PackResources() {
        private final PackLocationInfo location = new PackLocationInfo(
                "test-pack", Component.literal("test-pack"), PackSource.DEFAULT, Optional.empty());

        @Override
        public IoSupplier<java.io.InputStream> getRootResource(String... path) {
            return null;
        }

        @Override
        public IoSupplier<java.io.InputStream> getResource(PackType type, Identifier location) {
            return null;
        }

        @Override
        public void listResources(PackType type, String namespace, String directory, ResourceOutput output) {
        }

        @Override
        public Set<String> getNamespaces(PackType type) {
            return Set.of("rovenfall");
        }

        @Override
        public <T> T getMetadataSection(MetadataSectionType<T> metadataSerializer) {
            return null;
        }

        @Override
        public PackLocationInfo location() {
            return location;
        }

        @Override
        public void close() {
        }
    };
}
