package org.dldyou.rovenfall.rpg;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.resources.Identifier;

/** Server-thread mutation boundary for RPG player state. */
public final class RpgPlayerStateService {
    private static final int MAX_SOURCE_LENGTH = 160;

    private RpgPlayerStateService() {
    }

    public static ActivityAwardResult awardActivityXp(
            RpgPlayerSavedData state,
            RpgDefinitionSnapshot definitions,
            UUID playerId,
            Identifier activity,
            long amount,
            String source,
            long timestamp) {
        if (!validRequest(state, definitions, playerId, activity, amount, source, timestamp)) {
            return new ActivityAwardResult(Status.INVALID_REQUEST, 0, 0);
        }
        if (!state.isWritable()) {
            return new ActivityAwardResult(Status.READ_ONLY, state.state(playerId).activityXp().getOrDefault(activity, 0L), 0);
        }
        if (definitions.activity(activity).isEmpty()) {
            return new ActivityAwardResult(Status.UNKNOWN_DEFINITION, 0, 0);
        }
        RpgPlayerState before = state.state(playerId);
        long oldActivityXp = before.activityXp().getOrDefault(activity, 0L);
        long newActivityXp = boundedAdd(oldActivityXp, amount, RpgPlayerState.MAX_XP);
        if (newActivityXp < 0) {
            return new ActivityAwardResult(Status.OVERFLOW, oldActivityXp, 0);
        }

        Map<Identifier, Long> activityXp = new HashMap<>(before.activityXp());
        activityXp.put(activity, newActivityXp);
        Map<Identifier, RpgPlayerState.CareerProgress> careers = new HashMap<>(before.careers());
        List<RpgPlayerState.ProgressionProvenance> provenance = new ArrayList<>(before.provenance());
        provenance.add(new RpgPlayerState.ProgressionProvenance(
                RpgPlayerState.ProgressionProvenance.Kind.ACTIVITY_XP, activity, amount, timestamp, source));

        int careerLevel = 0;
        if (before.activeCareer().isPresent()) {
            Identifier careerId = before.activeCareer().orElseThrow();
            CareerDefinition career = definitions.career(careerId).orElse(null);
            RpgPlayerState.CareerProgress current = careers.get(careerId);
            if (career == null || current == null) {
                return new ActivityAwardResult(Status.UNKNOWN_DEFINITION, oldActivityXp, 0);
            }
            long newCareerXp = boundedAdd(current.experience(), amount, RpgPlayerState.MAX_XP);
            if (newCareerXp < 0) {
                return new ActivityAwardResult(Status.OVERFLOW, oldActivityXp, 0);
            }
            careerLevel = levelAtXp(career.levelXp(), newCareerXp);
            int gainedPoints = careerLevel - current.rank();
            int points = boundedIntAdd(current.skillPoints(), Math.max(gainedPoints, 0), RpgPlayerState.MAX_SKILL_POINTS);
            if (points < 0) {
                return new ActivityAwardResult(Status.OVERFLOW, oldActivityXp, 0);
            }
            careers.put(careerId, new RpgPlayerState.CareerProgress(newCareerXp, careerLevel, points, current.learnedSkills()));
            provenance.add(new RpgPlayerState.ProgressionProvenance(
                    RpgPlayerState.ProgressionProvenance.Kind.CAREER_XP, careerId, amount, timestamp, source));
        }
        RpgPlayerState next = new RpgPlayerState(
                activityXp, careers, before.activeCareer(), before.activeSkillSlots(), before.cooldowns(),
                boundedProvenance(provenance));
        if (!state.commit(playerId, next)) {
            return new ActivityAwardResult(Status.STATE_FULL, oldActivityXp, careerLevel);
        }
        return new ActivityAwardResult(Status.SUCCESS, newActivityXp, careerLevel);
    }

