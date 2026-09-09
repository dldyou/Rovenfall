package org.dldyou.rovenfall.administration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.Identifier;
import org.dldyou.rovenfall.PersistenceTestHarness;
import org.dldyou.rovenfall.activities.ActivityTrack;
import org.dldyou.rovenfall.careers.ActiveSkillState;
import org.dldyou.rovenfall.careers.CareerActiveSkillDefinition;
import org.dldyou.rovenfall.careers.CareerCatalog;
import org.dldyou.rovenfall.careers.CareerDefinition;
import org.dldyou.rovenfall.careers.CareerProgress;
import org.dldyou.rovenfall.careers.CareerSkillDefinition;
import org.dldyou.rovenfall.careers.PlayerCareerState;
import org.junit.jupiter.api.Test;

final class ActiveSkillServiceTest {
    private static final Identifier ROOT = id("root");
    private static final Identifier WARRIOR = id("warrior");
    private static final Identifier SCHOLAR = id("scholar");
    private static final Identifier SKILL_ONE = id("skill_one");
    private static final Identifier SKILL_TWO = id("skill_two");
    private static final Identifier SKILL_THREE = id("skill_three");
    private static final Identifier SKILL_FOUR = id("skill_four");
    private static final Identifier SKILL_FIVE = id("skill_five");

    @Test
    void fourSlotsCooldownResetAndRestartAreServerAuthoritative() {
        CareerCatalog catalog = singleCareerCatalog();
        PlatformSavedData state = new PlatformSavedData();
        UUID player = uuid(1);
        assertEquals(CareerPromotionService.Status.SUCCESS, CareerPromotionService.promote(
                state, catalog, player, ROOT, Map.of(), 1_000, uuid(101)).status());

        assertEquals(ActiveSkillService.Status.SKILL_NOT_UNLOCKED,
                ActiveSkillService.evaluateEquip(state, catalog, player, 1, SKILL_ONE).status());
        unlock(state, catalog, player, SKILL_ONE, 2_000, 102);
        unlock(state, catalog, player, SKILL_TWO, 3_000, 103);
        unlock(state, catalog, player, SKILL_THREE, 4_000, 104);
        unlock(state, catalog, player, SKILL_FOUR, 5_000, 105);

        assertEquals(ActiveSkillService.Status.SUCCESS,
                ActiveSkillService.equip(state, catalog, player, 1, SKILL_ONE, 6_000).status());
        assertEquals(ActiveSkillService.Status.SUCCESS,
                ActiveSkillService.equip(state, catalog, player, 2, SKILL_TWO, 7_000).status());
        assertEquals(ActiveSkillService.Status.SUCCESS,
                ActiveSkillService.equip(state, catalog, player, 3, SKILL_THREE, 8_000).status());
        assertEquals(ActiveSkillService.Status.SUCCESS,
                ActiveSkillService.equip(state, catalog, player, 4, SKILL_FOUR, 9_000).status());
        assertEquals(ActiveSkillService.Status.INVALID_REQUEST,
                ActiveSkillService.evaluateEquip(state, catalog, player, 5, SKILL_FIVE).status());
        assertEquals(ActiveSkillService.Status.ALREADY_EQUIPPED,
                ActiveSkillService.evaluateEquip(state, catalog, player, 2, SKILL_ONE).status());

        long usedAt = 10_000;
        var used = ActiveSkillService.use(
                state, catalog, player, 1, usedAt, ignored -> true);
        assertEquals(ActiveSkillService.Status.SUCCESS, used.status());
        assertEquals(40_000, used.evaluation().readyAtEpochMillis());
        assertEquals(ActiveSkillService.Status.COOLDOWN,
                ActiveSkillService.use(state, catalog, player, 1, usedAt + 1, ignored -> true).status());

        assertEquals(ActiveSkillService.Status.SUCCESS,
                ActiveSkillService.clear(state, player, 1, 12_000).status());
        assertEquals(ActiveSkillService.Status.SUCCESS,
                ActiveSkillService.equip(state, catalog, player, 1, SKILL_ONE, 14_000).status());
        assertEquals(ActiveSkillService.Status.COOLDOWN,
                ActiveSkillService.evaluateUse(
                        state, catalog, player, 1, 15_000, ignored -> true).status());

        PlatformSavedData restarted = PersistenceTestHarness.roundTrip(PlatformSavedData.CODEC, state);
        assertEquals(40_000, restarted.playerCareerState(player)
                .activeSkills().cooldownReadyAt(SKILL_ONE));
        assertEquals(Optional.of(SKILL_FOUR), restarted.playerCareerState(player).activeSkills().slot(4));

        assertEquals(CareerSkillService.Status.SUCCESS,
                CareerSkillService.reset(restarted, catalog, player, ROOT, 41_000, uuid(106)).status());
        assertEquals(Set.of(), restarted.playerCareerState(player).activeSkills().equippedSkills());
        assertTrue(restarted.playerCareerState(player).activeSkills().cooldownReadyAtEpochMillis().isEmpty());
    }

