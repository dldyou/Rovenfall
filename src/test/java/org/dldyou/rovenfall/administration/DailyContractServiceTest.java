package org.dldyou.rovenfall.administration;

import static org.dldyou.rovenfall.PersistenceTestHarness.roundTrip;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import org.dldyou.rovenfall.activities.ActivityKind;
import org.dldyou.rovenfall.activities.ActivityObservation;
import org.dldyou.rovenfall.activities.ActivityProvenance;
import org.dldyou.rovenfall.activities.ActivityRewardDefinition;
import org.dldyou.rovenfall.activities.ActivityRewardReloadListener.ResolvedReward;
import org.dldyou.rovenfall.activities.ActivityTrack;
import org.dldyou.rovenfall.activities.DailyContractDefinition;
import org.junit.jupiter.api.Test;

final class DailyContractServiceTest {
    private static final Identifier IRON_RUSH = id("iron_rush");
    private static final Identifier IRON_ORE = Identifier.withDefaultNamespace("iron_ore");
    private static final long DAY = DailyContractService.PERIOD_MILLIS;
    private static final long PERIOD_START = 10 * DAY;

    @Test
    void dailyClaimUsesWildernessEvidenceAndReopensNextPeriod() throws Exception {
        PlatformSavedData state = new PlatformSavedData();
        UUID player = uuid(1);
        DailyContractDefinition definition = contract(48, 80);

        award(state, player, Level.OVERWORLD, PERIOD_START + 1_000, 100, 101);
        assertEquals(DailyContractService.Status.IN_PROGRESS,
                DailyContractService.evaluate(
                        state, player, IRON_RUSH, definition, PERIOD_START + 2_000).status());

        award(state, player, WorldCombatService.WILDERNESS_DIMENSION, PERIOD_START + 3_000, 8, 102);
        var partial = DailyContractService.evaluate(
                state, player, IRON_RUSH, definition, PERIOD_START + 4_000);
        assertEquals(DailyContractService.Status.IN_PROGRESS, partial.status());
        assertEquals(24, partial.progressExperience());

        award(state, player, WorldCombatService.WILDERNESS_DIMENSION, PERIOD_START + 5_000, 8, 103);
        var claimed = DailyContractService.claim(
                state, player, IRON_RUSH, definition, PERIOD_START + 6_000, 0, 1_000);
        assertEquals(DailyContractService.Status.SUCCESS, claimed.status());
        assertEquals(80, claimed.awardedCurrency());
        assertEquals(80, claimed.balance());
        assertTrue(claimed.auditRecorded());
        UUID firstTransaction = claimed.evaluation().transactionId();
        assertEquals(EconomyTransactionReceipt.Kind.AWARD,
                state.economyReceipt(firstTransaction).orElseThrow().kind());
        assertEquals("daily contract rovenfall:iron_rush period " + PERIOD_START,
                state.auditPage(0, 1).entries().getFirst().reason());

        int auditCount = state.auditCount();
        var retry = DailyContractService.claim(
                state, player, IRON_RUSH, contract(48, 100), PERIOD_START + 7_000, 0, 1_000);
        assertEquals(DailyContractService.Status.ALREADY_CLAIMED, retry.status());
        assertEquals(80, retry.balance());
        assertFalse(retry.auditRecorded());
        assertEquals(auditCount, state.auditCount());

        long nextPeriod = PERIOD_START + DAY;
        var reopened = DailyContractService.evaluate(
                state, player, IRON_RUSH, definition, nextPeriod + 1_000);
        assertEquals(DailyContractService.Status.IN_PROGRESS, reopened.status());
        assertEquals(0, reopened.progressExperience());
        assertNotEquals(firstTransaction, reopened.transactionId());
        award(state, player, WorldCombatService.WILDERNESS_DIMENSION, nextPeriod + 2_000, 16, 104);
        assertEquals(DailyContractService.Status.SUCCESS, DailyContractService.claim(
                state, player, IRON_RUSH, definition, nextPeriod + 3_000, 0, 1_000).status());
        assertEquals(160, state.economyBalance(player).orElseThrow());

        PlatformSavedData restored = roundTrip(PlatformSavedData.CODEC, state);
        assertEquals(DailyContractService.Status.ALREADY_CLAIMED,
                DailyContractService.evaluate(
                        restored, player, IRON_RUSH, definition, nextPeriod + 4_000).status());
        assertEquals(160, restored.economyBalance(player).orElseThrow());
    }

