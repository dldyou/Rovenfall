package org.dldyou.rovenfall.quest;

import static org.dldyou.rovenfall.PersistenceTestHarness.roundTrip;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

final class RepeatableContractServiceTest {
    private static final long DAY = RepeatableContractService.DAY_MILLIS;
    private static final long MONDAY_EPOCH_DAY = 20_003L;
    private static final UUID PLAYER = uuid(1);

    @Test
    void rosterIsDeterministicBoundedAndPersistsAcrossRestart() {
        QuestDefinitionSnapshot definitions = definitions(4, 2);
        QuestPlayerSavedData savedData = new QuestPlayerSavedData();
        long timestamp = MONDAY_EPOCH_DAY * DAY + 12_345L;

        var first = RepeatableContractService.ensureAssignments(
                savedData, definitions, PLAYER, timestamp);
        List<QuestPlayerState.ContractKey> assigned = RepeatableContractService.currentKeys(
                savedData.state(PLAYER), timestamp);
        QuestPlayerSavedData restarted = roundTrip(QuestPlayerSavedData.CODEC, savedData);
        var duplicate = RepeatableContractService.ensureAssignments(
                restarted, definitions, PLAYER, timestamp);

        assertEquals(RepeatableContractService.AssignmentStatus.SUCCESS, first.status());
        assertEquals(3, first.assigned());
        assertEquals(2, assigned.stream()
                .filter(key -> key.window().cadence() == QuestDefinition.Cadence.DAILY).count());
        assertEquals(1, assigned.stream()
                .filter(key -> key.window().cadence() == QuestDefinition.Cadence.WEEKLY).count());
        assertEquals(RepeatableContractService.AssignmentStatus.UNCHANGED, duplicate.status());
        assertEquals(assigned, RepeatableContractService.currentKeys(restarted.state(PLAYER), timestamp));
    }

    @Test
    void assignmentPreservesTheTrackedJourney() {
        QuestPlayerSavedData savedData = new QuestPlayerSavedData();
        QuestPlayerState.TrackedJourney tracked = QuestPlayerState.TrackedJourney.story(id("story"), 1);
        QuestPlayerState initial = new QuestPlayerState(
                Map.of(), Map.of(), Map.of(), Set.of(), Optional.of(tracked));
        assertTrue(savedData.commit(PLAYER, QuestPlayerState.EMPTY, initial));

        assertEquals(RepeatableContractService.AssignmentStatus.SUCCESS,
                RepeatableContractService.ensureAssignments(
                        savedData, definitions(1, 1), PLAYER, MONDAY_EPOCH_DAY * DAY).status());
        assertEquals(Optional.of(tracked), savedData.state(PLAYER).trackedJourney());
    }

    @Test
    void assignmentVariesByPlayerWithoutLosingDeterminism() {
        QuestDefinitionSnapshot definitions = definitions(8, 4);
        long timestamp = MONDAY_EPOCH_DAY * DAY;
        Set<List<Identifier>> rosters = new HashSet<>();

        for (int index = 1; index <= 12; index++) {
            QuestPlayerSavedData first = new QuestPlayerSavedData();
            QuestPlayerSavedData second = new QuestPlayerSavedData();
            UUID player = uuid(index);
            RepeatableContractService.ensureAssignments(first, definitions, player, timestamp);
            RepeatableContractService.ensureAssignments(second, definitions, player, timestamp);
            List<Identifier> firstRoster = RepeatableContractService.currentKeys(first.state(player), timestamp)
                    .stream().map(QuestPlayerState.ContractKey::templateId).toList();
            List<Identifier> secondRoster = RepeatableContractService.currentKeys(second.state(player), timestamp)
                    .stream().map(QuestPlayerState.ContractKey::templateId).toList();
            assertEquals(firstRoster, secondRoster);
            rosters.add(firstRoster);
        }

        assertTrue(rosters.size() > 1);
    }

    @Test
    void persistedSparseRosterDoesNotFillAfterDatapackReload() {
        long timestamp = MONDAY_EPOCH_DAY * DAY;
        QuestPlayerSavedData savedData = new QuestPlayerSavedData();

        assertEquals(1, RepeatableContractService.ensureAssignments(
                savedData, definitions(1, 0), PLAYER, timestamp).assigned());
        assertEquals(RepeatableContractService.AssignmentStatus.UNCHANGED,
                RepeatableContractService.ensureAssignments(
                        savedData, definitions(4, 2), PLAYER, timestamp).status());

        assertEquals(1, RepeatableContractService.currentKeys(savedData.state(PLAYER), timestamp).size());
        assertEquals(2, savedData.state(PLAYER).initializedContractWindows().size());
    }

