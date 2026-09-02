package org.dldyou.rovenfall.careers;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.resources.Identifier;

public record PlayerCareerState(
        Optional<Identifier> activeCareer,
        Map<Identifier, CareerProgress> progressByCareer,
        ActiveSkillState activeSkills) {
    public static final int MAX_LEARNED_CAREERS = CareerCatalog.MAX_DEFINITIONS;
    private static final Codec<Map<Identifier, CareerProgress>> PROGRESS_CODEC = Codec.unboundedMap(
            Identifier.CODEC, CareerProgress.CODEC).validate(values ->
                    values.size() > MAX_LEARNED_CAREERS
                            ? DataResult.error(() -> "learned career count exceeds " + MAX_LEARNED_CAREERS)
                            : DataResult.success(values));
    public static final Codec<PlayerCareerState> CODEC = RecordCodecBuilder
            .<PlayerCareerState>create(instance -> instance.group(
                    Identifier.CODEC.optionalFieldOf("active").forGetter(PlayerCareerState::activeCareer),
                    PROGRESS_CODEC.optionalFieldOf("progress", Map.of()).forGetter(PlayerCareerState::progressByCareer),
                    ActiveSkillState.CODEC.optionalFieldOf("active_skills", ActiveSkillState.empty())
                            .forGetter(PlayerCareerState::activeSkills)
            ).apply(instance, PlayerCareerState::new))
            .validate(PlayerCareerState::validate);

    public PlayerCareerState {
        activeCareer = activeCareer == null ? Optional.empty() : activeCareer;
        progressByCareer = progressByCareer == null ? Map.of() : Map.copyOf(progressByCareer);
        activeSkills = activeSkills == null ? ActiveSkillState.empty() : activeSkills;
    }

    public PlayerCareerState(Optional<Identifier> activeCareer, Map<Identifier, CareerProgress> progressByCareer) {
        this(activeCareer, progressByCareer, ActiveSkillState.empty());
    }

    public static PlayerCareerState empty() {
        return new PlayerCareerState(Optional.empty(), Map.of(), ActiveSkillState.empty());
    }

    private static DataResult<PlayerCareerState> validate(PlayerCareerState state) {
        if (state == null) {
            return DataResult.error(() -> "player career state is missing");
        }
        if (state.activeCareer.isPresent()
                && !state.progressByCareer.containsKey(state.activeCareer.orElseThrow())) {
            return DataResult.error(() -> "active career is not present in learned progress");
        }
        Set<Identifier> unlockedSkills = state.progressByCareer.values().stream()
                .flatMap(progress -> progress.skillRanks().keySet().stream())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (!unlockedSkills.containsAll(state.activeSkills.equippedSkills())
                || !unlockedSkills.containsAll(state.activeSkills.cooldownReadyAtEpochMillis().keySet())) {
            return DataResult.error(() -> "active skill state references a skill that is not unlocked");
        }
        return DataResult.success(state);
    }

    public Set<Identifier> learnedCareers() {
        return progressByCareer.keySet();
    }

    public long experience(Identifier careerId) {
        CareerProgress progress = progressByCareer.get(careerId);
        return progress == null ? 0 : progress.experience();
    }

    public CareerProgress progress(Identifier careerId) {
        return progressByCareer.getOrDefault(careerId, CareerProgress.empty());
    }

    public PlayerCareerState promote(Identifier target, Set<Identifier> resetCareers) {
        return promote(target, resetCareers, 0);
    }

    public PlayerCareerState promote(
            Identifier target,
            Set<Identifier> resetCareers,
            int promotionSkillPoints) {
        if (target == null || resetCareers == null || resetCareers.contains(target)) {
            throw new IllegalArgumentException("Career promotion mutation is invalid");
        }
        Map<Identifier, CareerProgress> updated = new HashMap<>(progressByCareer);
        Set<Identifier> removedSkills = resetCareers.stream()
                .map(updated::get)
                .filter(java.util.Objects::nonNull)
                .flatMap(progress -> progress.skillRanks().keySet().stream())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        resetCareers.forEach(updated::remove);
        if (updated.size() >= MAX_LEARNED_CAREERS && !updated.containsKey(target)) {
            throw new IllegalStateException("Learned career capacity is exhausted");
        }
        updated.putIfAbsent(target, CareerProgress.promoted(promotionSkillPoints));
        return new PlayerCareerState(Optional.of(target), updated, activeSkills.removeSkills(removedSkills));
    }

    public PlayerCareerState awardActive(Identifier expectedCareer, long amount) {
        if (expectedCareer == null || amount < 1
                || !activeCareer.equals(Optional.of(expectedCareer))) {
            throw new IllegalArgumentException("Active career experience award is invalid");
        }
        CareerProgress current = progressByCareer.get(expectedCareer);
        if (current == null) {
            throw new IllegalStateException("Active career progress is missing");
        }
        Map<Identifier, CareerProgress> updated = new HashMap<>(progressByCareer);
        updated.put(expectedCareer, current.award(amount));
        return new PlayerCareerState(activeCareer, updated, activeSkills);
    }

    public PlayerCareerState unlockSkill(Identifier careerId, Identifier skillId, int pointCost) {
        CareerProgress current = progressByCareer.get(careerId);
        if (current == null) {
            throw new IllegalArgumentException("Career is not learned");
        }
        Map<Identifier, CareerProgress> updated = new HashMap<>(progressByCareer);
        updated.put(careerId, current.unlock(skillId, pointCost));
        return new PlayerCareerState(activeCareer, updated, activeSkills);
    }

    public PlayerCareerState resetSkills(Identifier careerId) {
        CareerProgress current = progressByCareer.get(careerId);
        if (current == null) {
            throw new IllegalArgumentException("Career is not learned");
        }
        Map<Identifier, CareerProgress> updated = new HashMap<>(progressByCareer);
        updated.put(careerId, current.resetSkills());
        return new PlayerCareerState(
                activeCareer, updated, activeSkills.removeSkills(current.skillRanks().keySet()));
    }

    public PlayerCareerState equipActiveSkill(int slot, Identifier skillId) {
        if (progressByCareer.values().stream().noneMatch(progress -> progress.skillRank(skillId) > 0)) {
            throw new IllegalArgumentException("Active skill is not unlocked");
        }
        return new PlayerCareerState(activeCareer, progressByCareer, activeSkills.equip(slot, skillId));
    }

    public PlayerCareerState clearActiveSkillSlot(int slot) {
        return new PlayerCareerState(activeCareer, progressByCareer, activeSkills.clear(slot));
    }

    public PlayerCareerState recordActiveSkillUse(
            Identifier skillId,
            long usedAtEpochMillis,
            long readyAtEpochMillis) {
        return new PlayerCareerState(
                activeCareer,
                progressByCareer,
                activeSkills.recordUse(skillId, usedAtEpochMillis, readyAtEpochMillis));
    }
}
