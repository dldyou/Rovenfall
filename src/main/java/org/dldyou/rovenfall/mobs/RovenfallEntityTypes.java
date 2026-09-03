package org.dldyou.rovenfall.mobs;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.entity.monster.skeleton.AbstractSkeleton;
import net.minecraft.world.entity.monster.spider.CaveSpider;
import net.minecraft.world.entity.monster.zombie.Drowned;
import net.minecraft.world.entity.monster.zombie.Husk;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.dldyou.rovenfall.Rovenfall;

public final class RovenfallEntityTypes {
    private static final DeferredRegister.Entities ENTITIES = DeferredRegister.createEntities(Rovenfall.MOD_ID);

    public static final DeferredHolder<EntityType<?>, EntityType<AshenStalker>> ASHEN_STALKER =
            ENTITIES.registerEntityType(
                    "ashen_stalker",
                    AshenStalker::new,
                    MobCategory.MONSTER,
                    builder -> builder.sized(0.6F, 1.95F).clientTrackingRange(8));

    public static final DeferredHolder<EntityType<?>, EntityType<RuneboundArcher>> RUNEBOUND_ARCHER =
            ENTITIES.registerEntityType(
                    "runebound_archer",
                    RuneboundArcher::new,
                    MobCategory.MONSTER,
                    builder -> builder.sized(0.6F, 1.99F).clientTrackingRange(8));

    public static final DeferredHolder<EntityType<?>, EntityType<Mirefang>> MIREFANG =
            ENTITIES.registerEntityType(
                    "mirefang",
                    Mirefang::new,
                    MobCategory.MONSTER,
                    builder -> builder.sized(0.7F, 0.5F).clientTrackingRange(8));

    public static final DeferredHolder<EntityType<?>, EntityType<CinderWisp>> CINDER_WISP =
            ENTITIES.registerEntityType(
                    "cinder_wisp",
                    CinderWisp::new,
                    MobCategory.MONSTER,
                    builder -> builder.fireImmune().sized(0.6F, 1.8F).clientTrackingRange(10));

    public static final DeferredHolder<EntityType<?>, EntityType<FrostboundReaver>> FROSTBOUND_REAVER =
            ENTITIES.registerEntityType(
                    "frostbound_reaver",
                    FrostboundReaver::new,
                    MobCategory.MONSTER,
                    builder -> builder.sized(0.6F, 1.99F).clientTrackingRange(8));

    public static final DeferredHolder<EntityType<?>, EntityType<TideboundRaider>> TIDEBOUND_RAIDER =
            ENTITIES.registerEntityType(
                    "tidebound_raider",
                    TideboundRaider::new,
                    MobCategory.MONSTER,
                    builder -> builder.sized(0.6F, 1.95F).clientTrackingRange(8));

    public static final DeferredHolder<EntityType<?>, EntityType<DeepstoneHusk>> DEEPSTONE_HUSK =
            ENTITIES.registerEntityType(
                    "deepstone_husk",
                    DeepstoneHusk::new,
                    MobCategory.MONSTER,
                    builder -> builder.sized(0.6F, 1.95F).clientTrackingRange(8));

    public static final DeferredHolder<EntityType<?>, EntityType<ArenaWarden>> ARENA_WARDEN =
            ENTITIES.registerEntityType(
                    "arena_warden",
                    ArenaWarden::new,
                    MobCategory.MONSTER,
                    builder -> builder.sized(0.9F, 2.9F).clientTrackingRange(12).updateInterval(2));

    private RovenfallEntityTypes() {
    }

    public static void register(IEventBus modBus) {
        ENTITIES.register(modBus);
        modBus.addListener(RovenfallEntityTypes::registerAttributes);
        modBus.addListener(MobSpawnPolicy::registerSpawnPlacements);
    }

    private static void registerAttributes(EntityAttributeCreationEvent event) {
        event.put(ASHEN_STALKER.get(), Zombie.createAttributes()
                .add(Attributes.MAX_HEALTH, 26.0)
                .add(Attributes.MOVEMENT_SPEED, 0.27)
                .add(Attributes.ATTACK_DAMAGE, 4.5)
                .add(Attributes.FOLLOW_RANGE, 40.0)
                .build());
        event.put(RUNEBOUND_ARCHER.get(), AbstractSkeleton.createAttributes()
                .add(Attributes.MAX_HEALTH, 24.0)
                .add(Attributes.MOVEMENT_SPEED, 0.27)
                .add(Attributes.FOLLOW_RANGE, 44.0)
                .build());
        event.put(MIREFANG.get(), CaveSpider.createCaveSpider()
                .add(Attributes.MAX_HEALTH, 22.0)
                .add(Attributes.MOVEMENT_SPEED, 0.34)
                .add(Attributes.ATTACK_DAMAGE, 4.0)
                .add(Attributes.FOLLOW_RANGE, 36.0)
                .add(Attributes.SCALE, 1.10)
                .build());
        event.put(CINDER_WISP.get(), Blaze.createAttributes()
                .add(Attributes.MAX_HEALTH, 32.0)
                .add(Attributes.MOVEMENT_SPEED, 0.26)
                .add(Attributes.ATTACK_DAMAGE, 6.0)
                .add(Attributes.ARMOR, 4.0)
                .add(Attributes.FOLLOW_RANGE, 48.0)
                .add(Attributes.SCALE, 0.90)
                .build());
        event.put(FROSTBOUND_REAVER.get(), AbstractSkeleton.createAttributes()
                .add(Attributes.MAX_HEALTH, 28.0)
                .add(Attributes.MOVEMENT_SPEED, 0.28)
                .add(Attributes.ARMOR, 3.0)
                .add(Attributes.FOLLOW_RANGE, 48.0)
                .build());
        event.put(TIDEBOUND_RAIDER.get(), Drowned.createAttributes()
                .add(Attributes.MAX_HEALTH, 30.0)
                .add(Attributes.MOVEMENT_SPEED, 0.25)
                .add(Attributes.ATTACK_DAMAGE, 5.0)
                .add(Attributes.ARMOR, 4.0)
                .add(Attributes.FOLLOW_RANGE, 48.0)
                .build());
        event.put(DEEPSTONE_HUSK.get(), Zombie.createAttributes()
                .add(Attributes.MAX_HEALTH, 32.0)
                .add(Attributes.MOVEMENT_SPEED, 0.26)
                .add(Attributes.ATTACK_DAMAGE, 6.0)
                .add(Attributes.ARMOR, 5.0)
                .add(Attributes.FOLLOW_RANGE, 44.0)
                .build());
        event.put(ARENA_WARDEN.get(), Zombie.createAttributes()
                .add(Attributes.MAX_HEALTH, 400.0)
                .add(Attributes.MOVEMENT_SPEED, 0.30)
                .add(Attributes.ATTACK_DAMAGE, 10.0)
                .add(Attributes.ARMOR, 12.0)
                .add(Attributes.KNOCKBACK_RESISTANCE, 0.75)
                .add(Attributes.FOLLOW_RANGE, 56.0)
                .add(Attributes.SCALE, 1.45)
                .build());
    }
}
