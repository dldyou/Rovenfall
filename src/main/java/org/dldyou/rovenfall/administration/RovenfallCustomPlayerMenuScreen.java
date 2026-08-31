package org.dldyou.rovenfall.administration;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.TooltipFlag;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import org.lwjgl.glfw.GLFW;

/** Card-based client view over the existing server-authoritative chest menu. */
final class RovenfallCustomPlayerMenuScreen extends ContainerScreen {
    private final List<RovenfallMenuCardButton> cards = new ArrayList<>();
    private RovenfallPlayerMenuLayout.Layout layout;
    private Button technicalButton;
    private EditBox query;
    private String queryValue = "";
    private int searchHeaderSlot = -1;
    private boolean advanced;
    private int page;
    private int contentCount;
    private int observedStateId;

    RovenfallCustomPlayerMenuScreen(
            ChestMenu menu,
            Inventory inventory,
            Component title,
            PlayerMenuNetwork.MenuKind kind) {
        super(menu, inventory, title);
        if (kind.isAdministration()) {
            throw new IllegalArgumentException("Administration menus use RovenfallAdministrationMenuScreen");
        }
    }

    @Override
    protected void init() {
        super.init();
        layout = RovenfallPlayerMenuLayout.fit(width, height);
        observedStateId = menu.getStateId();
        rebuildCards(true);
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        int stateId = menu.getStateId();
        if (stateId != observedStateId) {
            observedStateId = stateId;
            rebuildCards(true);
        }
    }

    private void rebuildCards(boolean resetPage) {
        String previousQuery = query == null ? queryValue : query.getValue();
        boolean previouslySearchable = searchHeaderSlot >= 0;
        setFocused(null);
        clearWidgets();
        cards.clear();
        query = null;

        int menuSlots = menu.getRowCount() * 9;
        int toolbarStart = RovenfallPlayerMenuLayout.toolbarStart(menu.getRowCount());
        List<Integer> contentSlots = occupiedSlots(0, Math.min(toolbarStart, menuSlots));
        List<Integer> toolbarSlots = occupiedSlots(Math.min(toolbarStart, menuSlots), menuSlots);
        searchHeaderSlot = contentSlots.stream()
                .filter(slotId -> AdministrationFormMarker.hasSearch(menu.getSlot(slotId).getItem()))
                .findFirst()
                .orElse(-1);
        if (!previouslySearchable || searchHeaderSlot < 0) {
            previousQuery = "";
        }
        layout = RovenfallPlayerMenuLayout.fit(width, height, searchHeaderSlot >= 0);
        contentCount = contentSlots.size();
        if (resetPage) {
            page = 0;
        }
        int pages = pageCount();
        page = Math.clamp(page, 0, pages - 1);
        int from = Math.min(contentSlots.size(), page * layout.pageSize());
        int to = Math.min(contentSlots.size(), from + layout.pageSize());
        for (int index = from; index < to; index++) {
            addCard(contentSlots.get(index), layout.card(index - from));
        }
        for (int index = 0; index < toolbarSlots.size(); index++) {
            addCard(toolbarSlots.get(index), layout.toolbarButton(index, toolbarSlots.size()));
        }
        addSearch(previousQuery);
        addTechnicalButton();
        if (pages > 1) {
            addPageButton(-1, layout.previousPageButton(), "gui.rovenfall.player.previous").active = page > 0;
            addPageButton(1, layout.nextPageButton(), "gui.rovenfall.player.next").active = page + 1 < pages;
        }
        if (!cards.isEmpty()) {
            setFocused(cards.getFirst());
            cards.getFirst().setFocused(true);
        }
    }

    private void addSearch(String previousQuery) {
        if (searchHeaderSlot < 0) {
            return;
        }
        queryValue = shorten(previousQuery);
        var field = layout.searchField();
        Component label = Component.translatable("gui.rovenfall.claim.atlas.search");
        query = addRenderableWidget(new EditBox(
                font, field.x(), field.y(), field.width(), field.height(), label));
        query.setBordered(false);
        query.setTextColor(RovenfallUiTheme.TEXT_PRIMARY);
        query.setTextColorUneditable(RovenfallUiTheme.TEXT_MUTED);
        query.setMaxLength(ClaimAtlasView.MAX_QUERY_LENGTH);
        query.setHint(label);
        query.setValue(queryValue);
        var submit = layout.searchButton();
        addRenderableWidget(Button.builder(
                        Component.translatable("gui.rovenfall.claim.atlas.search.submit"), ignored -> submitQuery())
                .bounds(submit.x(), submit.y(), submit.width(), submit.height())
                .build(RovenfallButton::new));
    }

    private void submitQuery() {
        if (query == null) {
            return;
        }
        queryValue = query.getValue();
        ClientPacketDistributor.sendToServer(new PlayerMenuNetwork.AdminQuery(
                menu.containerId, menu.getStateId(), queryValue));
        afterKeyboardAction();
    }

