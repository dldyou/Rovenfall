package org.dldyou.rovenfall.exploration;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.dldyou.rovenfall.Rovenfall;

/** Immutable, fully validated exploration catalog. */
public final class ExplorationDefinitionSnapshot {
    public static final int MAX_DEFINITIONS = 128;
    private static final Pattern TRANSLATION_KEY = Pattern.compile("[a-z0-9_.-]{1,160}");
    private static final Identifier CATALOG = Identifier.fromNamespaceAndPath(
            Rovenfall.MOD_ID, "exploration_definition_catalog");
    private static final ExplorationDefinitionSnapshot EMPTY = new ExplorationDefinitionSnapshot(Map.of());

    private final Map<Identifier, ExplorationDefinition> definitions;
    private final Map<ResourceKey<Level>, List<Entry>> byDimension;

    private ExplorationDefinitionSnapshot(Map<Identifier, ExplorationDefinition> definitions) {
        this.definitions = Map.copyOf(definitions);
        Map<ResourceKey<Level>, List<Entry>> grouped = new LinkedHashMap<>();
        definitions.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry ->
                grouped.computeIfAbsent(entry.getValue().dimension(), ignored -> new ArrayList<>())
                        .add(new Entry(entry.getKey(), entry.getValue())));
        Map<ResourceKey<Level>, List<Entry>> immutable = new LinkedHashMap<>();
        grouped.forEach((dimension, entries) -> immutable.put(dimension, List.copyOf(entries)));
        this.byDimension = Map.copyOf(immutable);
    }

    public static ExplorationDefinitionSnapshot empty() {
        return EMPTY;
    }

    public static ExplorationDefinitionSnapshot compile(Collection<Source> candidates) {
        if (candidates == null) {
            throw new ValidationException(List.of(new Problem(CATALOG, CATALOG, "definition catalog is null")));
        }
        List<Problem> problems = new ArrayList<>();
        List<Source> sources = candidates.stream()
                .filter(source -> {
                    if (source == null || source.id() == null || source.file() == null
                            || source.packId() == null || source.definition() == null) {
                        problems.add(new Problem(CATALOG, CATALOG,
                                "exploration definition source is invalid"));
                        return false;
                    }
                    return true;
                })
                .sorted(Comparator.comparing(Source::id).thenComparing(Source::file))
                .toList();
        if (sources.size() > MAX_DEFINITIONS) {
            problems.add(new Problem(CATALOG, CATALOG,
                    "exploration definition count exceeds " + MAX_DEFINITIONS));
        }
        Map<Identifier, List<Source>> byId = new LinkedHashMap<>();
        for (Source source : sources) {
            byId.computeIfAbsent(source.id(), ignored -> new ArrayList<>()).add(source);
            validate(source, problems);
        }
        byId.forEach((id, duplicates) -> {
            if (duplicates.size() > 1) {
                problems.add(new Problem(duplicates.getFirst().file(), id,
                        "duplicate exploration definition ID in " + duplicates.stream()
                                .map(source -> source.file() + " (" + source.packId() + ")").toList()));
            }
        });
        if (!problems.isEmpty()) {
            throw new ValidationException(problems);
        }
        Map<Identifier, ExplorationDefinition> definitions = new LinkedHashMap<>();
        sources.forEach(source -> definitions.put(source.id(), source.definition()));
        return new ExplorationDefinitionSnapshot(definitions);
    }

    private static void validate(Source source, List<Problem> problems) {
        ExplorationDefinition definition = source.definition();
        if (definition.titleTranslationKey() == null
                || !TRANSLATION_KEY.matcher(definition.titleTranslationKey()).matches()) {
            problems.add(problem(source, "invalid title translation key"));
        }
        if (definition.descriptionTranslationKey() == null
                || !TRANSLATION_KEY.matcher(definition.descriptionTranslationKey()).matches()) {
            problems.add(problem(source, "invalid description translation key"));
        }
        if (!definition.isValid()) {
            problems.add(problem(source, "invalid world, position, radius, version, or activity XP"));
        }
    }

    private static Problem problem(Source source, String cause) {
        return new Problem(source.file(), source.id(), cause);
    }

    public Optional<ExplorationDefinition> definition(Identifier id) {
        return Optional.ofNullable(definitions.get(id));
    }

    public Map<Identifier, ExplorationDefinition> definitions() {
        return definitions;
    }

    public List<Entry> entries(ResourceKey<Level> dimension) {
        return dimension == null ? List.of() : byDimension.getOrDefault(dimension, List.of());
    }

    public int size() {
        return definitions.size();
    }

    public record Entry(Identifier id, ExplorationDefinition definition) {
    }

    public record Source(Identifier file, String packId, Identifier id, ExplorationDefinition definition) {
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
                    .orElse("invalid exploration definitions"));
            this.problems = List.copyOf(problems);
        }

        public List<Problem> problems() {
            return problems;
        }
    }
}
