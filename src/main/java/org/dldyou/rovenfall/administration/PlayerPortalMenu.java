package org.dldyou.rovenfall.administration;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundTrackedWaypointPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.waypoints.Waypoint;
import net.minecraft.world.waypoints.WaypointStyleAssets;
import net.minecraft.world.phys.Vec3;
import org.dldyou.rovenfall.world.PortalDefinition;
import org.dldyou.rovenfall.world.WorldTopology;

/** Command-free portal discovery that delegates every travel mutation to the portal domain. */
public final class PlayerPortalMenu extends ChestMenu implements AdministrationTextInputMenu {
    static final int MENU_SIZE = 54;
    static final int PAGE_SIZE = PlayerPortalView.PAGE_SIZE;
    private static final int CONTENT_START = 9;
    private static final int BACK_SLOT = 45;
    private static final int PREVIOUS_SLOT = 48;
    private static final int PRIMARY_SLOT = 49;
    private static final int NEXT_SLOT = 50;
    private static final int REFRESH_SLOT = 53;
    static final UUID NAVIGATION_MARKER_ID =
            UUID.fromString("5e2f10b8-13ef-4ec8-a2b9-11696e59e6c9");

    enum Page {
        LIST,
        DETAIL
    }

    enum Action {
        NONE,
        SELECT,
        BACK,
        PREVIOUS,
        CLEAR_NAVIGATION,
        NAVIGATE,
        NEXT,
        TRAVEL,
        REFRESH
    }

    private final ServerPlayer viewer;
    private final UUID viewerId;
    private final SimpleContainer content;
    private Page page = Page.LIST;
    private int listPage;
    private String query = "";
    private PlayerPortalView renderedView;
    private List<PlayerPortalView.Row> displayedRows = List.of();
    private PlayerPortalView.Row selected;
    private long lastHandledGameTime = Long.MIN_VALUE;

    private PlayerPortalMenu(
            int containerId,
            Inventory inventory,
            ServerPlayer viewer,
            SimpleContainer content) {
        super(MenuType.GENERIC_9x6, containerId, inventory, content, 6);
        this.viewer = viewer;
        this.viewerId = viewer.getUUID();
        this.content = content;
        render();
        PlayerMenuNetwork.seedMenuSession(this, UUID.randomUUID());
    }

    public static void open(ServerPlayer player) {
        player.openMenu(new SimpleMenuProvider(
                (containerId, inventory, viewer) -> new PlayerPortalMenu(
                        containerId, inventory, (ServerPlayer) viewer, new SimpleContainer(MENU_SIZE)),
                Component.translatable("gui.rovenfall.portal.title")))
                .ifPresent(ignored -> PlayerMenuNetwork.sendMenuIdentity(player));
    }

    @Override
    public void clicked(int slotIndex, int buttonNum, ContainerInput input, Player player) {
        if (!(player instanceof ServerPlayer serverPlayer)
                || !viewerId.equals(serverPlayer.getUUID())
                || slotIndex < 0
                || slotIndex >= MENU_SIZE
                || !PlayerMenuNetwork.isPrimaryAction(buttonNum, input)) {
            return;
        }
        Action action = actionAt(page, slotIndex);
        long gameTime = viewer.level().getGameTime();
        if (action == Action.NONE
                || !PlayerDashboardMenu.canHandleClick(lastHandledGameTime, gameTime)) {
            return;
        }
        lastHandledGameTime = gameTime;
        switch (action) {
            case SELECT -> select(slotIndex);
            case BACK -> back();
            case PREVIOUS -> previous();
            case CLEAR_NAVIGATION -> clearNavigation();
            case NAVIGATE -> navigate();
            case NEXT -> next();
            case TRAVEL -> travel();
            case REFRESH -> {
                resetToList();
                render();
            }
            case NONE -> {
            }
        }
    }

