package org.dldyou.rovenfall.administration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class RovenfallPlayerMenuLayoutTest {
    @Test
    void responsiveCardsStayInsideBoundsWithoutOverlappingToolbarOrDetail() {
        var narrow = RovenfallPlayerMenuLayout.fit(320, 240);
        assertEquals(1, narrow.columns());
        assertFalse(narrow.wide());
        assertTrue(narrow.panel().x() >= 0);
        assertTrue(narrow.panel().right() <= 320);
        assertTrue(narrow.panel().bottom() <= 240);
        for (int index = 0; index < narrow.pageSize(); index++) {
            var card = narrow.card(index);
            assertTrue(card.x() >= narrow.cards().x());
            assertTrue(card.right() <= narrow.cards().right());
            assertTrue(card.bottom() <= narrow.cards().bottom());
            assertFalse(card.overlaps(narrow.toolbar()));
            for (int other = index + 1; other < narrow.pageSize(); other++) {
                assertFalse(card.overlaps(narrow.card(other)));
            }
        }

        var wide = RovenfallPlayerMenuLayout.fit(800, 420);
        assertEquals(2, wide.columns());
        assertTrue(wide.wide());
        assertFalse(wide.cards().overlaps(wide.detail()));
        assertFalse(wide.detail().overlaps(wide.toolbar()));
        assertTrue(wide.pageSize() > narrow.pageSize());
    }

    @Test
    void pageSizeAndToolbarSlotMappingAreDeterministic() {
        var layout = RovenfallPlayerMenuLayout.fit(320, 240);
        assertEquals(layout.columns() * layout.rows(), layout.pageSize());

        assertEquals(18, RovenfallPlayerMenuLayout.toolbarStart(3));
        assertFalse(RovenfallPlayerMenuLayout.isToolbarSlot(3, 17));
        assertTrue(RovenfallPlayerMenuLayout.isToolbarSlot(3, 18));
        assertTrue(RovenfallPlayerMenuLayout.isToolbarSlot(3, 26));
        assertFalse(RovenfallPlayerMenuLayout.isToolbarSlot(3, 27));

        assertEquals(45, RovenfallPlayerMenuLayout.toolbarStart(6));
        assertFalse(RovenfallPlayerMenuLayout.isToolbarSlot(6, 44));
        assertTrue(RovenfallPlayerMenuLayout.isToolbarSlot(6, 45));
        assertTrue(RovenfallPlayerMenuLayout.isToolbarSlot(6, 53));

        var first = layout.toolbarButton(0, 3);
        var second = layout.toolbarButton(1, 3);
        assertFalse(first.overlaps(second));
        assertTrue(first.x() >= layout.toolbar().x());
        assertTrue(layout.toolbarButton(2, 3).right() <= layout.toolbar().right());

        var previous = layout.previousPageButton();
        var next = layout.nextPageButton();
        assertFalse(previous.overlaps(next));
        assertTrue(previous.x() >= layout.panel().x());
        assertTrue(next.right() <= layout.panel().right());
        assertFalse(layout.pageLabel().overlaps(previous));
    }
}
