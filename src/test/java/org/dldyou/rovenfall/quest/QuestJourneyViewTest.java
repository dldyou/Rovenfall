package org.dldyou.rovenfall.quest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

final class QuestJourneyViewTest {
    @Test
    void projectsEveryRetainedStateWithoutUsingChangedOrMissingDefinitions() {
        QuestDefinitionSnapshot definitions = definitions(
                quest("available", 1, List.of(), 7, 2),
                quest("completed", 1, List.of(), 80, 4),
                quest("legacy", 1, List.of(), 90, 5),
                quest("pending", 1, List.of(), 70, 3),
                quest("prerequisite", 1, List.of(id("changed")), 10, 1),
                quest("changed", 2, List.of(), 20, 2),
                quest("changed_legacy", 2, List.of(), 21, 2));
        QuestPlayerState state = new QuestPlayerState(Map.of(
                id("completed"), completed(1, reward(1, 31, 12, "mining", 6), 31),
                id("legacy"), legacyCompleted(1, 32),
                id("pending"), pending(1, 33, 11, "hunting", 8),
                id("changed"), completed(1, reward(1, 34, 14, "mining", 7), 34),
                id("changed_legacy"), legacyCompleted(1, 35),
                id("removed"), pending(1, 36, 13, "hunting", 9)));

        QuestJourneyView view = QuestJourneyView.create(definitions, state, 8, false, 0, 28);
        Map<Identifier, QuestJourneyView.QuestRow> rows = view.entries().stream()
                .collect(Collectors.toMap(QuestJourneyView.QuestRow::id, Function.identity()));

        assertFalse(view.writable());
        assertEquals(QuestJourneyView.Status.AVAILABLE, rows.get(id("available")).status());
        assertEquals(QuestJourneyView.Status.COMPLETED, rows.get(id("completed")).status());
        assertEquals(new QuestJourneyView.RewardPreview(12, Optional.of(id("mining")), 6),
                rows.get(id("completed")).rewardPreview().orElseThrow());
        assertTrue(rows.get(id("legacy")).rewardPreview().isEmpty());
        assertEquals(QuestJourneyView.Status.PENDING, rows.get(id("pending")).status());
        assertEquals(new QuestJourneyView.RewardPreview(11, Optional.of(id("hunting")), 8),
                rows.get(id("pending")).rewardPreview().orElseThrow());
        assertEquals(QuestJourneyView.Status.PREREQUISITE_LOCKED,
                rows.get(id("prerequisite")).status());
        assertEquals(List.of(id("changed")), rows.get(id("prerequisite")).missingPrerequisites().stream()
                .map(QuestJourneyView.PrerequisiteRow::id).toList());
        assertEquals(QuestJourneyView.Status.DEFINITION_CHANGED, rows.get(id("changed")).status());
        assertTrue(rows.get(id("changed")).objectives().isEmpty());
        assertEquals(new QuestJourneyView.RewardPreview(14, Optional.of(id("mining")), 7),
                rows.get(id("changed")).rewardPreview().orElseThrow());
        assertTrue(rows.get(id("changed_legacy")).rewardPreview().isEmpty());
        assertEquals(QuestJourneyView.Status.UNRESOLVED, rows.get(id("removed")).status());
        assertTrue(rows.get(id("removed")).translationKey().isEmpty());
        assertEquals(new QuestJourneyView.RewardPreview(13, Optional.of(id("hunting")), 9),
                rows.get(id("removed")).rewardPreview().orElseThrow());
    }

    @Test
    void sameVersionCompletionUnlocksPrerequisiteAndProgressIsClamped() {
        QuestDefinitionSnapshot definitions = definitions(
                quest("first", 1, List.of(), 0, 1),
                quest("second", 1, List.of(id("first")), 0, 5));
        QuestPlayerState state = new QuestPlayerState(Map.of(
                id("first"), legacyCompleted(1, 41),
                id("second"), progress("second", 1, 99)));

        QuestJourneyView view = QuestJourneyView.create(definitions, state, 2, true, 0, 28);
        QuestJourneyView.QuestRow second = view.entries().stream()
                .filter(row -> row.id().equals(id("second"))).findFirst().orElseThrow();

        assertEquals(QuestJourneyView.Status.IN_PROGRESS, second.status());
        assertTrue(second.missingPrerequisites().isEmpty());
        assertEquals(5, second.objectives().getFirst().progress());
        assertTrue(second.objectives().getFirst().complete());
        assertTrue(view.nextStep().isEmpty());
    }

