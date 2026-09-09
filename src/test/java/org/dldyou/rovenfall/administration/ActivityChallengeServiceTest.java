package org.dldyou.rovenfall.administration;

import static org.dldyou.rovenfall.PersistenceTestHarness.roundTrip;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.Identifier;
import org.dldyou.rovenfall.activities.ActivityChallengeDefinition;
import org.dldyou.rovenfall.activities.ActivityTrack;
import org.junit.jupiter.api.Test;

final class ActivityChallengeServiceTest {
    private static final Identifier FIRST_STEPS = id("first_steps");

    @Test
    void successfulClaimIsAuditedIdempotentAndPersistent() throws Exception {
        PlatformSavedData state = new PlatformSavedData();
        UUID player = uuid(1);
        ActivityChallengeDefinition definition = challenge(100);
        Map<ActivityTrack, Integer> levels = Map.of(
                ActivityTrack.EXPLORATION, 1,
                ActivityTrack.HUNTING, 1);
        UUID transactionId = ActivityChallengeService.transactionId(player, FIRST_STEPS);

        var claimed = ActivityChallengeService.claim(
                state, player, FIRST_STEPS, definition, levels, 1_000, 0, 1_000);

        assertEquals(ActivityChallengeService.Status.SUCCESS, claimed.status());
        assertEquals(100, claimed.awardedCurrency());
        assertEquals(100, claimed.balance());
        assertTrue(claimed.auditRecorded());
        assertEquals(EconomyTransactionReceipt.Kind.AWARD,
                state.economyReceipt(transactionId).orElseThrow().kind());
        AuditEntry audit = state.auditPage(0, 1).entries().getFirst();
        assertEquals("rovenfall:economy_award", audit.actionType().toString());
        assertEquals("activity challenge rovenfall:first_steps", audit.reason());
        assertEquals(transactionId, audit.transactionId());

        int auditCount = state.auditCount();
        var retry = ActivityChallengeService.claim(
                state, player, FIRST_STEPS, challenge(120), levels, 2_000, 0, 1_000);
        assertEquals(ActivityChallengeService.Status.ALREADY_CLAIMED, retry.status());
        assertEquals(100, retry.balance());
        assertFalse(retry.auditRecorded());
        assertEquals(auditCount, state.auditCount());

        PlatformSavedData restored = roundTrip(PlatformSavedData.CODEC, state);
        assertEquals(ActivityChallengeService.Status.ALREADY_CLAIMED,
                ActivityChallengeService.evaluate(
                        restored, player, FIRST_STEPS, definition, levels).status());
        assertEquals(100, restored.economyBalance(player).orElseThrow());
    }

    @Test
    void unmetRequirementsAndRewardFailureDoNotPartiallyCommit() {
        ActivityChallengeDefinition definition = challenge(100);
        UUID player = uuid(2);
        PlatformSavedData unmetState = new PlatformSavedData();

        var unmet = ActivityChallengeService.claim(
                unmetState,
                player,
                FIRST_STEPS,
                definition,
                Map.of(ActivityTrack.EXPLORATION, 1, ActivityTrack.HUNTING, 0),
                1_000,
                0,
                1_000);

        assertEquals(ActivityChallengeService.Status.REQUIREMENTS_NOT_MET, unmet.status());
        assertTrue(unmetState.economyBalance(player).isEmpty());
        assertTrue(unmetState.economyReceipt(unmet.evaluation().transactionId()).isEmpty());
        assertEquals(0, unmetState.auditCount());

        PlatformSavedData cappedState = new PlatformSavedData();
        assertEquals(EconomyService.TransactionStatus.SUCCESS, EconomyService.award(
                cappedState, player, 950, "seed", 2_000, uuid(201), 0, 1_000).status());
        int auditCount = cappedState.auditCount();
        var capped = ActivityChallengeService.claim(
                cappedState,
                player,
                FIRST_STEPS,
                definition,
                Map.of(ActivityTrack.EXPLORATION, 1, ActivityTrack.HUNTING, 1),
                3_000,
                0,
                1_000);

        assertEquals(ActivityChallengeService.Status.REWARD_FAILED, capped.status());
        assertEquals(EconomyService.TransactionStatus.MAXIMUM_EXCEEDED,
                capped.economyStatus().orElseThrow());
        assertEquals(950, cappedState.economyBalance(player).orElseThrow());
        assertTrue(cappedState.economyReceipt(capped.evaluation().transactionId()).isEmpty());
        assertEquals(auditCount + 1, cappedState.auditCount());
        assertTrue(capped.auditRecorded());
    }

    @Test
    void conflictingDeterministicReceiptIsDeniedAndAudited() {
        PlatformSavedData state = new PlatformSavedData();
        UUID player = uuid(3);
        UUID otherPlayer = uuid(4);
        UUID transactionId = ActivityChallengeService.transactionId(player, FIRST_STEPS);
        assertEquals(EconomyService.TransactionStatus.SUCCESS, EconomyService.award(
                state, otherPlayer, 1, "collision fixture", 1_000, transactionId, 0, 1_000).status());
        int auditCount = state.auditCount();

        var result = ActivityChallengeService.claim(
                state,
                player,
                FIRST_STEPS,
                challenge(100),
                Map.of(ActivityTrack.EXPLORATION, 1, ActivityTrack.HUNTING, 1),
                2_000,
                0,
                1_000);

        assertEquals(ActivityChallengeService.Status.TRANSACTION_CONFLICT, result.status());
        assertTrue(result.auditRecorded());
        assertEquals(auditCount + 1, state.auditCount());
        assertTrue(state.economyBalance(player).isEmpty());
        assertEquals(1, state.economyBalance(otherPlayer).orElseThrow());
    }

    @Test
    void futureSchemaCannotClaimAReward() {
        PlatformSavedData writable = new PlatformSavedData();
        CompoundTag future = (CompoundTag) PlatformSavedData.CODEC
                .encodeStart(NbtOps.INSTANCE, writable).getOrThrow();
        future.putInt("schema_version", PlatformSavedData.CURRENT_SCHEMA_VERSION + 1);
        PlatformSavedData readOnly = PlatformSavedData.CODEC.parse(NbtOps.INSTANCE, future).getOrThrow();
        UUID player = uuid(5);

        var result = ActivityChallengeService.claim(
                readOnly,
                player,
                FIRST_STEPS,
                challenge(100),
                Map.of(ActivityTrack.EXPLORATION, 1, ActivityTrack.HUNTING, 1),
                1_000,
                0,
                1_000);

        assertEquals(ActivityChallengeService.Status.READ_ONLY_SCHEMA, result.status());
        assertTrue(readOnly.economyBalance(player).isEmpty());
        assertTrue(readOnly.economyReceipt(result.evaluation().transactionId()).isEmpty());
        assertEquals(0, readOnly.auditCount());
    }

    private static ActivityChallengeDefinition challenge(long reward) {
        return new ActivityChallengeDefinition(
                "activity_challenge.rovenfall.first_steps",
                "activity_challenge_description.rovenfall.first_steps",
                Map.of(ActivityTrack.EXPLORATION, 1, ActivityTrack.HUNTING, 1),
                reward);
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("rovenfall", path);
    }

    private static UUID uuid(long value) {
        return new UUID(0L, value);
    }
}
