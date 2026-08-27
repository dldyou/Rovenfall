package org.dldyou.rovenfall.administration;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.dldyou.rovenfall.mobs.BossEncounterSavedData;
import org.dldyou.rovenfall.mobs.BossEncounterState;
import org.dldyou.rovenfall.mobs.BossRewardOperation;
import org.dldyou.rovenfall.mobs.BossRewardSavedData;
import org.dldyou.rovenfall.rpg.ActivityXpConfig;
import org.dldyou.rovenfall.rpg.RpgAdministrationViewService;
import org.dldyou.rovenfall.rpg.RpgPlayerSavedData;
import org.dldyou.rovenfall.rpg.RpgPlayerState;

/** Explicit, read-only, bounded cross-domain operator snapshot. */
public final class OperationsMetricsService {
    public static final long DEFAULT_WINDOW_MILLIS = Duration.ofHours(1).toMillis();
    public static final long MIN_WINDOW_MILLIS = Duration.ofMinutes(1).toMillis();
    public static final long MAX_WINDOW_MILLIS = Duration.ofHours(24).toMillis();
    public static final int MAX_RPG_PLAYERS = 50;
    private static final int RPG_PAGE_SIZE = RpgAdministrationViewService.MAX_PAGE_SIZE;

    private OperationsMetricsService() {
    }

    public static Result snapshot(
            PlatformSavedData platform,
            RpgPlayerSavedData.Snapshot rpg,
            List<BossEncounterState> encounters,
            List<Map.Entry<UUID, BossRewardOperation>> rewards,
            UUID actorId,
            boolean authorizationOverride,
            long generatedAtEpochMillis,
            long windowMillis,
            ActivityXpConfig.ConfigSnapshot rpgConfig) {
        if (platform == null || rpg == null || encounters == null || rewards == null || actorId == null
                || generatedAtEpochMillis < 0 || windowMillis < MIN_WINDOW_MILLIS
                || windowMillis > MAX_WINDOW_MILLIS || rpgConfig == null) {
            return Result.invalid(generatedAtEpochMillis, windowMillis);
        }
        if (!authorizationOverride && !platform.hasAdminRole(actorId)) {
            return Result.unauthorized(generatedAtEpochMillis, windowMillis);
        }

        long since = Math.max(0L, generatedAtEpochMillis - windowMillis);
        Set<UUID> economyTransactions = new HashSet<>();
        platform.economyReceiptsView().forEach((transactionId, receipt) -> {
            if (inWindow(receipt.timestampEpochMillis(), since, generatedAtEpochMillis)) {
                economyTransactions.add(transactionId);
            }
        });

        Set<AlertKey> alerts = new HashSet<>();
        for (EconomyAlert alert : platform.economyAlertsView()) {
            if (inWindow(alert.timestampEpochMillis(), since, generatedAtEpochMillis)) {
                alerts.add(new AlertKey(alert.transactionId(), alert.type()));
            }
        }

        Set<AuditKey> denied = new HashSet<>();
        Set<AuditKey> malformed = new HashSet<>();
        for (AuditEntry entry : platform.auditEntriesView()) {
            if (!inWindow(entry.timestampEpochMillis(), since, generatedAtEpochMillis)) {
                continue;
            }
            String action = entry.actionType().getPath();
            AuditKey key = new AuditKey(entry.transactionId(), entry.actionType().toString());
            if (action.endsWith("_denied")) {
                denied.add(key);
            }
            String reason = entry.reason().toLowerCase(java.util.Locale.ROOT);
            if (action.contains("malformed") || reason.contains("malformed") || reason.contains("invalid")) {
                malformed.add(key);
            }
        }

        List<Map.Entry<UUID, RpgPlayerState>> players = rpg.players().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .limit(MAX_RPG_PLAYERS)
                .toList();
        Set<UUID> suspiciousAwards = new HashSet<>();
        for (Map.Entry<UUID, RpgPlayerState> player : players) {
            int page = 0;
            int totalPages;
            do {
                var evidence = RpgAdministrationViewService.awardHistory(
                        player.getValue(), java.util.Optional.empty(), true,
                        page, RPG_PAGE_SIZE, rpgConfig);
                evidence.entries().stream()
                        .map(RpgAdministrationViewService.AwardEvidence::evidence)
                        .filter(entry -> inWindow(entry.timestamp(), since, generatedAtEpochMillis))
                        .map(RpgPlayerState.ProgressionProvenance::transactionId)
                        .forEach(suspiciousAwards::add);
                totalPages = evidence.totalPages();
                page++;
            } while (page < totalPages && page <= RpgPlayerState.MAX_PROVENANCE / RPG_PAGE_SIZE + 1);
        }

        long activeEncounters = encounters.stream().map(BossEncounterState::encounterId).distinct().count();
        Set<UUID> pendingRewards = new HashSet<>();
        Set<UUID> pendingRewardRecovery = new HashSet<>();
        for (Map.Entry<UUID, BossRewardOperation> reward : rewards) {
            if (reward.getValue().phase() == BossRewardOperation.Phase.PENDING) {
                pendingRewards.add(reward.getKey());
            } else if (reward.getValue().phase() == BossRewardOperation.Phase.CORE_APPLIED) {
                pendingRewardRecovery.add(reward.getKey());
            }
        }

        Set<UUID> linkedEvidence = new HashSet<>();
        alerts.stream().map(AlertKey::transactionId).forEach(linkedEvidence::add);
        denied.stream().map(AuditKey::transactionId).forEach(linkedEvidence::add);
        malformed.stream().map(AuditKey::transactionId).forEach(linkedEvidence::add);
        linkedEvidence.addAll(suspiciousAwards);
        linkedEvidence.addAll(pendingRewards);
        linkedEvidence.addAll(pendingRewardRecovery);

        return new Result(Status.SUCCESS, generatedAtEpochMillis, windowMillis,
                economyTransactions.size(), count(alerts, EconomyAlert.Type.AMOUNT),
                count(alerts, EconomyAlert.Type.RATE), denied.size(), malformed.size(),
                suspiciousAwards.size(), Math.toIntExact(activeEncounters), pendingRewards.size(),
                Math.addExact(platform.pendingRecoveryOperationCount(), pendingRewardRecovery.size()),
                players.size(), rpg.players().size() > MAX_RPG_PLAYERS,
                linkedEvidence.stream().sorted().limit(5).toList());
    }

