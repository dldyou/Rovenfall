package org.dldyou.rovenfall.administration;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import org.dldyou.rovenfall.Rovenfall;
import org.dldyou.rovenfall.mobs.BossEncounterState;
import org.dldyou.rovenfall.mobs.BossRewardOperation;
import org.dldyou.rovenfall.mobs.BossRewardSavedData;
import org.dldyou.rovenfall.mobs.MobContentCatalog;
import org.dldyou.rovenfall.rpg.ActivityXpAwardService;
import org.dldyou.rovenfall.rpg.RpgDefinitionReloadListener;
import org.dldyou.rovenfall.rpg.RpgPlayerSavedData;
import org.dldyou.rovenfall.rpg.RpgPlayerState;

/** Durable coordinator for contribution-qualified, personal boss rewards. */
public final class BossRewardService {
    private static final Identifier HUNTING = id("hunting");
    private static final Identifier REWARD_DENIED = id("boss_reward_denied");
    private static final Identifier REWARD_COMPLETED = id("boss_reward_completed");
    private static final Identifier REWARD_FAILED = id("boss_reward_failed");
    private static final long MILLIS_PER_TICK = 50L;

    private BossRewardService() {
    }

    public static PreparationResult prepare(
            MinecraftServer server,
            BossEncounterState encounter,
            MobContentCatalog.BossDefinition boss,
            MobContentCatalog.MobDefinition mob,
            MobContentCatalog.ContributionRule contribution,
            MobContentCatalog.LootDefinition loot,
            long timestamp) {
        if (server == null || encounter == null || boss == null || mob == null
                || contribution == null || loot == null || timestamp < 0
                || !encounter.bossId().equals(boss.id())) {
            return new PreparationResult(PreparationStatus.INVALID, 0);
        }
        BossRewardSavedData rewards = BossRewardSavedData.get(server);
        PlatformSavedData platform = PlatformSavedData.get(server);
        RpgPlayerSavedData rpg = RpgPlayerSavedData.get(server);
        if (!rewards.isWritable() || !platform.isWritable() || !rpg.isWritable()) {
            return new PreparationResult(PreparationStatus.RETRY_REQUIRED, 0);
        }

        long totalPoints;
        long cooldownUntil;
        try {
            totalPoints = encounter.contributions().values().stream().reduce(0L, Math::addExact);
            cooldownUntil = Math.addExact(timestamp,
                    Math.multiplyExact((long) boss.rewardCooldownTicks(), MILLIS_PER_TICK));
        } catch (ArithmeticException exception) {
            return new PreparationResult(PreparationStatus.INVALID, 0);
        }
        if (totalPoints <= 0) {
            return new PreparationResult(PreparationStatus.NO_QUALIFIED_PLAYERS, 0);
        }

        Map<UUID, BossRewardOperation> requested = new LinkedHashMap<>();
        List<DeniedDecision> denied = new ArrayList<>();
        for (var entry : encounter.contributions().entrySet().stream()
                .sorted(Map.Entry.comparingByKey()).toList()) {
            UUID playerId = entry.getKey();
            long playerPoints = entry.getValue();
            UUID transactionId = transactionId(encounter.encounterId(), playerId);
            if (!BossRewardOperation.qualifies(playerPoints, totalPoints,
                    contribution.minimumPoints(), contribution.minimumShareBasisPoints())) {
                denied.add(new DeniedDecision(playerId, "participation_threshold"));
                continue;
            }
            BossRewardOperation existing = rewards.operation(transactionId).orElse(null);
            if (existing != null) {
                if (!matchesEvidence(existing, encounter, boss, contribution, loot, playerId,
                        playerPoints, totalPoints)) {
                    return new PreparationResult(PreparationStatus.RETRY_REQUIRED, 0);
                }
                requested.put(transactionId, existing);
                continue;
            }
            long retainedCooldown = rewards.cooldownUntil(boss.id(), playerId, transactionId, timestamp);
            if (retainedCooldown > timestamp) {
                denied.add(new DeniedDecision(playerId, "personal_cooldown"));
                continue;
            }
            List<ItemStack> items = generateLoot(
                    server, encounter, mob, loot, transactionId);
            if (items == null) {
                return new PreparationResult(PreparationStatus.RETRY_REQUIRED, 0);
            }
            BossRewardOperation operation = new BossRewardOperation(
                    encounter.encounterId(), boss.id(), encounter.definitionFingerprint(), playerId,
                    encounter.dimension(), encounter.center(), playerPoints, totalPoints,
                    contribution.minimumPoints(), contribution.minimumShareBasisPoints(),
                    loot.currency(), loot.experience(), cooldownUntil, timestamp, items,
                    BossRewardOperation.Phase.PENDING);
            if (!operation.isValid()) {
                return new PreparationResult(PreparationStatus.INVALID, 0);
            }
            requested.put(transactionId, operation);
        }

        BossRewardSavedData.BatchStatus batch = rewards.putBatch(requested, timestamp);
        if (batch != BossRewardSavedData.BatchStatus.SUCCESS
                && batch != BossRewardSavedData.BatchStatus.DUPLICATE) {
            return new PreparationResult(PreparationStatus.RETRY_REQUIRED, 0);
        }
        denied.forEach(decision -> auditDecision(
                platform, encounter, decision.playerId(), REWARD_DENIED, decision.reason(), timestamp));
        requested.keySet().forEach(transactionId -> process(server, transactionId, timestamp));
        return new PreparationResult(
                requested.isEmpty() ? PreparationStatus.NO_QUALIFIED_PLAYERS
                        : batch == BossRewardSavedData.BatchStatus.DUPLICATE
                                ? PreparationStatus.DUPLICATE : PreparationStatus.SUCCESS,
                requested.size());
    }

