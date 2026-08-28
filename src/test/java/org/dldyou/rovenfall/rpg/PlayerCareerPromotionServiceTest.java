package org.dldyou.rovenfall.rpg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.UUID;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

final class PlayerCareerPromotionServiceTest {
    @Test
    void promotionPaymentIdentityIsStableForRecoveryAndScopedPerCareer() {
        UUID player = UUID.randomUUID();
        Identifier warrior = Identifier.fromNamespaceAndPath("rovenfall", "warrior");
        Identifier guardian = Identifier.fromNamespaceAndPath("rovenfall", "guardian");

        assertEquals(
                PlayerCareerPromotionService.transactionId(player, warrior),
                PlayerCareerPromotionService.transactionId(player, warrior));
        assertNotEquals(
                PlayerCareerPromotionService.transactionId(player, warrior),
                PlayerCareerPromotionService.transactionId(player, guardian));
        assertNotEquals(
                PlayerCareerPromotionService.transactionId(player, warrior),
                PlayerCareerPromotionService.transactionId(UUID.randomUUID(), warrior));
    }
}
