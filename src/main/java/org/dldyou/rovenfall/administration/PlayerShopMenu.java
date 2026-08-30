package org.dldyou.rovenfall.administration;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
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
import net.minecraft.world.item.component.ItemLore;
import org.dldyou.rovenfall.economy.ShopInstance;

/** Native shop catalog and confirmation flow; all mutations remain inside {@link ShopTradeService}. */
public final class PlayerShopMenu extends ChestMenu {
    static final int MENU_SIZE = 54;
    static final int PAGE_SIZE = 36;
    private static final int CONTENT_START = 9;
    private static final int BACK_SLOT = 45;
    private static final int PREVIOUS_SLOT = 48;
    private static final int NEXT_SLOT = 50;
    private static final int REFRESH_SLOT = 53;

    enum Page {
        SHOPS,
        OFFERS,
        DETAIL,
        CONFIRM
    }

    private final ServerPlayer viewer;
    private final UUID viewerId;
    private final SimpleContainer catalog;
    private Page page = Page.SHOPS;
    private int pageIndex;
    private int quantity = 1;
    private Identifier selectedShop;
    private Identifier selectedOffer;
    private TradePreview preview;
    private long lastHandledGameTime = Long.MIN_VALUE;

    private PlayerShopMenu(
            int containerId,
            Inventory inventory,
            ServerPlayer viewer,
            SimpleContainer catalog) {
        super(MenuType.GENERIC_9x6, containerId, inventory, catalog, 6);
        this.viewer = viewer;
        this.viewerId = viewer.getUUID();
        this.catalog = catalog;
        render();
        PlayerMenuNetwork.seedMenuSession(this, UUID.randomUUID());
    }

    public static void open(ServerPlayer player) {
        player.openMenu(new SimpleMenuProvider(
                (containerId, inventory, viewer) -> new PlayerShopMenu(
                        containerId, inventory, (ServerPlayer) viewer, new SimpleContainer(MENU_SIZE)),
                Component.translatable("gui.rovenfall.shop.title")))
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
        long gameTime = viewer.level().getGameTime();
        if (!isActionSlot(slotIndex) || !PlayerDashboardMenu.canHandleClick(lastHandledGameTime, gameTime)) {
            return;
        }
        lastHandledGameTime = gameTime;
        if (slotIndex == REFRESH_SLOT) {
            if (page == Page.CONFIRM) {
                preview = null;
                page = Page.DETAIL;
            }
            render();
            return;
        }
        if (slotIndex == BACK_SLOT) {
            back();
            return;
        }
        if (slotIndex == PREVIOUS_SLOT && (page == Page.SHOPS || page == Page.OFFERS)) {
            pageIndex = Math.max(0, pageIndex - 1);
            render();
            return;
        }
        if (slotIndex == NEXT_SLOT && (page == Page.SHOPS || page == Page.OFFERS)) {
            pageIndex++;
            render();
            return;
        }
        switch (page) {
            case SHOPS -> selectShop(slotIndex);
            case OFFERS -> selectOffer(slotIndex);
            case DETAIL -> handleDetail(slotIndex);
            case CONFIRM -> handleConfirmation(slotIndex);
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slotIndex) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        return player.isAlive() && viewerId.equals(player.getUUID());
    }

    static int adjustedQuantity(int current, int delta) {
        return (int) Math.clamp(
                (long) current + delta,
                1L,
                ShopTradeService.MAX_TRADE_QUANTITY);
    }

    static String errorTranslationKey(ShopTradeService.Status status) {
        return switch (status) {
            case TRANSACTION_ID_CONFLICT -> "command.rovenfall.shop.error.transaction_id_conflict";
            case INVALID_REQUEST, INVALID_TRANSACTION -> "command.rovenfall.shop.error.invalid_request";
            case READ_ONLY_SCHEMA -> "command.rovenfall.shop.error.read_only";
            case TRANSACTION_LEDGER_FULL -> "command.rovenfall.shop.error.ledger_full";
            case SHOP_NOT_FOUND, OFFER_NOT_FOUND -> "command.rovenfall.shop.error.not_found";
            case OFFER_UNAVAILABLE -> "command.rovenfall.shop.error.offer_unavailable";
            case DEPENDENCY_LOCKED -> "command.rovenfall.shop.error.busy";
            case ACCESS_DENIED -> "command.rovenfall.shop.error.access_denied";
            case STALE_OFFER -> "command.rovenfall.shop.error.stale_offer";
            case ACCOUNT_NOT_FOUND -> "command.rovenfall.shop.error.account_missing";
            case OVERFLOW, MAXIMUM_BALANCE_EXCEEDED -> "command.rovenfall.shop.error.overflow";
            case INSUFFICIENT_FUNDS -> "command.rovenfall.shop.error.insufficient_funds";
            case INSUFFICIENT_STOCK -> "command.rovenfall.shop.error.insufficient_stock";
            case STOCK_CAPACITY_EXCEEDED -> "command.rovenfall.shop.error.stock_capacity";
            case INSUFFICIENT_ITEMS -> "command.rovenfall.shop.error.insufficient_items";
            case INSUFFICIENT_SPACE -> "command.rovenfall.shop.error.insufficient_space";
            case INVENTORY_UPDATE_FAILED -> "command.rovenfall.shop.error.inventory_update";
            case SUCCESS, DUPLICATE_TRANSACTION -> "command.rovenfall.shop.duplicate";
        };
    }

