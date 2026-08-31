package org.dldyou.rovenfall.quest;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.UnaryOperator;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import org.dldyou.rovenfall.Rovenfall;

/** Persistent quest state, deliberately separate from platform, economy, and RPG roots. */
public final class QuestPlayerSavedData extends SavedData {
    private static final UUID ZERO_UUID = new UUID(0L, 0L);
    public static final int CURRENT_SCHEMA_VERSION = 4;
    public static final int MAX_PLAYERS = 100_000;
    static final long PROCESSED_EVIDENCE_OWNER_RETENTION_MILLIS = Duration.ofDays(30).toMillis();
    static final long PROCESSED_EVIDENCE_RETIRE_CONFIRMATION_MILLIS = Duration.ofDays(30).toMillis();
    static final long PROCESSED_EVIDENCE_REPLAY_MILLIS =
            PROCESSED_EVIDENCE_OWNER_RETENTION_MILLIS
                    + PROCESSED_EVIDENCE_RETIRE_CONFIRMATION_MILLIS;

    private static final Codec<Map<UUID, QuestPlayerState>> PLAYERS_CODEC = PlayerEntry.CODEC
            .listOf(0, MAX_PLAYERS)
            .flatXmap(QuestPlayerSavedData::playersFromEntries, QuestPlayerSavedData::playerEntries);

