package org.dldyou.rovenfall.administration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
}
