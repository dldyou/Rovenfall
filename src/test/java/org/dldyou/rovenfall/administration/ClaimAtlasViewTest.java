package org.dldyou.rovenfall.administration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import net.minecraft.nbt.NbtOps;
import net.minecraft.world.level.Level;
import org.dldyou.rovenfall.claims.Claim;
import org.dldyou.rovenfall.claims.ClaimKey;
import org.dldyou.rovenfall.claims.ClaimRole;
import org.dldyou.rovenfall.claims.ClaimSettings;
import org.dldyou.rovenfall.world.WorldTopology;
import org.junit.jupiter.api.Test;

final class ClaimAtlasViewTest {
    private static final UUID VIEWER = id(1);
    private static final ClaimKey ORIGIN = key(0, 0);

    @Test
    void nearbyHidesRestrictedClaimsBeforeNameLookupAndKeepsExactStaleEvidence() {
        PlatformSavedData state = new PlatformSavedData();
        UUID publicOwner = id(10);
        UUID hiddenOwner = id(11);
        UUID managerOwner = id(12);
        UUID visitorOwner = id(13);
        UUID pendingOwner = id(14);
        UUID boundaryOwner = id(15);
        UUID outsideOwner = id(16);
        ClaimKey publicKey = key(1, 0);
        ClaimKey hiddenKey = key(2, 0);
        ClaimKey managerKey = key(3, 0);
        ClaimKey visitorKey = key(4, 0);
        ClaimKey pendingKey = key(5, 0);
        ClaimKey boundaryKey = key(ClaimAtlasView.NEARBY_RADIUS, 0);
        ClaimKey outsideKey = key(ClaimAtlasView.NEARBY_RADIUS + 1, 0);
        purchase(state, VIEWER, ORIGIN, 100);
        purchase(state, publicOwner, publicKey, 110);
        purchase(state, hiddenOwner, hiddenKey, 120);
        purchase(state, managerOwner, managerKey, 130);
        purchase(state, visitorOwner, visitorKey, 140);
        purchase(state, pendingOwner, pendingKey, 150);
        purchase(state, boundaryOwner, boundaryKey, 160);
        purchase(state, outsideOwner, outsideKey, 170);
        restrict(state, hiddenOwner, hiddenKey, 200);
        restrict(state, managerOwner, managerKey, 210);
        restrict(state, visitorOwner, visitorKey, 220);
        restrict(state, pendingOwner, pendingKey, 225);
        assertEquals(ClaimManagementService.Status.SUCCESS, ClaimManagementService.setRole(
                state, managerOwner, false, managerKey, VIEWER, ClaimRole.MANAGER,
                "manager", 230, id(230)).status());
        assertEquals(ClaimManagementService.Status.SUCCESS, ClaimManagementService.setRole(
                state, visitorOwner, false, visitorKey, VIEWER, ClaimRole.VISITOR,
                "visitor", 240, id(240)).status());
        assertEquals(ClaimManagementService.Status.SUCCESS, ClaimManagementService.offerTransfer(
                state, pendingOwner, pendingKey, VIEWER, "pending", 250, id(250)).status());

        Map<UUID, String> names = Map.of(
                VIEWER, "Viewer",
                publicOwner, "Alice",
                hiddenOwner, "Hidden",
                managerOwner, "Manager",
                visitorOwner, "Visitor",
                pendingOwner, "Pending",
                boundaryOwner, "Boundary",
                outsideOwner, "Outside");
        Set<UUID> lookedUp = new HashSet<>();
        Function<UUID, Optional<String>> lookup = owner -> {
            lookedUp.add(owner);
            return Optional.ofNullable(names.get(owner));
        };
        ClaimAtlasView view = ClaimAtlasView.create(
                state, ORIGIN, ClaimAtlasView.Section.NEARBY, VIEWER, "", 0, ignored -> false, lookup);
        Map<ClaimKey, ClaimAtlasView.Row> rows = byKey(view.entries());

        assertFalse(rows.containsKey(ORIGIN));
        assertFalse(rows.containsKey(hiddenKey));
        assertFalse(rows.containsKey(outsideKey));
        assertFalse(lookedUp.contains(hiddenOwner));
        assertFalse(lookedUp.contains(outsideOwner));
        assertEquals(Set.of(publicKey, managerKey, visitorKey, pendingKey, boundaryKey), rows.keySet());
        assertEquals(ClaimAtlasView.Relation.PUBLIC, rows.get(publicKey).relation());
        assertFalse(rows.get(publicKey).actionable());
        assertEquals(ClaimAtlasView.Relation.TRUSTED, rows.get(managerKey).relation());
        assertTrue(rows.get(managerKey).actionable());
        assertEquals(ClaimAtlasView.Relation.TRUSTED, rows.get(visitorKey).relation());
        assertFalse(rows.get(visitorKey).actionable());
        assertEquals(ClaimAtlasView.Relation.TRANSFER_PENDING, rows.get(pendingKey).relation());
        assertTrue(rows.get(pendingKey).actionable());
        assertEquals(1, rows.get(publicKey).distanceChunks());
        assertEquals(Optional.of(ClaimAtlasView.Direction.EAST), rows.get(publicKey).direction());

        ClaimAtlasView searched = ClaimAtlasView.create(
                state, ORIGIN, ClaimAtlasView.Section.NEARBY, VIEWER, "aLI", 0, ignored -> false, lookup);
        assertEquals(List.of(publicKey), searched.entries().stream().map(ClaimAtlasView.Row::key).toList());
        Claim expected = rows.get(publicKey).expectedClaim().orElseThrow();
        assertEquals(ClaimManagementService.Status.SUCCESS, ClaimManagementService.setSettings(
                state, publicOwner, false, publicKey, new ClaimSettings(true, false),
                "changed", 260, id(260)).status());
        assertEquals(expected, rows.get(publicKey).expectedClaim().orElseThrow());
        assertNotEquals(expected, state.claim(publicKey).orElseThrow());

        UUID moderator = id(17);
        assertEquals(AdministrationService.RoleChangeStatus.SUCCESS, AdministrationService.changeRole(
                state, AdministrationService.SYSTEM_ACTOR, true, moderator,
                AdminRole.MODERATOR.getSerializedName(), "atlas moderator", 270, id(270)).status());
        ClaimAtlasView moderated = ClaimAtlasView.create(
                state, ORIGIN, ClaimAtlasView.Section.NEARBY, moderator, "", 0,
                ignored -> false, lookup);
        ClaimAtlasView.Row hiddenForPlayer = byKey(moderated.entries()).get(hiddenKey);
        assertEquals(ClaimAtlasView.Relation.MODERATED, hiddenForPlayer.relation());
        assertTrue(hiddenForPlayer.actionable());
    }

