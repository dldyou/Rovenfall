package org.dldyou.rovenfall.quest;

import java.util.List;
import java.util.Optional;
import net.minecraft.resources.Identifier;

/** Bounded player-facing projection of the current UTC daily and weekly contracts. */
public record ContractJourneyView(
        long definitionRevision,
        boolean writable,
        List<ContractRow> entries) {
    public static final int MAX_ENTRIES = 3;

    public ContractJourneyView {
        entries = List.copyOf(entries);
        if (entries.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException("contract journey view exceeds its entry bound");
        }
    }

    public static ContractJourneyView create(
            QuestDefinitionSnapshot definitions,
            QuestPlayerState state,
            long definitionRevision,
            boolean writable,
            long currentEpochMillis) {
        if (definitions == null || state == null || definitionRevision < 0 || currentEpochMillis < 0) {
            throw new IllegalArgumentException("invalid contract journey view request");
        }
        List<ContractRow> entries = RepeatableContractService.currentKeys(state, currentEpochMillis).stream()
                .limit(MAX_ENTRIES)
                .map(key -> row(key, state.contracts().get(key), definitions))
                .toList();
        return new ContractJourneyView(definitionRevision, writable, entries);
    }

    static ContractRow row(
            QuestPlayerState.ContractKey key,
            QuestPlayerState.QuestEntry retained,
            QuestDefinitionSnapshot definitions) {
        QuestDefinition definition = definitions.quest(key.templateId()).orElse(null);
        if (definition == null) {
            return new ContractRow(
                    key, Optional.empty(), Optional.empty(), QuestJourneyView.Status.UNRESOLVED,
                    Optional.empty(), capturedReward(retained));
        }
        if (definition.version() != retained.definitionVersion()
                || definition.contract().filter(contract -> contract.cadence() == key.window().cadence()).isEmpty()) {
            return new ContractRow(
                    key, Optional.of(definition.translationKey()),
                    Optional.of(definition.descriptionTranslationKey()),
                    QuestJourneyView.Status.DEFINITION_CHANGED, Optional.empty(), capturedReward(retained));
        }

        QuestJourneyView.ObjectiveRow objective = definition.objectives().stream().findFirst()
                .map(value -> new QuestJourneyView.ObjectiveRow(
                        value.id(), value.kind(), value.target(),
                        Math.clamp(retained.objectiveProgress().getOrDefault(value.id(), 0L),
                                0L, value.requiredCount()),
                        value.requiredCount()))
                .orElse(null);
        QuestJourneyView.Status status;
        if (retained.pendingReward().isPresent()) {
            status = QuestJourneyView.Status.PENDING;
        } else if (retained.completion().isPresent()) {
            status = QuestJourneyView.Status.COMPLETED;
        } else if (objective != null && objective.progress() > 0) {
            status = QuestJourneyView.Status.IN_PROGRESS;
        } else {
            status = QuestJourneyView.Status.AVAILABLE;
        }
        Optional<QuestJourneyView.RewardPreview> reward = switch (status) {
            case PENDING, COMPLETED -> capturedReward(retained);
            default -> rewardPreview(definition.rewards());
        };
        return new ContractRow(
                key, Optional.of(definition.translationKey()),
                Optional.of(definition.descriptionTranslationKey()), status,
                Optional.ofNullable(objective), reward);
    }

    private static Optional<QuestJourneyView.RewardPreview> capturedReward(
            QuestPlayerState.QuestEntry retained) {
        return retained.pendingReward()
                .or(() -> retained.completion().flatMap(QuestPlayerState.CompletionReceipt::rewardOperation))
                .map(operation -> new QuestJourneyView.RewardPreview(
                        operation.currency(), operation.activity(), operation.activityXp()))
                .filter(QuestJourneyView.RewardPreview::present);
    }

    private static Optional<QuestJourneyView.RewardPreview> rewardPreview(QuestDefinition.Rewards rewards) {
        Optional<Identifier> activity = rewards.activityXp().map(QuestDefinition.ActivityXpReward::activity);
        long activityXp = rewards.activityXp().map(QuestDefinition.ActivityXpReward::amount).orElse(0L);
        return Optional.of(new QuestJourneyView.RewardPreview(rewards.currency(), activity, activityXp))
                .filter(QuestJourneyView.RewardPreview::present);
    }

    public record ContractRow(
            QuestPlayerState.ContractKey key,
            Optional<String> translationKey,
            Optional<String> descriptionTranslationKey,
            QuestJourneyView.Status status,
            Optional<QuestJourneyView.ObjectiveRow> objective,
            Optional<QuestJourneyView.RewardPreview> rewardPreview) {
        public ContractRow {
            translationKey = translationKey == null ? Optional.empty() : translationKey;
            descriptionTranslationKey = descriptionTranslationKey == null
                    ? Optional.empty()
                    : descriptionTranslationKey;
            objective = objective == null ? Optional.empty() : objective;
            rewardPreview = rewardPreview == null ? Optional.empty() : rewardPreview;
        }
    }
}
