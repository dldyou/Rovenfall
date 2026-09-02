package org.dldyou.rovenfall.administration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.serialization.JsonOps;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import org.dldyou.rovenfall.PersistenceTestHarness;
import org.dldyou.rovenfall.activities.ActivityKind;
import org.dldyou.rovenfall.activities.ActivityObservation;
import org.dldyou.rovenfall.activities.ActivityProvenance;
import org.dldyou.rovenfall.activities.ActivityRewardDefinition;
import org.dldyou.rovenfall.activities.ActivityRewardReloadListener.ResolvedReward;
import org.dldyou.rovenfall.activities.ActivityTrack;
import org.dldyou.rovenfall.careers.CareerCatalog;
import org.dldyou.rovenfall.careers.CareerDefinition;
import org.dldyou.rovenfall.careers.CareerProgress;
import org.dldyou.rovenfall.careers.CareerSkillDefinition;
import org.dldyou.rovenfall.careers.CareerSkillEffect;
import org.dldyou.rovenfall.careers.CareerState;
import org.dldyou.rovenfall.careers.SkillMutationReceipt;
import org.junit.jupiter.api.Test;

final class CareerSkillServiceTest {
    private static final Identifier SCOUT = id("scout");
    private static final Identifier SCHOLAR = id("scholar");
    private static final Identifier ROOT_SKILL = id("trail_sense");
    private static final Identifier CHILD_SKILL = id("pathfinder");
    private static final Identifier SCHOLAR_SKILL = id("study_habits");

    @Test
    void unlockResetAndActivityBonusAreAtomicIdempotentAndPersistent() {
        CareerCatalog catalog = catalog();
        PlatformSavedData state = new PlatformSavedData();
        UUID player = idUuid(1);
        assertEquals(CareerPromotionService.Status.SUCCESS, CareerPromotionService.promote(
                state, catalog, player, SCOUT, Map.of(), 1_000, idUuid(101)).status());

        assertEquals(CareerSkillService.Status.INSUFFICIENT_SKILL_POINTS,
                CareerSkillService.evaluateUnlock(state, catalog, player, ROOT_SKILL).status());
        assertEquals(10, award(state, catalog, player, "plains", 2_000, 10).awardedExperience());
        var available = CareerSkillService.evaluateUnlock(state, catalog, player, ROOT_SKILL);
        assertEquals(CareerSkillService.Status.SUCCESS, available.status());
        assertEquals(1, available.earnedPoints());
        assertEquals(1, available.availablePoints());

        UUID rootTransaction = idUuid(102);
        var root = CareerSkillService.unlock(
                state, catalog, player, ROOT_SKILL, 3_000, rootTransaction);
        assertEquals(CareerSkillService.Status.SUCCESS, root.status());
        assertEquals(1, state.playerCareerState(player).progress(SCOUT).skillRank(ROOT_SKILL));
        assertEquals(1, state.playerCareerState(player).progress(SCOUT).spentSkillPoints());
        assertEquals(EconomyTransactionReceipt.Kind.SKILL_UNLOCK,
                state.economyReceipt(rootTransaction).orElseThrow().kind());
        assertEquals(1, state.skillMutationReceipt(rootTransaction).orElseThrow().rankAfter());

        CompoundTag afterRoot = encoded(state);
        assertEquals(CareerSkillService.Status.DUPLICATE_TRANSACTION, CareerSkillService.unlock(
                state, catalog, player, ROOT_SKILL, 4_000, rootTransaction).status());
        assertEquals(afterRoot, encoded(state));
        assertEquals(1_000, catalog.activityExperienceBonusBasisPoints(
                state.playerCareerState(player), ActivityTrack.EXPLORATION));

        assertEquals(CareerSkillService.Status.INSUFFICIENT_SKILL_POINTS,
                CareerSkillService.evaluateUnlock(state, catalog, player, CHILD_SKILL).status());
        assertEquals(11, award(state, catalog, player, "forest", 5_000, 10).awardedExperience());
        assertEquals(11, award(state, catalog, player, "desert", 6_000, 10).awardedExperience());
        assertEquals(CareerSkillService.Status.SUCCESS,
                CareerSkillService.evaluateUnlock(state, catalog, player, CHILD_SKILL).status());
        assertEquals(CareerSkillService.Status.SUCCESS, CareerSkillService.unlock(
                state, catalog, player, CHILD_SKILL, 7_000, idUuid(103)).status());
        assertEquals(1_500, catalog.activityExperienceBonusBasisPoints(
                state.playerCareerState(player), ActivityTrack.EXPLORATION));

        assertEquals(CareerSkillService.Status.ACCOUNT_NOT_FOUND,
                CareerSkillService.evaluateReset(state, catalog, player, SCOUT).status());
        assertEquals(EconomyService.TransactionStatus.SUCCESS,
                EconomyService.createAccount(state, player, 10, 100, 8_000, idUuid(104)).status());
        UUID resetTransaction = idUuid(105);
        assertEquals(CareerSkillService.Status.SUCCESS, CareerSkillService.reset(
                state, catalog, player, SCOUT, 9_000, resetTransaction).status());
        CareerProgress reset = state.playerCareerState(player).progress(SCOUT);
        assertTrue(reset.skillRanks().isEmpty());
        assertEquals(0, reset.spentSkillPoints());
        assertEquals(2, reset.availableSkillPoints(catalog.definition(SCOUT).orElseThrow()));
        assertEquals(5, state.economyBalance(player).orElseThrow());
        assertEquals(EconomyTransactionReceipt.Kind.SKILL_RESET,
                state.economyReceipt(resetTransaction).orElseThrow().kind());
        assertEquals(0, catalog.activityExperienceBonusBasisPoints(
                state.playerCareerState(player), ActivityTrack.EXPLORATION));

        CompoundTag afterReset = encoded(state);
        assertEquals(CareerSkillService.Status.DUPLICATE_TRANSACTION, CareerSkillService.reset(
                state, catalog, player, SCOUT, 10_000, resetTransaction).status());
        assertEquals(afterReset, encoded(state));

        PlatformSavedData restored = PersistenceTestHarness.roundTrip(PlatformSavedData.CODEC, state);
        assertTrue(restored.playerCareerState(player).progress(SCOUT).skillRanks().isEmpty());
        assertTrue(restored.skillMutationReceipt(rootTransaction).isPresent());
        assertTrue(restored.skillMutationReceipt(resetTransaction).isPresent());
        assertEquals(5, restored.economyBalance(player).orElseThrow());
    }

