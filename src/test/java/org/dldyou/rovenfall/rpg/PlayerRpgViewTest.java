package org.dldyou.rovenfall.rpg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

final class PlayerRpgViewTest {
    private static final Identifier COMBAT = id("combat");
    private static final Identifier NOVICE = id("novice");
    private static final Identifier WARRIOR = id("warrior");
    private static final Identifier STURDY = id("sturdy_body");
    private static final Identifier STRIKE = id("power_strike");

    @Test
    void projectionKeepsUnresolvedStateAndExplainsDataDrivenLocks() {
        Identifier removedActivity = id("removed_activity");
        Identifier removedCareer = id("removed_career");
        Identifier removedSkill = id("removed_skill");
        RpgDefinitionSnapshot definitions = definitions();
        RpgPlayerState state = new RpgPlayerState(
                Map.of(COMBAT, 100L, removedActivity, 77L),
                Map.of(
                        NOVICE, new RpgPlayerState.CareerProgress(100, 1, 0, Map.of(STURDY, 1)),
                        removedCareer, new RpgPlayerState.CareerProgress(9, 1, 0, Map.of(removedSkill, 1))),
                Optional.of(NOVICE),
                Map.of(0, removedSkill),
                Map.of(removedSkill, 120L),
                Set.of(), List.of(), List.of());

        PlayerRpgView view = PlayerRpgView.create(definitions, state, 7, 20, 100);

        assertTrue(view.activities().stream().anyMatch(row -> row.id().equals(removedActivity) && row.unresolved()));
        assertTrue(view.careers().stream().anyMatch(row -> row.id().equals(removedCareer) && row.unresolved()));
        assertTrue(view.skills().stream().anyMatch(row -> row.id().equals(removedSkill) && row.unresolved()));
        assertTrue(view.slots().getFirst().unresolved());
        assertEquals(20, view.slots().getFirst().cooldownTicks());

        PlayerRpgView.CareerRow warrior = view.careers().stream()
                .filter(row -> row.id().equals(WARRIOR)).findFirst().orElseThrow();
        assertEquals(PlayerRpgView.LockReason.PARENT_RANK, warrior.lock().reason());
        assertEquals(2, warrior.lock().required());
        assertEquals(1, warrior.lock().actual());

        PlayerRpgView.SkillRow strike = view.skills().stream()
                .filter(row -> row.id().equals(STRIKE)).findFirst().orElseThrow();
        assertEquals(PlayerRpgView.LockReason.CAREER_NOT_PROMOTED, strike.lock().reason());
        assertFalse(strike.activeLineage());
    }

    @Test
    void projectionShowsAllFourSlotsAndTheActiveCareerLineage() {
        RpgPlayerState state = new RpgPlayerState(
                Map.of(COMBAT, 300L),
                Map.of(
                        NOVICE, new RpgPlayerState.CareerProgress(200, 2, 2, Map.of(STURDY, 2)),
                        WARRIOR, new RpgPlayerState.CareerProgress(0, 0, 0, Map.of())),
                Optional.of(WARRIOR), Map.of(), Map.of(), Set.of(), List.of(), List.of());

        PlayerRpgView view = PlayerRpgView.create(definitions(), state, 3, 1_000, 0);

        assertEquals(4, view.slots().size());
        assertEquals(Set.of(NOVICE, WARRIOR), view.activeLineage());
        assertTrue(view.careers().stream().filter(row -> row.id().equals(NOVICE))
                .findFirst().orElseThrow().inActiveLineage());
    }

    private static RpgDefinitionSnapshot definitions() {
        ActivityDefinition combat = new ActivityDefinition("activity.rovenfall.combat", List.of(100L, 300L));
        CareerDefinition novice = new CareerDefinition(
                "career.rovenfall.novice", 1, List.of(), List.of(100L, 200L), 0, List.of());
        CareerDefinition warrior = new CareerDefinition(
                "career.rovenfall.warrior", 2, List.of(NOVICE), List.of(200L), 100,
                List.of(new CareerDefinition.ActivityRequirement(COMBAT, 2)));
        SkillDefinition sturdy = new SkillDefinition(
                "skill.rovenfall.sturdy_body", NOVICE, SkillDefinition.Kind.PASSIVE, 3, 1,
                List.of(), Optional.empty(),
                Optional.of(new SkillDefinition.PassiveEffect(
                        SkillDefinition.EffectType.DAMAGE_TAKEN_REDUCTION, 100)));
        SkillDefinition strike = new SkillDefinition(
                "skill.rovenfall.power_strike", WARRIOR, SkillDefinition.Kind.ACTIVE, 3, 2,
                List.of(new SkillDefinition.Prerequisite(STURDY, 2)), Optional.of(100), Optional.empty(),
                Optional.of(new SkillDefinition.ActiveEffect(
                        SkillDefinition.EffectType.DAMAGE_DEALT, SkillDefinition.TargetType.LIVING_ENTITY,
                        100, 20, 4)));
        return RpgDefinitionSnapshot.compile(
                List.of(new RpgDefinitionSnapshot.ActivitySource(id("activities/combat"), "test", COMBAT, combat)),
                List.of(
                        new RpgDefinitionSnapshot.CareerSource(id("careers/novice"), "test", NOVICE, novice),
                        new RpgDefinitionSnapshot.CareerSource(id("careers/warrior"), "test", WARRIOR, warrior)),
                List.of(
                        new RpgDefinitionSnapshot.SkillSource(id("skills/sturdy"), "test", STURDY, sturdy),
                        new RpgDefinitionSnapshot.SkillSource(id("skills/strike"), "test", STRIKE, strike)));
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("rovenfall", path);
    }
}
