package org.dldyou.rovenfall.administration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class RovenfallMenuIdentityCacheTest {
    @Test
    void consumesOnlyAnExactServerIssuedMenuIdentity() {
        var cache = new RovenfallMenuIdentityCache();
        assertTrue(cache.accept(new PlayerMenuNetwork.MenuIdentity(
                7, 101, PlayerMenuNetwork.MenuKind.ADMIN_WORLD)));
        assertTrue(cache.consume(8, 101).isEmpty());

        assertTrue(cache.accept(new PlayerMenuNetwork.MenuIdentity(
                7, 101, PlayerMenuNetwork.MenuKind.ADMIN_WORLD)));
        assertTrue(cache.consume(7, 100).isEmpty());

        assertTrue(cache.accept(new PlayerMenuNetwork.MenuIdentity(
                7, 101, PlayerMenuNetwork.MenuKind.ADMIN_WORLD)));
        assertEquals(PlayerMenuNetwork.MenuKind.ADMIN_WORLD, cache.consume(7, 101).orElseThrow());
        assertTrue(cache.consume(7, 101).isEmpty());
    }

    @Test
    void rejectsMalformedOrIncompatibleIdentities() {
        var cache = new RovenfallMenuIdentityCache();
        assertFalse(cache.accept(null));
        assertFalse(cache.accept(new PlayerMenuNetwork.MenuIdentity(
                PlayerMenuNetwork.PACKET_REVISION + 1, 7, 101,
                PlayerMenuNetwork.MenuKind.DASHBOARD.wireId())));
        assertFalse(cache.accept(new PlayerMenuNetwork.MenuIdentity(
                PlayerMenuNetwork.PACKET_REVISION, -1, 101,
                PlayerMenuNetwork.MenuKind.DASHBOARD.wireId())));
        assertFalse(cache.accept(new PlayerMenuNetwork.MenuIdentity(
                PlayerMenuNetwork.PACKET_REVISION, 7, 101, 99)));
        assertTrue(cache.consume(7, 101).isEmpty());
    }
}