    public static void recover(MinecraftServer server, long timestamp) {
        if (server == null || timestamp < 0) {
            return;
        }
        for (var entry : BossRewardSavedData.get(server).pendingOperations()) {
            process(server, entry.getKey(), timestamp);
        }
    }

    public static void auditEncounterFailure(
            MinecraftServer server, BossEncounterState encounter, String reason, long timestamp) {
        if (server == null || encounter == null || reason == null || reason.isBlank() || timestamp < 0) {
            return;
        }
        PlatformSavedData platform = PlatformSavedData.get(server);
        UUID auditId = namedUuid("boss_reward_intent_failed:" + encounter.encounterId() + ":" + reason);
        if (!platform.isWritable() || platform.hasAuditTransaction(auditId)) {
            return;
        }
        platform.commitAudit(new AuditEntry(
                timestamp, AdministrationService.SYSTEM_ACTOR, REWARD_FAILED,
                encounter.encounterId().toString(), Optional.of(encounter.dimension().identifier()),
                Optional.of(encounter.center()), "contributors=" + encounter.contributions().size(),
                "reward=none", reason, auditId));
    }

    static ProcessingStatus process(MinecraftServer server, UUID transactionId, long timestamp) {
        if (server == null || transactionId == null || timestamp < 0) {
            return ProcessingStatus.INVALID;
        }
        BossRewardSavedData rewards = BossRewardSavedData.get(server);
        BossRewardOperation operation = rewards.operation(transactionId).orElse(null);
        if (operation == null) {
            return ProcessingStatus.INVALID;
        }
        if (operation.phase() == BossRewardOperation.Phase.COMPLETED) {
            return ProcessingStatus.COMPLETED;
        }
        if (operation.phase() == BossRewardOperation.Phase.FAILED) {
            return ProcessingStatus.FAILED;
        }
        PlatformSavedData platform = PlatformSavedData.get(server);
        RpgPlayerSavedData rpg = RpgPlayerSavedData.get(server);
        if (!rewards.isWritable() || !platform.isWritable() || !rpg.isWritable()) {
            return ProcessingStatus.RETRY_REQUIRED;
        }

        if (operation.phase() == BossRewardOperation.Phase.PENDING) {
            EconomyService.TransactionStatus preview = operation.currency() == 0
                    ? EconomyService.TransactionStatus.SUCCESS
                    : EconomyService.previewBossReward(
                            platform, operation.playerId(), operation.currency(), rewardReason(operation),
                            operation.createdAtEpochMillis(), transactionId,
                            initialBalance(), maximumBalance());
            if (preview != EconomyService.TransactionStatus.SUCCESS
                    && preview != EconomyService.TransactionStatus.DUPLICATE_TRANSACTION) {
                fail(rewards, platform, transactionId, operation, "economy_" + preview.name().toLowerCase(), timestamp);
                return ProcessingStatus.FAILED;
            }

            if (operation.experience() > 0) {
                var xp = ActivityXpAwardService.awardBossReward(
                        rpg, RpgDefinitionReloadListener.snapshot(server), operation.playerId(), HUNTING,
                        operation.experience(), operation.createdAtEpochMillis(), transactionId,
                        "boss_reward:" + operation.encounterId());
                boolean exactDuplicate = xp.status() == ActivityXpAwardService.Status.DUPLICATE
                        && rpgAppliedExactly(rpg, operation, transactionId);
                if (xp.status() != ActivityXpAwardService.Status.SUCCESS && !exactDuplicate) {
                    fail(rewards, platform, transactionId, operation,
                            "rpg_" + xp.status().name().toLowerCase(), timestamp);
                    return ProcessingStatus.FAILED;
                }
            }
            if (operation.currency() > 0) {
                var economy = EconomyService.awardBossReward(
                        platform, operation.playerId(), operation.currency(), rewardReason(operation),
                        operation.createdAtEpochMillis(), transactionId,
                        initialBalance(), maximumBalance());
                if (economy.status() != EconomyService.TransactionStatus.SUCCESS
                        && economy.status() != EconomyService.TransactionStatus.DUPLICATE_TRANSACTION) {
                    return ProcessingStatus.RETRY_REQUIRED;
                }
            }
            BossRewardOperation replacement = operation.atPhase(BossRewardOperation.Phase.CORE_APPLIED);
            if (!rewards.update(transactionId, operation, replacement)) {
                return ProcessingStatus.RETRY_REQUIRED;
            }
            operation = replacement;
        }

        if (!deliverItems(server, transactionId, operation)) {
            return ProcessingStatus.WAITING_FOR_PLAYER;
        }
        BossRewardOperation completed = operation.atPhase(BossRewardOperation.Phase.COMPLETED);
        if (!rewards.update(transactionId, operation, completed)) {
            return ProcessingStatus.RETRY_REQUIRED;
        }
        auditDecision(platform, operation, REWARD_COMPLETED, "delivered", timestamp);
        ServerPlayer player = rewardPlayer(server, operation.playerId());
        if (player != null) {
            player.sendSystemMessage(Component.translatable(
                    "message.rovenfall.boss.reward_received", operation.currency(), operation.experience()));
        }
        return ProcessingStatus.COMPLETED;
    }

