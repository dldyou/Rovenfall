package org.dldyou.rovenfall.careers;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import net.minecraft.resources.Identifier;
import net.minecraft.util.StringRepresentable;

public record CareerSkillDefinition(
        String translationKey,
        List<Identifier> prerequisites,
        int maximumRank,
        int pointCostPerRank,
        Scope scope,
        List<CareerSkillEffect> effects,
        Optional<CareerActiveSkillDefinition> active) {
    public static final int MAX_PREREQUISITES = 16;
    public static final int MAX_RANK = 100;
    public static final int MAX_POINT_COST_PER_RANK = 1_000;
    public static final int MAX_EFFECTS = 8;
    public static final Codec<CareerSkillDefinition> CODEC = RecordCodecBuilder
            .<CareerSkillDefinition>create(instance -> instance.group(
                    Codec.string(1, 128).fieldOf("translation_key")
                            .forGetter(CareerSkillDefinition::translationKey),
                    Identifier.CODEC.listOf(0, MAX_PREREQUISITES)
                            .optionalFieldOf("prerequisites", List.of())
                            .forGetter(CareerSkillDefinition::prerequisites),
                    Codec.intRange(1, MAX_RANK).optionalFieldOf("maximum_rank", 1)
                            .forGetter(CareerSkillDefinition::maximumRank),
                    Codec.intRange(1, MAX_POINT_COST_PER_RANK).optionalFieldOf("point_cost", 1)
                            .forGetter(CareerSkillDefinition::pointCostPerRank),
                    Scope.CODEC.optionalFieldOf("scope", Scope.CAREER)
                            .forGetter(CareerSkillDefinition::scope),
                    CareerSkillEffect.CODEC.listOf(0, MAX_EFFECTS).optionalFieldOf("effects", List.of())
                            .forGetter(CareerSkillDefinition::effects),
                    CareerActiveSkillDefinition.CODEC.optionalFieldOf("active")
                            .forGetter(CareerSkillDefinition::active)
            ).apply(instance, CareerSkillDefinition::new))
            .validate(CareerSkillDefinition::validate);

    public CareerSkillDefinition {
        prerequisites = prerequisites == null ? List.of() : List.copyOf(prerequisites);
        effects = effects == null ? List.of() : List.copyOf(effects);
        active = active == null ? Optional.empty() : active;
    }

    public CareerSkillDefinition(
            String translationKey,
            List<Identifier> prerequisites,
            int maximumRank,
            int pointCostPerRank,
            Scope scope,
            List<CareerSkillEffect> effects) {
        this(translationKey, prerequisites, maximumRank, pointCostPerRank, scope, effects, Optional.empty());
    }

    public static DataResult<CareerSkillDefinition> validate(CareerSkillDefinition definition) {
        if (definition == null || definition.translationKey == null || definition.translationKey.isBlank()
                || definition.translationKey.chars().anyMatch(value -> value < 0x20 || value == 0x7f)
                || definition.prerequisites.size() > MAX_PREREQUISITES
                || definition.maximumRank < 1 || definition.maximumRank > MAX_RANK
                || definition.pointCostPerRank < 1
                || definition.pointCostPerRank > MAX_POINT_COST_PER_RANK
                || definition.scope == null
                || definition.effects.size() > MAX_EFFECTS
                || definition.active == null
                || definition.effects.isEmpty() && definition.active.isEmpty()) {
            return DataResult.error(() -> "career skill definition is invalid");
        }
        if (new HashSet<>(definition.prerequisites).size() != definition.prerequisites.size()) {
            return DataResult.error(() -> "career skill contains duplicate prerequisites");
        }
        if (new HashSet<>(definition.effects).size() != definition.effects.size()) {
            return DataResult.error(() -> "career skill contains duplicate effects");
        }
        if (definition.effects.stream().anyMatch(effect ->
                CareerSkillEffect.validate(effect).error().isPresent())) {
            return DataResult.error(() -> "career skill contains an invalid effect");
        }
        if (definition.active.filter(value ->
                CareerActiveSkillDefinition.validate(value).error().isPresent()).isPresent()) {
            return DataResult.error(() -> "career skill contains an invalid active effect");
        }
        return DataResult.success(definition);
    }

    public enum Scope implements StringRepresentable {
        CAREER("career"),
        GLOBAL("global");

        private static final Codec<Scope> CODEC = StringRepresentable.fromEnum(Scope::values);
        private final String id;

        Scope(String id) {
            this.id = id;
        }

        @Override
        public String getSerializedName() {
            return id;
        }
    }
}
