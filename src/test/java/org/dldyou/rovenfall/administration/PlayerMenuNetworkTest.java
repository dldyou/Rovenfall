package org.dldyou.rovenfall.administration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.Test;

final class PlayerMenuNetworkTest {
    @Test
    void openPayloadHasABoundedFixedShapeAndRoundTrips() {
        var payload = new PlayerMenuNetwork.Open(PlayerMenuNetwork.MenuTarget.CLAIMS);
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            PlayerMenuNetwork.Open.STREAM_CODEC.encode(buffer, payload);
            assertTrue(buffer.readableBytes() <= PlayerMenuNetwork.MAX_OPEN_PACKET_BYTES);
            assertEquals(payload, PlayerMenuNetwork.Open.STREAM_CODEC.decode(buffer));
            assertEquals(0, buffer.readableBytes());
        } finally {
            buffer.release();
        }
    }

    @Test
    void rejectsUnknownTargetsAndBoundsRepeatedOpenRequests() {
        assertTrue(PlayerMenuNetwork.MenuTarget.fromWireId(0).isPresent());
        assertTrue(PlayerMenuNetwork.MenuTarget.fromWireId(3).isPresent());
        assertTrue(PlayerMenuNetwork.MenuTarget.fromWireId(99).isEmpty());

        assertTrue(PlayerMenuNetwork.canOpen(null, 100));
        assertFalse(PlayerMenuNetwork.canOpen(100L, 100));
        assertFalse(PlayerMenuNetwork.canOpen(100L, 104));
        assertTrue(PlayerMenuNetwork.canOpen(100L, 105));
        assertTrue(PlayerMenuNetwork.canOpen(100L, 90));
    }
}
