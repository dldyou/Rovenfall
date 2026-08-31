package org.dldyou.rovenfall.quest;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.resources.Identifier;

/** Deterministic, bounded assignment of server-owned daily and weekly contracts. */
public final class RepeatableContractService {
    public static final long DAY_MILLIS = 86_400_000L;
    public static final int DAILY_WINDOWS_RETAINED = 90;
    public static final int WEEKLY_WINDOWS_RETAINED = 52;
    static final long FUTURE_TIMESTAMP_SKEW_MILLIS = java.time.Duration.ofMinutes(5).toMillis();
    private static final UUID ZERO_UUID = new UUID(0L, 0L);

    private RepeatableContractService() {
    }

    /**
     * Initializes the current UTC daily and Monday-based weekly rosters exactly once.
     * A persisted empty roster remains empty after a datapack reload.
     */
    public static AssignmentResult ensureAssignments(
            QuestPlayerSavedData savedData,
            QuestDefinitionSnapshot definitions,
            UUID playerId,
            long timestampEpochMillis) {
        long now = System.currentTimeMillis();
        long latest = now > Long.MAX_VALUE - FUTURE_TIMESTAMP_SKEW_MILLIS
                ? Long.MAX_VALUE : now + FUTURE_TIMESTAMP_SKEW_MILLIS;
        if (savedData == null || definitions == null || playerId == null || ZERO_UUID.equals(playerId)
                || timestampEpochMillis < 0 || timestampEpochMillis > latest) {
            return new AssignmentResult(AssignmentStatus.INVALID, 0, 0, false);
        }
        if (!savedData.isWritable()) {
            return new AssignmentResult(AssignmentStatus.READ_ONLY, 0, 0, false);
        }

        QuestPlayerState current = savedData.state(playerId);
        Map<QuestPlayerState.ContractKey, QuestPlayerState.QuestEntry> contracts =
                new HashMap<>(current.contracts());
        Set<QuestPlayerState.ContractWindow> initialized =
                new HashSet<>(current.initializedContractWindows());
        int removed = pruneExpired(contracts, initialized, timestampEpochMillis);
        int assigned = 0;
        int initializedWindows = 0;

        for (QuestDefinition.Cadence cadence : QuestDefinition.Cadence.values()) {
            QuestPlayerState.ContractWindow window = windowAt(cadence, timestampEpochMillis);
            if (!initialized.add(window)) {
                continue;
            }
            initializedWindows++;
            for (Map.Entry<Identifier, QuestDefinition> candidate : selected(
                    definitions, playerId, window)) {
                contracts.put(new QuestPlayerState.ContractKey(window, candidate.getKey()),
                        new QuestPlayerState.QuestEntry(
                                candidate.getValue().version(), Map.of(), Optional.empty(), Optional.empty()));
                assigned++;
            }
        }

        if (contracts.size() > QuestPlayerState.MAX_CONTRACTS
                || initialized.size() > QuestPlayerState.MAX_INITIALIZED_CONTRACT_WINDOWS) {
            return new AssignmentResult(AssignmentStatus.STATE_FULL, 0, 0, false);
        }
        if (assigned == 0 && removed == 0 && initializedWindows == 0) {
            return new AssignmentResult(AssignmentStatus.UNCHANGED, 0, 0, false);
        }
        QuestPlayerState updated = new QuestPlayerState(
                current.quests(), current.processedEvidence(), contracts, initialized);
        if (!updated.isValid()) {
            return new AssignmentResult(AssignmentStatus.STATE_FULL, 0, 0, false);
        }
        boolean committed = savedData.commit(playerId, current, updated);
        return new AssignmentResult(
                committed ? AssignmentStatus.SUCCESS : AssignmentStatus.CONCURRENT_CHANGE,
                committed ? assigned : 0,
                committed ? removed : 0,
                committed);
    }

    public static QuestPlayerState.ContractWindow windowAt(
            QuestDefinition.Cadence cadence, long timestampEpochMillis) {
        if (cadence == null || timestampEpochMillis < 0) {
            throw new IllegalArgumentException("invalid contract window request");
        }
        long epochDay = timestampEpochMillis / DAY_MILLIS;
        long start = cadence == QuestDefinition.Cadence.DAILY
                ? epochDay
                : epochDay - Math.floorMod(epochDay + 3L, 7L);
        return new QuestPlayerState.ContractWindow(cadence, start);
    }

