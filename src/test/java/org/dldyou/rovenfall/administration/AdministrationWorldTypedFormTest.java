package org.dldyou.rovenfall.administration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.dldyou.rovenfall.claims.ClaimRole;
import org.dldyou.rovenfall.world.PortalDefinition;
import org.dldyou.rovenfall.world.ProtectedRegion;
import org.junit.jupiter.api.Test;

final class AdministrationWorldTypedFormTest {
    private static final UUID TRANSACTION = UUID.fromString("12345678-1234-5678-9abc-def012345678");
    private static final UUID PLAYER = UUID.fromString("4c54c1c8-96d5-4b16-bc2e-9288d4f9f9be");
    private static final Identifier OVERWORLD = Identifier.parse("minecraft:overworld");
    private static final Identifier WILDERNESS = Identifier.parse("rovenfall:wilderness");

    @Test
    void combinesSelectedPlayerWithOrdinaryClaimControls() {
        var role = AdministrationWorldTypedForm.claimRole(PLAYER, List.of("manager", "trusted builder"));
        var untrust = AdministrationWorldTypedForm.claimUntrust(PLAYER, List.of("remove access"));
        var settings = AdministrationWorldTypedForm.claimSettings(List.of("true", "false", "event closure"));

        assertEquals(PLAYER, role.orElseThrow().playerId());
        assertEquals(ClaimRole.MANAGER, role.orElseThrow().role());
        assertEquals(PLAYER, untrust.orElseThrow().playerId());
        assertTrue(settings.orElseThrow().entryRestricted());
        assertFalse(settings.orElseThrow().publicInteractions());
        assertTrue(AdministrationWorldTypedForm.claimRole(null, List.of("manager", "reason")).isEmpty());
    }

    @Test
    void serverGeneratesRegionAndPortalIdsFromTheTransaction() {
        var region = AdministrationWorldTypedForm.regionCreate(
                TRANSACTION, OVERWORLD, List.of("0,0", "3,3", "protect spawn")).orElseThrow();
        assertEquals("rovenfall:managed/region/12345678123456789abcdef012345678", region.regionId().toString());
        assertEquals(OVERWORLD, region.dimensionId());

        var portal = AdministrationWorldTypedForm.portalCreate(
                TRANSACTION, OVERWORLD, WILDERNESS,
                List.of("0,64,0", "10,70,10", "2", "5000", "nearest_safe", "false", "connect worlds"))
                .orElseThrow();
        assertEquals("rovenfall:managed/portal/12345678123456789abcdef012345678", portal.portalId().toString());
        assertEquals(OVERWORLD, portal.originDimensionId());
        assertEquals(WILDERNESS, portal.destinationDimensionId());
    }

    @Test
    void editDefaultsRoundTripAndInvalidOrIncompletePositionsFail() {
        var region = new ProtectedRegion(
                PLAYER, ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, OVERWORLD), 1, 2, 3, 4);
        assertEquals(List.of("1,2", "3,4", ""), AdministrationWorldTypedForm.regionDefaults(region));

        var portal = new PortalDefinition(
                PLAYER,
                new PortalDefinition.Endpoint(Level.OVERWORLD, new BlockPos(1, 64, 2)),
                new PortalDefinition.Endpoint(ResourceKey.create(
                        net.minecraft.core.registries.Registries.DIMENSION, WILDERNESS), new BlockPos(3, 70, 4)),
                2, 5_000, PortalDefinition.SafeArrivalPolicy.NEAREST_SAFE, false);
        assertEquals(List.of("1,64,2", "3,70,4", "2", "5000", "nearest_safe", "false", ""),
                AdministrationWorldTypedForm.portalDefaults(portal));

        assertTrue(AdministrationWorldTypedForm.regionCreate(
                TRANSACTION, OVERWORLD, List.of("", "3,3", "reason")).isEmpty());
        assertTrue(AdministrationWorldTypedForm.portalCreate(
                TRANSACTION, OVERWORLD, WILDERNESS,
                List.of("", "3,70,4", "2", "5000", "nearest_safe", "false", "reason")).isEmpty());
    }
}
