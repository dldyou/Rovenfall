package org.dldyou.rovenfall.quest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

class QuestDefinitionSnapshotTest {
    @Test
    void shippedFrontierChapterDecodesAndCompilesAsOneStoryGraph() {
        var snapshot = QuestDefinitionSnapshot.compile(List.of(
                shipped("first_steps"),
                shipped("frontier_homestead"),
                shipped("expedition_provisions"),
                shipped("wilderness_patrol"),
                shipped("rift_warden_oath")));

        assertEquals(5, snapshot.storyQuests().size());
        assertEquals(3, snapshot.quest(id("first_steps")).orElseThrow().objectives().size());
        var finale = snapshot.quest(id("rift_warden_oath")).orElseThrow();
        assertEquals(List.of(
                id("expedition_provisions"),
                id("frontier_homestead"),
                id("wilderness_patrol")), finale.prerequisites());
        assertEquals(400, finale.rewards().currency());
        assertTrue(finale.objectives().stream().anyMatch(objective ->
                objective.kind() == QuestDefinition.Kind.BOSS_DEFEAT
                        && objective.target().filter(id("rift_warden")::equals).isPresent()));
    }

    @Test
    void codecAndSnapshotSeparateBoundedContractTemplatesFromStoryQuests() {
        QuestDefinition daily = QuestDefinition.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("""
                {"translation_key":"quest.rovenfall.daily_trade",
                 "description_translation_key":"quest.rovenfall.daily_trade.description",
                 "version":1,
                 "objectives":[{"id":"rovenfall:objectives/daily_trade","kind":"shop_trade","required_count":1}],
                 "contract":{"cadence":"daily"}}
                """)).getOrThrow();
        QuestDefinition story = definition(List.of(), List.of(
                objective("story", QuestDefinition.Kind.SHOP_TRADE, Optional.empty())));
        var snapshot = QuestDefinitionSnapshot.compile(List.of(
                new QuestDefinitionSnapshot.Source(file("daily_trade"), "test", id("daily_trade"), daily),
                new QuestDefinitionSnapshot.Source(file("story"), "test", id("story"), story)));

