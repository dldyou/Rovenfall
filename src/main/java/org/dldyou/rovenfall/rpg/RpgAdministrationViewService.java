package org.dldyou.rovenfall.rpg;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.resources.Identifier;

/** Bounded read models for operator-facing RPG diagnostics. Authorization belongs to the admin adapter. */
public final class RpgAdministrationViewService {
    public static final int MAX_PAGE_SIZE = 50;
    private static final UUID ZERO_UUID = new UUID(0L, 0L);
    private static final Identifier COMBAT = Identifier.fromNamespaceAndPath("rovenfall", "combat");

    public enum EntryKind {
        ACTIVITY,
        CAREER,
        SKILL,
        SLOT,
        COOLDOWN
    }

    public enum Suspicion {
        AWARD_TOO_LARGE,
        SOURCE_COOLDOWN,
        WINDOW_RATE,
        COMBAT_SOURCE_CAP
    }

    public record ProgressionEntry(
            EntryKind kind,
            Identifier id,
            Optional<Identifier> owner,
            long value,
            int rank,
            int points) {
        public ProgressionEntry {
            owner = owner == null ? Optional.empty() : owner;
        }
    }

    public record ProgressionPage(
            UUID playerId,
            Optional<Identifier> activeCareer,
            int page,
            int totalPages,
            int totalEntries,
            List<ProgressionEntry> entries) {
        public ProgressionPage {
            activeCareer = activeCareer == null ? Optional.empty() : activeCareer;
            entries = List.copyOf(entries);
        }
    }

    public record AwardEvidence(
            RpgPlayerState.ProgressionProvenance evidence,
            Set<Suspicion> suspicions) {
        public AwardEvidence {
            suspicions = suspicions == null || suspicions.isEmpty()
                    ? Set.of()
                    : Set.copyOf(suspicions);
        }
    }

    public record AwardPage(
            int page,
            int totalPages,
            int totalEntries,
            List<AwardEvidence> entries) {
        public AwardPage {
            entries = List.copyOf(entries);
        }
    }

    private RpgAdministrationViewService() {
    }

    public static ProgressionPage progression(
            RpgPlayerSavedData state,
            RpgDefinitionSnapshot definitions,
            UUID playerId,
            int page,
            int pageSize) {
        if (state == null || definitions == null || playerId == null || ZERO_UUID.equals(playerId)
                || page < 0 || pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            return new ProgressionPage(playerId, Optional.empty(), 0, 0, 0, List.of());
        }
        RpgPlayerState player = state.state(playerId);
        List<ProgressionEntry> entries = new ArrayList<>();
        player.activityXp().forEach((activity, xp) -> entries.add(new ProgressionEntry(
                EntryKind.ACTIVITY,
                activity,
                Optional.empty(),
                xp,
                definitions.activity(activity)
                        .map(value -> CareerProgressionService.levelForXp(xp, value.levelXp()))
                        .orElse(-1),
                0)));
        player.careers().forEach((career, progress) -> {
            entries.add(new ProgressionEntry(
                    EntryKind.CAREER,
                    career,
                    Optional.empty(),
                    progress.experience(),
                    progress.rank(),
                    progress.skillPoints()));
            progress.learnedSkills().forEach((skill, rank) -> entries.add(new ProgressionEntry(
                    EntryKind.SKILL,
                    skill,
                    Optional.of(career),
                    0,
                    rank,
                    0)));
        });
        player.activeSkillSlots().forEach((slot, skill) -> entries.add(new ProgressionEntry(
                EntryKind.SLOT, skill, Optional.empty(), slot + 1L, 0, 0)));
        player.cooldowns().forEach((skill, deadline) -> entries.add(new ProgressionEntry(
                EntryKind.COOLDOWN, skill, Optional.empty(), deadline, 0, 0)));
        entries.sort(Comparator.comparing(ProgressionEntry::kind)
                .thenComparing(entry -> entry.owner().orElse(Identifier.fromNamespaceAndPath("rovenfall", "_")))
                .thenComparing(ProgressionEntry::id)
                .thenComparingLong(ProgressionEntry::value));
        int totalEntries = entries.size();
        int totalPages = pages(totalEntries, pageSize);
        List<ProgressionEntry> pageEntries = slice(entries, page, pageSize, totalPages);
        return new ProgressionPage(playerId, player.activeCareer(), page, totalPages, totalEntries, pageEntries);
    }