    @Test
    void prerequisitesActiveLineageAndGlobalScopeAreEnforced() {
        CareerCatalog catalog = catalog();
        PlatformSavedData state = new PlatformSavedData();
        UUID player = idUuid(2);
        assertEquals(CareerPromotionService.Status.SUCCESS, CareerPromotionService.promote(
                state, catalog, player, SCOUT, Map.of(), 1_000, idUuid(201)).status());
        award(state, catalog, player, "plains", 2_000, 40);

        assertEquals(CareerSkillService.Status.PREREQUISITE_NOT_MET,
                CareerSkillService.evaluateUnlock(state, catalog, player, CHILD_SKILL).status());
        assertEquals(CareerSkillService.Status.SUCCESS, CareerSkillService.unlock(
                state, catalog, player, ROOT_SKILL, 3_000, idUuid(202)).status());
        assertEquals(CareerPromotionService.Status.SUCCESS, CareerPromotionService.promote(
                state, catalog, player, SCHOLAR, Map.of(), 4_000, idUuid(203)).status());

        assertEquals(CareerSkillService.Status.CAREER_NOT_ACTIVE_LINEAGE,
                CareerSkillService.evaluateUnlock(state, catalog, player, CHILD_SKILL).status());
        assertEquals(1_000, catalog.activityExperienceBonusBasisPoints(
                state.playerCareerState(player), ActivityTrack.EXPLORATION));
        assertEquals(0, catalog.activityExperienceBonusBasisPoints(
                state.playerCareerState(player), ActivityTrack.COMBAT));

        int audits = state.auditCount();
        var conflict = CareerSkillService.unlock(
                state, catalog, player, CHILD_SKILL, 5_000, idUuid(203));
        assertEquals(CareerSkillService.Status.TRANSACTION_ID_CONFLICT, conflict.status());
        assertEquals(audits + 1, state.auditCount());
    }

    @Test
    void schemaTenDefaultsSkillReceiptsAndFutureSchemaIsReadOnly() {
        CareerCatalog catalog = catalog();
        PlatformSavedData state = new PlatformSavedData();
        UUID player = idUuid(3);
        CareerPromotionService.promote(state, catalog, player, SCOUT, Map.of(), 1_000, idUuid(301));
        award(state, catalog, player, "plains", 2_000, 10);
        UUID unlockTransaction = idUuid(302);
        CareerSkillService.unlock(state, catalog, player, ROOT_SKILL, 3_000, unlockTransaction);

        CompoundTag schemaTen = encoded(state);
        schemaTen.putInt("schema_version", 10);
        CompoundTag careerState = schemaTen.getCompoundOrEmpty("career_state");
        careerState.remove("skill_receipts");
        PlatformSavedData migrated = PlatformSavedData.CODEC.parse(NbtOps.INSTANCE, schemaTen).getOrThrow();
        assertEquals(PlatformSavedData.CURRENT_SCHEMA_VERSION, migrated.schemaVersion());
        assertTrue(migrated.isWritable());
        assertTrue(migrated.skillMutationReceipt(unlockTransaction).isEmpty());
        assertEquals(1, migrated.playerCareerState(player).progress(SCOUT).skillRank(ROOT_SKILL));

        CompoundTag future = encoded(state);
        future.putInt("schema_version", PlatformSavedData.CURRENT_SCHEMA_VERSION + 1);
        PlatformSavedData readOnly = PlatformSavedData.CODEC.parse(NbtOps.INSTANCE, future).getOrThrow();
        CompoundTag before = encoded(readOnly);
        var denied = CareerSkillService.unlock(
                readOnly, catalog, player, ROOT_SKILL, 4_000, idUuid(303));
        assertEquals(CareerSkillService.Status.READ_ONLY_SCHEMA, denied.status());
        assertFalse(denied.auditRecorded());
        assertEquals(before, encoded(readOnly));
        var malformed = CareerSkillService.unlock(
                readOnly, catalog, player, ROOT_SKILL, 5_000, new UUID(0, 0));
        assertEquals(CareerSkillService.Status.INVALID_TRANSACTION, malformed.status());
        assertFalse(malformed.auditRecorded());
        assertEquals(before, encoded(readOnly));
    }

