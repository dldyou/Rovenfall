package org.dldyou.rovenfall.rpg;

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
import java.util.function.Function;
import java.util.regex.Pattern;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import org.dldyou.rovenfall.Rovenfall;

public final class RpgDefinitionSnapshot {
    public static final int MAX_DEFINITIONS_PER_KIND = 4_096;
    private static final Pattern TRANSLATION_KEY = Pattern.compile("[a-z0-9_.-]{1,160}");
    private static final Identifier CATALOG_FILE = Identifier.fromNamespaceAndPath(Rovenfall.MOD_ID, "rpg_definition_catalog");
    private static final RpgDefinitionSnapshot EMPTY = new RpgDefinitionSnapshot(Map.of(), Map.of(), Map.of());

    private final Map<Identifier, ActivityDefinition> activities;
    private final Map<Identifier, CareerDefinition> careers;
    private final Map<Identifier, SkillDefinition> skills;

    private RpgDefinitionSnapshot(
            Map<Identifier, ActivityDefinition> activities,
            Map<Identifier, CareerDefinition> careers,
            Map<Identifier, SkillDefinition> skills) {
        this.activities = Map.copyOf(activities);
        this.careers = Map.copyOf(careers);
        this.skills = Map.copyOf(skills);
    }

    public static RpgDefinitionSnapshot empty() {
        return EMPTY;
    }

    public static RpgDefinitionSnapshot compile(
            Collection<ActivitySource> activityCandidates,
            Collection<CareerSource> careerCandidates,
            Collection<SkillSource> skillCandidates) {
        List<ActivitySource> activitySources = ordered(activityCandidates, ActivitySource::id, ActivitySource::file);
        List<CareerSource> careerSources = ordered(careerCandidates, CareerSource::id, CareerSource::file);
        List<SkillSource> skillSources = ordered(skillCandidates, SkillSource::id, SkillSource::file);
        List<Problem> problems = new ArrayList<>();

        validateCount("activity", activitySources.size(), problems);
        validateCount("career", careerSources.size(), problems);
        validateCount("skill", skillSources.size(), problems);
        validateDuplicates("activity", activitySources, ActivitySource::id, ActivitySource::file, ActivitySource::packId, problems);
        validateDuplicates("career", careerSources, CareerSource::id, CareerSource::file, CareerSource::packId, problems);
        validateDuplicates("skill", skillSources, SkillSource::id, SkillSource::file, SkillSource::packId, problems);

        Map<Identifier, ActivitySource> activities = unique(activitySources, ActivitySource::id);
        Map<Identifier, CareerSource> careers = unique(careerSources, CareerSource::id);
        Map<Identifier, SkillSource> skills = unique(skillSources, SkillSource::id);

        validateActivities(activitySources, problems);
        validateCareers(careerSources, activities, careers, problems);
        boolean careerCycle = validateCycles(
                "career", careers, source -> source.definition().parents(), problems);
        validateSkills(skillSources, careers, skills, careerCycle, problems);
        validateCycles(
                "skill",
                skills,
                source -> source.definition().prerequisites().stream().map(SkillDefinition.Prerequisite::skill).toList(),
                problems);

        if (!problems.isEmpty()) {
            throw new ValidationException(problems);
        }

        Map<Identifier, ActivityDefinition> compiledActivities = new LinkedHashMap<>();
        activitySources.forEach(source -> compiledActivities.put(source.id(), source.definition()));
        Map<Identifier, CareerDefinition> compiledCareers = new LinkedHashMap<>();
        careerSources.forEach(source -> compiledCareers.put(source.id(), source.definition()));
        Map<Identifier, SkillDefinition> compiledSkills = new LinkedHashMap<>();
        skillSources.forEach(source -> compiledSkills.put(source.id(), source.definition()));
        return new RpgDefinitionSnapshot(compiledActivities, compiledCareers, compiledSkills);
    }

