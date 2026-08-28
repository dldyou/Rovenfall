package org.dldyou.rovenfall.administration;

import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import org.dldyou.rovenfall.claims.ClaimRole;
import org.dldyou.rovenfall.world.PortalDefinition;
import org.dldyou.rovenfall.world.ProtectedRegion;

/** Bounded server-side parsing for administration world-management forms. */
final class AdministrationWorldFormParser {
    private static final int MAX_INPUT_LENGTH = AdministrationTextInputMenu.MAX_INPUT_LENGTH;

    private AdministrationWorldFormParser() {
    }

    static Optional<ClaimRoleForm> parseClaimRole(String input) {
        return parse(input, 2, parts -> {
            Optional<UUID> playerId = uuid(parts.values()[0]);
            Optional<ClaimRole> role = ClaimRole.fromId(parts.values()[1]);
            return playerId.isPresent() && role.isPresent() && role.orElseThrow() != ClaimRole.OWNER
                    ? Optional.of(new ClaimRoleForm(playerId.orElseThrow(), role.orElseThrow(), parts.reason()))
                    : Optional.empty();
        });
    }

    static Optional<ClaimSettingsForm> parseClaimSettings(String input) {
        return parse(input, 2, parts -> {
            Optional<Boolean> entryRestricted = strictBoolean(parts.values()[0]);
            Optional<Boolean> publicInteractions = strictBoolean(parts.values()[1]);
            return entryRestricted.isPresent() && publicInteractions.isPresent()
                    ? Optional.of(new ClaimSettingsForm(
                    entryRestricted.orElseThrow(), publicInteractions.orElseThrow(), parts.reason()))
                    : Optional.empty();
        });
    }

    static Optional<ClaimTargetForm> parseClaimTarget(String input) {
        return parse(input, 1, parts -> uuid(parts.values()[0])
                .map(playerId -> new ClaimTargetForm(playerId, parts.reason())));
    }

    static Optional<RegionCreateForm> parseRegionCreate(String input) {
        return parse(input, 6, parts -> {
            String[] values = parts.values();
            Optional<Identifier> regionId = identifier(values[0]);
            Optional<Identifier> dimensionId = identifier(values[1]);
            Optional<Bounds> bounds = bounds(values, 2);
            return regionId.isPresent() && dimensionId.isPresent() && bounds.isPresent()
                    ? Optional.of(new RegionCreateForm(regionId.orElseThrow(), dimensionId.orElseThrow(),
                    bounds.orElseThrow().minX(), bounds.orElseThrow().minZ(),
                    bounds.orElseThrow().maxX(), bounds.orElseThrow().maxZ(), parts.reason()))
                    : Optional.empty();
        });
    }

    static Optional<RegionEditForm> parseRegionEdit(String input) {
        return parse(input, 5, parts -> {
            String[] values = parts.values();
            Optional<Identifier> dimensionId = identifier(values[0]);
            Optional<Bounds> bounds = bounds(values, 1);
            return dimensionId.isPresent() && bounds.isPresent()
                    ? Optional.of(new RegionEditForm(dimensionId.orElseThrow(),
                    bounds.orElseThrow().minX(), bounds.orElseThrow().minZ(),
                    bounds.orElseThrow().maxX(), bounds.orElseThrow().maxZ(), parts.reason()))
                    : Optional.empty();
        });
    }

    static Optional<PortalCreateForm> parsePortalCreate(String input) {
        return parsePortal(input, true).map(values -> new PortalCreateForm(
                values.portalId().orElseThrow(), values.originDimensionId(), values.originX(), values.originY(), values.originZ(),
                values.destinationDimensionId(), values.destinationX(), values.destinationY(), values.destinationZ(),
                values.radiusChunks(), values.cooldownMillis(), values.policy(), values.allowCombat(), values.reason()));
    }

    static Optional<PortalEditForm> parsePortalEdit(String input) {
        return parsePortal(input, false).map(values -> new PortalEditForm(
                values.originDimensionId(), values.originX(), values.originY(), values.originZ(),
                values.destinationDimensionId(), values.destinationX(), values.destinationY(), values.destinationZ(),
                values.radiusChunks(), values.cooldownMillis(), values.policy(), values.allowCombat(), values.reason()));
    }

