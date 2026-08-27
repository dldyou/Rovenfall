package org.dldyou.rovenfall.rpg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.buffer.Unpooled;
import java.util.UUID;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

final class RpgSkillPayloadsTest {
    @Test
    void activationPayloadHasABoundedFixedShapeAndRoundTrips() {
        var payload = new RpgSkillPayloads.Activate(
                RpgSkillPayloads.PACKET_REVISION,
                15,
                92,
                3,
                Identifier.withDefaultNamespace("overworld"),
                123,
                new UUID(10, 20));
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            RpgSkillPayloads.Activate.STREAM_CODEC.encode(buffer, payload);
            assertTrue(buffer.readableBytes() <= RpgSkillPayloads.MAX_ACTIVATE_PACKET_BYTES);
            assertEquals(payload, RpgSkillPayloads.Activate.STREAM_CODEC.decode(buffer));
            assertEquals(0, buffer.readableBytes());
        } finally {
            buffer.release();
        }
    }

    @Test
    void stateSyncPayloadHasABoundedFixedShapeAndRoundTrips() {
        var payload = new RpgSkillPayloads.StateSync(
                RpgSkillPayloads.PACKET_REVISION,
                15,
                93,
                4,
                new UUID(30, 40));
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            RpgSkillPayloads.StateSync.STREAM_CODEC.encode(buffer, payload);
            assertTrue(buffer.readableBytes() <= RpgSkillPayloads.MAX_STATE_SYNC_PACKET_BYTES);
            assertEquals(payload, RpgSkillPayloads.StateSync.STREAM_CODEC.decode(buffer));
            assertEquals(0, buffer.readableBytes());
        } finally {
            buffer.release();
        }
    }
}
