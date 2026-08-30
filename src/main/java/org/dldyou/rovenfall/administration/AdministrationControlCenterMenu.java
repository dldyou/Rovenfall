package org.dldyou.rovenfall.administration;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Server-authoritative, permission-filtered read-only administration navigation. */
public final class AdministrationControlCenterMenu extends ChestMenu implements AdministrationTextInputMenu {
    static final int MENU_SIZE = 54;
    static final int CONTENT_START = 9;
    static final int CONTENT_END = 45;
    static final int BACK_SLOT = 45;
    static final int PREVIOUS_SLOT = 47;
    static final int FILTER_SLOT = 49;
    static final int NEXT_SLOT = 51;
    static final int REFRESH_SLOT = 53;
    private static final int PAGE_SIZE = CONTENT_END - CONTENT_START;

    private final ServerPlayer viewer;
    private final UUID viewerId;
    private final SimpleContainer contents;
    private AdministrationReadViewService.Domain domain;
    private AdministrationReadViewService.Filter filter = AdministrationReadViewService.Filter.ALL;
    private String query = "";
    private int page;
    private long lastHandledGameTime = Long.MIN_VALUE;

    private AdministrationControlCenterMenu(
            int containerId, Inventory inventory, ServerPlayer viewer, SimpleContainer contents) {
        super(MenuType.GENERIC_9x6, containerId, inventory, contents, 6);
        this.viewer = viewer;
        this.viewerId = viewer.getUUID();
        this.contents = contents;
        render();
        PlayerMenuNetwork.seedMenuSession(this, UUID.randomUUID());
    }

    public static boolean open(ServerPlayer player) {
        if (resolveRole(player).isEmpty()) {
            player.sendSystemMessage(Component.translatable("gui.rovenfall.admin.denied"));
            return false;
        }
        player.openMenu(new SimpleMenuProvider(
                (containerId, inventory, viewer) -> new AdministrationControlCenterMenu(
                        containerId, inventory, (ServerPlayer) viewer, new SimpleContainer(MENU_SIZE)),
                Component.translatable("gui.rovenfall.admin.title")))
                .ifPresent(ignored -> PlayerMenuNetwork.sendMenuIdentity(player));
        return true;
    }

    @Override
    public void clicked(int slotIndex, int buttonNum, ContainerInput input, Player player) {
        if (!(player instanceof ServerPlayer serverPlayer)
                || !viewerId.equals(serverPlayer.getUUID())
                || slotIndex < 0 || slotIndex >= MENU_SIZE
                || !PlayerMenuNetwork.isPrimaryAction(buttonNum, input)) {
            return;
        }
        long gameTime = viewer.level().getGameTime();
        if (!PlayerDashboardMenu.canHandleClick(lastHandledGameTime, gameTime)) {
            return;
        }
        lastHandledGameTime = gameTime;

        Optional<AdminRole> currentRole = resolveRole(viewer);
        if (!canContinue(currentRole, domain)) {
            denyAndClose();
            return;
        }
        if (domain == null) {
            AdministrationReadViewService.Domain selected = domainAt(currentRole.orElseThrow(), slotIndex);
            if (selected != null) {
                if (AdministrationDomainMenuRouter.open(viewer, selected)) {
                    return;
                }
                domain = selected;
                page = 0;
                filter = AdministrationReadViewService.Filter.ALL;
                query = "";
                render();
            } else if (slotIndex == REFRESH_SLOT) {
                render();
            }
            return;
        }
        switch (slotIndex) {
            case BACK_SLOT -> {
                domain = null;
                page = 0;
                query = "";
            }
            case PREVIOUS_SLOT -> page = Math.max(0, page - 1);
            case FILTER_SLOT -> {
                filter = filter.next();
                page = 0;
            }
            case NEXT_SLOT -> page++;
            case REFRESH_SLOT -> {
            }
            default -> {
                return;
            }
        }
        render();
    }

