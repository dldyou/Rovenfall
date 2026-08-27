package org.dldyou.rovenfall.rpg;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;

/** Bounded server-memory ledger for per-target combat caps and ordinary hunting participation. */
final class CombatContributionTracker {
    static final int MAX_TRACKED_TARGETS = 10_000;
    static final int MAX_CONTRIBUTORS_PER_TARGET = 64;
    static final long TARGET_TTL_MILLIS = 30 * 60 * 1_000L;
    static final double MIN_HUNTING_SHARE = 0.05D;

    private static final UUID ZERO_UUID = new UUID(0, 0);
    private final Map<UUID, TargetContribution> targets = new HashMap<>();
    private final PriorityQueue<Expiry> expirations = new PriorityQueue<>();
    private final Set<UUID> pendingDeaths = new HashSet<>();

    boolean record(UUID targetId, UUID playerId, double damage, long timestamp) {
        if (!valid(targetId) || !valid(playerId) || targetId.equals(playerId)
                || !Double.isFinite(damage) || damage <= 0 || timestamp < 0) {
            return false;
        }
        removeExpired(timestamp);
        TargetContribution target = targets.get(targetId);
        if (target == null) {
            if (targets.size() >= MAX_TRACKED_TARGETS) {
                evictOldestTarget();
            }
            target = new TargetContribution(timestamp);
            targets.put(targetId, target);
        }
        Double contribution = target.participants.get(playerId);
        if (contribution == null) {
            if (target.participants.size() >= MAX_CONTRIBUTORS_PER_TARGET) {
                return false;
            }
            contribution = 0D;
        }
        target.participants.put(playerId, contribution + damage);
        target.lastActivity = Math.max(target.lastActivity, timestamp);
        expirations.add(new Expiry(targetId, target.lastActivity));
        compactExpirationsIfNeeded();
        return true;
    }

    List<UUID> consumeHuntingCredit(UUID targetId, long timestamp) {
        if (!valid(targetId) || timestamp < 0) {
            return List.of();
        }
        removeExpired(timestamp);
        TargetContribution target = targets.remove(targetId);
        pendingDeaths.remove(targetId);
        if (target == null) {
            return List.of();
        }
        double totalDamage = target.participants.values().stream()
                .mapToDouble(Double::doubleValue)
                .sum();
        if (!Double.isFinite(totalDamage) || totalDamage <= 0) {
            return List.of();
        }
        double threshold = Math.max(1D, totalDamage * MIN_HUNTING_SHARE);
        return target.participants.entrySet().stream()
                .filter(entry -> entry.getValue() >= threshold)
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
    }

    boolean markDeath(UUID targetId, long timestamp) {
        if (!valid(targetId) || timestamp < 0) {
            return false;
        }
        removeExpired(timestamp);
        if (!pendingDeaths.contains(targetId) && pendingDeaths.size() >= MAX_TRACKED_TARGETS) {
            return false;
        }
        pendingDeaths.add(targetId);
        return true;
    }

    List<UUID> consumePendingHuntingCredit(UUID targetId, long timestamp) {
        if (!valid(targetId) || timestamp < 0 || !pendingDeaths.contains(targetId)) {
            return List.of();
        }
        return consumeHuntingCredit(targetId, timestamp);
    }

    List<HuntingCredit> drainPendingDeaths(long timestamp) {
        if (timestamp < 0) {
            return List.of();
        }
        removeExpired(timestamp);
        List<UUID> targetIds = pendingDeaths.stream().sorted().toList();
        List<HuntingCredit> result = new ArrayList<>(targetIds.size());
        for (UUID targetId : targetIds) {
            List<UUID> playerIds = consumePendingHuntingCredit(targetId, timestamp);
            if (!playerIds.isEmpty()) {
                result.add(new HuntingCredit(targetId, playerIds));
            }
        }
        return List.copyOf(result);
    }

    int trackedTargetCount() {
        return targets.size();
    }

    private void removeExpired(long timestamp) {
        while (!expirations.isEmpty()) {
            Expiry expiry = expirations.peek();
            if (timestamp < expiry.lastActivity()
                    || timestamp - expiry.lastActivity() <= TARGET_TTL_MILLIS) {
                break;
            }
            expirations.remove();
            TargetContribution target = targets.get(expiry.targetId());
            if (target != null && target.lastActivity == expiry.lastActivity()) {
                targets.remove(expiry.targetId());
                pendingDeaths.remove(expiry.targetId());
            }
        }
    }

    private void evictOldestTarget() {
        while (!expirations.isEmpty()) {
            Expiry expiry = expirations.remove();
            TargetContribution target = targets.get(expiry.targetId());
            if (target != null && target.lastActivity == expiry.lastActivity()) {
                targets.remove(expiry.targetId());
                pendingDeaths.remove(expiry.targetId());
                return;
            }
        }
        if (!targets.isEmpty()) {
            UUID targetId = targets.keySet().iterator().next();
            targets.remove(targetId);
            pendingDeaths.remove(targetId);
        }
    }

    private void compactExpirationsIfNeeded() {
        int maximumEntries = Math.max(1_024, targets.size() * 4);
        if (expirations.size() <= maximumEntries) {
            return;
        }
        expirations.clear();
        targets.forEach((targetId, target) -> expirations.add(new Expiry(targetId, target.lastActivity)));
    }

    private static boolean valid(UUID value) {
        return value != null && !ZERO_UUID.equals(value);
    }

    private record Expiry(UUID targetId, long lastActivity) implements Comparable<Expiry> {
        @Override
        public int compareTo(Expiry other) {
            int timestampOrder = Long.compare(lastActivity, other.lastActivity);
            return timestampOrder != 0 ? timestampOrder : targetId.compareTo(other.targetId);
        }
    }

    record HuntingCredit(UUID targetId, List<UUID> playerIds) {
        HuntingCredit {
            playerIds = List.copyOf(playerIds);
        }
    }

    private static final class TargetContribution {
        private final Map<UUID, Double> participants = new HashMap<>();
        private long lastActivity;

        private TargetContribution(long lastActivity) {
            this.lastActivity = lastActivity;
        }
    }
}
