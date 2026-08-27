package org.dldyou.rovenfall.mobs;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.dldyou.rovenfall.Rovenfall;
import org.dldyou.rovenfall.administration.AdministrationService;
import org.dldyou.rovenfall.administration.BossRewardService;
import org.dldyou.rovenfall.administration.PlatformSavedData;
import org.dldyou.rovenfall.administration.ProtectedRegionService;
import org.dldyou.rovenfall.claims.ClaimKey;
import org.dldyou.rovenfall.world.ProtectedRegion;
import org.dldyou.rovenfall.world.WorldTopology;

public final class BossEncounterRuntime {
    private static final String BOSS_ENCOUNTER = "rovenfall:boss_encounter";
    private static final String MINION_ENCOUNTER = "rovenfall:boss_minion_encounter";
    private static final String MINION_SEQUENCE = "rovenfall:boss_minion_sequence";
    private static final String ARENA_PREFIX = "boss_arena/";
    private static final Identifier MELEE_SWEEP = id("melee_sweep");
    private static final Identifier PROJECTILE_BARRAGE = id("projectile_barrage");
    private static final Identifier SHOCKWAVE = id("shockwave");
    private static final Identifier SUMMON_MINIONS = id("summon_minions");
    private static final Identifier PROTECTED_REGION_CREATE = id("protected_region_create");
    private static final long MILLIS_PER_TICK = 50L;
    private static final UUID ZERO_UUID = new UUID(0L, 0L);

    private BossEncounterRuntime() {
    }

    public static void register(IEventBus eventBus) {
        eventBus.addListener(EventPriority.LOWEST, BossEncounterRuntime::onEntityJoin);
        eventBus.addListener(BossEncounterRuntime::onDamage);
        eventBus.addListener(EventPriority.LOWEST, BossEncounterRuntime::onDeath);
        eventBus.addListener(BossEncounterRuntime::onServerTick);
        eventBus.addListener(BossEncounterRuntime::onServerStarted);
        eventBus.addListener(BossEncounterRuntime::onDatapackSync);
    }

    public static StartResult start(
            MinecraftServer server, Identifier bossId, long timestampEpochMillis, UUID encounterId) {
        if (server == null || bossId == null || timestampEpochMillis < 0
                || encounterId == null || ZERO_UUID.equals(encounterId)) {
            return new StartResult(StartStatus.INVALID_REQUEST, encounterId, null);
        }
        BossEncounterSavedData encounters = BossEncounterSavedData.get(server);
        PlatformSavedData platform = PlatformSavedData.get(server);
        if (!encounters.isWritable() || !platform.isWritable()) {
            return new StartResult(StartStatus.READ_ONLY_STATE, encounterId, null);
        }
        if (encounters.encounter(encounterId).isPresent()) {
            return new StartResult(StartStatus.DUPLICATE_TRANSACTION, encounterId, null);
        }
        if (encounters.activeEncounters().stream().anyMatch(active -> active.bossId().equals(bossId))) {
            return new StartResult(StartStatus.ALREADY_ACTIVE, encounterId, null);
        }
        if (platform.isWildernessOperationLocked()) {
            return new StartResult(StartStatus.WILDERNESS_LOCKED, encounterId, null);
        }

        MobContentSnapshot snapshot = MobContentReloadListener.snapshot(server);
        MobContentCatalog.BossDefinition boss = snapshot.boss(bossId).orElse(null);
        MobContentCatalog.ArenaPolicy arena = boss == null ? null : snapshot.arena(boss.arena()).orElse(null);
        MobContentCatalog.MobDefinition mobDefinition = boss == null ? null : snapshot.mob(boss.mob()).orElse(null);
        MobContentCatalog.ContributionRule contribution = boss == null
                ? null : snapshot.contributionRule(boss.contributionRule()).orElse(null);
        MobContentCatalog.LootDefinition loot = boss == null ? null : snapshot.loot(boss.loot()).orElse(null);
        if (boss == null || arena == null || mobDefinition == null || contribution == null || loot == null) {
            return new StartResult(StartStatus.DEFINITION_MISSING, encounterId, null);
        }
        if (!WorldTopology.isWilderness(arena.dimension())) {
            return new StartResult(StartStatus.INVALID_ARENA, encounterId, null);
        }
        ServerLevel level = server.getLevel(arena.dimension());
        if (level == null) {
            return new StartResult(StartStatus.TOPOLOGY_UNAVAILABLE, encounterId, null);
        }
        if (!level.isInWorldBounds(arena.center()) || !level.getWorldBorder().isWithinBounds(arena.center())) {
            return new StartResult(StartStatus.INVALID_ARENA, encounterId, null);
        }
        ProtectedRegion region = regionFor(arena);
        if (!region.isValid() || areaOccupied(platform, region)) {
            return new StartResult(StartStatus.AREA_OCCUPIED, encounterId, null);
        }

        Identifier regionId = regionId(encounterId);
        var regionResult = ProtectedRegionService.create(
                platform, AdministrationService.SYSTEM_ACTOR, true, regionId, region,
                "boss encounter reservation", timestampEpochMillis, encounterId);
        if (regionResult.status() == ProtectedRegionService.Status.DUPLICATE_TRANSACTION) {
            return new StartResult(StartStatus.DUPLICATE_TRANSACTION, encounterId, null);
        }
        if (regionResult.status() != ProtectedRegionService.Status.SUCCESS) {
            return new StartResult(StartStatus.REGION_RESERVATION_FAILED, encounterId, null);
        }

        Entity entity = BuiltInRegistries.ENTITY_TYPE.getValue(mobDefinition.entityType()) == null
                ? null
                : BuiltInRegistries.ENTITY_TYPE.getValue(mobDefinition.entityType())
                        .create(level, EntitySpawnReason.COMMAND);
        if (!(entity instanceof Mob mob)) {
            rollbackReservation(platform, regionId, encounterId, timestampEpochMillis);
            return new StartResult(StartStatus.SPAWN_FAILED, encounterId, null);
        }

        RovenfallMobRuntime.applyDefinition(mob, mobDefinition, false);
        mob.getPersistentData().putString(BOSS_ENCOUNTER, encounterId.toString());
        mob.setCustomName(Component.translatable(boss.translationKey()));
        mob.setCustomNameVisible(true);
        mob.setPersistenceRequired();
        mob.snapTo(arena.center().getX() + 0.5D, arena.center().getY(),
                arena.center().getZ() + 0.5D, 0, 0);

        UUID fingerprint = definitionFingerprint(boss, arena, mobDefinition, contribution, loot);
        BossEncounterState encounter = BossEncounterState.start(
                encounterId, boss.id(), fingerprint, mob.getUUID(), arena.dimension(), arena.center(),
                region, timestampEpochMillis, level.getGameTime());
        if (!encounters.put(encounter)) {
            rollbackReservation(platform, regionId, encounterId, timestampEpochMillis);
            return new StartResult(StartStatus.STATE_FULL, encounterId, null);
        }
        if (!level.addFreshEntity(mob)) {
            encounters.remove(encounterId);
            rollbackReservation(platform, regionId, encounterId, timestampEpochMillis);
            return new StartResult(StartStatus.SPAWN_FAILED, encounterId, null);
        }
        announce(level, encounter, arena.leashRadius(),
                Component.translatable("message.rovenfall.boss.started", Component.translatable(boss.translationKey())));
        return new StartResult(StartStatus.SUCCESS, encounterId, mob.getUUID());
    }