    @Test
    void effectValidationMalformedSlotsAndFutureSchemaNeverConsumeCooldown() {
        CareerCatalog catalog = singleCareerCatalog();
        PlatformSavedData state = new PlatformSavedData();
        UUID player = uuid(2);
        CareerPromotionService.promote(state, catalog, player, ROOT, Map.of(), 1_000, uuid(201));
        unlock(state, catalog, player, SKILL_ONE, 2_000, 202);
        ActiveSkillService.equip(state, catalog, player, 1, SKILL_ONE, 3_000);
        PlayerCareerState before = state.playerCareerState(player);

        assertEquals(ActiveSkillService.Status.INVALID_REQUEST,
                ActiveSkillService.equip(state, catalog, player, 2, SKILL_TWO, -1).status());

        assertEquals(ActiveSkillService.Status.EFFECT_UNAVAILABLE,
                ActiveSkillService.use(state, catalog, player, 1, 4_000, ignored -> false).status());
        assertEquals(ActiveSkillService.Status.EFFECT_NOT_APPLICABLE,
                ActiveSkillService.use(
                        state, catalog, player, 1, 5_100, ignored -> true, ignored -> false).status());
        assertEquals(ActiveSkillService.Status.INVALID_REQUEST,
                ActiveSkillService.use(state, catalog, player, 0, 6_200, ignored -> true).status());
        assertEquals(before, state.playerCareerState(player));

        CompoundTag futureTag = encoded(state);
        futureTag.putInt("schema_version", PlatformSavedData.CURRENT_SCHEMA_VERSION + 1);
        PlatformSavedData readOnly = PlatformSavedData.CODEC.parse(NbtOps.INSTANCE, futureTag).getOrThrow();
        CompoundTag readOnlyBefore = encoded(readOnly);
        var denied = ActiveSkillService.clear(readOnly, player, 1, 7_300);
        assertEquals(ActiveSkillService.Status.READ_ONLY_SCHEMA, denied.status());
        assertFalse(denied.auditRecorded());
        assertEquals(readOnlyBefore, encoded(readOnly));
    }

    @Test
    void branchResetRemovesConflictingActivesButRetainsGlobalAncestorSkill() {
        CareerCatalog catalog = branchingCatalog();
        PlatformSavedData state = new PlatformSavedData();
        UUID player = uuid(3);
        CareerPromotionService.promote(state, catalog, player, ROOT, Map.of(), 1_000, uuid(301));
        unlock(state, catalog, player, SKILL_ONE, 2_000, 302);
        ActiveSkillService.equip(state, catalog, player, 1, SKILL_ONE, 3_000);

        assertEquals(CareerPromotionService.Status.SUCCESS, CareerPromotionService.promote(
                state, catalog, player, WARRIOR, Map.of(), 4_000, uuid(303)).status());
        unlock(state, catalog, player, SKILL_TWO, 5_000, 304);
        ActiveSkillService.equip(state, catalog, player, 2, SKILL_TWO, 6_000);
        ActiveSkillService.use(state, catalog, player, 2, 7_000, ignored -> true);

        assertEquals(CareerPromotionService.Status.SUCCESS, CareerPromotionService.promote(
                state, catalog, player, SCHOLAR, Map.of(), 8_000, uuid(305)).status());
        ActiveSkillState skills = state.playerCareerState(player).activeSkills();
        assertEquals(Optional.of(SKILL_ONE), skills.slot(1));
        assertEquals(Optional.empty(), skills.slot(2));
        assertEquals(0, skills.cooldownReadyAt(SKILL_TWO));
        assertEquals(ActiveSkillService.Status.SUCCESS,
                ActiveSkillService.evaluateUse(state, catalog, player, 1, 9_000, ignored -> true).status());
    }

