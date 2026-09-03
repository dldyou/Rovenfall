package org.dldyou.rovenfall.activities;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

final class ActivityRewardDefinitionTest {
    @Test
    void permanentTrackCatalogIsFixedAndEveryEvidenceKindBelongsToOneTrack() {
        assertEquals(7, ActivityTrack.values().length);
        assertEquals(EnumSet.allOf(ActivityTrack.class), Arrays.stream(ActivityKind.values())
                .map(ActivityKind::track)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(ActivityTrack.class))));
        for (ActivityKind kind : ActivityKind.values()) {
            assertTrue(kind.validationError(kind.track(), provenanceFor(kind)).isEmpty(), kind.getSerializedName());
        }
    }

    @Test
    void rewardCodecRejectsMismatchedTracksAndInvalidBounds() {
        var mismatch = JsonParser.parseString("""
                {
                  "track": "mining",
                  "kind": "exploration_discovery",
                  "target": "minecraft:plains",
                  "experience": 25,
                  "window_millis": 60000,
                  "target_window_cap": 25,
                  "player_window_cap": 100
                }
                """);
        assertTrue(ActivityRewardDefinition.CODEC.parse(JsonOps.INSTANCE, mismatch).error().isPresent());

        var invalidWindow = mismatch.getAsJsonObject().deepCopy();
        invalidWindow.addProperty("track", "exploration");
        invalidWindow.addProperty("window_millis", 999);
        assertTrue(ActivityRewardDefinition.CODEC.parse(JsonOps.INSTANCE, invalidWindow).error().isPresent());

        var capBelowReward = mismatch.getAsJsonObject().deepCopy();
        capBelowReward.addProperty("track", "exploration");
        capBelowReward.addProperty("target_window_cap", 24);
        assertTrue(ActivityRewardDefinition.CODEC.parse(JsonOps.INSTANCE, capBelowReward).error().isPresent());
    }

    @Test
    void bundledPlainsDiscoveryRewardIsValid() throws Exception {
        var stream = getClass().getResourceAsStream(
                "/data/rovenfall/rovenfall/activity_rewards/plains_discovery.json");
        assertNotNull(stream);
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            var definition = ActivityRewardDefinition.CODEC.parse(
                    JsonOps.INSTANCE, JsonParser.parseReader(reader)).getOrThrow();
            assertEquals(ActivityTrack.EXPLORATION, definition.track());
            assertEquals(ActivityKind.EXPLORATION_DISCOVERY, definition.kind());
            assertEquals(30, definition.experience());
        }
    }

    @Test
    void bundledHighlandCheeseRewardUsesCookingResults() throws Exception {
        var definition = bundledReward("highland_cheese");
        assertEquals(ActivityTrack.COOKING, definition.track());
        assertEquals(ActivityKind.COOKING_RESULT, definition.kind());
        assertEquals("rovenfall:highland_cheese", definition.targetId().toString());
        assertEquals(4, definition.experience());
    }

    @Test
    void bundledExpeditionRewardsCoverEveryActivityTrack() throws Exception {
        Map<String, ActivityTrack> representatives = Map.of(
                "stone_bricks_building", ActivityTrack.BUILDING,
                "frontier_stew", ActivityTrack.COOKING,
                "ancient_debris", ActivityTrack.MINING,
                "deep_dark_discovery", ActivityTrack.EXPLORATION,
                "goat_breeding", ActivityTrack.FARMING,
                "ashen_stalker_combat", ActivityTrack.COMBAT,
                "runebound_archer_hunting", ActivityTrack.HUNTING);
        EnumSet<ActivityTrack> loaded = EnumSet.noneOf(ActivityTrack.class);

        for (var entry : representatives.entrySet()) {
            ActivityRewardDefinition definition = bundledReward(entry.getKey());
            assertEquals(entry.getValue(), definition.track(), entry.getKey());
            loaded.add(definition.track());
        }

        assertEquals(EnumSet.allOf(ActivityTrack.class), loaded);
    }

    @Test
    void newWildernessMobsHaveCombatAndHuntingRewards() throws Exception {
        var mirefangCombat = bundledReward("mirefang_combat");
        var mirefangHunting = bundledReward("mirefang_hunting");
        var cinderCombat = bundledReward("cinder_wisp_combat");
        var cinderHunting = bundledReward("cinder_wisp_hunting");
        var frostboundCombat = bundledReward("frostbound_reaver_combat");
        var frostboundHunting = bundledReward("frostbound_reaver_hunting");
        var tideboundCombat = bundledReward("tidebound_raider_combat");
        var tideboundHunting = bundledReward("tidebound_raider_hunting");
        var deepstoneCombat = bundledReward("deepstone_husk_combat");
        var deepstoneHunting = bundledReward("deepstone_husk_hunting");

        assertEquals("rovenfall:mirefang", mirefangCombat.targetId().toString());
        assertEquals(ActivityKind.COMBAT_DAMAGE, mirefangCombat.kind());
        assertEquals(ActivityKind.HUNTING_CONTRIBUTION, mirefangHunting.kind());
        assertEquals("rovenfall:cinder_wisp", cinderCombat.targetId().toString());
        assertEquals(3, cinderCombat.experience());
        assertEquals(ActivityKind.HUNTING_CONTRIBUTION, cinderHunting.kind());
        assertEquals("rovenfall:frostbound_reaver", frostboundCombat.targetId().toString());
        assertEquals(3, frostboundCombat.experience());
        assertEquals(ActivityKind.HUNTING_CONTRIBUTION, frostboundHunting.kind());
        assertEquals("rovenfall:tidebound_raider", tideboundCombat.targetId().toString());
        assertEquals(3, tideboundCombat.experience());
        assertEquals(ActivityKind.HUNTING_CONTRIBUTION, tideboundHunting.kind());
        assertEquals("rovenfall:deepstone_husk", deepstoneCombat.targetId().toString());
        assertEquals(3, deepstoneCombat.experience());
        assertEquals(ActivityKind.HUNTING_CONTRIBUTION, deepstoneHunting.kind());
        assertEquals(ActivityKind.EXPLORATION_DISCOVERY,
                bundledReward("sulfur_caves_discovery").kind());
    }

    private ActivityRewardDefinition bundledReward(String name) throws Exception {
        String path = "/data/rovenfall/rovenfall/activity_rewards/" + name + ".json";
        var stream = getClass().getResourceAsStream(path);
        assertNotNull(stream, path);
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return ActivityRewardDefinition.CODEC.parse(
                    JsonOps.INSTANCE, JsonParser.parseReader(reader)).getOrThrow();
        }
    }

    private static ActivityProvenance provenanceFor(ActivityKind kind) {
        return new ActivityProvenance(
                kind == ActivityKind.NATURAL_RESOURCE_BREAK,
                kind == ActivityKind.MATURE_CROP_HARVEST,
                kind == ActivityKind.EXPLORATION_DISCOVERY);
    }
}
