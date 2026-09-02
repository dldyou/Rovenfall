package org.dldyou.rovenfall.administration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class AdminGatewayTest {
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
