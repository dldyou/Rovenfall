package org.dldyou.rovenfall.quest;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.resources.Identifier;
import org.dldyou.rovenfall.Rovenfall;
import org.dldyou.rovenfall.administration.AdministrationService;
import org.dldyou.rovenfall.administration.AuditEntry;
import org.dldyou.rovenfall.administration.EconomyConfig;
import org.dldyou.rovenfall.administration.EconomyService;
import org.dldyou.rovenfall.administration.EconomyTransactionReceipt;
import org.dldyou.rovenfall.administration.PlatformSavedData;
import org.dldyou.rovenfall.mobs.BossRewardOperation;
import org.dldyou.rovenfall.rpg.ActivityXpAwardService;
import org.dldyou.rovenfall.rpg.RpgDefinitionSnapshot;
import org.dldyou.rovenfall.rpg.RpgPlayerSavedData;
import org.dldyou.rovenfall.rpg.RpgPlayerState;

/** Server-only quest mutation and captured cross-root reward recovery boundary. */
public final class QuestProgressService {
    private static final UUID ZERO_UUID = new UUID(0L, 0L);
    private static final long AUDIT_RETENTION_MILLIS = Duration.ofDays(30).toMillis();
    static final int MAX_REWARD_STEPS_PER_RECOVERY = 8;
    static final int MAX_COMPLETED_RECONCILIATIONS_PER_RECOVERY = 32;
    private static final Identifier QUEST_COMPLETED = id("quest_completed");
    private static final Identifier QUEST_PROGRESS_DENIED = id("quest_progress_denied");

    private QuestProgressService() {
    }

