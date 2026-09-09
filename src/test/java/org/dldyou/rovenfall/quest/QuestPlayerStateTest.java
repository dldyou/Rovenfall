package org.dldyou.rovenfall.quest;

import static org.dldyou.rovenfall.PersistenceTestHarness.roundTrip;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

final class QuestPlayerStateTest {
    private static final Identifier FIRST_STEPS = id("first_steps");
    private static final Identifier HIDDEN = id("removed_quest");
    private static final Identifier GATHER_WOOD = id("gather_wood");
    private static final Identifier CLAIM_LAND = id("claim_land");

    @Test
    void stateCodecRoundTripsCompletionEvidenceInStableOrder() {
        QuestPlayerState state = state(Map.of(
                HIDDEN, entry(2, Map.of(CLAIM_LAND, 1L), 12),
                FIRST_STEPS, entry(1, Map.of(CLAIM_LAND, 0L, GATHER_WOOD, 8L), 11)));

        var encoded = QuestPlayerState.CODEC.encodeStart(NbtOps.INSTANCE, state).getOrThrow();

        assertEquals(encoded, QuestPlayerState.CODEC.encodeStart(
                NbtOps.INSTANCE, roundTrip(QuestPlayerState.CODEC, state)).getOrThrow());
        assertEquals(state, roundTrip(QuestPlayerState.CODEC, state));
    }

    @Test
    void completionReceiptRetainsCapturedRewardIntentAcrossRestart() {
        UUID transactionId = uuid(12);
        QuestPlayerState.RewardOperation operation = new QuestPlayerState.RewardOperation(
                1, transactionId, 100, Optional.of(id("mining")), 25, 1_000,
                QuestPlayerState.RewardOperation.Phase.CAPTURED);
        QuestPlayerState.CompletionReceipt receipt = new QuestPlayerState.CompletionReceipt(
                1, transactionId, 2_000, operation);
        QuestPlayerState state = state(Map.of(FIRST_STEPS,
                new QuestPlayerState.QuestEntry(1, Map.of(GATHER_WOOD, 8L), Optional.of(receipt))));

        assertEquals(state, roundTrip(QuestPlayerState.CODEC, state));
        assertEquals(Optional.of(operation), receipt.rewardOperation());
    }

    @Test
    void contractAssignmentsRoundTripInStableOrderIncludingPreEpochWeeklyWindow() {
        var daily = new QuestPlayerState.ContractWindow(QuestDefinition.Cadence.DAILY, 20_000);
        var firstUtcWeek = new QuestPlayerState.ContractWindow(QuestDefinition.Cadence.WEEKLY, -3);
        var dailyKey = new QuestPlayerState.ContractKey(daily, id("daily_trade"));
        var weeklyKey = new QuestPlayerState.ContractKey(firstUtcWeek, id("weekly_boss"));
        QuestPlayerState state = new QuestPlayerState(
                Map.of(), Map.of(),
                Map.of(weeklyKey, openEntry(), dailyKey, openEntry()),
                Set.of(firstUtcWeek, daily));

        var encoded = QuestPlayerState.CODEC.encodeStart(NbtOps.INSTANCE, state).getOrThrow();
        QuestPlayerState restarted = roundTrip(QuestPlayerState.CODEC, state);

        assertTrue(firstUtcWeek.isValid());
        assertEquals(state, restarted);
        assertEquals(encoded, QuestPlayerState.CODEC.encodeStart(NbtOps.INSTANCE, restarted).getOrThrow());
        assertEquals(List.of(dailyKey, weeklyKey), restarted.contracts().keySet().stream().toList());
    }

    @Test
    void trackedJourneyCodecRequiresExactlyOneBoundedReferenceAndRoundTrips() {
        var daily = new QuestPlayerState.ContractWindow(QuestDefinition.Cadence.DAILY, 20_000);
        var contract = new QuestPlayerState.ContractKey(daily, id("daily_trade"));
        var story = QuestPlayerState.TrackedJourney.story(FIRST_STEPS, 3);
        var contractJourney = QuestPlayerState.TrackedJourney.contract(contract, 4);
        QuestPlayerState state = new QuestPlayerState(
                Map.of(), Map.of(), Map.of(contract, openEntry()), Set.of(daily), Optional.of(contractJourney));

        assertEquals(state, roundTrip(QuestPlayerState.CODEC, state));
        assertEquals(story, roundTrip(QuestPlayerState.TrackedJourney.CODEC, story));
        var ambiguous = new QuestPlayerState.TrackedJourney(
                1, Optional.of(FIRST_STEPS), Optional.of(contract));
        assertFalse(ambiguous.isValid());
        assertTrue(QuestPlayerState.TrackedJourney.CODEC.encodeStart(NbtOps.INSTANCE, ambiguous)
                .error().isPresent());
        assertFalse(new QuestPlayerState.TrackedJourney(
                1, Optional.empty(), Optional.empty()).isValid());
        assertTrue(QuestPlayerState.TrackedJourney.CODEC.encodeStart(
                NbtOps.INSTANCE,
                new QuestPlayerState.TrackedJourney(0, Optional.of(FIRST_STEPS), Optional.empty()))
                .error().isPresent());
    }