    @Test
    void nextStepPrefersStartedQuestAndCarriesOnlyObjectiveGuidance() {
        QuestDefinitionSnapshot definitions = definitions(
                quest("a_available", 1, List.of(), 0, 4),
                activityQuest("b_started", 1, 10, id("mining")));
        QuestPlayerState state = new QuestPlayerState(Map.of(id("b_started"), progress("b_started", 1, 3)));

        QuestJourneyView writable = QuestJourneyView.create(definitions, state, 4, true, 0, 28);
        QuestJourneyView readOnly = QuestJourneyView.create(definitions, state, 4, false, 0, 28);
        QuestJourneyView.NextStep next = writable.nextStep().orElseThrow();

        assertEquals(id("b_started"), next.questId());
        assertEquals("quest.rovenfall.b_started", next.questTranslationKey());
        assertEquals(QuestDefinition.Kind.ACTIVITY, next.kind());
        assertEquals(Optional.of(id("mining")), next.target());
        assertEquals(3, next.progress());
        assertEquals(10, next.requiredCount());
        assertEquals(writable.entries(), readOnly.entries());
        assertEquals(writable.nextStep(), readOnly.nextStep());
    }

    @Test
    void actionableJourneysAppearBeforeLexicallyEarlierLockedJourneys() {
        QuestDefinitionSnapshot definitions = definitions(
                quest("a_locked", 1, List.of(id("z_starter")), 0, 1),
                quest("z_starter", 1, List.of(), 0, 1));

        QuestJourneyView view = QuestJourneyView.create(
                definitions, QuestPlayerState.EMPTY, 1, true, 0, 28);

        assertEquals(List.of(id("z_starter"), id("a_locked")),
                view.entries().stream().map(QuestJourneyView.QuestRow::id).toList());
        assertEquals(QuestJourneyView.Status.AVAILABLE, view.entries().getFirst().status());
    }

    @Test
    void filtersStoryJourneysBeforePagingWithoutChangingNextStep() {
        QuestDefinitionSnapshot definitions = definitions(
                quest("available", 1, List.of(), 0, 1),
                quest("completed", 1, List.of(), 0, 1),
                quest("locked", 1, List.of(id("available")), 0, 1),
                activityQuest("started", 1, 10, id("mining")));
        QuestPlayerState state = new QuestPlayerState(Map.of(
                id("completed"), legacyCompleted(1, 51),
                id("started"), progress("started", 1, 3)));

        QuestJourneyView actionable = QuestJourneyView.create(
                definitions, state, 2, true, 0, 28, QuestJourneyView.Filter.ACTIONABLE);
        QuestJourneyView completed = QuestJourneyView.create(
                definitions, state, 2, true, 0, 28, QuestJourneyView.Filter.COMPLETED);

        assertEquals(List.of(id("started"), id("available")),
                actionable.entries().stream().map(QuestJourneyView.QuestRow::id).toList());
        assertEquals(List.of(id("completed")),
                completed.entries().stream().map(QuestJourneyView.QuestRow::id).toList());
        assertEquals(2, actionable.totalEntries());
        assertEquals(id("started"), completed.nextStep().orElseThrow().questId());
        assertThrows(IllegalArgumentException.class, () -> QuestJourneyView.create(
                definitions, state, 2, true, 0, 28, null));
    }

    @Test
    void pagesInIdentifierOrderAndBoundsThePublishedWindow() {
        List<QuestDefinitionSnapshot.Source> sources = new ArrayList<>();
        for (int index = 29; index >= 0; index--) {
            sources.add(quest("quest_" + String.format("%02d", index), 1, List.of(), 0, 1));
        }
        QuestDefinitionSnapshot definitions = QuestDefinitionSnapshot.compile(sources);

        QuestJourneyView view = QuestJourneyView.create(definitions, QuestPlayerState.EMPTY, 1, true, 99, 28);

        assertEquals(1, view.page());
        assertEquals(2, view.totalPages());
        assertEquals(30, view.totalEntries());
        assertEquals(List.of(id("quest_28"), id("quest_29")),
                view.entries().stream().map(QuestJourneyView.QuestRow::id).toList());
        assertThrows(UnsupportedOperationException.class, view.entries()::clear);
        assertThrows(IllegalArgumentException.class, () -> QuestJourneyView.create(
                definitions, QuestPlayerState.EMPTY, 1, true, 0, QuestJourneyView.MAX_PAGE_SIZE + 1));
    }

