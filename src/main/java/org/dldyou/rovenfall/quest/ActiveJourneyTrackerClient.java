package org.dldyou.rovenfall.quest;

import java.util.Objects;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Physical-client storage and change narration for the latest valid tracker snapshot. */
public final class ActiveJourneyTrackerClient {
    private static final State STATE = new State();

    private ActiveJourneyTrackerClient() {
    }

    public static void register(IEventBus modBus) {
        ActiveJourneyTrackerHud.register(modBus);
        RpgStatusHud.register(modBus);
        NeoForge.EVENT_BUS.addListener(ActiveJourneyTrackerClient::onLoggingIn);
        NeoForge.EVENT_BUS.addListener(ActiveJourneyTrackerClient::onLoggingOut);
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