    private static String shorten(String value) {
        return value.length() <= ClaimAtlasView.MAX_QUERY_LENGTH
                ? value
                : value.substring(0, ClaimAtlasView.MAX_QUERY_LENGTH);
    }

    private List<Integer> occupiedSlots(int from, int to) {
        List<Integer> result = new ArrayList<>();
        for (int slotId = from; slotId < to; slotId++) {
            if (menu.getSlot(slotId).hasItem()) {
                result.add(slotId);
            }
        }
        return result;
    }

    private void addCard(int slotId, RovenfallPlayerMenuLayout.Bounds bounds) {
        Slot slot = menu.getSlot(slotId);
        Item.TooltipContext tooltipContext = minecraft.level == null
                ? Item.TooltipContext.EMPTY
                : Item.TooltipContext.of(minecraft.level);
        List<Component> tooltip = slot.getItem().getTooltipLines(
                tooltipContext, minecraft.player, TooltipFlag.NORMAL);
        Component title = tooltip.isEmpty() ? slot.getItem().getHoverName() : tooltip.getFirst();
        List<Component> exposed = exposedLines(tooltip, advanced);
        Component summary = exposed.size() > 1 ? exposed.get(1) : Component.empty();
        Button.Builder builder = Button.builder(title, ignored -> activate(slotId))
                .tooltip(Tooltip.create(joinLines(exposed)))
                .bounds(bounds.x(), bounds.y(), bounds.width(), bounds.height());
        RovenfallMenuCardButton card = new RovenfallMenuCardButton(
                builder, font, slot.getItem(), summary, exposed);
        cards.add(addRenderableWidget(card));
    }

    private void addTechnicalButton() {
        Component narration = Component.translatable("gui.rovenfall.admin.advanced");
        var bounds = layout.technicalButton();
        technicalButton = addRenderableWidget(Button.builder(Component.literal("⋮"), ignored -> toggleAdvanced())
                .createNarration(ignored -> narration.copy())
                .tooltip(Tooltip.create(joinLines(List.of(
                        narration,
                        Component.translatable("gui.rovenfall.admin.advanced_info_hint")))))
                .bounds(bounds.x(), bounds.y(), bounds.width(), bounds.height())
                .build(RovenfallButton::new));
    }

    private void toggleAdvanced() {
        advanced = !advanced;
        rebuildCards(false);
        setFocused(technicalButton);
        technicalButton.setFocused(true);
        afterKeyboardAction();
    }

    private Button addPageButton(int delta, RovenfallPlayerMenuLayout.Bounds bounds, String translationKey) {
        Component label = Component.translatable(translationKey);
        return addRenderableWidget(Button.builder(label, ignored -> changePage(delta))
                .tooltip(Tooltip.create(label))
                .bounds(bounds.x(), bounds.y(), bounds.width(), bounds.height())
                .build(RovenfallButton::new));
    }

    private void activate(int slotId) {
        if (slotId < 0 || slotId >= menu.getRowCount() * 9 || !menu.getSlot(slotId).hasItem()) {
            return;
        }
        slotClicked(menu.getSlot(slotId), slotId, 0, ContainerInput.PICKUP);
        afterKeyboardAction();
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        RovenfallUiTheme.extractBackdrop(graphics, width, height);
        RovenfallUiTheme.extractPanel(graphics, new RovenfallUiTheme.PanelBounds(
                layout.panel().x(), layout.panel().y(), layout.panel().width(), layout.panel().height()));
        RovenfallUiTheme.extractField(graphics,
                layout.cards().x(), layout.cards().y(), layout.cards().width(), layout.cards().height(), false);
        RovenfallUiTheme.extractField(graphics,
                layout.toolbar().x(), layout.toolbar().y(), layout.toolbar().width(), layout.toolbar().height(), false);
        if (layout.wide()) {
            RovenfallUiTheme.extractField(graphics,
                    layout.detail().x(), layout.detail().y(), layout.detail().width(), layout.detail().height(), false);
        }
        if (query != null) {
            RovenfallUiTheme.extractField(
                    graphics, query.getX(), query.getY(), query.getWidth(), query.getHeight(), query.isFocused());
        }
        graphics.text(font, title, layout.panel().x() + 9, layout.panel().y() + 8,
                RovenfallUiTheme.TEXT_PRIMARY, false);
        var pageLabel = layout.pageLabel();
        if (pageLabel.width() > 0) {
            graphics.enableScissor(pageLabel.x(), pageLabel.y(), pageLabel.right(), pageLabel.bottom());
            try {
                graphics.text(font, pageLine(), pageLabel.x(), pageLabel.y(),
                        RovenfallUiTheme.TEXT_MUTED, false);
            } finally {
                graphics.disableScissor();
            }
        }
    }

