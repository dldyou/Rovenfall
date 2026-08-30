package org.dldyou.rovenfall.administration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

final class RovenfallAdministrationMenuLayoutTest {
    @Test
    void minimumNormalAndWideLayoutsStayOnScreenWithoutCollisions() {
        assertLayout(320, 240, false, 1);
        assertLayout(640, 480, true, 2);
        assertLayout(1_280, 720, true, 2);
    }

    @Test
    void cardsToolbarAndUpToEightFormFieldsNeverOverlap() {
        for (var layout : java.util.List.of(
                RovenfallAdministrationMenuLayout.fit(320, 240),
                RovenfallAdministrationMenuLayout.fit(640, 480),
                RovenfallAdministrationMenuLayout.fit(1_280, 720))) {
            for (int index = 0; index < layout.pageSize(); index++) {
                var card = layout.card(index);
                assertFalse(card.overlaps(layout.toolbar()));
                assertFalse(card.overlaps(layout.detail()));
                for (int other = index + 1; other < layout.pageSize(); other++) {
                    assertFalse(card.overlaps(layout.card(other)));
                }
            }
            for (int index = 0; index < 8; index++) {
                var field = layout.formField(index, 8);
                assertTrue(field.inside(layout.panel().right() + layout.panel().x(),
                        layout.panel().bottom() + layout.panel().y()));
                assertFalse(field.overlaps(layout.toolbar()));
                for (int other = index + 1; other < 8; other++) {
                    assertFalse(field.overlaps(layout.formField(other, 8)));
                }
            }
            for (int index = 0; index < 9; index++) {
                var button = layout.toolbarButton(index, 9);
                assertFalse(button.overlaps(layout.header()));
                for (int other = index + 1; other < 9; other++) {
                    assertFalse(button.overlaps(layout.toolbarButton(other, 9)));
                }
            }
        }
    }

    @Test
    void layoutIsDeterministicAndProvidesUsablePaging() {
        var first = RovenfallAdministrationMenuLayout.fit(800, 500);
        var second = RovenfallAdministrationMenuLayout.fit(800, 500);

        assertEquals(first, second);
        assertTrue(first.pageSize() > 0);
        assertFalse(first.previousPageButton().overlaps(first.nextPageButton()));
    }

    @Test
    void ordinaryCardsHideTrailingTechnicalDetails() {
        List<Component> lines = List.of(
                Component.literal("감사 기록"),
                Component.literal("시각: 1234"),
                Component.literal("대상: player:00000000-0000-0000-0000-000000000003"),
                Component.literal("rovenfall:shop_instance_create"),
                Component.literal("actor: 00000000-0000-0000-0000-000000000001"),
                Component.literal("transaction: 00000000-0000-0000-0000-000000000002"),
                Component.literal("상태: 완료"));

        assertEquals(List.of("감사 기록", "시각: 1234", "상태: 완료"),
                RovenfallAdministrationMenuScreen.publicLines(lines).stream()
                        .map(Component::getString)
                        .toList());
    }

    private static void assertLayout(int width, int height, boolean wide, int columns) {
        var layout = RovenfallAdministrationMenuLayout.fit(width, height);
        assertEquals(wide, layout.wide());
        assertEquals(columns, layout.columns());
        assertTrue(layout.pageSize() > 0);
        assertTrue(layout.panel().inside(width, height));
        assertTrue(layout.header().inside(width, height));
        assertTrue(layout.cards().inside(width, height));
        assertTrue(layout.detail().inside(width, height));
        assertTrue(layout.toolbar().inside(width, height));
        assertFalse(layout.header().overlaps(layout.cards()));
        assertFalse(layout.header().overlaps(layout.detail()));
        assertFalse(layout.cards().overlaps(layout.toolbar()));
        assertFalse(layout.detail().overlaps(layout.toolbar()));
        if (wide) {
            assertFalse(layout.cards().overlaps(layout.detail()));
        }
    }
}
