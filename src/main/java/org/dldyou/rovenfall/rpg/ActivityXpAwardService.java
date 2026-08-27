package org.dldyou.rovenfall.rpg;

import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.Identifier;

/** Single server-authoritative mutation boundary for activity XP. */
public final class ActivityXpAwardService {
    public static final int MAX_EVIDENCE_PAGE_SIZE = 50;
    private static final Identifier COMBAT = Identifier.fromNamespaceAndPath("rovenfall", "combat");
    private static final Identifier EXPLORATION = Identifier.fromNamespaceAndPath("rovenfall", "exploration");

    public enum Status {
        SUCCESS, INVALID_REQUEST, UNKNOWN_ACTIVITY, UNKNOWN_CAREER, READ_ONLY, DUPLICATE, COOLDOWN, RATE_LIMIT,
        OVERFLOW, STATE_FULL
    }
    public record AwardResult(Status status, long totalXp, boolean committed) {}

    private ActivityXpAwardService() {}

    public static AwardResult award(
            RpgPlayerSavedData state, RpgDefinitionSnapshot definitions, UUID playerId,
            Identifier activityId, long amount, long timestamp, UUID transactionId, String source) {
        return award(state, definitions, playerId, activityId, amount, timestamp, transactionId, source,
                ActivityXpConfig.limits());
    }

    public static AwardResult awardBossReward(
            RpgPlayerSavedData state,
            RpgDefinitionSnapshot definitions,
            UUID playerId,
            Identifier activityId,
            long amount,
            long timestamp,
            UUID transactionId,
            String source) {
        return award(state, definitions, playerId, activityId, amount, timestamp, transactionId, source,
                new ActivityXpConfig.Limits(
                        Integer.MAX_VALUE, RpgPlayerState.MAX_PROVENANCE, 0L, 0L, Integer.MAX_VALUE));
    }

