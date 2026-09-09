package org.dldyou.rovenfall.administration;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;

public record PlayerRecord(
        long firstSeenEpochMillis,
        long lastSeenEpochMillis,
        Optional<String> displayName) {
    static final int MAX_DISPLAY_NAME_LENGTH = 16;
    public static final int MAX_NAME_LENGTH = MAX_DISPLAY_NAME_LENGTH;

    public static final Codec<PlayerRecord> CODEC = RecordCodecBuilder.<PlayerRecord>create(instance -> instance.group(
            Codec.LONG.fieldOf("first_seen").forGetter(PlayerRecord::firstSeenEpochMillis),
            Codec.LONG.fieldOf("last_seen").forGetter(PlayerRecord::lastSeenEpochMillis),
            Codec.STRING.optionalFieldOf("display_name").forGetter(PlayerRecord::displayName),
            Codec.STRING.optionalFieldOf("last_known_name").forGetter(ignored -> Optional.empty())
    ).apply(instance, (firstSeen, lastSeen, displayName, legacyName) ->
            new PlayerRecord(firstSeen, lastSeen, displayName.or(() -> legacyName))))
            .validate(PlayerRecord::validate);

    public PlayerRecord {
        displayName = normalizeDisplayName(displayName == null ? null : displayName.orElse(null));
    }

    public PlayerRecord(long firstSeenEpochMillis, long lastSeenEpochMillis) {
        this(firstSeenEpochMillis, lastSeenEpochMillis, Optional.empty());
    }

    public PlayerRecord(long firstSeenEpochMillis, long lastSeenEpochMillis, String lastKnownName) {
        this(firstSeenEpochMillis, lastSeenEpochMillis, Optional.ofNullable(lastKnownName));
    }

    public String lastKnownName() {
        return displayName.orElse("");
    }

    PlayerRecord observe(long timestampEpochMillis) {
        return observe(timestampEpochMillis, null);
    }

    PlayerRecord observe(long timestampEpochMillis, String observedDisplayName) {
        long observedAt = Math.max(lastSeenEpochMillis, timestampEpochMillis);
        Optional<String> observedName = normalizeDisplayName(observedDisplayName);
        Optional<String> updatedName = observedName.isPresent() ? observedName : displayName;
        return observedAt != lastSeenEpochMillis || !updatedName.equals(displayName)
                ? new PlayerRecord(firstSeenEpochMillis, observedAt, updatedName)
                : this;
    }

    PlayerRecord observe(String playerName, long timestampEpochMillis) {
        return observe(timestampEpochMillis, playerName);
    }

    private static DataResult<PlayerRecord> validate(PlayerRecord record) {
        if (record.firstSeenEpochMillis < 0 || record.lastSeenEpochMillis < record.firstSeenEpochMillis) {
            return DataResult.error(() -> "Invalid player observation timestamps");
        }
        return DataResult.success(record);
    }

    private static Optional<String> normalizeDisplayName(String displayName) {
        String normalized = displayName == null ? "" : displayName.strip();
        if (normalized.isEmpty() || normalized.length() > MAX_DISPLAY_NAME_LENGTH
                || normalized.chars().anyMatch(Character::isISOControl)) {
            return Optional.empty();
        }
        return Optional.of(normalized);
    }
}
