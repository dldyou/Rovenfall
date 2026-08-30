package org.dldyou.rovenfall.administration;

import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.common.NeoForge;

/** Replaces only the ordinary survival inventory with the Rovenfall shell. */
public final class RovenfallInventoryClient {
    private static final RovenfallMenuIdentityCache MENU_IDENTITIES = new RovenfallMenuIdentityCache();

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

    static void acceptIdentity(PlayerMenuNetwork.MenuIdentity identity) {
        if (MENU_IDENTITIES.accept(identity)) {
            replaceCurrentPlayerMenu(Minecraft.getInstance());
        }
    }

    private static void onScreenOpening(ScreenEvent.Opening event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (shouldReplace(event.getNewScreen(), minecraft.player)) {
            event.setNewScreen(new RovenfallInventoryScreen(minecraft.player));
            return;
        }
        if (event.getNewScreen() instanceof ContainerScreen screen
                && !(screen instanceof RovenfallPlayerMenuScreen)) {
            Optional<Screen> replacement = replacePlayerMenu(screen, minecraft.player);
            if (replacement.isPresent()) {
                event.setNewScreen(replacement.orElseThrow());
                return;
            }
        }
    }

    private static void replaceCurrentPlayerMenu(Minecraft minecraft) {
        if (minecraft.gui.screen() instanceof ContainerScreen screen
                && !(screen instanceof RovenfallPlayerMenuScreen)) {
            replacePlayerMenu(screen, minecraft.player).ifPresent(minecraft.gui::setScreen);
        }
    }

    private static Optional<Screen> replacePlayerMenu(ContainerScreen screen, Player player) {
        if (player == null) {
            return Optional.empty();
        }
        Optional<PlayerMenuNetwork.MenuKind> kind = MENU_IDENTITIES.consume(
                screen.getMenu().containerId, screen.getMenu().getStateId());
        if (kind.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new RovenfallPlayerMenuScreen(
                screen.getMenu(), player.getInventory(), screen.getTitle(), kind.orElseThrow()));
    }
}
