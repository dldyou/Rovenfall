package org.dldyou.rovenfall.rpg;

import com.mojang.logging.LogUtils;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.slf4j.Logger;

/** Versioned active-skill payload registration and replay/rate-limit adapter. */
public final class RpgSkillNetwork {
    private static final String NETWORK_VERSION = "1";
    private static final int MAX_REQUESTS_PER_SECOND = 20;
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<UUID, Session> SESSIONS = new HashMap<>();

    private RpgSkillNetwork() {
    }

    public static void registerPayloads(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar(NETWORK_VERSION);
        registrar.playToServer(
                RpgSkillPayloads.Activate.TYPE,
                RpgSkillPayloads.Activate.STREAM_CODEC,
                RpgSkillNetwork::handleActivate);
        registrar.playToClient(
                RpgSkillPayloads.StateSync.TYPE,
                RpgSkillPayloads.StateSync.STREAM_CODEC,
                RpgSkillNetwork::handleStateSync);
    }

    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && !(player instanceof FakePlayer)) {
            SESSIONS.put(player.getUUID(), new Session(UUID.randomUUID()));
            sync(player);
        }
    }

    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID playerId = event.getEntity().getUUID();
        SESSIONS.remove(playerId);
        RpgActiveSkillRuntime.clear(playerId);
    }

    public static void sync(ServerPlayer player) {
        Session session = SESSIONS.computeIfAbsent(player.getUUID(), ignored -> new Session(UUID.randomUUID()));
        MinecraftServer server = player.level().getServer();
        RpgPlayerState state = RpgPlayerSavedData.get(server).state(player.getUUID());
        long nextRequest = state.lastActiveSkillRequestId() == Long.MAX_VALUE
                ? Long.MAX_VALUE
                : state.lastActiveSkillRequestId() + 1;
        PacketDistributor.sendToPlayer(player, new RpgSkillPayloads.StateSync(
                RpgSkillPayloads.PACKET_REVISION,
                RpgDefinitionReloadListener.revision(server),
                nextRequest,
                ActivityXpConfig.activeSkillSlots(),
                session.id));
    }

    public static void syncAll(MinecraftServer server) {
        server.getPlayerList().getPlayers().forEach(RpgSkillNetwork::sync);
    }

    private static void handleActivate(RpgSkillPayloads.Activate payload, IPayloadContext context) {
        context.enqueueWork(() -> handleActivateOnMainThread(payload, context));
    }

    private static void handleActivateOnMainThread(
            RpgSkillPayloads.Activate payload,
            IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player) || player instanceof FakePlayer) {
            return;
        }
        Session session = SESSIONS.get(player.getUUID());
        long now = System.currentTimeMillis();
        if (session == null) {
            return;
        }
        if (!session.allow(now)) {
            auditRejected(player, session, "rate_limit", now);
            return;
        }
        if (payload.packetRevision() != RpgSkillPayloads.PACKET_REVISION
                || payload.session() == null || !session.id.equals(payload.session())) {
            auditRejected(player, session, "network_envelope", now);
            sync(player);
            return;
        }
        var definitions = RpgDefinitionReloadListener.versioned(player.level().getServer());
        var result = RpgActiveSkillService.activate(
                RpgPlayerSavedData.get(player.level().getServer()),
                definitions.snapshot(),
                definitions.revision(),
                player.getUUID(),
                new RpgActiveSkillService.ActivationRequest(
                        payload.definitionRevision(),
                        payload.requestId(),
                        payload.slot(),
                        payload.dimension(),
                        payload.targetEntityId()),
                ActivityXpConfig.activeSkillSlots(),
                player.level().getGameTime(),
                RpgActiveSkillRuntime.gateway(player));
        if (result.status() != RpgActiveSkillService.Status.SUCCESS) {
            player.sendOverlayMessage(Component.translatable(
                    "message.rovenfall.skill.activate." + result.status().name().toLowerCase(java.util.Locale.ROOT)));
            if (result.status() == RpgActiveSkillService.Status.DUPLICATE
                    || result.status() == RpgActiveSkillService.Status.INVALID_REQUEST
                    || result.status() == RpgActiveSkillService.Status.STALE_DEFINITIONS
                    || result.status() == RpgActiveSkillService.Status.WRONG_DIMENSION
                    || result.status() == RpgActiveSkillService.Status.INVALID_TARGET) {
                auditRejected(player, session, result.status().name().toLowerCase(java.util.Locale.ROOT), now);
            }
        }
        sync(player);
    }

    private static void handleStateSync(RpgSkillPayloads.StateSync payload, IPayloadContext context) {
        context.enqueueWork(() -> RpgSkillClient.accept(payload));
    }

    private static void auditRejected(ServerPlayer player, Session session, String reason, long now) {
        if (session != null && now - session.lastAuditMillis < 1_000) {
            return;
        }
        if (session != null) {
            session.lastAuditMillis = now;
        }
        LOGGER.warn("Rejected active-skill request player={} reason={} dimension={}",
                player.getUUID(), reason, player.level().dimension().identifier());
    }

    private static final class Session {
        private final UUID id;
        private long windowStartMillis;
        private int requestCount;
        private long lastAuditMillis;

        private Session(UUID id) {
            this.id = id;
        }

        private boolean allow(long now) {
            if (now < windowStartMillis || now - windowStartMillis >= 1_000) {
                windowStartMillis = now;
                requestCount = 0;
            }
            requestCount++;
            return requestCount <= MAX_REQUESTS_PER_SECOND;
        }
    }
}
