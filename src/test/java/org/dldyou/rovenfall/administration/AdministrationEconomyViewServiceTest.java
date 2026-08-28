package org.dldyou.rovenfall.administration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

final class AdministrationEconomyViewServiceTest {
    @Test
    void searchesPersistedOfflineNameAndReturnsServerUuid() {
        PlatformSavedData state = new PlatformSavedData();
        UUID actor = id(1);
        UUID target = id(2);
        AdministrationService.changeRole(
                state, AdministrationService.SYSTEM_ACTOR, true, actor, "viewer",
                "bootstrap", 1_000, id(101));
        PlayerRecordService.observeLogin(state, target, "OfflineHero", 2_000);

        var page = AdministrationEconomyViewService.players(state, actor, false, "offlinehero", 0);

        assertEquals(AdministrationEconomyViewService.Status.SUCCESS, page.status());
        assertEquals(1, page.totalEntries());
        assertEquals(target, page.entries().getFirst().playerId());
        assertEquals("OfflineHero", page.entries().getFirst().displayName());
    }

    @Test
    void rejectsUnknownActorsAndInvalidQueries() {
        PlatformSavedData state = new PlatformSavedData();

        assertEquals(AdministrationEconomyViewService.Status.UNAUTHORIZED,
                AdministrationEconomyViewService.players(state, id(9), false, "", 0).status());
        assertEquals(AdministrationEconomyViewService.Status.INVALID_REQUEST,
                AdministrationEconomyViewService.players(
                        state, id(9), true,
                        "x".repeat(AdministrationReadViewService.MAX_QUERY_LENGTH + 1), 0).status());
        assertTrue(AdministrationEconomyViewService.players(state, id(9), true, "", 0).entries().isEmpty());
    }

    private static UUID id(long value) {
        return new UUID(0L, value);
    }
}
