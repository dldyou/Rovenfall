package org.dldyou.rovenfall.rpg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

final class ActivityXpAwardServiceTest {
    private static final Identifier ACTIVITY = id("combat");
    private static final Identifier MINING = id("mining");
    private static final Identifier EXPLORATION = id("exploration");
    private static final Identifier EXPLORATION_ADVANCEMENT = Identifier.parse(
            "minecraft:adventure/adventuring_time");
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final ActivityXpConfig.Limits LIMITS = new ActivityXpConfig.Limits(10, 2, 1_000, 100, 10);

    @Test
    void awardsAtomicallyRecordsEvidenceAndRoundTrips() {
        RpgPlayerSavedData state = new RpgPlayerSavedData();
        RpgDefinitionSnapshot definitions = definitions();
        var result = ActivityXpAwardService.award(state, definitions, PLAYER, ACTIVITY, 5, 1_000,
                uuid(1), "combat:target", LIMITS);
        assertEquals(ActivityXpAwardService.Status.SUCCESS, result.status());
        assertEquals(5, state.state(PLAYER).activityXp().get(ACTIVITY));
        assertEquals("combat:target", state.state(PLAYER).provenance().getFirst().source());
        assertEquals(uuid(1), state.state(PLAYER).provenance().getFirst().transactionId());
        var encoded = RpgPlayerSavedData.CODEC.encodeStart(NbtOps.INSTANCE, state).getOrThrow();
        var loaded = RpgPlayerSavedData.CODEC.parse(NbtOps.INSTANCE, encoded).getOrThrow();
        assertEquals(state.snapshot().player(PLAYER), loaded.snapshot().player(PLAYER));
        var evidence = ActivityXpAwardService.evidence(state, PLAYER, Optional.of(ACTIVITY), 0, 10);
        assertEquals(1, evidence.totalEntries());
        assertEquals(uuid(1), evidence.entries().getFirst().transactionId());
    }

    @Test
    void observedActivityOutboxSurvivesDisplayHistoryEvictionRestartAndCollision() {
        RpgPlayerSavedData state = new RpgPlayerSavedData();
        ActivityXpConfig.Limits recoveryLimits = new ActivityXpConfig.Limits(
                1, RpgPlayerState.MAX_PROVENANCE, 0, 0, Integer.MAX_VALUE);

        for (int index = 1; index <= RpgPlayerState.MAX_PROVENANCE + 1; index++) {
            assertEquals(ActivityXpAwardService.Status.SUCCESS,
                    ActivityXpAwardService.awardObservedActivity(
                            state, definitions(), PLAYER, MINING, 1, index,
                            uuid(10_000 + index), "mining:quest:" + index, recoveryLimits).status());
        }

        UUID firstTransaction = uuid(10_001);
        assertEquals(RpgPlayerState.MAX_PROVENANCE, state.state(PLAYER).provenance().size());
        assertTrue(state.state(PLAYER).provenance().stream()
                .noneMatch(entry -> entry.transactionId().equals(firstTransaction)));
        assertEquals(RpgPlayerState.MAX_PROVENANCE + 1, state.questActivityEvidenceCount());
        RpgPlayerSavedData restarted = RpgPlayerSavedData.CODEC.parse(
                NbtOps.INSTANCE, RpgPlayerSavedData.CODEC.encodeStart(NbtOps.INSTANCE, state).getOrThrow())
                .getOrThrow();
        assertTrue(restarted.questActivityEvidence(firstTransaction).isPresent());
        assertEquals(ActivityXpAwardService.Status.DUPLICATE,
                ActivityXpAwardService.awardObservedActivity(
                        restarted, definitions(), PLAYER, MINING, 1, 1,
                        firstTransaction, "mining:quest:1", recoveryLimits).status());
        assertEquals(ActivityXpAwardService.Status.TRANSACTION_ID_CONFLICT,
                ActivityXpAwardService.awardObservedActivity(
                        restarted, definitions(), uuid(2), MINING, 1, 1,
                        firstTransaction, "mining:quest:1", recoveryLimits).status());
        assertEquals(RpgPlayerState.MAX_PROVENANCE + 1, restarted.questActivityEvidenceCount());
    }

