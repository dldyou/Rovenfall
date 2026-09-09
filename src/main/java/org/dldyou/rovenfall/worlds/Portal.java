package org.dldyou.rovenfall.worlds;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

public record Portal(
        ResourceKey<Level> originDimension,
        BlockPos origin,
        ResourceKey<Level> destinationDimension,
        BlockPos destination,
        int protectionRadius,
        int safeSearchRadius,
        int cooldownSeconds) {
    public static final int MAX_PORTALS = 1_024;
    public static final int MAX_PROTECTION_RADIUS = 64;
    public static final int MAX_COOLDOWN_SECONDS = 3_600;

    public static final Codec<Portal> CODEC = RecordCodecBuilder.<Portal>create(instance -> instance.group(
            Level.RESOURCE_KEY_CODEC.fieldOf("origin_dimension").forGetter(Portal::originDimension),
            BlockPos.CODEC.fieldOf("origin").forGetter(Portal::origin),
            Level.RESOURCE_KEY_CODEC.fieldOf("destination_dimension").forGetter(Portal::destinationDimension),
            BlockPos.CODEC.fieldOf("destination").forGetter(Portal::destination),
            Codec.INT.fieldOf("protection_radius").forGetter(Portal::protectionRadius),
            Codec.INT.fieldOf("safe_search_radius").forGetter(Portal::safeSearchRadius),
            Codec.INT.fieldOf("cooldown_seconds").forGetter(Portal::cooldownSeconds)
    ).apply(instance, Portal::new)).validate(Portal::validate);

    public Portal {
        origin = origin == null ? null : origin.immutable();
        destination = destination == null ? null : destination.immutable();
    }

    public boolean protects(ResourceKey<Level> dimension, BlockPos position) {
        if (dimension == null || position == null || !originDimension.equals(dimension)) {
            return false;
        }
        long x = (long) position.getX() - origin.getX();
        long z = (long) position.getZ() - origin.getZ();
        return x * x + z * z <= (long) protectionRadius * protectionRadius;
    }

    public static DataResult<Portal> validate(Portal portal) {
        if (portal == null || portal.originDimension == null || portal.origin == null
                || portal.destinationDimension == null || portal.destination == null) {
            return DataResult.error(() -> "Portal has missing fields");
        }
        if (!Level.isInSpawnableBounds(portal.origin) || !Level.isInSpawnableBounds(portal.destination)) {
            return DataResult.error(() -> "Portal position is outside spawnable bounds");
        }
        if (portal.originDimension.equals(portal.destinationDimension)
                && portal.origin.equals(portal.destination)) {
            return DataResult.error(() -> "Portal origin and destination are identical");
        }
        if (portal.protectionRadius < 0 || portal.protectionRadius > MAX_PROTECTION_RADIUS) {
            return DataResult.error(() -> "Portal protection radius is invalid");
        }
        if (portal.safeSearchRadius < 0 || portal.safeSearchRadius > SafeArrivalResolver.MAX_SEARCH_RADIUS) {
            return DataResult.error(() -> "Portal safe search radius is invalid");
        }
        if (portal.cooldownSeconds < 0 || portal.cooldownSeconds > MAX_COOLDOWN_SECONDS) {
            return DataResult.error(() -> "Portal cooldown is invalid");
        }
        return DataResult.success(portal);
    }
}
