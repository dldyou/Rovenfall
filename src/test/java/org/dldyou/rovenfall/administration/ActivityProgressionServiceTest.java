package org.dldyou.rovenfall.administration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.serialization.JsonOps;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import org.dldyou.rovenfall.activities.ActivityEvidence;
import org.dldyou.rovenfall.activities.ActivityKind;
import org.dldyou.rovenfall.activities.ActivityObservation;
import org.dldyou.rovenfall.activities.ActivityProgress;
import org.dldyou.rovenfall.activities.ActivityProvenance;
import org.dldyou.rovenfall.activities.ActivityRewardDefinition;
import org.dldyou.rovenfall.activities.ActivityRewardReloadListener.ResolvedReward;
import org.dldyou.rovenfall.activities.ActivityState;
import org.dldyou.rovenfall.activities.ActivityTrack;
import org.dldyou.rovenfall.careers.CareerCatalog;
import org.dldyou.rovenfall.careers.CareerDefinition;
import org.junit.jupiter.api.Test;

final class ActivityProgressionServiceTest {
    private static final Identifier PLAINS = Identifier.withDefaultNamespace("plains");
    private static final Identifier FOREST = Identifier.withDefaultNamespace("forest");
    private static final Identifier DESERT = Identifier.withDefaultNamespace("desert");

    @Test
    void firstDiscoveryAwardsOnceAndPersistsWithoutDuplicateMutation() {
        PlatformSavedData state = new PlatformSavedData();
        UUID playerId = UUID.randomUUID();
        UUID evidenceId = UUID.randomUUID();
        var observation = discovery(evidenceId, playerId, PLAINS, 10_000);
        var reward = reward(PLAINS, 25, 25, 100);

        var first = ActivityProgressionService.award(state, observation, reward);
        assertEquals(ActivityProgressionService.Status.SUCCESS, first.status());
        assertEquals(25, first.awardedExperience());
        assertEquals(25, state.activityExperience(playerId, ActivityTrack.EXPLORATION));
        assertEquals(1, state.activityEvidenceCount());
        assertEquals(0, state.auditCount());

        var duplicate = ActivityProgressionService.award(state, observation, reward);
        assertEquals(ActivityProgressionService.Status.DUPLICATE_EVIDENCE, duplicate.status());
        assertEquals(0, duplicate.awardedExperience());
        assertEquals(25, state.activityExperience(playerId, ActivityTrack.EXPLORATION));
        assertEquals(1, state.activityEvidenceCount());
        assertEquals(0, state.auditCount());

        PlatformSavedData decoded = roundTrip(state);
        assertEquals(25, decoded.activityExperience(playerId, ActivityTrack.EXPLORATION));
        assertTrue(decoded.activityEvidence(evidenceId).isPresent());
        assertTrue(decoded.hasActivityDiscovery(playerId, observation.discoveryKey()));

        var secondId = discovery(UUID.randomUUID(), playerId, PLAINS, 10_001);
        assertEquals(ActivityProgressionService.Status.ALREADY_DISCOVERED,
                ActivityProgressionService.award(decoded, secondId, reward).status());
        assertEquals(25, decoded.activityExperience(playerId, ActivityTrack.EXPLORATION));
        assertEquals(1, decoded.activityEvidenceCount());
    }

    @Test
    void evidenceIdConflictIsDeniedAndAuditedWithoutMutation() {
        PlatformSavedData state = new PlatformSavedData();
        UUID playerId = UUID.randomUUID();
        UUID evidenceId = UUID.randomUUID();
        var reward = reward(PLAINS, 25, 25, 100);
        var original = discovery(evidenceId, playerId, PLAINS, 20_000);
        assertTrue(ActivityProgressionService.award(state, original, reward).awarded());
        var conflicting = new ActivityObservation(
                evidenceId, 20_000, playerId, ActivityTrack.EXPLORATION,
                ActivityKind.EXPLORATION_DISCOVERY, Level.OVERWORLD,
                1, 0, PLAINS, "biome:" + PLAINS, 1, ActivityProvenance.explorationDiscovery());

        var result = ActivityProgressionService.award(state, conflicting, reward);
        assertEquals(ActivityProgressionService.Status.EVIDENCE_ID_CONFLICT, result.status());
        assertTrue(result.auditRecorded());
        assertEquals(25, state.activityExperience(playerId, ActivityTrack.EXPLORATION));
        assertEquals(1, state.activityEvidenceCount());
        assertEquals(1, state.auditCount());
    }

