package org.dldyou.rovenfall.rpg;

import static org.dldyou.rovenfall.PersistenceTestHarness.roundTrip;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RpgPlayerStateTest {
    private static final Identifier COMBAT = id("combat");
    private static final Identifier NOVICE = id("novice");
    private static final Identifier STURDY = id("sturdy_body");
    private static final Identifier POWER = id("power_strike");

    @Test
    void stateCodecRoundTripsAllProgressionAndUsesStableOrdering() {
        var novice = new RpgPlayerState.CareerProgress(400, 2, 7, Map.of(STURDY, 2));
        var state = new RpgPlayerState(
                Map.of(COMBAT, 500L, id("mining"), 12L),
                Map.of(NOVICE, novice),
                Optional.of(NOVICE),
                Map.of(3, POWER),
                Map.of(POWER, 1_000L),
                Set.of(Identifier.parse("minecraft:adventure/adventuring_time")),
                List.of(new RpgPlayerState.ProgressionProvenance(
                        RpgPlayerState.ProgressionProvenance.Kind.ACTIVITY_XP,
                        COMBAT, 500, 42, idUuid(10), "gametest")));

        var encoded = RpgPlayerState.CODEC.encodeStart(NbtOps.INSTANCE, state).getOrThrow();
        assertEquals(encoded, RpgPlayerState.CODEC.encodeStart(NbtOps.INSTANCE, roundTrip(RpgPlayerState.CODEC, state)).getOrThrow());
        assertEquals(state, roundTrip(RpgPlayerState.CODEC, state));
        assertEquals(POWER, roundTrip(RpgPlayerState.CODEC, state).activeSkillSlots().get(3));
    }

    @Test
    void rootPersistsStateAndUnknownSchemaIsReadOnly() {
        var root = new RpgPlayerSavedData();
        UUID player = idUuid(1);
        var state = new RpgPlayerState(Map.of(COMBAT, 100L), Map.of(), Optional.empty(), Map.of(), Map.of(), List.of());
        assertTrue(root.commit(player, state));
        var loaded = roundTrip(RpgPlayerSavedData.CODEC, root);
        assertEquals(state, loaded.player(player).orElseThrow());
        assertEquals(state, loaded.snapshot().player(player).orElseThrow());

        CompoundTag schemaZero = (CompoundTag) RpgPlayerSavedData.CODEC
                .encodeStart(NbtOps.INSTANCE, root).getOrThrow();
        schemaZero.putInt("schema_version", 0);
        var migrated = RpgPlayerSavedData.CODEC.parse(NbtOps.INSTANCE, schemaZero).getOrThrow();
        assertEquals(RpgPlayerSavedData.CURRENT_SCHEMA_VERSION, migrated.schemaVersion());
        assertTrue(migrated.isWritable());
        assertEquals(state, migrated.state(player));

        CompoundTag future = (CompoundTag) RpgPlayerSavedData.CODEC.encodeStart(NbtOps.INSTANCE, root).getOrThrow();
        future.putInt("schema_version", RpgPlayerSavedData.CURRENT_SCHEMA_VERSION + 1);
        var readOnly = RpgPlayerSavedData.CODEC.parse(NbtOps.INSTANCE, future).getOrThrow();
        assertFalse(readOnly.isWritable());
        assertFalse(readOnly.commit(player, RpgPlayerState.EMPTY));
        assertEquals(state, readOnly.state(player));
    }

    @Test
    void snapshotsAreAtomicAndRoundTripTheDedicatedRoot(@TempDir Path directory) throws Exception {
        var root = new RpgPlayerSavedData();
        UUID player = idUuid(4);
        var state = new RpgPlayerState(Map.of(COMBAT, 77L), Map.of(), Optional.empty(), Map.of(), Map.of(), List.of());
        root.commit(player, state);
        var snapshots = new RpgPlayerSnapshotStore(directory);
        UUID snapshotId = idUuid(5);
        snapshots.write(snapshotId, root);
        assertEquals(state, snapshots.read(snapshotId).state(player));
        org.junit.jupiter.api.Assertions.assertThrows(
                RpgPlayerSnapshotStore.SnapshotException.class, () -> snapshots.write(snapshotId, root));

        UUID corruptId = idUuid(6);
        Files.writeString(directory.resolve(corruptId + ".nbt"), "not nbt");
        org.junit.jupiter.api.Assertions.assertThrows(
                RpgPlayerSnapshotStore.SnapshotException.class, () -> snapshots.read(corruptId));
    }

    @Test
    void corruptedDuplicatePlayerAndActiveSlotEntriesAreRejected() {
        var root = new RpgPlayerSavedData();
        UUID player = idUuid(2);
        var state = new RpgPlayerState(Map.of(COMBAT, 100L), Map.of(), Optional.empty(), Map.of(), Map.of(), List.of());
        root.commit(player, state);
        CompoundTag duplicatePlayer = ((CompoundTag) RpgPlayerSavedData.CODEC
                .encodeStart(NbtOps.INSTANCE, root).getOrThrow()).copy();
        var players = duplicatePlayer.getListOrEmpty("players");
        players.add(players.getFirst().copy());
        assertTrue(RpgPlayerSavedData.CODEC.parse(NbtOps.INSTANCE, duplicatePlayer).error().isPresent());

        var valid = new RpgPlayerState(
                Map.of(), Map.of(NOVICE, new RpgPlayerState.CareerProgress(0, 0, 1, Map.of(STURDY, 1))),
                Optional.of(NOVICE), Map.of(2, STURDY), Map.of(), List.of());
        CompoundTag duplicateSlots = ((CompoundTag) RpgPlayerState.CODEC
                .encodeStart(NbtOps.INSTANCE, valid).getOrThrow()).copy();
        duplicateSlots.getListOrEmpty("active_skill_slots").add(duplicateSlots.getListOrEmpty("active_skill_slots").getFirst().copy());
        assertTrue(RpgPlayerState.CODEC.parse(NbtOps.INSTANCE, duplicateSlots).error().isPresent());
    }

    @Test
    void runtimeCommitRejectsInvalidStateAndZeroPlayerWithoutMutation() {
        var root = new RpgPlayerSavedData();
        UUID player = idUuid(7);
        var invalidXp = new RpgPlayerState(
                Map.of(COMBAT, -1L), Map.of(), Optional.empty(), Map.of(), Map.of(), List.of());
        assertFalse(root.commit(player, invalidXp));

        var invalidCareer = new RpgPlayerState(
                Map.of(), Map.of(NOVICE, new RpgPlayerState.CareerProgress(-1, -1, -1, Map.of())),
                Optional.of(NOVICE), Map.of(), Map.of(), List.of());
        assertFalse(root.commit(player, invalidCareer));

        var invalidProvenance = new RpgPlayerState(
                Map.of(), Map.of(), Optional.empty(), Map.of(), Map.of(),
                List.of(new RpgPlayerState.ProgressionProvenance(
                        RpgPlayerState.ProgressionProvenance.Kind.ACTIVITY_XP,
                        COMBAT, 1, 1, new UUID(0L, 0L), " ")));
        assertFalse(root.commit(player, invalidProvenance));

        var evidence = new RpgPlayerState.ProgressionProvenance(
                RpgPlayerState.ProgressionProvenance.Kind.ACTIVITY_XP,
                COMBAT, 1, 1, idUuid(8), "combat");
        var duplicateTransaction = new RpgPlayerState(
                Map.of(), Map.of(), Optional.empty(), Map.of(), Map.of(), List.of(evidence, evidence));
        assertFalse(root.commit(player, duplicateTransaction));
        var duplicateAcrossLedgers = new RpgPlayerState(
                Map.of(), Map.of(), Optional.empty(), Map.of(), Map.of(), Set.of(),
                List.of(evidence), List.of(evidence));
        assertFalse(root.commit(player, duplicateAcrossLedgers));
        assertFalse(root.commit(new UUID(0L, 0L), RpgPlayerState.EMPTY));
        assertEquals(0, root.playerCount());
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("rovenfall", path);
    }

    private static UUID idUuid(long value) {
        return new UUID(0L, value);
    }
}
