package org.dldyou.rovenfall.administration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.serialization.JsonOps;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.dldyou.rovenfall.claims.ClaimKey;
import org.dldyou.rovenfall.world.PortalDefinition;
import org.dldyou.rovenfall.world.WorldTopology;
import org.junit.jupiter.api.Test;

final class WildernessResetServiceTest {
    @Test
    void failedFinalSaveNeverArmsPendingSwap() {
        AtomicBoolean armed = new AtomicBoolean();

        var status = WildernessResetService.persistStateThenArmPending(
                () -> false, () -> armed.set(true));

        assertEquals(WildernessResetService.Status.PRECOMMIT_FAILED, status);
        assertFalse(armed.get());
    }

    @Test
    void pendingSwapIsArmedOnlyAfterStatePersistence() {
        List<String> order = new ArrayList<>();

        var status = WildernessResetService.persistStateThenArmPending(
                () -> {
                    order.add("save");
                    return true;
                },
                () -> order.add("pending"));

        assertEquals(WildernessResetService.Status.SUCCESS, status);
        assertEquals(List.of("save", "pending"), order);
    }

    @Test
    void activeOperationWithoutLifecycleEvidenceFailsClosed() {
        assertThrows(WildernessResetStore.StoreException.class,
                () -> WildernessResetService.requireLifecycleResult(true));
    }

    @Test
    void resetLockBlocksOnlyFreshWildernessEntityJoins() {
        assertTrue(ClaimProtectionEvents.blocksEntityJoin(true, true, false));
        assertFalse(ClaimProtectionEvents.blocksEntityJoin(true, true, true));
        assertFalse(ClaimProtectionEvents.blocksEntityJoin(true, false, false));
        assertFalse(ClaimProtectionEvents.blocksEntityJoin(false, true, false));
    }

    @Test
    void warningIsOwnerOnlyAuditedAndPersistent() {
        PlatformSavedData state = new PlatformSavedData();
        UUID actor = UUID.randomUUID();
        UUID deniedId = UUID.randomUUID();

        var denied = WildernessResetService.warn(state, actor, false, "planned reset", 1_000L, deniedId);
        assertEquals(WildernessResetService.Status.UNAUTHORIZED, denied.status());
        assertTrue(denied.auditRecorded());
        assertTrue(state.wildernessResetState().warning().isEmpty());

        assertEquals(AdministrationService.RoleChangeStatus.SUCCESS, AdministrationService.changeRole(
                state, AdministrationService.SYSTEM_ACTOR, true, actor, "owner",
                "reset owner", 1_500L, UUID.randomUUID()).status());
        UUID warningId = UUID.randomUUID();
        var accepted = WildernessResetService.warn(state, actor, false, "planned reset", 2_000L, warningId);
        assertEquals(WildernessResetService.Status.SUCCESS, accepted.status());
        assertEquals(warningId, state.wildernessResetState().warning().orElseThrow().warningId());

        var encoded = PlatformSavedData.CODEC.encodeStart(JsonOps.INSTANCE, state).getOrThrow();
        PlatformSavedData decoded = PlatformSavedData.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();
        assertEquals(PlatformSavedData.CURRENT_SCHEMA_VERSION, decoded.schemaVersion());
        assertEquals(warningId, decoded.wildernessResetState().warning().orElseThrow().warningId());
        assertFalse(decoded.isWildernessOperationLocked());
    }

    @Test
    void schemaNineMigratesWithEmptyResetState() {
        PlatformSavedData state = new PlatformSavedData();
        var encoded = PlatformSavedData.CODEC.encodeStart(JsonOps.INSTANCE, state).getOrThrow().getAsJsonObject();
        encoded.addProperty("schema_version", 9);
        encoded.remove("wilderness_reset");

        PlatformSavedData migrated = PlatformSavedData.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();

        assertEquals(PlatformSavedData.CURRENT_SCHEMA_VERSION, migrated.schemaVersion());
        assertEquals(WildernessResetState.EMPTY, migrated.wildernessResetState());
        assertTrue(migrated.isWritable());
    }

    @Test
    void activeOperationLocksPortalTravelAndWildernessMutation() {
        PlatformSavedData state = new PlatformSavedData();
        UUID operator = UUID.randomUUID();
        UUID transactionId = UUID.randomUUID();
        String digest = "0".repeat(64);
        var operation = new WildernessResetState.Operation(
                WildernessResetState.Kind.RESET, transactionId, transactionId, transactionId, operator,
                1_000L, "locked reset", 0, 0, digest, 0, 0, digest);
        state.commitWildernessOperation(operation, new AuditEntry(
                1_000L, operator, Identifier.fromNamespaceAndPath("rovenfall", "wilderness_operation_staged"),
                "wilderness", Optional.of(WorldTopology.WILDERNESS.identifier()), Optional.empty(),
                "unlocked", "locked", "locked reset", transactionId));

        var decision = ClaimProtectionService.evaluate(
                state, operator, true, WorldTopology.HUB, BlockPos.ZERO, 1,
                new ClaimKey(WorldTopology.WILDERNESS, 0, 0), ClaimProtectionService.Action.BUILD);
        assertFalse(decision.allowed());
        assertEquals(ClaimProtectionService.Reason.WILDERNESS_LOCKED, decision.reason());

        Identifier portalId = Identifier.fromNamespaceAndPath("rovenfall", "locked_test");
        var origin = new PortalDefinition.Endpoint(WorldTopology.HUB, new BlockPos(16, 70, 16));
        var destination = new PortalDefinition.Endpoint(WorldTopology.WILDERNESS, new BlockPos(32, 80, 32));
        var travel = PortalTravelService.travel(
                state, UUID.randomUUID(), WorldTopology.HUB, Vec3.atCenterOf(origin.position()), portalId,
                2_000L, UUID.randomUUID(), new PortalTravelService.Gateway() {
                    @Override
                    public boolean dimensionAvailable(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension) {
                        return true;
                    }

                    @Override
                    public Optional<BlockPos> safeDestination(PortalDefinition definition) {
                        return Optional.of(destination.position());
                    }

                    @Override
                    public boolean teleport(
                            net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension,
                            BlockPos target) {
                        return true;
                    }
                });
        assertEquals(PortalTravelService.Status.WILDERNESS_LOCKED, travel.status());
    }
}
