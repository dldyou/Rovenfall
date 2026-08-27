package org.dldyou.rovenfall.mobs;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.dldyou.rovenfall.Rovenfall;

public final class MobContentSnapshot {
    public static final int MAX_CATALOGS = 256;
    public static final int MAX_TICKS = 20 * 60 * 60 * 24 * 30;
    public static final long MAX_REWARD = 1_000_000_000_000L;
    public static final ResourceKey<Level> WILDERNESS_DIMENSION = ResourceKey.create(
            Registries.DIMENSION, Identifier.fromNamespaceAndPath(Rovenfall.MOD_ID, "wilderness"));
    private static final Pattern TRANSLATION_KEY = Pattern.compile("[a-z0-9_.-]{1,160}");
    private static final Set<Identifier> BUILT_IN_BEHAVIOR_MODIFIERS = Set.of(
            rovenfall("ambush"), rovenfall("burrow"), rovenfall("boss_controller"), rovenfall("death_burst"));
    private static final Set<Identifier> BUILT_IN_PATTERN_TYPES = Set.of(
            rovenfall("melee_sweep"), rovenfall("projectile_barrage"),
            rovenfall("shockwave"), rovenfall("summon_minions"));
    private static final Identifier CATALOG_FILE = Identifier.fromNamespaceAndPath(Rovenfall.MOD_ID, "mob_content_catalog");
    private static final MobContentSnapshot EMPTY = new MobContentSnapshot(
            Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());

    private final Map<Identifier, MobContentCatalog.MobDefinition> mobs;
    private final Map<Identifier, MobContentCatalog.MutationDefinition> mutations;
    private final Map<Identifier, MobContentCatalog.ArenaPolicy> arenas;
    private final Map<Identifier, MobContentCatalog.ContributionRule> contributionRules;
    private final Map<Identifier, MobContentCatalog.LootDefinition> loot;
    private final Map<Identifier, MobContentCatalog.BossDefinition> bosses;
    private final Map<Identifier, Source> sources;

    private MobContentSnapshot(
            Map<Identifier, MobContentCatalog.MobDefinition> mobs,
            Map<Identifier, MobContentCatalog.MutationDefinition> mutations,
            Map<Identifier, MobContentCatalog.ArenaPolicy> arenas,
            Map<Identifier, MobContentCatalog.ContributionRule> contributionRules,
            Map<Identifier, MobContentCatalog.LootDefinition> loot,
            Map<Identifier, MobContentCatalog.BossDefinition> bosses,
            Map<Identifier, Source> sources) {
        this.mobs = Map.copyOf(mobs);
        this.mutations = Map.copyOf(mutations);
        this.arenas = Map.copyOf(arenas);
        this.contributionRules = Map.copyOf(contributionRules);
        this.loot = Map.copyOf(loot);
        this.bosses = Map.copyOf(bosses);
        this.sources = Map.copyOf(sources);
    }

    public static MobContentSnapshot empty() {
        return EMPTY;
    }