    @Test
    void contractStateRejectsMissingMarkersSlotOverflowMisalignedWeeksAndCrossDomainTransactions() {
        var daily = new QuestPlayerState.ContractWindow(QuestDefinition.Cadence.DAILY, 20_000);
        var invalidWeek = new QuestPlayerState.ContractWindow(QuestDefinition.Cadence.WEEKLY, 0);
        var first = new QuestPlayerState.ContractKey(daily, id("daily_one"));
        var second = new QuestPlayerState.ContractKey(daily, id("daily_two"));
        var third = new QuestPlayerState.ContractKey(daily, id("daily_three"));

        assertFalse(new QuestPlayerState(Map.of(), Map.of(), Map.of(first, openEntry()), Set.of()).isValid());
        assertFalse(new QuestPlayerState(
                Map.of(), Map.of(),
                Map.of(first, openEntry(), second, openEntry(), third, openEntry()), Set.of(daily)).isValid());
        assertFalse(invalidWeek.isValid());
        assertTrue(QuestPlayerState.ContractWindow.CODEC.encodeStart(NbtOps.INSTANCE, invalidWeek)
                .error().isPresent());

        QuestPlayerState duplicateTransaction = new QuestPlayerState(
                Map.of(FIRST_STEPS, entry(1, Map.of(), 77)),
                Map.of(),
                Map.of(first, entry(1, Map.of(), 77)),
                Set.of(daily));
        assertFalse(duplicateTransaction.isValid());
        assertTrue(QuestPlayerState.CODEC.encodeStart(NbtOps.INSTANCE, duplicateTransaction)
                .error().isPresent());
    }

    @Test
    void contractCodecsRejectDuplicateKeysAndWindowMarkers() {
        var daily = new QuestPlayerState.ContractWindow(QuestDefinition.Cadence.DAILY, 20_000);
        var key = new QuestPlayerState.ContractKey(daily, id("daily_trade"));
        QuestPlayerState state = new QuestPlayerState(
                Map.of(), Map.of(), Map.of(key, openEntry()), Set.of(daily));
        CompoundTag duplicateContract = (CompoundTag) QuestPlayerState.CODEC
                .encodeStart(NbtOps.INSTANCE, state).getOrThrow();
        duplicateContract.getListOrEmpty("contracts")
                .add(duplicateContract.getListOrEmpty("contracts").getFirst().copy());
        assertTrue(QuestPlayerState.CODEC.parse(NbtOps.INSTANCE, duplicateContract).error().isPresent());

        CompoundTag duplicateWindow = (CompoundTag) QuestPlayerState.CODEC
                .encodeStart(NbtOps.INSTANCE, state).getOrThrow();
        duplicateWindow.getListOrEmpty("initialized_contract_windows")
                .add(duplicateWindow.getListOrEmpty("initialized_contract_windows").getFirst().copy());
        assertTrue(QuestPlayerState.CODEC.parse(NbtOps.INSTANCE, duplicateWindow).error().isPresent());
    }