    public static CareerAwardResult awardCareerXp(
            RpgPlayerSavedData state,
            RpgDefinitionSnapshot definitions,
            UUID playerId,
            Identifier careerId,
            long amount,
            String source,
            long timestamp) {
        if (!validRequest(state, definitions, playerId, careerId, amount, source, timestamp)) {
            return new CareerAwardResult(Status.INVALID_REQUEST, 0, 0, 0);
        }
        if (!state.isWritable()) {
            var current = state.state(playerId).careers().get(careerId);
            return new CareerAwardResult(Status.READ_ONLY, current == null ? 0 : current.experience(),
                    current == null ? 0 : current.rank(), current == null ? 0 : current.skillPoints());
        }
        CareerDefinition definition = definitions.career(careerId).orElse(null);
        RpgPlayerState before = state.state(playerId);
        RpgPlayerState.CareerProgress current = before.careers().get(careerId);
        if (definition == null || current == null) {
            return new CareerAwardResult(Status.UNKNOWN_DEFINITION, 0, 0, 0);
        }
        long experience = boundedAdd(current.experience(), amount, RpgPlayerState.MAX_XP);
        if (experience < 0) {
            return new CareerAwardResult(Status.OVERFLOW, current.experience(), current.rank(), current.skillPoints());
        }
        int rank = levelAtXp(definition.levelXp(), experience);
        int points = boundedIntAdd(current.skillPoints(), Math.max(0, rank - current.rank()), RpgPlayerState.MAX_SKILL_POINTS);
        if (points < 0) {
            return new CareerAwardResult(Status.OVERFLOW, current.experience(), current.rank(), current.skillPoints());
        }
        Map<Identifier, RpgPlayerState.CareerProgress> careers = new HashMap<>(before.careers());
        careers.put(careerId, new RpgPlayerState.CareerProgress(experience, rank, points, current.learnedSkills()));
        List<RpgPlayerState.ProgressionProvenance> provenance = new ArrayList<>(before.provenance());
        provenance.add(new RpgPlayerState.ProgressionProvenance(
                RpgPlayerState.ProgressionProvenance.Kind.CAREER_XP, careerId, amount, timestamp, source));
        RpgPlayerState next = new RpgPlayerState(
                before.activityXp(), careers, before.activeCareer(), before.activeSkillSlots(), before.cooldowns(),
                boundedProvenance(provenance));
        if (!state.commit(playerId, next)) {
            return new CareerAwardResult(Status.STATE_FULL, current.experience(), current.rank(), current.skillPoints());
        }
        return new CareerAwardResult(Status.SUCCESS, experience, rank, points);
    }

    public static PromotionResult promote(
            RpgPlayerSavedData state,
            RpgDefinitionSnapshot definitions,
            UUID playerId,
            Identifier careerId,
            long timestamp,
            String source) {
        if (state == null || definitions == null || playerId == null || careerId == null
                || !validSource(source) || timestamp < 0) {
            return new PromotionResult(Status.INVALID_REQUEST);
        }
        if (!state.isWritable()) {
            return new PromotionResult(Status.READ_ONLY);
        }
        CareerDefinition definition = definitions.career(careerId).orElse(null);
        if (definition == null) {
            return new PromotionResult(Status.UNKNOWN_DEFINITION);
        }
        RpgPlayerState before = state.state(playerId);
        if (before.careers().containsKey(careerId)) {
            return new PromotionResult(Status.ALREADY_LEARNED);
        }
        for (Identifier parent : definition.parents()) {
            if (!before.careers().containsKey(parent)) {
                return new PromotionResult(Status.PREREQUISITE_MISSING);
            }
        }
        for (CareerDefinition.ActivityRequirement requirement : definition.requiredActivities()) {
            long xp = before.activityXp().getOrDefault(requirement.activity(), 0L);
            var activity = definitions.activity(requirement.activity()).orElse(null);
            if (activity == null || levelAtXp(activity.levelXp(), xp) < requirement.level()) {
                return new PromotionResult(Status.PREREQUISITE_MISSING);
            }
        }
        if (before.activeCareer().isPresent() && hasBranchConflict(
                before.activeCareer().orElseThrow(), careerId, before.careers().keySet(), definitions)) {
            return new PromotionResult(Status.BRANCH_CONFLICT);
        }
        Map<Identifier, RpgPlayerState.CareerProgress> careers = new HashMap<>(before.careers());
        careers.put(careerId, new RpgPlayerState.CareerProgress(0, 0, 0, Map.of()));
        List<RpgPlayerState.ProgressionProvenance> provenance = new ArrayList<>(before.provenance());
        provenance.add(new RpgPlayerState.ProgressionProvenance(
                RpgPlayerState.ProgressionProvenance.Kind.CAREER_PROMOTION, careerId, 0, timestamp, source));
        RpgPlayerState next = new RpgPlayerState(
                before.activityXp(), careers, Optional.of(careerId), before.activeSkillSlots(), before.cooldowns(),
                boundedProvenance(provenance));
        return state.commit(playerId, next) ? new PromotionResult(Status.SUCCESS) : new PromotionResult(Status.STATE_FULL);
    }

