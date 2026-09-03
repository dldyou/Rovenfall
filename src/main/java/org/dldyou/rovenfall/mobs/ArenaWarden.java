package org.dldyou.rovenfall.mobs;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.BossEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.dldyou.rovenfall.administration.BossEncounterService;
import org.dldyou.rovenfall.administration.PlatformSavedData;

public final class ArenaWarden extends Zombie {
    public static final String MINION_ENCOUNTER_TAG = "RovenfallBossMinion";
    private static final int INTRO_MILLIS = 3_000;
    private static final int MAX_MINIONS = 6;
    private UUID encounterId;
    private BlockPos arenaOrigin;
    private int arenaRadius;
    private int patternClock;
    private final ServerBossEvent bossBar = new ServerBossEvent(
            UUID.randomUUID(),
            Component.translatable("entity.rovenfall.arena_warden"),
            BossEvent.BossBarColor.PURPLE,
            BossEvent.BossBarOverlay.NOTCHED_12);

    public ArenaWarden(EntityType<? extends Zombie> entityType, Level level) {
        super(entityType, level);
        xpReward = 0;
        setPersistenceRequired();
    }

    public void initializeEncounter(UUID encounterId, BlockPos origin, int radius) {
        this.encounterId = encounterId;
        this.arenaOrigin = origin.immutable();
        this.arenaRadius = radius;
        setCustomName(Component.translatable("entity.rovenfall.arena_warden"));
        setCustomNameVisible(true);
    }

    public Optional<UUID> encounterId() {
        return Optional.ofNullable(encounterId);
    }

    public BlockPos arenaOrigin() {
        return arenaOrigin;
    }

    public int arenaRadius() {
        return arenaRadius;
    }

