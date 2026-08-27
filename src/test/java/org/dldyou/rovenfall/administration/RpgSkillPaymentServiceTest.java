package org.dldyou.rovenfall.administration;

import static org.dldyou.rovenfall.PersistenceTestHarness.roundTrip;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.dldyou.rovenfall.rpg.SkillResetPlan;
import org.junit.jupiter.api.Test;

final class RpgSkillPaymentServiceTest {
    private static final UUID PLAYER = uuid(1);
    private static final SkillResetPlan PLAN = new SkillResetPlan(
            SkillResetPlan.Mode.BRANCH, id("strike"),
            List.of(new SkillResetPlan.RemovedSkill(id("strike"), id("warrior"), 1, 2)));

    @Test
    void paymentAndExactPlanSurviveRestartAndCompleteIdempotently() {
        PlatformSavedData state = fundedState();
        UUID transaction = uuid(100);

        var paid = RpgSkillPaymentService.begin(
                state, PLAYER, PLAN, 500, 2_000, transaction, 0, 10_000);
        assertEquals(RpgSkillPaymentService.Status.SUCCESS, paid.status());
        assertEquals(1_500, state.economyBalance(PLAYER).orElseThrow());
        assertEquals(EconomyTransactionReceipt.Kind.RPG_SKILL_PAYMENT,
                state.economyReceipt(transaction).orElseThrow().kind());
        assertEquals(RpgSkillOperation.Phase.PENDING,
                state.rpgSkillOperation(transaction).orElseThrow().phase());

        state = roundTrip(PlatformSavedData.CODEC, state);
        assertEquals(Optional.of(PLAN), state.rpgSkillOperation(transaction).orElseThrow().plan());
        assertEquals(RpgSkillPaymentService.Status.DUPLICATE_PENDING,
                RpgSkillPaymentService.begin(
                        state, PLAYER, PLAN, 500, 2_000, transaction, 0, 10_000).status());
        assertEquals(1_500, state.economyBalance(PLAYER).orElseThrow());

        assertEquals(RpgSkillPaymentService.Status.SUCCESS,
                RpgSkillPaymentService.complete(state, PLAYER, transaction, 2_100).status());
        assertEquals(RpgSkillPaymentService.Status.DUPLICATE_COMPLETED,
                RpgSkillPaymentService.complete(state, PLAYER, transaction, 2_200).status());
        var audits = state.auditPage(0, 2).entries();
        assertEquals(id("rpg_skill_reset_completed"), audits.getFirst().actionType());
        assertEquals(transaction, audits.getFirst().transactionId());
        assertEquals(id("rpg_skill_reset_payment"), audits.getLast().actionType());
        assertEquals("2000", audits.getLast().beforeValue());
        assertEquals("1500", audits.getLast().afterValue());
        assertEquals(RpgSkillOperation.Phase.COMPLETED,
                roundTrip(PlatformSavedData.CODEC, state)
                        .rpgSkillOperation(transaction).orElseThrow().phase());
    }

    @Test
    void genericEconomyReversalCannotRefundSkillResetPayment() {
        PlatformSavedData state = fundedState();
        UUID transaction = uuid(200);
        assertEquals(RpgSkillPaymentService.Status.SUCCESS,
                RpgSkillPaymentService.begin(
                        state, PLAYER, PLAN, 500, 2_000, transaction, 0, 10_000).status());
        assertEquals(RpgSkillPaymentService.Status.SUCCESS,
                RpgSkillPaymentService.complete(state, PLAYER, transaction, 2_100).status());

        var reversed = EconomyReversalService.reverse(
                state, PLAYER, NonNullList.withSize(Inventory.INVENTORY_SIZE, ItemStack.EMPTY),
                AdministrationService.SYSTEM_ACTOR, true,
                transaction, EconomyTransactionReceipt.CompensationDecision.NONE, "test", 3_000,
                uuid(201), 10_000);

        assertEquals(EconomyReversalService.Status.ORIGINAL_NOT_REVERSIBLE, reversed.status());
        assertEquals(1_500, state.economyBalance(PLAYER).orElseThrow());
        assertTrue(state.economyReceipt(transaction).orElseThrow().reversedBy().isEmpty());
        assertEquals(RpgSkillOperation.Phase.COMPLETED,
                roundTrip(PlatformSavedData.CODEC, state)
                        .rpgSkillOperation(transaction).orElseThrow().phase());
    }

