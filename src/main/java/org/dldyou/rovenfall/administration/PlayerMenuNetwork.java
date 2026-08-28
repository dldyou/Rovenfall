package org.dldyou.rovenfall.administration;

import com.mojang.logging.LogUtils;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.dldyou.rovenfall.Rovenfall;
import org.slf4j.Logger;

/** Server-authoritative entry points requested by the RPG inventory shell. */
public final class PlayerMenuNetwork {
    static final int PACKET_REVISION = 1;
    static final int MIN_OPEN_INTERVAL_TICKS = 5;
    static final int MIN_MUTATION_INTERVAL_TICKS = 20;
    static final int MAX_OPEN_PACKET_BYTES = 10;
    static final int MAX_QUERY_PACKET_BYTES = 8_210;
    private static final String NETWORK_VERSION = "1";
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<UUID, Long> LAST_OPEN_TICK = new HashMap<>();
    private static final Map<UUID, Long> LAST_MUTATION_TICK = new HashMap<>();
    private static final Map<UUID, Long> LAST_QUERY_TICK = new HashMap<>();
    private static final Map<UUID, Long> LAST_REJECTION_AUDIT_TICK = new HashMap<>();

    private PlayerMenuNetwork() {
    }

    public static void registerPayloads(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar(NETWORK_VERSION);
        registrar.playToServer(Open.TYPE, Open.STREAM_CODEC, PlayerMenuNetwork::handleOpen);
        registrar.playToServer(AdminQuery.TYPE, AdminQuery.STREAM_CODEC, PlayerMenuNetwork::handleAdminQuery);
    }

    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID playerId = event.getEntity().getUUID();
        LAST_OPEN_TICK.remove(playerId);
        LAST_MUTATION_TICK.remove(playerId);
        LAST_QUERY_TICK.remove(playerId);
        LAST_REJECTION_AUDIT_TICK.remove(playerId);
    }

    static boolean canOpen(Long lastOpenTick, long gameTick) {
        return lastOpenTick == null || gameTick < lastOpenTick
                || gameTick - lastOpenTick >= MIN_OPEN_INTERVAL_TICKS;
    }

    static boolean canMutate(Long lastMutationTick, long gameTick) {
        return lastMutationTick == null || gameTick < lastMutationTick
                || gameTick - lastMutationTick >= MIN_MUTATION_INTERVAL_TICKS;
    }

    static boolean beginMutation(UUID playerId, long gameTick) {
        Long lastMutationTick = LAST_MUTATION_TICK.get(playerId);
        if (!canMutate(lastMutationTick, gameTick)) {
            return false;
        }
        LAST_MUTATION_TICK.put(playerId, gameTick);
        return true;
    }

    static boolean isPrimaryAction(int button, ContainerInput input) {
        return button == 0 && input == ContainerInput.PICKUP;
    }

    public static boolean isPlayerMenu(AbstractContainerMenu menu) {
        return menu instanceof PlayerDashboardMenu
                || menu instanceof PlayerShopMenu
                || menu instanceof PlayerClaimMenu
                || menu instanceof PlayerRpgMenu
                || menu instanceof AdministrationControlCenterMenu
                || menu instanceof AdministrationEconomyMenu
                || menu instanceof AdministrationWorldMenu
                || menu instanceof AdministrationRpgBossMenu
                || menu instanceof AdministrationOperationsMenu;
    }

    public static boolean isCurrentSession(
            int menuContainerId, int menuStateId, int packetContainerId, int packetStateId) {
        return menuContainerId == packetContainerId && menuStateId == packetStateId;
    }

    static int sessionStateId(UUID nonce) {
        return 1 + Math.floorMod(nonce.hashCode(), 32_767);
    }

    static void seedMenuSession(AbstractContainerMenu menu, UUID nonce) {
        menu.setItem(0, sessionStateId(nonce), menu.getSlot(0).getItem());
    }

    private static void handleOpen(Open payload, IPayloadContext context) {
        context.enqueueWork(() -> handleOpenOnServer(payload, context));
    }

    private static void handleAdminQuery(AdminQuery payload, IPayloadContext context) {
        context.enqueueWork(() -> handleAdminQueryOnServer(payload, context));
    }

    private static void handleAdminQueryOnServer(AdminQuery payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player) || player instanceof FakePlayer) {
            return;
        }
        long gameTick = player.level().getGameTime();
        var menu = player.containerMenu;
        if (payload.packetRevision() != PACKET_REVISION
                || payload.query().length() > AdministrationTextInputMenu.MAX_INPUT_LENGTH
                || !(menu instanceof AdministrationTextInputMenu inputMenu)
                || !isCurrentSession(menu.containerId, menu.getStateId(), payload.containerId(), payload.stateId())
                || !canOpen(LAST_QUERY_TICK.get(player.getUUID()), gameTick)) {
            auditRejected(player, "admin_query", gameTick);
            return;
        }
        LAST_QUERY_TICK.put(player.getUUID(), gameTick);
        if (!inputMenu.applyTextInput(player, payload.query())) {
            auditRejected(player, "admin_query_rejected", gameTick);
        }
    }

    private static void handleOpenOnServer(Open payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player) || player instanceof FakePlayer) {
            return;
        }
        long gameTick = player.level().getGameTime();
        Optional<MenuTarget> target = MenuTarget.fromWireId(payload.target());
        if (payload.packetRevision() != PACKET_REVISION || target.isEmpty()) {
            auditRejected(player, "network_envelope", gameTick);
            return;
        }
        if (!canOpen(LAST_OPEN_TICK.get(player.getUUID()), gameTick)) {
            auditRejected(player, "rate_limit", gameTick);
            return;
        }
        if (!player.isAlive() || player.isSpectator() || player.hasInfiniteMaterials()
                || player.containerMenu != player.inventoryMenu) {
            auditRejected(player, "invalid_context", gameTick);
            return;
        }
        LAST_OPEN_TICK.put(player.getUUID(), gameTick);
        switch (target.orElseThrow()) {
            case OVERVIEW -> PlayerDashboardMenu.open(player);
            case CLAIMS -> PlayerClaimMenu.open(player);
            case SKILLS -> PlayerRpgMenu.open(player);
            case SHOPS -> PlayerShopMenu.open(player);
            case ADMIN -> AdministrationControlCenterMenu.open(player);
        }
    }

    private static void auditRejected(ServerPlayer player, String reason, long gameTick) {
        Long lastAuditTick = LAST_REJECTION_AUDIT_TICK.get(player.getUUID());
        if (lastAuditTick != null && gameTick >= lastAuditTick && gameTick - lastAuditTick < 20) {
            return;
        }
        LAST_REJECTION_AUDIT_TICK.put(player.getUUID(), gameTick);
        LOGGER.warn("Rejected player-menu request player={} reason={} dimension={}",
                player.getUUID(), reason, player.level().dimension().identifier());
    }

    public enum MenuTarget {
        OVERVIEW(0),
        CLAIMS(1),
        SKILLS(2),
        SHOPS(3),
        ADMIN(4);

        private final int wireId;

        MenuTarget(int wireId) {
            this.wireId = wireId;
        }

        public int wireId() {
            return wireId;
        }

        static Optional<MenuTarget> fromWireId(int wireId) {
            for (MenuTarget target : values()) {
                if (target.wireId == wireId) {
                    return Optional.of(target);
                }
            }
            return Optional.empty();
        }
    }

    public record Open(int packetRevision, int target) implements CustomPacketPayload {
        public static final Type<Open> TYPE = new Type<>(
                Identifier.fromNamespaceAndPath(Rovenfall.MOD_ID, "open_player_menu"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Open> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Open::packetRevision,
                ByteBufCodecs.VAR_INT, Open::target,
                Open::new);

        public Open(MenuTarget target) {
            this(PACKET_REVISION, target.wireId());
        }

        @Override
        public Type<Open> type() {
            return TYPE;
        }
    }

    public record AdminQuery(
            int packetRevision, int containerId, int stateId, String query) implements CustomPacketPayload {
        public static final Type<AdminQuery> TYPE = new Type<>(
                Identifier.fromNamespaceAndPath(Rovenfall.MOD_ID, "admin_menu_query"));
        public static final StreamCodec<RegistryFriendlyByteBuf, AdminQuery> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, AdminQuery::packetRevision,
                ByteBufCodecs.VAR_INT, AdminQuery::containerId,
                ByteBufCodecs.VAR_INT, AdminQuery::stateId,
                ByteBufCodecs.stringUtf8(AdministrationTextInputMenu.MAX_INPUT_LENGTH), AdminQuery::query,
                AdminQuery::new);

        public AdminQuery(int containerId, int stateId, String query) {
            this(PACKET_REVISION, containerId, stateId, query);
        }

        @Override
        public Type<AdminQuery> type() {
            return TYPE;
        }
    }
}
