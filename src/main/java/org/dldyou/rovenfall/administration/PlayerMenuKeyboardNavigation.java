package org.dldyou.rovenfall.administration;

/** Pure slot-selection rules shared by the client screen and unit tests. */
final class PlayerMenuKeyboardNavigation {
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
}
