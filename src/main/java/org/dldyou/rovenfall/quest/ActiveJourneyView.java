package org.dldyou.rovenfall.quest;

import java.util.Optional;

/** Immutable, identifier-free projection of the player's selected next objective. */
public record ActiveJourneyView(
        long definitionRevision,
        boolean writable,
        Optional<Entry> journey) {
    public ActiveJourneyView {
        journey = journey == null ? Optional.empty() : journey;
        if (definitionRevision < 0) {
            throw new IllegalArgumentException("active journey definition revision cannot be negative");
        }
    }

    public record Entry(
            Kind kind,
            String titleTranslationKey,
            Status status,
            QuestDefinition.Kind objectiveKind,
            Optional<String> activityTargetTranslationKey,
            long progress,
            long requiredCount) {
        public Entry {
            activityTargetTranslationKey = activityTargetTranslationKey == null
                    ? Optional.empty()
                    : activityTargetTranslationKey;
            if (kind == null || titleTranslationKey == null || titleTranslationKey.isBlank()
                    || status == null || objectiveKind == null
                    || progress < 0 || requiredCount < 1 || progress >= requiredCount
                    || (status == Status.AVAILABLE && progress != 0)) {
                throw new IllegalArgumentException("invalid active journey projection");
            }
        }
    }

    public enum Kind {
        STORY,
        DAILY,
        WEEKLY
    }

    public enum Status {
        AVAILABLE,
        IN_PROGRESS
    }
}