    @Test
    void questRewardReceiptSurvivesProvenanceEvictionRestartAndConflict() {
        Identifier career = id("warrior");
        RpgPlayerSavedData state = new RpgPlayerSavedData();
        assertTrue(state.commit(PLAYER, new RpgPlayerState(
                java.util.Map.of(),
                java.util.Map.of(career, new RpgPlayerState.CareerProgress(0, 0, 0, java.util.Map.of())),
                Optional.of(career), java.util.Map.of(), java.util.Map.of(), List.of())));
        UUID transactionId = uuid(18_000);
        String source = "quest_reward:rovenfall:first_steps";

        assertEquals(ActivityXpAwardService.Status.SUCCESS,
                ActivityXpAwardService.awardQuestReward(
                        state, definitionsWithCareer(career), PLAYER, MINING, 2, 1_000,
                        transactionId, source).status());
        for (int index = 1; index <= RpgPlayerState.MAX_PROVENANCE + 1; index++) {
            assertEquals(ActivityXpAwardService.Status.SUCCESS,
                    ActivityXpAwardService.awardBossReward(
                            state, definitionsWithCareer(career), PLAYER, MINING, 1, 2_000 + index,
                            uuid(18_000 + index), "boss_reward:eviction:" + index).status());
        }
        assertFalse(state.state(PLAYER).provenance().stream()
                .anyMatch(entry -> entry.transactionId().equals(transactionId)));
        assertFalse(state.state(PLAYER).careerProvenance().stream()
                .anyMatch(entry -> entry.source().equals(source)));

        RpgPlayerSavedData restarted = RpgPlayerSavedData.CODEC.parse(
                NbtOps.INSTANCE, RpgPlayerSavedData.CODEC.encodeStart(NbtOps.INSTANCE, state).getOrThrow())
                .getOrThrow();
        long activityXp = restarted.state(PLAYER).activityXp().get(MINING);
        long careerXp = restarted.state(PLAYER).careers().get(career).experience();
        assertEquals(1, restarted.questRewardReceiptCount());
        assertEquals(ActivityXpAwardService.Status.DUPLICATE,
                ActivityXpAwardService.awardQuestReward(
                        restarted, definitionsWithCareer(career), PLAYER, MINING, 2, 1_000,
                        transactionId, source).status());
        assertEquals(ActivityXpAwardService.Status.TRANSACTION_ID_CONFLICT,
                ActivityXpAwardService.awardQuestReward(
                        restarted, definitionsWithCareer(career), PLAYER, MINING, 3, 1_000,
                        transactionId, source).status());
        assertEquals(ActivityXpAwardService.Status.DUPLICATE,
                ActivityXpAwardService.awardQuestReward(
                        restarted, RpgDefinitionSnapshot.empty(), PLAYER, MINING, 2, 1_000,
                        transactionId, source).status());
        CompoundTag future = (CompoundTag) RpgPlayerSavedData.CODEC
                .encodeStart(NbtOps.INSTANCE, restarted).getOrThrow();
        future.putInt("schema_version", RpgPlayerSavedData.CURRENT_SCHEMA_VERSION + 1);
        RpgPlayerSavedData readOnly = RpgPlayerSavedData.CODEC.parse(NbtOps.INSTANCE, future).getOrThrow();
        assertEquals(ActivityXpAwardService.Status.DUPLICATE,
                ActivityXpAwardService.awardQuestReward(
                        readOnly, RpgDefinitionSnapshot.empty(), PLAYER, MINING, 2, 1_000,
                        transactionId, source).status());
        assertEquals(activityXp, restarted.state(PLAYER).activityXp().get(MINING));
        assertEquals(careerXp, restarted.state(PLAYER).careers().get(career).experience());
        assertEquals(1, restarted.questRewardReceiptCount());
    }

    @Test
    void questRewardCanGrantExplorationXpWithoutInventingAPlayerDiscovery() {
        RpgPlayerSavedData state = new RpgPlayerSavedData();
        UUID transactionId = uuid(18_500);

        assertEquals(ActivityXpAwardService.Status.SUCCESS,
                ActivityXpAwardService.awardQuestReward(
                        state, definitions(), PLAYER, EXPLORATION, 5, 1_500,
                        transactionId, "quest_reward:rovenfall:explorer").status());
        assertEquals(5, state.state(PLAYER).activityXp().get(EXPLORATION));
        assertTrue(state.state(PLAYER).explorationDiscoveries().isEmpty());
        assertEquals(ActivityXpAwardService.Status.DUPLICATE,
                ActivityXpAwardService.awardQuestReward(
                        state, definitions(), PLAYER, EXPLORATION, 5, 1_500,
                        transactionId, "quest_reward:rovenfall:explorer").status());
    }

