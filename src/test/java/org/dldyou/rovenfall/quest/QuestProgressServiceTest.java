package org.dldyou.rovenfall.quest;

import static org.dldyou.rovenfall.PersistenceTestHarness.roundTrip;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.Identifier;
import org.dldyou.rovenfall.administration.AdministrationService;
import org.dldyou.rovenfall.administration.AuditEntry;
import org.dldyou.rovenfall.administration.EconomyTransactionReceipt;
import org.dldyou.rovenfall.administration.PlatformSavedData;
import org.dldyou.rovenfall.rpg.ActivityDefinition;
import org.dldyou.rovenfall.rpg.ActivityXpAwardService;
import org.dldyou.rovenfall.rpg.RpgDefinitionSnapshot;
import org.dldyou.rovenfall.rpg.RpgPlayerSavedData;
import org.junit.jupiter.api.Test;

final class QuestProgressServiceTest {
    private static final UUID PLAYER = uuid(1);
    private static final Identifier QUEST = id("first_steps");
    private static final Identifier MINING = id("mining");
    private static final Identifier OBJECTIVE = id("first_steps/mining");

    @Test
    void persistedActivityOutcomeRecoversAfterPostCommitCrashAndDuplicateDeliveryIsIgnored() {
        RpgPlayerSavedData rpg = new RpgPlayerSavedData();
        UUID sourceTransaction = uuid(10);
        assertEquals(ActivityXpAwardService.Status.SUCCESS,
                ActivityXpAwardService.awardObservedActivity(
                        rpg, rpgDefinitions(), PLAYER, MINING, 1, 1_000, sourceTransaction,
                        "mining:minecraft:overworld:1").status());
        RpgPlayerSavedData restartedRpg = roundTrip(RpgPlayerSavedData.CODEC, rpg);
        QuestPlayerSavedData restartedQuest = new QuestPlayerSavedData();
        var evidence = QuestProgressService.evidence(
                restartedRpg.questActivityEvidence(sourceTransaction).orElseThrow().provenance()).orElseThrow();

        var first = QuestProgressService.applyEvidence(restartedQuest, definitions(1, 0, 0), PLAYER, evidence);
        var duplicate = QuestProgressService.applyEvidence(restartedQuest, definitions(1, 0, 0), PLAYER, evidence);

        assertEquals(QuestProgressService.ProgressStatus.REWARD_PENDING, first.status());
        assertEquals(QuestProgressService.ProgressStatus.DUPLICATE, duplicate.status());
        assertEquals(1, restartedQuest.state(PLAYER).quests().get(QUEST)
                .objectiveProgress().get(OBJECTIVE));
        assertTrue(restartedQuest.state(PLAYER).processedEvidence().containsKey(sourceTransaction));
        assertTrue(restartedRpg.acknowledgeQuestActivityEvidence(
                sourceTransaction, PLAYER, 1_100, RpgPlayerSavedData.AckDisposition.APPLIED));
        RpgPlayerSavedData acknowledgedRpg = roundTrip(RpgPlayerSavedData.CODEC, restartedRpg);
        QuestPlayerSavedData persistedQuest = roundTrip(QuestPlayerSavedData.CODEC, restartedQuest);
        var acknowledged = acknowledgedRpg.questActivityEvidence(sourceTransaction).orElseThrow();
        assertFalse(QuestProgressRuntime.shouldDeliverActivityEvidence(
                acknowledged, persistedQuest.state(PLAYER)));
        assertTrue(QuestProgressRuntime.shouldDeliverActivityEvidence(
                acknowledged, QuestPlayerState.EMPTY));
    }

    @Test
    void ignoredActivityOutcomeIsNeverDeliveredAgainAfterAcknowledgement() {
        RpgPlayerSavedData rpg = new RpgPlayerSavedData();
        UUID sourceTransaction = uuid(11);
        assertEquals(ActivityXpAwardService.Status.SUCCESS,
                ActivityXpAwardService.awardObservedActivity(
                        rpg, rpgDefinitions(), PLAYER, MINING, 1, 1_000,
                        sourceTransaction, "mining:before_activation").status());
        var retained = rpg.questActivityEvidence(sourceTransaction).orElseThrow();
        var evidence = QuestProgressService.evidence(retained.provenance()).orElseThrow();

        assertEquals(QuestProgressService.ProgressStatus.IGNORED,
                QuestProgressService.applyEvidence(
                        new QuestPlayerSavedData(), QuestDefinitionSnapshot.empty(), PLAYER, evidence).status());
        assertTrue(rpg.acknowledgeQuestActivityEvidence(
                sourceTransaction, PLAYER, 1_100, RpgPlayerSavedData.AckDisposition.IGNORED));
        RpgPlayerSavedData restarted = roundTrip(RpgPlayerSavedData.CODEC, rpg);
        assertFalse(QuestProgressRuntime.shouldDeliverActivityEvidence(
                restarted.questActivityEvidence(sourceTransaction).orElseThrow(), QuestPlayerState.EMPTY));

        assertEquals(QuestProgressService.ProgressStatus.REWARD_PENDING,
                QuestProgressService.applyEvidence(
                        new QuestPlayerSavedData(), definitions(1, 0, 0), PLAYER, evidence).status());
    }

