package org.dldyou.rovenfall.exploration;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.LinkedHashMap;
import java.util.List;
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

/** Separate persistent root for the exploration journal. */
public final class ExplorationPlayerSavedData extends SavedData {
    public static final int CURRENT_SCHEMA_VERSION = 1;
    public static final int MAX_PLAYERS = 100_000;
    private static final UUID ZERO_UUID = new UUID(0L, 0L);

    private static final Codec<Map<UUID, ExplorationPlayerState>> PLAYERS_CODEC = PlayerEntry.CODEC
            .listOf(0, MAX_PLAYERS)
            .flatXmap(ExplorationPlayerSavedData::fromEntries, ExplorationPlayerSavedData::toEntries);
    public static final Codec<ExplorationPlayerSavedData> CODEC = RecordCodecBuilder
            .<ExplorationPlayerSavedData>create(instance -> instance.group(
                    Codec.INT.optionalFieldOf("schema_version", 0).forGetter(data -> data.schemaVersion),
                    PLAYERS_CODEC.optionalFieldOf("players", Map.of()).forGetter(data -> data.players)
            ).apply(instance, ExplorationPlayerSavedData::decode));
    public static final SavedDataType<ExplorationPlayerSavedData> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(Rovenfall.MOD_ID, "exploration_player_state"),
            ExplorationPlayerSavedData::new, CODEC);

    private static final Map<Integer, UnaryOperator<PersistedState>> MIGRATIONS = Map.of(
            0, state -> state.atVersion(1));

    private final int schemaVersion;
    private final boolean writable;
    private final NavigableMap<UUID, ExplorationPlayerState> players;

    public ExplorationPlayerSavedData() {
        this(CURRENT_SCHEMA_VERSION, Map.of(), true);
    }

    private ExplorationPlayerSavedData(
            int schemaVersion, Map<UUID, ExplorationPlayerState> players, boolean writable) {
        this.schemaVersion = schemaVersion;
        this.writable = writable;
        this.players = new TreeMap<>(players);
    }

    private static ExplorationPlayerSavedData decode(
            int schemaVersion, Map<UUID, ExplorationPlayerState> players) {
        PersistedState original = new PersistedState(schemaVersion, players);
        if (schemaVersion < 0 || schemaVersion > CURRENT_SCHEMA_VERSION) {
            return new ExplorationPlayerSavedData(schemaVersion, players, false);
        }
        PersistedState candidate = original;
        while (candidate.schemaVersion() < CURRENT_SCHEMA_VERSION) {
            UnaryOperator<PersistedState> migration = MIGRATIONS.get(candidate.schemaVersion());
            if (migration == null) {
                return new ExplorationPlayerSavedData(original.schemaVersion(), original.players(), false);
            }
            int next = candidate.schemaVersion() + 1;
            candidate = migration.apply(candidate);
            if (candidate.schemaVersion() != next) {
                return new ExplorationPlayerSavedData(original.schemaVersion(), original.players(), false);
            }
        }
        return new ExplorationPlayerSavedData(candidate.schemaVersion(), candidate.players(), true);
    }

    public static ExplorationPlayerSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    public int schemaVersion() {
        return schemaVersion;
    }

    public boolean isWritable() {
        return writable;
    }

    public int playerCount() {
        return players.size();
    }

    public ExplorationPlayerState state(UUID playerId) {
        return players.getOrDefault(playerId, ExplorationPlayerState.EMPTY);
    }

    public Optional<ExplorationPlayerState> player(UUID playerId) {
        return Optional.ofNullable(players.get(playerId));
    }

    public Snapshot snapshot() {
        return new Snapshot(schemaVersion, writable, players);
    }

    /** Commits only when the caller's observed immutable state remains current. */
    public boolean commit(UUID playerId, ExplorationPlayerState expected, ExplorationPlayerState updated) {
        if (!writable || playerId == null || ZERO_UUID.equals(playerId)
                || expected == null || updated == null || !updated.isValid()) {
            return false;
        }
        ExplorationPlayerState current = state(playerId);
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

    public PlayerBatch playersAfter(UUID afterExclusive, int maximumEntries) {
        if (maximumEntries < 1 || maximumEntries > 256) {
            throw new IllegalArgumentException("Exploration player batch must be between 1 and 256");
        }
        NavigableMap<UUID, ExplorationPlayerState> tail = afterExclusive == null
                ? players : players.tailMap(afterExclusive, false);
        List<Map.Entry<UUID, ExplorationPlayerState>> entries = tail.entrySet().stream()
                .limit(maximumEntries).map(entry -> Map.entry(entry.getKey(), entry.getValue())).toList();
        return new PlayerBatch(entries,
                entries.isEmpty() ? Optional.empty() : Optional.of(entries.getLast().getKey()),
                !entries.isEmpty() && players.higherKey(entries.getLast().getKey()) != null);
    }

    private static DataResult<Map<UUID, ExplorationPlayerState>> fromEntries(List<PlayerEntry> entries) {
        Map<UUID, ExplorationPlayerState> result = new LinkedHashMap<>();
        for (PlayerEntry entry : entries) {
            if (ZERO_UUID.equals(entry.id()) || result.putIfAbsent(entry.id(), entry.state()) != null) {
                return DataResult.error(() -> "Duplicate or zero exploration player ID " + entry.id());
            }
        }
        return DataResult.success(Map.copyOf(result));
    }

    private static DataResult<List<PlayerEntry>> toEntries(Map<UUID, ExplorationPlayerState> players) {
        return DataResult.success(players.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(entry -> new PlayerEntry(entry.getKey(), entry.getValue())).toList());
    }

    private record PlayerEntry(UUID id, ExplorationPlayerState state) {
        private static final Codec<PlayerEntry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                UUIDUtil.STRING_CODEC.fieldOf("id").forGetter(PlayerEntry::id),
                ExplorationPlayerState.CODEC.fieldOf("state").forGetter(PlayerEntry::state)
        ).apply(instance, PlayerEntry::new));
    }

    private record PersistedState(int schemaVersion, Map<UUID, ExplorationPlayerState> players) {
        PersistedState {
            players = Map.copyOf(players);
        }

        PersistedState atVersion(int version) {
            return new PersistedState(version, players);
        }
    }

    public record Snapshot(int schemaVersion, boolean writable, Map<UUID, ExplorationPlayerState> players) {
        public Snapshot {
            players = Map.copyOf(players);
        }

        public Optional<ExplorationPlayerState> player(UUID playerId) {
            return Optional.ofNullable(players.get(playerId));
        }
    }

    public record PlayerBatch(
            List<Map.Entry<UUID, ExplorationPlayerState>> entries,
            Optional<UUID> nextCursor,
            boolean hasMore) {
        public PlayerBatch {
            entries = List.copyOf(entries);
            nextCursor = nextCursor == null ? Optional.empty() : nextCursor;
        }
    }
}
