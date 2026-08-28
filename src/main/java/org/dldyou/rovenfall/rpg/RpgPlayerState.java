package org.dldyou.rovenfall.rpg;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;

/** Immutable, server-owned progression for one player. */
public record RpgPlayerState(
        Map<Identifier, Long> activityXp,
        Map<Identifier, CareerProgress> careers,
        Optional<Identifier> activeCareer,
        Map<Integer, Identifier> activeSkillSlots,
        Map<Identifier, Long> cooldowns,
        Set<Identifier> explorationDiscoveries,
        List<ProgressionProvenance> provenance,
        List<ProgressionProvenance> careerProvenance,
        long lastActiveSkillRequestId) {
    private static final UUID ZERO_UUID = new UUID(0L, 0L);
    public static final int MAX_ACTIVITIES = 128;
    public static final int MAX_CAREERS = 256;
    public static final int MAX_SKILLS = 1_024;
    public static final int MAX_ACTIVE_SKILL_SLOTS = 4;
    public static final int MAX_COOLDOWNS = 1_024;
    public static final int MAX_EXPLORATION_DISCOVERIES = 256;
    public static final int MAX_PROVENANCE = 256;
    public static final int MAX_CAREER_PROVENANCE = 256;
    public static final long MAX_XP = 1_000_000_000_000_000L;
    public static final int MAX_RANK = 1_000;
    public static final int MAX_SKILL_RANK = SkillDefinition.MAX_RANK;
    public static final int MAX_SKILL_POINTS = 1_000_000;

    private static final Codec<Long> XP_CODEC = Codec.LONG.validate(value ->
            value >= 0 && value <= MAX_XP
                    ? DataResult.success(value)
                    : DataResult.error(() -> "RPG XP must be between 0 and " + MAX_XP));
    private static final Codec<Long> TICK_CODEC = Codec.LONG.validate(value ->
            value >= 0 ? DataResult.success(value) : DataResult.error(() -> "RPG tick must be non-negative"));
    private static final Codec<Integer> SKILL_RANK_CODEC = Codec.intRange(1, MAX_SKILL_RANK);

    private static final Codec<Map<Identifier, Long>> ACTIVITY_XP_CODEC = mapCodec(
            XP_CODEC, MAX_ACTIVITIES, "activity XP");
    private static final Codec<Map<Identifier, CareerProgress>> CAREERS_CODEC = mapCodec(
            CareerProgress.CODEC, MAX_CAREERS, "career progress");
    private static final Codec<Map<Identifier, Long>> COOLDOWNS_CODEC = mapCodec(
            TICK_CODEC, MAX_COOLDOWNS, "skill cooldown");
    private static final Codec<Map<Integer, Identifier>> ACTIVE_SKILL_SLOTS_CODEC =
            ActiveSkillSlot.CODEC.listOf(0, MAX_ACTIVE_SKILL_SLOTS)
                    .flatXmap(RpgPlayerState::activeSkillSlotsFromEntries, RpgPlayerState::activeSkillSlotEntries);
    private static final Codec<Set<Identifier>> EXPLORATION_DISCOVERIES_CODEC =
            Identifier.CODEC.listOf(0, MAX_EXPLORATION_DISCOVERIES)
                    .flatXmap(RpgPlayerState::discoveriesFromEntries, RpgPlayerState::discoveryEntries);

    public static final Codec<RpgPlayerState> CODEC = RecordCodecBuilder.<RpgPlayerState>create(instance -> instance.group(
            ACTIVITY_XP_CODEC.optionalFieldOf("activity_xp", Map.of()).forGetter(RpgPlayerState::activityXp),
            CAREERS_CODEC.optionalFieldOf("careers", Map.of()).forGetter(RpgPlayerState::careers),
            Identifier.CODEC.optionalFieldOf("active_career").forGetter(RpgPlayerState::activeCareer),
            ACTIVE_SKILL_SLOTS_CODEC.optionalFieldOf("active_skill_slots", Map.of())
                    .forGetter(RpgPlayerState::activeSkillSlots),
            COOLDOWNS_CODEC.optionalFieldOf("cooldowns", Map.of()).forGetter(RpgPlayerState::cooldowns),
            EXPLORATION_DISCOVERIES_CODEC.optionalFieldOf("exploration_discoveries", Set.of())
                    .forGetter(RpgPlayerState::explorationDiscoveries),
            ProgressionProvenance.CODEC.listOf(0, MAX_PROVENANCE).optionalFieldOf("provenance", List.of())
                    .forGetter(RpgPlayerState::provenance),
            ProgressionProvenance.CODEC.listOf(0, MAX_CAREER_PROVENANCE)
                    .optionalFieldOf("career_provenance", List.of())
                    .forGetter(RpgPlayerState::careerProvenance),
            TICK_CODEC.optionalFieldOf("last_active_skill_request_id", 0L)
                    .forGetter(RpgPlayerState::lastActiveSkillRequestId)
    ).apply(instance, RpgPlayerState::new)).validate(RpgPlayerState::validate);

    public static final RpgPlayerState EMPTY = new RpgPlayerState(
            Map.of(), Map.of(), Optional.empty(), Map.of(), Map.of(), Set.of(), List.of(), List.of(), 0L);

    public RpgPlayerState(
            Map<Identifier, Long> activityXp,
            Map<Identifier, CareerProgress> careers,
            Optional<Identifier> activeCareer,
            Map<Integer, Identifier> activeSkillSlots,
            Map<Identifier, Long> cooldowns,
            Set<Identifier> explorationDiscoveries,
            List<ProgressionProvenance> provenance,
            List<ProgressionProvenance> careerProvenance) {
        this(activityXp, careers, activeCareer, activeSkillSlots, cooldowns,
                explorationDiscoveries, provenance, careerProvenance, 0L);
    }

    public RpgPlayerState(
            Map<Identifier, Long> activityXp,
            Map<Identifier, CareerProgress> careers,
            Optional<Identifier> activeCareer,
            Map<Integer, Identifier> activeSkillSlots,
            Map<Identifier, Long> cooldowns,
            List<ProgressionProvenance> provenance) {
        this(activityXp, careers, activeCareer, activeSkillSlots, cooldowns,
                Set.of(), provenance, List.of(), 0L);
    }

    public RpgPlayerState(
            Map<Identifier, Long> activityXp,
            Map<Identifier, CareerProgress> careers,
            Optional<Identifier> activeCareer,
            Map<Integer, Identifier> activeSkillSlots,
            Map<Identifier, Long> cooldowns,
            Set<Identifier> explorationDiscoveries,
            List<ProgressionProvenance> provenance) {
        this(activityXp, careers, activeCareer, activeSkillSlots, cooldowns,
                explorationDiscoveries, provenance, List.of(), 0L);
    }

    public RpgPlayerState {
        activityXp = Map.copyOf(activityXp);
        careers = Map.copyOf(careers);
        activeCareer = activeCareer == null ? Optional.empty() : activeCareer;
        activeSkillSlots = Map.copyOf(activeSkillSlots);
        cooldowns = Map.copyOf(cooldowns);
        explorationDiscoveries = Set.copyOf(explorationDiscoveries);
        provenance = List.copyOf(provenance);
        careerProvenance = List.copyOf(careerProvenance);
    }

    public boolean isValid() {
        return validationError(this).isEmpty();
    }

    private static DataResult<RpgPlayerState> validate(RpgPlayerState state) {
        Optional<String> error = validationError(state);
        return error.isEmpty()
                ? DataResult.success(state)
                : DataResult.error(error::orElseThrow);
    }

    private static Optional<String> validationError(RpgPlayerState state) {
        if (state.activityXp().size() > MAX_ACTIVITIES || state.careers().size() > MAX_CAREERS
                || state.cooldowns().size() > MAX_COOLDOWNS || state.provenance().size() > MAX_PROVENANCE
                || state.careerProvenance().size() > MAX_CAREER_PROVENANCE
                || state.explorationDiscoveries().size() > MAX_EXPLORATION_DISCOVERIES
                || state.activeSkillSlots().size() > MAX_ACTIVE_SKILL_SLOTS
                || state.lastActiveSkillRequestId() < 0) {
            return Optional.of("RPG player state exceeds a collection limit");
        }
        if (state.activeCareer().isPresent() && !state.careers().containsKey(state.activeCareer().orElseThrow())) {
            return Optional.of("Active career is missing from career progress");
        }
        Set<Identifier> slots = java.util.HashSet.newHashSet(state.activeSkillSlots().size());
        if (state.activeSkillSlots().entrySet().stream().anyMatch(entry ->
                entry.getKey() < 0 || entry.getKey() >= MAX_ACTIVE_SKILL_SLOTS || !slots.add(entry.getValue()))) {
            return Optional.of("Active skill slots are invalid or contain a duplicate skill");
        }
        for (Map.Entry<Identifier, Long> entry : state.activityXp().entrySet()) {
            if (entry.getValue() < 0 || entry.getValue() > MAX_XP) {
                return Optional.of("Activity XP is out of bounds");
            }
        }
        for (CareerProgress progress : state.careers().values()) {
            if (!progress.isValid()) {
                return Optional.of("Career progress is out of bounds");
            }
        }
        for (Map.Entry<Identifier, Long> entry : state.cooldowns().entrySet()) {
            if (entry.getValue() < 0) {
                return Optional.of("Skill cooldown is out of bounds");
            }
        }
        Set<UUID> transactions = java.util.HashSet.newHashSet(
                state.provenance().size() + state.careerProvenance().size());
        for (ProgressionProvenance entry : state.provenance()) {
            if (!entry.isValid() || !transactions.add(entry.transactionId())) {
                return Optional.of("Progression provenance is invalid or contains a duplicate transaction");
            }
        }
        for (ProgressionProvenance entry : state.careerProvenance()) {
            if (!entry.isValid() || !transactions.add(entry.transactionId())) {
                return Optional.of("Career provenance is invalid or contains a duplicate transaction");
            }
        }
        return Optional.empty();
    }

    private static DataResult<Map<Integer, Identifier>> activeSkillSlotsFromEntries(
            List<ActiveSkillSlot> entries) {
        Map<Integer, Identifier> result = new LinkedHashMap<>();
        Set<Identifier> skills = java.util.HashSet.newHashSet(entries.size());
        for (ActiveSkillSlot entry : entries) {
            if (result.putIfAbsent(entry.slot(), entry.skill()) != null) {
                return DataResult.error(() -> "Duplicate active skill slot " + entry.slot());
            }
            if (!skills.add(entry.skill())) {
                return DataResult.error(() -> "Duplicate active skill " + entry.skill());
            }
        }
        return DataResult.success(Map.copyOf(result));
    }

    private static DataResult<List<ActiveSkillSlot>> activeSkillSlotEntries(
            Map<Integer, Identifier> slots) {
        return DataResult.success(slots.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new ActiveSkillSlot(entry.getKey(), entry.getValue()))
                .toList());
    }

    private static DataResult<Set<Identifier>> discoveriesFromEntries(List<Identifier> entries) {
        Set<Identifier> result = new java.util.LinkedHashSet<>();
        for (Identifier entry : entries) {
            if (!result.add(entry)) {
                return DataResult.error(() -> "Duplicate exploration discovery " + entry);
            }
        }
        return DataResult.success(Set.copyOf(result));
    }

    private static DataResult<List<Identifier>> discoveryEntries(Set<Identifier> discoveries) {
        return DataResult.success(discoveries.stream().sorted().toList());
    }

    private record ActiveSkillSlot(int slot, Identifier skill) {
        private static final Codec<ActiveSkillSlot> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.intRange(0, MAX_ACTIVE_SKILL_SLOTS - 1).fieldOf("slot").forGetter(ActiveSkillSlot::slot),
                Identifier.CODEC.fieldOf("skill").forGetter(ActiveSkillSlot::skill)
        ).apply(instance, ActiveSkillSlot::new));
    }

    static <T> Codec<Map<Identifier, T>> mapCodec(Codec<T> valueCodec, int maximum, String label) {
        return MapEntry.codec(valueCodec).listOf(0, maximum)
                .flatXmap(entries -> fromEntries(entries, maximum, label), RpgPlayerState::toEntries);
    }

    private static <T> DataResult<Map<Identifier, T>> fromEntries(
            List<MapEntry<T>> entries, int maximum, String label) {
        if (entries.size() > maximum) {
            return DataResult.error(() -> label + " count exceeds " + maximum);
        }
        Map<Identifier, T> result = new LinkedHashMap<>();
        for (MapEntry<T> entry : entries) {
            if (result.putIfAbsent(entry.id(), entry.value()) != null) {
                return DataResult.error(() -> "Duplicate RPG " + label + " ID " + entry.id());
            }
        }
        return DataResult.success(Map.copyOf(result));
    }

    private static <T> DataResult<List<MapEntry<T>>> toEntries(Map<Identifier, T> values) {
        return DataResult.success(values.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new MapEntry<>(entry.getKey(), entry.getValue()))
                .toList());
    }

    private record MapEntry<T>(Identifier id, T value) {
        static <T> Codec<MapEntry<T>> codec(Codec<T> valueCodec) {
            return RecordCodecBuilder.create(instance -> instance.group(
                    Identifier.CODEC.fieldOf("id").forGetter(MapEntry::id),
                    valueCodec.fieldOf("value").forGetter(MapEntry::value)
            ).apply(instance, MapEntry::new));
        }
    }

    public record CareerProgress(long experience, int rank, int skillPoints, Map<Identifier, Integer> learnedSkills) {
        public static final Codec<CareerProgress> CODEC = RecordCodecBuilder.<CareerProgress>create(instance -> instance.group(
                XP_CODEC.fieldOf("experience").forGetter(CareerProgress::experience),
                Codec.intRange(0, MAX_RANK).fieldOf("rank").forGetter(CareerProgress::rank),
                Codec.intRange(0, MAX_SKILL_POINTS).fieldOf("skill_points").forGetter(CareerProgress::skillPoints),
                mapCodec(SKILL_RANK_CODEC, MAX_SKILLS, "learned skill").optionalFieldOf("learned_skills", Map.of())
                        .forGetter(CareerProgress::learnedSkills)
        ).apply(instance, CareerProgress::new)).validate(CareerProgress::validate);

        public CareerProgress {
            learnedSkills = Map.copyOf(learnedSkills);
        }

        private static DataResult<CareerProgress> validate(CareerProgress progress) {
            return progress.isValid()
                    ? DataResult.success(progress)
                    : DataResult.error(() -> "Career progress is out of bounds");
        }

        boolean isValid() {
            return experience >= 0 && experience <= MAX_XP
                    && rank >= 0 && rank <= MAX_RANK
                    && skillPoints >= 0 && skillPoints <= MAX_SKILL_POINTS
                    && learnedSkills.size() <= MAX_SKILLS
                    && learnedSkills.values().stream().allMatch(value -> value >= 1 && value <= MAX_SKILL_RANK);
        }
    }

    public record ProgressionProvenance(
            Kind kind,
            Identifier target,
            long amount,
            long timestamp,
            UUID transactionId,
            String source,
            Optional<Identifier> previousTarget,
            List<RpgItemCost> itemCosts,
            List<Long> itemCountsBefore,
            List<Long> itemCountsAfter,
            Optional<SkillResetPlan> resetPlan) {
        public static final Codec<ProgressionProvenance> CODEC = RecordCodecBuilder.<ProgressionProvenance>create(instance -> instance.group(
                Kind.CODEC.fieldOf("kind").forGetter(ProgressionProvenance::kind),
                Identifier.CODEC.fieldOf("target").forGetter(ProgressionProvenance::target),
                XP_CODEC.fieldOf("amount").forGetter(ProgressionProvenance::amount),
                TICK_CODEC.fieldOf("timestamp").forGetter(ProgressionProvenance::timestamp),
                UUIDUtil.STRING_CODEC.fieldOf("transaction").forGetter(ProgressionProvenance::transactionId),
                Codec.string(1, 160).fieldOf("source").forGetter(ProgressionProvenance::source),
                Identifier.CODEC.optionalFieldOf("previous_target").forGetter(ProgressionProvenance::previousTarget),
                RpgItemCost.LIST_CODEC.optionalFieldOf("item_costs", List.of())
                        .forGetter(ProgressionProvenance::itemCosts),
                Codec.LONG.listOf(0, RpgItemCost.MAX_ENTRIES)
                        .optionalFieldOf("item_counts_before", List.of())
                        .forGetter(ProgressionProvenance::itemCountsBefore),
                Codec.LONG.listOf(0, RpgItemCost.MAX_ENTRIES)
                        .optionalFieldOf("item_counts_after", List.of())
                        .forGetter(ProgressionProvenance::itemCountsAfter),
                SkillResetPlan.CODEC.optionalFieldOf("reset_plan")
                        .forGetter(ProgressionProvenance::resetPlan)
        ).apply(instance, ProgressionProvenance::new)).validate(ProgressionProvenance::validate);

        public ProgressionProvenance(
                Kind kind,
                Identifier target,
                long amount,
                long timestamp,
                UUID transactionId,
                String source,
                Optional<Identifier> previousTarget,
                List<RpgItemCost> itemCosts) {
            this(kind, target, amount, timestamp, transactionId, source,
                    previousTarget, itemCosts, List.of(), List.of(), Optional.empty());
        }

        public ProgressionProvenance(
                Kind kind,
                Identifier target,
                long amount,
                long timestamp,
                UUID transactionId,
                String source,
                Optional<Identifier> previousTarget,
                List<RpgItemCost> itemCosts,
                Optional<SkillResetPlan> resetPlan) {
            this(kind, target, amount, timestamp, transactionId, source,
                    previousTarget, itemCosts, List.of(), List.of(), resetPlan);
        }

        public ProgressionProvenance(
                Kind kind,
                Identifier target,
                long amount,
                long timestamp,
                UUID transactionId,
                String source,
                Optional<Identifier> previousTarget) {
            this(kind, target, amount, timestamp, transactionId, source,
                    previousTarget, List.of(), List.of(), List.of(), Optional.empty());
        }

        public ProgressionProvenance(
                Kind kind,
                Identifier target,
                long amount,
                long timestamp,
                UUID transactionId,
                String source) {
            this(kind, target, amount, timestamp, transactionId, source,
                    Optional.empty(), List.of(), List.of(), List.of(), Optional.empty());
        }

        public ProgressionProvenance {
            previousTarget = previousTarget == null ? Optional.empty() : previousTarget;
            itemCosts = itemCosts == null ? List.of() : List.copyOf(itemCosts);
            itemCountsBefore = itemCountsBefore == null ? List.of() : List.copyOf(itemCountsBefore);
            itemCountsAfter = itemCountsAfter == null ? List.of() : List.copyOf(itemCountsAfter);
            resetPlan = resetPlan == null ? Optional.empty() : resetPlan;
        }

        boolean isValid() {
            return kind != null && target != null && amount >= 0 && amount <= MAX_XP && timestamp >= 0
                    && transactionId != null && !ZERO_UUID.equals(transactionId)
                    && source != null && !source.isBlank() && source.length() <= 160
                    && previousTarget != null && itemCosts != null && itemCosts.size() <= RpgItemCost.MAX_ENTRIES
                    && itemCosts.stream().allMatch(item -> item != null && item.item() != null
                            && item.count() >= 1 && item.count() <= RpgItemCost.MAX_COUNT)
                    && itemCosts.stream().map(RpgItemCost::item).distinct().count() == itemCosts.size()
                    && itemCountsBefore != null && itemCountsAfter != null
                    && (itemCosts.isEmpty()
                            ? itemCountsBefore.isEmpty() && itemCountsAfter.isEmpty()
                            : itemCountsBefore.size() == itemCosts.size()
                                    && itemCountsAfter.size() == itemCosts.size()
                                    && java.util.stream.IntStream.range(0, itemCosts.size()).allMatch(index -> {
                                        long before = itemCountsBefore.get(index);
                                        long after = itemCountsAfter.get(index);
                                        return before >= itemCosts.get(index).count()
                                                && after == before - itemCosts.get(index).count();
                                    }))
                    && resetPlan != null
                    && (kind == Kind.SKILL_RESET
                            ? resetPlan.filter(plan -> plan.target().equals(target)).isPresent()
                                    || itemCosts.isEmpty() && resetPlan.isEmpty()
                            : resetPlan.isEmpty());
        }

        private static DataResult<ProgressionProvenance> validate(ProgressionProvenance provenance) {
            return provenance.isValid()
                    ? DataResult.success(provenance)
                    : DataResult.error(() -> "Progression provenance is invalid");
        }

        public enum Kind implements net.minecraft.util.StringRepresentable {
            ACTIVITY_XP("activity_xp"), CAREER_XP("career_xp"), SKILL_UNLOCK("skill_unlock"),
            CAREER_PROMOTION("career_promotion"), CAREER_SWITCH("career_switch"), SKILL_RESET("skill_reset"),
            SKILL_SLOT("skill_slot"), ADMIN_ACTIVITY_XP("admin_activity_xp"),
            ADMIN_PROMOTION("admin_promotion"), ADMIN_SKILL_RESET("admin_skill_reset");

            public static final Codec<Kind> CODEC = net.minecraft.util.StringRepresentable.fromEnum(Kind::values);
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
}