    public static final Codec<QuestPlayerSavedData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.optionalFieldOf("schema_version", 0).forGetter(data -> data.schemaVersion),
            PLAYERS_CODEC.optionalFieldOf("players", Map.of()).forGetter(data -> data.players)
    ).apply(instance, QuestPlayerSavedData::decode));

    public static final SavedDataType<QuestPlayerSavedData> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(Rovenfall.MOD_ID, "quest_player_state"),
            QuestPlayerSavedData::new,
            CODEC);

    private static final Map<Integer, UnaryOperator<PersistedState>> MIGRATIONS = Map.of(
            0, state -> state.atVersion(1),
            1, state -> state.atVersion(2),
            2, state -> state.atVersion(3),
            3, state -> state.atVersion(4));

    private final int schemaVersion;
    private final boolean writable;
    private final NavigableMap<UUID, QuestPlayerState> players;

    public QuestPlayerSavedData() {
        this(CURRENT_SCHEMA_VERSION, Map.of(), true);
    }

    private QuestPlayerSavedData(int schemaVersion, Map<UUID, QuestPlayerState> players, boolean writable) {
        this.schemaVersion = schemaVersion;
        this.writable = writable;
        this.players = new TreeMap<>(players);
    }

    private static QuestPlayerSavedData decode(int schemaVersion, Map<UUID, QuestPlayerState> players) {
        PersistedState original = new PersistedState(schemaVersion, players);
        if (schemaVersion < 0 || schemaVersion > CURRENT_SCHEMA_VERSION) {
            return new QuestPlayerSavedData(schemaVersion, players, false);
        }
        PersistedState candidate = original;
        while (candidate.schemaVersion() < CURRENT_SCHEMA_VERSION) {
            UnaryOperator<PersistedState> migration = MIGRATIONS.get(candidate.schemaVersion());
            if (migration == null) {
                return new QuestPlayerSavedData(original.schemaVersion(), original.players(), false);
            }
            int expectedVersion = candidate.schemaVersion() + 1;
            candidate = migration.apply(candidate);
            if (candidate.schemaVersion() != expectedVersion) {
                return new QuestPlayerSavedData(original.schemaVersion(), original.players(), false);
            }
        }
        return new QuestPlayerSavedData(candidate.schemaVersion(), candidate.players(), true);
    }

    public static QuestPlayerSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    public int schemaVersion() {
        return schemaVersion;
    }

    public boolean isWritable() {
        return writable;
    }

    public Optional<QuestPlayerState> player(UUID playerId) {
        return Optional.ofNullable(players.get(playerId));
    }

    public QuestPlayerState state(UUID playerId) {
        return players.getOrDefault(playerId, QuestPlayerState.EMPTY);
    }

    public int playerCount() {
        return players.size();
    }

    public Snapshot snapshot() {
        return new Snapshot(schemaVersion, players);
    }

    public PlayerBatch playersAfter(UUID afterExclusive, int maximumEntries) {
        if (maximumEntries < 1 || maximumEntries > 256) {
            throw new IllegalArgumentException("Quest player recovery batch must be between 1 and 256");
        }
        NavigableMap<UUID, QuestPlayerState> tail = afterExclusive == null
                ? players : players.tailMap(afterExclusive, false);
        java.util.List<Map.Entry<UUID, QuestPlayerState>> entries = tail.entrySet().stream()
                .limit(maximumEntries)
                .map(entry -> Map.entry(entry.getKey(), entry.getValue()))
                .toList();
        return new PlayerBatch(entries,
                entries.isEmpty() ? Optional.empty() : Optional.of(entries.getLast().getKey()),
                !entries.isEmpty() && players.higherKey(entries.getLast().getKey()) != null);
    }

    /** Commits only when the caller's observed state is still current. */
    boolean commit(UUID playerId, QuestPlayerState expected, QuestPlayerState updated) {
        if (!writable || playerId == null || ZERO_UUID.equals(playerId)
                || expected == null || updated == null || !updated.isValid()) {
            return false;
        }
        QuestPlayerState current = state(playerId);
        if (!current.equals(expected) || current.equals(updated)) {
            return false;
        }
        if (players.size() >= MAX_PLAYERS && !players.containsKey(playerId)) {
            return false;
        }
        players.put(playerId, updated);
        setDirty();
        return true;
    }

    /** Two-phase retirement for processed IDs whose owner-domain evidence is no longer retained. */
    int maintainProcessedEvidence(
            UUID playerId,
            Map<UUID, Boolean> ownerEvidencePresent,
            long timestampEpochMillis,
            int maximumEntries) {
        if (!writable || playerId == null || ZERO_UUID.equals(playerId)
                || ownerEvidencePresent == null || timestampEpochMillis < 0
                || maximumEntries < 1 || maximumEntries > 256
                || ownerEvidencePresent.size() > maximumEntries) {
            return 0;
        }
        QuestPlayerState current = state(playerId);
        Map<UUID, QuestPlayerState.ProcessedEvidence> processed =
                new LinkedHashMap<>(current.processedEvidence());
        long ownerRetentionCutoff = timestampEpochMillis <= PROCESSED_EVIDENCE_OWNER_RETENTION_MILLIS
                ? 0L
                : timestampEpochMillis - PROCESSED_EVIDENCE_OWNER_RETENTION_MILLIS;
        long retirementCutoff = timestampEpochMillis <= PROCESSED_EVIDENCE_RETIRE_CONFIRMATION_MILLIS
                ? 0L
                : timestampEpochMillis - PROCESSED_EVIDENCE_RETIRE_CONFIRMATION_MILLIS;
        int changed = 0;
        for (Map.Entry<UUID, Boolean> candidate : ownerEvidencePresent.entrySet()) {
            QuestPlayerState.ProcessedEvidence evidence = processed.get(candidate.getKey());
            if (evidence == null || evidence.kind().isEmpty()
                    || evidence.timestampEpochMillis() >= ownerRetentionCutoff) {
                continue;
            }
            if (Boolean.TRUE.equals(candidate.getValue())) {
                if (evidence.ownerEvidenceMissingSinceEpochMillis().isPresent()) {
                    processed.put(candidate.getKey(), evidence.ownerEvidenceMissingSince(Optional.empty()));
                    changed++;
                }
                continue;
            }
            Optional<Long> missingSince = evidence.ownerEvidenceMissingSinceEpochMillis();
            if (missingSince.isEmpty()) {
                processed.put(candidate.getKey(), evidence.ownerEvidenceMissingSince(
                        Optional.of(timestampEpochMillis)));
                changed++;
            } else if (missingSince.orElseThrow() < retirementCutoff) {
                processed.remove(candidate.getKey());
                changed++;
            }
        }
        if (changed == 0) {
            return 0;
        }
        return commit(playerId, current, new QuestPlayerState(current.quests(), processed)) ? changed : 0;
    }

    private static DataResult<Map<UUID, QuestPlayerState>> playersFromEntries(java.util.List<PlayerEntry> entries) {
        Map<UUID, QuestPlayerState> result = new LinkedHashMap<>();
        for (PlayerEntry entry : entries) {
            if (entry.id().equals(ZERO_UUID) || result.putIfAbsent(entry.id(), entry.state()) != null) {
                return DataResult.error(() -> "Duplicate or zero quest player ID " + entry.id());
            }
        }
        return DataResult.success(Map.copyOf(result));
    }

    private static DataResult<java.util.List<PlayerEntry>> playerEntries(Map<UUID, QuestPlayerState> players) {
        return DataResult.success(players.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new PlayerEntry(entry.getKey(), entry.getValue()))
                .toList());
    }

    private record PlayerEntry(UUID id, QuestPlayerState state) {
        private static final Codec<PlayerEntry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                UUIDUtil.STRING_CODEC.fieldOf("id").forGetter(PlayerEntry::id),
                QuestPlayerState.CODEC.fieldOf("state").forGetter(PlayerEntry::state)
        ).apply(instance, PlayerEntry::new));
    }

    private record PersistedState(int schemaVersion, Map<UUID, QuestPlayerState> players) {
        PersistedState {
            players = Map.copyOf(players);
        }

        PersistedState atVersion(int version) {
            return new PersistedState(version, players);
        }
    }

    public record Snapshot(int schemaVersion, Map<UUID, QuestPlayerState> players) {
        public Snapshot {
            players = Map.copyOf(players);
        }

        public Optional<QuestPlayerState> player(UUID playerId) {
            return Optional.ofNullable(players.get(playerId));
        }
    }

    public record PlayerBatch(
            java.util.List<Map.Entry<UUID, QuestPlayerState>> entries,
            Optional<UUID> nextCursor,
            boolean hasMore) {
        public PlayerBatch {
            entries = java.util.List.copyOf(entries);
            nextCursor = nextCursor == null ? Optional.empty() : nextCursor;
        }
    }
}
