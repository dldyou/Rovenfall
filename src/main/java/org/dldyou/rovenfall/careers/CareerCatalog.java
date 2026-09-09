package org.dldyou.rovenfall.careers;

import com.mojang.serialization.DataResult;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.resources.Identifier;
import org.dldyou.rovenfall.activities.ActivityTrack;

public final class CareerCatalog {
    public static final int MAX_DEFINITIONS = 4_096;
    private final Map<Identifier, CareerDefinition> definitions;
    private final Map<Identifier, Set<Identifier>> children;
    private final Map<Identifier, SkillBinding> skills;

    private CareerCatalog(
            Map<Identifier, CareerDefinition> definitions,
            Map<Identifier, Set<Identifier>> children,
            Map<Identifier, SkillBinding> skills) {
        this.definitions = Map.copyOf(definitions);
        Map<Identifier, Set<Identifier>> copiedChildren = new HashMap<>();
        children.forEach((id, values) -> copiedChildren.put(id, Set.copyOf(values)));
        this.children = Map.copyOf(copiedChildren);
        this.skills = Map.copyOf(skills);
    }

    public static DataResult<CareerCatalog> create(Map<Identifier, CareerDefinition> definitions) {
        if (definitions == null || definitions.isEmpty() || definitions.size() > MAX_DEFINITIONS) {
            return DataResult.error(() -> "career catalog is empty or exceeds " + MAX_DEFINITIONS);
        }
        Map<Identifier, Set<Identifier>> children = new HashMap<>();
        for (var entry : definitions.entrySet()) {
            Identifier id = entry.getKey();
            CareerDefinition definition = entry.getValue();
            if (id == null || definition == null || CareerDefinition.validate(definition).error().isPresent()) {
                return DataResult.error(() -> "career catalog contains an invalid definition at " + id);
            }
            for (Identifier parentId : definition.parents()) {
                CareerDefinition parent = definitions.get(parentId);
                if (parent == null) {
                    return DataResult.error(() -> id + " references missing parent " + parentId);
                }
                if (parentId.equals(id)) {
                    return DataResult.error(() -> id + " cannot parent itself");
                }
                if (parent.tier() >= definition.tier()) {
                    return DataResult.error(() -> id + " must have a higher tier than parent " + parentId);
                }
                children.computeIfAbsent(parentId, ignored -> new LinkedHashSet<>()).add(id);
            }
        }
        Map<Identifier, SkillBinding> skills = new HashMap<>();
        for (var careerEntry : definitions.entrySet()) {
            Identifier careerId = careerEntry.getKey();
            CareerDefinition career = careerEntry.getValue();
            for (var skillEntry : career.skills().entrySet()) {
                Identifier skillId = skillEntry.getKey();
                CareerSkillDefinition skill = skillEntry.getValue();
                if (skillId == null || skill == null
                        || CareerSkillDefinition.validate(skill).error().isPresent()) {
                    return DataResult.error(() -> careerId + " contains an invalid skill at " + skillId);
                }
                if (skills.putIfAbsent(skillId, new SkillBinding(careerId, skill)) != null) {
                    return DataResult.error(() -> "duplicate career skill ID " + skillId);
                }
            }
            Map<Identifier, Visit> skillVisits = new HashMap<>();
            for (var skillEntry : career.skills().entrySet()) {
                Identifier skillId = skillEntry.getKey();
                for (Identifier prerequisite : skillEntry.getValue().prerequisites()) {
                    if (!career.skills().containsKey(prerequisite)) {
                        return DataResult.error(() -> skillId + " references missing same-career skill "
                                + prerequisite);
                    }
                    if (skillId.equals(prerequisite)) {
                        return DataResult.error(() -> skillId + " cannot require itself");
                    }
                }
                Optional<String> problem = visitSkill(skillId, career.skills(), skillVisits);
                if (problem.isPresent()) {
                    return DataResult.error(problem::orElseThrow);
                }
            }
        }

        Map<Identifier, Visit> visits = new HashMap<>();
        for (Identifier id : definitions.keySet()) {
            Optional<String> problem = visit(id, definitions, visits);
            if (problem.isPresent()) {
                return DataResult.error(problem::orElseThrow);
            }
        }
        return DataResult.success(new CareerCatalog(definitions, children, skills));
    }

    private static Optional<String> visitSkill(
            Identifier id,
            Map<Identifier, CareerSkillDefinition> definitions,
            Map<Identifier, Visit> visits) {
        Visit visit = visits.get(id);
        if (visit == Visit.COMPLETE) {
            return Optional.empty();
        }
        if (visit == Visit.ACTIVE) {
            return Optional.of("career skill graph contains a cycle at " + id);
        }
        visits.put(id, Visit.ACTIVE);
        for (Identifier prerequisite : definitions.get(id).prerequisites()) {
            Optional<String> problem = visitSkill(prerequisite, definitions, visits);
            if (problem.isPresent()) {
                return problem;
            }
        }
        visits.put(id, Visit.COMPLETE);
        return Optional.empty();
    }

