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

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("rovenfall", path);
    }

    private static UUID uuid(long value) {
        return new UUID(0L, value);
    }
}
