package org.dldyou.rovenfall.mobs;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.SpawnPlacementTypes;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.skeleton.Stray;
import net.minecraft.world.entity.monster.zombie.Drowned;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.RegisterSpawnPlacementsEvent;
import net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent;
import org.dldyou.rovenfall.administration.WorldCombatService;

public final class MobSpawnPolicy {
    private MobSpawnPolicy() {
    }

    public static void registerGameplayEvents(IEventBus eventBus) {
        eventBus.addListener(MobSpawnPolicy::onFinalizeSpawn);
    }

    static void registerSpawnPlacements(RegisterSpawnPlacementsEvent event) {
        event.register(
                RovenfallEntityTypes.ASHEN_STALKER.get(),
                SpawnPlacementTypes.ON_GROUND,
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                MobSpawnPolicy::canNaturallySpawn,
                RegisterSpawnPlacementsEvent.Operation.REPLACE);
        event.register(
                RovenfallEntityTypes.RUNEBOUND_ARCHER.get(),
                SpawnPlacementTypes.ON_GROUND,
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                MobSpawnPolicy::canNaturallySpawn,
                RegisterSpawnPlacementsEvent.Operation.REPLACE);
        event.register(
                RovenfallEntityTypes.MIREFANG.get(),
                SpawnPlacementTypes.ON_GROUND,
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                MobSpawnPolicy::canNaturallySpawn,
                RegisterSpawnPlacementsEvent.Operation.REPLACE);
        event.register(
                RovenfallEntityTypes.CINDER_WISP.get(),
                SpawnPlacementTypes.ON_GROUND,
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                MobSpawnPolicy::canNaturallySpawn,
                RegisterSpawnPlacementsEvent.Operation.REPLACE);
        event.register(
                RovenfallEntityTypes.FROSTBOUND_REAVER.get(),
                SpawnPlacementTypes.ON_GROUND,
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                MobSpawnPolicy::canFrostboundReaverSpawn,
                RegisterSpawnPlacementsEvent.Operation.REPLACE);
        event.register(
                RovenfallEntityTypes.TIDEBOUND_RAIDER.get(),
                SpawnPlacementTypes.IN_WATER,
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                MobSpawnPolicy::canTideboundRaiderSpawn,
                RegisterSpawnPlacementsEvent.Operation.REPLACE);
        event.register(
                RovenfallEntityTypes.DEEPSTONE_HUSK.get(),
                SpawnPlacementTypes.ON_GROUND,
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                MobSpawnPolicy::canNaturallySpawn,
                RegisterSpawnPlacementsEvent.Operation.REPLACE);
    }

    static <T extends Monster> boolean canNaturallySpawn(
            EntityType<T> entityType,
            ServerLevelAccessor level,
            EntitySpawnReason reason,
            BlockPos position,
            RandomSource random) {
        return allowedOrdinarySpawn(level.getLevel().dimension(), reason)
                && naturalReason(reason)
                && Monster.checkMonsterSpawnRules(entityType, level, reason, position, random);
    }

    static boolean canFrostboundReaverSpawn(
            EntityType<FrostboundReaver> entityType,
            ServerLevelAccessor level,
            EntitySpawnReason reason,
            BlockPos position,
            RandomSource random) {
        return allowedOrdinarySpawn(level.getLevel().dimension(), reason)
                && naturalReason(reason)
                && Stray.checkStraySpawnRules(entityType, level, reason, position, random);
    }

    static boolean canTideboundRaiderSpawn(
            EntityType<TideboundRaider> entityType,
            ServerLevelAccessor level,
            EntitySpawnReason reason,
            BlockPos position,
            RandomSource random) {
        return allowedOrdinarySpawn(level.getLevel().dimension(), reason)
                && naturalReason(reason)
                && Drowned.checkDrownedSpawnRules(entityType, level, reason, position, random);
    }

    private static void onFinalizeSpawn(FinalizeSpawnEvent event) {
        if (!isRovenfallOrdinaryMob(event.getEntity())) {
            return;
        }
        if (!allowedOrdinarySpawn(event.getLevel().getLevel().dimension(), event.getSpawnType())) {
            event.setSpawnCancelled(true);
        }
    }

    public static boolean isRovenfallOrdinaryMob(net.minecraft.world.entity.Entity entity) {
        return entity.getType() == RovenfallEntityTypes.ASHEN_STALKER.get()
                || entity.getType() == RovenfallEntityTypes.RUNEBOUND_ARCHER.get()
                || entity.getType() == RovenfallEntityTypes.MIREFANG.get()
                || entity.getType() == RovenfallEntityTypes.CINDER_WISP.get()
                || entity.getType() == RovenfallEntityTypes.FROSTBOUND_REAVER.get()
                || entity.getType() == RovenfallEntityTypes.TIDEBOUND_RAIDER.get()
                || entity.getType() == RovenfallEntityTypes.DEEPSTONE_HUSK.get();
    }

    public static boolean naturalReason(EntitySpawnReason reason) {
        return reason == EntitySpawnReason.NATURAL || reason == EntitySpawnReason.CHUNK_GENERATION;
    }

    public static boolean allowedOrdinarySpawn(ResourceKey<Level> dimension, EntitySpawnReason reason) {
        return WorldCombatService.WILDERNESS_DIMENSION.equals(dimension)
                && (naturalReason(reason)
                        || reason == EntitySpawnReason.COMMAND
                        || reason == EntitySpawnReason.SPAWN_ITEM_USE
                        || reason == EntitySpawnReason.EVENT);
    }
}