    @Test
    void emptyCatalogStillPersistsTheWindowsSoReloadCannotBackfillThem() {
        long timestamp = MONDAY_EPOCH_DAY * DAY;
        QuestPlayerSavedData savedData = new QuestPlayerSavedData();

        var empty = RepeatableContractService.ensureAssignments(
                savedData, QuestDefinitionSnapshot.empty(), PLAYER, timestamp);
        var reloaded = RepeatableContractService.ensureAssignments(
                savedData, definitions(4, 2), PLAYER, timestamp);

        assertEquals(RepeatableContractService.AssignmentStatus.SUCCESS, empty.status());
        assertEquals(0, empty.assigned());
        assertEquals(2, savedData.state(PLAYER).initializedContractWindows().size());
        assertEquals(RepeatableContractService.AssignmentStatus.UNCHANGED, reloaded.status());
        assertTrue(savedData.state(PLAYER).contracts().isEmpty());
    }

    @Test
    void utcMidnightAndMondayRollBothRostersExactlyAtTheBoundary() {
        QuestDefinitionSnapshot definitions = definitions(4, 2);
        QuestPlayerSavedData savedData = new QuestPlayerSavedData();
        long beforeMonday = MONDAY_EPOCH_DAY * DAY - 1L;
        long monday = MONDAY_EPOCH_DAY * DAY;

        RepeatableContractService.ensureAssignments(savedData, definitions, PLAYER, beforeMonday);
        List<QuestPlayerState.ContractKey> before = RepeatableContractService.currentKeys(
                savedData.state(PLAYER), beforeMonday);
        RepeatableContractService.ensureAssignments(savedData, definitions, PLAYER, monday);
        List<QuestPlayerState.ContractKey> after = RepeatableContractService.currentKeys(
                savedData.state(PLAYER), monday);

        assertEquals(3, before.size());
        assertEquals(3, after.size());
        assertFalse(before.stream().map(QuestPlayerState.ContractKey::window)
                .anyMatch(after.stream().map(QuestPlayerState.ContractKey::window).collect(
                        java.util.stream.Collectors.toSet())::contains));
        assertEquals(4, savedData.state(PLAYER).initializedContractWindows().size());
    }

    @Test
    void weeklyWindowCoversTheUnixEpochBoundary() {
        QuestPlayerState.ContractWindow window = RepeatableContractService.windowAt(
                QuestDefinition.Cadence.WEEKLY, 0L);

        assertEquals(-3L, window.windowStartEpochDay());
        assertTrue(window.isValid());
        assertTrue(RepeatableContractService.contains(window, 0L));
        assertFalse(RepeatableContractService.contains(window, 4L * DAY));
    }

    @Test
    void farFutureAssignmentIsRejectedWithoutCreatingAWindow() {
        QuestPlayerSavedData savedData = new QuestPlayerSavedData();

        var result = RepeatableContractService.ensureAssignments(
                savedData, definitions(4, 2), PLAYER, Long.MAX_VALUE);

        assertEquals(RepeatableContractService.AssignmentStatus.INVALID, result.status());
        assertEquals(QuestPlayerState.EMPTY, savedData.state(PLAYER));
    }

