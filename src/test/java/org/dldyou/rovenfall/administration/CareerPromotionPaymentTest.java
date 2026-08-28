package org.dldyou.rovenfall.administration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;
import java.util.stream.IntStream;
import net.minecraft.nbt.NbtOps;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.Test;

final class CareerPromotionPaymentTest {
    @Test
    void paymentIsIdempotentAuditedAndNotAGenericDebitReceipt() {
        PlatformSavedData state = new PlatformSavedData();
        UUID player = UUID.randomUUID();
        UUID transaction = UUID.randomUUID();
        assertEquals(EconomyService.TransactionStatus.SUCCESS,
                EconomyService.createAccount(state, player, 500, 1_000, 1, UUID.randomUUID()).status());

        EconomyService.TransactionResult first = EconomyService.payCareerPromotion(
                state, player, 100, "career_promotion:rovenfall:warrior", 2, transaction, 0, 1_000);
        EconomyService.TransactionResult replay = EconomyService.payCareerPromotion(
                state, player, 100, "career_promotion:rovenfall:warrior", 3, transaction, 0, 1_000);

        assertEquals(EconomyService.TransactionStatus.SUCCESS, first.status());
        assertEquals(400, first.balance());
        assertEquals(EconomyService.TransactionStatus.DUPLICATE_TRANSACTION, replay.status());
        assertEquals(400, replay.balance());
        assertEquals(EconomyTransactionReceipt.Kind.CAREER_PROMOTION_PAYMENT,
                state.economyReceipt(transaction).orElseThrow().kind());
        assertEquals("rovenfall:career_promotion_payment",
                state.auditTransaction(transaction).orElseThrow().actionType().toString());

        PlatformSavedData loaded = PlatformSavedData.CODEC.parse(
                NbtOps.INSTANCE, PlatformSavedData.CODEC.encodeStart(NbtOps.INSTANCE, state)
                        .getOrThrow()).getOrThrow();
        assertEquals(EconomyTransactionReceipt.Kind.CAREER_PROMOTION_PAYMENT,
                loaded.economyReceipt(transaction).orElseThrow().kind());

        EconomyReversalService.Result reversal = EconomyReversalService.reverse(
                loaded, player,
                IntStream.range(0, Inventory.INVENTORY_SIZE).mapToObj(ignored -> ItemStack.EMPTY).toList(),
                UUID.randomUUID(), true, transaction,
                EconomyTransactionReceipt.CompensationDecision.NONE,
                "must use owning RPG compensation", 4, UUID.randomUUID(), 1_000);
        assertEquals(EconomyReversalService.Status.ORIGINAL_NOT_REVERSIBLE, reversal.status());
        assertEquals(400, loaded.economyBalance(player).orElseThrow());
    }
}