    @Test
    void capturedRewardsResumeAfterPartialFailureWithoutPayingCurrencyTwice() {
        QuestPlayerSavedData quests = new QuestPlayerSavedData();
        PlatformSavedData platform = new PlatformSavedData();
        RpgPlayerSavedData rpg = new RpgPlayerSavedData();
        var evidence = new QuestProgressService.Evidence(
                QuestDefinition.Kind.ACTIVITY, Optional.of(MINING), 1, 2_000, uuid(20));
        assertEquals(QuestProgressService.ProgressStatus.REWARD_PENDING,
                QuestProgressService.applyEvidence(quests, definitions(1, 100, 10), PLAYER, evidence).status());

        var futureRpg = (net.minecraft.nbt.CompoundTag) RpgPlayerSavedData.CODEC
                .encodeStart(NbtOps.INSTANCE, rpg).getOrThrow();
        futureRpg.putInt("schema_version", RpgPlayerSavedData.CURRENT_SCHEMA_VERSION + 1);
        RpgPlayerSavedData readOnlyRpg = RpgPlayerSavedData.CODEC.parse(NbtOps.INSTANCE, futureRpg).getOrThrow();
        var rejected = QuestProgressService.recoverRewards(
                quests, definitions(1, 100, 10), platform, readOnlyRpg, rpgDefinitions(), PLAYER, 2_100,
                0, 1_000_000);
        assertEquals(QuestProgressService.RewardStatus.RPG_REJECTED, rejected.status());
        assertEquals(100, platform.economyBalance(PLAYER).orElseThrow());
        assertEquals(QuestPlayerState.RewardOperation.Phase.CURRENCY_APPLIED,
                quests.state(PLAYER).quests().get(QUEST).pendingReward().orElseThrow().phase());

        QuestPlayerSavedData restarted = roundTrip(QuestPlayerSavedData.CODEC, quests);
        var recovered = QuestProgressService.recoverRewards(
                restarted, definitions(1, 100, 10), platform, rpg, rpgDefinitions(), PLAYER, 2_200,
                0, 1_000_000);
        var duplicateRecovery = QuestProgressService.recoverRewards(
                restarted, definitions(1, 100, 10), platform, rpg, rpgDefinitions(), PLAYER, 2_300,
                0, 1_000_000);

        assertEquals(QuestProgressService.RewardStatus.COMPLETED, recovered.status());
        assertEquals(QuestProgressService.RewardStatus.NOTHING_PENDING, duplicateRecovery.status());
        assertEquals(100, platform.economyBalance(PLAYER).orElseThrow());
        assertEquals(10, rpg.state(PLAYER).activityXp().get(MINING));
        assertTrue(restarted.state(PLAYER).quests().get(QUEST).completion().isPresent());
        assertTrue(platform.recentAuditEntries(20).stream()
                .anyMatch(entry -> entry.actionType().equals(id("quest_completed"))));
    }

    @Test
    void zeroCurrencyRewardReservesPlatformCapacityBeforePayingXp() {
        QuestPlayerSavedData quests = new QuestPlayerSavedData();
        PlatformSavedData platform = new PlatformSavedData();
        RpgPlayerSavedData rpg = new RpgPlayerSavedData();
        var evidence = new QuestProgressService.Evidence(
                QuestDefinition.Kind.ACTIVITY, Optional.of(MINING), 1, 2_400, uuid(20_500));
        assertEquals(QuestProgressService.ProgressStatus.REWARD_PENDING,
                QuestProgressService.applyEvidence(
                        quests, definitions(1, 0, 10), PLAYER, evidence).status());
        UUID completionTransaction = quests.state(PLAYER).quests().get(QUEST)
                .pendingReward().orElseThrow().transactionId();
        var futureRpg = (net.minecraft.nbt.CompoundTag) RpgPlayerSavedData.CODEC
                .encodeStart(NbtOps.INSTANCE, rpg).getOrThrow();
        futureRpg.putInt("schema_version", RpgPlayerSavedData.CURRENT_SCHEMA_VERSION + 1);
        RpgPlayerSavedData readOnlyRpg = RpgPlayerSavedData.CODEC.parse(
                NbtOps.INSTANCE, futureRpg).getOrThrow();

        var rejected = QuestProgressService.recoverRewards(
                quests, definitions(1, 0, 10), platform, readOnlyRpg, rpgDefinitions(), PLAYER,
                2_500, 0, 1_000_000);

        assertEquals(QuestProgressService.RewardStatus.RPG_REJECTED, rejected.status());
        EconomyTransactionReceipt reservation = platform.economyReceipt(
                completionTransaction).orElseThrow();
        assertEquals(EconomyTransactionReceipt.Kind.QUEST_REWARD, reservation.kind());
        assertEquals(0, reservation.amount());
        assertTrue(rpg.state(PLAYER).activityXp().isEmpty());

        assertEquals(QuestProgressService.RewardStatus.COMPLETED,
                QuestProgressService.recoverRewards(
                        quests, definitions(1, 0, 10), platform, rpg, rpgDefinitions(), PLAYER,
                        2_600, 0, 1_000_000).status());
        assertEquals(EconomyTransactionReceipt.Kind.QUEST_COMPLETION,
                platform.economyReceipt(completionTransaction).orElseThrow().kind());
        assertEquals(10, rpg.state(PLAYER).activityXp().get(MINING));
    }

    @Test
    void completionTimeNeverPrecedesAFutureDatedRewardOperation() {
        QuestPlayerSavedData quests = new QuestPlayerSavedData();
        PlatformSavedData platform = new PlatformSavedData();
        RpgPlayerSavedData rpg = new RpgPlayerSavedData();
        long evidenceTime = 5_000;
        var evidence = new QuestProgressService.Evidence(
                QuestDefinition.Kind.ACTIVITY, Optional.of(MINING), 1, evidenceTime, uuid(21));
        assertEquals(QuestProgressService.ProgressStatus.REWARD_PENDING,
                QuestProgressService.applyEvidence(
                        quests, definitions(1, 100, 10), PLAYER, evidence).status());

        var result = QuestProgressService.recoverRewards(
                quests, definitions(1, 100, 10), platform, rpg, rpgDefinitions(), PLAYER,
                evidenceTime - 3, 0, 1_000_000);

        assertEquals(QuestProgressService.RewardStatus.COMPLETED, result.status());
        var completion = quests.state(PLAYER).quests().get(QUEST).completion().orElseThrow();
        assertEquals(evidenceTime, completion.completedAtEpochMillis());
        assertTrue(quests.state(PLAYER).quests().get(QUEST).pendingReward().isEmpty());
    }

