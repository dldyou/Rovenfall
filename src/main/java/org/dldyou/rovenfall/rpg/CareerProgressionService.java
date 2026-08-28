package org.dldyou.rovenfall.rpg;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.Identifier;

/** Server-authoritative mutation boundary for career promotion and active-branch switching. */
public final class CareerProgressionService {
    public static final int MAX_HISTORY_PAGE_SIZE = 50;
    public enum Status {
        SUCCESS,
        INVALID_REQUEST,
        UNKNOWN_CAREER,
        READ_ONLY,
        DUPLICATE,
        ALREADY_PROMOTED,
        ALREADY_ACTIVE,
        CAREER_NOT_PROMOTED,
        MISSING_PARENT,
        PARENT_RANK_TOO_LOW,
        ACTIVITY_LEVEL_TOO_LOW,
        STATE_FULL
    }

    public record Result(
            Status status,
            Identifier career,
            Optional<Identifier> blocker,
            int requiredLevel,
            int actualLevel,
            UUID transactionId,
            boolean committed) {
        public Result {
            blocker = blocker == null ? Optional.empty() : blocker;
        }
    }

    private static final UUID ZERO_UUID = new UUID(0L, 0L);

    private CareerProgressionService() {
    }

    public static Result promote(
            RpgPlayerSavedData state,
            RpgDefinitionSnapshot definitions,
            UUID playerId,
            Identifier careerId,
            long timestamp,
            UUID transactionId,
            String source) {
        return promote(state, definitions, playerId, careerId, timestamp, transactionId, source, List.of());
    }

    public static Result promote(
            RpgPlayerSavedData state,
            RpgDefinitionSnapshot definitions,
            UUID playerId,
            Identifier careerId,
            long timestamp,
            UUID transactionId,
            String source,
            List<RpgItemCost> itemCosts) {
        return promote(state, definitions, playerId, careerId, timestamp, transactionId, source,
                itemCosts, List.of(), List.of());
    }

    public static Result promote(
            RpgPlayerSavedData state,
            RpgDefinitionSnapshot definitions,
            UUID playerId,
            Identifier careerId,
            long timestamp,
            UUID transactionId,
            String source,
            List<RpgItemCost> itemCosts,
            List<Long> itemCountsBefore,
            List<Long> itemCountsAfter) {
        Result invalid = validateRequest(state, definitions, playerId, careerId, timestamp, transactionId, source);
        if (invalid != null) {
            return invalid;
        }
        if (itemCosts == null || itemCosts.size() > RpgItemCost.MAX_ENTRIES
                || itemCountsBefore == null || itemCountsAfter == null) {
            return result(Status.INVALID_REQUEST, careerId, transactionId);
        }
        Optional<CareerDefinition> careerDefinition = definitions.career(careerId);
        if (careerDefinition.isEmpty()) {
            return result(Status.UNKNOWN_CAREER, careerId, transactionId);
        }
        if (!state.isWritable()) {
            return result(Status.READ_ONLY, careerId, transactionId);
        }

        RpgPlayerState current = state.state(playerId);
        if (hasTransaction(current, transactionId)) {
            return result(Status.DUPLICATE, careerId, transactionId);
        }
        if (current.careers().containsKey(careerId)) {
            return result(Status.ALREADY_PROMOTED, careerId, transactionId);
        }
        if (current.careers().size() >= RpgPlayerState.MAX_CAREERS) {
            return result(Status.STATE_FULL, careerId, transactionId);
        }

        CareerDefinition definition = careerDefinition.orElseThrow();
        for (Identifier parentId : definition.parents()) {
            RpgPlayerState.CareerProgress parentProgress = current.careers().get(parentId);
            if (parentProgress == null) {
                return blocked(Status.MISSING_PARENT, careerId, parentId, 1, 0, transactionId);
            }
            Optional<CareerDefinition> parentDefinition = definitions.career(parentId);
            if (parentDefinition.isEmpty()) {
                return blocked(Status.UNKNOWN_CAREER, careerId, parentId, 0, 0, transactionId);
            }
            int requiredRank = parentDefinition.orElseThrow().levelXp().size();
            int actualRank = Math.min(parentProgress.rank(), levelForXp(
                    parentProgress.experience(), parentDefinition.orElseThrow().levelXp()));
            if (actualRank < requiredRank) {
                return blocked(Status.PARENT_RANK_TOO_LOW, careerId, parentId,
                        requiredRank, actualRank, transactionId);
            }
        }
        for (CareerDefinition.ActivityRequirement requirement : definition.requiredActivities()) {
            Optional<ActivityDefinition> activity = definitions.activity(requirement.activity());
            if (activity.isEmpty()) {
                return blocked(Status.ACTIVITY_LEVEL_TOO_LOW, careerId, requirement.activity(),
                        requirement.level(), 0, transactionId);
            }
            int actualLevel = levelForXp(
                    current.activityXp().getOrDefault(requirement.activity(), 0L),
                    activity.orElseThrow().levelXp());
            if (actualLevel < requirement.level()) {
                return blocked(Status.ACTIVITY_LEVEL_TOO_LOW, careerId, requirement.activity(),
                        requirement.level(), actualLevel, transactionId);
            }
        }

        Map<Identifier, RpgPlayerState.CareerProgress> careers = new HashMap<>(current.careers());
        careers.put(careerId, new RpgPlayerState.CareerProgress(0, 0, 0, Map.of()));
        List<RpgPlayerState.ProgressionProvenance> careerProvenance = appendCareerEvidence(
                current, new RpgPlayerState.ProgressionProvenance(
                        RpgPlayerState.ProgressionProvenance.Kind.CAREER_PROMOTION,
                        careerId, definition.tier(), timestamp, transactionId, source,
                        current.activeCareer(), itemCosts, itemCountsBefore, itemCountsAfter, Optional.empty()));
        RpgPlayerState candidate = new RpgPlayerState(
                current.activityXp(), careers, Optional.of(careerId), current.activeSkillSlots(),
                current.cooldowns(), current.explorationDiscoveries(), activityEvidence(current), careerProvenance,
                current.lastActiveSkillRequestId());
        boolean committed = state.commit(playerId, candidate);
        if (committed) {
            RpgActiveSkillRuntime.clear(playerId);
        }
        return result(committed ? Status.SUCCESS : Status.STATE_FULL, careerId, transactionId, committed);
    }

