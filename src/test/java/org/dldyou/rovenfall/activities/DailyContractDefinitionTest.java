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

final class DailyContractDefinitionTest {
    @Test
    void bundledContractsAreValidAndCoverDailyWildernessActivities() throws Exception {
        Map<String, Long> expectedRewards = Map.ofEntries(
                Map.entry("iron_rush", 80L),
                Map.entry("harvest_rations", 90L),
                Map.entry("camp_provisions", 90L),
                Map.entry("zombie_cull", 120L),
                Map.entry("bone_patrol", 120L),
                Map.entry("ashen_pursuit", 180L),
                Map.entry("rune_breaker", 190L),
                Map.entry("mirefang_hunt", 170L),
                Map.entry("cinder_containment", 220L),
                Map.entry("frozen_front", 200L),
                Map.entry("sunken_patrol", 210L),
                Map.entry("depths_watch", 220L),
                Map.entry("frontier_feast", 130L),
                Map.entry("highland_herd", 140L),
                Map.entry("highland_provisions", 150L),
                Map.entry("warden_trial", 300L));

        for (var entry : expectedRewards.entrySet()) {
            DailyContractDefinition definition = bundled(entry.getKey());
            assertEquals(entry.getValue().longValue(), definition.currencyReward(), entry.getKey());
            assertTrue(definition.translationKey().endsWith(entry.getKey()), entry.getKey());
            assertTrue(definition.descriptionTranslationKey().endsWith(entry.getKey()), entry.getKey());
        }

        assertEquals(ActivityKind.NATURAL_RESOURCE_BREAK, bundled("iron_rush").kind());
        assertEquals(Identifier.withDefaultNamespace("iron_ore"), bundled("iron_rush").targetId());
        assertEquals(ActivityKind.HUNTING_CONTRIBUTION, bundled("ashen_pursuit").kind());
        assertEquals(160, bundled("ashen_pursuit").requiredExperience());
        assertEquals(id("runebound_archer"), bundled("rune_breaker").targetId());
        assertEquals(160, bundled("rune_breaker").requiredExperience());
        assertEquals(id("mirefang"), bundled("mirefang_hunt").targetId());
        assertEquals(240, bundled("cinder_containment").requiredExperience());
        assertEquals(id("frostbound_reaver"), bundled("frozen_front").targetId());
        assertEquals(180, bundled("frozen_front").requiredExperience());
        assertEquals(id("tidebound_raider"), bundled("sunken_patrol").targetId());
        assertEquals(210, bundled("sunken_patrol").requiredExperience());
        assertEquals(id("deepstone_husk"), bundled("depths_watch").targetId());
        assertEquals(210, bundled("depths_watch").requiredExperience());
        assertEquals(ActivityKind.COOKING_RESULT, bundled("frontier_feast").kind());
        assertEquals(id("frontier_stew"), bundled("frontier_feast").targetId());
        assertEquals(40, bundled("frontier_feast").requiredExperience());
        assertEquals(ActivityKind.BREEDING_COMPLETION, bundled("highland_herd").kind());
        assertEquals(Identifier.withDefaultNamespace("goat"), bundled("highland_herd").targetId());
        assertEquals(48, bundled("highland_herd").requiredExperience());
        assertEquals(ActivityKind.COOKING_RESULT, bundled("highland_provisions").kind());
        assertEquals(id("highland_cheese"), bundled("highland_provisions").targetId());
        assertEquals(48, bundled("highland_provisions").requiredExperience());
        assertEquals(id("arena_warden"), bundled("warden_trial").targetId());
        assertEquals(80, bundled("warden_trial").requiredExperience());
    }

    @Test
    void codecRejectsNonRepeatableDiscoveryAndInvalidAmounts() {
        for (String json : List.of(
                """
                {
                  "translation_key": "daily_contract.rovenfall.invalid",
                  "description_translation_key": "daily_contract_description.rovenfall.invalid",
                  "kind": "exploration_discovery",
                  "target": "minecraft:plains",
                  "required_experience": 1,
                  "currency_reward": 10
                }
                """,
                """
                {
                  "translation_key": "daily_contract.rovenfall.invalid",
                  "description_translation_key": "daily_contract_description.rovenfall.invalid",
                  "kind": "natural_resource_break",
                  "target": "minecraft:iron_ore",
                  "required_experience": 0,
                  "currency_reward": 10
                }
                """,
                """
                {
                  "translation_key": "daily_contract.rovenfall.invalid",
                  "description_translation_key": "daily_contract_description.rovenfall.invalid",
                  "kind": "natural_resource_break",
                  "target": "minecraft:iron_ore",
                  "required_experience": 10,
                  "currency_reward": 0
                }
                """)) {
            assertTrue(DailyContractDefinition.CODEC.parse(
                    JsonOps.INSTANCE, JsonParser.parseString(json)).error().isPresent());
        }
    }

    private DailyContractDefinition bundled(String name) throws Exception {
        String path = "/data/rovenfall/rovenfall/daily_contracts/" + name + ".json";
        var stream = getClass().getResourceAsStream(path);
        assertNotNull(stream, path);
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return DailyContractDefinition.CODEC.parse(
                    JsonOps.INSTANCE, JsonParser.parseReader(reader)).getOrThrow();
        }
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("rovenfall", path);
    }
}