    @Test
    void durableRpgReceiptRecoversQuestPhaseAfterDisplayEvidenceEviction() {
        QuestPlayerSavedData quests = new QuestPlayerSavedData();
        PlatformSavedData platform = new PlatformSavedData();
        RpgPlayerSavedData rpg = new RpgPlayerSavedData();
        UUID completionTransaction = uuid(22);
        var operation = new QuestPlayerState.RewardOperation(
                1, completionTransaction, 0, Optional.of(MINING), 10, 2_000,
                QuestPlayerState.RewardOperation.Phase.CURRENCY_APPLIED);
        var entry = new QuestPlayerState.QuestEntry(
                1, Map.of(OBJECTIVE, 1L), Optional.of(operation), Optional.empty());
        assertTrue(quests.commit(PLAYER, QuestPlayerState.EMPTY,
                new QuestPlayerState(Map.of(QUEST, entry))));
        UUID xpTransaction = QuestProgressService.childTransaction(completionTransaction, "activity_xp");
        assertEquals(ActivityXpAwardService.Status.SUCCESS,
                ActivityXpAwardService.awardQuestReward(
                        rpg, rpgDefinitions(), PLAYER, MINING, 10, 2_000,
                        xpTransaction, QuestProgressService.rewardSource(QUEST)).status());
        for (int index = 1; index <= org.dldyou.rovenfall.rpg.RpgPlayerState.MAX_PROVENANCE + 1; index++) {
            assertEquals(ActivityXpAwardService.Status.SUCCESS,
                    ActivityXpAwardService.awardBossReward(
                            rpg, rpgDefinitions(), PLAYER, MINING, 1, 3_000 + index,
                            uuid(40_000L + index), "boss_reward:quest_phase:" + index).status());
        }
        assertTrue(rpg.state(PLAYER).provenance().stream()
                .noneMatch(value -> value.transactionId().equals(xpTransaction)));
        RpgPlayerSavedData restartedRpg = roundTrip(RpgPlayerSavedData.CODEC, rpg);
        long beforeRecovery = restartedRpg.state(PLAYER).activityXp().get(MINING);

        var result = QuestProgressService.recoverRewards(
                quests, definitions(1, 0, 10), platform, restartedRpg, rpgDefinitions(), PLAYER, 5_000,
                0, 1_000_000);

        assertEquals(QuestProgressService.RewardStatus.COMPLETED, result.status());
        assertEquals(beforeRecovery, restartedRpg.state(PLAYER).activityXp().get(MINING));
        assertEquals(1, restartedRpg.questRewardReceiptCount());
        assertTrue(quests.state(PLAYER).quests().get(QUEST).completion().isPresent());
    }

    @Test
    void laterRewardPhaseReconcilesEffectsMissingFromTheirOwnerRoots() {
        int phaseIndex = 0;
        for (QuestPlayerState.RewardOperation.Phase phase : java.util.List.of(
                QuestPlayerState.RewardOperation.Phase.CURRENCY_APPLIED,
                QuestPlayerState.RewardOperation.Phase.XP_APPLIED,
                QuestPlayerState.RewardOperation.Phase.AUDIT_APPLIED)) {
            QuestPlayerSavedData quests = new QuestPlayerSavedData();
            PlatformSavedData platform = new PlatformSavedData();
            RpgPlayerSavedData rpg = new RpgPlayerSavedData();
            UUID completionTransaction = uuid(23 + phaseIndex++);
            var operation = new QuestPlayerState.RewardOperation(
                    1, completionTransaction, 100, Optional.of(MINING), 10, 2_000, phase);
            var entry = new QuestPlayerState.QuestEntry(
                    1, Map.of(OBJECTIVE, 1L), Optional.of(operation), Optional.empty());
            assertTrue(quests.commit(PLAYER, QuestPlayerState.EMPTY,
                    new QuestPlayerState(Map.of(QUEST, entry))), phase.name());

            var result = QuestProgressService.recoverRewards(
                    roundTrip(QuestPlayerSavedData.CODEC, quests), definitions(1, 100, 10),
                    platform, rpg, rpgDefinitions(), PLAYER, 3_000, 0, 1_000_000);

            assertEquals(QuestProgressService.RewardStatus.COMPLETED, result.status(), phase.name());
            assertEquals(100, platform.economyBalance(PLAYER).orElseThrow(), phase.name());
            assertEquals(10, rpg.state(PLAYER).activityXp().get(MINING), phase.name());
            assertTrue(platform.recentAuditEntries(10).stream()
                    .anyMatch(audit -> audit.actionType().equals(id("quest_completed"))), phase.name());
        }
    }

    @Test
    void completedReceiptReconcilesMissingEffectsWithoutReopeningQuest() {
        QuestPlayerSavedData quests = new QuestPlayerSavedData();
        PlatformSavedData platform = new PlatformSavedData();
        RpgPlayerSavedData rpg = new RpgPlayerSavedData();
        UUID completionTransaction = uuid(24);
        var operation = new QuestPlayerState.RewardOperation(
                1, completionTransaction, 100, Optional.of(MINING), 10, 2_000,
                QuestPlayerState.RewardOperation.Phase.CAPTURED);
        var receipt = new QuestPlayerState.CompletionReceipt(1, completionTransaction, 2_100, operation);
        var entry = new QuestPlayerState.QuestEntry(
                1, Map.of(OBJECTIVE, 1L), Optional.of(receipt));
        assertTrue(quests.commit(PLAYER, QuestPlayerState.EMPTY,
                new QuestPlayerState(Map.of(QUEST, entry))));

        var result = QuestProgressService.recoverRewards(
                roundTrip(QuestPlayerSavedData.CODEC, quests), definitions(1, 100, 10),
                platform, rpg, rpgDefinitions(), PLAYER, 3_000, 0, 1_000_000);

        assertEquals(QuestProgressService.RewardStatus.COMPLETED, result.status());
        assertEquals(100, platform.economyBalance(PLAYER).orElseThrow());
        assertEquals(10, rpg.state(PLAYER).activityXp().get(MINING));
        assertTrue(platform.recentAuditEntries(10).stream()
                .anyMatch(audit -> audit.actionType().equals(id("quest_completed"))));
        assertTrue(quests.state(PLAYER).quests().get(QUEST).completion().isPresent());
    }

    @Test
    void completedReconciliationUsesABoundedRotatingBatch() {
        QuestPlayerSavedData quests = new QuestPlayerSavedData();
        Map<Identifier, QuestPlayerState.QuestEntry> completed = new java.util.LinkedHashMap<>();
        java.util.List<UUID> transactions = new java.util.ArrayList<>();
        int total = QuestProgressService.MAX_COMPLETED_RECONCILIATIONS_PER_RECOVERY + 8;
        for (int index = 0; index < total; index++) {
            UUID transaction = uuid(60_000L + index);
            transactions.add(transaction);
            var operation = new QuestPlayerState.RewardOperation(
                    1, transaction, 0, Optional.empty(), 0, 2_000,
                    QuestPlayerState.RewardOperation.Phase.AUDIT_APPLIED);
            var receipt = new QuestPlayerState.CompletionReceipt(1, transaction, 2_000, operation);
            completed.put(id("completed_" + String.format(java.util.Locale.ROOT, "%03d", index)),
                    new QuestPlayerState.QuestEntry(
                            1, Map.of(OBJECTIVE, 1L), Optional.of(receipt)));
        }
        assertTrue(quests.commit(PLAYER, QuestPlayerState.EMPTY, new QuestPlayerState(completed)));
        PlatformSavedData platform = new PlatformSavedData();
        RpgPlayerSavedData rpg = new RpgPlayerSavedData();
        var cursor = new QuestProgressService.RecoveryCursor();

        var first = QuestProgressService.recoverRewards(
                quests, QuestDefinitionSnapshot.empty(), platform, rpg, RpgDefinitionSnapshot.empty(),
                PLAYER, 3_000, 0, 1_000_000, cursor);
        long firstMarkers = transactions.stream()
                .filter(transaction -> platform.economyReceipt(transaction).isPresent()).count();
        var second = QuestProgressService.recoverRewards(
                quests, QuestDefinitionSnapshot.empty(), platform, rpg, RpgDefinitionSnapshot.empty(),
                PLAYER, 3_001, 0, 1_000_000, cursor);
        long allMarkers = transactions.stream()
                .filter(transaction -> platform.economyReceipt(transaction).isPresent()).count();

        assertEquals(QuestProgressService.MAX_COMPLETED_RECONCILIATIONS_PER_RECOVERY, firstMarkers);
        assertEquals(QuestProgressService.MAX_COMPLETED_RECONCILIATIONS_PER_RECOVERY,
                first.completedQuests());
        assertEquals(8, second.completedQuests());
        assertEquals(total, allMarkers);
    }

