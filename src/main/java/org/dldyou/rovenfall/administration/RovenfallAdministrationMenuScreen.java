package org.dldyou.rovenfall.administration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import org.lwjgl.glfw.GLFW;

/**
 * Card-based administrator view over the existing server-authoritative 9x6 chest menu.
 *
 * <p>The screen intentionally knows no administrator IDs or operation rules. A card activates the
 * original menu slot, so the server continues to own authorization, selected targets, freshness,
 * parsing, previews, and commits.</p>
 */
final class RovenfallAdministrationMenuScreen extends ContainerScreen {
    private static final int HEADER_SLOT = 4;
    private static final int CONTENT_START = 9;
    private static final int CONTENT_END = 45;
    private static final int TOOLBAR_START = 45;
    private static final int TOOLBAR_END = 54;
    private static final Pattern UUID_TEXT = Pattern.compile(
            "(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern IDENTIFIER_TEXT = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    private static final Pattern LONG_HASH_TEXT = Pattern.compile("(?i)\\b[0-9a-f]{32,}\\b");

    private final PlayerMenuNetwork.MenuKind kind;
    private final List<RovenfallMenuCardButton> cards = new ArrayList<>();
    private final List<FormFieldVisual> formFieldVisuals = new ArrayList<>();
    private RovenfallAdministrationMenuLayout.Layout layout;
    private EditBox query;
    private Button advancedButton;
    private final Map<Integer, EditBox> formInputs = new HashMap<>();
    private boolean advanced;
    private int page;
    private int contentCount;
    private int observedStateId;
    private ItemStack headerItem = ItemStack.EMPTY;
    private List<Component> headerLines = List.of();
    private AdministrationFormMarker formMarker;
    private List<String> formValues = List.of();
    private Component formError = Component.empty();
    private Component serverFormError = Component.empty();

    RovenfallAdministrationMenuScreen(
            ChestMenu menu,
            Inventory inventory,
            Component title,
            PlayerMenuNetwork.MenuKind kind) {
        super(menu, inventory, title);
        if (!kind.isAdministration()) {
            throw new IllegalArgumentException("Only administration menus use this screen");
        }
        this.kind = kind;
    }

    @Override
    protected void init() {
        super.init();
        layout = RovenfallAdministrationMenuLayout.fit(width, height);
        observedStateId = menu.getStateId();
        rebuildWidgets(true);
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        if (menu.getStateId() != observedStateId) {
            observedStateId = menu.getStateId();
            rebuildWidgets(true);
        }
    }

    /** Keeps all client-only widgets in one rebuild point for the later typed-form integration. */
    private void rebuildWidgets(boolean resetPage) {
        String previousQuery = query == null ? "" : query.getValue();
        captureFormInputs();
        setFocused(null);
        clearWidgets();
        cards.clear();
        formInputs.clear();
        formFieldVisuals.clear();

        if (resetPage) {
            page = 0;
        }
        AdministrationFormMarker previousMarker = formMarker;
        captureHeader();
        if (resetPage || !Objects.equals(previousMarker, formMarker)) {
            formValues = formMarker == null ? List.of() : formMarker.defaults();
            formError = serverFormError;
        }
        List<Integer> contentSlots = occupiedSlots(CONTENT_START, CONTENT_END);
        List<Integer> actionSlots = actionSlots();
        contentCount = contentSlots.size();
        int pages = pageCount();
        page = Math.clamp(page, 0, pages - 1);
        int from = Math.min(contentSlots.size(), page * layout.pageSize());
        int to = Math.min(contentSlots.size(), from + layout.pageSize());
        for (int index = from; index < to; index++) {
            addCard(contentSlots.get(index), layout.card(index - from));
        }
        for (int index = 0; index < actionSlots.size(); index++) {
            addCard(actionSlots.get(index), layout.toolbarButton(index, actionSlots.size()));
        }
        addHeaderWidgets(previousQuery);
        if (showsTypedForm()) {
            addTypedFormWidgets();
        }
        if (!cards.isEmpty()) {
            setFocused(cards.getFirst());
            cards.getFirst().setFocused(true);
        }
    }

    private void captureHeader() {
        Slot slot = HEADER_SLOT < menu.getRowCount() * 9 ? menu.getSlot(HEADER_SLOT) : null;
        if (slot == null || !slot.hasItem()) {
            headerItem = ItemStack.EMPTY;
            headerLines = List.of(title);
            formMarker = null;
            serverFormError = Component.empty();
            return;
        }
        headerItem = slot.getItem().copy();
        headerLines = tooltip(headerItem);
        if (headerLines.isEmpty()) {
            headerLines = List.of(headerItem.getHoverName());
        }
        formMarker = AdministrationFormMarker.read(headerItem).orElse(null);
        serverFormError = formMarker != null && AdministrationFormMarker.hasError(headerItem)
                ? headerLines.getLast()
                : Component.empty();
    }

    private void addHeaderWidgets(String previousQuery) {
        int pageCount = pageCount();
        if (pageCount > 1) {
            addPageButton(-1, layout.previousPageButton(), "gui.rovenfall.admin.previous").active = page > 0;
            addPageButton(1, layout.nextPageButton(), "gui.rovenfall.admin.next").active = page + 1 < pageCount;
        }
        var header = layout.header();
        var previous = layout.previousPageButton();
        int toggleWidth = 20;
        int submitWidth = 40;
        int controlsRight = previous.x() - 5;
        int toggleX = controlsRight - toggleWidth;
        int submitX = toggleX - 5 - submitWidth;
        Component advancedNarration = Component.translatable("gui.rovenfall.admin.advanced");
        advancedButton = addRenderableWidget(Button.builder(Component.literal("⋮"), ignored -> toggleAdvanced())
                .createNarration(ignored -> advancedNarration.copy())
                .tooltip(Tooltip.create(joinLines(List.of(
                        advancedNarration,
                        Component.translatable("gui.rovenfall.admin.advanced_info_hint")))))
                .bounds(toggleX, header.y(), toggleWidth, header.height())
                .build(RovenfallButton::new));
        if (showsTypedForm()) {
            addRenderableWidget(Button.builder(Component.translatable("gui.rovenfall.admin.form.submit"),
                            ignored -> submitForm())
                    .bounds(header.x(), header.y(), Math.max(64, submitX - header.x() - 5), header.height())
                    .build(RovenfallButton::new));
            return;
        }
        if (!canSearch()) {
            return;
        }
        int fieldWidth = Math.max(48, submitX - 5 - header.x());
        query = addRenderableWidget(new EditBox(font, header.x(), header.y(), fieldWidth, header.height(),
                Component.translatable("gui.rovenfall.admin.search")));
        query.setBordered(false);
        query.setTextColor(RovenfallUiTheme.TEXT_PRIMARY);
        query.setTextColorUneditable(RovenfallUiTheme.TEXT_MUTED);
        query.setMaxLength(advanced
                ? AdministrationTextInputMenu.MAX_INPUT_LENGTH
                : AdministrationReadViewService.MAX_QUERY_LENGTH);
        query.setValue(advanced ? previousQuery : shorten(previousQuery));
        addRenderableWidget(Button.builder(Component.translatable("gui.rovenfall.admin.search.submit"),
                        ignored -> submitQuery())
                .bounds(submitX, header.y(), submitWidth, header.height())
                .build(RovenfallButton::new));
    }

    private List<Integer> actionSlots() {
        List<Integer> result = occupiedSlots(TOOLBAR_START, TOOLBAR_END);
        int menuSlots = menu.getRowCount() * 9;
        for (int slotId = 0; slotId < Math.min(menuSlots, TOOLBAR_END); slotId++) {
            if (slotId != HEADER_SLOT && (slotId < CONTENT_START || slotId >= TOOLBAR_END)
                    && menu.getSlot(slotId).hasItem()) {
                result.add(slotId);
            }
        }
        return result;
    }

    private List<Integer> occupiedSlots(int fromInclusive, int toExclusive) {
        List<Integer> result = new ArrayList<>();
        int end = Math.min(menu.getRowCount() * 9, toExclusive);
        for (int slotId = Math.max(0, fromInclusive); slotId < end; slotId++) {
            if (menu.getSlot(slotId).hasItem()) {
                result.add(slotId);
            }
        }
        return result;
    }

    private void addCard(int slotId, RovenfallAdministrationMenuLayout.Rect bounds) {
        ItemStack item = menu.getSlot(slotId).getItem();
        List<Component> lines = tooltip(item);
        Component cardTitle = lines.isEmpty() ? item.getHoverName() : lines.getFirst();
        List<Component> exposed = advanced ? lines : publicLines(lines);
        Component summary = exposed.size() > 1 ? exposed.get(1) : Component.empty();
        Button.Builder builder = Button.builder(cardTitle, ignored -> activate(slotId))
                .tooltip(Tooltip.create(joinLines(exposed)))
                .bounds(bounds.x(), bounds.y(), bounds.width(), bounds.height());
        cards.add(addRenderableWidget(new RovenfallMenuCardButton(
                builder, font, item, summary, exposed)));
    }

    private List<Component> tooltip(ItemStack stack) {
        Item.TooltipContext context = minecraft.level == null
                ? Item.TooltipContext.EMPTY : Item.TooltipContext.of(minecraft.level);
        return List.copyOf(stack.getTooltipLines(context, minecraft.player, TooltipFlag.NORMAL));
    }

    private Button addPageButton(int delta, RovenfallAdministrationMenuLayout.Rect bounds, String key) {
        Component label = Component.translatable(key);
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

    private boolean canSearch() {
        return formMarker != null || AdministrationFormMarker.hasSearch(headerItem);
    }

    private boolean showsTypedForm() {
        return formMarker != null && !advanced;
    }

    private void addTypedFormWidgets() {
        List<AdministrationFormType.Field> fields = formMarker.type().fields();
        if (fields.size() > RovenfallAdministrationMenuLayout.MAX_FORM_FIELDS) {
            formError = Component.translatable("gui.rovenfall.admin.form.error.invalid");
            return;
        }
        for (int index = 0; index < fields.size(); index++) {
            AdministrationFormType.Field field = fields.get(index);
            RovenfallAdministrationMenuLayout.Rect cell = layout.formField(index, fields.size());
            RovenfallAdministrationMenuLayout.Rect bounds = formControlBounds(cell);
            formFieldVisuals.add(new FormFieldVisual(cell, Component.translatable(field.translationKey())));
            switch (field.kind()) {
                case TEXT, INTEGER, OPTIONAL_INTEGER -> addTextField(index, field, bounds);
                case TOGGLE, SELECT -> addChoiceField(index, field, bounds);
                case POSITION, POSITION_XZ -> addPositionField(index, field, bounds);
            }
        }
    }

    private RovenfallAdministrationMenuLayout.Rect formControlBounds(
            RovenfallAdministrationMenuLayout.Rect cell) {
        int labelHeight = Math.min(font.lineHeight + 2, Math.max(0, cell.height() - 12));
        return new RovenfallAdministrationMenuLayout.Rect(
                cell.x(), cell.y() + labelHeight, cell.width(), Math.max(1, cell.height() - labelHeight));
    }

    private void addTextField(
            int index, AdministrationFormType.Field field, RovenfallAdministrationMenuLayout.Rect bounds) {
        EditBox input = addRenderableWidget(new EditBox(font, bounds.x(), bounds.y(), bounds.width(), bounds.height(),
                Component.translatable(field.translationKey())));
        input.setHint(Component.translatable(field.translationKey()));
        input.setBordered(false);
        input.setTextColor(RovenfallUiTheme.TEXT_PRIMARY);
        input.setTextColorUneditable(RovenfallUiTheme.TEXT_MUTED);
        input.setMaxLength((int) Math.min(AdministrationStructuredFormCodec.MAX_FIELD_LENGTH, field.maximum()));
        input.setValue(formValues.get(index));
        formInputs.put(index, input);
    }

    private void addChoiceField(
            int index, AdministrationFormType.Field field, RovenfallAdministrationMenuLayout.Rect bounds) {
        String value = formValues.get(index);
        Component label = Component.translatable("gui.rovenfall.admin.form.option." + field.name() + "." + value);
        addRenderableWidget(Button.builder(label, ignored -> {
                    List<String> values = new ArrayList<>(formValues);
                    int current = field.options().indexOf(values.get(index));
                    values.set(index, field.options().get((current + 1) % field.options().size()));
                    formValues = List.copyOf(values);
                    rebuildWidgets(false);
                })
                .tooltip(Tooltip.create(Component.translatable(field.translationKey())))
                .bounds(bounds.x(), bounds.y(), bounds.width(), bounds.height())
                .build(RovenfallButton::new));
    }

    private void addPositionField(
            int index, AdministrationFormType.Field field, RovenfallAdministrationMenuLayout.Rect bounds) {
        String value = formValues.get(index);
        Component label = value.isBlank()
                ? Component.translatable("gui.rovenfall.admin.form.current_position")
                : Component.translatable(field.translationKey()).append(": ").append(value);
        addRenderableWidget(Button.builder(label, ignored -> fillCurrentPosition(index, field.kind()))
                .tooltip(Tooltip.create(Component.translatable(field.translationKey())))
                .bounds(bounds.x(), bounds.y(), bounds.width(), bounds.height())
                .build(RovenfallButton::new));
    }

    private void fillCurrentPosition(int index, AdministrationFormType.FieldKind kind) {
        if (minecraft.player == null) {
            return;
        }
        String value = switch (kind) {
            case POSITION -> minecraft.player.blockPosition().getX() + ","
                    + minecraft.player.blockPosition().getY() + "," + minecraft.player.blockPosition().getZ();
            case POSITION_XZ -> (minecraft.player.blockPosition().getX() >> 4) + ","
                    + (minecraft.player.blockPosition().getZ() >> 4);
            default -> throw new IllegalArgumentException("Position control expected");
        };
        List<String> values = new ArrayList<>(formValues);
        values.set(index, value);
        formValues = List.copyOf(values);
        rebuildWidgets(false);
    }

    private void captureFormInputs() {
        if (formInputs.isEmpty() || formValues.isEmpty()) {
            return;
        }
        List<String> values = new ArrayList<>(formValues);
        formInputs.forEach((index, input) -> values.set(index, input.getValue()));
        formValues = List.copyOf(values);
    }

    private void submitQuery() {
        if (query != null) {
            if (formMarker != null && query.getValue().isBlank()) {
                formError = Component.translatable("gui.rovenfall.admin.form.error.invalid");
                afterKeyboardAction();
                return;
            }
            ClientPacketDistributor.sendToServer(new PlayerMenuNetwork.AdminQuery(
                    menu.containerId, menu.getStateId(), query.getValue()));
            afterKeyboardAction();
        }
    }

    private void submitForm() {
        captureFormInputs();
        if (!validFormValues()) {
            formError = Component.translatable("gui.rovenfall.admin.form.error.invalid");
            afterKeyboardAction();
            return;
        }
        Optional<String> encoded = AdministrationStructuredFormCodec.encode(formMarker.type(), formValues);
        if (encoded.isEmpty()) {
            formError = Component.translatable("gui.rovenfall.admin.form.error.invalid");
            afterKeyboardAction();
            return;
        }
        formError = Component.empty();
        ClientPacketDistributor.sendToServer(new PlayerMenuNetwork.AdminQuery(
                menu.containerId, menu.getStateId(), encoded.orElseThrow()));
        afterKeyboardAction();
    }

    private boolean validFormValues() {
        if (formMarker == null || !formMarker.type().accepts(formValues)) {
            return false;
        }
        List<AdministrationFormType.Field> fields = formMarker.type().fields();
        for (int index = 0; index < fields.size(); index++) {
            AdministrationFormType.Field field = fields.get(index);
            String value = formValues.get(index);
            if ((field.name().equals("reason") || field.kind() == AdministrationFormType.FieldKind.POSITION
                    || field.kind() == AdministrationFormType.FieldKind.POSITION_XZ) && value.isBlank()) {
                return false;
            }
        }
        return true;
    }

    private void toggleAdvanced() {
        advanced = !advanced;
        rebuildWidgets(false);
        setFocused(advancedButton);
        advancedButton.setFocused(true);
        afterKeyboardAction();
    }

    private static String shorten(String value) {
        return value.length() <= AdministrationReadViewService.MAX_QUERY_LENGTH
                ? value : value.substring(0, AdministrationReadViewService.MAX_QUERY_LENGTH);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        RovenfallUiTheme.extractBackdrop(graphics, width, height);
        var panel = layout.panel();
        RovenfallUiTheme.extractPanel(graphics, new RovenfallUiTheme.PanelBounds(
                panel.x(), panel.y(), panel.width(), panel.height()));
        extractArea(graphics, layout.cards());
        extractArea(graphics, layout.detail());
        extractArea(graphics, layout.toolbar());
        if (query != null) {
            RovenfallUiTheme.extractField(graphics, query.getX(), query.getY(), query.getWidth(), query.getHeight(),
                    query.isFocused());
        }
        for (EditBox input : formInputs.values()) {
            RovenfallUiTheme.extractField(graphics, input.getX(), input.getY(), input.getWidth(), input.getHeight(),
                    input.isFocused());
        }
        if (query == null && formMarker == null) {
            graphics.text(font, title, layout.header().x(), layout.header().y() + 7,
                    RovenfallUiTheme.TEXT_PRIMARY, false);
        }
    }

    private static void extractArea(GuiGraphicsExtractor graphics, RovenfallAdministrationMenuLayout.Rect area) {
        RovenfallUiTheme.extractField(graphics, area.x(), area.y(), area.width(), area.height(), false);
    }

    @Override
    public void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractContents(graphics, mouseX, mouseY, partialTick);
        extractDetail(graphics);
        for (FormFieldVisual visual : formFieldVisuals) {
            graphics.text(font, visual.label(), visual.cell().x() + 2, visual.cell().y(),
                    RovenfallUiTheme.TEXT_MUTED, false);
        }
    }

    private void extractDetail(GuiGraphicsExtractor graphics) {
        var detail = layout.detail();
        graphics.enableScissor(detail.x() + 4, detail.y() + 4, detail.right() - 4, detail.bottom() - 4);
        try {
            if (!headerItem.isEmpty()) {
                graphics.item(headerItem, detail.x() + 8, detail.y() + 8);
                graphics.itemDecorations(font, headerItem, detail.x() + 8, detail.y() + 8);
            }
            int x = detail.x() + (headerItem.isEmpty() ? 8 : 31);
            int y = detail.y() + 9;
            int textWidth = Math.max(1, detail.right() - x - 7);
            List<Component> lines = advanced ? headerLines
                    : formMarker == null ? publicLines(headerLines) : headerLines.stream().limit(1).toList();
            for (Component line : lines) {
                for (var wrapped : font.split(line, textWidth)) {
                    if (y + font.lineHeight > detail.bottom() - 5) {
                        return;
                    }
                    graphics.text(font, wrapped, x, y,
                            y == detail.y() + 9 ? RovenfallUiTheme.TEXT_PRIMARY : RovenfallUiTheme.TEXT_MUTED, false);
                    y += font.lineHeight + 2;
                }
                y += 2;
            }
            if (!formError.getString().isBlank()) {
                graphics.text(font, formError, detail.x() + 8, detail.bottom() - font.lineHeight - 6,
                        RovenfallUiTheme.FOCUS_OUTER, false);
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
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY != 0 && changePage(scrollY > 0 ? -1 : 1)) {
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int key = event.key();
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            if (textInputFocused()) {
                setFocused(cards.isEmpty() ? advancedButton : cards.getFirst());
                return true;
            }
            if (activateIfPresent(PlayerMenuKeyboardNavigation.TOOLBAR_BACK_SLOT)) {
                return true;
            }
        }
        if (query != null && !query.isFocused()
                && (key == GLFW.GLFW_KEY_SLASH
                || key == GLFW.GLFW_KEY_F && (event.modifiers() & GLFW.GLFW_MOD_CONTROL) != 0)) {
            setFocused(query);
            query.setFocused(true);
            return true;
        }
        if (!textInputFocused() && key == GLFW.GLFW_KEY_R && event.modifiers() == 0
                && activateIfPresent(PlayerMenuKeyboardNavigation.TOOLBAR_REFRESH_SLOT)) {
            return true;
        }
        if (advanced && key == GLFW.GLFW_KEY_C
                && (event.modifiers() & GLFW.GLFW_MOD_CONTROL) != 0 && !textInputFocused()) {
            copyAdvancedDetails();
            return true;
        }
        if (query != null && query.isFocused() && isSubmitKey(key)) {
            submitQuery();
            return true;
        }
        if (formInputs.values().stream().anyMatch(EditBox::isFocused)
                && isSubmitKey(key)) {
            submitForm();
            return true;
        }
        if (key == GLFW.GLFW_KEY_PAGE_UP && changePage(-1)) {
            return true;
        }
        if (key == GLFW.GLFW_KEY_PAGE_DOWN && changePage(1)) {
            return true;
        }
        return super.keyPressed(event);
    }

    private boolean activateIfPresent(int slotId) {
        if (slotId < 0 || slotId >= menu.getRowCount() * 9 || !menu.getSlot(slotId).hasItem()) {
            return false;
        }
        activate(slotId);
        return true;
    }

    static boolean isSubmitKey(int key) {
        return key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER;
    }

    private boolean textInputFocused() {
        return query != null && query.isFocused()
                || formInputs.values().stream().anyMatch(EditBox::isFocused);
    }

    private void copyAdvancedDetails() {
        RovenfallMenuCardButton selected = cards.stream()
                .filter(card -> card.isHovered() || card.isFocused())
                .findFirst()
                .orElse(null);
        List<Component> lines = selected == null ? headerLines : selected.detailLines();
        minecraft.keyboardHandler.setClipboard(lines.stream()
                .map(Component::getString)
                .filter(value -> !value.isBlank())
                .collect(java.util.stream.Collectors.joining(System.lineSeparator())));
    }

    @Override
    protected void updateNarrationState(NarrationElementOutput output) {
        super.updateNarrationState(output);
        RovenfallMenuCardButton selected = cards.stream().filter(RovenfallMenuCardButton::isHovered).findFirst()
                .orElseGet(() -> getFocused() instanceof RovenfallMenuCardButton card ? card : null);
        if (!formError.getString().isBlank()) {
            output.add(NarratedElementType.HINT, formError);
        }
        if (selected == null) {
            if (cards.isEmpty()) {
                output.add(NarratedElementType.HINT, Component.translatable("gui.rovenfall.menu.no_actions"));
            }
            return;
        }
        output.add(NarratedElementType.POSITION, Component.translatable(
                "gui.rovenfall.player.page", page + 1, pageCount(), contentCount));
        output.add(NarratedElementType.HINT, selected.detailLines().toArray(Component[]::new));
        output.add(NarratedElementType.USAGE, Component.translatable("gui.rovenfall.menu.custom_keyboard_usage"));
    }

    private boolean changePage(int delta) {
        int next = Math.clamp(page + delta, 0, pageCount() - 1);
        if (next == page) {
            return false;
        }
        page = next;
        rebuildWidgets(false);
        afterKeyboardAction();
        return true;
    }

    private int pageCount() {
        return Math.max(1, (contentCount + layout.pageSize() - 1) / layout.pageSize());
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

    static List<Component> publicLines(List<Component> lines) {
        if (lines.isEmpty()) {
            return List.of();
        }
        List<Component> result = new ArrayList<>();
        result.add(lines.getFirst());
        for (int index = 1; index < lines.size(); index++) {
            String text = lines.get(index).getString().strip();
            if (!text.isEmpty() && !looksTechnical(text)) {
                result.add(lines.get(index));
            }
        }
        return List.copyOf(result);
    }

    private static boolean looksTechnical(String text) {
        return UUID_TEXT.matcher(text).find()
                || IDENTIFIER_TEXT.matcher(text).find()
                || LONG_HASH_TEXT.matcher(text).find();
    }

    private record FormFieldVisual(RovenfallAdministrationMenuLayout.Rect cell, Component label) {
    }
}