    @Test
    void contractStateEnforcesAssignmentAndInitializedWindowBounds() {
        Map<QuestPlayerState.ContractKey, QuestPlayerState.QuestEntry> contracts = new java.util.LinkedHashMap<>();
        Set<QuestPlayerState.ContractWindow> windows = new java.util.LinkedHashSet<>();
        for (int index = 0; contracts.size() <= QuestPlayerState.MAX_CONTRACTS; index++) {
            var window = new QuestPlayerState.ContractWindow(QuestDefinition.Cadence.DAILY, index);
            windows.add(window);
            contracts.put(new QuestPlayerState.ContractKey(window, id("contract_" + index + "_a")), openEntry());
            if (contracts.size() <= QuestPlayerState.MAX_CONTRACTS) {
                contracts.put(new QuestPlayerState.ContractKey(window, id("contract_" + index + "_b")),
                        openEntry());
            }
        }
        assertEquals(QuestPlayerState.MAX_CONTRACTS + 1, contracts.size());
        assertFalse(new QuestPlayerState(Map.of(), Map.of(), contracts, windows).isValid());

        Set<QuestPlayerState.ContractWindow> tooManyWindows = java.util.stream.LongStream
                .rangeClosed(0, QuestPlayerState.MAX_INITIALIZED_CONTRACT_WINDOWS)
                .mapToObj(day -> new QuestPlayerState.ContractWindow(QuestDefinition.Cadence.DAILY, day))
                .collect(java.util.stream.Collectors.toSet());
        assertFalse(new QuestPlayerState(Map.of(), Map.of(), Map.of(), tooManyWindows).isValid());
    }

    @Test
    void retainsUnknownQuestEvidenceWithoutPruning() {
        QuestPlayerState state = state(Map.of(
                HIDDEN, entry(2, Map.of(), 11),
                FIRST_STEPS, entry(1, Map.of(GATHER_WOOD, 3L), 12)));
        QuestDefinitionSnapshot definitions = QuestDefinitionSnapshot.compile(List.of(new QuestDefinitionSnapshot.Source(
                Identifier.fromNamespaceAndPath("rovenfall", "rovenfall/quests/first_steps.json"),
                "test",
                FIRST_STEPS,
                new QuestDefinition(
                        "quest.rovenfall.first_steps",
                        "quest.rovenfall.first_steps.description",
                        2,
                        List.of(),
                        List.of(new QuestDefinition.Objective(
                                GATHER_WOOD, QuestDefinition.Kind.ACTIVITY,
                                Optional.of(id("mining")), 3))))));

        assertEquals(Set.of(HIDDEN), state.unresolvedQuestIds(definitions));
        assertEquals(Set.of(FIRST_STEPS), state.definitionChangedQuestIds(definitions));
        assertEquals(state, roundTrip(QuestPlayerState.CODEC, state));
    }

    @Test
    void rootMigratesSchemaZeroAndFutureSchemaIsReadOnly() {
        QuestPlayerSavedData root = new QuestPlayerSavedData();
        UUID player = uuid(1);
        QuestPlayerState state = state(Map.of(FIRST_STEPS, entry(1, Map.of(GATHER_WOOD, 3L), 2)));
        assertTrue(root.commit(player, QuestPlayerState.EMPTY, state));

        CompoundTag schemaZero = (CompoundTag) QuestPlayerSavedData.CODEC
                .encodeStart(NbtOps.INSTANCE, root).getOrThrow();
        schemaZero.putInt("schema_version", 0);
        QuestPlayerSavedData migrated = QuestPlayerSavedData.CODEC.parse(NbtOps.INSTANCE, schemaZero).getOrThrow();
        assertEquals(QuestPlayerSavedData.CURRENT_SCHEMA_VERSION, migrated.schemaVersion());
        assertTrue(migrated.isWritable());

        CompoundTag schemaThree = (CompoundTag) QuestPlayerSavedData.CODEC
                .encodeStart(NbtOps.INSTANCE, root).getOrThrow();
        schemaThree.putInt("schema_version", 3);
        QuestPlayerSavedData migratedFromThree = QuestPlayerSavedData.CODEC
                .parse(NbtOps.INSTANCE, schemaThree).getOrThrow();
        assertEquals(QuestPlayerSavedData.CURRENT_SCHEMA_VERSION, migratedFromThree.schemaVersion());
        assertTrue(migratedFromThree.isWritable());
        assertEquals(state, migrated.state(player));

        CompoundTag future = (CompoundTag) QuestPlayerSavedData.CODEC.encodeStart(NbtOps.INSTANCE, root).getOrThrow();
        future.putInt("schema_version", QuestPlayerSavedData.CURRENT_SCHEMA_VERSION + 1);
        QuestPlayerSavedData readOnly = QuestPlayerSavedData.CODEC.parse(NbtOps.INSTANCE, future).getOrThrow();
        assertFalse(readOnly.isWritable());
        assertFalse(readOnly.commit(player, state, QuestPlayerState.EMPTY));
        assertEquals(state, readOnly.state(player));
    }