    @Test
    void codecRejectsActivityOutboxAboveThePerPlayerLimit() {
        RpgPlayerSavedData state = new RpgPlayerSavedData();
        assertEquals(ActivityXpAwardService.Status.SUCCESS,
                ActivityXpAwardService.awardObservedActivity(
                        state, definitions(), PLAYER, MINING, 1, 1_000,
                        uuid(19_500), "mining:decoder_limit").status());
        CompoundTag encoded = (CompoundTag) RpgPlayerSavedData.CODEC
                .encodeStart(NbtOps.INSTANCE, state).getOrThrow();
        var evidence = encoded.getListOrEmpty("quest_activity_evidence");
        CompoundTag template = (CompoundTag) evidence.getFirst().copy();
        for (int index = 1; index < RpgPlayerSavedData.MAX_QUEST_ACTIVITY_EVIDENCE_PER_PLAYER; index++) {
            CompoundTag duplicate = template.copy();
            UUID transactionId = uuid(20_000L + index);
            duplicate.putString("id", transactionId.toString());
            duplicate.getCompoundOrEmpty("value").getCompoundOrEmpty("provenance")
                    .putString("transaction", transactionId.toString());
            evidence.add(duplicate);
        }
        CompoundTag overflow = template.copy();
        UUID overflowId = uuid(30_000);
        overflow.putString("id", overflowId.toString());
        overflow.getCompoundOrEmpty("value").getCompoundOrEmpty("provenance")
                .putString("transaction", overflowId.toString());
        evidence.add(overflow);

        assertTrue(RpgPlayerSavedData.CODEC.parse(NbtOps.INSTANCE, encoded).error().isPresent());
    }

    @Test
    void observedActivityFailsAtomicallyForFutureSchema() {
        CompoundTag future = (CompoundTag) RpgPlayerSavedData.CODEC
                .encodeStart(NbtOps.INSTANCE, new RpgPlayerSavedData()).getOrThrow();
        future.putInt("schema_version", RpgPlayerSavedData.CURRENT_SCHEMA_VERSION + 1);
        RpgPlayerSavedData readOnly = RpgPlayerSavedData.CODEC.parse(NbtOps.INSTANCE, future).getOrThrow();

        assertEquals(ActivityXpAwardService.Status.READ_ONLY,
                ActivityXpAwardService.awardObservedActivity(
                        readOnly, definitions(), PLAYER, MINING, 1, 1_000,
                        uuid(19_000), "mining:read_only").status());
        assertTrue(readOnly.state(PLAYER).activityXp().isEmpty());
        assertEquals(0, readOnly.questActivityEvidenceCount());
    }

    @Test
    void schemaFiveAcknowledgementMigratesToAppliedDisposition() {
        RpgPlayerSavedData state = new RpgPlayerSavedData();
        UUID transactionId = uuid(19_001);
        assertEquals(ActivityXpAwardService.Status.SUCCESS,
                ActivityXpAwardService.awardObservedActivity(
                        state, definitions(), PLAYER, MINING, 1, 1_000,
                        transactionId, "mining:legacy_ack").status());
        assertTrue(state.acknowledgeQuestActivityEvidence(
                transactionId, PLAYER, 1_100, RpgPlayerSavedData.AckDisposition.APPLIED));
        CompoundTag schemaFive = (CompoundTag) RpgPlayerSavedData.CODEC
                .encodeStart(NbtOps.INSTANCE, state).getOrThrow();
        schemaFive.putInt("schema_version", 5);
        schemaFive.getListOrEmpty("quest_activity_evidence").getCompoundOrEmpty(0)
                .getCompoundOrEmpty("value").remove("ack_disposition");

        RpgPlayerSavedData migrated = RpgPlayerSavedData.CODEC.parse(NbtOps.INSTANCE, schemaFive).getOrThrow();

        assertEquals(RpgPlayerSavedData.CURRENT_SCHEMA_VERSION, migrated.schemaVersion());
        assertEquals(Optional.of(RpgPlayerSavedData.AckDisposition.APPLIED),
                migrated.questActivityEvidence(transactionId).orElseThrow().ackDisposition());
    }

