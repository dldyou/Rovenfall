package org.dldyou.rovenfall.administration;

import static org.dldyou.rovenfall.PersistenceTestHarness.roundTrip;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.Identifier;
import org.dldyou.rovenfall.rpg.SkillResetPlan;
import org.junit.jupiter.api.Test;

final class RpgAdminOperationPersistenceTest {
    private static final UUID ACTOR = id(1);
    private static final UUID PLAYER = id(2);

    @Test
    void pendingOperationRoundTripsAndCompletesWithItsAuditAtomically() {
        PlatformSavedData state = new PlatformSavedData();
        UUID transaction = id(10);
        RpgAdminOperation operation = xpOperation(50, 100, 1_000);

        assertEquals(PlatformSavedData.RpgAdminOperationBeginStatus.SUCCESS,
                state.beginRpgAdminOperation(transaction, operation).status());
        state = roundTrip(PlatformSavedData.CODEC, state);
        assertEquals(operation, state.rpgAdminOperation(transaction).orElseThrow());
        assertEquals(1, state.pendingRpgAdminOperations(PLAYER).size());

        AuditEntry audit = audit(transaction, 1_100);
        assertTrue(state.completeRpgAdminOperation(transaction, operation, audit));
        assertEquals(RpgAdminOperation.Phase.COMPLETED,
                state.rpgAdminOperation(transaction).orElseThrow().phase());
        assertTrue(state.pendingRpgAdminOperations(PLAYER).isEmpty());
        assertEquals(audit, state.auditPage(0, 1).entries().getFirst());
        assertEquals(RpgAdminOperation.Phase.COMPLETED,
                roundTrip(PlatformSavedData.CODEC, state)
                        .rpgAdminOperation(transaction).orElseThrow().phase());
    }

    @Test
    void transactionReuseIsIdempotentOrConflictSafeAndWrongCompletionChangesNothing() {
        PlatformSavedData state = new PlatformSavedData();
        UUID transaction = id(20);
        RpgAdminOperation operation = xpOperation(10, 0, 1_000);
        assertEquals(PlatformSavedData.RpgAdminOperationBeginStatus.SUCCESS,
                state.beginRpgAdminOperation(transaction, operation).status());
        assertEquals(PlatformSavedData.RpgAdminOperationBeginStatus.DUPLICATE,
                state.beginRpgAdminOperation(transaction, operation).status());
        assertEquals(PlatformSavedData.RpgAdminOperationBeginStatus.CONFLICT,
                state.beginRpgAdminOperation(transaction, xpOperation(11, 0, 1_000)).status());
        assertFalse(state.completeRpgAdminOperation(transaction, xpOperation(11, 0, 1_000), audit(transaction, 1_001)));
        assertEquals(RpgAdminOperation.Phase.PENDING,
                state.rpgAdminOperation(transaction).orElseThrow().phase());
        assertEquals(0, state.auditCount());
    }

    @Test
    void schemaElevenMigratesWithAnEmptyAdminJournalAndCodecRejectsInvalidShapes() {
        PlatformSavedData original = new PlatformSavedData();
        CompoundTag schemaEleven = (CompoundTag) PlatformSavedData.CODEC
                .encodeStart(NbtOps.INSTANCE, original).getOrThrow();
        schemaEleven.putInt("schema_version", 11);
        schemaEleven.remove("rpg_admin_operations");
        PlatformSavedData migrated = PlatformSavedData.CODEC.parse(NbtOps.INSTANCE, schemaEleven).getOrThrow();
        assertEquals(12, migrated.schemaVersion());
        assertTrue(migrated.isWritable());

        RpgAdminOperation invalidXp = new RpgAdminOperation(
                ACTOR, PLAYER, RpgAdminOperation.Action.XP_ADJUST, id("combat"), 0, 5,
                Optional.empty(), "reason", 1_000, RpgAdminOperation.Phase.PENDING);
        RpgAdminOperation invalidReset = new RpgAdminOperation(
                ACTOR, PLAYER, RpgAdminOperation.Action.SKILL_RESET, id("warrior"), 0, 0,
                Optional.empty(), "reason", 1_000, RpgAdminOperation.Phase.PENDING);
        assertTrue(RpgAdminOperation.CODEC.encodeStart(NbtOps.INSTANCE, invalidXp).error().isPresent());
        assertTrue(RpgAdminOperation.CODEC.encodeStart(NbtOps.INSTANCE, invalidReset).error().isPresent());
    }

    private static RpgAdminOperation xpOperation(long delta, long before, long timestamp) {
        return new RpgAdminOperation(ACTOR, PLAYER, RpgAdminOperation.Action.XP_ADJUST, id("combat"),
                delta, before, Optional.empty(), "support correction", timestamp, RpgAdminOperation.Phase.PENDING);
    }

    private static AuditEntry audit(UUID transaction, long timestamp) {
        return new AuditEntry(timestamp, ACTOR, id("rpg_admin_xp_adjust"), PLAYER.toString(),
                Optional.empty(), Optional.empty(), "100", "150", "support correction", transaction);
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("rovenfall", path);
    }

    private static UUID id(long least) {
        return new UUID(0L, least);
    }
}
