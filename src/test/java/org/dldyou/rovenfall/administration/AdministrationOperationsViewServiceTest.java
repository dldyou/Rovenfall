package org.dldyou.rovenfall.administration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class AdministrationOperationsViewServiceTest {
    @Test
    void pageIsClampedAfterAResultSetShrinks() {
        assertEquals(0, AdministrationOperationsViewService.clampPage(4, 0));
        assertEquals(2, AdministrationOperationsViewService.clampPage(4, 3));
        assertEquals(1, AdministrationOperationsViewService.clampPage(1, 3));
        assertThrows(IllegalArgumentException.class,
                () -> AdministrationOperationsViewService.clampPage(-1, 3));
    }
}
