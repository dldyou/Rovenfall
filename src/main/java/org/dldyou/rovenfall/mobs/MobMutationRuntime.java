package org.dldyou.rovenfall.mobs;

import com.mojang.logging.LogUtils;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.living.LivingExperienceDropEvent;
import org.dldyou.rovenfall.Rovenfall;
import org.dldyou.rovenfall.administration.EconomyConfig;
import org.dldyou.rovenfall.administration.EconomyService;
import org.dldyou.rovenfall.administration.PlatformSavedData;
import org.dldyou.rovenfall.claims.ClaimKey;
import org.slf4j.Logger;

public final class MobMutationRuntime {
    public static final int MAX_MUTATIONS_PER_MOB = 8;
    private static final int CHANCE_SCALE = 1_000_000;
    private static final float DEATH_BURST_RADIUS = 2.5F;
    private static final Identifier DEATH_BURST = id("death_burst");
    private static final String MUTATIONS = "rovenfall:mutations";
    private static final String MODIFIERS = "rovenfall:mutation_modifiers";
    private static final String ORIGINAL_GLOW = "rovenfall:mutation_original_glow";
    private static final String ORIGINAL_NAME_VISIBLE = "rovenfall:mutation_original_name_visible";
    private static final String MANAGED_NAME = "rovenfall:mutation_managed_name";
    private static final String MANAGED_NAME_SIGNATURE = "rovenfall:mutation_managed_name_signature";
    private static final String DEATH_BURST_FIRED = "rovenfall:mutation_death_burst_fired";
    private static final Logger LOGGER = LogUtils.getLogger();

    private MobMutationRuntime() {
    }

    public static void register(IEventBus eventBus) {
        eventBus.addListener(EventPriority.LOWEST, MobMutationRuntime::onJoinLevel);
        eventBus.addListener(EventPriority.LOWEST, MobMutationRuntime::onDeath);
        eventBus.addListener(EventPriority.LOWEST, MobMutationRuntime::onDrops);
        eventBus.addListener(EventPriority.LOWEST, MobMutationRuntime::onExperienceDrop);
        eventBus.addListener(MobMutationRuntime::onDatapackSync);
    }

    public static int selectionBucket(UUID entityId, Identifier mutationId) {
        long value = entityId.getMostSignificantBits()
                ^ Long.rotateLeft(entityId.getLeastSignificantBits(), 29)
                ^ ((long) mutationId.toString().hashCode() * 0x9E3779B97F4A7C15L);
        value ^= value >>> 30;
        value *= 0xBF58476D1CE4E5B9L;
        value ^= value >>> 27;
        value *= 0x94D049BB133111EBL;
        value ^= value >>> 31;
        return (int) Math.floorMod(value, CHANCE_SCALE);
    }

    public static List<MobContentCatalog.MutationDefinition> selectFresh(
            MobContentSnapshot snapshot,
            Identifier entityType,
            UUID entityId,
            net.minecraft.resources.ResourceKey<Level> dimension,
            int y,
            boolean operationLocked,
            boolean protectedRegion) {
        if (operationLocked || protectedRegion) {
            return List.of();
        }
        return snapshot.mutations().values().stream()
                .sorted(Comparator.comparing(MobContentCatalog.MutationDefinition::id))
                .filter(mutation -> mutation.eligibleEntityTypes().contains(entityType))
                .filter(mutation -> RovenfallMobRuntime.allows(
                        mutation.spawn(), dimension, y, false, false))
                .filter(mutation -> selectionBucket(entityId, mutation.id()) < mutation.spawn().chancePerMillion())
                .limit(MAX_MUTATIONS_PER_MOB)
                .toList();
    }

