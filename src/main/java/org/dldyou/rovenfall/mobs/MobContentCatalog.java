package org.dldyou.rovenfall.mobs;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.loot.LootTable;

public record MobContentCatalog(
        List<MobDefinition> mobs,
        List<MutationDefinition> mutations,
        List<ArenaPolicy> arenas,
        List<ContributionRule> contributionRules,
        List<LootDefinition> loot,
        List<BossDefinition> bosses) {
    public static final int MAX_ENTRIES_PER_KIND = 1_024;
    public static final int MAX_REFERENCES = 64;
    public static final int MAX_PHASES = 32;
    public static final int MAX_PATTERNS = 64;

    public static final Codec<MobContentCatalog> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            MobDefinition.CODEC.listOf(0, MAX_ENTRIES_PER_KIND).optionalFieldOf("mobs", List.of())
                    .forGetter(MobContentCatalog::mobs),
            MutationDefinition.CODEC.listOf(0, MAX_ENTRIES_PER_KIND).optionalFieldOf("mutations", List.of())
                    .forGetter(MobContentCatalog::mutations),
            ArenaPolicy.CODEC.listOf(0, MAX_ENTRIES_PER_KIND).optionalFieldOf("arenas", List.of())
                    .forGetter(MobContentCatalog::arenas),
            ContributionRule.CODEC.listOf(0, MAX_ENTRIES_PER_KIND)
                    .optionalFieldOf("contribution_rules", List.of()).forGetter(MobContentCatalog::contributionRules),
            LootDefinition.CODEC.listOf(0, MAX_ENTRIES_PER_KIND).optionalFieldOf("loot", List.of())
                    .forGetter(MobContentCatalog::loot),
            BossDefinition.CODEC.listOf(0, MAX_ENTRIES_PER_KIND).optionalFieldOf("bosses", List.of())
                    .forGetter(MobContentCatalog::bosses)
    ).apply(instance, MobContentCatalog::new));

    public MobContentCatalog {
        mobs = List.copyOf(mobs);
        mutations = List.copyOf(mutations);
        arenas = List.copyOf(arenas);
        contributionRules = List.copyOf(contributionRules);
        loot = List.copyOf(loot);
        bosses = List.copyOf(bosses);
    }

    public record MobDefinition(
            Identifier id,
            String translationKey,
            Identifier entityType,
            double maxHealth,
            double attackDamage,
            double movementSpeed,
            List<Identifier> behaviorModifiers,
            Identifier loot,
            Optional<SpawnCondition> spawn) {
        public static final Codec<MobDefinition> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Identifier.CODEC.fieldOf("id").forGetter(MobDefinition::id),
                Codec.string(1, 160).fieldOf("translation_key").forGetter(MobDefinition::translationKey),
                Identifier.CODEC.fieldOf("entity_type").forGetter(MobDefinition::entityType),
                Codec.DOUBLE.fieldOf("max_health").forGetter(MobDefinition::maxHealth),
                Codec.DOUBLE.fieldOf("attack_damage").forGetter(MobDefinition::attackDamage),
                Codec.DOUBLE.fieldOf("movement_speed").forGetter(MobDefinition::movementSpeed),
                Identifier.CODEC.listOf(0, MAX_REFERENCES).optionalFieldOf("behavior_modifiers", List.of())
                        .forGetter(MobDefinition::behaviorModifiers),
                Identifier.CODEC.fieldOf("loot").forGetter(MobDefinition::loot),
                SpawnCondition.CODEC.optionalFieldOf("spawn").forGetter(MobDefinition::spawn)
        ).apply(instance, MobDefinition::new));

        public MobDefinition {
            behaviorModifiers = List.copyOf(behaviorModifiers);
            spawn = spawn == null ? Optional.empty() : spawn;
        }
    }

    public record MutationDefinition(
            Identifier id,
            String translationKey,
            List<Identifier> eligibleEntityTypes,
            List<AttributeModifier> attributes,
            List<Identifier> behaviorModifiers,
            String markerTranslationKey,
            SpawnCondition spawn,
            int rewardMultiplierPercent,
            Optional<Identifier> bonusLoot) {
        public static final Codec<MutationDefinition> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Identifier.CODEC.fieldOf("id").forGetter(MutationDefinition::id),
                Codec.string(1, 160).fieldOf("translation_key").forGetter(MutationDefinition::translationKey),
                Identifier.CODEC.listOf(1, MAX_REFERENCES).fieldOf("eligible_entity_types")
                        .forGetter(MutationDefinition::eligibleEntityTypes),
                AttributeModifier.CODEC.listOf(0, MAX_REFERENCES).optionalFieldOf("attributes", List.of())
                        .forGetter(MutationDefinition::attributes),
                Identifier.CODEC.listOf(0, MAX_REFERENCES).optionalFieldOf("behavior_modifiers", List.of())
                        .forGetter(MutationDefinition::behaviorModifiers),
                Codec.string(1, 160).fieldOf("marker_translation_key")
                        .forGetter(MutationDefinition::markerTranslationKey),
                SpawnCondition.CODEC.fieldOf("spawn").forGetter(MutationDefinition::spawn),
                Codec.INT.fieldOf("reward_multiplier_percent").forGetter(MutationDefinition::rewardMultiplierPercent),
                Identifier.CODEC.optionalFieldOf("bonus_loot").forGetter(MutationDefinition::bonusLoot)
        ).apply(instance, MutationDefinition::new));

        public MutationDefinition {
            eligibleEntityTypes = List.copyOf(eligibleEntityTypes);
            attributes = List.copyOf(attributes);
            behaviorModifiers = List.copyOf(behaviorModifiers);
        }
    }

    public record AttributeModifier(Identifier attribute, Operation operation, double amount) {
        public static final Codec<AttributeModifier> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Identifier.CODEC.fieldOf("attribute").forGetter(AttributeModifier::attribute),
                Operation.CODEC.fieldOf("operation").forGetter(AttributeModifier::operation),
                Codec.DOUBLE.fieldOf("amount").forGetter(AttributeModifier::amount)
        ).apply(instance, AttributeModifier::new));
    }

    public enum Operation {
        ADD,
        MULTIPLY_BASE,
        MULTIPLY_TOTAL;

        public static final Codec<Operation> CODEC = Codec.STRING.comapFlatMap(value -> {
            try {
                return DataResult.success(valueOf(value.toUpperCase(java.util.Locale.ROOT)));
            } catch (IllegalArgumentException exception) {
                return DataResult.error(() -> "unknown attribute operation: " + value);
            }
        }, value -> value.name().toLowerCase(java.util.Locale.ROOT));
    }

    public record SpawnCondition(ResourceKey<Level> dimension, int chancePerMillion, int minimumY, int maximumY) {
        public static final Codec<SpawnCondition> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                ResourceKey.codec(Registries.DIMENSION).fieldOf("dimension").forGetter(SpawnCondition::dimension),
                Codec.INT.fieldOf("chance_per_million").forGetter(SpawnCondition::chancePerMillion),
                Codec.INT.fieldOf("minimum_y").forGetter(SpawnCondition::minimumY),
                Codec.INT.fieldOf("maximum_y").forGetter(SpawnCondition::maximumY)
        ).apply(instance, SpawnCondition::new));
    }

    public record ArenaPolicy(
            Identifier id,
            ResourceKey<Level> dimension,
            BlockPos center,
            int protectionRadius,
            int leashRadius,
            int resetTimeoutTicks) {
        public static final Codec<ArenaPolicy> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Identifier.CODEC.fieldOf("id").forGetter(ArenaPolicy::id),
                ResourceKey.codec(Registries.DIMENSION).fieldOf("dimension").forGetter(ArenaPolicy::dimension),
                BlockPos.CODEC.fieldOf("center").forGetter(ArenaPolicy::center),
                Codec.INT.fieldOf("protection_radius").forGetter(ArenaPolicy::protectionRadius),
                Codec.INT.fieldOf("leash_radius").forGetter(ArenaPolicy::leashRadius),
                Codec.INT.fieldOf("reset_timeout_ticks").forGetter(ArenaPolicy::resetTimeoutTicks)
        ).apply(instance, ArenaPolicy::new));
    }

    public record ContributionRule(
            Identifier id,
            long minimumPoints,
            int minimumShareBasisPoints,
            int maximumContributors) {
        public static final Codec<ContributionRule> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Identifier.CODEC.fieldOf("id").forGetter(ContributionRule::id),
                Codec.LONG.fieldOf("minimum_points").forGetter(ContributionRule::minimumPoints),
                Codec.INT.fieldOf("minimum_share_basis_points").forGetter(ContributionRule::minimumShareBasisPoints),
                Codec.INT.fieldOf("maximum_contributors").forGetter(ContributionRule::maximumContributors)
        ).apply(instance, ContributionRule::new));
    }

    public record LootDefinition(
            Identifier id,
            ResourceKey<LootTable> lootTable,
            int rolls,
            long currency,
            long experience) {
        public static final Codec<LootDefinition> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Identifier.CODEC.fieldOf("id").forGetter(LootDefinition::id),
                ResourceKey.codec(Registries.LOOT_TABLE).fieldOf("loot_table").forGetter(LootDefinition::lootTable),
                Codec.INT.fieldOf("rolls").forGetter(LootDefinition::rolls),
                Codec.LONG.optionalFieldOf("currency", 0L).forGetter(LootDefinition::currency),
                Codec.LONG.optionalFieldOf("experience", 0L).forGetter(LootDefinition::experience)
        ).apply(instance, LootDefinition::new));
    }

    public record BossDefinition(
            Identifier id,
            String translationKey,
            Identifier mob,
            Identifier arena,
            Identifier contributionRule,
            Identifier loot,
            int rewardCooldownTicks,
            List<Phase> phases) {
        public static final Codec<BossDefinition> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Identifier.CODEC.fieldOf("id").forGetter(BossDefinition::id),
                Codec.string(1, 160).fieldOf("translation_key").forGetter(BossDefinition::translationKey),
                Identifier.CODEC.fieldOf("mob").forGetter(BossDefinition::mob),
                Identifier.CODEC.fieldOf("arena").forGetter(BossDefinition::arena),
                Identifier.CODEC.fieldOf("contribution_rule").forGetter(BossDefinition::contributionRule),
                Identifier.CODEC.fieldOf("loot").forGetter(BossDefinition::loot),
                Codec.INT.fieldOf("reward_cooldown_ticks").forGetter(BossDefinition::rewardCooldownTicks),
                Phase.CODEC.listOf(1, MAX_PHASES).fieldOf("phases").forGetter(BossDefinition::phases)
        ).apply(instance, BossDefinition::new));

        public BossDefinition {
            phases = List.copyOf(phases);
        }
    }

    public record Phase(Identifier id, String translationKey, int startHealthPercent, List<PatternDefinition> patterns) {
        public static final Codec<Phase> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Identifier.CODEC.fieldOf("id").forGetter(Phase::id),
                Codec.string(1, 160).fieldOf("translation_key").forGetter(Phase::translationKey),
                Codec.INT.fieldOf("start_health_percent").forGetter(Phase::startHealthPercent),
                PatternDefinition.CODEC.listOf(1, MAX_PATTERNS).fieldOf("patterns").forGetter(Phase::patterns)
        ).apply(instance, Phase::new));

        public Phase {
            patterns = List.copyOf(patterns);
        }
    }

    public record PatternDefinition(
            Identifier id,
            String translationKey,
            Identifier type,
            int telegraphTicks,
            int durationTicks,
            int cooldownTicks,
            int weight) {
        public static final Codec<PatternDefinition> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Identifier.CODEC.fieldOf("id").forGetter(PatternDefinition::id),
                Codec.string(1, 160).fieldOf("translation_key").forGetter(PatternDefinition::translationKey),
                Identifier.CODEC.fieldOf("type").forGetter(PatternDefinition::type),
                Codec.INT.fieldOf("telegraph_ticks").forGetter(PatternDefinition::telegraphTicks),
                Codec.INT.fieldOf("duration_ticks").forGetter(PatternDefinition::durationTicks),
                Codec.INT.fieldOf("cooldown_ticks").forGetter(PatternDefinition::cooldownTicks),
                Codec.INT.optionalFieldOf("weight", 1).forGetter(PatternDefinition::weight)
        ).apply(instance, PatternDefinition::new));
    }
}
