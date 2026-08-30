package org.dldyou.rovenfall.administration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.entity.player.Player;
import org.junit.jupiter.api.Test;

final class RovenfallInventoryCompatibilityTest {
    @Test
    void onlyTheExactVanillaSurvivalInventoryIsEligibleForReplacement() {
        assertTrue(RovenfallInventoryClient.supportsInventoryReplacement(InventoryScreen.class));
        assertFalse(RovenfallInventoryClient.supportsInventoryReplacement(RovenfallInventoryScreen.class));
        assertFalse(RovenfallInventoryClient.supportsInventoryReplacement(Screen.class));
        assertFalse(RovenfallInventoryClient.supportsInventoryReplacement(null));
    }

    @Test
    void characterShortcutLeavesUnknownAndModdedContainerScreensUntouched() {
        assertTrue(RovenfallInventoryClient.supportsCharacterShortcut(null));
        assertTrue(RovenfallInventoryClient.supportsCharacterShortcut(InventoryScreen.class));
        assertTrue(RovenfallInventoryClient.supportsCharacterShortcut(RovenfallInventoryScreen.class));
        assertTrue(RovenfallInventoryClient.supportsCharacterShortcut(RovenfallAdministrationMenuScreen.class));
        assertTrue(RovenfallInventoryClient.supportsCharacterShortcut(RovenfallCustomPlayerMenuScreen.class));

        assertFalse(RovenfallInventoryClient.supportsCharacterShortcut(ThirdPartyInventoryScreen.class));
        assertFalse(RovenfallInventoryClient.supportsCharacterShortcut(ContainerScreen.class));
        assertFalse(RovenfallInventoryClient.supportsCharacterShortcut(Screen.class));
    }

    private static final class ThirdPartyInventoryScreen extends InventoryScreen {
        private ThirdPartyInventoryScreen(Player player) {
            super(player);
        }
    }
}
