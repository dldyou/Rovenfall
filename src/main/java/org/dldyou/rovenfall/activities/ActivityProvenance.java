package org.dldyou.rovenfall.activities;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

public record ActivityProvenance(boolean natural, boolean mature, boolean firstDiscovery) {
    public static final Codec<ActivityProvenance> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.BOOL.optionalFieldOf("natural", false).forGetter(ActivityProvenance::natural),
            Codec.BOOL.optionalFieldOf("mature", false).forGetter(ActivityProvenance::mature),
            Codec.BOOL.optionalFieldOf("first_discovery", false).forGetter(ActivityProvenance::firstDiscovery)
    ).apply(instance, ActivityProvenance::new));

    public static ActivityProvenance explorationDiscovery() {
        return new ActivityProvenance(false, false, true);
    }
}
