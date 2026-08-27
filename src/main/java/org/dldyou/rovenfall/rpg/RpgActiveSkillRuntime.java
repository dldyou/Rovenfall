package org.dldyou.rovenfall.rpg;

import java.time.Instant;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.neoforge.common.util.FakePlayer;
import org.dldyou.rovenfall.administration.ClaimProtectionService;
import org.dldyou.rovenfall.administration.PlatformSavedData;
import org.dldyou.rovenfall.claims.ClaimConfig;
import org.dldyou.rovenfall.claims.ClaimKey;
import org.dldyou.rovenfall.world.WorldTopology;

/** Short-lived combat effects. Cooldown and replay state remain in persistent RPG data. */
public final class RpgActiveSkillRuntime {
    private static final Map<UUID, EnumMap<SkillDefinition.EffectType, ArmedEffect>> EFFECTS = new HashMap<>();

    private RpgActiveSkillRuntime() {
    }

    public static RpgActiveSkillService.EffectGateway gateway(ServerPlayer player) {
        return new ServerGateway(player);
    }

    public static float modifyDamage(
            ServerPlayer attacker,
            LivingEntity target,
            Identifier dimension,
            long gameTime,
            float amount) {
        if (!Float.isFinite(amount) || amount <= 0 || dimension == null || target == null) {
            return amount;
        }
        long bonus = attacker == null ? 0 : consumeDamageBonus(
                attacker.getUUID(), target.getUUID(), dimension, gameTime);
        long reduction = target instanceof ServerPlayer player
                ? activeReduction(player.getUUID(), dimension, gameTime)
                : 0;
        double changed = amount * (10_000.0 + Math.min(bonus, 100_000L)) / 10_000.0;
        changed = changed * (10_000.0 - Math.min(reduction, 9_000L)) / 10_000.0;
        return (float) Math.min(changed, Float.MAX_VALUE);
    }

    public static void clear(UUID playerId) {
        EFFECTS.remove(playerId);
    }

    public static void clearAll() {
        EFFECTS.clear();
    }

    private static long consumeDamageBonus(UUID playerId, UUID targetId, Identifier dimension, long gameTime) {
        ArmedEffect effect = effect(playerId, SkillDefinition.EffectType.DAMAGE_DEALT, dimension, gameTime);
        if (effect == null || effect.targetId().filter(targetId::equals).isEmpty()) {
            return 0;
        }
        remove(playerId, SkillDefinition.EffectType.DAMAGE_DEALT);
        return effect.basisPoints();
    }

    private static long activeReduction(UUID playerId, Identifier dimension, long gameTime) {
        ArmedEffect effect = effect(
                playerId, SkillDefinition.EffectType.DAMAGE_TAKEN_REDUCTION, dimension, gameTime);
        return effect == null ? 0 : effect.basisPoints();
    }

    private static ArmedEffect effect(
            UUID playerId,
            SkillDefinition.EffectType type,
            Identifier dimension,
            long gameTime) {
        EnumMap<SkillDefinition.EffectType, ArmedEffect> playerEffects = EFFECTS.get(playerId);
        if (playerEffects == null) {
            return null;
        }
        ArmedEffect effect = playerEffects.get(type);
        if (effect == null) {
            return null;
        }
        if (effect.expiresAt() <= gameTime || !effect.dimension().equals(dimension)) {
            remove(playerId, type);
            return null;
        }
        return effect;
    }

    private static void remove(UUID playerId, SkillDefinition.EffectType type) {
        EnumMap<SkillDefinition.EffectType, ArmedEffect> playerEffects = EFFECTS.get(playerId);
        if (playerEffects == null) {
            return;
        }
        playerEffects.remove(type);
        if (playerEffects.isEmpty()) {
            EFFECTS.remove(playerId);
        }
    }

    private static final class ServerGateway implements RpgActiveSkillService.EffectGateway {
        private final ServerPlayer player;
        private LivingEntity target;

        private ServerGateway(ServerPlayer player) {
            this.player = player;
        }

        @Override
        public Identifier dimension() {
            return player.level().dimension().identifier();
        }

        @Override
        public boolean validate(SkillDefinition.ActiveEffect effect, int targetEntityId) {
            target = null;
            if (player instanceof FakePlayer || player.isSpectator() || !player.isAlive()
                    || !(player.level() instanceof ServerLevel level)) {
                return false;
            }
            if (effect.target() == SkillDefinition.TargetType.SELF) {
                return true;
            }
            if (targetEntityId < 0) {
                return false;
            }
            Entity candidate = level.getEntity(targetEntityId);
            if (!(candidate instanceof LivingEntity living) || living == player || !living.isAlive()
                    || living.isRemoved() || living instanceof ServerPlayer serverPlayer && serverPlayer.isSpectator()
                    || player.distanceToSqr(living) > effect.range() * effect.range()
                    || !player.hasLineOfSight(living)) {
                return false;
            }
            PlatformSavedData platform = PlatformSavedData.get(level.getServer());
            boolean nativeOverride = player.permissions().hasPermission(Permissions.COMMANDS_OWNER)
                    && !platform.hasAnyAdminRoles();
            ClaimKey key = ClaimKey.at(level.dimension(), living.blockPosition());
            var decision = ClaimProtectionService.evaluate(
                    platform,
                    player.getUUID(),
                    nativeOverride,
                    WorldTopology.HUB,
                    level.getServer().overworld().getRespawnData().pos(),
                    ClaimConfig.protectedSpawnRadiusChunks(),
                    key,
                    ClaimProtectionService.Action.ENTITY);
            if (!decision.allowed()) {
                ClaimProtectionService.auditDenied(
                        platform,
                        player.getUUID(),
                        key,
                        ClaimProtectionService.Action.ENTITY,
                        decision,
                        Instant.now().toEpochMilli());
                return false;
            }
            target = living;
            return true;
        }

        @Override
        public void apply(
                Identifier skillId,
                int rank,
                SkillDefinition.ActiveEffect effect,
                int targetEntityId,
                long gameTime) {
            long basisPoints = Math.min(
                    (long) effect.basisPointsPerRank() * rank,
                    effect.type() == SkillDefinition.EffectType.DAMAGE_TAKEN_REDUCTION ? 9_000L : 100_000L);
            long expiresAt = Math.addExact(gameTime, effect.durationTicks());
            Optional<UUID> targetId = effect.target() == SkillDefinition.TargetType.LIVING_ENTITY
                    ? Optional.of(target.getUUID())
                    : Optional.empty();
            EFFECTS.computeIfAbsent(
                    player.getUUID(), ignored -> new EnumMap<>(SkillDefinition.EffectType.class))
                    .put(effect.type(), new ArmedEffect(
                            skillId, basisPoints, expiresAt, dimension(), targetId));
        }
    }

    private record ArmedEffect(
            Identifier skill,
            long basisPoints,
            long expiresAt,
            Identifier dimension,
            Optional<UUID> targetId) {
    }
}
