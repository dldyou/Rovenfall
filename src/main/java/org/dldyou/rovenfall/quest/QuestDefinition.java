package org.dldyou.rovenfall.quest;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import java.util.Optional;
import net.minecraft.resources.Identifier;
import net.minecraft.util.StringRepresentable;

public record QuestDefinition(
        String translationKey,
        String descriptionTranslationKey,
        int version,
        List<Identifier> prerequisites,
        List<Objective> objectives) {
    public static final int MAX_VERSION = 1_000_000;
    public static final int MAX_PREREQUISITES = 32;
    public static final int MAX_OBJECTIVES = 32;
    public static final int MAX_REQUIRED_COUNT = 1_000_000_000;

    public static final Codec<QuestDefinition> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.string(1, 160).fieldOf("translation_key").forGetter(QuestDefinition::translationKey),
            Codec.string(1, 160).fieldOf("description_translation_key")
                    .forGetter(QuestDefinition::descriptionTranslationKey),
            Codec.intRange(1, MAX_VERSION).fieldOf("version").forGetter(QuestDefinition::version),
            Identifier.CODEC.listOf(0, MAX_PREREQUISITES).optionalFieldOf("prerequisites", List.of())
                    .forGetter(QuestDefinition::prerequisites),
            Objective.CODEC.listOf(1, MAX_OBJECTIVES).fieldOf("objectives")
                    .forGetter(QuestDefinition::objectives)
    ).apply(instance, QuestDefinition::new));

    public QuestDefinition {
        prerequisites = List.copyOf(prerequisites);
        objectives = List.copyOf(objectives);
    }

    public record Objective(Identifier id, Kind kind, Optional<Identifier> target, int requiredCount) {
        public static final Codec<Objective> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Identifier.CODEC.fieldOf("id").forGetter(Objective::id),
                Kind.CODEC.fieldOf("kind").forGetter(Objective::kind),
                Identifier.CODEC.optionalFieldOf("target").forGetter(Objective::target),
                Codec.intRange(1, MAX_REQUIRED_COUNT).fieldOf("required_count").forGetter(Objective::requiredCount)
        ).apply(instance, Objective::new));

        public Objective {
            target = target == null ? Optional.empty() : target;
        }
    }

    public enum Kind implements StringRepresentable {
        ACTIVITY("activity"),
        SHOP_TRADE("shop_trade"),
        CLAIM_PURCHASE("claim_purchase"),
        BOSS_DEFEAT("boss_defeat");

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
}