    private static <T> List<T> ordered(
            Collection<T> candidates,
            Function<T, Identifier> id,
            Function<T, Identifier> file) {
        return candidates.stream().sorted(Comparator.comparing(id).thenComparing(file)).toList();
    }

    private static void validateCount(String kind, int count, List<Problem> problems) {
        if (count > MAX_DEFINITIONS_PER_KIND) {
            problems.add(new Problem(CATALOG_FILE, CATALOG_FILE,
                    kind + " definition count exceeds " + MAX_DEFINITIONS_PER_KIND));
        }
    }

    private static <T> void validateDuplicates(
            String kind,
            List<T> sources,
            Function<T, Identifier> id,
            Function<T, Identifier> file,
            Function<T, String> pack,
            List<Problem> problems) {
        Map<Identifier, List<T>> byId = new LinkedHashMap<>();
        sources.forEach(source -> byId.computeIfAbsent(id.apply(source), ignored -> new ArrayList<>()).add(source));
        byId.forEach((definitionId, duplicates) -> {
            if (duplicates.size() > 1) {
                String locations = duplicates.stream()
                        .map(source -> file.apply(source) + " (" + pack.apply(source) + ")")
                        .toList().toString();
                problems.add(new Problem(file.apply(duplicates.getFirst()), definitionId,
                        "duplicate " + kind + " definition ID in " + locations));
            }
        });
    }

    private static <T> Map<Identifier, T> unique(List<T> sources, Function<T, Identifier> id) {
        Map<Identifier, T> result = new LinkedHashMap<>();
        sources.forEach(source -> result.putIfAbsent(id.apply(source), source));
        return result;
    }

    private static void validateActivities(List<ActivitySource> sources, List<Problem> problems) {
        for (ActivitySource source : sources) {
            ActivityDefinition definition = source.definition();
            validateTranslation(source.file(), source.id(), definition.translationKey(), problems);
            validateXpCurve(source.file(), source.id(), definition.levelXp(), ActivityDefinition.MAX_LEVELS, problems);
        }
    }

    private static void validateCareers(
            List<CareerSource> sources,
            Map<Identifier, ActivitySource> activities,
            Map<Identifier, CareerSource> careers,
            List<Problem> problems) {
        for (CareerSource source : sources) {
            CareerDefinition definition = source.definition();
            validateTranslation(source.file(), source.id(), definition.translationKey(), problems);
            validateXpCurve(source.file(), source.id(), definition.levelXp(), CareerDefinition.MAX_LEVELS, problems);
            if (definition.tier() < 1 || definition.tier() > CareerDefinition.MAX_TIER) {
                problems.add(problem(source, "tier must be between 1 and " + CareerDefinition.MAX_TIER));
            }
            if (definition.tier() == 1 && !definition.parents().isEmpty()) {
                problems.add(problem(source, "tier 1 career cannot have a parent"));
            } else if (definition.tier() > 1 && definition.parents().isEmpty()) {
                problems.add(problem(source, "tier greater than 1 career requires at least one parent"));
            }
            if (definition.promotionCost() < 0 || definition.promotionCost() > CareerDefinition.MAX_PROMOTION_COST) {
                problems.add(problem(source, "promotion cost must be between 0 and " + CareerDefinition.MAX_PROMOTION_COST));
            }
            if (definition.careerXpMultiplier() < 1
                    || definition.careerXpMultiplier() > CareerDefinition.MAX_CAREER_XP_MULTIPLIER) {
                problems.add(problem(source, "career XP multiplier must be between 1 and "
                        + CareerDefinition.MAX_CAREER_XP_MULTIPLIER));
            }
            if (definition.parents().size() > CareerDefinition.MAX_PARENTS) {
                problems.add(problem(source, "parent count exceeds " + CareerDefinition.MAX_PARENTS));
            }
            if (definition.requiredActivities().size() > CareerDefinition.MAX_REQUIREMENTS) {
                problems.add(problem(source, "activity requirement count exceeds " + CareerDefinition.MAX_REQUIREMENTS));
            }
            validateItemCosts(source.file(), source.id(), "promotion", definition.promotionItems(), problems);
            validateItemCosts(source.file(), source.id(), "full reset", definition.fullResetItems(), problems);

            Set<Identifier> seenParents = new HashSet<>();
            for (Identifier parentId : definition.parents()) {
                if (!seenParents.add(parentId)) {
                    problems.add(problem(source, "duplicate parent career: " + parentId));
                    continue;
                }
                CareerSource parent = careers.get(parentId);
                if (parent == null) {
                    problems.add(problem(source, "missing parent career: " + parentId));
                } else if (parent.definition().tier() >= definition.tier()) {
                    problems.add(problem(source, "parent career " + parentId + " must have a lower tier"));
                }
            }

            Set<Identifier> seenActivities = new HashSet<>();
            for (CareerDefinition.ActivityRequirement requirement : definition.requiredActivities()) {
                if (!seenActivities.add(requirement.activity())) {
                    problems.add(problem(source, "duplicate activity requirement: " + requirement.activity()));
                    continue;
                }
                ActivitySource activity = activities.get(requirement.activity());
                if (activity == null) {
                    problems.add(problem(source, "missing activity: " + requirement.activity()));
                } else if (requirement.level() < 1 || requirement.level() > activity.definition().levelXp().size()) {
                    problems.add(problem(source, "activity requirement " + requirement.activity()
                            + " level exceeds its configured curve"));
                }
            }
        }
    }

