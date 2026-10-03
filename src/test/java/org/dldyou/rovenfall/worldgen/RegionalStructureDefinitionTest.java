package org.dldyou.rovenfall.worldgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class RegionalStructureDefinitionTest {
    private static final Map<String, String> STRUCTURES = Map.of(
            "frontier_watchtower", "rovenfall:rift_acolyte",
            "thornback_den", "rovenfall:thornback_stalker",
            "gravebound_chapel", "rovenfall:gravebound_knight");

    @Test
    void regionalStructuresHavePoolsBiomeTagsAndBoundedPlacement() throws Exception {
        for (var entry : STRUCTURES.entrySet()) {
            String name = entry.getKey();
            JsonObject structure = resource("/data/rovenfall/worldgen/structure/" + name + ".json");
            JsonObject set = resource("/data/rovenfall/worldgen/structure_set/" + name + ".json");
            JsonObject pool = resource("/data/rovenfall/worldgen/template_pool/" + name + ".json");
            JsonObject biomes = resource("/data/rovenfall/tags/worldgen/biome/has_structure/" + name + ".json");

            assertEquals("minecraft:jigsaw", structure.get("type").getAsString());
            assertEquals("rovenfall:" + name, structure.get("start_pool").getAsString());
            assertEquals(entry.getValue(), structure.getAsJsonObject("spawn_overrides")
                    .getAsJsonObject("monster").getAsJsonArray("spawns").get(0)
                    .getAsJsonObject().get("type").getAsString());
            int spacing = set.getAsJsonObject("placement").get("spacing").getAsInt();
            int separation = set.getAsJsonObject("placement").get("separation").getAsInt();
            assertTrue(spacing > separation && separation >= 8);
            assertEquals("minecraft:empty", pool.get("fallback").getAsString());
            assertTrue(!pool.getAsJsonArray("elements").isEmpty());
            assertTrue(!biomes.getAsJsonArray("values").isEmpty());
        }
    }

    private JsonObject resource(String path) throws Exception {
        var stream = getClass().getResourceAsStream(path);
        assertNotNull(stream, path);
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }
}
