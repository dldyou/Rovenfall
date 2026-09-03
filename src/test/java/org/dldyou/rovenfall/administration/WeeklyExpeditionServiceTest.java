package org.dldyou.rovenfall.administration;

import static org.dldyou.rovenfall.PersistenceTestHarness.roundTrip;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.Identifier;
import org.dldyou.rovenfall.activities.WeeklyExpeditionDefinition;
import org.junit.jupiter.api.Test;

final class WeeklyExpeditionServiceTest {
    private static final Identifier SUPPLY_LINES = id("supply_lines");
    private static final Identifier IRON_RUSH = id("iron_rush");
    private static final Identifier HARVEST_RATIONS = id("harvest_rations");
    private static final Identifier CAMP_PROVISIONS = id("camp_provisions");
    private static final long DAY = DailyContractService.PERIOD_MILLIS;
    private static final long WEEK = WeeklyExpeditionService.PERIOD_MILLIS;
    private static final long WEEK_START = (4 + 7 * 100L) * DAY;
    private static final long EVALUATION_TIME = WEEK_START + 2 * DAY + 60_000;

    @Test
    void weeklyClaimAggregatesDailyReceiptsAndReopensNextMonday() throws Exception {
        PlatformSavedData state = new PlatformSavedData();
        UUID player = uuid(1);
        var definition = supplyLines(500);

        var empty = WeeklyExpeditionService.evaluate(
                state, player, SUPPLY_LINES, definition, EVALUATION_TIME);
        assertEquals(WeeklyExpeditionService.Status.IN_PROGRESS, empty.status());
        assertFalse(empty.complete());
        assertTrue(empty.requirements().stream().allMatch(requirement ->
                requirement.currentCompletions() == 0));

        seedRequiredDailyCompletions(state, player);
        var claim = WeeklyExpeditionService.claim(
                state, player, SUPPLY_LINES, definition, EVALUATION_TIME, 0, 10_000);

        assertEquals(WeeklyExpeditionService.Status.SUCCESS, claim.status());
        assertEquals(500, claim.awardedCurrency());
        assertEquals(506, claim.balance());
        assertTrue(claim.auditRecorded());
        assertTrue(claim.evaluation().complete());
        assertEquals("weekly expedition rovenfall:supply_lines period " + WEEK_START,
                state.auditPage(0, 1).entries().getFirst().reason());

        int auditCount = state.auditCount();
        var retry = WeeklyExpeditionService.claim(
                state, player, SUPPLY_LINES, supplyLines(700), EVALUATION_TIME + 1, 0, 10_000);
        assertEquals(WeeklyExpeditionService.Status.ALREADY_CLAIMED, retry.status());
        assertEquals(506, retry.balance());
        assertFalse(retry.auditRecorded());
        assertEquals(auditCount, state.auditCount());

        var nextWeek = WeeklyExpeditionService.evaluate(
                state, player, SUPPLY_LINES, definition, WEEK_START + WEEK + 60_000);
        assertEquals(WeeklyExpeditionService.Status.IN_PROGRESS, nextWeek.status());
        assertTrue(nextWeek.requirements().stream().allMatch(requirement ->
                requirement.currentCompletions() == 0));
        assertNotEquals(claim.evaluation().transactionId(), nextWeek.transactionId());

        PlatformSavedData restored = roundTrip(PlatformSavedData.CODEC, state);
        assertEquals(WeeklyExpeditionService.Status.ALREADY_CLAIMED,
                WeeklyExpeditionService.evaluate(
                        restored, player, SUPPLY_LINES, definition, EVALUATION_TIME + 2).status());
        assertEquals(506, restored.economyBalance(player).orElseThrow());
    }

    @Test
    void futureDailyReceiptsAreNotCountedBeforeTheirDay() {
        PlatformSavedData state = new PlatformSavedData();
        UUID player = uuid(2);
        long futureDay = WEEK_START + 3 * DAY;
        seedDailyCompletion(state, player, IRON_RUSH, futureDay);

        var before = WeeklyExpeditionService.evaluate(
                state, player, SUPPLY_LINES, supplyLines(500), EVALUATION_TIME);
        assertEquals(0, requirement(before, IRON_RUSH).currentCompletions());

        var after = WeeklyExpeditionService.evaluate(
                state, player, SUPPLY_LINES, supplyLines(500), futureDay + 60_000);
        assertEquals(1, requirement(after, IRON_RUSH).currentCompletions());
        assertEquals(WEEK_START, WeeklyExpeditionService.periodStart(WEEK_START + 6 * DAY));
        assertEquals(WEEK_START + WEEK, WeeklyExpeditionService.periodStart(WEEK_START + WEEK));
    }