    @Test
    void codecsRejectDuplicateSlotsAndOrphanCooldowns() {
        CareerProgress progress = new CareerProgress(0, 1, 1, Map.of(SKILL_ONE, 1));
        ActiveSkillState duplicates = new ActiveSkillState(
                Optional.of(SKILL_ONE), Optional.of(SKILL_ONE), Optional.empty(), Optional.empty(), Map.of());
        PlayerCareerState duplicateState = new PlayerCareerState(
                Optional.of(ROOT), Map.of(ROOT, progress), duplicates);
        assertTrue(PlayerCareerState.CODEC.encodeStart(NbtOps.INSTANCE, duplicateState).error().isPresent());

        ActiveSkillState orphanCooldown = new ActiveSkillState(
                Optional.of(SKILL_ONE), Optional.empty(), Optional.empty(), Optional.empty(),
                Map.of(SKILL_TWO, 10_000L));
        PlayerCareerState orphanState = new PlayerCareerState(
                Optional.of(ROOT), Map.of(ROOT, progress), orphanCooldown);
        assertTrue(PlayerCareerState.CODEC.encodeStart(NbtOps.INSTANCE, orphanState).error().isPresent());
    }

    private static CareerCatalog singleCareerCatalog() {
        Map<Identifier, CareerSkillDefinition> skills = Map.of(
                SKILL_ONE, skill("one", CareerSkillDefinition.Scope.CAREER),
                SKILL_TWO, skill("two", CareerSkillDefinition.Scope.CAREER),
                SKILL_THREE, skill("three", CareerSkillDefinition.Scope.CAREER),
                SKILL_FOUR, skill("four", CareerSkillDefinition.Scope.CAREER),
                SKILL_FIVE, skill("five", CareerSkillDefinition.Scope.CAREER));
        return CareerCatalog.create(Map.of(ROOT, career(1, List.of(), skills, 5))).getOrThrow();
    }

    private static CareerCatalog branchingCatalog() {
        return CareerCatalog.create(Map.of(
                ROOT, career(1, List.of(), Map.of(
                        SKILL_ONE, skill("one", CareerSkillDefinition.Scope.GLOBAL)), 1),
                WARRIOR, career(2, List.of(ROOT), Map.of(
                        SKILL_TWO, skill("two", CareerSkillDefinition.Scope.CAREER)), 1),
                SCHOLAR, career(2, List.of(ROOT), Map.of(
                        SKILL_THREE, skill("three", CareerSkillDefinition.Scope.CAREER)), 1)))
                .getOrThrow();
    }

    private static CareerDefinition career(
            int tier,
            List<Identifier> parents,
            Map<Identifier, CareerSkillDefinition> skills,
            int promotionPoints) {
        return new CareerDefinition(
                "career.rovenfall.test",
                tier,
                parents,
                Map.of(),
                List.of(ActivityTrack.EXPLORATION),
                List.of(0L),
                0,
                skills,
                promotionPoints,
                0);
    }

    private static CareerSkillDefinition skill(String name, CareerSkillDefinition.Scope scope) {
        return new CareerSkillDefinition(
                "career_skill.rovenfall." + name,
                List.of(),
                1,
                1,
                scope,
                List.of(),
                Optional.of(new CareerActiveSkillDefinition(
                        Identifier.withDefaultNamespace("speed"), 200, 0, 30)));
    }

    private static void unlock(
            PlatformSavedData state,
            CareerCatalog catalog,
            UUID player,
            Identifier skill,
            long timestamp,
            long transaction) {
        assertEquals(CareerSkillService.Status.SUCCESS, CareerSkillService.unlock(
                state, catalog, player, skill, timestamp, uuid(transaction)).status());
    }

    private static CompoundTag encoded(PlatformSavedData state) {
        return (CompoundTag) PlatformSavedData.CODEC.encodeStart(NbtOps.INSTANCE, state).getOrThrow();
    }

    private static UUID uuid(long value) {
        return new UUID(0, value);
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("rovenfall", path);
    }
}
