package org.dldyou.rovenfall.mobs;

import net.minecraft.client.renderer.entity.SilverfishRenderer;
import net.minecraft.client.renderer.entity.SpiderRenderer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

public final class RovenfallMobClient {
    private RovenfallMobClient() {
    }

    public static void register(IEventBus modBus) {
        modBus.addListener(RovenfallMobClient::registerRenderers);
    }

    private static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(RovenfallMobEntities.GROVE_STALKER.get(), SpiderRenderer::new);
        event.registerEntityRenderer(RovenfallMobEntities.OREBOUND_BEETLE.get(), SilverfishRenderer::new);
    }
}
