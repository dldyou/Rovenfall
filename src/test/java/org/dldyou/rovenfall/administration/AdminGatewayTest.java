package org.dldyou.rovenfall.administration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