    @Test
    void appliedActivityEvidenceExpiresOnlyAfterTheQuestMarkerIsConfirmed() {
        RpgPlayerSavedData state = new RpgPlayerSavedData();
        UUID transactionId = uuid(19_001);
        assertEquals(ActivityXpAwardService.Status.SUCCESS,
                ActivityXpAwardService.awardObservedActivity(
                        state, definitions(), PLAYER, MINING, 1, 1_000,
                        transactionId, "mining:retention").status());

        assertEquals(0, state.trimAcknowledgedQuestActivityEvidence(PLAYER, 2_000, 10));
        assertTrue(state.acknowledgeQuestActivityEvidence(
                transactionId, PLAYER, 2_000, RpgPlayerSavedData.AckDisposition.APPLIED));
        assertEquals(0, state.trimAcknowledgedQuestActivityEvidence(
                PLAYER, 2_000 + java.time.Duration.ofDays(29).toMillis(), 10));
        long afterRetention = 1_000 + java.time.Duration.ofDays(31).toMillis();
        assertEquals(0, state.trimAcknowledgedQuestActivityEvidence(
                PLAYER, afterRetention, 10));
        assertEquals(1, state.trimAcknowledgedQuestActivityEvidence(
                PLAYER,
                java.util.Set.of(transactionId), afterRetention, 10));
        assertEquals(0, state.questActivityEvidenceCount());
    }

    @Test
    void ignoredActivityEvidenceExpiresWithoutAQuestMarker() {
        RpgPlayerSavedData state = new RpgPlayerSavedData();
        UUID transactionId = uuid(19_002);
        assertEquals(ActivityXpAwardService.Status.SUCCESS,
                ActivityXpAwardService.awardObservedActivity(
                        state, definitions(), PLAYER, MINING, 1, 1_000,
                        transactionId, "mining:ignored_retention").status());
        assertTrue(state.acknowledgeQuestActivityEvidence(
                transactionId, PLAYER, 2_000, RpgPlayerSavedData.AckDisposition.IGNORED));

        assertEquals(1, state.trimAcknowledgedQuestActivityEvidence(
                PLAYER, 1_000 + java.time.Duration.ofDays(31).toMillis(), 10));
        assertEquals(0, state.questActivityEvidenceCount());
    }

    @Test
    void globalReclaimUsesEachQuestOwnersProcessedMarker() {
        RpgPlayerSavedData state = new RpgPlayerSavedData();
        UUID secondPlayer = uuid(19_100);
        UUID firstTransaction = uuid(19_101);
        UUID secondTransaction = uuid(19_102);
        long afterRetention = 1_000 + java.time.Duration.ofDays(31).toMillis();

        assertEquals(ActivityXpAwardService.Status.SUCCESS,
                ActivityXpAwardService.awardObservedActivity(
                        state, definitions(), PLAYER, MINING, 1, 1_000,
                        firstTransaction, "mining:global:first").status());
        assertEquals(ActivityXpAwardService.Status.SUCCESS,
                ActivityXpAwardService.awardObservedActivity(
                        state, definitions(), secondPlayer, MINING, 1, 1_000,
                        secondTransaction, "mining:global:second").status());
        assertTrue(state.acknowledgeQuestActivityEvidence(
                firstTransaction, PLAYER, 2_000, RpgPlayerSavedData.AckDisposition.APPLIED));
        assertTrue(state.acknowledgeQuestActivityEvidence(
                secondTransaction, secondPlayer, 2_000, RpgPlayerSavedData.AckDisposition.APPLIED));

        assertEquals(1, state.trimAcknowledgedQuestActivityEvidence(
                java.util.Map.of(PLAYER, java.util.Set.of(firstTransaction)), afterRetention, 10));
        assertTrue(state.questActivityEvidence(firstTransaction).isEmpty());
        assertTrue(state.questActivityEvidence(secondTransaction).isPresent());
    }