    static ProgressResult applyEvidence(
            QuestPlayerSavedData state,
            QuestDefinitionSnapshot definitions,
            UUID playerId,
            Evidence evidence) {
        if (state == null || definitions == null || playerId == null || ZERO_UUID.equals(playerId)
                || evidence == null || !evidence.isValid()) {
            return new ProgressResult(ProgressStatus.INVALID, 0, 0, false);
        }
        if (!state.isWritable()) {
            return new ProgressResult(ProgressStatus.READ_ONLY, 0, 0, false);
        }
        if (state.state(playerId).processedEvidence().containsKey(evidence.sourceTransactionId())) {
            return new ProgressResult(ProgressStatus.DUPLICATE, 0, 0, false);
        }
        if (hasStaleMatchingDefinition(state.state(playerId), definitions, evidence)) {
            return new ProgressResult(ProgressStatus.STALE_DEFINITION, 0, 0, false);
        }
        RepeatableContractService.AssignmentResult assignment = RepeatableContractService.ensureAssignments(
                state, definitions, playerId, evidence.timestampEpochMillis());
        if (assignment.status() == RepeatableContractService.AssignmentStatus.STATE_FULL) {
            return new ProgressResult(ProgressStatus.STATE_FULL, 0, 0, false);
        }
        if (assignment.status() == RepeatableContractService.AssignmentStatus.CONCURRENT_CHANGE) {
            return new ProgressResult(ProgressStatus.CONCURRENT_CHANGE, 0, 0, false);
        }
        if (assignment.status() == RepeatableContractService.AssignmentStatus.INVALID) {
            return new ProgressResult(ProgressStatus.INVALID, 0, 0, false);
        }
        QuestPlayerState current = state.state(playerId);
        if (current.processedEvidence().containsKey(evidence.sourceTransactionId())) {
            return new ProgressResult(ProgressStatus.DUPLICATE, 0, 0, false);
        }

        Map<Identifier, QuestPlayerState.QuestEntry> quests = new HashMap<>(current.quests());
        Map<QuestPlayerState.ContractKey, QuestPlayerState.QuestEntry> contracts =
                new HashMap<>(current.contracts());
        int updatedQuests = 0;
        int completedQuests = 0;
        for (Map.Entry<Identifier, QuestDefinition> definitionEntry : definitions.storyQuests().entrySet().stream()
                .sorted(Map.Entry.comparingByKey()).toList()) {
            Identifier questId = definitionEntry.getKey();
            QuestDefinition definition = definitionEntry.getValue();
            QuestPlayerState.QuestEntry retained = quests.get(questId);
            if (retained != null && retained.definitionVersion() != definition.version()
                    && retained.completion().isEmpty() && matches(definition, evidence)) {
                return new ProgressResult(ProgressStatus.STALE_DEFINITION, 0, 0, false);
            }
            if (retained != null && (retained.completion().isPresent() || retained.pendingReward().isPresent())) {
                continue;
            }
            if (!prerequisitesCompleted(definition, definitions, quests)) {
                continue;
            }
            QuestPlayerState.QuestEntry entry = retained == null
                    ? new QuestPlayerState.QuestEntry(definition.version(), Map.of(), Optional.empty())
                    : retained;
            Map<Identifier, Long> progress = new HashMap<>(entry.objectiveProgress());
            boolean changed = false;
            for (QuestDefinition.Objective objective : definition.objectives()) {
                if (!matches(objective, evidence)) {
                    continue;
                }
                long before = progress.getOrDefault(objective.id(), 0L);
                long after = Math.min(objective.requiredCount(), saturatingAdd(before, evidence.count()));
                if (after != before) {
                    progress.put(objective.id(), after);
                    changed = true;
                }
            }
            if (!changed) {
                continue;
            }
            Optional<QuestPlayerState.RewardOperation> pending = Optional.empty();
            if (complete(definition, progress)) {
                QuestDefinition.Rewards rewards = definition.rewards();
                pending = Optional.of(new QuestPlayerState.RewardOperation(
                        definition.version(), completionTransaction(playerId, questId, definition.version()),
                        rewards.currency(), rewards.activityXp().map(QuestDefinition.ActivityXpReward::activity),
                        rewards.activityXp().map(QuestDefinition.ActivityXpReward::amount).orElse(0L),
                        evidence.timestampEpochMillis(), QuestPlayerState.RewardOperation.Phase.CAPTURED));
                completedQuests++;
            }
            quests.put(questId, new QuestPlayerState.QuestEntry(
                    definition.version(), progress, pending, Optional.empty()));
            updatedQuests++;
        }
        for (Map.Entry<QuestPlayerState.ContractKey, QuestPlayerState.QuestEntry> contractEntry
                : current.contracts().entrySet()) {
            QuestPlayerState.ContractKey key = contractEntry.getKey();
            if (!RepeatableContractService.contains(key.window(), evidence.timestampEpochMillis())) {
                continue;
            }
            QuestDefinition definition = definitions.quest(key.templateId()).orElse(null);
            if (definition == null || definition.contract()
                    .filter(contract -> contract.cadence() == key.window().cadence()).isEmpty()) {
                continue;
            }
            QuestPlayerState.QuestEntry retained = contractEntry.getValue();
            if (retained.definitionVersion() != definition.version()
                    && retained.completion().isEmpty() && matches(definition, evidence)) {
                return new ProgressResult(ProgressStatus.STALE_DEFINITION, 0, 0, false);
            }
            if (retained.completion().isPresent() || retained.pendingReward().isPresent()) {
                continue;
            }
            Map<Identifier, Long> progress = new HashMap<>(retained.objectiveProgress());
            boolean changed = false;
            for (QuestDefinition.Objective objective : definition.objectives()) {
                if (!matches(objective, evidence)) {
                    continue;
                }
                long before = progress.getOrDefault(objective.id(), 0L);
                long after = Math.min(objective.requiredCount(), saturatingAdd(before, evidence.count()));
                if (after != before) {
                    progress.put(objective.id(), after);
                    changed = true;
                }
            }
            if (!changed) {
                continue;
            }
            Optional<QuestPlayerState.RewardOperation> pending = Optional.empty();
            if (complete(definition, progress)) {
                QuestDefinition.Rewards rewards = definition.rewards();
                pending = Optional.of(new QuestPlayerState.RewardOperation(
                        definition.version(), completionTransaction(playerId, key, definition.version()),
                        rewards.currency(), rewards.activityXp().map(QuestDefinition.ActivityXpReward::activity),
                        rewards.activityXp().map(QuestDefinition.ActivityXpReward::amount).orElse(0L),
                        evidence.timestampEpochMillis(), QuestPlayerState.RewardOperation.Phase.CAPTURED));
                completedQuests++;
            }
            contracts.put(key, new QuestPlayerState.QuestEntry(
                    definition.version(), progress, pending, Optional.empty()));
            updatedQuests++;
        }
        if (updatedQuests == 0) {
            return new ProgressResult(ProgressStatus.IGNORED, 0, 0, false);
        }

        Map<UUID, QuestPlayerState.ProcessedEvidence> processed =
                new LinkedHashMap<>(current.processedEvidence());
        if (processed.size() >= QuestPlayerState.MAX_PROCESSED_EVIDENCE) {
            return new ProgressResult(ProgressStatus.STATE_FULL, 0, 0, false);
        }
        processed.put(evidence.sourceTransactionId(), new QuestPlayerState.ProcessedEvidence(
                evidence.timestampEpochMillis(), evidence.kind()));
        QuestPlayerState updated = new QuestPlayerState(
                quests, processed, contracts, current.initializedContractWindows());
        boolean committed = state.commit(playerId, current, updated);
        return new ProgressResult(
                committed ? (completedQuests > 0 ? ProgressStatus.REWARD_PENDING : ProgressStatus.SUCCESS)
                        : ProgressStatus.CONCURRENT_CHANGE,
                committed ? updatedQuests : 0,
                committed ? completedQuests : 0,
                committed);
    }