    static AwardResult award(
            RpgPlayerSavedData state, RpgDefinitionSnapshot definitions, UUID playerId,
            Identifier activityId, long amount, long timestamp, UUID transactionId, String source,
            ActivityXpConfig.Limits limits) {
        if (state == null || definitions == null || playerId == null || playerId.equals(new UUID(0, 0))
                || activityId == null || transactionId == null || transactionId.equals(new UUID(0, 0))
                || source == null || source.isBlank() || source.length() > 160 || amount < 1 || timestamp < 0
                || limits == null || limits.maxAward() < 1 || limits.maxWindowAwards() < 1
                || limits.maxWindowAwards() > RpgPlayerState.MAX_PROVENANCE
                || limits.windowMillis() < 0 || limits.cooldownMillis() < 0
                || limits.combatTargetXpCap() < 1) {
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
        Optional<Identifier> activeCareer = current.activeCareer();
        Optional<CareerDefinition> activeCareerDefinition = activeCareer.flatMap(definitions::career);
        if (activeCareer.isPresent() && activeCareerDefinition.isEmpty()) {
            return new AwardResult(Status.UNKNOWN_CAREER,
                    current.activityXp().getOrDefault(activityId, 0L), false);
        }
        Optional<UUID> careerTransactionId = activeCareer.map(careerId -> careerTransactionId(
                transactionId, careerId));
        if (current.careerProvenance().stream().anyMatch(entry ->
                entry.transactionId().equals(transactionId)
                        || careerTransactionId.filter(entry.transactionId()::equals).isPresent())) {
            return new AwardResult(Status.DUPLICATE,
                    current.activityXp().getOrDefault(activityId, 0L), false);
        }
        Optional<Identifier> discovery = activityId.equals(EXPLORATION)
                ? explorationDiscovery(source)
                : Optional.empty();
        if (activityId.equals(EXPLORATION) && discovery.isEmpty()) {
            return new AwardResult(Status.INVALID_REQUEST, current.activityXp().getOrDefault(activityId, 0L), false);
        }
        if (discovery.filter(current.explorationDiscoveries()::contains).isPresent()) {
            return new AwardResult(Status.DUPLICATE, current.activityXp().getOrDefault(activityId, 0L), false);
        }
        long windowStart = timestamp <= limits.windowMillis() ? 0 : timestamp - limits.windowMillis();
        int windowAwards = 0;
        long sourceXp = 0;
        long total = current.activityXp().getOrDefault(activityId, 0L);
        for (RpgPlayerState.ProgressionProvenance entry : current.provenance()) {
            if (entry.transactionId().equals(transactionId)
                    || careerTransactionId.filter(entry.transactionId()::equals).isPresent()) {
                return new AwardResult(Status.DUPLICATE, total, false);
            }
            if (entry.kind() != RpgPlayerState.ProgressionProvenance.Kind.ACTIVITY_XP) {
                continue;
            }
            if (entry.target().equals(activityId) && entry.source().equals(source)) {
                sourceXp += entry.amount();
                if (timestamp <= entry.timestamp()
                        || timestamp - entry.timestamp() < limits.cooldownMillis()) {
                    return new AwardResult(Status.COOLDOWN, total, false);
                }
            }
            if (entry.timestamp() >= windowStart && entry.timestamp() <= timestamp) {
                windowAwards++;
            }
        }
        if (windowAwards >= limits.maxWindowAwards()) {
            return new AwardResult(Status.RATE_LIMIT, total, false);
        }
        if (activityId.equals(COMBAT)
                && (sourceXp >= limits.combatTargetXpCap()
                        || amount > limits.combatTargetXpCap() - sourceXp)) {
            return new AwardResult(Status.RATE_LIMIT, total, false);
        }
        final long updated;
        try {
            updated = Math.addExact(total, amount);
        } catch (ArithmeticException exception) {
            return new AwardResult(Status.OVERFLOW, total, false);
        }
        if (updated > RpgPlayerState.MAX_XP) {
            return new AwardResult(Status.OVERFLOW, total, false);
        }
        RpgPlayerState.ProgressionProvenance activityEvidence = new RpgPlayerState.ProgressionProvenance(
                RpgPlayerState.ProgressionProvenance.Kind.ACTIVITY_XP,
                activityId, amount, timestamp, transactionId, source);
        List<RpgPlayerState.ProgressionProvenance> careerEvidence = List.of();
        var activityXp = new java.util.HashMap<>(current.activityXp());
        activityXp.put(activityId, updated);
        var careers = new java.util.HashMap<>(current.careers());
        if (activeCareer.isPresent()) {
            Identifier careerId = activeCareer.orElseThrow();
            RpgPlayerState.CareerProgress progress = careers.get(careerId);
            if (progress == null) {
                return new AwardResult(Status.UNKNOWN_CAREER, total, false);
            }
            final long careerAward;
            final long careerExperience;
            try {
                careerAward = Math.multiplyExact(amount, activeCareerDefinition.orElseThrow().careerXpMultiplier());
                careerExperience = Math.addExact(progress.experience(), careerAward);
            } catch (ArithmeticException exception) {
                return new AwardResult(Status.OVERFLOW, total, false);
            }
            if (careerExperience > RpgPlayerState.MAX_XP) {
                return new AwardResult(Status.OVERFLOW, total, false);
            }
            int rank = Math.max(progress.rank(), CareerProgressionService.levelForXp(
                    careerExperience, activeCareerDefinition.orElseThrow().levelXp()));
            final int skillPoints;
            try {
                skillPoints = Math.addExact(progress.skillPoints(), rank - progress.rank());
            } catch (ArithmeticException exception) {
                return new AwardResult(Status.OVERFLOW, total, false);
            }
            if (skillPoints > RpgPlayerState.MAX_SKILL_POINTS) {
                return new AwardResult(Status.OVERFLOW, total, false);
            }
            careers.put(careerId, new RpgPlayerState.CareerProgress(
                    careerExperience, rank, skillPoints, progress.learnedSkills()));
            careerEvidence = List.of(new RpgPlayerState.ProgressionProvenance(
                    RpgPlayerState.ProgressionProvenance.Kind.CAREER_XP,
                    careerId, careerAward, timestamp, careerTransactionId.orElseThrow(), source));
        }
        List<RpgPlayerState.ProgressionProvenance> provenance = CareerProgressionService.appendEvidence(
                CareerProgressionService.activityEvidence(current), activityEvidence);
        List<RpgPlayerState.ProgressionProvenance> careerProvenance =
                CareerProgressionService.appendCareerEvidence(
                        current, careerEvidence.toArray(RpgPlayerState.ProgressionProvenance[]::new));
        var discoveries = new HashSet<>(current.explorationDiscoveries());
        if (discovery.isPresent()
                && discoveries.size() >= RpgPlayerState.MAX_EXPLORATION_DISCOVERIES) {
            return new AwardResult(Status.STATE_FULL, total, false);
        }
        discovery.ifPresent(discoveries::add);
        RpgPlayerState candidate = new RpgPlayerState(activityXp, careers, current.activeCareer(),
                current.activeSkillSlots(), current.cooldowns(), discoveries, provenance, careerProvenance,
                current.lastActiveSkillRequestId());
        boolean committed = state.commit(playerId, candidate);
        return new AwardResult(committed ? Status.SUCCESS : Status.STATE_FULL, committed ? updated : total, committed);
    }

    public static EvidencePage evidence(
            RpgPlayerSavedData state,
            UUID playerId,
            Optional<Identifier> activityId,
            int page,
            int pageSize) {
        if (state == null || playerId == null || playerId.equals(new UUID(0, 0))
                || activityId == null || page < 0
                || pageSize < 1 || pageSize > MAX_EVIDENCE_PAGE_SIZE) {
            return new EvidencePage(0, 0, 0, List.of());
        }
        List<RpgPlayerState.ProgressionProvenance> matches = state.state(playerId).provenance().stream()
                .filter(entry -> entry.kind() == RpgPlayerState.ProgressionProvenance.Kind.ACTIVITY_XP)
                .filter(entry -> activityId.map(id -> id.equals(entry.target())).orElse(true))
                .sorted(Comparator.comparingLong(RpgPlayerState.ProgressionProvenance::timestamp).reversed()
                        .thenComparing(RpgPlayerState.ProgressionProvenance::transactionId))
                .toList();
        int totalEntries = matches.size();
        int totalPages = totalEntries == 0 ? 0 : (totalEntries + pageSize - 1) / pageSize;
        if (page >= totalPages) {
            return new EvidencePage(page, totalPages, totalEntries, List.of());
        }
        int from = page * pageSize;
        int to = Math.min(from + pageSize, totalEntries);
        return new EvidencePage(page, totalPages, totalEntries, matches.subList(from, to));
    }

    public record EvidencePage(
            int page,
            int totalPages,
            int totalEntries,
            List<RpgPlayerState.ProgressionProvenance> entries) {
        public EvidencePage {
            entries = List.copyOf(entries);
        }
    }

    private static Optional<Identifier> explorationDiscovery(String source) {
        String prefix = "exploration:";
        if (!source.startsWith(prefix) || source.length() == prefix.length()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Identifier.parse(source.substring(prefix.length())));
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    private static UUID careerTransactionId(UUID transactionId, Identifier careerId) {
        return UUID.nameUUIDFromBytes(("rovenfall:career_xp:" + transactionId + ":" + careerId)
                .getBytes(StandardCharsets.UTF_8));
    }
}
