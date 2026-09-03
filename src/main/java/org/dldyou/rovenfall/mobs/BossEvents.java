package org.dldyou.rovenfall.mobs;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.Mob;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.dldyou.rovenfall.Rovenfall;
import org.dldyou.rovenfall.administration.BossEncounterService;
import org.dldyou.rovenfall.administration.PlatformSavedData;
import org.dldyou.rovenfall.administration.WorldCombatService;

public final class BossEvents {
    private static final String RECOVERY_AUDITED_TAG = "RovenfallBossRecoveryAudited";
    private static final Identifier WARDEN_DEFEATED_ADVANCEMENT = Identifier.fromNamespaceAndPath(
            Rovenfall.MOD_ID, "wilderness/warden_defeated");
    private static final Identifier WARDEN_RELIC_REWARD_ADVANCEMENT = Identifier.fromNamespaceAndPath(
            Rovenfall.MOD_ID, "wilderness/warden_relic_reward");
    private static final Set<UUID> PENDING_SPAWNS = ConcurrentHashMap.newKeySet();

    private BossEvents() {
    }

    public static void register(IEventBus eventBus) {
        eventBus.addListener(EventPriority.HIGHEST, BossEvents::onFinalizeSpawn);
        eventBus.addListener(BossEvents::onEntityJoin);
        eventBus.addListener(BossEvents::onLivingDamage);
        eventBus.addListener(EventPriority.LOWEST, BossEvents::onLivingDeath);
        eventBus.addListener(BossEvents::onPlayerLoggedIn);
        eventBus.addListener(BossEvents::onServerTick);
    }

    public static boolean spawnManaged(ServerLevel level, BossEncounter encounter) {
        if (level == null || encounter == null || !level.dimension().equals(encounter.dimension())) {
            return false;
        }
        ArenaWarden boss = RovenfallEntityTypes.ARENA_WARDEN.get().create(level, EntitySpawnReason.EVENT);
        if (boss == null) {
            return false;
        }
        boss.setUUID(encounter.bossId());
        boss.initializeEncounter(encounter.encounterId(), encounter.origin(), encounter.radius());
        boss.setPos(encounter.origin().getX() + 0.5, encounter.origin().getY(), encounter.origin().getZ() + 0.5);
        PENDING_SPAWNS.add(encounter.encounterId());
        try {
            boss.finalizeSpawn(
                    level,
                    level.getCurrentDifficultyAt(encounter.origin()),
                    EntitySpawnReason.EVENT,
                    null);
            return !boss.isSpawnCancelled() && level.addFreshEntity(boss);
        } finally {
            PENDING_SPAWNS.remove(encounter.encounterId());
        }
    }

    private static void onFinalizeSpawn(FinalizeSpawnEvent event) {
        if (!(event.getEntity() instanceof ArenaWarden boss)) {
            return;
        }
        boolean allowed = event.getLevel().getLevel().dimension().equals(WorldCombatService.WILDERNESS_DIMENSION)
                && event.getSpawnType() == EntitySpawnReason.EVENT
                && boss.encounterId().filter(PENDING_SPAWNS::contains).isPresent();
        if (!allowed) {
            event.setSpawnCancelled(true);
        }
    }

