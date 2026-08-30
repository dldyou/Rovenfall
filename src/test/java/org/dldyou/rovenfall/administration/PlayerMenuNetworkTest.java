package org.dldyou.rovenfall.administration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    void menuIdentityHasABoundedFixedShapeAndRoundTrips() {
        var payload = new PlayerMenuNetwork.MenuIdentity(
                41, 9_001, PlayerMenuNetwork.MenuKind.ADMIN_WORLD);
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            PlayerMenuNetwork.MenuIdentity.STREAM_CODEC.encode(buffer, payload);
            assertTrue(buffer.readableBytes() <= PlayerMenuNetwork.MAX_IDENTITY_PACKET_BYTES);
            assertEquals(payload, PlayerMenuNetwork.MenuIdentity.STREAM_CODEC.decode(buffer));
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
    void inventorySummaryPayloadsAreBoundedAndDoNotCarryTechnicalPlayerIdentity() {
        var request = new PlayerMenuNetwork.InventorySummaryRequest(true);
        var requestBuffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            PlayerMenuNetwork.InventorySummaryRequest.STREAM_CODEC.encode(requestBuffer, request);
            assertTrue(requestBuffer.readableBytes() <= PlayerMenuNetwork.MAX_INVENTORY_SUMMARY_REQUEST_PACKET_BYTES);
            assertEquals(request, PlayerMenuNetwork.InventorySummaryRequest.STREAM_CODEC.decode(requestBuffer));
            assertEquals(0, requestBuffer.readableBytes());
        } finally {
            requestBuffer.release();
        }

        var response = new PlayerMenuNetwork.InventorySummary(125L, "career.rovenfall.novice", true);
        var responseBuffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            PlayerMenuNetwork.InventorySummary.STREAM_CODEC.encode(responseBuffer, response);
            assertTrue(responseBuffer.readableBytes() <= PlayerMenuNetwork.MAX_INVENTORY_SUMMARY_PACKET_BYTES);
            assertEquals(response, PlayerMenuNetwork.InventorySummary.STREAM_CODEC.decode(responseBuffer));
            assertEquals(0, responseBuffer.readableBytes());
        } finally {
            responseBuffer.release();
        }
        assertFalse(new PlayerMenuNetwork.InventorySummary(125L,
                "x".repeat(PlayerMenuNetwork.MAX_CAREER_TRANSLATION_KEY_LENGTH + 1), false).isValid());
        assertThrows(RuntimeException.class, () -> {
            var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
            try {
                PlayerMenuNetwork.InventorySummary.STREAM_CODEC.encode(buffer,
                        new PlayerMenuNetwork.InventorySummary(125L,
                                "x".repeat(PlayerMenuNetwork.MAX_CAREER_TRANSLATION_KEY_LENGTH + 1), false));
            } finally {
                buffer.release();
            }
        });
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

        assertTrue(PlayerMenuNetwork.canRequestInventorySummary(null, 100));
        assertFalse(PlayerMenuNetwork.canRequestInventorySummary(100L, 104));
        assertTrue(PlayerMenuNetwork.canRequestInventorySummary(100L, 105));
    }

    @Test
    void inventorySummaryRequiresTheCurrentSurvivalInventoryContext() {
        assertTrue(PlayerMenuNetwork.isValidInventorySummaryContext(
                PlayerMenuNetwork.PACKET_REVISION, true, false, false, true, false));
        assertFalse(PlayerMenuNetwork.isValidInventorySummaryContext(
                PlayerMenuNetwork.PACKET_REVISION + 1, true, false, false, true, false));
        assertFalse(PlayerMenuNetwork.isValidInventorySummaryContext(
                PlayerMenuNetwork.PACKET_REVISION, false, false, false, true, false));
        assertFalse(PlayerMenuNetwork.isValidInventorySummaryContext(
                PlayerMenuNetwork.PACKET_REVISION, true, true, false, true, false));
        assertFalse(PlayerMenuNetwork.isValidInventorySummaryContext(
                PlayerMenuNetwork.PACKET_REVISION, true, false, true, true, false));
        assertFalse(PlayerMenuNetwork.isValidInventorySummaryContext(
                PlayerMenuNetwork.PACKET_REVISION, true, false, false, false, false));
        assertTrue(PlayerMenuNetwork.isValidInventorySummaryContext(
                PlayerMenuNetwork.PACKET_REVISION, true, false, false, false, true));
    }

    @Test
    void menuKindsRejectUnknownWireIdsAndDescribeAdministrationInput() {
        assertEquals(PlayerMenuNetwork.MenuKind.DASHBOARD,
                PlayerMenuNetwork.MenuKind.fromWireId(0).orElseThrow());
        assertEquals(PlayerMenuNetwork.MenuKind.ADMIN_OPERATIONS,
                PlayerMenuNetwork.MenuKind.fromWireId(8).orElseThrow());
        assertTrue(PlayerMenuNetwork.MenuKind.fromWireId(99).isEmpty());
        assertFalse(PlayerMenuNetwork.MenuKind.DASHBOARD.isAdministration());
        assertTrue(PlayerMenuNetwork.MenuKind.ADMIN_HOME.isAdministration());
        assertFalse(PlayerMenuNetwork.MenuKind.ADMIN_HOME.usesLongTextInput());
        assertTrue(PlayerMenuNetwork.MenuKind.ADMIN_ECONOMY.usesLongTextInput());
        assertFalse(PlayerMenuNetwork.isPlayerMenu(null));
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
