package org.dldyou.rovenfall.administration;

/** Pure slot-selection rules shared by the client screen and unit tests. */
final class PlayerMenuKeyboardNavigation {
    static final int DASHBOARD_BACK_SLOT = 18;
    static final int DASHBOARD_REFRESH_SLOT = 26;
    static final int TOOLBAR_BACK_SLOT = 45;
    static final int TOOLBAR_REFRESH_SLOT = 53;

    private PlayerMenuKeyboardNavigation() {
    }

    static int nextOccupied(boolean[] occupied, int start, int step) {
        if (occupied.length == 0 || step == 0) {
            return -1;
        }
        int current = start;
        for (int checked = 0; checked < occupied.length; checked++) {
            current = Math.floorMod(current + step, occupied.length);
            if (occupied[current]) {
                return current;
            }
        }
        return -1;
    }

    static int backSlot(boolean dashboard, boolean dashboardBackAvailable, boolean toolbarBackAvailable) {
        if (toolbarBackAvailable) {
            return TOOLBAR_BACK_SLOT;
        }
        return dashboard && dashboardBackAvailable ? DASHBOARD_BACK_SLOT : -1;
    }

    static int refreshSlot(boolean dashboard) {
        return dashboard ? DASHBOARD_REFRESH_SLOT : TOOLBAR_REFRESH_SLOT;
    }
}
