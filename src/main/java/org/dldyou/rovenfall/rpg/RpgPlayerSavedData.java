package org.dldyou.rovenfall.rpg;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.time.Duration;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.UnaryOperator;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import org.dldyou.rovenfall.Rovenfall;

/** Persistent RPG state, deliberately separate from the platform/economy root. */
public final class RpgPlayerSavedData extends SavedData {
    private static final UUID ZERO_UUID = new UUID(0L, 0L);
    public static final int CURRENT_SCHEMA_VERSION = 6;
    public static final int MAX_PLAYERS = 100_000;
    public static final int MAX_QUEST_ACTIVITY_EVIDENCE = 250_000;
    public static final int MAX_QUEST_ACTIVITY_EVIDENCE_PER_PLAYER = 4_096;
    public static final int MAX_QUEST_ACTIVITY_EVIDENCE_BATCH_SIZE = 256;
    public static final int MAX_QUEST_REWARD_RECEIPTS = 50_000;
    private static final long QUEST_ACTIVITY_EVIDENCE_RETENTION_MILLIS = Duration.ofDays(30).toMillis();

    private static final Codec<Map<UUID, RpgPlayerState>> PLAYERS_CODEC =
            PlayerEntry.CODEC.listOf(0, MAX_PLAYERS)
                    .flatXmap(RpgPlayerSavedData::playersFromEntries, RpgPlayerSavedData::playersToEntries);
    private static final Codec<Map<UUID, QuestActivityEvidence>> QUEST_ACTIVITY_EVIDENCE_CODEC =
            QuestActivityEvidenceEntry.CODEC.listOf(0, MAX_QUEST_ACTIVITY_EVIDENCE)
                    .flatXmap(RpgPlayerSavedData::questActivityEvidenceFromEntries,
                            RpgPlayerSavedData::questActivityEvidenceToEntries);
    private static final Codec<Map<UUID, QuestRewardReceipt>> QUEST_REWARD_RECEIPTS_CODEC =
            QuestRewardReceiptEntry.CODEC.listOf(0, MAX_QUEST_REWARD_RECEIPTS)
                    .flatXmap(RpgPlayerSavedData::questRewardReceiptsFromEntries,
                            RpgPlayerSavedData::questRewardReceiptsToEntries);