    private static void validateSkills(
            List<SkillSource> sources,
            Map<Identifier, CareerSource> careers,
            Map<Identifier, SkillSource> skills,
            boolean careerCycle,
            List<Problem> problems) {
        for (SkillSource source : sources) {
            SkillDefinition definition = source.definition();
            validateTranslation(source.file(), source.id(), definition.translationKey(), problems);
            CareerSource career = careers.get(definition.career());
            if (career == null) {
                problems.add(problem(source, "missing career: " + definition.career()));
            }
            if (definition.maxRank() < 1 || definition.maxRank() > SkillDefinition.MAX_RANK) {
                problems.add(problem(source, "max rank must be between 1 and " + SkillDefinition.MAX_RANK));
            }
            if (definition.pointCost() < 1 || definition.pointCost() > SkillDefinition.MAX_POINT_COST) {
                problems.add(problem(source, "point cost must be between 1 and " + SkillDefinition.MAX_POINT_COST));
            }
            if (definition.prerequisites().size() > SkillDefinition.MAX_PREREQUISITES) {
                problems.add(problem(source, "skill prerequisite count exceeds " + SkillDefinition.MAX_PREREQUISITES));
            }
            validateItemCosts(source.file(), source.id(), "branch reset", definition.branchResetItems(), problems);
            if (definition.kind() == SkillDefinition.Kind.ACTIVE && definition.cooldownTicks().isEmpty()) {
                problems.add(problem(source, "active skill requires cooldown_ticks"));
            } else if (definition.kind() == SkillDefinition.Kind.PASSIVE && definition.cooldownTicks().isPresent()) {
                problems.add(problem(source, "passive skill cannot define cooldown_ticks"));
            }
            if (definition.kind() == SkillDefinition.Kind.PASSIVE && definition.passiveEffect().isEmpty()) {
                problems.add(problem(source, "passive skill requires passive_effect"));
            } else if (definition.kind() == SkillDefinition.Kind.ACTIVE && definition.passiveEffect().isPresent()) {
                problems.add(problem(source, "active skill cannot define passive_effect"));
            }
            if (definition.kind() == SkillDefinition.Kind.PASSIVE && definition.activeEffect().isPresent()) {
                problems.add(problem(source, "passive skill cannot define active_effect"));
            } else if (definition.kind() == SkillDefinition.Kind.ACTIVE && definition.activeEffect().isEmpty()) {
                problems.add(problem(source, "active skill requires active_effect"));
            }
            definition.passiveEffect().ifPresent(effect -> {
                if (effect.type() == null || effect.basisPointsPerRank() < 1
                        || effect.basisPointsPerRank() > SkillDefinition.PassiveEffect.MAX_BASIS_POINTS_PER_RANK) {
                    problems.add(problem(source, "passive effect is invalid"));
                }
            });
            definition.activeEffect().ifPresent(effect -> {
                if (effect.type() == null || effect.target() == null
                        || effect.basisPointsPerRank() < 1
                        || effect.basisPointsPerRank() > SkillDefinition.ActiveEffect.MAX_BASIS_POINTS_PER_RANK
                        || effect.durationTicks() < 1
                        || effect.durationTicks() > SkillDefinition.ActiveEffect.MAX_DURATION_TICKS
                        || !Double.isFinite(effect.range()) || effect.range() < 0
                        || effect.range() > SkillDefinition.ActiveEffect.MAX_RANGE
                        || (effect.target() == SkillDefinition.TargetType.SELF && effect.range() != 0)
                        || (effect.target() == SkillDefinition.TargetType.LIVING_ENTITY && effect.range() <= 0)
                        || (effect.type() == SkillDefinition.EffectType.DAMAGE_DEALT
                                && effect.target() != SkillDefinition.TargetType.LIVING_ENTITY)
                        || (effect.type() == SkillDefinition.EffectType.DAMAGE_TAKEN_REDUCTION
                                && effect.target() != SkillDefinition.TargetType.SELF)) {
                    problems.add(problem(source, "active effect is invalid"));
                }
            });
            definition.cooldownTicks().ifPresent(cooldown -> {
                if (cooldown < 1 || cooldown > SkillDefinition.MAX_COOLDOWN_TICKS) {
                    problems.add(problem(source, "cooldown must be between 1 and " + SkillDefinition.MAX_COOLDOWN_TICKS));
                }
            });

            Set<Identifier> seenPrerequisites = new HashSet<>();
            for (SkillDefinition.Prerequisite prerequisite : definition.prerequisites()) {
                if (!seenPrerequisites.add(prerequisite.skill())) {
                    problems.add(problem(source, "duplicate skill prerequisite: " + prerequisite.skill()));
                    continue;
                }
                SkillSource required = skills.get(prerequisite.skill());
                if (required == null) {
                    problems.add(problem(source, "missing skill prerequisite: " + prerequisite.skill()));
                    continue;
                }
                if (prerequisite.rank() < 1 || prerequisite.rank() > required.definition().maxRank()) {
                    problems.add(problem(source, "skill prerequisite " + prerequisite.skill()
                            + " rank exceeds its maximum rank"));
                }
                if (!careerCycle && career != null && !isCareerInLineage(
                        required.definition().career(), definition.career(), careers)) {
                    problems.add(problem(source, "skill prerequisite " + prerequisite.skill()
                            + " belongs outside the career lineage"));
                }
            }
        }
    }

