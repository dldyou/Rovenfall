package org.dldyou.rovenfall.administration;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
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
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.dldyou.rovenfall.economy.ShopInstance;
import org.dldyou.rovenfall.economy.ShopTemplateReloadListener;

/** Server-authoritative player, economy receipt, and shop administration workflow. */
public final class AdministrationEconomyMenu extends ChestMenu implements AdministrationTextInputMenu {
    static final int MENU_SIZE = 54;
    static final int CONTENT_START = 9;
    static final int CONTENT_SIZE = 36;
    static final int BACK_SLOT = 45;
    static final int CREATE_SLOT = 46;
    static final int PREVIOUS_SLOT = 47;
    static final int NEXT_SLOT = 51;
    static final int REFRESH_SLOT = 53;
    static final int CONFIRM_SLOT = 31;
    static final int CANCEL_SLOT = 33;
    private static final int OFFER_CONTENT_START = 18;
    private static final int OFFER_PAGE_SIZE = 27;

    private final ServerPlayer viewer;
    private final UUID viewerId;
    private final SimpleContainer contents;
    private final AdministrationReadViewService.Domain entryDomain;
    private Mode mode;
    private Mode returnMode;
    private FormKind formKind;
    private String query = "";
    private String formError = "";
    private int page;
    private UUID selectedPlayer;
    private UUID receiptPlayerFilter;
    private UUID selectedReceipt;
    private Identifier selectedShop;
    private Identifier selectedOffer;
    private Identifier selectedTemplate;
    private Identifier selectedItem;
    private AdministrationEconomyActionService.PendingAction pending;
    private AdministrationEconomyActionService.Result result;
    private long lastHandledGameTime = Long.MIN_VALUE;

    private AdministrationEconomyMenu(
            int containerId,
            Inventory inventory,
            ServerPlayer viewer,
            SimpleContainer contents,
            AdministrationReadViewService.Domain entryDomain) {
        super(MenuType.GENERIC_9x6, containerId, inventory, contents, 6);
        this.viewer = viewer;
        this.viewerId = viewer.getUUID();
        this.contents = contents;
        this.entryDomain = entryDomain;
        this.mode = switch (entryDomain) {
            case PLAYERS -> Mode.PLAYERS;
            case SHOPS -> Mode.SHOPS;
            case RECEIPTS -> Mode.RECEIPTS;
            default -> throw new IllegalArgumentException("Unsupported economy administration domain " + entryDomain);
        };
        render();
        PlayerMenuNetwork.seedMenuSession(this, UUID.randomUUID());
    }

    public static boolean open(ServerPlayer player, AdministrationReadViewService.Domain domain) {
        if (player == null || domain == null
                || domain != AdministrationReadViewService.Domain.PLAYERS
                        && domain != AdministrationReadViewService.Domain.SHOPS
                        && domain != AdministrationReadViewService.Domain.RECEIPTS
                || !canView(player, domain)) {
            return false;
        }
        player.openMenu(new SimpleMenuProvider(
                (containerId, inventory, viewer) -> new AdministrationEconomyMenu(
                        containerId, inventory, (ServerPlayer) viewer, new SimpleContainer(MENU_SIZE), domain),
                Component.translatable("gui.rovenfall.admin.economy.title")))
                .ifPresent(ignored -> PlayerMenuNetwork.sendMenuIdentity(player));
        return true;
    }

    static boolean openReceipt(ServerPlayer player, UUID transactionId) {
        if (player == null || transactionId == null
                || !canView(player, AdministrationReadViewService.Domain.RECEIPTS)
                || PlatformSavedData.get(player.level().getServer()).economyReceipt(transactionId).isEmpty()) {
            return false;
        }
        player.openMenu(new SimpleMenuProvider(
                (containerId, inventory, viewer) -> {
                    var menu = new AdministrationEconomyMenu(
                            containerId, inventory, (ServerPlayer) viewer,
                            new SimpleContainer(MENU_SIZE), AdministrationReadViewService.Domain.RECEIPTS);
                    menu.selectedReceipt = transactionId;
                    menu.mode = Mode.RECEIPT_DETAIL;
                    menu.render();
                    return menu;
                },
                Component.translatable("gui.rovenfall.admin.economy.title")))
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
        if (!canView(viewer, entryDomain)) {
            denyAndClose();
            return;
        }
        if (slotIndex == REFRESH_SLOT) {
            render();
            return;
        }
        if (slotIndex == BACK_SLOT) {
            back();
            return;
        }
        switch (mode) {
            case PLAYERS -> clickPlayers(slotIndex);
            case PLAYER_DETAIL -> clickPlayerDetail(slotIndex);
            case RECEIPTS -> clickReceipts(slotIndex);
            case RECEIPT_DETAIL -> clickReceiptDetail(slotIndex);
            case SHOPS -> clickShops(slotIndex);
            case SHOP_DETAIL -> clickShopDetail(slotIndex);
            case OFFER_DETAIL -> clickOfferDetail(slotIndex);
            case TEMPLATE_SELECT -> clickTemplateSelect(slotIndex);
            case ITEM_SELECT -> clickItemSelect(slotIndex);
            case FORM -> {
            }
            case PREVIEW -> {
                if (slotIndex == CONFIRM_SLOT) {
                    confirm();
                } else if (slotIndex == CANCEL_SLOT) {
                    cancelForm();
                }
            }
            case RESULT -> {
                if (slotIndex == CONFIRM_SLOT) {
                    finishResult();
                }
            }
        }
    }

