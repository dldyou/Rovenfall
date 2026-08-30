package org.dldyou.rovenfall.administration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.Test;

final class AdministrationOperationsViewServiceTest {
    @Test
    void pageIsClampedAfterAResultSetShrinks() {
        assertEquals(0, AdministrationOperationsViewService.clampPage(4, 0));
        assertEquals(2, AdministrationOperationsViewService.clampPage(4, 3));
        assertEquals(1, AdministrationOperationsViewService.clampPage(1, 3));
        assertThrows(IllegalArgumentException.class,
                () -> AdministrationOperationsViewService.clampPage(-1, 3));
    }

    @Test
    void alertEvidenceQueryTargetsTheAlertTransactionWithinTheBoundedWindow() {
        UUID playerId = UUID.randomUUID();
        UUID transactionId = UUID.randomUUID();
        long timestamp = AuditQuery.MAX_WINDOW_MILLIS + 42L;
        EconomyAlert alert = new EconomyAlert(
                timestamp, playerId, transactionId, EconomyAlert.Type.AMOUNT, 100L, 50L);

        AuditQuery query = AdministrationOperationsViewService.alertEvidenceQuery(alert);

        assertEquals(42L, query.sinceEpochMillis());
        assertEquals(timestamp, query.untilEpochMillis());
        assertEquals(transactionId, query.transactionId().orElseThrow());
    }
}