    @Test
    void observedActivityReclaimsExpiredAcknowledgementsBeforePerPlayerCapacityCheck() {
        RpgPlayerSavedData state = new RpgPlayerSavedData();
        ActivityXpConfig.Limits recoveryLimits = new ActivityXpConfig.Limits(
                1, RpgPlayerState.MAX_PROVENANCE, 0, 0, Integer.MAX_VALUE);
        for (int index = 1; index <= RpgPlayerSavedData.MAX_QUEST_ACTIVITY_EVIDENCE_PER_PLAYER; index++) {
            UUID transactionId = uuid(40_000L + index);
            assertEquals(ActivityXpAwardService.Status.SUCCESS,
                    ActivityXpAwardService.awardObservedActivity(
                            state, definitions(), PLAYER, MINING, 1, index, transactionId,
                            "mining:capacity:" + index, recoveryLimits).status());
            assertTrue(state.acknowledgeQuestActivityEvidence(
                    transactionId, PLAYER, index, RpgPlayerSavedData.AckDisposition.IGNORED));
        }

        long afterRetention = java.time.Duration.ofDays(31).toMillis();
        assertEquals(ActivityXpAwardService.Status.SUCCESS,
                ActivityXpAwardService.awardObservedActivity(
                        state, definitions(), PLAYER, MINING, 1, afterRetention,
                        uuid(50_000), "mining:capacity:new", recoveryLimits).status());
        assertTrue(state.questActivityEvidenceCount()
                < RpgPlayerSavedData.MAX_QUEST_ACTIVITY_EVIDENCE_PER_PLAYER);
        assertEquals((long) RpgPlayerSavedData.MAX_QUEST_ACTIVITY_EVIDENCE_PER_PLAYER + 1,
                state.state(PLAYER).activityXp().get(MINING));
    }

    @Test
    void bossRewardBypassesOrdinaryRateLimitsButRemainsIdempotent() {
        RpgPlayerSavedData state = new RpgPlayerSavedData();
        Identifier hunting = id("hunting");
        RpgDefinitionSnapshot definitions = RpgDefinitionSnapshot.compile(List.of(
                new RpgDefinitionSnapshot.ActivitySource(
                        id("activities/hunting"), "test", hunting,
                        new ActivityDefinition("activity.rovenfall.hunting", List.of(1_000L)))),
                List.of(), List.of());

        assertEquals(ActivityXpAwardService.Status.SUCCESS,
                ActivityXpAwardService.awardBossReward(
                        state, definitions, PLAYER, hunting, 500, 1_000,
                        uuid(90), "boss_reward:test").status());
        assertEquals(ActivityXpAwardService.Status.DUPLICATE,
                ActivityXpAwardService.awardBossReward(
                        state, definitions, PLAYER, hunting, 500, 1_001,
                        uuid(90), "boss_reward:test").status());
        assertEquals(500, state.state(PLAYER).activityXp().get(hunting));
        assertEquals(1, state.state(PLAYER).provenance().size());
    }

    @Test
    void rejectsDuplicateCooldownRateUnknownAndReadOnlyWithoutMutation() {
        RpgPlayerSavedData state = new RpgPlayerSavedData();
        RpgDefinitionSnapshot definitions = definitions();
        assertEquals(ActivityXpAwardService.Status.SUCCESS,
                ActivityXpAwardService.award(state, definitions, PLAYER, ACTIVITY, 5, 1_000,
                        uuid(2), "combat:one", LIMITS).status());
        assertEquals(ActivityXpAwardService.Status.COOLDOWN,
                ActivityXpAwardService.award(state, definitions, PLAYER, ACTIVITY, 5, 1_050,
                        uuid(3), "combat:one", LIMITS).status());
        assertEquals(ActivityXpAwardService.Status.DUPLICATE,
                ActivityXpAwardService.award(state, definitions, PLAYER, ACTIVITY, 5, 1_500,
                        uuid(2), "combat:replay", LIMITS).status());
        assertEquals(ActivityXpAwardService.Status.SUCCESS,
                ActivityXpAwardService.award(state, definitions, PLAYER, ACTIVITY, 5, 1_200,
                        uuid(5), "combat:two", LIMITS).status());
        int provenance = state.state(PLAYER).provenance().size();
        assertEquals(ActivityXpAwardService.Status.RATE_LIMIT,
                ActivityXpAwardService.award(state, definitions, PLAYER, ACTIVITY, 1, 1_300,
                        uuid(6), "combat:three", LIMITS).status());
        assertEquals(provenance, state.state(PLAYER).provenance().size());
        assertEquals(ActivityXpAwardService.Status.UNKNOWN_ACTIVITY,
                ActivityXpAwardService.award(state, definitions, PLAYER, id("missing"), 1, 2_000,
                        uuid(7), "missing", LIMITS).status());
        CompoundTag future = (CompoundTag) RpgPlayerSavedData.CODEC
                .encodeStart(NbtOps.INSTANCE, state).getOrThrow();
        future.putInt("schema_version", RpgPlayerSavedData.CURRENT_SCHEMA_VERSION + 1);
        RpgPlayerSavedData readOnly = RpgPlayerSavedData.CODEC.parse(NbtOps.INSTANCE, future).getOrThrow();
        assertTrue(!readOnly.isWritable());
        assertEquals(ActivityXpAwardService.Status.READ_ONLY,
                ActivityXpAwardService.award(readOnly, definitions, PLAYER, ACTIVITY, 1, 4_000,
                        uuid(8), "read-only", LIMITS).status());
    }