    @Test
    void targetAndPlayerWindowsCapAwardsWithoutPunishment() {
        PlatformSavedData targetState = new PlatformSavedData();
        UUID targetPlayer = UUID.randomUUID();
        var combatReward = combatReward(10, 15, 100);
        assertEquals(10, ActivityProgressionService.award(
                targetState, combat(UUID.randomUUID(), targetPlayer, 30_000, "hit:a"), combatReward)
                .awardedExperience());
        var partialTarget = ActivityProgressionService.award(
                targetState, combat(UUID.randomUUID(), targetPlayer, 30_001, "hit:b"), combatReward);
        assertEquals(ActivityProgressionService.Status.CAPPED_SUCCESS, partialTarget.status());
        assertEquals(5, partialTarget.awardedExperience());
        var targetLimited = ActivityProgressionService.award(
                targetState, combat(UUID.randomUUID(), targetPlayer, 30_002, "hit:c"), combatReward);
        assertEquals(ActivityProgressionService.Status.RATE_LIMITED, targetLimited.status());
        assertTrue(targetLimited.auditRecorded());
        assertEquals(15, targetState.activityExperience(targetPlayer, ActivityTrack.COMBAT));

        PlatformSavedData playerState = new PlatformSavedData();
        UUID playerId = UUID.randomUUID();
        assertEquals(25, ActivityProgressionService.award(
                playerState, discovery(UUID.randomUUID(), playerId, PLAINS, 40_000),
                reward(PLAINS, 25, 25, 40)).awardedExperience());
        var partialPlayer = ActivityProgressionService.award(
                playerState, discovery(UUID.randomUUID(), playerId, FOREST, 40_001),
                reward(FOREST, 25, 25, 40));
        assertEquals(ActivityProgressionService.Status.CAPPED_SUCCESS, partialPlayer.status());
        assertEquals(15, partialPlayer.awardedExperience());
        var playerLimited = ActivityProgressionService.award(
                playerState, discovery(UUID.randomUUID(), playerId, DESERT, 40_002),
                reward(DESERT, 25, 25, 40));
        assertEquals(ActivityProgressionService.Status.RATE_LIMITED, playerLimited.status());
        assertEquals(40, playerState.activityExperience(playerId, ActivityTrack.EXPLORATION));
        assertEquals(2, playerState.activityEvidenceCount());
    }

    @Test
    void contributionScalesRequestedExperienceBeforeWindowCaps() {
        PlatformSavedData state = new PlatformSavedData();
        UUID playerId = UUID.randomUUID();
        var base = combat(UUID.randomUUID(), playerId, 45_000, "batch");
        var batch = new ActivityObservation(
                base.evidenceId(), base.observedAtEpochMillis(), base.playerId(), base.track(), base.kind(),
                base.dimension(), base.chunkX(), base.chunkZ(), base.targetId(), base.subjectKey(), 3,
                base.provenance());

        var result = ActivityProgressionService.award(state, batch, combatReward(10, 25, 100));
        assertEquals(ActivityProgressionService.Status.CAPPED_SUCCESS, result.status());
        assertEquals(25, result.awardedExperience());
        var evidence = state.activityEvidence(batch.evidenceId()).orElseThrow();
        assertEquals(30, evidence.requestedExperience());
        assertEquals(25, evidence.awardedExperience());
    }