    public static SkillResult learnSkill(
            RpgPlayerSavedData state,
            RpgDefinitionSnapshot definitions,
            UUID playerId,
            Identifier skillId,
            long timestamp,
            String source) {
        if (state == null || definitions == null || playerId == null || skillId == null
                || !validSource(source) || timestamp < 0) {
            return new SkillResult(Status.INVALID_REQUEST, 0);
        }
        if (!state.isWritable()) {
            return new SkillResult(Status.READ_ONLY, 0);
        }
        SkillDefinition skill = definitions.skill(skillId).orElse(null);
        RpgPlayerState before = state.state(playerId);
        Identifier activeId = before.activeCareer().orElse(null);
        if (skill == null) {
            return new SkillResult(Status.UNKNOWN_DEFINITION, 0);
        }
        if (activeId == null || !isCareerInLineage(skill.career(), activeId, definitions)) {
            return new SkillResult(Status.CAREER_NOT_ACTIVE, 0);
        }
        RpgPlayerState.CareerProgress current = before.careers().get(skill.career());
        if (current == null) {
            return new SkillResult(Status.CAREER_NOT_ACTIVE, 0);
        }
        int oldRank = current.learnedSkills().getOrDefault(skillId, 0);
        if (oldRank >= skill.maxRank()) {
            return new SkillResult(Status.MAX_RANK, oldRank);
        }
        for (SkillDefinition.Prerequisite prerequisite : skill.prerequisites()) {
            int learned = learnedRank(before, prerequisite.skill());
            if (learned < prerequisite.rank()) {
                return new SkillResult(Status.PREREQUISITE_MISSING, oldRank);
            }
        }
        int points = boundedIntAdd(current.skillPoints(), -skill.pointCost(), RpgPlayerState.MAX_SKILL_POINTS);
        if (points < 0) {
            return new SkillResult(Status.INSUFFICIENT_SKILL_POINTS, oldRank);
        }
        Map<Identifier, Integer> learned = new HashMap<>(current.learnedSkills());
        learned.put(skillId, oldRank + 1);
        Map<Identifier, RpgPlayerState.CareerProgress> careers = new HashMap<>(before.careers());
        careers.put(skill.career(), new RpgPlayerState.CareerProgress(current.experience(), current.rank(), points, learned));
        List<RpgPlayerState.ProgressionProvenance> provenance = new ArrayList<>(before.provenance());
        provenance.add(new RpgPlayerState.ProgressionProvenance(
                RpgPlayerState.ProgressionProvenance.Kind.SKILL_UNLOCK, skillId, skill.pointCost(), timestamp, source));
        RpgPlayerState next = new RpgPlayerState(
                before.activityXp(), careers, before.activeCareer(), before.activeSkillSlots(), before.cooldowns(),
                boundedProvenance(provenance));
        return state.commit(playerId, next) ? new SkillResult(Status.SUCCESS, oldRank + 1) : new SkillResult(Status.STATE_FULL, oldRank);
    }

