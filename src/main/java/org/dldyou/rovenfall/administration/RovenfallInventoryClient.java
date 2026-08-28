package org.dldyou.rovenfall.administration;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.contents.TranslatableContents;
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
            return;
        }
        if (event.getNewScreen() instanceof ContainerScreen screen
                && !(screen instanceof RovenfallPlayerMenuScreen)
                && minecraft.player != null
                && isPlayerMenuTitle(screen.getTitle())) {
            event.setNewScreen(new RovenfallPlayerMenuScreen(
                    screen.getMenu(), minecraft.player.getInventory(), screen.getTitle()));
        }
    }

    static boolean isPlayerMenuTitle(net.minecraft.network.chat.Component title) {
        if (!(title.getContents() instanceof TranslatableContents contents)) {
            return false;
        }
        return switch (contents.getKey()) {
            case "gui.rovenfall.player.title", "gui.rovenfall.shop.title",
                    "gui.rovenfall.claim.title", "gui.rovenfall.rpg.title",
                    "gui.rovenfall.admin.title", "gui.rovenfall.admin.economy.title",
                    "gui.rovenfall.admin.world.title", "gui.rovenfall.admin.rpg_boss.title",
                    "gui.rovenfall.admin.operations.title" -> true;
            default -> false;
        };
    }

    static boolean isAdminMenuTitle(net.minecraft.network.chat.Component title) {
        return title.getContents() instanceof TranslatableContents contents
                && (contents.getKey().equals("gui.rovenfall.admin.title")
                        || contents.getKey().equals("gui.rovenfall.admin.economy.title")
                        || contents.getKey().equals("gui.rovenfall.admin.world.title")
                        || contents.getKey().equals("gui.rovenfall.admin.rpg_boss.title")
                        || contents.getKey().equals("gui.rovenfall.admin.operations.title"));
    }

    static int adminInputLength(net.minecraft.network.chat.Component title) {
        if (title.getContents() instanceof TranslatableContents contents
                && (contents.getKey().equals("gui.rovenfall.admin.economy.title")
                        || contents.getKey().equals("gui.rovenfall.admin.world.title")
                        || contents.getKey().equals("gui.rovenfall.admin.rpg_boss.title")
                        || contents.getKey().equals("gui.rovenfall.admin.operations.title"))) {
            return AdministrationTextInputMenu.MAX_INPUT_LENGTH;
        }
        return AdministrationReadViewService.MAX_QUERY_LENGTH;
    }
}