    @Test
    void schemaFourMigrationDefaultsContractStateAndMaintenancePreservesCurrentContracts() {
        UUID player = uuid(30);
        UUID evidenceId = uuid(31);
        var daily = new QuestPlayerState.ContractWindow(QuestDefinition.Cadence.DAILY, 20_000);
        var key = new QuestPlayerState.ContractKey(daily, id("daily_trade"));
        QuestPlayerState state = new QuestPlayerState(
                Map.of(),
                Map.of(evidenceId, new QuestPlayerState.ProcessedEvidence(1, QuestDefinition.Kind.ACTIVITY)),
                Map.of(key, openEntry()), Set.of(daily),
                Optional.of(QuestPlayerState.TrackedJourney.contract(key, 1)));
        QuestPlayerSavedData root = new QuestPlayerSavedData();
        assertTrue(root.commit(player, QuestPlayerState.EMPTY, state));

        assertEquals(1, root.maintainProcessedEvidence(
                player, Map.of(evidenceId, false),
                QuestPlayerSavedData.PROCESSED_EVIDENCE_OWNER_RETENTION_MILLIS + 2, 1));
        assertEquals(state.contracts(), root.state(player).contracts());
        assertEquals(state.initializedContractWindows(), root.state(player).initializedContractWindows());
        assertEquals(state.trackedJourney(), root.state(player).trackedJourney());

        CompoundTag schemaFour = (CompoundTag) QuestPlayerSavedData.CODEC
                .encodeStart(NbtOps.INSTANCE, root).getOrThrow();
        schemaFour.putInt("schema_version", 4);
        CompoundTag oldState = schemaFour.getListOrEmpty("players").getCompoundOrEmpty(0)
                .getCompoundOrEmpty("state");
        oldState.remove("contracts");
        oldState.remove("initialized_contract_windows");
        oldState.remove("tracked_journey");
        QuestPlayerSavedData migrated = QuestPlayerSavedData.CODEC.parse(NbtOps.INSTANCE, schemaFour).getOrThrow();

        assertEquals(QuestPlayerSavedData.CURRENT_SCHEMA_VERSION, migrated.schemaVersion());
        assertTrue(migrated.state(player).contracts().isEmpty());
        assertTrue(migrated.state(player).initializedContractWindows().isEmpty());
        assertTrue(migrated.state(player).trackedJourney().isEmpty());
    }

    @Test
    void schemaFiveMigrationDefaultsTheTrackedJourney() {
        UUID player = uuid(40);
        QuestPlayerSavedData root = new QuestPlayerSavedData();
        QuestPlayerState tracked = new QuestPlayerState(
                Map.of(), Map.of(), Map.of(), Set.of(),
                Optional.of(QuestPlayerState.TrackedJourney.story(FIRST_STEPS, 1)));
        assertTrue(root.commit(player, QuestPlayerState.EMPTY, tracked));

        CompoundTag schemaFive = (CompoundTag) QuestPlayerSavedData.CODEC
                .encodeStart(NbtOps.INSTANCE, root).getOrThrow();
        schemaFive.putInt("schema_version", 5);
        schemaFive.getListOrEmpty("players").getCompoundOrEmpty(0)
                .getCompoundOrEmpty("state").remove("tracked_journey");

        QuestPlayerSavedData migrated = QuestPlayerSavedData.CODEC.parse(
                NbtOps.INSTANCE, schemaFive).getOrThrow();
        assertEquals(QuestPlayerSavedData.CURRENT_SCHEMA_VERSION, migrated.schemaVersion());
        assertTrue(migrated.isWritable());
        assertTrue(migrated.state(player).trackedJourney().isEmpty());
    }