    public static AwardPage awardHistory(
            RpgPlayerSavedData state,
            UUID playerId,
            Optional<Identifier> activity,
            boolean suspiciousOnly,
            int page,
            int pageSize,
            ActivityXpConfig.ConfigSnapshot config) {
        if (state == null || playerId == null || ZERO_UUID.equals(playerId) || activity == null
                || page < 0 || pageSize < 1 || pageSize > MAX_PAGE_SIZE || config == null) {
            return new AwardPage(0, 0, 0, List.of());
        }
        List<RpgPlayerState.ProgressionProvenance> chronological = state.state(playerId).provenance().stream()
                .filter(entry -> entry.kind() == RpgPlayerState.ProgressionProvenance.Kind.ACTIVITY_XP)
                .sorted(Comparator.comparingLong(RpgPlayerState.ProgressionProvenance::timestamp)
                        .thenComparing(RpgPlayerState.ProgressionProvenance::transactionId))
                .toList();
        List<AwardEvidence> evidence = new ArrayList<>();
        for (int index = 0; index < chronological.size(); index++) {
            RpgPlayerState.ProgressionProvenance current = chronological.get(index);
            EnumSet<Suspicion> suspicions = EnumSet.noneOf(Suspicion.class);
            if (current.amount() > config.maxAward()) {
                suspicions.add(Suspicion.AWARD_TOO_LARGE);
            }
            long windowStart = Math.max(0L, current.timestamp() - config.windowMillis());
            long windowCount = chronological.stream()
                    .filter(entry -> entry.timestamp() >= windowStart && entry.timestamp() <= current.timestamp())
                    .count();
            if (windowCount > config.maxWindowAwards()) {
                suspicions.add(Suspicion.WINDOW_RATE);
            }
            for (int previousIndex = index - 1; previousIndex >= 0; previousIndex--) {
                RpgPlayerState.ProgressionProvenance previous = chronological.get(previousIndex);
                if (!previous.target().equals(current.target()) || !previous.source().equals(current.source())) {
                    continue;
                }
                if (current.timestamp() <= previous.timestamp()
                        || current.timestamp() - previous.timestamp() < config.cooldownMillis()) {
                    suspicions.add(Suspicion.SOURCE_COOLDOWN);
                }
                break;
            }
            if (current.target().equals(COMBAT)) {
                long sourceTotal = chronological.subList(0, index + 1).stream()
                        .filter(entry -> entry.target().equals(COMBAT) && entry.source().equals(current.source()))
                        .mapToLong(RpgPlayerState.ProgressionProvenance::amount)
                        .sum();
                if (sourceTotal > config.combatTargetXpCap()) {
                    suspicions.add(Suspicion.COMBAT_SOURCE_CAP);
                }
            }
            if (activity.map(current.target()::equals).orElse(true)
                    && (!suspiciousOnly || !suspicions.isEmpty())) {
                evidence.add(new AwardEvidence(current, suspicions));
            }
        }
        evidence.sort(Comparator.comparingLong(
                        (AwardEvidence entry) -> entry.evidence().timestamp()).reversed()
                .thenComparing(entry -> entry.evidence().transactionId()));
        int totalEntries = evidence.size();
        int totalPages = pages(totalEntries, pageSize);
        return new AwardPage(page, totalPages, totalEntries, slice(evidence, page, pageSize, totalPages));
    }

    private static int pages(int size, int pageSize) {
        return size == 0 ? 0 : (size + pageSize - 1) / pageSize;
    }

    private static <T> List<T> slice(List<T> entries, int page, int pageSize, int totalPages) {
        if (page >= totalPages) {
            return List.of();
        }
        int from = page * pageSize;
        return List.copyOf(entries.subList(from, Math.min(from + pageSize, entries.size())));
    }
}
