package org.dldyou.rovenfall.administration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import org.junit.jupiter.api.Test;

final class EconomyReceiptPersistenceTest {
    @Test
    void schemaFourMigratesWithEmptyReceiptAndAlertState() {
        PlatformSavedData current = new PlatformSavedData();
        EconomyService.award(current, id(1), 10, "seed", 1_000, id(100), 0, Long.MAX_VALUE);
        CompoundTag schemaFour = (CompoundTag) PlatformSavedData.CODEC
                .encodeStart(NbtOps.INSTANCE, current).getOrThrow();
        schemaFour.putInt("schema_version", 4);
        schemaFour.remove("economy_receipts");
        schemaFour.remove("economy_alerts");

        PlatformSavedData migrated = PlatformSavedData.CODEC.parse(NbtOps.INSTANCE, schemaFour).getOrThrow();

        assertEquals(PlatformSavedData.CURRENT_SCHEMA_VERSION, migrated.schemaVersion());
        assertTrue(migrated.isWritable());
        assertTrue(migrated.economyReceiptsView().isEmpty());
        assertTrue(migrated.economyAlertsView().isEmpty());
        assertTrue(migrated.hasTransaction(id(100), 1_000));
    }

    @Test
    void receiptCodecRejectsCapacityOverflowAndDuplicateIdsBeforeStateLoad() {
        var codec = PlatformSavedData.boundedReceiptsCodec(1);
        EconomyTransactionReceipt receipt = receipt(1_000, id(1));

        assertTrue(codec.encodeStart(NbtOps.INSTANCE, Map.of(id(100), receipt, id(101), receipt))
                .error().isPresent());

        ListTag encoded = (ListTag) codec.encodeStart(NbtOps.INSTANCE, Map.of(id(100), receipt)).getOrThrow();
        encoded.add(encoded.getFirst().copy());
        assertTrue(PlatformSavedData.boundedReceiptsCodec(2).parse(NbtOps.INSTANCE, encoded).error().isPresent());
    }

    @Test
    void commitRejectsMismatchedEvidenceBeforeAnyMutation() {
        PlatformSavedData state = new PlatformSavedData();
        UUID player = id(1);
        EconomyService.award(state, player, 10, "seed", 1_000, id(200), 0, Long.MAX_VALUE);
        int audits = state.auditCount();
        UUID rejectedId = id(201);

        assertThrows(IllegalArgumentException.class, () -> state.commitEconomyTransaction(
                player, 99, rejectedId, 2_000, receipt(2_000, id(999)), List.of(),
                new AuditEntry(
                        2_000, AdministrationService.SYSTEM_ACTOR,
                        net.minecraft.resources.Identifier.fromNamespaceAndPath("rovenfall", "injected"),
                        player.toString(), Optional.empty(), Optional.empty(), "10", "99", "test", rejectedId)));

        assertEquals(10, state.economyBalance(player).orElseThrow());
        assertTrue(state.economyReceipt(rejectedId).isEmpty());
        assertTrue(!state.hasTransaction(rejectedId, 2_000));
        assertEquals(audits, state.auditCount());
    }

    @Test
    void receiptCodecRejectsOrphanReversalAndPlayerMismatch() {
        UUID originalId = id(300);
        UUID reversalId = id(301);
        var codec = PlatformSavedData.boundedReceiptsCodec(2);

        ListTag orphan = (ListTag) codec.encodeStart(
                NbtOps.INSTANCE, Map.of(reversalId, reversal(2_000, id(1), originalId))).getOrThrow();
        assertTrue(codec.parse(NbtOps.INSTANCE, orphan).error().isPresent());

        EconomyTransactionReceipt original = receipt(1_000, id(1)).withReversedBy(reversalId);
        ListTag mismatchedPlayer = (ListTag) codec.encodeStart(NbtOps.INSTANCE, Map.of(
                originalId, original,
                reversalId, reversal(2_000, id(2), originalId))).getOrThrow();
        assertTrue(codec.parse(NbtOps.INSTANCE, mismatchedPlayer).error().isPresent());
    }

    @Test
    void receiptCodecRejectsReversalOfReversalCycle() {
        UUID firstId = id(310);
        UUID secondId = id(311);
        var codec = PlatformSavedData.boundedReceiptsCodec(2);
        ListTag cycle = (ListTag) codec.encodeStart(NbtOps.INSTANCE, Map.of(
                firstId, reversal(2_000, id(1), secondId).withReversedBy(secondId),
                secondId, reversal(3_000, id(1), firstId).withReversedBy(firstId))).getOrThrow();

        assertTrue(codec.parse(NbtOps.INSTANCE, cycle).error().isPresent());
    }

    @Test
    void activeReversalRetainsItsExpiredOriginalEvidence() {
        UUID originalId = id(400);
        UUID reversalId = id(401);
        Map<UUID, EconomyTransactionReceipt> receipts = Map.of(
                originalId, receipt(1_000, id(1)).withReversedBy(reversalId),
                reversalId, reversal(2_000, id(1), originalId),
                id(402), receipt(500, id(2)));

        assertEquals(Set.of(originalId, reversalId),
                PlatformSavedData.retainedReceiptIds(receipts, Set.of(reversalId)));
    }

    private static EconomyTransactionReceipt receipt(long timestamp, UUID playerId) {
        return new EconomyTransactionReceipt(
                timestamp, AdministrationService.SYSTEM_ACTOR, playerId,
                EconomyTransactionReceipt.Kind.AWARD, 10,
                Optional.empty(), Optional.empty(), Optional.empty(), 0,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                EconomyTransactionReceipt.CompensationDecision.NONE);
    }

    private static EconomyTransactionReceipt reversal(long timestamp, UUID playerId, UUID originalId) {
        return new EconomyTransactionReceipt(
                timestamp, AdministrationService.SYSTEM_ACTOR, playerId,
                EconomyTransactionReceipt.Kind.REVERSAL, 10,
                Optional.empty(), Optional.empty(), Optional.empty(), 0,
                Optional.empty(), Optional.empty(), Optional.of(originalId), Optional.empty(), Optional.empty(),
                EconomyTransactionReceipt.CompensationDecision.NONE);
    }

    private static UUID id(long value) {
        return new UUID(0L, value);
    }
}
