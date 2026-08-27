package org.dldyou.rovenfall.administration;

import java.time.Instant;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import org.dldyou.rovenfall.world.PortalDefinition;

public final class PortalEvents {
    private PortalEvents() {
    }

    public static void register(IEventBus eventBus) {
        eventBus.addListener(EventPriority.HIGHEST, PortalEvents::onRightClickBlock);
        eventBus.addListener(PortalEvents::onLivingDamage);
    }

    private static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getLevel() instanceof ServerLevel level)
                || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        PlatformSavedData state = PlatformSavedData.get(level.getServer());
        var portalId = state.portalAt(new PortalDefinition.Endpoint(level.dimension(), event.getPos())).orElse(null);
        if (portalId == null) {
            return;
        }
        long now = Instant.now().toEpochMilli();
        var result = PortalTravelService.travel(state, player, portalId, now, UUID.randomUUID());
        if (result.status() == PortalTravelService.Status.SUCCESS) {
            if (player.connection != null) {
                player.sendSystemMessage(Component.translatable("portal.rovenfall.travel.success", portalId.toString()));
            }
            event.setCancellationResult(InteractionResult.SUCCESS);
        } else if (result.status() == PortalTravelService.Status.COOLDOWN
                || result.status() == PortalTravelService.Status.COMBAT_LOCKED) {
            long seconds = Math.max(1L, (result.retryAtEpochMillis() - now + 999L) / 1_000L);
            if (player.connection != null) {
                player.sendSystemMessage(Component.translatable(
                        "portal.rovenfall.travel.error." + result.status().name().toLowerCase(java.util.Locale.ROOT), seconds));
            }
            event.setCancellationResult(InteractionResult.FAIL);
        } else {
            if (player.connection != null) {
                player.sendSystemMessage(Component.translatable(
                        "portal.rovenfall.travel.error." + result.status().name().toLowerCase(java.util.Locale.ROOT)));
            }
            event.setCancellationResult(InteractionResult.FAIL);
        }
        event.setCanceled(true);
    }

    private static void onLivingDamage(LivingDamageEvent.Post event) {
        if (event.getInflictedDamage() <= 0.0F) {
            return;
        }
        long now = Instant.now().toEpochMilli();
        if (event.getEntity() instanceof ServerPlayer victim) {
            PortalTravelService.recordCombat(PlatformSavedData.get(victim.level().getServer()), victim.getUUID(), now);
        }
        if (event.getSource().getEntity() instanceof ServerPlayer attacker) {
            PortalTravelService.recordCombat(PlatformSavedData.get(attacker.level().getServer()), attacker.getUUID(), now);
        }
    }
}