    static ProgressResult applyEvidence(
            QuestPlayerSavedData state,
            QuestDefinitionSnapshot definitions,
            PlatformSavedData platform,
            UUID playerId,
            Evidence evidence) {
        ProgressResult result = applyEvidence(state, definitions, playerId, evidence);
        if (platform != null && evidence != null
                && (result.status() == ProgressStatus.STALE_DEFINITION
                        || result.status() == ProgressStatus.STATE_FULL
                        || result.status() == ProgressStatus.READ_ONLY
                        || result.status() == ProgressStatus.CONCURRENT_CHANGE)) {
            recordDenial(platform, playerId, evidence.sourceTransactionId(),
                    result.status().name().toLowerCase(java.util.Locale.ROOT),
                    evidence.timestampEpochMillis());
        }
        return result;
    }

    public static RewardResult recoverRewards(
            QuestPlayerSavedData quests,
            QuestDefinitionSnapshot questDefinitions,
            PlatformSavedData platform,
            RpgPlayerSavedData rpg,
            RpgDefinitionSnapshot rpgDefinitions,
            UUID playerId,
            long timestampEpochMillis) {
        return recoverRewards(quests, questDefinitions, platform, rpg, rpgDefinitions, playerId,
                timestampEpochMillis, EconomyConfig.initialBalance(), EconomyConfig.maximumBalance(),
                new RecoveryCursor());
    }

    static RewardResult recoverRewards(
            QuestPlayerSavedData quests,
            QuestDefinitionSnapshot questDefinitions,
            PlatformSavedData platform,
            RpgPlayerSavedData rpg,
            RpgDefinitionSnapshot rpgDefinitions,
            UUID playerId,
            long timestampEpochMillis,
            long initialBalance,
            long maximumBalance) {
        return recoverRewards(quests, questDefinitions, platform, rpg, rpgDefinitions, playerId,
                timestampEpochMillis, initialBalance, maximumBalance, new RecoveryCursor());
    }

    static RewardResult recoverRewards(
            QuestPlayerSavedData quests,
            QuestDefinitionSnapshot questDefinitions,
            PlatformSavedData platform,
            RpgPlayerSavedData rpg,
            RpgDefinitionSnapshot rpgDefinitions,
            UUID playerId,
            long timestampEpochMillis,
            long initialBalance,
            long maximumBalance,
            RecoveryCursor cursor) {
        if (quests == null || questDefinitions == null || platform == null || rpg == null
                || rpgDefinitions == null || playerId == null || ZERO_UUID.equals(playerId)
                || timestampEpochMillis < 0 || initialBalance < 0 || maximumBalance < initialBalance
                || cursor == null) {
            return new RewardResult(RewardStatus.INVALID, 0);
        }
        int completed = 0;
        Set<RewardKey> failedQuests = new HashSet<>();
        RewardStatus firstFailure = null;
        int steps = 0;
        for (; steps < MAX_REWARD_STEPS_PER_RECOVERY; steps++) {
            QuestPlayerState current = quests.state(playerId);
            RewardCandidate pending = nextPendingReward(
                    current, failedQuests, cursor.pendingAfter);
            if (pending == null) {
                cursor.pendingAfter = null;
                break;
            }
            RewardKey rewardKey = pending.key();
            Identifier questId = rewardKey.questId();
            cursor.pendingAfter = rewardKey;
            QuestPlayerState.QuestEntry entry = pending.entry();
            QuestPlayerState.RewardOperation operation = entry.pendingReward().orElseThrow();
            RewardStep step = applyRewardStep(
                    platform, rpg, rpgDefinitions, playerId, questId, operation, timestampEpochMillis,
                    initialBalance, maximumBalance);
            if (!step.applied()) {
                if (!auditExpired(operation.startedAtEpochMillis(), timestampEpochMillis)) {
                    recordDenial(platform, playerId, operation.transactionId(),
                            step.status().name().toLowerCase(java.util.Locale.ROOT),
                            operation.startedAtEpochMillis());
                }
                failedQuests.add(rewardKey);
                if (firstFailure == null) {
                    firstFailure = step.status();
                }
                continue;
            }
            Map<Identifier, QuestPlayerState.QuestEntry> updatedQuests = new HashMap<>(current.quests());
            Map<QuestPlayerState.ContractKey, QuestPlayerState.QuestEntry> updatedContracts =
                    new HashMap<>(current.contracts());
            boolean completedStep = step.completed();
            QuestPlayerState.QuestEntry updatedEntry;
            if (step.completed()) {
                long completedAt = Math.max(timestampEpochMillis, operation.startedAtEpochMillis());
                updatedEntry = new QuestPlayerState.QuestEntry(
                        entry.definitionVersion(), entry.objectiveProgress(), Optional.empty(),
                        Optional.of(new QuestPlayerState.CompletionReceipt(
                                entry.definitionVersion(), operation.transactionId(), completedAt, operation)));
            } else {
                updatedEntry = new QuestPlayerState.QuestEntry(
                        entry.definitionVersion(), entry.objectiveProgress(),
                        Optional.of(operation.atPhase(step.nextPhase())), Optional.empty());
            }
            if (rewardKey.contractKey().isPresent()) {
                updatedContracts.put(rewardKey.contractKey().orElseThrow(), updatedEntry);
            } else {
                updatedQuests.put(questId, updatedEntry);
            }
            if (!quests.commit(playerId, current, new QuestPlayerState(
                    updatedQuests, current.processedEvidence(), updatedContracts,
                    current.initializedContractWindows()))) {
                return new RewardResult(RewardStatus.CONCURRENT_CHANGE, completed);
            }
            if (completedStep) {
                completed++;
            }
        }
        CompletedReconciliation reconciliation = reconcileCompletedRewards(
                quests.state(playerId), platform, rpg, rpgDefinitions, playerId, timestampEpochMillis,
                initialBalance, maximumBalance, cursor);
        RewardStatus failure = firstFailure != null
                ? firstFailure : reconciliation.failure().orElse(null);
        int affected = completed + reconciliation.repaired();
        return new RewardResult(failure != null ? failure
                : steps >= MAX_REWARD_STEPS_PER_RECOVERY ? RewardStatus.RETRY_REQUIRED
                : affected > 0 ? RewardStatus.COMPLETED : RewardStatus.NOTHING_PENDING, affected);
    }

