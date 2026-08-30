package org.dldyou.rovenfall.administration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class RovenfallInventoryLayoutTest {
    @Test
    void tabsStayOnScreenAndWrapAtNarrowWidths() {
        var wide = RovenfallInventoryLayout.tabs(320, 37);
        assertEquals(6, wide.columns());
        assertEquals(1, wide.rows());
        assertTrue(wide.x() >= 0);
        assertTrue(wide.right() <= 320);

        var narrow = RovenfallInventoryLayout.tabs(240, 45);
        assertEquals(3, narrow.columns());
        assertEquals(2, narrow.rows());
        assertTrue(narrow.x() >= 0);
        assertTrue(narrow.right() <= 240);
        assertTrue(narrow.y() >= 0);
        assertTrue(narrow.bottom() <= 45);
        assertEquals(narrow.x(), narrow.xFor(3));
        assertTrue(narrow.yFor(3) > narrow.yFor(0));
    }

    @Test
    void summaryUsesSideSpaceOrFallsBackInsideFreeInventoryArea() {
        var side = RovenfallInventoryLayout.summary(640, 232, 37, 176);
        assertFalse(side.compact());
        assertTrue(side.x() >= 0);
        assertTrue(side.right() <= 640);

        var recipeBookOpen = RovenfallInventoryLayout.summary(480, 280, 37, 176);
        assertTrue(recipeBookOpen.compact());

        var compact = RovenfallInventoryLayout.summary(320, 72, 37, 176);
        assertTrue(compact.compact());
        assertEquals(167, compact.x());
        assertEquals(95, compact.y());
        assertTrue(compact.right() <= 248);
        assertTrue(compact.bottom() <= 203);
    }
}
