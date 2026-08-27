package org.dldyou.rovenfall.rpg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

final class ActivityXpAwardServiceTest {
    private static final Identifier ACTIVITY = id("combat");
    private static final Identifier MINING = id("mining");
    private static final Identifier EXPLORATION = id("exploration");
    private static final Identifier EXPLORATION_ADVANCEMENT = Identifier.parse(
            "minecraft:adventure/adventuring_time");
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final ActivityXpConfig.Limits LIMITS = new ActivityXpConfig.Limits(10, 2, 1_000, 100, 10);

    @Test
    void awardsAtomicallyRecordsEvidenceAndRoundTrips() {
        RpgPlayerSavedData state = new RpgPlayerSavedData();
        RpgDefinitionSnapshot definitions = definitions();
        var result = ActivityXpAwardService.award(state, definitions, PLAYER, ACTIVITY, 5, 1_000,
                uuid(1), "combat:target", LIMITS);
        assertEquals(ActivityXpAwardService.Status.SUCCESS, result.status());
        assertEquals(5, state.state(PLAYER).activityXp().get(ACTIVITY));
        assertEquals("combat:target", state.state(PLAYER).provenance().getFirst().source());
        assertEquals(uuid(1), state.state(PLAYER).provenance().getFirst().transactionId());
        var encoded = RpgPlayerSavedData.CODEC.encodeStart(NbtOps.INSTANCE, state).getOrThrow();
        var loaded = RpgPlayerSavedData.CODEC.parse(NbtOps.INSTANCE, encoded).getOrThrow();
        assertEquals(state.snapshot().player(PLAYER), loaded.snapshot().player(PLAYER));
        var evidence = ActivityXpAwardService.evidence(state, PLAYER, Optional.of(ACTIVITY), 0, 10);
        assertEquals(1, evidence.totalEntries());
        assertEquals(uuid(1), evidence.entries().getFirst().transactionId());
    }

    @Test
    void rejectsDuplicateCooldownRateUnknownAndReadOnlyWithoutMutation() {
        RpgPlayerSavedData state = new RpgPlayerSavedData();
        RpgDefinitionSnapshot definitions = definitions();
        assertEquals(ActivityXpAwardService.Status.SUCCESS,
                ActivityXpAwardService.award(state, definitions, PLAYER, ACTIVITY, 5, 1_000,
                        uuid(2), "combat:one", LIMITS).status());
        assertEquals(ActivityXpAwardService.Status.COOLDOWN,
                ActivityXpAwardService.award(state, definitions, PLAYER, ACTIVITY, 5, 1_050,
                        uuid(3), "combat:one", LIMITS).status());
        assertEquals(ActivityXpAwardService.Status.DUPLICATE,
                ActivityXpAwardService.award(state, definitions, PLAYER, ACTIVITY, 5, 1_500,
                        uuid(2), "combat:replay", LIMITS).status());
        assertEquals(ActivityXpAwardService.Status.SUCCESS,
                ActivityXpAwardService.award(state, definitions, PLAYER, ACTIVITY, 5, 1_200,
                        uuid(5), "combat:two", LIMITS).status());
        int provenance = state.state(PLAYER).provenance().size();
        assertEquals(ActivityXpAwardService.Status.RATE_LIMIT,
                ActivityXpAwardService.award(state, definitions, PLAYER, ACTIVITY, 1, 1_300,
                        uuid(6), "combat:three", LIMITS).status());
        assertEquals(provenance, state.state(PLAYER).provenance().size());
        assertEquals(ActivityXpAwardService.Status.UNKNOWN_ACTIVITY,
                ActivityXpAwardService.award(state, definitions, PLAYER, id("missing"), 1, 2_000,
                        uuid(7), "missing", LIMITS).status());
        CompoundTag future = (CompoundTag) RpgPlayerSavedData.CODEC
                .encodeStart(NbtOps.INSTANCE, state).getOrThrow();
        future.putInt("schema_version", RpgPlayerSavedData.CURRENT_SCHEMA_VERSION + 1);
        RpgPlayerSavedData readOnly = RpgPlayerSavedData.CODEC.parse(NbtOps.INSTANCE, future).getOrThrow();
        assertTrue(!readOnly.isWritable());
        assertEquals(ActivityXpAwardService.Status.READ_ONLY,
                ActivityXpAwardService.award(readOnly, definitions, PLAYER, ACTIVITY, 1, 4_000,
                        uuid(8), "read-only", LIMITS).status());
    }

