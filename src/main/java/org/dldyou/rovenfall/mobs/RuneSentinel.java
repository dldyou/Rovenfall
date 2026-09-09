package org.dldyou.rovenfall.mobs;

import java.util.EnumSet;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.dldyou.rovenfall.administration.PlatformSavedData;
import org.dldyou.rovenfall.claims.ClaimKey;

/** A stationary, telegraphed strike aimed at the target's original position. */
public final class RuneSentinel extends Zombie {
    public RuneSentinel(EntityType<? extends Zombie> type, Level level) {
        super(type, level);
        setCustomName(net.minecraft.network.chat.Component.translatable("entity.rovenfall.rune_sentinel"));
        setCustomNameVisible(true);
    }

    @Override
    protected void registerGoals() {
        super.registerGoals();
        goalSelector.addGoal(1, new RuneStrikeGoal());
    }

    @Override
    protected boolean convertsInWater() {
        return false;
    }

    @Override
    protected boolean isSunSensitive() {
        return false;
    }

    @Override
    protected int getBaseExperienceReward(ServerLevel level) {
        return RovenfallMobRuntime.experienceReward(level, RovenfallMobEntities.RUNE_SENTINEL_ID);
    }

    @Override
    protected void dropFromLootTable(ServerLevel level, DamageSource source, boolean playerKilled) {
        RovenfallMobRuntime.dropConfiguredLoot(this, level, source, playerKilled,
                RovenfallMobEntities.RUNE_SENTINEL_ID);
    }

    final class RuneStrikeGoal extends Goal {
        private MobContentCatalog.MobDefinition definition;
        private MobContentCatalog.RuneStrike strike;
        private ServerPlayer victim;
        private Vec3 marker;
        private long nextStrike;
        private int elapsed;

        RuneStrikeGoal() {
            setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            if (!(level() instanceof ServerLevel level) || level.getGameTime() < nextStrike
                    || !(getTarget() instanceof ServerPlayer player)) {
                return false;
            }
            definition = MobContentReloadListener.mob(level.getServer(), RovenfallMobEntities.RUNE_SENTINEL_ID)
                    .orElse(null);
            strike = definition == null ? null : definition.runeStrike().orElse(null);
            victim = player;
            return strike != null && eligible(level);
        }

        @Override
        public void start() {
            elapsed = 0;
            marker = victim.position();
            getNavigation().stop();
            playSound(SoundEvents.EVOKER_PREPARE_ATTACK, 1.0F, 0.8F);
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }

        @Override
        public boolean canContinueToUse() {
            return level() instanceof ServerLevel level
                    && elapsed < strike.windupTicks() + strike.recoveryTicks()
                    && MobContentReloadListener.mob(level.getServer(), RovenfallMobEntities.RUNE_SENTINEL_ID)
                            .filter(definition::equals).isPresent()
                    && eligible(level);
        }

        private boolean eligible(ServerLevel level) {
            if (!isAlive() || victim == null || !victim.isAlive() || victim.level() != level
                    || victim.isCreative() || victim.isSpectator() || getTarget() != victim
                    || !RovenfallMobRuntime.isEligibleRewardPlayer(victim)
                    || distanceToSqr(victim) > strike.range() * strike.range() || !hasLineOfSight(victim)
                    || definition.spawn().isEmpty()) {
                return false;
            }
            var state = PlatformSavedData.get(level.getServer());
            return RovenfallMobRuntime.allows(definition.spawn().orElseThrow(), level.dimension(),
                    blockPosition().getY(), state.isWildernessOperationLocked(),
                    state.isProtectedRegion(ClaimKey.at(level.dimension(), blockPosition())))
                    && !state.isProtectedRegion(ClaimKey.at(level.dimension(), victim.blockPosition()));
        }

        @Override
        public void tick() {
            if (!(level() instanceof ServerLevel level) || !canContinueToUse()) {
                return;
            }
            getNavigation().stop();
            getLookControl().setLookAt(marker.x, marker.y, marker.z);
            elapsed++;
            if (elapsed <= strike.windupTicks() && elapsed % 4 == 0) {
                for (int point = 0; point < 16; point++) {
                    double angle = point * Math.PI / 8;
                    level.sendParticles(ParticleTypes.ENCHANT, marker.x + Math.cos(angle) * strike.radius(),
                            marker.y + 0.1, marker.z + Math.sin(angle) * strike.radius(), 1, 0, 0, 0, 0);
                }
            }
            if (elapsed == strike.windupTicks()) {
                level.sendParticles(ParticleTypes.CRIT, marker.x, marker.y + 0.5, marker.z,
                        20, strike.radius() / 2, 0.3, strike.radius() / 2, 0);
                playSound(SoundEvents.IRON_GOLEM_ATTACK, 1.0F, 0.7F);
                if (victim.position().distanceToSqr(marker) <= strike.radius() * strike.radius()) {
                    victim.hurtServer(level, damageSources().mobAttack(RuneSentinel.this), (float) strike.damage());
                }
            }
        }

        @Override
        public void stop() {
            // Interrupted casts never resume after unload/reload: a fresh warning is always required.
            nextStrike = level().getGameTime() + (strike == null ? 0 : strike.cooldownTicks());
            victim = null;
            marker = null;
        }
    }
}
