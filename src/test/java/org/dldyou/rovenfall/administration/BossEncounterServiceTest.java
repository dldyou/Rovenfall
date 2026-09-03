package org.dldyou.rovenfall.administration;

import static org.dldyou.rovenfall.PersistenceTestHarness.roundTrip;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.world.level.Level;
import org.dldyou.rovenfall.mobs.ArenaWarden;
import org.dldyou.rovenfall.mobs.BossEncounter;
import org.junit.jupiter.api.Test;

final class BossEncounterServiceTest {
    private static final BlockPos ORIGIN = new BlockPos(120, 72, -40);

    @Test
    void contributionRewardsCooldownProtectionAndRestartAreAuthoritative() {
        PlatformSavedData state = new PlatformSavedData();
        UUID encounterId = id(10);
        var started = BossEncounterService.start(
                state,
                AdministrationService.SYSTEM_ACTOR,
                true,
                WorldCombatService.WILDERNESS_DIMENSION,
                ORIGIN,
                24,
                "unit boss",
                1_000,
                encounterId,
                ignored -> true);
        assertEquals(BossEncounterService.StartStatus.SUCCESS, started.status());
        assertEquals(BossEncounter.Status.INTRO, state.bossEncounter().orElseThrow().status());
        assertTrue(state.isBossArenaProtected(WorldCombatService.WILDERNESS_DIMENSION, ORIGIN));
        assertTrue(state.isBossArenaProtected(new org.dldyou.rovenfall.claims.ClaimKey(
                WorldCombatService.WILDERNESS_DIMENSION, ORIGIN.getX() >> 4, ORIGIN.getZ() >> 4)));
        assertFalse(state.isBossArenaProtected(Level.OVERWORLD, ORIGIN));

        assertTrue(BossEncounterService.activate(state, encounterId, 4_000));
        UUID contributor = id(20);
        UUID lastHitOnly = id(21);
        UUID bossId = state.bossEncounter().orElseThrow().bossId();
        assertTrue(BossEncounterService.recordContribution(
                state, encounterId, bossId, contributor, ORIGIN, 180, 5_000));
        assertTrue(BossEncounterService.recordContribution(
                state, encounterId, bossId, lastHitOnly, ORIGIN, 10, 5_100));
        assertFalse(BossEncounterService.recordContribution(
                state, encounterId, bossId, id(22), ORIGIN.offset(100, 0, 0), 100, 5_200));
        assertTrue(BossEncounterService.observePhase(state, encounterId, 2, 5_300));
        assertTrue(BossEncounterService.observePhase(state, encounterId, 3, 5_400));

        assertTrue(BossEncounterService.beginRewards(state, encounterId, 6_000));
        List<BossEncounterService.RewardResult> rewards = BossEncounterService.settleRewards(state, 6_100);
        assertEquals(2, rewards.size());
        assertEquals(BossEncounterService.RewardStatus.SUCCESS,
                rewards.stream().filter(value -> value.playerId().equals(contributor)).findFirst().orElseThrow().status());
        assertEquals(BossEncounterService.RewardStatus.INELIGIBLE,
                rewards.stream().filter(value -> value.playerId().equals(lastHitOnly)).findFirst().orElseThrow().status());
        assertEquals(EconomyConfig.DEFAULT_INITIAL_BALANCE + BossEncounterService.REWARD_AMOUNT,
                state.economyBalance(contributor).orElseThrow());
        assertTrue(state.economyBalance(lastHitOnly).isEmpty());
        assertEquals(BossEncounter.Status.DEFEATED, state.bossEncounter().orElseThrow().status());
        long readyAt = state.bossState().rewardReadyAt(contributor);
        assertEquals(6_100 + BossEncounterService.REWARD_COOLDOWN_MILLIS, readyAt);

        PlatformSavedData restarted = roundTrip(PlatformSavedData.CODEC, state);
        assertEquals(PlatformSavedData.CURRENT_SCHEMA_VERSION, restarted.schemaVersion());
        assertEquals(BossEncounter.Status.DEFEATED, restarted.bossEncounter().orElseThrow().status());
        assertEquals(readyAt, restarted.bossState().rewardReadyAt(contributor));
        assertEquals(190.0, restarted.bossEncounter().orElseThrow().totalContribution());

        UUID secondEncounter = id(11);
        assertEquals(BossEncounterService.StartStatus.SUCCESS, BossEncounterService.start(
                restarted,
                AdministrationService.SYSTEM_ACTOR,
                true,
                WorldCombatService.WILDERNESS_DIMENSION,
                ORIGIN.offset(64, 0, 0),
                20,
                "cooldown retry",
                7_000,
                secondEncounter,
                ignored -> true).status());
        BossEncounterService.activate(restarted, secondEncounter, 10_000);
        BossEncounter second = restarted.bossEncounter().orElseThrow();
        BossEncounterService.recordContribution(
                restarted, secondEncounter, second.bossId(), contributor, second.origin(), 100, 11_000);
        BossEncounterService.beginRewards(restarted, secondEncounter, 12_000);
        var cooldown = BossEncounterService.settleRewards(restarted, 12_100).getFirst();
        assertEquals(BossEncounterService.RewardStatus.COOLDOWN, cooldown.status());
        assertEquals(EconomyConfig.DEFAULT_INITIAL_BALANCE + BossEncounterService.REWARD_AMOUNT,
                restarted.economyBalance(contributor).orElseThrow());
    }

