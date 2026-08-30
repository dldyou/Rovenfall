package org.dldyou.rovenfall.administration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.Test;

final class AdministrationGeneratedIdentifierTest {
    @Test
    void derivesStableOpaqueIdsFromTheServerTransaction() {
        UUID first = UUID.fromString("12345678-1234-5678-9abc-def012345678");
        UUID second = UUID.fromString("12345678-1234-5678-9abc-def012345679");

        var shop = AdministrationGeneratedIdentifier.fromTransaction("shop", first);
        assertEquals("rovenfall:managed/shop/12345678123456789abcdef012345678", shop.toString());
        assertEquals(shop, AdministrationGeneratedIdentifier.fromTransaction("SHOP", first));
        assertNotEquals(shop, AdministrationGeneratedIdentifier.fromTransaction("shop", second));
        assertNotEquals(shop, AdministrationGeneratedIdentifier.fromTransaction("offer", first));
    }

    @Test
    void rejectsUnboundedOrInvalidKinds() {
        UUID transactionId = UUID.randomUUID();
        assertThrows(NullPointerException.class,
                () -> AdministrationGeneratedIdentifier.fromTransaction(null, transactionId));
        assertThrows(NullPointerException.class,
                () -> AdministrationGeneratedIdentifier.fromTransaction("shop", null));
        assertThrows(IllegalArgumentException.class,
                () -> AdministrationGeneratedIdentifier.fromTransaction("../shop", transactionId));
        assertThrows(IllegalArgumentException.class,
                () -> AdministrationGeneratedIdentifier.fromTransaction("x".repeat(33), transactionId));
    }
}