    @Test
    void eligibleActivityAtomicallyAdvancesOnlyTheActiveCareersConfiguredTracks() {
        Identifier careerId = id("scout");
        CareerCatalog catalog = CareerCatalog.create(Map.of(careerId, new CareerDefinition(
                "career.rovenfall.scout",
                1,
                List.of(),
                Map.of(),
                List.of(ActivityTrack.EXPLORATION),
                List.of(0L, 20L, 50L),
                0,
                Map.of(),
                0,
                0))).getOrThrow();
        PlatformSavedData state = new PlatformSavedData();
        UUID playerId = UUID.randomUUID();
        assertEquals(CareerPromotionService.Status.SUCCESS, CareerPromotionService.promote(
                state, catalog, playerId, careerId, Map.of(), 1_000, UUID.randomUUID()).status());

        UUID explorationEvidenceId = UUID.randomUUID();
        var base = discovery(explorationEvidenceId, playerId, PLAINS, 10_000);
        var quantity = new ActivityObservation(
                base.evidenceId(), base.observedAtEpochMillis(), base.playerId(), base.track(), base.kind(),
                base.dimension(), base.chunkX(), base.chunkZ(), base.targetId(), base.subjectKey(), 3,
                base.provenance());
        var exploration = ActivityProgressionService.award(
                state, quantity, reward(PLAINS, 10, 25, 100), catalog);

        assertEquals(ActivityProgressionService.Status.CAPPED_SUCCESS, exploration.status());
        assertEquals(25, exploration.awardedExperience());
        assertEquals(Optional.of(careerId), exploration.careerId());
        assertEquals(25, exploration.awardedCareerExperience());
        assertEquals(25, exploration.totalCareerExperience());
        assertEquals(25, state.playerCareerState(playerId).experience(careerId));
        var explorationEvidence = state.activityEvidence(explorationEvidenceId).orElseThrow();
        assertEquals(careerId, explorationEvidence.careerAward().orElseThrow().careerId());
        assertEquals(25, explorationEvidence.careerAward().orElseThrow().awardedExperience());

        assertEquals(ActivityProgressionService.Status.DUPLICATE_EVIDENCE,
                ActivityProgressionService.award(
                        state, quantity, reward(PLAINS, 10, 25, 100), catalog).status());
        assertEquals(25, state.playerCareerState(playerId).experience(careerId));

        UUID combatEvidenceId = UUID.randomUUID();
        var combat = ActivityProgressionService.award(
                state,
                combat(combatEvidenceId, playerId, 11_000, "unconfigured-track"),
                combatReward(10, 100, 100),
                catalog);
        assertEquals(ActivityProgressionService.Status.SUCCESS, combat.status());
        assertTrue(combat.careerId().isEmpty());
        assertEquals(0, combat.awardedCareerExperience());
        assertEquals(25, state.playerCareerState(playerId).experience(careerId));
        assertTrue(state.activityEvidence(combatEvidenceId).orElseThrow().careerAward().isEmpty());

        PlatformSavedData restored = roundTrip(state);
        assertEquals(25, restored.playerCareerState(playerId).experience(careerId));
        assertEquals(careerId,
                restored.activityEvidence(explorationEvidenceId).orElseThrow()
                        .careerAward().orElseThrow().careerId());
    }

