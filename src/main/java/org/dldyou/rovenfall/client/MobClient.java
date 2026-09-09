package org.dldyou.rovenfall.client;

import net.minecraft.client.renderer.entity.BlazeRenderer;
import net.minecraft.client.renderer.entity.CaveSpiderRenderer;
import net.minecraft.client.renderer.entity.DrownedRenderer;
import net.minecraft.client.renderer.entity.HuskRenderer;
import net.minecraft.client.renderer.entity.SkeletonRenderer;
import net.minecraft.client.renderer.entity.StrayRenderer;
import net.minecraft.client.renderer.entity.ZombieRenderer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import org.dldyou.rovenfall.mobs.RovenfallEntityTypes;

public final class MobClient {
    private MobClient() {
    }

    public static void register(IEventBus modBus) {
        modBus.addListener(MobClient::registerRenderers);
    }

    private static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(RovenfallEntityTypes.ASHEN_STALKER.get(), ZombieRenderer::new);
        event.registerEntityRenderer(RovenfallEntityTypes.RUNEBOUND_ARCHER.get(), SkeletonRenderer::new);
        event.registerEntityRenderer(RovenfallEntityTypes.MIREFANG.get(), CaveSpiderRenderer::new);
        event.registerEntityRenderer(RovenfallEntityTypes.CINDER_WISP.get(), BlazeRenderer::new);
        event.registerEntityRenderer(RovenfallEntityTypes.FROSTBOUND_REAVER.get(), StrayRenderer::new);
        event.registerEntityRenderer(RovenfallEntityTypes.TIDEBOUND_RAIDER.get(), DrownedRenderer::new);
        event.registerEntityRenderer(RovenfallEntityTypes.DEEPSTONE_HUSK.get(), HuskRenderer::new);
        event.registerEntityRenderer(RovenfallEntityTypes.ARENA_WARDEN.get(), ZombieRenderer::new);
    }
}