    @Override
    protected void customServerAiStep(ServerLevel level) {
        super.customServerAiStep(level);
        if (encounterId == null || arenaOrigin == null) {
            bossBar.setVisible(false);
            return;
        }
        var state = PlatformSavedData.get(level.getServer());
        var encounter = state.bossEncounter()
                .filter(value -> value.encounterId().equals(encounterId) && value.bossId().equals(getUUID()))
                .orElse(null);
        if (encounter == null || encounter.status() == BossEncounter.Status.DEFEATED
                || encounter.status() == BossEncounter.Status.FAILED) {
            bossBar.removeAllPlayers();
            bossBar.setVisible(false);
            if (encounter != null) {
                discard();
            }
            return;
        }
        updateBossBar(level, encounter);
        long now = System.currentTimeMillis();
        if (encounter.status() == BossEncounter.Status.INTRO) {
            setNoAi(true);
            if (tickCount % 10 == 0) {
                level.sendParticles(ParticleTypes.OMINOUS_SPAWNING,
                        getX(), getY() + 1.0, getZ(), 12, 0.6, 1.0, 0.6, 0.02);
            }
            if (now - encounter.startedAtEpochMillis() >= INTRO_MILLIS) {
                BossEncounterService.activate(state, encounterId, now);
                setNoAi(false);
                level.playSound(null, blockPosition(), SoundEvents.WITHER_SPAWN, SoundSource.HOSTILE, 1.0F, 1.2F);
            }
            return;
        }
        if (encounter.status() != BossEncounter.Status.ACTIVE) {
            setNoAi(true);
            return;
        }
        setNoAi(false);
        if (tickCount % 20 == 0 && !encounter.protects(level.dimension(), blockPosition())) {
            BlockPos previous = blockPosition();
            teleportTo(arenaOrigin.getX() + 0.5, arenaOrigin.getY(), arenaOrigin.getZ() + 0.5);
            getNavigation().stop();
            BossEncounterService.auditBoundaryReturn(state, encounterId, previous, now);
        }
        int phase = phaseForHealth(getHealth(), getMaxHealth());
        if (phase > encounter.phase()) {
            BossEncounterService.observePhase(state, encounterId, phase, now);
            level.playSound(null, blockPosition(), SoundEvents.WARDEN_ROAR, SoundSource.HOSTILE, 1.0F, 0.8F + phase * 0.1F);
            level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME,
                    getX(), getY() + 1.0, getZ(), 40, 1.2, 1.2, 1.2, 0.06);
        }
        patternClock++;
        runPatterns(level, phase);
    }

    private void updateBossBar(ServerLevel level, BossEncounter encounter) {
        bossBar.setVisible(true);
        bossBar.setProgress(Math.max(0, Math.min(1, getHealth() / getMaxHealth())));
        bossBar.setColor(switch (Math.max(1, encounter.phase())) {
            case 1 -> BossEvent.BossBarColor.PURPLE;
            case 2 -> BossEvent.BossBarColor.YELLOW;
            default -> BossEvent.BossBarColor.RED;
        });
        bossBar.setName(Component.translatable(
                "entity.rovenfall.arena_warden.phase", Math.max(1, encounter.phase())));
        var eligible = new HashSet<>(
                level.getPlayers(player -> encounter.protects(level.dimension(), player.blockPosition())));
        for (ServerPlayer current : List.copyOf(bossBar.getPlayers())) {
            if (!eligible.remove(current)) {
                bossBar.removePlayer(current);
            }
        }
        eligible.forEach(bossBar::addPlayer);
    }

    private void runPatterns(ServerLevel level, int phase) {
        int sweep = patternClock % 80;
        if (sweep == 60) {
            telegraph(level, SoundEvents.EVOKER_PREPARE_ATTACK, ParticleTypes.ENCHANTED_HIT, 4.0);
        } else if (sweep == 0) {
            hitPlayers(level, 4.0, 8.0F, ParticleTypes.SWEEP_ATTACK, SoundEvents.RAVAGER_ATTACK);
        }
        if (phase >= 2) {
            int shockwave = patternClock % 140;
            if (shockwave == 110) {
                telegraph(level, SoundEvents.WARDEN_SONIC_CHARGE, ParticleTypes.SOUL, 9.0);
            } else if (shockwave == 0) {
                hitPlayers(level, 9.0, 6.0F, ParticleTypes.SONIC_BOOM, SoundEvents.WARDEN_SONIC_BOOM);
            }
        }
        if (phase >= 3) {
            int summon = patternClock % 200;
            if (summon == 160) {
                telegraph(level, SoundEvents.EVOKER_PREPARE_SUMMON, ParticleTypes.OMINOUS_SPAWNING, 5.0);
            } else if (summon == 0) {
                summonMinions(level);
            }
        }
    }

    private void telegraph(
            ServerLevel level,
            net.minecraft.sounds.SoundEvent sound,
            net.minecraft.core.particles.SimpleParticleType particle,
            double spread) {
        level.playSound(null, blockPosition(), sound, SoundSource.HOSTILE, 1.0F, 1.0F);
        level.sendParticles(particle, getX(), getY() + 0.8, getZ(), 24, spread * 0.15, 0.7, spread * 0.15, 0.03);
    }

    private void hitPlayers(
            ServerLevel level,
            double range,
            float damage,
            net.minecraft.core.particles.SimpleParticleType particle,
            net.minecraft.sounds.SoundEvent sound) {
        var source = level.damageSources().mobAttack(this);
        level.getEntitiesOfClass(ServerPlayer.class, getBoundingBox().inflate(range), player ->
                        player.isAlive() && encounterContains(player.blockPosition()))
                .forEach(player -> player.hurtServer(level, source, damage));
        level.playSound(null, blockPosition(), sound, SoundSource.HOSTILE, 1.0F, 0.9F);
        level.sendParticles(particle, getX(), getY() + 0.8, getZ(), 32, range * 0.2, 0.5, range * 0.2, 0.02);
    }

    private void summonMinions(ServerLevel level) {
        long existing = level.getEntitiesOfClass(
                        AshenStalker.class,
                        getBoundingBox().inflate(arenaRadius),
                        minion -> minion.getPersistentData().getStringOr(MINION_ENCOUNTER_TAG, "")
                                .equals(encounterId.toString()))
                .size();
        int count = (int) Math.min(2, MAX_MINIONS - existing);
        for (int index = 0; index < count; index++) {
            AshenStalker minion = RovenfallEntityTypes.ASHEN_STALKER.get().create(level, EntitySpawnReason.EVENT);
            if (minion == null) {
                continue;
            }
            double angle = (patternClock + index * Math.PI) % (Math.PI * 2);
            minion.setPos(getX() + Math.cos(angle) * 3.0, getY(), getZ() + Math.sin(angle) * 3.0);
            minion.getPersistentData().putString(MINION_ENCOUNTER_TAG, encounterId.toString());
            minion.setPersistenceRequired();
            minion.finalizeSpawn(
                    level,
                    level.getCurrentDifficultyAt(minion.blockPosition()),
                    EntitySpawnReason.EVENT,
                    null);
            if (!minion.isSpawnCancelled()) {
                level.addFreshEntity(minion);
            }
        }
        level.playSound(null, blockPosition(), SoundEvents.EVOKER_CAST_SPELL, SoundSource.HOSTILE, 1.0F, 0.8F);
    }

    private boolean encounterContains(BlockPos position) {
        if (arenaOrigin == null || position == null) {
            return false;
        }
        long x = (long) position.getX() - arenaOrigin.getX();
        long z = (long) position.getZ() - arenaOrigin.getZ();
        return x * x + z * z <= (long) arenaRadius * arenaRadius;
    }

    @Override
    public boolean canAttack(LivingEntity target) {
        return (!(target instanceof Player) || encounterContains(target.blockPosition())) && super.canAttack(target);
    }

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        boolean intro = encounterId != null && PlatformSavedData.get(level.getServer()).bossEncounter()
                .filter(encounter -> encounter.encounterId().equals(encounterId))
                .map(encounter -> encounter.status() == BossEncounter.Status.INTRO)
                .orElse(false);
        return !intro && super.hurtServer(level, source, amount);
    }

    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        return false;
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        if (encounterId != null) {
            output.store("RovenfallEncounterId", UUIDUtil.CODEC, encounterId);
        }
        if (arenaOrigin != null) {
            output.store("RovenfallArenaOrigin", BlockPos.CODEC, arenaOrigin);
        }
        output.putInt("RovenfallArenaRadius", arenaRadius);
        output.putInt("RovenfallPatternClock", patternClock);
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        encounterId = input.read("RovenfallEncounterId", UUIDUtil.CODEC).orElse(null);
        arenaOrigin = input.read("RovenfallArenaOrigin", BlockPos.CODEC).orElse(null);
        arenaRadius = input.getIntOr("RovenfallArenaRadius", 0);
        patternClock = Math.max(0, input.getIntOr("RovenfallPatternClock", 0));
    }

    @Override
    public void onRemoval(RemovalReason reason) {
        bossBar.removeAllPlayers();
        bossBar.setVisible(false);
        super.onRemoval(reason);
    }

    public static int phaseForHealth(float health, float maximumHealth) {
        if (!Float.isFinite(health) || !Float.isFinite(maximumHealth) || maximumHealth <= 0) {
            return 1;
        }
        float share = health / maximumHealth;
        return share > 0.66F ? 1 : share > 0.33F ? 2 : 3;
    }
}