    @Test
    void incompleteAndFailedRewardsDoNotPartiallyCommit() {
        UUID player = uuid(3);
        PlatformSavedData incompleteState = new PlatformSavedData();
        var incomplete = WeeklyExpeditionService.claim(
                incompleteState, player, SUPPLY_LINES, supplyLines(500), EVALUATION_TIME, 0, 1_000);
        assertEquals(WeeklyExpeditionService.Status.IN_PROGRESS, incomplete.status());
        assertTrue(incompleteState.economyBalance(player).isEmpty());
        assertTrue(incompleteState.economyReceipt(incomplete.evaluation().transactionId()).isEmpty());
        assertEquals(0, incompleteState.auditCount());

        PlatformSavedData cappedState = new PlatformSavedData();
        seedRequiredDailyCompletions(cappedState, player);
        assertEquals(EconomyService.TransactionStatus.SUCCESS, EconomyService.award(
                cappedState, player, 990, "seed", EVALUATION_TIME - 1,
                uuid(30_001), 0, 1_000).status());
        int auditCount = cappedState.auditCount();
        var capped = WeeklyExpeditionService.claim(
                cappedState, player, SUPPLY_LINES, supplyLines(500), EVALUATION_TIME, 0, 1_000);
        assertEquals(WeeklyExpeditionService.Status.REWARD_FAILED, capped.status());
        assertEquals(EconomyService.TransactionStatus.MAXIMUM_EXCEEDED,
                capped.economyStatus().orElseThrow());
        assertEquals(996, cappedState.economyBalance(player).orElseThrow());
        assertTrue(cappedState.economyReceipt(capped.evaluation().transactionId()).isEmpty());
        assertEquals(auditCount + 1, cappedState.auditCount());
        assertTrue(capped.auditRecorded());
    }

    @Test
    void deterministicReceiptConflictIsDeniedAndAudited() {
        PlatformSavedData state = new PlatformSavedData();
        UUID player = uuid(4);
        UUID otherPlayer = uuid(5);
        seedRequiredDailyCompletions(state, player);
        UUID transaction = WeeklyExpeditionService.transactionId(player, SUPPLY_LINES, WEEK_START);
        assertEquals(EconomyService.TransactionStatus.SUCCESS, EconomyService.award(
                state, otherPlayer, 1, "collision fixture", EVALUATION_TIME - 1,
                transaction, 0, 10_000).status());
        int auditCount = state.auditCount();

        var result = WeeklyExpeditionService.claim(
                state, player, SUPPLY_LINES, supplyLines(500), EVALUATION_TIME, 0, 10_000);

        assertEquals(WeeklyExpeditionService.Status.TRANSACTION_CONFLICT, result.status());
        assertTrue(result.auditRecorded());
        assertEquals(auditCount + 1, state.auditCount());
        assertEquals(6, state.economyBalance(player).orElseThrow());
        assertEquals(1, state.economyBalance(otherPlayer).orElseThrow());
    }

    @Test
    void futureSchemaAndInvalidInputsCannotClaim() {
        CompoundTag future = (CompoundTag) PlatformSavedData.CODEC
                .encodeStart(NbtOps.INSTANCE, new PlatformSavedData()).getOrThrow();
        future.putInt("schema_version", PlatformSavedData.CURRENT_SCHEMA_VERSION + 1);
        PlatformSavedData readOnly = PlatformSavedData.CODEC.parse(NbtOps.INSTANCE, future).getOrThrow();
        UUID player = uuid(6);

        var readOnlyResult = WeeklyExpeditionService.claim(
                readOnly, player, SUPPLY_LINES, supplyLines(500), EVALUATION_TIME, 0, 10_000);
        assertEquals(WeeklyExpeditionService.Status.READ_ONLY_SCHEMA, readOnlyResult.status());
        assertTrue(readOnly.economyBalance(player).isEmpty());
        assertEquals(0, readOnly.auditCount());

        assertEquals(WeeklyExpeditionService.Status.INVALID_REQUEST,
                WeeklyExpeditionService.evaluate(
                        new PlatformSavedData(), player, SUPPLY_LINES, supplyLines(500), -1).status());
        assertEquals(new UUID(0L, 0L), WeeklyExpeditionService.transactionId(
                player, SUPPLY_LINES, WEEK_START + 1));
    }

    private static WeeklyExpeditionService.Requirement requirement(
            WeeklyExpeditionService.Evaluation evaluation,
            Identifier contractId) {
        return evaluation.requirements().stream()
                .filter(requirement -> requirement.dailyContractId().equals(contractId))
                .findFirst()
                .orElseThrow();
    }

    private static void seedRequiredDailyCompletions(PlatformSavedData state, UUID player) {
        for (Identifier contractId : supplyLines(500).dailyContractRequirements().keySet()) {
            seedDailyCompletion(state, player, contractId, WEEK_START);
            seedDailyCompletion(state, player, contractId, WEEK_START + DAY);
        }
    }

    private static void seedDailyCompletion(
            PlatformSavedData state,
            UUID player,
            Identifier contractId,
            long dayStart) {
        assertEquals(EconomyService.TransactionStatus.SUCCESS, EconomyService.award(
                state,
                player,
                1,
                "daily contract completion fixture",
                dayStart + 1_000,
                DailyContractService.transactionId(player, contractId, dayStart),
                0,
                10_000).status());
        assertTrue(DailyContractService.claimedForPeriod(state, player, contractId, dayStart));
    }

    private static WeeklyExpeditionDefinition supplyLines(long reward) {
        return new WeeklyExpeditionDefinition(
                "weekly_expedition.rovenfall.supply_lines",
                "weekly_expedition_description.rovenfall.supply_lines",
                Map.of(IRON_RUSH, 2, HARVEST_RATIONS, 2, CAMP_PROVISIONS, 2),
                reward);
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("rovenfall", path);
    }

    private static UUID uuid(long value) {
        return new UUID(0L, value);
    }
}