    @Test
    void publishesAllSevenServerObservedActivitySeams() {
        assertEquals(7, RpgActivityEvents.mapping().size());
        assertTrue(RpgActivityEvents.mapping().get("mining").startsWith("BlockDropsEvent"));
        assertTrue(RpgActivityEvents.mapping().get("farming").startsWith("BlockDropsEvent"));
    }

    @Test
    void globalWindowAndTransactionLimitsCannotBeBypassedByChangingActivity() {
        RpgPlayerSavedData state = new RpgPlayerSavedData();
        RpgDefinitionSnapshot definitions = definitions();
        ActivityXpConfig.Limits oneAward = new ActivityXpConfig.Limits(10, 1, 1_000, 0, 10);

        assertEquals(ActivityXpAwardService.Status.SUCCESS,
                ActivityXpAwardService.award(state, definitions, PLAYER, ACTIVITY, 1, 1_000,
                        uuid(20), "combat:target", oneAward).status());
        assertEquals(ActivityXpAwardService.Status.DUPLICATE,
                ActivityXpAwardService.award(state, definitions, PLAYER, MINING, 1, 2_001,
                        uuid(20), "mining:position", oneAward).status());
        assertEquals(ActivityXpAwardService.Status.RATE_LIMIT,
                ActivityXpAwardService.award(state, definitions, PLAYER, MINING, 1, 1_500,
                        uuid(21), "mining:position", oneAward).status());
        assertTrue(state.state(PLAYER).activityXp().get(MINING) == null);
    }

    @Test
    void capsPersistedCombatXpPerTargetSource() {
        RpgPlayerSavedData state = new RpgPlayerSavedData();
        ActivityXpConfig.Limits targetCap = new ActivityXpConfig.Limits(10, 10, 0, 0, 2);

        assertEquals(ActivityXpAwardService.Status.SUCCESS,
                ActivityXpAwardService.award(state, definitions(), PLAYER, ACTIVITY, 1, 1,
                        uuid(30), "combat:one-target", targetCap).status());
        assertEquals(ActivityXpAwardService.Status.SUCCESS,
                ActivityXpAwardService.award(state, definitions(), PLAYER, ACTIVITY, 1, 2,
                        uuid(31), "combat:one-target", targetCap).status());
        assertEquals(ActivityXpAwardService.Status.RATE_LIMIT,
                ActivityXpAwardService.award(state, definitions(), PLAYER, ACTIVITY, 1, 3,
                        uuid(32), "combat:one-target", targetCap).status());
        assertEquals(2L, state.state(PLAYER).activityXp().get(ACTIVITY));
    }

