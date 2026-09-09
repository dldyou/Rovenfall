package org.dldyou.rovenfall.quest;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.dldyou.rovenfall.administration.EconomyConfig;
import org.dldyou.rovenfall.administration.PlatformSavedData;
import org.dldyou.rovenfall.mobs.BossRewardSavedData;
import org.dldyou.rovenfall.rpg.RpgDefinitionReloadListener;
import org.dldyou.rovenfall.rpg.RpgPlayerSavedData;

/** Bounded server-thread recovery for durable activity, economy, land, boss, and reward evidence. */
public final class QuestProgressRuntime {
    private static final int PLAYER_BATCH = 16;
    private static final int ACTIVITY_BATCH = 64;
    private static final int BOSS_BATCH = 64;
    private static final int ECONOMY_BATCH = 64;
    static final long FUTURE_EVIDENCE_SKEW_MILLIS =
            RepeatableContractService.FUTURE_TIMESTAMP_SKEW_MILLIS;
    private static final Map<MinecraftServer, Cursor> CURSORS = new WeakHashMap<>();

    private QuestProgressRuntime() {
    }

    public static void register(IEventBus eventBus) {
        eventBus.addListener(QuestProgressRuntime::onServerStarted);
        eventBus.addListener(QuestProgressRuntime::onServerTick);
        eventBus.addListener(QuestProgressRuntime::onPlayerLoggedIn);
    }

    public static void acceptActivityEvidence(MinecraftServer server, UUID playerId, UUID transactionId) {
        if (server == null || !server.isSameThread() || playerId == null || transactionId == null) {
            return;
        }
        QuestPlayerSavedData quests = QuestPlayerSavedData.get(server);
        RpgPlayerSavedData.get(server).questActivityEvidence(transactionId)
                .filter(entry -> entry.playerId().equals(playerId))
                .filter(entry -> shouldDeliverActivityEvidence(entry, quests.state(playerId)))
                .flatMap(entry -> QuestProgressService.evidence(entry.provenance()))
                .ifPresent(evidence -> acceptActivity(
                        server, playerId, transactionId, evidence, System.currentTimeMillis()));
    }

    /** Server-thread activation check used only to decide whether RPG must retain an activity outcome. */
    public static boolean shouldCaptureActivityEvidence(
            MinecraftServer server, UUID playerId, Identifier activityId) {
        return shouldCaptureActivityEvidence(
                server, playerId, activityId, System.currentTimeMillis());
    }

    /** Uses the same observed timestamp that will be written to the RPG owner evidence. */
    public static boolean shouldCaptureActivityEvidence(
            MinecraftServer server,
            UUID playerId,
            Identifier activityId,
            long timestampEpochMillis) {
        return server != null && server.isSameThread()
                && QuestProgressService.shouldCaptureActivity(
                        QuestPlayerSavedData.get(server), QuestDefinitionReloadListener.snapshot(server),
                        playerId, activityId, timestampEpochMillis);
    }

    /** Reclaims only owner-confirmed outcomes before an observed activity needs outbox capacity. */
    public static void prepareActivityEvidenceCapacity(
            MinecraftServer server, UUID playerId, long timestampEpochMillis) {
        if (server == null || !server.isSameThread() || playerId == null || timestampEpochMillis < 0) {
            return;
        }
        RpgPlayerSavedData rpg = RpgPlayerSavedData.get(server);
        QuestPlayerSavedData quests = QuestPlayerSavedData.get(server);
        if (rpg.questActivityEvidenceCount(playerId)
                >= RpgPlayerSavedData.MAX_QUEST_ACTIVITY_EVIDENCE_PER_PLAYER) {
            rpg.trimAcknowledgedQuestActivityEvidence(
                    playerId, quests.state(playerId).processedEvidence().keySet(),
                    timestampEpochMillis, ACTIVITY_BATCH);
        }
        if (rpg.questActivityEvidenceCount() >= RpgPlayerSavedData.MAX_QUEST_ACTIVITY_EVIDENCE) {
            rpg.trimAcknowledgedQuestActivityEvidence(
                    (ownerId, transactionId) -> quests.state(ownerId)
                            .processedEvidence().containsKey(transactionId),
                    timestampEpochMillis, ACTIVITY_BATCH);
        }
    }

    public static void acceptEconomyEvidence(MinecraftServer server, UUID transactionId) {
        if (server == null || !server.isSameThread() || transactionId == null) {
            return;
        }
        PlatformSavedData platform = PlatformSavedData.get(server);
        platform.economyReceipt(transactionId)
                .flatMap(receipt -> QuestProgressService.evidence(transactionId, receipt))
                .ifPresent(evidence -> accept(server, platform.economyReceipt(transactionId).orElseThrow().playerId(),
                        evidence));
    }

