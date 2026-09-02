package org.dldyou.rovenfall.administration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.serialization.JsonOps;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.NbtOps;
import net.minecraft.world.level.Level;
import org.dldyou.rovenfall.activities.ActivityBlockKey;
import org.dldyou.rovenfall.activities.ActivityState;
import org.junit.jupiter.api.Test;

final class ActivityResourceProvenanceTest {
    @Test
    void placedResourceProvenancePersistsMovesAndClearsByDimension() {
        PlatformSavedData state = new PlatformSavedData();
        BlockPos source = new BlockPos(10, 20, 30);
        BlockPos destination = source.east();
        BlockPos nether = new BlockPos(-4, 64, 8);
        assertTrue(state.observeActivityResourcePlacement(Level.OVERWORLD, source, true));
        assertTrue(state.observeActivityResourcePlacement(Level.NETHER, nether, true));

        PlatformSavedData decoded = PlatformSavedData.CODEC.parse(
                NbtOps.INSTANCE,
                PlatformSavedData.CODEC.encodeStart(NbtOps.INSTANCE, state).getOrThrow()).getOrThrow();
        assertTrue(decoded.isActivityResourcePlayerPlaced(Level.OVERWORLD, source));
        assertTrue(decoded.isActivityResourcePlayerPlaced(Level.NETHER, nether));
        assertEquals(2, decoded.placedActivityResourceCount());

        decoded.moveActivityResourcePlacements(
                Level.OVERWORLD, Map.of(source, destination), List.of());
        assertFalse(decoded.isActivityResourcePlayerPlaced(Level.OVERWORLD, source));
        assertTrue(decoded.isActivityResourcePlayerPlaced(Level.OVERWORLD, destination));
        assertTrue(decoded.isActivityResourcePlayerPlaced(Level.NETHER, nether));

        decoded.clearActivityResourcePlacements(Level.OVERWORLD);
        assertFalse(decoded.isActivityResourcePlayerPlaced(Level.OVERWORLD, destination));
        assertTrue(decoded.isActivityResourcePlayerPlaced(Level.NETHER, nether));
        assertEquals(1, decoded.placedActivityResourceCount());
        assertTrue(decoded.observeActivityResourcePlacement(Level.NETHER, nether, false));
        assertEquals(0, decoded.placedActivityResourceCount());
    }

    @Test
    void pistonMovementPreservesEveryMarkedBlockInAChainAndDropsDestroyedBlocks() {
        PlatformSavedData state = new PlatformSavedData();
        BlockPos first = new BlockPos(0, 64, 0);
        BlockPos second = first.east();
        BlockPos destroyed = second.east();
        state.observeActivityResourcePlacement(Level.OVERWORLD, first, true);
        state.observeActivityResourcePlacement(Level.OVERWORLD, second, true);
        state.observeActivityResourcePlacement(Level.OVERWORLD, destroyed, true);

        state.moveActivityResourcePlacements(
                Level.OVERWORLD,
                Map.of(first, second, second, destroyed),
                List.of(destroyed));

        assertFalse(state.isActivityResourcePlayerPlaced(Level.OVERWORLD, first));
        assertTrue(state.isActivityResourcePlayerPlaced(Level.OVERWORLD, second));
        assertTrue(state.isActivityResourcePlayerPlaced(Level.OVERWORLD, destroyed));
        assertEquals(2, state.placedActivityResourceCount());
    }

    @Test
    void placedResourceCodecRejectsDuplicates() {
        ActivityBlockKey key = new ActivityBlockKey(Level.OVERWORLD, new BlockPos(1, 2, 3));
        ActivityState state = new ActivityState(Map.of(), Map.of(), java.util.Set.of(key));
        var encoded = ActivityState.CODEC.encodeStart(JsonOps.INSTANCE, state)
                .getOrThrow().getAsJsonObject();
        encoded.getAsJsonArray("placed_resource_blocks").add(
                encoded.getAsJsonArray("placed_resource_blocks").get(0).deepCopy());
        assertTrue(ActivityState.CODEC.parse(JsonOps.INSTANCE, encoded).error().isPresent());
    }
}