    public static Result snapshot(
            net.minecraft.server.MinecraftServer server,
            UUID actorId,
            boolean authorizationOverride,
            long generatedAtEpochMillis,
            long windowMillis) {
        return snapshot(
                PlatformSavedData.get(server),
                RpgPlayerSavedData.get(server).snapshot(),
                BossEncounterSavedData.get(server).activeEncounters(),
                BossRewardSavedData.get(server).operations(),
                actorId,
                authorizationOverride,
                generatedAtEpochMillis,
                windowMillis,
                ActivityXpConfig.snapshot());
    }

    private static int count(Set<AlertKey> alerts, EconomyAlert.Type type) {
        return (int) alerts.stream().filter(alert -> alert.type() == type).count();
    }

    private static boolean inWindow(long timestamp, long since, long until) {
        return timestamp >= since && timestamp <= until;
    }

    public enum Status {
        SUCCESS,
        UNAUTHORIZED,
        INVALID_REQUEST
    }

    public record Result(
            Status status,
            long generatedAtEpochMillis,
            long windowMillis,
            int economyTransactionCount,
            int amountAlertCount,
            int rateAlertCount,
            int deniedRequestCount,
            int malformedRequestCount,
            int suspiciousRpgAwardCount,
            int activeEncounterCount,
            int pendingRewardCount,
            int pendingRecoveryCount,
            int scannedRpgPlayers,
            boolean rpgTruncated,
            List<UUID> evidenceTransactionIds) {
        public Result {
            evidenceTransactionIds = List.copyOf(evidenceTransactionIds);
        }

        static Result unauthorized(long generatedAt, long window) {
            return empty(Status.UNAUTHORIZED, generatedAt, window);
        }

        static Result invalid(long generatedAt, long window) {
            return empty(Status.INVALID_REQUEST, generatedAt, window);
        }

        private static Result empty(Status status, long generatedAt, long window) {
            return new Result(status, generatedAt, window, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, false, List.of());
        }

        public boolean hasAnomaly() {
            return amountAlertCount > 0 || rateAlertCount > 0 || deniedRequestCount > 0
                    || malformedRequestCount > 0 || suspiciousRpgAwardCount > 0 || pendingRecoveryCount > 0;
        }
    }

    private record AlertKey(UUID transactionId, EconomyAlert.Type type) {
    }

    private record AuditKey(UUID transactionId, String action) {
    }
}
