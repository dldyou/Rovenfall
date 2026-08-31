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
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import org.dldyou.rovenfall.Rovenfall;
import org.dldyou.rovenfall.rpg.CareerDefinition;
import org.dldyou.rovenfall.rpg.RpgDefinitionReloadListener;
import org.dldyou.rovenfall.rpg.RpgPlayerSavedData;
import org.slf4j.Logger;

/** Server-authoritative entry points requested by the RPG inventory shell. */
public final class PlayerMenuNetwork {
    static final int PACKET_REVISION = 2;
    static final int MIN_OPEN_INTERVAL_TICKS = 5;
    static final int MIN_MUTATION_INTERVAL_TICKS = 20;
    static final int MAX_OPEN_PACKET_BYTES = 10;
    static final int MAX_IDENTITY_PACKET_BYTES = 20;
    static final int MAX_QUERY_PACKET_BYTES = 8_210;
    static final int MAX_INVENTORY_SUMMARY_REQUEST_PACKET_BYTES = 6;
    static final int MAX_INVENTORY_SUMMARY_PACKET_BYTES = 660;
    static final int MAX_CAREER_TRANSLATION_KEY_LENGTH = 160;
    static final String NETWORK_VERSION = "2";
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<UUID, Long> LAST_OPEN_TICK = new HashMap<>();
    private static final Map<UUID, Long> LAST_MUTATION_TICK = new HashMap<>();
    private static final Map<UUID, Long> LAST_QUERY_TICK = new HashMap<>();
    private static final Map<UUID, Long> LAST_INVENTORY_SUMMARY_TICK = new HashMap<>();
    private static final Map<UUID, Long> LAST_REJECTION_AUDIT_TICK = new HashMap<>();

    private PlayerMenuNetwork() {
    }

    public static void registerPayloads(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar(NETWORK_VERSION);
        registrar.playToServer(Open.TYPE, Open.STREAM_CODEC, PlayerMenuNetwork::handleOpen);
        registrar.playToServer(AdminQuery.TYPE, AdminQuery.STREAM_CODEC, PlayerMenuNetwork::handleAdminQuery);
        registrar.playToServer(InventorySummaryRequest.TYPE, InventorySummaryRequest.STREAM_CODEC,
                PlayerMenuNetwork::handleInventorySummaryRequest);
        registrar.playToClient(MenuIdentity.TYPE, MenuIdentity.STREAM_CODEC, PlayerMenuNetwork::handleMenuIdentity);
        registrar.playToClient(InventorySummary.TYPE, InventorySummary.STREAM_CODEC,
                PlayerMenuNetwork::handleInventorySummary);
    }

    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID playerId = event.getEntity().getUUID();
        LAST_OPEN_TICK.remove(playerId);
        LAST_MUTATION_TICK.remove(playerId);
        LAST_QUERY_TICK.remove(playerId);
        LAST_INVENTORY_SUMMARY_TICK.remove(playerId);
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

    static boolean canRequestInventorySummary(Long lastRequestTick, long gameTick) {
        return canOpen(lastRequestTick, gameTick);
    }

