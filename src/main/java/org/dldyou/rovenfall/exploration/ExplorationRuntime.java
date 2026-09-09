package org.dldyou.rovenfall.exploration;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.dldyou.rovenfall.rpg.RpgDefinitionReloadListener;
import org.dldyou.rovenfall.rpg.RpgPlayerSavedData;

/** Movement/reload-triggered discovery checks and bounded pending reward recovery. */
public final class ExplorationRuntime {
    private static final int PLAYER_RECOVERY_BATCH = 16;
    private static final Map<MinecraftServer, RuntimeState> STATES = new WeakHashMap<>();

    private ExplorationRuntime() {
    }

    public static void register(IEventBus eventBus) {
        eventBus.addListener(ExplorationRuntime::onPlayerTick);
        eventBus.addListener(ExplorationRuntime::onServerTick);
        eventBus.addListener(ExplorationRuntime::onPlayerLoggedOut);
    }

    private static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || player instanceof FakePlayer
                || player.level().getServer() == null) {
            return;
        }
        MinecraftServer server = player.level().getServer();
        RuntimeState state = STATES.computeIfAbsent(server, ignored -> new RuntimeState());
        long revision = ExplorationDefinitionReloadListener.revision(server);
        ObservationKey observed = new ObservationKey(
                player.level().dimension(), player.blockPosition().immutable(), revision);
        if (observed.equals(state.lastObserved.put(player.getUUID(), observed))) {
            return;
        }
        ExplorationDiscoveryService.observe(
                player, ExplorationDefinitionReloadListener.snapshot(server), System.currentTimeMillis());
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server.overworld().getGameTime() % 20L != 0L) {
            return;
        }
        RuntimeState runtime = STATES.computeIfAbsent(server, ignored -> new RuntimeState());
        ExplorationPlayerSavedData exploration = ExplorationPlayerSavedData.get(server);
        var batch = exploration.playersAfter(runtime.recoveryCursor, PLAYER_RECOVERY_BATCH);
        long now = System.currentTimeMillis();
        for (var player : batch.entries()) {
            ExplorationDiscoveryService.recover(
                    exploration, RpgPlayerSavedData.get(server), RpgDefinitionReloadListener.snapshot(server),
                    player.getKey(), now);
        }
        runtime.recoveryCursor = batch.hasMore() ? batch.nextCursor().orElse(null) : null;
    }

    private static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || player.level().getServer() == null) {
            return;
        }
        RuntimeState state = STATES.get(player.level().getServer());
        if (state != null) {
            state.lastObserved.remove(player.getUUID());
        }
    }

    private record ObservationKey(ResourceKey<Level> dimension, BlockPos position, long revision) {
    }

    private static final class RuntimeState {
        private final Map<UUID, ObservationKey> lastObserved = new HashMap<>();
        private UUID recoveryCursor;
    }
}
