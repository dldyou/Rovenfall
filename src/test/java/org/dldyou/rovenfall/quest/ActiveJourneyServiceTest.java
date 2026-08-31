package org.dldyou.rovenfall.quest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.Identifier;
import org.dldyou.rovenfall.rpg.ActivityDefinition;
import org.dldyou.rovenfall.rpg.RpgDefinitionSnapshot;
import org.junit.jupiter.api.Test;

final class ActiveJourneyServiceTest {
    private static final UUID PLAYER = new UUID(0L, 123L);
    private static final Identifier MINING = id("mining");
    private static final long NOW = Instant.parse("2026-09-01T12:00:00Z").toEpochMilli();

    @Test
    void selectsReplacesAndClearsOneEligibleStoryUsingCas() {
        QuestDefinitionSnapshot definitions = definitions(
                story("first", 1, QuestDefinition.Kind.SHOP_TRADE, Optional.empty(), 3, List.of()),
                story("second", 2, QuestDefinition.Kind.SHOP_TRADE, Optional.empty(), 2, List.of()));
        QuestPlayerSavedData saved = new QuestPlayerSavedData();

        assertEquals(ActiveJourneyService.MutationStatus.SUCCESS,
                ActiveJourneyService.selectStory(saved, definitions, PLAYER, id("first")).status());
        QuestPlayerState first = saved.state(PLAYER);
        assertEquals(Optional.of(QuestPlayerState.TrackedJourney.story(id("first"), 1)),
                first.trackedJourney());
        assertEquals(ActiveJourneyService.MutationStatus.UNCHANGED,
                ActiveJourneyService.selectStory(saved, definitions, PLAYER, id("first")).status());
        assertEquals(ActiveJourneyService.MutationStatus.SUCCESS,
                ActiveJourneyService.selectStory(saved, definitions, PLAYER, id("second")).status());
        assertEquals(ActiveJourneyService.MutationStatus.CONCURRENT_CHANGE,
                ActiveJourneyService.replace(saved, PLAYER, first, Optional.empty(), true).status());

        ActiveJourneyService.MutationResult cleared = ActiveJourneyService.clear(saved, PLAYER);
        assertEquals(ActiveJourneyService.MutationStatus.SUCCESS, cleared.status());
        assertTrue(cleared.cleared());
        assertTrue(saved.state(PLAYER).trackedJourney().isEmpty());
        assertEquals(ActiveJourneyService.MutationStatus.UNCHANGED,
                ActiveJourneyService.clear(saved, PLAYER).status());
    }

    @Test
    void rejectsLockedCompletedPendingAndNonCurrentSelectionsWithoutMutation() {
        QuestDefinitionSnapshot definitions = definitions(
                story("parent", 1, QuestDefinition.Kind.SHOP_TRADE, Optional.empty(), 1, List.of()),
                story("locked", 1, QuestDefinition.Kind.SHOP_TRADE, Optional.empty(), 1, List.of(id("parent"))),
                contract("daily", 1, QuestDefinition.Cadence.DAILY, 3));
        QuestPlayerSavedData saved = new QuestPlayerSavedData();
        assertEquals(ActiveJourneyService.MutationStatus.NOT_ELIGIBLE,
                ActiveJourneyService.selectStory(saved, definitions, PLAYER, id("locked")).status());

        QuestPlayerState.ContractWindow daily = RepeatableContractService.windowAt(
                QuestDefinition.Cadence.DAILY, NOW);
        QuestPlayerState.ContractKey key = new QuestPlayerState.ContractKey(daily, id("daily"));
        QuestPlayerState.QuestEntry completed = new QuestPlayerState.QuestEntry(
                1, Map.of(id("daily/objective"), 3L), Optional.of(
                        new QuestPlayerState.CompletionReceipt(1, new UUID(0, 99), NOW)));
        QuestPlayerState state = new QuestPlayerState(
                Map.of(), Map.of(), Map.of(key, completed), Set.of(daily));
        assertTrue(saved.commit(PLAYER, QuestPlayerState.EMPTY, state));

        assertEquals(ActiveJourneyService.MutationStatus.NOT_ELIGIBLE,
                ActiveJourneyService.selectContract(saved, definitions, PLAYER, key, NOW).status());
        assertEquals(ActiveJourneyService.MutationStatus.NOT_ELIGIBLE,
                ActiveJourneyService.selectContract(
                        saved, definitions, PLAYER, key, NOW + RepeatableContractService.DAY_MILLIS).status());
        assertTrue(saved.state(PLAYER).trackedJourney().isEmpty());

        QuestPlayerSavedData stale = new QuestPlayerSavedData();
        QuestPlayerState staleState = new QuestPlayerState(
                Map.of(), Map.of(),
                Map.of(key, new QuestPlayerState.QuestEntry(2, Map.of(), Optional.empty())), Set.of(daily));
        assertTrue(stale.commit(PLAYER, QuestPlayerState.EMPTY, staleState));
        assertEquals(ActiveJourneyService.MutationStatus.NOT_ELIGIBLE,
                ActiveJourneyService.selectContract(stale, definitions, PLAYER, key, NOW).status());
    }

