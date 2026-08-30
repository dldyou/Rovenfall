package org.dldyou.rovenfall.administration;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.Identifier;
import org.dldyou.rovenfall.world.PortalDefinition;
import org.dldyou.rovenfall.world.ProtectedRegion;

/** Combines ordinary form values with the world selections held by the authoritative menu. */
final class AdministrationWorldTypedForm {
    private AdministrationWorldTypedForm() {
    }

    static Optional<AdministrationWorldFormParser.ClaimRoleForm> claimRole(UUID playerId, List<String> values) {
        if (playerId == null || !AdministrationFormType.WORLD_CLAIM_ROLE.accepts(values)) {
            return Optional.empty();
        }
        return AdministrationWorldFormParser.parseClaimRole(
                playerId + "," + values.get(0) + " | " + values.get(1));
    }

    static Optional<AdministrationWorldFormParser.ClaimTargetForm> claimUntrust(
            UUID playerId, List<String> values) {
        if (playerId == null || !AdministrationFormType.WORLD_CLAIM_UNTRUST.accepts(values)) {
            return Optional.empty();
        }
        return AdministrationWorldFormParser.parseClaimTarget(playerId + " | " + values.getFirst());
    }

    static Optional<AdministrationWorldFormParser.ClaimSettingsForm> claimSettings(List<String> values) {
        if (!AdministrationFormType.WORLD_CLAIM_SETTINGS.accepts(values)) {
            return Optional.empty();
        }
        return AdministrationWorldFormParser.parseClaimSettings(
                values.get(0) + "," + values.get(1) + " | " + values.get(2));
    }

    static Optional<AdministrationWorldFormParser.RegionCreateForm> regionCreate(
            UUID transactionId, Identifier dimension, List<String> values) {
        if (transactionId == null || dimension == null
                || !AdministrationFormType.WORLD_REGION_CREATE.accepts(values)) {
            return Optional.empty();
        }
        Identifier regionId = AdministrationGeneratedIdentifier.fromTransaction("region", transactionId);
        return AdministrationWorldFormParser.parseRegionCreate(
                regionId + "," + dimension + "," + bounds(values) + " | " + values.get(2));
    }

    static Optional<AdministrationWorldFormParser.RegionEditForm> regionEdit(
            Identifier dimension, List<String> values) {
        if (dimension == null || !AdministrationFormType.WORLD_REGION_EDIT.accepts(values)) {
            return Optional.empty();
        }
        return AdministrationWorldFormParser.parseRegionEdit(
                dimension + "," + bounds(values) + " | " + values.get(2));
    }

    static Optional<AdministrationWorldFormParser.PortalCreateForm> portalCreate(
            UUID transactionId,
            Identifier originDimension,
            Identifier destinationDimension,
            List<String> values) {
        if (transactionId == null || originDimension == null || destinationDimension == null
                || !AdministrationFormType.WORLD_PORTAL_CREATE.accepts(values)) {
            return Optional.empty();
        }
        Identifier portalId = AdministrationGeneratedIdentifier.fromTransaction("portal", transactionId);
        return AdministrationWorldFormParser.parsePortalCreate(
                portalId + "," + portalFields(originDimension, destinationDimension, values));
    }

    static Optional<AdministrationWorldFormParser.PortalEditForm> portalEdit(
            Identifier originDimension,
            Identifier destinationDimension,
            List<String> values) {
        if (originDimension == null || destinationDimension == null
                || !AdministrationFormType.WORLD_PORTAL_EDIT.accepts(values)) {
            return Optional.empty();
        }
        return AdministrationWorldFormParser.parsePortalEdit(portalFields(originDimension, destinationDimension, values));
    }

    static List<String> regionDefaults(ProtectedRegion region) {
        return List.of(
                region.minChunkX() + "," + region.minChunkZ(),
                region.maxChunkX() + "," + region.maxChunkZ(),
                "");
    }

    static List<String> portalDefaults(PortalDefinition portal) {
        return List.of(
                position(portal.origin().position()),
                position(portal.destination().position()),
                Integer.toString(portal.protectionRadiusChunks()),
                Long.toString(portal.cooldownMillis()),
                portal.safeArrivalPolicy().getSerializedName(),
                Boolean.toString(portal.allowCombat()),
                "");
    }

    private static String bounds(List<String> values) {
        return values.get(0) + "," + values.get(1);
    }

    private static String portalFields(
            Identifier originDimension, Identifier destinationDimension, List<String> values) {
        return originDimension + "," + values.get(0) + ","
                + destinationDimension + "," + values.get(1) + ","
                + values.get(2) + "," + values.get(3) + "," + values.get(4) + ","
                + values.get(5) + " | " + values.get(6);
    }

    private static String position(net.minecraft.core.BlockPos position) {
        return position.getX() + "," + position.getY() + "," + position.getZ();
    }
}
