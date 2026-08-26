package org.dldyou.rovenfall.mobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

final class MobContentSnapshotTest {
    @Test
    void builtInCatalogCompilesToAnImmutableResolvedSnapshot() {
        MobContentSnapshot snapshot = MobContentSnapshot.compile(List.of(source("foundation", builtIn())))
                .validateBoundRegistries();

        assertEquals(11, snapshot.size());
        var boss = snapshot.boss(id("rift_warden")).orElseThrow();
        assertEquals(id("rift_warden_vessel"), boss.mob());
        assertEquals(2, boss.phases().size());
        assertEquals(2, boss.phases().get(1).patterns().size());
        assertTrue(snapshot.arena(boss.arena()).isPresent());
        assertTrue(snapshot.contributionRule(boss.contributionRule()).isPresent());
        assertTrue(snapshot.loot(boss.loot()).isPresent());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.mobs().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.mob(id("grove_stalker")).orElseThrow().behaviorModifiers().clear());
    }

    @Test
    void rejectsMissingReferencesAndInvalidBoundsWithDefinitionEvidence() {
        MobContentCatalog valid = builtIn();
        var boss = valid.bosses().getFirst();
        var invalidBoss = new MobContentCatalog.BossDefinition(
                boss.id(), boss.translationKey(), boss.mob(), boss.arena(), boss.contributionRule(), id("missing_loot"),
                0, List.of(new MobContentCatalog.Phase(
                        boss.phases().getFirst().id(), boss.phases().getFirst().translationKey(), 75,
                        boss.phases().getFirst().patterns())));
        MobContentCatalog invalid = new MobContentCatalog(
                valid.mobs(), valid.mutations(), valid.arenas(), valid.contributionRules(), valid.loot(),
                List.of(invalidBoss));

        var error = assertThrows(MobContentSnapshot.ValidationException.class,
                () -> MobContentSnapshot.compile(List.of(source("invalid", invalid))));

        assertTrue(error.problems().stream().anyMatch(problem -> problem.cause().contains("missing loot reference")));
        assertTrue(error.problems().stream().anyMatch(problem -> problem.cause().contains("reward cooldown")));
        assertTrue(error.problems().stream().anyMatch(problem -> problem.cause().contains("first phase")));
        assertTrue(error.problems().stream().allMatch(problem -> problem.file().equals(file("invalid"))));
    }

    @Test
    void rejectsDuplicateIdsAndInvalidPatternDefinitions() {
        MobContentCatalog valid = builtIn();
        var boss = valid.bosses().getFirst();
        var firstPhase = boss.phases().getFirst();
        var firstPattern = firstPhase.patterns().getFirst();
        var invalidPattern = new MobContentCatalog.PatternDefinition(
                firstPattern.id(), "Invalid Key", id("missing_pattern_type"), 0, firstPattern.durationTicks(),
                firstPattern.cooldownTicks(), 0);
        var invalidBoss = new MobContentCatalog.BossDefinition(
                boss.id(), boss.translationKey(), boss.mob(), boss.arena(), boss.contributionRule(), boss.loot(),
                boss.rewardCooldownTicks(), List.of(new MobContentCatalog.Phase(
                        firstPhase.id(), firstPhase.translationKey(), 100, List.of(firstPattern, invalidPattern))));
        MobContentCatalog invalid = new MobContentCatalog(
                valid.mobs(), valid.mutations(), valid.arenas(), valid.contributionRules(), valid.loot(),
                List.of(invalidBoss));

        var error = assertThrows(MobContentSnapshot.ValidationException.class,
                () -> MobContentSnapshot.compile(List.of(source("invalid_patterns", invalid))));

        assertTrue(error.problems().stream().anyMatch(problem -> problem.cause().contains("duplicate definition ID")));
        assertTrue(error.problems().stream().anyMatch(problem -> problem.cause().contains("invalid translation key")));
        assertTrue(error.problems().stream().anyMatch(problem -> problem.cause().contains("unknown pattern type")));
        assertTrue(error.problems().stream().anyMatch(problem -> problem.cause().contains("telegraph ticks")));
        assertTrue(error.problems().stream().anyMatch(problem -> problem.cause().contains("pattern weight")));
    }

    @Test
    void failedReplacementPreservesTheLastBoundSnapshot() {
        var store = new MobContentStore();
        MobContentCatalog valid = builtIn();
        MobContentSnapshot previous = store.replace(List.of(source("foundation", valid)));
        var mob = valid.mobs().getFirst();
        var unknownEntityMob = new MobContentCatalog.MobDefinition(
                mob.id(), mob.translationKey(), id("missing_entity_type"), mob.maxHealth(), mob.attackDamage(),
                mob.movementSpeed(), mob.behaviorModifiers(), mob.loot());
        var mobs = new java.util.ArrayList<>(valid.mobs());
        mobs.set(0, unknownEntityMob);
        MobContentCatalog invalid = new MobContentCatalog(
                mobs, valid.mutations(), valid.arenas(), valid.contributionRules(), valid.loot(), valid.bosses());

        var error = assertThrows(MobContentSnapshot.ValidationException.class,
                () -> store.replace(List.of(source("unknown_entity", invalid))));

        assertSame(previous, store.current());
        assertTrue(error.problems().stream().anyMatch(problem -> problem.cause().contains("unknown entity type")));
        assertFalse(store.current().mob(id("grove_stalker")).isEmpty());
    }

    private static MobContentCatalog builtIn() {
        String path = "/data/rovenfall/rovenfall/mob_content/foundation.json";
        var stream = MobContentSnapshotTest.class.getResourceAsStream(path);
        if (stream == null) {
            throw new AssertionError(path);
        }
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return MobContentCatalog.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseReader(reader))
                    .getOrThrow(AssertionError::new);
        } catch (java.io.IOException exception) {
            throw new AssertionError(exception);
        }
    }

    private static MobContentSnapshot.Source source(String path, MobContentCatalog catalog) {
        return new MobContentSnapshot.Source(file(path), "test-pack", id(path), catalog);
    }

    private static Identifier file(String path) {
        return Identifier.fromNamespaceAndPath("rovenfall", "rovenfall/mob_content/" + path + ".json");
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("rovenfall", path);
    }
}
