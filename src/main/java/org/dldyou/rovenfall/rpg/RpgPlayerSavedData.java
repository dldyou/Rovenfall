package org.dldyou.rovenfall.rpg;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import org.dldyou.rovenfall.Rovenfall;

/** Persistent RPG state, deliberately separate from the platform/economy root. */
public final class RpgPlayerSavedData extends SavedData {
    private static final UUID ZERO_UUID = new UUID(0L, 0L);
    public static final int CURRENT_SCHEMA_VERSION = 2;
    public static final int MAX_PLAYERS = 100_000;

    private static final Codec<Map<UUID, RpgPlayerState>> PLAYERS_CODEC =
            PlayerEntry.CODEC.listOf(0, MAX_PLAYERS)
                    .flatXmap(RpgPlayerSavedData::playersFromEntries, RpgPlayerSavedData::playersToEntries);

    public static final Codec<RpgPlayerSavedData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.optionalFieldOf("schema_version", 0).forGetter(data -> data.schemaVersion),
            PLAYERS_CODEC.optionalFieldOf("players", Map.of()).forGetter(data -> data.players)
    ).apply(instance, RpgPlayerSavedData::decode));

    public static final SavedDataType<RpgPlayerSavedData> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(Rovenfall.MOD_ID, "rpg_player_state"),
            RpgPlayerSavedData::new,
            CODEC);

    private static final Map<Integer, UnaryOperator<PersistedState>> MIGRATIONS = Map.of(
            0, state -> state.atVersion(1),
            1, state -> state.atVersion(2));

    private final int schemaVersion;
    private final boolean writable;
    private final Map<UUID, RpgPlayerState> players;

    public RpgPlayerSavedData() {
        this(CURRENT_SCHEMA_VERSION, Map.of(), true);
    }

    private RpgPlayerSavedData(int schemaVersion, Map<UUID, RpgPlayerState> players, boolean writable) {
        this.schemaVersion = schemaVersion;
        this.writable = writable;
        this.players = new LinkedHashMap<>(players);
    }

    private static RpgPlayerSavedData decode(int schemaVersion, Map<UUID, RpgPlayerState> players) {
        PersistedState original = new PersistedState(schemaVersion, players);
        if (schemaVersion < 0 || schemaVersion > CURRENT_SCHEMA_VERSION) {
            return new RpgPlayerSavedData(schemaVersion, players, false);
        }
        PersistedState candidate = original;
        while (candidate.schemaVersion() < CURRENT_SCHEMA_VERSION) {
            UnaryOperator<PersistedState> migration = MIGRATIONS.get(candidate.schemaVersion());
            if (migration == null) {
                return new RpgPlayerSavedData(original.schemaVersion(), original.players(), false);
            }
            int expected = candidate.schemaVersion() + 1;
            candidate = migration.apply(candidate);
            if (candidate.schemaVersion() != expected) {
                return new RpgPlayerSavedData(original.schemaVersion(), original.players(), false);
            }
        }
        return new RpgPlayerSavedData(candidate.schemaVersion(), candidate.players(), true);
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
                .limit(maximumEntries)
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> Map.entry(entry.getKey(), entry.getValue()))
                .toList();
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

    private record PlayerEntry(UUID id, RpgPlayerState state) {
        private static final Codec<PlayerEntry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                UUIDUtil.STRING_CODEC.fieldOf("id").forGetter(PlayerEntry::id),
                RpgPlayerState.CODEC.fieldOf("state").forGetter(PlayerEntry::state)
        ).apply(instance, PlayerEntry::new));
    }

    private record PersistedState(int schemaVersion, Map<UUID, RpgPlayerState> players) {
        PersistedState {
            players = Map.copyOf(players);
        }

        PersistedState atVersion(int version) {
            return new PersistedState(version, players);
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
}
