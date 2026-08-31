package org.dldyou.rovenfall.quest;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import org.dldyou.rovenfall.rpg.RpgDefinitionReloadListener;

/** Bounded, deduplicated server-to-client active-journey synchronization. */
public final class ActiveJourneyTrackerNetwork {
    public static final int SYNC_INTERVAL_TICKS = 20;
    public static final int MAX_PLAYERS_PER_TICK = 16;
    private static final String NETWORK_VERSION = "1";
    private static final Map<MinecraftServer, SyncState> STATES = new WeakHashMap<>();

    private ActiveJourneyTrackerNetwork() {
    }

    public static void registerPayloads(RegisterPayloadHandlersEvent event) {
        event.registrar(NETWORK_VERSION).playToClient(
                ActiveJourneyTrackerPayloads.Snapshot.TYPE,
                ActiveJourneyTrackerPayloads.Snapshot.STREAM_CODEC,
                ActiveJourneyTrackerNetwork::handleSnapshot);
    }

    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && !(player instanceof FakePlayer)) {
            state(player.level().getServer()).lastSnapshots.remove(player.getUUID());
            sync(player);
        }
    }

    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || player.level().getServer() == null) {
            return;
        }
        SyncState state = STATES.get(player.level().getServer());
        if (state != null) {
            state.lastSnapshots.remove(player.getUUID());
        }
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server.overworld().getGameTime() % SYNC_INTERVAL_TICKS != 0L) {
            return;
        }
        SyncState state = state(server);
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        int[] indexes = batchIndexes(players.size(), state.cursor);
        for (int index : indexes) {
            sync(players.get(index));
        }
        state.cursor = players.isEmpty() ? 0 : (state.cursor + indexes.length) % players.size();
    }

    public static void onDatapackSync(OnDatapackSyncEvent event) {
        if (event.getPlayer() == null) {
            syncAll(event.getPlayerList().getServer());
        } else {
            sync(event.getPlayer());
        }
    }

    public static void sync(ServerPlayer player) {
        if (player == null || player instanceof FakePlayer || player.connection == null
                || player.level().getServer() == null) {
            return;
        }
        MinecraftServer server = player.level().getServer();
        if (!server.isSameThread()) {
            return;
        }
        SyncState state = state(server);
        var definitions = QuestDefinitionReloadListener.versioned(server);
        QuestPlayerSavedData savedData = QuestPlayerSavedData.get(server);
        long now = System.currentTimeMillis();
        ActiveJourneyService.reconcile(savedData, definitions.snapshot(), player.getUUID(), now);
        if (!NetworkRegistry.hasChannel(
                player.connection, ActiveJourneyTrackerPayloads.Snapshot.TYPE.id())) {
            state.lastSnapshots.remove(player.getUUID());
            return;
        }
        ActiveJourneyView view = ActiveJourneyService.view(
                savedData,
                definitions.snapshot(),
                RpgDefinitionReloadListener.snapshot(server),
                player.getUUID(),
                definitions.revision(),
                now);
        ActiveJourneyTrackerPayloads.Snapshot snapshot = snapshot(view);
        if (!snapshot.isValid() || snapshot.equals(state.lastSnapshots.get(player.getUUID()))) {
            return;
        }
        PacketDistributor.sendToPlayer(player, snapshot);
        state.lastSnapshots.put(player.getUUID(), snapshot);
    }

    public static void syncAll(MinecraftServer server) {
        if (server == null || !server.isSameThread()) {
            return;
        }
        server.getPlayerList().getPlayers().forEach(ActiveJourneyTrackerNetwork::sync);
    }

    static ActiveJourneyTrackerPayloads.Snapshot snapshot(ActiveJourneyView view) {
        ActiveJourneyView.Entry entry = view.journey().orElse(null);
        if (entry == null) {
            return ActiveJourneyTrackerPayloads.Snapshot.inactive();
        }
        return new ActiveJourneyTrackerPayloads.Snapshot(
                ActiveJourneyTrackerPayloads.PACKET_REVISION,
                true,
                switch (entry.kind()) {
                    case STORY -> ActiveJourneyTrackerPayloads.JourneyKind.STORY;
                    case DAILY -> ActiveJourneyTrackerPayloads.JourneyKind.DAILY;
                    case WEEKLY -> ActiveJourneyTrackerPayloads.JourneyKind.WEEKLY;
                },
                entry.titleTranslationKey(),
                switch (entry.status()) {
                    case AVAILABLE -> ActiveJourneyTrackerPayloads.JourneyStatus.AVAILABLE;
                    case IN_PROGRESS -> ActiveJourneyTrackerPayloads.JourneyStatus.IN_PROGRESS;
                },
                switch (entry.objectiveKind()) {
                    case ACTIVITY -> ActiveJourneyTrackerPayloads.ObjectiveKind.ACTIVITY;
                    case SHOP_TRADE -> ActiveJourneyTrackerPayloads.ObjectiveKind.SHOP_TRADE;
                    case CLAIM_PURCHASE -> ActiveJourneyTrackerPayloads.ObjectiveKind.CLAIM_PURCHASE;
                    case BOSS_DEFEAT -> ActiveJourneyTrackerPayloads.ObjectiveKind.BOSS_DEFEAT;
                },
                entry.activityTargetTranslationKey().orElse(""),
                entry.progress(),
                entry.requiredCount());
    }

    static int[] batchIndexes(int playerCount, int cursor) {
        if (playerCount <= 0) {
            return new int[0];
        }
        int count = Math.min(playerCount, MAX_PLAYERS_PER_TICK);
        int start = Math.floorMod(cursor, playerCount);
        int[] indexes = new int[count];
        for (int offset = 0; offset < count; offset++) {
            indexes[offset] = (start + offset) % playerCount;
        }
        return indexes;
    }

    private static void handleSnapshot(
            ActiveJourneyTrackerPayloads.Snapshot payload, IPayloadContext context) {
        context.enqueueWork(() -> ActiveJourneyTrackerClient.accept(payload));
    }

    private static SyncState state(MinecraftServer server) {
        return STATES.computeIfAbsent(server, ignored -> new SyncState());
    }

    private static final class SyncState {
        private final Map<UUID, ActiveJourneyTrackerPayloads.Snapshot> lastSnapshots = new HashMap<>();
        private int cursor;
    }
}
