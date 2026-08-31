package org.dldyou.rovenfall.quest;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import net.minecraft.resources.Identifier;

/** Immutable player-facing projection built from one authoritative quest definition/state snapshot. */
public record QuestJourneyView(
        long definitionRevision,
        boolean writable,
        int page,
        int pageSize,
        int totalPages,
        int totalEntries,
        List<QuestRow> entries,
        Optional<NextStep> nextStep) {
    public static final int MAX_PAGE_SIZE = 28;

    public QuestJourneyView {
        entries = List.copyOf(entries);
        nextStep = nextStep == null ? Optional.empty() : nextStep;
    }

    public static QuestJourneyView create(
            QuestDefinitionSnapshot definitions,
            QuestPlayerState state,
            long definitionRevision,
            boolean writable,
            int requestedPage,
            int pageSize) {
        if (definitions == null || state == null || definitionRevision < 0 || requestedPage < 0
                || pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("invalid quest journey view request");
        }

        TreeSet<Identifier> ids = new TreeSet<>(definitions.storyQuests().keySet());
        ids.addAll(state.quests().keySet());
        int totalEntries = ids.size();
        int totalPages = totalEntries == 0 ? 0 : (totalEntries + pageSize - 1) / pageSize;
        int page = totalPages == 0 ? 0 : Math.min(requestedPage, totalPages - 1);
        int from = page * pageSize;
        int to = Math.min(from + pageSize, totalEntries);
        List<QuestRow> entries = new ArrayList<>(to - from);
        int index = 0;
        for (Identifier id : ids) {
            if (index >= from && index < to) {
                entries.add(row(id, definitions, state));
            }
            if (++index >= to) {
                break;
            }
        }

        return new QuestJourneyView(
                definitionRevision,
                writable,
                page,
                pageSize,
                totalPages,
                totalEntries,
                entries,
                nextStep(definitions, state));
    }

    static QuestRow row(
            Identifier id, QuestDefinitionSnapshot definitions, QuestPlayerState state) {
        QuestDefinition definition = definitions.quest(id).orElse(null);
        QuestPlayerState.QuestEntry retained = state.quests().get(id);
        if (definition == null) {
            return new QuestRow(
                    id, Optional.empty(), Optional.empty(), Status.UNRESOLVED,
                    Optional.empty(), optionalVersion(retained), List.of(), List.of(), capturedReward(retained));
        }

        Optional<Integer> currentVersion = Optional.of(definition.version());
        Optional<Integer> retainedVersion = optionalVersion(retained);
        if (retained != null && (retained.definitionVersion() != definition.version()
                || definition.contract().isPresent())) {
            return new QuestRow(
                    id, Optional.of(definition.translationKey()), Optional.of(definition.descriptionTranslationKey()),
                    Status.DEFINITION_CHANGED, currentVersion, retainedVersion,
                    List.of(), List.of(), capturedReward(retained));
        }

        List<ObjectiveRow> objectives = objectives(definition, retained);
        List<PrerequisiteRow> missingPrerequisites = definition.prerequisites().stream()
                .filter(prerequisite -> !completedCurrentVersion(prerequisite, definitions, state))
                .map(prerequisite -> new PrerequisiteRow(
                        prerequisite,
                        definitions.quest(prerequisite).map(QuestDefinition::translationKey)))
                .toList();
        Status status;
        if (retained != null && retained.pendingReward().isPresent()) {
            status = Status.PENDING;
        } else if (retained != null && retained.completion().isPresent()) {
            status = Status.COMPLETED;
        } else if (!missingPrerequisites.isEmpty()) {
            status = Status.PREREQUISITE_LOCKED;
        } else if (objectives.stream().anyMatch(objective -> objective.progress() > 0)) {
            status = Status.IN_PROGRESS;
        } else {
            status = Status.AVAILABLE;
        }

        return new QuestRow(
                id,
                Optional.of(definition.translationKey()),
                Optional.of(definition.descriptionTranslationKey()),
                status,
                currentVersion,
                retainedVersion,
                objectives,
                missingPrerequisites,
                rewardPreview(status, definition, retained));
    }

    private static List<ObjectiveRow> objectives(
            QuestDefinition definition, QuestPlayerState.QuestEntry retained) {
        return definition.objectives().stream().map(objective -> {
            long retainedProgress = retained == null
                    ? 0
                    : retained.objectiveProgress().getOrDefault(objective.id(), 0L);
            return new ObjectiveRow(
                    objective.id(),
                    objective.kind(),
                    objective.target(),
                    Math.clamp(retainedProgress, 0L, objective.requiredCount()),
                    objective.requiredCount());
        }).toList();
    }

    private static Optional<RewardPreview> rewardPreview(
            Status status, QuestDefinition definition, QuestPlayerState.QuestEntry retained) {
        if (status == Status.PENDING) {
            return capturedReward(retained);
        }
        if (status == Status.COMPLETED) {
            return capturedReward(retained);
        }
        return rewardPreview(definition.rewards()).filter(RewardPreview::present);
    }

    private static Optional<RewardPreview> capturedReward(QuestPlayerState.QuestEntry retained) {
        if (retained == null) {
            return Optional.empty();
        }
        return retained.pendingReward()
                .or(() -> retained.completion().flatMap(QuestPlayerState.CompletionReceipt::rewardOperation))
                .map(QuestJourneyView::rewardPreview)
                .filter(RewardPreview::present);
    }

    private static RewardPreview rewardPreview(QuestPlayerState.RewardOperation operation) {
        return new RewardPreview(operation.currency(), operation.activity(), operation.activityXp());
    }

    private static Optional<RewardPreview> rewardPreview(QuestDefinition.Rewards rewards) {
        Optional<Identifier> activity = rewards.activityXp().map(QuestDefinition.ActivityXpReward::activity);
        long activityXp = rewards.activityXp().map(QuestDefinition.ActivityXpReward::amount).orElse(0L);
        return Optional.of(new RewardPreview(rewards.currency(), activity, activityXp));
    }

    private static Optional<NextStep> nextStep(
            QuestDefinitionSnapshot definitions, QuestPlayerState state) {
        NextStep available = null;
        Set<Identifier> ids = new TreeSet<>(definitions.storyQuests().keySet());
        for (Identifier id : ids) {
            QuestRow row = row(id, definitions, state);
            if (row.status() != Status.AVAILABLE && row.status() != Status.IN_PROGRESS) {
                continue;
            }
            Optional<ObjectiveRow> objective = row.objectives().stream().filter(value -> !value.complete()).findFirst();
            if (objective.isEmpty()) {
                continue;
            }
            ObjectiveRow value = objective.orElseThrow();
            NextStep candidate = new NextStep(
                    id, row.translationKey().orElseThrow(),
                    value.kind(), value.target(), value.progress(), value.requiredCount());
            if (row.status() == Status.IN_PROGRESS) {
                return Optional.of(candidate);
            }
            if (available == null) {
                available = candidate;
            }
        }
        return Optional.ofNullable(available);
    }

    private static boolean completedCurrentVersion(
            Identifier id, QuestDefinitionSnapshot definitions, QuestPlayerState state) {
        QuestPlayerState.QuestEntry retained = state.quests().get(id);
        return retained != null && retained.completion().isPresent()
                && definitions.quest(id)
                        .filter(definition -> definition.version() == retained.definitionVersion())
                        .isPresent();
    }

    private static Optional<Integer> optionalVersion(QuestPlayerState.QuestEntry entry) {
        return entry == null ? Optional.empty() : Optional.of(entry.definitionVersion());
    }

    public record QuestRow(
            Identifier id,
            Optional<String> translationKey,
            Optional<String> descriptionTranslationKey,
            Status status,
            Optional<Integer> definitionVersion,
            Optional<Integer> retainedDefinitionVersion,
            List<ObjectiveRow> objectives,
            List<PrerequisiteRow> missingPrerequisites,
            Optional<RewardPreview> rewardPreview) {
        public QuestRow {
            translationKey = translationKey == null ? Optional.empty() : translationKey;
            descriptionTranslationKey = descriptionTranslationKey == null
                    ? Optional.empty()
                    : descriptionTranslationKey;
            definitionVersion = definitionVersion == null ? Optional.empty() : definitionVersion;
            retainedDefinitionVersion = retainedDefinitionVersion == null
                    ? Optional.empty()
                    : retainedDefinitionVersion;
            objectives = List.copyOf(objectives);
            missingPrerequisites = List.copyOf(missingPrerequisites);
            rewardPreview = rewardPreview == null ? Optional.empty() : rewardPreview;
        }
    }

    public record ObjectiveRow(
            Identifier id,
            QuestDefinition.Kind kind,
            Optional<Identifier> target,
            long progress,
            long requiredCount) {
        public ObjectiveRow {
            target = target == null ? Optional.empty() : target;
        }

        public boolean complete() {
            return progress >= requiredCount;
        }
    }

    public record PrerequisiteRow(Identifier id, Optional<String> translationKey) {
        public PrerequisiteRow {
            translationKey = translationKey == null ? Optional.empty() : translationKey;
        }
    }

    public record RewardPreview(long currency, Optional<Identifier> activity, long activityXp) {
        public RewardPreview {
            activity = activity == null ? Optional.empty() : activity;
        }

        public boolean present() {
            return currency > 0 || activityXp > 0;
        }
    }

    public record NextStep(
            Identifier questId,
            String questTranslationKey,
            QuestDefinition.Kind kind,
            Optional<Identifier> target,
            long progress,
            long requiredCount) {
        public NextStep {
            target = target == null ? Optional.empty() : target;
        }
    }

    public enum Status {
        AVAILABLE,
        IN_PROGRESS,
        PREREQUISITE_LOCKED,
        PENDING,
        COMPLETED,
        UNRESOLVED,
        DEFINITION_CHANGED
    }
}
