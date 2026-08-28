package org.dldyou.rovenfall.mobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.Lifecycle;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
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
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.loot.LootTable;
import net.neoforged.neoforge.common.conditions.ICondition;
import org.junit.jupiter.api.Test;

final class MobContentSnapshotTest {
    @Test
    void builtInCatalogCompilesToAnImmutableResolvedSnapshot() {
        MobContentCatalog catalog = builtIn();
        MobContentSnapshot.RuntimeBindings bindings = runtimeBindings(catalog);
        MobContentSnapshot snapshot = MobContentSnapshot.compile(List.of(source("foundation", catalog)))
                .validateRuntimeBindings(bindings);

        assertEquals(11, snapshot.size());
        var boss = snapshot.boss(id("rift_warden")).orElseThrow();
        assertEquals(id("rift_warden_vessel"), boss.mob());
        assertEquals(2, boss.phases().size());
        assertEquals(2, boss.phases().get(1).patterns().size());
        assertTrue(snapshot.arena(boss.arena()).isPresent());
        assertTrue(snapshot.contributionRule(boss.contributionRule()).isPresent());
        assertTrue(snapshot.loot(boss.loot()).isPresent());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.mobs().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.mob(id("grove_stalker")).orElseThrow().behaviorModifiers().clear());
        var strictError = assertThrows(MobContentSnapshot.ValidationException.class,
                () -> snapshot.validateRuntimeBindings(MobContentSnapshot.RuntimeBindings.strict(
                        bindings.registries(), Level.OVERWORLD)));
        assertTrue(strictError.problems().stream().anyMatch(problem -> problem.cause().contains("unknown dimension")));
    }

    @Test
    void rejectsMissingReferencesAndInvalidBoundsWithDefinitionEvidence() {
        MobContentCatalog valid = builtIn();
        var boss = valid.bosses().getFirst();
        var invalidBoss = new MobContentCatalog.BossDefinition(
                boss.id(), boss.translationKey(), boss.mob(), boss.arena(), boss.contributionRule(), id("missing_loot"),
                0, List.of(new MobContentCatalog.Phase(
                        boss.phases().getFirst().id(), boss.phases().getFirst().translationKey(), 75,
                        boss.phases().getFirst().patterns())));
        MobContentCatalog invalid = new MobContentCatalog(
                valid.mobs(), valid.mutations(), valid.arenas(), valid.contributionRules(), valid.loot(),
                List.of(invalidBoss));

        var error = assertThrows(MobContentSnapshot.ValidationException.class,
                () -> MobContentSnapshot.compile(List.of(source("invalid", invalid))));

        assertTrue(error.problems().stream().anyMatch(problem -> problem.cause().contains("missing loot reference")));
        assertTrue(error.problems().stream().anyMatch(problem -> problem.cause().contains("reward cooldown")));
        assertTrue(error.problems().stream().anyMatch(problem -> problem.cause().contains("first phase")));
        assertTrue(error.problems().stream().allMatch(problem -> problem.file().equals(file("invalid"))));
    }

    @Test
    void rejectsDuplicateIdsAndInvalidPatternDefinitions() {
        MobContentCatalog valid = builtIn();
        var boss = valid.bosses().getFirst();
        var firstPhase = boss.phases().getFirst();
        var firstPattern = firstPhase.patterns().getFirst();
        var invalidPattern = new MobContentCatalog.PatternDefinition(
                firstPattern.id(), "Invalid Key", id("missing_pattern_type"), 0, firstPattern.durationTicks(),
                firstPattern.cooldownTicks(), 0);
        var invalidBoss = new MobContentCatalog.BossDefinition(
                boss.id(), boss.translationKey(), boss.mob(), boss.arena(), boss.contributionRule(), boss.loot(),
                boss.rewardCooldownTicks(), List.of(new MobContentCatalog.Phase(
                        firstPhase.id(), firstPhase.translationKey(), 100, List.of(firstPattern, invalidPattern))));
        MobContentCatalog invalid = new MobContentCatalog(
                valid.mobs(), valid.mutations(), valid.arenas(), valid.contributionRules(), valid.loot(),
                List.of(invalidBoss));

        var error = assertThrows(MobContentSnapshot.ValidationException.class,
                () -> MobContentSnapshot.compile(List.of(source("invalid_patterns", invalid))));

        assertTrue(error.problems().stream().anyMatch(problem -> problem.cause().contains("duplicate definition ID")));
        assertTrue(error.problems().stream().anyMatch(problem -> problem.cause().contains("invalid translation key")));
        assertTrue(error.problems().stream().anyMatch(problem -> problem.cause().contains("unknown pattern type")));
        assertTrue(error.problems().stream().anyMatch(problem -> problem.cause().contains("telegraph ticks")));
        assertTrue(error.problems().stream().anyMatch(problem -> problem.cause().contains("pattern weight")));
    }

    @Test
    void rejectsMutationMultipliersThatCanEraseOrExplodeAttributes() {
        MobContentCatalog valid = builtIn();
        var mutation = valid.mutations().getFirst();
        var modifier = mutation.attributes().getFirst();
        var invalidMutation = new MobContentCatalog.MutationDefinition(
                mutation.id(), mutation.translationKey(), mutation.eligibleEntityTypes(),
                List.of(new MobContentCatalog.AttributeModifier(
                        modifier.attribute(), modifier.operation(), -1.0)),
                mutation.behaviorModifiers(), mutation.markerTranslationKey(), mutation.spawn(),
                mutation.rewardMultiplierPercent(), mutation.bonusLoot());
        var invalid = new MobContentCatalog(
                valid.mobs(), List.of(invalidMutation), valid.arenas(), valid.contributionRules(),
                valid.loot(), valid.bosses());

        var error = assertThrows(MobContentSnapshot.ValidationException.class,
                () -> MobContentSnapshot.compile(List.of(source("invalid_multiplier", invalid))));

        assertTrue(error.problems().stream().anyMatch(
                problem -> problem.cause().contains("attribute multiplier")));
    }

    @Test
    void rejectsBossArenaDimensionsAndRadiiOutsideProtectionBounds() {
        var wrongDimension = JsonParser.parseString(builtInJson()).getAsJsonObject();
        wrongDimension.getAsJsonArray("arenas").get(0).getAsJsonObject()
                .addProperty("dimension", "minecraft:the_nether");
        var wrongDimensionError = assertThrows(MobContentSnapshot.ValidationException.class,
                () -> MobContentSnapshot.compile(List.of(source(
                        "wrong_arena_dimension",
                        MobContentCatalog.CODEC.parse(JsonOps.INSTANCE, wrongDimension).getOrThrow()))));
        assertTrue(wrongDimensionError.problems().stream().anyMatch(
                problem -> problem.cause().contains("boss arena dimension must be rovenfall:wilderness")));

        var oversized = JsonParser.parseString(builtInJson()).getAsJsonObject();
        oversized.getAsJsonArray("arenas").get(0).getAsJsonObject()
                .addProperty("protection_radius", 1_024);
        var oversizedError = assertThrows(MobContentSnapshot.ValidationException.class,
                () -> MobContentSnapshot.compile(List.of(source(
                        "oversized_arena",
                        MobContentCatalog.CODEC.parse(JsonOps.INSTANCE, oversized).getOrThrow()))));
        assertTrue(oversizedError.problems().stream().anyMatch(
                problem -> problem.cause().contains("boss arena protection exceeds protected-region bounds")));
    }

    @Test
    void rejectsBossExperienceThatCannotBeAwardedAtomicallyToRpgState() {
        MobContentCatalog valid = builtIn();
        var loot = valid.loot().stream().map(definition -> definition.id().equals(id("rift_warden_loot"))
                ? new MobContentCatalog.LootDefinition(
                        definition.id(), definition.lootTable(), definition.rolls(), definition.currency(),
                        (long) Integer.MAX_VALUE + 1L)
                : definition).toList();
        var invalid = new MobContentCatalog(
                valid.mobs(), valid.mutations(), valid.arenas(), valid.contributionRules(), loot, valid.bosses());

        var error = assertThrows(MobContentSnapshot.ValidationException.class,
                () -> MobContentSnapshot.compile(List.of(source("boss_xp_overflow", invalid))));

        assertTrue(error.problems().stream().anyMatch(
                problem -> problem.cause().contains("boss experience reward exceeds the RPG award limit")));
    }

    @Test
    void failedReplacementPreservesTheLastBoundSnapshot() {
        var store = new MobContentStore();
        MobContentCatalog valid = builtIn();
        MobContentSnapshot.RuntimeBindings bindings = runtimeBindings(valid);
        MobContentSnapshot previous = store.replace(List.of(source("foundation", valid)), bindings);
        var mob = valid.mobs().getFirst();
        var unknownEntityMob = new MobContentCatalog.MobDefinition(
                mob.id(), mob.translationKey(), id("missing_entity_type"), mob.maxHealth(), mob.attackDamage(),
                mob.movementSpeed(), mob.behaviorModifiers(), mob.loot(), mob.spawn());
        var mobs = new java.util.ArrayList<>(valid.mobs());
        mobs.set(0, unknownEntityMob);
        MobContentCatalog invalid = new MobContentCatalog(
                mobs, valid.mutations(), valid.arenas(), valid.contributionRules(), valid.loot(), valid.bosses());

        var error = assertThrows(MobContentSnapshot.ValidationException.class,
                () -> store.replace(List.of(source("unknown_entity", invalid)), bindings));

        assertSame(previous, store.current());
        assertTrue(error.problems().stream().anyMatch(problem -> problem.cause().contains("unknown entity type")));
        assertFalse(store.current().mob(id("grove_stalker")).isEmpty());
    }

    @Test
    void resourceManagerReloadRejectsUnboundResourcesAndHubContentWithoutReplacingSnapshot() {
        MobContentCatalog validCatalog = builtIn();
        MobContentSnapshot.RuntimeBindings bindings = runtimeBindings(validCatalog);
        var listener = new MobContentReloadListener();
        listener.injectContext(ICondition.IContext.EMPTY, bindings.registries());
        ResourceManager validManager = resourceManager(builtInJson());
        listener.apply(listener.prepare(validManager, null), validManager, null);
        MobContentSnapshot previous = listener.snapshot();
        assertEquals(11, previous.size());

        var unboundJson = JsonParser.parseString(builtInJson()).getAsJsonObject();
        unboundJson.getAsJsonArray("loot").get(0).getAsJsonObject()
                .addProperty("loot_table", "rovenfall:missing_loot_table");
        ResourceManager unboundManager = resourceManager(unboundJson.toString());
        MobContentSnapshot unbound = listener.prepare(unboundManager, null);
        var bindingError = assertThrows(MobContentSnapshot.ValidationException.class,
                () -> unbound.validateRuntimeBindings(bindings));
        assertTrue(bindingError.problems().stream().anyMatch(
                problem -> problem.cause().contains("unknown loot table: rovenfall:missing_loot_table")));
        listener.apply(unbound, unboundManager, null);
        assertSame(previous, listener.snapshot());
        assertFalse(listener.snapshot().loot(id("rift_warden_loot")).isEmpty());
        assertTrue(listener.lastProblems().stream().anyMatch(problem ->
                problem.file().equals(file("foundation"))
                        && problem.definitionId().equals(id("grove_stalker_loot"))
                        && problem.cause().contains("unknown loot table: rovenfall:missing_loot_table")));
        listener.beginValidationAttempt();
        assertTrue(listener.lastProblems().isEmpty());

        var hubJson = JsonParser.parseString(builtInJson()).getAsJsonObject();
        hubJson.getAsJsonArray("mutations").get(0).getAsJsonObject().getAsJsonObject("spawn")
                .addProperty("dimension", "minecraft:overworld");
        hubJson.getAsJsonArray("arenas").get(0).getAsJsonObject()
                .addProperty("dimension", "minecraft:overworld");
        var hubError = assertThrows(MobContentSnapshot.ValidationException.class,
                () -> listener.prepare(resourceManager(hubJson.toString()), null));
        assertTrue(hubError.problems().stream().anyMatch(
                problem -> problem.cause().contains("spawn dimension must be rovenfall:wilderness")));
        assertTrue(hubError.problems().stream().anyMatch(
                problem -> problem.cause().contains("boss arena dimension must be rovenfall:wilderness")));
        assertSame(previous, listener.snapshot());
        assertFalse(listener.snapshot().mob(id("grove_stalker")).isEmpty());
        assertTrue(listener.lastProblems().stream().anyMatch(problem ->
                problem.file().equals(file("foundation"))
                        && problem.definitionId().equals(id("volatile"))
                        && problem.cause().contains("spawn dimension must be rovenfall:wilderness")));
        listener.beginValidationAttempt();
        assertTrue(listener.lastProblems().isEmpty());
    }

    @Test
    void catalogLimitFailureRetainsDiagnosticWithoutReplacingSnapshot() {
        var listener = new MobContentReloadListener();
        MobContentCatalog validCatalog = builtIn();
        MobContentSnapshot.RuntimeBindings bindings = runtimeBindings(validCatalog);
        listener.injectContext(ICondition.IContext.EMPTY, bindings.registries());
        ResourceManager validManager = resourceManager(builtInJson());
        listener.apply(listener.prepare(validManager, null), validManager, null);
        MobContentSnapshot previous = listener.snapshot();

        var error = assertThrows(MobContentSnapshot.ValidationException.class,
                () -> listener.prepare(resourceManagerWithCatalogCount(MobContentSnapshot.MAX_CATALOGS + 1), null));

        Identifier catalog = Identifier.fromNamespaceAndPath("rovenfall", "mob_content_catalog");
        assertSame(previous, listener.snapshot());
        assertTrue(error.problems().stream().anyMatch(problem ->
                problem.file().equals(catalog)
                        && problem.definitionId().equals(catalog)
                        && problem.cause().contains("catalog count exceeds " + MobContentSnapshot.MAX_CATALOGS)));
        assertEquals(error.problems(), listener.lastProblems());
    }

    private static MobContentCatalog builtIn() {
        return MobContentCatalog.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(builtInJson()))
                .getOrThrow(AssertionError::new);
    }

    private static String builtInJson() {
        String path = "/data/rovenfall/rovenfall/mob_content/foundation.json";
        try (var stream = MobContentSnapshotTest.class.getResourceAsStream(path)) {
            if (stream == null) {
                throw new AssertionError(path);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (java.io.IOException exception) {
            throw new AssertionError(exception);
        }
    }

    private static MobContentSnapshot.RuntimeBindings runtimeBindings(MobContentCatalog catalog) {
        var lootRegistry = new MappedRegistry<LootTable>(Registries.LOOT_TABLE, Lifecycle.stable());
        catalog.loot().forEach(definition -> lootRegistry.register(
                definition.lootTable(), LootTable.lootTable().build(), RegistrationInfo.BUILT_IN));
        var registries = new RegistryAccess.ImmutableRegistryAccess(List.of(lootRegistry));
        return MobContentSnapshot.RuntimeBindings.awaitingWildernessRegistration(registries);
    }

    private static ResourceManager resourceManager(String json) {
        Identifier file = file("foundation");
        Resource resource = new Resource(TEST_PACK,
                () -> new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
        return new ResourceManager() {
            @Override
            public Set<String> getNamespaces() {
                return Set.of("rovenfall");
            }

            @Override
            public Optional<Resource> getResource(Identifier location) {
                return location.equals(file) ? Optional.of(resource) : Optional.empty();
            }

            @Override
            public List<Resource> getResourceStack(Identifier location) {
                return location.equals(file) ? List.of(resource) : List.of();
            }

            @Override
            public Map<Identifier, Resource> listResources(String directory, Predicate<Identifier> filter) {
                return filter.test(file) ? Map.of(file, resource) : Map.of();
            }

            @Override
            public Map<Identifier, List<Resource>> listResourceStacks(
                    String directory, Predicate<Identifier> filter) {
                return filter.test(file) ? Map.of(file, List.of(resource)) : Map.of();
            }

            @Override
            public Stream<PackResources> listPacks() {
                return Stream.of(TEST_PACK);
            }
        };
    }

    private static ResourceManager resourceManagerWithCatalogCount(int count) {
        Resource resource = new Resource(TEST_PACK,
                () -> new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8)));
        Map<Identifier, List<Resource>> resources = new LinkedHashMap<>();
        for (int index = 0; index < count; index++) {
            resources.put(file("catalog_" + index), List.of(resource));
        }
        Map<Identifier, List<Resource>> immutable = Map.copyOf(resources);
        return new ResourceManager() {
            @Override
            public Set<String> getNamespaces() {
                return Set.of("rovenfall");
            }

            @Override
            public Optional<Resource> getResource(Identifier location) {
                return Optional.empty();
            }

            @Override
            public List<Resource> getResourceStack(Identifier location) {
                return immutable.getOrDefault(location, List.of());
            }

            @Override
            public Map<Identifier, Resource> listResources(String directory, Predicate<Identifier> filter) {
                return Map.of();
            }

            @Override
            public Map<Identifier, List<Resource>> listResourceStacks(
                    String directory, Predicate<Identifier> filter) {
                return immutable;
            }

            @Override
            public Stream<PackResources> listPacks() {
                return Stream.of(TEST_PACK);
            }
        };
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

    private static MobContentSnapshot.Source source(String path, MobContentCatalog catalog) {
        return new MobContentSnapshot.Source(file(path), "test-pack", id(path), catalog);
    }

    private static Identifier file(String path) {
        return Identifier.fromNamespaceAndPath("rovenfall", "rovenfall/mob_content/" + path + ".json");
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("rovenfall", path);
    }
}
