package org.dldyou.rovenfall.worlds;

import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;

public final class SafeArrivalResolver {
    public static final int MAX_SEARCH_RADIUS = 128;
    private static final Set<Block> DANGEROUS_BLOCKS = Set.of(
            Blocks.CACTUS,
            Blocks.MAGMA_BLOCK,
            Blocks.POWDER_SNOW,
            Blocks.SWEET_BERRY_BUSH,
            Blocks.WITHER_ROSE,
            Blocks.POINTED_DRIPSTONE,
            Blocks.LAVA_CAULDRON);

    private SafeArrivalResolver() {
    }

    public static Result resolve(
            ServerLevel level,
            BlockPos center,
            int searchRadius,
            Predicate<BlockPos> mayEnter) {
        if (level == null || center == null || mayEnter == null
                || searchRadius < 0 || searchRadius > MAX_SEARCH_RADIUS) {
            return new Result(Status.INVALID_REQUEST, Optional.empty());
        }
        BlockPos found = candidate(level, center.getX(), center.getZ(), mayEnter);
        if (found != null) {
            return new Result(Status.FOUND, Optional.of(found));
        }
        for (int radius = 1; radius <= searchRadius; radius++) {
            for (int offset = -radius; offset <= radius; offset++) {
                found = candidate(level, center.getX() + offset, center.getZ() - radius, mayEnter);
                if (found != null) {
                    return new Result(Status.FOUND, Optional.of(found));
                }
                found = candidate(level, center.getX() + offset, center.getZ() + radius, mayEnter);
                if (found != null) {
                    return new Result(Status.FOUND, Optional.of(found));
                }
            }
            for (int offset = -radius + 1; offset < radius; offset++) {
                found = candidate(level, center.getX() - radius, center.getZ() + offset, mayEnter);
                if (found != null) {
                    return new Result(Status.FOUND, Optional.of(found));
                }
                found = candidate(level, center.getX() + radius, center.getZ() + offset, mayEnter);
                if (found != null) {
                    return new Result(Status.FOUND, Optional.of(found));
                }
            }
        }
        return new Result(Status.NOT_FOUND, Optional.empty());
    }

    private static BlockPos candidate(
            ServerLevel level,
            int x,
            int z,
            Predicate<BlockPos> mayEnter) {
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
        BlockPos feet = new BlockPos(x, y, z);
        return isSafe(level, feet) && mayEnter.test(feet) ? feet.immutable() : null;
    }

    static boolean isSafe(ServerLevel level, BlockPos feet) {
        BlockPos floor = feet.below();
        BlockPos head = feet.above();
        if (!Level.isInSpawnableBounds(feet)
                || !level.isInWorldBounds(floor)
                || !level.isInWorldBounds(head)
                || !level.getWorldBorder().isWithinBounds(feet)) {
            return false;
        }
        var floorState = level.getBlockState(floor);
        var feetState = level.getBlockState(feet);
        var headState = level.getBlockState(head);
        if (!floorState.isFaceSturdy(level, floor, Direction.UP)
                || !feetState.getCollisionShape(level, feet).isEmpty()
                || !headState.getCollisionShape(level, head).isEmpty()
                || !feetState.getFluidState().isEmpty()
                || !headState.getFluidState().isEmpty()) {
            return false;
        }
        for (int y = -1; y <= 1; y++) {
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    if (Math.abs(x) + Math.abs(z) > 1) {
                        continue;
                    }
                    var state = level.getBlockState(feet.offset(x, y, z));
                    if (DANGEROUS_BLOCKS.contains(state.getBlock())
                            || state.is(BlockTags.FIRE)
                            || state.is(BlockTags.CAMPFIRES)
                            || state.is(BlockTags.PORTALS)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    public enum Status {
        FOUND,
        INVALID_REQUEST,
        NOT_FOUND
    }

    public record Result(Status status, Optional<BlockPos> position) {
        public Result {
            position = position == null ? Optional.empty() : position;
        }
    }
}
