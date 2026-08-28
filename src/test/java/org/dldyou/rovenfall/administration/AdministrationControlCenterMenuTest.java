package org.dldyou.rovenfall.administration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class AdministrationControlCenterMenuTest {
    @Test
    void exposesExactlyTheReadDomainsAssignedToEachRole() {
        assertEquals(List.of(AdministrationReadViewService.Domain.values()),
                AdministrationReadViewService.Domain.allowedForRole(AdminRole.VIEWER));
        assertEquals(List.of(
                        AdministrationReadViewService.Domain.PLAYERS,
                        AdministrationReadViewService.Domain.CLAIMS,
                        AdministrationReadViewService.Domain.RPG,
                        AdministrationReadViewService.Domain.AUDIT,
                        AdministrationReadViewService.Domain.METRICS),
                AdministrationReadViewService.Domain.allowedForRole(AdminRole.MODERATOR));
        assertEquals(List.of(
                        AdministrationReadViewService.Domain.PLAYERS,
                        AdministrationReadViewService.Domain.SHOPS,
                        AdministrationReadViewService.Domain.AUDIT,
                        AdministrationReadViewService.Domain.ALERTS,
                        AdministrationReadViewService.Domain.METRICS),
                AdministrationReadViewService.Domain.allowedForRole(AdminRole.ECONOMY_MANAGER));
        assertEquals(List.of(
                        AdministrationReadViewService.Domain.PLAYERS,
                        AdministrationReadViewService.Domain.PORTALS,
                        AdministrationReadViewService.Domain.RPG,
                        AdministrationReadViewService.Domain.ENCOUNTERS,
                        AdministrationReadViewService.Domain.AUDIT,
                        AdministrationReadViewService.Domain.METRICS),
                AdministrationReadViewService.Domain.allowedForRole(AdminRole.CONTENT_MANAGER));
        assertEquals(List.of(AdministrationReadViewService.Domain.values()),
                AdministrationReadViewService.Domain.allowedForRole(AdminRole.OWNER));
    }

    @Test
    void hiddenSlotsCannotSelectUnauthorizedDomains() {
        int claimsSlot = AdministrationControlCenterMenu.CONTENT_START
                + AdministrationReadViewService.Domain.CLAIMS.ordinal();
        int shopsSlot = AdministrationControlCenterMenu.CONTENT_START
                + AdministrationReadViewService.Domain.SHOPS.ordinal();

        assertEquals(AdministrationReadViewService.Domain.CLAIMS,
                AdministrationControlCenterMenu.domainAt(AdminRole.MODERATOR, claimsSlot));
        assertNull(AdministrationControlCenterMenu.domainAt(AdminRole.MODERATOR, shopsSlot));
        assertEquals(AdministrationReadViewService.Domain.SHOPS,
                AdministrationControlCenterMenu.domainAt(AdminRole.ECONOMY_MANAGER, shopsSlot));
        assertNull(AdministrationControlCenterMenu.domainAt(AdminRole.ECONOMY_MANAGER, claimsSlot));
        assertNull(AdministrationControlCenterMenu.domainAt(AdminRole.OWNER, -1));
        assertNull(AdministrationControlCenterMenu.domainAt(
                AdminRole.OWNER, AdministrationControlCenterMenu.MENU_SIZE));
    }

    @Test
    void demotionOrCrossDomainRoleChangeImmediatelyInvalidatesAnOpenView() {
        assertTrue(AdministrationControlCenterMenu.canContinue(
                Optional.of(AdminRole.OWNER), AdministrationReadViewService.Domain.CLAIMS));
        assertFalse(AdministrationControlCenterMenu.canContinue(
                Optional.empty(), AdministrationReadViewService.Domain.CLAIMS));
        assertFalse(AdministrationControlCenterMenu.canContinue(
                Optional.of(AdminRole.ECONOMY_MANAGER), AdministrationReadViewService.Domain.CLAIMS));
        assertTrue(AdministrationControlCenterMenu.canContinue(
                Optional.of(AdminRole.MODERATOR), AdministrationReadViewService.Domain.CLAIMS));
    }

    @Test
    void attentionFilterIsExplicitAndReversible() {
        assertEquals(AdministrationReadViewService.Filter.ATTENTION,
                AdministrationReadViewService.Filter.ALL.next());
        assertEquals(AdministrationReadViewService.Filter.ALL,
                AdministrationReadViewService.Filter.ATTENTION.next());
    }

    @Test
    void searchFilterAndPaginationRemainBounded() {
        var rows = List.of(
                new AdministrationReadViewService.Row("alpha", "normal player", false),
                new AdministrationReadViewService.Row("beta", "attention player", true),
                new AdministrationReadViewService.Row("gamma", "attention claim", true));

        var searched = AdministrationReadViewService.filterAndPage(
                rows, true, AdministrationReadViewService.Filter.ATTENTION, "PLAYER", 0, 1);

        assertEquals(AdministrationReadViewService.Status.SUCCESS, searched.status());
        assertEquals(1, searched.totalEntries());
        assertEquals(List.of(rows.get(1)), searched.entries());
        assertTrue(searched.truncated());

        var invalid = AdministrationReadViewService.filterAndPage(
                rows, false, AdministrationReadViewService.Filter.ALL,
                "x".repeat(AdministrationReadViewService.MAX_QUERY_LENGTH + 1), 0, 1);
        assertEquals(AdministrationReadViewService.Status.INVALID_REQUEST, invalid.status());
    }
}
