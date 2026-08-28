package org.dldyou.rovenfall.administration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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

    private static RpgPlayerState state(long xp) {
        return new RpgPlayerState(
                Map.of(Identifier.fromNamespaceAndPath("rovenfall", "combat"), xp),
                Map.of(), Optional.empty(), Map.of(), Map.of(), Set.of(), List.of(), List.of());
    }
}
