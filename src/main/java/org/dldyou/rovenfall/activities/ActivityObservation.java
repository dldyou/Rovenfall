package org.dldyou.rovenfall.activities;

import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

public record ActivityObservation(
        UUID evidenceId,
        long observedAtEpochMillis,
        UUID playerId,
        ActivityTrack track,
        ActivityKind kind,
        ResourceKey<Level> dimension,
        int chunkX,
        int chunkZ,
        Identifier targetId,
        String subjectKey,
        long contribution,
        ActivityProvenance provenance) {
    public static final int MAX_SUBJECT_KEY_LENGTH = 256;
    public static final long MAX_CONTRIBUTION = 1_000_000_000L;
    private static final UUID ZERO_UUID = new UUID(0L, 0L);

    public Optional<String> validationError() {
        if (evidenceId == null || ZERO_UUID.equals(evidenceId)) {
            return Optional.of("activity evidence ID is missing");
        }
        if (observedAtEpochMillis < 0 || playerId == null || ZERO_UUID.equals(playerId)
                || track == null || kind == null || dimension == null || targetId == null) {
            return Optional.of("activity observation has missing or invalid identity fields");
        }
        if (subjectKey == null || subjectKey.isBlank() || subjectKey.length() > MAX_SUBJECT_KEY_LENGTH
                || subjectKey.chars().anyMatch(value -> value < 0x20 || value == 0x7f)) {
            return Optional.of("activity subject key is invalid");
        }
        if (contribution < 1 || contribution > MAX_CONTRIBUTION) {
            return Optional.of("activity contribution is outside the supported range");
        }
        return kind.validationError(track, provenance);
    }

    public String discoveryKey() {
        return kind.getSerializedName() + ":" + subjectKey;
    }
}