    public static Result switchActive(
            RpgPlayerSavedData state,
            RpgDefinitionSnapshot definitions,
            UUID playerId,
            Identifier careerId,
            long timestamp,
            UUID transactionId,
            String source) {
        Result invalid = validateRequest(state, definitions, playerId, careerId, timestamp, transactionId, source);
        if (invalid != null) {
            return invalid;
        }
        if (definitions.career(careerId).isEmpty()) {
            return result(Status.UNKNOWN_CAREER, careerId, transactionId);
        }
        if (!state.isWritable()) {
            return result(Status.READ_ONLY, careerId, transactionId);
        }

        RpgPlayerState current = state.state(playerId);
        if (hasTransaction(current, transactionId)) {
            return result(Status.DUPLICATE, careerId, transactionId);
        }
        if (!current.careers().containsKey(careerId)) {
            return result(Status.CAREER_NOT_PROMOTED, careerId, transactionId);
        }
        if (current.activeCareer().filter(careerId::equals).isPresent()) {
            return result(Status.ALREADY_ACTIVE, careerId, transactionId);
        }

        List<RpgPlayerState.ProgressionProvenance> careerProvenance = appendCareerEvidence(
                current, new RpgPlayerState.ProgressionProvenance(
                        RpgPlayerState.ProgressionProvenance.Kind.CAREER_SWITCH,
                        careerId, 0, timestamp, transactionId, source, current.activeCareer()));
        RpgPlayerState candidate = new RpgPlayerState(
                current.activityXp(), current.careers(), Optional.of(careerId), current.activeSkillSlots(),
                current.cooldowns(), current.explorationDiscoveries(), activityEvidence(current), careerProvenance,
                current.lastActiveSkillRequestId());
        boolean committed = state.commit(playerId, candidate);
        if (committed) {
            RpgActiveSkillRuntime.clear(playerId);
        }
        return result(committed ? Status.SUCCESS : Status.STATE_FULL, careerId, transactionId, committed);
    }

    static int levelForXp(long experience, List<Long> thresholds) {
        int level = 0;
        while (level < thresholds.size() && experience >= thresholds.get(level)) {
            level++;
        }
        return level;
    }

    static List<RpgPlayerState.ProgressionProvenance> appendEvidence(
            List<RpgPlayerState.ProgressionProvenance> current,
            RpgPlayerState.ProgressionProvenance... entries) {
        List<RpgPlayerState.ProgressionProvenance> result = new ArrayList<>(current);
        result.addAll(List.of(entries));
        while (result.size() > RpgPlayerState.MAX_PROVENANCE) {
            result.removeFirst();
        }
        return result;
    }

