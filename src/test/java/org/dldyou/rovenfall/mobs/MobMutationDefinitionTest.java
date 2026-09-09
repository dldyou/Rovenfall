package org.dldyou.rovenfall.mobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import org.junit.jupiter.api.Test;

final class MobMutationDefinitionTest {
    @Test
    void validatedDefinitionRoundTripsWithTypedComposition() {
        MobMutationDefinition definition = definition(id("zombie"), 0.07, true);
        var encoded = MobMutationDefinition.CODEC.encodeStart(NbtOps.INSTANCE, definition).getOrThrow();
        assertEquals(definition, MobMutationDefinition.CODEC.parse(NbtOps.INSTANCE, encoded).getOrThrow());
        assertEquals(MobMutationDefinition.AiModifier.LEAP, definition.aiModifier());
        assertEquals(AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL,
                definition.attributes().getFirst().operation());
    }

    @Test
    void invisibleDuplicateAndOverabundantMutationsAreRejected() {
        assertTrue(MobMutationDefinition.validate(definition(id("zombie"), 0.07, false)).error().isPresent());
        MobMutationDefinition duplicate = new MobMutationDefinition(
                List.of(id("zombie"), id("zombie")),
                0.07,
                "mutation.rovenfall.test",
                true,
                definition(id("zombie"), 0.07, true).attributes(),
                MobMutationDefinition.AiModifier.LEAP,
                10);
        assertTrue(MobMutationDefinition.validate(duplicate).error().isPresent());

        assertTrue(MobMutationCatalog.create(Map.of(
                id("one"), definition(id("zombie"), 0.20, true),
                id("two"), definition(id("skeleton"), 0.06, true))).error().isPresent());
    }

    private static MobMutationDefinition definition(
            Identifier eligible, double chance, boolean glowing) {
        return new MobMutationDefinition(
                List.of(eligible),
                chance,
                "mutation.rovenfall.test",
                glowing,
                List.of(new MobMutationDefinition.AttributeChange(
                        id("max_health"),
                        0.25,
                        AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL)),
                MobMutationDefinition.AiModifier.LEAP,
                10);
    }

    private static Identifier id(String path) {
        return Identifier.withDefaultNamespace(path);
    }
}