    private boolean isActionSlot(int slot) {
        return switch (page) {
            case SHOPS, OFFERS -> slot == BACK_SLOT || slot == PREVIOUS_SLOT || slot == NEXT_SLOT
                    || slot == REFRESH_SLOT || slot >= CONTENT_START && slot < CONTENT_START + PAGE_SIZE;
            case DETAIL -> slot == BACK_SLOT || slot == REFRESH_SLOT
                    || slot == 19 || slot == 20 || slot == 24 || slot == 25 || slot == 31 || slot == 33;
            case CONFIRM -> slot == BACK_SLOT || slot == REFRESH_SLOT || slot == 29 || slot == 33;
        };
    }

    private void selectShop(int slot) {
        int index = pageIndex * PAGE_SIZE + slot - CONTENT_START;
        List<Identifier> shops = accessibleShops();
        if (index < 0 || index >= shops.size()) {
            render();
            return;
        }
        selectedShop = shops.get(index);
        selectedOffer = null;
        page = Page.OFFERS;
        pageIndex = 0;
        render();
    }

    private void selectOffer(int slot) {
        int index = pageIndex * PAGE_SIZE + slot - CONTENT_START;
        List<Identifier> offers = offers();
        if (index < 0 || index >= offers.size()) {
            render();
            return;
        }
        selectedOffer = offers.get(index);
        quantity = 1;
        preview = null;
        page = Page.DETAIL;
        render();
    }

    private void handleDetail(int slot) {
        switch (slot) {
            case 19 -> quantity = adjustedQuantity(quantity, -10);
            case 20 -> quantity = adjustedQuantity(quantity, -1);
            case 24 -> quantity = adjustedQuantity(quantity, 1);
            case 25 -> quantity = adjustedQuantity(quantity, 10);
            case 31 -> preparePreview(ShopTradeService.Direction.BUY);
            case 33 -> preparePreview(ShopTradeService.Direction.SELL);
            default -> {
                return;
            }
        }
        render();
    }

    private void preparePreview(ShopTradeService.Direction direction) {
        Optional<ShopInstance.Offer> offer = selectedOffer();
        Optional<Long> price = offer.flatMap(value -> direction == ShopTradeService.Direction.BUY
                ? value.buyPrice()
                : value.sellPrice());
        if (offer.isEmpty() || price.isEmpty()) {
            viewer.sendOverlayMessage(Component.translatable("command.rovenfall.shop.error.offer_unavailable"));
            return;
        }
        preview = new TradePreview(
                selectedShop, selectedOffer, direction, quantity,
                offer.orElseThrow().item(), price.orElseThrow());
        page = Page.CONFIRM;
    }

    private void handleConfirmation(int slot) {
        if (slot == 29) {
            preview = null;
            page = Page.DETAIL;
            render();
            return;
        }
        if (slot != 33 || preview == null) {
            return;
        }
        if (!PlayerMenuNetwork.beginMutation(viewerId, viewer.level().getGameTime())) {
            viewer.sendOverlayMessage(Component.translatable("gui.rovenfall.player.rate_limit"));
            return;
        }
        UUID transactionId = UUID.randomUUID();
        ShopTradeService.TradeResult result = ShopTradeService.trade(
                platform(),
                viewer,
                new ShopTradeService.TradeRequest(
                        preview.shopId(), preview.offerId(), preview.direction(), preview.quantity(),
                        preview.expectedItem(), preview.expectedUnitPrice(), transactionId),
                viewer.level().getGameTime(),
                Instant.now().toEpochMilli());
        if (result.status() == ShopTradeService.Status.SUCCESS) {
            viewer.sendOverlayMessage(Component.translatable(
                    preview.direction() == ShopTradeService.Direction.BUY
                            ? "command.rovenfall.shop.buy.success"
                            : "command.rovenfall.shop.sell.success",
                    preview.quantity(), preview.offerId().toString(), transactionId.toString()));
        } else if (result.status() == ShopTradeService.Status.DUPLICATE_TRANSACTION) {
            viewer.sendOverlayMessage(Component.translatable(
                    "command.rovenfall.shop.duplicate", transactionId.toString()));
        } else {
            viewer.sendOverlayMessage(Component.translatable(errorTranslationKey(result.status())));
        }
        preview = null;
        page = Page.DETAIL;
        render();
    }

