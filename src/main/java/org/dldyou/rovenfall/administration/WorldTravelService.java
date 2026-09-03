package org.dldyou.rovenfall.administration;

import java.time.Instant;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import org.dldyou.rovenfall.Rovenfall;
import org.dldyou.rovenfall.claims.ClaimConfig;
import org.dldyou.rovenfall.claims.ClaimKey;
import org.dldyou.rovenfall.worlds.SafeArrivalResolver;
import org.dldyou.rovenfall.worlds.WorldConfig;

public final class WorldTravelService {
    private static final long DENIED_AUDIT_INTERVAL_MILLIS = 1_000L;
    private static final long ATTEMPT_INTERVAL_MILLIS = 1_000L;
    private static final Identifier HUB_TO_WILDERNESS =
            Identifier.fromNamespaceAndPath(Rovenfall.MOD_ID, "spawn_hub_to_wilderness");
    private static final Identifier WILDERNESS_TO_HUB =
            Identifier.fromNamespaceAndPath(Rovenfall.MOD_ID, "spawn_wilderness_to_hub");
    private static final Map<MinecraftServer, Map<TransitKey, Long>> NEXT_TRANSIT_BY_SERVER = new IdentityHashMap<>();
    private static final Map<MinecraftServer, Map<UUID, Long>> NEXT_ATTEMPT_BY_SERVER = new IdentityHashMap<>();
    private static final Map<MinecraftServer, Map<UUID, Long>> COMBAT_UNTIL_BY_SERVER = new IdentityHashMap<>();

    private WorldTravelService() {
    }

    public static void register(IEventBus eventBus) {
        eventBus.addListener(WorldTravelService::onPlayerLoggedOut);
        eventBus.addListener(WorldTravelService::onLivingDamage);
        eventBus.addListener(WorldTravelService::onServerStopped);
    }

    public static Result transit(ServerPlayer player, long timestampEpochMillis) {
        return transit(player, null, timestampEpochMillis);
    }

