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
    private static final int TAB_COUNT = 8;
    private RovenfallInventoryLayout.TabLayout tabLayout;

    public RovenfallInventoryScreen(Player player) {
        super(player);
    }

    @Override
    protected void init() {
        super.init();
        tabLayout = RovenfallInventoryLayout.tabs(width, topPos, TAB_COUNT);

        Button inventory = addTab(0, "gui.rovenfall.inventory.inventory", null);
        inventory.active = false;
        addTab(1, "gui.rovenfall.inventory.overview", PlayerMenuNetwork.MenuTarget.OVERVIEW);
        addTab(2, "gui.rovenfall.inventory.claims", PlayerMenuNetwork.MenuTarget.CLAIMS);
        addTab(3, "gui.rovenfall.inventory.travel", PlayerMenuNetwork.MenuTarget.PORTALS);
        addTab(4, "gui.rovenfall.inventory.skills", PlayerMenuNetwork.MenuTarget.SKILLS);
        addTab(5, "gui.rovenfall.inventory.shops", PlayerMenuNetwork.MenuTarget.SHOPS);
        addTab(6, "gui.rovenfall.inventory.journey", PlayerMenuNetwork.MenuTarget.QUESTS);
        addTab(7, "gui.rovenfall.inventory.admin", PlayerMenuNetwork.MenuTarget.ADMIN);
        RovenfallInventoryClient.requestSummary();
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        RovenfallUiTheme.extractBackdrop(graphics, width, height);
        RovenfallUiTheme.extractPanel(
                graphics,
                RovenfallUiTheme.panelFor(leftPos, topPos, imageWidth, imageHeight, 8));
        RovenfallUiTheme.extractField(graphics, leftPos + 7, topPos + 5, 88, 75, false);
        RovenfallUiTheme.extractField(graphics, leftPos + 95, topPos + 5, 77, 51, false);
        RovenfallUiTheme.extractField(graphics, leftPos + 7, topPos + 82, 165, 77, false);
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
        extractCharacterSummary(graphics);
        RovenfallUiTheme.extractField(
                graphics,
                tabLayout.x() - 3,
                tabLayout.y() - 3,
                tabLayout.right() - tabLayout.x() + 6,
                tabLayout.bottom() - tabLayout.y() + 6,
                false);
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        graphics.text(
                font,
                Component.translatable("gui.rovenfall.inventory.title"),
                titleLabelX,
                titleLabelY,
                RovenfallUiTheme.TEXT_PRIMARY,
                false);
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
                .bounds(
                        tabLayout.xFor(index),
                        tabLayout.yFor(index),
                        tabLayout.tabWidth(),
                        RovenfallInventoryLayout.TAB_HEIGHT)
                .build(RovenfallButton::new));
    }

    private void extractCharacterSummary(GuiGraphicsExtractor graphics) {
        var bounds = RovenfallInventoryLayout.summary(width, leftPos, topPos, imageWidth);
        if (bounds.compact()) {
            RovenfallUiTheme.extractField(
                    graphics, bounds.x(), bounds.y(), bounds.width(), bounds.height(), false);
        } else {
            RovenfallUiTheme.extractPanel(graphics, new RovenfallUiTheme.PanelBounds(
                    bounds.x(), bounds.y(), bounds.width(), bounds.height()));
        }

        var summary = RovenfallInventoryClient.summary(minecraft == null ? null : minecraft.player);
        Component balance = summary.<Component>map(value -> Component.translatable(
                        "gui.rovenfall.inventory.balance", value.balance()))
                .orElseGet(() -> Component.translatable("gui.rovenfall.inventory.summary_loading"));
        Component career = summary.<Component>map(value -> Component.translatable(
                        "gui.rovenfall.inventory.active_career",
                        value.careerTranslationKey().isEmpty()
                                ? Component.translatable("gui.rovenfall.inventory.career_none")
                                : Component.translatable(value.careerTranslationKey())))
                .orElseGet(() -> Component.translatable("gui.rovenfall.inventory.summary_loading"));

        int textX = bounds.x() + 5;
        int textWidth = bounds.width() - 10;
        int balanceY = bounds.compact() ? bounds.y() + 3 : bounds.y() + 27;
        int careerY = balanceY + 11;
        if (!bounds.compact()) {
            drawClipped(graphics, Component.translatable("gui.rovenfall.inventory.character_summary"),
                    textX, bounds.y() + 7, textWidth, RovenfallUiTheme.TEXT_PRIMARY);
        }
        drawClipped(graphics, balance, textX, balanceY, textWidth, RovenfallUiTheme.TEXT_PRIMARY);
        drawClipped(graphics, career, textX, careerY, textWidth, RovenfallUiTheme.TEXT_MUTED);
    }

    private void drawClipped(
            GuiGraphicsExtractor graphics, Component text, int x, int y, int width, int color) {
        graphics.enableScissor(x, y, x + width, y + font.lineHeight);
        try {
            graphics.text(font, text, x, y, color, false);
        } finally {
            graphics.disableScissor();
        }
    }
}