    private static CompletedReconciliation reconcileCompletedRewards(
            QuestPlayerState state,
            PlatformSavedData platform,
            RpgPlayerSavedData rpg,
            RpgDefinitionSnapshot rpgDefinitions,
            UUID playerId,
            long timestamp,
            long initialBalance,
            long maximumBalance,
            RecoveryCursor cursor) {
        int repaired = 0;
        RewardStatus firstFailure = null;
        java.util.List<RewardCandidate> candidates =
                completedReconciliationBatch(state, cursor.completedAfter);
        for (RewardCandidate quest : candidates) {
            Optional<QuestPlayerState.CompletionReceipt> completion = quest.entry().completion();
            if (completion.isEmpty() || completion.orElseThrow().rewardOperation().isEmpty()) {
                continue;
            }
            QuestPlayerState.RewardOperation operation = completion.orElseThrow().rewardOperation().orElseThrow();
            Reconciliation result = reconcileRewardEffects(
                    platform, rpg, rpgDefinitions, playerId, quest.key().questId(), operation, timestamp,
                    initialBalance, maximumBalance, true, true, true);
            if (!result.applied()) {
                if (!auditExpired(operation.startedAtEpochMillis(), timestamp)) {
                    recordDenial(platform, playerId, operation.transactionId(),
                            result.status().name().toLowerCase(java.util.Locale.ROOT),
                            operation.startedAtEpochMillis());
                }
                if (firstFailure == null) {
                    firstFailure = result.status();
                }
                continue;
            }
            if (result.changed()) {
                repaired++;
            }
        }
        cursor.completedAfter = candidates.isEmpty() ? null : candidates.getLast().key();
        return new CompletedReconciliation(repaired, Optional.ofNullable(firstFailure));
    }

    private static RewardCandidate nextPendingReward(
            QuestPlayerState state, Set<RewardKey> excluded, RewardKey afterExclusive) {
        List<RewardCandidate> ordered = rewardCandidates(state).stream()
                .filter(candidate -> candidate.entry().pendingReward().isPresent())
                .filter(candidate -> !excluded.contains(candidate.key()))
                .toList();
        if (ordered.isEmpty()) {
            return null;
        }
        if (afterExclusive != null) {
            RewardCandidate after = ordered.stream()
                    .filter(candidate -> candidate.key().compareTo(afterExclusive) > 0)
                    .findFirst().orElse(null);
            if (after != null) {
                return after;
            }
        }
        return ordered.getFirst();
    }

    private static List<RewardCandidate> completedReconciliationBatch(
            QuestPlayerState state, RewardKey afterExclusive) {
        List<RewardCandidate> ordered = rewardCandidates(state).stream()
                .filter(candidate -> candidate.entry().completion()
                        .flatMap(QuestPlayerState.CompletionReceipt::rewardOperation).isPresent())
                .toList();
        if (ordered.isEmpty()) {
            return List.of();
        }
        List<RewardCandidate> result = new java.util.ArrayList<>(
                MAX_COMPLETED_RECONCILIATIONS_PER_RECOVERY);
        if (afterExclusive != null) {
            ordered.stream()
                    .filter(candidate -> candidate.key().compareTo(afterExclusive) > 0)
                    .limit(MAX_COMPLETED_RECONCILIATIONS_PER_RECOVERY)
                    .forEach(result::add);
        }
        if (result.size() < MAX_COMPLETED_RECONCILIATIONS_PER_RECOVERY) {
            ordered.stream()
                    .filter(candidate -> afterExclusive == null
                            || candidate.key().compareTo(afterExclusive) <= 0)
                    .limit(MAX_COMPLETED_RECONCILIATIONS_PER_RECOVERY - result.size())
                    .forEach(result::add);
        }
        return List.copyOf(result);
    }

