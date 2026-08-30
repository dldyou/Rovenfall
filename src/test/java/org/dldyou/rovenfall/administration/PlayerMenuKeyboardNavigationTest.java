package org.dldyou.rovenfall.administration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class PlayerMenuKeyboardNavigationTest {
    @Test
    void keyboardFocusSkipsEmptySlotsAndWrapsInEitherDirection() {
        boolean[] occupied = new boolean[27];
        occupied[4] = true;
        occupied[10] = true;
        occupied[26] = true;

        assertEquals(4, PlayerMenuKeyboardNavigation.nextOccupied(occupied, -1, 1));
        assertEquals(10, PlayerMenuKeyboardNavigation.nextOccupied(occupied, 4, 1));
        assertEquals(4, PlayerMenuKeyboardNavigation.nextOccupied(occupied, 26, 1));
        assertEquals(26, PlayerMenuKeyboardNavigation.nextOccupied(occupied, 4, -1));
        assertEquals(-1, PlayerMenuKeyboardNavigation.nextOccupied(new boolean[27], 0, 1));
    }
}