    @Override
    public void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractContents(graphics, mouseX, mouseY, partialTick);
        if (layout.wide()) {
            extractDetail(graphics);
        }
    }

    private void extractDetail(GuiGraphicsExtractor graphics) {
        RovenfallMenuCardButton selected = selectedCard();
        if (selected == null) {
            return;
        }
        var bounds = layout.detail();
        graphics.enableScissor(bounds.x() + 4, bounds.y() + 4, bounds.right() - 4, bounds.bottom() - 4);
        try {
            graphics.item(selected.item(), bounds.x() + 8, bounds.y() + 8);
            graphics.itemDecorations(font, selected.item(), bounds.x() + 8, bounds.y() + 8);
            int textX = bounds.x() + 31;
            int y = bounds.y() + 9;
            int textWidth = Math.max(1, bounds.right() - textX - 7);
            for (Component line : selected.detailLines()) {
                for (var wrapped : font.split(line, textWidth)) {
                    if (y + font.lineHeight > bounds.bottom() - 5) {
                        return;
                    }
                    graphics.text(font, wrapped, textX, y,
                            y == bounds.y() + 9 ? RovenfallUiTheme.TEXT_PRIMARY : RovenfallUiTheme.TEXT_MUTED,
                            false);
                    y += font.lineHeight + 2;
                }
                y += 2;
            }
        } finally {
            graphics.disableScissor();
        }
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
    }

    @Override
    protected void extractSlots(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
    }

    @Override
    protected boolean isHovering(int left, int top, int width, int height, double mouseX, double mouseY) {
        return false;
    }

    @Override
    protected void updateNarrationState(NarrationElementOutput output) {
        super.updateNarrationState(output);
        RovenfallMenuCardButton selected = hoveredCard();
        if (selected == null && getFocused() instanceof RovenfallMenuCardButton focusedCard) {
            selected = focusedCard;
        }
        if (selected == null) {
            if (cards.isEmpty()) {
                output.add(NarratedElementType.HINT, Component.translatable("gui.rovenfall.menu.no_actions"));
            }
            return;
        }
        output.add(NarratedElementType.POSITION,
                Component.translatable("gui.rovenfall.menu.card_position",
                        cards.indexOf(selected) + 1, cards.size()),
                pageLine());
        output.add(NarratedElementType.HINT, selected.detailLines().toArray(Component[]::new));
        output.add(NarratedElementType.USAGE,
                Component.translatable("gui.rovenfall.menu.custom_keyboard_usage"));
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY != 0 && changePage(scrollY > 0 ? -1 : 1)) {
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (advanced && event.key() == GLFW.GLFW_KEY_C
                && (event.modifiers() & GLFW.GLFW_MOD_CONTROL) != 0
                && (query == null || !query.isFocused())) {
            copyAdvancedDetails();
            return true;
        }
        if (query != null && query.isFocused()
                && RovenfallAdministrationMenuScreen.isSubmitKey(event.key())) {
            submitQuery();
            return true;
        }
        if (event.key() == GLFW.GLFW_KEY_PAGE_UP && changePage(-1)) {
            return true;
        }
        if (event.key() == GLFW.GLFW_KEY_PAGE_DOWN && changePage(1)) {
            return true;
        }
        return super.keyPressed(event);
    }

    private void copyAdvancedDetails() {
        RovenfallMenuCardButton selected = selectedCard();
        if (selected == null) {
            return;
        }
        minecraft.keyboardHandler.setClipboard(selected.detailLines().stream()
                .map(Component::getString)
                .filter(value -> !value.isBlank())
                .collect(java.util.stream.Collectors.joining(System.lineSeparator())));
    }

    private boolean changePage(int delta) {
        int next = Math.clamp(page + delta, 0, pageCount() - 1);
        if (next == page) {
            return false;
        }
        page = next;
        rebuildCards(false);
        afterKeyboardAction();
        return true;
    }

    private int pageCount() {
        return Math.max(1, (contentCount + layout.pageSize() - 1) / layout.pageSize());
    }

    private RovenfallMenuCardButton selectedCard() {
        RovenfallMenuCardButton hovered = hoveredCard();
        if (hovered != null) {
            return hovered;
        }
        return cards.stream()
                .filter(RovenfallMenuCardButton::isFocused)
                .findFirst()
                .orElse(cards.isEmpty() ? null : cards.getFirst());
    }

    private RovenfallMenuCardButton hoveredCard() {
        return cards.stream()
                .filter(RovenfallMenuCardButton::isHovered)
                .findFirst()
                .orElse(null);
    }

    private Component pageLine() {
        return Component.translatable(
                "gui.rovenfall.player.page", contentCount == 0 ? 0 : page + 1,
                contentCount == 0 ? 0 : pageCount(), contentCount);
    }

    static List<Component> exposedLines(List<Component> lines, boolean advanced) {
        return advanced ? List.copyOf(lines) : RovenfallAdministrationMenuScreen.publicLines(lines);
    }

    private static Component joinLines(List<Component> lines) {
        MutableComponent result = Component.empty();
        for (int index = 0; index < lines.size(); index++) {
            if (index > 0) {
                result.append("\n");
            }
            result.append(lines.get(index));
        }
        return result;
    }
}