    public static boolean reset(MinecraftServer server, UUID encounterId, long timestampEpochMillis) {
        if (server == null || encounterId == null || timestampEpochMillis < 0) {
            return false;
        }
        finish(server, encounterId, "manual_reset", timestampEpochMillis, true);
        return BossEncounterSavedData.get(server).encounter(encounterId).isEmpty()
                && PlatformSavedData.get(server).protectedRegion(regionId(encounterId)).isEmpty();
    }

    public static void recover(MinecraftServer server, long timestampEpochMillis) {
        if (server != null && timestampEpochMillis >= 0) {
            reconcile(server, timestampEpochMillis);
        }
    }

    public static boolean allowsArenaCombat(Entity target, ServerPlayer player) {
        if (target == null || !RovenfallMobRuntime.isEligibleRewardPlayer(player)
                || !(target.level() instanceof ServerLevel level) || player.level() != level) {
            return false;
        }
        UUID encounterId = encounterId(target).orElse(null);
        if (encounterId == null) {
            return false;
        }
        BossEncounterState encounter = BossEncounterSavedData.get(level.getServer())
                .encounter(encounterId).orElse(null);
        if (encounter == null || !encounter.dimension().equals(level.dimension())
                || !isEncounterEntity(target, encounter)) {
            return false;
        }
        ResolvedPlan plan = resolvedPlan(level.getServer(), encounter).orElse(null);
        if (plan == null) {
            return false;
        }
        ProtectedRegion expected = regionFor(plan.arena());
        ProtectedRegion retained = PlatformSavedData.get(level.getServer())
                .protectedRegion(regionId(encounter.encounterId())).orElse(null);
        double maximumDistance = square(plan.arena().leashRadius());
        Vec3 center = Vec3.atCenterOf(encounter.center());
        return expected.equals(retained)
                && isOwnedArenaRegion(level.getServer(), regionId(encounter.encounterId()), retained)
                && player.distanceToSqr(center) <= maximumDistance
                && target.distanceToSqr(center) <= maximumDistance;
    }

