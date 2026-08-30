package org.dldyou.rovenfall.mobs;

import static org.dldyou.rovenfall.PersistenceTestHarness.roundTrip;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import org.dldyou.rovenfall.world.WorldTopology;
import org.junit.jupiter.api.Test;

final class BossRewardSavedDataTest {
    private static final UUID TRANSACTION = id(1);
    private static final UUID ENCOUNTER = id(2);
    private static final UUID PLAYER = id(3);

    @Test
    void qualificationIsOverflowSafeAndRequiresBothThresholds() {
        assertTrue(BossRewardOperation.qualifies(Long.MAX_VALUE, Long.MAX_VALUE, 1, 10_000));
        assertTrue(BossRewardOperation.qualifies(25, 100, 10, 2_500));
        assertFalse(BossRewardOperation.qualifies(24, 100, 10, 2_500));
        assertFalse(BossRewardOperation.qualifies(9, 10, 10, 1));
    }

    @Test
    void durableBatchIsAtomicIdempotentAndCooldownAwareAcrossRestart() {
        BossRewardSavedData state = new BossRewardSavedData();
        BossRewardOperation pending = operation(BossRewardOperation.Phase.PENDING, 2_000);

        assertEquals(BossRewardSavedData.BatchStatus.SUCCESS,
                state.putBatch(Map.of(TRANSACTION, pending), 1_000));
        assertTrue(state.update(TRANSACTION, pending,
                pending.atPhase(BossRewardOperation.Phase.CORE_APPLIED)));
        assertEquals(BossRewardSavedData.BatchStatus.DUPLICATE,
                state.putBatch(Map.of(TRANSACTION, pending), 1_001));
        assertEquals(2_000, state.cooldownUntil(pending.bossId(), PLAYER, null, 1_500));

        BossRewardSavedData loaded = roundTrip(BossRewardSavedData.CODEC, state);
        assertEquals(BossRewardOperation.Phase.CORE_APPLIED,
                loaded.operation(TRANSACTION).orElseThrow().phase());
        assertEquals(1, loaded.pendingOperations().size());
        assertEquals(2_000, loaded.cooldownUntil(pending.bossId(), PLAYER, null, 1_500));
    }

    @Test
    void conflictingBatchAndInvalidReplacementChangeNothing() {
        BossRewardSavedData state = new BossRewardSavedData();
        BossRewardOperation retained = operation(BossRewardOperation.Phase.PENDING, 2_000);
        assertEquals(BossRewardSavedData.BatchStatus.SUCCESS,
                state.putBatch(Map.of(TRANSACTION, retained), 1_000));

        BossRewardOperation conflict = new BossRewardOperation(
                retained.encounterId(), retained.bossId(), retained.definitionFingerprint(), id(99),
                retained.dimension(), retained.center(), retained.playerPoints(), retained.totalPoints(),
                retained.minimumPoints(), retained.minimumShareBasisPoints(), retained.currency(),
                retained.experience(), retained.cooldownUntilEpochMillis(), retained.createdAtEpochMillis(),
                retained.items(), retained.phase());
        assertEquals(BossRewardSavedData.BatchStatus.CONFLICT,
                state.putBatch(Map.of(TRANSACTION, conflict, id(4), operation(
                        BossRewardOperation.Phase.PENDING, 3_000)), 1_100));
        assertEquals(1, state.operationCount());
        assertTrue(state.operation(id(4)).isEmpty());
        assertFalse(state.update(TRANSACTION, retained, conflict));
        assertEquals(PLAYER, state.operation(TRANSACTION).orElseThrow().playerId());
        assertFalse(state.update(TRANSACTION, retained,
                retained.atPhase(BossRewardOperation.Phase.COMPLETED)));
        assertEquals(BossRewardSavedData.BatchStatus.INVALID,
                state.putBatch(Map.of(id(6), operation(BossRewardOperation.Phase.COMPLETED, 3_000)), 1_200));
    }

    @Test
    void futureSchemaRetainsEvidenceReadOnly() {
        BossRewardSavedData state = new BossRewardSavedData();
        assertEquals(BossRewardSavedData.BatchStatus.SUCCESS,
                state.putBatch(Map.of(TRANSACTION, operation(BossRewardOperation.Phase.PENDING, 2_000)), 1_000));
        CompoundTag encoded = (CompoundTag) BossRewardSavedData.CODEC
                .encodeStart(NbtOps.INSTANCE, state).getOrThrow();
        encoded.putInt("schema_version", BossRewardSavedData.CURRENT_SCHEMA_VERSION + 1);

        BossRewardSavedData loaded = BossRewardSavedData.CODEC.parse(NbtOps.INSTANCE, encoded).getOrThrow();

        assertFalse(loaded.isWritable());
        assertEquals(1, loaded.operationCount());
        assertEquals(BossRewardSavedData.BatchStatus.INVALID,
                loaded.putBatch(Map.of(id(5), operation(BossRewardOperation.Phase.PENDING, 3_000)), 2_000));
    }

    @Test
    void operationCursorBatchesAreBoundedAndDeterministic() {
        BossRewardSavedData state = new BossRewardSavedData();
        Map<UUID, BossRewardOperation> operations = new java.util.LinkedHashMap<>();
        for (int value = 9; value >= 1; value--) {
            operations.put(id(value), operation(BossRewardOperation.Phase.PENDING, 2_000 + value));
        }
        assertEquals(BossRewardSavedData.BatchStatus.SUCCESS, state.putBatch(operations, 1_000));

        var first = state.operationsAfter(null, 4);
        var second = state.operationsAfter(first.nextCursor().orElseThrow(), 4);
        var last = state.operationsAfter(second.nextCursor().orElseThrow(), 4);

        assertEquals(List.of(id(1), id(2), id(3), id(4)),
                first.entries().stream().map(Map.Entry::getKey).toList());
        assertTrue(first.hasMore());
        assertEquals(List.of(id(5), id(6), id(7), id(8)),
                second.entries().stream().map(Map.Entry::getKey).toList());
        assertEquals(List.of(id(9)), last.entries().stream().map(Map.Entry::getKey).toList());
        assertFalse(last.hasMore());
        assertThrows(IllegalArgumentException.class, () -> state.operationsAfter(null, 0));
    }

    private static BossRewardOperation operation(BossRewardOperation.Phase phase, long cooldownUntil) {
        return new BossRewardOperation(
                ENCOUNTER, net.minecraft.resources.Identifier.parse("rovenfall:test_boss"), id(10), PLAYER,
                WorldTopology.WILDERNESS, new BlockPos(4_096, 96, 4_096), 25, 100,
                10, 2_500, 50, 500, cooldownUntil, 1_000, List.of(), phase);
    }

    private static UUID id(long value) {
        return new UUID(0L, value);
    }
}
