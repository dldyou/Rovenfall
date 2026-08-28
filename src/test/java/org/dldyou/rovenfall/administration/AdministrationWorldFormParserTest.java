package org.dldyou.rovenfall.administration;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.Identifier;
import org.dldyou.rovenfall.claims.ClaimRole;
import org.dldyou.rovenfall.world.PortalDefinition;
import org.dldyou.rovenfall.world.ProtectedRegion;
import org.junit.jupiter.api.Test;

class AdministrationWorldFormParserTest {
    private static final UUID PLAYER_ID = UUID.fromString("4c54c1c8-96d5-4b16-bc2e-9288d4f9f9be");

    @Test
    void parsesClaimRoleSettingsAndReasonForms() {
        var role = AdministrationWorldFormParser.parseClaimRole(PLAYER_ID + ", manager |  trusted helper  ");
        var settings = AdministrationWorldFormParser.parseClaimSettings("true,false | close entry");
        var reason = AdministrationWorldFormParser.parseReasonOnly(" | confirm removal");

        assertEquals(Optional.of(new AdministrationWorldFormParser.ClaimRoleForm(
                PLAYER_ID, ClaimRole.MANAGER, "trusted helper")), role);
        assertEquals(Optional.of(new AdministrationWorldFormParser.ClaimSettingsForm(true, false, "close entry")), settings);
        assertEquals(Optional.of(new AdministrationWorldFormParser.ReasonForm("confirm removal")), reason);

        var target = AdministrationWorldFormParser.parseClaimTarget(
                "00000000-0000-0000-0000-000000000011 | remove trust").orElseThrow();
        assertEquals(new UUID(0L, 17L), target.playerId());
        assertEquals("remove trust", target.reason());
    }

    @Test
    void parsesRegionsAndRestoreAtTheirBounds() {
        var create = AdministrationWorldFormParser.parseRegionCreate(
                "rovenfall:spawn,minecraft:overworld," + -ProtectedRegion.MAX_ABSOLUTE_CHUNK + ",0,"
                        + (-ProtectedRegion.MAX_ABSOLUTE_CHUNK + ProtectedRegion.MAX_SIDE_CHUNKS - 1)
                        + "," + (ProtectedRegion.MAX_SIDE_CHUNKS - 1) + " | create spawn");
        var edit = AdministrationWorldFormParser.parseRegionEdit("minecraft:overworld,0,0,31,31 | resize");
        var restore = AdministrationWorldFormParser.parseRestore(PLAYER_ID + " | restore snapshot");

        assertTrue(create.isPresent());
        assertEquals(ProtectedRegion.MAX_SIDE_CHUNKS, create.orElseThrow().maxChunkX() - create.orElseThrow().minChunkX() + 1);
        assertEquals(Optional.of(new AdministrationWorldFormParser.RegionEditForm(
                Identifier.parse("minecraft:overworld"), 0, 0, 31, 31, "resize")), edit);
        assertEquals(Optional.of(new AdministrationWorldFormParser.RestoreForm(PLAYER_ID, "restore snapshot")), restore);
    }

    @Test
    void parsesPortalCreateAndEditWithExactSerializedPolicy() {
        var create = AdministrationWorldFormParser.parsePortalCreate(
                "rovenfall:hub,minecraft:overworld,0,64,0,rovenfall:wilderness,10,70,10,"
                        + PortalDefinition.MAX_PROTECTION_RADIUS_CHUNKS + ","
                        + PortalDefinition.MAX_COOLDOWN_MILLIS + ",nearest_safe,true | create portal");
        var edit = AdministrationWorldFormParser.parsePortalEdit(
                "minecraft:overworld,0,64,0,rovenfall:wilderness,10,70,10,0,0,exact,false | edit portal");

        assertTrue(create.isPresent());
        assertEquals(PortalDefinition.SafeArrivalPolicy.NEAREST_SAFE, create.orElseThrow().policy());
        assertEquals(PortalDefinition.MAX_COOLDOWN_MILLIS, create.orElseThrow().cooldownMillis());
        assertTrue(edit.isPresent());
        assertFalse(edit.orElseThrow().allowCombat());
    }

    @Test
    void rejectsOwnersMalformedDelimitersAndOutOfBoundsValues() {
        String tooLongReason = "x".repeat(AdministrationService.MAX_REASON_LENGTH + 1);

        assertFalse(AdministrationWorldFormParser.parseClaimRole(PLAYER_ID + ",owner | no").isPresent());
        assertFalse(AdministrationWorldFormParser.parseClaimSettings("True,false | not strict").isPresent());
        assertFalse(AdministrationWorldFormParser.parseRegionEdit("minecraft:overworld,0,0,32,0 | too wide").isPresent());
        assertFalse(AdministrationWorldFormParser.parsePortalCreate(
                "rovenfall:p,minecraft:overworld,0,40000000,0,rovenfall:wilderness,0,64,0,0,0,exact,false | outside").isPresent());
        assertFalse(AdministrationWorldFormParser.parsePortalEdit(
                "minecraft:overworld,0,64,0,rovenfall:wilderness,0,64,0,0,0,EXACT,false | wrong enum").isPresent());
        assertFalse(AdministrationWorldFormParser.parseRestore("not-a-uuid | invalid").isPresent());
        assertFalse(AdministrationWorldFormParser.parseReasonOnly("reason without delimiter").isPresent());
        assertFalse(AdministrationWorldFormParser.parseReasonOnly(" | " + tooLongReason).isPresent());
        assertFalse(AdministrationWorldFormParser.parsePortalEdit(
                "minecraft:overworld,0,64,0,rovenfall:wilderness,0,64,0,0,0,exact,false | one | two").isPresent());
        assertFalse(AdministrationWorldFormParser.parseClaimRole(PLAYER_ID + ",user\n | line break").isPresent());
    }

    @Test
    void enforcesWireBoundaryOverflowUuidAndExactFieldCounts() {
        String reason = " | boundary";
        String valid = PLAYER_ID + reason;
        String atLimit = valid + " ".repeat(AdministrationTextInputMenu.MAX_INPUT_LENGTH - valid.length());

        assertAll(
                () -> assertTrue(AdministrationWorldFormParser.parseClaimTarget(atLimit).isPresent()),
                () -> assertFalse(AdministrationWorldFormParser.parseClaimTarget(atLimit + " ").isPresent()),
                () -> assertFalse(AdministrationWorldFormParser.parseClaimRole("not-a-uuid,manager | bad").isPresent()),
                () -> assertFalse(AdministrationWorldFormParser.parseClaimTarget("not-a-uuid | bad").isPresent()),
                () -> assertFalse(AdministrationWorldFormParser.parseRegionEdit(
                        "minecraft:overworld,9223372036854775808,0,0,0 | overflow").isPresent()),
                () -> assertFalse(AdministrationWorldFormParser.parseClaimSettings("true | missing").isPresent()),
                () -> assertFalse(AdministrationWorldFormParser.parseRegionCreate(
                        "rovenfall:r,minecraft:overworld,0,0,0,0,extra | extra").isPresent()),
                () -> assertFalse(AdministrationWorldFormParser.parsePortalCreate(
                        "rovenfall:p,minecraft:overworld,0,64,0,rovenfall:wilderness,0,64,0,0,0,exact | missing").isPresent()),
                () -> assertFalse(AdministrationWorldFormParser.parsePortalEdit(
                        "minecraft:overworld,0,64,0,rovenfall:wilderness,0,64,0,0,0,exact,false,extra | extra").isPresent()));
    }
}
