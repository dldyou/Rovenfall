package org.dldyou.rovenfall.administration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.UUID;
import java.util.List;
import java.util.stream.IntStream;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.Test;
import org.dldyou.rovenfall.rpg.RpgItemCost;

final class CareerPromotionPaymentTest {
    @Test
    void paymentIsDurableIdempotentAndNotGenericallyReversible() {
        PlatformSavedData state = new PlatformSavedData();
        UUID player = UUID.randomUUID();
        UUID transaction = UUID.randomUUID();
        Identifier career = Identifier.fromNamespaceAndPath("rovenfall", "warrior");
        assertEquals(EconomyService.TransactionStatus.SUCCESS,
                EconomyService.createAccount(state, player, 500, 1_000, 1, UUID.randomUUID()).status());

        CareerPromotionPaymentService.Result first = CareerPromotionPaymentService.begin(
                state, player, career, 100, 2, transaction, 0, 1_000);
        CareerPromotionPaymentService.Result replay = CareerPromotionPaymentService.begin(
                state, player, career, 100, 3, transaction, 0, 1_000);

        assertEquals(CareerPromotionPaymentService.Status.SUCCESS, first.status());
        assertEquals(400, first.balance());
        assertEquals(CareerPromotionPaymentService.Status.DUPLICATE_PENDING, replay.status());
        assertEquals(400, replay.balance());
        assertEquals(EconomyTransactionReceipt.Kind.CAREER_PROMOTION_PAYMENT,
                state.economyReceipt(transaction).orElseThrow().kind());
        assertEquals(RpgSkillOperation.Kind.CAREER_PROMOTION,
                state.rpgSkillOperation(transaction).orElseThrow().kind());
        assertEquals(RpgSkillOperation.Phase.PENDING,
                state.rpgSkillOperation(transaction).orElseThrow().phase());
        assertEquals("rovenfall:career_promotion_payment",
                state.auditTransaction(transaction).orElseThrow().actionType().toString());

        PlatformSavedData loaded = PlatformSavedData.CODEC.parse(
                NbtOps.INSTANCE, PlatformSavedData.CODEC.encodeStart(NbtOps.INSTANCE, state)
                        .getOrThrow()).getOrThrow();
        assertEquals(EconomyTransactionReceipt.Kind.CAREER_PROMOTION_PAYMENT,
                loaded.economyReceipt(transaction).orElseThrow().kind());
        assertEquals(career, loaded.rpgSkillOperation(transaction).orElseThrow().target());
        assertEquals(CareerPromotionPaymentService.Status.SUCCESS,
                CareerPromotionPaymentService.complete(loaded, player, transaction, 3).status());
        assertEquals(CareerPromotionPaymentService.Status.DUPLICATE_COMPLETED,
                CareerPromotionPaymentService.complete(loaded, player, transaction, 4).status());

        EconomyReversalService.Result reversal = EconomyReversalService.reverse(
                loaded, player,
                IntStream.range(0, Inventory.INVENTORY_SIZE).mapToObj(ignored -> ItemStack.EMPTY).toList(),
                UUID.randomUUID(), true, transaction,
                EconomyTransactionReceipt.CompensationDecision.NONE,
                "must use owning RPG compensation", 5, UUID.randomUUID(), 1_000);
        assertEquals(EconomyReversalService.Status.ORIGINAL_NOT_REVERSIBLE, reversal.status());
        assertEquals(400, loaded.economyBalance(player).orElseThrow());
    }

    @Test
    void mismatchedPaymentActorMakesPersistedPlatformReadOnly() {
        PlatformSavedData state = new PlatformSavedData();
        UUID player = UUID.randomUUID();
        UUID transaction = UUID.randomUUID();
        Identifier career = Identifier.fromNamespaceAndPath("rovenfall", "warrior");
        assertEquals(EconomyService.TransactionStatus.SUCCESS,
                EconomyService.createAccount(state, player, 500, 1_000, 1, UUID.randomUUID()).status());
        assertEquals(CareerPromotionPaymentService.Status.SUCCESS,
                CareerPromotionPaymentService.begin(
                        state, player, career, 100, 2, transaction, 0, 1_000).status());

        CompoundTag encoded = (CompoundTag) PlatformSavedData.CODEC
                .encodeStart(NbtOps.INSTANCE, state).getOrThrow();
        ListTag receipts = encoded.getListOrEmpty("economy_receipts");
        CompoundTag receipt = IntStream.range(0, receipts.size())
                .mapToObj(receipts::getCompoundOrEmpty)
                .filter(entry -> entry.getStringOr("id", "").equals(transaction.toString()))
                .findFirst().orElseThrow().getCompoundOrEmpty("receipt");
        receipt.putString("actor", UUID.randomUUID().toString());

        PlatformSavedData loaded = PlatformSavedData.CODEC.parse(NbtOps.INSTANCE, encoded).getOrThrow();
        assertFalse(loaded.isWritable());
    }

    @Test
    void itemOnlyPromotionHasDurableExactEvidence() {
        PlatformSavedData state = new PlatformSavedData();
        UUID player = UUID.randomUUID();
        UUID transaction = UUID.randomUUID();
        Identifier career = Identifier.fromNamespaceAndPath("rovenfall", "item_only");
        List<RpgItemCost> items = List.of(
                new RpgItemCost(Identifier.parse("minecraft:iron_ingot"), 8));
        assertEquals(EconomyService.TransactionStatus.SUCCESS,
                EconomyService.createAccount(state, player, 500, 1_000, 1, UUID.randomUUID()).status());

        CareerPromotionPaymentService.Result payment = CareerPromotionPaymentService.begin(
                state, player, career, 0, items, List.of(8L), List.of(0L),
                2, transaction, 0, 1_000);

        assertEquals(CareerPromotionPaymentService.Status.SUCCESS, payment.status());
        assertEquals(500, payment.balance());
        assertEquals(items, payment.operation().orElseThrow().itemCosts());
        assertEquals(List.of(8L), payment.operation().orElseThrow().itemCountsBefore());
        assertEquals(List.of(0L), payment.operation().orElseThrow().itemCountsAfter());
        assertEquals(RpgSkillOperation.Phase.ITEMS_CONSUMED,
                payment.operation().orElseThrow().phase());
        PlatformSavedData loaded = PlatformSavedData.CODEC.parse(
                NbtOps.INSTANCE, PlatformSavedData.CODEC.encodeStart(NbtOps.INSTANCE, state)
                        .getOrThrow()).getOrThrow();
        assertEquals(items, loaded.rpgSkillOperation(transaction).orElseThrow().itemCosts());
        assertEquals(List.of(8L), loaded.rpgSkillOperation(transaction).orElseThrow().itemCountsBefore());
        assertEquals(CareerPromotionPaymentService.Status.SUCCESS,
                CareerPromotionPaymentService.complete(loaded, player, transaction, 3).status());
    }
}
