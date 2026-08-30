package org.dldyou.rovenfall.administration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RovenfallUiThemeTest {
    @Test
    void panelBoundsGrowAroundVanillaSlotGeometry() {
        assertEquals(
                new RovenfallUiTheme.PanelBounds(72, 32, 192, 184),
                RovenfallUiTheme.panelFor(80, 40, 176, 168, 8));
        assertEquals(
                new RovenfallUiTheme.PanelBounds(72, 5, 192, 238),
                RovenfallUiTheme.panelFor(80, 13, 176, 222, 8));
    }

    @Test
    void panelBoundsRejectInvalidGeometry() {
        assertThrows(IllegalArgumentException.class, () -> RovenfallUiTheme.panelFor(0, 0, 0, 10, 1));
        assertThrows(IllegalArgumentException.class, () -> RovenfallUiTheme.panelFor(0, 0, 10, 10, -1));
    }

    @Test
    void keyboardFocusRingHasAtLeastThreeToOneAdjacentContrast() {
        assertTrue(contrastRatio(RovenfallUiTheme.FOCUS_OUTER, RovenfallUiTheme.FOCUS_INNER) >= 3.0);
    }

    private static double contrastRatio(int first, int second) {
        double light = Math.max(luminance(first), luminance(second));
        double dark = Math.min(luminance(first), luminance(second));
        return (light + 0.05) / (dark + 0.05);
    }

    private static double luminance(int color) {
        return 0.2126 * channel(color >> 16) + 0.7152 * channel(color >> 8) + 0.0722 * channel(color);
    }

    private static double channel(int shifted) {
        double value = (shifted & 0xFF) / 255.0;
        return value <= 0.04045 ? value / 12.92 : Math.pow((value + 0.055) / 1.055, 2.4);
    }
}
