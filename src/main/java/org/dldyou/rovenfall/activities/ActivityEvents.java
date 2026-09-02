package org.dldyou.rovenfall.activities;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CropBlock;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.living.BabyEntitySpawnEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.PistonEvent;
import net.neoforged.neoforge.event.level.block.BreakBlockEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import org.dldyou.rovenfall.administration.ActivityProgressionService;
import org.dldyou.rovenfall.administration.PlatformSavedData;
import org.dldyou.rovenfall.activities.ActivityRewardReloadListener.ResolvedReward;
import org.dldyou.rovenfall.careers.CareerDefinitionReloadListener;

public final class ActivityEvents {
    private static final TagKey<Block> ORES = TagKey.create(
            Registries.BLOCK, Identifier.fromNamespaceAndPath("c", "ores"));
    private static final int MAX_HUNTING_TARGETS = 10_000;
    private static final int MAX_HUNTING_CONTRIBUTORS = 64;
    private static final long HUNTING_RETENTION_MILLIS = 10 * 60 * 1_000L;
    private final Map<PistonKey, PistonMovement> pendingPistonMovements = new HashMap<>();
    private final Map<UUID, HuntingTarget> huntingTargets = new HashMap<>();

    private ActivityEvents() {
    }

    public static void register(IEventBus eventBus) {
        ActivityEvents handler = new ActivityEvents();
        eventBus.addListener(handler::onPlayerTick);
        eventBus.addListener(EventPriority.LOWEST, handler::onBlockPlaced);
        eventBus.addListener(EventPriority.LOWEST, handler::onBlockBroken);
        eventBus.addListener(handler::onItemCrafted);
        eventBus.addListener(handler::onItemSmelted);
        eventBus.addListener(EventPriority.LOWEST, handler::onBabySpawn);
        eventBus.addListener(EventPriority.LOWEST, handler::onPistonPre);
        eventBus.addListener(handler::onPistonPost);
        eventBus.addListener(handler::onLivingDamage);
        eventBus.addListener(EventPriority.LOWEST, handler::onLivingDeath);
        eventBus.addListener(handler::onEntityLeaveLevel);
    }

