package org.dldyou.rovenfall.rpg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.util.List;
import java.util.Optional;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

final class RpgDefinitionStoreTest {
    @Test
    void publishesOneImmutableBranchingSnapshot() {
        var store = new RpgDefinitionStore();

        RpgDefinitionSnapshot snapshot = store.replace(
                List.of(activity("combat")),
                List.of(
                        career("novice", 1, List.of(), List.of()),
                        career("warrior", 2, List.of(id("novice")), List.of(requirement("combat", 2))),
                        career("guardian", 3, List.of(id("warrior")), List.of(requirement("combat", 2))),
                        career("berserker", 3, List.of(id("warrior")), List.of(requirement("combat", 2)))),
                List.of(
                        skill("foundation", "novice", SkillDefinition.Kind.PASSIVE, List.of()),
                        skill("strike", "warrior", SkillDefinition.Kind.ACTIVE,
                                List.of(prerequisite("foundation", 1)))));

        assertSame(snapshot, store.current());
        assertEquals(3, snapshot.career(id("guardian")).orElseThrow().tier());
        assertEquals(SkillDefinition.Kind.ACTIVE, snapshot.skill(id("strike")).orElseThrow().kind());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.activities().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.career(id("warrior")).orElseThrow().parents().clear());
    }

    @Test
    void failedReplacementPreservesTheLastGoodSnapshot() {
        var store = new RpgDefinitionStore();
        RpgDefinitionSnapshot previous = store.replace(
                List.of(activity("combat")),
                List.of(career("novice", 1, List.of(), List.of())),
                List.of());

        var error = assertThrows(RpgDefinitionSnapshot.ValidationException.class, () -> store.replace(
                List.of(activity("combat")),
                List.of(career("broken", 2, List.of(id("missing")), List.of())),
                List.of()));

        assertSame(previous, store.current());
        assertTrue(error.getMessage().contains("missing parent career"));
    }

    @Test
    void rejectsDuplicatesAndEveryMissingReferenceKindWithSourceEvidence() {
        var error = assertThrows(RpgDefinitionSnapshot.ValidationException.class, () ->
                RpgDefinitionSnapshot.compile(
                        List.of(activity("one", "combat"), activity("two", "combat")),
                        List.of(career("broken", 2, List.of(id("missing_parent")),
                                List.of(requirement("missing_activity", 1)))),
                        List.of(skill("broken", "missing_career", SkillDefinition.Kind.PASSIVE,
                                List.of(prerequisite("missing_skill", 1))))));

        assertTrue(error.getMessage().contains("duplicate activity definition ID"));
        assertTrue(error.getMessage().contains("one.json"));
        assertTrue(error.getMessage().contains("two.json"));
        assertTrue(error.getMessage().contains("missing parent career"));
        assertTrue(error.getMessage().contains("missing activity"));
        assertTrue(error.getMessage().contains("missing career"));
        assertTrue(error.getMessage().contains("missing skill prerequisite"));
    }

    @Test
    void rejectsCareerAndSkillCycles() {
        var error = assertThrows(RpgDefinitionSnapshot.ValidationException.class, () ->
                RpgDefinitionSnapshot.compile(
                        List.of(activity("combat")),
                        List.of(
                                career("one", 1, List.of(id("two")), List.of()),
                                career("two", 2, List.of(id("one")), List.of())),
                        List.of(
                                skill("alpha", "one", SkillDefinition.Kind.PASSIVE,
                                        List.of(prerequisite("beta", 1))),
                                skill("beta", "one", SkillDefinition.Kind.PASSIVE,
                                        List.of(prerequisite("alpha", 1))))));

        assertTrue(error.problems().stream().anyMatch(problem -> problem.cause().equals("career dependency cycle")));
        assertTrue(error.problems().stream().anyMatch(problem -> problem.cause().equals("skill dependency cycle")));
    }

    @Test
    void rejectsInvalidCostsBoundsCurvesAndLocalization() {
        var invalidActivity = new RpgDefinitionSnapshot.ActivitySource(
                file("activity"), "test", id("activity"),
                new ActivityDefinition("Invalid Key", List.of(100L, 100L)));
        var invalidCareer = new RpgDefinitionSnapshot.CareerSource(
                file("career"), "test", id("career"),
                new CareerDefinition("career.rovenfall.invalid", 0, List.of(), List.of(100L), -1, List.of()));
        var invalidSkill = new RpgDefinitionSnapshot.SkillSource(
                file("skill"), "test", id("skill"),
                new SkillDefinition("skill.rovenfall.invalid", id("career"), SkillDefinition.Kind.ACTIVE,
                        0, 0, List.of(), Optional.empty()));

        var error = assertThrows(RpgDefinitionSnapshot.ValidationException.class, () ->
                RpgDefinitionSnapshot.compile(List.of(invalidActivity), List.of(invalidCareer), List.of(invalidSkill)));

        assertTrue(error.getMessage().contains("invalid translation key"));
        assertTrue(error.getMessage().contains("strictly increasing"));
        assertTrue(error.getMessage().contains("promotion cost"));
        assertTrue(error.getMessage().contains("point cost"));
        assertTrue(error.getMessage().contains("active skill requires cooldown_ticks"));
    }

    @Test
    void codecsRejectUnsafeNumericInput() {
        var activity = ActivityDefinition.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("""
                {"translation_key":"activity.rovenfall.invalid","level_xp":[0]}
                """));
        var career = CareerDefinition.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("""
                {"translation_key":"career.rovenfall.invalid","tier":1,"level_xp":[100],"promotion_cost":-1}
                """));
        var skill = SkillDefinition.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("""
                {"translation_key":"skill.rovenfall.invalid","career":"rovenfall:novice","kind":"passive",
                 "max_rank":1,"point_cost":0}
                """));

        assertFalse(activity.isSuccess());
        assertFalse(career.isSuccess());
        assertFalse(skill.isSuccess());
    }

    @Test
    void rejectsPrerequisiteOutsideCareerLineage() {
        var error = assertThrows(RpgDefinitionSnapshot.ValidationException.class, () ->
                RpgDefinitionSnapshot.compile(
                        List.of(activity("combat")),
                        List.of(
                                career("novice", 1, List.of(), List.of()),
                                career("warrior", 2, List.of(id("novice")), List.of()),
                                career("farmer", 2, List.of(id("novice")), List.of())),
                        List.of(
                                skill("harvest", "farmer", SkillDefinition.Kind.PASSIVE, List.of()),
                                skill("strike", "warrior", SkillDefinition.Kind.ACTIVE,
                                        List.of(prerequisite("harvest", 1))))));

        assertTrue(error.getMessage().contains("outside the career lineage"));
    }

    private static RpgDefinitionSnapshot.ActivitySource activity(String path) {
        return activity(path, path);
    }

    private static RpgDefinitionSnapshot.ActivitySource activity(String filePath, String id) {
        return new RpgDefinitionSnapshot.ActivitySource(
                file(filePath), "test", id(id),
                new ActivityDefinition("activity.rovenfall." + id, List.of(100L, 300L)));
    }

    private static RpgDefinitionSnapshot.CareerSource career(
            String path,
            int tier,
            List<Identifier> parents,
            List<CareerDefinition.ActivityRequirement> requirements) {
        return new RpgDefinitionSnapshot.CareerSource(
                file(path), "test", id(path),
                new CareerDefinition("career.rovenfall." + path, tier, parents, List.of(100L, 300L), 10, requirements));
    }

    private static RpgDefinitionSnapshot.SkillSource skill(
            String path,
            String career,
            SkillDefinition.Kind kind,
            List<SkillDefinition.Prerequisite> prerequisites) {
        return new RpgDefinitionSnapshot.SkillSource(
                file(path), "test", id(path),
                new SkillDefinition("skill.rovenfall." + path, id(career), kind, 3, 1, prerequisites,
                        kind == SkillDefinition.Kind.ACTIVE ? Optional.of(100) : Optional.empty()));
    }

    private static CareerDefinition.ActivityRequirement requirement(String activity, int level) {
        return new CareerDefinition.ActivityRequirement(id(activity), level);
    }

    private static SkillDefinition.Prerequisite prerequisite(String skill, int rank) {
        return new SkillDefinition.Prerequisite(id(skill), rank);
    }

    private static Identifier file(String path) {
        return Identifier.fromNamespaceAndPath("rovenfall", "rovenfall/rpg/" + path + ".json");
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("rovenfall", path);
    }
}
