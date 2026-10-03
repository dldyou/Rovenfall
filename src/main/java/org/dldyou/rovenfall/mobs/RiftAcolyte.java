package org.dldyou.rovenfall.mobs;

import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/** A cultist that telegraphs a short ranged hex before weakening its target. */
public final class RiftAcolyte extends Zombie {
    private static final int HEX_WINDUP_TICKS = 30;
    private static final int HEX_COOLDOWN_TICKS = 140;
    private UUID hexTarget;
    private int hexWindup;
    private int hexCooldown;

    public RiftAcolyte(EntityType<? extends Zombie> entityType, Level level) {
        super(entityType, level);
        xpReward = 14;
    }

    @Override
    protected void customServerAiStep(ServerLevel level) {
        super.customServerAiStep(level);
        if (hexCooldown > 0) {
            hexCooldown--;
        }
        if (hexWindup > 0) {
            tickHex(level);
            return;
        }
        LivingEntity target = getTarget();
        if (target != null && canStartHex(hexCooldown, hexWindup, target.isAlive(), distanceToSqr(target))) {
            hexTarget = target.getUUID();
            hexWindup = HEX_WINDUP_TICKS;
            getNavigation().stop();
            if (target instanceof ServerPlayer player) {
                player.sendOverlayMessage(Component.translatable("message.rovenfall.mob.rift_hex"));
            }
            level.playSound(null, blockPosition(), SoundEvents.EVOKER_PREPARE_WOLOLO,
                    SoundSource.HOSTILE, 0.9F, 0.8F);
        }
    }

    public static boolean canStartHex(int cooldown, int windup, boolean targetAlive, double distanceSquared) {
        return cooldown == 0 && windup == 0 && targetAlive
                && Double.isFinite(distanceSquared) && distanceSquared >= 0 && distanceSquared <= 64.0;
    }

    private void tickHex(ServerLevel level) {
        hexWindup--;
        if (hexWindup % 5 == 0) {
            level.sendParticles(ParticleTypes.PORTAL, getX(), getY() + 1.2, getZ(),
                    10, 0.45, 0.5, 0.45, 0.02);
        }
        if (hexWindup > 0) {
            return;
        }
        var entity = hexTarget == null ? null : level.getEntity(hexTarget);
        if (entity instanceof LivingEntity target && target.isAlive() && distanceToSqr(target) <= 100.0) {
            target.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 120, 0), this);
            target.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 60, 0), this);
            level.sendParticles(ParticleTypes.WITCH, target.getX(), target.getY() + 1.0, target.getZ(),
                    18, 0.5, 0.7, 0.5, 0.03);
        }
        hexTarget = null;
        hexCooldown = HEX_COOLDOWN_TICKS;
    }

    @Override
    protected boolean isSunSensitive() {
        return false;
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        if (hexTarget != null && hexWindup > 0) {
            output.store("RovenfallHexTarget", UUIDUtil.CODEC, hexTarget);
            output.putInt("RovenfallHexWindup", hexWindup);
        }
        output.putInt("RovenfallHexCooldown", hexCooldown);
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        hexTarget = input.read("RovenfallHexTarget", UUIDUtil.CODEC).orElse(null);
        hexWindup = hexTarget == null ? 0 : Math.max(0, input.getIntOr("RovenfallHexWindup", 0));
        hexCooldown = Math.max(0, input.getIntOr("RovenfallHexCooldown", 0));
    }
}
