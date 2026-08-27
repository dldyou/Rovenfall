package org.dldyou.rovenfall.world;

import java.nio.file.Path;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;
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

    public static Path wildernessPath(MinecraftServer server) {
        return DimensionType.getStorageFolder(WILDERNESS, server.getWorldPath(LevelResource.ROOT));
    }
}
