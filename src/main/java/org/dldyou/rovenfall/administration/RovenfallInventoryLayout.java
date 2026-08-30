package org.dldyou.rovenfall.administration;

/** Responsive geometry that never changes the server-backed inventory slot positions. */
final class RovenfallInventoryLayout {
    static final int TAB_GAP = 2;
    static final int TAB_HEIGHT = 20;
    static final int SUMMARY_WIDTH = 120;
    static final int SUMMARY_HEIGHT = 58;
    private static final int EDGE = 8;
    private static final int SUMMARY_GAP = 12;

    private RovenfallInventoryLayout() {
    }

    static TabLayout tabs(int screenWidth, int inventoryTop) {
        int columns = screenWidth >= 268 ? 6 : 3;
        int rows = 6 / columns;
        int available = Math.max(columns * 24, screenWidth - EDGE * 2 - TAB_GAP * (columns - 1));
        int tabWidth = Math.min(72, available / columns);
        int rowWidth = tabWidth * columns + TAB_GAP * (columns - 1);
        int height = rows * TAB_HEIGHT + (rows - 1) * TAB_GAP;
        return new TabLayout(
                Math.max(0, (screenWidth - rowWidth) / 2),
                Math.max(0, inventoryTop - height - 4),
                tabWidth,
                columns,
                rows);
    }

    static SummaryBounds summary(int screenWidth, int left, int top, int inventoryWidth) {
        int rightX = left + inventoryWidth + SUMMARY_GAP;
        if (rightX + SUMMARY_WIDTH <= screenWidth - EDGE) {
            return new SummaryBounds(rightX, top + 24, SUMMARY_WIDTH, SUMMARY_HEIGHT, false);
        }
        // The recipe book owns the space left of the inventory when it is open.
        return new SummaryBounds(left + 95, top + 58, 77, 23, true);
    }

    record TabLayout(int x, int y, int tabWidth, int columns, int rows) {
        int xFor(int index) {
            return x + index % columns * (tabWidth + TAB_GAP);
        }

        int yFor(int index) {
            return y + index / columns * (TAB_HEIGHT + TAB_GAP);
        }

        int right() {
            return x + columns * tabWidth + (columns - 1) * TAB_GAP;
        }

        int bottom() {
            return y + rows * TAB_HEIGHT + (rows - 1) * TAB_GAP;
        }
    }

    record SummaryBounds(int x, int y, int width, int height, boolean compact) {
        int right() {
            return x + width;
        }

        int bottom() {
            return y + height;
        }
    }
}