    public static void acceptBossEvidence(MinecraftServer server, UUID transactionId) {
        if (server == null || !server.isSameThread() || transactionId == null) {
            return;
        }
        BossRewardSavedData.get(server).operation(transactionId)
                .flatMap(operation -> QuestProgressService.evidence(transactionId, operation))
                .ifPresent(evidence -> accept(server,
                        BossRewardSavedData.get(server).operation(transactionId).orElseThrow().playerId(), evidence));
    }

    public static void recover(MinecraftServer server, long timestampEpochMillis) {
        if (server == null || !server.isSameThread() || timestampEpochMillis < 0) {
            return;
        }
        Cursor cursor = CURSORS.computeIfAbsent(server, ignored -> new Cursor());
        recoverActivity(server, cursor, timestampEpochMillis);
        recoverPlatform(server, cursor, timestampEpochMillis);
        recoverBoss(server, cursor, timestampEpochMillis);
        recoverPendingRewards(server, cursor, timestampEpochMillis);
    }

    private static void recoverActivity(MinecraftServer server, Cursor cursor, long timestampEpochMillis) {
        RpgPlayerSavedData rpg = RpgPlayerSavedData.get(server);
        QuestPlayerSavedData quests = QuestPlayerSavedData.get(server);
        var batch = rpg
                .questActivityEvidenceAfter(cursor.activityEvidence, ACTIVITY_BATCH);
        for (var entry : batch.entries()) {
            if (!shouldDeliverActivityEvidence(
                    entry.getValue(), quests.state(entry.getValue().playerId()))) {
                continue;
            }
            QuestProgressService.evidence(entry.getValue().provenance())
                    .ifPresent(evidence -> acceptActivity(
                            server, entry.getValue().playerId(), entry.getKey(), evidence,
                            timestampEpochMillis));
        }
        batch.entries().stream()
                .map(entry -> entry.getValue().playerId())
                .distinct()
                .forEach(playerId -> rpg.trimAcknowledgedQuestActivityEvidence(
                        playerId, quests.state(playerId).processedEvidence().keySet(),
                        timestampEpochMillis, ACTIVITY_BATCH));
        cursor.activityEvidence = batch.hasMore() ? batch.nextCursor().orElse(null) : null;
    }

    private static void recoverPlatform(MinecraftServer server, Cursor cursor, long timestampEpochMillis) {
        PlatformSavedData platform = PlatformSavedData.get(server);
        var batch = platform.economyReceiptsAfter(cursor.economyReceipt, ECONOMY_BATCH);
        for (var receipt : batch.entries()) {
            if (withinReplayWindow(receipt.getValue().timestampEpochMillis(), timestampEpochMillis)) {
                QuestProgressService.evidence(receipt.getKey(), receipt.getValue())
                        .ifPresent(evidence -> accept(server, receipt.getValue().playerId(), evidence));
            }
        }
        cursor.economyReceipt = batch.hasMore() ? batch.nextCursor().orElse(null) : null;
    }

    private static void recoverBoss(MinecraftServer server, Cursor cursor, long timestampEpochMillis) {
        var batch = BossRewardSavedData.get(server).operationsAfter(cursor.bossReward, BOSS_BATCH);
        for (var operation : batch.entries()) {
            if (withinReplayWindow(operation.getValue().createdAtEpochMillis(), timestampEpochMillis)) {
                QuestProgressService.evidence(operation.getKey(), operation.getValue())
                        .ifPresent(evidence -> accept(server, operation.getValue().playerId(), evidence));
            }
        }
        cursor.bossReward = batch.hasMore() ? batch.nextCursor().orElse(null) : null;
    }

    private static void recoverPendingRewards(MinecraftServer server, Cursor cursor, long timestamp) {
        QuestPlayerSavedData quests = QuestPlayerSavedData.get(server);
        var batch = quests.playersAfter(cursor.questPlayer, PLAYER_BATCH);
        for (var player : batch.entries()) {
            recoverPlayerRewards(server, player.getKey(), timestamp);
            maintainProcessedEvidence(server, player.getKey(), timestamp, cursor);
        }
        cursor.questPlayer = batch.hasMore() ? batch.nextCursor().orElse(null) : null;
    }

    private static QuestProgressService.ProgressResult accept(
            MinecraftServer server, UUID playerId, QuestProgressService.Evidence evidence) {
        if (evidence == null) {
            return new QuestProgressService.ProgressResult(
                    QuestProgressService.ProgressStatus.INVALID, 0, 0, false);
        }
        if (!withinReplayWindow(evidence.timestampEpochMillis(), System.currentTimeMillis())) {
            return new QuestProgressService.ProgressResult(
                    QuestProgressService.ProgressStatus.IGNORED, 0, 0, false);
        }
        QuestProgressService.ProgressResult result = QuestProgressService.applyEvidence(
                QuestPlayerSavedData.get(server), QuestDefinitionReloadListener.snapshot(server),
                PlatformSavedData.get(server), playerId, evidence);
        recoverPlayerRewards(server, playerId, System.currentTimeMillis());
        syncOnlinePlayer(server, playerId);
        return result;
    }

