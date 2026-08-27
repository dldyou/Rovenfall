package org.dldyou.rovenfall.rpg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

final class ActivityWorldSavedDataTest {
    private static final BlockPos SOURCE = new BlockPos(10, 64, -20);

    @Test
    void placedResourceMarkerRoundTripsAndIsConsumedOnce() {
        ActivityWorldSavedData state = new ActivityWorldSavedData();
        assertTrue(state.markSynthetic(Level.OVERWORLD, SOURCE));
        assertTrue(state.markSynthetic(Level.OVERWORLD, SOURCE));
        assertEquals(1, state.syntheticResourceCount());

        var encoded = ActivityWorldSavedData.CODEC.encodeStart(NbtOps.INSTANCE, state).getOrThrow();
        ActivityWorldSavedData loaded = ActivityWorldSavedData.CODEC.parse(NbtOps.INSTANCE, encoded).getOrThrow();
        assertTrue(loaded.consumeSynthetic(Level.OVERWORLD, SOURCE));
        assertFalse(loaded.consumeSynthetic(Level.OVERWORLD, SOURCE));
    }

    @Test
    void pistonPropagationKeepsSourceAndMarksDestinationFailSafe() {
        ActivityWorldSavedData state = new ActivityWorldSavedData();
        state.markSynthetic(Level.OVERWORLD, SOURCE);

        state.propagatePistonMove(Level.OVERWORLD, java.util.List.of(SOURCE), Direction.EAST);

        assertTrue(state.consumeSynthetic(Level.OVERWORLD, SOURCE));
        assertTrue(state.consumeSynthetic(Level.OVERWORLD, SOURCE.east()));
    }

    @Test
    void futureSchemaIsReadOnlyAndRejectsNaturalResourceCreditFailClosed() {
        ActivityWorldSavedData state = new ActivityWorldSavedData();
        CompoundTag encoded = (CompoundTag) ActivityWorldSavedData.CODEC
                .encodeStart(NbtOps.INSTANCE, state).getOrThrow();
        encoded.putInt("schema_version", ActivityWorldSavedData.CURRENT_SCHEMA_VERSION + 1);

        ActivityWorldSavedData loaded = ActivityWorldSavedData.CODEC.parse(NbtOps.INSTANCE, encoded).getOrThrow();

        assertFalse(loaded.isWritable());
        assertFalse(loaded.markSynthetic(Level.OVERWORLD, SOURCE));
        assertTrue(loaded.consumeSynthetic(Level.OVERWORLD, SOURCE));
    }

    @Test
    void schemaZeroMigrationPreservesSyntheticResourceMarkers() {
        ActivityWorldSavedData state = new ActivityWorldSavedData();
        state.markSynthetic(Level.OVERWORLD, SOURCE);
        CompoundTag encoded = (CompoundTag) ActivityWorldSavedData.CODEC
                .encodeStart(NbtOps.INSTANCE, state).getOrThrow();
        encoded.remove("schema_version");

        ActivityWorldSavedData loaded = ActivityWorldSavedData.CODEC.parse(NbtOps.INSTANCE, encoded).getOrThrow();

        assertEquals(ActivityWorldSavedData.CURRENT_SCHEMA_VERSION, loaded.schemaVersion());
        assertTrue(loaded.isWritable());
        assertTrue(loaded.consumeSynthetic(Level.OVERWORLD, SOURCE));
    }

    @Test
    void resetRemovesOnlyTheRegeneratedDimensionMarkers() {
        ActivityWorldSavedData state = new ActivityWorldSavedData();
        state.markSynthetic(Level.OVERWORLD, SOURCE);
        state.markSynthetic(Level.NETHER, SOURCE);

        assertEquals(1, state.clearDimension(Level.NETHER));
        assertTrue(state.consumeSynthetic(Level.OVERWORLD, SOURCE));
        assertFalse(state.consumeSynthetic(Level.NETHER, SOURCE));
    }

    @Test
    void restoreReplacesOnlyTheSnapshotDimensionMarkers() {
        ActivityWorldSavedData state = new ActivityWorldSavedData();
        BlockPos restored = SOURCE.offset(8, 0, 8);
        state.markSynthetic(Level.OVERWORLD, SOURCE);
        state.markSynthetic(Level.NETHER, SOURCE);
        ActivityWorldSavedData.DimensionSnapshot snapshot = new ActivityWorldSavedData.DimensionSnapshot(
                Level.NETHER, java.util.Set.of(restored.asLong()));

        assertTrue(state.replaceDimension(snapshot));

        assertTrue(state.consumeSynthetic(Level.OVERWORLD, SOURCE));
        assertFalse(state.consumeSynthetic(Level.NETHER, SOURCE));
        assertTrue(state.consumeSynthetic(Level.NETHER, restored));
    }
}