    /** True only for an exact, retained boss or minion belonging to a managed encounter. */
    public static boolean isManagedEncounterEntity(Entity entity) {
        if (entity == null || !(entity.level() instanceof ServerLevel level)) {
            return false;
        }
        UUID retainedId = encounterId(entity).orElse(null);
        BossEncounterState encounter = retainedId == null ? null : BossEncounterSavedData.get(level.getServer())
                .encounter(retainedId).orElse(null);
        return encounter != null
                && encounter.dimension().equals(level.dimension())
                && isEncounterEntity(entity, encounter);
    }

    public static ProtectedRegion regionFor(MobContentCatalog.ArenaPolicy arena) {
        int minimumChunkX = Math.toIntExact(Math.floorDiv(
                (long) arena.center().getX() - arena.protectionRadius(), 16L));
        int minimumChunkZ = Math.toIntExact(Math.floorDiv(
                (long) arena.center().getZ() - arena.protectionRadius(), 16L));
        int maximumChunkX = Math.toIntExact(Math.floorDiv(
                (long) arena.center().getX() + arena.protectionRadius(), 16L));
        int maximumChunkZ = Math.toIntExact(Math.floorDiv(
                (long) arena.center().getZ() + arena.protectionRadius(), 16L));
        return new ProtectedRegion(
                AdministrationService.SYSTEM_ACTOR, arena.dimension(), minimumChunkX, minimumChunkZ,
                maximumChunkX, maximumChunkZ);
    }

    public static Identifier regionId(UUID encounterId) {
        return Identifier.fromNamespaceAndPath(Rovenfall.MOD_ID, ARENA_PREFIX + encounterId);
    }

    public static UUID definitionFingerprint(
            MobContentCatalog.BossDefinition boss,
            MobContentCatalog.ArenaPolicy arena,
            MobContentCatalog.MobDefinition mob,
            MobContentCatalog.ContributionRule contribution,
            MobContentCatalog.LootDefinition loot) {
        String canonical = boss + "|" + arena + "|" + mob + "|" + contribution + "|" + loot;
        return UUID.nameUUIDFromBytes(canonical.getBytes(StandardCharsets.UTF_8));
    }

    public static int phaseIndex(MobContentCatalog.BossDefinition boss, float health, float maximumHealth) {
        if (boss == null || maximumHealth <= 0 || !Float.isFinite(health) || !Float.isFinite(maximumHealth)) {
            return 0;
        }
        double percentage = Math.max(0, Math.min(100, health * 100.0D / maximumHealth));
        int result = 0;
        for (int index = 1; index < boss.phases().size(); index++) {
            if (percentage <= boss.phases().get(index).startHealthPercent()) {
                result = index;
            }
        }
        return result;
    }

    public static MobContentCatalog.PatternDefinition selectPattern(
            BossEncounterState encounter, MobContentCatalog.Phase phase) {
        List<MobContentCatalog.PatternDefinition> patterns = phase.patterns().stream()
                .sorted(Comparator.comparing(MobContentCatalog.PatternDefinition::id))
                .toList();
        long total = patterns.stream().mapToLong(MobContentCatalog.PatternDefinition::weight).sum();
        long mixed = encounter.encounterId().getMostSignificantBits()
                ^ Long.rotateLeft(encounter.encounterId().getLeastSignificantBits(), 23)
                ^ ((long) encounter.sequence() * 0x9E3779B97F4A7C15L)
                ^ phase.id().toString().hashCode();
        long selected = Math.floorMod(mixed, total);
        for (var pattern : patterns) {
            if (selected < pattern.weight()) {
                return pattern;
            }
            selected -= pattern.weight();
        }
        return patterns.getLast();
    }

    public static boolean isTimedOut(
            BossEncounterState encounter, MobContentCatalog.ArenaPolicy arena, long timestampEpochMillis) {
        if (timestampEpochMillis < encounter.lastParticipantAtEpochMillis()) {
            return false;
        }
        long timeoutMillis;
        try {
            timeoutMillis = Math.multiplyExact(arena.resetTimeoutTicks(), MILLIS_PER_TICK);
        } catch (ArithmeticException exception) {
            return true;
        }
        return timestampEpochMillis - encounter.lastParticipantAtEpochMillis() >= timeoutMillis;
    }

