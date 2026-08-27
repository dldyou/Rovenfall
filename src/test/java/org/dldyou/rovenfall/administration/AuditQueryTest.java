package org.dldyou.rovenfall.administration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

final class AuditQueryTest {
    @Test
    void parserBuildsCanonicalAndQueryAndMatchesEveryFilter() {
        UUID actor = id(1);
        UUID transaction = id(2);
        AuditQuery query = AuditQuery.parse(
                "target=player: action=rovenfall:test actor=" + actor
                        + " until=2000 transaction=" + transaction + " since=1000",
                0, 0, true);
        AuditEntry matching = entry(1_500, actor, "test", "player:one", transaction);

        assertTrue(query.matches(matching));
        assertFalse(query.matches(entry(1_500, id(3), "test", "player:one", transaction)));
        assertFalse(query.matches(entry(1_500, actor, "other", "player:one", transaction)));
        assertFalse(query.matches(entry(1_500, actor, "test", "shop:one", transaction)));
        assertEquals("since=1000 until=2000 actor=" + actor
                + " action=rovenfall:test target=player: transaction=" + transaction, query.canonical());
    }

    @Test
    void parserAppliesSearchDefaultsButRequiresExplicitExportWindow() {
        AuditQuery search = AuditQuery.parse("action=rovenfall:test", 100, 200, false);

        assertEquals(100, search.sinceEpochMillis());
        assertEquals(200, search.untilEpochMillis());
        assertThrows(IllegalArgumentException.class,
                () -> AuditQuery.parse("action=rovenfall:test", 100, 200, true));
    }

    @Test
    void parserRejectsDuplicatesUnknownKeysOversizedWindowsAndTargets() {
        assertThrows(IllegalArgumentException.class,
                () -> AuditQuery.parse("since=1 since=2 until=3", 0, 0, true));
        assertThrows(IllegalArgumentException.class,
                () -> AuditQuery.parse("since=1 until=3 nope=value", 0, 0, true));
        assertThrows(IllegalArgumentException.class,
                () -> AuditQuery.parse("since=0 until=" + (AuditQuery.MAX_WINDOW_MILLIS + 1), 0, 0, true));
        assertThrows(IllegalArgumentException.class,
                () -> AuditQuery.parse("since=1 until=2 target=" + "x".repeat(257), 0, 0, true));
    }

    @Test
    void filteredPaginationRemainsNewestFirstAndReportsExactMatches() {
        PlatformSavedData state = new PlatformSavedData();
        UUID actor = id(10);
        long[] timestamps = {1_004, 1_001, 1_006, 1_003, 1_000, 1_005, 1_002};
        for (int index = 0; index < timestamps.length; index++) {
            state.commitAudit(entry(timestamps[index], actor, index % 2 == 0 ? "test" : "other",
                    "player:" + index, id(100 + index)));
        }
        AuditQuery query = AuditQuery.parse(
                "since=1000 until=2000 action=rovenfall:test target=player:", 0, 0, true);

        PlatformSavedData.AuditPage first = state.auditPage(query, 0, 2);
        PlatformSavedData.AuditPage second = state.auditPage(query, 1, 2);
        assertEquals(4, first.totalEntries());
        assertEquals(2, first.totalPages());
        assertEquals("player:2", first.entries().getFirst().target());
        assertEquals("player:6", second.entries().getFirst().target());
        assertEquals("player:4", second.entries().getLast().target());
    }

    private static AuditEntry entry(long timestamp, UUID actor, String action, String target, UUID transaction) {
        return new AuditEntry(timestamp, actor, Identifier.fromNamespaceAndPath("rovenfall", action), target,
                Optional.empty(), Optional.empty(), "before", "after", "reason", transaction);
    }

    private static UUID id(long value) {
        return new UUID(0L, value);
    }
}