    @Override
    public boolean applyTextInput(ServerPlayer player, String input) {
        if (player == null || !viewerId.equals(player.getUUID()) || page != Page.LIST
                || !validQuery(input)) {
            return false;
        }
        query = input.strip();
        listPage = 0;
        selected = null;
        render();
        return true;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slotIndex) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        return player.isAlive() && viewerId.equals(player.getUUID());
    }

    static Action actionAt(Page page, int slot) {
        if (slot == BACK_SLOT) {
            return Action.BACK;
        }
        if (slot == REFRESH_SLOT) {
            return Action.REFRESH;
        }
        if (page == Page.LIST) {
            if (slot == PREVIOUS_SLOT) {
                return Action.PREVIOUS;
            }
            if (slot == PRIMARY_SLOT) {
                return Action.CLEAR_NAVIGATION;
            }
            if (slot == NEXT_SLOT) {
                return Action.NEXT;
            }
            return contentOffset(slot) >= 0 ? Action.SELECT : Action.NONE;
        }
        if (slot == PREVIOUS_SLOT) {
            return Action.CLEAR_NAVIGATION;
        }
        if (slot == PRIMARY_SLOT) {
            return Action.NAVIGATE;
        }
        return slot == NEXT_SLOT ? Action.TRAVEL : Action.NONE;
    }

    static boolean validQuery(String value) {
        return value != null
                && value.length() <= PlayerPortalView.MAX_QUERY_LENGTH
                && value.indexOf('\n') < 0
                && value.indexOf('\r') < 0;
    }

    private void select(int slot) {
        int offset = contentOffset(slot);
        if (offset < 0 || offset >= displayedRows.size()) {
            render();
            return;
        }
        PlayerPortalView.Row row = displayedRows.get(offset);
        if (!selectionFresh(platform(), row)) {
            stale();
            return;
        }
        selected = row;
        page = Page.DETAIL;
        render();
    }

    private void previous() {
        listPage = Math.max(0, listPage - 1);
        render();
    }

    private void next() {
        if (renderedView != null && listPage + 1 < renderedView.totalPages()) {
            listPage++;
        }
        render();
    }

    private void back() {
        if (page == Page.DETAIL) {
            resetToList();
            render();
            return;
        }
        PlayerDashboardMenu.open(viewer);
    }

    private void navigate() {
        if (!freshSelection()) {
            stale();
            return;
        }
        PortalDefinition.Endpoint origin = selected.origin();
        if (!viewer.level().dimension().equals(origin.dimension())) {
            message("gui.rovenfall.portal.navigation.other_world");
            return;
        }
        viewer.connection.send(navigationPacket(origin));
        message("gui.rovenfall.portal.navigation.started");
        viewer.closeContainer();
    }

    private void clearNavigation() {
        viewer.connection.send(clearNavigationPacket());
        message("gui.rovenfall.portal.navigation.cleared");
    }

    private void travel() {
        if (!freshSelection()) {
            stale();
            return;
        }
        if (!PlayerMenuNetwork.beginMutation(viewerId, viewer.level().getGameTime())) {
            message("gui.rovenfall.player.rate_limit");
            return;
        }
        long now = now();
        PortalTravelService.TravelResult result = PortalTravelService.travel(
                platform(), viewer, selected.portalId(), now, UUID.randomUUID());
        if (result.status() == PortalTravelService.Status.SUCCESS) {
            message("gui.rovenfall.portal.travel.success");
            viewer.closeContainer();
            return;
        }
        if (result.status() == PortalTravelService.Status.COOLDOWN
                || result.status() == PortalTravelService.Status.COMBAT_LOCKED) {
            message(
                    "portal.rovenfall.travel.error." + result.status().name().toLowerCase(Locale.ROOT),
                    secondsUntil(result.retryAtEpochMillis(), now));
        } else {
            message("portal.rovenfall.travel.error." + result.status().name().toLowerCase(Locale.ROOT));
        }
        render();
    }

    private void render() {
        PlatformSavedData state = platform();
        if (page == Page.DETAIL && !selectionFresh(state, selected)) {
            resetToList();
        }
        content.clearContent();
        if (page == Page.LIST) {
            renderList(state);
        } else {
            renderDetail(state);
        }
        content.setItem(REFRESH_SLOT, PlayerDashboardMenu.icon(
                Items.CLOCK,
                Component.translatable("gui.rovenfall.player.refresh"),
                Component.translatable("gui.rovenfall.player.click")));
        broadcastChanges();
    }

    private void renderList(PlatformSavedData state) {
        renderedView = PlayerPortalView.create(
                state, viewer.level().dimension(), viewer.position(), query, listPage);
        listPage = renderedView.page();
        ItemStack header = PlayerDashboardMenu.icon(
                Items.ENDER_EYE,
                Component.translatable("gui.rovenfall.portal.title"),
                Component.translatable("gui.rovenfall.portal.summary", renderedView.totalEntries()),
                pageLine(renderedView.page(), renderedView.totalPages(), renderedView.totalEntries()),
                Component.translatable("gui.rovenfall.portal.search.hint"));
        AdministrationFormMarker.writeSearch(header);
        content.setItem(4, header);

        displayedRows = renderedView.entries();
        for (int index = 0; index < displayedRows.size(); index++) {
            content.setItem(CONTENT_START + index, portalIcon(displayedRows.get(index), state, true));
        }
        if (displayedRows.isEmpty()) {
            content.setItem(22, PlayerDashboardMenu.icon(
                    Items.PAPER,
                    Component.translatable("gui.rovenfall.portal.empty"),
                    Component.translatable("gui.rovenfall.portal.empty.hint")));
        }
        addListNavigation();
    }

    private void renderDetail(PlatformSavedData state) {
        renderedView = null;
        displayedRows = List.of();
        if (selected == null) {
            resetToList();
            renderList(state);
            return;
        }
        content.setItem(4, portalIcon(selected, state, false));
        content.setItem(BACK_SLOT, button(Items.ARROW, "gui.rovenfall.player.back"));
        content.setItem(PREVIOUS_SLOT, button(Items.BARRIER, "gui.rovenfall.portal.navigation.clear"));
        if (selected.currentDimension()) {
            content.setItem(PRIMARY_SLOT, button(Items.COMPASS, "gui.rovenfall.portal.navigation.start"));
        }
        long now = now();
        Availability availability = availability(state, viewerId, selected, now);
        if (availability.status() == PortalTravelService.Status.SUCCESS) {
            content.setItem(NEXT_SLOT, PlayerDashboardMenu.icon(
                    Items.ENDER_PEARL,
                    Component.translatable("gui.rovenfall.portal.travel"),
                    Component.translatable("gui.rovenfall.portal.travel.confirm"),
                    Component.translatable("gui.rovenfall.player.click")));
        }
    }

    private ItemStack portalIcon(PlayerPortalView.Row row, PlatformSavedData state, boolean clickable) {
        long now = now();
        Availability availability = availability(state, viewerId, row, now);
        java.util.ArrayList<Component> lore = new java.util.ArrayList<>();
        lore.add(Component.translatable(
                "gui.rovenfall.portal.origin", dimensionName(row.origin().dimension())));
        lore.add(Component.translatable(
                "gui.rovenfall.portal.destination", dimensionName(row.destination().dimension())));
        if (row.distanceBlocks().isPresent()) {
            lore.add(Component.translatable(
                    "gui.rovenfall.portal.distance",
                    Math.max(0L, Math.round(row.distanceBlocks().orElseThrow()))));
        } else {
            lore.add(Component.translatable("gui.rovenfall.portal.distance.other_world"));
        }
        lore.add(statusLine(availability, now));
        if (clickable) {
            lore.add(Component.translatable("gui.rovenfall.player.click"));
        }
        lore.add(Component.translatable(
                "gui.rovenfall.portal.technical",
                row.portalId().toString(),
                endpoint(row.origin()),
                endpoint(row.destination())));
        return PlayerDashboardMenu.icon(
                availability.status() == PortalTravelService.Status.SUCCESS
                        ? Items.ENDER_PEARL
                        : row.currentDimension() ? Items.COMPASS : Items.OBSIDIAN,
                Component.translatable(
                        "gui.rovenfall.portal.card", dimensionName(row.destination().dimension())),
                lore.toArray(Component[]::new));
    }

    private void addListNavigation() {
        content.setItem(BACK_SLOT, button(Items.ARROW, "gui.rovenfall.player.back"));
        if (renderedView.page() > 0) {
            content.setItem(PREVIOUS_SLOT, button(Items.ARROW, "gui.rovenfall.player.previous"));
        }
        content.setItem(PRIMARY_SLOT, button(Items.BARRIER, "gui.rovenfall.portal.navigation.clear"));
        if (renderedView.page() + 1 < renderedView.totalPages()) {
            content.setItem(NEXT_SLOT, button(Items.ARROW, "gui.rovenfall.player.next"));
        }
    }

    static Availability availability(
            PlatformSavedData state, UUID viewerId, PlayerPortalView.Row row, long now) {
        if (!state.isWritable()) {
            return new Availability(PortalTravelService.Status.READ_ONLY_SCHEMA, 0L);
        }
        if (state.isWildernessOperationLocked()) {
            return new Availability(PortalTravelService.Status.WILDERNESS_LOCKED, 0L);
        }
        if (!state.portalProtectionIntact(row.portalId(), row.expectedDefinition())) {
            return new Availability(PortalTravelService.Status.PROTECTION_UNAVAILABLE, 0L);
        }
        if (!row.currentDimension()) {
            return new Availability(PortalTravelService.Status.WRONG_DIMENSION, 0L);
        }
        if (!row.withinUseDistance()) {
            return new Availability(PortalTravelService.Status.TOO_FAR, 0L);
        }
        long cooldown = state.portalCooldownUntil(viewerId, row.portalId());
        if (cooldown > now) {
            return new Availability(PortalTravelService.Status.COOLDOWN, cooldown);
        }
        long lastCombat = state.portalCombatTimestamp(viewerId).orElse(0L);
        if (!row.expectedDefinition().allowCombat() && lastCombat > 0
                && (lastCombat > now || now - lastCombat < PortalTravelService.COMBAT_LOCK_MILLIS)) {
            long retryAt = lastCombat > Long.MAX_VALUE - PortalTravelService.COMBAT_LOCK_MILLIS
                    ? Long.MAX_VALUE
                    : lastCombat + PortalTravelService.COMBAT_LOCK_MILLIS;
            return new Availability(PortalTravelService.Status.COMBAT_LOCKED, retryAt);
        }
        return new Availability(PortalTravelService.Status.SUCCESS, 0L);
    }

    private static Component statusLine(Availability availability, long now) {
        return switch (availability.status()) {
            case SUCCESS -> Component.translatable("gui.rovenfall.portal.status.ready");
            case WRONG_DIMENSION -> Component.translatable("gui.rovenfall.portal.status.other_world");
            case TOO_FAR -> Component.translatable("gui.rovenfall.portal.status.guidance");
            case COOLDOWN, COMBAT_LOCKED -> Component.translatable(
                    "portal.rovenfall.travel.error."
                            + availability.status().name().toLowerCase(Locale.ROOT),
                    secondsUntil(availability.retryAt(), now));
            default -> Component.translatable(
                    "portal.rovenfall.travel.error."
                            + availability.status().name().toLowerCase(Locale.ROOT));
        };
    }

    static Component dimensionName(ResourceKey<Level> dimension) {
        if (WorldTopology.isHub(dimension)) {
            return Component.translatable("gui.rovenfall.portal.world.hub");
        }
        if (WorldTopology.isWilderness(dimension)) {
            return Component.translatable("gui.rovenfall.portal.world.wilderness");
        }
        if (Level.NETHER.equals(dimension)) {
            return Component.translatable("gui.rovenfall.portal.world.nether");
        }
        if (Level.END.equals(dimension)) {
            return Component.translatable("gui.rovenfall.portal.world.end");
        }
        return Component.translatable("gui.rovenfall.portal.world.other");
    }

    static ClientboundTrackedWaypointPacket navigationPacket(PortalDefinition.Endpoint origin) {
        Waypoint.Icon icon = new Waypoint.Icon();
        icon.style = WaypointStyleAssets.BOWTIE;
        icon.color = Optional.of(0x6CC4FF);
        return ClientboundTrackedWaypointPacket.addWaypointChunk(
                NAVIGATION_MARKER_ID, icon,
                new ChunkPos(origin.position().getX() >> 4, origin.position().getZ() >> 4));
    }

    static ClientboundTrackedWaypointPacket clearNavigationPacket() {
        return ClientboundTrackedWaypointPacket.removeWaypoint(NAVIGATION_MARKER_ID);
    }

    private boolean freshSelection() {
        return selectionFresh(platform(), selected);
    }

    static boolean selectionFresh(PlatformSavedData state, PlayerPortalView.Row row) {
        return state != null
                && row != null
                && row.fresh(state)
                && state.portalProtectionIntact(row.portalId(), row.expectedDefinition());
    }

    private void stale() {
        message("gui.rovenfall.portal.stale");
        resetToList();
        render();
    }

    private void resetToList() {
        page = Page.LIST;
        selected = null;
    }

    private PlatformSavedData platform() {
        return PlatformSavedData.get(viewer.level().getServer());
    }

    private void message(String key, Object... arguments) {
        viewer.sendOverlayMessage(Component.translatable(key, arguments));
    }

    private static ItemStack button(net.minecraft.world.item.Item item, String key) {
        return PlayerDashboardMenu.icon(
                item,
                Component.translatable(key),
                Component.translatable("gui.rovenfall.player.click"));
    }

    private static Component pageLine(int page, int pages, int entries) {
        return Component.translatable(
                "gui.rovenfall.player.page", entries == 0 ? 0 : page + 1, pages, entries);
    }

    private static int contentOffset(int slot) {
        return slot >= CONTENT_START && slot < CONTENT_START + PAGE_SIZE
                ? slot - CONTENT_START
                : -1;
    }

    private static String endpoint(PortalDefinition.Endpoint endpoint) {
        return endpoint.dimension().identifier()
                + "@" + endpoint.position().getX()
                + "," + endpoint.position().getY()
                + "," + endpoint.position().getZ();
    }

    private static long secondsUntil(long retryAt, long now) {
        return retryAt <= now ? 1L : Math.max(1L, Math.ceilDiv(retryAt - now, 1_000L));
    }

    private static long now() {
        return Instant.now().toEpochMilli();
    }

    record Availability(PortalTravelService.Status status, long retryAt) {
    }
}