    public static MobContentSnapshot compile(Collection<Source> candidates) {
        List<Source> ordered = candidates.stream()
                .sorted(Comparator.comparing(Source::catalogId).thenComparing(Source::file))
                .toList();
        if (ordered.size() > MAX_CATALOGS) {
            throw new ValidationException(List.of(new Problem(
                    CATALOG_FILE, CATALOG_FILE, "catalog count exceeds " + MAX_CATALOGS)));
        }

        var mobs = new LinkedHashMap<Identifier, MobContentCatalog.MobDefinition>();
        var mutations = new LinkedHashMap<Identifier, MobContentCatalog.MutationDefinition>();
        var arenas = new LinkedHashMap<Identifier, MobContentCatalog.ArenaPolicy>();
        var contributionRules = new LinkedHashMap<Identifier, MobContentCatalog.ContributionRule>();
        var loot = new LinkedHashMap<Identifier, MobContentCatalog.LootDefinition>();
        var bosses = new LinkedHashMap<Identifier, MobContentCatalog.BossDefinition>();
        var sources = new LinkedHashMap<Identifier, Source>();
        var occurrences = new HashMap<Identifier, List<Occurrence>>();

        for (Source source : ordered) {
            source.catalog().mobs().forEach(definition -> {
                mobs.putIfAbsent(definition.id(), definition);
                register(source, definition.id(), "mob", sources, occurrences);
            });
            source.catalog().mutations().forEach(definition -> {
                mutations.putIfAbsent(definition.id(), definition);
                register(source, definition.id(), "mutation", sources, occurrences);
            });
            source.catalog().arenas().forEach(definition -> {
                arenas.putIfAbsent(definition.id(), definition);
                register(source, definition.id(), "arena", sources, occurrences);
            });
            source.catalog().contributionRules().forEach(definition -> {
                contributionRules.putIfAbsent(definition.id(), definition);
                register(source, definition.id(), "contribution rule", sources, occurrences);
            });
            source.catalog().loot().forEach(definition -> {
                loot.putIfAbsent(definition.id(), definition);
                register(source, definition.id(), "loot", sources, occurrences);
            });
            source.catalog().bosses().forEach(definition -> {
                bosses.putIfAbsent(definition.id(), definition);
                register(source, definition.id(), "boss", sources, occurrences);
                definition.phases().forEach(phase -> {
                    register(source, phase.id(), "boss phase", sources, occurrences);
                    phase.patterns().forEach(pattern ->
                            register(source, pattern.id(), "boss pattern", sources, occurrences));
                });
            });
        }

        List<Problem> problems = new ArrayList<>();
        occurrences.entrySet().stream()
                .filter(entry -> entry.getValue().size() > 1)
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    Occurrence first = entry.getValue().getFirst();
                    String locations = entry.getValue().stream()
                            .map(value -> value.kind() + " in " + value.source().file()
                                    + " (" + value.source().packId() + ")")
                            .toList().toString();
                    problems.add(new Problem(first.source().file(), entry.getKey(),
                            "duplicate definition ID in " + locations));
                });
        globalCount("mob", mobs.size(), problems);
        globalCount("mutation", mutations.size(), problems);
        globalCount("arena", arenas.size(), problems);
        globalCount("contribution rule", contributionRules.size(), problems);
        globalCount("loot", loot.size(), problems);
        globalCount("boss", bosses.size(), problems);

        for (Source source : ordered) {
            validate(source, mobs, arenas, contributionRules, loot, problems);
        }
        if (!problems.isEmpty()) {
            throw new ValidationException(problems);
        }
        return new MobContentSnapshot(mobs, mutations, arenas, contributionRules, loot, bosses, sources);
    }

    private static void register(
            Source source,
            Identifier id,
            String kind,
            Map<Identifier, Source> sources,
            Map<Identifier, List<Occurrence>> occurrences) {
        sources.putIfAbsent(id, source);
        occurrences.computeIfAbsent(id, ignored -> new ArrayList<>()).add(new Occurrence(source, kind));
    }

    private static void validate(
            Source source,
            Map<Identifier, MobContentCatalog.MobDefinition> mobs,
            Map<Identifier, MobContentCatalog.ArenaPolicy> arenas,
            Map<Identifier, MobContentCatalog.ContributionRule> contributionRules,
            Map<Identifier, MobContentCatalog.LootDefinition> loot,
            List<Problem> problems) {
        MobContentCatalog catalog = source.catalog();
        catalog.mobs().forEach(mob -> validateMob(source, mob, loot, problems));
        catalog.mutations().forEach(mutation -> validateMutation(source, mutation, loot, problems));
        catalog.arenas().forEach(arena -> validateArena(source, arena, problems));
        catalog.contributionRules().forEach(rule -> validateContribution(source, rule, problems));
        catalog.loot().forEach(reward -> validateLoot(source, reward, problems));
        catalog.bosses().forEach(boss -> validateBoss(
                source, boss, mobs, arenas, contributionRules, loot, problems));
    }

    private static void validateMob(
            Source source,
            MobContentCatalog.MobDefinition mob,
            Map<Identifier, MobContentCatalog.LootDefinition> loot,
            List<Problem> problems) {
        translation(source, mob.id(), mob.translationKey(), problems);
        finiteRange(source, mob.id(), "max health", mob.maxHealth(), 1, 1_000_000, problems);
        finiteRange(source, mob.id(), "attack damage", mob.attackDamage(), 0, 1_000_000, problems);
        finiteRange(source, mob.id(), "movement speed", mob.movementSpeed(), 0.001, 10, problems);
        count(source, mob.id(), "behavior modifiers", mob.behaviorModifiers().size(), 0,
                MobContentCatalog.MAX_REFERENCES, problems);
        unique(source, mob.id(), "behavior modifier", mob.behaviorModifiers(), problems);
        registered(source, mob.id(), "behavior modifier", mob.behaviorModifiers(),
                BUILT_IN_BEHAVIOR_MODIFIERS, problems);
        reference(source, mob.id(), "loot", mob.loot(), loot, problems);
    }

    private static void validateMutation(
            Source source,
            MobContentCatalog.MutationDefinition mutation,
            Map<Identifier, MobContentCatalog.LootDefinition> loot,
            List<Problem> problems) {
        translation(source, mutation.id(), mutation.translationKey(), problems);
        translation(source, mutation.id(), mutation.markerTranslationKey(), problems);
        count(source, mutation.id(), "eligible entity types", mutation.eligibleEntityTypes().size(), 1,
                MobContentCatalog.MAX_REFERENCES, problems);
        count(source, mutation.id(), "attribute modifiers", mutation.attributes().size(), 0,
                MobContentCatalog.MAX_REFERENCES, problems);
        count(source, mutation.id(), "behavior modifiers", mutation.behaviorModifiers().size(), 0,
                MobContentCatalog.MAX_REFERENCES, problems);
        unique(source, mutation.id(), "eligible entity type", mutation.eligibleEntityTypes(), problems);
        unique(source, mutation.id(), "behavior modifier", mutation.behaviorModifiers(), problems);
        registered(source, mutation.id(), "behavior modifier", mutation.behaviorModifiers(),
                BUILT_IN_BEHAVIOR_MODIFIERS, problems);
        if (mutation.attributes().isEmpty() && mutation.behaviorModifiers().isEmpty()) {
            problems.add(problem(source, mutation.id(), "mutation requires an attribute or behavior modifier"));
        }
        Set<String> attributes = new HashSet<>();
        for (MobContentCatalog.AttributeModifier modifier : mutation.attributes()) {
            String key = modifier.attribute() + "/" + modifier.operation();
            if (!attributes.add(key)) {
                problems.add(problem(source, mutation.id(), "duplicate attribute operation: " + key));
            }
            finiteRange(source, mutation.id(), "attribute amount", modifier.amount(), -1_000_000, 1_000_000, problems);
        }
        MobContentCatalog.SpawnCondition spawn = mutation.spawn();
        if (!spawn.dimension().equals(WILDERNESS_DIMENSION)) {
            problems.add(problem(source, mutation.id(),
                    "mutation spawn dimension must be " + WILDERNESS_DIMENSION.identifier()));
        }
        range(source, mutation.id(), "spawn chance per million", spawn.chancePerMillion(), 1, 1_000_000, problems);
        range(source, mutation.id(), "minimum Y", spawn.minimumY(), -2_048, 2_048, problems);
        range(source, mutation.id(), "maximum Y", spawn.maximumY(), -2_048, 2_048, problems);
        if (spawn.minimumY() > spawn.maximumY()) {
            problems.add(problem(source, mutation.id(), "minimum Y must not exceed maximum Y"));
        }
        range(source, mutation.id(), "reward multiplier percent", mutation.rewardMultiplierPercent(), 1, 10_000, problems);
        mutation.bonusLoot().ifPresent(id -> reference(source, mutation.id(), "bonus loot", id, loot, problems));
    }

    private static void validateArena(
            Source source, MobContentCatalog.ArenaPolicy arena, List<Problem> problems) {
        if (arena.dimension().equals(Level.OVERWORLD)) {
            problems.add(problem(source, arena.id(), "boss arena cannot target the Hub dimension"));
        }
        range(source, arena.id(), "protection radius", arena.protectionRadius(), 1, 1_024, problems);
        range(source, arena.id(), "leash radius", arena.leashRadius(), 1, 2_048, problems);
        if (arena.leashRadius() < arena.protectionRadius()) {
            problems.add(problem(source, arena.id(), "leash radius must cover the protection radius"));
        }
        range(source, arena.id(), "reset timeout", arena.resetTimeoutTicks(), 20, MAX_TICKS, problems);
    }

    private static void validateContribution(
            Source source, MobContentCatalog.ContributionRule rule, List<Problem> problems) {
        longRange(source, rule.id(), "minimum points", rule.minimumPoints(), 1, MAX_REWARD, problems);
        range(source, rule.id(), "minimum share basis points", rule.minimumShareBasisPoints(), 1, 10_000, problems);
        range(source, rule.id(), "maximum contributors", rule.maximumContributors(), 1, 1_024, problems);
    }

    private static void validateLoot(
            Source source, MobContentCatalog.LootDefinition loot, List<Problem> problems) {
        range(source, loot.id(), "loot rolls", loot.rolls(), 1, 64, problems);
        longRange(source, loot.id(), "currency reward", loot.currency(), 0, MAX_REWARD, problems);
        longRange(source, loot.id(), "experience reward", loot.experience(), 0, MAX_REWARD, problems);
    }

    private static void validateBoss(
            Source source,
            MobContentCatalog.BossDefinition boss,
            Map<Identifier, MobContentCatalog.MobDefinition> mobs,
            Map<Identifier, MobContentCatalog.ArenaPolicy> arenas,
            Map<Identifier, MobContentCatalog.ContributionRule> contributionRules,
            Map<Identifier, MobContentCatalog.LootDefinition> loot,
            List<Problem> problems) {
        translation(source, boss.id(), boss.translationKey(), problems);
        reference(source, boss.id(), "mob", boss.mob(), mobs, problems);
        reference(source, boss.id(), "arena", boss.arena(), arenas, problems);
        reference(source, boss.id(), "contribution rule", boss.contributionRule(), contributionRules, problems);
        reference(source, boss.id(), "loot", boss.loot(), loot, problems);
        range(source, boss.id(), "reward cooldown", boss.rewardCooldownTicks(), 1, MAX_TICKS, problems);
        count(source, boss.id(), "phases", boss.phases().size(), 1, MobContentCatalog.MAX_PHASES, problems);

        int priorThreshold = 101;
        for (int index = 0; index < boss.phases().size(); index++) {
            MobContentCatalog.Phase phase = boss.phases().get(index);
            translation(source, phase.id(), phase.translationKey(), problems);
            range(source, phase.id(), "start health percent", phase.startHealthPercent(), 1, 100, problems);
            if (index == 0 && phase.startHealthPercent() != 100) {
                problems.add(problem(source, phase.id(), "first phase must start at 100 percent health"));
            }
            if (phase.startHealthPercent() >= priorThreshold) {
                problems.add(problem(source, phase.id(), "phase health thresholds must be strictly descending"));
            }
            priorThreshold = phase.startHealthPercent();
            count(source, phase.id(), "patterns", phase.patterns().size(), 1,
                    MobContentCatalog.MAX_PATTERNS, problems);
            for (MobContentCatalog.PatternDefinition pattern : phase.patterns()) {
                translation(source, pattern.id(), pattern.translationKey(), problems);
                if (!BUILT_IN_PATTERN_TYPES.contains(pattern.type())) {
                    problems.add(problem(source, pattern.id(), "unknown pattern type reference: " + pattern.type()));
                }
                range(source, pattern.id(), "telegraph ticks", pattern.telegraphTicks(), 1, MAX_TICKS, problems);
                range(source, pattern.id(), "duration ticks", pattern.durationTicks(), 1, MAX_TICKS, problems);
                range(source, pattern.id(), "cooldown ticks", pattern.cooldownTicks(), 1, MAX_TICKS, problems);
                range(source, pattern.id(), "pattern weight", pattern.weight(), 1, 10_000, problems);
            }
        }
    }

    private static void translation(Source source, Identifier id, String key, List<Problem> problems) {
        if (!TRANSLATION_KEY.matcher(key).matches()) {
            problems.add(problem(source, id, "invalid translation key: " + key));
        }
    }

    private static void globalCount(String kind, int value, List<Problem> problems) {
        if (value > MobContentCatalog.MAX_ENTRIES_PER_KIND) {
            problems.add(new Problem(CATALOG_FILE, CATALOG_FILE,
                    kind + " count exceeds " + MobContentCatalog.MAX_ENTRIES_PER_KIND));
        }
    }

    private static void count(
            Source source, Identifier id, String name, int value, int minimum, int maximum,
            List<Problem> problems) {
        if (value < minimum || value > maximum) {
            problems.add(problem(source, id, name + " count must be between " + minimum + " and " + maximum));
        }
    }

    private static <T> void unique(
            Source source, Identifier id, String kind, Collection<T> values, List<Problem> problems) {
        Set<T> seen = new HashSet<>();
        for (T value : values) {
            if (!seen.add(value)) {
                problems.add(problem(source, id, "duplicate " + kind + ": " + value));
            }
        }
    }

    private static void registered(
            Source source,
            Identifier id,
            String kind,
            Collection<Identifier> references,
            Set<Identifier> registered,
            List<Problem> problems) {
        references.stream()
                .filter(reference -> !registered.contains(reference))
                .forEach(reference -> problems.add(problem(source, id,
                        "unknown " + kind + " reference: " + reference)));
    }

    private static Identifier rovenfall(String path) {
        return Identifier.fromNamespaceAndPath(Rovenfall.MOD_ID, path);
    }

    private static void finiteRange(
            Source source, Identifier id, String name, double value, double minimum, double maximum,
            List<Problem> problems) {
        if (!Double.isFinite(value) || value < minimum || value > maximum) {
            problems.add(problem(source, id, name + " must be between " + minimum + " and " + maximum));
        }
    }

    private static void range(
            Source source, Identifier id, String name, int value, int minimum, int maximum,
            List<Problem> problems) {
        if (value < minimum || value > maximum) {
            problems.add(problem(source, id, name + " must be between " + minimum + " and " + maximum));
        }
    }

    private static void longRange(
            Source source, Identifier id, String name, long value, long minimum, long maximum,
            List<Problem> problems) {
        if (value < minimum || value > maximum) {
            problems.add(problem(source, id, name + " must be between " + minimum + " and " + maximum));
        }
    }

    private static <T> void reference(
            Source source, Identifier id, String kind, Identifier reference, Map<Identifier, T> target,
            List<Problem> problems) {
        if (!target.containsKey(reference)) {
            problems.add(problem(source, id, "missing " + kind + " reference: " + reference));
        }
    }

    private static Problem problem(Source source, Identifier id, String cause) {
        return new Problem(source.file(), id, cause);
    }

    public MobContentSnapshot validateRuntimeBindings(RuntimeBindings bindings) {
        List<Problem> problems = new ArrayList<>();
        mobs.forEach((id, mob) -> validateEntityType(id, mob.entityType(), problems));
        mutations.forEach((id, mutation) -> {
            mutation.eligibleEntityTypes().forEach(entityType -> validateEntityType(id, entityType, problems));
            mutation.attributes().forEach(modifier -> {
                if (!BuiltInRegistries.ATTRIBUTE.containsKey(modifier.attribute())) {
                    problems.add(problem(sources.get(id), id, "unknown attribute: " + modifier.attribute()));
                }
            });
            validateDimension(id, mutation.spawn().dimension(), bindings, problems);
        });
        arenas.forEach((id, arena) -> validateDimension(id, arena.dimension(), bindings, problems));
        loot.forEach((id, reward) -> {
            if (bindings.registries().get(reward.lootTable()).isEmpty()) {
                problems.add(problem(sources.get(id), id,
                        "unknown loot table: " + reward.lootTable().identifier()));
            }
        });
        if (!problems.isEmpty()) {
            throw new ValidationException(problems);
        }
        return this;
    }

    private void validateDimension(
            Identifier id, ResourceKey<Level> dimension, RuntimeBindings bindings, List<Problem> problems) {
        Source source = sources.get(id);
        if (dimension.equals(bindings.hubDimension())) {
            problems.add(problem(source, id, "mob content cannot target the Hub dimension"));
        } else if (!bindings.configuredDimensions().contains(dimension)
                && bindings.registries().get(dimension).isEmpty()) {
            problems.add(problem(source, id, "unknown dimension: " + dimension.identifier()));
        }
    }

    private void validateEntityType(Identifier id, Identifier entityType, List<Problem> problems) {
        if (!BuiltInRegistries.ENTITY_TYPE.containsKey(entityType)) {
            Source source = sources.get(id);
            problems.add(problem(source, id, "unknown entity type: " + entityType));
        }
    }

    public record RuntimeBindings(
            HolderLookup.Provider registries,
            ResourceKey<Level> hubDimension,
            Set<ResourceKey<Level>> configuredDimensions) {
        public RuntimeBindings {
            Objects.requireNonNull(registries, "registries");
            Objects.requireNonNull(hubDimension, "hubDimension");
            configuredDimensions = Set.copyOf(configuredDimensions);
        }

        public static RuntimeBindings strict(
                HolderLookup.Provider registries,
                ResourceKey<Level> hubDimension,
                Set<ResourceKey<Level>> configuredDimensions) {
            return new RuntimeBindings(registries, hubDimension, configuredDimensions);
        }
    }

    public Optional<MobContentCatalog.MobDefinition> mob(Identifier id) {
        return Optional.ofNullable(mobs.get(id));
    }

    public Optional<MobContentCatalog.MutationDefinition> mutation(Identifier id) {
        return Optional.ofNullable(mutations.get(id));
    }

    public Optional<MobContentCatalog.ArenaPolicy> arena(Identifier id) {
        return Optional.ofNullable(arenas.get(id));
    }

    public Optional<MobContentCatalog.ContributionRule> contributionRule(Identifier id) {
        return Optional.ofNullable(contributionRules.get(id));
    }

    public Optional<MobContentCatalog.LootDefinition> loot(Identifier id) {
        return Optional.ofNullable(loot.get(id));
    }

    public Optional<MobContentCatalog.BossDefinition> boss(Identifier id) {
        return Optional.ofNullable(bosses.get(id));
    }

    public int size() {
        return mobs.size() + mutations.size() + arenas.size() + contributionRules.size() + loot.size() + bosses.size();
    }

    public Map<Identifier, MobContentCatalog.MobDefinition> mobs() {
        return mobs;
    }

    public Map<Identifier, MobContentCatalog.MutationDefinition> mutations() {
        return mutations;
    }

    public Map<Identifier, MobContentCatalog.BossDefinition> bosses() {
        return bosses;
    }

    public record Source(Identifier file, String packId, Identifier catalogId, MobContentCatalog catalog) {
    }

    private record Occurrence(Source source, String kind) {
    }

    public record Problem(Identifier file, Identifier definitionId, String cause) {
        @Override
        public String toString() {
            return file + " [" + definitionId + "]: " + cause;
        }
    }

    public static final class ValidationException extends RuntimeException {
        private final List<Problem> problems;

        public ValidationException(Collection<Problem> problems) {
            super(problems.stream().map(Problem::toString).reduce((left, right) -> left + "; " + right)
                    .orElse("invalid mob content definitions"));
            this.problems = List.copyOf(problems);
        }

        public List<Problem> problems() {
            return problems;
        }
    }
}
