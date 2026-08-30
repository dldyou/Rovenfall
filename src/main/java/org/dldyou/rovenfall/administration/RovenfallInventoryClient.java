package org.dldyou.rovenfall.administration;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.common.NeoForge;
import org.dldyou.rovenfall.Rovenfall;

/** Replaces only the ordinary survival inventory with the Rovenfall shell. */
public final class RovenfallInventoryClient {
    private static final RovenfallMenuIdentityCache MENU_IDENTITIES = new RovenfallMenuIdentityCache();
    private static final KeyMapping.Category CHARACTER_CATEGORY = new KeyMapping.Category(
            Identifier.fromNamespaceAndPath(Rovenfall.MOD_ID, "character"));
    private static final KeyMapping CHARACTER_SCREEN_KEY = new KeyMapping(
            "key.rovenfall.character_screen",
            InputConstants.Type.KEYSYM,
            InputConstants.KEY_I,
            CHARACTER_CATEGORY);
    private static PlayerMenuNetwork.InventorySummary inventorySummary;
    private static UUID summaryOwner;

    private RovenfallInventoryClient() {
    }

    public static void register(IEventBus modBus) {
        modBus.addListener(RovenfallInventoryClient::registerKeyMappings);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, RovenfallInventoryClient::onScreenOpening);
        NeoForge.EVENT_BUS.addListener(RovenfallInventoryClient::onClientTick);
    }

    static boolean shouldReplace(Screen screen, Player player) {
        return screen != null && screen.getClass() == InventoryScreen.class
                && player != null && player.isAlive() && !player.isSpectator()
                && !player.hasInfiniteMaterials() && player.containerMenu == player.inventoryMenu;
    }

    static void request(PlayerMenuNetwork.MenuTarget target) {
        ClientPacketDistributor.sendToServer(new PlayerMenuNetwork.Open(target));
    }

    static void requestSummary() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.getConnection() == null) {
            return;
        }
        if (!minecraft.player.getUUID().equals(summaryOwner)) {
            inventorySummary = null;
            summaryOwner = minecraft.player.getUUID();
        }
        ClientPacketDistributor.sendToServer(new PlayerMenuNetwork.InventorySummaryRequest());
    }

    static Optional<PlayerMenuNetwork.InventorySummary> summary(Player player) {
        if (player == null || inventorySummary == null || !player.getUUID().equals(summaryOwner)) {
            return Optional.empty();
        }
        return Optional.of(inventorySummary);
    }

    static void acceptSummary(PlayerMenuNetwork.InventorySummary summary) {
        if (summary == null || !summary.isValid()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (!canOpenCharacterScreen(minecraft.player)) {
            return;
        }
        inventorySummary = summary;
        summaryOwner = minecraft.player.getUUID();
        if (summary.openScreen() && !(minecraft.gui.screen() instanceof RovenfallInventoryScreen)) {
            minecraft.gui.setScreen(new RovenfallInventoryScreen(minecraft.player));
        }
    }

    static void acceptIdentity(PlayerMenuNetwork.MenuIdentity identity) {
        if (MENU_IDENTITIES.accept(identity)) {
            replaceCurrentPlayerMenu(Minecraft.getInstance());
        }
    }

    private static void registerKeyMappings(RegisterKeyMappingsEvent event) {
        event.registerCategory(CHARACTER_CATEGORY);
        event.register(CHARACTER_SCREEN_KEY);
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            inventorySummary = null;
            summaryOwner = null;
        }
        while (CHARACTER_SCREEN_KEY.consumeClick()) {
            Screen screen = minecraft.gui.screen();
            if (!canUseCharacterScreen(minecraft.player)
                    || screen instanceof RovenfallInventoryScreen) {
                continue;
            }
            if (canOpenCharacterScreen(minecraft.player)
                    && (screen == null || screen instanceof InventoryScreen)) {
                minecraft.gui.setScreen(new RovenfallInventoryScreen(minecraft.player));
            } else if (screen instanceof ContainerScreen) {
                ClientPacketDistributor.sendToServer(new PlayerMenuNetwork.InventorySummaryRequest(true));
            }
        }
    }

    private static boolean canUseCharacterScreen(Player player) {
        return player != null && player.isAlive() && !player.isSpectator() && !player.hasInfiniteMaterials();
    }

    private static boolean canOpenCharacterScreen(Player player) {
        return canUseCharacterScreen(player) && player.containerMenu == player.inventoryMenu;
    }

    private static void onScreenOpening(ScreenEvent.Opening event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (shouldReplace(event.getNewScreen(), minecraft.player)) {
            event.setNewScreen(new RovenfallInventoryScreen(minecraft.player));
            return;
        }
        if (event.getNewScreen() instanceof ContainerScreen screen
                && !isRovenfallPlayerMenuScreen(screen)) {
            Optional<Screen> replacement = replacePlayerMenu(screen, minecraft.player);
            if (replacement.isPresent()) {
                event.setNewScreen(replacement.orElseThrow());
                return;
            }
        }
    }

    private static void replaceCurrentPlayerMenu(Minecraft minecraft) {
        if (minecraft.gui.screen() instanceof ContainerScreen screen
                && !isRovenfallPlayerMenuScreen(screen)) {
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
        PlayerMenuNetwork.MenuKind resolved = kind.orElseThrow();
        return Optional.of(resolved.isAdministration()
                ? new RovenfallAdministrationMenuScreen(
                        screen.getMenu(), player.getInventory(), screen.getTitle(), resolved)
                : new RovenfallCustomPlayerMenuScreen(
                        screen.getMenu(), player.getInventory(), screen.getTitle(), resolved));
    }

    private static boolean isRovenfallPlayerMenuScreen(ContainerScreen screen) {
        return screen instanceof RovenfallAdministrationMenuScreen
                || screen instanceof RovenfallCustomPlayerMenuScreen;
    }
}
