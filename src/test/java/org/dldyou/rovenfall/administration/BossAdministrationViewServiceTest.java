package org.dldyou.rovenfall.administration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

final class BossAdministrationViewServiceTest {
    @Test
    void pagesAreBoundedStableAndOutOfRangeSafe() {
        List<Integer> values = List.of(1, 2, 3, 4, 5);

        var first = BossAdministrationViewService.page(values, 0, 2);
        var last = BossAdministrationViewService.page(values, 2, 2);
        var missing = BossAdministrationViewService.page(values, 3, 2);

        assertEquals(List.of(1, 2), first.entries());
        assertEquals(3, first.totalPages());
        assertEquals(5, first.totalEntries());
        assertEquals(List.of(5), last.entries());
        assertEquals(List.of(), missing.entries());
        assertThrows(IllegalArgumentException.class,
                () -> BossAdministrationViewService.page(values, 0,
                        BossAdministrationViewService.MAX_PAGE_SIZE + 1));
    }
}
