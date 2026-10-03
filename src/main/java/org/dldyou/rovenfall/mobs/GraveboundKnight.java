package org.dldyou.rovenfall.mobs;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.zombie.Husk;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/** An armored ruin guardian that braces when badly wounded. */
public final class GraveboundKnight extends Husk {
    private static final int GUARD_COOLDOWN_TICKS = 240;
    private int guardCooldown;

    public GraveboundKnight(EntityType<? extends Husk> entityType, Level level) {
        super(entityType, level);
        xpReward = 18;
    }

    @Override
    protected void customServerAiStep(ServerLevel level) {
        super.customServerAiStep(level);
        if (guardCooldown > 0) {
            guardCooldown--;
        }
        if (shouldGuard(guardCooldown, getHealth(), getMaxHealth(), getTarget() != null)) {
            addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 100, 1));
            addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 100, 1));
            guardCooldown = GUARD_COOLDOWN_TICKS;
            level.sendParticles(ParticleTypes.ENCHANTED_HIT, getX(), getY() + 1.0, getZ(),
                    24, 0.7, 0.9, 0.7, 0.02);
            level.playSound(null, blockPosition(), SoundEvents.IRON_GOLEM_REPAIR,
                    SoundSource.HOSTILE, 1.0F, 0.7F);
        }
    }

    public static boolean shouldGuard(int cooldown, float health, float maximumHealth, boolean hasTarget) {
        return cooldown == 0 && hasTarget && Float.isFinite(health) && Float.isFinite(maximumHealth)
                && maximumHealth > 0 && health > 0 && health <= maximumHealth * 0.5F;
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        output.putInt("RovenfallGuardCooldown", guardCooldown);
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        guardCooldown = Math.max(0, input.getIntOr("RovenfallGuardCooldown", 0));
    }
}
