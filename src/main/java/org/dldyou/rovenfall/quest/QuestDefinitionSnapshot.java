package org.dldyou.rovenfall.quest;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import net.minecraft.resources.Identifier;
import org.dldyou.rovenfall.Rovenfall;

public final class QuestDefinitionSnapshot {
    public static final int MAX_DEFINITIONS = 4_096;
    private static final Pattern TRANSLATION_KEY = Pattern.compile("[a-z0-9_.-]{1,160}");
    private static final Identifier CATALOG_FILE =
            Identifier.fromNamespaceAndPath(Rovenfall.MOD_ID, "quest_definition_catalog");
    private static final QuestDefinitionSnapshot EMPTY = new QuestDefinitionSnapshot(Map.of());

    private final Map<Identifier, QuestDefinition> quests;

    private QuestDefinitionSnapshot(Map<Identifier, QuestDefinition> quests) {
        this.quests = Map.copyOf(quests);
    }

    public static QuestDefinitionSnapshot empty() {
        return EMPTY;
    }

    public static QuestDefinitionSnapshot compile(Collection<Source> candidates) {
        List<Source> sources = candidates.stream()
                .sorted(Comparator.comparing(Source::id).thenComparing(Source::file))
                .toList();
        List<Problem> problems = new ArrayList<>();
        if (sources.size() > MAX_DEFINITIONS) {
            problems.add(new Problem(CATALOG_FILE, CATALOG_FILE,
                    "quest definition count exceeds " + MAX_DEFINITIONS));
        }

        Map<Identifier, List<Source>> byId = new LinkedHashMap<>();
        sources.forEach(source -> byId.computeIfAbsent(source.id(), ignored -> new ArrayList<>()).add(source));
        byId.forEach((id, duplicates) -> {
            if (duplicates.size() > 1) {
                String locations = duplicates.stream()
                        .map(source -> source.file() + " (" + source.packId() + ")")
                        .toList().toString();
                problems.add(new Problem(duplicates.getFirst().file(), id,
                        "duplicate quest definition ID in " + locations));
            }
        });

        Map<Identifier, Source> unique = new LinkedHashMap<>();
        sources.forEach(source -> unique.putIfAbsent(source.id(), source));
        Set<Identifier> objectiveIds = new HashSet<>();
        for (Source source : sources) {
            validate(source, unique, objectiveIds, problems);
        }
        validateCycles(unique, problems);

        if (!problems.isEmpty()) {
            throw new ValidationException(problems);
        }
        Map<Identifier, QuestDefinition> compiled = new LinkedHashMap<>();
        sources.forEach(source -> compiled.put(source.id(), source.definition()));
        return new QuestDefinitionSnapshot(compiled);
    }