    @Test
    void retentionPrunesExpiredProgressButPreservesPendingRewards() {
        QuestDefinitionSnapshot definitions = definitions(4, 2);
        QuestPlayerSavedData savedData = new QuestPlayerSavedData();
        QuestPlayerState.ContractWindow oldWindow = new QuestPlayerState.ContractWindow(
                QuestDefinition.Cadence.DAILY,
                MONDAY_EPOCH_DAY - RepeatableContractService.DAILY_WINDOWS_RETAINED - 5L);
        QuestPlayerState.ContractKey expired = new QuestPlayerState.ContractKey(oldWindow, id("daily_0"));
        QuestPlayerState.ContractKey pending = new QuestPlayerState.ContractKey(oldWindow, id("daily_1"));
        QuestPlayerState.RewardOperation operation = new QuestPlayerState.RewardOperation(
                1, uuid(90), 1, Optional.empty(), 0, 1_000,
                QuestPlayerState.RewardOperation.Phase.CAPTURED);
        QuestPlayerState oldState = new QuestPlayerState(
                Map.of(), Map.of(),
                Map.of(
                        expired, new QuestPlayerState.QuestEntry(1, Map.of(), Optional.empty(), Optional.empty()),
                        pending, new QuestPlayerState.QuestEntry(
                                1, Map.of(), Optional.of(operation), Optional.empty())),
                Set.of(oldWindow));
        assertTrue(savedData.commit(PLAYER, QuestPlayerState.EMPTY, oldState));

        var result = RepeatableContractService.ensureAssignments(
                savedData, definitions, PLAYER, MONDAY_EPOCH_DAY * DAY);

        assertEquals(RepeatableContractService.AssignmentStatus.SUCCESS, result.status());
        assertEquals(1, result.removed());
        assertFalse(savedData.state(PLAYER).contracts().containsKey(expired));
        assertTrue(savedData.state(PLAYER).contracts().containsKey(pending));
        assertTrue(savedData.state(PLAYER).initializedContractWindows().contains(oldWindow));
    }

    @Test
    void fullPendingHistoryRejectsNewRosterWithoutMutatingState() {
        QuestPlayerSavedData savedData = new QuestPlayerSavedData();
        Map<QuestPlayerState.ContractKey, QuestPlayerState.QuestEntry> contracts = new java.util.TreeMap<>();
        Set<QuestPlayerState.ContractWindow> windows = new HashSet<>();
        for (int day = 1; day <= QuestPlayerState.MAX_CONTRACTS / 2; day++) {
            QuestPlayerState.ContractWindow window = new QuestPlayerState.ContractWindow(
                    QuestDefinition.Cadence.DAILY, day);
            windows.add(window);
            for (int slot = 0; slot < 2; slot++) {
                long transaction = 10_000L + day * 2L + slot;
                QuestPlayerState.RewardOperation operation = new QuestPlayerState.RewardOperation(
                        1, uuid(transaction), 1, Optional.empty(), 0, 1_000,
                        QuestPlayerState.RewardOperation.Phase.CAPTURED);
                contracts.put(new QuestPlayerState.ContractKey(window, id("pending_" + slot)),
                        new QuestPlayerState.QuestEntry(
                                1, Map.of(), Optional.of(operation), Optional.empty()));
            }
        }
        QuestPlayerState full = new QuestPlayerState(Map.of(), Map.of(), contracts, windows);
        assertTrue(full.isValid());
        assertTrue(savedData.commit(PLAYER, QuestPlayerState.EMPTY, full));

        var result = RepeatableContractService.ensureAssignments(
                savedData, definitions(4, 2), PLAYER, MONDAY_EPOCH_DAY * DAY);

        assertEquals(RepeatableContractService.AssignmentStatus.STATE_FULL, result.status());
        assertEquals(full, savedData.state(PLAYER));
    }

    private static QuestDefinitionSnapshot definitions(int daily, int weekly) {
        List<QuestDefinitionSnapshot.Source> sources = new ArrayList<>();
        addDefinitions(sources, QuestDefinition.Cadence.DAILY, daily);
        addDefinitions(sources, QuestDefinition.Cadence.WEEKLY, weekly);
        return QuestDefinitionSnapshot.compile(sources);
    }

    private static void addDefinitions(
            List<QuestDefinitionSnapshot.Source> sources,
            QuestDefinition.Cadence cadence,
            int count) {
        String prefix = cadence.getSerializedName();
        for (int index = 0; index < count; index++) {
            Identifier template = id(prefix + "_" + index);
            QuestDefinition definition = new QuestDefinition(
                    "quest.rovenfall.contract." + prefix + "_" + index,
                    "quest.rovenfall.contract." + prefix + "_" + index + ".description",
                    1,
                    List.of(),
                    List.of(new QuestDefinition.Objective(
                            id(prefix + "_objective_" + index), QuestDefinition.Kind.ACTIVITY,
                            Optional.of(id("mining")), 10)),
                    QuestDefinition.Rewards.NONE,
                    Optional.of(new QuestDefinition.Contract(cadence)));
            sources.add(new QuestDefinitionSnapshot.Source(
                    id("rovenfall/quests/contracts/" + prefix + "_" + index + ".json"),
                    "test", template, definition));
        }
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("rovenfall", path);
    }

    private static UUID uuid(long value) {
        return new UUID(0L, value);
    }
}
