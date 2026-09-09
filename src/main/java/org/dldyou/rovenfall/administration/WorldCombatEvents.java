package org.dldyou.rovenfall.administration;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.EntityInvulnerabilityCheckEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import org.dldyou.rovenfall.worlds.WorldConfig;

public final class WorldCombatEvents {
    private static final long FEEDBACK_INTERVAL_MILLIS = 1_000L;
    private final Map<UUID, Long> lastFeedbackByPlayer = new HashMap<>();

    private WorldCombatEvents() {
    }

    public static void register(IEventBus eventBus) {
        WorldCombatEvents handler = new WorldCombatEvents();
        eventBus.addListener(handler::onEntityInvulnerabilityCheck);
        eventBus.addListener(handler::onPlayerLoggedOut);
    }

    private void onEntityInvulnerabilityCheck(EntityInvulnerabilityCheckEvent event) {
        if (event.isInvulnerable()
                || !(event.getEntity() instanceof ServerPlayer target)
                || !(target.level() instanceof ServerLevel level)
                || !(event.getSource().getEntity() instanceof ServerPlayer attacker)
                || attacker.getUUID().equals(target.getUUID())) {
            return;
        }
        var decision = WorldCombatService.evaluate(
                level.getServer().overworld().dimension(),
                WorldCombatService.WILDERNESS_DIMENSION,
                level.dimension(),
                WorldConfig.hubPvpEnabled(),
                WorldConfig.wildernessPvpEnabled());
        if (decision.allowed()) {
            return;
        }
        event.setInvulnerable(true);
        long now = Instant.now().toEpochMilli();
        WorldCombatService.auditDenied(
                PlatformSavedData.get(level.getServer()),
                attacker.getUUID(),
                target.getUUID(),
                level.dimension(),
                target.blockPosition(),
                decision,
                now);
        Long previous = lastFeedbackByPlayer.get(attacker.getUUID());
        if (attacker.connection != null
                && (previous == null || now - previous >= FEEDBACK_INTERVAL_MILLIS)) {
            lastFeedbackByPlayer.put(attacker.getUUID(), now);
            attacker.sendOverlayMessage(Component.translatable("message.rovenfall.pvp.denied"));
        }
    }

    private void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        lastFeedbackByPlayer.remove(event.getEntity().getUUID());
    }
}