    private static boolean isCareerInLineage(
            Identifier expected,
            Identifier careerId,
            Map<Identifier, CareerSource> careers) {
        var remaining = new ArrayDeque<Identifier>();
        var visited = new HashSet<Identifier>();
        remaining.add(careerId);
        while (!remaining.isEmpty()) {
            Identifier current = remaining.removeFirst();
            if (!visited.add(current)) {
                continue;
            }
            if (current.equals(expected)) {
                return true;
            }
            CareerSource career = careers.get(current);
            if (career != null) {
                remaining.addAll(career.definition().parents());
            }
        }
        return false;
    }

    private static <T> boolean validateCycles(
            String kind,
            Map<Identifier, T> sources,
            Function<T, List<Identifier>> dependencies,
            List<Problem> problems) {
        Map<Identifier, Integer> dependencyCount = new HashMap<>();
        Map<Identifier, List<Identifier>> dependents = new HashMap<>();
        sources.forEach((id, source) -> {
            int count = 0;
            for (Identifier dependency : new HashSet<>(dependencies.apply(source))) {
                if (sources.containsKey(dependency)) {
                    count++;
                    dependents.computeIfAbsent(dependency, ignored -> new ArrayList<>()).add(id);
                }
            }
            dependencyCount.put(id, count);
        });

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
        if (resolved == sources.size()) {
            return false;
        }

        dependencyCount.entrySet().stream()
                .filter(entry -> entry.getValue() > 0)
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    T source = sources.get(entry.getKey());
                    Identifier file = source instanceof CareerSource career ? career.file() : ((SkillSource) source).file();
                    problems.add(new Problem(file, entry.getKey(), kind + " dependency cycle"));
                });
        return true;
    }

    private static void validateTranslation(
            Identifier file,
            Identifier definitionId,
            String translationKey,
            List<Problem> problems) {
        if (translationKey == null || !TRANSLATION_KEY.matcher(translationKey).matches()) {
            problems.add(new Problem(file, definitionId, "invalid translation key: " + translationKey));
        }
    }

    private static void validateItemCosts(
            Identifier file,
            Identifier definitionId,
            String operation,
            List<RpgItemCost> costs,
            List<Problem> problems) {
        if (costs == null || costs.size() > RpgItemCost.MAX_ENTRIES) {
            problems.add(new Problem(file, definitionId,
                    operation + " item cost count exceeds " + RpgItemCost.MAX_ENTRIES));
            return;
        }
        Set<Identifier> seen = new HashSet<>();
        for (RpgItemCost cost : costs) {
            if (cost == null || cost.item() == null || cost.count() < 1 || cost.count() > RpgItemCost.MAX_COUNT) {
                problems.add(new Problem(file, definitionId, operation + " item cost is invalid"));
            } else if (!seen.add(cost.item())) {
                problems.add(new Problem(file, definitionId,
                        "duplicate " + operation + " item: " + cost.item()));
            } else if (!BuiltInRegistries.ITEM.containsKey(cost.item())) {
                problems.add(new Problem(file, definitionId,
                        "unknown " + operation + " item: " + cost.item()));
            }
        }
    }

    private static void validateXpCurve(
            Identifier file,
            Identifier definitionId,
            List<Long> curve,
            int maximumLevels,
            List<Problem> problems) {
        if (curve == null || curve.isEmpty() || curve.size() > maximumLevels) {
            problems.add(new Problem(file, definitionId,
                    "XP curve requires between 1 and " + maximumLevels + " levels"));
            return;
        }
        long previous = 0;
        for (long threshold : curve) {
            if (threshold <= previous || threshold > ActivityDefinition.MAX_XP) {
                problems.add(new Problem(file, definitionId,
                        "XP thresholds must be strictly increasing and at most " + ActivityDefinition.MAX_XP));
                return;
            }
            previous = threshold;
        }
    }

    private static Problem problem(CareerSource source, String cause) {
        return new Problem(source.file(), source.id(), cause);
    }

    private static Problem problem(SkillSource source, String cause) {
        return new Problem(source.file(), source.id(), cause);
    }

    public Optional<ActivityDefinition> activity(Identifier id) {
        return Optional.ofNullable(activities.get(id));
    }

    public Optional<CareerDefinition> career(Identifier id) {
        return Optional.ofNullable(careers.get(id));
    }

    public Optional<SkillDefinition> skill(Identifier id) {
        return Optional.ofNullable(skills.get(id));
    }

    public Map<Identifier, ActivityDefinition> activities() {
        return activities;
    }

    public Map<Identifier, CareerDefinition> careers() {
        return careers;
    }

    public Map<Identifier, SkillDefinition> skills() {
        return skills;
    }

    public record ActivitySource(Identifier file, String packId, Identifier id, ActivityDefinition definition) {
    }

    public record CareerSource(Identifier file, String packId, Identifier id, CareerDefinition definition) {
    }

    public record SkillSource(Identifier file, String packId, Identifier id, SkillDefinition definition) {
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
                    .orElse("invalid RPG definitions"));
            this.problems = List.copyOf(problems);
        }

        public List<Problem> problems() {
            return problems;
        }
    }
}
