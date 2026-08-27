package org.dldyou.rovenfall.administration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class PlayerShopMenuTest {
    @Test
    void quantityControlsStayInsideTheAuthoritativeTradeBounds() {
        assertEquals(1, PlayerShopMenu.adjustedQuantity(1, -10));
        assertEquals(2, PlayerShopMenu.adjustedQuantity(1, 1));
        assertEquals(11, PlayerShopMenu.adjustedQuantity(1, 10));
        assertEquals(ShopTradeService.MAX_TRADE_QUANTITY,
                PlayerShopMenu.adjustedQuantity(ShopTradeService.MAX_TRADE_QUANTITY, 10));
    }

    @Test
    void everyRejectedTradeStatusHasALocalizedPlayerMessage() {
        for (ShopTradeService.Status status : ShopTradeService.Status.values()) {
            if (status == ShopTradeService.Status.SUCCESS || status == ShopTradeService.Status.DUPLICATE_TRANSACTION) {
                continue;
            }
            assertTrue(PlayerShopMenu.errorTranslationKey(status).startsWith("command.rovenfall.shop."), status.name());
        }
    }
}
