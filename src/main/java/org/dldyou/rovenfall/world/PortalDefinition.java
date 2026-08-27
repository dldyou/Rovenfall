package org.dldyou.rovenfall.world;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.Level;

public record PortalDefinition(
        UUID administratorId,
        Endpoint origin,
        Endpoint destination,
        int protectionRadiusChunks,
        long cooldownMillis,
        SafeArrivalPolicy safeArrivalPolicy,
        boolean allowCombat) {
    public static final int MAX_PROTECTION_RADIUS_CHUNKS = 8;
    public static final long MAX_COOLDOWN_MILLIS = 86_400_000L;
    public static final double MAX_USE_DISTANCE = 8.0D;
    public static final Codec<PortalDefinition> CODEC = RecordCodecBuilder.<PortalDefinition>create(instance -> instance.group(
            UUIDUtil.STRING_CODEC.fieldOf("administrator").forGetter(PortalDefinition::administratorId),
            Endpoint.CODEC.fieldOf("origin").forGetter(PortalDefinition::origin),
            Endpoint.CODEC.fieldOf("destination").forGetter(PortalDefinition::destination),
            Codec.INT.fieldOf("protection_radius_chunks").forGetter(PortalDefinition::protectionRadiusChunks),
            Codec.LONG.fieldOf("cooldown_millis").forGetter(PortalDefinition::cooldownMillis),
            SafeArrivalPolicy.CODEC.fieldOf("safe_arrival_policy").forGetter(PortalDefinition::safeArrivalPolicy),
            Codec.BOOL.fieldOf("allow_combat").forGetter(PortalDefinition::allowCombat)
    ).apply(instance, PortalDefinition::new)).validate(PortalDefinition::validate);

    public boolean isValid() {
        return administratorId != null && origin != null && origin.isValid()
                && destination != null && destination.isValid()
                && protectionRadiusChunks >= 0
                && protectionRadiusChunks <= MAX_PROTECTION_RADIUS_CHUNKS
                && cooldownMillis >= 0 && cooldownMillis <= MAX_COOLDOWN_MILLIS
                && safeArrivalPolicy != null;
    }

    public ProtectedRegion protectedRegion(Endpoint endpoint) {
        int chunkX = endpoint.position().getX() >> 4;
        int chunkZ = endpoint.position().getZ() >> 4;
        return new ProtectedRegion(
                administratorId,
                endpoint.dimension(),
                chunkX - protectionRadiusChunks,
                chunkZ - protectionRadiusChunks,
                chunkX + protectionRadiusChunks,
                chunkZ + protectionRadiusChunks);
    }

    public static Identifier originProtectionId(Identifier portalId) {
        return protectionId(portalId, "origin");
    }

    public static Identifier destinationProtectionId(Identifier portalId) {
        return protectionId(portalId, "destination");
    }

    private static Identifier protectionId(Identifier portalId, String endpoint) {
        return Identifier.fromNamespaceAndPath(
                "rovenfall", "portal/" + portalId.getNamespace() + "/" + portalId.getPath() + "/" + endpoint);
    }

    public String auditSummary() {
        return "administrator=" + administratorId
                + ";origin=" + origin.auditSummary()
                + ";destination=" + destination.auditSummary()
                + ";radius=" + protectionRadiusChunks
                + ";cooldown=" + cooldownMillis
                + ";safe=" + safeArrivalPolicy.getSerializedName()
                + ";allow_combat=" + allowCombat;
    }

    private static DataResult<PortalDefinition> validate(PortalDefinition definition) {
        return definition != null && definition.isValid()
                ? DataResult.success(definition)
                : DataResult.error(() -> "Portal definition is invalid");
    }

    public record Endpoint(ResourceKey<Level> dimension, BlockPos position) {
        public static final Codec<Endpoint> CODEC = RecordCodecBuilder.<Endpoint>create(instance -> instance.group(
                Level.RESOURCE_KEY_CODEC.fieldOf("dimension").forGetter(Endpoint::dimension),
                BlockPos.CODEC.fieldOf("position").forGetter(Endpoint::position)
        ).apply(instance, Endpoint::new)).validate(Endpoint::validate);

        public boolean isValid() {
            return dimension != null && position != null && Level.isInSpawnableBounds(position);
        }

        public String auditSummary() {
            return dimension.identifier() + "@" + position.getX() + "," + position.getY() + "," + position.getZ();
        }

        private static DataResult<Endpoint> validate(Endpoint endpoint) {
            return endpoint != null && endpoint.isValid()
                    ? DataResult.success(endpoint)
                    : DataResult.error(() -> "Portal endpoint is outside spawnable bounds");
        }
    }

    public enum SafeArrivalPolicy implements StringRepresentable {
        EXACT("exact"),
        NEAREST_SAFE("nearest_safe");

        public static final Codec<SafeArrivalPolicy> CODEC = StringRepresentable.fromEnum(SafeArrivalPolicy::values);
        private final String id;

        SafeArrivalPolicy(String id) {
            this.id = id;
        }

        @Override
        public String getSerializedName() {
            return id;
        }
    }
}
