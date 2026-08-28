package org.dldyou.rovenfall.administration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.buffer.Unpooled;
import java.util.UUID;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.inventory.ContainerInput;
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
    void administrationSearchCarriesBoundedQueryAndSessionEvidence() {
        var payload = new PlayerMenuNetwork.AdminQuery(
                7, 101, "가".repeat(AdministrationTextInputMenu.MAX_INPUT_LENGTH));
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            PlayerMenuNetwork.AdminQuery.STREAM_CODEC.encode(buffer, payload);
            assertTrue(buffer.readableBytes() <= PlayerMenuNetwork.MAX_QUERY_PACKET_BYTES);
            assertEquals(payload, PlayerMenuNetwork.AdminQuery.STREAM_CODEC.decode(buffer));
            assertEquals(0, buffer.readableBytes());
        } finally {
            buffer.release();
        }
    }

    @Test
    void rejectsUnknownTargetsAndBoundsRepeatedOpenRequests() {
        assertTrue(PlayerMenuNetwork.MenuTarget.fromWireId(0).isPresent());
        assertTrue(PlayerMenuNetwork.MenuTarget.fromWireId(3).isPresent());
        assertTrue(PlayerMenuNetwork.MenuTarget.fromWireId(4).isPresent());
        assertTrue(PlayerMenuNetwork.MenuTarget.fromWireId(99).isEmpty());

        assertTrue(PlayerMenuNetwork.canOpen(null, 100));
        assertFalse(PlayerMenuNetwork.canOpen(100L, 100));
        assertFalse(PlayerMenuNetwork.canOpen(100L, 104));
        assertTrue(PlayerMenuNetwork.canOpen(100L, 105));
        assertTrue(PlayerMenuNetwork.canOpen(100L, 90));
    }

    @Test
    void acceptsOnlyPrimaryPickupClicksAndCurrentServerIssuedSessionState() {
        assertTrue(PlayerMenuNetwork.isPrimaryAction(0, ContainerInput.PICKUP));
        assertFalse(PlayerMenuNetwork.isPrimaryAction(1, ContainerInput.PICKUP));
        assertFalse(PlayerMenuNetwork.isPrimaryAction(0, ContainerInput.QUICK_MOVE));

        assertTrue(PlayerMenuNetwork.isCurrentSession(7, 101, 7, 101));
        assertFalse(PlayerMenuNetwork.isCurrentSession(7, 101, 8, 101));
        assertFalse(PlayerMenuNetwork.isCurrentSession(7, 101, 7, 100));
        int stateId = PlayerMenuNetwork.sessionStateId(UUID.randomUUID());
        assertTrue(stateId >= 1 && stateId <= 32_767);
    }
}