    private static List<RewardCandidate> rewardCandidates(QuestPlayerState state) {
        List<RewardCandidate> result = new java.util.ArrayList<>(
                state.quests().size() + state.contracts().size());
        state.quests().forEach((id, entry) -> result.add(
                new RewardCandidate(RewardKey.story(id), entry)));
        state.contracts().forEach((key, entry) -> result.add(
                new RewardCandidate(RewardKey.contract(key), entry)));
        result.sort(Comparator.comparing(RewardCandidate::key));
        return List.copyOf(result);
    }

    public static Optional<Evidence> evidence(UUID transactionId, EconomyTransactionReceipt receipt) {
        if (transactionId == null || receipt == null
                || receipt.reversedBy().isPresent() || receipt.invalidatedByRestore().isPresent()) {
            return Optional.empty();
        }
        return switch (receipt.kind()) {
            case PURCHASE, SALE -> receipt.shopId().map(shop -> new Evidence(
                    QuestDefinition.Kind.SHOP_TRADE, Optional.of(shop), 1, receipt.timestampEpochMillis(),
                    transactionId));
            case CLAIM_PURCHASE -> Optional.of(new Evidence(
                    QuestDefinition.Kind.CLAIM_PURCHASE, Optional.empty(), 1, receipt.timestampEpochMillis(),
                    transactionId));
            default -> Optional.empty();
        };
    }

    public static Optional<Evidence> evidence(RpgPlayerState.ProgressionProvenance provenance) {
        if (provenance == null || provenance.kind() != RpgPlayerState.ProgressionProvenance.Kind.ACTIVITY_XP
                || provenance.source().startsWith("boss_reward:")
                || provenance.source().startsWith("quest_reward:")) {
            return Optional.empty();
        }
        return Optional.of(new Evidence(
                QuestDefinition.Kind.ACTIVITY, Optional.of(provenance.target()), provenance.amount(),
                provenance.timestamp(), provenance.transactionId()));
    }

    static boolean shouldCaptureActivity(
            QuestPlayerSavedData state,
            QuestDefinitionSnapshot definitions,
            UUID playerId,
            Identifier activityId) {
        return shouldCaptureActivity(
                state, definitions, playerId, activityId, System.currentTimeMillis());
    }

    static boolean shouldCaptureActivity(
            QuestPlayerSavedData state,
            QuestDefinitionSnapshot definitions,
            UUID playerId,
            Identifier activityId,
            long timestampEpochMillis) {
        if (state == null || definitions == null || playerId == null || ZERO_UUID.equals(playerId)
                || activityId == null || timestampEpochMillis < 0) {
            return false;
        }
        RepeatableContractService.ensureAssignments(
                state, definitions, playerId, timestampEpochMillis);
        QuestPlayerState current = state.state(playerId);
        for (Map.Entry<Identifier, QuestDefinition> definitionEntry : definitions.storyQuests().entrySet()) {
            QuestDefinition definition = definitionEntry.getValue();
            QuestPlayerState.QuestEntry retained = current.quests().get(definitionEntry.getKey());
            if (retained != null && (retained.completion().isPresent() || retained.pendingReward().isPresent())) {
                continue;
            }
            if (!prerequisitesCompleted(definition, definitions, current.quests())) {
                continue;
            }
            if (capturesActivity(definition, retained, activityId)) {
                return true;
            }
        }
        for (QuestPlayerState.ContractKey key
                : RepeatableContractService.currentKeys(current, timestampEpochMillis)) {
            QuestPlayerState.QuestEntry retained = current.contracts().get(key);
            if (retained.completion().isPresent() || retained.pendingReward().isPresent()) {
                continue;
            }
            QuestDefinition definition = definitions.quest(key.templateId()).orElse(null);
            if (definition != null && definition.contract()
                    .filter(contract -> contract.cadence() == key.window().cadence()).isPresent()
                    && capturesActivity(definition, retained, activityId)) {
                return true;
            }
        }
        return false;
    }

    private static boolean capturesActivity(
            QuestDefinition definition,
            QuestPlayerState.QuestEntry retained,
            Identifier activityId) {
        for (QuestDefinition.Objective objective : definition.objectives()) {
            long progress = retained == null
                    ? 0L
                    : retained.objectiveProgress().getOrDefault(objective.id(), 0L);
            if (objective.kind() == QuestDefinition.Kind.ACTIVITY
                    && objective.target().filter(activityId::equals).isPresent()
                    && progress < objective.requiredCount()) {
                return true;
            }
        }
        return false;
    }

    public static Optional<Evidence> evidence(UUID transactionId, BossRewardOperation operation) {
        if (transactionId == null || operation == null
                || operation.phase() != BossRewardOperation.Phase.COMPLETED) {
            return Optional.empty();
        }
        return Optional.of(new Evidence(
                QuestDefinition.Kind.BOSS_DEFEAT, Optional.of(operation.bossId()), 1,
                operation.createdAtEpochMillis(), transactionId));
    }

