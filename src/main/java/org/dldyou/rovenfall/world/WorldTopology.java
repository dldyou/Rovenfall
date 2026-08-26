package org.dldyou.rovenfall.world;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.LevelStem;
import org.dldyou.rovenfall.Rovenfall;

public final class WorldTopology {
    private static final Identifier WILDERNESS_ID = Identifier.fromNamespaceAndPath(Rovenfall.MOD_ID, "wilderness");
    public static final ResourceKey<Level> HUB = Level.OVERWORLD;
    public static final ResourceKey<Level> WILDERNESS = ResourceKey.create(
            Registries.DIMENSION,
            WILDERNESS_ID);
    public static final ResourceKey<LevelStem> WILDERNESS_STEM = ResourceKey.create(
            Registries.LEVEL_STEM,
            WILDERNESS_ID);

    private WorldTopology() {
    }

    public static boolean isHub(ResourceKey<Level> dimension) {
        return HUB.equals(dimension);
    }

    public static boolean isWilderness(ResourceKey<Level> dimension) {
        return WILDERNESS.equals(dimension);
    }

    public static boolean allowsClaims(ResourceKey<Level> dimension) {
        return isHub(dimension);
    }

    public static boolean allowsOrdinaryBuilding(ResourceKey<Level> dimension) {
        return isWilderness(dimension);
    }
}