    @Test
    void reconciliationClearsRemovedStaleCompletedPendingAndRolledOverJourneys() {
        QuestDefinitionSnapshot firstVersion = definitions(
                story("story", 1, QuestDefinition.Kind.SHOP_TRADE, Optional.empty(), 2, List.of()),
                contract("daily", 1, QuestDefinition.Cadence.DAILY, 2));
        QuestPlayerSavedData saved = new QuestPlayerSavedData();
        assertEquals(ActiveJourneyService.MutationStatus.SUCCESS,
                ActiveJourneyService.selectStory(saved, firstVersion, PLAYER, id("story")).status());

        QuestDefinitionSnapshot secondVersion = definitions(
                story("story", 2, QuestDefinition.Kind.SHOP_TRADE, Optional.empty(), 2, List.of()),
                contract("daily", 1, QuestDefinition.Cadence.DAILY, 2));
        assertCleared(saved, secondVersion, NOW);

        QuestPlayerSavedData completedSaved = new QuestPlayerSavedData();
        assertEquals(ActiveJourneyService.MutationStatus.SUCCESS,
                ActiveJourneyService.selectStory(completedSaved, firstVersion, PLAYER, id("story")).status());
        QuestPlayerState current = completedSaved.state(PLAYER);
        QuestPlayerState completed = new QuestPlayerState(
                Map.of(id("story"), new QuestPlayerState.QuestEntry(
                        1, Map.of(id("story/objective"), 2L), Optional.of(
                                new QuestPlayerState.CompletionReceipt(1, new UUID(0, 100), NOW)))),
                current.processedEvidence(), current.contracts(), current.initializedContractWindows(),
                current.trackedJourney());
        assertTrue(completedSaved.commit(PLAYER, current, completed));
        assertCleared(completedSaved, firstVersion, NOW);

        QuestPlayerSavedData pendingSaved = new QuestPlayerSavedData();
        assertEquals(ActiveJourneyService.MutationStatus.SUCCESS,
                ActiveJourneyService.selectStory(pendingSaved, firstVersion, PLAYER, id("story")).status());
        current = pendingSaved.state(PLAYER);
        QuestPlayerState.RewardOperation operation = new QuestPlayerState.RewardOperation(
                1, new UUID(0, 101), 0, Optional.empty(), 0, NOW,
                QuestPlayerState.RewardOperation.Phase.CAPTURED);
        QuestPlayerState pending = new QuestPlayerState(
                Map.of(id("story"), new QuestPlayerState.QuestEntry(
                        1, Map.of(id("story/objective"), 2L), Optional.of(operation), Optional.empty())),
                current.processedEvidence(), current.contracts(), current.initializedContractWindows(),
                current.trackedJourney());
        assertTrue(pendingSaved.commit(PLAYER, current, pending));
        assertCleared(pendingSaved, firstVersion, NOW);

        QuestPlayerSavedData contractSaved = new QuestPlayerSavedData();
        QuestPlayerState.ContractWindow daily = RepeatableContractService.windowAt(
                QuestDefinition.Cadence.DAILY, NOW);
        QuestPlayerState.ContractKey key = new QuestPlayerState.ContractKey(daily, id("daily"));
        QuestPlayerState withContract = new QuestPlayerState(
                Map.of(), Map.of(),
                Map.of(key, new QuestPlayerState.QuestEntry(1, Map.of(), Optional.empty())), Set.of(daily));
        assertTrue(contractSaved.commit(PLAYER, QuestPlayerState.EMPTY, withContract));
        assertEquals(ActiveJourneyService.MutationStatus.SUCCESS,
                ActiveJourneyService.selectContract(contractSaved, firstVersion, PLAYER, key, NOW).status());
        assertCleared(contractSaved, firstVersion, NOW + RepeatableContractService.DAY_MILLIS);

        QuestPlayerSavedData removedSaved = new QuestPlayerSavedData();
        assertEquals(ActiveJourneyService.MutationStatus.SUCCESS,
                ActiveJourneyService.selectStory(removedSaved, firstVersion, PLAYER, id("story")).status());
        assertCleared(removedSaved, QuestDefinitionSnapshot.empty(), NOW);
    }