    @Test
    void deniedAndFailedStartsNeverPartiallyCommit() {
        PlatformSavedData state = new PlatformSavedData();
        UUID viewer = id(30);
        AdministrationService.changeRole(
                state,
                AdministrationService.SYSTEM_ACTOR,
                true,
                viewer,
                AdminRole.VIEWER.getSerializedName(),
                "bootstrap",
                1_000,
                id(31));

        assertEquals(BossEncounterService.StartStatus.UNAUTHORIZED, BossEncounterService.start(
                state, viewer, false, WorldCombatService.WILDERNESS_DIMENSION, ORIGIN, 20,
                "denied", 3_000, id(32), ignored -> true).status());
        assertTrue(state.bossEncounter().isEmpty());
        assertEquals(BossEncounterService.StartStatus.INVALID_REQUEST, BossEncounterService.start(
                state, viewer, true, Level.OVERWORLD, ORIGIN, 20,
                "wrong world", 5_000, id(33), ignored -> true).status());
        assertEquals(BossEncounterService.StartStatus.SPAWN_FAILED, BossEncounterService.start(
                state, viewer, true, WorldCombatService.WILDERNESS_DIMENSION, ORIGIN, 20,
                "spawn failure", 7_000, id(34), ignored -> false).status());
        assertTrue(state.bossEncounter().isEmpty());
        assertFalse(state.hasTransaction(id(34), 7_000));
        assertTrue(state.auditPage(0, 10).entries().stream()
                .anyMatch(entry -> entry.actionType().toString().equals("rovenfall:boss_start_denied")));
    }

    @Test
    void challengeSigilAuthorizesOnlyThePlayerBossEntryPoint() {
        PlatformSavedData state = new PlatformSavedData();
        UUID player = id(35);

        assertEquals(BossEncounterService.StartStatus.UNAUTHORIZED, BossEncounterService.start(
                state, player, false, WorldCombatService.WILDERNESS_DIMENSION, ORIGIN, 24,
                "ordinary command", 1_000, id(36), ignored -> true).status());
        assertTrue(state.bossEncounter().isEmpty());

        var started = BossEncounterService.startPlayerChallenge(
                state,
                player,
                WorldCombatService.WILDERNESS_DIMENSION,
                ORIGIN,
                3_000,
                id(37),
                ignored -> true);
        assertEquals(BossEncounterService.StartStatus.SUCCESS, started.status());
        assertEquals(BossEncounterService.PLAYER_CHALLENGE_RADIUS,
                started.encounter().orElseThrow().radius());
        assertTrue(state.roleOf(player).isEmpty());
        assertTrue(state.auditPage(0, 10).entries().stream().anyMatch(entry ->
                entry.actorId().equals(player)
                        && entry.actionType().toString().equals("rovenfall:boss_started")
                        && entry.reason().equals(BossEncounterService.PLAYER_CHALLENGE_REASON)));
    }

    @Test
    void codecsRejectInvalidSettlementsAndFutureSchemaRemainsReadOnly() {
        BossEncounter invalid = new BossEncounter(
                id(40), id(41), WorldCombatService.WILDERNESS_DIMENSION, ORIGIN, 20,
                BossEncounter.Status.ACTIVE, 1, 1_000, 1_000,
                java.util.Map.of(), java.util.Set.of(id(42)));
        assertTrue(BossEncounter.validate(invalid).error().isPresent());
        assertEquals(1, ArenaWarden.phaseForHealth(400, 400));
        assertEquals(2, ArenaWarden.phaseForHealth(200, 400));
        assertEquals(3, ArenaWarden.phaseForHealth(100, 400));

        CompoundTag future = (CompoundTag) PlatformSavedData.CODEC
                .encodeStart(NbtOps.INSTANCE, new PlatformSavedData()).getOrThrow();
        future.putInt("schema_version", PlatformSavedData.CURRENT_SCHEMA_VERSION + 1);
        PlatformSavedData readOnly = PlatformSavedData.CODEC.parse(NbtOps.INSTANCE, future).getOrThrow();
        assertFalse(readOnly.isWritable());
        assertEquals(BossEncounterService.StartStatus.READ_ONLY_SCHEMA, BossEncounterService.start(
                readOnly,
                AdministrationService.SYSTEM_ACTOR,
                true,
                WorldCombatService.WILDERNESS_DIMENSION,
                ORIGIN,
                20,
                "future",
                1_000,
                id(43),
                ignored -> true).status());

        CompoundTag schemaTwelve = (CompoundTag) PlatformSavedData.CODEC
                .encodeStart(NbtOps.INSTANCE, new PlatformSavedData()).getOrThrow();
        schemaTwelve.putInt("schema_version", 12);
        schemaTwelve.remove("boss_state");
        PlatformSavedData migrated = PlatformSavedData.CODEC.parse(NbtOps.INSTANCE, schemaTwelve).getOrThrow();
        assertEquals(PlatformSavedData.CURRENT_SCHEMA_VERSION, migrated.schemaVersion());
        assertTrue(migrated.isWritable());
        assertTrue(migrated.bossEncounter().isEmpty());
    }

    private static UUID id(long value) {
        return new UUID(0, value);
    }
}
