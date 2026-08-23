package org.dldyou.rovenfall.administration;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;

public record PlayerRecord(long firstSeenEpochMillis, long lastSeenEpochMillis) {
    public static final Codec<PlayerRecord> CODEC = RecordCodecBuilder.<PlayerRecord>create(instance -> instance.group(
            Codec.LONG.fieldOf("first_seen").forGetter(PlayerRecord::firstSeenEpochMillis),
            Codec.LONG.fieldOf("last_seen").forGetter(PlayerRecord::lastSeenEpochMillis)
    ).apply(instance, PlayerRecord::new)).validate(PlayerRecord::validate);

    PlayerRecord observe(long timestampEpochMillis) {
        return timestampEpochMillis > lastSeenEpochMillis
                ? new PlayerRecord(firstSeenEpochMillis, timestampEpochMillis)
                : this;
    }

    private static DataResult<PlayerRecord> validate(PlayerRecord record) {
        if (record.firstSeenEpochMillis < 0 || record.lastSeenEpochMillis < record.firstSeenEpochMillis) {
            return DataResult.error(() -> "Invalid player observation timestamps");
        }
        return DataResult.success(record);
    }
}
