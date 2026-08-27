package org.dldyou.rovenfall.administration;

import static org.dldyou.rovenfall.PersistenceTestHarness.roundTrip;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.dldyou.rovenfall.claims.ClaimKey;
import org.dldyou.rovenfall.world.PortalDefinition;
import org.dldyou.rovenfall.world.ProtectedRegion;
import org.dldyou.rovenfall.world.WorldTopology;
import org.junit.jupiter.api.Test;

final class PortalServiceTest {
    private static final Identifier PORTAL_ID = id("hub_wilderness");
    private static final PortalDefinition.Endpoint ORIGIN =
            new PortalDefinition.Endpoint(WorldTopology.HUB, new BlockPos(1_608, 70, 1_608));
    private static final PortalDefinition.Endpoint DESTINATION =
            new PortalDefinition.Endpoint(WorldTopology.WILDERNESS, new BlockPos(3_208, 80, 3_208));

    @Test
    void ownerMutationAtomicallyOwnsBothProtectionRingsAndPersists() {
        PlatformSavedData state = new PlatformSavedData();
        UUID owner = id(1);
        UUID moderator = id(2);
        owner(state, owner);
        AdministrationService.changeRole(
                state, owner, false, moderator, "moderator", "support", 2_000, id(102));
        PortalDefinition initial = definition(owner, ORIGIN, DESTINATION, 1, 5_000, false);

        int auditsBeforeNull = state.auditCount();
        assertEquals(PortalService.Status.INVALID_REQUEST, PortalService.create(
                state, owner, false, PORTAL_ID, null, ignored -> true,
                "invalid", 3_000, id(103)).status());
        assertEquals(auditsBeforeNull, state.auditCount());

        int regionsBeforeDenied = state.protectedRegionCount();
        var denied = PortalService.create(
                state, moderator, false, PORTAL_ID, initial, ignored -> true,
                "attempt", 4_000, id(104));
        assertEquals(PortalService.Status.UNAUTHORIZED, denied.status());
        assertTrue(denied.auditRecorded());
        assertTrue(state.portalDefinition(PORTAL_ID).isEmpty());
        assertEquals(regionsBeforeDenied, state.protectedRegionCount());

        UUID createId = id(105);
        var created = PortalService.create(
                state, owner, false, PORTAL_ID, initial, ignored -> true,
                "open route", 6_000, createId);
        assertEquals(PortalService.Status.SUCCESS, created.status());
        assertEquals(initial, state.portalDefinition(PORTAL_ID).orElseThrow());
        assertEquals(PORTAL_ID, state.portalAt(ORIGIN).orElseThrow());
        assertEquals(initial.protectedRegion(ORIGIN), state.protectedRegion(
                PortalDefinition.originProtectionId(PORTAL_ID)).orElseThrow());
        assertEquals(initial.protectedRegion(DESTINATION), state.protectedRegion(
                PortalDefinition.destinationProtectionId(PORTAL_ID)).orElseThrow());
        assertTrue(state.isProtectedRegion(ClaimKey.at(ORIGIN.dimension(), ORIGIN.position())));
        assertTrue(state.isProtectedRegion(ClaimKey.at(DESTINATION.dimension(), DESTINATION.position())));
        assertEquals("rovenfall:portal_create", latest(state).actionType().toString());
        assertEquals(createId, latest(state).transactionId());

        assertEquals(ProtectedRegionService.Status.DEPENDENCY_LOCKED, ProtectedRegionService.delete(
                state, owner, false, PortalDefinition.originProtectionId(PORTAL_ID),
                "bypass", 8_000, id(106)).status());
        assertEquals(initial, state.portalDefinition(PORTAL_ID).orElseThrow());

        Identifier conflictId = id("future_arena");
        ProtectedRegion conflict = new ProtectedRegion(owner, WorldTopology.WILDERNESS, 300, 300, 300, 300);
        assertEquals(ProtectedRegionService.Status.SUCCESS, ProtectedRegionService.create(
                state, owner, false, conflictId, conflict, "arena", 10_000, id(107)).status());
        PortalDefinition conflicting = definition(
                owner,
                new PortalDefinition.Endpoint(WorldTopology.HUB, new BlockPos(1_624, 70, 1_624)),
                new PortalDefinition.Endpoint(WorldTopology.WILDERNESS, new BlockPos(4_808, 80, 4_808)),
                0,
                2_000,
                false);
        var rejectedEdit = PortalService.edit(
                state, owner, false, PORTAL_ID, conflicting, ignored -> true,
                "unsafe move", 12_000, id(108));
        assertEquals(PortalService.Status.PROTECTION_CONFLICT, rejectedEdit.status());
        assertEquals(initial, state.portalDefinition(PORTAL_ID).orElseThrow());
        assertEquals(initial.protectedRegion(ORIGIN), state.protectedRegion(
                PortalDefinition.originProtectionId(PORTAL_ID)).orElseThrow());

        PortalDefinition edited = definition(
                owner,
                new PortalDefinition.Endpoint(WorldTopology.HUB, new BlockPos(1_640, 70, 1_640)),
                new PortalDefinition.Endpoint(WorldTopology.WILDERNESS, new BlockPos(3_240, 80, 3_240)),
                0,
                2_000,
                true);
        UUID editId = id(109);
        assertEquals(PortalService.Status.SUCCESS, PortalService.edit(
                state, owner, false, PORTAL_ID, edited, ignored -> true,
                "move route", 14_000, editId).status());
        assertEquals(PortalService.Status.DUPLICATE_TRANSACTION, PortalService.edit(
                state, owner, false, PORTAL_ID, initial, ignored -> true,
                "replay", 16_000, editId).status());
        assertEquals(edited, state.portalDefinition(PORTAL_ID).orElseThrow());

        PlatformSavedData loaded = roundTrip(PlatformSavedData.CODEC, state);
        assertEquals(edited, loaded.portalDefinition(PORTAL_ID).orElseThrow());
        assertEquals(PORTAL_ID, loaded.portalAt(edited.origin()).orElseThrow());
        assertTrue(loaded.portalProtectionIntact(PORTAL_ID, edited));
        assertTrue(loaded.auditPage(0, 20).entries().stream()
                .anyMatch(entry -> entry.actionType().toString().equals("rovenfall:portal_mutation_denied")));

        assertEquals(PortalService.Status.SUCCESS, PortalService.delete(
                loaded, owner, false, PORTAL_ID, "retire route", 20_000, id(110)).status());
        assertTrue(loaded.portalDefinition(PORTAL_ID).isEmpty());
        assertTrue(loaded.protectedRegion(PortalDefinition.originProtectionId(PORTAL_ID)).isEmpty());
        assertTrue(loaded.protectedRegion(PortalDefinition.destinationProtectionId(PORTAL_ID)).isEmpty());
    }

