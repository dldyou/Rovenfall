package org.dldyou.rovenfall.definition;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import net.minecraft.resources.Identifier;

public record TestDefinition(String translationKey, int value, List<Identifier> requires) {
    public static final int MAX_VALUE = 1_000_000;
    public static final int MAX_REFERENCES = 64;

    public static final Codec<TestDefinition> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.string(1, 160).fieldOf("translation_key").forGetter(TestDefinition::translationKey),
            Codec.intRange(0, MAX_VALUE).fieldOf("value").forGetter(TestDefinition::value),
            Identifier.CODEC.listOf(0, MAX_REFERENCES).optionalFieldOf("requires", List.of()).forGetter(TestDefinition::requires)
    ).apply(instance, TestDefinition::new));

    public TestDefinition {
        requires = List.copyOf(requires);
    }
}
