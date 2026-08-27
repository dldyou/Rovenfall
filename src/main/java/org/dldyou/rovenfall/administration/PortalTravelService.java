package org.dldyou.rovenfall.administration;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import org.dldyou.rovenfall.Rovenfall;
import org.dldyou.rovenfall.claims.Claim;
import org.dldyou.rovenfall.claims.ClaimKey;
import org.dldyou.rovenfall.claims.ClaimRole;
import org.dldyou.rovenfall.world.PortalDefinition;
import org.dldyou.rovenfall.world.ProtectedRegion;

public final class PortalTravelService {
    public static final long COMBAT_LOCK_MILLIS = 15_000L;
    private static final UUID ZERO_UUID = new UUID(0L, 0L);
    private static final long DENIED_AUDIT_INTERVAL_MILLIS = 1_000L;
    private static final int SAFE_SEARCH_RADIUS = 4;
    private static final int SAFE_SEARCH_VERTICAL = 2;
    private static final Identifier TRAVEL = action("portal_travel");
    private static final Identifier DENIED = action("portal_travel_denied");

    private PortalTravelService() {
    }

    public static TravelResult travel(
            PlatformSavedData state,
            ServerPlayer player,
            Identifier portalId,
            long timestampEpochMillis,
            UUID transactionId) {
        if (state == null || player == null || portalId == null || timestampEpochMillis < 0) {
            return new TravelResult(Status.INVALID_REQUEST, transactionId, 0L, false);
        }
        if (!state.isWritable()) {
            return new TravelResult(Status.READ_ONLY_SCHEMA, transactionId, 0L, false);
        }
        if (player instanceof FakePlayer) {
            return denied(
                    state,
                    player.getUUID(),
                    player.level().dimension(),
                    player.blockPosition(),
                    portalId,
                    Status.INVALID_REQUEST,
                    "fake_player",
                    timestampEpochMillis,
                    transactionId,
                    0L);
        }
        return travel(
                state,
                player.getUUID(),
                player.level().dimension(),
                player.position(),
                portalId,
                timestampEpochMillis,
                transactionId,
                new NativeGateway(player, state, portalId));
    }

