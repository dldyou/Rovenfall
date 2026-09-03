package org.dldyou.rovenfall.activities;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ActivityLevelDefinitionTest {
    @Test
    void progressUsesCumulativeStrictThresholds() {
        var definition = new ActivityLevelDefinition(ActivityTrack.MINING, List.of(0L, 100L, 300L));
        var initial = definition.progress(0);
        assertEquals(0, initial.level());
        assertEquals(0, initial.experienceIntoLevel());
        assertEquals(100, initial.experienceForNextLevel());
        assertFalse(initial.maximum());

        var levelOne = definition.progress(175);
        assertEquals(1, levelOne.level());
        assertEquals(75, levelOne.experienceIntoLevel());
        assertEquals(200, levelOne.experienceForNextLevel());
        assertFalse(levelOne.maximum());

        var maximum = definition.progress(999);
        assertEquals(2, maximum.level());
        assertEquals(2, maximum.maximumLevel());
        assertEquals(699, maximum.experienceIntoLevel());
        assertEquals(0, maximum.experienceForNextLevel());
        assertTrue(maximum.maximum());
    }

    @Test
    void codecRejectsMissingZeroAndNonIncreasingThresholds() {
        var missingZero = JsonParser.parseString("""
                {"track":"combat","thresholds":[1,100]}
                """);
        assertTrue(ActivityLevelDefinition.CODEC.parse(JsonOps.INSTANCE, missingZero).error().isPresent());
        var duplicate = JsonParser.parseString("""
                {"track":"combat","thresholds":[0,100,100]}
                """);
        assertTrue(ActivityLevelDefinition.CODEC.parse(JsonOps.INSTANCE, duplicate).error().isPresent());
        var descending = JsonParser.parseString("""
                {"track":"combat","thresholds":[0,100,99]}
                """);
        assertTrue(ActivityLevelDefinition.CODEC.parse(JsonOps.INSTANCE, descending).error().isPresent());
    }

    @Test
    void everyBundledTrackHasAValidCurve() throws Exception {
        for (ActivityTrack track : ActivityTrack.values()) {
            String path = "/data/rovenfall/rovenfall/activity_levels/"
                    + track.getSerializedName() + ".json";
            var stream = getClass().getResourceAsStream(path);
            assertNotNull(stream, path);
            try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                var definition = ActivityLevelDefinition.CODEC.parse(
                        JsonOps.INSTANCE, JsonParser.parseReader(reader)).getOrThrow();
                assertEquals(track, definition.track());
                assertEquals(10, definition.progress(30_000).level());
            }
        }
    }
}