    private static List<ItemStack> generateLoot(
            MinecraftServer server,
            BossEncounterState encounter,
            MobContentCatalog.MobDefinition mob,
            MobContentCatalog.LootDefinition loot,
            UUID transactionId) {
        ServerLevel level = server.getLevel(encounter.dimension());
        var type = BuiltInRegistries.ENTITY_TYPE.getValue(mob.entityType());
        Entity entity = level == null || type == null ? null : type.create(level, EntitySpawnReason.COMMAND);
        if (entity == null) {
            return null;
        }
        entity.snapTo(encounter.center().getX() + 0.5D, encounter.center().getY(),
                encounter.center().getZ() + 0.5D, 0, 0);
        LootParams params = new LootParams.Builder(level)
                .withParameter(LootContextParams.THIS_ENTITY, entity)
                .withParameter(LootContextParams.ORIGIN, entity.position())
                .withParameter(LootContextParams.DAMAGE_SOURCE, level.damageSources().generic())
                .create(LootContextParamSets.ENTITY);
        LootTable table = server.reloadableRegistries().getLootTable(loot.lootTable());
        List<ItemStack> items = new ArrayList<>();
        for (int roll = 0; roll < loot.rolls(); roll++) {
            long seed = transactionId.getMostSignificantBits()
                    ^ Long.rotateLeft(transactionId.getLeastSignificantBits(), 17)
                    ^ ((long) roll * 0x9E3779B97F4A7C15L);
            for (ItemStack stack : table.getRandomItems(params, seed)) {
                if (!stack.isEmpty()) {
                    if (items.size() >= BossRewardOperation.MAX_ITEM_STACKS) {
                        return null;
                    }
                    items.add(stack.copy());
                }
            }
        }
        return List.copyOf(items);
    }

