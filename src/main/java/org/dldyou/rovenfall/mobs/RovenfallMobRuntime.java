package org.dldyou.rovenfall.mobs;

import com.mojang.logging.LogUtils;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import org.dldyou.rovenfall.administration.EconomyConfig;
import org.dldyou.rovenfall.administration.EconomyService;
import org.dldyou.rovenfall.administration.PlatformSavedData;
import org.dldyou.rovenfall.claims.ClaimKey;
import org.slf4j.Logger;

public final class RovenfallMobRuntime {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int CHANCE_SCALE = 1_000_000;

    private RovenfallMobRuntime() {
    }

    public static void register(IEventBus eventBus) {
        eventBus.addListener(RovenfallMobRuntime::onJoinLevel);
        eventBus.addListener(EventPriority.LOWEST, RovenfallMobRuntime::onDeath);
    }

    public static boolean canSpawnNaturally(
            ServerLevel level, Identifier definitionId, BlockPos position, RandomSource random) {
        var definition = MobContentReloadListener.mob(level.getServer(), definitionId).orElse(null);
        if (definition == null || definition.spawn().isEmpty()) {
            return false;
        }
        var state = PlatformSavedData.get(level.getServer());
        var spawn = definition.spawn().orElseThrow();
        return allows(spawn, level.dimension(), position.getY(), state.isWildernessOperationLocked(),
                        state.isProtectedRegion(ClaimKey.at(level.dimension(), position)))
                && random.nextInt(CHANCE_SCALE) < spawn.chancePerMillion();
    }

    public static boolean allows(
            MobContentCatalog.SpawnCondition spawn,
            ResourceKey<Level> dimension,
            int y,
            boolean wildernessOperationLocked,
            boolean protectedRegion) {
        return spawn != null
                && spawn.dimension().equals(dimension)
                && y >= spawn.minimumY()
                && y <= spawn.maximumY()
                && !wildernessOperationLocked
                && !protectedRegion;
    }

    public static void applyDefinition(
            Mob mob, MobContentCatalog.MobDefinition definition, boolean loadedFromDisk) {
        float health = mob.getHealth();
        setBaseValue(mob, Attributes.MAX_HEALTH, definition.maxHealth());
        setBaseValue(mob, Attributes.ATTACK_DAMAGE, definition.attackDamage());
        setBaseValue(mob, Attributes.MOVEMENT_SPEED, definition.movementSpeed());
        mob.setHealth(loadedFromDisk ? Math.min(health, mob.getMaxHealth()) : mob.getMaxHealth());
    }

    public static int experienceReward(ServerLevel level, Identifier definitionId) {
        return MobContentReloadListener.mob(level.getServer(), definitionId)
                .flatMap(definition -> MobContentReloadListener.snapshot(level.getServer()).loot(definition.loot()))
                .map(reward -> (int) Math.min(Integer.MAX_VALUE, reward.experience()))
                .orElse(0);
    }

    public static void dropConfiguredLoot(
            Mob mob,
            ServerLevel level,
            DamageSource source,
            boolean playerKilled,
        Identifier definitionId) {
        var snapshot = MobContentReloadListener.snapshot(level.getServer());
        var reward = snapshot.mob(definitionId)
                .flatMap(mobDefinition -> snapshot.loot(mobDefinition.loot()))
                .orElse(null);
        if (reward == null) {
            return;
        }
        for (int roll = 0; roll < reward.rolls(); roll++) {
            mob.dropFromLootTable(level, source, playerKilled, reward.lootTable());
        }
    }

    public static UUID rewardTransactionId(UUID entityId, UUID playerId) {
        String seed = "rovenfall:mob_reward:" + entityId + ":" + playerId;
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8));
    }

    public static boolean isEligibleRewardPlayer(Player player) {
        return player instanceof ServerPlayer && !(player instanceof FakePlayer);
    }

    static EconomyService.TransactionResult awardCurrency(
            PlatformSavedData state,
            UUID playerId,
            long currency,
            Identifier definitionId,
            long timestamp,
            UUID entityId,
            long initialBalance,
            long maximumBalance) {
        return EconomyService.award(
                state,
                playerId,
                currency,
                "mob_reward:" + definitionId,
                timestamp,
                rewardTransactionId(entityId, playerId),
                initialBalance,
                maximumBalance);
    }

    private static void onJoinLevel(EntityJoinLevelEvent event) {
        if (!(event.getEntity() instanceof Mob mob) || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        Identifier definitionId = RovenfallMobEntities.definitionId(mob.getType());
        if (definitionId == null) {
            return;
        }
        var definition = MobContentReloadListener.mob(level.getServer(), definitionId).orElse(null);
        if (definition == null || definition.spawn().isEmpty()) {
            event.setCanceled(true);
            return;
        }
        var state = PlatformSavedData.get(level.getServer());
        var spawn = definition.spawn().orElseThrow();
        boolean operationLocked = !event.loadedFromDisk() && state.isWildernessOperationLocked();
        if (!allows(spawn, level.dimension(), mob.blockPosition().getY(), operationLocked,
                state.isProtectedRegion(ClaimKey.at(level.dimension(), mob.blockPosition())))) {
            event.setCanceled(true);
            return;
        }
        applyDefinition(mob, definition, event.loadedFromDisk());
    }

    private static void onDeath(LivingDeathEvent event) {
        if (event.isCanceled() || !(event.getEntity() instanceof Mob mob)
                || !(mob.level() instanceof ServerLevel level)) {
            return;
        }
        Identifier definitionId = RovenfallMobEntities.definitionId(mob.getType());
        if (definitionId == null) {
            return;
        }
        var state = PlatformSavedData.get(level.getServer());
        var snapshot = MobContentReloadListener.snapshot(level.getServer());
        var definition = snapshot.mob(definitionId).orElse(null);
        if (definition == null || definition.spawn().isEmpty()
                || !allows(definition.spawn().orElseThrow(), level.dimension(), mob.blockPosition().getY(),
                        state.isWildernessOperationLocked(),
                        state.isProtectedRegion(ClaimKey.at(level.dimension(), mob.blockPosition())))) {
            return;
        }
        ServerPlayer player = responsiblePlayer(event, mob);
        if (player == null) {
            return;
        }
        var reward = snapshot.loot(definition.loot()).orElse(null);
        if (reward == null || reward.currency() <= 0) {
            return;
        }
        var result = awardCurrency(
                state,
                player.getUUID(),
                reward.currency(),
                definitionId,
                System.currentTimeMillis(),
                mob.getUUID(),
                EconomyConfig.initialBalance(),
                EconomyConfig.maximumBalance());
        if (result.status() != EconomyService.TransactionStatus.SUCCESS
                && result.status() != EconomyService.TransactionStatus.DUPLICATE_TRANSACTION) {
            LOGGER.warn("Could not award mob reward mob={} player={} status={}",
                    definitionId, player.getUUID(), result.status());
        }
    }

    private static ServerPlayer responsiblePlayer(LivingDeathEvent event, Mob mob) {
        if (event.getSource().getEntity() instanceof ServerPlayer player && isEligibleRewardPlayer(player)) {
            return player;
        }
        Player lastAttacker = mob.getLastHurtByPlayer();
        return isEligibleRewardPlayer(lastAttacker) ? (ServerPlayer) lastAttacker : null;
    }

    private static void setBaseValue(Mob mob, net.minecraft.core.Holder<Attribute> attribute, double value) {
        AttributeInstance instance = mob.getAttribute(attribute);
        if (instance != null) {
            instance.setBaseValue(value);
        }
    }
}
