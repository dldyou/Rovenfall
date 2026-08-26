package org.dldyou.rovenfall.claims;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;

public record Claim(UUID ownerId) {
    public static final int MAX_CLAIMS = 100_000;
    public static final Codec<Claim> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            UUIDUtil.STRING_CODEC.fieldOf("owner").forGetter(Claim::ownerId)
    ).apply(instance, Claim::new));

    public Claim {
        if (ownerId == null) {
            throw new IllegalArgumentException("Claim owner is missing");
        }
    }
}