    private void back() {
        switch (page) {
            case SHOPS -> {
                PlayerDashboardMenu.open(viewer);
                return;
            }
            case OFFERS -> {
                selectedShop = null;
                page = Page.SHOPS;
            }
            case DETAIL -> {
                selectedOffer = null;
                page = Page.OFFERS;
            }
            case CONFIRM -> {
                preview = null;
                page = Page.DETAIL;
            }
        }
        pageIndex = 0;
        render();
    }

    private void render() {
        catalog.clearContent();
        switch (page) {
            case SHOPS -> renderShops();
            case OFFERS -> renderOffers();
            case DETAIL -> renderDetail();
            case CONFIRM -> renderConfirmation();
        }
        catalog.setItem(REFRESH_SLOT, PlayerDashboardMenu.icon(
                Items.CLOCK,
                Component.translatable("gui.rovenfall.player.refresh"),
                Component.translatable("gui.rovenfall.player.click")));
        broadcastChanges();
    }

    private void renderShops() {
        List<Identifier> shops = accessibleShops();
        pageIndex = boundedPage(pageIndex, shops.size());
        catalog.setItem(4, PlayerDashboardMenu.icon(
                Items.CHEST,
                Component.translatable("gui.rovenfall.shop.list"),
                Component.translatable("gui.rovenfall.player.balance", balance()),
                pageLine(shops.size())));
        if (shops.isEmpty()) {
            catalog.setItem(22, PlayerDashboardMenu.icon(
                    Items.BARRIER,
                    Component.translatable("gui.rovenfall.shop.no_shops")));
        } else {
            pageEntries(shops).forEach(entry -> {
                ShopInstance shop = platform().shopInstance(entry.value()).orElseThrow();
                catalog.setItem(CONTENT_START + entry.offset(), PlayerDashboardMenu.icon(
                        Items.CHEST,
                        Component.literal(entry.value().toString()),
                        Component.translatable("gui.rovenfall.shop.offers", shop.offers().size()),
                        bindingLine(shop),
                        Component.translatable("gui.rovenfall.player.click")));
            });
        }
        addBackAndPaging(shops.size());
    }

    private void renderOffers() {
        if (!selectedShopAccessible()) {
            selectedShop = null;
            page = Page.SHOPS;
            pageIndex = 0;
            viewer.sendOverlayMessage(Component.translatable("command.rovenfall.shop.error.access_denied"));
            renderShops();
            return;
        }
        List<Identifier> offers = offers();
        pageIndex = boundedPage(pageIndex, offers.size());
        catalog.setItem(4, PlayerDashboardMenu.icon(
                Items.CHEST,
                Component.literal(selectedShop.toString()),
                Component.translatable("gui.rovenfall.player.balance", balance()),
                pageLine(offers.size())));
        if (offers.isEmpty()) {
            catalog.setItem(22, PlayerDashboardMenu.icon(
                    Items.BARRIER,
                    Component.translatable("gui.rovenfall.shop.no_offers")));
        } else {
            pageEntries(offers).forEach(entry -> catalog.setItem(
                    CONTENT_START + entry.offset(),
                    offerIcon(entry.value(), shop().offers().get(entry.value()))));
        }
        addBackAndPaging(offers.size());
    }