    @Test
    void publishesAllSevenServerObservedActivitySeams() {
        assertEquals(7, RpgActivityEvents.mapping().size());
        assertTrue(RpgActivityEvents.mapping().get("mining").startsWith("BlockDropsEvent"));
        assertTrue(RpgActivityEvents.mapping().get("farming").startsWith("BlockDropsEvent"));
    }

    @Test
    void globalWindowAndTransactionLimitsCannotBeBypassedByChangingActivity() {
        RpgPlayerSavedData state = new RpgPlayerSavedData();
        RpgDefinitionSnapshot definitions = definitions();
        ActivityXpConfig.Limits oneAward = new ActivityXpConfig.Limits(10, 1, 1_000, 0, 10);

        assertEquals(ActivityXpAwardService.Status.SUCCESS,
                ActivityXpAwardService.award(state, definitions, PLAYER, ACTIVITY, 1, 1_000,
                        uuid(20), "combat:target", oneAward).status());
        assertEquals(ActivityXpAwardService.Status.DUPLICATE,
                ActivityXpAwardService.award(state, definitions, PLAYER, MINING, 1, 2_001,
                        uuid(20), "mining:position", oneAward).status());
        assertEquals(ActivityXpAwardService.Status.RATE_LIMIT,
                ActivityXpAwardService.award(state, definitions, PLAYER, MINING, 1, 1_500,
                        uuid(21), "mining:position", oneAward).status());
        assertTrue(state.state(PLAYER).activityXp().get(MINING) == null);
    }

    @Test
    void capsPersistedCombatXpPerTargetSource() {
        RpgPlayerSavedData state = new RpgPlayerSavedData();
        ActivityXpConfig.Limits targetCap = new ActivityXpConfig.Limits(10, 10, 0, 0, 2);

        assertEquals(ActivityXpAwardService.Status.SUCCESS,
                ActivityXpAwardService.award(state, definitions(), PLAYER, ACTIVITY, 1, 1,
                        uuid(30), "combat:one-target", targetCap).status());
        assertEquals(ActivityXpAwardService.Status.SUCCESS,
                ActivityXpAwardService.award(state, definitions(), PLAYER, ACTIVITY, 1, 2,
                        uuid(31), "combat:one-target", targetCap).status());
        assertEquals(ActivityXpAwardService.Status.RATE_LIMIT,
                ActivityXpAwardService.award(state, definitions(), PLAYER, ACTIVITY, 1, 3,
                        uuid(32), "combat:one-target", targetCap).status());
        assertEquals(2L, state.state(PLAYER).activityXp().get(ACTIVITY));
    }

    @Test
    void explorationUsesConfiguredFirstDiscoveryOnly() {
        RpgPlayerSavedData state = new RpgPlayerSavedData();

        assertTrue(ActivityXpConfig.isExplorationAdvancement(EXPLORATION_ADVANCEMENT));
        assertTrue(!ActivityXpConfig.isExplorationAdvancement(
                Identifier.parse("minecraft:story/mine_stone")));
        assertEquals(ActivityXpAwardService.Status.SUCCESS,
                ActivityXpAwardService.award(state, definitions(), PLAYER, EXPLORATION, 1, 1_000,
                        uuid(40), "exploration:" + EXPLORATION_ADVANCEMENT, LIMITS).status());
        assertEquals(ActivityXpAwardService.Status.DUPLICATE,
                ActivityXpAwardService.award(state, definitions(), PLAYER, EXPLORATION, 1, 10_000,
                        uuid(41), "exploration:" + EXPLORATION_ADVANCEMENT, LIMITS).status());
        assertTrue(state.state(PLAYER).explorationDiscoveries().contains(EXPLORATION_ADVANCEMENT));
        assertEquals(1L, state.state(PLAYER).activityXp().get(EXPLORATION));
    }

    private static RpgDefinitionSnapshot definitions() {
        return RpgDefinitionSnapshot.compile(List.of(
                new RpgDefinitionSnapshot.ActivitySource(
                        id("activities/combat"), "test", ACTIVITY,
                        new ActivityDefinition("activity.rovenfall.combat", List.of(100L))),
                new RpgDefinitionSnapshot.ActivitySource(
                        id("activities/mining"), "test", MINING,
                        new ActivityDefinition("activity.rovenfall.mining", List.of(100L))),
                new RpgDefinitionSnapshot.ActivitySource(
                        id("activities/exploration"), "test", EXPLORATION,
                        new ActivityDefinition("activity.rovenfall.exploration", List.of(100L)))),
                List.of(), List.of());
    }

    private static Identifier id(String path) { return Identifier.fromNamespaceAndPath("rovenfall", path); }
    private static UUID uuid(long least) { return new UUID(0, least); }
}
