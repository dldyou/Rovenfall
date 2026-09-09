package org.dldyou.rovenfall.quest;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
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
        List<Objective> objectives,
        Rewards rewards,
        Optional<Contract> contract) {
    public static final int MAX_VERSION = 1_000_000;
    public static final int MAX_PREREQUISITES = 32;
    public static final int MAX_OBJECTIVES = 32;
    public static final int MAX_REQUIRED_COUNT = 1_000_000_000;
    public static final long MAX_CURRENCY_REWARD = 1_000_000_000L;
    public static final long MAX_ACTIVITY_XP_REWARD = 1_000_000_000L;

    public static final Codec<QuestDefinition> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.string(1, 160).fieldOf("translation_key").forGetter(QuestDefinition::translationKey),
            Codec.string(1, 160).fieldOf("description_translation_key")
                    .forGetter(QuestDefinition::descriptionTranslationKey),
            Codec.intRange(1, MAX_VERSION).fieldOf("version").forGetter(QuestDefinition::version),
            Identifier.CODEC.listOf(0, MAX_PREREQUISITES).optionalFieldOf("prerequisites", List.of())
                    .forGetter(QuestDefinition::prerequisites),
            Objective.CODEC.listOf(1, MAX_OBJECTIVES).fieldOf("objectives")
                    .forGetter(QuestDefinition::objectives),
            Rewards.CODEC.optionalFieldOf("rewards", Rewards.NONE).forGetter(QuestDefinition::rewards),
            Contract.CODEC.optionalFieldOf("contract").forGetter(QuestDefinition::contract)
    ).apply(instance, QuestDefinition::new));

    public QuestDefinition {
        prerequisites = List.copyOf(prerequisites);
        objectives = List.copyOf(objectives);
        rewards = rewards == null ? Rewards.NONE : rewards;
        contract = contract == null ? Optional.empty() : contract;
    }

    public QuestDefinition(
            String translationKey,
            String descriptionTranslationKey,
            int version,
            List<Identifier> prerequisites,
            List<Objective> objectives,
            Rewards rewards) {
        this(translationKey, descriptionTranslationKey, version, prerequisites, objectives, rewards, Optional.empty());
    }

    public QuestDefinition(
            String translationKey,
            String descriptionTranslationKey,
            int version,
            List<Identifier> prerequisites,
            List<Objective> objectives) {
        this(translationKey, descriptionTranslationKey, version, prerequisites, objectives, Rewards.NONE,
                Optional.empty());
    }

    public record Contract(Cadence cadence) {
        public static final Codec<Contract> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Cadence.CODEC.fieldOf("cadence").forGetter(Contract::cadence)
        ).apply(instance, Contract::new));
    }

    public enum Cadence implements StringRepresentable {
        DAILY("daily", 2),
        WEEKLY("weekly", 1);

        public static final Codec<Cadence> CODEC = StringRepresentable.fromEnum(Cadence::values);
        private final String id;
        private final int slots;

        Cadence(String id, int slots) {
            this.id = id;
            this.slots = slots;
        }

        public int slots() {
            return slots;
        }

        @Override
        public String getSerializedName() {
            return id;
        }
    }

    public record Rewards(long currency, Optional<ActivityXpReward> activityXp) {
        private static final Codec<Long> CURRENCY_CODEC = Codec.LONG.validate(value ->
                value >= 0 && value <= MAX_CURRENCY_REWARD ? DataResult.success(value)
                        : DataResult.error(() -> "Quest currency reward exceeds its bound"));
        public static final Rewards NONE = new Rewards(0, Optional.empty());
        public static final Codec<Rewards> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                CURRENCY_CODEC.optionalFieldOf("currency", 0L)
                        .forGetter(Rewards::currency),
                ActivityXpReward.CODEC.optionalFieldOf("activity_xp").forGetter(Rewards::activityXp)
        ).apply(instance, Rewards::new));

        public Rewards {
            activityXp = activityXp == null ? Optional.empty() : activityXp;
        }

        public boolean isValid() {
            return currency >= 0 && currency <= MAX_CURRENCY_REWARD
                    && activityXp.map(ActivityXpReward::isValid).orElse(true);
        }
    }

    public record ActivityXpReward(Identifier activity, long amount) {
        private static final Codec<Long> AMOUNT_CODEC = Codec.LONG.validate(value ->
                value >= 1 && value <= MAX_ACTIVITY_XP_REWARD ? DataResult.success(value)
                        : DataResult.error(() -> "Quest activity XP reward exceeds its bound"));
        public static final Codec<ActivityXpReward> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Identifier.CODEC.fieldOf("activity").forGetter(ActivityXpReward::activity),
                AMOUNT_CODEC.fieldOf("amount")
                        .forGetter(ActivityXpReward::amount)
        ).apply(instance, ActivityXpReward::new));

        public boolean isValid() {
            return activity != null && amount >= 1 && amount <= MAX_ACTIVITY_XP_REWARD;
        }
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
