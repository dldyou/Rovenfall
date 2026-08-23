package org.dldyou.rovenfall.definition;

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

public final class DefinitionSnapshot {
    public static final int MAX_DEFINITIONS = 4_096;
    private static final Pattern TRANSLATION_KEY = Pattern.compile("[a-z0-9_.-]{1,160}");
    private static final Identifier CATALOG_FILE = Identifier.fromNamespaceAndPath(Rovenfall.MOD_ID, "definition_catalog");
    private static final DefinitionSnapshot EMPTY = new DefinitionSnapshot(Map.of());

    private final Map<Identifier, TestDefinition> definitions;

    private DefinitionSnapshot(Map<Identifier, TestDefinition> definitions) {
        this.definitions = Map.copyOf(definitions);
    }

    public static DefinitionSnapshot empty() {
        return EMPTY;
    }

    public static DefinitionSnapshot compile(Collection<Source> candidates) {
        List<Source> ordered = candidates.stream()
                .sorted(Comparator.comparing(Source::id).thenComparing(Source::file))
                .toList();
        if (ordered.size() > MAX_DEFINITIONS) {
            throw new ValidationException(List.of(new Problem(
                    CATALOG_FILE,
                    CATALOG_FILE,
                    "definition count exceeds " + MAX_DEFINITIONS)));
        }

        Map<Identifier, List<Source>> byId = new LinkedHashMap<>();
        for (Source candidate : ordered) {
            byId.computeIfAbsent(candidate.id(), ignored -> new ArrayList<>()).add(candidate);
        }

        List<Problem> problems = new ArrayList<>();
        for (var entry : byId.entrySet()) {
            if (entry.getValue().size() > 1) {
                Source first = entry.getValue().getFirst();
                String locations = entry.getValue().stream()
                        .map(source -> source.file() + " (" + source.packId() + ")")
                        .toList()
                        .toString();
                problems.add(new Problem(first.file(), entry.getKey(), "duplicate definition ID in " + locations));
            }
        }
        if (!problems.isEmpty()) {
            throw new ValidationException(problems);
        }

        Map<Identifier, Source> unique = new LinkedHashMap<>();
        for (Source source : ordered) {
            unique.put(source.id(), source);
        }

        for (Source source : ordered) {
            TestDefinition definition = source.definition();
            if (!TRANSLATION_KEY.matcher(definition.translationKey()).matches()) {
                problems.add(problem(source, "invalid translation key: " + definition.translationKey()));
            }
            if (definition.value() < 0 || definition.value() > TestDefinition.MAX_VALUE) {
                problems.add(problem(source, "value must be between 0 and " + TestDefinition.MAX_VALUE));
            }
            if (definition.requires().size() > TestDefinition.MAX_REFERENCES) {
                problems.add(problem(source, "reference count exceeds " + TestDefinition.MAX_REFERENCES));
            }

            Set<Identifier> seenReferences = new HashSet<>();
            for (Identifier requiredId : definition.requires()) {
                if (!seenReferences.add(requiredId)) {
                    problems.add(problem(source, "duplicate reference: " + requiredId));
                } else if (!unique.containsKey(requiredId)) {
                    problems.add(problem(source, "missing reference: " + requiredId));
                }
            }
        }

        Map<Identifier, Integer> dependencyCount = new HashMap<>();
        Map<Identifier, List<Identifier>> dependents = new HashMap<>();
        for (Source source : ordered) {
            int count = 0;
            for (Identifier requiredId : new HashSet<>(source.definition().requires())) {
                if (unique.containsKey(requiredId)) {
                    count++;
                    dependents.computeIfAbsent(requiredId, ignored -> new ArrayList<>()).add(source.id());
                }
            }
            dependencyCount.put(source.id(), count);
        }

        var ready = new ArrayDeque<Identifier>();
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
        if (resolved != unique.size()) {
            dependencyCount.entrySet().stream()
                    .filter(entry -> entry.getValue() > 0)
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> problems.add(problem(unique.get(entry.getKey()), "dependency cycle")));
        }

        if (!problems.isEmpty()) {
            throw new ValidationException(problems);
        }

        Map<Identifier, TestDefinition> compiled = new LinkedHashMap<>();
        ordered.forEach(source -> compiled.put(source.id(), source.definition()));
        return new DefinitionSnapshot(compiled);
    }

    private static Problem problem(Source source, String cause) {
        return new Problem(source.file(), source.id(), cause);
    }

    public Optional<TestDefinition> get(Identifier id) {
        return Optional.ofNullable(definitions.get(id));
    }

    public int size() {
        return definitions.size();
    }

    public Map<Identifier, TestDefinition> definitions() {
        return definitions;
    }

    public record Source(Identifier file, String packId, Identifier id, TestDefinition definition) {
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
            super(problems.stream().map(Problem::toString).reduce((left, right) -> left + "; " + right).orElse("invalid definitions"));
            this.problems = List.copyOf(problems);
        }

        public List<Problem> problems() {
            return problems;
        }
    }
}
