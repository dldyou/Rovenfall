package org.dldyou.rovenfall.administration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import org.dldyou.rovenfall.activities.ActivityChallengeDefinition;
import org.dldyou.rovenfall.activities.ActivityKind;
import org.dldyou.rovenfall.activities.ActivityLevelDefinition;
import org.dldyou.rovenfall.activities.ActivityObservation;
import org.dldyou.rovenfall.activities.ActivityProvenance;
import org.dldyou.rovenfall.activities.ActivityRewardDefinition;
import org.dldyou.rovenfall.activities.ActivityRewardReloadListener.ResolvedReward;
import org.dldyou.rovenfall.activities.ActivityTrack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class AdminGatewayTest {
    @BeforeEach
    void clearPreviews() {
        AdminGateway.clearActionPreviews();
    }

    @Test
    void ownerBridgeActionsReuseAuditedRoleAndEconomyBoundaries() {
        PlatformSavedData state = new PlatformSavedData();
        UUID playerId = id(1);
        PlayerRecordService.observeLogin(state, playerId, "QuartzFox", 1_000);

        AdminGateway.Response role = AdminGateway.action(
                state,
                body("set_role", playerId, "viewer", null, id(101), "support access"),
                ignored -> null);
        AdminGateway.Response grant = AdminGateway.action(
                state,
                body("grant_balance", playerId, null, "2500", id(102), "event reward correction"),
                ignored -> null);

        assertEquals(200, role.status());
        assertEquals(200, grant.status());
        assertEquals(AdminRole.VIEWER, state.roleOf(playerId).orElseThrow());
        assertEquals(2_500L, state.economyBalance(playerId).orElseThrow());
        assertEquals(2, state.auditCount());
        assertTrue((Boolean) responseBody(role).get("ok"));
        assertTrue((Boolean) responseBody(grant).get("ok"));

        Map<String, Object> dashboard = AdminGateway.dashboard(state, Set.of(playerId), 2_000);
        assertEquals(1, dashboard.get("onlinePlayers"));
        assertEquals(1, dashboard.get("knownPlayers"));
        assertEquals("2500", dashboard.get("recentVolume"));
    }

    @Test
    void invalidDomainRequestDoesNotMutateState() {
        PlatformSavedData state = new PlatformSavedData();
        UUID playerId = id(2);
        JsonObject request = body("debit_balance", playerId, null, "50", id(201), "manual correction");

        AdminGateway.Response response = AdminGateway.action(state, request, ignored -> null);

        assertEquals(409, response.status());
        assertFalse((Boolean) responseBody(response).get("ok"));
        assertTrue(state.economyBalance(playerId).isEmpty());
        assertEquals(1, state.auditCount());
    }

    @Test
    void previewIsReadOnlyAndOneTimeConfirmationExecutesTheBoundAction() {
        PlatformSavedData state = new PlatformSavedData();
        UUID playerId = id(3);
        PlayerRecordService.observeLogin(state, playerId, "CopperWren", 1_000);

        AdminGateway.Response preview = AdminGateway.previewAction(
                state,
                body("set_role", playerId, "viewer", null, id(301), "support access"),
                ignored -> null);

        assertEquals(200, preview.status());
        assertTrue(state.roleOf(playerId).isEmpty());
        assertEquals(0, state.auditCount());
        Map<String, Object> previewBody = responseBody(preview);
        assertTrue((Boolean) previewBody.get("requiresTypedConfirmation"));
        assertFalse(id(301).toString().equals(previewBody.get("transactionId")));

        JsonObject confirmation = new JsonObject();
        confirmation.addProperty("previewId", (String) previewBody.get("previewId"));
        confirmation.addProperty("confirmation", "EXECUTE");
        AdminGateway.Response committed = AdminGateway.confirmAction(state, confirmation, ignored -> null);
        AdminGateway.Response replayed = AdminGateway.confirmAction(state, confirmation, ignored -> null);

        assertEquals(200, committed.status());
        assertEquals(409, replayed.status());
        assertEquals(AdminRole.VIEWER, state.roleOf(playerId).orElseThrow());
        assertEquals(1, state.auditCount());
    }

    @Test
    void confirmationRejectsAChangedServerSnapshot() {
        PlatformSavedData state = new PlatformSavedData();
        UUID playerId = id(4);
        PlayerRecordService.observeLogin(state, playerId, "MossBadger", 1_000);
        AdminGateway.Response preview = AdminGateway.previewAction(
                state,
                body("set_role", playerId, "viewer", null, id(401), "temporary access"),
                ignored -> null);
        Map<String, Object> previewBody = responseBody(preview);
        AdministrationService.changeRole(
                state, AdministrationService.SYSTEM_ACTOR, true, playerId, "moderator",
                "policy update", System.currentTimeMillis() - 1_000, id(402));

        JsonObject confirmation = new JsonObject();
        confirmation.addProperty("previewId", (String) previewBody.get("previewId"));
        confirmation.addProperty("confirmation", "execute");
        AdminGateway.Response rejected = AdminGateway.confirmAction(state, confirmation, ignored -> null);

        assertEquals(409, rejected.status());
        assertEquals("stale_preview", responseBody(rejected).get("status"));
        assertEquals(AdminRole.MODERATOR, state.roleOf(playerId).orElseThrow());
        assertEquals(2, state.auditCount());
    }

    @Test
    void dangerousPreviewCannotBeConfirmedWithoutExplicitPhrase() {
        PlatformSavedData state = new PlatformSavedData();
        UUID playerId = id(5);
        EconomyService.adminGrant(
                state, AdministrationService.SYSTEM_ACTOR, true, playerId, 100,
                "fixture", 1_000, id(501), 0, 1_000);
        AdminGateway.Response preview = AdminGateway.previewAction(
                state,
                body("debit_balance", playerId, null, "25", id(502), "manual correction"),
                ignored -> null);
        Map<String, Object> previewBody = responseBody(preview);

        JsonObject confirmation = new JsonObject();
        confirmation.addProperty("previewId", (String) previewBody.get("previewId"));
        AdminGateway.Response missingPhrase = AdminGateway.confirmAction(state, confirmation, ignored -> null);
        confirmation.addProperty("confirmation", "execute");
        AdminGateway.Response committed = AdminGateway.confirmAction(state, confirmation, ignored -> null);

        assertEquals(400, missingPhrase.status());
        assertEquals(200, committed.status());
        assertEquals(75L, state.economyBalance(playerId).orElseThrow());
    }

    @Test
    void progressProjectionShowsEveryTrackAndChallengeReadinessWithoutMutatingIt() {
        PlatformSavedData state = new PlatformSavedData();
        UUID playerId = id(6);
        Identifier firstChallenge = definitionId("first_steps");
        Identifier secondChallenge = definitionId("veteran");
        Map<ActivityTrack, ActivityLevelDefinition> levelDefinitions = new EnumMap<>(ActivityTrack.class);
        for (ActivityTrack track : ActivityTrack.values()) {
            levelDefinitions.put(track, new ActivityLevelDefinition(track, List.of(0L, 100L, 300L)));
        }
        Map<Identifier, ActivityChallengeDefinition> challenges = Map.of(
                firstChallenge,
                challenge("first_steps", 1, 100),
                secondChallenge,
                challenge("veteran", 2, 250));
        ActivityProgressionService.AwardResult award = ActivityProgressionService.award(
                state,
                combatObservation(playerId),
                combatReward(150));

        assertTrue(award.awarded());
        AdminGateway.ProgressProjection before = AdminGateway.progressProjection(
                state, playerId, levelDefinitions, challenges);
        AdminGateway.ActivityProgressRow combat = before.activities().stream()
                .filter(row -> row.track().equals("combat"))
                .findFirst()
                .orElseThrow();
        assertEquals(7, before.activities().size());
        assertEquals(1, combat.level());
        assertEquals("150", combat.totalExperience());
        assertEquals("50", combat.experienceIntoLevel());
        assertEquals("200", combat.experienceForNextLevel());
        assertEquals(new AdminGateway.ChallengeProgress(true, 2, 1, 0), before.challenges());

        ActivityChallengeService.ClaimResult claimed = ActivityChallengeService.claim(
                state,
                playerId,
                firstChallenge,
                challenges.get(firstChallenge),
                Map.of(ActivityTrack.COMBAT, 1),
                2_000,
                0,
                10_000);
        assertEquals(ActivityChallengeService.Status.SUCCESS, claimed.status());
        AdminGateway.ProgressProjection after = AdminGateway.progressProjection(
                state, playerId, levelDefinitions, challenges);
        assertEquals(new AdminGateway.ChallengeProgress(true, 2, 0, 1), after.challenges());

        AdminGateway.ProgressProjection incomplete = AdminGateway.progressProjection(
                state,
                playerId,
                Map.of(ActivityTrack.COMBAT, levelDefinitions.get(ActivityTrack.COMBAT)),
                challenges);
        assertEquals(new AdminGateway.ChallengeProgress(false, 0, 0, 0), incomplete.challenges());
    }

    private static ActivityObservation combatObservation(UUID playerId) {
        return new ActivityObservation(
                id(601),
                1_000,
                playerId,
                ActivityTrack.COMBAT,
                ActivityKind.COMBAT_DAMAGE,
                Level.OVERWORLD,
                0,
                0,
                definitionId("training_dummy"),
                "admin-projection-fixture",
                1,
                new ActivityProvenance(false, false, false));
    }

    private static ResolvedReward combatReward(long experience) {
        return new ResolvedReward(
                definitionId("combat_reward"),
                new ActivityRewardDefinition(
                        ActivityTrack.COMBAT,
                        ActivityKind.COMBAT_DAMAGE,
                        definitionId("training_dummy"),
                        experience,
                        60_000,
                        1_000,
                        1_000));
    }

    private static ActivityChallengeDefinition challenge(String path, int level, long reward) {
        return new ActivityChallengeDefinition(
                "activity_challenge.rovenfall." + path,
                "activity_challenge_description.rovenfall." + path,
                Map.of(ActivityTrack.COMBAT, level),
                reward);
    }

    private static Identifier definitionId(String path) {
        return Identifier.fromNamespaceAndPath("rovenfall", path);
    }

    private static JsonObject body(
            String type,
            UUID playerId,
            String role,
            String amount,
            UUID transactionId,
            String reason) {
        JsonObject body = new JsonObject();
        body.addProperty("type", type);
        body.addProperty("playerId", playerId.toString());
        if (role != null) {
            body.addProperty("role", role);
        }
        if (amount != null) {
            body.addProperty("amount", amount);
        }
        body.addProperty("transactionId", transactionId.toString());
        body.addProperty("reason", reason);
        return body;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> responseBody(AdminGateway.Response response) {
        return (Map<String, Object>) response.body();
    }

    private static UUID id(long value) {
        return new UUID(0L, value);
    }
}