    @Test
    void nativeOwnerOverrideAndEndpointValidationAreServerAuthoritative() {
        PlatformSavedData state = new PlatformSavedData();
        UUID nativeOperator = id(200);
        PortalDefinition definition = definition(nativeOperator, ORIGIN, DESTINATION, 0, 0, true);
        PortalDefinition outOfBoundsProtection = definition(
                nativeOperator,
                new PortalDefinition.Endpoint(WorldTopology.HUB, new BlockPos(29_999_999, 70, 0)),
                DESTINATION,
                PortalDefinition.MAX_PROTECTION_RADIUS_CHUNKS,
                0,
                true);
        assertEquals(PortalService.Status.INVALID_REQUEST, PortalService.create(
                state, nativeOperator, true, PORTAL_ID, outOfBoundsProtection,
                ignored -> true, "invalid protection bounds", 500, id(203)).status());
        assertTrue(state.portalDefinition(PORTAL_ID).isEmpty());

        assertEquals(PortalService.Status.ENDPOINT_UNAVAILABLE, PortalService.create(
                state, nativeOperator, true, PORTAL_ID, definition,
                endpoint -> !endpoint.equals(DESTINATION), "unavailable", 1_000, id(201)).status());
        assertTrue(state.portalDefinition(PORTAL_ID).isEmpty());
        assertEquals(0, state.protectedRegionCount());

        assertEquals(PortalService.Status.SUCCESS, PortalService.create(
                state, nativeOperator, true, PORTAL_ID, definition,
                ignored -> true, "native create", 3_000, id(202)).status());
    }

