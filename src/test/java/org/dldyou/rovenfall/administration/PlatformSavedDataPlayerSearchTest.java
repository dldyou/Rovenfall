package org.dldyou.rovenfall.administration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

final class PlatformSavedDataPlayerSearchTest {
    @Test
    void boundedNameSearchFindsPlayersBeyondTheDefaultUuidWindow() {
        PlatformSavedData state = new PlatformSavedData();
        for (long value = 1; value <= 1_000; value++) {
            PlayerRecordService.observeLogin(state, id(value), "Player" + value, value);
        }
        UUID target = id(2_000);
        PlayerRecordService.observeLogin(state, target, "NeedleHero", 2_000);

        assertFalse(state.playerRecords(1_000).stream().anyMatch(entry -> entry.getKey().equals(target)));
        var matches = state.playerRecordsMatchingName("  nEeDlEhErO  ", 1_000);

        assertEquals(1, matches.size());
        assertEquals(target, matches.getFirst().getKey());
        assertEquals(state.playerRecords(3), state.playerRecordsMatchingName(" ", 3));
        assertTrue(state.playerRecordsMatchingName("missing", 3).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> state.playerRecordsMatchingName("player", 0));
    }

    private static UUID id(long value) {
        return new UUID(0L, value);
    }
}
