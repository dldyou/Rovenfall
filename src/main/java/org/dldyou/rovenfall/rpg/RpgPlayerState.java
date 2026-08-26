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
import net.minecraft.resources.Identifier;

/** Immutable, server-owned progression for one player. */
public record RpgPlayerState(
        Map<Identifier, Long> activityXp,
        Map<Identifier, CareerProgress> careers,
        Optional<Identifier> activeCareer,
        List<Identifier> activeSkillSlots,
        Map<Identifier, Long> cooldowns,
        List<ProgressionProvenance> provenance) {
    public static final int MAX_ACTIVITIES = 128;
    public static final int MAX_CAREERS = 256;
    public static final int MAX_SKILLS = 1_024;
    public static final int MAX_ACTIVE_SKILL_SLOTS = 4;
    public static final int MAX_COOLDOWNS = 1_024;
    public static final int MAX_PROVENANCE = 256;
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

    public static final Codec<RpgPlayerState> CODEC = RecordCodecBuilder.<RpgPlayerState>create(instance -> instance.group(
            ACTIVITY_XP_CODEC.optionalFieldOf("activity_xp", Map.of()).forGetter(RpgPlayerState::activityXp),
            CAREERS_CODEC.optionalFieldOf("careers", Map.of()).forGetter(RpgPlayerState::careers),
            Identifier.CODEC.optionalFieldOf("active_career").forGetter(RpgPlayerState::activeCareer),
            Identifier.CODEC.listOf(0, MAX_ACTIVE_SKILL_SLOTS).optionalFieldOf("active_skill_slots", List.of())
                    .forGetter(RpgPlayerState::activeSkillSlots),
            COOLDOWNS_CODEC.optionalFieldOf("cooldowns", Map.of()).forGetter(RpgPlayerState::cooldowns),
            ProgressionProvenance.CODEC.listOf(0, MAX_PROVENANCE).optionalFieldOf("provenance", List.of())
                    .forGetter(RpgPlayerState::provenance)
    ).apply(instance, RpgPlayerState::new)).validate(RpgPlayerState::validate);

    public static final RpgPlayerState EMPTY = new RpgPlayerState(
            Map.of(), Map.of(), Optional.empty(), List.of(), Map.of(), List.of());

    public RpgPlayerState {
        activityXp = Map.copyOf(activityXp);
        careers = Map.copyOf(careers);
        activeCareer = activeCareer == null ? Optional.empty() : activeCareer;
        activeSkillSlots = List.copyOf(activeSkillSlots);
        cooldowns = Map.copyOf(cooldowns);
        provenance = List.copyOf(provenance);
    }

    private static DataResult<RpgPlayerState> validate(RpgPlayerState state) {
        if (state.activityXp().size() > MAX_ACTIVITIES || state.careers().size() > MAX_CAREERS
                || state.cooldowns().size() > MAX_COOLDOWNS || state.provenance().size() > MAX_PROVENANCE
                || state.activeSkillSlots().size() > MAX_ACTIVE_SKILL_SLOTS) {
            return DataResult.error(() -> "RPG player state exceeds a collection limit");
        }
        if (state.activeCareer().isPresent() && !state.careers().containsKey(state.activeCareer().orElseThrow())) {
            return DataResult.error(() -> "Active career is missing from career progress");
        }
        Set<Identifier> slots = java.util.HashSet.newHashSet(state.activeSkillSlots().size());
        if (!state.activeSkillSlots().stream().allMatch(slots::add)) {
            return DataResult.error(() -> "Active skill slots contain a duplicate skill");
        }
        for (Map.Entry<Identifier, Long> entry : state.activityXp().entrySet()) {
            if (entry.getValue() < 0 || entry.getValue() > MAX_XP) {
                return DataResult.error(() -> "Activity XP is out of bounds");
            }
        }
        for (Map.Entry<Identifier, Long> entry : state.cooldowns().entrySet()) {
            if (entry.getValue() < 0) {
                return DataResult.error(() -> "Skill cooldown is out of bounds");
            }
        }
        for (ProgressionProvenance entry : state.provenance()) {
            if (entry.amount() < 0 || entry.amount() > MAX_XP || entry.timestamp() < 0) {
                return DataResult.error(() -> "Progression provenance is out of bounds");
            }
        }
        return DataResult.success(state);
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
            if (progress.experience() < 0 || progress.experience() > MAX_XP
                    || progress.rank() < 0 || progress.rank() > MAX_RANK
                    || progress.skillPoints() < 0 || progress.skillPoints() > MAX_SKILL_POINTS
                    || progress.learnedSkills().size() > MAX_SKILLS) {
                return DataResult.error(() -> "Career progress is out of bounds");
            }
            return DataResult.success(progress);
        }
    }

    public record ProgressionProvenance(
            Kind kind, Identifier target, long amount, long timestamp, String source) {
        public static final Codec<ProgressionProvenance> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Kind.CODEC.fieldOf("kind").forGetter(ProgressionProvenance::kind),
                Identifier.CODEC.fieldOf("target").forGetter(ProgressionProvenance::target),
                XP_CODEC.fieldOf("amount").forGetter(ProgressionProvenance::amount),
                TICK_CODEC.fieldOf("timestamp").forGetter(ProgressionProvenance::timestamp),
                Codec.string(1, 160).fieldOf("source").forGetter(ProgressionProvenance::source)
        ).apply(instance, ProgressionProvenance::new));

        public enum Kind implements net.minecraft.util.StringRepresentable {
            ACTIVITY_XP("activity_xp"), CAREER_XP("career_xp"), SKILL_UNLOCK("skill_unlock"), CAREER_PROMOTION("career_promotion");

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
