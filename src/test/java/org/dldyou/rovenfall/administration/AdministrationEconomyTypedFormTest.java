package org.dldyou.rovenfall.administration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

final class AdministrationEconomyTypedFormTest {
    private static final UUID TRANSACTION = new UUID(0L, 42L);

    @Test
    void generatedShopAndOfferIdentifiersStayServerOwned() {
        var shop = AdministrationEconomyTypedForm.legacy(
                AdministrationFormType.ECONOMY_SHOP_CREATE, List.of("create"), TRANSACTION,
                Identifier.parse("rovenfall:starter"), null, null);
        var offer = AdministrationEconomyTypedForm.legacy(
                AdministrationFormType.ECONOMY_OFFER_UPSERT,
                List.of("both", "5", "7", "finite", "2", "9", "1", "stock"), TRANSACTION,
                null, Identifier.parse("minecraft:bread"), null);

        assertTrue(shop.orElseThrow().startsWith("rovenfall:managed/shop/"));
        assertTrue(offer.orElseThrow().startsWith("rovenfall:managed/offer/"));
    }

    @Test
    void rejectsInvalidDirectionPriceAndStockCombinations() {
        assertFalse(AdministrationEconomyTypedForm.legacy(
                AdministrationFormType.ECONOMY_OFFER_UPSERT,
                List.of("buy", "", "", "unlimited", "", "", "1", "reason"), TRANSACTION,
                null, Identifier.parse("minecraft:bread"), null).isPresent());
        assertFalse(AdministrationEconomyTypedForm.legacy(
                AdministrationFormType.ECONOMY_OFFER_UPSERT,
                List.of("sell", "", "7", "finite", "9", "2", "1", "reason"), TRANSACTION,
                null, Identifier.parse("minecraft:bread"), null).isPresent());
        assertFalse(AdministrationEconomyTypedForm.legacy(
                AdministrationFormType.ECONOMY_GRANT, List.of("1", ""), TRANSACTION,
                null, null, null).isPresent());
    }
}