    @Test
    void storyBoardRetainsAClassificationChangedLegacyEntryWithoutListingNewContracts() {
        QuestDefinition contract = new QuestDefinition(
                "quest.rovenfall.contract.daily",
                "quest.rovenfall.contract.daily.description",
                1,
                List.of(),
                List.of(new QuestDefinition.Objective(
                        id("contract/objective"), QuestDefinition.Kind.SHOP_TRADE, Optional.empty(), 1)),
                QuestDefinition.Rewards.NONE,
                Optional.of(new QuestDefinition.Contract(QuestDefinition.Cadence.DAILY)));
        QuestDefinitionSnapshot definitions = definitions(
                quest("story", 1, List.of(), 0, 1), source("contract", contract));
        QuestPlayerState legacy = new QuestPlayerState(Map.of(
                id("contract"), progress("contract", 1, 1)));

        QuestJourneyView view = QuestJourneyView.create(definitions, legacy, 1, true, 0, 28);

        assertEquals(List.of(id("story"), id("contract")),
                view.entries().stream().map(QuestJourneyView.QuestRow::id).toList());
        assertEquals(QuestJourneyView.Status.DEFINITION_CHANGED, view.entries().getLast().status());
        assertEquals(2, view.totalEntries());
    }

    private static QuestDefinitionSnapshot definitions(QuestDefinitionSnapshot.Source... sources) {
        return QuestDefinitionSnapshot.compile(List.of(sources));
    }

    private static QuestDefinitionSnapshot.Source quest(
            String path, int version, List<Identifier> prerequisites, long currency, int required) {
        return source(path, new QuestDefinition(
                "quest.rovenfall." + path,
                "quest.rovenfall." + path + ".description",
                version,
                prerequisites,
                List.of(new QuestDefinition.Objective(
                        id(path + "/objective"), QuestDefinition.Kind.SHOP_TRADE, Optional.empty(), required)),
                new QuestDefinition.Rewards(currency, Optional.empty())));
    }

    private static QuestDefinitionSnapshot.Source activityQuest(
            String path, int version, int required, Identifier activity) {
        return source(path, new QuestDefinition(
                "quest.rovenfall." + path,
                "quest.rovenfall." + path + ".description",
                version,
                List.of(),
                List.of(new QuestDefinition.Objective(
                        id(path + "/objective"), QuestDefinition.Kind.ACTIVITY, Optional.of(activity), required)),
                QuestDefinition.Rewards.NONE));
    }

    private static QuestDefinitionSnapshot.Source source(String path, QuestDefinition definition) {
        return new QuestDefinitionSnapshot.Source(
                Identifier.fromNamespaceAndPath("rovenfall", "rovenfall/quests/" + path + ".json"),
                "test", id(path), definition);
    }

    private static QuestPlayerState.QuestEntry progress(String quest, int version, long progress) {
        return new QuestPlayerState.QuestEntry(
                version, Map.of(id(quest + "/objective"), progress), Optional.empty());
    }

    private static QuestPlayerState.QuestEntry pending(
            int version, long transaction, long currency, String activity, long activityXp) {
        return new QuestPlayerState.QuestEntry(
                version,
                Map.of(),
                Optional.of(reward(version, transaction, currency, activity, activityXp)),
                Optional.empty());
    }

    private static QuestPlayerState.QuestEntry completed(
            int version, QuestPlayerState.RewardOperation reward, long transaction) {
        return new QuestPlayerState.QuestEntry(
                version,
                Map.of(),
                Optional.empty(),
                Optional.of(new QuestPlayerState.CompletionReceipt(
                        version, uuid(transaction), 2_000, reward)));
    }

    private static QuestPlayerState.QuestEntry legacyCompleted(int version, long transaction) {
        return new QuestPlayerState.QuestEntry(
                version,
                Map.of(),
                Optional.of(new QuestPlayerState.CompletionReceipt(version, uuid(transaction), 2_000)));
    }

    private static QuestPlayerState.RewardOperation reward(
            int version, long transaction, long currency, String activity, long activityXp) {
        return new QuestPlayerState.RewardOperation(
                version,
                uuid(transaction),
                currency,
                Optional.of(id(activity)),
                activityXp,
                1_000,
                QuestPlayerState.RewardOperation.Phase.CAPTURED);
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("rovenfall", path);
    }

    private static UUID uuid(long value) {
        return new UUID(0, value);
    }
}
