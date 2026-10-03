package org.dldyou.rovenfall.careers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.resources.Identifier;
import org.dldyou.rovenfall.activities.ActivityTrack;
import org.junit.jupiter.api.Test;

final class CareerCombatSkillServiceTest {
    private static final Identifier CAREER = id("guardian");
    private static final Identifier OFFENSE = id("offense");
    private static final Identifier DEFENSE = id("defense");

    @Test
    void appliesActiveLineageOffenseThenDefenseWithinBounds() {
        CareerCatalog catalog = catalog(1_000, 2_000);
        PlayerCareerState attacker = state(Map.of(OFFENSE, 2));
        PlayerCareerState defender = state(Map.of(DEFENSE, 2));

        assertEquals(7.2F, CareerCombatSkillService.modifyDamage(catalog, attacker, defender, 10.0F), 0.001F);
        assertEquals(10.0F, CareerCombatSkillService.modifyDamage(null, attacker, defender, 10.0F));
        assertEquals(Float.NaN, CareerCombatSkillService.modifyDamage(catalog, attacker, defender, Float.NaN));
    }

    @Test
    void combatBonusesAreCappedAndInactiveCareersDoNotApply() {
        CareerCatalog catalog = catalog(5_000, 5_000);
        PlayerCareerState ranked = state(Map.of(OFFENSE, 100, DEFENSE, 100));
        PlayerCareerState inactive = new PlayerCareerState(Optional.empty(), ranked.progressByCareer());

        assertEquals(7_500, Math.round(CareerCombatSkillService.modifyDamage(catalog, ranked, ranked, 10_000)));
        assertEquals(10.0F, CareerCombatSkillService.modifyDamage(catalog, inactive, inactive, 10.0F));
        assertTrue(CareerSkillEffect.validate(new CareerSkillEffect(
                CareerSkillEffect.Type.OUTGOING_DAMAGE_BONUS,
                Optional.of(ActivityTrack.COMBAT), 100)).error().isPresent());
    }

    private static CareerCatalog catalog(int offense, int defense) {
        var offenseSkill = new CareerSkillDefinition("career_skill.rovenfall.offense", List.of(), 100, 1,
                CareerSkillDefinition.Scope.CAREER,
                List.of(new CareerSkillEffect(CareerSkillEffect.Type.OUTGOING_DAMAGE_BONUS,
                        Optional.empty(), offense)));
        var defenseSkill = new CareerSkillDefinition("career_skill.rovenfall.defense", List.of(), 100, 1,
                CareerSkillDefinition.Scope.CAREER,
                List.of(new CareerSkillEffect(CareerSkillEffect.Type.INCOMING_DAMAGE_REDUCTION,
                        Optional.empty(), defense)));
        var career = new CareerDefinition("career.rovenfall.guardian", 1, List.of(), Map.of(),
                List.of(ActivityTrack.COMBAT), List.of(0L, 100L), 0,
                Map.of(OFFENSE, offenseSkill, DEFENSE, defenseSkill), 0, 0);
        return CareerCatalog.create(Map.of(CAREER, career)).getOrThrow();
    }

    private static PlayerCareerState state(Map<Identifier, Integer> ranks) {
        int spent = ranks.values().stream().mapToInt(Integer::intValue).sum();
        return new PlayerCareerState(Optional.of(CAREER),
                Map.of(CAREER, new CareerProgress(0, spent, spent, ranks)));
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("rovenfall", path);
    }
}