    private void renderDetail() {
        Optional<ShopInstance.Offer> current = selectedOffer();
        if (current.isEmpty()) {
            if (!selectedShopAccessible()) {
                selectedShop = null;
                selectedOffer = null;
                page = Page.SHOPS;
                viewer.sendOverlayMessage(Component.translatable("command.rovenfall.shop.error.access_denied"));
                renderShops();
                return;
            }
            selectedOffer = null;
            page = Page.OFFERS;
            viewer.sendOverlayMessage(Component.translatable("command.rovenfall.shop.error.not_found"));
            renderOffers();
            return;
        }
        ShopInstance.Offer offer = current.orElseThrow();
        catalog.setItem(4, PlayerDashboardMenu.icon(
                Items.CHEST,
                Component.literal(selectedShop + " / " + selectedOffer),
                Component.translatable("gui.rovenfall.player.balance", balance())));
        catalog.setItem(13, offerIcon(selectedOffer, offer));
        catalog.setItem(19, quantityButton("-10"));
        catalog.setItem(20, quantityButton("-1"));
        catalog.setItem(22, PlayerDashboardMenu.icon(
                Items.PAPER,
                Component.translatable("gui.rovenfall.shop.quantity", quantity),
                Component.translatable("gui.rovenfall.shop.item_count", safeItemCount(offer, quantity))));
        catalog.setItem(24, quantityButton("+1"));
        catalog.setItem(25, quantityButton("+10"));
        catalog.setItem(31, directionButton(offer, ShopTradeService.Direction.BUY));
        catalog.setItem(33, directionButton(offer, ShopTradeService.Direction.SELL));
        addBack();
    }

    private void renderConfirmation() {
        if (preview == null) {
            page = Page.DETAIL;
            renderDetail();
            return;
        }
        catalog.setItem(4, PlayerDashboardMenu.icon(
                Items.PAPER,
                Component.translatable("gui.rovenfall.shop.confirm_title"),
                Component.translatable("gui.rovenfall.shop.direction." + preview.direction().name().toLowerCase()),
                Component.translatable("gui.rovenfall.shop.quantity", preview.quantity()),
                Component.translatable("gui.rovenfall.shop.unit_price", preview.expectedUnitPrice()),
                Component.translatable("gui.rovenfall.shop.total", preview.total())));
        ItemStack item = preview.expectedItem();
        item.set(DataComponents.LORE, new ItemLore(List.of(
                Component.translatable("gui.rovenfall.shop.offer_id", preview.offerId().toString()),
                Component.translatable("gui.rovenfall.shop.item_count", safeItemCount(item, preview.quantity())))));
        catalog.setItem(13, item);
        catalog.setItem(29, PlayerDashboardMenu.icon(
                Items.BARRIER,
                Component.translatable("gui.rovenfall.player.cancel"),
                Component.translatable("gui.rovenfall.player.click")));
        catalog.setItem(33, PlayerDashboardMenu.icon(
                Items.EMERALD,
                Component.translatable("gui.rovenfall.player.confirm"),
                Component.translatable("gui.rovenfall.shop.total", preview.total()),
                Component.translatable("gui.rovenfall.player.click")));
        addBack();
    }

    private ItemStack offerIcon(Identifier offerId, ShopInstance.Offer offer) {
        ItemStack stack = offer.item();
        stack.set(DataComponents.LORE, new ItemLore(List.of(
                Component.translatable("gui.rovenfall.shop.offer_id", offerId.toString()),
                priceLine("buy", offer.buyPrice()),
                priceLine("sell", offer.sellPrice()),
                stockLine(offer.stock()),
                Component.translatable("gui.rovenfall.player.click"))));
        return stack;
    }

    private ItemStack quantityButton(String label) {
        return PlayerDashboardMenu.icon(
                Items.ARROW,
                Component.literal(label),
                Component.translatable("gui.rovenfall.shop.quantity", quantity));
    }

    private ItemStack directionButton(ShopInstance.Offer offer, ShopTradeService.Direction direction) {
        Optional<Long> price = direction == ShopTradeService.Direction.BUY ? offer.buyPrice() : offer.sellPrice();
        if (price.isEmpty()) {
            return PlayerDashboardMenu.icon(
                    Items.BARRIER,
                    Component.translatable("gui.rovenfall.shop.direction." + direction.name().toLowerCase()),
                    Component.translatable("command.rovenfall.shop.error.offer_unavailable"));
        }
        return PlayerDashboardMenu.icon(
                direction == ShopTradeService.Direction.BUY ? Items.GOLD_INGOT : Items.IRON_INGOT,
                Component.translatable("gui.rovenfall.shop.direction." + direction.name().toLowerCase()),
                Component.translatable("gui.rovenfall.shop.unit_price", price.orElseThrow()),
                Component.translatable("gui.rovenfall.shop.total", safeTotal(price.orElseThrow(), quantity)),
                Component.translatable("gui.rovenfall.player.click"));
    }

