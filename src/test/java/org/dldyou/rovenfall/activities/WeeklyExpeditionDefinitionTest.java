package org.dldyou.rovenfall.activities;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

final class WeeklyExpeditionDefinitionTest {
    @Test
    void bundledExpeditionsAreValidAndReferenceDailyContracts() throws Exception {
        Map<String, Long> expectedRewards = Map.of(
                "supply_lines", 1_000L,
                "threat_control", 1_450L,
                "wilderness_campaign", 3_900L,
                "frontier_anomalies", 1_450L,
                "warden_oath", 1_000L);

        for (var entry : expectedRewards.entrySet()) {
            WeeklyExpeditionDefinition definition = bundled(entry.getKey());
            assertEquals(entry.getValue().longValue(), definition.currencyReward(), entry.getKey());
            assertTrue(definition.translationKey().endsWith(entry.getKey()), entry.getKey());
            assertTrue(definition.descriptionTranslationKey().endsWith(entry.getKey()), entry.getKey());
        }

        var supplyLines = bundled("supply_lines");
        assertEquals(6, supplyLines.dailyContractRequirements().size());
        assertEquals(2, supplyLines.dailyContractRequirements().get(id("iron_rush")));
        assertEquals(2, supplyLines.dailyContractRequirements().get(id("frontier_feast")));
        assertEquals(2, supplyLines.dailyContractRequirements().get(id("highland_herd")));
        assertEquals(2, supplyLines.dailyContractRequirements().get(id("highland_provisions")));
        var campaign = bundled("wilderness_campaign");
        assertEquals(16, campaign.dailyContractRequirements().size());
        assertTrue(campaign.dailyContractRequirements().values().stream().allMatch(value -> value == 2));
        var threatControl = bundled("threat_control");
        assertEquals(9, threatControl.dailyContractRequirements().size());
        assertEquals(1, threatControl.dailyContractRequirements().get(id("rune_breaker")));
        assertEquals(1, threatControl.dailyContractRequirements().get(id("frozen_front")));
        assertEquals(1, threatControl.dailyContractRequirements().get(id("sunken_patrol")));
        assertEquals(1, threatControl.dailyContractRequirements().get(id("depths_watch")));
        assertEquals(5, bundled("frontier_anomalies").dailyContractRequirements().size());
        assertEquals(Map.of(id("warden_trial"), 3),
                bundled("warden_oath").dailyContractRequirements());
    }

    @Test
    void codecRejectsEmptyRequirementsInvalidCountsAndRewards() {
        for (String json : List.of(
                """
                {
                  "translation_key": "weekly_expedition.rovenfall.invalid",
                  "description_translation_key": "weekly_expedition_description.rovenfall.invalid",
                  "daily_contracts": {},
                  "currency_reward": 10
                }
                """,
                """
                {
                  "translation_key": "weekly_expedition.rovenfall.invalid",
                  "description_translation_key": "weekly_expedition_description.rovenfall.invalid",
                  "daily_contracts": {"rovenfall:iron_rush": 8},
                  "currency_reward": 10
                }
                """,
                """
                {
                  "translation_key": "weekly_expedition.rovenfall.invalid",
                  "description_translation_key": "weekly_expedition_description.rovenfall.invalid",
                  "daily_contracts": {"rovenfall:iron_rush": 1},
                  "currency_reward": 0
                }
                """)) {
            assertTrue(WeeklyExpeditionDefinition.CODEC.parse(
                    JsonOps.INSTANCE, JsonParser.parseString(json)).error().isPresent());
        }
    }

    private WeeklyExpeditionDefinition bundled(String name) throws Exception {
        String path = "/data/rovenfall/rovenfall/weekly_expeditions/" + name + ".json";
        var stream = getClass().getResourceAsStream(path);
        assertNotNull(stream, path);
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return WeeklyExpeditionDefinition.CODEC.parse(
                    JsonOps.INSTANCE, JsonParser.parseReader(reader)).getOrThrow();
        }
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("rovenfall", path);
    }
}