    @Test
    void expiredCompletedAuditIsNotReinsertedWhilePermanentRewardsRemainReconciled() {
        QuestPlayerSavedData quests = new QuestPlayerSavedData();
        PlatformSavedData platform = new PlatformSavedData();
        RpgPlayerSavedData rpg = new RpgPlayerSavedData();
        UUID completionTransaction = uuid(26);
        long completedAt = 1_000;
        var operation = new QuestPlayerState.RewardOperation(
                1, completionTransaction, 100, Optional.of(MINING), 10, completedAt,
                QuestPlayerState.RewardOperation.Phase.CAPTURED);
        var receipt = new QuestPlayerState.CompletionReceipt(1, completionTransaction, completedAt, operation);
        assertTrue(quests.commit(PLAYER, QuestPlayerState.EMPTY, new QuestPlayerState(Map.of(QUEST,
                new QuestPlayerState.QuestEntry(1, Map.of(OBJECTIVE, 1L), Optional.of(receipt))))));

        var result = QuestProgressService.recoverRewards(
                quests, definitions(1, 100, 10), platform, rpg, rpgDefinitions(), PLAYER,
                completedAt + java.time.Duration.ofDays(30).toMillis() + 1, 0, 1_000_000);

        assertEquals(QuestProgressService.RewardStatus.COMPLETED, result.status());
        assertEquals(100, platform.economyBalance(PLAYER).orElseThrow());
        assertEquals(10, rpg.state(PLAYER).activityXp().get(MINING));
        assertTrue(platform.recentAuditEntries(10).stream()
                .noneMatch(audit -> audit.actionType().equals(id("quest_completed"))));
    }

    @Test
    void unknownRewardActivityRejectsBeforeAnyCurrencyIsPaid() {
        QuestPlayerSavedData quests = new QuestPlayerSavedData();
        PlatformSavedData platform = new PlatformSavedData();
        RpgPlayerSavedData rpg = new RpgPlayerSavedData();
        var evidence = new QuestProgressService.Evidence(
                QuestDefinition.Kind.ACTIVITY, Optional.of(MINING), 1, 2_500, uuid(25));
        assertEquals(QuestProgressService.ProgressStatus.REWARD_PENDING,
                QuestProgressService.applyEvidence(quests, definitions(1, 100, 10), PLAYER, evidence).status());

        var result = QuestProgressService.recoverRewards(
                quests, definitions(1, 100, 10), platform, rpg, RpgDefinitionSnapshot.empty(), PLAYER, 2_600,
                0, 1_000_000);

        assertEquals(QuestProgressService.RewardStatus.UNKNOWN_REWARD_ACTIVITY, result.status());
        assertTrue(platform.economyBalance(PLAYER).isEmpty());
        assertEquals(QuestPlayerState.RewardOperation.Phase.CAPTURED,
                quests.state(PLAYER).quests().get(QUEST).pendingReward().orElseThrow().phase());
        assertTrue(platform.recentAuditEntries(10).stream()
                .anyMatch(entry -> entry.reason().equals("unknown_reward_activity")));
    }

    @Test
    void expiredRewardFailureDoesNotReinsertAnOldDenialAudit() {
        QuestPlayerSavedData quests = new QuestPlayerSavedData();
        Identifier missingActivity = id("missing_activity");
        UUID transaction = uuid(26_500);
        long startedAt = 1_000;
        var operation = new QuestPlayerState.RewardOperation(
                1, transaction, 100, Optional.of(missingActivity), 10, startedAt,
                QuestPlayerState.RewardOperation.Phase.CAPTURED);
        assertTrue(quests.commit(PLAYER, QuestPlayerState.EMPTY, new QuestPlayerState(Map.of(
                QUEST, new QuestPlayerState.QuestEntry(
                        1, Map.of(OBJECTIVE, 1L), Optional.of(operation), Optional.empty())))));
        PlatformSavedData platform = new PlatformSavedData();

        var result = QuestProgressService.recoverRewards(
                quests, definitions(1, 0, 0), platform, new RpgPlayerSavedData(),
                RpgDefinitionSnapshot.empty(), PLAYER,
                startedAt + java.time.Duration.ofDays(30).toMillis() + 1, 0, 1_000_000);

        assertEquals(QuestProgressService.RewardStatus.UNKNOWN_REWARD_ACTIVITY, result.status());
        assertTrue(platform.recentAuditEntries(10).stream()
                .noneMatch(entry -> entry.reason().equals("unknown_reward_activity")));
    }

