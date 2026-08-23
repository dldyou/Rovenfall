package org.dldyou.rovenfall.administration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

final class AdminGuiAuthorizationTest {
    @Test
    void everyConfiguredRoleIncludingViewerCanOpenWhileOrdinaryPlayersCannot() {
        for (AdminRole role : AdminRole.values()) {
            PlatformSavedData state = new PlatformSavedData();
            UUID admin = id(role.ordinal() + 1);
            AdministrationService.changeRole(
                    state,
                    AdministrationService.SYSTEM_ACTOR,
                    true,
                    admin,
                    role.getSerializedName(),
                    "bootstrap",
                    1_000,
                    id(role.ordinal() + 101));

            assertTrue(RovenfallCommands.canUseAdministration(state, admin, false));
            assertFalse(RovenfallCommands.canUseAdministration(state, id(999), true));
        }
    }

    @Test
    void nativeOwnerCanBootstrapOnlyBeforeRolesAreConfigured() {
        PlatformSavedData state = new PlatformSavedData();
        UUID player = id(1);
        assertTrue(RovenfallCommands.canUseAdministration(state, player, true));
        assertFalse(RovenfallCommands.canUseAdministration(state, player, false));
    }

    private static UUID id(long value) {
        return new UUID(0, value);
    }
}
