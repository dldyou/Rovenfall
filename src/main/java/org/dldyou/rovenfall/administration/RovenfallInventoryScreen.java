package org.dldyou.rovenfall.administration;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

/** Vanilla inventory behavior with compact RPG navigation layered above it. */
public final class RovenfallInventoryScreen extends InventoryScreen {
    private static final int TAB_COUNT = 5;
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
        tabWidth = Math.clamp((width - 16 - TAB_GAP * (TAB_COUNT - 1)) / TAB_COUNT, 44, 72);
        int totalWidth = tabWidth * TAB_COUNT + TAB_GAP * (TAB_COUNT - 1);
        tabX = (width - totalWidth) / 2;
        tabY = Math.max(4, topPos - TAB_HEIGHT - 4);

        Button inventory = addTab(0, "gui.rovenfall.inventory.inventory", null);
        inventory.active = false;
        addTab(1, "gui.rovenfall.inventory.overview", PlayerMenuNetwork.MenuTarget.OVERVIEW);
        addTab(2, "gui.rovenfall.inventory.claims", PlayerMenuNetwork.MenuTarget.CLAIMS);
        addTab(3, "gui.rovenfall.inventory.skills", PlayerMenuNetwork.MenuTarget.SKILLS);
        addTab(4, "gui.rovenfall.inventory.shops", PlayerMenuNetwork.MenuTarget.SHOPS);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(graphics, mouseX, mouseY, partialTick);
        int totalWidth = tabWidth * TAB_COUNT + TAB_GAP * (TAB_COUNT - 1);
        graphics.fill(tabX - 3, tabY - 3, tabX + totalWidth + 3, tabY + TAB_HEIGHT + 3, 0xD019120D);
        graphics.outline(tabX - 3, tabY - 3, totalWidth + 6, TAB_HEIGHT + 6, 0xFFB89445);
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
                .build());
    }
}
