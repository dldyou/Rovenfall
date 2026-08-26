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
        Optional<Integer> cooldownTicks) {
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
                    .forGetter(SkillDefinition::cooldownTicks)
    ).apply(instance, SkillDefinition::new));

    public SkillDefinition {
        prerequisites = List.copyOf(prerequisites);
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
}
