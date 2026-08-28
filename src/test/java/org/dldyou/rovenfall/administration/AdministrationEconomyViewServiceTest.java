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

    @Test
    void boundedPlayerSearchIsDeterministicAndExactUuidBypassesTheWindow() {
        PlatformSavedData state = new PlatformSavedData();
        UUID actor = id(1);
        UUID target = new UUID(Long.MIN_VALUE, 2_047L);
        AdministrationService.changeRole(
                state, AdministrationService.SYSTEM_ACTOR, true, actor, "viewer",
                "bootstrap", 1_000, id(101));
        for (long value = 10; value < 1_010; value++) {
            PlayerRecordService.observeLogin(state, id(value), "Player" + value, value + 2_000);
        }
        PlayerRecordService.observeLogin(state, target, "NeedleHero", 4_000);

        var byName = AdministrationEconomyViewService.players(state, actor, false, "needlehero", 0);
        var byId = AdministrationEconomyViewService.players(state, actor, false, target.toString(), 0);

        assertEquals(target, byName.entries().getFirst().playerId());
        assertEquals(target, byId.entries().getFirst().playerId());
        assertTrue(byName.truncated());
        assertTrue(!byId.truncated());
    }

    @Test
    void exactReceiptLookupBypassesTheRecentWindow() {
        PlatformSavedData state = new PlatformSavedData();
        UUID actor = id(1);
        UUID player = id(2);
        UUID target = new UUID(Long.MIN_VALUE, 2_047L);
        AdministrationService.changeRole(
                state, AdministrationService.SYSTEM_ACTOR, true, actor, "viewer",
                "bootstrap", 1_000, id(101));
        for (long value = 10; value < 1_010; value++) {
            EconomyService.award(
                    state, player, 1, "bounded lookup", value + 2_000, id(value + 10_000),
                    0, EconomyConfig.DEFAULT_MAXIMUM_BALANCE);
        }
        EconomyService.award(
                state, player, 1, "bounded lookup", 4_000, target,
                0, EconomyConfig.DEFAULT_MAXIMUM_BALANCE);

        var page = AdministrationEconomyViewService.receipts(
                state, actor, false, null, target.toString(), 0);

        assertEquals(1, page.totalEntries());
        assertEquals(target, page.entries().getFirst().transactionId());
        assertTrue(!page.truncated());
    }

    private static UUID id(long value) {
        return new UUID(0L, value);
    }
}
