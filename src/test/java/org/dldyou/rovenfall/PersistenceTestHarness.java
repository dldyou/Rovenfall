package org.dldyou.rovenfall;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.mojang.serialization.Codec;
import net.minecraft.nbt.NbtOps;
import org.junit.jupiter.api.Test;

final class PersistenceTestHarness {
    static <T> T roundTrip(Codec<T> codec, T value) {
        var encoded = codec.encodeStart(NbtOps.INSTANCE, value).getOrThrow();
        return codec.parse(NbtOps.INSTANCE, encoded).getOrThrow();
    }

    @Test
    void roundTripsLongValuesThroughNbt() {
        assertEquals(Long.MAX_VALUE, roundTrip(Codec.LONG, Long.MAX_VALUE));
    }
}