    private static void acceptActivity(
            MinecraftServer server,
            UUID playerId,
            UUID transactionId,
            QuestProgressService.Evidence evidence,
            long timestampEpochMillis) {
        QuestProgressService.ProgressResult result = accept(server, playerId, evidence);
        RpgPlayerSavedData.AckDisposition disposition = switch (result.status()) {
            case SUCCESS, REWARD_PENDING, DUPLICATE -> RpgPlayerSavedData.AckDisposition.APPLIED;
            case IGNORED -> RpgPlayerSavedData.AckDisposition.IGNORED;
            case STALE_DEFINITION, READ_ONLY, STATE_FULL, CONCURRENT_CHANGE, INVALID -> null;
        };
        if (disposition != null) {
            RpgPlayerSavedData.get(server).acknowledgeQuestActivityEvidence(
                    transactionId, playerId, timestampEpochMillis, disposition);
        }
    }

    private static void recoverPlayerRewards(MinecraftServer server, UUID playerId, long timestamp) {
        Cursor cursor = CURSORS.computeIfAbsent(server, ignored -> new Cursor());
        QuestProgressService.recoverRewards(
                QuestPlayerSavedData.get(server), QuestDefinitionReloadListener.snapshot(server),
                PlatformSavedData.get(server), RpgPlayerSavedData.get(server),
                RpgDefinitionReloadListener.snapshot(server), playerId, timestamp,
                EconomyConfig.initialBalance(), EconomyConfig.maximumBalance(),
                cursor.rewardRecovery.computeIfAbsent(
                        playerId, ignored -> new QuestProgressService.RecoveryCursor()));
    }

    private static void maintainProcessedEvidence(
            MinecraftServer server, UUID playerId, long timestamp, Cursor cursor) {
        if (timestamp < 0) {
            return;
        }
        QuestPlayerSavedData quests = QuestPlayerSavedData.get(server);
        Map<UUID, Boolean> ownerEvidencePresent = new LinkedHashMap<>();
        var candidates = processedEvidenceMaintenanceBatch(
                quests.state(playerId), timestamp, cursor.processedEvidence.get(playerId), ACTIVITY_BATCH);
        candidates.forEach(entry -> ownerEvidencePresent.put(
                entry.getKey(), ownerEvidencePresent(
                        server, entry.getKey(), entry.getValue(), timestamp)));
        if (candidates.isEmpty()) {
            cursor.processedEvidence.remove(playerId);
        } else {
            cursor.processedEvidence.put(playerId, candidates.getLast().getKey());
        }
        quests.maintainProcessedEvidence(playerId, ownerEvidencePresent, timestamp, ACTIVITY_BATCH);
    }

    static java.util.List<Map.Entry<UUID, QuestPlayerState.ProcessedEvidence>> processedEvidenceMaintenanceBatch(
            QuestPlayerState state, long timestamp, UUID afterExclusive, int maximumEntries) {
        if (state == null || timestamp < 0 || maximumEntries < 1 || maximumEntries > 256) {
            return java.util.List.of();
        }
        long cutoff = timestamp <= QuestPlayerSavedData.PROCESSED_EVIDENCE_OWNER_RETENTION_MILLIS
                ? 0L
                : timestamp - QuestPlayerSavedData.PROCESSED_EVIDENCE_OWNER_RETENTION_MILLIS;
        long retirementCutoff = timestamp <= QuestPlayerSavedData.PROCESSED_EVIDENCE_RETIRE_CONFIRMATION_MILLIS
                ? 0L
                : timestamp - QuestPlayerSavedData.PROCESSED_EVIDENCE_RETIRE_CONFIRMATION_MILLIS;
        var eligible = state.processedEvidence().entrySet().stream()
                .filter(entry -> entry.getValue().kind().isPresent())
                .filter(entry -> entry.getValue().timestampEpochMillis() < cutoff)
                .filter(entry -> entry.getValue().ownerEvidenceMissingSinceEpochMillis().isEmpty()
                        || entry.getValue().ownerEvidenceMissingSinceEpochMillis().orElseThrow()
                                < retirementCutoff)
                .sorted(Map.Entry.comparingByKey())
                .toList();
        int start = 0;
        if (afterExclusive != null) {
            while (start < eligible.size() && eligible.get(start).getKey().compareTo(afterExclusive) <= 0) {
                start++;
            }
            if (start == eligible.size()) {
                start = 0;
            }
        }
        return java.util.List.copyOf(eligible.subList(start, Math.min(start + maximumEntries, eligible.size())));
    }