    @Test
    void contentManagerCanCreateAndDeletePortals() {
        PlatformSavedData state = new PlatformSavedData();
        UUID owner = id(210);
        UUID contentManager = id(211);
        owner(state, owner);
        assertEquals(AdministrationService.RoleChangeStatus.SUCCESS, AdministrationService.changeRole(
                state, owner, false, contentManager, "content_manager", "portal operations", 1_000, id(212)).status());
        PortalDefinition definition = definition(contentManager, ORIGIN, DESTINATION, 0, 0, true);

        assertEquals(PortalService.Status.SUCCESS, PortalService.create(
                state, contentManager, false, PORTAL_ID, definition,
                ignored -> true, "content create", 2_000, id(213)).status());
        assertEquals(PortalService.Status.SUCCESS, PortalService.delete(
                state, contentManager, false, PORTAL_ID,
                "content delete", 3_000, id(214)).status());
    }

    @Test
    void portalCreationDoesNotOverwriteAProtectedRegionWithItsDerivedId() {
        PlatformSavedData state = new PlatformSavedData();
        UUID owner = id(220);
        owner(state, owner);
        Identifier derivedId = PortalDefinition.originProtectionId(PORTAL_ID);
        ProtectedRegion retained = new ProtectedRegion(owner, WorldTopology.HUB, 500, 500, 500, 500);
        assertEquals(ProtectedRegionService.Status.SUCCESS, ProtectedRegionService.create(
                state, owner, false, derivedId, retained, "reserved id", 1_000, id(221)).status());

        assertEquals(PortalService.Status.PROTECTION_CONFLICT, PortalService.create(
                state, owner, false, PORTAL_ID, definition(owner, ORIGIN, DESTINATION, 0, 0, true),
                ignored -> true, "must not overwrite", 2_000, id(222)).status());
        assertEquals(retained, state.protectedRegion(derivedId).orElseThrow());
        assertTrue(state.portalDefinition(PORTAL_ID).isEmpty());
        assertTrue(state.protectedRegion(PortalDefinition.destinationProtectionId(PORTAL_ID)).isEmpty());
    }

