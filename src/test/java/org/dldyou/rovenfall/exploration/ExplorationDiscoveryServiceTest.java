package org.dldyou.rovenfall.exploration;

import static org.dldyou.rovenfall.PersistenceTestHarness.roundTrip;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import org.dldyou.rovenfall.rpg.ActivityDefinition;
import org.dldyou.rovenfall.rpg.RpgDefinitionSnapshot;
import org.dldyou.rovenfall.rpg.RpgPlayerSavedData;
import org.dldyou.rovenfall.rpg.RpgPlayerState;
import org.dldyou.rovenfall.world.WorldTopology;
import org.junit.jupiter.api.Test;

final class ExplorationDiscoveryServiceTest {
    private static final long NOW = 1_700_000_000_000L;
    private static final UUID PLAYER = uuid(1);

    @Test
    void exactThreeDimensionalEntryCommitsOnceAndSurvivesRestart() {
        Identifier discovery = id("stone_circle");
        var definitions = definitions(discovery, 1, new BlockPos(0, 64, 0), 5, true, Optional.of(25L));
        var exploration = new ExplorationPlayerSavedData();
        var rpg = new RpgPlayerSavedData();

        assertEquals(ExplorationDiscoveryService.Status.NO_CHANGE,
                observe(exploration, definitions, rpg, WorldTopology.WILDERNESS,
                        new BlockPos(3, 68, 1), NOW).status());
        assertEquals(ExplorationDiscoveryService.Status.NO_CHANGE,
                observe(exploration, definitions, rpg, WorldTopology.HUB,
                        new BlockPos(3, 68, 0), NOW).status());
        var first = observe(exploration, definitions, rpg, WorldTopology.WILDERNESS,
                new BlockPos(3, 68, 0), NOW);

        assertEquals(ExplorationDiscoveryService.Status.SUCCESS, first.status());
        assertEquals(1, first.discovered());
        assertEquals(25L, rpg.state(PLAYER).activityXp()
                .get(ExplorationDiscoveryService.EXPLORATION_ACTIVITY));
        var receipt = exploration.state(PLAYER).discovery(discovery).orElseThrow();
        assertEquals(ExplorationPlayerState.RewardOperation.Phase.APPLIED,
                receipt.rewardOperation().orElseThrow().phase());

        var restartedExploration = roundTrip(ExplorationPlayerSavedData.CODEC, exploration);
        var restartedRpg = roundTrip(RpgPlayerSavedData.CODEC, rpg);
        var duplicate = observe(restartedExploration, definitions, restartedRpg,
                WorldTopology.WILDERNESS, new BlockPos(0, 64, 0), NOW + 1);
        assertEquals(ExplorationDiscoveryService.Status.NO_CHANGE, duplicate.status());
        assertEquals(receipt.transactionId(), restartedExploration.state(PLAYER)
                .discovery(discovery).orElseThrow().transactionId());
        assertEquals(25L, restartedRpg.state(PLAYER).activityXp()
                .get(ExplorationDiscoveryService.EXPLORATION_ACTIVITY));
    }

    @Test
    void versionChangeRequiresNewRadiusAndNeverRewardsAgain() {
        Identifier discovery = id("moved_ruin");
        var exploration = new ExplorationPlayerSavedData();
        var rpg = new RpgPlayerSavedData();
        var firstDefinitions = definitions(discovery, 1, BlockPos.ZERO, 4, false, Optional.of(25L));
        assertEquals(ExplorationDiscoveryService.Status.SUCCESS,
                observe(exploration, firstDefinitions, rpg, WorldTopology.WILDERNESS, BlockPos.ZERO, NOW).status());
        UUID transaction = exploration.state(PLAYER).discovery(discovery).orElseThrow().transactionId();
        var moved = definitions(discovery, 2, new BlockPos(100, 0, 0), 4, false, Optional.of(999L));

        assertEquals(ExplorationDiscoveryService.Status.NO_CHANGE,
                observe(exploration, moved, rpg, WorldTopology.WILDERNESS, BlockPos.ZERO, NOW + 1).status());
        assertEquals(1, exploration.state(PLAYER).discovery(discovery).orElseThrow().definitionVersion());
        var refreshed = observe(exploration, moved, rpg, WorldTopology.WILDERNESS,
                new BlockPos(100, 0, 0), NOW + 2);

        assertEquals(1, refreshed.refreshed());
        var receipt = exploration.state(PLAYER).discovery(discovery).orElseThrow();
        assertEquals(2, receipt.definitionVersion());
        assertEquals(transaction, receipt.transactionId());
        assertEquals(25L, rpg.state(PLAYER).activityXp()
                .get(ExplorationDiscoveryService.EXPLORATION_ACTIVITY));
    }