    private static boolean ownerEvidencePresent(
            MinecraftServer server,
            UUID transactionId,
            QuestPlayerState.ProcessedEvidence evidence,
            long timestampEpochMillis) {
        return switch (evidence.kind().orElseThrow()) {
            case ACTIVITY -> RpgPlayerSavedData.get(server).questActivityEvidence(transactionId).isPresent();
            case SHOP_TRADE, CLAIM_PURCHASE -> PlatformSavedData.get(server).economyReceipt(transactionId)
                    .filter(receipt -> withinOwnerRetention(
                            receipt.timestampEpochMillis(), timestampEpochMillis))
                    .isPresent();
            case BOSS_DEFEAT -> BossRewardSavedData.get(server).operation(transactionId)
                    .filter(operation -> withinOwnerRetention(
                            operation.createdAtEpochMillis(), timestampEpochMillis))
                    .isPresent();
        };
    }

    static boolean withinOwnerRetention(long evidenceTimestamp, long timestampEpochMillis) {
        return timestampEpochMillis <= QuestPlayerSavedData.PROCESSED_EVIDENCE_OWNER_RETENTION_MILLIS
                || evidenceTimestamp >= timestampEpochMillis
                        - QuestPlayerSavedData.PROCESSED_EVIDENCE_OWNER_RETENTION_MILLIS;
    }

    static boolean withinReplayWindow(long evidenceTimestamp, long timestampEpochMillis) {
        if (evidenceTimestamp < 0 || timestampEpochMillis < 0) {
            return false;
        }
        long latest = timestampEpochMillis > Long.MAX_VALUE - FUTURE_EVIDENCE_SKEW_MILLIS
                ? Long.MAX_VALUE
                : timestampEpochMillis + FUTURE_EVIDENCE_SKEW_MILLIS;
        return evidenceTimestamp <= latest
                && (timestampEpochMillis <= QuestPlayerSavedData.PROCESSED_EVIDENCE_REPLAY_MILLIS
                        || evidenceTimestamp >= timestampEpochMillis
                                - QuestPlayerSavedData.PROCESSED_EVIDENCE_REPLAY_MILLIS);
    }

    static boolean shouldDeliverActivityEvidence(
            RpgPlayerSavedData.QuestActivityEvidence evidence, QuestPlayerState questState) {
        if (evidence == null || questState == null) {
            return false;
        }
        return evidence.ackDisposition().isEmpty()
                || evidence.ackDisposition().orElseThrow() == RpgPlayerSavedData.AckDisposition.APPLIED
                        && !questState.processedEvidence().containsKey(evidence.provenance().transactionId());
    }

    private static void onServerStarted(ServerStartedEvent event) {
        recover(event.getServer(), System.currentTimeMillis());
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        if (event.getServer().overworld().getGameTime() % 20L == 0L) {
            recover(event.getServer(), System.currentTimeMillis());
        }
    }

    private static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        MinecraftServer server = player.level().getServer();
        QuestPlayerSavedData quests = QuestPlayerSavedData.get(server);
        Cursor cursor = CURSORS.computeIfAbsent(server, ignored -> new Cursor());
        for (var entry : RpgPlayerSavedData.get(server)
                .questActivityEvidenceFor(
                        player.getUUID(), RpgPlayerSavedData.MAX_QUEST_ACTIVITY_EVIDENCE_BATCH_SIZE)) {
            if (!shouldDeliverActivityEvidence(entry.getValue(), quests.state(player.getUUID()))) {
                continue;
            }
            QuestProgressService.evidence(entry.getValue().provenance())
                    .ifPresent(evidence -> acceptActivity(
                            server, player.getUUID(), entry.getKey(), evidence,
                            System.currentTimeMillis()));
        }
        RpgPlayerSavedData.get(server).trimAcknowledgedQuestActivityEvidence(
                player.getUUID(),
                quests.state(player.getUUID()).processedEvidence().keySet(),
                System.currentTimeMillis(), ACTIVITY_BATCH);
        recoverPlayerRewards(server, player.getUUID(), System.currentTimeMillis());
        maintainProcessedEvidence(server, player.getUUID(), System.currentTimeMillis(), cursor);
        ActiveJourneyTrackerNetwork.sync(player);
    }

    private static void syncOnlinePlayer(MinecraftServer server, UUID playerId) {
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player != null) {
            ActiveJourneyTrackerNetwork.sync(player);
        }
    }

    private static final class Cursor {
        private UUID activityEvidence;
        private UUID economyReceipt;
        private UUID bossReward;
        private UUID questPlayer;
        private final Map<UUID, UUID> processedEvidence = new java.util.HashMap<>();
        private final Map<UUID, QuestProgressService.RecoveryCursor> rewardRecovery = new java.util.HashMap<>();
    }
}