    private static RewardStep applyRewardStep(
            PlatformSavedData platform,
            RpgPlayerSavedData rpg,
            RpgDefinitionSnapshot rpgDefinitions,
            UUID playerId,
            Identifier questId,
            QuestPlayerState.RewardOperation operation,
            long timestamp,
            long initialBalance,
            long maximumBalance) {
        return switch (operation.phase()) {
            case CAPTURED -> {
                Reconciliation result = reconcileRewardEffects(
                        platform, rpg, rpgDefinitions, playerId, questId, operation, timestamp,
                        initialBalance, maximumBalance, true, false, false);
                if (!result.applied()) {
                    yield RewardStep.failed(result.status());
                }
                yield RewardStep.next(QuestPlayerState.RewardOperation.Phase.CURRENCY_APPLIED);
            }
            case CURRENCY_APPLIED -> {
                Reconciliation result = reconcileRewardEffects(
                        platform, rpg, rpgDefinitions, playerId, questId, operation, timestamp,
                        initialBalance, maximumBalance, true, true, false);
                if (!result.applied()) {
                    yield RewardStep.failed(result.status());
                }
                yield RewardStep.next(QuestPlayerState.RewardOperation.Phase.XP_APPLIED);
            }
            case XP_APPLIED -> {
                Reconciliation result = reconcileRewardEffects(
                        platform, rpg, rpgDefinitions, playerId, questId, operation, timestamp,
                        initialBalance, maximumBalance, true, true, true);
                if (!result.applied()) {
                    yield RewardStep.failed(result.status());
                }
                yield RewardStep.next(QuestPlayerState.RewardOperation.Phase.AUDIT_APPLIED);
            }
            case AUDIT_APPLIED -> {
                Reconciliation result = reconcileRewardEffects(
                        platform, rpg, rpgDefinitions, playerId, questId, operation, timestamp,
                        initialBalance, maximumBalance, true, true, true);
                yield result.applied() ? RewardStep.complete() : RewardStep.failed(result.status());
            }
        };
    }

    private static Reconciliation reconcileRewardEffects(
            PlatformSavedData platform,
            RpgPlayerSavedData rpg,
            RpgDefinitionSnapshot rpgDefinitions,
            UUID playerId,
            Identifier questId,
            QuestPlayerState.RewardOperation operation,
            long timestamp,
            long initialBalance,
            long maximumBalance,
            boolean ensureCurrency,
            boolean ensureXp,
            boolean ensureAudit) {
        UUID currencyTransaction = operation.transactionId();
        UUID xpTransaction = childTransaction(operation.transactionId(), "activity_xp");
        boolean currencyPresent = exactQuestCurrencyReceipt(
                platform, currencyTransaction, playerId, operation.currency());
        boolean xpPresent = operation.activity().isEmpty()
                || rpg.questRewardReceipt(xpTransaction)
                        .filter(receipt -> receipt.matches(playerId, operation.activity().orElseThrow(),
                                operation.activityXp(), operation.startedAtEpochMillis(), rewardSource(questId)))
                        .isPresent();
        if (operation.activity().filter(activity -> rpgDefinitions.activity(activity).isEmpty()).isPresent()
                && (!currencyPresent || !xpPresent)) {
            return Reconciliation.failed(RewardStatus.UNKNOWN_REWARD_ACTIVITY);
        }
        boolean changed = false;
        if (ensureCurrency && !currencyPresent) {
            if (operation.currency() == 0) {
                if (!platform.reserveQuestCompletionReceipt(
                        playerId, currencyTransaction, operation.startedAtEpochMillis())) {
                    return Reconciliation.failed(RewardStatus.ECONOMY_REJECTED);
                }
                changed = true;
            } else {
                var result = EconomyService.awardQuestReward(
                        platform, playerId, operation.currency(), rewardSource(questId),
                        operation.startedAtEpochMillis(), currencyTransaction, initialBalance, maximumBalance);
                if (result.status() != EconomyService.TransactionStatus.SUCCESS
                        && result.status() != EconomyService.TransactionStatus.DUPLICATE_TRANSACTION) {
                    return Reconciliation.failed(RewardStatus.ECONOMY_REJECTED);
                }
                changed = result.status() == EconomyService.TransactionStatus.SUCCESS;
            }
            currencyPresent = true;
        }
        if (ensureXp && operation.activity().isPresent() && !xpPresent) {
            var result = ActivityXpAwardService.awardQuestReward(
                    rpg, rpgDefinitions, playerId, operation.activity().orElseThrow(), operation.activityXp(),
                    operation.startedAtEpochMillis(), xpTransaction, rewardSource(questId));
            if (result.status() != ActivityXpAwardService.Status.SUCCESS
                    && result.status() != ActivityXpAwardService.Status.DUPLICATE) {
                return Reconciliation.failed(RewardStatus.RPG_REJECTED);
            }
            changed = result.status() == ActivityXpAwardService.Status.SUCCESS || changed;
            xpPresent = true;
        }
        if (ensureAudit && !auditExpired(operation.startedAtEpochMillis(), timestamp)) {
            AuditEntry audit = new AuditEntry(
                    operation.startedAtEpochMillis(), AdministrationService.SYSTEM_ACTOR, QUEST_COMPLETED,
                    playerId + "/" + questId, Optional.empty(), Optional.empty(),
                    "pending", "completed", "server_outcome", childTransaction(operation.transactionId(), "audit"));
            boolean alreadyPresent = exactQuestCompletionReceipt(
                    platform, operation.transactionId(), playerId, operation.currency(),
                    operation.startedAtEpochMillis());
            if (!platform.recordQuestCompletionAudit(
                    playerId, operation.transactionId(), operation.currency(), audit)) {
                return Reconciliation.failed(RewardStatus.AUDIT_REJECTED);
            }
            changed = changed || !alreadyPresent;
        }
        return Reconciliation.success(changed);
    }