    @Test
    void travelCommitsOnlyAfterTeleportAndCooldownEvidenceSurvivesRestart() {
        PlatformSavedData state = new PlatformSavedData();
        UUID owner = id(300);
        UUID player = id(301);
        owner(state, owner);
        PortalDefinition definition = definition(owner, ORIGIN, DESTINATION, 0, 5_000, false);
        assertEquals(PortalService.Status.SUCCESS, PortalService.create(
                state, owner, false, PORTAL_ID, definition, ignored -> true,
                "travel route", 1_000, id(302)).status());

        FakeGateway success = new FakeGateway(true, Optional.of(DESTINATION.position()), true);
        UUID travelId = id(303);
        var travelled = travel(state, player, ORIGIN, PORTAL_ID, 10_000, travelId, success);
        assertEquals(PortalTravelService.Status.SUCCESS, travelled.status());
        assertEquals(1, success.teleports);
        assertEquals(15_000, state.portalCooldownUntil(player, PORTAL_ID));
        assertTrue(state.portalTravelReceipt(travelId).isPresent());
        assertEquals("rovenfall:portal_travel", latest(state).actionType().toString());

        PlatformSavedData loaded = roundTrip(PlatformSavedData.CODEC, state);
        assertEquals(15_000, loaded.portalCooldownUntil(player, PORTAL_ID));
        assertTrue(loaded.portalTravelReceipt(travelId).isPresent());
        assertTrue(loaded.portalProtectionIntact(PORTAL_ID, definition));

        FakeGateway replay = new FakeGateway(true, Optional.of(DESTINATION.position()), true);
        assertEquals(PortalTravelService.Status.DUPLICATE_TRANSACTION,
                travel(loaded, player, ORIGIN, PORTAL_ID, 16_000, travelId, replay).status());
        assertEquals(0, replay.teleports);

        FakeGateway cooldown = new FakeGateway(true, Optional.of(DESTINATION.position()), true);
        assertEquals(PortalTravelService.Status.COOLDOWN,
                travel(loaded, player, ORIGIN, PORTAL_ID, 11_000, id(304), cooldown).status());
        assertEquals(0, cooldown.teleports);

        UUID distantPlayer = id(305);
        FakeGateway distant = new FakeGateway(true, Optional.of(DESTINATION.position()), true);
        assertEquals(PortalTravelService.Status.TOO_FAR, PortalTravelService.travel(
                loaded, distantPlayer, ORIGIN.dimension(), new Vec3(0, 70, 0), PORTAL_ID,
                20_000, id(306), distant).status());
        assertEquals(0, distant.teleports);
        assertEquals(0, loaded.portalCooldownUntil(distantPlayer, PORTAL_ID));

        UUID blockedPlayer = id(307);
        PortalTravelService.recordCombat(loaded, blockedPlayer, 30_000);
        FakeGateway combat = new FakeGateway(true, Optional.of(DESTINATION.position()), true);
        assertEquals(PortalTravelService.Status.COMBAT_LOCKED,
                travel(loaded, blockedPlayer, ORIGIN, PORTAL_ID, 31_000, id(308), combat).status());
        assertEquals(0, combat.teleports);

        UUID failingPlayer = id(309);
        assertEquals(PortalTravelService.Status.TARGET_UNAVAILABLE,
                travel(loaded, failingPlayer, ORIGIN, PORTAL_ID, 40_000, id(310),
                        new FakeGateway(false, Optional.of(DESTINATION.position()), true)).status());
        assertEquals(PortalTravelService.Status.UNSAFE_DESTINATION,
                travel(loaded, failingPlayer, ORIGIN, PORTAL_ID, 42_000, id(311),
                        new FakeGateway(true, Optional.empty(), true)).status());
        FakeGateway failedTeleport = new FakeGateway(true, Optional.of(DESTINATION.position()), false);
        assertEquals(PortalTravelService.Status.TELEPORT_FAILED,
                travel(loaded, failingPlayer, ORIGIN, PORTAL_ID, 44_000, id(312), failedTeleport).status());
        assertEquals(1, failedTeleport.teleports);
        assertEquals(0, loaded.portalCooldownUntil(failingPlayer, PORTAL_ID));
        assertTrue(loaded.portalTravelReceipt(id(312)).isEmpty());

        UUID throwingPlayer = id(315);
        FakeGateway throwingTeleport = new FakeGateway(
                true, Optional.of(DESTINATION.position()), true, true);
        assertEquals(PortalTravelService.Status.TELEPORT_FAILED,
                travel(loaded, throwingPlayer, ORIGIN, PORTAL_ID, 46_000, id(316), throwingTeleport).status());
        assertEquals(1, throwingTeleport.teleports);
        assertEquals(0, loaded.portalCooldownUntil(throwingPlayer, PORTAL_ID));
        assertTrue(loaded.portalTravelReceipt(id(316)).isEmpty());

        FakeGateway overflow = new FakeGateway(true, Optional.of(DESTINATION.position()), true);
        assertEquals(PortalTravelService.Status.INVALID_REQUEST,
                travel(loaded, id(313), ORIGIN, PORTAL_ID, Long.MAX_VALUE - 1, id(314), overflow).status());
        assertEquals(0, overflow.teleports);
    }