    private static boolean matchesEvidence(
            BossRewardOperation operation,
            BossEncounterState encounter,
            MobContentCatalog.BossDefinition boss,
            MobContentCatalog.ContributionRule contribution,
            MobContentCatalog.LootDefinition loot,
            UUID playerId,
            long playerPoints,
            long totalPoints) {
        return operation.encounterId().equals(encounter.encounterId())
                && operation.bossId().equals(boss.id())
                && operation.definitionFingerprint().equals(encounter.definitionFingerprint())
                && operation.playerId().equals(playerId)
                && operation.dimension().equals(encounter.dimension())
                && operation.center().equals(encounter.center())
                && operation.playerPoints() == playerPoints
                && operation.totalPoints() == totalPoints
                && operation.minimumPoints() == contribution.minimumPoints()
                && operation.minimumShareBasisPoints() == contribution.minimumShareBasisPoints()
                && operation.currency() == loot.currency()
                && operation.experience() == loot.experience();
    }

    private static boolean rpgAppliedExactly(
            RpgPlayerSavedData rpg, BossRewardOperation operation, UUID transactionId) {
        String source = "boss_reward:" + operation.encounterId();
        return rpg.state(operation.playerId()).provenance().stream().anyMatch(entry ->
                entry.kind() == RpgPlayerState.ProgressionProvenance.Kind.ACTIVITY_XP
                        && entry.target().equals(HUNTING)
                        && entry.amount() == operation.experience()
                        && entry.timestamp() == operation.createdAtEpochMillis()
                        && entry.transactionId().equals(transactionId)
                        && entry.source().equals(source));
    }

    private static boolean deliverItems(
            MinecraftServer server, UUID transactionId, BossRewardOperation operation) {
        if (operation.items().isEmpty()) {
            return true;
        }
        ServerPlayer player = rewardPlayer(server, operation.playerId());
        if (player == null) {
            return false;
        }
        List<ItemStack> items = operation.items();
        for (int index = 0; index < items.size(); index++) {
            UUID itemId = itemEntityId(transactionId, index);
            Entity retained = findEntity(server, itemId);
            if (retained != null) {
                if (!(retained instanceof ItemEntity item)
                        || !operation.playerId().equals(item.getTarget())
                        || !ItemStack.matches(items.get(index), item.getItem())) {
                    return false;
                }
                continue;
            }
            ItemEntity item = new ItemEntity(
                    player.level(), player.getX(), player.getY() + 0.5D, player.getZ(), items.get(index).copy());
            item.setUUID(itemId);
            item.setTarget(operation.playerId());
            item.setExtendedLifetime();
            item.setNoPickUpDelay();
            if (!player.level().addFreshEntity(item)) {
                return false;
            }
        }
        return true;
    }

    private static Entity findEntity(MinecraftServer server, UUID entityId) {
        for (ServerLevel level : server.getAllLevels()) {
            Entity entity = level.getEntity(entityId);
            if (entity != null) {
                return entity;
            }
        }
        return null;
    }