    static boolean isValidInventorySummaryContext(
            int packetRevision,
            boolean alive,
            boolean spectator,
            boolean infiniteMaterials,
            boolean inventoryMenu,
            boolean openScreen) {
        return packetRevision == PACKET_REVISION && alive && !spectator && !infiniteMaterials
                && (inventoryMenu || openScreen);
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
        return MenuKind.fromMenu(menu).isPresent();
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

    static void sendMenuIdentity(ServerPlayer player) {
        Optional<MenuKind> kind = MenuKind.fromMenu(player.containerMenu);
        if (kind.isEmpty() || kind.orElseThrow().isAdministration() || player.connection == null
                || !NetworkRegistry.hasChannel(player.connection, MenuIdentity.TYPE.id())) {
            return;
        }
        AbstractContainerMenu menu = player.containerMenu;
        PacketDistributor.sendToPlayer(
                player, new MenuIdentity(menu.containerId, menu.getStateId(), kind.orElseThrow()));
    }

    /** Sends the caller's private character summary; commands may request that the client opens its inventory screen. */
    public static boolean sendInventorySummary(ServerPlayer player, boolean openScreen) {
        if (player == null || player instanceof FakePlayer
                || !isValidInventorySummaryContext(PACKET_REVISION, player.isAlive(), player.isSpectator(),
                player.hasInfiniteMaterials(), player.containerMenu == player.inventoryMenu, openScreen)
                || player.connection == null
                || !NetworkRegistry.hasChannel(player.connection, InventorySummary.TYPE.id())) {
            return false;
        }
        long gameTick = player.level().getGameTime();
        if (!canRequestInventorySummary(LAST_INVENTORY_SUMMARY_TICK.get(player.getUUID()), gameTick)) {
            return false;
        }
        LAST_INVENTORY_SUMMARY_TICK.put(player.getUUID(), gameTick);
        if (openScreen && player.containerMenu != player.inventoryMenu) {
            player.closeContainer();
        }
        if (player.containerMenu != player.inventoryMenu) {
            return false;
        }
        return sendInventorySummary(player, openScreen, player.level().getServer());
    }

    private static void handleOpen(Open payload, IPayloadContext context) {
        context.enqueueWork(() -> handleOpenOnServer(payload, context));
    }

    private static void handleAdminQuery(AdminQuery payload, IPayloadContext context) {
        context.enqueueWork(() -> handleAdminQueryOnServer(payload, context));
    }

    private static void handleInventorySummaryRequest(InventorySummaryRequest payload, IPayloadContext context) {
        context.enqueueWork(() -> handleInventorySummaryRequestOnServer(payload, context));
    }

    private static void handleMenuIdentity(MenuIdentity payload, IPayloadContext context) {
        context.enqueueWork(() -> RovenfallInventoryClient.acceptIdentity(payload));
    }

    private static void handleInventorySummary(InventorySummary payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (payload.isValid()) {
                RovenfallInventoryClient.acceptSummary(payload);
            }
        });
    }

    private static void handleInventorySummaryRequestOnServer(
            InventorySummaryRequest payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player) || player instanceof FakePlayer) {
            return;
        }
        long gameTick = player.level().getGameTime();
        if (!isValidInventorySummaryContext(payload.packetRevision(), player.isAlive(), player.isSpectator(),
                player.hasInfiniteMaterials(), player.containerMenu == player.inventoryMenu, payload.openScreen())) {
            auditRejected(player, "inventory_summary_context", gameTick);
            return;
        }
        if (!sendInventorySummary(player, payload.openScreen())) {
            auditRejected(player, "inventory_summary_rate_or_channel", gameTick);
        }
    }

    private static boolean sendInventorySummary(ServerPlayer player, boolean openScreen, net.minecraft.server.MinecraftServer server) {
        if (server == null || player.connection == null
                || !NetworkRegistry.hasChannel(player.connection, InventorySummary.TYPE.id())) {
            return false;
        }
        PlatformSavedData platform = PlatformSavedData.get(server);
        long balance = platform.economyBalance(player.getUUID()).orElse(EconomyConfig.initialBalance());
        String careerTranslationKey = RpgPlayerSavedData.get(server).state(player.getUUID()).activeCareer()
                .flatMap(id -> RpgDefinitionReloadListener.snapshot(server).career(id))
                .map(CareerDefinition::translationKey)
                .orElse("");
        PacketDistributor.sendToPlayer(player, new InventorySummary(balance, careerTranslationKey, openScreen));
        return true;
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
            case QUESTS -> PlayerQuestMenu.open(player);
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
        ADMIN(4),
        QUESTS(5);

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

    public enum MenuKind {
        DASHBOARD(0, false, false),
        SHOP(1, false, false),
        CLAIM(2, false, false),
        RPG(3, false, false),
        ADMIN_HOME(4, true, false),
        ADMIN_ECONOMY(5, true, true),
        ADMIN_WORLD(6, true, true),
        ADMIN_RPG_BOSS(7, true, true),
        ADMIN_OPERATIONS(8, true, true),
        QUEST(9, false, false);

        private final int wireId;
        private final boolean administration;
        private final boolean longTextInput;

        MenuKind(int wireId, boolean administration, boolean longTextInput) {
            this.wireId = wireId;
            this.administration = administration;
            this.longTextInput = longTextInput;
        }

        public int wireId() {
            return wireId;
        }

        boolean isAdministration() {
            return administration;
        }

        boolean usesLongTextInput() {
            return longTextInput;
        }

        static Optional<MenuKind> fromWireId(int wireId) {
            for (MenuKind kind : values()) {
                if (kind.wireId == wireId) {
                    return Optional.of(kind);
                }
            }
            return Optional.empty();
        }

        static Optional<MenuKind> fromMenu(AbstractContainerMenu menu) {
            if (menu == null) {
                return Optional.empty();
            }
            return Optional.ofNullable(switch (menu) {
                case PlayerDashboardMenu ignored -> DASHBOARD;
                case PlayerShopMenu ignored -> SHOP;
                case PlayerClaimMenu ignored -> CLAIM;
                case PlayerRpgMenu ignored -> RPG;
                case PlayerQuestMenu ignored -> QUEST;
                case AdministrationControlCenterMenu ignored -> ADMIN_HOME;
                case AdministrationEconomyMenu ignored -> ADMIN_ECONOMY;
                case AdministrationWorldMenu ignored -> ADMIN_WORLD;
                case AdministrationRpgBossMenu ignored -> ADMIN_RPG_BOSS;
                case AdministrationOperationsMenu ignored -> ADMIN_OPERATIONS;
                default -> null;
            });
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

    public record MenuIdentity(
            int packetRevision, int containerId, int stateId, int kind) implements CustomPacketPayload {
        public static final Type<MenuIdentity> TYPE = new Type<>(
                Identifier.fromNamespaceAndPath(Rovenfall.MOD_ID, "player_menu_identity"));
        public static final StreamCodec<RegistryFriendlyByteBuf, MenuIdentity> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, MenuIdentity::packetRevision,
                ByteBufCodecs.VAR_INT, MenuIdentity::containerId,
                ByteBufCodecs.VAR_INT, MenuIdentity::stateId,
                ByteBufCodecs.VAR_INT, MenuIdentity::kind,
                MenuIdentity::new);

        public MenuIdentity(int containerId, int stateId, MenuKind kind) {
            this(PACKET_REVISION, containerId, stateId, kind.wireId());
        }

        @Override
        public Type<MenuIdentity> type() {
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

    /** A bounded client intent with no player identity or other player-selected state. */
    public record InventorySummaryRequest(int packetRevision, boolean openScreen) implements CustomPacketPayload {
        public static final Type<InventorySummaryRequest> TYPE = new Type<>(
                Identifier.fromNamespaceAndPath(Rovenfall.MOD_ID, "inventory_summary_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf, InventorySummaryRequest> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, InventorySummaryRequest::packetRevision,
                ByteBufCodecs.BOOL, InventorySummaryRequest::openScreen,
                InventorySummaryRequest::new);

        public InventorySummaryRequest() {
            this(PACKET_REVISION, false);
        }

        public InventorySummaryRequest(boolean openScreen) {
            this(PACKET_REVISION, openScreen);
        }

        @Override
        public Type<InventorySummaryRequest> type() {
            return TYPE;
        }
    }

    /** Private display data for the recipient's character screen; technical IDs are deliberately absent. */
    public record InventorySummary(
            int packetRevision, long balance, String careerTranslationKey, boolean openScreen)
            implements CustomPacketPayload {
        public static final Type<InventorySummary> TYPE = new Type<>(
                Identifier.fromNamespaceAndPath(Rovenfall.MOD_ID, "inventory_summary"));
        public static final StreamCodec<RegistryFriendlyByteBuf, InventorySummary> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, InventorySummary::packetRevision,
                ByteBufCodecs.VAR_LONG, InventorySummary::balance,
                ByteBufCodecs.stringUtf8(MAX_CAREER_TRANSLATION_KEY_LENGTH), InventorySummary::careerTranslationKey,
                ByteBufCodecs.BOOL, InventorySummary::openScreen,
                InventorySummary::new);

        public InventorySummary(long balance, String careerTranslationKey, boolean openScreen) {
            this(PACKET_REVISION, balance, careerTranslationKey, openScreen);
        }

        public boolean isValid() {
            return packetRevision == PACKET_REVISION && balance >= 0
                    && careerTranslationKey != null
                    && careerTranslationKey.length() <= MAX_CAREER_TRANSLATION_KEY_LENGTH;
        }

        @Override
        public Type<InventorySummary> type() {
            return TYPE;
        }
    }
}