    @Test
    void incompleteAndFailedRewardsDoNotPartiallyCommit() {
        UUID player = uuid(2);
        DailyContractDefinition definition = contract(48, 80);
        PlatformSavedData incompleteState = new PlatformSavedData();

        var incomplete = DailyContractService.claim(
                incompleteState, player, IRON_RUSH, definition, PERIOD_START + 1_000, 0, 1_000);
        assertEquals(DailyContractService.Status.IN_PROGRESS, incomplete.status());
        assertTrue(incompleteState.economyBalance(player).isEmpty());
        assertTrue(incompleteState.economyReceipt(incomplete.evaluation().transactionId()).isEmpty());
        assertEquals(0, incompleteState.auditCount());

        PlatformSavedData cappedState = new PlatformSavedData();
        award(cappedState, player, WorldCombatService.WILDERNESS_DIMENSION, PERIOD_START + 2_000, 16, 201);
        assertEquals(EconomyService.TransactionStatus.SUCCESS, EconomyService.award(
                cappedState, player, 950, "seed", PERIOD_START + 3_000, uuid(202), 0, 1_000).status());
        int auditCount = cappedState.auditCount();
        var capped = DailyContractService.claim(
                cappedState, player, IRON_RUSH, definition, PERIOD_START + 4_000, 0, 1_000);
        assertEquals(DailyContractService.Status.REWARD_FAILED, capped.status());
        assertEquals(EconomyService.TransactionStatus.MAXIMUM_EXCEEDED,
                capped.economyStatus().orElseThrow());
        assertEquals(950, cappedState.economyBalance(player).orElseThrow());
        assertTrue(cappedState.economyReceipt(capped.evaluation().transactionId()).isEmpty());
        assertEquals(auditCount + 1, cappedState.auditCount());
        assertTrue(capped.auditRecorded());
    }

    @Test
    void deterministicReceiptConflictIsDeniedAndAudited() {
        PlatformSavedData state = new PlatformSavedData();
        UUID player = uuid(3);
        UUID otherPlayer = uuid(4);
        award(state, player, WorldCombatService.WILDERNESS_DIMENSION, PERIOD_START + 1_000, 16, 301);
        UUID transaction = DailyContractService.transactionId(player, IRON_RUSH, PERIOD_START);
        assertEquals(EconomyService.TransactionStatus.SUCCESS, EconomyService.award(
                state, otherPlayer, 1, "collision fixture", PERIOD_START + 2_000,
                transaction, 0, 1_000).status());
        int auditCount = state.auditCount();

        var result = DailyContractService.claim(
                state, player, IRON_RUSH, contract(48, 80), PERIOD_START + 3_000, 0, 1_000);

        assertEquals(DailyContractService.Status.TRANSACTION_CONFLICT, result.status());
        assertTrue(result.auditRecorded());
        assertEquals(auditCount + 1, state.auditCount());
        assertTrue(state.economyBalance(player).isEmpty());
        assertEquals(1, state.economyBalance(otherPlayer).orElseThrow());
    }

    @Test
    void futureSchemaAndInvalidTimeCannotClaim() {
        CompoundTag future = (CompoundTag) PlatformSavedData.CODEC
                .encodeStart(NbtOps.INSTANCE, new PlatformSavedData()).getOrThrow();
        future.putInt("schema_version", PlatformSavedData.CURRENT_SCHEMA_VERSION + 1);
        PlatformSavedData readOnly = PlatformSavedData.CODEC.parse(NbtOps.INSTANCE, future).getOrThrow();
        UUID player = uuid(5);

        var readOnlyResult = DailyContractService.claim(
                readOnly, player, IRON_RUSH, contract(48, 80), PERIOD_START + 1_000, 0, 1_000);
        assertEquals(DailyContractService.Status.READ_ONLY_SCHEMA, readOnlyResult.status());
        assertTrue(readOnly.economyBalance(player).isEmpty());
        assertEquals(0, readOnly.auditCount());

        assertEquals(DailyContractService.Status.INVALID_REQUEST, DailyContractService.evaluate(
                new PlatformSavedData(), player, IRON_RUSH, contract(48, 80), -1).status());
        assertEquals(new UUID(0L, 0L), DailyContractService.transactionId(
                player, IRON_RUSH, PERIOD_START + 1));
    }

    private static void award(
            PlatformSavedData state,
            UUID player,
            net.minecraft.resources.ResourceKey<Level> dimension,
            long timestamp,
            long contribution,
            long evidenceId) {
        ActivityRewardDefinition rewardDefinition = new ActivityRewardDefinition(
                ActivityTrack.MINING,
                ActivityKind.NATURAL_RESOURCE_BREAK,
                IRON_ORE,
                3,
                60_000,
                1_000_000,
                1_000_000);
        var observation = new ActivityObservation(
                uuid(evidenceId),
                timestamp,
                player,
                ActivityTrack.MINING,
                ActivityKind.NATURAL_RESOURCE_BREAK,
                dimension,
                0,
                0,
                IRON_ORE,
                "resource:" + IRON_ORE + ":" + evidenceId,
                contribution,
                new ActivityProvenance(true, false, false));
        assertTrue(ActivityProgressionService.award(
                state, observation, new ResolvedReward(id("iron_ore"), rewardDefinition)).awarded());
    }

    private static DailyContractDefinition contract(long requiredExperience, long reward) {
        return new DailyContractDefinition(
                "daily_contract.rovenfall.iron_rush",
                "daily_contract_description.rovenfall.iron_rush",
                ActivityKind.NATURAL_RESOURCE_BREAK,
                IRON_ORE,
                requiredExperience,
                reward);
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("rovenfall", path);
    }

    private static UUID uuid(long value) {
        return new UUID(0L, value);
    }
}
