package org.dldyou.rovenfall.claims;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

public record ClaimSettings(boolean entryRestricted, boolean publicInteractions) {
    public static final Codec<ClaimSettings> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.BOOL.optionalFieldOf("entry_restricted", false).forGetter(ClaimSettings::entryRestricted),
            Codec.BOOL.optionalFieldOf("public_interactions", false).forGetter(ClaimSettings::publicInteractions)
    ).apply(instance, ClaimSettings::new));

    public static ClaimSettings defaults() {
        return new ClaimSettings(false, false);
    }
}
