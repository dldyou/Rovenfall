package org.dldyou.rovenfall.administration;

/** Screen-space geometry for the custom player menu; server slot coordinates stay untouched. */
final class RovenfallPlayerMenuLayout {
    static final int GAP = 6;
    static final int CARD_HEIGHT = 42;
    static final int TOOLBAR_HEIGHT = 24;
    static final int PAGE_BUTTON_WIDTH = 52;
    static final int TECHNICAL_BUTTON_WIDTH = 20;
    private static final int EDGE = 8;
    private static final int HEADER_HEIGHT = 30;
    private static final int MAX_WIDTH = 760;
    private static final int DETAIL_WIDTH = 250;

    private RovenfallPlayerMenuLayout() {
    }

    static Layout fit(int screenWidth, int screenHeight) {
        int panelWidth = Math.max(160, Math.min(MAX_WIDTH, screenWidth - EDGE * 2));
        int panelHeight = Math.max(120, screenHeight - EDGE * 2);
        Bounds panel = new Bounds((screenWidth - panelWidth) / 2, EDGE, panelWidth, panelHeight);
        Bounds toolbar = new Bounds(
                panel.x() + GAP,
                panel.bottom() - TOOLBAR_HEIGHT - GAP,
                panel.width() - GAP * 2,
                TOOLBAR_HEIGHT);
        int bodyY = panel.y() + HEADER_HEIGHT;
        int bodyHeight = Math.max(CARD_HEIGHT, toolbar.y() - GAP - bodyY);
        boolean wide = panel.width() >= 560;
        int detailWidth = wide ? DETAIL_WIDTH : 0;
        int cardsWidth = panel.width() - GAP * 2 - (wide ? detailWidth + GAP : 0);
        Bounds cards = new Bounds(panel.x() + GAP, bodyY, cardsWidth, bodyHeight);
        Bounds detail = wide
                ? new Bounds(cards.right() + GAP, bodyY, detailWidth, bodyHeight)
                : new Bounds(cards.right(), bodyY, 0, bodyHeight);
        int columns = cards.width() >= 330 ? 2 : 1;
        int rows = Math.max(1, (cards.height() + GAP) / (CARD_HEIGHT + GAP));
        return new Layout(panel, cards, detail, toolbar, columns, rows, wide);
    }

    static int toolbarStart(int rowCount) {
        return Math.max(0, (rowCount - 1) * 9);
    }

    static boolean isToolbarSlot(int rowCount, int slotId) {
        return slotId >= toolbarStart(rowCount) && slotId < rowCount * 9;
    }

    record Layout(
            Bounds panel,
            Bounds cards,
            Bounds detail,
            Bounds toolbar,
            int columns,
            int rows,
            boolean wide) {
        int pageSize() {
            return columns * rows;
        }

        Bounds card(int index) {
            int width = (cards.width() - GAP * (columns - 1)) / columns;
            return new Bounds(
                    cards.x() + index % columns * (width + GAP),
                    cards.y() + index / columns * (CARD_HEIGHT + GAP),
                    width,
                    CARD_HEIGHT);
        }

        Bounds toolbarButton(int index, int count) {
            int safeCount = Math.max(1, count);
            int width = (toolbar.width() - GAP * (safeCount - 1)) / safeCount;
            return new Bounds(toolbar.x() + index * (width + GAP), toolbar.y(), width, toolbar.height());
        }

        Bounds previousPageButton() {
            return new Bounds(
                    panel.right() - TECHNICAL_BUTTON_WIDTH - GAP - PAGE_BUTTON_WIDTH * 2 - GAP - 7,
                    panel.y() + 3,
                    PAGE_BUTTON_WIDTH,
                    20);
        }

        Bounds nextPageButton() {
            return new Bounds(
                    panel.right() - TECHNICAL_BUTTON_WIDTH - GAP - PAGE_BUTTON_WIDTH - 7,
                    panel.y() + 3,
                    PAGE_BUTTON_WIDTH,
                    20);
        }

        Bounds technicalButton() {
            return new Bounds(
                    panel.right() - TECHNICAL_BUTTON_WIDTH - 7,
                    panel.y() + 3,
                    TECHNICAL_BUTTON_WIDTH,
                    20);
        }

        Bounds pageLabel() {
            int x = panel.x() + panel.width() / 3;
            return new Bounds(x, panel.y() + 8, Math.max(0, previousPageButton().x() - GAP - x), 9);
        }
    }

    record Bounds(int x, int y, int width, int height) {
        int right() {
            return x + width;
        }

        int bottom() {
            return y + height;
        }

        boolean overlaps(Bounds other) {
            return x < other.right() && right() > other.x()
                    && y < other.bottom() && bottom() > other.y();
        }
    }
}