        assertEquals(2, QuestDefinition.Cadence.DAILY.slots());
        assertEquals(1, QuestDefinition.Cadence.WEEKLY.slots());
        assertEquals(1, snapshot.contractCount());
        assertEquals(Set.of(id("story")), snapshot.storyQuests().keySet());
        assertEquals(Set.of(id("daily_trade")),
                snapshot.contractTemplates(QuestDefinition.Cadence.DAILY).keySet());
        assertTrue(snapshot.contractTemplates(QuestDefinition.Cadence.WEEKLY).isEmpty());
        assertTrue(story.contract().isEmpty());
    }

    @Test
    void rejectsContractPrerequisitesMultipleObjectivesStoryDependenciesAndCatalogOverflow() {
        QuestDefinition contract = contract(
                QuestDefinition.Cadence.DAILY,
                List.of(id("story")),
                List.of(
                        objective("contract_one", QuestDefinition.Kind.SHOP_TRADE, Optional.empty()),
                        objective("contract_two", QuestDefinition.Kind.SHOP_TRADE, Optional.empty())));
        QuestDefinition story = definition(List.of(id("contract")), List.of(
                objective("story", QuestDefinition.Kind.SHOP_TRADE, Optional.empty())));
        var invalid = assertThrows(QuestDefinitionSnapshot.ValidationException.class, () ->
                QuestDefinitionSnapshot.compile(List.of(
                        new QuestDefinitionSnapshot.Source(file("contract"), "test", id("contract"), contract),
                        new QuestDefinitionSnapshot.Source(file("story"), "test", id("story"), story))));

        assertTrue(invalid.getMessage().contains("contract cannot define prerequisites"));
        assertTrue(invalid.getMessage().contains("contract must define exactly one objective"));
        assertTrue(invalid.getMessage().contains("story quest cannot require contract"));

        List<QuestDefinitionSnapshot.Source> tooMany = java.util.stream.IntStream
                .rangeClosed(0, QuestDefinitionSnapshot.MAX_CONTRACT_DEFINITIONS)
                .mapToObj(index -> new QuestDefinitionSnapshot.Source(
                        file("contract_" + index), "test", id("contract_" + index),
                        contract(QuestDefinition.Cadence.DAILY, List.of(), List.of(
                                objective("bounded_" + index, QuestDefinition.Kind.SHOP_TRADE, Optional.empty())))))
                .toList();
        var overflow = assertThrows(QuestDefinitionSnapshot.ValidationException.class,
                () -> QuestDefinitionSnapshot.compile(tooMany));
        assertTrue(overflow.getMessage().contains("contract definition count exceeds"));
    }

    @Test
    void compilesValidatedQuestGraphAndObjectiveKinds() {
        var snapshot = QuestDefinitionSnapshot.compile(List.of(
                source("first_steps", List.of(), List.of(
                        objective("activity", QuestDefinition.Kind.ACTIVITY, Optional.of(id("mining"))),
                        objective("trade", QuestDefinition.Kind.SHOP_TRADE, Optional.empty()),
                        objective("land", QuestDefinition.Kind.CLAIM_PURCHASE, Optional.empty()))),
                source("boss_hunt", List.of(id("first_steps")), List.of(
                        objective("boss", QuestDefinition.Kind.BOSS_DEFEAT,
                                Optional.of(id("rift_warden")))))));

        assertEquals(2, snapshot.size());
        assertEquals(List.of(id("first_steps")), snapshot.quest(id("boss_hunt")).orElseThrow().prerequisites());
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.quests().put(id("other"), definition(List.of(), List.of(
                        objective("other", QuestDefinition.Kind.SHOP_TRADE, Optional.empty())))));
    }

    @Test
    void rejectsDuplicateIdsMissingReferencesCyclesAndInvalidTargetPolicies() {
        var error = assertThrows(QuestDefinitionSnapshot.ValidationException.class, () ->
                QuestDefinitionSnapshot.compile(List.of(
                        source("duplicate_file", "duplicate", List.of(id("missing")), List.of(
                                objective("shared", QuestDefinition.Kind.ACTIVITY, Optional.empty()),
                                objective("land", QuestDefinition.Kind.CLAIM_PURCHASE,
                                        Optional.of(id("not_allowed"))))),
                        source("duplicate_other", "duplicate", List.of(), List.of(
                                objective("other", QuestDefinition.Kind.SHOP_TRADE, Optional.empty()))),
                        source("cycle_one", List.of(id("cycle_two")), List.of(
                                objective("shared", QuestDefinition.Kind.SHOP_TRADE, Optional.empty()))),
                        source("cycle_two", List.of(id("cycle_one")), List.of(
                                objective("cycle_two", QuestDefinition.Kind.BOSS_DEFEAT, Optional.empty()))))));

        assertTrue(error.getMessage().contains("duplicate quest definition ID"));
        assertTrue(error.getMessage().contains("missing prerequisite quest"));
        assertTrue(error.getMessage().contains("duplicate objective ID"));
        assertTrue(error.getMessage().contains("activity objective requires target"));
        assertTrue(error.getMessage().contains("claim purchase objective cannot define target"));
        assertTrue(error.getMessage().contains("quest dependency cycle"));
    }

    @Test
    void rejectsDirectConstructionThatBypassesCodecBoundsAndKeyChecks() {
        QuestDefinition invalid = new QuestDefinition(
                "Invalid Key",
                "quest.rovenfall.invalid description",
                0,
                List.of(id("same"), id("same")),
                List.of(new QuestDefinition.Objective(
                        id("invalid_count"), QuestDefinition.Kind.SHOP_TRADE, Optional.empty(), 0)));

        var error = assertThrows(QuestDefinitionSnapshot.ValidationException.class, () ->
                QuestDefinitionSnapshot.compile(List.of(new QuestDefinitionSnapshot.Source(
                        file("invalid"), "test", id("invalid"), invalid))));

        assertTrue(error.getMessage().contains("invalid title translation key"));
        assertTrue(error.getMessage().contains("invalid description translation key"));
        assertTrue(error.getMessage().contains("version must be between"));
        assertTrue(error.getMessage().contains("duplicate prerequisite quest ID"));
        assertTrue(error.getMessage().contains("objective required count must be between"));
    }

    @Test
    void rejectsDefinitionsAboveTheObjectiveBound() {
        List<QuestDefinition.Objective> objectives = java.util.stream.IntStream
                .rangeClosed(0, QuestDefinition.MAX_OBJECTIVES)
                .mapToObj(index -> objective(
                        "objective_" + index, QuestDefinition.Kind.SHOP_TRADE, Optional.empty()))
                .toList();

        var error = assertThrows(QuestDefinitionSnapshot.ValidationException.class, () ->
                QuestDefinitionSnapshot.compile(List.of(source("oversized", List.of(), objectives))));

        assertTrue(error.getMessage().contains("objective count must be between"));
    }

    @Test
    void codecRejectsUnsafeNumericAndIdentifierInput() {
        var invalidVersion = QuestDefinition.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("""
                {"translation_key":"quest.rovenfall.invalid",
                 "description_translation_key":"quest.rovenfall.invalid.description",
                 "version":0,
                 "objectives":[{"id":"rovenfall:objective","kind":"shop_trade","required_count":1}]}
                """));
        var invalidCount = QuestDefinition.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("""
                {"translation_key":"quest.rovenfall.invalid",
                 "description_translation_key":"quest.rovenfall.invalid.description",
                 "version":1,
                 "objectives":[{"id":"not an id","kind":"shop_trade","required_count":0}]}
                """));

        assertFalse(invalidVersion.isSuccess());
        assertFalse(invalidCount.isSuccess());
    }

    @Test
    void failedStoreReplacementIsAtomic() {
        var store = new QuestDefinitionStore();
        QuestDefinitionSnapshot installed = store.replace(List.of(source(
                "first_steps", List.of(), List.of(
                        objective("trade", QuestDefinition.Kind.SHOP_TRADE, Optional.empty())))));
        long revision = store.revision();

        assertThrows(QuestDefinitionSnapshot.ValidationException.class, () -> store.replace(List.of(source(
                "invalid", List.of(id("missing")), List.of(
                        objective("invalid", QuestDefinition.Kind.SHOP_TRADE, Optional.empty()))))));

        assertSame(installed, store.current());
        assertEquals(revision, store.revision());
    }

    private static QuestDefinitionSnapshot.Source source(
            String path,
            List<Identifier> prerequisites,
            List<QuestDefinition.Objective> objectives) {
        return source(path, path, prerequisites, objectives);
    }

    private static QuestDefinitionSnapshot.Source shipped(String path) {
        var stream = QuestDefinitionSnapshotTest.class.getResourceAsStream(
                "/data/rovenfall/rovenfall/quests/" + path + ".json");
        try (var reader = new InputStreamReader(java.util.Objects.requireNonNull(stream), StandardCharsets.UTF_8)) {
            QuestDefinition definition = QuestDefinition.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseReader(reader))
                    .getOrThrow();
            return new QuestDefinitionSnapshot.Source(file(path), "builtin", id(path), definition);
        } catch (java.io.IOException exception) {
            throw new AssertionError(exception);
        }
    }

    private static QuestDefinitionSnapshot.Source source(
            String filePath,
            String id,
            List<Identifier> prerequisites,
            List<QuestDefinition.Objective> objectives) {
        return new QuestDefinitionSnapshot.Source(
                file(filePath), "test", id(id), definition(prerequisites, objectives));
    }

    private static QuestDefinition definition(
            List<Identifier> prerequisites, List<QuestDefinition.Objective> objectives) {
        return new QuestDefinition(
                "quest.rovenfall.test", "quest.rovenfall.test.description", 1, prerequisites, objectives);
    }

    private static QuestDefinition contract(
            QuestDefinition.Cadence cadence,
            List<Identifier> prerequisites,
            List<QuestDefinition.Objective> objectives) {
        return new QuestDefinition(
                "quest.rovenfall.contract", "quest.rovenfall.contract.description", 1,
                prerequisites, objectives, QuestDefinition.Rewards.NONE,
                Optional.of(new QuestDefinition.Contract(cadence)));
    }

    private static QuestDefinition.Objective objective(
            String path, QuestDefinition.Kind kind, Optional<Identifier> target) {
        return new QuestDefinition.Objective(id("objectives/" + path), kind, target, 1);
    }

    private static Identifier file(String path) {
        return Identifier.fromNamespaceAndPath("rovenfall", "rovenfall/quests/" + path + ".json");
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("rovenfall", path);
    }
}
