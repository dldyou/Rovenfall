package org.dldyou.rovenfall.careers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.Identifier;
import org.dldyou.rovenfall.activities.ActivityTrack;
import org.junit.jupiter.api.Test;

final class CareerCatalogTest {
    private static final List<String> BUNDLED_CAREERS = List.of(
            "adventurer", "warrior", "artisan", "scout",
            "vanguard", "slayer", "architect", "cultivator", "pathfinder", "ranger");

    @Test
    void validDagSupportsArbitraryTiersMultipleParentsAndLineageQueries() {
        Identifier root = id("root");
        Identifier scout = id("scout");
        Identifier crafter = id("crafter");
        Identifier rangerSmith = id("ranger_smith");
        CareerCatalog catalog = CareerCatalog.create(Map.of(
                root, definition(1, List.of()),
                scout, definition(4, List.of(root)),
                crafter, definition(3, List.of(root)),
                rangerSmith, definition(9, List.of(scout, crafter)))).getOrThrow();

        assertEquals(Set.of(root, scout, crafter), catalog.ancestors(rangerSmith));
        assertEquals(Set.of(scout, crafter, rangerSmith), catalog.descendants(root));
        assertEquals(4, catalog.size());
    }

    @Test
    void catalogRejectsMissingParentsInvalidTierEdgesAndCycles() {
        Identifier root = id("root");
        Identifier child = id("child");
        assertTrue(CareerCatalog.create(Map.of(
                child, definition(2, List.of(id("missing"))))).error().isPresent());
        assertTrue(CareerCatalog.create(Map.of(
                root, definition(2, List.of()),
                child, definition(2, List.of(root)))).error().isPresent());
        assertTrue(CareerCatalog.create(Map.of(
                root, definition(2, List.of(child)),
                child, definition(3, List.of(root)))).error().isPresent());
    }

    @Test
    void siblingSwitchListsOnlyConflictingBranchAndItsDescendants() {
        Identifier root = id("root");
        Identifier warrior = id("warrior");
        Identifier artisan = id("artisan");
        Identifier berserker = id("berserker");
        CareerCatalog catalog = CareerCatalog.create(Map.of(
                root, definition(1, List.of()),
                warrior, definition(2, List.of(root)),
                artisan, definition(2, List.of(root)),
                berserker, definition(3, List.of(warrior)))).getOrThrow();

        assertEquals(Set.of(warrior, berserker), catalog.conflictingLearnedCareers(
                artisan, Set.of(root, warrior, berserker)));
        assertEquals(Set.of(), catalog.conflictingLearnedCareers(
                berserker, Set.of(root, warrior, berserker)));
    }

    @Test
    void bundledCareerGraphDefinesTierTwoBranchesAndTierThreeSpecializations() throws Exception {
        Map<Identifier, CareerDefinition> definitions = new LinkedHashMap<>();
        for (String name : BUNDLED_CAREERS) {
            String path = "/data/rovenfall/rovenfall/professions/" + name + ".json";
            var stream = getClass().getResourceAsStream(path);
            assertNotNull(stream, path);
            try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                definitions.put(id(name), CareerDefinition.CODEC.parse(
                        JsonOps.INSTANCE, JsonParser.parseReader(reader)).getOrThrow());
            }
        }
        CareerCatalog catalog = CareerCatalog.create(definitions).getOrThrow();
        assertEquals(10, catalog.size());
        assertEquals(20, catalog.skillIds().size());
        assertEquals(20, catalog.activeSkillIds().size());
        assertEquals(Identifier.withDefaultNamespace("speed"), catalog.skill(id("well_traveled"))
                .orElseThrow().definition().active().orElseThrow().effectId());
        assertEquals(id("adventurer"), catalog.skill(id("well_traveled")).orElseThrow().careerId());
        assertEquals(Set.of(id("adventurer")), catalog.ancestors(id("warrior")));
        assertEquals(Set.of(id("adventurer")), catalog.ancestors(id("scout")));
        assertEquals(Set.of(id("adventurer"), id("warrior")), catalog.ancestors(id("vanguard")));
        assertEquals(Set.of(id("adventurer"), id("artisan")), catalog.ancestors(id("architect")));
        assertEquals(Set.of(id("adventurer"), id("scout")), catalog.ancestors(id("ranger")));
        assertEquals(Identifier.withDefaultNamespace("night_vision"), catalog.skill(id("keen_senses"))
                .orElseThrow().definition().active().orElseThrow().effectId());
        assertEquals(Identifier.withDefaultNamespace("resistance"), catalog.skill(id("shield_wall"))
                .orElseThrow().definition().active().orElseThrow().effectId());
        assertEquals(Set.of(id("warrior")), catalog.conflictingLearnedCareers(
                id("artisan"), Set.of(id("adventurer"), id("warrior"))));
        assertEquals(Set.of(id("warrior"), id("artisan")), catalog.conflictingLearnedCareers(
                id("scout"), Set.of(id("adventurer"), id("warrior"), id("artisan"))));
        assertEquals(Set.of(id("vanguard")), catalog.conflictingLearnedCareers(
                id("slayer"), Set.of(id("adventurer"), id("warrior"), id("vanguard"))));
        assertEquals(Set.of(id("warrior"), id("slayer")), catalog.conflictingLearnedCareers(
                id("architect"), Set.of(id("adventurer"), id("warrior"), id("slayer"))));
    }

    private static CareerDefinition definition(int tier, List<Identifier> parents) {
        return new CareerDefinition(
                "career.rovenfall.test", tier, parents, Map.of(), List.of(ActivityTrack.EXPLORATION),
                List.of(0L, 100L), 0, Map.of(), 0, 0);
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("rovenfall", path);
    }
}