    @Test
    void portalStateCodecRejectsOversizedRuntimeListsBeforeBuildingMaps() {
        JsonObject encoded = new JsonObject();
        JsonArray combatTimestamps = new JsonArray();
        for (int index = 0; index <= PortalState.MAX_COMBAT_ENTRIES; index++) {
            JsonObject entry = new JsonObject();
            entry.addProperty("player", new UUID(1L, index + 1L).toString());
            entry.addProperty("timestamp", index);
            combatTimestamps.add(entry);
        }
        encoded.add("combat_timestamps", combatTimestamps);

        assertTrue(PortalState.CODEC.parse(JsonOps.INSTANCE, encoded).error().isPresent());
    }

    @Test
    void schemaEightMigratesWithEmptyPortalState() {
        CompoundTag schemaEight = (CompoundTag) PlatformSavedData.CODEC.encodeStart(
                NbtOps.INSTANCE, new PlatformSavedData()).getOrThrow();
        schemaEight.putInt("schema_version", 8);
        schemaEight.remove("portal_state");

        PlatformSavedData migrated = PlatformSavedData.CODEC.parse(NbtOps.INSTANCE, schemaEight).getOrThrow();
        assertEquals(PlatformSavedData.CURRENT_SCHEMA_VERSION, migrated.schemaVersion());
        assertTrue(migrated.isWritable());
        assertTrue(migrated.portalDefinitions().isEmpty());
    }

    private static PortalTravelService.TravelResult travel(
            PlatformSavedData state,
            UUID player,
            PortalDefinition.Endpoint origin,
            Identifier portalId,
            long timestamp,
            UUID transactionId,
            FakeGateway gateway) {
        return PortalTravelService.travel(
                state, player, origin.dimension(), Vec3.atCenterOf(origin.position()), portalId,
                timestamp, transactionId, gateway);
    }

    private static PortalDefinition definition(
            UUID administrator,
            PortalDefinition.Endpoint origin,
            PortalDefinition.Endpoint destination,
            int radius,
            long cooldown,
            boolean allowCombat) {
        return new PortalDefinition(
                administrator, origin, destination, radius, cooldown,
                PortalDefinition.SafeArrivalPolicy.NEAREST_SAFE, allowCombat);
    }

    private static void owner(PlatformSavedData state, UUID owner) {
        AdministrationService.changeRole(
                state, AdministrationService.SYSTEM_ACTOR, true, owner, "owner", "bootstrap", 500, id(900));
    }

    private static AuditEntry latest(PlatformSavedData state) {
        return state.auditPage(0, 1).entries().getFirst();
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("rovenfall", path);
    }

    private static UUID id(long value) {
        return new UUID(0L, value);
    }

    private static final class FakeGateway implements PortalTravelService.Gateway {
        private final boolean available;
        private final Optional<BlockPos> safeDestination;
        private final boolean teleportSucceeds;
        private final boolean teleportThrows;
        private int teleports;

        private FakeGateway(boolean available, Optional<BlockPos> safeDestination, boolean teleportSucceeds) {
            this(available, safeDestination, teleportSucceeds, false);
        }

        private FakeGateway(
                boolean available,
                Optional<BlockPos> safeDestination,
                boolean teleportSucceeds,
                boolean teleportThrows) {
            this.available = available;
            this.safeDestination = safeDestination;
            this.teleportSucceeds = teleportSucceeds;
            this.teleportThrows = teleportThrows;
        }

        @Override
        public boolean dimensionAvailable(ResourceKey<Level> dimension) {
            return available;
        }

        @Override
        public Optional<BlockPos> safeDestination(PortalDefinition definition) {
            return safeDestination;
        }

        @Override
        public boolean teleport(ResourceKey<Level> dimension, BlockPos destination) {
            teleports++;
            if (teleportThrows) {
                throw new IllegalStateException("simulated teleport failure");
            }
            return teleportSucceeds;
        }
    }
}