    public static Result transit(
            ServerPlayer player, Identifier requestedPortalId, long timestampEpochMillis) {
        if (player == null || timestampEpochMillis < 0) {
            return result(Status.INVALID_REQUEST, null, requestedPortalId, 0, false);
        }
        MinecraftServer server = player.level().getServer();
        PlatformSavedData state = PlatformSavedData.get(server);
        if (!state.isWritable()) {
            return result(Status.READ_ONLY_SCHEMA, null, requestedPortalId, 0, false);
        }
        if (player.isPassenger() || !player.getPassengers().isEmpty()) {
            return denied(state, player, Status.MOUNTED, null, requestedPortalId, timestampEpochMillis, 0);
        }
        ServerLevel origin = player.level();
        PortalRoute route = resolveRoute(state, server, origin, requestedPortalId).orElse(null);
        if (route == null) {
            Status status = requestedPortalId == null ? Status.UNSUPPORTED_ORIGIN : Status.PORTAL_NOT_FOUND;
            return denied(state, player, status, null, requestedPortalId, timestampEpochMillis, 0);
        }
        if (!route.originDimension().equals(origin.dimension())) {
            return denied(state, player, Status.UNSUPPORTED_ORIGIN, route.destinationDimension(), route.id(),
                    timestampEpochMillis, 0);
        }
        if (RestartWildernessResetService.isResetPending(server)
                && (origin.dimension().equals(WorldCombatService.WILDERNESS_DIMENSION)
                || route.destinationDimension().equals(WorldCombatService.WILDERNESS_DIMENSION))) {
            return denied(state, player, Status.RESET_PENDING, route.destinationDimension(), route.id(),
                    timestampEpochMillis, 0);
        }
        long nextTransitAt;
        long nextAttemptAt;
        try {
            nextTransitAt = Math.addExact(
                    timestampEpochMillis,
                    Math.multiplyExact((long) route.cooldownSeconds(), 1_000L));
            nextAttemptAt = Math.addExact(timestampEpochMillis, ATTEMPT_INTERVAL_MILLIS);
        } catch (ArithmeticException exception) {
            return denied(state, player, Status.INVALID_REQUEST, route.destinationDimension(), route.id(),
                    timestampEpochMillis, 0);
        }
        ResourceKey<Level> destinationKey = route.destinationDimension();
        if (!insideRing(player.position(), route.origin(), route.activationRadius())) {
            return denied(state, player, Status.OUTSIDE_PORTAL_RING, destinationKey, route.id(),
                    timestampEpochMillis, 0);
        }
        Map<UUID, Long> attempts = NEXT_ATTEMPT_BY_SERVER.computeIfAbsent(server, ignored -> new HashMap<>());
        long nextAttempt = attempts.getOrDefault(player.getUUID(), 0L);
        if (nextAttempt > timestampEpochMillis) {
            return denied(state, player, Status.RATE_LIMITED, destinationKey, route.id(),
                    timestampEpochMillis, 1);
        }
        attempts.put(player.getUUID(), nextAttemptAt);
        long combatUntil = COMBAT_UNTIL_BY_SERVER
                .computeIfAbsent(server, ignored -> new HashMap<>())
                .getOrDefault(player.getUUID(), 0L);
        if (combatUntil > timestampEpochMillis) {
            long remainingSeconds = Math.max(1L, (combatUntil - timestampEpochMillis + 999L) / 1_000L);
            return denied(state, player, Status.IN_COMBAT, destinationKey, route.id(),
                    timestampEpochMillis, remainingSeconds);
        }
        TransitKey transitKey = new TransitKey(player.getUUID(), route.id());
        long nextTransit = NEXT_TRANSIT_BY_SERVER
                .computeIfAbsent(server, ignored -> new HashMap<>())
                .getOrDefault(transitKey, 0L);
        if (nextTransit > timestampEpochMillis) {
            long remainingSeconds = Math.max(1L, (nextTransit - timestampEpochMillis + 999L) / 1_000L);
            return denied(state, player, Status.COOLDOWN, destinationKey, route.id(),
                    timestampEpochMillis, remainingSeconds);
        }
        ServerLevel destination = server.getLevel(destinationKey);
        if (destination == null) {
            return denied(state, player, Status.DESTINATION_UNAVAILABLE, destinationKey, route.id(),
                    timestampEpochMillis, 0);
        }
        var arrival = SafeArrivalResolver.resolve(
                destination,
                route.destination().orElseGet(() -> destination.getRespawnData().pos()),
                route.safeSearchRadius(),
                position -> mayEnter(state, player, destination, position));
        if (arrival.status() != SafeArrivalResolver.Status.FOUND) {
            return denied(state, player, Status.NO_SAFE_ARRIVAL, destinationKey, route.id(),
                    timestampEpochMillis, 0);
        }
        BlockPos target = arrival.position().orElseThrow();
        BlockPos originPosition = player.blockPosition();
        var transition = new TeleportTransition(
                destination,
                Vec3.atBottomCenterOf(target),
                Vec3.ZERO,
                player.getYRot(),
                player.getXRot(),
                Set.of(),
                TeleportTransition.PLAY_PORTAL_SOUND);
        if (player.teleport(transition) == null) {
            return denied(state, player, Status.TELEPORT_REJECTED, destinationKey, route.id(),
                    timestampEpochMillis, 0);
        }
        NEXT_TRANSIT_BY_SERVER.get(server).put(transitKey, nextTransitAt);
        recordSuccessfulTransit(
                state,
                player.getUUID(),
                route.id(),
                origin.dimension(),
                originPosition,
                destination.dimension(),
                target,
                timestampEpochMillis,
                UUID.randomUUID());
        return result(Status.SUCCESS, destinationKey, route.id(), 0, true);
    }

    static boolean recordSuccessfulTransit(
            PlatformSavedData state,
            UUID actorId,
            ResourceKey<Level> originDimension,
            BlockPos originPosition,
            ResourceKey<Level> destinationDimension,
            BlockPos destinationPosition,
            long timestampEpochMillis,
            UUID transactionId) {
        return recordSuccessfulTransit(
                state,
                actorId,
                null,
                originDimension,
                originPosition,
                destinationDimension,
                destinationPosition,
                timestampEpochMillis,
                transactionId);
    }