    @Test
    void failedRewardDoesNotStarveLaterRecoverableQuest() {
        Identifier blockedQuest = id("a_blocked_reward");
        Identifier healthyQuest = id("b_healthy_reward");
        Identifier missingActivity = id("missing_activity");
        var blocked = new QuestPlayerState.RewardOperation(
                1, uuid(27), 100, Optional.of(missingActivity), 10, 2_000,
                QuestPlayerState.RewardOperation.Phase.CAPTURED);
        var healthy = new QuestPlayerState.RewardOperation(
                1, uuid(28), 50, Optional.of(MINING), 5, 2_000,
                QuestPlayerState.RewardOperation.Phase.CAPTURED);
        QuestPlayerSavedData quests = new QuestPlayerSavedData();
        assertTrue(quests.commit(PLAYER, QuestPlayerState.EMPTY, new QuestPlayerState(Map.of(
                blockedQuest, new QuestPlayerState.QuestEntry(
                        1, Map.of(OBJECTIVE, 1L), Optional.of(blocked), Optional.empty()),
                healthyQuest, new QuestPlayerState.QuestEntry(
                        1, Map.of(OBJECTIVE, 1L), Optional.of(healthy), Optional.empty())))));
        PlatformSavedData platform = new PlatformSavedData();
        RpgPlayerSavedData rpg = new RpgPlayerSavedData();

        var result = QuestProgressService.recoverRewards(
                quests, definitions(1, 0, 0), platform, rpg, rpgDefinitions(), PLAYER,
                3_000, 0, 1_000_000);

        assertEquals(QuestProgressService.RewardStatus.UNKNOWN_REWARD_ACTIVITY, result.status());
        assertEquals(1, result.completedQuests());
        assertEquals(50, platform.economyBalance(PLAYER).orElseThrow());
        assertEquals(5, rpg.state(PLAYER).activityXp().get(MINING));
        assertTrue(quests.state(PLAYER).quests().get(blockedQuest).pendingReward().isPresent());
        assertTrue(quests.state(PLAYER).quests().get(healthyQuest).completion().isPresent());
    }

    @Test
    void boundedFailedRewardsRotateUntilALaterQuestCompletes() {
        Map<Identifier, QuestPlayerState.QuestEntry> entries = new java.util.LinkedHashMap<>();
        Identifier missingActivity = id("missing_activity");
        for (int index = 0; index <= QuestProgressService.MAX_REWARD_STEPS_PER_RECOVERY; index++) {
            Identifier questId = id("blocked_" + String.format(java.util.Locale.ROOT, "%03d", index));
            var blocked = new QuestPlayerState.RewardOperation(
                    1, uuid(70_000L + index), 0, Optional.of(missingActivity), 1, 2_000,
                    QuestPlayerState.RewardOperation.Phase.CAPTURED);
            entries.put(questId, new QuestPlayerState.QuestEntry(
                    1, Map.of(OBJECTIVE, 1L), Optional.of(blocked), Optional.empty()));
        }
        Identifier healthyQuest = id("z_healthy_reward");
        var healthy = new QuestPlayerState.RewardOperation(
                1, uuid(71_000), 50, Optional.of(MINING), 5, 2_000,
                QuestPlayerState.RewardOperation.Phase.CAPTURED);
        entries.put(healthyQuest, new QuestPlayerState.QuestEntry(
                1, Map.of(OBJECTIVE, 1L), Optional.of(healthy), Optional.empty()));
        QuestPlayerSavedData quests = new QuestPlayerSavedData();
        assertTrue(quests.commit(PLAYER, QuestPlayerState.EMPTY, new QuestPlayerState(entries)));
        PlatformSavedData platform = new PlatformSavedData();
        RpgPlayerSavedData rpg = new RpgPlayerSavedData();
        var cursor = new QuestProgressService.RecoveryCursor();

        for (int attempt = 0; attempt < 6
                && quests.state(PLAYER).quests().get(healthyQuest).completion().isEmpty(); attempt++) {
            QuestProgressService.recoverRewards(
                    quests, QuestDefinitionSnapshot.empty(), platform, rpg, rpgDefinitions(), PLAYER,
                    3_000 + attempt, 0, 1_000_000, cursor);
        }

        assertTrue(quests.state(PLAYER).quests().get(healthyQuest).completion().isPresent());
        assertEquals(50, platform.economyBalance(PLAYER).orElseThrow());
        assertEquals(5, rpg.state(PLAYER).activityXp().get(MINING));
    }

    @Test
    void staleDefinitionRejectsAtomicallyAndRecordsDenialAudit() {
        QuestPlayerSavedData quests = new QuestPlayerSavedData();
        PlatformSavedData platform = new PlatformSavedData();
        QuestPlayerState retained = new QuestPlayerState(Map.of(
                QUEST, new QuestPlayerState.QuestEntry(1, Map.of(OBJECTIVE, 0L), Optional.empty())));
        assertTrue(quests.commit(PLAYER, QuestPlayerState.EMPTY, retained));
        var evidence = new QuestProgressService.Evidence(
                QuestDefinition.Kind.ACTIVITY, Optional.of(MINING), 1, 3_000, uuid(30));

        var result = QuestProgressService.applyEvidence(quests, definitions(2, 0, 0), platform, PLAYER, evidence);

        assertEquals(QuestProgressService.ProgressStatus.STALE_DEFINITION, result.status());
        assertEquals(retained, quests.state(PLAYER));
        assertTrue(platform.recentAuditEntries(10).stream()
                .anyMatch(entry -> entry.actionType().equals(id("quest_progress_denied"))
                        && entry.reason().equals("stale_definition")));
    }

    @Test
    void adaptersAcceptOnlyCompletedOwnerEvidence() {
        EconomyTransactionReceipt shop = new EconomyTransactionReceipt(
                4_000, PLAYER, PLAYER, EconomyTransactionReceipt.Kind.PURCHASE, 5,
                Optional.empty(), Optional.of(id("starter_shop")), Optional.of(id("bread")),
                Optional.empty(), 1,
                Optional.of(org.dldyou.rovenfall.economy.ShopInstance.Stock.unlimitedStock()),
                Optional.of(org.dldyou.rovenfall.economy.ShopInstance.Stock.unlimitedStock()),
                Optional.empty(), Optional.empty(), Optional.empty(),
                EconomyTransactionReceipt.CompensationDecision.NONE);
        assertEquals(QuestDefinition.Kind.SHOP_TRADE,
                QuestProgressService.evidence(uuid(40), shop).orElseThrow().kind());
        assertFalse(QuestProgressService.evidence(
                uuid(43), receiptWithFlags(shop, Optional.of(uuid(44)), Optional.empty())).isPresent());
        assertFalse(QuestProgressService.evidence(
                uuid(45), receiptWithFlags(shop, Optional.empty(), Optional.of(uuid(46)))).isPresent());

        var pendingBoss = boss(org.dldyou.rovenfall.mobs.BossRewardOperation.Phase.PENDING);
        var completedBoss = boss(org.dldyou.rovenfall.mobs.BossRewardOperation.Phase.COMPLETED);
        assertFalse(QuestProgressService.evidence(uuid(41), pendingBoss).isPresent());
        assertEquals(QuestDefinition.Kind.BOSS_DEFEAT,
                QuestProgressService.evidence(uuid(41), completedBoss).orElseThrow().kind());
    }