    @Test
    void skillCodecsRejectInvalidProgressReceiptKeysAndCycles() {
        CareerProgress invalidProgress = new CareerProgress(0, 0, 1, Map.of());
        assertTrue(CareerProgress.CODEC.encodeStart(JsonOps.INSTANCE, invalidProgress).error().isPresent());

        SkillMutationReceipt receipt = new SkillMutationReceipt(
                1_000,
                idUuid(401),
                idUuid(4),
                SCOUT,
                Optional.of(ROOT_SKILL),
                SkillMutationReceipt.Operation.UNLOCK,
                0,
                1,
                0,
                1,
                0);
        CareerState mismatched = new CareerState(Map.of(), Map.of(), Map.of(idUuid(402), receipt));
        assertTrue(CareerState.CODEC.encodeStart(JsonOps.INSTANCE, mismatched).error().isPresent());

        CareerSkillDefinition cyclicRoot = skill(
                "cyclic_root", List.of(CHILD_SKILL), CareerSkillDefinition.Scope.CAREER, 100);
        CareerSkillDefinition cyclicChild = skill(
                "cyclic_child", List.of(ROOT_SKILL), CareerSkillDefinition.Scope.CAREER, 100);
        CareerDefinition invalidCareer = careerDefinition(Map.of(
                ROOT_SKILL, cyclicRoot,
                CHILD_SKILL, cyclicChild));
        assertTrue(CareerCatalog.create(Map.of(SCOUT, invalidCareer)).error().isPresent());
    }

    private static CareerCatalog catalog() {
        CareerDefinition scout = careerDefinition(Map.of(
                ROOT_SKILL, skill("trail_sense", List.of(), CareerSkillDefinition.Scope.GLOBAL, 1_000),
                CHILD_SKILL, skill("pathfinder", List.of(ROOT_SKILL), CareerSkillDefinition.Scope.CAREER, 500)));
        CareerDefinition scholar = new CareerDefinition(
                "career.rovenfall.scholar",
                1,
                List.of(),
                Map.of(),
                List.of(ActivityTrack.EXPLORATION),
                List.of(0L, 10L, 30L),
                0,
                Map.of(SCHOLAR_SKILL,
                        skill("study_habits", List.of(), CareerSkillDefinition.Scope.CAREER, 500)),
                0,
                5);
        return CareerCatalog.create(Map.of(SCOUT, scout, SCHOLAR, scholar)).getOrThrow();
    }

    private static CareerDefinition careerDefinition(Map<Identifier, CareerSkillDefinition> skills) {
        return new CareerDefinition(
                "career.rovenfall.scout",
                1,
                List.of(),
                Map.of(),
                List.of(ActivityTrack.EXPLORATION),
                List.of(0L, 10L, 30L),
                0,
                skills,
                0,
                5);
    }

    private static CareerSkillDefinition skill(
            String name,
            List<Identifier> prerequisites,
            CareerSkillDefinition.Scope scope,
            int bonusBasisPoints) {
        return new CareerSkillDefinition(
                "career_skill.rovenfall." + name,
                prerequisites,
                2,
                1,
                scope,
                List.of(new CareerSkillEffect(
                        CareerSkillEffect.Type.ACTIVITY_EXPERIENCE_BONUS,
                        Optional.of(ActivityTrack.EXPLORATION),
                        bonusBasisPoints)));
    }

    private static ActivityProgressionService.AwardResult award(
            PlatformSavedData state,
            CareerCatalog catalog,
            UUID player,
            String biome,
            long timestamp,
            long experience) {
        Identifier target = Identifier.withDefaultNamespace(biome);
        ActivityObservation observation = new ActivityObservation(
                UUID.randomUUID(),
                timestamp,
                player,
                ActivityTrack.EXPLORATION,
                ActivityKind.EXPLORATION_DISCOVERY,
                Level.OVERWORLD,
                0,
                0,
                target,
                "biome:" + target,
                1,
                ActivityProvenance.explorationDiscovery());
        ResolvedReward reward = new ResolvedReward(
                id("reward_" + biome),
                new ActivityRewardDefinition(
                        ActivityTrack.EXPLORATION,
                        ActivityKind.EXPLORATION_DISCOVERY,
                        target,
                        experience,
                        60_000,
                        1_000,
                        1_000));
        return ActivityProgressionService.award(state, observation, reward, catalog);
    }

    private static CompoundTag encoded(PlatformSavedData state) {
        return (CompoundTag) PlatformSavedData.CODEC.encodeStart(NbtOps.INSTANCE, state).getOrThrow();
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("rovenfall", path);
    }

    private static UUID idUuid(long value) {
        return new UUID(0L, value);
    }
}
