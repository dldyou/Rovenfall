package org.dldyou.rovenfall.mobs;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import org.dldyou.rovenfall.administration.EconomyConfig;
import org.dldyou.rovenfall.administration.EconomyService;
import org.dldyou.rovenfall.administration.PlatformSavedData;
import org.dldyou.rovenfall.administration.WorldCombatService;

public final class MobMutationEvents {
    private static final int MAX_TARGETS = 10_000;
    private static final int MAX_CONTRIBUTORS = 64;
    private static final double MINIMUM_REWARD_SHARE = 0.10;
    private final Map<UUID, Contributions> contributions = new HashMap<>();

    private MobMutationEvents() {
    }

    public static void register(IEventBus eventBus) {
        MobMutationEvents handler = new MobMutationEvents();
        eventBus.addListener(EventPriority.LOWEST, handler::onFinalizeSpawn);
        eventBus.addListener(handler::onEntityJoin);
        eventBus.addListener(handler::onLivingDamage);
        eventBus.addListener(EventPriority.LOWEST, handler::onLivingDeath);
        eventBus.addListener(handler::onEntityLeave);
    }

    private void onFinalizeSpawn(FinalizeSpawnEvent event) {
        if (!(event.getEntity() instanceof Mob mob)
                || event.isSpawnCancelled()
                || !eligibleSpawn(
                        event.getLevel().getLevel().dimension(),
                        event.getSpawnType(),
                        MobSpawnPolicy.isRovenfallOrdinaryMob(mob),
                        MobMutationApplicator.mutationId(mob).isPresent())) {
            return;
        }
        MobMutationReloadListener.snapshot(event.getLevel().getServer())
                .flatMap(catalog -> catalog.choose(mob, event.getLevel().getRandom()))
                .ifPresent(mutation -> MobMutationApplicator.apply(mob, mutation, false));
    }

    public static boolean eligibleSpawn(
            ResourceKey<Level> dimension,
            EntitySpawnReason reason,
            boolean rovenfallOrdinaryMob,
            boolean alreadyMutated) {
        return WorldCombatService.WILDERNESS_DIMENSION.equals(dimension)
                && MobSpawnPolicy.naturalReason(reason)
                && !rovenfallOrdinaryMob
                && !alreadyMutated;
    }

    private void onEntityJoin(EntityJoinLevelEvent event) {
        if (!event.loadedFromDisk() || !(event.getLevel() instanceof ServerLevel level)
                || !(event.getEntity() instanceof Mob mob)) {
            return;
        }
        MobMutationApplicator.mutationId(mob).flatMap(id ->
                MobMutationReloadListener.snapshot(level.getServer()).flatMap(catalog -> catalog.get(id)))
                .ifPresent(mutation -> MobMutationApplicator.apply(mob, mutation, true));
    }

    private void onLivingDamage(LivingDamageEvent.Post event) {
        if (!(event.getEntity() instanceof Mob mob)
                || MobMutationApplicator.mutationId(mob).isEmpty()
                || !(event.getSource().getEntity() instanceof ServerPlayer player)
                || player instanceof FakePlayer
                || !Float.isFinite(event.getHealthDamage()) || event.getHealthDamage() <= 0) {
            return;
        }
        if (contributions.size() >= MAX_TARGETS && !contributions.containsKey(mob.getUUID())) {
            return;
        }
        contributions.computeIfAbsent(mob.getUUID(), ignored -> new Contributions())
                .add(player.getUUID(), Math.min(event.getHealthDamage(), mob.getMaxHealth()));
    }

    private void onLivingDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof Mob mob) || !(mob.level() instanceof ServerLevel level)) {
            return;
        }
        Contributions target = contributions.remove(mob.getUUID());
        Optional<MobMutationCatalog.ResolvedMutation> mutation = MobMutationApplicator.mutationId(mob)
                .flatMap(id -> MobMutationReloadListener.snapshot(level.getServer()).flatMap(catalog -> catalog.get(id)));
        if (target == null || mutation.isEmpty() || target.total <= 0) {
            return;
        }
        long timestamp = Instant.now().toEpochMilli();
        target.byPlayer.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .filter(entry -> entry.getValue() / target.total >= MINIMUM_REWARD_SHARE)
                .forEach(entry -> reward(level, mob, mutation.orElseThrow(), entry, timestamp));
    }

    private static void reward(
            ServerLevel level,
            Mob mob,
            MobMutationCatalog.ResolvedMutation mutation,
            Map.Entry<UUID, Double> contribution,
            long timestamp) {
        UUID transactionId = UUID.nameUUIDFromBytes(("rovenfall:mutation_reward:"
                + mob.getUUID() + ":" + contribution.getKey()).getBytes(StandardCharsets.UTF_8));
        var result = EconomyService.award(
                PlatformSavedData.get(level.getServer()),
                contribution.getKey(),
                mutation.definition().currencyReward(),
                "mutation reward " + mutation.id(),
                timestamp,
                transactionId,
                EconomyConfig.initialBalance(),
                EconomyConfig.maximumBalance());
        if (result.status() == EconomyService.TransactionStatus.SUCCESS) {
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(contribution.getKey());
            if (player != null && player.connection != null) {
                player.sendOverlayMessage(Component.translatable(
                        "message.rovenfall.mutation.reward",
                        Component.translatable(mutation.definition().translationKey()),
                        mutation.definition().currencyReward()));
            }
        }
    }

    private void onEntityLeave(EntityLeaveLevelEvent event) {
        contributions.remove(event.getEntity().getUUID());
    }

    private static final class Contributions {
        private final Map<UUID, Double> byPlayer = new HashMap<>();
        private double total;

        private void add(UUID playerId, double amount) {
            if (byPlayer.size() >= MAX_CONTRIBUTORS && !byPlayer.containsKey(playerId)) {
                return;
            }
            byPlayer.merge(playerId, amount, Double::sum);
            total += amount;
        }
    }
}
