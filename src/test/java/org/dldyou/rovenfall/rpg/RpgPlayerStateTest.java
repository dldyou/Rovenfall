package org.dldyou.rovenfall.rpg;

import static org.dldyou.rovenfall.PersistenceTestHarness.roundTrip;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.nio.file.Path;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RpgPlayerStateTest {
    private static final Identifier COMBAT = id("combat");
    private static final Identifier NOVICE = id("novice");
    private static final Identifier WARRIOR = id("warrior");
    private static final Identifier STURDY = id("sturdy_body");
    private static final Identifier POWER = id("power_strike");

    @Test
    void stateCodecRoundTripsAllProgressionAndUsesStableOrdering() {
        var novice = new RpgPlayerState.CareerProgress(400, 2, 7, Map.of(STURDY, 2));
        var state = new RpgPlayerState(
                Map.of(COMBAT, 500L, id("mining"), 12L),
                Map.of(NOVICE, novice),
                Optional.of(NOVICE),
                List.of(POWER),
                Map.of(POWER, 1_000L),
                List.of(new RpgPlayerState.ProgressionProvenance(
                        RpgPlayerState.ProgressionProvenance.Kind.ACTIVITY_XP, COMBAT, 500, 42, "gametest")));

        var encoded = RpgPlayerState.CODEC.encodeStart(NbtOps.INSTANCE, state).getOrThrow();
        assertEquals(encoded, RpgPlayerState.CODEC.encodeStart(NbtOps.INSTANCE, roundTrip(RpgPlayerState.CODEC, state)).getOrThrow());
        assertEquals(state, roundTrip(RpgPlayerState.CODEC, state));
    }

    @Test
    void rootPersistsStateAndUnknownSchemaIsReadOnly() {
        var root = new RpgPlayerSavedData();
        UUID player = idUuid(1);
        var state = new RpgPlayerState(Map.of(COMBAT, 100L), Map.of(), Optional.empty(), List.of(), Map.of(), List.of());
        assertTrue(root.commit(player, state));
        var loaded = roundTrip(RpgPlayerSavedData.CODEC, root);
        assertEquals(state, loaded.player(player).orElseThrow());
        assertEquals(state, loaded.snapshot().player(player).orElseThrow());

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
        var state = new RpgPlayerState(Map.of(COMBAT, 77L), Map.of(), Optional.empty(), List.of(), Map.of(), List.of());
        root.commit(player, state);
        var snapshots = new RpgPlayerSnapshotStore(directory);
        UUID snapshotId = idUuid(5);
        snapshots.write(snapshotId, root);
        assertEquals(state, snapshots.read(snapshotId).state(player));
        org.junit.jupiter.api.Assertions.assertThrows(
                RpgPlayerSnapshotStore.SnapshotException.class, () -> snapshots.write(snapshotId, root));
    }

    @Test
    void corruptedDuplicatePlayerAndSkillEntriesAreRejected() {
        var root = new RpgPlayerSavedData();
        UUID player = idUuid(2);
        var state = new RpgPlayerState(Map.of(COMBAT, 100L), Map.of(), Optional.empty(), List.of(), Map.of(), List.of());
        root.commit(player, state);
        CompoundTag duplicatePlayer = ((CompoundTag) RpgPlayerSavedData.CODEC
                .encodeStart(NbtOps.INSTANCE, root).getOrThrow()).copy();
        var players = duplicatePlayer.getListOrEmpty("players");
        players.add(players.getFirst().copy());
        assertTrue(RpgPlayerSavedData.CODEC.parse(NbtOps.INSTANCE, duplicatePlayer).error().isPresent());

        var valid = new RpgPlayerState(
                Map.of(), Map.of(NOVICE, new RpgPlayerState.CareerProgress(0, 0, 1, Map.of(STURDY, 1))),
                Optional.of(NOVICE), List.of(STURDY), Map.of(), List.of());
        CompoundTag duplicateSlots = ((CompoundTag) RpgPlayerState.CODEC
                .encodeStart(NbtOps.INSTANCE, valid).getOrThrow()).copy();
        duplicateSlots.getListOrEmpty("active_skill_slots").add(duplicateSlots.getListOrEmpty("active_skill_slots").getFirst().copy());
        assertTrue(RpgPlayerState.CODEC.parse(NbtOps.INSTANCE, duplicateSlots).error().isPresent());
    }

    @Test
    void activityPromotionSkillsAndCooldownsAreAtomicAndServerValidated() {
        var definitions = definitions();
        var root = new RpgPlayerSavedData();
        UUID player = idUuid(3);

        assertEquals(RpgPlayerStateService.Status.SUCCESS,
                RpgPlayerStateService.awardActivityXp(root, definitions, player, COMBAT, 300, "combat", 1).status());
        assertEquals(RpgPlayerStateService.Status.SUCCESS,
                RpgPlayerStateService.promote(root, definitions, player, NOVICE, 2, "promotion").status());
        assertEquals(RpgPlayerStateService.Status.SUCCESS,
                RpgPlayerStateService.awardActivityXp(root, definitions, player, COMBAT, 300, "combat", 3).status());
        assertEquals(RpgPlayerStateService.Status.SUCCESS,
                RpgPlayerStateService.learnSkill(root, definitions, player, STURDY, 4, "skill").status());
        assertEquals(1, root.state(player).careers().get(NOVICE).learnedSkills().get(STURDY));

        assertEquals(RpgPlayerStateService.Status.SUCCESS,
                RpgPlayerStateService.learnSkill(root, definitions, player, STURDY, 5, "skill").status());
        assertEquals(RpgPlayerStateService.Status.SUCCESS,
                RpgPlayerStateService.awardActivityXp(root, definitions, player, COMBAT, 300, "combat", 6).status());
        assertEquals(RpgPlayerStateService.Status.SUCCESS,
                RpgPlayerStateService.promote(root, definitions, player, WARRIOR, 7, "promotion").status());
        assertEquals(RpgPlayerStateService.Status.SUCCESS,
                RpgPlayerStateService.awardCareerXp(root, definitions, player, WARRIOR, 600, "career", 8).status());
        assertEquals(RpgPlayerStateService.Status.SUCCESS,
                RpgPlayerStateService.learnSkill(root, definitions, player, POWER, 9, "skill").status());
        assertEquals(RpgPlayerStateService.Status.SUCCESS,
                RpgPlayerStateService.equipActiveSkill(root, definitions, player, POWER, 0).status());
        assertEquals(RpgPlayerStateService.Status.SUCCESS,
                RpgPlayerStateService.startCooldown(root, definitions, player, POWER, 10).status());
        assertEquals(RpgPlayerStateService.Status.COOLDOWN,
                RpgPlayerStateService.startCooldown(root, definitions, player, POWER, 11).status());
        assertEquals(170, root.state(player).cooldowns().get(POWER));
    }

    private static RpgDefinitionSnapshot definitions() {
        return RpgDefinitionSnapshot.compile(
                List.of(new RpgDefinitionSnapshot.ActivitySource(file("activities/combat"), "test", COMBAT,
                        new ActivityDefinition("activity.rovenfall.combat", List.of(100L, 300L, 600L)))),
                List.of(
                        new RpgDefinitionSnapshot.CareerSource(file("careers/novice"), "test", NOVICE,
                                new CareerDefinition("career.rovenfall.novice", 1, List.of(), List.of(100L, 300L), 0, List.of())),
                        new RpgDefinitionSnapshot.CareerSource(file("careers/warrior"), "test", WARRIOR,
                                new CareerDefinition("career.rovenfall.warrior", 2, List.of(NOVICE), List.of(200L, 600L), 0,
                                        List.of(new CareerDefinition.ActivityRequirement(COMBAT, 2))))),
                List.of(
                        new RpgDefinitionSnapshot.SkillSource(file("skills/sturdy_body"), "test", STURDY,
                                new SkillDefinition("skill.rovenfall.sturdy_body", NOVICE, SkillDefinition.Kind.PASSIVE,
                                        3, 1, List.of(), Optional.empty())),
                        new RpgDefinitionSnapshot.SkillSource(file("skills/power_strike"), "test", POWER,
                                new SkillDefinition("skill.rovenfall.power_strike", WARRIOR, SkillDefinition.Kind.ACTIVE,
                                        3, 1, List.of(new SkillDefinition.Prerequisite(STURDY, 2)), Optional.of(160)))));
    }

    private static Identifier file(String path) {
        return Identifier.fromNamespaceAndPath("rovenfall", "rovenfall/rpg/" + path + ".json");
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("rovenfall", path);
    }

    private static UUID idUuid(long value) {
        return new UUID(0L, value);
    }
}