    @Test
    void explorationUsesConfiguredFirstDiscoveryOnly() {
        RpgPlayerSavedData state = new RpgPlayerSavedData();

        assertTrue(ActivityXpConfig.isExplorationAdvancement(EXPLORATION_ADVANCEMENT));
        assertTrue(!ActivityXpConfig.isExplorationAdvancement(
                Identifier.parse("minecraft:story/mine_stone")));
        assertEquals(ActivityXpAwardService.Status.SUCCESS,
                ActivityXpAwardService.award(state, definitions(), PLAYER, EXPLORATION, 1, 1_000,
                        uuid(40), "exploration:" + EXPLORATION_ADVANCEMENT, LIMITS).status());
        assertEquals(ActivityXpAwardService.Status.DUPLICATE,
                ActivityXpAwardService.award(state, definitions(), PLAYER, EXPLORATION, 1, 10_000,
                        uuid(41), "exploration:" + EXPLORATION_ADVANCEMENT, LIMITS).status());
        assertTrue(state.state(PLAYER).explorationDiscoveries().contains(EXPLORATION_ADVANCEMENT));
        assertEquals(1L, state.state(PLAYER).activityXp().get(EXPLORATION));
    }

    @Test
    void acceptedActivityAtomicallyAwardsActiveCareerXpAndRankWithDistinctEvidence() {
        Identifier career = id("warrior");
        RpgPlayerSavedData state = new RpgPlayerSavedData();
        assertTrue(state.commit(PLAYER, new RpgPlayerState(
                java.util.Map.of(),
                java.util.Map.of(career, new RpgPlayerState.CareerProgress(0, 0, 7, java.util.Map.of())),
                Optional.of(career), java.util.Map.of(), java.util.Map.of(), List.of())));

        var result = ActivityXpAwardService.award(
                state, definitionsWithCareer(career), PLAYER, ACTIVITY, 5, 10_000,
                uuid(50), "combat:career-target", LIMITS);

        assertEquals(ActivityXpAwardService.Status.SUCCESS, result.status());
        assertEquals(5L, state.state(PLAYER).activityXp().get(ACTIVITY));
        var progress = state.state(PLAYER).careers().get(career);
        assertEquals(15, progress.experience());
        assertEquals(1, progress.rank());
        assertEquals(8, progress.skillPoints());
        assertEquals(1, state.state(PLAYER).provenance().size());
        assertEquals(1, state.state(PLAYER).careerProvenance().size());
        assertEquals(RpgPlayerState.ProgressionProvenance.Kind.CAREER_XP,
                state.state(PLAYER).careerProvenance().getLast().kind());
        assertTrue(!state.state(PLAYER).provenance().getFirst().transactionId()
                .equals(state.state(PLAYER).careerProvenance().getLast().transactionId()));
    }

    @Test
    void crossingMultipleCareerRanksGrantsOnlyTheRankDelta() {
        Identifier career = id("warrior");
        RpgPlayerSavedData state = new RpgPlayerSavedData();
        assertTrue(state.commit(PLAYER, new RpgPlayerState(
                java.util.Map.of(),
                java.util.Map.of(career, new RpgPlayerState.CareerProgress(0, 0, 0, java.util.Map.of())),
                Optional.of(career), java.util.Map.of(), java.util.Map.of(), List.of())));

        assertEquals(ActivityXpAwardService.Status.SUCCESS,
                ActivityXpAwardService.award(
                        state, definitionsWithCareer(career), PLAYER, ACTIVITY, 10, 10_000,
                        uuid(60), "combat:rank-jump", LIMITS).status());
        assertEquals(2, state.state(PLAYER).careers().get(career).rank());
        assertEquals(2, state.state(PLAYER).careers().get(career).skillPoints());

        assertEquals(ActivityXpAwardService.Status.SUCCESS,
                ActivityXpAwardService.award(
                        state, definitionsWithCareer(career), PLAYER, ACTIVITY, 1, 11_000,
                        uuid(61), "combat:same-rank", LIMITS).status());
        assertEquals(2, state.state(PLAYER).careers().get(career).skillPoints());
    }

    @Test
    void skillPointCapRejectsTheWholeActivityAndCareerAward() {
        Identifier career = id("warrior");
        RpgPlayerSavedData state = new RpgPlayerSavedData();
        assertTrue(state.commit(PLAYER, new RpgPlayerState(
                java.util.Map.of(),
                java.util.Map.of(career, new RpgPlayerState.CareerProgress(
                        0, 0, RpgPlayerState.MAX_SKILL_POINTS, java.util.Map.of())),
                Optional.of(career), java.util.Map.of(), java.util.Map.of(), List.of())));
        RpgPlayerState before = state.state(PLAYER);

        assertEquals(ActivityXpAwardService.Status.OVERFLOW,
                ActivityXpAwardService.award(
                        state, definitionsWithCareer(career), PLAYER, ACTIVITY, 5, 20_000,
                        uuid(62), "combat:point-cap", LIMITS).status());
        assertEquals(before, state.state(PLAYER));
    }

