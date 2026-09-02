package org.dldyou.rovenfall.mobs;

import com.mojang.serialization.DataResult;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Mob;

public final class MobMutationCatalog {
    public static final int MAX_DEFINITIONS = 256;
    private final Map<Identifier, MobMutationDefinition> definitions;

    private MobMutationCatalog(Map<Identifier, MobMutationDefinition> definitions) {
        this.definitions = Map.copyOf(definitions);
    }

    public static DataResult<MobMutationCatalog> create(Map<Identifier, MobMutationDefinition> definitions) {
        if (definitions == null || definitions.size() > MAX_DEFINITIONS) {
            return DataResult.error(() -> "mob mutation definition count is invalid");
        }
        double totalChance = definitions.values().stream()
                .mapToDouble(MobMutationDefinition::spawnChance)
                .sum();
        if (!Double.isFinite(totalChance) || totalChance > MobMutationDefinition.MAX_SPAWN_CHANCE) {
            return DataResult.error(() -> "combined mob mutation spawn chance exceeds "
                    + MobMutationDefinition.MAX_SPAWN_CHANCE);
        }
        return DataResult.success(new MobMutationCatalog(new LinkedHashMap<>(definitions)));
    }

    public Optional<ResolvedMutation> get(Identifier id) {
        MobMutationDefinition definition = definitions.get(id);
        return definition == null ? Optional.empty() : Optional.of(new ResolvedMutation(id, definition));
    }

    public Optional<ResolvedMutation> choose(Mob mob, RandomSource random) {
        Identifier entityTypeId = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType());
        double roll = random.nextDouble();
        double cumulative = 0;
        for (var entry : definitions.entrySet()) {
            if (!entry.getValue().eligibleEntityTypes().contains(entityTypeId)) {
                continue;
            }
            cumulative += entry.getValue().spawnChance();
            if (roll < cumulative) {
                return Optional.of(new ResolvedMutation(entry.getKey(), entry.getValue()));
            }
        }
        return Optional.empty();
    }

    public int size() {
        return definitions.size();
    }

    public record ResolvedMutation(Identifier id, MobMutationDefinition definition) {
    }
}