    public static OptionalLong scaleReward(long base, List<Integer> multipliers, List<Long> bonuses) {
        if (base < 0 || multipliers.size() > MAX_MUTATIONS_PER_MOB
                || bonuses.size() > MAX_MUTATIONS_PER_MOB) {
            return OptionalLong.empty();
        }
        try {
            long scaled = base;
            for (int multiplier : multipliers) {
                if (multiplier < 1 || multiplier > 10_000) {
                    return OptionalLong.empty();
                }
                scaled = Math.multiplyExact(scaled, multiplier) / 100;
                if (scaled > MobContentSnapshot.MAX_REWARD) {
                    return OptionalLong.empty();
                }
            }
            for (long bonus : bonuses) {
                if (bonus < 0) {
                    return OptionalLong.empty();
                }
                scaled = Math.addExact(scaled, bonus);
                if (scaled > MobContentSnapshot.MAX_REWARD) {
                    return OptionalLong.empty();
                }
            }
            return OptionalLong.of(scaled);
        } catch (ArithmeticException exception) {
            return OptionalLong.empty();
        }
    }

    public static List<Identifier> mutationIds(Mob mob) {
        String encoded = mob.getPersistentData().getStringOr(MUTATIONS, "");
        if (encoded.isBlank()) {
            return List.of();
        }
        List<Identifier> ids = new ArrayList<>();
        Set<Identifier> unique = new HashSet<>();
        for (String value : encoded.split(",")) {
            Identifier id = Identifier.tryParse(value);
            if (id == null || !unique.add(id) || ids.size() >= MAX_MUTATIONS_PER_MOB) {
                return List.of();
            }
            ids.add(id);
        }
        ids.sort(Comparator.naturalOrder());
        return List.copyOf(ids);
    }

    public static void applyMutations(
            Mob mob, List<MobContentCatalog.MutationDefinition> requested, boolean loadedFromDisk) {
        List<MobContentCatalog.MutationDefinition> mutations = requested.stream()
                .sorted(Comparator.comparing(MobContentCatalog.MutationDefinition::id))
                .limit(MAX_MUTATIONS_PER_MOB + 1L)
                .toList();
        if (mutations.size() > MAX_MUTATIONS_PER_MOB
                || mutations.stream().map(MobContentCatalog.MutationDefinition::id).distinct().count()
                != mutations.size()) {
            cleanup(mob);
            return;
        }
        if (mutations.isEmpty()) {
            cleanup(mob);
            return;
        }

        List<ResolvedModifier> resolved = new ArrayList<>();
        for (var mutation : mutations) {
            for (var requestedModifier : mutation.attributes()) {
                Attribute attribute = BuiltInRegistries.ATTRIBUTE.getValue(requestedModifier.attribute());
                if (attribute == null) {
                    cleanup(mob);
                    return;
                }
                AttributeInstance instance = mob.getAttribute(BuiltInRegistries.ATTRIBUTE.wrapAsHolder(attribute));
                if (instance == null) {
                    cleanup(mob);
                    return;
                }
                Identifier modifierId = modifierId(mutation.id(), requestedModifier);
                resolved.add(new ResolvedModifier(
                        requestedModifier.attribute(),
                        instance,
                        new net.minecraft.world.entity.ai.attributes.AttributeModifier(
                                modifierId, requestedModifier.amount(), operation(requestedModifier.operation()))));
            }
        }

        var data = mob.getPersistentData();
        List<Identifier> previous = mutationIds(mob);
        if (previous.isEmpty() && !data.contains(ORIGINAL_GLOW)) {
            data.putBoolean(ORIGINAL_GLOW, mob.hasGlowingTag());
            data.putBoolean(ORIGINAL_NAME_VISIBLE, mob.isCustomNameVisible());
        }
        float health = mob.getHealth();
        removeStoredModifiers(mob);

        List<String> modifierEvidence = new ArrayList<>();
        for (ResolvedModifier modifier : resolved) {
            modifier.instance().addOrReplacePermanentModifier(modifier.modifier());
            modifierEvidence.add(modifier.attributeId() + ">" + modifier.modifier().id());
        }

        data.putString(MUTATIONS, mutations.stream().map(mutation -> mutation.id().toString())
                .reduce((left, right) -> left + "," + right).orElse(""));
        data.putString(MODIFIERS, String.join(",", modifierEvidence));
        mob.setGlowingTag(true);
        updateManagedName(mob, mutations);
        mob.setHealth(loadedFromDisk ? Math.min(health, mob.getMaxHealth()) : mob.getMaxHealth());
    }