    private static boolean exactQuestCurrencyReceipt(
            PlatformSavedData platform, UUID transactionId, UUID playerId, long amount) {
        return platform.economyReceipt(transactionId).filter(receipt ->
                receipt.actorId().equals(AdministrationService.SYSTEM_ACTOR)
                        && receipt.playerId().equals(playerId)
                        && (receipt.kind() == EconomyTransactionReceipt.Kind.QUEST_REWARD
                                || receipt.kind() == EconomyTransactionReceipt.Kind.QUEST_COMPLETION)
                        && receipt.amount() == amount
                        && receipt.reversedBy().isEmpty()
                        && receipt.invalidatedByRestore().isEmpty()).isPresent();
    }

    private static boolean exactQuestCompletionReceipt(
            PlatformSavedData platform,
            UUID transactionId,
            UUID playerId,
            long amount,
            long timestamp) {
        return platform.economyReceipt(transactionId).filter(receipt ->
                receipt.actorId().equals(AdministrationService.SYSTEM_ACTOR)
                        && receipt.playerId().equals(playerId)
                        && receipt.kind() == EconomyTransactionReceipt.Kind.QUEST_COMPLETION
                        && receipt.amount() == amount
                        && receipt.timestampEpochMillis() == timestamp
                        && receipt.reversedBy().isEmpty()
                        && receipt.invalidatedByRestore().isEmpty()).isPresent();
    }

    private static boolean auditExpired(long startedAt, long now) {
        return now >= startedAt && now - startedAt >= AUDIT_RETENTION_MILLIS;
    }

    private record Reconciliation(boolean applied, boolean changed, RewardStatus status) {
        static Reconciliation success(boolean changed) {
            return new Reconciliation(true, changed, RewardStatus.COMPLETED);
        }

        static Reconciliation failed(RewardStatus status) {
            return new Reconciliation(false, false, status);
        }
    }

    private record CompletedReconciliation(int repaired, Optional<RewardStatus> failure) {
    }

    private static boolean matches(QuestDefinition definition, Evidence evidence) {
        return definition.objectives().stream().anyMatch(objective -> matches(objective, evidence));
    }