    public static SkillResult equipActiveSkill(
            RpgPlayerSavedData state, RpgDefinitionSnapshot definitions, UUID playerId, Identifier skillId, int slot) {
        if (state == null || definitions == null || playerId == null || skillId == null
                || slot < 0 || slot >= RpgPlayerState.MAX_ACTIVE_SKILL_SLOTS) {
            return new SkillResult(Status.INVALID_REQUEST, 0);
        }
        if (!state.isWritable()) {
            return new SkillResult(Status.READ_ONLY, 0);
        }
        SkillDefinition skill = definitions.skill(skillId).orElse(null);
        RpgPlayerState before = state.state(playerId);
        if (skill == null) {
            return new SkillResult(Status.UNKNOWN_DEFINITION, 0);
        }
        if (skill.kind() != SkillDefinition.Kind.ACTIVE) {
            return new SkillResult(Status.NOT_ACTIVE_SKILL, 0);
        }
        Identifier activeCareer = before.activeCareer().orElse(null);
        if (activeCareer == null || !isCareerInLineage(skill.career(), activeCareer, definitions)
                || before.careers().getOrDefault(skill.career(), new RpgPlayerState.CareerProgress(0, 0, 0, Map.of()))
                .learnedSkills().getOrDefault(skillId, 0) < 1) {
            return new SkillResult(Status.SKILL_NOT_LEARNED, 0);
        }
        List<Identifier> slots = new ArrayList<>(before.activeSkillSlots());
        slots.remove(skillId);
        while (slots.size() <= slot) {
            slots.add(null);
        }
        slots.set(slot, skillId);
        slots.removeIf(java.util.Objects::isNull);
        RpgPlayerState next = new RpgPlayerState(
                before.activityXp(), before.careers(), before.activeCareer(), slots, before.cooldowns(), before.provenance());
        return state.commit(playerId, next) ? new SkillResult(Status.SUCCESS, 1) : new SkillResult(Status.STATE_FULL, 0);
    }

    public static SkillResult startCooldown(
            RpgPlayerSavedData state, RpgDefinitionSnapshot definitions, UUID playerId, Identifier skillId, long now) {
        if (state == null || definitions == null || playerId == null || skillId == null || now < 0) {
            return new SkillResult(Status.INVALID_REQUEST, 0);
        }
        if (!state.isWritable()) {
            return new SkillResult(Status.READ_ONLY, 0);
        }
        SkillDefinition skill = definitions.skill(skillId).orElse(null);
        RpgPlayerState before = state.state(playerId);
        if (skill == null) {
            return new SkillResult(Status.UNKNOWN_DEFINITION, 0);
        }
        if (!before.activeSkillSlots().contains(skillId) || skill.kind() != SkillDefinition.Kind.ACTIVE) {
            return new SkillResult(Status.SKILL_NOT_EQUIPPED, 0);
        }
        long readyAt = before.cooldowns().getOrDefault(skillId, 0L);
        if (readyAt > now) {
            return new SkillResult(Status.COOLDOWN, readyAt - now);
        }
        long duration = skill.cooldownTicks().orElseThrow();
        if (Long.MAX_VALUE - now < duration) {
            return new SkillResult(Status.OVERFLOW, 0);
        }
        Map<Identifier, Long> cooldowns = new HashMap<>(before.cooldowns());
        readyAt = now + duration;
        cooldowns.put(skillId, readyAt);
        RpgPlayerState next = new RpgPlayerState(
                before.activityXp(), before.careers(), before.activeCareer(), before.activeSkillSlots(), cooldowns,
                before.provenance());
        return state.commit(playerId, next) ? new SkillResult(Status.SUCCESS, readyAt) : new SkillResult(Status.STATE_FULL, 0);
    }

