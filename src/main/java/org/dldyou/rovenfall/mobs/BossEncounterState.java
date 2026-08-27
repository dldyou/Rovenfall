package org.dldyou.rovenfall.mobs;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.Level;
import org.dldyou.rovenfall.administration.AdministrationService;
import org.dldyou.rovenfall.claims.ClaimKey;
import org.dldyou.rovenfall.world.ProtectedRegion;

public record BossEncounterState(
        UUID encounterId,
        Identifier bossId,
        UUID definitionFingerprint,
        UUID entityId,
        ResourceKey<Level> dimension,
        BlockPos center,
        ProtectedRegion reservation,
        long startedAtEpochMillis,
        long lastParticipantAtEpochMillis,
        int phaseIndex,
        Stage stage,
        Optional<Identifier> patternId,
        long stageDeadlineGameTime,
        long nextPatternGameTime,
        int sequence,
        Map<UUID, Long> contributions,
        Optional<RewardPlan> rewardPlan) {
    public static final int MAX_CONTRIBUTORS = 1_024;
    public static final int MAX_SEQUENCE = 1_000_000_000;
    private static final UUID ZERO_UUID = new UUID(0L, 0L);
    private static final long MAX_CONTRIBUTION = MobContentSnapshot.MAX_REWARD;

    private static final Codec<Map<UUID, Long>> CONTRIBUTIONS_CODEC =
            Contribution.CODEC.listOf(0, MAX_CONTRIBUTORS)
                    .flatXmap(BossEncounterState::contributionsFromEntries,
                            BossEncounterState::contributionEntries);

    private static final MapCodec<PersistedState> PERSISTED_STATE_CODEC =
            RecordCodecBuilder.mapCodec(instance -> instance.group(
                    UUIDUtil.STRING_CODEC.fieldOf("encounter_id").forGetter(PersistedState::encounterId),
                    Identifier.CODEC.fieldOf("boss_id").forGetter(PersistedState::bossId),
                    UUIDUtil.STRING_CODEC.fieldOf("definition_fingerprint")
                            .forGetter(PersistedState::definitionFingerprint),
                    UUIDUtil.STRING_CODEC.fieldOf("entity_id").forGetter(PersistedState::entityId),
                    Level.RESOURCE_KEY_CODEC.fieldOf("dimension").forGetter(PersistedState::dimension),
                    BlockPos.CODEC.fieldOf("center").forGetter(PersistedState::center),
                    ProtectedRegion.CODEC.fieldOf("reservation").forGetter(PersistedState::reservation),
                    Codec.LONG.fieldOf("started_at").forGetter(PersistedState::startedAtEpochMillis),
                    Codec.LONG.fieldOf("last_participant_at")
                            .forGetter(PersistedState::lastParticipantAtEpochMillis),
                    Codec.INT.fieldOf("phase_index").forGetter(PersistedState::phaseIndex),
                    Stage.CODEC.fieldOf("stage").forGetter(PersistedState::stage),
                    Identifier.CODEC.optionalFieldOf("pattern_id").forGetter(PersistedState::patternId),
                    Codec.LONG.fieldOf("stage_deadline").forGetter(PersistedState::stageDeadlineGameTime),
                    Codec.LONG.fieldOf("next_pattern").forGetter(PersistedState::nextPatternGameTime),
                    Codec.INT.fieldOf("sequence").forGetter(PersistedState::sequence),
                    CONTRIBUTIONS_CODEC.optionalFieldOf("contributions", Map.of())
                            .forGetter(PersistedState::contributions)
            ).apply(instance, PersistedState::new));

    public static final Codec<BossEncounterState> CODEC = RecordCodecBuilder.<BossEncounterState>create(
            instance -> instance.group(
            PERSISTED_STATE_CODEC.forGetter(BossEncounterState::persistedState),
            RewardPlan.CODEC.optionalFieldOf("reward_plan").forGetter(BossEncounterState::rewardPlan)
            ).apply(instance, PersistedState::toEncounter)).validate(state -> state.isValid()
                    ? DataResult.success(state)
                    : DataResult.error(() -> "Invalid boss encounter state"));

    public BossEncounterState {
        patternId = patternId == null ? Optional.empty() : patternId;
        contributions = contributions == null ? Map.of() : Map.copyOf(contributions);
        rewardPlan = rewardPlan == null ? Optional.empty() : rewardPlan;
    }

    public static BossEncounterState start(
            UUID encounterId,
            Identifier bossId,
            UUID definitionFingerprint,
            UUID entityId,
            ResourceKey<Level> dimension,
            BlockPos center,
            ProtectedRegion reservation,
            long timestampEpochMillis,
            long gameTime) {
        return new BossEncounterState(
                encounterId, bossId, definitionFingerprint, entityId, dimension, center.immutable(), reservation,
                timestampEpochMillis, timestampEpochMillis, 0, Stage.IDLE, Optional.empty(), 0,
                gameTime + 20, 0, Map.of(), Optional.empty());
    }

    public boolean isValid() {
        boolean stageValid = stage == Stage.IDLE || stage == Stage.REWARD_PENDING
                ? patternId.isEmpty() : patternId.isPresent();
        boolean rewardValid = stage == Stage.REWARD_PENDING
                ? rewardPlan.filter(plan -> plan.matches(bossId, definitionFingerprint)).isPresent()
                : rewardPlan.isEmpty();
        return encounterId != null && !ZERO_UUID.equals(encounterId)
                && bossId != null && definitionFingerprint != null && !ZERO_UUID.equals(definitionFingerprint)
                && entityId != null && !ZERO_UUID.equals(entityId)
                && dimension != null && center != null && reservation != null
                && AdministrationService.SYSTEM_ACTOR.equals(reservation.administratorId())
                && dimension.equals(reservation.dimension())
                && reservation.contains(ClaimKey.at(dimension, center))
                && startedAtEpochMillis >= 0 && lastParticipantAtEpochMillis >= startedAtEpochMillis
                && phaseIndex >= 0 && phaseIndex < MobContentCatalog.MAX_PHASES
                && stage != null && stageValid && rewardValid
                && stageDeadlineGameTime >= 0 && nextPatternGameTime >= 0
                && sequence >= 0 && sequence <= MAX_SEQUENCE
                && contributions.size() <= MAX_CONTRIBUTORS
                && contributions.entrySet().stream().allMatch(entry ->
                        entry.getKey() != null && !ZERO_UUID.equals(entry.getKey())
                                && entry.getValue() != null && entry.getValue() > 0
                                && entry.getValue() <= MAX_CONTRIBUTION);
    }

    public BossEncounterState touch(long timestampEpochMillis) {
        if (timestampEpochMillis < lastParticipantAtEpochMillis) {
            return this;
        }
        return copy(phaseIndex, stage, patternId, stageDeadlineGameTime, nextPatternGameTime,
                sequence, contributions, timestampEpochMillis);
    }

    public BossEncounterState contribute(
            UUID playerId, long points, int maximumContributors, long timestampEpochMillis) {
        if (playerId == null || ZERO_UUID.equals(playerId) || points <= 0
                || maximumContributors < 1 || maximumContributors > MAX_CONTRIBUTORS) {
            return this;
        }
        Long previous = contributions.get(playerId);
        if (previous == null && contributions.size() >= maximumContributors) {
            return touch(timestampEpochMillis);
        }
        long retained = previous == null ? 0 : previous;
        long updated = retained > MAX_CONTRIBUTION - points ? MAX_CONTRIBUTION : retained + points;
        Map<UUID, Long> result = new LinkedHashMap<>(contributions);
        result.put(playerId, updated);
        return copy(phaseIndex, stage, patternId, stageDeadlineGameTime, nextPatternGameTime,
                sequence, Map.copyOf(result), Math.max(timestampEpochMillis, lastParticipantAtEpochMillis));
    }

    public BossEncounterState enterPhase(int newPhaseIndex, long gameTime) {
        if (stage == Stage.REWARD_PENDING
                || newPhaseIndex <= phaseIndex || newPhaseIndex >= MobContentCatalog.MAX_PHASES) {
            return this;
        }
        return copy(newPhaseIndex, Stage.IDLE, Optional.empty(), 0, gameTime + 20,
                sequence, contributions, lastParticipantAtEpochMillis);
    }

    public BossEncounterState beginTelegraph(Identifier selectedPattern, long deadlineGameTime) {
        if (stage != Stage.IDLE || selectedPattern == null || deadlineGameTime < 0) {
            return this;
        }
        return copy(phaseIndex, Stage.TELEGRAPH, Optional.of(selectedPattern), deadlineGameTime, 0,
                sequence, contributions, lastParticipantAtEpochMillis);
    }

    public BossEncounterState beginExecution(long deadlineGameTime) {
        if (stage != Stage.TELEGRAPH || patternId.isEmpty() || deadlineGameTime < 0) {
            return this;
        }
        return copy(phaseIndex, Stage.EXECUTING, patternId, deadlineGameTime, 0,
                sequence, contributions, lastParticipantAtEpochMillis);
    }

    public BossEncounterState finishPattern(long nextPatternAtGameTime) {
        if (stage != Stage.EXECUTING || nextPatternAtGameTime < 0 || sequence >= MAX_SEQUENCE) {
            return this;
        }
        return copy(phaseIndex, Stage.IDLE, Optional.empty(), 0, nextPatternAtGameTime,
                sequence + 1, contributions, lastParticipantAtEpochMillis);
    }

    public BossEncounterState markRewardPending(RewardPlan plan) {
        if (plan == null || !plan.matches(bossId, definitionFingerprint)) {
            return this;
        }
        if (stage == Stage.REWARD_PENDING) {
            return this;
        }
        return new BossEncounterState(
                encounterId, bossId, definitionFingerprint, entityId, dimension, center, reservation,
                startedAtEpochMillis, lastParticipantAtEpochMillis, phaseIndex, Stage.REWARD_PENDING,
                Optional.empty(), 0, 0, sequence, contributions, Optional.of(plan));
    }

    private BossEncounterState copy(
            int nextPhase,
            Stage nextStage,
            Optional<Identifier> nextPattern,
            long nextDeadline,
            long nextPatternAt,
            int nextSequence,
            Map<UUID, Long> nextContributions,
            long nextLastParticipant) {
        return new BossEncounterState(
                encounterId, bossId, definitionFingerprint, entityId, dimension, center, reservation,
                startedAtEpochMillis,
                nextLastParticipant, nextPhase, nextStage, nextPattern, nextDeadline,
                nextPatternAt, nextSequence, nextContributions, rewardPlan);
    }

    private PersistedState persistedState() {
        return new PersistedState(
                encounterId, bossId, definitionFingerprint, entityId, dimension, center, reservation,
                startedAtEpochMillis, lastParticipantAtEpochMillis, phaseIndex, stage, patternId,
                stageDeadlineGameTime, nextPatternGameTime, sequence, contributions);
    }

    private static DataResult<Map<UUID, Long>> contributionsFromEntries(List<Contribution> entries) {
        Map<UUID, Long> result = new LinkedHashMap<>();
        for (Contribution entry : entries) {
            if (entry == null || entry.playerId() == null || ZERO_UUID.equals(entry.playerId())
                    || entry.points() <= 0 || entry.points() > MAX_CONTRIBUTION
                    || result.putIfAbsent(entry.playerId(), entry.points()) != null) {
                return DataResult.error(() -> "Invalid or duplicate boss contribution");
            }
        }
        return DataResult.success(Map.copyOf(result));
    }

    private static DataResult<List<Contribution>> contributionEntries(Map<UUID, Long> contributions) {
        return DataResult.success(contributions.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new Contribution(entry.getKey(), entry.getValue()))
                .toList());
    }

    public record RewardPlan(
            MobContentCatalog.BossDefinition boss,
            MobContentCatalog.ArenaPolicy arena,
            MobContentCatalog.MobDefinition mob,
            MobContentCatalog.ContributionRule contribution,
            MobContentCatalog.LootDefinition loot) {
        public static final Codec<RewardPlan> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                MobContentCatalog.BossDefinition.CODEC.fieldOf("boss").forGetter(RewardPlan::boss),
                MobContentCatalog.ArenaPolicy.CODEC.fieldOf("arena").forGetter(RewardPlan::arena),
                MobContentCatalog.MobDefinition.CODEC.fieldOf("mob").forGetter(RewardPlan::mob),
                MobContentCatalog.ContributionRule.CODEC.fieldOf("contribution").forGetter(RewardPlan::contribution),
                MobContentCatalog.LootDefinition.CODEC.fieldOf("loot").forGetter(RewardPlan::loot)
        ).apply(instance, RewardPlan::new));

        boolean matches(Identifier encounterBossId, UUID fingerprint) {
            return boss != null && arena != null && mob != null && contribution != null && loot != null
                    && boss.id().equals(encounterBossId)
                    && boss.mob().equals(mob.id())
                    && boss.arena().equals(arena.id())
                    && boss.contributionRule().equals(contribution.id())
                    && boss.loot().equals(loot.id())
                    && BossEncounterRuntime.definitionFingerprint(
                            boss, arena, mob, contribution, loot).equals(fingerprint);
        }
    }

    private record PersistedState(
            UUID encounterId,
            Identifier bossId,
            UUID definitionFingerprint,
            UUID entityId,
            ResourceKey<Level> dimension,
            BlockPos center,
            ProtectedRegion reservation,
            long startedAtEpochMillis,
            long lastParticipantAtEpochMillis,
            int phaseIndex,
            Stage stage,
            Optional<Identifier> patternId,
            long stageDeadlineGameTime,
            long nextPatternGameTime,
            int sequence,
            Map<UUID, Long> contributions) {
        private BossEncounterState toEncounter(Optional<RewardPlan> rewardPlan) {
            return new BossEncounterState(
                    encounterId, bossId, definitionFingerprint, entityId, dimension, center, reservation,
                    startedAtEpochMillis, lastParticipantAtEpochMillis, phaseIndex, stage, patternId,
                    stageDeadlineGameTime, nextPatternGameTime, sequence, contributions, rewardPlan);
        }
    }

    public enum Stage implements StringRepresentable {
        IDLE("idle"),
        TELEGRAPH("telegraph"),
        EXECUTING("executing"),
        REWARD_PENDING("reward_pending");

        public static final Codec<Stage> CODEC = StringRepresentable.fromEnum(Stage::values);
        private final String serializedName;

        Stage(String serializedName) {
            this.serializedName = serializedName;
        }

        @Override
        public String getSerializedName() {
            return serializedName;
        }
    }

    private record Contribution(UUID playerId, long points) {
        private static final Codec<Contribution> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                UUIDUtil.STRING_CODEC.fieldOf("player").forGetter(Contribution::playerId),
                Codec.LONG.fieldOf("points").forGetter(Contribution::points)
        ).apply(instance, Contribution::new));
    }
}