    static boolean recordSuccessfulTransit(
            PlatformSavedData state,
            UUID actorId,
            Identifier portalId,
            ResourceKey<Level> originDimension,
            BlockPos originPosition,
            ResourceKey<Level> destinationDimension,
            BlockPos destinationPosition,
            long timestampEpochMillis,
            UUID transactionId) {
        if (state == null || !state.isWritable() || actorId == null
                || originDimension == null || originPosition == null
                || destinationDimension == null || destinationPosition == null
                || timestampEpochMillis < 0 || transactionId == null
                || transactionId.equals(new UUID(0L, 0L))) {
            return false;
        }
        state.commitAudit(new AuditEntry(
                timestampEpochMillis,
                actorId,
                Identifier.fromNamespaceAndPath(Rovenfall.MOD_ID, "portal_travel"),
                portalId == null ? destinationDimension.identifier().toString() : portalId.toString(),
                Optional.of(originDimension.identifier()),
                Optional.of(originPosition.immutable()),
                originDimension.identifier() + "@" + originPosition.toShortString(),
                destinationDimension.identifier() + "@" + destinationPosition.toShortString(),
                portalId == null ? "spawn_ring" : "portal",
                transactionId));
        return true;
    }

    private static Optional<PortalRoute> resolveRoute(
            PlatformSavedData state,
            MinecraftServer server,
            ServerLevel origin,
            Identifier requestedPortalId) {
        if (requestedPortalId != null) {
            return state.portal(requestedPortalId).map(portal -> new PortalRoute(
                    requestedPortalId,
                    portal.originDimension(),
                    portal.origin(),
                    portal.destinationDimension(),
                    Optional.of(portal.destination()),
                    portal.protectionRadius(),
                    portal.safeSearchRadius(),
                    portal.cooldownSeconds()));
        }
        Optional<ResourceKey<Level>> destination = destinationFor(
                origin.dimension(), server.overworld().dimension(), WorldCombatService.WILDERNESS_DIMENSION);
        if (destination.isEmpty()) {
            return Optional.empty();
        }
        Identifier portalId = origin.dimension().equals(server.overworld().dimension())
                ? HUB_TO_WILDERNESS
                : WILDERNESS_TO_HUB;
        return Optional.of(new PortalRoute(
                portalId,
                origin.dimension(),
                origin.getRespawnData().pos(),
                destination.orElseThrow(),
                Optional.empty(),
                WorldConfig.portalActivationRadius(),
                WorldConfig.portalSearchRadius(),
                WorldConfig.portalCooldownSeconds()));
    }

    static Optional<ResourceKey<Level>> destinationFor(
            ResourceKey<Level> current,
            ResourceKey<Level> hub,
            ResourceKey<Level> wilderness) {
        if (current == null || hub == null || wilderness == null || hub.equals(wilderness)) {
            return Optional.empty();
        }
        if (current.equals(hub)) {
            return Optional.of(wilderness);
        }
        return current.equals(wilderness) ? Optional.of(hub) : Optional.empty();
    }

    static boolean insideRing(Vec3 playerPosition, BlockPos center, int radius) {
        if (playerPosition == null || center == null || radius < 0) {
            return false;
        }
        double x = playerPosition.x - (center.getX() + 0.5);
        double z = playerPosition.z - (center.getZ() + 0.5);
        return x * x + z * z <= (double) radius * radius;
    }

    private static boolean mayEnter(
            PlatformSavedData state,
            ServerPlayer player,
            ServerLevel destination,
            BlockPos position) {
        var hub = destination.getServer().overworld();
        return ClaimProtectionService.evaluate(
                state,
                player.getUUID(),
                false,
                hub.dimension(),
                hub.getRespawnData().pos(),
                ClaimConfig.protectedSpawnRadiusChunks(),
                state.isAdministratorProtected(destination.dimension(), position),
                ClaimKey.at(destination.dimension(), position),
                ClaimProtectionService.Action.ENTRY).allowed();
    }

    private static Result denied(
            PlatformSavedData state,
            ServerPlayer player,
            Status status,
            ResourceKey<Level> destination,
            Identifier portalId,
            long timestampEpochMillis,
            long retryAfterSeconds) {
        BlockPos position = player.blockPosition();
        String snapshot = player.level().dimension().identifier() + "@" + position.toShortString();
        boolean audited = state.appendDeniedAudit(new AuditEntry(
                timestampEpochMillis,
                player.getUUID(),
                Identifier.fromNamespaceAndPath(Rovenfall.MOD_ID, "portal_travel_denied"),
                portalId == null ? "none" : portalId.toString(),
                Optional.of(player.level().dimension().identifier()),
                Optional.of(position),
                snapshot,
                snapshot,
                status.id,
                UUID.randomUUID()), DENIED_AUDIT_INTERVAL_MILLIS);
        return result(status, destination, portalId, retryAfterSeconds, audited);
    }

