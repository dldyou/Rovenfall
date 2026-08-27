package org.dldyou.rovenfall.administration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

final class BossAdministrationServiceTest {
    @Test
    void everyAdministratorCanViewButOnlyOwnerCanRecover() {
        for (AdminRole role : AdminRole.values()) {
            PlatformSavedData state = new PlatformSavedData();
            UUID actor = id(role.ordinal() + 1);
            AdministrationService.changeRole(
                    state, AdministrationService.SYSTEM_ACTOR, true, actor,
                    role.getSerializedName(), "test role", 1_000, id(100 + role.ordinal()));

            assertTrue(BossAdministrationService.canView(state, actor, false));
            assertTrue(BossAdministrationService.canRecover(state, actor, false) == (role == AdminRole.OWNER));
        }
    }

    @Test
    void nativeOwnerOverrideDoesNotGrantOrdinaryViewersMutationAccess() {
        PlatformSavedData state = new PlatformSavedData();
        UUID stranger = id(20);

        assertFalse(BossAdministrationService.canView(state, stranger, false));
        assertFalse(BossAdministrationService.canRecover(state, stranger, false));
        assertTrue(BossAdministrationService.canView(state, stranger, true));
        assertTrue(BossAdministrationService.canRecover(state, stranger, true));
    }

    private static UUID id(long value) {
        return new UUID(0L, value);
    }
}