    @Test
    void codecsRejectDuplicateQuestObjectivePlayerAndCompletionTransactionEntries() {
        QuestPlayerState state = state(Map.of(FIRST_STEPS, entry(1, Map.of(GATHER_WOOD, 3L), 8)));
        CompoundTag duplicateQuest = (CompoundTag) QuestPlayerState.CODEC.encodeStart(NbtOps.INSTANCE, state).getOrThrow();
        duplicateQuest.getListOrEmpty("quests").add(duplicateQuest.getListOrEmpty("quests").getFirst().copy());
        assertTrue(QuestPlayerState.CODEC.parse(NbtOps.INSTANCE, duplicateQuest).error().isPresent());

        CompoundTag duplicateObjective = (CompoundTag) QuestPlayerState.CODEC.encodeStart(NbtOps.INSTANCE, state).getOrThrow();
        CompoundTag entryValue = duplicateObjective.getListOrEmpty("quests").getCompoundOrEmpty(0)
                .getCompoundOrEmpty("value");
        entryValue.getListOrEmpty("objective_progress")
                .add(entryValue.getListOrEmpty("objective_progress").getFirst().copy());
        assertTrue(QuestPlayerState.CODEC.parse(NbtOps.INSTANCE, duplicateObjective).error().isPresent());

        QuestPlayerState duplicateTransaction = state(Map.of(
                FIRST_STEPS, entry(1, Map.of(), 9),
                HIDDEN, entry(2, Map.of(), 9)));
        assertFalse(duplicateTransaction.isValid());
        assertTrue(QuestPlayerState.CODEC.encodeStart(NbtOps.INSTANCE, duplicateTransaction).error().isPresent());

        QuestPlayerSavedData root = new QuestPlayerSavedData();
        assertTrue(root.commit(uuid(2), QuestPlayerState.EMPTY, state));
        CompoundTag duplicatePlayer = (CompoundTag) QuestPlayerSavedData.CODEC
                .encodeStart(NbtOps.INSTANCE, root).getOrThrow();
        duplicatePlayer.getListOrEmpty("players").add(duplicatePlayer.getListOrEmpty("players").getFirst().copy());
        assertTrue(QuestPlayerSavedData.CODEC.parse(NbtOps.INSTANCE, duplicatePlayer).error().isPresent());
    }

    @Test
    void rejectsOutOfRangeProgressAndCompletionEvidence() {
        var tooMuchProgress = new QuestPlayerState.QuestEntry(
                1,
                Map.of(GATHER_WOOD, QuestPlayerState.MAX_OBJECTIVE_PROGRESS + 1),
                Optional.empty());
        var mismatchedReceipt = new QuestPlayerState.QuestEntry(
                1,
                Map.of(),
                Optional.of(new QuestPlayerState.CompletionReceipt(2, uuid(20), 2_000)));
        Map<Identifier, Long> tooManyObjectives = new java.util.LinkedHashMap<>();
        for (int index = 0; index <= QuestPlayerState.MAX_OBJECTIVES_PER_QUEST; index++) {
            tooManyObjectives.put(id("objective_" + index), 0L);
        }
        var oversized = new QuestPlayerState.QuestEntry(1, tooManyObjectives, Optional.empty());

        for (var invalid : List.of(tooMuchProgress, mismatchedReceipt, oversized)) {
            QuestPlayerState state = state(Map.of(FIRST_STEPS, invalid));
            assertFalse(state.isValid());
            assertTrue(QuestPlayerState.CODEC.encodeStart(NbtOps.INSTANCE, state).error().isPresent());
        }
    }

    @Test
    void expectedStateCommitRejectsStaleOrInvalidUpdatesWithoutMutation() {
        QuestPlayerSavedData root = new QuestPlayerSavedData();
        UUID player = uuid(4);
        QuestPlayerState first = state(Map.of(FIRST_STEPS, entry(1, Map.of(GATHER_WOOD, 1L), 4)));
        QuestPlayerState second = state(Map.of(FIRST_STEPS, entry(1, Map.of(GATHER_WOOD, 2L), 5)));
        QuestPlayerState invalid = state(Map.of(FIRST_STEPS,
                new QuestPlayerState.QuestEntry(1, Map.of(GATHER_WOOD, -1L), java.util.Optional.empty())));

        assertTrue(root.commit(player, QuestPlayerState.EMPTY, first));
        assertFalse(root.commit(player, QuestPlayerState.EMPTY, second));
        assertFalse(root.commit(player, first, invalid));
        assertFalse(root.commit(new UUID(0L, 0L), first, second));
        assertEquals(first, root.state(player));
        assertEquals(first, root.snapshot().player(player).orElseThrow());
    }

    private static QuestPlayerState state(Map<Identifier, QuestPlayerState.QuestEntry> quests) {
        return new QuestPlayerState(quests);
    }

    private static QuestPlayerState.QuestEntry entry(
            int definitionVersion, Map<Identifier, Long> objectiveProgress, long transaction) {
        return new QuestPlayerState.QuestEntry(
                definitionVersion,
                objectiveProgress,
                java.util.Optional.of(new QuestPlayerState.CompletionReceipt(
                        definitionVersion, uuid(transaction), transaction * 100L)));
    }

    private static QuestPlayerState.QuestEntry openEntry() {
        return new QuestPlayerState.QuestEntry(1, Map.of(), Optional.empty());
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("rovenfall", path);
    }

    private static UUID uuid(long value) {
        return new UUID(0L, value);
    }
}
