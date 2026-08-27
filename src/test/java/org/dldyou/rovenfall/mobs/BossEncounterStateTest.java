package org.dldyou.rovenfall.mobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.serialization.JsonOps;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.loot.LootTable;
import org.dldyou.rovenfall.world.WorldTopology;
import org.junit.jupiter.api.Test;

final class BossEncounterStateTest {
    private static final UUID ENCOUNTER = UUID.fromString("7f40c570-a8f0-4dce-90ef-2e66165596b6");
    private static final BossEncounterState.RewardPlan REWARD_PLAN = rewardPlan();
    private static final UUID FINGERPRINT = BossEncounterRuntime.definitionFingerprint(
            REWARD_PLAN.boss(), REWARD_PLAN.arena(), REWARD_PLAN.mob(),
            REWARD_PLAN.contribution(), REWARD_PLAN.loot());
    private static final UUID ENTITY = UUID.fromString("c9e78625-ea68-455a-9227-66063c4ec050");

    @Test
    void phaseAndPatternTransitionsAreMonotonicAndCodecSafe() {
        BossEncounterState state = state();

        state = state.enterPhase(1, 30)
                .beginTelegraph(id("pattern"), 50)
                .beginExecution(70)
                .finishPattern(100);

        assertEquals(1, state.phaseIndex());
        assertEquals(BossEncounterState.Stage.IDLE, state.stage());
        assertEquals(1, state.sequence());
        assertEquals(100, state.nextPatternGameTime());
        assertEquals(state, state.enterPhase(0, 1));
        var encoded = BossEncounterState.CODEC.encodeStart(JsonOps.INSTANCE, state).getOrThrow();
        assertEquals(state, BossEncounterState.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow());
    }

    @Test
    void contributionLedgerIsBoundedSaturatingAndPersistent() {
        UUID first = UUID.fromString("973c9d65-33d0-42ad-a974-404a4a34cc3b");
        UUID second = UUID.fromString("68dd7421-b573-4685-b1af-d0ffc172acb0");

        BossEncounterState state = state()
                .contribute(first, MobContentSnapshot.MAX_REWARD, 1, 2_000)
                .contribute(first, 10, 1, 2_001)
                .contribute(second, 10, 1, 2_002);

        assertEquals(Map.of(first, MobContentSnapshot.MAX_REWARD), state.contributions());
        assertEquals(2_002, state.lastParticipantAtEpochMillis());
        assertTrue(state.isValid());
    }

    @Test
    void rewardPendingIntentIsPersistentAndStopsPatternProgression() {
        BossEncounterState pending = state().beginTelegraph(id("pattern"), 50).markRewardPending(REWARD_PLAN);

        assertEquals(BossEncounterState.Stage.REWARD_PENDING, pending.stage());
        assertTrue(pending.patternId().isEmpty());
        assertEquals(pending, pending.beginExecution(60));
        var encoded = BossEncounterState.CODEC.encodeStart(JsonOps.INSTANCE, pending).getOrThrow();
        assertEquals(pending, BossEncounterState.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow());
    }

    @Test
    void encounterSelectionTimeoutAndArenaBoundsAreDeterministic() {
        var phaseOne = new MobContentCatalog.Phase(
                id("phase_one"), "boss_phase.rovenfall.one", 100,
                List.of(pattern("heavy", 3), pattern("light", 1)));
        var phaseTwo = new MobContentCatalog.Phase(
                id("phase_two"), "boss_phase.rovenfall.two", 50,
                List.of(pattern("last", 1)));
        var boss = new MobContentCatalog.BossDefinition(
                id("boss"), "boss.rovenfall.test", id("mob"), id("arena"), id("rule"), id("loot"),
                20, List.of(phaseOne, phaseTwo));
        var arena = new MobContentCatalog.ArenaPolicy(
                id("arena"), WorldTopology.WILDERNESS, new BlockPos(4096, 96, 4096), 48, 64, 20);

        assertEquals(0, BossEncounterRuntime.phaseIndex(boss, 51, 100));
        assertEquals(1, BossEncounterRuntime.phaseIndex(boss, 50, 100));
        assertEquals(
                BossEncounterRuntime.selectPattern(state(), phaseOne),
                BossEncounterRuntime.selectPattern(state(), phaseOne));
        assertFalse(BossEncounterRuntime.isTimedOut(state(), arena, 2_999));
        assertTrue(BossEncounterRuntime.isTimedOut(state(), arena, 3_000));
        assertTrue(BossEncounterRuntime.regionFor(arena).isValid());
        assertEquals(49, BossEncounterRuntime.regionFor(arena).areaChunks());
    }

    @Test
    void savedDataRejectsDuplicateEntityOwnershipAndSupportsCleanup() {
        BossEncounterSavedData data = new BossEncounterSavedData();
        BossEncounterState first = state();
        BossEncounterState duplicateEntity = new BossEncounterState(
                UUID.randomUUID(), id("other"), FINGERPRINT, ENTITY, WorldTopology.WILDERNESS,
                new BlockPos(0, 80, 0), reservation(new BlockPos(0, 80, 0)),
                1_000, 1_000, 0, BossEncounterState.Stage.IDLE,
                Optional.empty(), 0, 20, 0, Map.of(), Optional.empty());

        assertTrue(data.put(first));
        assertFalse(data.put(duplicateEntity));
        assertEquals(first, data.encounterByEntity(ENTITY).orElseThrow());
        assertTrue(data.remove(ENCOUNTER));
        assertEquals(0, data.activeCount());
    }

