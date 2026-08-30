package org.dldyou.rovenfall.administration;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import org.dldyou.rovenfall.mobs.BossEncounterSavedData;
import org.dldyou.rovenfall.mobs.BossEncounterRuntime;
import org.dldyou.rovenfall.mobs.BossEncounterState;
import org.dldyou.rovenfall.mobs.BossRewardOperation;
import org.dldyou.rovenfall.mobs.BossRewardSavedData;
import org.dldyou.rovenfall.mobs.MobMutationRuntime;

/** Bounded immutable projections of live mob and durable boss state. */
public final class BossAdministrationViewService {
    public static final int MAX_PAGE_SIZE = 50;
    public static final int MAX_MUTATION_SCAN_ENTITIES = 50_000;
    public static final int MAX_MUTATION_ROWS = 10_000;
    public static final int MAX_PARTICIPANT_ROWS = BossEncounterState.MAX_CONTRIBUTORS;
    public static final int MAX_REWARD_SCAN_ROWS = BossRewardSavedData.MAX_OPERATIONS;

    private BossAdministrationViewService() {
    }

    public static Page<MutationRow> activeMutations(MinecraftServer server, int page, int pageSize) {
        return activeMutations(server, "", page, pageSize);
    }

    public static Page<MutationRow> activeMutations(
            MinecraftServer server, String query, int page, int pageSize) {
        String needle = validatedNeedle(query, page, pageSize);
        List<MutationRow> rows = new ArrayList<>();
        int scanned = 0;
        boolean truncated = false;
        scan:
        for (var level : server.getAllLevels()) {
            for (var entity : level.getAllEntities()) {
                if (scanned++ >= MAX_MUTATION_SCAN_ENTITIES) {
                    truncated = true;
                    break scan;
                }
                if (entity instanceof Mob mob) {
                    List<Identifier> mutations = MobMutationRuntime.mutationIds(mob);
                    if (mutations.isEmpty()) {
                        continue;
                    }
                    MutationRow row = new MutationRow(
                            mob.getUUID(), EntityType.getKey(mob.getType()),
                            level.dimension().identifier(), mob.blockPosition(), mutations);
                    if (matches(needle, mutationSearchText(row))) {
                        if (rows.size() >= MAX_MUTATION_ROWS) {
                            truncated = true;
                            break scan;
                        }
                        rows.add(row);
                    }
                }
            }
        }
        rows.sort(Comparator.comparing(MutationRow::dimension)
                .thenComparing(MutationRow::entityId));
        return page(rows, page, pageSize, truncated);
    }

    public static Page<EncounterRow> encounters(MinecraftServer server, int page, int pageSize) {
        return encounters(server, "", page, pageSize);
    }

    public static Page<EncounterRow> encounters(
            MinecraftServer server, String query, int page, int pageSize) {
        List<EncounterRow> rows = BossEncounterSavedData.get(server).activeEncounters().stream()
                .map(encounter -> encounterRow(server, encounter))
                .toList();
        return searchPage(rows, query, page, pageSize, BossAdministrationViewService::encounterSearchText);
    }

    public static Optional<EncounterRow> encounter(MinecraftServer server, UUID encounterId) {
        return BossEncounterSavedData.get(server).encounter(encounterId)
                .map(encounter -> encounterRow(server, encounter));
    }

    public static Page<ParticipantRow> participants(
            MinecraftServer server, UUID encounterId, int page, int pageSize) {
        return participants(server, encounterId, "", page, pageSize);
    }

