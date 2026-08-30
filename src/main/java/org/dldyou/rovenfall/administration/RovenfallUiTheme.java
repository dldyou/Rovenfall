package org.dldyou.rovenfall.administration;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.inventory.Slot;

/** Shared code-drawn palette and framing for Rovenfall's dark-fantasy screens. */
final class RovenfallUiTheme {
    static final int TEXT_PRIMARY = 0xFFF1E4C5;
    static final int TEXT_MUTED = 0xFFB9A783;
    static final int FOCUS_OUTER = 0xFFFFE29A;
    static final int FOCUS_INNER = 0xFF5A3519;

    private static final int BACKDROP = 0xFF0B0908;
    private static final int BACKDROP_BAND = 0xFF100D0B;
    private static final int PANEL_SHADOW = 0xCC000000;
    private static final int PANEL_EDGE = 0xFF25170F;
    private static final int PANEL_INNER = 0xFF17110D;
    private static final int PANEL_HEADER = 0xFF2A1A11;
    private static final int GOLD = 0xFFB89445;
    private static final int GOLD_BRIGHT = 0xFFE0BD68;
    private static final int GOLD_DARK = 0xFF6E4D24;
    private static final int SLOT_ACTION = 0xFF21160F;
    private static final int SLOT_INVENTORY = 0xFF12100E;
    private static final int FIELD = 0xFF100D0B;
    private static final int BUTTON = 0xFF271910;
    private static final int BUTTON_HOVERED = 0xFF4A2E17;
    private static final int BUTTON_DISABLED = 0xFF17130F;

    private RovenfallUiTheme() {
    }

    static PanelBounds panelFor(int left, int top, int width, int height, int padding) {
        if (width <= 0 || height <= 0 || padding < 0) {
            throw new IllegalArgumentException("Panel dimensions must be positive and padding non-negative");
        }
        return new PanelBounds(
                left - padding,
                top - padding,
                width + padding * 2,
                height + padding * 2);
    }

    static void extractBackdrop(GuiGraphicsExtractor graphics, int width, int height) {
        graphics.fill(0, 0, width, height, BACKDROP);
        for (int y = 0; y < height; y += 32) {
            graphics.fill(0, y, width, Math.min(height, y + 1), BACKDROP_BAND);
        }
    }

    static void extractPanel(GuiGraphicsExtractor graphics, PanelBounds panel) {
        graphics.fill(panel.x() + 4, panel.y() + 4, panel.right() + 4, panel.bottom() + 4, PANEL_SHADOW);
        graphics.fill(panel.x(), panel.y(), panel.right(), panel.bottom(), PANEL_EDGE);
        graphics.outline(panel.x(), panel.y(), panel.width(), panel.height(), GOLD_DARK);
        graphics.fill(panel.x() + 3, panel.y() + 3, panel.right() - 3, panel.bottom() - 3, PANEL_INNER);
        graphics.fill(panel.x() + 4, panel.y() + 4, panel.right() - 4, panel.y() + 22, PANEL_HEADER);
        graphics.fill(panel.x() + 4, panel.y() + 22, panel.right() - 4, panel.y() + 23, GOLD_DARK);
        extractCornerOrnaments(graphics, panel);
    }

    static void extractPortrait(GuiGraphicsExtractor graphics, int x, int y, int width, int height) {
        graphics.fill(x, y, x + width, y + height, FIELD);
        graphics.outline(x, y, width, height, GOLD_DARK);
        graphics.outline(x + 2, y + 2, width - 4, height - 4, PANEL_EDGE);
    }

    static void extractSlot(GuiGraphicsExtractor graphics, Slot slot, boolean actionSlot) {
        int x = slot.x - 1;
        int y = slot.y - 1;
        graphics.fill(x, y, x + 18, y + 18, actionSlot ? SLOT_ACTION : SLOT_INVENTORY);
        graphics.outline(x, y, 18, 18, actionSlot ? GOLD_DARK : PANEL_EDGE);
        if (actionSlot) {
            graphics.fill(x + 2, y + 2, x + 16, y + 3, 0x553E2B17);
        }
    }

    static void extractField(GuiGraphicsExtractor graphics, int x, int y, int width, int height, boolean focused) {
        graphics.fill(x, y, x + width, y + height, FIELD);
        graphics.outline(x, y, width, height, focused ? GOLD_BRIGHT : GOLD_DARK);
        graphics.outline(x + 2, y + 2, width - 4, height - 4, PANEL_EDGE);
    }

    static void extractButton(
            GuiGraphicsExtractor graphics,
            int x,
            int y,
            int width,
            int height,
            boolean active,
            boolean hovered,
            boolean focused) {
        int background = !active ? BUTTON_DISABLED : hovered || focused ? BUTTON_HOVERED : BUTTON;
        int border = active && (hovered || focused) ? GOLD_BRIGHT : active ? GOLD : GOLD_DARK;
        graphics.fill(x, y, x + width, y + height, background);
        graphics.outline(x, y, width, height, border);
        graphics.fill(x + 2, y + 2, x + width - 2, y + 3, active ? 0x665B3D1D : 0x332B2118);
        if (focused) {
            graphics.outline(x, y, width, height, FOCUS_INNER);
            graphics.outline(x - 1, y - 1, width + 2, height + 2, FOCUS_OUTER);
        }
    }

    private static void extractCornerOrnaments(GuiGraphicsExtractor graphics, PanelBounds panel) {
        int right = panel.right() - 4;
        int bottom = panel.bottom() - 4;
        graphics.fill(panel.x() + 3, panel.y() + 3, panel.x() + 9, panel.y() + 5, GOLD);
        graphics.fill(panel.x() + 3, panel.y() + 3, panel.x() + 5, panel.y() + 9, GOLD);
        graphics.fill(right - 6, panel.y() + 3, right, panel.y() + 5, GOLD);
        graphics.fill(right - 2, panel.y() + 3, right, panel.y() + 9, GOLD);
        graphics.fill(panel.x() + 3, bottom - 2, panel.x() + 9, bottom, GOLD_DARK);
        graphics.fill(panel.x() + 3, bottom - 6, panel.x() + 5, bottom, GOLD_DARK);
        graphics.fill(right - 6, bottom - 2, right, bottom, GOLD_DARK);
        graphics.fill(right - 2, bottom - 6, right, bottom, GOLD_DARK);
    }

    record PanelBounds(int x, int y, int width, int height) {
        int right() {
            return x + width;
        }

        int bottom() {
            return y + height;
        }
    }
}
