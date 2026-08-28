package org.dldyou.rovenfall.rpg;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import net.minecraft.resources.Identifier;

/** One bounded, data-driven inventory requirement for an RPG mutation. */
public record RpgItemCost(Identifier item, int count) {
    public static final int MAX_ENTRIES = 16;
    public static final int MAX_COUNT = 1_000_000;
    public static final Codec<RpgItemCost> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Identifier.CODEC.fieldOf("item").forGetter(RpgItemCost::item),
            Codec.intRange(1, MAX_COUNT).fieldOf("count").forGetter(RpgItemCost::count)
    ).apply(instance, RpgItemCost::new));
    public static final Codec<List<RpgItemCost>> LIST_CODEC =
            CODEC.listOf(0, MAX_ENTRIES);

    public static UUID fingerprint(List<RpgItemCost> costs) {
        String canonical = costs.stream()
                .sorted(Comparator.comparing(RpgItemCost::item))
                .map(cost -> cost.item() + "x" + cost.count())
                .collect(java.util.stream.Collectors.joining("|"));
        return UUID.nameUUIDFromBytes(canonical.getBytes(StandardCharsets.UTF_8));
    }
}