    private static Result result(
            Status status,
            ResourceKey<Level> destination,
            Identifier portalId,
            long retryAfterSeconds,
            boolean auditRecorded) {
        return new Result(
                status, Optional.ofNullable(destination), Optional.ofNullable(portalId), retryAfterSeconds, auditRecorded);
    }

    private static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        MinecraftServer server = event.getEntity().level().getServer();
        removeTransitPlayer(server, event.getEntity().getUUID());
        removePlayer(NEXT_ATTEMPT_BY_SERVER, server, event.getEntity().getUUID());
        removePlayer(COMBAT_UNTIL_BY_SERVER, server, event.getEntity().getUUID());
    }

    private static void onLivingDamage(LivingDamageEvent.Post event) {
        if (event.getHealthDamage() <= 0 && event.getBlockedDamage() <= 0) {
            return;
        }
        ServerPlayer target = event.getEntity() instanceof ServerPlayer player ? player : null;
        ServerPlayer attacker = event.getSource().getEntity() instanceof ServerPlayer player ? player : null;
        if (target == null && attacker == null) {
            return;
        }
        MinecraftServer server = target != null ? target.level().getServer() : attacker.level().getServer();
        long duration = WorldConfig.portalCombatLockSeconds() * 1_000L;
        long until = Instant.now().toEpochMilli() + duration;
        Map<UUID, Long> locks = COMBAT_UNTIL_BY_SERVER.computeIfAbsent(server, ignored -> new HashMap<>());
        if (target != null) {
            locks.merge(target.getUUID(), until, Math::max);
        }
        if (attacker != null) {
            locks.merge(attacker.getUUID(), until, Math::max);
        }
    }

    private static void removePlayer(
            Map<MinecraftServer, Map<UUID, Long>> values,
            MinecraftServer server,
            UUID playerId) {
        Map<UUID, Long> byPlayer = values.get(server);
        if (byPlayer != null) {
            byPlayer.remove(playerId);
        }
    }

    private static void removeTransitPlayer(MinecraftServer server, UUID playerId) {
        Map<TransitKey, Long> byPortal = NEXT_TRANSIT_BY_SERVER.get(server);
        if (byPortal != null) {
            byPortal.keySet().removeIf(key -> key.playerId().equals(playerId));
        }
    }

    private static void onServerStopped(ServerStoppedEvent event) {
        NEXT_TRANSIT_BY_SERVER.remove(event.getServer());
        NEXT_ATTEMPT_BY_SERVER.remove(event.getServer());
        COMBAT_UNTIL_BY_SERVER.remove(event.getServer());
    }

    public enum Status {
        SUCCESS("success"),
        INVALID_REQUEST("invalid_request"),
        READ_ONLY_SCHEMA("read_only_schema"),
        MOUNTED("mounted"),
        PORTAL_NOT_FOUND("portal_not_found"),
        UNSUPPORTED_ORIGIN("unsupported_origin"),
        OUTSIDE_PORTAL_RING("outside_portal_ring"),
        RATE_LIMITED("rate_limited"),
        IN_COMBAT("in_combat"),
        RESET_PENDING("reset_pending"),
        COOLDOWN("cooldown"),
        DESTINATION_UNAVAILABLE("destination_unavailable"),
        NO_SAFE_ARRIVAL("no_safe_arrival"),
        TELEPORT_REJECTED("teleport_rejected");

        private final String id;

        Status(String id) {
            this.id = id;
        }
    }

    public record Result(
            Status status,
            Optional<ResourceKey<Level>> destination,
            Optional<Identifier> portalId,
            long retryAfterSeconds,
            boolean auditRecorded) {
        public Result {
            destination = destination == null ? Optional.empty() : destination;
            portalId = portalId == null ? Optional.empty() : portalId;
        }
    }

    private record PortalRoute(
            Identifier id,
            ResourceKey<Level> originDimension,
            BlockPos origin,
            ResourceKey<Level> destinationDimension,
            Optional<BlockPos> destination,
            int activationRadius,
            int safeSearchRadius,
            int cooldownSeconds) {
    }

    private record TransitKey(UUID playerId, Identifier portalId) {
    }
}