    @Test
    void missingActiveCareerDefinitionRejectsTheWholeAward() {
        Identifier missing = id("removed_career");
        RpgPlayerSavedData state = new RpgPlayerSavedData();
        assertTrue(state.commit(PLAYER, new RpgPlayerState(
                java.util.Map.of(),
                java.util.Map.of(missing, new RpgPlayerState.CareerProgress(0, 0, 0, java.util.Map.of())),
                Optional.of(missing), java.util.Map.of(), java.util.Map.of(), List.of())));

        assertEquals(ActivityXpAwardService.Status.UNKNOWN_CAREER,
                ActivityXpAwardService.award(
                        state, definitions(), PLAYER, ACTIVITY, 1, 20_000,
                        uuid(51), "combat:target", LIMITS).status());
        assertTrue(state.state(PLAYER).activityXp().isEmpty());
        assertTrue(state.state(PLAYER).provenance().isEmpty());
        assertTrue(state.state(PLAYER).careerProvenance().isEmpty());
    }

    @Test
    void separateCareerEvidenceDoesNotShortenActivityDuplicateRetention() {
        Identifier career = id("warrior");
        RpgPlayerSavedData state = new RpgPlayerSavedData();
        assertTrue(state.commit(PLAYER, new RpgPlayerState(
                java.util.Map.of(),
                java.util.Map.of(career, new RpgPlayerState.CareerProgress(0, 0, 0, java.util.Map.of())),
                Optional.of(career), java.util.Map.of(), java.util.Map.of(), List.of())));
        ActivityXpConfig.Limits retentionLimits = new ActivityXpConfig.Limits(10, 256, 10_000, 0, 10);

        for (int index = 1; index <= 200; index++) {
            assertEquals(ActivityXpAwardService.Status.SUCCESS,
                    ActivityXpAwardService.award(
                            state, definitionsWithCareer(career), PLAYER, MINING, 1, index,
                            uuid(1_000 + index), "mining:retention", retentionLimits).status());
        }

        assertEquals(200, state.state(PLAYER).provenance().size());
        assertEquals(200, state.state(PLAYER).careerProvenance().size());
        assertEquals(ActivityXpAwardService.Status.DUPLICATE,
                ActivityXpAwardService.award(
                        state, definitionsWithCareer(career), PLAYER, MINING, 1, 10_001,
                        uuid(1_001), "mining:retention", retentionLimits).status());
    }

    private static RpgDefinitionSnapshot definitions() {
        return RpgDefinitionSnapshot.compile(List.of(
                new RpgDefinitionSnapshot.ActivitySource(
                        id("activities/combat"), "test", ACTIVITY,
                        new ActivityDefinition("activity.rovenfall.combat", List.of(100L))),
                new RpgDefinitionSnapshot.ActivitySource(
                        id("activities/mining"), "test", MINING,
                        new ActivityDefinition("activity.rovenfall.mining", List.of(100L))),
                new RpgDefinitionSnapshot.ActivitySource(
                        id("activities/exploration"), "test", EXPLORATION,
                        new ActivityDefinition("activity.rovenfall.exploration", List.of(100L)))),
                List.of(), List.of());
    }

    private static RpgDefinitionSnapshot definitionsWithCareer(Identifier career) {
        return RpgDefinitionSnapshot.compile(
                definitions().activities().entrySet().stream()
                        .map(entry -> new RpgDefinitionSnapshot.ActivitySource(
                                id("activities/" + entry.getKey().getPath()), "test", entry.getKey(), entry.getValue()))
                        .toList(),
                List.of(new RpgDefinitionSnapshot.CareerSource(
                        id("careers/" + career.getPath()), "test", career,
                        new CareerDefinition("career.rovenfall.warrior", 1, List.of(),
                                List.of(10L, 30L), 0, List.of(), 3))),
                List.of());
    }

    private static Identifier id(String path) { return Identifier.fromNamespaceAndPath("rovenfall", path); }
    private static UUID uuid(long least) { return new UUID(0, least); }
}
