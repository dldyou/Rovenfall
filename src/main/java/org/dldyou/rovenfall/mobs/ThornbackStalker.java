package org.dldyou.rovenfall.mobs;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.spider.Spider;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;

/** A forest predator that periodically performs a visible gap-closing pounce. */
public final class ThornbackStalker extends Spider {
    private static final int POUNCE_COOLDOWN_TICKS = 100;
    private int pounceCooldown;

    public ThornbackStalker(EntityType<? extends Spider> entityType, Level level) {
        super(entityType, level);
        xpReward = 13;
    }

    @Override
    protected void customServerAiStep(ServerLevel level) {
        super.customServerAiStep(level);
        if (pounceCooldown > 0) {
            pounceCooldown--;
            return;
        }
        LivingEntity target = getTarget();
        if (target == null || !target.isAlive()) {
            return;
        }
        double distance = distanceToSqr(target);
        if (!canPounce(pounceCooldown, onGround(), distance)) {
            return;
        }
        Vec3 direction = target.position().subtract(position()).normalize();
        setDeltaMovement(direction.x * 0.75, 0.55, direction.z * 0.75);
        pounceCooldown = POUNCE_COOLDOWN_TICKS;
        level.sendParticles(ParticleTypes.SPORE_BLOSSOM_AIR, getX(), getY() + 0.4, getZ(),
                12, 0.5, 0.2, 0.5, 0.01);
        level.playSound(null, blockPosition(), SoundEvents.SPIDER_AMBIENT,
                SoundSource.HOSTILE, 1.0F, 0.65F);
    }

    public static boolean canPounce(int cooldown, boolean grounded, double distanceSquared) {
        return cooldown == 0 && grounded && Double.isFinite(distanceSquared)
                && distanceSquared >= 9.0 && distanceSquared <= 64.0;
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        output.putInt("RovenfallPounceCooldown", pounceCooldown);
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        pounceCooldown = Math.max(0, input.getIntOr("RovenfallPounceCooldown", 0));
    }
}
