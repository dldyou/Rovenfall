package org.dldyou.rovenfall.administration;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.common.NeoForge;

/** Replaces only the ordinary survival inventory with the Rovenfall shell. */
public final class RovenfallInventoryClient {
    private RovenfallInventoryClient() {
    }

    public static void register() {
        NeoForge.EVENT_BUS.addListener(RovenfallInventoryClient::onScreenOpening);
    }

    static boolean shouldReplace(Screen screen, Player player) {
        return screen != null && screen.getClass() == InventoryScreen.class
                && player != null && player.isAlive() && !player.isSpectator()
                && !player.hasInfiniteMaterials();
    }

    static void request(PlayerMenuNetwork.MenuTarget target) {
        ClientPacketDistributor.sendToServer(new PlayerMenuNetwork.Open(target));
    }

    private static void onScreenOpening(ScreenEvent.Opening event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (shouldReplace(event.getNewScreen(), minecraft.player)) {
            event.setNewScreen(new RovenfallInventoryScreen(minecraft.player));
        }
    }
}