    private static void onEntityJoin(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getEntity() instanceof ArenaWarden boss)) {
            return;
        }
        UUID encounterId = boss.encounterId().orElse(null);
        boolean pending = encounterId != null && PENDING_SPAWNS.contains(encounterId);
        boolean retained = encounterId != null && PlatformSavedData.get(level.getServer()).bossEncounter()
                .filter(encounter -> encounter.active()
                        && encounter.encounterId().equals(encounterId)
                        && encounter.bossId().equals(boss.getUUID())
                        && encounter.dimension().equals(level.dimension()))
                .isPresent();
        if (!pending && !retained) {
            event.setCanceled(true);
            return;
        }
        if (event.loadedFromDisk() && retained
                && !boss.getPersistentData().getBooleanOr(RECOVERY_AUDITED_TAG, false)) {
            BossEncounterService.auditRecovered(
                    PlatformSavedData.get(level.getServer()), encounterId, System.currentTimeMillis());
            boss.getPersistentData().putBoolean(RECOVERY_AUDITED_TAG, true);
        }
    }

    private static void onLivingDamage(LivingDamageEvent.Post event) {
        if (!(event.getEntity() instanceof ArenaWarden boss)
                || !(boss.level() instanceof ServerLevel level)
                || !(event.getSource().getEntity() instanceof ServerPlayer player)
                || player instanceof FakePlayer
                || !Float.isFinite(event.getHealthDamage()) || event.getHealthDamage() <= 0) {
            return;
        }
        boss.encounterId().ifPresent(encounterId -> BossEncounterService.recordContribution(
                PlatformSavedData.get(level.getServer()),
                encounterId,
                boss.getUUID(),
                player.getUUID(),
                player.blockPosition(),
                Math.min(event.getHealthDamage(), boss.getMaxHealth()),
                System.currentTimeMillis()));
    }

    private static void onLivingDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ArenaWarden boss)
                || !(boss.level() instanceof ServerLevel level)) {
            return;
        }
        boss.encounterId().ifPresent(encounterId -> {
            PlatformSavedData state = PlatformSavedData.get(level.getServer());
            long now = System.currentTimeMillis();
            BossEncounterService.beginRewards(state, encounterId, now);
            sendRewardFeedback(level, BossEncounterService.settleRewards(state, now));
            cleanupMinions(level, boss, encounterId);
        });
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        if (event.getServer().overworld().getGameTime() % 20 != 0) {
            return;
        }
        sendRewardFeedback(
                event.getServer().overworld(),
                BossEncounterService.settleRewards(
                        PlatformSavedData.get(event.getServer()), System.currentTimeMillis()));
    }

    private static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || player instanceof FakePlayer) {
            return;
        }
        if (PlatformSavedData.get(player.level().getServer()).bossState()
                .rewardReadyAt(player.getUUID()) > 0) {
            awardWardenVictory(player);
        }
    }

    private static void cleanupMinions(ServerLevel level, ArenaWarden boss, UUID encounterId) {
        level.getEntitiesOfClass(Mob.class, boss.getBoundingBox().inflate(boss.arenaRadius() + 8.0), mob ->
                        mob.getPersistentData().getStringOr(ArenaWarden.MINION_ENCOUNTER_TAG, "")
                                .equals(encounterId.toString()))
                .forEach(Mob::discard);
    }

    private static void sendRewardFeedback(
            ServerLevel level, java.util.List<BossEncounterService.RewardResult> results) {
        results.forEach(result -> {
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(result.playerId());
            if (player == null || player.connection == null) {
                return;
            }
            if (result.status() == BossEncounterService.RewardStatus.SUCCESS) {
                awardWardenVictory(player);
            }
            switch (result.status()) {
                case SUCCESS -> player.sendOverlayMessage(Component.translatable(
                        "message.rovenfall.boss.reward", result.amount()));
                case COOLDOWN -> player.sendOverlayMessage(Component.translatable(
                        "message.rovenfall.boss.reward_cooldown", result.readyAtEpochMillis()));
                case INELIGIBLE -> player.sendOverlayMessage(Component.translatable(
                        "message.rovenfall.boss.reward_ineligible"));
                default -> player.sendOverlayMessage(Component.translatable(
                        "message.rovenfall.boss.reward_failed"));
            }
        });
    }

    private static void awardWardenVictory(ServerPlayer player) {
        var advancements = player.level().getServer().getAdvancements();
        var defeated = advancements.get(WARDEN_DEFEATED_ADVANCEMENT);
        if (defeated != null) {
            player.getAdvancements().award(defeated, "defeated");
        }
        var relicReward = advancements.get(WARDEN_RELIC_REWARD_ADVANCEMENT);
        if (relicReward != null) {
            player.getAdvancements().award(relicReward, "rewarded");
        }
    }
}
