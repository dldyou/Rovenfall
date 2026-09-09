package org.dldyou.rovenfall.administration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.dldyou.rovenfall.PersistenceTestHarness;
import org.junit.jupiter.api.Test;

final class OperationsScaleTest {
    private static final int TARGET_PLAYERS = 50;
    private static final int TRANSACTIONS_PER_PLAYER = 20;
    private static final long PROFILE_BUDGET_MILLIS = 15_000;

    @Test
    void fiftyPlayerOperationsProfileKeepsWritesPersistenceAndSearchBounded() {
        long profileStarted = System.nanoTime();
        PlatformSavedData state = new PlatformSavedData();
        UUID viewer = id(9_000, 1);
        assertEquals(AdministrationService.RoleChangeStatus.SUCCESS,
                AdministrationService.changeRole(
                        state, AdministrationService.SYSTEM_ACTOR, true, viewer,
                        AdminRole.VIEWER.getSerializedName(), "scale profile", 1_000, id(9_000, 2)).status());

        long writesStarted = System.nanoTime();
        for (int playerIndex = 0; playerIndex < TARGET_PLAYERS; playerIndex++) {
            UUID playerId = id(1, playerIndex + 1L);
            for (int transactionIndex = 0; transactionIndex < TRANSACTIONS_PER_PLAYER; transactionIndex++) {
                long timestamp = 2_000L + playerIndex * 100L + transactionIndex;
                UUID transactionId = id(playerIndex + 10L, transactionIndex + 1L);
                assertEquals(EconomyService.TransactionStatus.SUCCESS,
                        EconomyService.award(
                                state, playerId, 1, "fifty-player profile", timestamp,
                                transactionId, 0, Long.MAX_VALUE).status());
            }
        }
        long writesMillis = elapsedMillis(writesStarted);
        assertEquals(TARGET_PLAYERS, state.economyAccountCount());
        assertEquals(TARGET_PLAYERS * TRANSACTIONS_PER_PLAYER + 1, state.auditCount());

        long persistenceStarted = System.nanoTime();
        PlatformSavedData persisted = PersistenceTestHarness.roundTrip(PlatformSavedData.CODEC, state);
        long persistenceMillis = elapsedMillis(persistenceStarted);
        assertEquals(TARGET_PLAYERS, persisted.economyAccountCount());

        int auditsBeforeSearch = persisted.auditCount();
        long searchStarted = System.nanoTime();
        AdminSearchService.Page players = search(persisted, viewer, AdminSearchService.Scope.PLAYERS);
        AdminSearchService.Page balances = search(persisted, viewer, AdminSearchService.Scope.BALANCES);
        AdminSearchService.Page transactions = search(persisted, viewer, AdminSearchService.Scope.TRANSACTIONS);
        AdminSearchService.Page alerts = search(persisted, viewer, AdminSearchService.Scope.ALERTS);
        for (int index = 0; index < 25; index++) {
            AdminSearchService.Page filtered = AdminSearchService.search(
                    persisted, viewer, false, AdminSearchService.Scope.TRANSACTIONS,
                    Integer.toString(index), 0, AdminSearchService.MAX_PAGE_SIZE);
            assertEquals(AdminSearchService.Status.SUCCESS, filtered.status());
            assertTrue(filtered.entries().size() <= AdminSearchService.MAX_PAGE_SIZE);
        }
        long searchesMillis = elapsedMillis(searchStarted);

        assertEquals(TARGET_PLAYERS + 1, players.totalEntries());
        assertEquals(TARGET_PLAYERS, balances.totalEntries());
        assertEquals(TARGET_PLAYERS * TRANSACTIONS_PER_PLAYER, transactions.totalEntries());
        assertEquals(TARGET_PLAYERS, alerts.totalEntries());
        assertEquals(AdminSearchService.MAX_PAGE_SIZE, transactions.entries().size());
        assertEquals(auditsBeforeSearch, persisted.auditCount());

        long totalMillis = elapsedMillis(profileStarted);
        System.out.printf(
                "Rovenfall 50-player operations profile: writes=%dms persistence=%dms searches=%dms total=%dms%n",
                writesMillis, persistenceMillis, searchesMillis, totalMillis);
        assertTrue(totalMillis < PROFILE_BUDGET_MILLIS,
                () -> "50-player operations profile exceeded " + PROFILE_BUDGET_MILLIS + "ms: " + totalMillis);
    }

    private static AdminSearchService.Page search(
            PlatformSavedData state,
            UUID viewer,
            AdminSearchService.Scope scope) {
        AdminSearchService.Page result = AdminSearchService.search(
                state, viewer, false, scope, "*", 0, AdminSearchService.MAX_PAGE_SIZE);
        assertEquals(AdminSearchService.Status.SUCCESS, result.status());
        assertTrue(result.entries().size() <= AdminSearchService.MAX_PAGE_SIZE);
        return result;
    }

    private static long elapsedMillis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }

    private static UUID id(long most, long least) {
        return new UUID(most, least);
    }
}