    @Test
    void availableUsesOnlyHubUnclaimedUnprotectedCoordinatesAndClampsPages() {
        PlatformSavedData state = new PlatformSavedData();
        ClaimKey claimed = key(1, 0);
        ClaimKey protectedKey = key(0, -1);
        purchase(state, id(20), claimed, 300);
        AtomicInteger checks = new AtomicInteger();
        java.util.function.Predicate<ClaimKey> protectedAt = key -> {
            checks.incrementAndGet();
            return key.equals(protectedKey);
        };

        ClaimAtlasView first = ClaimAtlasView.create(
                state, ORIGIN, ClaimAtlasView.Section.AVAILABLE, VIEWER, " ", 0,
                protectedAt, ignored -> Optional.empty());
        ClaimAtlasView last = ClaimAtlasView.create(
                state, ORIGIN, ClaimAtlasView.Section.AVAILABLE, VIEWER, "", 99,
                protectedAt, ignored -> Optional.empty());

        assertEquals(287, first.totalEntries());
        assertEquals(8, first.totalPages());
        assertEquals(0, first.page());
        assertEquals(7, last.page());
        assertEquals(35, last.entries().size());
        assertEquals(ORIGIN, first.entries().getFirst().key());
        assertTrue(first.entries().getFirst().current());
        assertTrue(first.entries().getFirst().actionable());
        assertFalse(first.entries().stream().anyMatch(row -> row.key().equals(claimed)));
        assertFalse(first.entries().stream().anyMatch(row -> row.key().equals(protectedKey)));
        assertTrue(first.entries().stream().allMatch(row -> row.expectedClaim().isEmpty()));
        assertThrows(UnsupportedOperationException.class, first.entries()::clear);
        assertThrows(IllegalArgumentException.class, () -> ClaimAtlasView.create(
                state, ORIGIN, ClaimAtlasView.Section.AVAILABLE, VIEWER, "owner", 0,
                protectedAt, ignored -> Optional.empty()));

        int beforeWilderness = checks.get();
        ClaimAtlasView wilderness = ClaimAtlasView.create(
                state, new ClaimKey(WorldTopology.WILDERNESS, 0, 0), ClaimAtlasView.Section.AVAILABLE,
                VIEWER, "", 0, protectedAt, ignored -> Optional.empty());
        assertTrue(wilderness.entries().isEmpty());
        assertEquals(beforeWilderness, checks.get());
        assertThrows(IllegalArgumentException.class, () -> ClaimAtlasView.create(
                state, ORIGIN, ClaimAtlasView.Section.NEARBY, VIEWER, "bad\nquery", 0,
                protectedAt, ignored -> Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> ClaimAtlasView.create(
                state, ORIGIN, ClaimAtlasView.Section.NEARBY, VIEWER,
                "x".repeat(ClaimAtlasView.MAX_QUERY_LENGTH + 1), 0,
                protectedAt, ignored -> Optional.empty()));
    }

    @Test
    void ownerIndexTracksPurchaseTransferSaleAndReloadInStableOrder() {
        PlatformSavedData state = new PlatformSavedData();
        UUID owner = id(30);
        UUID recipient = id(31);
        ClaimKey later = key(4, 4);
        ClaimKey earlier = key(-2, 3);
        purchase(state, owner, later, 400);
        purchase(state, owner, earlier, 410);

        List<Map.Entry<ClaimKey, Claim>> owned = state.claimsOwnedBy(owner, 10);
        List<ClaimKey> expectedOrder = List.of(earlier, later).stream()
                .sorted(java.util.Comparator.comparing(ClaimKey::auditTarget)).toList();
        assertEquals(expectedOrder, owned.stream().map(Map.Entry::getKey).toList());
        assertEquals(List.of(expectedOrder.getFirst()),
                state.claimsOwnedBy(owner, 1).stream().map(Map.Entry::getKey).toList());
        assertThrows(UnsupportedOperationException.class, owned::clear);

        assertEquals(ClaimManagementService.Status.SUCCESS, ClaimManagementService.offerTransfer(
                state, owner, earlier, recipient, "offer", 500, id(500)).status());
        assertEquals(ClaimManagementService.Status.SUCCESS, ClaimManagementService.acceptTransfer(
                state, recipient, earlier, ignored -> false, 64, "accept", 510, id(510)).status());
        assertEquals(List.of(later), state.claimsOwnedBy(owner, 10).stream().map(Map.Entry::getKey).toList());
        assertEquals(List.of(earlier),
                state.claimsOwnedBy(recipient, 10).stream().map(Map.Entry::getKey).toList());
        assertEquals(1, state.claimCount(owner));
        assertEquals(1, state.claimCount(recipient));

        assertEquals(ClaimManagementService.Status.SUCCESS, ClaimManagementService.sell(
                state, owner, later, 50, Long.MAX_VALUE, "sell", 520, id(520)).status());
        assertTrue(state.claimsOwnedBy(owner, 10).isEmpty());
        assertEquals(0, state.claimCount(owner));
        PlatformSavedData loaded = roundTrip(state);
        assertEquals(List.of(earlier),
                loaded.claimsOwnedBy(recipient, 10).stream().map(Map.Entry::getKey).toList());
        assertEquals(1, loaded.claimCount(recipient));
    }

    @Test
    void ownedPaginationIsDeterministicAndDistanceOrdered() {
        PlatformSavedData state = new PlatformSavedData();
        UUID owner = id(40);
        for (int index = 0; index < 40; index++) {
            purchase(state, owner, key(index + 1, index % 3), 600 + index);
        }

        ClaimAtlasView first = ClaimAtlasView.create(
                state, ORIGIN, ClaimAtlasView.Section.OWNED, owner, "", 1,
                ignored -> false, ignored -> Optional.of("Owner"));
        ClaimAtlasView second = ClaimAtlasView.create(
                state, ORIGIN, ClaimAtlasView.Section.OWNED, owner, "", 1,
                ignored -> false, ignored -> Optional.of("Owner"));

        assertEquals(1, first.page());
        assertEquals(2, first.totalPages());
        assertEquals(40, first.totalEntries());
        assertEquals(4, first.entries().size());
        assertEquals(first, second);
        assertFalse(first.truncated());
        assertTrue(first.entries().stream().allMatch(ClaimAtlasView.Row::actionable));
    }

    private static Map<ClaimKey, ClaimAtlasView.Row> byKey(List<ClaimAtlasView.Row> rows) {
        Map<ClaimKey, ClaimAtlasView.Row> result = new HashMap<>();
        rows.forEach(row -> result.put(row.key(), row));
        return Map.copyOf(result);
    }

    private static void purchase(PlatformSavedData state, UUID owner, ClaimKey key, long seed) {
        if (state.economyBalance(owner).isEmpty()) {
            assertEquals(EconomyService.TransactionStatus.SUCCESS, EconomyService.award(
                    state, owner, 100_000, "seed", seed - 1, id(seed + 100_000), 0, Long.MAX_VALUE).status());
        }
        assertEquals(ClaimPurchaseService.Status.SUCCESS, ClaimPurchaseService.purchase(
                state, owner, Level.OVERWORLD, key.dimension(), key.auditPosition(),
                ignored -> true, ignored -> false, 1_000, 0, 100,
                seed, id(seed)).status());
    }

    private static void restrict(PlatformSavedData state, UUID owner, ClaimKey key, long seed) {
        assertEquals(ClaimManagementService.Status.SUCCESS, ClaimManagementService.setSettings(
                state, owner, false, key, new ClaimSettings(true, false),
                "restricted", seed, id(seed)).status());
    }

    private static PlatformSavedData roundTrip(PlatformSavedData state) {
        var encoded = PlatformSavedData.CODEC.encodeStart(NbtOps.INSTANCE, state).getOrThrow();
        return PlatformSavedData.CODEC.parse(NbtOps.INSTANCE, encoded).getOrThrow();
    }

    private static ClaimKey key(int chunkX, int chunkZ) {
        return new ClaimKey(Level.OVERWORLD, chunkX, chunkZ);
    }

    private static UUID id(long value) {
        return new UUID(0, value);
    }
}
