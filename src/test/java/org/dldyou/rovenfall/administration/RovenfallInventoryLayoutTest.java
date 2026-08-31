package org.dldyou.rovenfall.administration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class RovenfallInventoryLayoutTest {
    @Test
    void tabsStayOnScreenAndWrapAtNarrowWidths() {
        var wide = RovenfallInventoryLayout.tabs(320, 37, 7);
        assertEquals(7, wide.columns());
        assertEquals(1, wide.rows());
        assertTrue(wide.x() >= 0);
        assertTrue(wide.right() <= 320);
        assertTrue(wide.xFor(6) >= wide.x());
        assertTrue(wide.xFor(6) + wide.tabWidth() <= wide.right());
        assertTrue(wide.yFor(6) + RovenfallInventoryLayout.TAB_HEIGHT <= wide.bottom());

        var narrow = RovenfallInventoryLayout.tabs(240, 67, 7);
        assertEquals(3, narrow.columns());
        assertEquals(3, narrow.rows());
        assertTrue(narrow.x() >= 0);
        assertTrue(narrow.right() <= 240);
        assertTrue(narrow.y() >= 0);
        assertTrue(narrow.bottom() <= 67);
        assertEquals(narrow.x(), narrow.xFor(3));
        assertTrue(narrow.yFor(3) > narrow.yFor(0));
        assertTrue(narrow.xFor(6) >= narrow.x());
        assertTrue(narrow.xFor(6) + narrow.tabWidth() <= narrow.right());
        assertTrue(narrow.yFor(6) + RovenfallInventoryLayout.TAB_HEIGHT <= narrow.bottom());
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

    @Test
    void supportedGuiScaleMatrixKeepsTabsAndSummaryOnScreen() {
        for (int[] size : java.util.List.of(
                new int[]{320, 240}, new int[]{426, 240}, new int[]{640, 360},
                new int[]{854, 480}, new int[]{1_920, 1_080})) {
            int inventoryLeft = (size[0] - 176) / 2;
            int inventoryTop = (size[1] - 166) / 2;
            var tabs = RovenfallInventoryLayout.tabs(size[0], inventoryTop, 7);
            var summary = RovenfallInventoryLayout.summary(size[0], inventoryLeft, inventoryTop, 176);
            assertTrue(tabs.x() >= 0);
            assertTrue(tabs.right() <= size[0]);
            assertTrue(tabs.y() >= 0);
            assertTrue(tabs.bottom() <= size[1]);
            assertTrue(tabs.xFor(6) >= tabs.x());
            assertTrue(tabs.xFor(6) + tabs.tabWidth() <= tabs.right());
            assertTrue(tabs.yFor(6) + RovenfallInventoryLayout.TAB_HEIGHT <= tabs.bottom());
            assertTrue(summary.x() >= 0);
            assertTrue(summary.right() <= size[0]);
            assertTrue(summary.y() >= 0);
            assertTrue(summary.bottom() <= size[1]);
        }
    }

    @Test
    void rejectsAnEmptyTabSet() {
        assertThrows(IllegalArgumentException.class, () -> RovenfallInventoryLayout.tabs(320, 37, 0));
    }
}