    private void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)
                || player instanceof FakePlayer
                || !(player.level() instanceof ServerLevel level)
                || level.getGameTime() % 20 != 0) {
            return;
        }
        var biomeKey = level.getBiome(player.blockPosition()).unwrapKey();
        if (biomeKey.isEmpty()) {
            return;
        }
        var targetId = biomeKey.orElseThrow().identifier();
        var reward = ActivityRewardReloadListener.get(
                level.getServer(), ActivityKind.EXPLORATION_DISCOVERY, targetId);
        if (reward.isEmpty()) {
            return;
        }
        String subjectKey = "biome:" + targetId;
        var state = PlatformSavedData.get(level.getServer());
        String discoveryKey = ActivityKind.EXPLORATION_DISCOVERY.getSerializedName() + ":" + subjectKey;
        if (state.hasActivityDiscovery(player.getUUID(), discoveryKey)) {
            return;
        }
        var position = player.blockPosition();
        long timestamp = System.currentTimeMillis();
        UUID evidenceId = UUID.nameUUIDFromBytes((
                "rovenfall:exploration:" + player.getUUID() + ":" + targetId)
                .getBytes(StandardCharsets.UTF_8));
        award(player, level, position, evidenceId, timestamp,
                ActivityKind.EXPLORATION_DISCOVERY, targetId, subjectKey, 1,
                ActivityProvenance.explorationDiscovery(), reward.orElseThrow());
    }

    private void onBlockPlaced(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)
                || player instanceof FakePlayer
                || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        var targetId = BuiltInRegistries.BLOCK.getKey(event.getPlacedBlock().getBlock());
        var state = PlatformSavedData.get(level.getServer());
        boolean trackedResource = event.getPlacedBlock().is(ORES)
                || ActivityRewardReloadListener.get(
                        level.getServer(), ActivityKind.NATURAL_RESOURCE_BREAK, targetId).isPresent();
        if (!state.observeActivityResourcePlacement(level.dimension(), event.getPos(), trackedResource)
                && trackedResource) {
            event.setCanceled(true);
            return;
        }
        ActivityRewardReloadListener.get(level.getServer(), ActivityKind.BUILDING_PLACEMENT, targetId)
                .ifPresent(reward -> award(
                        player, level, event.getPos(), UUID.randomUUID(), System.currentTimeMillis(),
                        ActivityKind.BUILDING_PLACEMENT, targetId, "block:" + targetId, 1,
                        new ActivityProvenance(false, false, false), reward));
    }

    private void onBlockBroken(BreakBlockEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayer player)
                || player instanceof FakePlayer
                || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        var targetId = BuiltInRegistries.BLOCK.getKey(event.getState().getBlock());
        var state = PlatformSavedData.get(level.getServer());
        boolean playerPlacedResource = state.isActivityResourcePlayerPlaced(level.dimension(), event.getPos());
        state.clearActivityResourcePlacement(level.dimension(), event.getPos());
        long timestamp = System.currentTimeMillis();
        if (event.getState().getBlock() instanceof CropBlock crop && crop.isMaxAge(event.getState())) {
            ActivityRewardReloadListener.get(level.getServer(), ActivityKind.MATURE_CROP_HARVEST, targetId)
                    .ifPresent(reward -> award(
                            player, level, event.getPos(), UUID.randomUUID(), timestamp,
                            ActivityKind.MATURE_CROP_HARVEST, targetId, "crop:" + targetId, 1,
                            new ActivityProvenance(false, true, false), reward));
        }
        if (!playerPlacedResource) {
            ActivityRewardReloadListener.get(level.getServer(), ActivityKind.NATURAL_RESOURCE_BREAK, targetId)
                    .ifPresent(reward -> award(
                            player, level, event.getPos(), UUID.randomUUID(), timestamp,
                            ActivityKind.NATURAL_RESOURCE_BREAK, targetId, "resource:" + targetId, 1,
                            new ActivityProvenance(true, false, false), reward));
        }
    }

    private void onItemSmelted(PlayerEvent.ItemSmeltedEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        awardCookingResult(player, event.getSmelting(), event.getAmountRemoved());
    }

    private void onItemCrafted(PlayerEvent.ItemCraftedEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        awardCookingResult(player, event.getCrafting(), event.getCrafting().getCount());
    }

    private static void awardCookingResult(ServerPlayer player, ItemStack result, long amount) {
        if (player instanceof FakePlayer
                || !(player.level() instanceof ServerLevel level)
                || result == null
                || result.isEmpty()
                || amount < 1) {
            return;
        }
        var targetId = BuiltInRegistries.ITEM.getKey(result.getItem());
        long contribution = Math.min(amount, ActivityObservation.MAX_CONTRIBUTION);
        ActivityRewardReloadListener.get(level.getServer(), ActivityKind.COOKING_RESULT, targetId)
                .ifPresent(reward -> award(
                        player, level, player.blockPosition(), UUID.randomUUID(), System.currentTimeMillis(),
                        ActivityKind.COOKING_RESULT, targetId, "item:" + targetId, contribution,
                        new ActivityProvenance(false, false, false), reward));
    }

    private void onBabySpawn(BabyEntitySpawnEvent event) {
        if (!(event.getCausedByPlayer() instanceof ServerPlayer player)
                || player instanceof FakePlayer
                || event.getChild() == null
                || !(event.getParentA().level() instanceof ServerLevel level)) {
            return;
        }
        var targetId = BuiltInRegistries.ENTITY_TYPE.getKey(event.getChild().getType());
        ActivityRewardReloadListener.get(level.getServer(), ActivityKind.BREEDING_COMPLETION, targetId)
                .ifPresent(reward -> award(
                        player, level, event.getParentA().blockPosition(), UUID.randomUUID(),
                        System.currentTimeMillis(), ActivityKind.BREEDING_COMPLETION, targetId,
                        "entity:" + targetId, 1, new ActivityProvenance(false, false, false), reward));
    }

    private void onPistonPre(PistonEvent.Pre event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        var resolver = event.getStructureHelper();
        if (resolver == null || !resolver.resolve()) {
            return;
        }
        PlatformSavedData state = PlatformSavedData.get(level.getServer());
        Map<BlockPos, BlockPos> movements = new LinkedHashMap<>();
        Direction movement = resolver.getPushDirection();
        for (BlockPos source : resolver.getToPush()) {
            movements.put(source.immutable(), source.relative(movement).immutable());
        }
        List<BlockPos> destroyed = resolver.getToDestroy().stream().map(BlockPos::immutable).toList();
        boolean tracked = movements.keySet().stream()
                .anyMatch(position -> state.isActivityResourcePlayerPlaced(level.dimension(), position))
                || destroyed.stream()
                .anyMatch(position -> state.isActivityResourcePlayerPlaced(level.dimension(), position));
        PistonKey key = PistonKey.of(level.dimension(), event);
        if (tracked) {
            pendingPistonMovements.put(key, new PistonMovement(Map.copyOf(movements), destroyed));
        } else {
            pendingPistonMovements.remove(key);
        }
    }

    private void onPistonPost(PistonEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        PistonMovement movement = pendingPistonMovements.remove(PistonKey.of(level.dimension(), event));
        if (movement != null) {
            PlatformSavedData.get(level.getServer()).moveActivityResourcePlacements(
                    level.dimension(), movement.movements(), movement.destroyed());
        }
    }

    private void onLivingDamage(LivingDamageEvent.Post event) {
        if (!(event.getSource().getEntity() instanceof ServerPlayer player)
                || player instanceof FakePlayer
                || event.getEntity() == player
                || !(event.getEntity().level() instanceof ServerLevel level)
                || !Float.isFinite(event.getHealthDamage())
                || event.getHealthDamage() <= 0) {
            return;
        }
        Identifier targetId = BuiltInRegistries.ENTITY_TYPE.getKey(event.getEntity().getType());
        long timestamp = System.currentTimeMillis();
        long contribution = damageUnits(event.getHealthDamage(), event.getEntity().getMaxHealth());
        ActivityRewardReloadListener.get(level.getServer(), ActivityKind.COMBAT_DAMAGE, targetId)
                .ifPresent(reward -> award(
                        player, level, event.getEntity().blockPosition(), UUID.randomUUID(), timestamp,
                        ActivityKind.COMBAT_DAMAGE, targetId, "entity:" + targetId, contribution,
                        new ActivityProvenance(false, false, false), reward));

        if (ActivityRewardReloadListener.get(
                level.getServer(), ActivityKind.HUNTING_CONTRIBUTION, targetId).isPresent()) {
            trackHuntingContribution(
                    event.getEntity().getUUID(), targetId, player.getUUID(), event.getHealthDamage(),
                    event.getEntity().getMaxHealth(), timestamp);
        }
    }

    private void onLivingDeath(LivingDeathEvent event) {
        if (!(event.getEntity().level() instanceof ServerLevel level)) {
            return;
        }
        HuntingTarget target = huntingTargets.remove(event.getEntity().getUUID());
        if (target == null) {
            return;
        }
        Optional<ResolvedReward> reward = ActivityRewardReloadListener.get(
                level.getServer(), ActivityKind.HUNTING_CONTRIBUTION, target.targetId());
        if (reward.isEmpty()) {
            return;
        }
        long timestamp = System.currentTimeMillis();
        BlockPos position = event.getEntity().blockPosition();
        target.contributions().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    ServerPlayer player = level.getServer().getPlayerList().getPlayer(entry.getKey());
                    award(entry.getKey(), player, level, position, UUID.randomUUID(), timestamp,
                            ActivityKind.HUNTING_CONTRIBUTION, target.targetId(),
                            "entity:" + target.targetId() + ":kill:" + event.getEntity().getUUID(),
                            damageUnits(entry.getValue(), target.maximumContribution()),
                            new ActivityProvenance(false, false, false), reward.orElseThrow());
                });
    }

    private void onEntityLeaveLevel(EntityLeaveLevelEvent event) {
        huntingTargets.remove(event.getEntity().getUUID());
    }

    private void trackHuntingContribution(
            UUID targetId,
            Identifier targetType,
            UUID playerId,
            double damage,
            double maximumContribution,
            long timestamp) {
        if (huntingTargets.size() >= MAX_HUNTING_TARGETS && !huntingTargets.containsKey(targetId)) {
            long cutoff = timestamp - HUNTING_RETENTION_MILLIS;
            huntingTargets.entrySet().removeIf(entry -> entry.getValue().lastObservedAt() < cutoff);
            if (huntingTargets.size() >= MAX_HUNTING_TARGETS) {
                return;
            }
        }
        HuntingTarget target = huntingTargets.computeIfAbsent(
                targetId, ignored -> new HuntingTarget(targetType, Math.max(1, maximumContribution)));
        target.add(playerId, damage, timestamp);
    }

    private static long damageUnits(double damage, double maximum) {
        double bounded = Math.min(Math.max(1, damage), Math.max(1, maximum));
        return Math.min(ActivityObservation.MAX_CONTRIBUTION, (long) Math.ceil(bounded));
    }

    private static void award(
            ServerPlayer player,
            ServerLevel level,
            BlockPos position,
            UUID evidenceId,
            long timestamp,
            ActivityKind kind,
            Identifier targetId,
            String subjectKey,
            long contribution,
            ActivityProvenance provenance,
            ResolvedReward reward) {
        award(player.getUUID(), player, level, position, evidenceId, timestamp,
                kind, targetId, subjectKey, contribution, provenance, reward);
    }

    private static void award(
            UUID playerId,
            ServerPlayer feedbackPlayer,
            ServerLevel level,
            BlockPos position,
            UUID evidenceId,
            long timestamp,
            ActivityKind kind,
            Identifier targetId,
            String subjectKey,
            long contribution,
            ActivityProvenance provenance,
            ResolvedReward reward) {
        var observation = new ActivityObservation(
                evidenceId,
                timestamp,
                playerId,
                kind.track(),
                kind,
                level.dimension(),
                position.getX() >> 4,
                position.getZ() >> 4,
                targetId,
                subjectKey,
                contribution,
                provenance);
        var careerCatalog = CareerDefinitionReloadListener.snapshot(level.getServer()).orElse(null);
        var result = ActivityProgressionService.award(
                PlatformSavedData.get(level.getServer()), observation, reward, careerCatalog);
        if (result.awarded() && feedbackPlayer != null && feedbackPlayer.connection != null) {
            if (result.awardedCareerExperience() > 0 && result.careerId().isPresent()) {
                Identifier careerId = result.careerId().orElseThrow();
                Component careerName = careerCatalog.definition(careerId)
                        .<Component>map(definition -> Component.translatable(definition.translationKey()))
                        .orElseGet(() -> Component.literal(careerId.toString()));
                feedbackPlayer.sendOverlayMessage(Component.translatable(
                        "message.rovenfall.activity.awarded_with_career",
                        Component.translatable(kind.track().translationKey()),
                        result.awardedExperience(),
                        result.totalExperience(),
                        careerName,
                        result.awardedCareerExperience(),
                        result.totalCareerExperience()));
            } else {
                feedbackPlayer.sendOverlayMessage(Component.translatable(
                        "message.rovenfall.activity.awarded",
                        Component.translatable(kind.track().translationKey()),
                        result.awardedExperience(),
                        result.totalExperience()));
            }
        }
    }

    private record PistonKey(
            ResourceKey<Level> dimension,
            BlockPos position,
            Direction direction,
            PistonEvent.PistonMoveType moveType) {
        private static PistonKey of(ResourceKey<Level> dimension, PistonEvent event) {
            return new PistonKey(
                    dimension, event.getPos().immutable(), event.getDirection(), event.getPistonMoveType());
        }
    }

    private record PistonMovement(Map<BlockPos, BlockPos> movements, List<BlockPos> destroyed) {
        private PistonMovement {
            movements = Map.copyOf(movements);
            destroyed = List.copyOf(destroyed);
        }
    }

    private static final class HuntingTarget {
        private final Identifier targetId;
        private final double maximumContribution;
        private final Map<UUID, Double> contributions = new HashMap<>();
        private double totalContribution;
        private long lastObservedAt;

        private HuntingTarget(Identifier targetId, double maximumContribution) {
            this.targetId = targetId;
            this.maximumContribution = maximumContribution;
        }

        private void add(UUID playerId, double damage, long timestamp) {
            if (!Double.isFinite(damage) || damage <= 0
                    || !contributions.containsKey(playerId)
                    && contributions.size() >= MAX_HUNTING_CONTRIBUTORS) {
                return;
            }
            double accepted = Math.min(damage, Math.max(0, maximumContribution - totalContribution));
            if (accepted <= 0) {
                return;
            }
            contributions.merge(playerId, accepted, Double::sum);
            totalContribution += accepted;
            lastObservedAt = timestamp;
        }

        private Identifier targetId() {
            return targetId;
        }

        private double maximumContribution() {
            return maximumContribution;
        }

        private Map<UUID, Double> contributions() {
            return Map.copyOf(contributions);
        }

        private long lastObservedAt() {
            return lastObservedAt;
        }
    }
}
