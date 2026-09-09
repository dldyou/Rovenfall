package org.dldyou.rovenfall.exploration;

import static org.dldyou.rovenfall.PersistenceTestHarness.roundTrip;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

final class ExplorationPlayerStateTest {
    @Test
    void stateAndDedicatedRootRoundTripWithCapturedReward() {
        UUID player = uuid(1);
        UUID transaction = uuid(2);
        var operation = new ExplorationPlayerState.RewardOperation(
                transaction, 25, 1_000,
                ExplorationPlayerState.RewardOperation.Phase.CAPTURED);
        var playerState = new ExplorationPlayerState(Map.of(
                id("ancient_tree"), new ExplorationPlayerState.DiscoveryReceipt(
                        3, 1_000, transaction, Optional.of(operation))));
        var root = new ExplorationPlayerSavedData();

        assertTrue(root.commit(player, ExplorationPlayerState.EMPTY, playerState));
        var restarted = roundTrip(ExplorationPlayerSavedData.CODEC, root);

        assertEquals(ExplorationPlayerSavedData.CURRENT_SCHEMA_VERSION, restarted.schemaVersion());
        assertEquals(playerState, restarted.state(player));
        assertTrue(restarted.isWritable());
    }

    @Test
    void schemaZeroMigratesAndFutureSchemaRetainsStateReadOnly() {
        UUID player = uuid(3);
        var state = new ExplorationPlayerState(Map.of(
                id("kept"), new ExplorationPlayerState.DiscoveryReceipt(
                        1, 10, uuid(4), Optional.empty())));
        var root = new ExplorationPlayerSavedData();
        assertTrue(root.commit(player, ExplorationPlayerState.EMPTY, state));

        CompoundTag schemaZero = (CompoundTag) ExplorationPlayerSavedData.CODEC
                .encodeStart(NbtOps.INSTANCE, root).getOrThrow();
        schemaZero.putInt("schema_version", 0);
        var migrated = ExplorationPlayerSavedData.CODEC.parse(NbtOps.INSTANCE, schemaZero).getOrThrow();
        assertEquals(ExplorationPlayerSavedData.CURRENT_SCHEMA_VERSION, migrated.schemaVersion());
        assertTrue(migrated.isWritable());
        assertEquals(state, migrated.state(player));

        CompoundTag future = (CompoundTag) ExplorationPlayerSavedData.CODEC
                .encodeStart(NbtOps.INSTANCE, root).getOrThrow();
        future.putInt("schema_version", ExplorationPlayerSavedData.CURRENT_SCHEMA_VERSION + 1);
        var readOnly = ExplorationPlayerSavedData.CODEC.parse(NbtOps.INSTANCE, future).getOrThrow();
        assertFalse(readOnly.isWritable());
        assertEquals(state, readOnly.state(player));
        assertFalse(readOnly.commit(player, state, ExplorationPlayerState.EMPTY));
    }

    @Test
    void discoveryBoundAndCompareAndSetRejectWithoutMutation() {
        Map<Identifier, ExplorationPlayerState.DiscoveryReceipt> entries = new LinkedHashMap<>();
        for (int index = ExplorationPlayerState.MAX_DISCOVERIES - 1; index >= 0; index--) {
            entries.put(id("entry_" + index), new ExplorationPlayerState.DiscoveryReceipt(
                    1, index + 1L, uuid(index + 10L), Optional.empty()));
        }
        var full = new ExplorationPlayerState(entries);
        var root = new ExplorationPlayerSavedData();
        UUID player = uuid(9_000);

        assertTrue(full.isValid());
        assertTrue(root.commit(player, ExplorationPlayerState.EMPTY, full));
        assertFalse(root.commit(player, ExplorationPlayerState.EMPTY, ExplorationPlayerState.EMPTY));
        assertEquals(full, root.state(player));
        assertEquals("entry_0", full.discoveries().keySet().iterator().next().getPath());
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("rovenfall", path);
    }

    private static UUID uuid(long value) {
        return new UUID(0L, value);
    }
}