    @Test
    void projectsOnlyTranslationKeysAndResolvesKnownActivityTargets() {
        QuestDefinitionSnapshot definitions = definitions(
                story("mining_story", 1, QuestDefinition.Kind.ACTIVITY,
                        Optional.of(MINING), 5, List.of()));
        RpgDefinitionSnapshot rpg = RpgDefinitionSnapshot.compile(
                List.of(new RpgDefinitionSnapshot.ActivitySource(
                        id("activities/mining.json"), "test", MINING,
                        new ActivityDefinition("activity.rovenfall.mining", List.of(10L)))),
                List.of(), List.of());
        QuestPlayerSavedData saved = new QuestPlayerSavedData();
        assertEquals(ActiveJourneyService.MutationStatus.SUCCESS,
                ActiveJourneyService.selectStory(saved, definitions, PLAYER, id("mining_story")).status());

        ActiveJourneyView view = ActiveJourneyService.view(saved, definitions, rpg, PLAYER, 17, NOW);
        ActiveJourneyView.Entry entry = view.journey().orElseThrow();
        assertEquals(17, view.definitionRevision());
        assertTrue(view.writable());
        assertEquals(ActiveJourneyView.Kind.STORY, entry.kind());
        assertEquals("quest.rovenfall.mining_story", entry.titleTranslationKey());
        assertEquals(ActiveJourneyView.Status.AVAILABLE, entry.status());
        assertEquals(QuestDefinition.Kind.ACTIVITY, entry.objectiveKind());
        assertEquals(Optional.of("activity.rovenfall.mining"), entry.activityTargetTranslationKey());
        assertEquals(0, entry.progress());
        assertEquals(5, entry.requiredCount());
        assertTrue(ActiveJourneyService.view(
                saved, definitions, RpgDefinitionSnapshot.empty(), PLAYER, 17, NOW)
                .journey().orElseThrow().activityTargetTranslationKey().isEmpty());

        QuestDefinitionSnapshot contractDefinitions = definitions(
                contract("weekly", 1, QuestDefinition.Cadence.WEEKLY, 4));
        QuestPlayerState.ContractWindow weekly = RepeatableContractService.windowAt(
                QuestDefinition.Cadence.WEEKLY, NOW);
        QuestPlayerState.ContractKey key = new QuestPlayerState.ContractKey(weekly, id("weekly"));
        QuestPlayerSavedData contractSaved = new QuestPlayerSavedData();
        QuestPlayerState contractState = new QuestPlayerState(
                Map.of(), Map.of(), Map.of(key, new QuestPlayerState.QuestEntry(
                        1, Map.of(id("weekly/objective"), 2L), Optional.empty())), Set.of(weekly));
        assertTrue(contractSaved.commit(PLAYER, QuestPlayerState.EMPTY, contractState));
        assertEquals(ActiveJourneyService.MutationStatus.SUCCESS,
                ActiveJourneyService.selectContract(
                        contractSaved, contractDefinitions, PLAYER, key, NOW).status());
        ActiveJourneyView.Entry contractEntry = ActiveJourneyService.view(
                contractSaved, contractDefinitions, RpgDefinitionSnapshot.empty(), PLAYER, 18, NOW)
                .journey().orElseThrow();
        assertEquals(ActiveJourneyView.Kind.WEEKLY, contractEntry.kind());
        assertEquals(ActiveJourneyView.Status.IN_PROGRESS, contractEntry.status());
        assertEquals(2, contractEntry.progress());
    }