    static Optional<ReasonForm> parseReasonOnly(String input) {
        try {
            Optional<Parts> parts = split(input);
            return parts.isPresent() && parts.orElseThrow().values().length == 0
                    ? Optional.of(new ReasonForm(parts.orElseThrow().reason())) : Optional.empty();
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    static Optional<RestoreForm> parseRestore(String input) {
        return parse(input, 1, parts -> uuid(parts.values()[0])
                .map(snapshotId -> new RestoreForm(snapshotId, parts.reason())));
    }

    private static Optional<PortalValues> parsePortal(String input, boolean create) {
        return parse(input, create ? 13 : 12, parts -> {
            String[] values = parts.values();
            int offset = create ? 1 : 0;
            Optional<Identifier> portalId = create ? identifier(values[0]) : Optional.empty();
            Optional<Identifier> originDimensionId = identifier(values[offset]);
            Optional<Identifier> destinationDimensionId = identifier(values[offset + 4]);
            Optional<BlockPos> origin = blockPos(values, offset + 1);
            Optional<BlockPos> destination = blockPos(values, offset + 5);
            long radius = boundedLong(values[offset + 8], 0, PortalDefinition.MAX_PROTECTION_RADIUS_CHUNKS);
            long cooldown = boundedLong(values[offset + 9], 0, PortalDefinition.MAX_COOLDOWN_MILLIS);
            Optional<PortalDefinition.SafeArrivalPolicy> policy = policy(values[offset + 10]);
            Optional<Boolean> allowCombat = strictBoolean(values[offset + 11]);
            if (create && portalId.isEmpty() || originDimensionId.isEmpty() || destinationDimensionId.isEmpty()
                    || origin.isEmpty() || destination.isEmpty() || radius == Long.MIN_VALUE || cooldown == Long.MIN_VALUE
                    || policy.isEmpty() || allowCombat.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new PortalValues(portalId, originDimensionId.orElseThrow(), origin.orElseThrow(),
                    destinationDimensionId.orElseThrow(), destination.orElseThrow(), (int) radius, cooldown,
                    policy.orElseThrow(), allowCombat.orElseThrow(), parts.reason()));
        });
    }

    private static Optional<Bounds> bounds(String[] values, int offset) {
        long minX = boundedLong(values[offset], -ProtectedRegion.MAX_ABSOLUTE_CHUNK, ProtectedRegion.MAX_ABSOLUTE_CHUNK);
        long minZ = boundedLong(values[offset + 1], -ProtectedRegion.MAX_ABSOLUTE_CHUNK, ProtectedRegion.MAX_ABSOLUTE_CHUNK);
        long maxX = boundedLong(values[offset + 2], -ProtectedRegion.MAX_ABSOLUTE_CHUNK, ProtectedRegion.MAX_ABSOLUTE_CHUNK);
        long maxZ = boundedLong(values[offset + 3], -ProtectedRegion.MAX_ABSOLUTE_CHUNK, ProtectedRegion.MAX_ABSOLUTE_CHUNK);
        if (minX == Long.MIN_VALUE || minZ == Long.MIN_VALUE || maxX == Long.MIN_VALUE || maxZ == Long.MIN_VALUE) {
            return Optional.empty();
        }
        long width = maxX - minX + 1;
        long height = maxZ - minZ + 1;
        return width >= 1 && height >= 1 && width <= ProtectedRegion.MAX_SIDE_CHUNKS
                && height <= ProtectedRegion.MAX_SIDE_CHUNKS && width * height <= ProtectedRegion.MAX_AREA_CHUNKS
                ? Optional.of(new Bounds((int) minX, (int) minZ, (int) maxX, (int) maxZ)) : Optional.empty();
    }

    private static Optional<BlockPos> blockPos(String[] values, int offset) {
        long x = boundedLong(values[offset], Integer.MIN_VALUE, Integer.MAX_VALUE);
        long y = boundedLong(values[offset + 1], Integer.MIN_VALUE, Integer.MAX_VALUE);
        long z = boundedLong(values[offset + 2], Integer.MIN_VALUE, Integer.MAX_VALUE);
        if (x == Long.MIN_VALUE || y == Long.MIN_VALUE || z == Long.MIN_VALUE) {
            return Optional.empty();
        }
        BlockPos position = new BlockPos((int) x, (int) y, (int) z);
        return Level.isInSpawnableBounds(position) ? Optional.of(position) : Optional.empty();
    }

    private static Optional<PortalDefinition.SafeArrivalPolicy> policy(String value) {
        return Arrays.stream(PortalDefinition.SafeArrivalPolicy.values())
                .filter(candidate -> candidate.getSerializedName().equals(value)).findFirst();
    }

    private static Optional<Boolean> strictBoolean(String value) {
        return "true".equals(value) ? Optional.of(true) : "false".equals(value) ? Optional.of(false) : Optional.empty();
    }

    private static Optional<UUID> uuid(String value) {
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private static Optional<Identifier> identifier(String value) {
        try {
            Identifier parsed = Identifier.tryParse(value);
            return parsed == null ? Optional.empty() : Optional.of(parsed);
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    private static <T> Optional<T> parse(String input, int fields, FieldParser<T> parser) {
        try {
            Optional<Parts> parts = split(input);
            return parts.isPresent() && parts.orElseThrow().values().length == fields
                    ? parser.parse(parts.orElseThrow()) : Optional.empty();
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    private static Optional<Parts> split(String input) {
        if (input == null || input.length() > MAX_INPUT_LENGTH || hasLineBreak(input)) {
            return Optional.empty();
        }
        int delimiter = input.indexOf('|');
        if (delimiter < 0 || delimiter != input.lastIndexOf('|')) {
            return Optional.empty();
        }
        String fieldText = input.substring(0, delimiter).strip();
        String reason = input.substring(delimiter + 1).strip();
        if (reason.isEmpty() || reason.length() > AdministrationService.MAX_REASON_LENGTH) {
            return Optional.empty();
        }
        if (fieldText.isEmpty()) {
            return Optional.of(new Parts(new String[0], reason));
        }
        String[] values = fieldText.split(",", -1);
        for (int index = 0; index < values.length; index++) {
            values[index] = values[index].strip();
            if (values[index].isEmpty() || hasLineBreak(values[index])) {
                return Optional.empty();
            }
        }
        return Optional.of(new Parts(values, reason));
    }

    private static boolean hasLineBreak(String value) {
        return value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0
                || value.indexOf('\u2028') >= 0 || value.indexOf('\u2029') >= 0;
    }

    private static long boundedLong(String value, long minimum, long maximum) {
        try {
            long parsed = Long.parseLong(value);
            return parsed >= minimum && parsed <= maximum ? parsed : Long.MIN_VALUE;
        } catch (NumberFormatException exception) {
            return Long.MIN_VALUE;
        }
    }

    @FunctionalInterface
    private interface FieldParser<T> {
        Optional<T> parse(Parts parts);
    }

    private record Parts(String[] values, String reason) {
        private Parts {
            values = values.clone();
        }

        @Override
        public String[] values() {
            return values.clone();
        }
    }

    private record Bounds(int minX, int minZ, int maxX, int maxZ) {
    }

    private record PortalValues(
            Optional<Identifier> portalId,
            Identifier originDimensionId,
            BlockPos origin,
            Identifier destinationDimensionId,
            BlockPos destination,
            int radiusChunks,
            long cooldownMillis,
            PortalDefinition.SafeArrivalPolicy policy,
            boolean allowCombat,
            String reason) {
        private int originX() { return origin.getX(); }
        private int originY() { return origin.getY(); }
        private int originZ() { return origin.getZ(); }
        private int destinationX() { return destination.getX(); }
        private int destinationY() { return destination.getY(); }
        private int destinationZ() { return destination.getZ(); }
    }

    record ClaimRoleForm(UUID playerId, ClaimRole role, String reason) {
    }

    record ClaimSettingsForm(boolean entryRestricted, boolean publicInteractions, String reason) {
    }

    record ClaimTargetForm(UUID playerId, String reason) {
    }

    record RegionCreateForm(
            Identifier regionId, Identifier dimensionId, int minChunkX, int minChunkZ,
            int maxChunkX, int maxChunkZ, String reason) {
    }

    record RegionEditForm(
            Identifier dimensionId, int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ, String reason) {
    }

    record PortalCreateForm(
            Identifier portalId, Identifier originDimensionId, int originX, int originY, int originZ,
            Identifier destinationDimensionId, int destinationX, int destinationY, int destinationZ,
            int radiusChunks, long cooldownMillis, PortalDefinition.SafeArrivalPolicy policy,
            boolean allowCombat, String reason) {
    }

    record PortalEditForm(
            Identifier originDimensionId, int originX, int originY, int originZ,
            Identifier destinationDimensionId, int destinationX, int destinationY, int destinationZ,
            int radiusChunks, long cooldownMillis, PortalDefinition.SafeArrivalPolicy policy,
            boolean allowCombat, String reason) {
    }

    record ReasonForm(String reason) {
    }

    record RestoreForm(UUID snapshotId, String reason) {
    }
}