    @Test
    void definitionFingerprintCoversResolvedContributionAndLootValues() {
        var phase = new MobContentCatalog.Phase(
                id("phase"), "boss_phase.rovenfall.phase", 100, List.of(pattern("pattern", 1)));
        var boss = new MobContentCatalog.BossDefinition(
                id("boss"), "boss.rovenfall.test", id("mob"), id("arena"), id("rule"), id("loot"),
                20, List.of(phase));
        var arena = new MobContentCatalog.ArenaPolicy(
                id("arena"), WorldTopology.WILDERNESS, new BlockPos(4096, 96, 4096), 48, 64, 20);
        var mob = new MobContentCatalog.MobDefinition(
                id("mob"), "mob.rovenfall.test", Identifier.withDefaultNamespace("iron_golem"),
                600, 16, 0.25, List.of(), id("loot"), Optional.empty());
        var contribution = new MobContentCatalog.ContributionRule(id("rule"), 50, 500, 50);
        var changedContribution = new MobContentCatalog.ContributionRule(id("rule"), 51, 500, 50);
        ResourceKey<LootTable> lootTable = ResourceKey.create(Registries.LOOT_TABLE, id("boss_loot"));
        var loot = new MobContentCatalog.LootDefinition(id("loot"), lootTable, 3, 250, 500);
        var changedLoot = new MobContentCatalog.LootDefinition(id("loot"), lootTable, 3, 251, 500);

        UUID fingerprint = BossEncounterRuntime.definitionFingerprint(boss, arena, mob, contribution, loot);

        assertNotEquals(fingerprint,
                BossEncounterRuntime.definitionFingerprint(boss, arena, mob, changedContribution, loot));
        assertNotEquals(fingerprint,
                BossEncounterRuntime.definitionFingerprint(boss, arena, mob, contribution, changedLoot));
    }

    @Test
    void arenaChunkCalculationDoesNotOverflowAtIntegerCoordinates() {
        var positive = new MobContentCatalog.ArenaPolicy(
                id("positive"), WorldTopology.WILDERNESS,
                new BlockPos(Integer.MAX_VALUE, 96, Integer.MAX_VALUE), 1_024, 1_024, 20);
        var negative = new MobContentCatalog.ArenaPolicy(
                id("negative"), WorldTopology.WILDERNESS,
                new BlockPos(Integer.MIN_VALUE, 96, Integer.MIN_VALUE), 1_024, 1_024, 20);

        assertTrue(BossEncounterRuntime.regionFor(positive).minChunkX() > 0);
        assertTrue(BossEncounterRuntime.regionFor(negative).maxChunkX() < 0);
    }

    private static BossEncounterState state() {
        return BossEncounterState.start(
                ENCOUNTER, id("boss"), FINGERPRINT, ENTITY, WorldTopology.WILDERNESS,
                new BlockPos(4096, 96, 4096), reservation(new BlockPos(4096, 96, 4096)), 2_000, 10);
    }

    private static org.dldyou.rovenfall.world.ProtectedRegion reservation(BlockPos center) {
        return new org.dldyou.rovenfall.world.ProtectedRegion(
                org.dldyou.rovenfall.administration.AdministrationService.SYSTEM_ACTOR,
                WorldTopology.WILDERNESS, center.getX() >> 4, center.getZ() >> 4,
                center.getX() >> 4, center.getZ() >> 4);
    }

    private static MobContentCatalog.PatternDefinition pattern(String path, int weight) {
        return new MobContentCatalog.PatternDefinition(
                id(path), "boss_pattern.rovenfall." + path, id("melee_sweep"), 20, 20, 20, weight);
    }

    private static BossEncounterState.RewardPlan rewardPlan() {
        var phase = new MobContentCatalog.Phase(
                id("phase"), "boss_phase.rovenfall.phase", 100, List.of(pattern("pattern", 1)));
        var boss = new MobContentCatalog.BossDefinition(
                id("boss"), "boss.rovenfall.test", id("mob"), id("arena"), id("rule"), id("loot"),
                20, List.of(phase));
        var arena = new MobContentCatalog.ArenaPolicy(
                id("arena"), WorldTopology.WILDERNESS, new BlockPos(4096, 96, 4096), 48, 64, 20);
        var mob = new MobContentCatalog.MobDefinition(
                id("mob"), "mob.rovenfall.test", Identifier.withDefaultNamespace("iron_golem"),
                600, 16, 0.25, List.of(), id("loot"), Optional.empty());
        var contribution = new MobContentCatalog.ContributionRule(id("rule"), 50, 500, 50);
        var loot = new MobContentCatalog.LootDefinition(
                id("loot"), ResourceKey.create(Registries.LOOT_TABLE, id("boss_loot")), 3, 250, 500);
        return new BossEncounterState.RewardPlan(boss, arena, mob, contribution, loot);
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("rovenfall", path);
    }
}