    @Test
    void questAuditAcceptsOnlyAnExactRetryForOneTransaction() {
        PlatformSavedData platform = new PlatformSavedData();
        UUID transactionId = uuid(42);
        AuditEntry retained = new AuditEntry(
                4_200, AdministrationService.SYSTEM_ACTOR, id("quest_completed"), PLAYER.toString(),
                Optional.empty(), Optional.empty(), "pending", "completed", "server_outcome", transactionId);
        AuditEntry conflict = new AuditEntry(
                4_200, AdministrationService.SYSTEM_ACTOR, id("quest_completed"), uuid(2).toString(),
                Optional.empty(), Optional.empty(), "pending", "completed", "server_outcome", transactionId);

        assertTrue(platform.recordQuestAudit(retained));
        assertTrue(platform.recordQuestAudit(retained));
        assertFalse(platform.recordQuestAudit(conflict));
        assertEquals(1, platform.recentAuditEntries(10).stream()
                .filter(entry -> entry.transactionId().equals(transactionId)).count());
    }

    @Test
    void processedEvidenceRetiresInTwoPhasesAndAllowsProgressPastTheLedgerLimit() {
        QuestPlayerSavedData quests = new QuestPlayerSavedData();
        Map<UUID, QuestPlayerState.ProcessedEvidence> full = new java.util.LinkedHashMap<>();
        for (int index = 0; index < QuestPlayerState.MAX_PROCESSED_EVIDENCE; index++) {
            full.put(uuid(20_000L + index), new QuestPlayerState.ProcessedEvidence(
                    1_000, QuestDefinition.Kind.ACTIVITY));
        }
        assertTrue(quests.commit(PLAYER, QuestPlayerState.EMPTY, new QuestPlayerState(Map.of(), full)));
        long missingObservedAt = 1_000 + java.time.Duration.ofDays(31).toMillis();
        while (quests.state(PLAYER).processedEvidence().values().stream()
                .anyMatch(value -> value.ownerEvidenceMissingSinceEpochMillis().isEmpty())) {
            Map<UUID, Boolean> batch = quests.state(PLAYER).processedEvidence().entrySet().stream()
                    .filter(entry -> entry.getValue().ownerEvidenceMissingSinceEpochMillis().isEmpty())
                    .limit(256)
                    .collect(java.util.stream.Collectors.toMap(
                            Map.Entry::getKey, ignored -> false, (left, right) -> left,
                            java.util.LinkedHashMap::new));
            assertEquals(batch.size(), quests.maintainProcessedEvidence(
                    PLAYER, batch, missingObservedAt, 256));
        }
        QuestPlayerSavedData restarted = roundTrip(QuestPlayerSavedData.CODEC, quests);
        assertEquals(QuestPlayerState.MAX_PROCESSED_EVIDENCE,
                restarted.state(PLAYER).processedEvidence().size());

        long confirmedMissingAt = missingObservedAt + java.time.Duration.ofDays(31).toMillis();
        while (!restarted.state(PLAYER).processedEvidence().isEmpty()) {
            Map<UUID, Boolean> batch = restarted.state(PLAYER).processedEvidence().keySet().stream()
                    .limit(256)
                    .collect(java.util.stream.Collectors.toMap(
                            value -> value, ignored -> false, (left, right) -> left,
                            java.util.LinkedHashMap::new));
            assertEquals(batch.size(), restarted.maintainProcessedEvidence(
                    PLAYER, batch, confirmedMissingAt, 256));
        }
        var evidence = new QuestProgressService.Evidence(
                QuestDefinition.Kind.ACTIVITY, Optional.of(MINING), 1, confirmedMissingAt, uuid(30_000));
        assertEquals(QuestProgressService.ProgressStatus.REWARD_PENDING,
                QuestProgressService.applyEvidence(restarted, definitions(1, 0, 0), PLAYER, evidence).status());
        assertEquals(1, restarted.state(PLAYER).processedEvidence().size());
    }

    @Test
    void processedEvidenceMaintenanceRotatesPastRetainedLowIds() {
        Map<UUID, QuestPlayerState.ProcessedEvidence> processed = new java.util.LinkedHashMap<>();
        for (int index = 0; index < 70; index++) {
            processed.put(uuid(50_000L + index), new QuestPlayerState.ProcessedEvidence(
                    1_000, QuestDefinition.Kind.ACTIVITY));
        }
        QuestPlayerState state = new QuestPlayerState(Map.of(), processed);
        long timestamp = 1_001 + java.time.Duration.ofDays(31).toMillis();

        var first = QuestProgressRuntime.processedEvidenceMaintenanceBatch(state, timestamp, null, 64);
        var second = QuestProgressRuntime.processedEvidenceMaintenanceBatch(
                state, timestamp, first.getLast().getKey(), 64);
        var wrapped = QuestProgressRuntime.processedEvidenceMaintenanceBatch(
                state, timestamp, second.getLast().getKey(), 64);

        assertEquals(64, first.size());
        assertEquals(6, second.size());
        assertTrue(first.stream().map(Map.Entry::getKey).noneMatch(
                id -> second.stream().anyMatch(entry -> entry.getKey().equals(id))));
        assertEquals(first.getFirst().getKey(), wrapped.getFirst().getKey());
    }

    @Test
    void ownerEvidenceAndRecoveryWindowsHaveStableInclusiveBoundaries() {
        long evidenceTimestamp = 1_000;
        long ownerBoundary = evidenceTimestamp
                + QuestPlayerSavedData.PROCESSED_EVIDENCE_OWNER_RETENTION_MILLIS;
        long replayBoundary = evidenceTimestamp
                + QuestPlayerSavedData.PROCESSED_EVIDENCE_REPLAY_MILLIS;

        assertTrue(QuestProgressRuntime.withinOwnerRetention(evidenceTimestamp, ownerBoundary));
        assertFalse(QuestProgressRuntime.withinOwnerRetention(evidenceTimestamp, ownerBoundary + 1));
        assertTrue(QuestProgressRuntime.withinReplayWindow(evidenceTimestamp, replayBoundary));
        assertFalse(QuestProgressRuntime.withinReplayWindow(evidenceTimestamp, replayBoundary + 1));
        assertTrue(QuestProgressRuntime.withinReplayWindow(
                replayBoundary + QuestProgressRuntime.FUTURE_EVIDENCE_SKEW_MILLIS,
                replayBoundary));
        assertFalse(QuestProgressRuntime.withinReplayWindow(
                replayBoundary + QuestProgressRuntime.FUTURE_EVIDENCE_SKEW_MILLIS + 1,
                replayBoundary));
        assertFalse(QuestProgressRuntime.withinReplayWindow(-1, replayBoundary));
    }

