package org.dldyou.rovenfall.administration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import org.dldyou.rovenfall.claims.ClaimKey;
import org.dldyou.rovenfall.claims.ClaimRole;
import org.dldyou.rovenfall.rpg.RpgPlayerState;
import org.junit.jupiter.api.Test;

final class PlayerDashboardMenuTest {
    @Test
    void buildsAReadOnlySnapshotFromAuthoritativeDomainState() {
        UUID player = id(1);
        PlatformSavedData platform = new PlatformSavedData();
        assertEquals(EconomyService.TransactionStatus.SUCCESS,
                EconomyService.award(platform, player, 5_000, "dashboard test", 1_000, id(10), 0, Long.MAX_VALUE)
                        .status());
        BlockPos position = new BlockPos(31, 70, -1);
        assertEquals(ClaimPurchaseService.Status.SUCCESS, ClaimPurchaseService.purchase(
                platform, player, Level.OVERWORLD, Level.OVERWORLD, position,
                ignored -> true, ignored -> false, 1_000, 250, 64, 2_000, id(11)).status());

        Identifier activity = Identifier.fromNamespaceAndPath("rovenfall", "combat");
        Identifier career = Identifier.fromNamespaceAndPath("rovenfall", "warrior");
        Identifier skill = Identifier.fromNamespaceAndPath("rovenfall", "power_strike");
        RpgPlayerState rpg = new RpgPlayerState(
                Map.of(activity, 100L),
                Map.of(career, new RpgPlayerState.CareerProgress(200, 1, 2, Map.of(skill, 1))),
                Optional.of(career),
                Map.of(0, skill),
                Map.of(),
                Set.of(),
                List.of(),
                List.of(),
                0L);

        PlayerDashboardMenu.DashboardSnapshot snapshot = PlayerDashboardMenu.snapshot(
                platform, rpg, player, ClaimKey.at(Level.OVERWORLD, position));

        assertEquals(4_000, snapshot.balance());
        assertTrue(snapshot.hasEconomyAccount());
        assertEquals(1, snapshot.ownedClaims());
        assertEquals(Optional.of(player), snapshot.claimOwner());
        assertEquals(Optional.of(ClaimRole.OWNER), snapshot.claimRole());
        assertEquals(Optional.of(career), snapshot.activeCareer());
        assertEquals(1, snapshot.activityTracks());
        assertEquals(1, snapshot.learnedCareers());
        assertEquals(1, snapshot.learnedSkills());
        assertEquals(List.of(Optional.of(skill), Optional.empty(), Optional.empty(), Optional.empty()),
                snapshot.activeSkills());
    }

    @Test
    void mapsOnlyDeclaredSlotsAndBoundsClicksToOncePerTick() {
        assertEquals(PlayerDashboardMenu.Action.OPEN_ECONOMY,
                PlayerDashboardMenu.actionAt(PlayerDashboardMenu.Page.HOME, 10));
        assertEquals(PlayerDashboardMenu.Action.OPEN_CLAIMS,
                PlayerDashboardMenu.actionAt(PlayerDashboardMenu.Page.HOME, 13));
        assertEquals(PlayerDashboardMenu.Action.OPEN_RPG,
                PlayerDashboardMenu.actionAt(PlayerDashboardMenu.Page.HOME, 16));
        assertEquals(PlayerDashboardMenu.Action.OPEN_SHOPS,
                PlayerDashboardMenu.actionAt(PlayerDashboardMenu.Page.ECONOMY, 15));
        assertEquals(PlayerDashboardMenu.Action.BACK,
                PlayerDashboardMenu.actionAt(PlayerDashboardMenu.Page.CLAIMS, 18));
        assertEquals(PlayerDashboardMenu.Action.UNAVAILABLE,
                PlayerDashboardMenu.actionAt(PlayerDashboardMenu.Page.RPG, 24));
        assertEquals(PlayerDashboardMenu.Action.NONE,
                PlayerDashboardMenu.actionAt(PlayerDashboardMenu.Page.HOME, 0));

        assertTrue(PlayerDashboardMenu.canHandleClick(Long.MIN_VALUE, 100));
        assertFalse(PlayerDashboardMenu.canHandleClick(100, 100));
        assertTrue(PlayerDashboardMenu.canHandleClick(100, 101));
    }

    private static UUID id(long value) {
        return new UUID(0, value);
    }
}
