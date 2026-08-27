package org.dldyou.rovenfall.administration;

import java.time.Instant;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

public final class WildernessResetEvents {
    private WildernessResetEvents() {
    }

    public static void register(IEventBus eventBus) {
        eventBus.addListener(WildernessResetEvents::onServerAboutToStart);
        eventBus.addListener(WildernessResetEvents::onServerStarted);
    }

    private static void onServerAboutToStart(ServerAboutToStartEvent event) {
        try {
            WildernessResetService.applyPendingBeforeLevels(event.getServer());
        } catch (WildernessResetStore.StoreException exception) {
            throw new IllegalStateException("Wilderness reset recovery requires operator intervention", exception);
        }
    }

    private static void onServerStarted(ServerStartedEvent event) {
        try {
            WildernessResetService.finishLifecycle(event.getServer(), Instant.now().toEpochMilli());
        } catch (WildernessResetStore.StoreException exception) {
            throw new IllegalStateException("Wilderness reset completion requires operator intervention", exception);
        }
    }
}
