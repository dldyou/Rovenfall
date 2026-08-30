package org.dldyou.rovenfall.administration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import org.dldyou.rovenfall.claims.ClaimKey;
import org.dldyou.rovenfall.world.ProtectedRegion;
import org.junit.jupiter.api.Test;

final class AdministrationWorldViewServiceTest {
    @Test
    void claimsAreTypedSearchableAndRoleBounded() {
        PlatformSavedData state = new PlatformSavedData();
        UUID owner = id(1);
        UUID viewer = id(2);
        ClaimKey key = ClaimKey.at(Level.OVERWORLD, new BlockPos(32, 70, 48));
        EconomyService.award(state, owner, 5_000, "seed", 1_000, id(100), 0, Long.MAX_VALUE);
        ClaimPurchaseService.purchase(
                state, owner, key.dimension(), key.dimension(), key.auditPosition(),
                ignored -> true, ignored -> false, 1_000, 0, 64, 2_000, id(101));
        AdministrationService.changeRole(
                state, AdministrationService.SYSTEM_ACTOR, true, viewer, "viewer",
                "bootstrap", 3_000, id(102));

        var page = AdministrationWorldViewService.claims(
                state, viewer, false, owner.toString(), 0);

        assertEquals(AdministrationWorldViewService.Status.SUCCESS, page.status());
        assertEquals(1, page.totalEntries());
        assertEquals(key, page.entries().getFirst().key());
        assertTrue(AdministrationWorldViewService.claims(
                state, id(9), false, "", 0).entries().isEmpty());
    }

    @Test
    void boundedWorldRowsAreDeterministicAndReportTruncation() {
        PlatformSavedData state = new PlatformSavedData();
        UUID owner = id(10);
        int retained = AdministrationWorldViewService.MAX_SCANNED_ROWS;
        EconomyService.award(
                state, owner, retained + 1L, "seed", 1, id(10_000), 0, retained + 1L);
        for (int index = 0; index <= retained; index++) {
            var result = ClaimPurchaseService.purchase(
                    state, owner, Level.OVERWORLD, Level.OVERWORLD,
                    new BlockPos(index << 4, 64, 0), ignored -> true, ignored -> false,
                    1, 0, retained + 1, index + 2L, id(20_000L + index));
            assertEquals(ClaimPurchaseService.Status.SUCCESS, result.status());
        }

        var first = AdministrationWorldViewService.claims(state, owner, true, "", 0);
        var bounded = state.claims(retained);
        var excluded = state.claims().get(retained).getKey();
        var searched = AdministrationWorldViewService.claims(
                state, owner, true, excluded.auditTarget(), 0);

        assertEquals(retained, first.totalEntries());
        assertEquals(AdministrationWorldViewService.PAGE_SIZE, first.entries().size());
        assertTrue(first.truncated());
        assertEquals(0, searched.totalEntries());
        assertTrue(searched.truncated());
        assertEquals(retained, bounded.size());
        assertEquals(bounded, state.claims(retained));
    }

    @Test
    void protectedRegionsUseBoundedOrderedAccessAndSearch() {
        PlatformSavedData state = new PlatformSavedData();
        UUID actor = id(20);
        addRegion(state, actor, "z-last", 2);
        addRegion(state, actor, "a-first", 0);
        addRegion(state, actor, "m-middle", 1);

        List<Identifier> firstTwo = state.protectedRegions(2).stream().map(java.util.Map.Entry::getKey).toList();
        var page = AdministrationWorldViewService.regions(state, actor, true, "m-middle", 0);

        assertEquals(List.of(identifier("a-first"), identifier("m-middle")), firstTwo);
        assertEquals(1, page.totalEntries());
        assertEquals(identifier("m-middle"), page.entries().getFirst().regionId());
        assertFalse(page.truncated());
    }

    @Test
    void wildernessEvidenceIsBoundedNewestFirstAndSearchable() {
        PlatformSavedData state = new PlatformSavedData();
        UUID actor = id(30);
        for (int index = 0; index < 3; index++) {
            UUID transactionId = id(30_000L + index);
            long timestamp = 100L + index * 10L;
            WildernessResetState.Operation operation = new WildernessResetState.Operation(
                    WildernessResetState.Kind.RESET,
                    transactionId, transactionId, transactionId, actor, timestamp, "test",
                    0, 0, "0".repeat(64), 0, 0, "0".repeat(64));
            state.commitWildernessOperation(operation, audit(actor, transactionId, timestamp));
            state.completeWildernessOperation(
                    new WildernessResetState.Evidence(
                            operation, WildernessResetState.Result.COMPLETED, timestamp + 1, "marker-" + index),
                    audit(actor, transactionId, timestamp + 1));
        }

        var page = AdministrationWorldViewService.evidence(state, actor, true, "", 0);
        var searched = AdministrationWorldViewService.evidence(state, actor, true, "marker-1", 0);

        assertEquals(id(30_002), page.entries().getFirst().evidence().operation().transactionId());
        assertEquals(1, searched.totalEntries());
        assertEquals("marker-1", searched.entries().getFirst().evidence().detail());
        assertFalse(page.truncated());
    }

    private static void addRegion(PlatformSavedData state, UUID actor, String path, int chunk) {
        Identifier regionId = identifier(path);
        state.commitProtectedRegionMutation(
                regionId,
                Optional.of(new ProtectedRegion(actor, Level.OVERWORLD, chunk, chunk, chunk, chunk)),
                audit(actor, id(40_000L + chunk), chunk));
    }

    private static AuditEntry audit(UUID actor, UUID transactionId, long timestamp) {
        return new AuditEntry(
                timestamp, actor, identifier("test"), "test", Optional.empty(), Optional.empty(),
                "before", "after", "test", transactionId);
    }

    private static Identifier identifier(String path) {
        return Identifier.fromNamespaceAndPath("rovenfall", path);
    }

    private static UUID id(long value) {
        return new UUID(0L, value);
    }
}