    @Test
    void unknownOrReadOnlyRpgStaysPendingAndRecoveryIsExactOnce() {
        Identifier discovery = id("pending_reward");
        var definitions = definitions(discovery, 1, BlockPos.ZERO, 4, false, Optional.of(40L));
        var exploration = new ExplorationPlayerSavedData();
        var rpg = new RpgPlayerSavedData();

        var pending = ExplorationDiscoveryService.observe(
                exploration, definitions, rpg, RpgDefinitionSnapshot.empty(), PLAYER,
                WorldTopology.HUB, BlockPos.ZERO, NOW, NOW);
        assertEquals(ExplorationDiscoveryService.Status.REWARD_PENDING, pending.status());
        assertEquals(ExplorationPlayerState.RewardOperation.Phase.CAPTURED,
                exploration.state(PLAYER).discovery(discovery).orElseThrow()
                        .rewardOperation().orElseThrow().phase());

        var recovered = ExplorationDiscoveryService.recover(
                exploration, rpg, rpgDefinitions(), PLAYER, NOW + 1);
        var duplicateRecovery = ExplorationDiscoveryService.recover(
                exploration, rpg, rpgDefinitions(), PLAYER, NOW + 2);
        assertEquals(ExplorationDiscoveryService.RecoveryStatus.COMPLETE, recovered.status());
        assertEquals(1, recovered.applied());
        assertEquals(0, duplicateRecovery.attempted());
        assertEquals(40L, rpg.state(PLAYER).activityXp()
                .get(ExplorationDiscoveryService.EXPLORATION_ACTIVITY));
    }

    @Test
    void readOnlyRpgDoesNotLoseCapturedReward() {
        Identifier discovery = id("read_only_reward");
        var exploration = new ExplorationPlayerSavedData();
        CompoundTag future = (CompoundTag) RpgPlayerSavedData.CODEC
                .encodeStart(NbtOps.INSTANCE, new RpgPlayerSavedData()).getOrThrow();
        future.putInt("schema_version", RpgPlayerSavedData.CURRENT_SCHEMA_VERSION + 1);
        var readOnlyRpg = RpgPlayerSavedData.CODEC.parse(NbtOps.INSTANCE, future).getOrThrow();

        var result = ExplorationDiscoveryService.observe(
                exploration,
                definitions(discovery, 1, BlockPos.ZERO, 4, false, Optional.of(10L)),
                readOnlyRpg, rpgDefinitions(), PLAYER, WorldTopology.WILDERNESS,
                BlockPos.ZERO, NOW, NOW);

        assertEquals(ExplorationDiscoveryService.Status.REWARD_PENDING, result.status());
        assertFalse(readOnlyRpg.isWritable());
        assertEquals(ExplorationPlayerState.RewardOperation.Phase.CAPTURED,
                exploration.state(PLAYER).discovery(discovery).orElseThrow()
                        .rewardOperation().orElseThrow().phase());
        assertTrue(readOnlyRpg.state(PLAYER).activityXp().isEmpty());
    }

    @Test
    void zeroAndFarFutureTimestampsAndFullStateFailWithoutMutation() {
        Identifier discovery = id("rejected");
        var definitions = definitions(discovery, 1, BlockPos.ZERO, 4, false, Optional.empty());
        var exploration = new ExplorationPlayerSavedData();
        var rpg = new RpgPlayerSavedData();

        assertEquals(ExplorationDiscoveryService.Status.INVALID,
                observe(exploration, definitions, rpg, WorldTopology.HUB, BlockPos.ZERO, 0).status());
        assertEquals(ExplorationDiscoveryService.Status.INVALID,
                observe(exploration, definitions, rpg, WorldTopology.HUB, BlockPos.ZERO,
                        NOW + ExplorationDiscoveryService.FUTURE_TIMESTAMP_SKEW_MILLIS + 1).status());
        assertEquals(ExplorationPlayerState.EMPTY, exploration.state(PLAYER));

        Map<Identifier, ExplorationPlayerState.DiscoveryReceipt> full = new java.util.TreeMap<>();
        for (int index = 0; index < ExplorationPlayerState.MAX_DISCOVERIES; index++) {
            full.put(id("existing_" + index), new ExplorationPlayerState.DiscoveryReceipt(
                    1, index + 1L, uuid(index + 100), Optional.empty()));
        }
        ExplorationPlayerState fullState = new ExplorationPlayerState(full);
        assertTrue(exploration.commit(PLAYER, ExplorationPlayerState.EMPTY, fullState));
        assertEquals(ExplorationDiscoveryService.Status.STATE_FULL,
                observe(exploration, definitions, rpg, WorldTopology.HUB, BlockPos.ZERO, NOW).status());
        assertEquals(fullState, exploration.state(PLAYER));
    }

