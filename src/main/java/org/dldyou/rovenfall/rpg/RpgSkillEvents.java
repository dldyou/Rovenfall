package org.dldyou.rovenfall.rpg;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

/** NeoForge adapter for authoritative passive effects. */
public final class RpgSkillEvents {
    private RpgSkillEvents() {
    }

    public static void register(IEventBus eventBus) {
        eventBus.addListener(EventPriority.HIGH, RpgSkillEvents::onIncomingDamage);
    }

    private static void onIncomingDamage(LivingIncomingDamageEvent event) {
        if (event.isCanceled() || !(event.getEntity().level() instanceof ServerLevel level)) {
            return;
        }
        ServerPlayer attacker = playerFrom(event.getSource().getEntity());
        ServerPlayer target = event.getEntity() instanceof ServerPlayer player && !(player instanceof FakePlayer)
                ? player
                : null;
        if (attacker == null && target == null) {
            return;
        }
        RpgPlayerSavedData state = RpgPlayerSavedData.get(level.getServer());
        float changed = RpgPassiveSkillService.modifyDamage(
                RpgDefinitionReloadListener.snapshot(level.getServer()),
                attacker == null ? null : state.state(attacker.getUUID()),
                target == null ? null : state.state(target.getUUID()),
                event.getAmount());
        changed = RpgActiveSkillRuntime.modifyDamage(
                attacker,
                event.getEntity(),
                level.dimension().identifier(),
                level.getGameTime(),
                changed);
        if (Float.isFinite(changed) && changed >= 0 && changed != event.getAmount()) {
            event.setAmount(changed);
        }
    }

    private static ServerPlayer playerFrom(Entity entity) {
        if (entity instanceof ServerPlayer player && !(player instanceof FakePlayer)) {
            return player;
        }
        if (entity instanceof Projectile projectile && projectile.getOwner() instanceof ServerPlayer player
                && !(player instanceof FakePlayer)) {
            return player;
        }
        return null;
    }
}
