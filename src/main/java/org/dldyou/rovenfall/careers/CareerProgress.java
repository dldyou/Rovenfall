package org.dldyou.rovenfall.careers;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.resources.Identifier;
import org.dldyou.rovenfall.activities.ActivityProgress;

public record CareerProgress(
        long experience,
        int bonusSkillPoints,
        int spentSkillPoints,
        Map<Identifier, Integer> skillRanks) {
    public static final int MAX_UNLOCKED_SKILLS = CareerDefinition.MAX_SKILLS;
    public static final int MAX_SKILL_POINTS = 100_000_000;
    private static final Codec<Long> EXPERIENCE_CODEC = Codec.LONG.validate(value ->
            value < 0 || value > ActivityProgress.MAX_EXPERIENCE
                    ? DataResult.error(() -> "career experience is outside the supported range")
                    : DataResult.success(value));
    private static final Codec<Map<Identifier, Integer>> SKILL_RANKS_CODEC = Codec.unboundedMap(
            Identifier.CODEC, Codec.intRange(1, CareerSkillDefinition.MAX_RANK)).validate(values ->
                    values.size() > MAX_UNLOCKED_SKILLS
                            ? DataResult.error(() -> "career unlocked skill count exceeds " + MAX_UNLOCKED_SKILLS)
                            : DataResult.success(values));
    public static final Codec<CareerProgress> CODEC = RecordCodecBuilder
            .<CareerProgress>create(instance -> instance.group(
                    EXPERIENCE_CODEC.optionalFieldOf("experience", 0L).forGetter(CareerProgress::experience),
                    Codec.intRange(0, MAX_SKILL_POINTS).optionalFieldOf("bonus_skill_points", 0)
                            .forGetter(CareerProgress::bonusSkillPoints),
                    Codec.intRange(0, MAX_SKILL_POINTS).optionalFieldOf("spent_skill_points", 0)
                            .forGetter(CareerProgress::spentSkillPoints),
                    SKILL_RANKS_CODEC.optionalFieldOf("skill_ranks", Map.of())
                            .forGetter(CareerProgress::skillRanks)
            ).apply(instance, CareerProgress::new))
            .validate(CareerProgress::validate);

    public CareerProgress {
        skillRanks = skillRanks == null ? Map.of() : Map.copyOf(skillRanks);
    }

    public static CareerProgress empty() {
        return new CareerProgress(0, 0, 0, Map.of());
    }

    public static CareerProgress promoted(int promotionSkillPoints) {
        if (promotionSkillPoints < 0 || promotionSkillPoints > MAX_SKILL_POINTS) {
            throw new IllegalArgumentException("Promotion skill points are outside the supported range");
        }
        return new CareerProgress(0, promotionSkillPoints, 0, Map.of());
    }

    public CareerProgress award(long amount) {
        if (amount < 1) {
            throw new IllegalArgumentException("Career experience award must be positive");
        }
        long updated = Math.addExact(experience, amount);
        if (updated > ActivityProgress.MAX_EXPERIENCE) {
            throw new ArithmeticException("Career experience exceeds the supported maximum");
        }
        return new CareerProgress(updated, bonusSkillPoints, spentSkillPoints, skillRanks);
    }

    public int skillRank(Identifier skillId) {
        return skillRanks.getOrDefault(skillId, 0);
    }

    public int earnedSkillPoints(CareerDefinition definition) {
        if (definition == null) {
            throw new IllegalArgumentException("Career definition is required");
        }
        return Math.addExact(definition.level(experience), bonusSkillPoints);
    }

    public int availableSkillPoints(CareerDefinition definition) {
        return Math.max(0, earnedSkillPoints(definition) - spentSkillPoints);
    }

    public CareerProgress unlock(Identifier skillId, int pointCost) {
        if (skillId == null || pointCost < 1) {
            throw new IllegalArgumentException("Career skill unlock is invalid");
        }
        if (!skillRanks.containsKey(skillId) && skillRanks.size() >= MAX_UNLOCKED_SKILLS) {
            throw new IllegalStateException("Career unlocked skill capacity is exhausted");
        }
        int rank = Math.addExact(skillRank(skillId), 1);
        if (rank > CareerSkillDefinition.MAX_RANK) {
            throw new IllegalStateException("Career skill rank exceeds the supported maximum");
        }
        int spent = Math.addExact(spentSkillPoints, pointCost);
        if (spent > MAX_SKILL_POINTS) {
            throw new IllegalStateException("Career spent skill points exceed the supported maximum");
        }
        Map<Identifier, Integer> updated = new HashMap<>(skillRanks);
        updated.put(skillId, rank);
        return new CareerProgress(experience, bonusSkillPoints, spent, updated);
    }

    public CareerProgress resetSkills() {
        return skillRanks.isEmpty() && spentSkillPoints == 0
                ? this
                : new CareerProgress(experience, bonusSkillPoints, 0, Map.of());
    }

    private static DataResult<CareerProgress> validate(CareerProgress progress) {
        if (progress == null || progress.experience < 0 || progress.experience > ActivityProgress.MAX_EXPERIENCE
                || progress.bonusSkillPoints < 0 || progress.bonusSkillPoints > MAX_SKILL_POINTS
                || progress.spentSkillPoints < 0 || progress.spentSkillPoints > MAX_SKILL_POINTS
                || progress.skillRanks.size() > MAX_UNLOCKED_SKILLS
                || progress.skillRanks.isEmpty() && progress.spentSkillPoints != 0
                || !progress.skillRanks.isEmpty() && progress.spentSkillPoints < 1) {
            return DataResult.error(() -> "career progress is invalid");
        }
        return DataResult.success(progress);
    }
}
