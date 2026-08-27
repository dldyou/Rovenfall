package org.dldyou.rovenfall.rpg;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import java.util.Optional;
import net.minecraft.resources.Identifier;
import net.minecraft.util.StringRepresentable;

public record SkillDefinition(
        String translationKey,
        Identifier career,
        Kind kind,
        int maxRank,
        int pointCost,
        List<Prerequisite> prerequisites,
        Optional<Integer> cooldownTicks,
        Optional<PassiveEffect> passiveEffect,
        Optional<ActiveEffect> activeEffect) {
    public static final int MAX_RANK = 100;
    public static final int MAX_POINT_COST = 1_000_000;
    public static final int MAX_PREREQUISITES = 32;
    public static final int MAX_COOLDOWN_TICKS = 20 * 60 * 60 * 24;

    public static final Codec<SkillDefinition> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.string(1, 160).fieldOf("translation_key").forGetter(SkillDefinition::translationKey),
            Identifier.CODEC.fieldOf("career").forGetter(SkillDefinition::career),
            Kind.CODEC.fieldOf("kind").forGetter(SkillDefinition::kind),
            Codec.intRange(1, MAX_RANK).fieldOf("max_rank").forGetter(SkillDefinition::maxRank),
            Codec.intRange(1, MAX_POINT_COST).fieldOf("point_cost").forGetter(SkillDefinition::pointCost),
            Prerequisite.CODEC.listOf(0, MAX_PREREQUISITES).optionalFieldOf("prerequisites", List.of())
                    .forGetter(SkillDefinition::prerequisites),
            Codec.intRange(1, MAX_COOLDOWN_TICKS).optionalFieldOf("cooldown_ticks")
                    .forGetter(SkillDefinition::cooldownTicks),
            PassiveEffect.CODEC.optionalFieldOf("passive_effect").forGetter(SkillDefinition::passiveEffect),
            ActiveEffect.CODEC.optionalFieldOf("active_effect").forGetter(SkillDefinition::activeEffect)
    ).apply(instance, SkillDefinition::new));

    public SkillDefinition(
            String translationKey,
            Identifier career,
            Kind kind,
            int maxRank,
            int pointCost,
            List<Prerequisite> prerequisites,
            Optional<Integer> cooldownTicks,
            Optional<PassiveEffect> passiveEffect) {
        this(translationKey, career, kind, maxRank, pointCost, prerequisites,
                cooldownTicks, passiveEffect, Optional.empty());
    }

    public SkillDefinition(
            String translationKey,
            Identifier career,
            Kind kind,
            int maxRank,
            int pointCost,
            List<Prerequisite> prerequisites,
            Optional<Integer> cooldownTicks) {
        this(translationKey, career, kind, maxRank, pointCost, prerequisites,
                cooldownTicks, Optional.empty(), Optional.empty());
    }

    public SkillDefinition {
        prerequisites = List.copyOf(prerequisites);
        cooldownTicks = cooldownTicks == null ? Optional.empty() : cooldownTicks;
        passiveEffect = passiveEffect == null ? Optional.empty() : passiveEffect;
        activeEffect = activeEffect == null ? Optional.empty() : activeEffect;
    }

    public enum Kind implements StringRepresentable {
        PASSIVE("passive"),
        ACTIVE("active");

        public static final Codec<Kind> CODEC = StringRepresentable.fromEnum(Kind::values);
        private final String id;

        Kind(String id) {
            this.id = id;
        }

        @Override
        public String getSerializedName() {
            return id;
        }
    }

    public record Prerequisite(Identifier skill, int rank) {
        public static final Codec<Prerequisite> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Identifier.CODEC.fieldOf("skill").forGetter(Prerequisite::skill),
                Codec.intRange(1, MAX_RANK).fieldOf("rank").forGetter(Prerequisite::rank)
        ).apply(instance, Prerequisite::new));
    }

    public record PassiveEffect(EffectType type, int basisPointsPerRank) {
        public static final int MAX_BASIS_POINTS_PER_RANK = 5_000;
        public static final Codec<PassiveEffect> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                EffectType.CODEC.fieldOf("type").forGetter(PassiveEffect::type),
                Codec.intRange(1, MAX_BASIS_POINTS_PER_RANK).fieldOf("basis_points_per_rank")
                        .forGetter(PassiveEffect::basisPointsPerRank)
        ).apply(instance, PassiveEffect::new));
    }

    public record ActiveEffect(
            EffectType type,
            TargetType target,
            int basisPointsPerRank,
            int durationTicks,
            double range) {
        public static final int MAX_BASIS_POINTS_PER_RANK = 10_000;
        public static final int MAX_DURATION_TICKS = 20 * 60 * 10;
        public static final double MAX_RANGE = 64.0;
        public static final Codec<ActiveEffect> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                EffectType.CODEC.fieldOf("type").forGetter(ActiveEffect::type),
                TargetType.CODEC.fieldOf("target").forGetter(ActiveEffect::target),
                Codec.intRange(1, MAX_BASIS_POINTS_PER_RANK).fieldOf("basis_points_per_rank")
                        .forGetter(ActiveEffect::basisPointsPerRank),
                Codec.intRange(1, MAX_DURATION_TICKS).fieldOf("duration_ticks")
                        .forGetter(ActiveEffect::durationTicks),
                Codec.doubleRange(0.0, MAX_RANGE).optionalFieldOf("range", 0.0)
                        .forGetter(ActiveEffect::range)
        ).apply(instance, ActiveEffect::new));
    }

    public enum TargetType implements StringRepresentable {
        SELF("self"), LIVING_ENTITY("living_entity");

        public static final Codec<TargetType> CODEC = StringRepresentable.fromEnum(TargetType::values);
        private final String id;

        TargetType(String id) {
            this.id = id;
        }

        @Override
        public String getSerializedName() {
            return id;
        }
    }

    public enum EffectType implements StringRepresentable {
        DAMAGE_DEALT("damage_dealt"),
        DAMAGE_TAKEN_REDUCTION("damage_taken_reduction");

        public static final Codec<EffectType> CODEC = StringRepresentable.fromEnum(EffectType::values);
        private final String id;

        EffectType(String id) {
            this.id = id;
        }

        @Override
        public String getSerializedName() {
            return id;
        }
    }
}
