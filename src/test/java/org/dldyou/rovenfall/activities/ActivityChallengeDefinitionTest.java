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
import org.junit.jupiter.api.Test;

final class ActivityChallengeDefinitionTest {
    @Test
    void bundledChallengesAreValidAndProvideAProgressionArc() throws Exception {
        Map<String, Long> expectedRewards = Map.of(
                "first_steps", 100L,
                "deep_delver", 150L,
                "homestead", 200L,
                "monster_hunter", 250L,
                "wilderness_veteran", 500L,
                "master_of_trades", 750L);

        for (var entry : expectedRewards.entrySet()) {
            ActivityChallengeDefinition definition = bundled(entry.getKey());
            assertEquals(entry.getValue().longValue(), definition.currencyReward(), entry.getKey());
            assertTrue(definition.translationKey().endsWith(entry.getKey()), entry.getKey());
            assertTrue(definition.descriptionTranslationKey().endsWith(entry.getKey()), entry.getKey());
        }

        assertEquals(7, bundled("master_of_trades").activityLevelRequirements().size());
        assertEquals(5, bundled("wilderness_veteran")
                .activityLevelRequirements().get(ActivityTrack.EXPLORATION));
    }

    @Test
    void codecRejectsMissingRequirementsAndInvalidRewards() {
        for (String json : List.of(
                """
                {
                  "translation_key": "activity_challenge.rovenfall.invalid",
                  "description_translation_key": "activity_challenge_description.rovenfall.invalid",
                  "activity_levels": {},
                  "currency_reward": 100
                }
                """,
                """
                {
                  "translation_key": "activity_challenge.rovenfall.invalid",
                  "description_translation_key": "activity_challenge_description.rovenfall.invalid",
                  "activity_levels": {"mining": 1},
                  "currency_reward": 0
                }
                """)) {
            assertTrue(ActivityChallengeDefinition.CODEC.parse(
                    JsonOps.INSTANCE, JsonParser.parseString(json)).error().isPresent());
        }
    }

    private ActivityChallengeDefinition bundled(String name) throws Exception {
        String path = "/data/rovenfall/rovenfall/activity_challenges/" + name + ".json";
        var stream = getClass().getResourceAsStream(path);
        assertNotNull(stream, path);
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return ActivityChallengeDefinition.CODEC.parse(
                    JsonOps.INSTANCE, JsonParser.parseReader(reader)).getOrThrow();
        }
    }
}