    @Test
    void futureSchemaIsReadOnlyAndFailsClosed() {
        QuestPlayerSavedData root = new QuestPlayerSavedData();
        QuestPlayerState trackedState = new QuestPlayerState(
                Map.of(), Map.of(), Map.of(), Set.of(),
                Optional.of(QuestPlayerState.TrackedJourney.story(id("story"), 1)));
        assertTrue(root.commit(PLAYER, QuestPlayerState.EMPTY, trackedState));
        CompoundTag future = (CompoundTag) QuestPlayerSavedData.CODEC
                .encodeStart(NbtOps.INSTANCE, root).getOrThrow();
        future.putInt("schema_version", QuestPlayerSavedData.CURRENT_SCHEMA_VERSION + 1);
        QuestPlayerSavedData readOnly = QuestPlayerSavedData.CODEC.parse(
                NbtOps.INSTANCE, future).getOrThrow();
        QuestDefinitionSnapshot definitions = definitions(
                story("story", 1, QuestDefinition.Kind.SHOP_TRADE, Optional.empty(), 1, List.of()));

        assertEquals(ActiveJourneyService.MutationStatus.READ_ONLY,
                ActiveJourneyService.selectStory(readOnly, definitions, PLAYER, id("story")).status());
        assertEquals(ActiveJourneyService.MutationStatus.READ_ONLY,
                ActiveJourneyService.reconcile(readOnly, definitions, PLAYER, NOW).status());
        assertTrue(readOnly.state(PLAYER).trackedJourney().isPresent());
        ActiveJourneyView view = ActiveJourneyService.view(
                readOnly, definitions, RpgDefinitionSnapshot.empty(), PLAYER, 0, NOW);
        assertFalse(view.writable());
        assertTrue(view.journey().isEmpty());
    }

    private static void assertCleared(
            QuestPlayerSavedData saved, QuestDefinitionSnapshot definitions, long now) {
        ActiveJourneyService.MutationResult result = ActiveJourneyService.reconcile(
                saved, definitions, PLAYER, now);
        assertEquals(ActiveJourneyService.MutationStatus.SUCCESS, result.status());
        assertTrue(result.cleared());
        assertTrue(saved.state(PLAYER).trackedJourney().isEmpty());
    }

    private static QuestDefinitionSnapshot definitions(QuestDefinitionSnapshot.Source... sources) {
        return QuestDefinitionSnapshot.compile(List.of(sources));
    }

    private static QuestDefinitionSnapshot.Source story(
            String path,
            int version,
            QuestDefinition.Kind kind,
            Optional<Identifier> target,
            int required,
            List<Identifier> prerequisites) {
        return source(path, new QuestDefinition(
                "quest.rovenfall." + path,
                "quest.rovenfall." + path + ".description",
                version,
                prerequisites,
                List.of(new QuestDefinition.Objective(
                        id(path + "/objective"), kind, target, required)),
                QuestDefinition.Rewards.NONE));
    }

    private static QuestDefinitionSnapshot.Source contract(
            String path, int version, QuestDefinition.Cadence cadence, int required) {
        return source(path, new QuestDefinition(
                "quest.rovenfall." + path,
                "quest.rovenfall." + path + ".description",
                version,
                List.of(),
                List.of(new QuestDefinition.Objective(
                        id(path + "/objective"), QuestDefinition.Kind.SHOP_TRADE,
                        Optional.empty(), required)),
                QuestDefinition.Rewards.NONE,
                Optional.of(new QuestDefinition.Contract(cadence))));
    }

    private static QuestDefinitionSnapshot.Source source(String path, QuestDefinition definition) {
        return new QuestDefinitionSnapshot.Source(
                id("rovenfall/quests/" + path + ".json"), "test", id(path), definition);
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("rovenfall", path);
    }
}