    private static Optional<String> visit(
            Identifier id,
            Map<Identifier, CareerDefinition> definitions,
            Map<Identifier, Visit> visits) {
        Visit visit = visits.get(id);
        if (visit == Visit.COMPLETE) {
            return Optional.empty();
        }
        if (visit == Visit.ACTIVE) {
            return Optional.of("career graph contains a cycle at " + id);
        }
        visits.put(id, Visit.ACTIVE);
        for (Identifier parent : definitions.get(id).parents()) {
            Optional<String> problem = visit(parent, definitions, visits);
            if (problem.isPresent()) {
                return problem;
            }
        }
        visits.put(id, Visit.COMPLETE);
        return Optional.empty();
    }

    public Optional<CareerDefinition> definition(Identifier id) {
        return Optional.ofNullable(definitions.get(id));
    }

    public Set<Identifier> ids() {
        return definitions.keySet();
    }

    public int size() {
        return definitions.size();
    }

    public Optional<SkillBinding> skill(Identifier id) {
        return Optional.ofNullable(skills.get(id));
    }

    public Set<Identifier> skillIds() {
        return skills.keySet();
    }

    public Set<Identifier> activeSkillIds() {
        return skills.entrySet().stream()
                .filter(entry -> entry.getValue().definition.active().isPresent())
                .map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public Set<Identifier> skillIds(Identifier careerId) {
        CareerDefinition definition = definitions.get(careerId);
        return definition == null ? Set.of() : definition.skills().keySet();
    }

    public int activityExperienceBonusBasisPoints(
            PlayerCareerState playerState,
            ActivityTrack track) {
        if (playerState == null || track == null || playerState.activeCareer().isEmpty()) {
            return 0;
        }
        Identifier activeCareer = playerState.activeCareer().orElseThrow();
        if (!definitions.containsKey(activeCareer)) {
            return 0;
        }
        Set<Identifier> activeLineage = new HashSet<>(ancestors(activeCareer));
        activeLineage.add(activeCareer);
        long total = 0;
        for (var careerEntry : playerState.progressByCareer().entrySet()) {
            Identifier learnedCareer = careerEntry.getKey();
            CareerProgress progress = careerEntry.getValue();
            for (var rankEntry : progress.skillRanks().entrySet()) {
                SkillBinding binding = skills.get(rankEntry.getKey());
                if (binding == null || !binding.careerId.equals(learnedCareer)
                        || binding.definition.scope() != CareerSkillDefinition.Scope.GLOBAL
                        && !activeLineage.contains(learnedCareer)) {
                    continue;
                }
                int rank = Math.min(rankEntry.getValue(), binding.definition.maximumRank());
                for (CareerSkillEffect effect : binding.definition.effects()) {
                    if (!effect.appliesTo(track)) {
                        continue;
                    }
                    total += (long) rank * effect.magnitudePerRankBasisPoints();
                    if (total >= CareerSkillEffect.MAX_TOTAL_ACTIVITY_BONUS_BASIS_POINTS) {
                        return CareerSkillEffect.MAX_TOTAL_ACTIVITY_BONUS_BASIS_POINTS;
                    }
                }
            }
        }
        return (int) total;
    }

    public Set<Identifier> ancestors(Identifier id) {
        if (!definitions.containsKey(id)) {
            return Set.of();
        }
        Set<Identifier> result = new LinkedHashSet<>();
        ArrayDeque<Identifier> pending = new ArrayDeque<>(definitions.get(id).parents());
        while (!pending.isEmpty()) {
            Identifier parent = pending.removeFirst();
            if (result.add(parent)) {
                pending.addAll(definitions.get(parent).parents());
            }
        }
        return Set.copyOf(result);
    }

    public Set<Identifier> descendants(Identifier id) {
        if (!definitions.containsKey(id)) {
            return Set.of();
        }
        Set<Identifier> result = new LinkedHashSet<>();
        ArrayDeque<Identifier> pending = new ArrayDeque<>(children.getOrDefault(id, Set.of()));
        while (!pending.isEmpty()) {
            Identifier child = pending.removeFirst();
            if (result.add(child)) {
                pending.addAll(children.getOrDefault(child, Set.of()));
            }
        }
        return Set.copyOf(result);
    }

    public Set<Identifier> conflictingLearnedCareers(Identifier target, Set<Identifier> learned) {
        if (!definitions.containsKey(target) || learned == null || learned.isEmpty()) {
            return Set.of();
        }
        Set<Identifier> ancestors = ancestors(target);
        Set<Identifier> targetLineage = new HashSet<>(ancestors);
        targetLineage.add(target);
        Set<Identifier> conflicts = new LinkedHashSet<>();
        for (Identifier ancestor : ancestors) {
            for (Identifier siblingRoot : children.getOrDefault(ancestor, Set.of())) {
                if (targetLineage.contains(siblingRoot)) {
                    continue;
                }
                if (learned.contains(siblingRoot)) {
                    conflicts.add(siblingRoot);
                }
                for (Identifier descendant : descendants(siblingRoot)) {
                    if (learned.contains(descendant)) {
                        conflicts.add(descendant);
                    }
                }
            }
        }
        return Set.copyOf(conflicts);
    }

    private enum Visit {
        ACTIVE,
        COMPLETE
    }

    public record SkillBinding(Identifier careerId, CareerSkillDefinition definition) {
    }
}