    private void addBackAndPaging(int entries) {
        addBack();
        if (pageIndex > 0) {
            catalog.setItem(PREVIOUS_SLOT, PlayerDashboardMenu.icon(
                    Items.ARROW, Component.translatable("gui.rovenfall.player.previous")));
        }
        if ((long) (pageIndex + 1) * PAGE_SIZE < entries) {
            catalog.setItem(NEXT_SLOT, PlayerDashboardMenu.icon(
                    Items.ARROW, Component.translatable("gui.rovenfall.player.next")));
        }
    }

    private void addBack() {
        catalog.setItem(BACK_SLOT, PlayerDashboardMenu.icon(
                Items.ARROW,
                Component.translatable("gui.rovenfall.player.back"),
                Component.translatable("gui.rovenfall.player.click")));
    }

    private List<Identifier> accessibleShops() {
        return ShopTradeService.accessibleShopIds(
                platform(), viewer.level().dimension(), viewer.position());
    }

    private boolean selectedShopAccessible() {
        return selectedShop != null && accessibleShops().contains(selectedShop);
    }

    private ShopInstance shop() {
        return platform().shopInstance(selectedShop).orElseThrow();
    }

    private List<Identifier> offers() {
        if (!selectedShopAccessible()) {
            return List.of();
        }
        return shop().offers().keySet().stream().sorted().toList();
    }

    private Optional<ShopInstance.Offer> selectedOffer() {
        if (selectedOffer == null || !selectedShopAccessible()) {
            return Optional.empty();
        }
        return Optional.ofNullable(shop().offers().get(selectedOffer));
    }

    private PlatformSavedData platform() {
        return PlatformSavedData.get(viewer.level().getServer());
    }

    private long balance() {
        return platform().economyBalance(viewerId).orElse(0L);
    }

    private Component pageLine(int entries) {
        int pages = entries == 0 ? 0 : (entries + PAGE_SIZE - 1) / PAGE_SIZE;
        return Component.translatable("gui.rovenfall.player.page", entries == 0 ? 0 : pageIndex + 1, pages, entries);
    }

    private Component bindingLine(ShopInstance shop) {
        return shop.binding()
                .<Component>map(binding -> Component.translatable(
                        "gui.rovenfall.shop.binding",
                        binding.dimension().identifier().toString(),
                        binding.position().getX(), binding.position().getY(), binding.position().getZ(),
                        shop.accessPolicy().maxDistance()))
                .orElseGet(() -> Component.translatable("gui.rovenfall.shop.unbound"));
    }

    private static Component priceLine(String direction, Optional<Long> price) {
        return price
                .<Component>map(value -> Component.translatable("gui.rovenfall.shop.price." + direction, value))
                .orElseGet(() -> Component.translatable("gui.rovenfall.shop.price.unavailable"));
    }

    private static Component stockLine(ShopInstance.Stock stock) {
        return stock.unlimited()
                ? Component.translatable("gui.rovenfall.shop.stock.unlimited")
                : Component.translatable("gui.rovenfall.shop.stock", stock.current(), stock.maximum());
    }

    private static int boundedPage(int page, int entries) {
        int lastPage = entries == 0 ? 0 : (entries - 1) / PAGE_SIZE;
        return Math.clamp(page, 0, lastPage);
    }

    private static <T> List<PageEntry<T>> pageEntries(List<T> entries, int page) {
        int from = Math.min(entries.size(), page * PAGE_SIZE);
        int to = Math.min(entries.size(), from + PAGE_SIZE);
        return java.util.stream.IntStream.range(from, to)
                .mapToObj(index -> new PageEntry<>(index - from, entries.get(index)))
                .toList();
    }

    private <T> List<PageEntry<T>> pageEntries(List<T> entries) {
        return pageEntries(entries, pageIndex);
    }

    private static long safeTotal(long unitPrice, int quantity) {
        try {
            return Math.multiplyExact(unitPrice, quantity);
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    private static long safeItemCount(ShopInstance.Offer offer, int quantity) {
        return safeItemCount(offer.item(), quantity);
    }

    private static long safeItemCount(ItemStack item, int quantity) {
        return (long) item.getCount() * quantity;
    }

    private record PageEntry<T>(int offset, T value) {
    }

    private record TradePreview(
            Identifier shopId,
            Identifier offerId,
            ShopTradeService.Direction direction,
            int quantity,
            ItemStack expectedItem,
            long expectedUnitPrice) {
        TradePreview {
            expectedItem = expectedItem.copy();
        }

        @Override
        public ItemStack expectedItem() {
            return expectedItem.copy();
        }

        long total() {
            return safeTotal(expectedUnitPrice, quantity);
        }
    }
}
