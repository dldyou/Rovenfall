package org.dldyou.rovenfall.rpg;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import net.minecraft.resources.Identifier;
import net.minecraft.util.StringRepresentable;

/** Immutable reset payload persisted by the cross-root payment coordinator. */
public record SkillResetPlan(Mode mode, Identifier target, List<RemovedSkill> removedSkills) {
    public static final Codec<SkillResetPlan> CODEC = RecordCodecBuilder.<SkillResetPlan>create(instance -> instance.group(
            Mode.CODEC.fieldOf("mode").forGetter(SkillResetPlan::mode),
            Identifier.CODEC.fieldOf("target").forGetter(SkillResetPlan::target),
            RemovedSkill.CODEC.listOf(1, RpgPlayerState.MAX_SKILLS).fieldOf("removed_skills")
                    .forGetter(SkillResetPlan::removedSkills)
    ).apply(instance, SkillResetPlan::new)).validate(SkillResetPlan::validate);

    public SkillResetPlan {
        removedSkills = List.copyOf(removedSkills);
    }

    public long refundedPoints() {
        return removedSkills.stream().mapToLong(RemovedSkill::refundedPoints).sum();
    }

    public boolean isValid() {
        return validate(this).error().isEmpty();
    }

    private static DataResult<SkillResetPlan> validate(SkillResetPlan plan) {
        if (plan == null || plan.mode == null || plan.target == null || plan.removedSkills == null
                || plan.removedSkills.isEmpty() || plan.removedSkills.size() > RpgPlayerState.MAX_SKILLS) {
            return DataResult.error(() -> "Skill reset plan is invalid");
        }
        java.util.Set<Identifier> skills = new java.util.HashSet<>();
        for (RemovedSkill removed : plan.removedSkills) {
            if (removed == null || removed.skill == null || removed.career == null
                    || removed.rank < 1 || removed.rank > RpgPlayerState.MAX_SKILL_RANK
                    || removed.refundedPoints < 1 || removed.refundedPoints > RpgPlayerState.MAX_SKILL_POINTS
                    || !skills.add(removed.skill)) {
                return DataResult.error(() -> "Skill reset plan contains an invalid or duplicate skill");
            }
        }
        return DataResult.success(plan);
    }

    public record RemovedSkill(Identifier skill, Identifier career, int rank, int refundedPoints) {
        public static final Codec<RemovedSkill> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Identifier.CODEC.fieldOf("skill").forGetter(RemovedSkill::skill),
                Identifier.CODEC.fieldOf("career").forGetter(RemovedSkill::career),
                Codec.intRange(1, RpgPlayerState.MAX_SKILL_RANK).fieldOf("rank").forGetter(RemovedSkill::rank),
                Codec.intRange(1, RpgPlayerState.MAX_SKILL_POINTS).fieldOf("refunded_points")
                        .forGetter(RemovedSkill::refundedPoints)
        ).apply(instance, RemovedSkill::new));
    }

    public enum Mode implements StringRepresentable {
        FULL("full"), BRANCH("branch");

        public static final Codec<Mode> CODEC = StringRepresentable.fromEnum(Mode::values);
        private final String id;

        Mode(String id) {
            this.id = id;
        }

        @Override
        public String getSerializedName() {
            return id;
        }
    }
}