    public static void cleanup(Mob mob) {
        var data = mob.getPersistentData();
        float health = mob.getHealth();
        removeStoredModifiers(mob);
        if (data.contains(ORIGINAL_GLOW)) {
            mob.setGlowingTag(data.getBooleanOr(ORIGINAL_GLOW, false));
        }
        if (data.getBooleanOr(MANAGED_NAME, false)) {
            String signature = data.getStringOr(MANAGED_NAME_SIGNATURE, "");
            Component current = mob.getCustomName();
            if (current != null && current.toString().equals(signature)) {
                mob.setCustomName(null);
                mob.setCustomNameVisible(data.getBooleanOr(ORIGINAL_NAME_VISIBLE, false));
            }
        }
        data.remove(MUTATIONS);
        data.remove(MODIFIERS);
        data.remove(ORIGINAL_GLOW);
        data.remove(ORIGINAL_NAME_VISIBLE);
        data.remove(MANAGED_NAME);
        data.remove(MANAGED_NAME_SIGNATURE);
        data.remove(DEATH_BURST_FIRED);
        mob.setHealth(Math.min(health, mob.getMaxHealth()));
    }

    private static void onJoinLevel(EntityJoinLevelEvent event) {
        if (event.isCanceled() || !(event.getEntity() instanceof Mob mob)
                || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        refresh(mob, level, MobContentReloadListener.snapshot(level.getServer()), event.loadedFromDisk());
    }

    private static void refresh(
            Mob mob, ServerLevel level, MobContentSnapshot snapshot, boolean loadedFromDisk) {
        PlatformSavedData state = PlatformSavedData.get(level.getServer());
        boolean protectedRegion = state.isProtectedRegion(ClaimKey.at(level.dimension(), mob.blockPosition()));
        boolean operationLocked = !loadedFromDisk && state.isWildernessOperationLocked();
        Identifier entityType = EntityType.getKey(mob.getType());
        List<Identifier> persisted = mutationIds(mob);

        if (!persisted.isEmpty()) {
            List<MobContentCatalog.MutationDefinition> retained = persisted.stream()
                    .map(snapshot::mutation)
                    .flatMap(java.util.Optional::stream)
                    .filter(mutation -> mutation.eligibleEntityTypes().contains(entityType))
                    .filter(mutation -> RovenfallMobRuntime.allows(
                            mutation.spawn(), level.dimension(), mob.blockPosition().getY(),
                            operationLocked, protectedRegion))
                    .toList();
            applyMutations(mob, retained, true);
            return;
        }
        if (loadedFromDisk) {
            cleanup(mob);
            return;
        }
        applyMutations(mob, selectFresh(
                snapshot, entityType, mob.getUUID(), level.dimension(), mob.blockPosition().getY(),
                operationLocked, protectedRegion), false);
    }

    private static void onDatapackSync(OnDatapackSyncEvent event) {
        if (event.getPlayer() != null) {
            return;
        }
        var server = event.getPlayerList().getServer();
        MobContentSnapshot snapshot = MobContentReloadListener.snapshot(server);
        for (ServerLevel level : server.getAllLevels()) {
            for (var entity : level.getAllEntities()) {
                if (entity instanceof Mob mob) {
                    refresh(mob, level, snapshot, true);
                }
            }
        }
    }

    private static void onDeath(LivingDeathEvent event) {
        if (event.isCanceled() || !(event.getEntity() instanceof Mob mob)
                || !(mob.level() instanceof ServerLevel level)) {
            return;
        }
        List<MobContentCatalog.MutationDefinition> mutations = activeMutations(mob, level);
        if (mutations.isEmpty()) {
            return;
        }
        if (mutations.stream().anyMatch(mutation -> mutation.behaviorModifiers().contains(DEATH_BURST))
                && !mob.getPersistentData().getBooleanOr(DEATH_BURST_FIRED, false)) {
            mob.getPersistentData().putBoolean(DEATH_BURST_FIRED, true);
            level.explode(mob, mob.getX(), mob.getY(), mob.getZ(), DEATH_BURST_RADIUS,
                    Level.ExplosionInteraction.NONE);
        }
        ServerPlayer player = responsiblePlayer(event.getSource().getEntity(), mob);
        if (player == null) {
            return;
        }
        MobContentSnapshot snapshot = MobContentReloadListener.snapshot(level.getServer());
        long additional = additionalCurrency(snapshot, EntityType.getKey(mob.getType()), mutations).orElse(0);
        if (additional <= 0) {
            return;
        }
        UUID transactionId = rewardTransactionId(mob.getUUID(), player.getUUID(), mutationIds(mob));
        var result = EconomyService.award(
                PlatformSavedData.get(level.getServer()), player.getUUID(), additional,
                "mutation_reward:" + transactionId, System.currentTimeMillis(), transactionId,
                EconomyConfig.initialBalance(), EconomyConfig.maximumBalance());
        if (result.status() != EconomyService.TransactionStatus.SUCCESS
                && result.status() != EconomyService.TransactionStatus.DUPLICATE_TRANSACTION) {
            LOGGER.warn("Could not award mutation reward mob={} player={} status={}",
                    mob.getUUID(), player.getUUID(), result.status());
        }
    }

    private static void onDrops(LivingDropsEvent event) {
        if (event.isCanceled() || !(event.getEntity() instanceof Mob mob)
                || !(mob.level() instanceof ServerLevel level)
                || responsiblePlayer(event.getSource().getEntity(), mob) == null) {
            return;
        }
        MobContentSnapshot snapshot = MobContentReloadListener.snapshot(level.getServer());
        for (var mutation : activeMutations(mob, level)) {
            mutation.bonusLoot().flatMap(snapshot::loot).ifPresent(reward -> {
                for (int roll = 0; roll < reward.rolls(); roll++) {
                    mob.dropFromLootTable(level, event.getSource(), event.isRecentlyHit(), reward.lootTable(),
                            stack -> event.getDrops().add(new ItemEntity(
                                    level, mob.getX(), mob.getY(), mob.getZ(), stack)));
                }
            });
        }
    }

    private static void onExperienceDrop(LivingExperienceDropEvent event) {
        if (event.isCanceled() || !(event.getEntity() instanceof Mob mob)
                || !(mob.level() instanceof ServerLevel level)
                || !RovenfallMobRuntime.isEligibleRewardPlayer(event.getAttackingPlayer())) {
            return;
        }
        MobContentSnapshot snapshot = MobContentReloadListener.snapshot(level.getServer());
        List<MobContentCatalog.MutationDefinition> mutations = activeMutations(mob, level);
        OptionalLong scaled = scaleReward(
                event.getOriginalExperience(),
                mutations.stream().map(MobContentCatalog.MutationDefinition::rewardMultiplierPercent).toList(),
                mutations.stream().map(mutation -> mutation.bonusLoot()
                                .flatMap(snapshot::loot).map(MobContentCatalog.LootDefinition::experience).orElse(0L))
                        .toList());
        scaled.ifPresent(value -> event.setDroppedExperience((int) Math.min(Integer.MAX_VALUE, value)));
    }

    private static List<MobContentCatalog.MutationDefinition> activeMutations(Mob mob, ServerLevel level) {
        PlatformSavedData state = PlatformSavedData.get(level.getServer());
        if (state.isWildernessOperationLocked()
                || state.isProtectedRegion(ClaimKey.at(level.dimension(), mob.blockPosition()))) {
            return List.of();
        }
        Identifier entityType = EntityType.getKey(mob.getType());
        MobContentSnapshot snapshot = MobContentReloadListener.snapshot(level.getServer());
        return mutationIds(mob).stream()
                .map(snapshot::mutation)
                .flatMap(java.util.Optional::stream)
                .filter(mutation -> mutation.eligibleEntityTypes().contains(entityType))
                .filter(mutation -> RovenfallMobRuntime.allows(
                        mutation.spawn(), level.dimension(), mob.blockPosition().getY(), false, false))
                .toList();
    }

    private static OptionalLong additionalCurrency(
            MobContentSnapshot snapshot,
            Identifier entityType,
            List<MobContentCatalog.MutationDefinition> mutations) {
        long base = snapshot.mobs().values().stream()
                .filter(mob -> mob.entityType().equals(entityType))
                .findFirst()
                .flatMap(mob -> snapshot.loot(mob.loot()))
                .map(MobContentCatalog.LootDefinition::currency)
                .orElse(0L);
        OptionalLong scaled = scaleReward(
                base,
                mutations.stream().map(MobContentCatalog.MutationDefinition::rewardMultiplierPercent).toList(),
                mutations.stream().map(mutation -> mutation.bonusLoot()
                                .flatMap(snapshot::loot).map(MobContentCatalog.LootDefinition::currency).orElse(0L))
                        .toList());
        if (scaled.isEmpty() || scaled.getAsLong() < base) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(scaled.getAsLong() - base);
    }

    private static ServerPlayer responsiblePlayer(net.minecraft.world.entity.Entity directAttacker, Mob mob) {
        if (directAttacker instanceof ServerPlayer player
                && RovenfallMobRuntime.isEligibleRewardPlayer(player)) {
            return player;
        }
        Player lastAttacker = mob.getLastHurtByPlayer();
        return RovenfallMobRuntime.isEligibleRewardPlayer(lastAttacker) ? (ServerPlayer) lastAttacker : null;
    }

    private static UUID rewardTransactionId(UUID entityId, UUID playerId, List<Identifier> mutations) {
        String seed = "rovenfall:mutation_reward:" + entityId + ":" + playerId + ":" + mutations;
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8));
    }

    private static void updateManagedName(
            Mob mob, List<MobContentCatalog.MutationDefinition> mutations) {
        var data = mob.getPersistentData();
        Component current = mob.getCustomName();
        boolean managed = data.getBooleanOr(MANAGED_NAME, false);
        String signature = data.getStringOr(MANAGED_NAME_SIGNATURE, "");
        if (current != null && (!managed || !current.toString().equals(signature))) {
            data.putBoolean(MANAGED_NAME, false);
            data.remove(MANAGED_NAME_SIGNATURE);
            return;
        }
        var name = Component.empty();
        for (var mutation : mutations) {
            name = name.append(Component.literal("[")).append(Component.translatable(mutation.markerTranslationKey()))
                    .append(Component.literal("] "));
        }
        name = name.append(Component.translatable(mob.getType().getDescriptionId()));
        mob.setCustomName(name);
        mob.setCustomNameVisible(true);
        data.putBoolean(MANAGED_NAME, true);
        data.putString(MANAGED_NAME_SIGNATURE, name.toString());
    }

    private static void removeStoredModifiers(Mob mob) {
        String encoded = mob.getPersistentData().getStringOr(MODIFIERS, "");
        if (encoded.isBlank()) {
            return;
        }
        for (String entry : encoded.split(",")) {
            String[] parts = entry.split(">", 2);
            if (parts.length != 2) {
                continue;
            }
            Identifier attributeId = Identifier.tryParse(parts[0]);
            Identifier modifierId = Identifier.tryParse(parts[1]);
            if (attributeId == null || modifierId == null) {
                continue;
            }
            Attribute attribute = BuiltInRegistries.ATTRIBUTE.getValue(attributeId);
            if (attribute == null) {
                continue;
            }
            AttributeInstance instance = mob.getAttribute(BuiltInRegistries.ATTRIBUTE.wrapAsHolder(attribute));
            if (instance != null) {
                instance.removeModifier(modifierId);
            }
        }
        mob.getPersistentData().remove(MODIFIERS);
    }

    private static Identifier modifierId(
            Identifier mutationId, MobContentCatalog.AttributeModifier modifier) {
        String path = "mutation/" + mutationId.getNamespace() + "/" + mutationId.getPath()
                + "/" + modifier.attribute().getNamespace() + "/" + modifier.attribute().getPath()
                + "/" + modifier.operation().name().toLowerCase(java.util.Locale.ROOT);
        return id(path);
    }

    private static net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation operation(
            MobContentCatalog.Operation operation) {
        return switch (operation) {
            case ADD -> net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_VALUE;
            case MULTIPLY_BASE ->
                    net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_MULTIPLIED_BASE;
            case MULTIPLY_TOTAL ->
                    net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL;
        };
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(Rovenfall.MOD_ID, path);
    }

    private record ResolvedModifier(
            Identifier attributeId,
            AttributeInstance instance,
            net.minecraft.world.entity.ai.attributes.AttributeModifier modifier) {
    }
}
