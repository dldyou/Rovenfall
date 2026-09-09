package org.dldyou.rovenfall.activities;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record ActivityProgress(Map<ActivityTrack, Long> experience, Set<String> discoveries) {
    public static final long MAX_EXPERIENCE = 9_000_000_000_000_000L;
    public static final int MAX_DISCOVERIES = 65_536;
    private static final Codec<Long> EXPERIENCE_CODEC = Codec.LONG.validate(value ->
            value < 0 || value > MAX_EXPERIENCE
                    ? DataResult.error(() -> "activity experience is outside the supported range")
                    : DataResult.success(value));
    private static final Codec<Map<ActivityTrack, Long>> EXPERIENCE_MAP_CODEC =
            Codec.unboundedMap(ActivityTrack.CODEC, EXPERIENCE_CODEC).validate(values ->
                    values.size() > ActivityTrack.values().length
                            ? DataResult.error(() -> "activity progress has too many tracks")
                            : DataResult.success(values));
    private static final Codec<Set<String>> DISCOVERY_SET_CODEC =
            Codec.string(1, ActivityObservation.MAX_SUBJECT_KEY_LENGTH + 64)
                    .listOf(0, MAX_DISCOVERIES)
                    .flatXmap(ActivityProgress::discoveriesFromList, ActivityProgress::discoveriesToList);
    public static final Codec<ActivityProgress> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            EXPERIENCE_MAP_CODEC.optionalFieldOf("experience", Map.of()).forGetter(ActivityProgress::experience),
            DISCOVERY_SET_CODEC.optionalFieldOf("discoveries", Set.of()).forGetter(ActivityProgress::discoveries)
    ).apply(instance, ActivityProgress::new));

    public ActivityProgress {
        experience = experience == null ? Map.of() : Map.copyOf(experience);
        discoveries = discoveries == null ? Set.of() : Set.copyOf(discoveries);
    }

    public static ActivityProgress empty() {
        return new ActivityProgress(Map.of(), Set.of());
    }

    public long experience(ActivityTrack track) {
        return experience.getOrDefault(track, 0L);
    }

    public boolean hasDiscovery(String discoveryKey) {
        return discoveries.contains(discoveryKey);
    }

    public ActivityProgress award(ActivityTrack track, long amount, String discoveryKey) {
        if (track == null || amount < 1) {
            throw new IllegalArgumentException("Activity award is invalid");
        }
        long updated = Math.addExact(experience(track), amount);
        if (updated > MAX_EXPERIENCE) {
            throw new ArithmeticException("Activity experience exceeds the supported maximum");
        }
        Map<ActivityTrack, Long> updatedExperience = new HashMap<>(experience);
        updatedExperience.put(track, updated);
        Set<String> updatedDiscoveries = new HashSet<>(discoveries);
        if (discoveryKey != null && !discoveryKey.isEmpty()) {
            if (!updatedDiscoveries.contains(discoveryKey) && updatedDiscoveries.size() >= MAX_DISCOVERIES) {
                throw new IllegalStateException("Activity discovery capacity is exhausted");
            }
            updatedDiscoveries.add(discoveryKey);
        }
        return new ActivityProgress(updatedExperience, updatedDiscoveries);
    }

    private static DataResult<Set<String>> discoveriesFromList(List<String> values) {
        Set<String> discoveries = new HashSet<>();
        for (String value : values) {
            if (!discoveries.add(value)) {
                return DataResult.error(() -> "duplicate activity discovery " + value);
            }
        }
        return DataResult.success(Set.copyOf(discoveries));
    }

    private static DataResult<List<String>> discoveriesToList(Set<String> values) {
        List<String> sorted = new ArrayList<>(values);
        sorted.sort(String::compareTo);
        return DataResult.success(List.copyOf(sorted));
    }
}