    static TravelResult travel(
            PlatformSavedData state,
            UUID playerId,
            ResourceKey<Level> currentDimension,
            Vec3 currentPosition,
            Identifier portalId,
            long timestampEpochMillis,
            UUID transactionId,
            Gateway gateway) {
        if (state == null || playerId == null || currentDimension == null || currentPosition == null
                || portalId == null || gateway == null || timestampEpochMillis < 0) {
            return new TravelResult(Status.INVALID_REQUEST, transactionId, 0L, false);
        }
        if (!state.isWritable()) {
            return new TravelResult(Status.READ_ONLY_SCHEMA, transactionId, 0L, false);
        }
        if (transactionId == null || ZERO_UUID.equals(transactionId)) {
            return denied(state, playerId, currentDimension, BlockPos.containing(currentPosition), portalId,
                    Status.INVALID_TRANSACTION, "invalid_transaction", timestampEpochMillis, transactionId, 0L);
        }
        PortalDefinition definition = state.portalDefinition(portalId).orElse(null);
        if (definition == null) {
            return denied(state, playerId, currentDimension, BlockPos.containing(currentPosition), portalId,
                    Status.NOT_FOUND, "not_found", timestampEpochMillis, transactionId, 0L);
        }
        if (state.portalTravelReceipt(transactionId).isPresent()) {
            return denied(state, playerId, currentDimension, BlockPos.containing(currentPosition), portalId,
                    Status.DUPLICATE_TRANSACTION, "duplicate_transaction", timestampEpochMillis, transactionId,
                    state.portalCooldownUntil(playerId, portalId));
        }
        if (!state.portalProtectionIntact(portalId, definition)) {
            return denied(state, playerId, currentDimension, BlockPos.containing(currentPosition), portalId,
                    Status.PROTECTION_UNAVAILABLE, "protection_unavailable",
                    timestampEpochMillis, transactionId, 0L);
        }
        if (!currentDimension.equals(definition.origin().dimension())) {
            return denied(state, playerId, currentDimension, BlockPos.containing(currentPosition), portalId,
                    Status.WRONG_DIMENSION, "wrong_dimension", timestampEpochMillis, transactionId, 0L);
        }
        if (currentPosition.distanceToSqr(Vec3.atCenterOf(definition.origin().position()))
                > PortalDefinition.MAX_USE_DISTANCE * PortalDefinition.MAX_USE_DISTANCE) {
            return denied(state, playerId, currentDimension, BlockPos.containing(currentPosition), portalId,
                    Status.TOO_FAR, "too_far", timestampEpochMillis, transactionId, 0L);
        }
        long cooldownUntil = state.portalCooldownUntil(playerId, portalId);
        if (cooldownUntil > timestampEpochMillis) {
            return denied(state, playerId, currentDimension, BlockPos.containing(currentPosition), portalId,
                    Status.COOLDOWN, "cooldown", timestampEpochMillis, transactionId, cooldownUntil);
        }
        long lastCombat = state.portalCombatTimestamp(playerId).orElse(0L);
        if (!definition.allowCombat() && lastCombat > 0
                && (lastCombat > timestampEpochMillis || timestampEpochMillis - lastCombat < COMBAT_LOCK_MILLIS)) {
            return denied(state, playerId, currentDimension, BlockPos.containing(currentPosition), portalId,
                    Status.COMBAT_LOCKED, "combat_locked", timestampEpochMillis, transactionId,
                    lastCombat + COMBAT_LOCK_MILLIS);
        }
        if (!state.canCommitPortalTravel(transactionId, timestampEpochMillis)) {
            return denied(state, playerId, currentDimension, BlockPos.containing(currentPosition), portalId,
                    Status.EVIDENCE_FULL, "evidence_full", timestampEpochMillis, transactionId, 0L);
        }
        long nextUse;
        try {
            nextUse = Math.addExact(timestampEpochMillis, definition.cooldownMillis());
        } catch (ArithmeticException exception) {
            return denied(state, playerId, currentDimension, BlockPos.containing(currentPosition), portalId,
                    Status.INVALID_REQUEST, "cooldown_overflow", timestampEpochMillis, transactionId, 0L);
        }
        if (!gateway.dimensionAvailable(definition.destination().dimension())) {
            return denied(state, playerId, currentDimension, BlockPos.containing(currentPosition), portalId,
                    Status.TARGET_UNAVAILABLE, "target_unavailable", timestampEpochMillis, transactionId, 0L);
        }
        BlockPos destination = gateway.safeDestination(definition).orElse(null);
        if (destination == null) {
            return denied(state, playerId, currentDimension, BlockPos.containing(currentPosition), portalId,
                    Status.UNSAFE_DESTINATION, "unsafe_destination", timestampEpochMillis, transactionId, 0L);
        }
        if (!gateway.teleport(definition.destination().dimension(), destination)) {
            return denied(state, playerId, currentDimension, BlockPos.containing(currentPosition), portalId,
                    Status.TELEPORT_FAILED, "teleport_failed", timestampEpochMillis, transactionId, 0L);
        }

        PortalDefinition.Endpoint resolved = new PortalDefinition.Endpoint(
                definition.destination().dimension(), destination);
        state.commitPortalTravel(
                playerId,
                portalId,
                nextUse,
                transactionId,
                timestampEpochMillis,
                resolved,
                new AuditEntry(
                        timestampEpochMillis,
                        playerId,
                        TRAVEL,
                        portalId.toString(),
                        Optional.of(definition.destination().dimension().identifier()),
                        Optional.of(destination),
                        "cooldown_until=" + cooldownUntil,
                        "cooldown_until=" + nextUse,
                        "travel",
                        transactionId));
        return new TravelResult(Status.SUCCESS, transactionId, nextUse, true);
    }

    public static void recordCombat(PlatformSavedData state, UUID playerId, long timestampEpochMillis) {
        if (state != null && state.isWritable()) {
            state.recordPortalCombat(playerId, timestampEpochMillis);
        }
    }

    private static TravelResult denied(
            PlatformSavedData state,
            UUID playerId,
            ResourceKey<Level> dimension,
            BlockPos position,
            Identifier portalId,
            Status status,
            String reason,
            long timestampEpochMillis,
            UUID transactionId,
            long retryAt) {
        UUID evidenceId = transactionId == null || ZERO_UUID.equals(transactionId) ? UUID.randomUUID() : transactionId;
        boolean recorded = state.appendDeniedAudit(new AuditEntry(
                timestampEpochMillis,
                playerId,
                DENIED,
                portalId.toString(),
                Optional.of(dimension.identifier()),
                Optional.of(position),
                "unchanged",
                "unchanged",
                reason,
                evidenceId), DENIED_AUDIT_INTERVAL_MILLIS);
        return new TravelResult(status, transactionId, retryAt, recorded);
    }