    private static boolean validRequest(
            RpgPlayerSavedData state, RpgDefinitionSnapshot definitions, UUID playerId, Identifier id,
            long amount, String source, long timestamp) {
        return state != null && definitions != null && playerId != null && id != null && amount > 0
                && amount <= RpgPlayerState.MAX_XP && validSource(source) && timestamp >= 0;
    }

    private static boolean validSource(String source) {
        return source != null && !source.isBlank() && source.length() <= MAX_SOURCE_LENGTH;
    }

    private static long boundedAdd(long current, long amount, long maximum) {
        if (current < 0 || amount < 0 || current > maximum || amount > maximum - current) {
            return -1;
        }
        return current + amount;
    }

    private static int boundedIntAdd(int current, int amount, int maximum) {
        if (current < 0 || current > maximum || amount > 0 && amount > maximum - current
                || amount < 0 && current < -amount) {
            return -1;
        }
        return current + amount;
    }

    private static List<RpgPlayerState.ProgressionProvenance> boundedProvenance(
            List<RpgPlayerState.ProgressionProvenance> entries) {
        return entries.size() <= RpgPlayerState.MAX_PROVENANCE
                ? List.copyOf(entries)
                : List.copyOf(entries.subList(entries.size() - RpgPlayerState.MAX_PROVENANCE, entries.size()));
    }

    static int levelAtXp(List<Long> thresholds, long experience) {
        int level = 0;
        for (long threshold : thresholds) {
            if (experience < threshold) {
                break;
            }
            level++;
        }
        return Math.min(level, RpgPlayerState.MAX_RANK);
    }

    private static int learnedRank(RpgPlayerState state, Identifier skillId) {
        return state.careers().values().stream()
                .mapToInt(progress -> progress.learnedSkills().getOrDefault(skillId, 0))
                .max().orElse(0);
    }

    static boolean isCareerInLineage(Identifier expected, Identifier current, RpgDefinitionSnapshot definitions) {
        Set<Identifier> visited = new HashSet<>();
        ArrayList<Identifier> pending = new ArrayList<>();
        pending.add(current);
        while (!pending.isEmpty()) {
            Identifier id = pending.removeLast();
            if (!visited.add(id)) {
                continue;
            }
            if (id.equals(expected)) {
                return true;
            }
            definitions.career(id).ifPresent(career -> pending.addAll(career.parents()));
        }
        return false;
    }

    private static boolean hasBranchConflict(
            Identifier current, Identifier target, Set<Identifier> learned, RpgDefinitionSnapshot definitions) {
        if (isCareerInLineage(current, target, definitions) || isCareerInLineage(target, current, definitions)) {
            return false;
        }
        for (Identifier existing : learned) {
            if (existing.equals(current) || existing.equals(target)) {
                continue;
            }
            if (isCareerInLineage(existing, current, definitions)
                    && isCareerInLineage(existing, target, definitions)) {
                return true;
            }
        }
        return false;
    }

    public enum Status {
        SUCCESS,
        INVALID_REQUEST,
        READ_ONLY,
        UNKNOWN_DEFINITION,
        OVERFLOW,
        STATE_FULL,
        PREREQUISITE_MISSING,
        ALREADY_LEARNED,
        BRANCH_CONFLICT,
        CAREER_NOT_ACTIVE,
        MAX_RANK,
        INSUFFICIENT_SKILL_POINTS,
        NOT_ACTIVE_SKILL,
        SKILL_NOT_LEARNED,
        SKILL_NOT_EQUIPPED,
        COOLDOWN
    }

    public record ActivityAwardResult(Status status, long activityXp, int careerRank) {
    }

    public record CareerAwardResult(Status status, long careerXp, int careerRank, int skillPoints) {
    }

    public record PromotionResult(Status status) {
    }

    public record SkillResult(Status status, long value) {
    }
}
