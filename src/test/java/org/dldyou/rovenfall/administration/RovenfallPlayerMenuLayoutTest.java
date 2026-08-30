package org.dldyou.rovenfall.administration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.minecraft.network.chat.Component;
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
        assertFalse(next.overlaps(layout.technicalButton()));
    }

    @Test
    void supportedGuiScaleMatrixKeepsEveryControlOnScreen() {
        for (int[] size : List.of(
                new int[]{320, 240}, new int[]{426, 240}, new int[]{640, 360},
                new int[]{854, 480}, new int[]{1_920, 1_080})) {
            var layout = RovenfallPlayerMenuLayout.fit(size[0], size[1]);
            assertTrue(layout.panel().x() >= 0);
            assertTrue(layout.panel().right() <= size[0]);
            assertTrue(layout.panel().bottom() <= size[1]);
            assertTrue(layout.previousPageButton().x() >= layout.panel().x());
            assertTrue(layout.technicalButton().right() <= layout.panel().right());
            assertFalse(layout.previousPageButton().overlaps(layout.nextPageButton()));
            assertFalse(layout.nextPageButton().overlaps(layout.technicalButton()));
        }
    }

    @Test
    void technicalIdentifiersAreHiddenUntilAdvancedDetailsAreEnabled() {
        List<Component> lines = List.of(
                Component.literal("토지 정보"),
                Component.literal("소유자: 모험가"),
                Component.literal("UUID: 00000000-0000-0000-0000-000000000001"),
                Component.literal("rovenfall:warrior"));

        assertEquals(List.of("토지 정보", "소유자: 모험가"),
                RovenfallCustomPlayerMenuScreen.exposedLines(lines, false).stream()
                        .map(Component::getString).toList());
        assertEquals(lines, RovenfallCustomPlayerMenuScreen.exposedLines(lines, true));
    }
}
