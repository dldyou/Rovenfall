package org.dldyou.rovenfall.quest;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.common.NeoForge;
import org.dldyou.rovenfall.Rovenfall;
import org.lwjgl.glfw.GLFW;

/** Physical-client storage and change narration for the latest valid tracker snapshot. */
public final class ActiveJourneyTrackerClient {
    private static final State STATE = new State();
    private static final KeyMapping.Category HUD_CATEGORY = new KeyMapping.Category(
            Identifier.fromNamespaceAndPath(Rovenfall.MOD_ID, "hud"));
    private static final KeyMapping HUD_MODE_KEY = new KeyMapping(
            "key.rovenfall.hud_mode",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_H,
            HUD_CATEGORY);
    private static DisplayMode displayMode = DisplayMode.FULL;

    private ActiveJourneyTrackerClient() {
    }

    public static void register(IEventBus modBus) {
        ActiveJourneyTrackerHud.register(modBus);
        RpgStatusHud.register(modBus);
        modBus.addListener(ActiveJourneyTrackerClient::registerKeyMappings);
        NeoForge.EVENT_BUS.addListener(ActiveJourneyTrackerClient::onClientTick);
        NeoForge.EVENT_BUS.addListener(ActiveJourneyTrackerClient::onLoggingIn);
        NeoForge.EVENT_BUS.addListener(ActiveJourneyTrackerClient::onLoggingOut);
    }

    private static void registerKeyMappings(RegisterKeyMappingsEvent event) {
        event.registerCategory(HUD_CATEGORY);
        event.register(HUD_MODE_KEY);
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        while (HUD_MODE_KEY.consumeClick()) {
            displayMode = displayMode.next();
            Component message = Component.translatable(
                    "hud.rovenfall.mode.changed",
                    Component.translatable(displayMode.translationKey()));
            minecraft.gui.hud.setOverlayMessage(message, false);
            minecraft.getNarrator().saySystemNow(message);
        }
    }

    public static void accept(ActiveJourneyTrackerPayloads.Snapshot payload) {
        Change change = STATE.accept(payload);
        if (change == Change.NONE) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        Component message = change == Change.CLEARED
                ? Component.translatable("hud.rovenfall.journey.tracker.cleared")
                : narration(payload);
        minecraft.getNarrator().saySystemNow(message);
    }

    static Component narration(ActiveJourneyTrackerPayloads.Snapshot payload) {
        var lines = ActiveJourneyTrackerHud.lines(payload);
        MutableComponent message = Component.empty().append(Component.translatable(
                "hud.rovenfall.journey.tracker.changed", lines.getFirst()));
        for (int index = 1; index < lines.size(); index++) {
            message.append(Component.literal(". "));
            message.append(lines.get(index));
        }
        return message;
    }

    public static Optional<ActiveJourneyTrackerPayloads.Snapshot> current() {
        return STATE.current();
    }

    static DisplayMode displayMode() {
        return displayMode;
    }

    private static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        STATE.clear();
    }

    private static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        STATE.clear();
    }

    enum Change {
        NONE,
        UPDATED,
        CLEARED
    }

    enum DisplayMode {
        FULL("hud.rovenfall.mode.full"),
        QUEST_ONLY("hud.rovenfall.mode.quest_only"),
        HIDDEN("hud.rovenfall.mode.hidden");

        private final String translationKey;

        DisplayMode(String translationKey) {
            this.translationKey = translationKey;
        }

        DisplayMode next() {
            DisplayMode[] values = values();
            return values[(ordinal() + 1) % values.length];
        }

        String translationKey() {
            return translationKey;
        }
    }

    static final class State {
        private ActiveJourneyTrackerPayloads.Snapshot current;

        Change accept(ActiveJourneyTrackerPayloads.Snapshot next) {
            if (next == null || !next.isValid()) {
                current = null;
                return Change.NONE;
            }
            ActiveJourneyTrackerPayloads.Snapshot previous = current;
            current = next;
            if (Objects.equals(previous, next)) {
                return Change.NONE;
            }
            if (previous == null) {
                return next.active() ? Change.UPDATED : Change.NONE;
            }
            return next.active() ? Change.UPDATED : Change.CLEARED;
        }

        Optional<ActiveJourneyTrackerPayloads.Snapshot> current() {
            return Optional.ofNullable(current);
        }

        void clear() {
            current = null;
            ActiveJourneyTrackerHud.clearCache();
        }
    }
}