    public static final Codec<RpgPlayerSavedData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.optionalFieldOf("schema_version", 0).forGetter(data -> data.schemaVersion),
            PLAYERS_CODEC.optionalFieldOf("players", Map.of()).forGetter(data -> data.players),
            QUEST_ACTIVITY_EVIDENCE_CODEC.optionalFieldOf("quest_activity_evidence", Map.of())
                    .forGetter(data -> data.questActivityEvidence),
            QUEST_REWARD_RECEIPTS_CODEC.optionalFieldOf("quest_reward_receipts", Map.of())
                    .forGetter(data -> data.questRewardReceipts)
    ).apply(instance, RpgPlayerSavedData::decode));

    public static final SavedDataType<RpgPlayerSavedData> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(Rovenfall.MOD_ID, "rpg_player_state"),
            RpgPlayerSavedData::new,
            CODEC);

    private static final Map<Integer, UnaryOperator<PersistedState>> MIGRATIONS = Map.of(
            0, state -> state.atVersion(1),
            1, state -> state.atVersion(2),
            2, state -> state.atVersion(3),
            3, state -> state.atVersion(4),
            4, state -> state.atVersion(5),
            5, state -> state.atVersion(6));

    private final int schemaVersion;
    private final boolean writable;
    private final NavigableMap<UUID, RpgPlayerState> players;
    private final NavigableMap<UUID, QuestActivityEvidence> questActivityEvidence;
    private final NavigableMap<UUID, QuestRewardReceipt> questRewardReceipts;
    private final Map<UUID, Integer> questActivityEvidenceCountByPlayer = new HashMap<>();
    private final Map<UUID, NavigableSet<UUID>> questActivityEvidenceIdsByPlayer = new HashMap<>();

    public RpgPlayerSavedData() {
        this(CURRENT_SCHEMA_VERSION, Map.of(), Map.of(), Map.of(), true);
    }

    private RpgPlayerSavedData(
            int schemaVersion,
            Map<UUID, RpgPlayerState> players,
            Map<UUID, QuestActivityEvidence> questActivityEvidence,
            Map<UUID, QuestRewardReceipt> questRewardReceipts,
            boolean writable) {
        this.schemaVersion = schemaVersion;
        this.writable = writable;
        this.players = new TreeMap<>(players);
        this.questActivityEvidence = new TreeMap<>(questActivityEvidence);
        this.questRewardReceipts = new TreeMap<>(questRewardReceipts);
        rebuildQuestActivityEvidenceIndex();
    }

    private static RpgPlayerSavedData decode(
            int schemaVersion,
            Map<UUID, RpgPlayerState> players,
            Map<UUID, QuestActivityEvidence> questActivityEvidence,
            Map<UUID, QuestRewardReceipt> questRewardReceipts) {
        PersistedState original = new PersistedState(
                schemaVersion, players, questActivityEvidence, questRewardReceipts);
        if (schemaVersion < 0 || schemaVersion > CURRENT_SCHEMA_VERSION) {
            return new RpgPlayerSavedData(
                    schemaVersion, players, questActivityEvidence, questRewardReceipts, false);
        }
        PersistedState candidate = original;
        while (candidate.schemaVersion() < CURRENT_SCHEMA_VERSION) {
            UnaryOperator<PersistedState> migration = MIGRATIONS.get(candidate.schemaVersion());
            if (migration == null) {
                return new RpgPlayerSavedData(
                        original.schemaVersion(), original.players(), original.questActivityEvidence(),
                        original.questRewardReceipts(), false);
            }
            int expected = candidate.schemaVersion() + 1;
            candidate = migration.apply(candidate);
            if (candidate.schemaVersion() != expected) {
                return new RpgPlayerSavedData(
                        original.schemaVersion(), original.players(), original.questActivityEvidence(),
                        original.questRewardReceipts(), false);
            }
        }
        return new RpgPlayerSavedData(
                candidate.schemaVersion(), candidate.players(), candidate.questActivityEvidence(),
                candidate.questRewardReceipts(), true);
    }

    public static RpgPlayerSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    public int schemaVersion() {
        return schemaVersion;
    }

    public boolean isWritable() {
        return writable;
    }

    public Optional<RpgPlayerState> player(UUID playerId) {
        return Optional.ofNullable(players.get(playerId));
    }

    public RpgPlayerState state(UUID playerId) {
        return players.getOrDefault(playerId, RpgPlayerState.EMPTY);
    }

    public int playerCount() {
        return players.size();
    }

    public int questActivityEvidenceCount() {
        return questActivityEvidence.size();
    }

    public int questActivityEvidenceCount(UUID playerId) {
        return playerId == null ? 0 : questActivityEvidenceCountByPlayer.getOrDefault(playerId, 0);
    }

    public int questRewardReceiptCount() {
        return questRewardReceipts.size();
    }

    public Optional<QuestActivityEvidence> questActivityEvidence(UUID transactionId) {
        return Optional.ofNullable(questActivityEvidence.get(transactionId));
    }

    public Optional<QuestRewardReceipt> questRewardReceipt(UUID transactionId) {
        return Optional.ofNullable(questRewardReceipts.get(transactionId));
    }

    /** Stable, bounded cursor over durable activity outcomes retained for quest restart recovery. */
    public QuestActivityEvidenceBatch questActivityEvidenceAfter(UUID afterExclusive, int maximumEntries) {
        if (maximumEntries < 1 || maximumEntries > MAX_QUEST_ACTIVITY_EVIDENCE_BATCH_SIZE) {
            throw new IllegalArgumentException("Quest activity evidence batch must be between 1 and "
                    + MAX_QUEST_ACTIVITY_EVIDENCE_BATCH_SIZE);
        }
        var tail = afterExclusive == null
                ? questActivityEvidence
                : questActivityEvidence.tailMap(afterExclusive, false);
        java.util.List<Map.Entry<UUID, QuestActivityEvidence>> entries = tail.entrySet().stream()
                .limit(maximumEntries)
                .map(entry -> Map.entry(entry.getKey(), entry.getValue()))
                .toList();
        return new QuestActivityEvidenceBatch(entries,
                entries.isEmpty() ? Optional.empty() : Optional.of(entries.getLast().getKey()),
                !entries.isEmpty() && questActivityEvidence.higherKey(entries.getLast().getKey()) != null);
    }

    /** Bounded login recovery projection for one player. */
    public java.util.List<Map.Entry<UUID, QuestActivityEvidence>> questActivityEvidenceFor(
            UUID playerId, int maximumEntries) {
        if (playerId == null || maximumEntries < 1
                || maximumEntries > MAX_QUEST_ACTIVITY_EVIDENCE_BATCH_SIZE) {
            throw new IllegalArgumentException("Invalid player quest activity evidence query");
        }
        return questActivityEvidenceIdsByPlayer.getOrDefault(playerId, java.util.Collections.emptyNavigableSet())
                .stream()
                .limit(maximumEntries)
                .map(transactionId -> Map.entry(transactionId, questActivityEvidence.get(transactionId)))
                .toList();
    }

    /** Returns a stable immutable view suitable for admin screens and background reads. */
    public Snapshot snapshot() {
        return new Snapshot(schemaVersion, players);
    }

    /** Immutable, bounded player projection for operator read views. */
    public java.util.List<Map.Entry<UUID, RpgPlayerState>> players(int maximumEntries) {
        if (maximumEntries < 1) {
            throw new IllegalArgumentException("RPG player query must be bounded");
        }
        return players.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .limit(maximumEntries)
                .map(entry -> Map.entry(entry.getKey(), entry.getValue()))
                .toList();
    }

    /** Stable cursor batch used by bounded restart recovery. */
    public PlayerBatch playersAfter(UUID afterExclusive, int maximumEntries) {
        if (maximumEntries < 1 || maximumEntries > 256) {
            throw new IllegalArgumentException("RPG player recovery batch must be between 1 and 256");
        }
        var tail = afterExclusive == null ? players : players.tailMap(afterExclusive, false);
        java.util.List<Map.Entry<UUID, RpgPlayerState>> entries = tail.entrySet().stream()
                .limit(maximumEntries)
                .map(entry -> Map.entry(entry.getKey(), entry.getValue()))
                .toList();
        return new PlayerBatch(entries,
                entries.isEmpty() ? Optional.empty() : Optional.of(entries.getLast().getKey()),
                !entries.isEmpty() && players.higherKey(entries.getLast().getKey()) != null);
    }

    boolean commit(UUID playerId, RpgPlayerState state) {
        if (!writable || playerId == null || ZERO_UUID.equals(playerId) || state == null || !state.isValid()) {
            return false;
        }
        RpgPlayerState previous = players.get(playerId);
        if (state.equals(previous)) {
            return false;
        }
        if (players.size() >= MAX_PLAYERS && previous == null) {
            return false;
        }
        players.put(playerId, state);
        setDirty();
        return true;
    }

    /** Atomically commits an RPG activity award and the owner-root quest recovery evidence. */
    boolean commitActivityOutcome(
            UUID playerId,
            RpgPlayerState expected,
            RpgPlayerState replacement,
            QuestActivityEvidence evidence) {
        if (!writable || playerId == null || ZERO_UUID.equals(playerId)
                || expected == null || replacement == null || !replacement.isValid()
                || evidence == null || !evidence.isValid() || !evidence.playerId().equals(playerId)
                || !expected.equals(state(playerId))
                || questActivityEvidence.containsKey(evidence.provenance().transactionId())) {
            return false;
        }
        long timestamp = evidence.provenance().timestamp();
        if (questActivityEvidenceCountByPlayer.getOrDefault(playerId, 0)
                >= MAX_QUEST_ACTIVITY_EVIDENCE_PER_PLAYER) {
            trimAcknowledgedQuestActivityEvidence(
                    playerId, timestamp, MAX_QUEST_ACTIVITY_EVIDENCE_BATCH_SIZE);
        }
        if (questActivityEvidence.size() >= MAX_QUEST_ACTIVITY_EVIDENCE) {
            trimAcknowledgedQuestActivityEvidence(
                    timestamp, MAX_QUEST_ACTIVITY_EVIDENCE_BATCH_SIZE);
        }
        if (questActivityEvidence.size() >= MAX_QUEST_ACTIVITY_EVIDENCE
                || questActivityEvidenceCountByPlayer.getOrDefault(playerId, 0)
                        >= MAX_QUEST_ACTIVITY_EVIDENCE_PER_PLAYER) {
            return false;
        }
        RpgPlayerState previous = players.get(playerId);
        if (players.size() >= MAX_PLAYERS && previous == null) {
            return false;
        }
        players.put(playerId, replacement);
        questActivityEvidence.put(evidence.provenance().transactionId(), evidence);
        questActivityEvidenceCountByPlayer.merge(playerId, 1, Math::addExact);
        questActivityEvidenceIdsByPlayer.computeIfAbsent(playerId, ignored -> new TreeSet<>())
                .add(evidence.provenance().transactionId());
        setDirty();
        return true;
    }

    /** Atomically commits captured quest XP and its non-expiring exact-once receipt. */
    boolean commitQuestRewardOutcome(
            UUID playerId,
            RpgPlayerState expected,
            RpgPlayerState replacement,
            QuestRewardReceipt receipt) {
        if (!writable || playerId == null || ZERO_UUID.equals(playerId)
                || expected == null || replacement == null || !replacement.isValid()
                || receipt == null || !receipt.isValid() || !receipt.playerId().equals(playerId)
                || !expected.equals(state(playerId))
                || questRewardReceipts.containsKey(receipt.transactionId())
                || questRewardReceipts.size() >= MAX_QUEST_REWARD_RECEIPTS) {
            return false;
        }
        RpgPlayerState previous = players.get(playerId);
        if (players.size() >= MAX_PLAYERS && previous == null) {
            return false;
        }
        players.put(playerId, replacement);
        questRewardReceipts.put(receipt.transactionId(), receipt);
        setDirty();
        return true;
    }

    /** Marks a terminal quest delivery while retaining the full owner evidence for restart safety. */
    public boolean acknowledgeQuestActivityEvidence(
            UUID transactionId,
            UUID playerId,
            long timestampEpochMillis,
            AckDisposition disposition) {
        if (!writable || transactionId == null || ZERO_UUID.equals(transactionId)
                || playerId == null || ZERO_UUID.equals(playerId) || timestampEpochMillis < 0
                || disposition == null) {
            return false;
        }
        QuestActivityEvidence current = questActivityEvidence.get(transactionId);
        if (current == null || !current.playerId().equals(playerId)) {
            return false;
        }
        if (current.ackDisposition().isPresent()
                && current.ackDisposition().orElseThrow() != disposition) {
            return true;
        }
        long acknowledgedAt = Math.max(timestampEpochMillis, current.provenance().timestamp());
        if (current.acknowledgedAtEpochMillis().filter(value -> value >= acknowledgedAt).isPresent()) {
            return true;
        }
        questActivityEvidence.put(transactionId, current.acknowledgedAt(acknowledgedAt, disposition));
        setDirty();
        return true;
    }

    /** Removes only outcomes acknowledged for a full recovery window, in a bounded batch. */
    public int trimAcknowledgedQuestActivityEvidence(
            UUID playerId,
            long timestampEpochMillis,
            int maximumEntries) {
        return trimAcknowledgedQuestActivityEvidence(
                playerId, Set.of(), timestampEpochMillis, maximumEntries);
    }

    /** APPLIED outcomes require the quest owner's processed marker before they may be reclaimed. */
    public int trimAcknowledgedQuestActivityEvidence(
            UUID playerId,
            Set<UUID> processedEvidenceIds,
            long timestampEpochMillis,
            int maximumEntries) {
        if (!writable || playerId == null || ZERO_UUID.equals(playerId)
                || processedEvidenceIds == null
                || timestampEpochMillis < 0 || maximumEntries < 1
                || maximumEntries > MAX_QUEST_ACTIVITY_EVIDENCE_BATCH_SIZE) {
            return 0;
        }
        long cutoff = timestampEpochMillis <= QUEST_ACTIVITY_EVIDENCE_RETENTION_MILLIS
                ? 0L
                : timestampEpochMillis - QUEST_ACTIVITY_EVIDENCE_RETENTION_MILLIS;
        java.util.List<UUID> removable = questActivityEvidenceIdsByPlayer
                .getOrDefault(playerId, java.util.Collections.emptyNavigableSet()).stream()
                .filter(transactionId -> reclaimableQuestActivityEvidence(
                        transactionId, questActivityEvidence.get(transactionId), processedEvidenceIds, cutoff))
                .limit(maximumEntries)
                .toList();
        return removeQuestActivityEvidence(removable);
    }

    /** Global bounded reclaim used only when the shared outbox reaches its ceiling. */
    public int trimAcknowledgedQuestActivityEvidence(long timestampEpochMillis, int maximumEntries) {
        return trimAcknowledgedQuestActivityEvidence((playerId, transactionId) -> false,
                timestampEpochMillis, maximumEntries);
    }

    /**
     * Globally reclaims a bounded batch while the quest owner supplies its processed markers.
     * APPLIED outcomes remain retained unless their player's owner marker is present.
     */
    public int trimAcknowledgedQuestActivityEvidence(
            Map<UUID, ? extends Set<UUID>> processedEvidenceIdsByPlayer,
            long timestampEpochMillis,
            int maximumEntries) {
        if (processedEvidenceIdsByPlayer == null) {
            return 0;
        }
        return trimAcknowledgedQuestActivityEvidence(
                (playerId, transactionId) -> {
                    Set<UUID> processedIds = processedEvidenceIdsByPlayer.get(playerId);
                    return processedIds != null && processedIds.contains(transactionId);
                },
                timestampEpochMillis, maximumEntries);
    }

    /** Global capacity reclaim with a read-only marker lookup supplied by the quest owner. */
    public int trimAcknowledgedQuestActivityEvidence(
            java.util.function.BiPredicate<UUID, UUID> processedEvidenceMarker,
            long timestampEpochMillis,
            int maximumEntries) {
        if (!writable || timestampEpochMillis < 0 || maximumEntries < 1
                || maximumEntries > MAX_QUEST_ACTIVITY_EVIDENCE_BATCH_SIZE
                || processedEvidenceMarker == null) {
            return 0;
        }
        long cutoff = timestampEpochMillis <= QUEST_ACTIVITY_EVIDENCE_RETENTION_MILLIS
                ? 0L
                : timestampEpochMillis - QUEST_ACTIVITY_EVIDENCE_RETENTION_MILLIS;
        java.util.List<UUID> removable = questActivityEvidence.entrySet().stream()
                .filter(entry -> reclaimableQuestActivityEvidence(
                        entry.getKey(), entry.getValue(),
                        processedEvidenceMarker.test(entry.getValue().playerId(), entry.getKey()), cutoff))
                .limit(maximumEntries)
                .map(Map.Entry::getKey)
                .toList();
        return removeQuestActivityEvidence(removable);
    }

    private static boolean reclaimableQuestActivityEvidence(
            UUID transactionId,
            QuestActivityEvidence evidence,
            Set<UUID> processedEvidenceIds,
            long cutoff) {
        return reclaimableQuestActivityEvidence(
                transactionId, evidence, processedEvidenceIds.contains(transactionId), cutoff);
    }

    private static boolean reclaimableQuestActivityEvidence(
            UUID transactionId,
            QuestActivityEvidence evidence,
            boolean processedEvidenceMarker,
            long cutoff) {
        return evidence != null
                && evidence.acknowledgedAtEpochMillis()
                        .filter(acknowledgedAt -> acknowledgedAt < cutoff).isPresent()
                && evidence.ackDisposition().filter(disposition ->
                        disposition == AckDisposition.IGNORED
                                || processedEvidenceMarker).isPresent();
    }

    private int removeQuestActivityEvidence(java.util.List<UUID> transactionIds) {
        if (transactionIds.isEmpty()) {
            return 0;
        }
        int removed = 0;
        for (UUID transactionId : transactionIds) {
            QuestActivityEvidence evidence = questActivityEvidence.remove(transactionId);
            if (evidence == null) {
                continue;
            }
            removed++;
            questActivityEvidenceCountByPlayer.computeIfPresent(evidence.playerId(), (ignored, count) ->
                    count == 1 ? null : count - 1);
            questActivityEvidenceIdsByPlayer.computeIfPresent(evidence.playerId(), (ignored, ids) -> {
                ids.remove(transactionId);
                return ids.isEmpty() ? null : ids;
            });
        }
        if (removed > 0) {
            setDirty();
        }
        return removed;
    }

    private void rebuildQuestActivityEvidenceIndex() {
        questActivityEvidenceCountByPlayer.clear();
        questActivityEvidenceIdsByPlayer.clear();
        questActivityEvidence.forEach((transactionId, evidence) -> {
            questActivityEvidenceCountByPlayer.merge(evidence.playerId(), 1, Math::addExact);
            questActivityEvidenceIdsByPlayer.computeIfAbsent(evidence.playerId(), ignored -> new TreeSet<>())
                    .add(transactionId);
        });
    }

    private static DataResult<Map<UUID, RpgPlayerState>> playersFromEntries(
            java.util.List<PlayerEntry> entries) {
        Map<UUID, RpgPlayerState> result = new LinkedHashMap<>();
        for (PlayerEntry entry : entries) {
            if (entry.id().equals(ZERO_UUID) || result.putIfAbsent(entry.id(), entry.state()) != null) {
                return DataResult.error(() -> "Duplicate or zero RPG player ID " + entry.id());
            }
        }
        return DataResult.success(Map.copyOf(result));
    }

    private static DataResult<java.util.List<PlayerEntry>> playersToEntries(Map<UUID, RpgPlayerState> players) {
        return DataResult.success(players.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new PlayerEntry(entry.getKey(), entry.getValue()))
                .toList());
    }

    private static DataResult<Map<UUID, QuestActivityEvidence>> questActivityEvidenceFromEntries(
            java.util.List<QuestActivityEvidenceEntry> entries) {
        Map<UUID, QuestActivityEvidence> result = new LinkedHashMap<>();
        Map<UUID, Integer> countByPlayer = new HashMap<>();
        for (QuestActivityEvidenceEntry entry : entries) {
            if (entry.id().equals(ZERO_UUID)
                    || !entry.id().equals(entry.value().provenance().transactionId())
                    || result.putIfAbsent(entry.id(), entry.value()) != null) {
                return DataResult.error(() -> "Duplicate, zero, or mismatched quest activity evidence ID "
                        + entry.id());
            }
            int playerCount = countByPlayer.merge(entry.value().playerId(), 1, Math::addExact);
            if (playerCount > MAX_QUEST_ACTIVITY_EVIDENCE_PER_PLAYER) {
                return DataResult.error(() -> "Quest activity evidence exceeds the per-player limit for "
                        + entry.value().playerId());
            }
        }
        return DataResult.success(Map.copyOf(result));
    }

    private static DataResult<java.util.List<QuestActivityEvidenceEntry>> questActivityEvidenceToEntries(
            Map<UUID, QuestActivityEvidence> evidence) {
        return DataResult.success(evidence.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new QuestActivityEvidenceEntry(entry.getKey(), entry.getValue()))
                .toList());
    }

    private static DataResult<Map<UUID, QuestRewardReceipt>> questRewardReceiptsFromEntries(
            java.util.List<QuestRewardReceiptEntry> entries) {
        Map<UUID, QuestRewardReceipt> result = new LinkedHashMap<>();
        for (QuestRewardReceiptEntry entry : entries) {
            if (entry.id().equals(ZERO_UUID)
                    || !entry.id().equals(entry.value().transactionId())
                    || result.putIfAbsent(entry.id(), entry.value()) != null) {
                return DataResult.error(() -> "Duplicate, zero, or mismatched quest reward receipt ID "
                        + entry.id());
            }
        }
        return DataResult.success(Map.copyOf(result));
    }

    private static DataResult<java.util.List<QuestRewardReceiptEntry>> questRewardReceiptsToEntries(
            Map<UUID, QuestRewardReceipt> receipts) {
        return DataResult.success(receipts.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new QuestRewardReceiptEntry(entry.getKey(), entry.getValue()))
                .toList());
    }

    private record PlayerEntry(UUID id, RpgPlayerState state) {
        private static final Codec<PlayerEntry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                UUIDUtil.STRING_CODEC.fieldOf("id").forGetter(PlayerEntry::id),
                RpgPlayerState.CODEC.fieldOf("state").forGetter(PlayerEntry::state)
        ).apply(instance, PlayerEntry::new));
    }

    private record QuestActivityEvidenceEntry(UUID id, QuestActivityEvidence value) {
        private static final Codec<QuestActivityEvidenceEntry> CODEC = RecordCodecBuilder.create(instance ->
                instance.group(
                        UUIDUtil.STRING_CODEC.fieldOf("id").forGetter(QuestActivityEvidenceEntry::id),
                        QuestActivityEvidence.CODEC.fieldOf("value").forGetter(QuestActivityEvidenceEntry::value)
                ).apply(instance, QuestActivityEvidenceEntry::new));
    }

    private record QuestRewardReceiptEntry(UUID id, QuestRewardReceipt value) {
        private static final Codec<QuestRewardReceiptEntry> CODEC = RecordCodecBuilder.create(instance ->
                instance.group(
                        UUIDUtil.STRING_CODEC.fieldOf("id").forGetter(QuestRewardReceiptEntry::id),
                        QuestRewardReceipt.CODEC.fieldOf("value").forGetter(QuestRewardReceiptEntry::value)
                ).apply(instance, QuestRewardReceiptEntry::new));
    }

    private record PersistedState(
            int schemaVersion,
            Map<UUID, RpgPlayerState> players,
            Map<UUID, QuestActivityEvidence> questActivityEvidence,
            Map<UUID, QuestRewardReceipt> questRewardReceipts) {
        PersistedState {
            players = Map.copyOf(players);
            questActivityEvidence = Map.copyOf(questActivityEvidence);
            questRewardReceipts = Map.copyOf(questRewardReceipts);
        }

        PersistedState atVersion(int version) {
            return new PersistedState(version, players, questActivityEvidence, questRewardReceipts);
        }
    }

    public record QuestActivityEvidence(
            UUID playerId,
            RpgPlayerState.ProgressionProvenance provenance,
            Optional<Long> acknowledgedAtEpochMillis,
            Optional<AckDisposition> ackDisposition) {
        public static final Codec<QuestActivityEvidence> CODEC = RecordCodecBuilder
                .<QuestActivityEvidence>create(instance -> instance.group(
                        UUIDUtil.STRING_CODEC.fieldOf("player_id").forGetter(QuestActivityEvidence::playerId),
                        RpgPlayerState.ProgressionProvenance.CODEC.fieldOf("provenance")
                                .forGetter(QuestActivityEvidence::provenance),
                        Codec.LONG.optionalFieldOf("acknowledged_at_epoch_millis")
                                .forGetter(QuestActivityEvidence::acknowledgedAtEpochMillis),
                        AckDisposition.CODEC.optionalFieldOf("ack_disposition")
                                .forGetter(QuestActivityEvidence::ackDisposition)
                ).apply(instance, QuestActivityEvidence::new))
                .validate(QuestActivityEvidence::validate);

        public QuestActivityEvidence(UUID playerId, RpgPlayerState.ProgressionProvenance provenance) {
            this(playerId, provenance, Optional.empty(), Optional.empty());
        }

        public QuestActivityEvidence {
            acknowledgedAtEpochMillis = acknowledgedAtEpochMillis == null
                    ? Optional.empty()
                    : acknowledgedAtEpochMillis;
            ackDisposition = ackDisposition == null ? Optional.empty() : ackDisposition;
            if (acknowledgedAtEpochMillis.isPresent() && ackDisposition.isEmpty()) {
                ackDisposition = Optional.of(AckDisposition.APPLIED);
            }
        }

        public boolean isValid() {
            return playerId != null && !ZERO_UUID.equals(playerId)
                    && provenance != null && provenance.isValid()
                    && provenance.kind() == RpgPlayerState.ProgressionProvenance.Kind.ACTIVITY_XP
                    && !provenance.source().startsWith("boss_reward:")
                    && !provenance.source().startsWith("quest_reward:")
                    && acknowledgedAtEpochMillis != null
                    && ackDisposition != null
                    && acknowledgedAtEpochMillis.isPresent() == ackDisposition.isPresent()
                    && acknowledgedAtEpochMillis.filter(value -> value < provenance.timestamp()).isEmpty();
        }

        QuestActivityEvidence acknowledgedAt(long timestampEpochMillis, AckDisposition disposition) {
            return new QuestActivityEvidence(
                    playerId, provenance, Optional.of(timestampEpochMillis), Optional.of(disposition));
        }

        private static DataResult<QuestActivityEvidence> validate(QuestActivityEvidence evidence) {
            return evidence.isValid()
                    ? DataResult.success(evidence)
                    : DataResult.error(() -> "Quest activity evidence is invalid");
        }
    }

    public enum AckDisposition implements StringRepresentable {
        APPLIED("applied"),
        IGNORED("ignored");

        public static final Codec<AckDisposition> CODEC = StringRepresentable.fromEnum(AckDisposition::values);
        private final String serializedName;

        AckDisposition(String serializedName) {
            this.serializedName = serializedName;
        }

        @Override
        public String getSerializedName() {
            return serializedName;
        }
    }

    public record QuestRewardReceipt(
            UUID transactionId,
            UUID playerId,
            Identifier activityId,
            long amount,
            long timestampEpochMillis,
            String source) {
        public static final Codec<QuestRewardReceipt> CODEC = RecordCodecBuilder
                .<QuestRewardReceipt>create(instance -> instance.group(
                        UUIDUtil.STRING_CODEC.fieldOf("transaction_id")
                                .forGetter(QuestRewardReceipt::transactionId),
                        UUIDUtil.STRING_CODEC.fieldOf("player_id").forGetter(QuestRewardReceipt::playerId),
                        Identifier.CODEC.fieldOf("activity_id").forGetter(QuestRewardReceipt::activityId),
                        Codec.LONG.fieldOf("amount").forGetter(QuestRewardReceipt::amount),
                        Codec.LONG.fieldOf("timestamp_epoch_millis")
                                .forGetter(QuestRewardReceipt::timestampEpochMillis),
                        Codec.string(1, 160).fieldOf("source").forGetter(QuestRewardReceipt::source)
                ).apply(instance, QuestRewardReceipt::new)).validate(QuestRewardReceipt::validate);

        public boolean isValid() {
            return transactionId != null && !ZERO_UUID.equals(transactionId)
                    && playerId != null && !ZERO_UUID.equals(playerId)
                    && activityId != null && amount >= 1 && amount <= RpgPlayerState.MAX_XP
                    && timestampEpochMillis >= 0 && source != null && !source.isBlank()
                    && source.length() <= 160 && source.startsWith("quest_reward:");
        }

        public boolean matches(
                UUID expectedPlayerId,
                Identifier expectedActivityId,
                long expectedAmount,
                long expectedTimestampEpochMillis,
                String expectedSource) {
            return playerId.equals(expectedPlayerId)
                    && activityId.equals(expectedActivityId)
                    && amount == expectedAmount
                    && timestampEpochMillis == expectedTimestampEpochMillis
                    && source.equals(expectedSource);
        }

        private static DataResult<QuestRewardReceipt> validate(QuestRewardReceipt receipt) {
            return receipt.isValid()
                    ? DataResult.success(receipt)
                    : DataResult.error(() -> "Quest reward receipt is invalid");
        }
    }

    public record Snapshot(int schemaVersion, Map<UUID, RpgPlayerState> players) {
        public Snapshot {
            players = Map.copyOf(players);
        }

        public Optional<RpgPlayerState> player(UUID playerId) {
            return Optional.ofNullable(players.get(playerId));
        }
    }

    public record PlayerBatch(
            java.util.List<Map.Entry<UUID, RpgPlayerState>> entries,
            Optional<UUID> nextCursor,
            boolean hasMore) {
        public PlayerBatch {
            entries = java.util.List.copyOf(entries);
            nextCursor = nextCursor == null ? Optional.empty() : nextCursor;
        }
    }

    public record QuestActivityEvidenceBatch(
            java.util.List<Map.Entry<UUID, QuestActivityEvidence>> entries,
            Optional<UUID> nextCursor,
            boolean hasMore) {
        public QuestActivityEvidenceBatch {
            entries = java.util.List.copyOf(entries);
            nextCursor = nextCursor == null ? Optional.empty() : nextCursor;
        }
    }
}