    public static long windowEndEpochMillis(QuestPlayerState.ContractWindow window) {
        if (window == null || !window.isValid()) {
            throw new IllegalArgumentException("invalid contract window");
        }
        long days = window.windowStartEpochDay()
                + (window.cadence() == QuestDefinition.Cadence.DAILY ? 1L : 7L);
        try {
            return Math.multiplyExact(days, DAY_MILLIS);
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    public static boolean contains(QuestPlayerState.ContractWindow window, long timestampEpochMillis) {
        if (window == null || !window.isValid() || timestampEpochMillis < 0) {
            return false;
        }
        return window.equals(windowAt(window.cadence(), timestampEpochMillis));
    }

    /** Current roster keys only; stale retained windows never leak into the player-facing view. */
    public static List<QuestPlayerState.ContractKey> currentKeys(
            QuestPlayerState state, long timestampEpochMillis) {
        if (state == null || timestampEpochMillis < 0) {
            return List.of();
        }
        Set<QuestPlayerState.ContractWindow> current = Set.of(
                windowAt(QuestDefinition.Cadence.DAILY, timestampEpochMillis),
                windowAt(QuestDefinition.Cadence.WEEKLY, timestampEpochMillis));
        return state.contracts().keySet().stream()
                .filter(key -> current.contains(key.window()))
                .sorted()
                .toList();
    }

    private static List<Map.Entry<Identifier, QuestDefinition>> selected(
            QuestDefinitionSnapshot definitions,
            UUID playerId,
            QuestPlayerState.ContractWindow window) {
        List<Map.Entry<Identifier, QuestDefinition>> candidates = new ArrayList<>(
                definitions.contractTemplates(window.cadence()).entrySet());
        candidates.sort(Comparator
                .comparing((Map.Entry<Identifier, QuestDefinition> entry) ->
                        assignmentRank(playerId, window, entry.getKey()))
                .thenComparing(Map.Entry::getKey));
        return List.copyOf(candidates.subList(0, Math.min(window.cadence().slots(), candidates.size())));
    }

    private static UUID assignmentRank(
            UUID playerId, QuestPlayerState.ContractWindow window, Identifier templateId) {
        String value = "rovenfall:contract:assignment:" + playerId + ":"
                + window.cadence().getSerializedName() + ":" + window.windowStartEpochDay() + ":" + templateId;
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    private static int pruneExpired(
            Map<QuestPlayerState.ContractKey, QuestPlayerState.QuestEntry> contracts,
            Set<QuestPlayerState.ContractWindow> initialized,
            long timestampEpochMillis) {
        QuestPlayerState.ContractWindow daily = windowAt(QuestDefinition.Cadence.DAILY, timestampEpochMillis);
        QuestPlayerState.ContractWindow weekly = windowAt(QuestDefinition.Cadence.WEEKLY, timestampEpochMillis);
        Set<QuestPlayerState.ContractWindow> retainedForPending = new HashSet<>();
        int contractsBefore = contracts.size();
        int windowsBefore = initialized.size();
        contracts.entrySet().removeIf(entry -> {
            if (!expired(entry.getKey().window(), daily, weekly)) {
                return false;
            }
            if (entry.getValue().pendingReward().isPresent()) {
                retainedForPending.add(entry.getKey().window());
                return false;
            }
            return true;
        });
        initialized.removeIf(window -> expired(window, daily, weekly)
                && !retainedForPending.contains(window));
        return contractsBefore - contracts.size() + windowsBefore - initialized.size();
    }

    private static boolean expired(
            QuestPlayerState.ContractWindow window,
            QuestPlayerState.ContractWindow currentDaily,
            QuestPlayerState.ContractWindow currentWeekly) {
        long cutoff = window.cadence() == QuestDefinition.Cadence.DAILY
                ? currentDaily.windowStartEpochDay() - (DAILY_WINDOWS_RETAINED - 1L)
                : currentWeekly.windowStartEpochDay() - 7L * (WEEKLY_WINDOWS_RETAINED - 1L);
        return window.windowStartEpochDay() < cutoff;
    }

    public enum AssignmentStatus {
        SUCCESS,
        UNCHANGED,
        READ_ONLY,
        STATE_FULL,
        CONCURRENT_CHANGE,
        INVALID
    }

    public record AssignmentResult(AssignmentStatus status, int assigned, int removed, boolean committed) {
    }
}