    @Test
    void longQuestIdsUseStableBoundedRewardSources() {
        Identifier longQuestId = id("quest_" + "a".repeat(300));

        String source = QuestProgressService.rewardSource(longQuestId);

        assertTrue(source.startsWith("quest_reward:"));
        assertTrue(source.length() <= 160);
        assertEquals(source, QuestProgressService.rewardSource(longQuestId));
    }

    @Test
    void oneEvidenceAtomicallyAdvancesStoryAndCurrentContractRosters() {
        QuestPlayerSavedData quests = new QuestPlayerSavedData();
        long timestamp = 12 * RepeatableContractService.DAY_MILLIS;
        var evidence = new QuestProgressService.Evidence(
                QuestDefinition.Kind.ACTIVITY, Optional.of(MINING), 1, timestamp, uuid(70_001));

        var result = QuestProgressService.applyEvidence(quests, contractDefinitions(10, 20, 30), PLAYER, evidence);
        QuestPlayerState state = quests.state(PLAYER);

        assertEquals(QuestProgressService.ProgressStatus.REWARD_PENDING, result.status());
        assertEquals(3, result.updatedQuests());
        assertEquals(3, result.completedQuests());
        assertEquals(1, state.quests().size());
        assertEquals(2, state.contracts().size());
        assertTrue(state.quests().get(QUEST).pendingReward().isPresent());
        assertTrue(state.contracts().values().stream().allMatch(entry -> entry.pendingReward().isPresent()));
        assertTrue(state.processedEvidence().containsKey(evidence.sourceTransactionId()));

        var duplicate = QuestProgressService.applyEvidence(quests, contractDefinitions(10, 20, 30), PLAYER, evidence);
        assertEquals(QuestProgressService.ProgressStatus.DUPLICATE, duplicate.status());
        assertEquals(1, state.quests().get(QUEST).objectiveProgress().get(OBJECTIVE));
        assertTrue(quests.state(PLAYER).contracts().values().stream()
                .allMatch(entry -> entry.objectiveProgress().values().stream().allMatch(progress -> progress == 1)));
    }

    @Test
    void evidenceOnlyAdvancesTheUtcWindowContainingItsTimestamp() {
        QuestPlayerSavedData quests = new QuestPlayerSavedData();
        long firstDay = 20 * RepeatableContractService.DAY_MILLIS;
        long secondDay = firstDay + RepeatableContractService.DAY_MILLIS;
        QuestDefinitionSnapshot definitions = contractDefinitions(0, 0, 0);

        assertEquals(QuestProgressService.ProgressStatus.REWARD_PENDING,
                QuestProgressService.applyEvidence(quests, definitions, PLAYER,
                        new QuestProgressService.Evidence(
                                QuestDefinition.Kind.ACTIVITY, Optional.of(MINING), 1, firstDay, uuid(70_002)))
                        .status());
        QuestPlayerState.ContractWindow firstDaily = RepeatableContractService.windowAt(
                QuestDefinition.Cadence.DAILY, firstDay);
        QuestPlayerState.ContractKey oldDaily = quests.state(PLAYER).contracts().keySet().stream()
                .filter(key -> key.window().equals(firstDaily)).findFirst().orElseThrow();

        assertEquals(QuestProgressService.ProgressStatus.REWARD_PENDING,
                QuestProgressService.applyEvidence(quests, definitions, PLAYER,
                        new QuestProgressService.Evidence(
                                QuestDefinition.Kind.ACTIVITY, Optional.of(MINING), 1, secondDay, uuid(70_003)))
                        .status());

        QuestPlayerState state = quests.state(PLAYER);
        assertEquals(1, state.contracts().get(oldDaily).objectiveProgress().get(DAILY_OBJECTIVE));
        QuestPlayerState.ContractWindow secondDaily = RepeatableContractService.windowAt(
                QuestDefinition.Cadence.DAILY, secondDay);
        QuestPlayerState.ContractKey currentDaily = state.contracts().keySet().stream()
                .filter(key -> key.window().equals(secondDaily)).findFirst().orElseThrow();
        assertEquals(1, state.contracts().get(currentDaily).objectiveProgress().get(DAILY_OBJECTIVE));
    }

    @Test
    void contractCompletionTransactionIsStableAcrossRestartAndDuplicateDelivery() {
        QuestDefinitionSnapshot definitions = contractDefinitions(0, 25, 50);
        long timestamp = 35 * RepeatableContractService.DAY_MILLIS;
        var evidence = new QuestProgressService.Evidence(
                QuestDefinition.Kind.ACTIVITY, Optional.of(MINING), 1, timestamp, uuid(70_004));
        QuestPlayerSavedData first = new QuestPlayerSavedData();
        assertEquals(QuestProgressService.ProgressStatus.REWARD_PENDING,
                QuestProgressService.applyEvidence(first, definitions, PLAYER, evidence).status());
        UUID firstTransaction = dailyContract(first.state(PLAYER), timestamp).pendingReward().orElseThrow()
                .transactionId();

        QuestPlayerSavedData restarted = roundTrip(QuestPlayerSavedData.CODEC, first);
        assertEquals(QuestProgressService.ProgressStatus.DUPLICATE,
                QuestProgressService.applyEvidence(restarted, definitions, PLAYER, evidence).status());
        assertEquals(firstTransaction, dailyContract(restarted.state(PLAYER), timestamp)
                .pendingReward().orElseThrow().transactionId());

        QuestPlayerSavedData replayed = new QuestPlayerSavedData();
        assertEquals(QuestProgressService.ProgressStatus.REWARD_PENDING,
                QuestProgressService.applyEvidence(replayed, definitions, PLAYER, evidence).status());
        assertEquals(firstTransaction, dailyContract(replayed.state(PLAYER), timestamp)
                .pendingReward().orElseThrow().transactionId());
    }

