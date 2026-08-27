package org.dldyou.rovenfall.mobs;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.SpawnPlacementTypes;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.Silverfish;
import net.minecraft.world.entity.monster.spider.Spider;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.event.entity.RegisterSpawnPlacementsEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.dldyou.rovenfall.Rovenfall;

public final class RovenfallMobEntities {
    public static final Identifier GROVE_STALKER_ID = id("grove_stalker");
    public static final Identifier OREBOUND_BEETLE_ID = id("orebound_beetle");

    private static final DeferredRegister.Entities ENTITY_TYPES = DeferredRegister.createEntities(Rovenfall.MOD_ID);

    public static final DeferredHolder<EntityType<?>, EntityType<GroveStalker>> GROVE_STALKER =
            ENTITY_TYPES.registerEntityType("grove_stalker", GroveStalker::new, MobCategory.MONSTER,
                    builder -> builder.sized(1.4F, 0.9F).clientTrackingRange(8));
    public static final DeferredHolder<EntityType<?>, EntityType<OreboundBeetle>> OREBOUND_BEETLE =
            ENTITY_TYPES.registerEntityType("orebound_beetle", OreboundBeetle::new, MobCategory.MONSTER,
                    builder -> builder.sized(0.4F, 0.3F).clientTrackingRange(8));

    private RovenfallMobEntities() {
    }

    public static void register(IEventBus modBus) {
        ENTITY_TYPES.register(modBus);
        modBus.addListener(RovenfallMobEntities::createAttributes);
        modBus.addListener(RovenfallMobEntities::registerSpawnPlacements);
    }

    static Identifier definitionId(EntityType<?> type) {
        Identifier typeId = BuiltInRegistries.ENTITY_TYPE.getKey(type);
        if (GROVE_STALKER_ID.equals(typeId) || OREBOUND_BEETLE_ID.equals(typeId)) {
            return typeId;
        }
        return null;
    }

    private static void createAttributes(EntityAttributeCreationEvent event) {
        event.put(GROVE_STALKER.get(), Spider.createAttributes()
                .add(Attributes.ATTACK_DAMAGE, 2.0)
                .build());
        event.put(OREBOUND_BEETLE.get(), Silverfish.createAttributes().build());
    }

    private static void registerSpawnPlacements(RegisterSpawnPlacementsEvent event) {
        event.register(
                GROVE_STALKER.get(),
                SpawnPlacementTypes.ON_GROUND,
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                (type, level, reason, pos, random) -> Monster.checkMonsterSpawnRules(
                                type, level, reason, pos, random)
                        && RovenfallMobRuntime.canSpawnNaturally(level.getLevel(), GROVE_STALKER_ID, pos, random),
                RegisterSpawnPlacementsEvent.Operation.REPLACE);
        event.register(
                OREBOUND_BEETLE.get(),
                SpawnPlacementTypes.ON_GROUND,
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                RovenfallMobEntities::canSpawnOreboundBeetle,
                RegisterSpawnPlacementsEvent.Operation.REPLACE);
    }

    private static boolean canSpawnOreboundBeetle(
            EntityType<OreboundBeetle> type,
            ServerLevelAccessor level,
            EntitySpawnReason reason,
            net.minecraft.core.BlockPos pos,
            RandomSource random) {
        return Silverfish.checkSilverfishSpawnRules(type, level, reason, pos, random)
                && RovenfallMobRuntime.canSpawnNaturally(level.getLevel(), OREBOUND_BEETLE_ID, pos, random);
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(Rovenfall.MOD_ID, path);
    }
}
