package org.dldyou.rovenfall.administration;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import org.lwjgl.glfw.GLFW;

/** Adds keyboard slot navigation and narration to Rovenfall's server-authoritative chest menus. */
final class RovenfallPlayerMenuScreen extends ContainerScreen {
    private final PlayerMenuNetwork.MenuKind kind;
    private int focusedMenuSlot = -1;
    private EditBox adminQuery;

    RovenfallPlayerMenuScreen(
            ChestMenu menu,
            Inventory inventory,
            Component title,
            PlayerMenuNetwork.MenuKind kind) {
        super(menu, inventory, title);
        this.kind = kind;
    }

    @Override
    protected void init() {
        super.init();
        focusedMenuSlot = findOccupiedSlot(-1, 1);
        if (kind.isAdministration()) {
            adminQuery = addRenderableWidget(new EditBox(
                    font, leftPos, Math.max(4, topPos - 24), 132, 20,
                    Component.translatable("gui.rovenfall.admin.search")));
            adminQuery.setMaxLength(kind.usesLongTextInput()
                    ? AdministrationTextInputMenu.MAX_INPUT_LENGTH
                    : AdministrationReadViewService.MAX_QUERY_LENGTH);
            adminQuery.setBordered(false);
            adminQuery.setTextColor(RovenfallUiTheme.TEXT_PRIMARY);
            adminQuery.setTextColorUneditable(RovenfallUiTheme.TEXT_MUTED);
            addRenderableWidget(Button.builder(
                            Component.translatable("gui.rovenfall.admin.search.submit"),
                            ignored -> submitAdminQuery())
                    .bounds(leftPos + 136, Math.max(4, topPos - 24), 40, 20)
                    .build(RovenfallButton::new));
        }
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        RovenfallUiTheme.extractBackdrop(graphics, width, height);
        RovenfallUiTheme.extractPanel(
                graphics,
                RovenfallUiTheme.panelFor(leftPos, topPos, imageWidth, imageHeight, 8));
        if (adminQuery != null) {
            RovenfallUiTheme.extractField(
                    graphics,
                    adminQuery.getX(),
                    adminQuery.getY(),
                    adminQuery.getWidth(),
                    adminQuery.getHeight(),
                    adminQuery.isFocused());
        }
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        graphics.text(font, title, titleLabelX, titleLabelY, RovenfallUiTheme.TEXT_PRIMARY, false);
        graphics.text(
                font,
                playerInventoryTitle,
                inventoryLabelX,
                inventoryLabelY,
                RovenfallUiTheme.TEXT_MUTED,
                false);
    }

    @Override
    protected void extractSlots(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        int menuSlots = menu.getRowCount() * 9;
        for (Slot slot : menu.slots) {
            if (slot.isActive()) {
                RovenfallUiTheme.extractSlot(graphics, slot, slot.index < menuSlots);
            }
        }
        super.extractSlots(graphics, mouseX, mouseY);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int key = event.key();
        if (adminQuery != null && key == GLFW.GLFW_KEY_TAB) {
            if (adminQuery.isFocused()) {
                setFocused(null);
                adminQuery.setFocused(false);
                moveFocus(event.hasShiftDown() ? -1 : 1);
            } else {
                setFocused(adminQuery);
                adminQuery.setFocused(true);
            }
            return true;
        }
        if (adminQuery != null && adminQuery.isFocused()
                && (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER)) {
            submitAdminQuery();
            return true;
        }
        if (adminQuery != null && adminQuery.isFocused() && key == GLFW.GLFW_KEY_ESCAPE) {
            setFocused(null);
            adminQuery.setFocused(false);
            return true;
        }
        if (adminQuery != null && adminQuery.isFocused() && key != GLFW.GLFW_KEY_ESCAPE) {
            return super.keyPressed(event);
        }
        if (key == GLFW.GLFW_KEY_LEFT) {
            moveFocus(-1);
            return true;
        }
        if (key == GLFW.GLFW_KEY_RIGHT) {
            moveFocus(1);
            return true;
        }
        if (key == GLFW.GLFW_KEY_UP) {
            moveFocus(-9);
            return true;
        }
        if (key == GLFW.GLFW_KEY_DOWN) {
            moveFocus(9);
            return true;
        }
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER || key == GLFW.GLFW_KEY_SPACE) {
            Slot slot = focusedSlot();
            if (slot != null && slot.hasItem()) {
                slotClicked(slot, focusedMenuSlot, 0, ContainerInput.PICKUP);
                afterKeyboardAction();
                return true;
            }
        }
        return super.keyPressed(event);
    }

    private void submitAdminQuery() {
        if (adminQuery == null) {
            return;
        }
        ClientPacketDistributor.sendToServer(new PlayerMenuNetwork.AdminQuery(
                menu.containerId, menu.getStateId(), adminQuery.getValue()));
    }

    @Override
    public void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractContents(graphics, mouseX, mouseY, partialTick);
        Slot slot = focusedSlot();
        if (slot == null || !slot.hasItem()) {
            return;
        }
        int x = leftPos + slot.x - 1;
        int y = topPos + slot.y - 1;
        graphics.outline(x, y, 18, 18, RovenfallUiTheme.FOCUS_INNER);
        graphics.outline(x - 1, y - 1, 20, 20, RovenfallUiTheme.FOCUS_OUTER);
    }

    @Override
    protected void updateNarrationState(NarrationElementOutput output) {
        super.updateNarrationState(output);
        if (adminQuery != null && adminQuery.isFocused()) {
            return;
        }
        Slot slot = focusedSlot();
        if (slot == null || !slot.hasItem()) {
            output.add(NarratedElementType.HINT, Component.translatable("gui.rovenfall.menu.no_actions"));
            return;
        }
        output.add(NarratedElementType.POSITION, Component.translatable(
                "gui.rovenfall.menu.slot_position", focusedMenuSlot + 1, menu.getRowCount() * 9));
        var tooltip = getTooltipFromContainerItem(slot.getItem());
        output.add(NarratedElementType.HINT, tooltip.toArray(Component[]::new));
        output.add(NarratedElementType.USAGE, Component.translatable("gui.rovenfall.menu.keyboard_usage"));
    }

    private void moveFocus(int step) {
        int next = findOccupiedSlot(focusedMenuSlot, step);
        if (next >= 0) {
            focusedMenuSlot = next;
            afterKeyboardAction();
        }
    }

    private int findOccupiedSlot(int start, int step) {
        int menuSlots = menu.getRowCount() * 9;
        boolean[] occupied = new boolean[menuSlots];
        for (int index = 0; index < menuSlots; index++) {
            occupied[index] = menu.getSlot(index).hasItem();
        }
        return PlayerMenuKeyboardNavigation.nextOccupied(occupied, start, step);
    }

    private Slot focusedSlot() {
        int menuSlots = menu.getRowCount() * 9;
        return focusedMenuSlot >= 0 && focusedMenuSlot < menuSlots
                ? menu.getSlot(focusedMenuSlot)
                : null;
    }
}