    public static Optional<BlockPos> resolveSafeDestination(
            ServerLevel level,
            PlatformSavedData state,
            UUID playerId,
            Identifier portalId,
            PortalDefinition definition) {
        if (level == null || state == null || playerId == null || portalId == null || definition == null
                || !level.dimension().equals(definition.destination().dimension())) {
            return Optional.empty();
        }
        BlockPos exact = definition.destination().position();
        if (isSafe(level, state, playerId, portalId, definition, exact)) {
            return Optional.of(exact);
        }
        if (definition.safeArrivalPolicy() == PortalDefinition.SafeArrivalPolicy.EXACT) {
            return Optional.empty();
        }
        for (int radius = 1; radius <= SAFE_SEARCH_RADIUS; radius++) {
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    if (Math.max(Math.abs(x), Math.abs(z)) != radius) {
                        continue;
                    }
                    for (int y = 0; y <= SAFE_SEARCH_VERTICAL; y++) {
                        for (int direction : y == 0 ? new int[]{0} : new int[]{y, -y}) {
                            BlockPos candidate = exact.offset(x, direction, z);
                            if (isSafe(level, state, playerId, portalId, definition, candidate)) {
                                return Optional.of(candidate);
                            }
                        }
                    }
                }
            }
        }
        return Optional.empty();
    }

    private static boolean isSafe(
            ServerLevel level,
            PlatformSavedData state,
            UUID playerId,
            Identifier portalId,
            PortalDefinition definition,
            BlockPos position) {
        BlockPos head = position.above();
        BlockPos floor = position.below();
        if (!level.isInWorldBounds(floor) || !level.isInWorldBounds(head)
                || !level.getWorldBorder().isWithinBounds(position)) {
            return false;
        }
        ProtectedRegion destinationRegion = definition.protectedRegion(definition.destination());
        ClaimKey key = ClaimKey.at(level.dimension(), position);
        if (!destinationRegion.contains(key)) {
            return false;
        }
        Set<Identifier> owned = Set.of(
                PortalDefinition.originProtectionId(portalId),
                PortalDefinition.destinationProtectionId(portalId));
        if (!owned.containsAll(state.protectedRegionsAt(key))) {
            return false;
        }
        Claim claim = state.claim(key).orElse(null);
        if (claim != null && claim.settings().entryRestricted()
                && !claim.roleOf(playerId).atLeast(ClaimRole.USER)) {
            return false;
        }
        BlockState floorState = level.getBlockState(floor);
        BlockState feetState = level.getBlockState(position);
        BlockState headState = level.getBlockState(head);
        return floorState.isFaceSturdy(level, floor, Direction.UP)
                && feetState.getCollisionShape(level, position).isEmpty()
                && headState.getCollisionShape(level, head).isEmpty()
                && feetState.getFluidState().isEmpty()
                && headState.getFluidState().isEmpty()
                && !hazard(floorState) && !hazard(feetState) && !hazard(headState);
    }

    private static boolean hazard(BlockState state) {
        return state.is(BlockTags.FIRE)
                || state.getFluidState().is(FluidTags.LAVA)
                || state.is(Blocks.CACTUS)
                || state.is(Blocks.MAGMA_BLOCK)
                || state.is(Blocks.CAMPFIRE)
                || state.is(Blocks.SOUL_CAMPFIRE)
                || state.is(Blocks.SWEET_BERRY_BUSH)
                || state.is(Blocks.POWDER_SNOW)
                || state.is(Blocks.WITHER_ROSE);
    }

    interface Gateway {
        boolean dimensionAvailable(ResourceKey<Level> dimension);

        Optional<BlockPos> safeDestination(PortalDefinition definition);

        boolean teleport(ResourceKey<Level> dimension, BlockPos destination);
    }

    private record NativeGateway(ServerPlayer player, PlatformSavedData state, Identifier portalId) implements Gateway {
        @Override
        public boolean dimensionAvailable(ResourceKey<Level> dimension) {
            return player.level().getServer().getLevel(dimension) != null;
        }

        @Override
        public Optional<BlockPos> safeDestination(PortalDefinition definition) {
            ServerLevel level = player.level().getServer().getLevel(definition.destination().dimension());
            return resolveSafeDestination(level, state, player.getUUID(), portalId, definition);
        }

        @Override
        public boolean teleport(ResourceKey<Level> dimension, BlockPos destination) {
            MinecraftServer server = player.level().getServer();
            ServerLevel level = server.getLevel(dimension);
            return level != null && player.teleportTo(
                    level,
                    destination.getX() + 0.5D,
                    destination.getY(),
                    destination.getZ() + 0.5D,
                    Set.<Relative>of(),
                    player.getYRot(),
                    player.getXRot(),
                    false);
        }
    }

    private static Identifier action(String path) {
        return Identifier.fromNamespaceAndPath(Rovenfall.MOD_ID, path);
    }

    public enum Status {
        SUCCESS,
        INVALID_REQUEST,
        INVALID_TRANSACTION,
        READ_ONLY_SCHEMA,
        NOT_FOUND,
        DUPLICATE_TRANSACTION,
        PROTECTION_UNAVAILABLE,
        WRONG_DIMENSION,
        TOO_FAR,
        COOLDOWN,
        COMBAT_LOCKED,
        EVIDENCE_FULL,
        TARGET_UNAVAILABLE,
        UNSAFE_DESTINATION,
        TELEPORT_FAILED
    }

    public record TravelResult(Status status, UUID transactionId, long retryAtEpochMillis, boolean auditRecorded) {
    }
}