    @Test
    void overflowLeavesCapturedRewardPending() {
        Identifier discovery = id("overflow_reward");
        var exploration = new ExplorationPlayerSavedData();
        var rpg = new RpgPlayerSavedData();
        var maxed = new RpgPlayerState(
                Map.of(ExplorationDiscoveryService.EXPLORATION_ACTIVITY, ActivityDefinition.MAX_XP),
                Map.of(), Optional.empty(), Map.of(), Map.of(), Set.of(), List.of(), List.of(), 0);
        CompoundTag encoded = new CompoundTag();
        encoded.putInt("schema_version", RpgPlayerSavedData.CURRENT_SCHEMA_VERSION);
        CompoundTag playerEntry = new CompoundTag();
        playerEntry.putString("id", PLAYER.toString());
        playerEntry.put("state", RpgPlayerState.CODEC.encodeStart(NbtOps.INSTANCE, maxed).getOrThrow());
        ListTag players = new ListTag();
        players.add(playerEntry);
        encoded.put("players", players);
        rpg = RpgPlayerSavedData.CODEC.parse(NbtOps.INSTANCE, encoded).getOrThrow();

        var result = observe(exploration,
                definitions(discovery, 1, BlockPos.ZERO, 4, false, Optional.of(1L)),
                rpg, WorldTopology.HUB, BlockPos.ZERO, NOW);

        assertEquals(ExplorationDiscoveryService.Status.REWARD_PENDING, result.status());
        assertEquals(ExplorationPlayerState.RewardOperation.Phase.CAPTURED,
                exploration.state(PLAYER).discovery(discovery).orElseThrow()
                        .rewardOperation().orElseThrow().phase());
        assertEquals(ActivityDefinition.MAX_XP, rpg.state(PLAYER).activityXp()
                .get(ExplorationDiscoveryService.EXPLORATION_ACTIVITY));
    }

    @Test
    void waypointCoordinatesRequirePublicGuidanceOrCurrentReceipt() {
        Identifier privateId = id("private_place");
        var privateDefinitions = definitions(privateId, 2, new BlockPos(9, 70, 9), 4, false, Optional.empty());
        var oldReceipt = new ExplorationPlayerState.DiscoveryReceipt(1, NOW, uuid(50), Optional.empty());

        assertTrue(ExplorationDiscoveryService.waypoint(
                privateDefinitions, ExplorationPlayerState.EMPTY, privateId).isEmpty());
        assertTrue(ExplorationDiscoveryService.waypoint(
                privateDefinitions, new ExplorationPlayerState(Map.of(privateId, oldReceipt)), privateId).isEmpty());

        var current = oldReceipt.atVersion(2);
        assertEquals(new BlockPos(9, 70, 9), ExplorationDiscoveryService.waypoint(
                privateDefinitions, new ExplorationPlayerState(Map.of(privateId, current)), privateId)
                .orElseThrow().position());
        var publicDefinitions = definitions(privateId, 2, new BlockPos(9, 70, 9), 4, true, Optional.empty());
        assertTrue(ExplorationDiscoveryService.waypoint(
                publicDefinitions, ExplorationPlayerState.EMPTY, privateId).isPresent());
    }

    private static ExplorationDiscoveryService.ObservationResult observe(
            ExplorationPlayerSavedData exploration,
            ExplorationDefinitionSnapshot definitions,
            RpgPlayerSavedData rpg,
            net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension,
            BlockPos position,
            long timestamp) {
        return ExplorationDiscoveryService.observe(
                exploration, definitions, rpg, rpgDefinitions(), PLAYER,
                dimension, position, timestamp, NOW);
    }

    private static ExplorationDefinitionSnapshot definitions(
            Identifier id, int version, BlockPos position, int radius,
            boolean publicGuidance, Optional<Long> xp) {
        var definition = new ExplorationDefinition(
                "discovery.rovenfall." + id.getPath(),
                "discovery.rovenfall." + id.getPath() + ".description",
                version, WorldTopology.WILDERNESS, position, radius, publicGuidance, xp);
        if (id.getPath().equals("rejected") || id.getPath().equals("overflow_reward")
                || id.getPath().equals("pending_reward")) {
            definition = new ExplorationDefinition(
                    definition.titleTranslationKey(), definition.descriptionTranslationKey(), version,
                    WorldTopology.HUB, position, radius, publicGuidance, xp);
        }
        return ExplorationDefinitionSnapshot.compile(List.of(new ExplorationDefinitionSnapshot.Source(
                id("rovenfall/discoveries/" + id.getPath() + ".json"), "test", id, definition)));
    }

    private static RpgDefinitionSnapshot rpgDefinitions() {
        return RpgDefinitionSnapshot.compile(List.of(new RpgDefinitionSnapshot.ActivitySource(
                id("rovenfall/activities/exploration.json"), "test",
                ExplorationDiscoveryService.EXPLORATION_ACTIVITY,
                new ActivityDefinition("activity.rovenfall.exploration", List.of(100L)))),
                List.of(), List.of());
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("rovenfall", path);
    }

    private static UUID uuid(long value) {
        return new UUID(0L, value);
    }
}
