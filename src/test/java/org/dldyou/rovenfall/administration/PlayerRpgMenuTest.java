package org.dldyou.rovenfall.administration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.resources.Identifier;
import org.dldyou.rovenfall.rpg.RpgPlayerState;
import org.junit.jupiter.api.Test;

final class PlayerRpgMenuTest {
    @Test
    void confirmationRequiresTheSameDefinitionRevisionAndAuthoritativeState() {
        RpgPlayerState rendered = state(10);

        assertTrue(PlayerRpgMenu.canConfirm(4, rendered, 4, rendered));
        assertFalse(PlayerRpgMenu.canConfirm(4, rendered, 5, rendered));
        assertFalse(PlayerRpgMenu.canConfirm(4, rendered, 4, state(11)));
        assertFalse(PlayerRpgMenu.canConfirm(0, rendered, 0, rendered));
    }

    @Test
    void pagingNeverEscapesTheCurrentAuthoritativeEntrySet() {
        assertTrue(PlayerRpgMenu.boundedPage(-1, 0) == 0);
        assertTrue(PlayerRpgMenu.boundedPage(99, PlayerRpgMenu.PAGE_SIZE + 1) == 1);
        assertTrue(PlayerRpgMenu.boundedPage(0, PlayerRpgMenu.PAGE_SIZE) == 0);
    }

    @Test
    void durablePartialOperationsAreShownAsRecoveryPending() {
        assertTrue(PlayerRpgMenu.resultKey(false, "RPG_FAILED").endsWith("pending"));
        assertTrue(PlayerRpgMenu.resultKey(false, "COMPLETION_FAILED").endsWith("pending"));
        assertTrue(PlayerRpgMenu.resultKey(false, "PAYMENT_FAILED").endsWith("failed"));
    }

    @Test
    void confirmationRejectsChangedBalanceOrConfiguredCost() {
        assertTrue(PlayerRpgMenu.canConfirmEconomy(1_000, 100, 1_000, 100));
        assertFalse(PlayerRpgMenu.canConfirmEconomy(1_000, 100, 900, 100));
        assertFalse(PlayerRpgMenu.canConfirmEconomy(1_000, 100, 1_000, 200));
    }

    @Test
    void staleContainerOrServerIssuedStateCannotReplayAnAction() {
        assertTrue(PlayerRpgMenu.isCurrentSession(7, 101, 7, 101));
        assertFalse(PlayerRpgMenu.isCurrentSession(7, 101, 8, 101));
        assertFalse(PlayerRpgMenu.isCurrentSession(7, 101, 7, 100));
        int stateId = PlayerRpgMenu.sessionStateId(UUID.randomUUID());
        assertTrue(stateId >= 1 && stateId <= 32_767);
    }

    private static RpgPlayerState state(long xp) {
        return new RpgPlayerState(
                Map.of(Identifier.fromNamespaceAndPath("rovenfall", "combat"), xp),
                Map.of(), Optional.empty(), Map.of(), Map.of(), Set.of(), List.of(), List.of());
    }
}
