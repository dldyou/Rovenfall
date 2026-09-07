package org.dldyou.rovenfall.mobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

final class RuneStrikeDefinitionTest {
    @Test
    void bundledSentinelHasValidatedTelegraphRecoveryAndWildernessLoot() throws Exception {
        try (var reader = new InputStreamReader(getClass().getResourceAsStream(
                "/data/rovenfall/rovenfall/mob_content/rune_sentinel.json"), StandardCharsets.UTF_8)) {
            var catalog = MobContentCatalog.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseReader(reader)).getOrThrow();
            var mob = catalog.mobs().getFirst();
            var strike = mob.runeStrike().orElseThrow();
            assertEquals(28, strike.windupTicks());
            assertEquals(24, strike.recoveryTicks());
            assertEquals(100, strike.cooldownTicks());
            assertEquals("rovenfall:wilderness", mob.spawn().orElseThrow().dimension().identifier().toString());
            assertEquals(catalog.loot().getFirst().id(), mob.loot());
            assertEquals(0, catalog.loot().getFirst().currency(), "Currency comes from contribution-based daily tasks");
            assertEquals(catalog, MobContentCatalog.CODEC.parse(JsonOps.INSTANCE,
                    MobContentCatalog.CODEC.encodeStart(JsonOps.INSTANCE, catalog).getOrThrow()).getOrThrow());
            assertEquals(2, MobContentSnapshot.compile(List.of(new MobContentSnapshot.Source(
                    mob.id(), "test", mob.id(), catalog))).size());
        }
    }

    @Test
    void invalidTimingsNonfiniteNumbersAndExcessiveAreaAreRejected() {
        var valid = new MobContentCatalog.RuneStrike(28, 24, 100, 8, 1.75, 7);
        var json = MobContentCatalog.RuneStrike.CODEC.encodeStart(JsonOps.INSTANCE, valid).getOrThrow().getAsJsonObject();
        for (String field : List.of("windup_ticks", "recovery_ticks", "cooldown_ticks", "range", "radius", "damage")) {
            var invalid = json.deepCopy();
            invalid.addProperty(field, -1);
            assertTrue(MobContentCatalog.RuneStrike.CODEC.parse(JsonOps.INSTANCE, invalid).error().isPresent(), field);
        }
        assertTrue(MobContentCatalog.RuneStrike.validate(
                new MobContentCatalog.RuneStrike(28, 24, 100, Double.NaN, 1, 7)).error().isPresent());
        assertTrue(MobContentCatalog.RuneStrike.validate(
                new MobContentCatalog.RuneStrike(28, 24, 100, 2, 3, 7)).error().isPresent());
    }
}
