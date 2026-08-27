package org.dldyou.rovenfall.rpg;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.resources.Identifier;

/** Single server-authoritative mutation boundary for activity XP. */
public final class ActivityXpAwardService {
    public enum Status { SUCCESS, INVALID_REQUEST, UNKNOWN_ACTIVITY, READ_ONLY, DUPLICATE, COOLDOWN, RATE_LIMIT, OVERFLOW }
    public record AwardResult(Status status, long totalXp, boolean committed) {}

    private ActivityXpAwardService() {}

    public static AwardResult award(
            RpgPlayerSavedData state, RpgDefinitionSnapshot definitions, UUID playerId,
            Identifier activityId, long amount, long timestamp, UUID transactionId, String source) {
        return award(state, definitions, playerId, activityId, amount, timestamp, transactionId, source,
                ActivityXpConfig.limits());
    }

    static AwardResult award(
            RpgPlayerSavedData state, RpgDefinitionSnapshot definitions, UUID playerId,
            Identifier activityId, long amount, long timestamp, UUID transactionId, String source,
            ActivityXpConfig.Limits limits) {
        if (state == null || definitions == null || playerId == null || playerId.equals(new UUID(0, 0))
                || activityId == null || transactionId == null || transactionId.equals(new UUID(0, 0))
                || source == null || source.isBlank() || source.length() > 160 || amount < 1 || timestamp < 0
                || limits == null || limits.maxAward() < 1 || limits.maxWindowAwards() < 1
                || limits.windowMillis() < 0 || limits.cooldownMillis() < 0) {
            return new AwardResult(Status.INVALID_REQUEST, 0, false);
        }
        if (definitions.activity(activityId).isEmpty()) {
            return new AwardResult(Status.UNKNOWN_ACTIVITY, 0, false);
        }
        if (!state.isWritable()) {
            return new AwardResult(Status.READ_ONLY, state.state(playerId).activityXp().getOrDefault(activityId, 0L), false);
        }
        if (amount > limits.maxAward()) {
            return new AwardResult(Status.RATE_LIMIT, state.state(playerId).activityXp().getOrDefault(activityId, 0L), false);
        }
        RpgPlayerState current = state.state(playerId);
        long windowStart = timestamp <= limits.windowMillis() ? 0 : timestamp - limits.windowMillis();
        int windowAwards = 0;
        long total = current.activityXp().getOrDefault(activityId, 0L);
        for (RpgPlayerState.ProgressionProvenance entry : current.provenance()) {
            if (entry.kind() != RpgPlayerState.ProgressionProvenance.Kind.ACTIVITY_XP
                    || !entry.target().equals(activityId)) continue;
            if (entry.source().equals(source)) {
                if (timestamp <= entry.timestamp()) return new AwardResult(Status.DUPLICATE, total, false);
                if (timestamp - entry.timestamp() < limits.cooldownMillis()) return new AwardResult(Status.COOLDOWN, total, false);
            }
            if (entry.timestamp() >= windowStart && entry.timestamp() <= timestamp) windowAwards++;
        }
        if (windowAwards >= limits.maxWindowAwards()) return new AwardResult(Status.RATE_LIMIT, total, false);
        final long updated;
        try { updated = Math.addExact(total, amount); } catch (ArithmeticException ex) {
            return new AwardResult(Status.OVERFLOW, total, false);
        }
        if (updated > RpgPlayerState.MAX_XP) return new AwardResult(Status.OVERFLOW, total, false);
        List<RpgPlayerState.ProgressionProvenance> provenance = new ArrayList<>(current.provenance());
        provenance.add(new RpgPlayerState.ProgressionProvenance(
                RpgPlayerState.ProgressionProvenance.Kind.ACTIVITY_XP, activityId, amount, timestamp, source));
        while (provenance.size() > RpgPlayerState.MAX_PROVENANCE) provenance.removeFirst();
        var activityXp = new java.util.HashMap<>(current.activityXp());
        activityXp.put(activityId, updated);
        RpgPlayerState candidate = new RpgPlayerState(activityXp, current.careers(), current.activeCareer(),
                current.activeSkillSlots(), current.cooldowns(), provenance);
        boolean committed = state.commit(playerId, candidate);
        return new AwardResult(committed ? Status.SUCCESS : Status.DUPLICATE, updated, committed);
    }
}
