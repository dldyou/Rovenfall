package org.dldyou.rovenfall.administration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class AdministrationRpgBossMenuTest {
    @Test
    void onlyServerBackedListModesAcceptSearch() {
        assertTrue(AdministrationRpgBossMenu.searchable(AdministrationRpgBossMenu.Mode.RPG_PLAYERS));
        assertTrue(AdministrationRpgBossMenu.searchable(AdministrationRpgBossMenu.Mode.HISTORY));
        assertTrue(AdministrationRpgBossMenu.searchable(AdministrationRpgBossMenu.Mode.PROMOTIONS));
        assertTrue(AdministrationRpgBossMenu.searchable(AdministrationRpgBossMenu.Mode.DEFINITIONS));
        assertTrue(AdministrationRpgBossMenu.searchable(AdministrationRpgBossMenu.Mode.MUTATIONS));
        assertTrue(AdministrationRpgBossMenu.searchable(AdministrationRpgBossMenu.Mode.ENCOUNTERS));
        assertTrue(AdministrationRpgBossMenu.searchable(AdministrationRpgBossMenu.Mode.PARTICIPANTS));
        assertTrue(AdministrationRpgBossMenu.searchable(AdministrationRpgBossMenu.Mode.REWARDS));
        assertFalse(AdministrationRpgBossMenu.searchable(AdministrationRpgBossMenu.Mode.RPG_PLAYER));
        assertFalse(AdministrationRpgBossMenu.searchable(AdministrationRpgBossMenu.Mode.ENCOUNTER_DETAIL));
        assertFalse(AdministrationRpgBossMenu.searchable(AdministrationRpgBossMenu.Mode.FORM));
    }
}
