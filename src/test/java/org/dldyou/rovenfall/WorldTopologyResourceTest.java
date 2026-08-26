package org.dldyou.rovenfall;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

final class WorldTopologyResourceTest {
    @Test
    void wildernessShipsAsAnOverworldNoiseDimension() throws Exception {
        var stream = WorldTopologyResourceTest.class.getResourceAsStream(
                "/data/rovenfall/dimension/wilderness.json");
        assertNotNull(stream);
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            var root = JsonParser.parseReader(reader).getAsJsonObject();
            assertEquals("minecraft:overworld", root.get("type").getAsString());
            var generator = root.getAsJsonObject("generator");
            assertEquals("minecraft:noise", generator.get("type").getAsString());
            assertEquals("minecraft:overworld", generator.get("settings").getAsString());
            assertEquals("minecraft:overworld",
                    generator.getAsJsonObject("biome_source").get("preset").getAsString());
        }
    }
}
