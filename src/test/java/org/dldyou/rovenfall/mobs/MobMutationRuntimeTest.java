package org.dldyou.rovenfall.mobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import org.dldyou.rovenfall.world.WorldTopology;
import org.junit.jupiter.api.Test;

final class MobMutationRuntimeTest {
    @Test
    void freshSelectionIsDeterministicBoundedAndRestrictedToWilderness() {
        var alpha = mutation("alpha", 1_000_000, -64, 320);
        var beta = mutation("beta", 1_000_000, -64, 320);
        var snapshot = snapshot(alpha, beta);
        UUID entityId = UUID.fromString("4d10f613-65cf-45d0-94fc-615ecacb9ed7");
        Identifier zombie = Identifier.withDefaultNamespace("zombie");

        var selected = MobMutationRuntime.selectFresh(
                snapshot, zombie, entityId, WorldTopology.WILDERNESS, 64, false, false);

        assertEquals(List.of(alpha, beta), selected);
        assertEquals(selected, MobMutationRuntime.selectFresh(
                snapshot, zombie, entityId, WorldTopology.WILDERNESS, 64, false, false));
        assertTrue(MobMutationRuntime.selectFresh(
                snapshot, zombie, entityId, Level.OVERWORLD, 64, false, false).isEmpty());
        assertTrue(MobMutationRuntime.selectFresh(
                snapshot, zombie, entityId, WorldTopology.WILDERNESS, 64, true, false).isEmpty());
        assertTrue(MobMutationRuntime.selectFresh(
                snapshot, zombie, entityId, WorldTopology.WILDERNESS, 64, false, true).isEmpty());
        assertTrue(MobMutationRuntime.selectFresh(
                snapshot, zombie, entityId, WorldTopology.WILDERNESS, 321, false, false).isEmpty());
    }

    @Test
    void rewardScalingComposesMutationsAndRejectsUnsafeInput() {
        assertEquals(OptionalLong.of(312),
                MobMutationRuntime.scaleReward(100, List.of(150, 200), List.of(5L, 7L)));
        assertTrue(MobMutationRuntime.scaleReward(-1, List.of(), List.of()).isEmpty());
        assertTrue(MobMutationRuntime.scaleReward(
                1, Collections.nCopies(MobMutationRuntime.MAX_MUTATIONS_PER_MOB + 1, 100), List.of()).isEmpty());
        assertTrue(MobMutationRuntime.scaleReward(Long.MAX_VALUE, List.of(200), List.of()).isEmpty());
    }

    @Test
    void selectionBucketIsStable() {
        UUID entityId = UUID.fromString("85405f47-a2b4-4aa5-a9fe-6e16878266bd");
        Identifier mutation = id("stable");

        int bucket = MobMutationRuntime.selectionBucket(entityId, mutation);

        assertEquals(bucket, MobMutationRuntime.selectionBucket(entityId, mutation));
        assertTrue(bucket >= 0 && bucket < 1_000_000);
    }

    private static MobContentSnapshot snapshot(MobContentCatalog.MutationDefinition... mutations) {
        var catalog = new MobContentCatalog(
                List.of(), List.of(mutations), List.of(), List.of(), List.of(), List.of());
        return MobContentSnapshot.compile(List.of(new MobContentSnapshot.Source(
                id("test.json"), "test", id("test"), catalog)));
    }

    private static MobContentCatalog.MutationDefinition mutation(
            String path, int chance, int minimumY, int maximumY) {
        return new MobContentCatalog.MutationDefinition(
                id(path), "mutation.rovenfall." + path,
                List.of(Identifier.withDefaultNamespace("zombie")), List.of(), List.of(id("death_burst")),
                "mutation.rovenfall." + path + ".marker",
                new MobContentCatalog.SpawnCondition(WorldTopology.WILDERNESS, chance, minimumY, maximumY),
                100, Optional.empty());
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("rovenfall", path);
    }
}
