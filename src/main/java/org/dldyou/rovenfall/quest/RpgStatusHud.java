package org.dldyou.rovenfall.quest;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.neoforge.common.NeoForge;
import org.dldyou.rovenfall.Rovenfall;

/** Compact RPG bars that replace the vanilla heart, hunger, and level counters. */
public final class RpgStatusHud {
    static final int SCREEN_MARGIN = 8;
    static final int MAX_BAR_WIDTH = 96;
    static final int MIN_BAR_WIDTH = 48;
    static final int BAR_HEIGHT = 12;
    private static final int BACKGROUND = 0xE6100D0B;
    private static final int BORDER = 0xFF59452D;
    private static final int HEALTH = 0xFFD84A43;
    private static final int HUNGER = 0xFFD29A3A;
    private static final int SATURATION = 0xFFF1D26A;
    private static final int ABSORPTION = 0xFFFFD866;
    private static final int TEXT = 0xFFF8EED8;
    private static final Identifier LAYER_ID = Identifier.fromNamespaceAndPath(
            Rovenfall.MOD_ID, "rpg_status");

    private RpgStatusHud() {
    }

    static void register(IEventBus modBus) {
        modBus.addListener(RpgStatusHud::registerGuiLayers);
        NeoForge.EVENT_BUS.addListener(RpgStatusHud::hideVanillaStatus);
    }

    private static void registerGuiLayers(RegisterGuiLayersEvent event) {
        event.registerAbove(VanillaGuiLayers.HOTBAR, LAYER_ID, RpgStatusHud::render);
    }

    private static void hideVanillaStatus(RenderGuiLayerEvent.Pre event) {
        if (!visible()) {
            return;
        }
        Identifier layer = event.getName();
        if (layer.equals(VanillaGuiLayers.PLAYER_HEALTH)
                || layer.equals(VanillaGuiLayers.FOOD_LEVEL)
                || layer.equals(VanillaGuiLayers.EXPERIENCE_LEVEL)) {
            event.setCanceled(true);
        }
    }

    private static void render(GuiGraphicsExtractor graphics, net.minecraft.client.DeltaTracker ignored) {
        if (!visible()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        var player = minecraft.player;
        StatusLayout layout = layout(graphics.guiWidth(), graphics.guiHeight());
        Font font = minecraft.font;

        float maxHealth = Math.max(1.0F, player.getMaxHealth());
        drawBar(
                graphics,
                font,
                layout.leftX(),
                layout.y(),
                layout.barWidth(),
                ratio(player.getHealth(), maxHealth),
                HEALTH,
                Component.translatable(
                        "hud.rovenfall.vitals.health",
                        Mth.ceil(player.getHealth()), Mth.ceil(maxHealth)));
        if (player.getAbsorptionAmount() > 0) {
            int absorptionWidth = Math.round((layout.barWidth() - 4)
                    * ratio(player.getAbsorptionAmount(), maxHealth));
            graphics.fill(
                    layout.leftX() + 2,
                    layout.y() - 2,
                    layout.leftX() + 2 + absorptionWidth,
                    layout.y(),
                    ABSORPTION);
        }

        int food = player.getFoodData().getFoodLevel();
        drawBar(
                graphics,
                font,
                layout.rightX(),
                layout.y(),
                layout.barWidth(),
                ratio(food, 20),
                HUNGER,
                Component.translatable("hud.rovenfall.vitals.hunger", food, 20));
        int saturationWidth = Math.round((layout.barWidth() - 4)
                * ratio(player.getFoodData().getSaturationLevel(), 20));
        graphics.fill(
                layout.rightX() + 2,
                layout.y() + 2,
                layout.rightX() + 2 + saturationWidth,
                layout.y() + 3,
                SATURATION);

        Component level = Component.translatable(
                "hud.rovenfall.vitals.level", player.experienceLevel);
        int badgeX = graphics.guiWidth() / 2 - 21;
        graphics.fill(badgeX, layout.y() - 1, badgeX + 42, layout.y() + BAR_HEIGHT + 1, BACKGROUND);
        graphics.outline(badgeX, layout.y() - 1, 42, BAR_HEIGHT + 2, BORDER);
        graphics.text(
                font,
                level,
                graphics.guiWidth() / 2 - font.width(level) / 2,
                layout.y() + 2,
                ABSORPTION,
                true);
    }

    private static void drawBar(
            GuiGraphicsExtractor graphics,
            Font font,
            int x,
            int y,
            int width,
            float progress,
            int color,
            Component label) {
        graphics.fill(x, y, x + width, y + BAR_HEIGHT, BACKGROUND);
        graphics.outline(x, y, width, BAR_HEIGHT, BORDER);
        int innerWidth = width - 4;
        graphics.fill(x + 2, y + 2, x + 2 + Math.round(innerWidth * progress), y + BAR_HEIGHT - 2, color);
        int textX = x + Math.max(3, (width - font.width(label)) / 2);
        graphics.enableScissor(x + 2, y + 1, x + width - 2, y + BAR_HEIGHT - 1);
        try {
            graphics.text(font, label, textX, y + 2, TEXT, true);
        } finally {
            graphics.disableScissor();
        }
    }

    private static boolean visible() {
        Minecraft minecraft = Minecraft.getInstance();
        return !minecraft.gui.hud.isHidden()
                && minecraft.gui.screen() == null
                && minecraft.player != null
                && minecraft.gameMode != null
                && minecraft.gameMode.canHurtPlayer();
    }

    static StatusLayout layout(int screenWidth, int screenHeight) {
        if (screenWidth < 1 || screenHeight < 1) {
            throw new IllegalArgumentException("invalid RPG status HUD layout request");
        }
        int availableHalf = Math.max(MIN_BAR_WIDTH, (screenWidth - SCREEN_MARGIN * 2 - 8) / 2);
        int width = Math.min(MAX_BAR_WIDTH, availableHalf);
        int center = screenWidth / 2;
        int left = Math.max(0, center - width - 4);
        int right = Math.min(screenWidth - width, center + 4);
        int y = Math.max(0, screenHeight - 42);
        return new StatusLayout(left, right, y, width);
    }

    static float ratio(float value, float maximum) {
        return maximum <= 0 ? 0 : Math.clamp(value / maximum, 0.0F, 1.0F);
    }

    record StatusLayout(int leftX, int rightX, int y, int barWidth) {
    }
}