    static List<RpgPlayerState.ProgressionProvenance> activityEvidence(RpgPlayerState state) {
        return state.provenance().stream()
                .filter(entry -> entry.kind() == RpgPlayerState.ProgressionProvenance.Kind.ACTIVITY_XP
                        || entry.kind() == RpgPlayerState.ProgressionProvenance.Kind.ADMIN_ACTIVITY_XP)
                .toList();
    }

    static List<RpgPlayerState.ProgressionProvenance> appendCareerEvidence(
            RpgPlayerState state,
            RpgPlayerState.ProgressionProvenance... entries) {
        List<RpgPlayerState.ProgressionProvenance> result = new ArrayList<>();
        state.provenance().stream()
                .filter(entry -> entry.kind() != RpgPlayerState.ProgressionProvenance.Kind.ACTIVITY_XP
                        && entry.kind() != RpgPlayerState.ProgressionProvenance.Kind.ADMIN_ACTIVITY_XP)
                .forEach(result::add);
        result.addAll(state.careerProvenance());
        result.addAll(List.of(entries));
        while (result.size() > RpgPlayerState.MAX_CAREER_PROVENANCE) {
            result.removeFirst();
        }
        return result;
    }

    /** Bounded operator-facing view of authoritative promotion and switch evidence. */
    public static HistoryPage history(
            RpgPlayerSavedData state,
            UUID playerId,
            Optional<Identifier> careerId,
            int page,
            int pageSize) {
        if (state == null || playerId == null || ZERO_UUID.equals(playerId) || careerId == null
                || page < 0 || pageSize < 1 || pageSize > MAX_HISTORY_PAGE_SIZE) {
            return new HistoryPage(0, 0, 0, List.of());
        }
        RpgPlayerState player = state.state(playerId);
        List<RpgPlayerState.ProgressionProvenance> matches = java.util.stream.Stream.concat(
                        player.provenance().stream(), player.careerProvenance().stream())
                .filter(entry -> entry.kind() == RpgPlayerState.ProgressionProvenance.Kind.CAREER_PROMOTION
                        || entry.kind() == RpgPlayerState.ProgressionProvenance.Kind.CAREER_SWITCH
                        || entry.kind() == RpgPlayerState.ProgressionProvenance.Kind.ADMIN_PROMOTION)
                .filter(entry -> careerId.map(entry.target()::equals).orElse(true))
                .sorted(Comparator.comparingLong(RpgPlayerState.ProgressionProvenance::timestamp).reversed()
                        .thenComparing(RpgPlayerState.ProgressionProvenance::transactionId))
                .toList();
        int totalEntries = matches.size();
        int totalPages = totalEntries == 0 ? 0 : (totalEntries + pageSize - 1) / pageSize;
        if (page >= totalPages) {
            return new HistoryPage(page, totalPages, totalEntries, List.of());
        }
        int from = page * pageSize;
        return new HistoryPage(page, totalPages, totalEntries,
                matches.subList(from, Math.min(from + pageSize, totalEntries)));
    }

    public record HistoryPage(
            int page,
            int totalPages,
            int totalEntries,
            List<RpgPlayerState.ProgressionProvenance> entries) {
        public HistoryPage {
            entries = List.copyOf(entries);
        }
    }

    private static Result validateRequest(
            RpgPlayerSavedData state,
            RpgDefinitionSnapshot definitions,
            UUID playerId,
            Identifier careerId,
            long timestamp,
            UUID transactionId,
            String source) {
        if (state == null || definitions == null || playerId == null || ZERO_UUID.equals(playerId)
                || careerId == null || timestamp < 0 || transactionId == null || ZERO_UUID.equals(transactionId)
                || source == null || source.isBlank() || source.length() > 160) {
            return result(Status.INVALID_REQUEST, careerId, transactionId);
        }
        return null;
    }

    private static boolean hasTransaction(RpgPlayerState state, UUID transactionId) {
        return java.util.stream.Stream.concat(state.provenance().stream(), state.careerProvenance().stream())
                .anyMatch(entry -> entry.transactionId().equals(transactionId));
    }

    private static Result blocked(
            Status status,
            Identifier careerId,
            Identifier blocker,
            int requiredLevel,
            int actualLevel,
            UUID transactionId) {
        return new Result(status, careerId, Optional.of(blocker), requiredLevel, actualLevel,
                transactionId, false);
    }

    private static Result result(Status status, Identifier careerId, UUID transactionId) {
        return result(status, careerId, transactionId, false);
    }

    private static Result result(Status status, Identifier careerId, UUID transactionId, boolean committed) {
        return new Result(status, careerId, Optional.empty(), 0, 0, transactionId, committed);
    }
}