    @Test
    void malformedProvenanceAndRewardMismatchAreRejected() {
        UUID playerId = UUID.randomUUID();
        var invalid = new ActivityObservation(
                UUID.randomUUID(), 50_000, playerId, ActivityTrack.EXPLORATION,
                ActivityKind.EXPLORATION_DISCOVERY, Level.OVERWORLD,
                0, 0, PLAINS, "biome:" + PLAINS, 1,
                new ActivityProvenance(false, false, false));
        PlatformSavedData invalidState = new PlatformSavedData();
        assertEquals(ActivityProgressionService.Status.INVALID_OBSERVATION,
                ActivityProgressionService.award(invalidState, invalid, reward(PLAINS, 25, 25, 100)).status());
        assertEquals(0, invalidState.activityEvidenceCount());
        assertEquals(0, invalidState.activityExperience(playerId, ActivityTrack.EXPLORATION));

        PlatformSavedData mismatchState = new PlatformSavedData();
        var valid = discovery(UUID.randomUUID(), playerId, PLAINS, 51_500);
        assertEquals(ActivityProgressionService.Status.REWARD_MISMATCH,
                ActivityProgressionService.award(mismatchState, valid, reward(FOREST, 25, 25, 100)).status());
        assertEquals(0, mismatchState.activityEvidenceCount());
        assertEquals(0, mismatchState.activityExperience(playerId, ActivityTrack.EXPLORATION));
    }

    @Test
    void schemaEightDefaultsActivityStateAndFutureSchemaIsReadOnly() {
        CompoundTag schemaEight = (CompoundTag) PlatformSavedData.CODEC.encodeStart(
                NbtOps.INSTANCE, new PlatformSavedData()).getOrThrow();
        schemaEight.putInt("schema_version", 8);
        schemaEight.remove("activity_state");
        PlatformSavedData migrated = PlatformSavedData.CODEC.parse(NbtOps.INSTANCE, schemaEight).getOrThrow();
        assertEquals(PlatformSavedData.CURRENT_SCHEMA_VERSION, migrated.schemaVersion());
        assertTrue(migrated.isWritable());
        assertEquals(0, migrated.activityEvidenceCount());

        CompoundTag future = (CompoundTag) PlatformSavedData.CODEC.encodeStart(
                NbtOps.INSTANCE, new PlatformSavedData()).getOrThrow();
        future.putInt("schema_version", PlatformSavedData.CURRENT_SCHEMA_VERSION + 1);
        PlatformSavedData readOnly = PlatformSavedData.CODEC.parse(NbtOps.INSTANCE, future).getOrThrow();
        UUID playerId = UUID.randomUUID();
        var result = ActivityProgressionService.award(
                readOnly, discovery(UUID.randomUUID(), playerId, PLAINS, 60_000),
                reward(PLAINS, 25, 25, 100));
        assertEquals(ActivityProgressionService.Status.READ_ONLY_SCHEMA, result.status());
        assertEquals(0, readOnly.activityEvidenceCount());
        assertEquals(0, readOnly.activityExperience(playerId, ActivityTrack.EXPLORATION));
    }

    @Test
    void codecsRejectDuplicateDiscoveriesAndEvidenceKeyMismatch() {
        ActivityProgress progress = ActivityProgress.empty().award(
                ActivityTrack.EXPLORATION, 25, "exploration_discovery:biome:minecraft:plains");
        var progressJson = ActivityProgress.CODEC.encodeStart(JsonOps.INSTANCE, progress)
                .getOrThrow().getAsJsonObject();
        progressJson.getAsJsonArray("discoveries").add(
                progressJson.getAsJsonArray("discoveries").get(0).deepCopy());
        assertTrue(ActivityProgress.CODEC.parse(JsonOps.INSTANCE, progressJson).error().isPresent());

        UUID playerId = UUID.randomUUID();
        var observation = discovery(UUID.randomUUID(), playerId, PLAINS, 70_000);
        ActivityEvidence evidence = ActivityEvidence.recorded(observation, id("plains"), 25, 25);
        ActivityState activityState = new ActivityState(
                Map.of(playerId, progress), Map.of(evidence.evidenceId(), evidence));
        var stateJson = ActivityState.CODEC.encodeStart(JsonOps.INSTANCE, activityState)
                .getOrThrow().getAsJsonObject();
        var evidenceJson = stateJson.getAsJsonObject("evidence");
        var value = evidenceJson.remove(evidence.evidenceId().toString());
        evidenceJson.add(UUID.randomUUID().toString(), value);
        assertTrue(ActivityState.CODEC.parse(JsonOps.INSTANCE, stateJson).error().isPresent());

        ActivityEvidence impossibleCareerAward = ActivityEvidence.recorded(
                observation,
                id("plains"),
                25,
                25,
                Optional.of(new ActivityEvidence.CareerAward(id("scout"), 26)));
        assertTrue(ActivityEvidence.CODEC.encodeStart(JsonOps.INSTANCE, impossibleCareerAward)
                .error().isPresent());
    }