    private static void validate(
            Source source,
            Map<Identifier, Source> quests,
            Set<Identifier> objectiveIds,
            List<Problem> problems) {
        QuestDefinition definition = source.definition();
        validateTranslation(source, definition.translationKey(), "title", problems);
        validateTranslation(source, definition.descriptionTranslationKey(), "description", problems);
        if (definition.version() < 1 || definition.version() > QuestDefinition.MAX_VERSION) {
            problems.add(problem(source, "version must be between 1 and " + QuestDefinition.MAX_VERSION));
        }
        if (definition.prerequisites() == null
                || definition.prerequisites().size() > QuestDefinition.MAX_PREREQUISITES) {
            problems.add(problem(source, "prerequisite count exceeds " + QuestDefinition.MAX_PREREQUISITES));
        } else {
            Set<Identifier> seen = new HashSet<>();
            for (Identifier prerequisite : definition.prerequisites()) {
                if (prerequisite == null) {
                    problems.add(problem(source, "invalid prerequisite quest ID"));
                } else if (!seen.add(prerequisite)) {
                    problems.add(problem(source, "duplicate prerequisite quest ID: " + prerequisite));
                } else if (!quests.containsKey(prerequisite)) {
                    problems.add(problem(source, "missing prerequisite quest: " + prerequisite));
                }
            }
        }

        if (definition.objectives() == null || definition.objectives().isEmpty()
                || definition.objectives().size() > QuestDefinition.MAX_OBJECTIVES) {
            problems.add(problem(source,
                    "objective count must be between 1 and " + QuestDefinition.MAX_OBJECTIVES));
            return;
        }
        if (definition.rewards() == null || !definition.rewards().isValid()) {
            problems.add(problem(source, "quest rewards exceed a bound or contain an invalid activity"));
        }
        for (QuestDefinition.Objective objective : definition.objectives()) {
            if (objective == null || objective.id() == null || objective.kind() == null) {
                problems.add(problem(source, "invalid objective"));
                continue;
            }
            if (!objectiveIds.add(objective.id())) {
                problems.add(problem(source, "duplicate objective ID: " + objective.id()));
            }
            if (objective.requiredCount() < 1
                    || objective.requiredCount() > QuestDefinition.MAX_REQUIRED_COUNT) {
                problems.add(problem(source,
                        "objective required count must be between 1 and "
                                + QuestDefinition.MAX_REQUIRED_COUNT + ": " + objective.id()));
            }
            Optional<Identifier> target = objective.target() == null ? Optional.empty() : objective.target();
            if (objective.kind() == QuestDefinition.Kind.ACTIVITY && target.isEmpty()) {
                problems.add(problem(source, "activity objective requires target: " + objective.id()));
            }
            if (objective.kind() == QuestDefinition.Kind.CLAIM_PURCHASE && target.isPresent()) {
                problems.add(problem(source, "claim purchase objective cannot define target: " + objective.id()));
            }
        }
    }

    private static void validateTranslation(
            Source source, String key, String label, List<Problem> problems) {
        if (key == null || !TRANSLATION_KEY.matcher(key).matches()) {
            problems.add(problem(source, "invalid " + label + " translation key: " + key));
        }
    }

    private static void validateCycles(Map<Identifier, Source> quests, List<Problem> problems) {
        Map<Identifier, Integer> dependencyCount = new HashMap<>();
        Map<Identifier, List<Identifier>> dependents = new HashMap<>();
        quests.forEach((id, source) -> {
            int count = 0;
            for (Identifier prerequisite : source.definition().prerequisites()) {
                if (quests.containsKey(prerequisite)) {
                    count++;
                    dependents.computeIfAbsent(prerequisite, ignored -> new ArrayList<>()).add(id);
                }
            }
            dependencyCount.put(id, count);
        });
        ArrayDeque<Identifier> ready = new ArrayDeque<>();
        dependencyCount.forEach((id, count) -> {
            if (count == 0) {
                ready.add(id);
            }
        });
        int resolved = 0;
        while (!ready.isEmpty()) {
            Identifier id = ready.removeFirst();
            resolved++;
            for (Identifier dependent : dependents.getOrDefault(id, List.of())) {
                int remaining = dependencyCount.computeIfPresent(dependent, (ignored, count) -> count - 1);
                if (remaining == 0) {
                    ready.addLast(dependent);
                }
            }
        }
        if (resolved == quests.size()) {
            return;
        }
        dependencyCount.entrySet().stream()
                .filter(entry -> entry.getValue() > 0)
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> problems.add(problem(quests.get(entry.getKey()), "quest dependency cycle")));
    }

    private static Problem problem(Source source, String cause) {
        return new Problem(source.file(), source.id(), cause);
    }

    public Optional<QuestDefinition> quest(Identifier id) {
        return Optional.ofNullable(quests.get(id));
    }

    public Map<Identifier, QuestDefinition> quests() {
        return quests;
    }

    public int size() {
        return quests.size();
    }

    public record Source(Identifier file, String packId, Identifier id, QuestDefinition definition) {
    }

    public record Problem(Identifier file, Identifier definitionId, String cause) {
        @Override
        public String toString() {
            return file + " [" + definitionId + "]: " + cause;
        }
    }

    public static final class ValidationException extends RuntimeException {
        private final List<Problem> problems;

        public ValidationException(Collection<Problem> problems) {
            super(problems.stream().map(Problem::toString).reduce((left, right) -> left + "; " + right)
                    .orElse("invalid quest definitions"));
            this.problems = List.copyOf(problems);
        }

        public List<Problem> problems() {
            return problems;
        }
    }
}