    public static Page<ParticipantRow> participants(
            MinecraftServer server, UUID encounterId, String query, int page, int pageSize) {
        BossEncounterState encounter = BossEncounterSavedData.get(server).encounter(encounterId).orElse(null);
        if (encounter == null) {
            return searchPage(List.<ParticipantRow>of(), query, page, pageSize, value -> "");
        }
        long total = encounter.contributions().values().stream().mapToLong(Long::longValue).sum();
        List<ParticipantRow> rows = encounter.contributions().entrySet().stream()
                .sorted(Map.Entry.<UUID, Long>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .map(entry -> new ParticipantRow(entry.getKey(), entry.getValue(), total))
                .limit(MAX_PARTICIPANT_ROWS)
                .toList();
        return searchPage(rows, query, page, pageSize,
                row -> row.playerId() + " " + playerDisplayName(server, row.playerId()));
    }

    public static Page<RewardRow> rewards(
            MinecraftServer server, UUID encounterId, int page, int pageSize) {
        return rewards(server, encounterId, "", page, pageSize);
    }

    public static Page<RewardRow> rewards(
            MinecraftServer server, UUID encounterId, String query, int page, int pageSize) {
        String needle = validatedNeedle(query, page, pageSize);
        BossRewardSavedData rewards = BossRewardSavedData.get(server);
        long offset = (long) page * pageSize;
        List<RewardRow> entries = new ArrayList<>(pageSize);
        int total = 0;
        int scanned = 0;
        UUID cursor = null;
        boolean truncated = false;
        boolean more;
        do {
            var batch = rewards.operationsAfter(cursor, BossRewardSavedData.MAX_OPERATION_BATCH_SIZE);
            for (var entry : batch.entries()) {
                if (scanned++ >= MAX_REWARD_SCAN_ROWS) {
                    truncated = true;
                    break;
                }
                BossRewardOperation operation = entry.getValue();
                if (!operation.encounterId().equals(encounterId)) {
                    continue;
                }
                RewardRow row = rewardRow(entry.getKey(), operation);
                if (!matches(needle, rewardSearchText(server, row))) {
                    continue;
                }
                if (total >= offset && entries.size() < pageSize) {
                    entries.add(row);
                }
                total++;
            }
            cursor = batch.nextCursor().orElse(null);
            more = batch.hasMore() && !truncated;
        } while (more);
        return windowPage(page, pageSize, total, entries, truncated);
    }

    public static Page<CooldownRow> cooldowns(
            MinecraftServer server, UUID playerId, int page, int pageSize) {
        Map<Identifier, CooldownRow> latest = new LinkedHashMap<>();
        for (var entry : BossRewardSavedData.get(server).operations()) {
            BossRewardOperation operation = entry.getValue();
            if (!operation.playerId().equals(playerId)
                    || operation.phase() == BossRewardOperation.Phase.FAILED) {
                continue;
            }
            CooldownRow candidate = new CooldownRow(
                    operation.bossId(), entry.getKey(), operation.cooldownUntilEpochMillis(), operation.phase());
            latest.merge(operation.bossId(), candidate,
                    (left, right) -> left.deadlineEpochMillis() >= right.deadlineEpochMillis() ? left : right);
        }
        List<CooldownRow> rows = latest.values().stream()
                .sorted(Comparator.comparing(CooldownRow::bossId))
                .toList();
        return page(rows, page, pageSize);
    }

    private static EncounterRow encounterRow(MinecraftServer server, BossEncounterState encounter) {
        boolean arenaProtected = PlatformSavedData.get(server)
                .protectedRegion(BossEncounterRuntime.regionId(encounter.encounterId()))
                .filter(encounter.reservation()::equals)
                .isPresent();
        return new EncounterRow(
                encounter.encounterId(), encounter.bossId(), encounter.entityId(),
                encounter.dimension().identifier(), encounter.center(), encounter.stage(),
                encounter.phaseIndex(), encounter.patternId(), encounter.contributions().size(),
                arenaProtected, encounter.startedAtEpochMillis(), encounter.lastParticipantAtEpochMillis());
    }

    private static RewardRow rewardRow(UUID transactionId, BossRewardOperation operation) {
        return new RewardRow(
                transactionId, operation.playerId(), operation.playerPoints(), operation.totalPoints(),
                operation.currency(), operation.experience(), operation.items().size(), operation.phase(),
                operation.cooldownUntilEpochMillis());
    }

    private static String mutationSearchText(MutationRow row) {
        return row.entityId() + " " + row.entityType() + " " + row.dimension() + " "
                + row.position().toShortString() + " " + row.mutations();
    }

    private static String encounterSearchText(EncounterRow row) {
        return row.encounterId() + " " + row.bossId() + " " + row.entityId() + " "
                + row.dimension() + " " + row.center().toShortString() + " " + row.stage().getSerializedName()
                + " " + row.patternId().map(Identifier::toString).orElse("");
    }

    private static String rewardSearchText(MinecraftServer server, RewardRow row) {
        return row.transactionId() + " " + row.playerId() + " "
                + playerDisplayName(server, row.playerId()) + " " + row.phase().getSerializedName();
    }

    private static String playerDisplayName(MinecraftServer server, UUID playerId) {
        return PlatformSavedData.get(server).playerRecord(playerId)
                .flatMap(PlayerRecord::displayName)
                .orElseGet(() -> {
                    var online = server.getPlayerList().getPlayer(playerId);
                    return online == null ? "" : online.getGameProfile().name();
                });
    }

    static <T> Page<T> searchPage(
            List<T> values, String query, int page, int pageSize, Function<T, String> searchText) {
        String needle = validatedNeedle(query, page, pageSize);
        return page(values.stream()
                .filter(value -> matches(needle, searchText.apply(value)))
                .toList(), page, pageSize);
    }

    private static String validatedNeedle(String query, int page, int pageSize) {
        if (query == null || query.length() > AdministrationReadViewService.MAX_QUERY_LENGTH
                || page < 0 || pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("Invalid boss administration search request");
        }
        return query.strip().toLowerCase(Locale.ROOT);
    }

    private static boolean matches(String needle, String value) {
        return needle.isEmpty() || value.toLowerCase(Locale.ROOT).contains(needle);
    }

    static <T> Page<T> page(List<T> values, int page, int pageSize) {
        return page(values, page, pageSize, false);
    }

    private static <T> Page<T> windowPage(
            int page, int pageSize, int total, List<T> entries, boolean truncated) {
        int totalPages = total == 0 ? 0 : (total + pageSize - 1) / pageSize;
        return new Page<>(page, totalPages, total, entries, truncated);
    }

    private static <T> Page<T> page(List<T> values, int page, int pageSize, boolean truncated) {
        if (page < 0 || pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("Invalid boss administration page request");
        }
        int total = values.size();
        int totalPages = total == 0 ? 0 : (total + pageSize - 1) / pageSize;
        long offset = (long) page * pageSize;
        List<T> entries = offset >= total ? List.of()
                : List.copyOf(values.subList((int) offset, Math.min(total, (int) offset + pageSize)));
        return new Page<>(page, totalPages, total, entries, truncated);
    }

    public record Page<T>(
            int page, int totalPages, int totalEntries, List<T> entries, boolean truncated) {
        public Page {
            entries = List.copyOf(entries);
        }
    }

    public record MutationRow(
            UUID entityId, Identifier entityType, Identifier dimension, BlockPos position,
            List<Identifier> mutations) {
        public MutationRow {
            position = position.immutable();
            mutations = List.copyOf(mutations);
        }
    }

    public record EncounterRow(
            UUID encounterId, Identifier bossId, UUID entityId, Identifier dimension, BlockPos center,
            BossEncounterState.Stage stage, int phaseIndex, Optional<Identifier> patternId,
            int participantCount, boolean arenaProtected,
            long startedAtEpochMillis, long lastParticipantAtEpochMillis) {
    }

    public record ParticipantRow(UUID playerId, long points, long totalPoints) {
    }

    public record RewardRow(
            UUID transactionId, UUID playerId, long points, long totalPoints, long currency,
            long experience, int itemStacks, BossRewardOperation.Phase phase, long cooldownUntilEpochMillis) {
    }

    public record CooldownRow(
            Identifier bossId, UUID transactionId, long deadlineEpochMillis, BossRewardOperation.Phase phase) {
    }
}