    private static boolean hasStaleMatchingDefinition(
            QuestPlayerState state,
            QuestDefinitionSnapshot definitions,
            Evidence evidence) {
        for (Map.Entry<Identifier, QuestDefinition> entry : definitions.storyQuests().entrySet()) {
            QuestPlayerState.QuestEntry retained = state.quests().get(entry.getKey());
            if (retained != null && retained.definitionVersion() != entry.getValue().version()
                    && retained.completion().isEmpty() && matches(entry.getValue(), evidence)) {
                return true;
            }
        }
        for (Map.Entry<QuestPlayerState.ContractKey, QuestPlayerState.QuestEntry> entry
                : state.contracts().entrySet()) {
            if (!RepeatableContractService.contains(
                    entry.getKey().window(), evidence.timestampEpochMillis())) {
                continue;
            }
            QuestDefinition definition = definitions.quest(entry.getKey().templateId()).orElse(null);
            if (definition != null
                    && definition.contract().filter(contract ->
                            contract.cadence() == entry.getKey().window().cadence()).isPresent()
                    && entry.getValue().definitionVersion() != definition.version()
                    && entry.getValue().completion().isEmpty()
                    && matches(definition, evidence)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matches(QuestDefinition.Objective objective, Evidence evidence) {
        return objective.kind() == evidence.kind()
                && objective.target().map(target -> evidence.target().filter(target::equals).isPresent()).orElse(true);
    }

    private static boolean prerequisitesCompleted(
            QuestDefinition definition,
            QuestDefinitionSnapshot definitions,
            Map<Identifier, QuestPlayerState.QuestEntry> quests) {
        return definition.prerequisites().stream().allMatch(id -> {
            QuestPlayerState.QuestEntry prerequisite = quests.get(id);
            return prerequisite != null && prerequisite.completion().isPresent()
                    && definitions.quest(id).map(value -> value.version() == prerequisite.definitionVersion())
                            .orElse(false);
        });
    }

    private static boolean complete(QuestDefinition definition, Map<Identifier, Long> progress) {
        return definition.objectives().stream().allMatch(objective ->
                progress.getOrDefault(objective.id(), 0L) >= objective.requiredCount());
    }

    private static long saturatingAdd(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    private static UUID completionTransaction(UUID playerId, Identifier questId, int version) {
        return namedTransaction("completion:" + playerId + ":" + questId + ":" + version);
    }

    private static UUID completionTransaction(
            UUID playerId, QuestPlayerState.ContractKey key, int version) {
        return namedTransaction("contract_completion:" + playerId + ":"
                + key.window().cadence().getSerializedName() + ":"
                + key.window().windowStartEpochDay() + ":" + key.templateId() + ":" + version);
    }

    static UUID childTransaction(UUID parent, String kind) {
        return namedTransaction(parent + ":" + kind);
    }

    private static UUID namedTransaction(String value) {
        return UUID.nameUUIDFromBytes(("rovenfall:quest:" + value).getBytes(StandardCharsets.UTF_8));
    }

    static String rewardSource(Identifier questId) {
        String readable = "quest_reward:" + questId;
        return readable.length() <= 160
                ? readable
                : "quest_reward:" + namedTransaction("reward_source:" + questId);
    }

    private static void recordDenial(
            PlatformSavedData platform, UUID playerId, UUID sourceTransaction, String reason, long timestamp) {
        if (playerId == null || sourceTransaction == null || timestamp < 0) {
            return;
        }
        platform.recordQuestAudit(new AuditEntry(
                timestamp, AdministrationService.SYSTEM_ACTOR, QUEST_PROGRESS_DENIED,
                playerId.toString(), Optional.empty(), Optional.empty(), "unchanged", "unchanged", reason,
                childTransaction(sourceTransaction, "denied:" + reason)));
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(Rovenfall.MOD_ID, path);
    }

    public enum ProgressStatus {
        SUCCESS, REWARD_PENDING, DUPLICATE, IGNORED, STALE_DEFINITION, READ_ONLY, STATE_FULL,
        CONCURRENT_CHANGE, INVALID
    }

    public enum RewardStatus {
        COMPLETED, NOTHING_PENDING, ECONOMY_REJECTED, UNKNOWN_REWARD_ACTIVITY, RPG_REJECTED, AUDIT_REJECTED,
        CONCURRENT_CHANGE, RETRY_REQUIRED, INVALID
    }

    public record ProgressResult(ProgressStatus status, int updatedQuests, int completedQuests, boolean committed) {
    }

    public record RewardResult(RewardStatus status, int completedQuests) {
    }

    static final class RecoveryCursor {
        private RewardKey pendingAfter;
        private RewardKey completedAfter;
    }

    public record Evidence(
            QuestDefinition.Kind kind,
            Optional<Identifier> target,
            long count,
            long timestampEpochMillis,
            UUID sourceTransactionId) {
        public Evidence {
            target = target == null ? Optional.empty() : target;
        }

        public boolean isValid() {
            return kind != null && count >= 1 && count <= QuestDefinition.MAX_REQUIRED_COUNT
                    && timestampEpochMillis >= 0 && sourceTransactionId != null
                    && !ZERO_UUID.equals(sourceTransactionId)
                    && (kind != QuestDefinition.Kind.ACTIVITY || target.isPresent())
                    && (kind != QuestDefinition.Kind.CLAIM_PURCHASE || target.isEmpty());
        }
    }

    private record RewardCandidate(RewardKey key, QuestPlayerState.QuestEntry entry) {
    }

    private record RewardKey(Optional<QuestPlayerState.ContractKey> contractKey, Identifier questId)
            implements Comparable<RewardKey> {
        RewardKey {
            contractKey = contractKey == null ? Optional.empty() : contractKey;
        }

        static RewardKey story(Identifier questId) {
            return new RewardKey(Optional.empty(), questId);
        }

        static RewardKey contract(QuestPlayerState.ContractKey key) {
            return new RewardKey(Optional.of(key), key.templateId());
        }

        @Override
        public int compareTo(RewardKey other) {
            if (contractKey.isEmpty() != other.contractKey.isEmpty()) {
                return contractKey.isEmpty() ? -1 : 1;
            }
            return contractKey.isPresent()
                    ? contractKey.orElseThrow().compareTo(other.contractKey.orElseThrow())
                    : questId.compareTo(other.questId);
        }
    }

    private record RewardStep(
            boolean applied,
            boolean completed,
            QuestPlayerState.RewardOperation.Phase nextPhase,
            RewardStatus status) {
        static RewardStep next(QuestPlayerState.RewardOperation.Phase phase) {
            return new RewardStep(true, false, phase, RewardStatus.NOTHING_PENDING);
        }

        static RewardStep complete() {
            return new RewardStep(true, true, null, RewardStatus.COMPLETED);
        }

        static RewardStep failed(RewardStatus status) {
            return new RewardStep(false, false, null, status);
        }
    }
}
