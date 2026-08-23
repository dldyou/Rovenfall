package org.dldyou.rovenfall.administration;

import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

public final class PlayerRecordService {
    private PlayerRecordService() {
    }

    static boolean observeLogin(PlatformSavedData state, UUID playerId, long timestampEpochMillis) {
        if (!state.isWritable() || timestampEpochMillis < 0) {
            return false;
        }
        return state.commitPlayerLogin(playerId, timestampEpochMillis);
    }

    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        MinecraftServer server = player.level().getServer();
        UUID playerId = player.getUUID();
        long timestampEpochMillis = System.currentTimeMillis();
        Runnable update = () -> observeLogin(PlatformSavedData.get(server), playerId, timestampEpochMillis);
        if (server.isSameThread()) {
            update.run();
        } else {
            server.execute(update);
        }
    }
}
