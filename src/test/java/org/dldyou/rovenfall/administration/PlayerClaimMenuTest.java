package org.dldyou.rovenfall.administration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.dldyou.rovenfall.claims.Claim;
import org.dldyou.rovenfall.claims.ClaimKey;
import org.dldyou.rovenfall.claims.ClaimRole;
import org.dldyou.rovenfall.claims.ClaimSettings;
import org.junit.jupiter.api.Test;

final class PlayerClaimMenuTest {
    private static final ClaimKey KEY = ClaimKey.at(Level.OVERWORLD, new BlockPos(32, 70, 32));

    @Test
    void exposesOnlyActionsAuthorizedByTheOwningClaimServices() {
        UUID owner = id(1);
        UUID manager = id(2);
        UUID builder = id(3);
        UUID user = id(4);
        UUID visitor = id(5);
        UUID recipient = id(6);
        UUID moderator = id(7);
        PlatformSavedData state = new PlatformSavedData();
        assertEquals(AdministrationService.RoleChangeStatus.SUCCESS, AdministrationService.changeRole(
                state, AdministrationService.SYSTEM_ACTOR, true, moderator, AdminRole.MODERATOR.getSerializedName(),
                "claim GUI test", 1_000, id(100)).status());

        Claim claim = new Claim(owner, 1_000)
                .withRole(manager, ClaimRole.MANAGER)
                .withRole(builder, ClaimRole.BUILDER)
                .withRole(user, ClaimRole.USER);

        assertEquals(EnumSet.of(
                        PlayerClaimMenu.PermissionAction.VIEW_TRUSTED,
                        PlayerClaimMenu.PermissionAction.MANAGE_TRUST,
                        PlayerClaimMenu.PermissionAction.MANAGE_SETTINGS,
                        PlayerClaimMenu.PermissionAction.OFFER_TRANSFER,
                        PlayerClaimMenu.PermissionAction.SELL),
                PlayerClaimMenu.allowedActions(state, owner, claim));
        assertEquals(EnumSet.of(
                        PlayerClaimMenu.PermissionAction.VIEW_TRUSTED,
                        PlayerClaimMenu.PermissionAction.MANAGE_TRUST,
                        PlayerClaimMenu.PermissionAction.MANAGE_SETTINGS),
                PlayerClaimMenu.allowedActions(state, manager, claim));
        assertEquals(EnumSet.of(PlayerClaimMenu.PermissionAction.VIEW_TRUSTED),
                PlayerClaimMenu.allowedActions(state, builder, claim));
        assertEquals(EnumSet.of(PlayerClaimMenu.PermissionAction.VIEW_TRUSTED),
                PlayerClaimMenu.allowedActions(state, user, claim));
        assertEquals(EnumSet.of(PlayerClaimMenu.PermissionAction.VIEW_TRUSTED),
                PlayerClaimMenu.allowedActions(state, visitor, claim));
        assertEquals(EnumSet.of(
                        PlayerClaimMenu.PermissionAction.VIEW_TRUSTED,
                        PlayerClaimMenu.PermissionAction.MANAGE_TRUST,
                        PlayerClaimMenu.PermissionAction.MANAGE_SETTINGS),
                PlayerClaimMenu.allowedActions(state, moderator, claim));

        Claim pending = claim.withPendingTransfer(recipient);
        assertTrue(PlayerClaimMenu.allowedActions(state, owner, pending)
                .contains(PlayerClaimMenu.PermissionAction.CANCEL_TRANSFER));
        assertFalse(PlayerClaimMenu.allowedActions(state, owner, pending)
                .contains(PlayerClaimMenu.PermissionAction.SELL));
        assertTrue(PlayerClaimMenu.allowedActions(state, recipient, pending)
                .contains(PlayerClaimMenu.PermissionAction.ACCEPT_TRANSFER));
    }

    @Test
    void boundsServerProvidedCandidatesAndKeepsUuidIdentity() {
        UUID owner = id(1);
        UUID excluded = id(2);
        List<UUID> online = new ArrayList<>();
        online.add(owner);
        online.add(excluded);
        for (long value = 50; value >= 3; value--) {
            online.add(id(value));
        }
        online.add(id(3));

        List<UUID> candidates = PlayerClaimMenu.boundedCandidateIds(
                online, owner, Set.of(excluded));

        assertEquals(PlayerClaimMenu.MAX_CANDIDATES, candidates.size());
        assertFalse(candidates.contains(owner));
        assertFalse(candidates.contains(excluded));
        assertEquals(id(3), candidates.getFirst());
        assertEquals(id(38), candidates.getLast());
        assertEquals(candidates.size(), Set.copyOf(candidates).size());
    }

    @Test
    void rejectsChangedChunkOwnershipPriceAndTransferStateBeforeConfirmation() {
        UUID owner = id(1);
        Claim claim = new Claim(owner, 1_000);
        var purchase = new PlayerClaimMenu.Confirmation(
                PlayerClaimMenu.ConfirmationKind.PURCHASE, KEY, Optional.empty(), 1_000, null);

        assertTrue(PlayerClaimMenu.confirmationIsCurrent(
                KEY, Optional.empty(), purchase, Optional.of(1_000L)));
        assertFalse(PlayerClaimMenu.confirmationIsCurrent(
                new ClaimKey(Level.OVERWORLD, 3, 3), Optional.empty(), purchase, Optional.of(1_000L)));
        assertFalse(PlayerClaimMenu.confirmationIsCurrent(
                KEY, Optional.of(claim), purchase, Optional.of(1_000L)));
        assertFalse(PlayerClaimMenu.confirmationIsCurrent(
                KEY, Optional.empty(), purchase, Optional.of(1_250L)));

        Claim pending = claim.withPendingTransfer(id(2));
        var accept = new PlayerClaimMenu.Confirmation(
                PlayerClaimMenu.ConfirmationKind.TRANSFER_ACCEPT,
                KEY, Optional.of(pending), 0, id(2));
        assertTrue(PlayerClaimMenu.confirmationIsCurrent(
                KEY, Optional.of(pending), accept, Optional.empty()));
        assertFalse(PlayerClaimMenu.confirmationIsCurrent(
                KEY, Optional.of(pending.withSettings(new ClaimSettings(true, false))),
                accept, Optional.empty()));
    }

    @Test
    void everyRejectedServiceStatusHasALocalizedPlayerMessage() {
        for (ClaimPurchaseService.Status status : ClaimPurchaseService.Status.values()) {
            if (status != ClaimPurchaseService.Status.SUCCESS
                    && status != ClaimPurchaseService.Status.DUPLICATE_TRANSACTION) {
                assertTrue(PlayerClaimMenu.purchaseErrorTranslationKey(status)
                        .startsWith("command.rovenfall.claim."), status.name());
            }
        }
        for (ClaimManagementService.Status status : ClaimManagementService.Status.values()) {
            if (status != ClaimManagementService.Status.SUCCESS
                    && status != ClaimManagementService.Status.DUPLICATE_TRANSACTION
                    && status != ClaimManagementService.Status.NO_CHANGE) {
                assertTrue(PlayerClaimMenu.mutationErrorTranslationKey(status)
                        .startsWith("command.rovenfall.claim."), status.name());
            }
        }
    }

    private static UUID id(long value) {
        return new UUID(0, value);
    }
}