    private static ServerPlayer rewardPlayer(MinecraftServer server, UUID playerId) {
        ServerPlayer retained = server.getPlayerList().getPlayer(playerId);
        if (retained != null) {
            return retained;
        }
        for (ServerLevel level : server.getAllLevels()) {
            for (ServerPlayer player : level.players()) {
                if (playerId.equals(player.getUUID())) {
                    return player;
                }
            }
        }
        return null;
    }

    private static void fail(
            BossRewardSavedData rewards,
            PlatformSavedData platform,
            UUID transactionId,
            BossRewardOperation operation,
            String reason,
            long timestamp) {
        BossRewardOperation failed = operation.atPhase(BossRewardOperation.Phase.FAILED);
        if (rewards.update(transactionId, operation, failed)) {
            auditDecision(platform, operation, REWARD_FAILED, reason, timestamp);
        }
    }

    private static void auditDecision(
            PlatformSavedData platform,
            BossEncounterState encounter,
            UUID playerId,
            Identifier action,
            String reason,
            long timestamp) {
        UUID auditId = auditId(encounter.encounterId(), playerId, action, reason);
        if (platform.hasAuditTransaction(auditId)) {
            return;
        }
        platform.commitAudit(new AuditEntry(
                timestamp, AdministrationService.SYSTEM_ACTOR, action, playerId.toString(),
                Optional.of(encounter.dimension().identifier()), Optional.of(encounter.center()),
                "boss=" + encounter.bossId() + ",contribution="
                        + encounter.contributions().getOrDefault(playerId, 0L),
                "reward=none", reason, auditId));
    }

    private static void auditDecision(
            PlatformSavedData platform,
            BossRewardOperation operation,
            Identifier action,
            String reason,
            long timestamp) {
        UUID auditId = auditId(operation.encounterId(), operation.playerId(), action, reason);
        if (platform.hasAuditTransaction(auditId)) {
            return;
        }
        platform.commitAudit(new AuditEntry(
                timestamp, AdministrationService.SYSTEM_ACTOR, action, operation.playerId().toString(),
                Optional.of(operation.dimension().identifier()), Optional.of(operation.center()),
                "points=" + operation.playerPoints() + "/" + operation.totalPoints(),
                "currency=" + operation.currency() + ",xp=" + operation.experience()
                        + ",items=" + operation.items().size(),
                reason, auditId));
    }

    public static UUID transactionId(UUID encounterId, UUID playerId) {
        return namedUuid("boss_reward:" + encounterId + ":" + playerId);
    }

    static UUID itemEntityId(UUID transactionId, int index) {
        return namedUuid("boss_reward_item:" + transactionId + ":" + index);
    }

    private static UUID auditId(UUID encounterId, UUID playerId, Identifier action, String reason) {
        return namedUuid("boss_reward_audit:" + encounterId + ":" + playerId + ":" + action + ":" + reason);
    }

    private static UUID namedUuid(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String rewardReason(BossRewardOperation operation) {
        return "boss reward " + operation.bossId();
    }

    private static long initialBalance() {
        try {
            return EconomyConfig.initialBalance();
        } catch (IllegalStateException exception) {
            return EconomyConfig.DEFAULT_INITIAL_BALANCE;
        }
    }

    private static long maximumBalance() {
        try {
            return EconomyConfig.maximumBalance();
        } catch (IllegalStateException exception) {
            return EconomyConfig.DEFAULT_MAXIMUM_BALANCE;
        }
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(Rovenfall.MOD_ID, path);
    }

    public enum PreparationStatus {
        SUCCESS,
        DUPLICATE,
        NO_QUALIFIED_PLAYERS,
        RETRY_REQUIRED,
        INVALID;

        public boolean durable() {
            return this == SUCCESS || this == DUPLICATE || this == NO_QUALIFIED_PLAYERS;
        }
    }

    public record PreparationResult(PreparationStatus status, int operationCount) {
    }

    enum ProcessingStatus {
        COMPLETED, WAITING_FOR_PLAYER, RETRY_REQUIRED, FAILED, INVALID
    }

    private record DeniedDecision(UUID playerId, String reason) {
    }
}
