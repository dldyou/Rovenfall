package org.dldyou.rovenfall.quest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class RpgStatusHudTest {
    @Test
    void statusBarsStayCenteredAndInsideWideOrNarrowScreens() {
        var wide = RpgStatusHud.layout(640, 360);
        assertEquals(RpgStatusHud.MAX_BAR_WIDTH, wide.barWidth());
        assertEquals(318, wide.y());
        assertTrue(wide.leftX() >= 0);
        assertTrue(wide.rightX() + wide.barWidth() <= 640);

        var narrow = RpgStatusHud.layout(120, 80);
        assertTrue(narrow.barWidth() >= RpgStatusHud.MIN_BAR_WIDTH);
        assertTrue(narrow.leftX() >= 0);
        assertTrue(narrow.rightX() + narrow.barWidth() <= 120);
        assertThrows(IllegalArgumentException.class, () -> RpgStatusHud.layout(0, 80));
    }

    @Test
    void statusProgressIsAlwaysBounded() {
        assertEquals(0.0F, RpgStatusHud.ratio(-1, 20));
        assertEquals(0.5F, RpgStatusHud.ratio(10, 20));
        assertEquals(1.0F, RpgStatusHud.ratio(30, 20));
        assertEquals(0.0F, RpgStatusHud.ratio(1, 0));
    }
}