    @Test
    void completedRpgEvidenceCanRecoverAPlatformPaymentWithoutAResetPlan() {
        PlatformSavedData state = fundedState();
        UUID transaction = uuid(300);

        var recovered = RpgSkillPaymentService.recoverCompleted(
                state, PLAYER, PLAN.mode(), PLAN.target(), 500, 2_000, transaction, 0, 10_000);

        assertEquals(RpgSkillPaymentService.Status.SUCCESS, recovered.status());
        assertEquals(1_500, state.economyBalance(PLAYER).orElseThrow());
        RpgSkillOperation operation = state.rpgSkillOperation(transaction).orElseThrow();
        assertEquals(RpgSkillOperation.Phase.COMPLETED, operation.phase());
        assertTrue(operation.plan().isEmpty());
        assertEquals(id("rpg_skill_reset_payment_recovered"),
                state.auditPage(0, 1).entries().getFirst().actionType());
        assertEquals(RpgSkillPaymentService.Status.DUPLICATE_COMPLETED,
                RpgSkillPaymentService.recoverCompleted(
                        state, PLAYER, PLAN.mode(), PLAN.target(), 500, 2_000,
                        transaction, 0, 10_000).status());
    }

    @Test
    void insufficientFundsAndTransactionReuseChangeNothing() {
        PlatformSavedData state = fundedState();
        UUID transaction = uuid(400);
        assertEquals(RpgSkillPaymentService.Status.INSUFFICIENT_FUNDS,
                RpgSkillPaymentService.begin(
                        state, PLAYER, PLAN, 2_001, 2_000, transaction, 0, 10_000).status());
        assertTrue(state.rpgSkillOperation(transaction).isEmpty());
        assertEquals(2_000, state.economyBalance(PLAYER).orElseThrow());
        assertEquals(id("rpg_skill_reset_payment_denied"),
                state.auditPage(0, 1).entries().getFirst().actionType());

        assertEquals(RpgSkillPaymentService.Status.SUCCESS,
                RpgSkillPaymentService.begin(
                        state, PLAYER, PLAN, 500, 2_000, transaction, 0, 10_000).status());
        SkillResetPlan other = new SkillResetPlan(
                SkillResetPlan.Mode.FULL, id("warrior"), PLAN.removedSkills());
        assertEquals(RpgSkillPaymentService.Status.TRANSACTION_CONFLICT,
                RpgSkillPaymentService.begin(
                        state, PLAYER, other, 500, 2_000, transaction, 0, 10_000).status());
        assertEquals(1_500, state.economyBalance(PLAYER).orElseThrow());
    }

    @Test
    void schemaTenMigratesAndBrokenOperationEvidenceLoadsReadOnly() {
        PlatformSavedData empty = new PlatformSavedData();
        CompoundTag schemaTen = (CompoundTag) PlatformSavedData.CODEC
                .encodeStart(NbtOps.INSTANCE, empty).getOrThrow();
        schemaTen.putInt("schema_version", 10);
        schemaTen.remove("rpg_skill_operations");
        PlatformSavedData migrated = PlatformSavedData.CODEC.parse(NbtOps.INSTANCE, schemaTen).getOrThrow();
        assertEquals(PlatformSavedData.CURRENT_SCHEMA_VERSION, migrated.schemaVersion());
        assertTrue(migrated.isWritable());

        PlatformSavedData paid = fundedState();
        UUID transaction = uuid(500);
        assertEquals(RpgSkillPaymentService.Status.SUCCESS,
                RpgSkillPaymentService.begin(
                        paid, PLAYER, PLAN, 500, 2_000, transaction, 0, 10_000).status());
        CompoundTag broken = (CompoundTag) PlatformSavedData.CODEC
                .encodeStart(NbtOps.INSTANCE, paid).getOrThrow();
        broken.remove("economy_receipts");
        PlatformSavedData readOnly = PlatformSavedData.CODEC.parse(NbtOps.INSTANCE, broken).getOrThrow();
        assertTrue(!readOnly.isWritable());
        assertEquals(RpgSkillPaymentService.Status.READ_ONLY,
                RpgSkillPaymentService.complete(readOnly, PLAYER, transaction, 3_000).status());
    }

    @Test
    void operationCodecRejectsFreePendingPaymentAndPendingWithoutPlan() {
        RpgSkillOperation free = new RpgSkillOperation(
                PLAYER, PLAN.mode(), PLAN.target(), 0, 1_000,
                Optional.of(PLAN), RpgSkillOperation.Phase.PENDING);
        RpgSkillOperation planless = new RpgSkillOperation(
                PLAYER, PLAN.mode(), PLAN.target(), 500, 1_000,
                Optional.empty(), RpgSkillOperation.Phase.PENDING);

        assertTrue(RpgSkillOperation.CODEC.encodeStart(NbtOps.INSTANCE, free).error().isPresent());
        assertTrue(RpgSkillOperation.CODEC.encodeStart(NbtOps.INSTANCE, planless).error().isPresent());
    }

    private static PlatformSavedData fundedState() {
        PlatformSavedData state = new PlatformSavedData();
        assertEquals(EconomyService.TransactionStatus.SUCCESS,
                EconomyService.award(state, PLAYER, 2_000, "seed", 1_000, uuid(10), 0, 10_000).status());
        return state;
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("rovenfall", path);
    }

    private static UUID uuid(long least) {
        return new UUID(0L, least);
    }
}
