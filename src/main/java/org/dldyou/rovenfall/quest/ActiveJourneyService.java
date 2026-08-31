package org.dldyou.rovenfall.quest;

import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.Identifier;
import org.dldyou.rovenfall.rpg.RpgDefinitionSnapshot;

/** Server-owned selection, reconciliation, and privacy-safe projection of one active journey. */
public final class ActiveJourneyService {
    private static final UUID ZERO_UUID = new UUID(0L, 0L);

    private ActiveJourneyService() {
    }

    public static MutationResult selectStory(
            QuestPlayerSavedData savedData,
            QuestDefinitionSnapshot definitions,
            UUID playerId,
            Identifier questId) {
        if (!valid(savedData, definitions, playerId) || questId == null) {
            return result(MutationStatus.INVALID, false);
        }
        if (!savedData.isWritable()) {
            return result(MutationStatus.READ_ONLY, false);
        }
        QuestDefinition definition = definitions.quest(questId)
                .filter(value -> value.contract().isEmpty())
                .orElse(null);
        if (definition == null) {
            return result(MutationStatus.NOT_ELIGIBLE, false);
        }
        QuestPlayerState current = savedData.state(playerId);
        QuestPlayerState.TrackedJourney tracked = QuestPlayerState.TrackedJourney.story(
                questId, definition.version());
        if (candidate(tracked, definitions, current, 0L, RpgDefinitionSnapshot.empty()).isEmpty()) {
            return result(MutationStatus.NOT_ELIGIBLE, false);
        }
        return replace(savedData, playerId, current, Optional.of(tracked), false);
    }

    public static MutationResult selectContract(
            QuestPlayerSavedData savedData,
            QuestDefinitionSnapshot definitions,
            UUID playerId,
            QuestPlayerState.ContractKey key,
            long currentEpochMillis) {
        if (!valid(savedData, definitions, playerId) || key == null || !key.isValid()
                || currentEpochMillis < 0) {
            return result(MutationStatus.INVALID, false);
        }
        if (!savedData.isWritable()) {
            return result(MutationStatus.READ_ONLY, false);
        }
        QuestDefinition definition = definitions.quest(key.templateId())
                .filter(value -> value.contract()
                        .filter(contract -> contract.cadence() == key.window().cadence())
                        .isPresent())
                .orElse(null);
        if (definition == null) {
            return result(MutationStatus.NOT_ELIGIBLE, false);
        }
        QuestPlayerState current = savedData.state(playerId);
        QuestPlayerState.TrackedJourney tracked = QuestPlayerState.TrackedJourney.contract(
                key, definition.version());
        if (candidate(tracked, definitions, current, currentEpochMillis, RpgDefinitionSnapshot.empty()).isEmpty()) {
            return result(MutationStatus.NOT_ELIGIBLE, false);
        }
        return replace(savedData, playerId, current, Optional.of(tracked), false);
    }

    public static MutationResult clear(QuestPlayerSavedData savedData, UUID playerId) {
        if (savedData == null || playerId == null || ZERO_UUID.equals(playerId)) {
            return result(MutationStatus.INVALID, false);
        }
        if (!savedData.isWritable()) {
            return result(MutationStatus.READ_ONLY, false);
        }
        QuestPlayerState current = savedData.state(playerId);
        return replace(savedData, playerId, current, Optional.empty(), true);
    }

    public static MutationResult reconcile(
            QuestPlayerSavedData savedData,
            QuestDefinitionSnapshot definitions,
            UUID playerId,
            long currentEpochMillis) {
        if (!valid(savedData, definitions, playerId) || currentEpochMillis < 0) {
            return result(MutationStatus.INVALID, false);
        }
        if (!savedData.isWritable()) {
            return result(MutationStatus.READ_ONLY, false);
        }
        QuestPlayerState current = savedData.state(playerId);
        if (current.trackedJourney().isEmpty()
                || candidate(current.trackedJourney().orElseThrow(), definitions, current,
                        currentEpochMillis, RpgDefinitionSnapshot.empty()).isPresent()) {
            return result(MutationStatus.UNCHANGED, false);
        }
        return replace(savedData, playerId, current, Optional.empty(), true);
    }

    public static ActiveJourneyView view(
            QuestPlayerSavedData savedData,
            QuestDefinitionSnapshot definitions,
            RpgDefinitionSnapshot rpgDefinitions,
            UUID playerId,
            long definitionRevision,
            long currentEpochMillis) {
        if (!valid(savedData, definitions, playerId) || rpgDefinitions == null
                || definitionRevision < 0 || currentEpochMillis < 0) {
            throw new IllegalArgumentException("invalid active journey view request");
        }
        if (!savedData.isWritable()) {
            return new ActiveJourneyView(definitionRevision, false, Optional.empty());
        }
        QuestPlayerState current = savedData.state(playerId);
        Optional<ActiveJourneyView.Entry> journey = current.trackedJourney()
                .flatMap(tracked -> candidate(
                        tracked, definitions, current, currentEpochMillis, rpgDefinitions));
        return new ActiveJourneyView(definitionRevision, savedData.isWritable(), journey);
    }

