package org.dldyou.rovenfall.administration;

/**
 * Screen-space geometry for the custom administration view.  The server's 9x6 menu slots remain
 * the authority; this class only gives the client a responsive place to present them.
 */
final class RovenfallAdministrationMenuLayout {
    static final int MAX_FORM_FIELDS = 8;
    private static final int EDGE = 6;
    private static final int GAP = 5;
    private static final int HEADER_HEIGHT = 24;
    private static final int TOOLBAR_HEIGHT = 22;
    private static final int CARD_HEIGHT = 32;
    private static final int NARROW_CARD_AREA_HEIGHT = 42;
    private static final int MAX_PANEL_WIDTH = 920;
    private static final int DETAIL_WIDTH = 280;

    private RovenfallAdministrationMenuLayout() {
    }

    static Layout fit(int screenWidth, int screenHeight) {
        int width = Math.max(1, screenWidth);
        int height = Math.max(1, screenHeight);
        int panelWidth = Math.max(1, Math.min(MAX_PANEL_WIDTH, width - EDGE * 2));
        int panelHeight = Math.max(1, height - EDGE * 2);
        Rect panel = new Rect((width - panelWidth) / 2, (height - panelHeight) / 2, panelWidth, panelHeight);
        int toolbarY = Math.max(panel.y(), panel.bottom() - TOOLBAR_HEIGHT - GAP);
        Rect header = new Rect(panel.x() + GAP, panel.y() + GAP,
                Math.max(1, panel.width() - GAP * 2), Math.min(HEADER_HEIGHT, panel.height()));
        Rect toolbar = new Rect(panel.x() + GAP, toolbarY,
                Math.max(1, panel.width() - GAP * 2), Math.max(1, panel.bottom() - toolbarY - GAP));
        int bodyY = Math.min(panel.bottom(), header.bottom() + GAP);
        int bodyBottom = Math.max(bodyY, toolbar.y() - GAP);
        int bodyHeight = bodyBottom - bodyY;

        boolean wide = panel.width() >= 560 && bodyHeight >= CARD_HEIGHT * 2;
        Rect cards;
        Rect detail;
        int columns;
        int rows;
        if (wide) {
            int detailWidth = Math.min(DETAIL_WIDTH, Math.max(220, panel.width() / 2));
            int cardsWidth = Math.max(1, panel.width() - GAP * 2 - GAP - detailWidth);
            cards = new Rect(panel.x() + GAP, bodyY, cardsWidth, bodyHeight);
            detail = new Rect(cards.right() + GAP, bodyY,
                    Math.max(1, panel.right() - GAP - cards.right() - GAP), bodyHeight);
            columns = cards.width() >= 300 ? 2 : 1;
            rows = Math.max(1, (cards.height() + GAP) / (CARD_HEIGHT + GAP));
        } else {
            int cardsHeight = Math.min(NARROW_CARD_AREA_HEIGHT, Math.max(1, bodyHeight / 3));
            cards = new Rect(panel.x() + GAP, bodyY, Math.max(1, panel.width() - GAP * 2), cardsHeight);
            int detailY = Math.min(bodyBottom, cards.bottom() + GAP);
            detail = new Rect(panel.x() + GAP, detailY, Math.max(1, panel.width() - GAP * 2),
                    Math.max(1, bodyBottom - detailY));
            columns = 1;
            rows = 1;
        }
        return new Layout(panel, header, cards, detail, toolbar, columns, rows, wide);
    }

    record Layout(Rect panel, Rect header, Rect cards, Rect detail, Rect toolbar,
                  int columns, int rows, boolean wide) {
        int pageSize() {
            return columns * rows;
        }

        Rect card(int index) {
            if (index < 0 || index >= pageSize()) {
                throw new IllegalArgumentException("Card index outside page");
            }
            int width = Math.max(1, (cards.width() - GAP * (columns - 1)) / columns);
            return new Rect(cards.x() + index % columns * (width + GAP),
                    cards.y() + index / columns * (CARD_HEIGHT + GAP), width,
                    Math.min(CARD_HEIGHT, cards.bottom() - (cards.y() + index / columns * (CARD_HEIGHT + GAP))));
        }

        Rect toolbarButton(int index, int count) {
            if (count < 1 || index < 0 || index >= count) {
                throw new IllegalArgumentException("Invalid toolbar button");
            }
            int width = Math.max(1, (toolbar.width() - GAP * (count - 1)) / count);
            return new Rect(toolbar.x() + index * (width + GAP), toolbar.y(), width, toolbar.height());
        }

        Rect formField(int index, int count) {
            if (count < 1 || count > MAX_FORM_FIELDS || index < 0 || index >= count) {
                throw new IllegalArgumentException("Invalid form field");
            }
            int columns = detail.width() >= 240 ? 2 : 1;
            int rows = (count + columns - 1) / columns;
            int width = Math.max(1, (detail.width() - GAP * (columns - 1)) / columns);
            int fieldsTop = Math.min(detail.bottom(), detail.y() + Math.min(24, detail.height() / 4));
            int availableHeight = Math.max(1, detail.bottom() - fieldsTop);
            int height = Math.max(1, (availableHeight - GAP * (rows - 1)) / rows);
            return new Rect(detail.x() + index % columns * (width + GAP),
                    fieldsTop + index / columns * (height + GAP), width, height);
        }

        Rect previousPageButton() {
            int width = Math.min(50, Math.max(1, header.width() / 5));
            return new Rect(header.right() - width * 2 - GAP, header.y(), width, header.height());
        }

        Rect nextPageButton() {
            int width = Math.min(50, Math.max(1, header.width() / 5));
            return new Rect(header.right() - width, header.y(), width, header.height());
        }
    }

    record Rect(int x, int y, int width, int height) {
        int right() {
            return x + width;
        }

        int bottom() {
            return y + height;
        }

        boolean overlaps(Rect other) {
            return x < other.right() && right() > other.x() && y < other.bottom() && bottom() > other.y();
        }

        boolean inside(int screenWidth, int screenHeight) {
            return x >= 0 && y >= 0 && right() <= screenWidth && bottom() <= screenHeight;
        }
    }
}