    @Test
    void snapshotRestoreCarriesActivityProgressAndEvidence() {
        PlatformSavedData snapshot = new PlatformSavedData();
        UUID playerId = UUID.randomUUID();
        UUID evidenceId = UUID.randomUUID();
        long timestamp = 80_000;
        assertTrue(ActivityProgressionService.award(
                snapshot, discovery(evidenceId, playerId, PLAINS, timestamp),
                reward(PLAINS, 25, 25, 100)).awarded());

        PlatformSavedData current = new PlatformSavedData();
        UUID restoreId = UUID.randomUUID();
        var preparation = current.prepareTransactionRestore(snapshot, restoreId, timestamp + 1);
        assertEquals(PlatformSavedData.RestorePreparationStatus.SUCCESS, preparation.status());
        current.commitRestore(snapshot, preparation.evidence().orElseThrow(), new AuditEntry(
                timestamp + 1, UUID.randomUUID(), id("snapshot_restore"), "activity-test",
                Optional.empty(), Optional.empty(), "current", "snapshot", "test", restoreId));

        assertEquals(25, current.activityExperience(playerId, ActivityTrack.EXPLORATION));
        assertTrue(current.activityEvidence(evidenceId).isPresent());
        assertTrue(current.hasActivityDiscovery(
                playerId, "exploration_discovery:biome:minecraft:plains"));
    }

    private static PlatformSavedData roundTrip(PlatformSavedData state) {
        var encoded = PlatformSavedData.CODEC.encodeStart(NbtOps.INSTANCE, state).getOrThrow();
        return PlatformSavedData.CODEC.parse(NbtOps.INSTANCE, encoded).getOrThrow();
    }

    private static ActivityObservation discovery(
            UUID evidenceId, UUID playerId, Identifier biome, long timestamp) {
        return new ActivityObservation(
                evidenceId, timestamp, playerId, ActivityTrack.EXPLORATION,
                ActivityKind.EXPLORATION_DISCOVERY, Level.OVERWORLD,
                0, 0, biome, "biome:" + biome, 1, ActivityProvenance.explorationDiscovery());
    }

    private static ActivityObservation combat(
            UUID evidenceId, UUID playerId, long timestamp, String subject) {
        return new ActivityObservation(
                evidenceId, timestamp, playerId, ActivityTrack.COMBAT,
                ActivityKind.COMBAT_DAMAGE, Level.OVERWORLD,
                0, 0, id("training_dummy"), subject, 1,
                new ActivityProvenance(false, false, false));
    }

    private static ResolvedReward reward(
            Identifier target, long experience, long targetCap, long playerCap) {
        return new ResolvedReward(id("reward_" + target.getPath()), new ActivityRewardDefinition(
                ActivityTrack.EXPLORATION,
                ActivityKind.EXPLORATION_DISCOVERY,
                target,
                experience,
                60_000,
                targetCap,
                playerCap));
    }

    private static ResolvedReward combatReward(long experience, long targetCap, long playerCap) {
        return new ResolvedReward(id("combat_reward"), new ActivityRewardDefinition(
                ActivityTrack.COMBAT,
                ActivityKind.COMBAT_DAMAGE,
                id("training_dummy"),
                experience,
                60_000,
                targetCap,
                playerCap));
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("rovenfall", path);
    }
}