    private static void onEntityJoin(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        UUID tagged = encounterId(event.getEntity()).orElse(null);
        if (tagged == null) {
            return;
        }
        BossEncounterState encounter = BossEncounterSavedData.get(level.getServer()).encounter(tagged).orElse(null);
        if (encounter == null || !encounter.dimension().equals(level.dimension())
                || event.getEntity().getPersistentData().contains(BOSS_ENCOUNTER)
                && !encounter.entityId().equals(event.getEntity().getUUID())) {
            event.setCanceled(true);
            event.getEntity().discard();
            return;
        }
        if (event.getEntity() instanceof Mob mob && encounter.entityId().equals(mob.getUUID())) {
            resolvedPlan(level.getServer(), encounter).ifPresentOrElse(plan -> {
                if (plan.mob().entityType().equals(BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()))) {
                    RovenfallMobRuntime.applyDefinition(mob, plan.mob(), true);
                    mob.setPersistenceRequired();
                } else {
                    event.setCanceled(true);
                    mob.discard();
                }
            }, () -> {
                event.setCanceled(true);
                mob.discard();
            });
        }
    }

    private static void onDamage(LivingDamageEvent.Post event) {
        if (event.getHealthDamage() <= 0 || !(event.getEntity() instanceof Mob mob)
                || !(mob.level() instanceof ServerLevel level)) {
            return;
        }
        UUID encounterId = encounterId(mob, BOSS_ENCOUNTER).orElse(null);
        if (encounterId == null) {
            return;
        }
        ServerPlayer player = event.getSource().getEntity() instanceof ServerPlayer value
                && RovenfallMobRuntime.isEligibleRewardPlayer(value) ? value : null;
        if (player == null) {
            return;
        }
        BossEncounterSavedData data = BossEncounterSavedData.get(level.getServer());
        BossEncounterState encounter = data.encounter(encounterId).orElse(null);
        ResolvedPlan plan = encounter == null ? null : resolvedPlan(level.getServer(), encounter).orElse(null);
        if (plan == null || !allowsArenaCombat(mob, player)) {
            return;
        }
        long points = Math.max(1L, (long) Math.ceil(event.getHealthDamage()));
        data.put(encounter.contribute(
                player.getUUID(), points, plan.contribution().maximumContributors(), System.currentTimeMillis()));
    }

    private static void onDeath(LivingDeathEvent event) {
        if (event.isCanceled() || !(event.getEntity().level() instanceof ServerLevel level)) {
            return;
        }
        UUID encounterId = encounterId(event.getEntity(), BOSS_ENCOUNTER).orElse(null);
        BossEncounterState encounter = encounterId == null ? null : BossEncounterSavedData.get(level.getServer())
                .encounter(encounterId).orElse(null);
        ResolvedPlan plan = encounter == null ? null : resolvedPlan(level.getServer(), encounter).orElse(null);
        if (encounter != null
                && encounter.entityId().equals(event.getEntity().getUUID())
                && encounter.dimension().equals(level.dimension())
                && plan != null
                && plan.mob().entityType().equals(BuiltInRegistries.ENTITY_TYPE.getKey(event.getEntity().getType()))) {
            long timestamp = System.currentTimeMillis();
            if (!validRewardArena(level.getServer(), encounter, plan)) {
                BossRewardService.auditEncounterFailure(
                        level.getServer(), encounter, "arena_evidence_invalid", timestamp);
                finish(level.getServer(), encounterId, "reward_evidence_invalid", timestamp, false);
                return;
            }
            BossEncounterState pending = encounter.markRewardPending(new BossEncounterState.RewardPlan(
                    plan.boss(), plan.arena(), plan.mob(), plan.contribution(), plan.loot()));
            if (pending.stage() != BossEncounterState.Stage.REWARD_PENDING) {
                BossRewardService.auditEncounterFailure(
                        level.getServer(), encounter, "reward_plan_invalid", timestamp);
                finish(level.getServer(), encounterId, "reward_plan_invalid", timestamp, false);
                return;
            }
            if (pending != encounter && !BossEncounterSavedData.get(level.getServer()).put(pending)) {
                return;
            }
            var rewards = BossRewardService.prepare(
                    level.getServer(), pending, plan.boss(), plan.mob(), plan.contribution(), plan.loot(), timestamp);
            if (rewards.status().durable()) {
                finish(level.getServer(), encounterId, "completed", timestamp, false);
            }
        }
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        long timestamp = System.currentTimeMillis();
        for (BossEncounterState encounter : BossEncounterSavedData.get(server).activeEncounters()) {
            tick(server, encounter, timestamp);
        }
        if (server.overworld().getGameTime() % 20L == 0L) {
            BossRewardService.recover(server, timestamp);
        }
    }

    private static void tick(MinecraftServer server, BossEncounterState persisted, long timestamp) {
        ResolvedPlan plan = resolvedPlan(server, persisted).orElse(null);
        if (persisted.stage() == BossEncounterState.Stage.REWARD_PENDING) {
            retryPendingReward(server, persisted, plan, timestamp);
            return;
        }
        PlatformSavedData platform = PlatformSavedData.get(server);
        Identifier regionId = regionId(persisted.encounterId());
        ProtectedRegion retainedRegion = platform.protectedRegion(regionId).orElse(null);
        if (plan == null || platform.isWildernessOperationLocked()
                || !regionFor(plan.arena()).equals(retainedRegion)
                || !isOwnedArenaRegion(server, regionId, retainedRegion)) {
            finish(server, persisted.encounterId(), "invalid_or_locked", timestamp, true);
            return;
        }
        ServerLevel level = server.getLevel(persisted.dimension());
        if (level == null) {
            finish(server, persisted.encounterId(), "missing_dimension", timestamp, true);
            return;
        }
        Entity retained = level.getEntity(persisted.entityId());
        if (retained instanceof Mob deadBoss && !deadBoss.isAlive()) {
            var rewards = BossRewardService.prepare(
                    server, persisted, plan.boss(), plan.mob(), plan.contribution(), plan.loot(), timestamp);
            if (rewards.status().durable()) {
                finish(server, persisted.encounterId(), "completed_recovery", timestamp, false);
            }
            return;
        }
        if (!(retained instanceof Mob boss)) {
            if (isTimedOut(persisted, plan.arena(), timestamp)) {
                finish(server, persisted.encounterId(), "missing_boss_timeout", timestamp, true);
            }
            return;
        }
        if (boss.distanceToSqr(Vec3.atCenterOf(persisted.center())) > square(plan.arena().leashRadius())) {
            boss.teleportTo(
                    persisted.center().getX() + 0.5D, persisted.center().getY(), persisted.center().getZ() + 0.5D);
        }

        List<ServerPlayer> players = participants(level, persisted, plan.arena().leashRadius());
        BossEncounterState current = persisted;
        if (!players.isEmpty() && timestamp - current.lastParticipantAtEpochMillis() >= 1_000L) {
            current = current.touch(timestamp);
        }
        if (players.isEmpty() && isTimedOut(current, plan.arena(), timestamp)) {
            finish(server, current.encounterId(), "participant_timeout", timestamp, true);
            return;
        }

        int phase = phaseIndex(plan.boss(), boss.getHealth(), boss.getMaxHealth());
        long gameTime = level.getGameTime();
        if (phase > current.phaseIndex()) {
            current = current.enterPhase(phase, gameTime);
            announce(level, current, plan.arena().leashRadius(), Component.translatable(
                    "message.rovenfall.boss.phase",
                    Component.translatable(plan.boss().phases().get(phase).translationKey())));
        }
        MobContentCatalog.Phase phaseDefinition = plan.boss().phases().get(current.phaseIndex());
        if (current.stage() == BossEncounterState.Stage.IDLE && gameTime >= current.nextPatternGameTime()) {
            var selected = selectPattern(current, phaseDefinition);
            current = current.beginTelegraph(selected.id(), gameTime + selected.telegraphTicks());
            telegraph(level, current, plan.arena().leashRadius(), selected);
        } else if (current.stage() == BossEncounterState.Stage.TELEGRAPH
                && gameTime >= current.stageDeadlineGameTime()) {
            var pattern = pattern(phaseDefinition, current.patternId().orElse(null));
            if (pattern == null) {
                finish(server, current.encounterId(), "missing_pattern", timestamp, true);
                return;
            }
            current = current.beginExecution(gameTime + pattern.durationTicks());
            if (!BossEncounterSavedData.get(server).put(current)) {
                finish(server, current.encounterId(), "state_transition_failed", timestamp, true);
                return;
            }
            execute(level, boss, current, plan.arena(), pattern, players);
        } else if (current.stage() == BossEncounterState.Stage.EXECUTING
                && gameTime >= current.stageDeadlineGameTime()) {
            var pattern = pattern(phaseDefinition, current.patternId().orElse(null));
            if (pattern == null) {
                finish(server, current.encounterId(), "missing_pattern", timestamp, true);
                return;
            }
            current = current.finishPattern(gameTime + pattern.cooldownTicks());
        }
        BossEncounterSavedData.get(server).put(current);
    }

    private static void execute(
            ServerLevel level,
            Mob boss,
            BossEncounterState encounter,
            MobContentCatalog.ArenaPolicy arena,
            MobContentCatalog.PatternDefinition pattern,
            List<ServerPlayer> participants) {
        if (pattern.type().equals(MELEE_SWEEP)) {
            damage(level, boss, participants, 6, 8.0F, false);
        } else if (pattern.type().equals(PROJECTILE_BARRAGE)) {
            damage(level, boss, participants, Math.min(32, arena.leashRadius()), 4.0F, false);
        } else if (pattern.type().equals(SHOCKWAVE)) {
            damage(level, boss, participants, 10, 6.0F, true);
        } else if (pattern.type().equals(SUMMON_MINIONS)) {
            int existing = 0;
            for (Entity entity : level.getAllEntities()) {
                if (encounterId(entity, MINION_ENCOUNTER).filter(encounter.encounterId()::equals).isPresent()
                        && entity.getPersistentData().getIntOr(MINION_SEQUENCE, -1) == encounter.sequence()) {
                    existing++;
                }
            }
            for (int index = existing; index < 3; index++) {
                var minion = EntityTypes.ZOMBIE.create(level, EntitySpawnReason.COMMAND);
                if (minion == null) {
                    continue;
                }
                double angle = index * Math.PI * 2.0D / 3.0D;
                minion.snapTo(boss.getX() + Math.cos(angle) * 3.0D, boss.getY(),
                        boss.getZ() + Math.sin(angle) * 3.0D, 0, 0);
                minion.getPersistentData().putString(MINION_ENCOUNTER, encounter.encounterId().toString());
                minion.getPersistentData().putInt(MINION_SEQUENCE, encounter.sequence());
                level.addFreshEntity(minion);
            }
        }
    }

    private static void damage(
            ServerLevel level,
            Mob boss,
            List<ServerPlayer> participants,
            double radius,
            float amount,
            boolean knockback) {
        for (ServerPlayer player : participants) {
            if (player.distanceToSqr(boss) > square(radius)) {
                continue;
            }
            player.hurtServer(level, level.damageSources().mobAttack(boss), amount);
            if (knockback) {
                Vec3 direction = player.position().subtract(boss.position());
                if (direction.horizontalDistanceSqr() > 0.001D) {
                    player.push(direction.x / 4.0D, 0.4D, direction.z / 4.0D);
                }
            }
        }
    }

    private static void telegraph(
            ServerLevel level,
            BossEncounterState encounter,
            int leashRadius,
            MobContentCatalog.PatternDefinition pattern) {
        announce(level, encounter, leashRadius, Component.translatable(
                "message.rovenfall.boss.telegraph", Component.translatable(pattern.translationKey())));
        level.sendParticles(
                ParticleTypes.FLAME,
                encounter.center().getX() + 0.5D,
                encounter.center().getY() + 1.0D,
                encounter.center().getZ() + 0.5D,
                40, 4.0D, 1.0D, 4.0D, 0.02D);
    }

    private static void announce(
            ServerLevel level, BossEncounterState encounter, int leashRadius, Component message) {
        for (ServerPlayer player : participants(level, encounter, leashRadius)) {
            player.sendSystemMessage(message);
        }
    }

    private static List<ServerPlayer> participants(
            ServerLevel level, BossEncounterState encounter, int leashRadius) {
        return level.players().stream()
                .filter(RovenfallMobRuntime::isEligibleRewardPlayer)
                .filter(player -> player.distanceToSqr(Vec3.atCenterOf(encounter.center())) <= square(leashRadius))
                .toList();
    }

    private static void finish(
            MinecraftServer server,
            UUID encounterId,
            String reason,
            long timestampEpochMillis,
            boolean discardBoss) {
        BossEncounterSavedData encounters = BossEncounterSavedData.get(server);
        PlatformSavedData platform = PlatformSavedData.get(server);
        if (!encounters.isWritable() || !platform.isWritable()) {
            return;
        }
        BossEncounterState encounter = encounters.encounter(encounterId).orElse(null);
        if (encounter == null) {
            cleanupOrphanRegion(server, regionId(encounterId), timestampEpochMillis);
            return;
        }
        ServerLevel level = server.getLevel(encounter.dimension());
        if (level != null) {
            Entity boss = level.getEntity(encounter.entityId());
            if (discardBoss && boss != null) {
                boss.discard();
            }
            cleanupMinions(level, encounter);
            resolvedPlan(server, encounter).ifPresent(plan -> announce(
                    level, encounter, plan.arena().leashRadius(), Component.translatable(
                            "message.rovenfall.boss.ended", reason)));
        }

        Identifier regionId = regionId(encounterId);
        ProtectedRegion retained = platform.protectedRegion(regionId).orElse(null);
        if (Optional.ofNullable(retained)
                .filter(region -> isOwnedArenaRegion(server, regionId, region))
                .isPresent()) {
            UUID cleanupTransaction = UUID.nameUUIDFromBytes((
                    "boss_arena_finish:" + encounterId + ":" + timestampEpochMillis)
                    .getBytes(StandardCharsets.UTF_8));
            ProtectedRegionService.delete(
                    platform, AdministrationService.SYSTEM_ACTOR, true, regionId,
                    "boss encounter " + reason, timestampEpochMillis, cleanupTransaction);
        }
        if (platform.protectedRegion(regionId).isEmpty()
                || retained != null && !encounter.reservation().equals(retained)) {
            encounters.remove(encounterId);
        }
    }

    private static void cleanupMinions(ServerLevel level, BossEncounterState encounter) {
        List<Mob> retained = new ArrayList<>();
        for (Entity entity : level.getAllEntities()) {
            if (entity instanceof Mob minion && encounterId(minion, MINION_ENCOUNTER)
                    .filter(encounter.encounterId()::equals).isPresent()) {
                retained.add(minion);
            }
        }
        retained.forEach(Entity::discard);
    }

    private static void onServerStarted(ServerStartedEvent event) {
        long timestamp = System.currentTimeMillis();
        reconcile(event.getServer(), timestamp);
        BossRewardService.recover(event.getServer(), timestamp);
    }

    private static void onDatapackSync(OnDatapackSyncEvent event) {
        if (event.getPlayer() == null) {
            reconcile(event.getPlayerList().getServer(), System.currentTimeMillis());
        }
    }

    private static void reconcile(MinecraftServer server, long timestampEpochMillis) {
        BossEncounterSavedData encounters = BossEncounterSavedData.get(server);
        PlatformSavedData platform = PlatformSavedData.get(server);
        if (!encounters.isWritable() || !platform.isWritable()) {
            return;
        }
        for (BossEncounterState encounter : encounters.activeEncounters()) {
            ResolvedPlan plan = resolvedPlan(server, encounter).orElse(null);
            if (encounter.stage() == BossEncounterState.Stage.REWARD_PENDING) {
                retryPendingReward(server, encounter, plan, timestampEpochMillis);
            } else if (plan == null
                    || platform.isWildernessOperationLocked()) {
                finish(server, encounter.encounterId(), "recovery_reset", timestampEpochMillis, true);
            }
        }
        java.util.Set<Identifier> retained = encounters.activeEncounters().stream()
                .map(BossEncounterState::encounterId)
                .map(BossEncounterRuntime::regionId)
                .collect(java.util.stream.Collectors.toSet());
        for (var region : platform.protectedRegions()) {
            if (isOwnedArenaRegion(server, region.getKey(), region.getValue())
                    && !retained.contains(region.getKey())) {
                cleanupOrphanRegion(server, region.getKey(), timestampEpochMillis);
            }
        }
    }

    private static void retryPendingReward(
            MinecraftServer server,
            BossEncounterState encounter,
            ResolvedPlan plan,
            long timestamp) {
        if (plan == null) {
            return;
        }
        PlatformSavedData platform = PlatformSavedData.get(server);
        if (!platform.isWritable()) {
            return;
        }
        if (!validRewardArena(server, encounter, plan)) {
            BossRewardService.auditEncounterFailure(server, encounter, "arena_evidence_invalid", timestamp);
            finish(server, encounter.encounterId(), "reward_evidence_invalid", timestamp, false);
            return;
        }
        var rewards = BossRewardService.prepare(
                server, encounter, plan.boss(), plan.mob(), plan.contribution(), plan.loot(), timestamp);
        if (rewards.status().durable()) {
            finish(server, encounter.encounterId(), "completed_recovery", timestamp, false);
        }
    }

    private static boolean validRewardArena(
            MinecraftServer server, BossEncounterState encounter, ResolvedPlan plan) {
        Identifier regionId = regionId(encounter.encounterId());
        ProtectedRegion retained = PlatformSavedData.get(server).protectedRegion(regionId).orElse(null);
        return encounter.reservation().equals(regionFor(plan.arena()))
                && encounter.reservation().equals(retained)
                && isOwnedArenaRegion(server, regionId, retained);
    }

    private static void cleanupOrphanRegion(
            MinecraftServer server, Identifier regionId, long timestampEpochMillis) {
        PlatformSavedData platform = PlatformSavedData.get(server);
        ProtectedRegion retained = platform.protectedRegion(regionId).orElse(null);
        if (retained == null || !isOwnedArenaRegion(server, regionId, retained)) {
            return;
        }
        ProtectedRegionService.delete(
                platform, AdministrationService.SYSTEM_ACTOR, true, regionId,
                "orphan boss arena recovery", timestampEpochMillis, UUID.randomUUID());
    }

    private static Optional<ResolvedPlan> resolvedPlan(
            MinecraftServer server, BossEncounterState encounter) {
        if (encounter.stage() == BossEncounterState.Stage.REWARD_PENDING) {
            return encounter.rewardPlan().map(plan -> new ResolvedPlan(
                    plan.boss(), plan.arena(), plan.mob(), plan.contribution(), plan.loot()));
        }
        MobContentSnapshot snapshot = MobContentReloadListener.snapshot(server);
        var boss = snapshot.boss(encounter.bossId()).orElse(null);
        var arena = boss == null ? null : snapshot.arena(boss.arena()).orElse(null);
        var mob = boss == null ? null : snapshot.mob(boss.mob()).orElse(null);
        var contribution = boss == null ? null : snapshot.contributionRule(boss.contributionRule()).orElse(null);
        var loot = boss == null ? null : snapshot.loot(boss.loot()).orElse(null);
        if (boss == null || arena == null || mob == null || contribution == null || loot == null
                || !arena.dimension().equals(encounter.dimension())
                || !arena.center().equals(encounter.center())
                || encounter.phaseIndex() >= boss.phases().size()
                || !definitionFingerprint(boss, arena, mob, contribution, loot)
                        .equals(encounter.definitionFingerprint())) {
            return Optional.empty();
        }
        return Optional.of(new ResolvedPlan(boss, arena, mob, contribution, loot));
    }

    private static MobContentCatalog.PatternDefinition pattern(
            MobContentCatalog.Phase phase, Identifier patternId) {
        return patternId == null ? null : phase.patterns().stream()
                .filter(pattern -> pattern.id().equals(patternId))
                .findFirst()
                .orElse(null);
    }

    private static boolean areaOccupied(PlatformSavedData state, ProtectedRegion region) {
        for (int chunkX = region.minChunkX(); chunkX <= region.maxChunkX(); chunkX++) {
            for (int chunkZ = region.minChunkZ(); chunkZ <= region.maxChunkZ(); chunkZ++) {
                ClaimKey key = new ClaimKey(region.dimension(), chunkX, chunkZ);
                if (state.claim(key).isPresent() || state.isProtectedRegion(key)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void rollbackReservation(
            PlatformSavedData platform,
            Identifier regionId,
            UUID encounterId,
            long timestampEpochMillis) {
        UUID rollback = UUID.nameUUIDFromBytes(("boss_arena_rollback:" + encounterId)
                .getBytes(StandardCharsets.UTF_8));
        ProtectedRegionService.delete(
                platform, AdministrationService.SYSTEM_ACTOR, true, regionId,
                "boss encounter spawn rollback", timestampEpochMillis, rollback);
    }

    private static Optional<UUID> encounterId(Entity entity) {
        Optional<UUID> boss = encounterId(entity, BOSS_ENCOUNTER);
        return boss.isPresent() ? boss : encounterId(entity, MINION_ENCOUNTER);
    }

    private static Optional<UUID> encounterId(Entity entity, String key) {
        String encoded = entity.getPersistentData().getStringOr(key, "");
        if (encoded.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(encoded));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private static boolean isEncounterEntity(Entity entity, BossEncounterState encounter) {
        return (encounterId(entity, BOSS_ENCOUNTER)
                        .filter(encounter.encounterId()::equals)
                        .isPresent()
                && encounter.entityId().equals(entity.getUUID()))
                || (encounterId(entity, MINION_ENCOUNTER)
                        .filter(encounter.encounterId()::equals)
                        .isPresent());
    }

    /** Exact ownership predicate shared by arena cleanup and administrator recovery evidence. */
    public static boolean isOwnedArenaRegion(
            MinecraftServer server, Identifier regionId, ProtectedRegion region) {
        UUID encounterId = arenaEncounterId(regionId).orElse(null);
        if (encounterId == null
                || region == null
                || !AdministrationService.SYSTEM_ACTOR.equals(region.administratorId())) {
            return false;
        }
        if (BossEncounterSavedData.get(server).encounter(encounterId)
                .filter(encounter -> encounter.reservation().equals(region))
                .isPresent()) {
            return true;
        }
        return PlatformSavedData.get(server).auditTransaction(encounterId)
                .filter(entry -> AdministrationService.SYSTEM_ACTOR.equals(entry.actorId()))
                .filter(entry -> PROTECTED_REGION_CREATE.equals(entry.actionType()))
                .filter(entry -> regionId.toString().equals(entry.target()))
                .filter(entry -> region.auditSummary().equals(entry.afterValue()))
                .isPresent();
    }

    private static Optional<UUID> arenaEncounterId(Identifier regionId) {
        if (regionId == null || !regionId.getNamespace().equals(Rovenfall.MOD_ID)
                || !regionId.getPath().startsWith(ARENA_PREFIX)) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(regionId.getPath().substring(ARENA_PREFIX.length())));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private static double square(double value) {
        return value * value;
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(Rovenfall.MOD_ID, path);
    }

    public enum StartStatus {
        SUCCESS,
        INVALID_REQUEST,
        READ_ONLY_STATE,
        DUPLICATE_TRANSACTION,
        ALREADY_ACTIVE,
        WILDERNESS_LOCKED,
        DEFINITION_MISSING,
        INVALID_ARENA,
        TOPOLOGY_UNAVAILABLE,
        AREA_OCCUPIED,
        REGION_RESERVATION_FAILED,
        STATE_FULL,
        SPAWN_FAILED
    }

    public record StartResult(StartStatus status, UUID encounterId, UUID entityId) {
    }

    private record ResolvedPlan(
            MobContentCatalog.BossDefinition boss,
            MobContentCatalog.ArenaPolicy arena,
            MobContentCatalog.MobDefinition mob,
            MobContentCatalog.ContributionRule contribution,
            MobContentCatalog.LootDefinition loot) {
    }
}
