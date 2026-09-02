package org.dldyou.rovenfall.activities;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;

public record ActivityState(
        Map<UUID, ActivityProgress> progressByPlayer,
        Map<UUID, ActivityEvidence> evidenceById,
        Set<ActivityBlockKey> placedResourceBlocks) {
    public static final int MAX_PLAYERS = 1_000_000;
    public static final int MAX_EVIDENCE = 250_000;
    public static final int MAX_PLACED_RESOURCE_BLOCKS = 1_000_000;
    private static final Codec<Map<UUID, ActivityProgress>> PROGRESS_CODEC =
            Codec.unboundedMap(UUIDUtil.STRING_CODEC, ActivityProgress.CODEC).validate(values ->
                    values.size() > MAX_PLAYERS
                            ? DataResult.error(() -> "activity player count exceeds " + MAX_PLAYERS)
                            : DataResult.success(values));
    private static final Codec<Map<UUID, ActivityEvidence>> EVIDENCE_CODEC =
            Codec.unboundedMap(UUIDUtil.STRING_CODEC, ActivityEvidence.CODEC).validate(ActivityState::validateEvidence);
    private static final Codec<Set<ActivityBlockKey>> PLACED_RESOURCE_CODEC =
            ActivityBlockKey.CODEC.listOf(0, MAX_PLACED_RESOURCE_BLOCKS)
                    .flatXmap(ActivityState::placedResourcesFromList, ActivityState::placedResourcesToList);
    public static final Codec<ActivityState> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            PROGRESS_CODEC.optionalFieldOf("progress", Map.of()).forGetter(ActivityState::progressByPlayer),
            EVIDENCE_CODEC.optionalFieldOf("evidence", Map.of()).forGetter(ActivityState::evidenceById),
            PLACED_RESOURCE_CODEC.optionalFieldOf("placed_resource_blocks", Set.of())
                    .forGetter(ActivityState::placedResourceBlocks)
    ).apply(instance, ActivityState::new));

    public ActivityState {
        progressByPlayer = progressByPlayer == null ? Map.of() : Map.copyOf(progressByPlayer);
        evidenceById = evidenceById == null ? Map.of() : Map.copyOf(evidenceById);
        placedResourceBlocks = placedResourceBlocks == null ? Set.of() : Set.copyOf(placedResourceBlocks);
    }

    public ActivityState(
            Map<UUID, ActivityProgress> progressByPlayer,
            Map<UUID, ActivityEvidence> evidenceById) {
        this(progressByPlayer, evidenceById, Set.of());
    }

    public static ActivityState empty() {
        return new ActivityState(Map.of(), Map.of(), Set.of());
    }

    private static DataResult<Map<UUID, ActivityEvidence>> validateEvidence(
            Map<UUID, ActivityEvidence> evidenceById) {
        if (evidenceById.size() > MAX_EVIDENCE) {
            return DataResult.error(() -> "activity evidence count exceeds " + MAX_EVIDENCE);
        }
        for (var entry : evidenceById.entrySet()) {
            if (!entry.getKey().equals(entry.getValue().evidenceId())) {
                return DataResult.error(() -> "activity evidence map key does not match " + entry.getKey());
            }
        }
        return DataResult.success(evidenceById);
    }

    private static DataResult<Set<ActivityBlockKey>> placedResourcesFromList(List<ActivityBlockKey> values) {
        Set<ActivityBlockKey> resources = new HashSet<>();
        for (ActivityBlockKey value : values) {
            if (!resources.add(value)) {
                return DataResult.error(() -> "duplicate placed activity resource at " + value);
            }
        }
        return DataResult.success(Set.copyOf(resources));
    }

    private static DataResult<List<ActivityBlockKey>> placedResourcesToList(Set<ActivityBlockKey> values) {
        List<ActivityBlockKey> sorted = new ArrayList<>(values);
        sorted.sort(Comparator
                .comparing((ActivityBlockKey key) -> key.dimension().identifier().toString())
                .thenComparingLong(key -> key.position().asLong()));
        return DataResult.success(List.copyOf(sorted));
    }
}