    @Override
    public boolean applyTextInput(ServerPlayer player, String requestedQuery) {
        if (!viewerId.equals(player.getUUID()) || domain == null || requestedQuery == null
                || requestedQuery.length() > AdministrationReadViewService.MAX_QUERY_LENGTH) {
            return false;
        }
        Optional<AdminRole> currentRole = resolveRole(viewer);
        if (!canContinue(currentRole, domain)) {
            denyAndClose();
            return false;
        }
        query = requestedQuery.strip();
        page = 0;
        render();
        return true;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slotIndex) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        return player.isAlive() && viewerId.equals(player.getUUID()) && canContinue(resolveRole(viewer), domain);
    }

    static boolean canContinue(
            Optional<AdminRole> role, AdministrationReadViewService.Domain currentDomain) {
        return role != null && role.isPresent()
                && (currentDomain == null || currentDomain.allowedFor(role.orElseThrow()));
    }

    static AdministrationReadViewService.Domain domainAt(AdminRole role, int slot) {
        if (role == null || slot < CONTENT_START || slot >= CONTENT_START + AdministrationReadViewService.Domain.values().length) {
            return null;
        }
        AdministrationReadViewService.Domain domain = AdministrationReadViewService.Domain.values()[slot - CONTENT_START];
        return domain.allowedFor(role) ? domain : null;
    }

    static Optional<AdminRole> resolveRole(ServerPlayer player) {
        PlatformSavedData platform = PlatformSavedData.get(player.level().getServer());
        Optional<AdminRole> stored = platform.roleOf(player.getUUID());
        if (stored.isPresent()) {
            return stored;
        }
        return !platform.hasAnyAdminRoles() && player.permissions().hasPermission(Permissions.COMMANDS_OWNER)
                ? Optional.of(AdminRole.OWNER)
                : Optional.empty();
    }

    private void render() {
        contents.clearContent();
        Optional<AdminRole> currentRole = resolveRole(viewer);
        if (currentRole.isEmpty()) {
            denyAndClose();
            return;
        }
        if (domain == null) {
            renderHome(currentRole.orElseThrow());
        } else if (!domain.allowedFor(currentRole.orElseThrow())) {
            domain = null;
            renderHome(currentRole.orElseThrow());
        } else {
            renderDomain(currentRole.orElseThrow());
        }
        contents.setItem(REFRESH_SLOT, icon(
                Items.CLOCK, "gui.rovenfall.admin.refresh", "gui.rovenfall.admin.click"));
        broadcastChanges();
    }

    private void renderHome(AdminRole role) {
        contents.setItem(4, PlayerDashboardMenu.icon(
                Items.COMPASS,
                Component.translatable("gui.rovenfall.admin.home"),
                Component.translatable("gui.rovenfall.admin.role", Component.translatable(role.translationKey())),
                Component.translatable("gui.rovenfall.admin.read_only")));
        for (AdministrationReadViewService.Domain candidate : AdministrationReadViewService.Domain.allowedForRole(role)) {
            int slot = CONTENT_START + candidate.ordinal();
            contents.setItem(slot, PlayerDashboardMenu.icon(
                    item(candidate),
                    Component.translatable(domainKey(candidate)),
                    Component.translatable("gui.rovenfall.admin.click")));
        }
    }

    private void renderDomain(AdminRole role) {
        boolean override = PlatformSavedData.get(viewer.level().getServer()).roleOf(viewerId).isEmpty();
        AdministrationReadViewService.Page result = AdministrationReadViewService.query(
                viewer.level().getServer(), viewerId, override, role, domain, filter, query,
                page, PAGE_SIZE, Instant.now().toEpochMilli());
        if (result.status() != AdministrationReadViewService.Status.SUCCESS) {
            denyAndClose();
            return;
        }
        if (result.totalPages() > 0 && page >= result.totalPages()) {
            page = result.totalPages() - 1;
            result = AdministrationReadViewService.query(
                    viewer.level().getServer(), viewerId, override, role, domain, filter, query,
                    page, PAGE_SIZE, Instant.now().toEpochMilli());
        }
        contents.setItem(4, PlayerDashboardMenu.icon(
                item(domain), Component.translatable(domainKey(domain)),
                Component.translatable("gui.rovenfall.admin.page", page + 1, Math.max(1, result.totalPages())),
                Component.translatable("gui.rovenfall.admin.total", result.totalEntries()),
                Component.translatable(result.truncated()
                        ? "gui.rovenfall.admin.truncated"
                        : "gui.rovenfall.admin.complete"),
                Component.translatable("gui.rovenfall.admin.query", query.isBlank() ? "*" : query)));
        List<AdministrationReadViewService.Row> entries = result.entries();
        for (int index = 0; index < entries.size(); index++) {
            AdministrationReadViewService.Row row = entries.get(index);
            contents.setItem(CONTENT_START + index, PlayerDashboardMenu.icon(
                    row.attention() ? Items.REDSTONE_TORCH : item(domain),
                    Component.literal(row.title()),
                    Component.literal(row.detail()),
                    Component.translatable(row.attention()
                            ? "gui.rovenfall.admin.attention"
                            : "gui.rovenfall.admin.normal")));
        }
        if (entries.isEmpty()) {
            contents.setItem(22, icon(Items.BARRIER, "gui.rovenfall.admin.empty", "gui.rovenfall.admin.search_hint"));
        }
        contents.setItem(BACK_SLOT, icon(Items.ARROW, "gui.rovenfall.admin.back", "gui.rovenfall.admin.click"));
        if (page > 0) {
            contents.setItem(PREVIOUS_SLOT, icon(Items.ARROW, "gui.rovenfall.admin.previous", "gui.rovenfall.admin.click"));
        }
        contents.setItem(FILTER_SLOT, PlayerDashboardMenu.icon(
                filter == AdministrationReadViewService.Filter.ALL ? Items.HOPPER : Items.REDSTONE_TORCH,
                Component.translatable(filter == AdministrationReadViewService.Filter.ALL
                        ? "gui.rovenfall.admin.filter.all"
                        : "gui.rovenfall.admin.filter.attention"),
                Component.translatable("gui.rovenfall.admin.filter.click")));
        if (page + 1 < result.totalPages()) {
            contents.setItem(NEXT_SLOT, icon(Items.ARROW, "gui.rovenfall.admin.next", "gui.rovenfall.admin.click"));
        }
    }

    private void denyAndClose() {
        viewer.sendSystemMessage(Component.translatable("gui.rovenfall.admin.denied"));
        viewer.closeContainer();
    }

    private static ItemStack icon(Item item, String title, String lore) {
        return PlayerDashboardMenu.icon(item, Component.translatable(title), Component.translatable(lore));
    }

    private static String domainKey(AdministrationReadViewService.Domain domain) {
        return "gui.rovenfall.admin.domain." + domain.name().toLowerCase(java.util.Locale.ROOT);
    }

    private static Item item(AdministrationReadViewService.Domain domain) {
        return switch (domain) {
            case PLAYERS -> Items.PLAYER_HEAD;
            case CLAIMS -> Items.GRASS_BLOCK;
            case SHOPS -> Items.CHEST;
            case PORTALS -> Items.ENDER_PEARL;
            case RPG -> Items.EXPERIENCE_BOTTLE;
            case ENCOUNTERS -> Items.WITHER_SKELETON_SKULL;
            case AUDIT -> Items.WRITABLE_BOOK;
            case ALERTS -> Items.BELL;
            case METRICS -> Items.COMPARATOR;
            case RECEIPTS -> Items.WRITTEN_BOOK;
        };
    }
}
