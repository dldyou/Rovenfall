package org.dldyou.rovenfall.rpg;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import net.minecraft.resources.Identifier;

/** Immutable player-facing projection built from one authoritative RPG definition/state snapshot. */
public record PlayerRpgView(
        long definitionRevision,
        long balance,
        List<ActivityRow> activities,
        List<CareerRow> careers,
        List<SkillRow> skills,
        List<SlotRow> slots,
        Set<Identifier> activeLineage) {

    public PlayerRpgView {
        activities = List.copyOf(activities);
        careers = List.copyOf(careers);
        skills = List.copyOf(skills);
        slots = List.copyOf(slots);
        activeLineage = Set.copyOf(activeLineage);
    }

    public static PlayerRpgView create(
            RpgDefinitionSnapshot definitions,
            RpgPlayerState state,
            long definitionRevision,
            long balance,
            long gameTime) {
        Set<Identifier> lineage = activeLineage(state.activeCareer(), definitions);
        return new PlayerRpgView(
                definitionRevision,
                balance,
                activities(definitions, state),
                careers(definitions, state, balance, lineage),
                skills(definitions, state, lineage, gameTime),
                slots(definitions, state, gameTime),
                lineage);
    }

    private static List<ActivityRow> activities(RpgDefinitionSnapshot definitions, RpgPlayerState state) {
        Set<Identifier> ids = new TreeSet<>(definitions.activities().keySet());
        ids.addAll(state.activityXp().keySet());
        return ids.stream().map(id -> {
            ActivityDefinition definition = definitions.activity(id).orElse(null);
            long xp = state.activityXp().getOrDefault(id, 0L);
            int level = definition == null ? 0 : levelForXp(xp, definition.levelXp());
            long next = definition == null || level >= definition.levelXp().size()
                    ? 0L : definition.levelXp().get(level);
            return new ActivityRow(id, definition == null ? Optional.empty() : Optional.of(definition.translationKey()),
                    xp, level, next, definition == null);
        }).toList();
    }

    private static List<CareerRow> careers(
            RpgDefinitionSnapshot definitions,
            RpgPlayerState state,
            long balance,
            Set<Identifier> lineage) {
        Set<Identifier> ids = new TreeSet<>(definitions.careers().keySet());
        ids.addAll(state.careers().keySet());
        state.activeCareer().ifPresent(ids::add);
        return ids.stream().map(id -> {
            CareerDefinition definition = definitions.career(id).orElse(null);
            RpgPlayerState.CareerProgress progress = state.careers().get(id);
            List<Requirement> requirements = definition == null
                    ? List.of()
                    : careerRequirements(definition, definitions, state, balance);
            Lock lock = definition == null
                    ? new Lock(LockReason.UNRESOLVED, Optional.of(id), 0, 0)
                    : progress != null
                            ? Lock.NONE
                            : requirements.stream().filter(requirement -> !requirement.met())
                                    .findFirst()
                                    .map(Requirement::lock)
                                    .orElse(Lock.NONE);
            return new CareerRow(
                    id,
                    definition == null ? Optional.empty() : Optional.of(definition.translationKey()),
                    definition == null ? 0 : definition.tier(),
                    progress == null ? 0L : progress.experience(),
                    progress == null ? 0 : progress.rank(),
                    progress == null ? 0 : progress.skillPoints(),
                    progress != null,
                    state.activeCareer().filter(id::equals).isPresent(),
                    lineage.contains(id),
                    definition == null ? 0L : definition.promotionCost(),
                    requirements,
                    lock,
                    definition == null);
        }).toList();
    }

    private static List<Requirement> careerRequirements(
            CareerDefinition definition,
            RpgDefinitionSnapshot definitions,
            RpgPlayerState state,
            long balance) {
        List<Requirement> result = new ArrayList<>();
        for (Identifier parent : definition.parents()) {
            CareerDefinition parentDefinition = definitions.career(parent).orElse(null);
            RpgPlayerState.CareerProgress parentProgress = state.careers().get(parent);
            int required = parentDefinition == null ? 1 : parentDefinition.levelXp().size();
            int actual = parentProgress == null ? 0 : Math.min(parentProgress.rank(),
                    parentDefinition == null ? parentProgress.rank()
                            : levelForXp(parentProgress.experience(), parentDefinition.levelXp()));
            LockReason reason = parentProgress == null ? LockReason.MISSING_PARENT : LockReason.PARENT_RANK;
            result.add(new Requirement(parent, required, actual,
                    new Lock(reason, Optional.of(parent), required, actual)));
        }
        for (CareerDefinition.ActivityRequirement requirement : definition.requiredActivities()) {
            ActivityDefinition activity = definitions.activity(requirement.activity()).orElse(null);
            int actual = activity == null ? 0 : levelForXp(
                    state.activityXp().getOrDefault(requirement.activity(), 0L), activity.levelXp());
            result.add(new Requirement(requirement.activity(), requirement.level(), actual,
                    new Lock(LockReason.ACTIVITY_LEVEL, Optional.of(requirement.activity()),
                            requirement.level(), actual)));
        }
        if (definition.promotionCost() > 0) {
            long required = definition.promotionCost();
            long actual = Math.max(0L, balance);
            result.add(new Requirement(null, required, actual,
                    new Lock(LockReason.INSUFFICIENT_FUNDS, Optional.empty(), required, actual)));
        }
        return List.copyOf(result);
    }

    private static List<SkillRow> skills(
            RpgDefinitionSnapshot definitions,
            RpgPlayerState state,
            Set<Identifier> lineage,
            long gameTime) {
        Set<Identifier> ids = new TreeSet<>(definitions.skills().keySet());
        state.careers().values().forEach(progress -> ids.addAll(progress.learnedSkills().keySet()));
        state.activeSkillSlots().values().forEach(ids::add);
        state.cooldowns().keySet().forEach(ids::add);
        return ids.stream().map(id -> {
            SkillDefinition definition = definitions.skill(id).orElse(null);
            Identifier career = definition == null ? learnedCareer(state, id).orElse(null) : definition.career();
            RpgPlayerState.CareerProgress progress = career == null ? null : state.careers().get(career);
            int rank = progress == null ? 0 : progress.learnedSkills().getOrDefault(id, 0);
            List<Requirement> requirements = definition == null
                    ? List.of()
                    : skillRequirements(definition, definitions, state);
            Lock lock;
            if (definition == null) {
                lock = new Lock(LockReason.UNRESOLVED, Optional.of(id), 0, 0);
            } else if (progress == null) {
                lock = new Lock(LockReason.CAREER_NOT_PROMOTED, Optional.of(definition.career()), 1, 0);
            } else if (rank >= definition.maxRank()) {
                lock = new Lock(LockReason.MAX_RANK, Optional.of(id), definition.maxRank(), rank);
            } else {
                lock = requirements.stream().filter(requirement -> !requirement.met())
                        .findFirst().map(Requirement::lock).orElseGet(() ->
                                progress.skillPoints() < definition.pointCost()
                                        ? new Lock(LockReason.INSUFFICIENT_POINTS, Optional.of(definition.career()),
                                                definition.pointCost(), progress.skillPoints())
                                        : Lock.NONE);
            }
            long cooldownUntil = state.cooldowns().getOrDefault(id, 0L);
            return new SkillRow(
                    id,
                    definition == null ? Optional.empty() : Optional.of(definition.translationKey()),
                    Optional.ofNullable(career),
                    definition == null ? Optional.empty() : Optional.of(definition.kind()),
                    rank,
                    definition == null ? 0 : definition.maxRank(),
                    definition == null ? 0 : definition.pointCost(),
                    requirements,
                    lock,
                    career != null && lineage.contains(career),
                    Math.max(0L, cooldownUntil - gameTime),
                    definition == null);
        }).toList();
    }

    private static List<Requirement> skillRequirements(
            SkillDefinition definition,
            RpgDefinitionSnapshot definitions,
            RpgPlayerState state) {
        return definition.prerequisites().stream().map(prerequisite -> {
            SkillDefinition required = definitions.skill(prerequisite.skill()).orElse(null);
            RpgPlayerState.CareerProgress progress = required == null ? null : state.careers().get(required.career());
            int actual = progress == null ? 0 : progress.learnedSkills().getOrDefault(prerequisite.skill(), 0);
            return new Requirement(prerequisite.skill(), prerequisite.rank(), actual,
                    new Lock(LockReason.SKILL_PREREQUISITE, Optional.of(prerequisite.skill()),
                            prerequisite.rank(), actual));
        }).toList();
    }

    private static List<SlotRow> slots(
            RpgDefinitionSnapshot definitions, RpgPlayerState state, long gameTime) {
        return java.util.stream.IntStream.range(0, RpgPlayerState.MAX_ACTIVE_SKILL_SLOTS)
                .mapToObj(slot -> {
                    Identifier skill = state.activeSkillSlots().get(slot);
                    long cooldownUntil = skill == null ? 0L : state.cooldowns().getOrDefault(skill, 0L);
                    return new SlotRow(slot, Optional.ofNullable(skill),
                            skill != null && definitions.skill(skill).isEmpty(),
                            Math.max(0L, cooldownUntil - gameTime));
                }).toList();
    }

    private static Optional<Identifier> learnedCareer(RpgPlayerState state, Identifier skill) {
        return state.careers().entrySet().stream()
                .filter(entry -> entry.getValue().learnedSkills().containsKey(skill))
                .map(Map.Entry::getKey).findFirst();
    }

    private static Set<Identifier> activeLineage(
            Optional<Identifier> activeCareer, RpgDefinitionSnapshot definitions) {
        if (activeCareer.isEmpty()) {
            return Set.of();
        }
        Set<Identifier> result = new LinkedHashSet<>();
        ArrayDeque<Identifier> remaining = new ArrayDeque<>();
        remaining.add(activeCareer.orElseThrow());
        while (!remaining.isEmpty()) {
            Identifier current = remaining.removeFirst();
            if (result.add(current)) {
                definitions.career(current).ifPresent(definition -> remaining.addAll(definition.parents()));
            }
        }
        return Set.copyOf(result);
    }

    private static int levelForXp(long experience, List<Long> thresholds) {
        int level = 0;
        while (level < thresholds.size() && experience >= thresholds.get(level)) {
            level++;
        }
        return level;
    }

    public record ActivityRow(
            Identifier id, Optional<String> translationKey, long experience, int level, long nextLevelXp,
            boolean unresolved) {
    }

    public record CareerRow(
            Identifier id,
            Optional<String> translationKey,
            int tier,
            long experience,
            int rank,
            int skillPoints,
            boolean promoted,
            boolean active,
            boolean inActiveLineage,
            long promotionCost,
            List<Requirement> requirements,
            Lock lock,
            boolean unresolved) {
        public CareerRow {
            requirements = List.copyOf(requirements);
        }
    }

    public record SkillRow(
            Identifier id,
            Optional<String> translationKey,
            Optional<Identifier> career,
            Optional<SkillDefinition.Kind> kind,
            int rank,
            int maxRank,
            int pointCost,
            List<Requirement> requirements,
            Lock lock,
            boolean activeLineage,
            long cooldownTicks,
            boolean unresolved) {
        public SkillRow {
            requirements = List.copyOf(requirements);
        }
    }

    public record SlotRow(int slot, Optional<Identifier> skill, boolean unresolved, long cooldownTicks) {
    }

    public record Requirement(Identifier target, long required, long actual, Lock lock) {
        public boolean met() {
            return actual >= required;
        }
    }

    public record Lock(LockReason reason, Optional<Identifier> blocker, long required, long actual) {
        public static final Lock NONE = new Lock(LockReason.NONE, Optional.empty(), 0, 0);

        public Lock {
            blocker = blocker == null ? Optional.empty() : blocker;
        }

        public boolean locked() {
            return reason != LockReason.NONE;
        }
    }

    public enum LockReason {
        NONE,
        UNRESOLVED,
        MISSING_PARENT,
        PARENT_RANK,
        ACTIVITY_LEVEL,
        INSUFFICIENT_FUNDS,
        CAREER_NOT_PROMOTED,
        SKILL_PREREQUISITE,
        INSUFFICIENT_POINTS,
        MAX_RANK
    }
}