    @Test
    void pendingContractRewardsRecoverCurrencyAndXpExactlyOnce() {
        QuestPlayerSavedData quests = new QuestPlayerSavedData();
        PlatformSavedData platform = new PlatformSavedData();
        RpgPlayerSavedData rpg = new RpgPlayerSavedData();
        QuestDefinitionSnapshot definitions = contractDefinitions(0, 25, 50);
        long timestamp = 50 * RepeatableContractService.DAY_MILLIS;
        assertEquals(QuestProgressService.ProgressStatus.REWARD_PENDING,
                QuestProgressService.applyEvidence(quests, definitions, PLAYER,
                        new QuestProgressService.Evidence(
                                QuestDefinition.Kind.ACTIVITY, Optional.of(MINING), 1, timestamp, uuid(70_005)))
                        .status());

        assertEquals(QuestProgressService.RewardStatus.RETRY_REQUIRED,
                QuestProgressService.recoverRewards(
                        quests, definitions, platform, rpg, rpgDefinitions(), PLAYER, timestamp + 1,
                        0, 1_000_000).status());
        assertEquals(QuestProgressService.RewardStatus.COMPLETED,
                QuestProgressService.recoverRewards(
                        quests, definitions, platform, rpg, rpgDefinitions(), PLAYER, timestamp + 2,
                        0, 1_000_000).status());
        assertEquals(75, platform.economyBalance(PLAYER).orElseThrow());
        assertEquals(18, rpg.state(PLAYER).activityXp().get(MINING));
        assertTrue(quests.state(PLAYER).contracts().values().stream()
                .allMatch(entry -> entry.completion().isPresent() && entry.pendingReward().isEmpty()));

        assertEquals(QuestProgressService.RewardStatus.NOTHING_PENDING,
                QuestProgressService.recoverRewards(
                        quests, definitions, platform, rpg, rpgDefinitions(), PLAYER, timestamp + 3,
                        0, 1_000_000).status());
        assertEquals(75, platform.economyBalance(PLAYER).orElseThrow());
        assertEquals(18, rpg.state(PLAYER).activityXp().get(MINING));
    }

    private static org.dldyou.rovenfall.mobs.BossRewardOperation boss(
            org.dldyou.rovenfall.mobs.BossRewardOperation.Phase phase) {
        return new org.dldyou.rovenfall.mobs.BossRewardOperation(
                uuid(50), id("rift_warden"), uuid(51), PLAYER,
                org.dldyou.rovenfall.world.WorldTopology.HUB, net.minecraft.core.BlockPos.ZERO,
                10, 10, 1, 1, 0, 0, 10_000, 4_000, List.of(), phase);
    }

    private static EconomyTransactionReceipt receiptWithFlags(
            EconomyTransactionReceipt receipt,
            Optional<UUID> reversedBy,
            Optional<UUID> invalidatedByRestore) {
        return new EconomyTransactionReceipt(
                receipt.timestampEpochMillis(), receipt.actorId(), receipt.playerId(), receipt.kind(),
                receipt.amount(), receipt.claim(), receipt.shopId(), receipt.offerId(), receipt.item(),
                receipt.quantity(), receipt.stockBefore(), receipt.stockAfter(), receipt.originalTransactionId(),
                reversedBy, invalidatedByRestore, receipt.compensationDecision());
    }

    private static QuestDefinitionSnapshot definitions(int version, long currency, long xp) {
        QuestDefinition definition = new QuestDefinition(
                "quest.rovenfall.first_steps", "quest.rovenfall.first_steps.description", version, List.of(),
                List.of(new QuestDefinition.Objective(
                        OBJECTIVE, QuestDefinition.Kind.ACTIVITY, Optional.of(MINING), 1)),
                new QuestDefinition.Rewards(currency, xp == 0 ? Optional.empty()
                        : Optional.of(new QuestDefinition.ActivityXpReward(MINING, xp))));
        return QuestDefinitionSnapshot.compile(List.of(new QuestDefinitionSnapshot.Source(
                id("rovenfall/quests/first_steps.json"), "test", QUEST, definition)));
    }

    private static final Identifier DAILY_CONTRACT = id("daily_mining");
    private static final Identifier WEEKLY_CONTRACT = id("weekly_mining");
    private static final Identifier DAILY_OBJECTIVE = id("daily_mining/progress");
    private static final Identifier WEEKLY_OBJECTIVE = id("weekly_mining/progress");

    private static QuestDefinitionSnapshot contractDefinitions(long storyCurrency, long dailyCurrency, long weeklyCurrency) {
        QuestDefinition story = new QuestDefinition(
                "quest.rovenfall.first_steps", "quest.rovenfall.first_steps.description", 1, List.of(),
                List.of(new QuestDefinition.Objective(
                        OBJECTIVE, QuestDefinition.Kind.ACTIVITY, Optional.of(MINING), 1)),
                new QuestDefinition.Rewards(storyCurrency, Optional.empty()));
        QuestDefinition daily = contractDefinition(
                "quest.rovenfall.daily_mining", DAILY_OBJECTIVE, dailyCurrency, 7,
                QuestDefinition.Cadence.DAILY);
        QuestDefinition weekly = contractDefinition(
                "quest.rovenfall.weekly_mining", WEEKLY_OBJECTIVE, weeklyCurrency, 11,
                QuestDefinition.Cadence.WEEKLY);
        return QuestDefinitionSnapshot.compile(List.of(
                new QuestDefinitionSnapshot.Source(id("rovenfall/quests/first_steps.json"), "test", QUEST, story),
                new QuestDefinitionSnapshot.Source(id("rovenfall/quests/contracts/daily_mining.json"), "test",
                        DAILY_CONTRACT, daily),
                new QuestDefinitionSnapshot.Source(id("rovenfall/quests/contracts/weekly_mining.json"), "test",
                        WEEKLY_CONTRACT, weekly)));
    }

    private static QuestDefinition contractDefinition(
            String key,
            Identifier objective,
            long currency,
            long xp,
            QuestDefinition.Cadence cadence) {
        return new QuestDefinition(key, key + ".description", 1, List.of(),
                List.of(new QuestDefinition.Objective(
                        objective, QuestDefinition.Kind.ACTIVITY, Optional.of(MINING), 1)),
                new QuestDefinition.Rewards(currency,
                        Optional.of(new QuestDefinition.ActivityXpReward(MINING, xp))),
                Optional.of(new QuestDefinition.Contract(cadence)));
    }

    private static QuestPlayerState.QuestEntry dailyContract(QuestPlayerState state, long timestamp) {
        QuestPlayerState.ContractWindow window = RepeatableContractService.windowAt(
                QuestDefinition.Cadence.DAILY, timestamp);
        return state.contracts().entrySet().stream()
                .filter(entry -> entry.getKey().window().equals(window))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElseThrow();
    }

    private static RpgDefinitionSnapshot rpgDefinitions() {
        return RpgDefinitionSnapshot.compile(List.of(new RpgDefinitionSnapshot.ActivitySource(
                id("activities/mining"), "test", MINING,
                new ActivityDefinition("activity.rovenfall.mining", List.of(1_000L)))), List.of(), List.of());
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("rovenfall", path);
    }

    private static UUID uuid(long value) {
        return new UUID(0L, value);
    }
}