    @Override
    public boolean applyTextInput(ServerPlayer player, String input) {
        if (!viewerId.equals(player.getUUID()) || input == null
                || input.length() > AdministrationTextInputMenu.MAX_INPUT_LENGTH
                || !canView(viewer, entryDomain)) {
            return false;
        }
        if (mode == Mode.FORM) {
            if (!canManage(viewer)) {
                denyAndClose();
                return false;
            }
            return parseForm(input);
        }
        if (mode != Mode.PLAYERS && mode != Mode.RECEIPTS && mode != Mode.SHOPS
                && mode != Mode.TEMPLATE_SELECT && mode != Mode.ITEM_SELECT) {
            return false;
        }
        if (input.length() > AdministrationReadViewService.MAX_QUERY_LENGTH) {
            formError = "query_too_long";
            render();
            return false;
        }
        query = input.strip();
        page = 0;
        formError = "";
        render();
        return true;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slotIndex) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        return player.isAlive() && viewerId.equals(player.getUUID()) && canView(viewer, entryDomain);
    }

    static boolean canManage(AdminRole role) {
        return role == AdminRole.ECONOMY_MANAGER || role == AdminRole.OWNER;
    }

    private static boolean canManage(ServerPlayer player) {
        return AdministrationControlCenterMenu.resolveRole(player).filter(AdministrationEconomyMenu::canManage).isPresent();
    }

    private static boolean canView(ServerPlayer player, AdministrationReadViewService.Domain domain) {
        return AdministrationControlCenterMenu.resolveRole(player).filter(domain::allowedFor).isPresent();
    }

    private void clickPlayers(int slot) {
        if (slot == PREVIOUS_SLOT) {
            page = Math.max(0, page - 1);
        } else if (slot == NEXT_SLOT) {
            page++;
        } else if (slot >= CONTENT_START && slot < CONTENT_START + CONTENT_SIZE) {
            var resultPage = playersPage();
            int index = slot - CONTENT_START;
            if (index < resultPage.entries().size()) {
                selectedPlayer = resultPage.entries().get(index).playerId();
                mode = Mode.PLAYER_DETAIL;
                page = 0;
            }
        } else {
            return;
        }
        render();
    }

    private void clickPlayerDetail(int slot) {
        if (selectedPlayer == null) {
            mode = Mode.PLAYERS;
        } else if (slot == 20 && canManage(viewer)) {
            enterForm(FormKind.GRANT, Mode.PLAYER_DETAIL);
            return;
        } else if (slot == 24 && canManage(viewer)) {
            enterForm(FormKind.DEBIT, Mode.PLAYER_DETAIL);
            return;
        } else if (slot == 22) {
            receiptPlayerFilter = selectedPlayer;
            query = "";
            page = 0;
            mode = Mode.RECEIPTS;
        } else {
            return;
        }
        render();
    }

    private void clickReceipts(int slot) {
        if (slot == PREVIOUS_SLOT) {
            page = Math.max(0, page - 1);
        } else if (slot == NEXT_SLOT) {
            page++;
        } else if (slot >= CONTENT_START && slot < CONTENT_START + CONTENT_SIZE) {
            var resultPage = receiptsPage();
            int index = slot - CONTENT_START;
            if (index < resultPage.entries().size()) {
                selectedReceipt = resultPage.entries().get(index).transactionId();
                mode = Mode.RECEIPT_DETAIL;
            }
        } else {
            return;
        }
        render();
    }

    private void clickReceiptDetail(int slot) {
        EconomyTransactionReceipt receipt = selectedReceipt == null
                ? null : state().economyReceipt(selectedReceipt).orElse(null);
        if (!canManage(viewer) || !canReverse(receipt) || !reversalTargetOnline(receipt)) {
            return;
        }
        if (slot == 31) {
            enterForm(FormKind.REVERSE_STRICT, Mode.RECEIPT_DETAIL);
        } else if (slot == 33) {
            enterForm(FormKind.REVERSE_COMPENSATE, Mode.RECEIPT_DETAIL);
        }
    }

    private void clickShops(int slot) {
        if (slot == CREATE_SLOT && canManage(viewer)) {
            selectedTemplate = null;
            query = "";
            page = 0;
            mode = Mode.TEMPLATE_SELECT;
            render();
            return;
        }
        if (slot == PREVIOUS_SLOT) {
            page = Math.max(0, page - 1);
        } else if (slot == NEXT_SLOT) {
            page++;
        } else if (slot >= CONTENT_START && slot < CONTENT_START + CONTENT_SIZE) {
            var resultPage = shopsPage();
            int index = slot - CONTENT_START;
            if (index < resultPage.entries().size()) {
                selectedShop = resultPage.entries().get(index).shopId();
                selectedOffer = null;
                page = 0;
                mode = Mode.SHOP_DETAIL;
            }
        } else {
            return;
        }
        render();
    }

    private void clickShopDetail(int slot) {
        ShopInstance shop = selectedShop == null ? null : state().shopInstance(selectedShop).orElse(null);
        if (shop == null) {
            mode = Mode.SHOPS;
            render();
            return;
        }
        if (slot >= OFFER_CONTENT_START && slot < OFFER_CONTENT_START + OFFER_PAGE_SIZE) {
            List<Identifier> offers = offerIds(shop);
            int index = page * OFFER_PAGE_SIZE + slot - OFFER_CONTENT_START;
            if (index < offers.size()) {
                selectedOffer = offers.get(index);
                mode = Mode.OFFER_DETAIL;
                render();
            }
            return;
        }
        if (slot == PREVIOUS_SLOT) {
            page = Math.max(0, page - 1);
        } else if (slot == NEXT_SLOT) {
            page++;
        } else if (!canManage(viewer)) {
            return;
        } else if (slot == 10) {
            enterForm(FormKind.DELETE_SHOP, Mode.SHOP_DETAIL);
            return;
        } else if (slot == 11) {
            enterForm(FormKind.BIND_HERE, Mode.SHOP_DETAIL);
            return;
        } else if (slot == 12) {
            enterForm(FormKind.UNBIND, Mode.SHOP_DETAIL);
            return;
        } else if (slot == 13) {
            enterForm(FormKind.ACCESS, Mode.SHOP_DETAIL);
            return;
        } else if (slot == 14) {
            selectedItem = null;
            selectedOffer = null;
            query = "";
            page = 0;
            mode = Mode.ITEM_SELECT;
            render();
            return;
        } else {
            return;
        }
        render();
    }

    private void clickOfferDetail(int slot) {
        if (!canManage(viewer)) {
            return;
        }
        if (slot == 20) {
            ShopInstance shop = selectedShop == null ? null : state().shopInstance(selectedShop).orElse(null);
            ShopInstance.Offer offer = shop == null || selectedOffer == null ? null : shop.offers().get(selectedOffer);
            selectedItem = offer == null ? null : BuiltInRegistries.ITEM.getKey(offer.item().getItem());
            enterForm(FormKind.UPSERT_OFFER, Mode.OFFER_DETAIL);
        } else if (slot == 22) {
            enterForm(FormKind.REMOVE_OFFER, Mode.OFFER_DETAIL);
        } else if (slot == 24) {
            enterForm(FormKind.RESTOCK, Mode.OFFER_DETAIL);
        }
    }

    private void clickTemplateSelect(int slot) {
        if (slot == PREVIOUS_SLOT) {
            page = Math.max(0, page - 1);
        } else if (slot == NEXT_SLOT) {
            page++;
        } else if (slot >= CONTENT_START && slot < CONTENT_START + CONTENT_SIZE) {
            List<Identifier> templates = templateIds();
            int index = page * CONTENT_SIZE + slot - CONTENT_START;
            if (index >= templates.size()) {
                return;
            }
            selectedTemplate = templates.get(index);
            enterForm(FormKind.CREATE_SHOP, Mode.SHOPS);
            return;
        } else {
            return;
        }
        render();
    }

    private void clickItemSelect(int slot) {
        if (slot == PREVIOUS_SLOT) {
            page = Math.max(0, page - 1);
        } else if (slot == NEXT_SLOT) {
            page++;
        } else if (slot >= CONTENT_START && slot < CONTENT_START + CONTENT_SIZE) {
            List<Identifier> items = itemIds();
            int index = page * CONTENT_SIZE + slot - CONTENT_START;
            if (index >= items.size()) {
                return;
            }
            selectedItem = items.get(index);
            enterForm(FormKind.UPSERT_OFFER, Mode.SHOP_DETAIL);
            return;
        } else {
            return;
        }
        render();
    }

    private boolean parseForm(String input) {
        UUID transactionId = UUID.randomUUID();
        String legacy = input;
        if (input.startsWith("rf-form/")) {
            Optional<List<String>> decoded = AdministrationStructuredFormCodec.decode(formType(formKind), input);
            legacy = decoded.flatMap(values -> AdministrationEconomyTypedForm.legacy(
                    formType(formKind), values, transactionId, selectedTemplate, selectedItem, selectedOffer))
                    .orElse(null);
            if (legacy == null) {
                formError = "invalid_form";
                render();
                return false;
            }
        }
        return parseLegacyForm(legacy, transactionId);
    }

    private boolean parseLegacyForm(String input, UUID transactionId) {
        PlatformSavedData state = state();
        pending = switch (formKind) {
            case GRANT, DEBIT -> AdministrationEconomyFormParser.parseBalance(input)
                    .map(value -> new AdministrationEconomyActionService.BalanceAction(
                            transactionId, selectedPlayer, value.amount(), formKind == FormKind.GRANT,
                            state.economyBalance(selectedPlayer), value.reason()))
                    .orElse(null);
            case CREATE_SHOP -> AdministrationEconomyFormParser.parseShopCreate(input)
                    .flatMap(value -> ShopTemplateReloadListener.snapshot(viewer.level().getServer())
                            .get(value.templateId()).map(template ->
                                    new AdministrationEconomyActionService.ShopCreateAction(
                                            transactionId, value.shopId(), value.templateId(), template, value.reason())))
                    .orElse(null);
            case DELETE_SHOP -> reason(input).map(value -> new AdministrationEconomyActionService.ShopDeleteAction(
                    transactionId, selectedShop, state.shopInstance(selectedShop), value)).orElse(null);
            case BIND_HERE -> reason(input).map(value -> new AdministrationEconomyActionService.ShopBindingAction(
                    transactionId, selectedShop, state.shopInstance(selectedShop), Optional.of(new ShopInstance.Binding(
                            viewer.level().dimension(), BlockPos.containing(viewer.position()))), value)).orElse(null);
            case UNBIND -> reason(input).map(value -> new AdministrationEconomyActionService.ShopBindingAction(
                    transactionId, selectedShop, state.shopInstance(selectedShop), Optional.empty(), value)).orElse(null);
            case ACCESS -> AdministrationEconomyFormParser.parseAccessDistance(input)
                    .map(value -> new AdministrationEconomyActionService.ShopAccessAction(
                            transactionId, selectedShop, state.shopInstance(selectedShop), value.distance(), value.reason()))
                    .orElse(null);
            case UPSERT_OFFER -> offerAction(state, transactionId, input);
            case REMOVE_OFFER -> reason(input).map(value -> new AdministrationEconomyActionService.ShopOfferRemoveAction(
                    transactionId, selectedShop, state.shopInstance(selectedShop), selectedOffer, value)).orElse(null);
            case RESTOCK -> AdministrationEconomyFormParser.parseRestock(input)
                    .filter(value -> selectedOffer == null || selectedOffer.equals(value.offerId()))
                    .map(value -> new AdministrationEconomyActionService.ShopRestockAction(
                            transactionId, selectedShop, state.shopInstance(selectedShop), value.offerId(),
                            value.amount(), value.intervalTicks(), value.reason()))
                    .orElse(null);
            case REVERSE_STRICT, REVERSE_COMPENSATE -> reversalAction(state, transactionId, input);
        };
        if (pending == null || !AdministrationEconomyActionService.fresh(
                state, ShopTemplateReloadListener.snapshot(viewer.level().getServer()), pending)) {
            formError = "invalid_form";
            pending = null;
            render();
            return false;
        }
        formError = "";
        mode = Mode.PREVIEW;
        render();
        return true;
    }

    private AdministrationEconomyActionService.PendingAction offerAction(
            PlatformSavedData state, UUID transactionId, String input) {
        var parsed = AdministrationEconomyFormParser.parseOfferUpsert(input).orElse(null);
        if (parsed == null || selectedShop == null || selectedOffer != null && !selectedOffer.equals(parsed.offerId())) {
            return null;
        }
        Item item = BuiltInRegistries.ITEM.getValue(parsed.itemId());
        ShopInstance shop = state.shopInstance(selectedShop).orElse(null);
        ShopInstance.Offer existing = shop == null ? null : shop.offers().get(parsed.offerId());
        ItemStack stack = offerStack(existing == null ? ItemStack.EMPTY : existing.item(),
                parsed.itemId(), parsed.count());
        if (item == null || item == Items.AIR || stack.isEmpty()) {
            return null;
        }
        ShopInstance.Stock stock = parsed.stock() == -1
                ? ShopInstance.Stock.unlimitedStock()
                : ShopInstance.Stock.finite(parsed.stock(), parsed.maximumStock());
        ShopInstance.Offer offer = new ShopInstance.Offer(
                stack, parsed.buyPrice(), parsed.sellPrice(), stock);
        if (ShopInstance.Offer.CODEC.encodeStart(net.minecraft.nbt.NbtOps.INSTANCE, offer).error().isPresent()) {
            return null;
        }
        return new AdministrationEconomyActionService.ShopOfferAction(
                transactionId, selectedShop, state.shopInstance(selectedShop), parsed.offerId(), offer, parsed.reason());
    }

    private AdministrationEconomyActionService.PendingAction reversalAction(
            PlatformSavedData state, UUID transactionId, String input) {
        var parsed = AdministrationEconomyFormParser.parseReasonOnly(input).orElse(null);
        EconomyTransactionReceipt receipt = selectedReceipt == null
                ? null : state.economyReceipt(selectedReceipt).orElse(null);
        if (parsed == null || receipt == null) {
            return null;
        }
        if (!canReverse(receipt)) {
            return null;
        }
        ServerPlayer target = viewer.level().getServer().getPlayerList().getPlayer(receipt.playerId());
        if (target == null) {
            return null;
        }
        List<ItemStack> expectedInventory = receipt.isTrade()
                ? ShopTradeService.copyInventory(target.getInventory().getNonEquipmentItems()) : List.of();
        Optional<ShopInstance> expectedShop = receipt.isTrade()
                ? receipt.shopId().flatMap(state::shopInstance) : Optional.empty();
        return new AdministrationEconomyActionService.ReceiptReversalAction(
                transactionId, selectedReceipt, receipt.playerId(), receipt,
                state.economyBalance(receipt.playerId()),
                expectedInventory, expectedShop,
                formKind == FormKind.REVERSE_COMPENSATE
                        ? EconomyTransactionReceipt.CompensationDecision.REFUND_WITHOUT_ITEMS_OR_STOCK
                        : EconomyTransactionReceipt.CompensationDecision.NONE,
                parsed.reason());
    }

    private static Optional<String> reason(String input) {
        return AdministrationEconomyFormParser.parseReasonOnly(input)
                .map(AdministrationEconomyFormParser.ReasonOnly::reason);
    }

    private void confirm() {
        if (pending == null) {
            return;
        }
        if (!PlayerMenuNetwork.beginMutation(viewerId, viewer.level().getGameTime())) {
            result = new AdministrationEconomyActionService.Result(
                    AdministrationEconomyActionService.Status.FAILED, "rate_limited", pending.transactionId());
            mode = Mode.RESULT;
            render();
            return;
        }
        result = AdministrationEconomyActionService.execute(viewer, pending);
        mode = Mode.RESULT;
        render();
    }

    private void enterForm(FormKind kind, Mode destination) {
        formKind = kind;
        returnMode = destination;
        formError = "";
        pending = null;
        mode = Mode.FORM;
        render();
    }

    private void cancelForm() {
        pending = null;
        formKind = null;
        mode = returnMode;
        render();
    }

    private void finishResult() {
        pending = null;
        formKind = null;
        if (result != null && result.succeeded() && returnMode == Mode.SHOP_DETAIL
                && selectedShop != null && state().shopInstance(selectedShop).isEmpty()) {
            returnMode = Mode.SHOPS;
        }
        mode = returnMode;
        result = null;
        render();
    }

    private void back() {
        switch (mode) {
            case PLAYERS, SHOPS -> AdministrationControlCenterMenu.open(viewer);
            case TEMPLATE_SELECT -> {
                mode = Mode.SHOPS;
                query = "";
                page = 0;
                render();
            }
            case ITEM_SELECT -> {
                mode = Mode.SHOP_DETAIL;
                query = "";
                page = 0;
                render();
            }
            case RECEIPTS -> {
                if (receiptPlayerFilter != null) {
                    selectedPlayer = receiptPlayerFilter;
                    receiptPlayerFilter = null;
                    mode = Mode.PLAYER_DETAIL;
                    query = "";
                    page = 0;
                    render();
                } else {
                    AdministrationControlCenterMenu.open(viewer);
                }
            }
            case PLAYER_DETAIL -> {
                mode = Mode.PLAYERS;
                selectedPlayer = null;
                render();
            }
            case RECEIPT_DETAIL -> {
                mode = Mode.RECEIPTS;
                selectedReceipt = null;
                render();
            }
            case SHOP_DETAIL -> {
                mode = Mode.SHOPS;
                selectedShop = null;
                selectedOffer = null;
                page = 0;
                render();
            }
            case OFFER_DETAIL -> {
                mode = Mode.SHOP_DETAIL;
                selectedOffer = null;
                render();
            }
            case FORM, PREVIEW -> cancelForm();
            case RESULT -> finishResult();
        }
    }

    private void render() {
        contents.clearContent();
        if (!canView(viewer, entryDomain)) {
            denyAndClose();
            return;
        }
        switch (mode) {
            case PLAYERS -> renderPlayers();
            case PLAYER_DETAIL -> renderPlayerDetail();
            case RECEIPTS -> renderReceipts();
            case RECEIPT_DETAIL -> renderReceiptDetail();
            case SHOPS -> renderShops();
            case SHOP_DETAIL -> renderShopDetail();
            case OFFER_DETAIL -> renderOfferDetail();
            case TEMPLATE_SELECT -> renderTemplateSelect();
            case ITEM_SELECT -> renderItemSelect();
            case FORM -> renderForm();
            case PREVIEW -> renderPreview();
            case RESULT -> renderResult();
        }
        contents.setItem(REFRESH_SLOT, icon(
                Items.CLOCK, "gui.rovenfall.admin.refresh", "gui.rovenfall.admin.economy.refresh_hint"));
        broadcastChanges();
    }

    private void renderPlayers() {
        var resultPage = playersPage();
        renderListHeader(Items.PLAYER_HEAD, "gui.rovenfall.admin.domain.players", resultPage);
        for (int index = 0; index < resultPage.entries().size(); index++) {
            var row = resultPage.entries().get(index);
            contents.setItem(CONTENT_START + index, AdministrationPlayerHead.create(
                    row.playerId(), row.displayName(),
                    Component.translatable("gui.rovenfall.admin.economy.balance_value", optionalLong(row.balance())),
                    Component.translatable("gui.rovenfall.admin.economy.field.uuid", row.playerId().toString())));
        }
        renderPagination(resultPage.page(), resultPage.totalPages());
    }

    private void renderPlayerDetail() {
        PlayerRecord record = selectedPlayer == null ? null : state().playerRecord(selectedPlayer).orElse(null);
        if (record == null) {
            mode = Mode.PLAYERS;
            renderPlayers();
            return;
        }
        String name = record.displayName().orElse("");
        long balance = state().economyBalance(selectedPlayer).orElse(EconomyConfig.initialBalance());
        contents.setItem(4, AdministrationPlayerHead.create(selectedPlayer, name,
                Component.translatable("gui.rovenfall.admin.economy.balance_value", balance),
                Component.translatable("gui.rovenfall.admin.economy.last_seen", record.lastSeenEpochMillis()),
                Component.translatable("gui.rovenfall.admin.economy.field.uuid", selectedPlayer.toString())));
        contents.setItem(22, icon(
                Items.WRITTEN_BOOK, "gui.rovenfall.admin.economy.receipts", "gui.rovenfall.admin.click"));
        if (canManage(viewer)) {
            contents.setItem(20, icon(
                    Items.EMERALD, "gui.rovenfall.admin.economy.grant", "gui.rovenfall.admin.economy.form.balance"));
            contents.setItem(24, icon(
                    Items.REDSTONE, "gui.rovenfall.admin.economy.debit", "gui.rovenfall.admin.economy.form.balance"));
        }
        renderBack();
    }

    private void renderReceipts() {
        var resultPage = receiptsPage();
        renderListHeader(Items.WRITTEN_BOOK, "gui.rovenfall.admin.domain.receipts", resultPage);
        for (int index = 0; index < resultPage.entries().size(); index++) {
            var row = resultPage.entries().get(index);
            var receipt = row.receipt();
            contents.setItem(CONTENT_START + index, PlayerDashboardMenu.icon(
                    receipt.reversedBy().isPresent() ? Items.BARRIER : Items.WRITTEN_BOOK,
                    receiptKind(receipt),
                    Component.translatable(
                            "gui.rovenfall.admin.economy.field.player_amount",
                            playerName(receipt.playerId()), receipt.amount()),
                    Component.translatable(
                            "gui.rovenfall.admin.economy.field.reversed_by", optionalUuid(receipt.reversedBy())),
                    Component.translatable(
                            "gui.rovenfall.admin.economy.field.transaction", row.transactionId().toString()),
                    Component.literal(receipt.playerId().toString())));
        }
        renderPagination(resultPage.page(), resultPage.totalPages());
    }

    private void renderReceiptDetail() {
        EconomyTransactionReceipt receipt = selectedReceipt == null
                ? null : state().economyReceipt(selectedReceipt).orElse(null);
        if (receipt == null) {
            mode = Mode.RECEIPTS;
            renderReceipts();
            return;
        }
        contents.setItem(4, PlayerDashboardMenu.icon(
                Items.WRITTEN_BOOK, receiptKind(receipt),
                Component.translatable(
                        "gui.rovenfall.admin.economy.field.player_actor",
                        playerName(receipt.playerId()), playerName(receipt.actorId())),
                Component.translatable(
                        "gui.rovenfall.admin.economy.field.amount_time",
                        receipt.amount(), receipt.timestampEpochMillis()),
                Component.translatable(
                        "gui.rovenfall.admin.economy.field.reversed_by", optionalUuid(receipt.reversedBy())),
                Component.translatable(
                        "gui.rovenfall.admin.economy.field.transaction", selectedReceipt.toString()),
                Component.literal(receipt.playerId() + " | " + receipt.actorId())));
        if (canManage(viewer) && canReverse(receipt) && reversalTargetOnline(receipt)) {
            contents.setItem(31, icon(
                    Items.EMERALD, "gui.rovenfall.admin.economy.reverse.strict",
                    "gui.rovenfall.admin.economy.form.reason"));
            if (receipt.kind() == EconomyTransactionReceipt.Kind.PURCHASE) {
                contents.setItem(33, icon(
                        Items.GOLD_INGOT, "gui.rovenfall.admin.economy.reverse.compensate",
                        "gui.rovenfall.admin.economy.reverse.warning"));
            }
        } else if (canManage(viewer)) {
            String reason = receipt.reversedBy().isPresent()
                    ? "gui.rovenfall.admin.economy.reverse.unavailable.reversed"
                    : receipt.invalidatedByRestore().isPresent()
                            ? "gui.rovenfall.admin.economy.reverse.unavailable.restored"
                            : !EconomyReversalService.isReversibleKind(receipt.kind())
                                    ? "gui.rovenfall.admin.economy.reverse.unavailable.kind"
                                    : "gui.rovenfall.admin.economy.reverse.unavailable.offline";
            contents.setItem(31, icon(
                    Items.BARRIER, "gui.rovenfall.admin.economy.reverse.unavailable", reason));
        }
        renderBack();
    }

    private void renderShops() {
        var resultPage = shopsPage();
        renderListHeader(Items.CHEST, "gui.rovenfall.admin.domain.shops", resultPage);
        for (int index = 0; index < resultPage.entries().size(); index++) {
            var row = resultPage.entries().get(index);
            contents.setItem(CONTENT_START + index, PlayerDashboardMenu.icon(
                    Items.CHEST, shopName(row.shop()),
                    Component.translatable(
                            "gui.rovenfall.admin.economy.field.offers_access",
                            row.shop().offers().size(), row.shop().accessPolicy().maxDistance()),
                    Component.translatable(
                            "gui.rovenfall.admin.economy.field.binding", binding(row.shop().binding())),
                    Component.translatable(
                            "gui.rovenfall.admin.economy.field.template", row.shop().templateId().toString()),
                    Component.literal(row.shopId().toString())));
        }
        if (canManage(viewer)) {
            contents.setItem(CREATE_SLOT, icon(
                    Items.EMERALD, "gui.rovenfall.admin.economy.shop.create",
                    "gui.rovenfall.admin.economy.form.shop_create"));
        }
        renderPagination(resultPage.page(), resultPage.totalPages());
    }

    private void renderShopDetail() {
        ShopInstance shop = selectedShop == null ? null : state().shopInstance(selectedShop).orElse(null);
        if (shop == null) {
            mode = Mode.SHOPS;
            renderShops();
            return;
        }
        contents.setItem(4, PlayerDashboardMenu.icon(
                Items.CHEST, shopName(shop),
                Component.translatable("gui.rovenfall.admin.economy.field.binding", binding(shop.binding())),
                Component.translatable(
                        "gui.rovenfall.admin.economy.field.access_offers",
                        shop.accessPolicy().maxDistance(), shop.offers().size()),
                Component.translatable(
                        "gui.rovenfall.admin.economy.field.template", shop.templateId().toString()),
                Component.literal(selectedShop.toString())));
        if (canManage(viewer)) {
            contents.setItem(10, icon(Items.BARRIER, "gui.rovenfall.admin.economy.shop.delete",
                    "gui.rovenfall.admin.economy.form.reason"));
            contents.setItem(11, icon(Items.TRIPWIRE_HOOK, "gui.rovenfall.admin.economy.shop.bind",
                    "gui.rovenfall.admin.economy.form.reason"));
            contents.setItem(12, icon(Items.SHEARS, "gui.rovenfall.admin.economy.shop.unbind",
                    "gui.rovenfall.admin.economy.form.reason"));
            contents.setItem(13, icon(Items.COMPASS, "gui.rovenfall.admin.economy.shop.access",
                    "gui.rovenfall.admin.economy.form.access"));
            contents.setItem(14, icon(Items.EMERALD, "gui.rovenfall.admin.economy.offer.upsert",
                    "gui.rovenfall.admin.economy.form.offer"));
        }
        List<Identifier> offers = offerIds(shop);
        int from = Math.min(offers.size(), page * OFFER_PAGE_SIZE);
        int to = Math.min(offers.size(), from + OFFER_PAGE_SIZE);
        for (int index = from; index < to; index++) {
            Identifier offerId = offers.get(index);
            ShopInstance.Offer offer = shop.offers().get(offerId);
            ItemStack display = offer.item();
            display.setCount(1);
            contents.setItem(OFFER_CONTENT_START + index - from, PlayerDashboardMenu.icon(
                    display.getItem(), display.getHoverName(),
                    Component.translatable(
                            "gui.rovenfall.admin.economy.field.prices",
                            optionalLong(offer.buyPrice()), optionalLong(offer.sellPrice())),
                    stock(offer.stock()),
                    Component.translatable(
                            "gui.rovenfall.admin.economy.field.item_count",
                            BuiltInRegistries.ITEM.getKey(offer.item().getItem()).toString(), offer.item().getCount()),
                    Component.literal(offerId.toString())));
        }
        int totalPages = offers.isEmpty() ? 0 : (offers.size() + OFFER_PAGE_SIZE - 1) / OFFER_PAGE_SIZE;
        renderPagination(page, totalPages);
    }

    private void renderOfferDetail() {
        ShopInstance shop = selectedShop == null ? null : state().shopInstance(selectedShop).orElse(null);
        ShopInstance.Offer offer = shop == null || selectedOffer == null ? null : shop.offers().get(selectedOffer);
        if (offer == null) {
            mode = Mode.SHOP_DETAIL;
            renderShopDetail();
            return;
        }
        contents.setItem(4, PlayerDashboardMenu.icon(
                offer.item().getItem(), offer.item().getHoverName(),
                Component.translatable(
                        "gui.rovenfall.admin.economy.field.prices",
                        optionalLong(offer.buyPrice()), optionalLong(offer.sellPrice())),
                stock(offer.stock()),
                Component.translatable(
                        "gui.rovenfall.admin.economy.field.item_count",
                        BuiltInRegistries.ITEM.getKey(offer.item().getItem()).toString(), offer.item().getCount()),
                Component.literal(selectedOffer.toString())));
        if (canManage(viewer)) {
            contents.setItem(20, icon(Items.EMERALD, "gui.rovenfall.admin.economy.offer.upsert",
                    "gui.rovenfall.admin.economy.form.offer"));
            contents.setItem(22, icon(Items.BARRIER, "gui.rovenfall.admin.economy.offer.remove",
                    "gui.rovenfall.admin.economy.form.reason"));
            contents.setItem(24, icon(Items.CLOCK, "gui.rovenfall.admin.economy.offer.restock",
                    "gui.rovenfall.admin.economy.form.restock"));
        }
        renderBack();
    }

    private void renderTemplateSelect() {
        List<Identifier> templates = templateIds();
        int from = Math.min(templates.size(), page * CONTENT_SIZE);
        int to = Math.min(templates.size(), from + CONTENT_SIZE);
        ItemStack header = PlayerDashboardMenu.icon(
                Items.CHEST, Component.translatable("gui.rovenfall.admin.economy.shop.create"),
                Component.translatable("gui.rovenfall.admin.click"));
        AdministrationFormMarker.writeSearch(header);
        contents.setItem(4, header);
        for (int index = from; index < to; index++) {
            Identifier id = templates.get(index);
            var template = ShopTemplateReloadListener.snapshot(viewer.level().getServer()).get(id).orElseThrow();
            contents.setItem(CONTENT_START + index - from, PlayerDashboardMenu.icon(
                    Items.CHEST, Component.translatable(template.translationKey()),
                    Component.translatable("gui.rovenfall.admin.economy.field.offers_access", template.offers().size(), 0),
                    Component.literal(id.toString())));
        }
        renderPagination(page, pages(templates.size()));
    }

    private void renderItemSelect() {
        List<Identifier> items = itemIds();
        int from = Math.min(items.size(), page * CONTENT_SIZE);
        int to = Math.min(items.size(), from + CONTENT_SIZE);
        ItemStack header = PlayerDashboardMenu.icon(
                Items.EMERALD, Component.translatable("gui.rovenfall.admin.economy.offer.upsert"),
                Component.translatable("gui.rovenfall.admin.click"));
        AdministrationFormMarker.writeSearch(header);
        contents.setItem(4, header);
        for (int index = from; index < to; index++) {
            Identifier id = items.get(index);
            Item item = BuiltInRegistries.ITEM.getValue(id);
            contents.setItem(CONTENT_START + index - from, PlayerDashboardMenu.icon(
                    item, item.getDefaultInstance().getHoverName(), Component.literal(id.toString())));
        }
        renderPagination(page, pages(items.size()));
    }

    private void renderForm() {
        ItemStack header = PlayerDashboardMenu.icon(
                Items.WRITABLE_BOOK, Component.translatable("gui.rovenfall.admin.economy.form.title"),
                Component.translatable(formHint(formKind)),
                Component.translatable("gui.rovenfall.admin.economy.form.submit"),
                formError.isBlank() ? Component.empty()
                        : Component.translatable("gui.rovenfall.admin.economy.error", formError));
        AdministrationFormMarker.write(header, new AdministrationFormMarker(formType(formKind), formDefaults(formKind)));
        if (!formError.isBlank()) {
            AdministrationFormMarker.writeError(header);
        }
        contents.setItem(4, header);
        renderBack();
    }

    private void renderPreview() {
        List<Component> preview = previewLines(pending);
        contents.setItem(4, PlayerDashboardMenu.icon(
                Items.WRITABLE_BOOK, Component.translatable("gui.rovenfall.admin.economy.preview"),
                preview.toArray(Component[]::new)));
        if (canManage(viewer)) {
            contents.setItem(CONFIRM_SLOT, icon(
                    Items.EMERALD, "gui.rovenfall.admin.economy.confirm",
                    "gui.rovenfall.admin.economy.confirm_fresh"));
        }
        contents.setItem(CANCEL_SLOT, icon(
                Items.BARRIER, "gui.rovenfall.admin.economy.cancel", "gui.rovenfall.admin.click"));
        renderBack();
    }

    private void renderResult() {
        Item item = result != null && result.succeeded() ? Items.EMERALD : Items.BARRIER;
        contents.setItem(4, PlayerDashboardMenu.icon(
                item, Component.translatable(result != null && result.succeeded()
                        ? "gui.rovenfall.admin.economy.result.success"
                        : "gui.rovenfall.admin.economy.result.failed"),
                resultDetail(result == null ? "unknown" : result.detail()),
                Component.translatable(
                        "gui.rovenfall.admin.economy.field.transaction",
                        result == null || result.transactionId() == null
                                ? Component.translatable("gui.rovenfall.admin.economy.none")
                                : result.transactionId().toString())));
        contents.setItem(CONFIRM_SLOT, icon(
                Items.ARROW, "gui.rovenfall.admin.economy.continue", "gui.rovenfall.admin.click"));
        renderBack();
    }

    private <T> void renderListHeader(
            Item item, String titleKey, AdministrationEconomyViewService.Page<T> resultPage) {
        if (resultPage.status() != AdministrationEconomyViewService.Status.SUCCESS) {
            denyAndClose();
            return;
        }
        ItemStack header = PlayerDashboardMenu.icon(
                item, Component.translatable(titleKey),
                Component.translatable("gui.rovenfall.admin.page", page + 1, Math.max(1, resultPage.totalPages())),
                Component.translatable("gui.rovenfall.admin.total", resultPage.totalEntries()),
                Component.translatable(resultPage.truncated()
                        ? "gui.rovenfall.admin.truncated" : "gui.rovenfall.admin.complete"),
                Component.translatable("gui.rovenfall.admin.query", query.isBlank() ? "*" : query));
        AdministrationFormMarker.writeSearch(header);
        contents.setItem(4, header);
        if (resultPage.entries().isEmpty()) {
            contents.setItem(22, icon(
                    Items.BARRIER, "gui.rovenfall.admin.empty", "gui.rovenfall.admin.search_hint"));
        }
    }

    private void renderPagination(int currentPage, int totalPages) {
        renderBack();
        if (currentPage > 0) {
            contents.setItem(PREVIOUS_SLOT, icon(
                    Items.ARROW, "gui.rovenfall.admin.previous", "gui.rovenfall.admin.click"));
        }
        if (currentPage + 1 < totalPages) {
            contents.setItem(NEXT_SLOT, icon(
                    Items.ARROW, "gui.rovenfall.admin.next", "gui.rovenfall.admin.click"));
        }
    }

    private void renderBack() {
        contents.setItem(BACK_SLOT, icon(
                Items.ARROW, "gui.rovenfall.admin.back", "gui.rovenfall.admin.click"));
    }

    private AdministrationEconomyViewService.Page<AdministrationEconomyViewService.PlayerRow> playersPage() {
        return AdministrationEconomyViewService.players(state(), viewerId, authorizationOverride(), query, page);
    }

    private AdministrationEconomyViewService.Page<AdministrationEconomyViewService.ShopRow> shopsPage() {
        return AdministrationEconomyViewService.shops(state(), viewerId, authorizationOverride(), query, page);
    }

    private AdministrationEconomyViewService.Page<AdministrationEconomyViewService.ReceiptRow> receiptsPage() {
        return AdministrationEconomyViewService.receipts(
                state(), viewerId, authorizationOverride(), receiptPlayerFilter, query, page);
    }

    private PlatformSavedData state() {
        return PlatformSavedData.get(viewer.level().getServer());
    }

    private boolean authorizationOverride() {
        return state().roleOf(viewerId).isEmpty();
    }

    private void denyAndClose() {
        viewer.sendSystemMessage(Component.translatable("gui.rovenfall.admin.denied"));
        viewer.closeContainer();
    }

    private static ItemStack icon(Item item, String title, String lore) {
        return PlayerDashboardMenu.icon(item, Component.translatable(title), Component.translatable(lore));
    }

    static ItemStack offerStack(ItemStack existing, Identifier requestedItemId, int count) {
        if (requestedItemId == null || count < 1) {
            return ItemStack.EMPTY;
        }
        Item requestedItem = BuiltInRegistries.ITEM.getValue(requestedItemId);
        if (requestedItem == null || requestedItem == Items.AIR || count > requestedItem.getDefaultMaxStackSize()) {
            return ItemStack.EMPTY;
        }
        if (existing != null && !existing.isEmpty()
                && requestedItemId.equals(BuiltInRegistries.ITEM.getKey(existing.getItem()))) {
            return copyOfferStack(existing, count);
        }
        return new ItemStack(requestedItem, count);
    }

    static ItemStack copyOfferStack(ItemStack existing, int count) {
        if (existing == null || existing.isEmpty() || count < 1 || count > existing.getMaxStackSize()) {
            return ItemStack.EMPTY;
        }
        ItemStack preserved = existing.copy();
        preserved.setCount(count);
        return preserved;
    }

    static boolean canReverse(EconomyTransactionReceipt receipt) {
        return receipt != null
                && EconomyReversalService.isReversibleKind(receipt.kind())
                && receipt.reversedBy().isEmpty()
                && receipt.invalidatedByRestore().isEmpty();
    }

    private boolean reversalTargetOnline(EconomyTransactionReceipt receipt) {
        return receipt != null
                && viewer.level().getServer().getPlayerList().getPlayer(receipt.playerId()) != null;
    }

    private List<Component> previewLines(AdministrationEconomyActionService.PendingAction action) {
        List<Component> lines = new java.util.ArrayList<>();
        if (action instanceof AdministrationEconomyActionService.BalanceAction value) {
            long before = value.expectedBalance().orElse(EconomyConfig.initialBalance());
            String after;
            try {
                after = Long.toString(value.grant()
                        ? Math.addExact(before, value.amount()) : Math.subtractExact(before, value.amount()));
            } catch (ArithmeticException exception) {
                after = "invalid";
            }
            lines.add(Component.translatable(
                    value.grant() ? "gui.rovenfall.admin.economy.grant" : "gui.rovenfall.admin.economy.debit"));
            lines.add(Component.translatable(
                    "gui.rovenfall.admin.economy.preview.player", playerName(value.playerId())));
            lines.add(Component.translatable(
                    "gui.rovenfall.admin.economy.preview.amount", value.amount()));
            lines.add(Component.translatable(
                    "gui.rovenfall.admin.economy.preview.balance_change", before, after));
        } else if (action instanceof AdministrationEconomyActionService.ShopCreateAction value) {
            lines.add(Component.translatable("gui.rovenfall.admin.economy.shop.create"));
            lines.add(Component.translatable(
                    "gui.rovenfall.admin.economy.preview.shop_template",
                    value.shopId().toString(), value.templateId().toString(), value.expectedTemplate().offers().size()));
        } else if (action instanceof AdministrationEconomyActionService.ShopDeleteAction value) {
            ShopInstance before = value.expectedShop().orElseThrow();
            lines.add(Component.translatable("gui.rovenfall.admin.economy.shop.delete"));
            lines.add(Component.translatable(
                    "gui.rovenfall.admin.economy.preview.shop_delete",
                    value.shopId().toString(), before.templateId().toString(), before.offers().size()));
        } else if (action instanceof AdministrationEconomyActionService.ShopBindingAction value) {
            ShopInstance before = value.expectedShop().orElseThrow();
            lines.add(Component.translatable(
                    value.binding().isPresent()
                            ? "gui.rovenfall.admin.economy.shop.bind"
                            : "gui.rovenfall.admin.economy.shop.unbind"));
            lines.add(Component.translatable(
                    "gui.rovenfall.admin.economy.preview.change",
                    binding(before.binding()), binding(value.binding())));
        } else if (action instanceof AdministrationEconomyActionService.ShopAccessAction value) {
            ShopInstance before = value.expectedShop().orElseThrow();
            lines.add(Component.translatable("gui.rovenfall.admin.economy.shop.access"));
            lines.add(Component.translatable(
                    "gui.rovenfall.admin.economy.preview.change",
                    before.accessPolicy().maxDistance(), value.maxDistance()));
        } else if (action instanceof AdministrationEconomyActionService.ShopOfferAction value) {
            ShopInstance.Offer before = value.expectedShop().orElseThrow().offers().get(value.offerId());
            lines.add(Component.translatable("gui.rovenfall.admin.economy.offer.upsert"));
            if (before != null) {
                lines.add(offerPreview("gui.rovenfall.admin.economy.preview.before", value.offerId(), before));
            }
            lines.add(offerPreview("gui.rovenfall.admin.economy.preview.after", value.offerId(), value.offer()));
        } else if (action instanceof AdministrationEconomyActionService.ShopOfferRemoveAction value) {
            ShopInstance.Offer before = value.expectedShop().orElseThrow().offers().get(value.offerId());
            lines.add(Component.translatable("gui.rovenfall.admin.economy.offer.remove"));
            lines.add(before == null
                    ? Component.translatable("gui.rovenfall.admin.economy.preview.missing")
                    : offerPreview("gui.rovenfall.admin.economy.preview.before", value.offerId(), before));
        } else if (action instanceof AdministrationEconomyActionService.ShopRestockAction value) {
            ShopInstance.Offer before = value.expectedShop().orElseThrow().offers().get(value.offerId());
            lines.add(Component.translatable("gui.rovenfall.admin.economy.offer.restock"));
            lines.add(Component.translatable(
                    "gui.rovenfall.admin.economy.preview.restock",
                    before == null
                            ? Component.translatable("gui.rovenfall.admin.economy.preview.missing")
                            : stock(before.stock()),
                    optionalLong(value.amount()), optionalLong(value.intervalTicks())));
        } else if (action instanceof AdministrationEconomyActionService.ReceiptReversalAction value) {
            EconomyTransactionReceipt receipt = value.expectedReceipt();
            long before = value.expectedBalance().orElse(0L);
            String after = reversalBalance(receipt, before);
            lines.add(Component.translatable(
                    value.decision() == EconomyTransactionReceipt.CompensationDecision.NONE
                            ? "gui.rovenfall.admin.economy.reverse.strict"
                            : "gui.rovenfall.admin.economy.reverse.compensate"));
            lines.add(Component.translatable(
                    "gui.rovenfall.admin.economy.preview.receipt",
                    value.originalTransactionId().toString(), receiptKind(receipt), receipt.amount()));
            lines.add(Component.translatable(
                    "gui.rovenfall.admin.economy.preview.balance_change", before, after));
            lines.add(Component.translatable(
                    "gui.rovenfall.admin.economy.preview.reversal_legs",
                    receipt.isTrade()
                            ? value.decision() == EconomyTransactionReceipt.CompensationDecision.NONE
                                    ? Component.translatable("gui.rovenfall.admin.economy.preview.legs.trade_strict")
                                    : Component.translatable("gui.rovenfall.admin.economy.preview.legs.compensation")
                            : Component.translatable("gui.rovenfall.admin.economy.preview.legs.balance")));
        }
        lines.add(Component.translatable(
                "gui.rovenfall.admin.economy.preview.reason", action.reason()));
        lines.add(Component.translatable(
                "gui.rovenfall.admin.economy.preview.transaction", action.transactionId().toString()));
        return lines;
    }

    private static Component offerPreview(String key, Identifier offerId, ShopInstance.Offer offer) {
        return Component.translatable(
                key, offerId.toString(), offer.item().getHoverName(), offer.item().getCount(),
                optionalLong(offer.buyPrice()), optionalLong(offer.sellPrice()), stock(offer.stock()));
    }

    private static String reversalBalance(EconomyTransactionReceipt receipt, long before) {
        try {
            return switch (receipt.kind()) {
                case ADMIN_GRANT, AWARD, SALE -> Long.toString(Math.subtractExact(before, receipt.amount()));
                case ADMIN_DEBIT, DEBIT, PURCHASE -> Long.toString(Math.addExact(before, receipt.amount()));
                default -> "not_reversible";
            };
        } catch (ArithmeticException exception) {
            return "invalid";
        }
    }

    private static List<Identifier> offerIds(ShopInstance shop) {
        return shop.offers().keySet().stream().sorted().toList();
    }

    private static Component optionalLong(Optional<Long> value) {
        return value.<Component>map(number -> Component.literal(Long.toString(number)))
                .orElseGet(() -> Component.translatable("gui.rovenfall.admin.economy.none"));
    }

    private static Component stock(ShopInstance.Stock stock) {
        Component amount = stock.unlimited()
                ? Component.translatable("gui.rovenfall.admin.economy.stock_unlimited")
                : Component.translatable(
                        "gui.rovenfall.admin.economy.stock_finite", stock.current(), stock.maximum());
        return Component.translatable(
                "gui.rovenfall.admin.economy.field.stock_restock",
                amount, optionalLong(stock.restockAmount()), optionalLong(stock.restockIntervalTicks()));
    }

    private static Component binding(Optional<ShopInstance.Binding> binding) {
        return binding.<Component>map(value -> Component.literal(
                        value.dimension().identifier() + " @ " + value.position()))
                .orElseGet(() -> Component.translatable("gui.rovenfall.admin.economy.none"));
    }

    private List<Identifier> templateIds() {
        String normalized = query.strip().toLowerCase(java.util.Locale.ROOT);
        return ShopTemplateReloadListener.snapshot(viewer.level().getServer()).templates().entrySet().stream()
                .filter(entry -> normalized.isBlank() || entry.getKey().toString().contains(normalized)
                        || entry.getValue().translationKey().toLowerCase(java.util.Locale.ROOT).contains(normalized))
                .map(java.util.Map.Entry::getKey).sorted().toList();
    }

    private List<Identifier> itemIds() {
        String normalized = query.strip().toLowerCase(java.util.Locale.ROOT);
        return BuiltInRegistries.ITEM.keySet().stream()
                .filter(id -> id != null && !id.equals(BuiltInRegistries.ITEM.getKey(Items.AIR)))
                .filter(id -> normalized.isBlank() || id.toString().contains(normalized)
                        || BuiltInRegistries.ITEM.getValue(id).getDefaultInstance().getHoverName().getString()
                                .toLowerCase(java.util.Locale.ROOT).contains(normalized))
                .sorted().toList();
    }

    private static int pages(int entries) {
        return entries == 0 ? 0 : (entries + CONTENT_SIZE - 1) / CONTENT_SIZE;
    }

    private Component shopName(ShopInstance shop) {
        return shop == null ? Component.translatable("gui.rovenfall.admin.economy.none")
                : Component.translatable(ShopTemplateReloadListener.snapshot(viewer.level().getServer()).get(shop.templateId())
                        .map(template -> template.translationKey()).orElse("gui.rovenfall.admin.economy.none"));
    }

    private Component playerName(UUID playerId) {
        String name = state().playerRecord(playerId).flatMap(PlayerRecord::displayName).orElse("");
        return name.isBlank()
                ? Component.translatable("gui.rovenfall.player.unknown_player")
                : Component.literal(name);
    }

    private static AdministrationFormType formType(FormKind kind) {
        return switch (kind) {
            case GRANT -> AdministrationFormType.ECONOMY_GRANT;
            case DEBIT -> AdministrationFormType.ECONOMY_DEBIT;
            case CREATE_SHOP -> AdministrationFormType.ECONOMY_SHOP_CREATE;
            case DELETE_SHOP -> AdministrationFormType.ECONOMY_SHOP_DELETE;
            case BIND_HERE -> AdministrationFormType.ECONOMY_SHOP_BIND_HERE;
            case UNBIND -> AdministrationFormType.ECONOMY_SHOP_UNBIND;
            case ACCESS -> AdministrationFormType.ECONOMY_SHOP_ACCESS;
            case UPSERT_OFFER -> AdministrationFormType.ECONOMY_OFFER_UPSERT;
            case REMOVE_OFFER -> AdministrationFormType.ECONOMY_OFFER_REMOVE;
            case RESTOCK -> AdministrationFormType.ECONOMY_RESTOCK;
            case REVERSE_STRICT -> AdministrationFormType.ECONOMY_REVERSE_STRICT;
            case REVERSE_COMPENSATE -> AdministrationFormType.ECONOMY_REVERSE_COMPENSATE;
        };
    }

    private List<String> formDefaults(FormKind kind) {
        if (kind == FormKind.UPSERT_OFFER) {
            ShopInstance.Offer offer = selectedShop == null || selectedOffer == null
                    ? null : state().shopInstance(selectedShop).map(shop -> shop.offers().get(selectedOffer)).orElse(null);
            if (offer != null) {
                String direction = offer.buyPrice().isPresent() && offer.sellPrice().isPresent() ? "both"
                        : offer.buyPrice().isPresent() ? "buy" : "sell";
                return List.of(direction, offer.buyPrice().map(Object::toString).orElse(""),
                        offer.sellPrice().map(Object::toString).orElse(""),
                        offer.stock().unlimited() ? "unlimited" : "finite",
                        offer.stock().unlimited() ? "" : Long.toString(offer.stock().current()),
                        offer.stock().unlimited() ? "" : Long.toString(offer.stock().maximum()),
                        Integer.toString(offer.item().getCount()), "");
            }
        }
        if (kind == FormKind.RESTOCK) {
            ShopInstance.Offer offer = selectedShop == null || selectedOffer == null
                    ? null : state().shopInstance(selectedShop).map(shop -> shop.offers().get(selectedOffer)).orElse(null);
            if (offer != null) {
                boolean enabled = offer.stock().restockAmount().isPresent() && offer.stock().restockIntervalTicks().isPresent();
                return List.of(Boolean.toString(enabled), offer.stock().restockAmount().map(Object::toString).orElse("1"),
                        offer.stock().restockIntervalTicks().map(Object::toString).orElse("1200"), "");
            }
        }
        return formType(kind).defaults();
    }

    private static Component optionalUuid(Optional<UUID> value) {
        return value.<Component>map(uuid -> Component.literal(uuid.toString()))
                .orElseGet(() -> Component.translatable("gui.rovenfall.admin.economy.none"));
    }

    private static Component receiptKind(EconomyTransactionReceipt receipt) {
        return Component.translatable(
                "economy_transaction_kind.rovenfall." + receipt.kind().getSerializedName());
    }

    private static Component resultDetail(String detail) {
        String suffix = switch (detail) {
            case "success" -> "success";
            case "duplicate_transaction" -> "duplicate";
            case "stale_confirmation" -> "stale";
            case "unauthorized" -> "unauthorized";
            case "target_offline" -> "target_offline";
            case "rate_limited" -> "rate_limited";
            case "read_only_schema" -> "read_only";
            case "transaction_id_conflict", "invalid_transaction", "transaction_ledger_full" -> "transaction";
            case "insufficient_funds" -> "funds";
            case "maximum_exceeded", "maximum_balance_exceeded", "overflow" -> "balance_limit";
            case "compensation_required", "shop_mismatch", "exact_items_unavailable",
                    "stock_inverse_unavailable", "insufficient_space", "inventory_update_failed" -> "evidence";
            case "original_not_reversible", "already_reversed" -> "not_reversible";
            case "dependency_locked" -> "busy";
            default -> "invalid";
        };
        return Component.translatable("gui.rovenfall.admin.economy.result.detail." + suffix);
    }

    private static String formHint(FormKind kind) {
        return switch (kind) {
            case GRANT, DEBIT -> "gui.rovenfall.admin.economy.form.balance";
            case CREATE_SHOP -> "gui.rovenfall.admin.economy.form.shop_create";
            case DELETE_SHOP, BIND_HERE, UNBIND, REMOVE_OFFER, REVERSE_STRICT, REVERSE_COMPENSATE ->
                    "gui.rovenfall.admin.economy.form.reason";
            case ACCESS -> "gui.rovenfall.admin.economy.form.access";
            case UPSERT_OFFER -> "gui.rovenfall.admin.economy.form.offer";
            case RESTOCK -> "gui.rovenfall.admin.economy.form.restock";
        };
    }

    private enum Mode {
        PLAYERS,
        PLAYER_DETAIL,
        RECEIPTS,
        RECEIPT_DETAIL,
        SHOPS,
        SHOP_DETAIL,
        OFFER_DETAIL,
        TEMPLATE_SELECT,
        ITEM_SELECT,
        FORM,
        PREVIEW,
        RESULT
    }

    private enum FormKind {
        GRANT,
        DEBIT,
        CREATE_SHOP,
        DELETE_SHOP,
        BIND_HERE,
        UNBIND,
        ACCESS,
        UPSERT_OFFER,
        REMOVE_OFFER,
        RESTOCK,
        REVERSE_STRICT,
        REVERSE_COMPENSATE
    }
}