    private static Optional<ActiveJourneyView.Entry> candidate(
            QuestPlayerState.TrackedJourney tracked,
            QuestDefinitionSnapshot definitions,
            QuestPlayerState state,
            long currentEpochMillis,
            RpgDefinitionSnapshot rpgDefinitions) {
        if (tracked.storyQuestId().isPresent()) {
            Identifier questId = tracked.storyQuestId().orElseThrow();
            QuestDefinition definition = definitions.quest(questId)
                    .filter(value -> value.contract().isEmpty())
                    .filter(value -> value.version() == tracked.definitionVersion())
                    .orElse(null);
            if (definition == null) {
                return Optional.empty();
            }
            QuestJourneyView.QuestRow row = QuestJourneyView.row(questId, definitions, state);
            return eligible(row.status(), row.translationKey(), row.objectives().stream()
                    .filter(objective -> !objective.complete()).findFirst(),
                    ActiveJourneyView.Kind.STORY, rpgDefinitions);
        }

        QuestPlayerState.ContractKey key = tracked.contractKey().orElseThrow();
        QuestPlayerState.QuestEntry retained = state.contracts().get(key);
        QuestDefinition definition = definitions.quest(key.templateId())
                .filter(value -> value.version() == tracked.definitionVersion())
                .filter(value -> value.contract()
                        .filter(contract -> contract.cadence() == key.window().cadence())
                        .isPresent())
                .orElse(null);
        if (definition == null || retained == null
                || retained.definitionVersion() != tracked.definitionVersion()
                || !RepeatableContractService.contains(key.window(), currentEpochMillis)) {
            return Optional.empty();
        }
        ContractJourneyView.ContractRow row = ContractJourneyView.row(key, retained, definitions);
        ActiveJourneyView.Kind kind = key.window().cadence() == QuestDefinition.Cadence.DAILY
                ? ActiveJourneyView.Kind.DAILY
                : ActiveJourneyView.Kind.WEEKLY;
        return eligible(row.status(), row.translationKey(), row.objective().filter(value -> !value.complete()),
                kind, rpgDefinitions);
    }

    private static Optional<ActiveJourneyView.Entry> eligible(
            QuestJourneyView.Status status,
            Optional<String> titleTranslationKey,
            Optional<QuestJourneyView.ObjectiveRow> objective,
            ActiveJourneyView.Kind kind,
            RpgDefinitionSnapshot rpgDefinitions) {
        if ((status != QuestJourneyView.Status.AVAILABLE
                && status != QuestJourneyView.Status.IN_PROGRESS)
                || titleTranslationKey.isEmpty() || objective.isEmpty()) {
            return Optional.empty();
        }
        QuestJourneyView.ObjectiveRow next = objective.orElseThrow();
        Optional<String> activityTargetTranslationKey = next.kind() == QuestDefinition.Kind.ACTIVITY
                ? next.target().flatMap(rpgDefinitions::activity).map(activity -> activity.translationKey())
                : Optional.empty();
        return Optional.of(new ActiveJourneyView.Entry(
                kind,
                titleTranslationKey.orElseThrow(),
                status == QuestJourneyView.Status.IN_PROGRESS
                        ? ActiveJourneyView.Status.IN_PROGRESS
                        : ActiveJourneyView.Status.AVAILABLE,
                next.kind(),
                activityTargetTranslationKey,
                next.progress(),
                next.requiredCount()));
    }

    static MutationResult replace(
            QuestPlayerSavedData savedData,
            UUID playerId,
            QuestPlayerState current,
            Optional<QuestPlayerState.TrackedJourney> tracked,
            boolean cleared) {
        if (current.trackedJourney().equals(tracked)) {
            return result(MutationStatus.UNCHANGED, false);
        }
        QuestPlayerState updated = new QuestPlayerState(
                current.quests(), current.processedEvidence(), current.contracts(),
                current.initializedContractWindows(), tracked);
        boolean committed = savedData.commit(playerId, current, updated);
        return result(committed ? MutationStatus.SUCCESS : MutationStatus.CONCURRENT_CHANGE,
                committed && cleared);
    }

    private static boolean valid(
            QuestPlayerSavedData savedData,
            QuestDefinitionSnapshot definitions,
            UUID playerId) {
        return savedData != null && definitions != null && playerId != null && !ZERO_UUID.equals(playerId);
    }

    private static MutationResult result(MutationStatus status, boolean cleared) {
        return new MutationResult(status, cleared);
    }

    public enum MutationStatus {
        SUCCESS,
        UNCHANGED,
        NOT_ELIGIBLE,
        READ_ONLY,
        CONCURRENT_CHANGE,
        INVALID
    }

    public record MutationResult(MutationStatus status, boolean cleared) {
    }
}
