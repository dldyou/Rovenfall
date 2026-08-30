package org.dldyou.rovenfall.administration;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;

/** Vanilla inventory behavior with compact RPG navigation layered above it. */
public final class RovenfallInventoryScreen extends InventoryScreen {
    private static final int TAB_COUNT = 6;
    private static final int TAB_GAP = 2;
    private static final int TAB_HEIGHT = 20;
    private int tabX;
    private int tabY;
    private int tabWidth;

    public RovenfallInventoryScreen(Player player) {
        super(player);
    }

    @Override
    protected void init() {
        super.init();
        tabWidth = Math.clamp((width - 16 - TAB_GAP * (TAB_COUNT - 1)) / TAB_COUNT, 40, 72);
        int totalWidth = tabWidth * TAB_COUNT + TAB_GAP * (TAB_COUNT - 1);
        tabX = (width - totalWidth) / 2;
        tabY = Math.max(4, topPos - TAB_HEIGHT - 4);

        Button inventory = addTab(0, "gui.rovenfall.inventory.inventory", null);
        inventory.active = false;
        addTab(1, "gui.rovenfall.inventory.overview", PlayerMenuNetwork.MenuTarget.OVERVIEW);
        addTab(2, "gui.rovenfall.inventory.claims", PlayerMenuNetwork.MenuTarget.CLAIMS);
        addTab(3, "gui.rovenfall.inventory.skills", PlayerMenuNetwork.MenuTarget.SKILLS);
        addTab(4, "gui.rovenfall.inventory.shops", PlayerMenuNetwork.MenuTarget.SHOPS);
        addTab(5, "gui.rovenfall.inventory.admin", PlayerMenuNetwork.MenuTarget.ADMIN);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        RovenfallUiTheme.extractBackdrop(graphics, width, height);
        RovenfallUiTheme.extractPanel(
                graphics,
                RovenfallUiTheme.panelFor(leftPos, topPos, imageWidth, imageHeight, 8));
        RovenfallUiTheme.extractPortrait(graphics, leftPos + 25, topPos + 7, 51, 72);
        if (minecraft != null && minecraft.player != null) {
            InventoryScreen.extractEntityInInventoryFollowsMouse(
                    graphics,
                    leftPos + 26,
                    topPos + 8,
                    leftPos + 75,
                    topPos + 78,
                    30,
                    0.0625F,
                    mouseX,
                    mouseY,
                    minecraft.player);
        }
        int totalWidth = tabWidth * TAB_COUNT + TAB_GAP * (TAB_COUNT - 1);
        RovenfallUiTheme.extractField(
                graphics,
                tabX - 3,
                tabY - 3,
                totalWidth + 6,
                TAB_HEIGHT + 6,
                false);
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        graphics.text(font, title, titleLabelX, titleLabelY, RovenfallUiTheme.TEXT_PRIMARY, false);
    }

    @Override
    protected void extractSlots(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        for (Slot slot : menu.slots) {
            if (slot.isActive()) {
                RovenfallUiTheme.extractSlot(graphics, slot, false);
            }
        }
        super.extractSlots(graphics, mouseX, mouseY);
    }

    private Button addTab(int index, String translationKey, PlayerMenuNetwork.MenuTarget target) {
        Component label = Component.translatable(translationKey);
        return addRenderableWidget(Button.builder(
                        label,
                        ignored -> {
                            if (target != null) {
                                RovenfallInventoryClient.request(target);
                            }
                        })
                .tooltip(Tooltip.create(Component.translatable(
                        target == null
                                ? "gui.rovenfall.inventory.current_tab"
                                : "gui.rovenfall.inventory.open_tab",
                        label)))
                .bounds(tabX + index * (tabWidth + TAB_GAP), tabY, tabWidth, TAB_HEIGHT)
                .build(RovenfallButton::new));
    }
}
