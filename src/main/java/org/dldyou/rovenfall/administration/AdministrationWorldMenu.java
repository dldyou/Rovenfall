package org.dldyou.rovenfall.administration;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import org.dldyou.rovenfall.claims.Claim;
import org.dldyou.rovenfall.claims.ClaimKey;
import org.dldyou.rovenfall.claims.ClaimRole;
import org.dldyou.rovenfall.claims.ClaimSettings;
import org.dldyou.rovenfall.world.PortalDefinition;
import org.dldyou.rovenfall.world.ProtectedRegion;

/** Server-authoritative inventory workflow for claims, portals, protected regions, and Wilderness recovery. */
public final class AdministrationWorldMenu extends ChestMenu implements AdministrationTextInputMenu {
    static final int MENU_SIZE = 54;
    static final int CONTENT_START = 9;
    static final int CONTENT_SIZE = 36;
    static final int BACK_SLOT = 45;
    static final int PRIMARY_SLOT = 46;
    static final int PREVIOUS_SLOT = 47;
    static final int SECONDARY_SLOT = 48;
    static final int CENTER_SLOT = 49;
    static final int TERTIARY_SLOT = 50;
    static final int NEXT_SLOT = 51;
    static final int DANGER_SLOT = 52;
    static final int REFRESH_SLOT = 53;
    static final int CONFIRM_SLOT = 31;
    static final int CANCEL_SLOT = 33;

    private final ServerPlayer viewer;
    private final UUID viewerId;
    private final SimpleContainer contents;
    private final AdministrationReadViewService.Domain entryDomain;
    private Mode mode;
    private Mode returnMode;
    private FormKind formKind;
    private String query = "";
    private String formError = "";
    private int page;
    private ClaimKey selectedClaim;
    private net.minecraft.resources.Identifier selectedRegion;
    private net.minecraft.resources.Identifier selectedPortal;
    private WildernessResetState.Evidence selectedEvidence;
    private UUID selectedSnapshot;
    private UUID selectedClaimTarget;
    private net.minecraft.resources.Identifier selectedRegionDimension;
    private net.minecraft.resources.Identifier selectedPortalOriginDimension;
    private net.minecraft.resources.Identifier selectedPortalDestinationDimension;
    private AdministrationWorldActionService.PendingAction pending;
    private AdministrationWorldActionService.Result result;
    private long lastHandledGameTime = Long.MIN_VALUE;

    private AdministrationWorldMenu(
            int containerId,
            Inventory inventory,
            ServerPlayer viewer,
            SimpleContainer contents,
            AdministrationReadViewService.Domain entryDomain) {
        super(RovenfallAdministrationMenus.WORLD.get(), containerId, inventory, contents, 6);
        this.viewer = viewer;
        this.viewerId = viewer.getUUID();
        this.contents = contents;
        this.entryDomain = entryDomain;
        this.mode = entryDomain == AdministrationReadViewService.Domain.CLAIMS ? Mode.CLAIMS : Mode.PORTALS;
        render();
        PlayerMenuNetwork.seedMenuSession(this, UUID.randomUUID());
    }

    public static boolean open(ServerPlayer player, AdministrationReadViewService.Domain domain) {
        if (player == null || domain == null
                || domain != AdministrationReadViewService.Domain.CLAIMS
                        && domain != AdministrationReadViewService.Domain.PORTALS
                || !canView(player, domain)) {
            return false;
        }
        player.openMenu(new SimpleMenuProvider(
                (containerId, inventory, viewer) -> new AdministrationWorldMenu(
                        containerId, inventory, (ServerPlayer) viewer, new SimpleContainer(MENU_SIZE), domain),
                Component.translatable("gui.rovenfall.admin.world.title")))
                .ifPresent(ignored -> PlayerMenuNetwork.sendMenuIdentity(player));
        return true;
    }

    @Override
    public void clicked(int slotIndex, int buttonNum, ContainerInput input, Player player) {
        if (!(player instanceof ServerPlayer serverPlayer)
                || !viewerId.equals(serverPlayer.getUUID())
                || slotIndex < 0 || slotIndex >= MENU_SIZE
                || !PlayerMenuNetwork.isPrimaryAction(buttonNum, input)) {
            return;
        }
        long gameTime = viewer.level().getGameTime();
        if (!PlayerDashboardMenu.canHandleClick(lastHandledGameTime, gameTime)) {
            return;
        }
        lastHandledGameTime = gameTime;
        if (!canView(viewer, entryDomain)) {
            denyAndClose();
            return;
        }
        if (slotIndex == REFRESH_SLOT) {
            render();
            return;
        }
        if (slotIndex == BACK_SLOT) {
            back();
            return;
        }
        switch (mode) {
            case CLAIMS -> clickClaims(slotIndex);
            case CLAIM_DETAIL -> clickClaimDetail(slotIndex);
            case REGIONS -> clickRegions(slotIndex);
            case REGION_DETAIL -> clickRegionDetail(slotIndex);
            case PORTALS -> clickPortals(slotIndex);
            case PORTAL_DETAIL -> clickPortalDetail(slotIndex);
            case WILDERNESS -> clickWilderness(slotIndex);
            case EVIDENCE -> clickEvidence(slotIndex);
            case EVIDENCE_DETAIL -> clickEvidenceDetail(slotIndex);
            case CLAIM_TARGET_SELECT -> clickClaimTargetSelect(slotIndex);
            case REGION_DIMENSION_SELECT -> clickRegionDimensionSelect(slotIndex);
            case PORTAL_ORIGIN_DIMENSION_SELECT -> clickPortalOriginDimensionSelect(slotIndex);
            case PORTAL_DESTINATION_DIMENSION_SELECT -> clickPortalDestinationDimensionSelect(slotIndex);
            case FORM -> {
            }
            case PREVIEW -> {
                if (slotIndex == CONFIRM_SLOT) {
                    confirm();
                } else if (slotIndex == CANCEL_SLOT) {
                    cancelForm();
                }
            }
            case RESULT -> {
                if (slotIndex == CONFIRM_SLOT) {
                    finishResult();
                }
            }
        }
    }

    @Override
    public boolean applyTextInput(ServerPlayer player, String input) {
        if (!viewerId.equals(player.getUUID()) || input == null
                || input.length() > AdministrationTextInputMenu.MAX_INPUT_LENGTH
                || !canView(viewer, entryDomain)) {
            return false;
        }
        if (mode == Mode.FORM) {
            return parseForm(input);
        }
        if (mode != Mode.CLAIMS && mode != Mode.REGIONS && mode != Mode.PORTALS && mode != Mode.EVIDENCE
                && mode != Mode.CLAIM_TARGET_SELECT && mode != Mode.REGION_DIMENSION_SELECT
                && mode != Mode.PORTAL_ORIGIN_DIMENSION_SELECT && mode != Mode.PORTAL_DESTINATION_DIMENSION_SELECT) {
            return false;
        }
        if (input.length() > AdministrationReadViewService.MAX_QUERY_LENGTH) {
            formError = "query_too_long";
            render();
            return false;
        }
        query = input.strip();
        page = 0;
        formError = "";
        render();
        return true;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slotIndex) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        return player.isAlive() && viewerId.equals(player.getUUID()) && canView(viewer, entryDomain);
    }

    static boolean canManageClaims(AdminRole role) {
        return role == AdminRole.MODERATOR || role == AdminRole.OWNER;
    }

    static boolean canManageRegions(AdminRole role) {
        return role == AdminRole.OWNER;
    }

    static boolean canManagePortals(AdminRole role) {
        return role == AdminRole.CONTENT_MANAGER || role == AdminRole.OWNER;
    }

    static boolean canManageWilderness(AdminRole role) {
        return role == AdminRole.OWNER;
    }

    private static boolean canView(ServerPlayer player, AdministrationReadViewService.Domain domain) {
        return AdministrationControlCenterMenu.resolveRole(player).filter(domain::allowedFor).isPresent();
    }

    private AdminRole currentRole() {
        return AdministrationControlCenterMenu.resolveRole(viewer).orElse(null);
    }

    private void clickClaims(int slot) {
        if (slot == PRIMARY_SLOT) {
            mode = Mode.REGIONS;
            query = "";
            page = 0;
        } else if (slot == PREVIOUS_SLOT) {
            page = Math.max(0, page - 1);
        } else if (slot == NEXT_SLOT) {
            page++;
        } else if (slot >= CONTENT_START && slot < CONTENT_START + CONTENT_SIZE) {
            var resultPage = claimsPage();
            int index = slot - CONTENT_START;
            if (index < resultPage.entries().size()) {
                selectedClaim = resultPage.entries().get(index).key();
                mode = Mode.CLAIM_DETAIL;
                page = 0;
            }
        } else {
            return;
        }
        render();
    }

    private void clickClaimDetail(int slot) {
        if (selectedClaim == null || state().claim(selectedClaim).isEmpty()) {
            mode = Mode.CLAIMS;
            render();
            return;
        }
        int trustedPages = Math.max(1, (state().claim(selectedClaim).orElseThrow().trustedRoles().size()
                + CONTENT_SIZE - 1) / CONTENT_SIZE);
        if (slot == PREVIOUS_SLOT) {
            page = Math.max(0, page - 1);
            render();
            return;
        }
        if (slot == NEXT_SLOT && page + 1 < trustedPages) {
            page++;
            render();
            return;
        }
        if (!canManageClaims(currentRole())) {
            return;
        }
        if (slot == PRIMARY_SLOT) {
            selectedClaimTarget = null;
            query = "";
            page = 0;
            formKind = FormKind.CLAIM_ROLE;
            mode = Mode.CLAIM_TARGET_SELECT;
            render();
        } else if (slot == SECONDARY_SLOT) {
            selectedClaimTarget = null;
            query = "";
            page = 0;
            mode = Mode.CLAIM_TARGET_SELECT;
            formKind = FormKind.CLAIM_UNTRUST;
            render();
        } else if (slot == TERTIARY_SLOT) {
            enterForm(FormKind.CLAIM_SETTINGS, Mode.CLAIM_DETAIL);
        } else if (slot == DANGER_SLOT) {
            enterForm(FormKind.CLAIM_RECLAIM, Mode.CLAIM_DETAIL);
        }
    }

    private void clickRegions(int slot) {
        if (slot == PRIMARY_SLOT && canManageRegions(currentRole())) {
            selectedRegionDimension = null;
            query = "";
            page = 0;
            mode = Mode.REGION_DIMENSION_SELECT;
            render();
            return;
        }
        if (slot == PREVIOUS_SLOT) {
            page = Math.max(0, page - 1);
        } else if (slot == NEXT_SLOT) {
            page++;
        } else if (slot >= CONTENT_START && slot < CONTENT_START + CONTENT_SIZE) {
            var resultPage = regionsPage();
            int index = slot - CONTENT_START;
            if (index < resultPage.entries().size()) {
                selectedRegion = resultPage.entries().get(index).regionId();
                mode = Mode.REGION_DETAIL;
            }
        } else {
            return;
        }
        render();
    }

    private void clickRegionDetail(int slot) {
        if (selectedRegion == null || state().protectedRegion(selectedRegion).isEmpty()) {
            mode = Mode.REGIONS;
            render();
            return;
        }
        if (!canManageRegions(currentRole())) {
            return;
        }
        if (slot == SECONDARY_SLOT) {
            enterForm(FormKind.REGION_EDIT, Mode.REGION_DETAIL);
        } else if (slot == DANGER_SLOT) {
            enterForm(FormKind.REGION_DELETE, Mode.REGION_DETAIL);
        }
    }

    private void clickPortals(int slot) {
        if (slot == PRIMARY_SLOT && canManagePortals(currentRole())) {
            selectedPortalOriginDimension = null;
            selectedPortalDestinationDimension = null;
            query = "";
            page = 0;
            mode = Mode.PORTAL_ORIGIN_DIMENSION_SELECT;
            render();
            return;
        }
        if (slot == CENTER_SLOT) {
            mode = Mode.WILDERNESS;
            query = "";
            page = 0;
        } else if (slot == PREVIOUS_SLOT) {
            page = Math.max(0, page - 1);
        } else if (slot == NEXT_SLOT) {
            page++;
        } else if (slot >= CONTENT_START && slot < CONTENT_START + CONTENT_SIZE) {
            var resultPage = portalsPage();
            int index = slot - CONTENT_START;
            if (index < resultPage.entries().size()) {
                selectedPortal = resultPage.entries().get(index).portalId();
                mode = Mode.PORTAL_DETAIL;
            }
        } else {
            return;
        }
        render();
    }

    private void clickPortalDetail(int slot) {
        if (selectedPortal == null || state().portalDefinition(selectedPortal).isEmpty()) {
            mode = Mode.PORTALS;
            render();
            return;
        }
        if (!canManagePortals(currentRole())) {
            return;
        }
        if (slot == SECONDARY_SLOT) {
            enterForm(FormKind.PORTAL_EDIT, Mode.PORTAL_DETAIL);
        } else if (slot == DANGER_SLOT) {
            enterForm(FormKind.PORTAL_DISABLE, Mode.PORTAL_DETAIL);
        }
    }

    private void clickWilderness(int slot) {
        if (slot == CENTER_SLOT) {
            mode = Mode.EVIDENCE;
            query = "";
            page = 0;
            render();
            return;
        }
        if (!canManageWilderness(currentRole())) {
            return;
        }
        if (slot == 20) {
            enterForm(FormKind.WILDERNESS_WARN, Mode.WILDERNESS);
        } else if (slot == 24 && state().wildernessResetState().warning().isPresent()) {
            enterForm(FormKind.WILDERNESS_RESET, Mode.WILDERNESS);
        }
    }

    private void clickEvidence(int slot) {
        if (slot == PREVIOUS_SLOT) {
            page = Math.max(0, page - 1);
        } else if (slot == NEXT_SLOT) {
            page++;
        } else if (slot >= CONTENT_START && slot < CONTENT_START + CONTENT_SIZE) {
            var resultPage = evidencePage();
            int index = slot - CONTENT_START;
            if (index < resultPage.entries().size()) {
                selectedEvidence = resultPage.entries().get(index).evidence();
                mode = Mode.EVIDENCE_DETAIL;
            }
        } else {
            return;
        }
        render();
    }

    private void clickEvidenceDetail(int slot) {
        if (selectedEvidence == null || !canManageWilderness(currentRole())) {
            return;
        }
        if (slot == CONFIRM_SLOT) {
            selectedSnapshot = selectedEvidence.operation().snapshotId();
            enterForm(FormKind.WILDERNESS_RESTORE, Mode.EVIDENCE_DETAIL);
        } else if (slot == CANCEL_SLOT) {
            selectedSnapshot = selectedEvidence.operation().recoverySnapshotId();
            enterForm(FormKind.WILDERNESS_RESTORE, Mode.EVIDENCE_DETAIL);
        }
    }

    private void clickClaimTargetSelect(int slot) {
        if (slot == PREVIOUS_SLOT) {
            page = Math.max(0, page - 1);
        } else if (slot == NEXT_SLOT) {
            page++;
        } else if (slot >= CONTENT_START && slot < CONTENT_START + CONTENT_SIZE) {
            List<Map.Entry<UUID, PlayerRecord>> players = claimTargets();
            int index = page * CONTENT_SIZE + slot - CONTENT_START;
            if (index >= players.size()) {
                return;
            }
            selectedClaimTarget = players.get(index).getKey();
            enterForm(formKind == FormKind.CLAIM_UNTRUST ? FormKind.CLAIM_UNTRUST : FormKind.CLAIM_ROLE, Mode.CLAIM_DETAIL);
            return;
        } else {
            return;
        }
        render();
    }

    private void clickRegionDimensionSelect(int slot) {
        Optional<net.minecraft.resources.Identifier> dimension = selectedDimension(slot);
        if (dimension.isPresent()) {
            selectedRegionDimension = dimension.orElseThrow();
            enterForm(FormKind.REGION_CREATE, Mode.REGIONS);
            return;
        }
        if (slot == PREVIOUS_SLOT) {
            page = Math.max(0, page - 1);
        } else if (slot == NEXT_SLOT) {
            page++;
        } else {
            return;
        }
        render();
    }

    private void clickPortalOriginDimensionSelect(int slot) {
        Optional<net.minecraft.resources.Identifier> dimension = selectedDimension(slot);
        if (dimension.isPresent()) {
            selectedPortalOriginDimension = dimension.orElseThrow();
            query = "";
            page = 0;
            mode = Mode.PORTAL_DESTINATION_DIMENSION_SELECT;
            render();
            return;
        }
        if (slot == PREVIOUS_SLOT) {
            page = Math.max(0, page - 1);
        } else if (slot == NEXT_SLOT) {
            page++;
        } else {
            return;
        }
        render();
    }

    private void clickPortalDestinationDimensionSelect(int slot) {
        Optional<net.minecraft.resources.Identifier> dimension = selectedDimension(slot);
        if (dimension.isPresent()) {
            selectedPortalDestinationDimension = dimension.orElseThrow();
            enterForm(FormKind.PORTAL_CREATE, Mode.PORTALS);
            return;
        }
        if (slot == PREVIOUS_SLOT) {
            page = Math.max(0, page - 1);
        } else if (slot == NEXT_SLOT) {
            page++;
        } else {
            return;
        }
        render();
    }

    private boolean parseForm(String input) {
        if (input.startsWith("rf-form/")) {
            Optional<List<String>> values = AdministrationStructuredFormCodec.decode(formType(formKind), input);
            if (values.isEmpty()) {
                formError = "invalid_form";
                render();
                return false;
            }
            return parseTypedForm(values.orElseThrow());
        }
        return parseLegacyForm(input);
    }

    private boolean parseTypedForm(List<String> values) {
        PlatformSavedData state = state();
        UUID transactionId = UUID.randomUUID();
        pending = switch (formKind) {
            case CLAIM_ROLE -> AdministrationWorldTypedForm.claimRole(selectedClaimTarget, values)
                .map(value -> new AdministrationWorldActionService.ClaimRoleAction(
                            transactionId, selectedClaim, state.claim(selectedClaim), value.playerId(), value.role(),
                            false, value.reason()))
                    .orElse(null);
            case CLAIM_UNTRUST -> AdministrationWorldTypedForm.claimUntrust(selectedClaimTarget, values)
                    .map(value -> new AdministrationWorldActionService.ClaimRoleAction(
                            transactionId, selectedClaim, state.claim(selectedClaim), value.playerId(),
                            ClaimRole.VISITOR, true, value.reason()))
                    .orElse(null);
            case CLAIM_SETTINGS -> AdministrationWorldTypedForm.claimSettings(values)
                    .map(value -> new AdministrationWorldActionService.ClaimSettingsAction(
                            transactionId, selectedClaim, state.claim(selectedClaim),
                            new ClaimSettings(value.entryRestricted(), value.publicInteractions()), value.reason()))
                    .orElse(null);
            case CLAIM_RECLAIM -> typedReason(values)
                    .map(value -> new AdministrationWorldActionService.ClaimReclaimAction(
                            transactionId, selectedClaim, state.claim(selectedClaim), value))
                    .orElse(null);
            case REGION_CREATE -> AdministrationWorldTypedForm.regionCreate(transactionId, selectedRegionDimension, values)
                    .map(value -> new AdministrationWorldActionService.RegionCreateAction(
                            transactionId, value.regionId(), new ProtectedRegion(
                                    viewerId, dimension(value.dimensionId()), value.minChunkX(), value.minChunkZ(),
                                    value.maxChunkX(), value.maxChunkZ()), value.reason()))
                    .orElse(null);
            case REGION_EDIT -> state.protectedRegion(selectedRegion).flatMap(region ->
                    AdministrationWorldTypedForm.regionEdit(region.dimension().identifier(), values))
                    .map(value -> new AdministrationWorldActionService.RegionEditAction(
                            transactionId, selectedRegion, state.protectedRegion(selectedRegion), new ProtectedRegion(
                                    viewerId, dimension(value.dimensionId()), value.minChunkX(), value.minChunkZ(),
                                    value.maxChunkX(), value.maxChunkZ()), value.reason()))
                    .orElse(null);
            case REGION_DELETE -> typedReason(values)
                    .map(value -> new AdministrationWorldActionService.RegionDeleteAction(
                            transactionId, selectedRegion, state.protectedRegion(selectedRegion), value))
                    .orElse(null);
            case PORTAL_CREATE -> AdministrationWorldTypedForm.portalCreate(
                    transactionId, selectedPortalOriginDimension, selectedPortalDestinationDimension, values)
                    .map(value -> new AdministrationWorldActionService.PortalCreateAction(
                            transactionId, value.portalId(), portal(value), value.reason()))
                    .orElse(null);
            case PORTAL_EDIT -> state.portalDefinition(selectedPortal).flatMap(portal ->
                    AdministrationWorldTypedForm.portalEdit(
                            portal.origin().dimension().identifier(), portal.destination().dimension().identifier(), values))
                    .map(value -> new AdministrationWorldActionService.PortalEditAction(
                            transactionId, selectedPortal, state.portalDefinition(selectedPortal), portal(value),
                            value.reason()))
                    .orElse(null);
            case PORTAL_DISABLE -> typedReason(values)
                    .map(value -> new AdministrationWorldActionService.PortalDeleteAction(
                            transactionId, selectedPortal, state.portalDefinition(selectedPortal), value))
                    .orElse(null);
            case WILDERNESS_WARN -> typedReason(values)
                    .map(value -> new AdministrationWorldActionService.WildernessWarnAction(
                            transactionId, state.wildernessResetState(), value))
                    .orElse(null);
            case WILDERNESS_RESET -> typedReason(values).flatMap(value -> state.wildernessResetState().warning()
                    .map(warning -> new AdministrationWorldActionService.WildernessResetAction(
                            transactionId, state.wildernessResetState(), warning.warningId(), value)))
                    .orElse(null);
            case WILDERNESS_RESTORE -> typedReason(values)
                    .filter(value -> selectedSnapshot != null)
                    .map(value -> new AdministrationWorldActionService.WildernessRestoreAction(
                            transactionId, state.wildernessResetState(), selectedSnapshot, value))
                    .orElse(null);
        };
        if (pending == null || !AdministrationWorldActionService.allowed(currentRole(), pending)
                || !AdministrationWorldActionService.fresh(state, pending)) {
            formError = "invalid_form";
            pending = null;
            render();
            return false;
        }
        formError = "";
        mode = Mode.PREVIEW;
        render();
        return true;
    }

    private boolean parseLegacyForm(String input) {
        PlatformSavedData state = state();
        UUID transactionId = UUID.randomUUID();
        pending = switch (formKind) {
            case CLAIM_ROLE -> AdministrationWorldFormParser.parseClaimRole(input)
                    .map(value -> new AdministrationWorldActionService.ClaimRoleAction(transactionId, selectedClaim,
                            state.claim(selectedClaim), value.playerId(), value.role(), false, value.reason())).orElse(null);
            case CLAIM_UNTRUST -> AdministrationWorldFormParser.parseClaimTarget(input)
                    .map(value -> new AdministrationWorldActionService.ClaimRoleAction(transactionId, selectedClaim,
                            state.claim(selectedClaim), value.playerId(), ClaimRole.VISITOR, true, value.reason())).orElse(null);
            case CLAIM_SETTINGS -> AdministrationWorldFormParser.parseClaimSettings(input)
                    .map(value -> new AdministrationWorldActionService.ClaimSettingsAction(transactionId, selectedClaim,
                            state.claim(selectedClaim), new ClaimSettings(value.entryRestricted(), value.publicInteractions()), value.reason())).orElse(null);
            case CLAIM_RECLAIM -> reason(input).map(value -> new AdministrationWorldActionService.ClaimReclaimAction(
                    transactionId, selectedClaim, state.claim(selectedClaim), value)).orElse(null);
            case REGION_CREATE -> AdministrationWorldFormParser.parseRegionCreate(input).map(value ->
                    new AdministrationWorldActionService.RegionCreateAction(transactionId, value.regionId(), new ProtectedRegion(
                            viewerId, dimension(value.dimensionId()), value.minChunkX(), value.minChunkZ(),
                            value.maxChunkX(), value.maxChunkZ()), value.reason())).orElse(null);
            case REGION_EDIT -> AdministrationWorldFormParser.parseRegionEdit(input).map(value ->
                    new AdministrationWorldActionService.RegionEditAction(transactionId, selectedRegion,
                            state.protectedRegion(selectedRegion), new ProtectedRegion(viewerId, dimension(value.dimensionId()),
                            value.minChunkX(), value.minChunkZ(), value.maxChunkX(), value.maxChunkZ()), value.reason())).orElse(null);
            case REGION_DELETE -> reason(input).map(value -> new AdministrationWorldActionService.RegionDeleteAction(
                    transactionId, selectedRegion, state.protectedRegion(selectedRegion), value)).orElse(null);
            case PORTAL_CREATE -> AdministrationWorldFormParser.parsePortalCreate(input).map(value ->
                    new AdministrationWorldActionService.PortalCreateAction(transactionId, value.portalId(), portal(value), value.reason())).orElse(null);
            case PORTAL_EDIT -> AdministrationWorldFormParser.parsePortalEdit(input).map(value ->
                    new AdministrationWorldActionService.PortalEditAction(transactionId, selectedPortal,
                            state.portalDefinition(selectedPortal), portal(value), value.reason())).orElse(null);
            case PORTAL_DISABLE -> reason(input).map(value -> new AdministrationWorldActionService.PortalDeleteAction(
                    transactionId, selectedPortal, state.portalDefinition(selectedPortal), value)).orElse(null);
            case WILDERNESS_WARN -> reason(input).map(value -> new AdministrationWorldActionService.WildernessWarnAction(
                    transactionId, state.wildernessResetState(), value)).orElse(null);
            case WILDERNESS_RESET -> reason(input).flatMap(value -> state.wildernessResetState().warning().map(warning ->
                    new AdministrationWorldActionService.WildernessResetAction(transactionId, state.wildernessResetState(), warning.warningId(), value))).orElse(null);
            case WILDERNESS_RESTORE -> reason(input).filter(value -> selectedSnapshot != null).map(value ->
                    new AdministrationWorldActionService.WildernessRestoreAction(transactionId, state.wildernessResetState(), selectedSnapshot, value)).orElse(null);
        };
        return finishParsedForm(state);
    }

    private boolean finishParsedForm(PlatformSavedData state) {
        if (pending == null || !AdministrationWorldActionService.allowed(currentRole(), pending)
                || !AdministrationWorldActionService.fresh(state, pending)) {
            formError = "invalid_form";
            pending = null;
            render();
            return false;
        }
        formError = "";
        mode = Mode.PREVIEW;
        render();
        return true;
    }

    private PortalDefinition portal(AdministrationWorldFormParser.PortalCreateForm value) {
        return new PortalDefinition(
                viewerId,
                new PortalDefinition.Endpoint(
                        dimension(value.originDimensionId()), new BlockPos(value.originX(), value.originY(), value.originZ())),
                new PortalDefinition.Endpoint(
                        dimension(value.destinationDimensionId()),
                        new BlockPos(value.destinationX(), value.destinationY(), value.destinationZ())),
                value.radiusChunks(), value.cooldownMillis(), value.policy(), value.allowCombat());
    }

    private PortalDefinition portal(AdministrationWorldFormParser.PortalEditForm value) {
        return new PortalDefinition(
                viewerId,
                new PortalDefinition.Endpoint(
                        dimension(value.originDimensionId()), new BlockPos(value.originX(), value.originY(), value.originZ())),
                new PortalDefinition.Endpoint(
                        dimension(value.destinationDimensionId()),
                        new BlockPos(value.destinationX(), value.destinationY(), value.destinationZ())),
                value.radiusChunks(), value.cooldownMillis(), value.policy(), value.allowCombat());
    }

    private static ResourceKey<Level> dimension(net.minecraft.resources.Identifier dimensionId) {
        return ResourceKey.create(Registries.DIMENSION, dimensionId);
    }

    private static Optional<String> reason(String input) {
        return AdministrationWorldFormParser.parseReasonOnly(input)
                .map(AdministrationWorldFormParser.ReasonForm::reason);
    }

    private Optional<String> typedReason(List<String> values) {
        if (!formType(formKind).accepts(values)) {
            return Optional.empty();
        }
        int index = values.size() - 1;
        return AdministrationWorldFormParser.parseReasonOnly(" | " + values.get(index))
                .map(AdministrationWorldFormParser.ReasonForm::reason);
    }

    private void confirm() {
        if (pending == null) {
            return;
        }
        if (!PlayerMenuNetwork.beginMutation(viewerId, viewer.level().getGameTime())) {
            result = new AdministrationWorldActionService.Result(
                    AdministrationWorldActionService.Status.FAILED, "rate_limited", pending.transactionId());
            mode = Mode.RESULT;
            render();
            return;
        }
        result = AdministrationWorldActionService.execute(viewer, pending);
        mode = Mode.RESULT;
        render();
    }

    private void enterForm(FormKind kind, Mode destination) {
        formKind = kind;
        returnMode = destination;
        formError = "";
        pending = null;
        mode = Mode.FORM;
        render();
    }

    private void cancelForm() {
        pending = null;
        formKind = null;
        mode = returnMode;
        render();
    }

    private void finishResult() {
        boolean succeeded = result != null && result.succeeded();
        if (succeeded && returnMode == Mode.CLAIM_DETAIL
                && (selectedClaim == null || state().claim(selectedClaim).isEmpty())) {
            returnMode = Mode.CLAIMS;
            selectedClaim = null;
        }
        if (succeeded && returnMode == Mode.REGION_DETAIL
                && (selectedRegion == null || state().protectedRegion(selectedRegion).isEmpty())) {
            returnMode = Mode.REGIONS;
            selectedRegion = null;
        }
        if (succeeded && returnMode == Mode.PORTAL_DETAIL
                && (selectedPortal == null || state().portalDefinition(selectedPortal).isEmpty())) {
            returnMode = Mode.PORTALS;
            selectedPortal = null;
        }
        pending = null;
        formKind = null;
        mode = returnMode;
        result = null;
        render();
    }

    private void back() {
        switch (mode) {
            case CLAIMS, PORTALS -> AdministrationControlCenterMenu.open(viewer);
            case CLAIM_TARGET_SELECT -> {
                mode = Mode.CLAIM_DETAIL;
                query = "";
                page = 0;
                render();
            }
            case REGION_DIMENSION_SELECT -> {
                mode = Mode.REGIONS;
                query = "";
                page = 0;
                render();
            }
            case PORTAL_ORIGIN_DIMENSION_SELECT, PORTAL_DESTINATION_DIMENSION_SELECT -> {
                mode = Mode.PORTALS;
                query = "";
                page = 0;
                render();
            }
            case CLAIM_DETAIL -> {
                selectedClaim = null;
                mode = Mode.CLAIMS;
                render();
            }
            case REGIONS -> {
                mode = Mode.CLAIMS;
                query = "";
                page = 0;
                render();
            }
            case REGION_DETAIL -> {
                selectedRegion = null;
                mode = Mode.REGIONS;
                render();
            }
            case PORTAL_DETAIL -> {
                selectedPortal = null;
                mode = Mode.PORTALS;
                render();
            }
            case WILDERNESS -> {
                mode = Mode.PORTALS;
                query = "";
                page = 0;
                render();
            }
            case EVIDENCE -> {
                mode = Mode.WILDERNESS;
                query = "";
                page = 0;
                render();
            }
            case EVIDENCE_DETAIL -> {
                selectedEvidence = null;
                selectedSnapshot = null;
                mode = Mode.EVIDENCE;
                render();
            }
            case FORM, PREVIEW -> cancelForm();
            case RESULT -> finishResult();
        }
    }

    private void render() {
        contents.clearContent();
        if (!canView(viewer, entryDomain)) {
            denyAndClose();
            return;
        }
        switch (mode) {
            case CLAIMS -> renderClaims();
            case CLAIM_DETAIL -> renderClaimDetail();
            case REGIONS -> renderRegions();
            case REGION_DETAIL -> renderRegionDetail();
            case PORTALS -> renderPortals();
            case PORTAL_DETAIL -> renderPortalDetail();
            case WILDERNESS -> renderWilderness();
            case EVIDENCE -> renderEvidence();
            case EVIDENCE_DETAIL -> renderEvidenceDetail();
            case CLAIM_TARGET_SELECT -> renderClaimTargetSelect();
            case REGION_DIMENSION_SELECT -> renderDimensionSelect("gui.rovenfall.admin.world.region.create");
            case PORTAL_ORIGIN_DIMENSION_SELECT -> renderDimensionSelect("gui.rovenfall.admin.world.field.origin");
            case PORTAL_DESTINATION_DIMENSION_SELECT -> renderDimensionSelect("gui.rovenfall.admin.world.field.destination");
            case FORM -> renderForm();
            case PREVIEW -> renderPreview();
            case RESULT -> renderResult();
        }
        contents.setItem(REFRESH_SLOT, icon(
                Items.COMPASS, "gui.rovenfall.admin.refresh", "gui.rovenfall.admin.click"));
        broadcastChanges();
    }

    private void renderClaims() {
        var resultPage = claimsPage();
        renderListHeader(Items.GRASS_BLOCK, "gui.rovenfall.admin.world.claims", resultPage);
        for (int index = 0; index < resultPage.entries().size(); index++) {
            var row = resultPage.entries().get(index);
            Claim claim = row.claim();
            contents.setItem(CONTENT_START + index, PlayerDashboardMenu.icon(
                    Items.GRASS_BLOCK, claimName(row.key()),
                    Component.translatable("gui.rovenfall.admin.world.field.owner", playerName(claim.ownerId())),
                    Component.translatable("gui.rovenfall.admin.world.field.trusted", claim.trustedRoles().size()),
                    Component.translatable("gui.rovenfall.admin.world.field.flags",
                            enabled(claim.settings().entryRestricted()), enabled(claim.settings().publicInteractions())),
                    Component.translatable("gui.rovenfall.admin.world.field.transfer",
                            optionalPlayerName(claim.pendingTransferTo())),
                    Component.literal(row.key().auditTarget() + " | " + claim.ownerId())));
        }
        contents.setItem(PRIMARY_SLOT, icon(
                Items.STRUCTURE_VOID, "gui.rovenfall.admin.world.regions", "gui.rovenfall.admin.click"));
        renderPagination(resultPage.page(), resultPage.totalPages());
    }

    private void renderClaimDetail() {
        Claim claim = selectedClaim == null ? null : state().claim(selectedClaim).orElse(null);
        if (claim == null) {
            mode = Mode.CLAIMS;
            renderClaims();
            return;
        }
        contents.setItem(4, PlayerDashboardMenu.icon(
                Items.WRITABLE_BOOK, Component.translatable("gui.rovenfall.admin.world.claim.detail"),
                claimName(selectedClaim),
                Component.translatable("gui.rovenfall.admin.world.field.owner", playerName(claim.ownerId())),
                Component.translatable("gui.rovenfall.admin.world.field.price", claim.purchasePrice()),
                Component.translatable("gui.rovenfall.admin.world.field.flags",
                        enabled(claim.settings().entryRestricted()), enabled(claim.settings().publicInteractions())),
                Component.translatable("gui.rovenfall.admin.world.field.transfer",
                        optionalPlayerName(claim.pendingTransferTo())),
                Component.literal(selectedClaim.auditTarget() + " | " + claim.ownerId())));
        List<Map.Entry<UUID, ClaimRole>> allTrusted = claim.trustedRoles().entrySet().stream()
                .sorted(Map.Entry.comparingByKey()).toList();
        int trustedPages = allTrusted.isEmpty() ? 0 : (allTrusted.size() + CONTENT_SIZE - 1) / CONTENT_SIZE;
        long offset = (long) page * CONTENT_SIZE;
        List<Map.Entry<UUID, ClaimRole>> trusted = offset >= allTrusted.size()
                ? List.of()
                : allTrusted.subList((int) offset, Math.min(allTrusted.size(), (int) offset + CONTENT_SIZE));
        for (int index = 0; index < trusted.size(); index++) {
            Map.Entry<UUID, ClaimRole> entry = trusted.get(index);
            PlayerRecord player = state().playerRecord(entry.getKey()).orElse(null);
            contents.setItem(CONTENT_START + index, AdministrationPlayerHead.create(entry.getKey(),
                    player == null ? "" : player.displayName().orElse(""),
                    Component.translatable("gui.rovenfall.admin.world.field.claim_role",
                            Component.translatable(entry.getValue().translationKey())),
                    Component.literal(entry.getKey().toString())));
        }
        if (canManageClaims(currentRole())) {
            contents.setItem(PRIMARY_SLOT, icon(
                    Items.NAME_TAG, "gui.rovenfall.admin.world.claim.role", "gui.rovenfall.admin.world.form.claim_role"));
            contents.setItem(SECONDARY_SLOT, icon(
                    Items.SHEARS, "gui.rovenfall.admin.world.claim.untrust", "gui.rovenfall.admin.world.form.claim_target"));
            contents.setItem(TERTIARY_SLOT, icon(
                    Items.REPEATER, "gui.rovenfall.admin.world.claim.settings", "gui.rovenfall.admin.world.form.claim_settings"));
            contents.setItem(DANGER_SLOT, icon(
                    Items.BARRIER, "gui.rovenfall.admin.world.claim.reclaim", "gui.rovenfall.admin.world.irreversible"));
        }
        renderPagination(page, trustedPages);
    }

    private void renderRegions() {
        var resultPage = regionsPage();
        renderListHeader(Items.STRUCTURE_VOID, "gui.rovenfall.admin.world.regions", resultPage);
        for (int index = 0; index < resultPage.entries().size(); index++) {
            var row = resultPage.entries().get(index);
            ProtectedRegion region = row.region();
            contents.setItem(CONTENT_START + index, PlayerDashboardMenu.icon(
                    Items.STRUCTURE_VOID, Component.literal(region.dimension().identifier().getPath().replace('_', ' ')),
                    Component.translatable("gui.rovenfall.admin.world.field.bounds",
                            region.minChunkX(), region.minChunkZ(), region.maxChunkX(), region.maxChunkZ()),
                    Component.translatable("gui.rovenfall.admin.world.field.administrator",
                            playerName(region.administratorId())),
                    Component.translatable("gui.rovenfall.admin.world.field.dimension",
                            region.dimension().identifier().toString()),
                    Component.literal(region.administratorId().toString()),
                    Component.literal(row.regionId().toString())));
        }
        if (canManageRegions(currentRole())) {
            contents.setItem(PRIMARY_SLOT, icon(
                    Items.EMERALD, "gui.rovenfall.admin.world.region.create", "gui.rovenfall.admin.world.form.region_create"));
        }
        renderPagination(resultPage.page(), resultPage.totalPages());
    }

    private void renderRegionDetail() {
        ProtectedRegion region = selectedRegion == null ? null : state().protectedRegion(selectedRegion).orElse(null);
        if (region == null) {
            mode = Mode.REGIONS;
            renderRegions();
            return;
        }
        contents.setItem(4, PlayerDashboardMenu.icon(
                Items.STRUCTURE_VOID, Component.literal(region.dimension().identifier().getPath().replace('_', ' ')),
                Component.translatable("gui.rovenfall.admin.world.field.bounds",
                        region.minChunkX(), region.minChunkZ(), region.maxChunkX(), region.maxChunkZ()),
                Component.translatable("gui.rovenfall.admin.world.field.area", region.areaChunks()),
                Component.translatable("gui.rovenfall.admin.world.field.administrator",
                        playerName(region.administratorId())),
                Component.translatable("gui.rovenfall.admin.world.field.dimension",
                        region.dimension().identifier().toString()),
                Component.literal(region.administratorId().toString()),
                Component.literal(selectedRegion.toString())));
        if (canManageRegions(currentRole())) {
            contents.setItem(SECONDARY_SLOT, icon(
                    Items.WRITABLE_BOOK, "gui.rovenfall.admin.world.region.edit", "gui.rovenfall.admin.world.form.region_edit"));
            contents.setItem(DANGER_SLOT, icon(
                    Items.BARRIER, "gui.rovenfall.admin.world.region.delete", "gui.rovenfall.admin.world.irreversible"));
        }
        renderBack();
    }

    private void renderClaimTargetSelect() {
        List<Map.Entry<UUID, PlayerRecord>> players = claimTargets();
        int from = Math.min(players.size(), page * CONTENT_SIZE);
        int to = Math.min(players.size(), from + CONTENT_SIZE);
        ItemStack header = PlayerDashboardMenu.icon(Items.PLAYER_HEAD,
                Component.translatable(formKind == FormKind.CLAIM_UNTRUST
                        ? "gui.rovenfall.admin.world.claim.untrust" : "gui.rovenfall.admin.world.claim.role"),
                Component.translatable("gui.rovenfall.admin.click"));
        AdministrationFormMarker.writeSearch(header);
        contents.setItem(4, header);
        for (int index = from; index < to; index++) {
            Map.Entry<UUID, PlayerRecord> entry = players.get(index);
            contents.setItem(CONTENT_START + index - from, AdministrationPlayerHead.create(entry.getKey(),
                    entry.getValue().displayName().orElse(""),
                    Component.literal(entry.getKey().toString())));
        }
        renderPagination(page, pages(players.size()));
    }

    private void renderDimensionSelect(String titleKey) {
        List<net.minecraft.resources.Identifier> dimensions = dimensions();
        int from = Math.min(dimensions.size(), page * CONTENT_SIZE);
        int to = Math.min(dimensions.size(), from + CONTENT_SIZE);
        ItemStack header = PlayerDashboardMenu.icon(Items.COMPASS, Component.translatable(titleKey),
                Component.translatable("gui.rovenfall.admin.click"));
        AdministrationFormMarker.writeSearch(header);
        contents.setItem(4, header);
        for (int index = from; index < to; index++) {
            net.minecraft.resources.Identifier dimension = dimensions.get(index);
            contents.setItem(CONTENT_START + index - from, PlayerDashboardMenu.icon(
                    Items.COMPASS, Component.literal(dimension.getPath().replace('_', ' ')), Component.literal(dimension.toString())));
        }
        renderPagination(page, pages(dimensions.size()));
    }

    private void renderPortals() {
        var resultPage = portalsPage();
        renderListHeader(Items.ENDER_PEARL, "gui.rovenfall.admin.world.portals", resultPage);
        for (int index = 0; index < resultPage.entries().size(); index++) {
            var row = resultPage.entries().get(index);
            PortalDefinition portal = row.definition();
            contents.setItem(CONTENT_START + index, PlayerDashboardMenu.icon(
                    Items.ENDER_PEARL, Component.literal(portal.origin().dimension().identifier().getPath().replace('_', ' ')),
                    Component.translatable("gui.rovenfall.admin.world.field.origin", portal.origin().auditSummary()),
                    Component.translatable("gui.rovenfall.admin.world.field.destination",
                            portal.destination().auditSummary()),
                    Component.translatable("gui.rovenfall.admin.world.field.portal_policy",
                            portalPolicy(portal.safeArrivalPolicy()), portal.cooldownMillis(),
                            enabled(portal.allowCombat())),
                    Component.literal(row.portalId().toString())));
        }
        if (canManagePortals(currentRole())) {
            contents.setItem(PRIMARY_SLOT, icon(
                    Items.EMERALD, "gui.rovenfall.admin.world.portal.create", "gui.rovenfall.admin.world.form.portal_create"));
        }
        contents.setItem(CENTER_SLOT, icon(
                Items.MAP, "gui.rovenfall.admin.world.wilderness", "gui.rovenfall.admin.click"));
        renderPagination(resultPage.page(), resultPage.totalPages());
    }

    private void renderPortalDetail() {
        PortalDefinition portal = selectedPortal == null ? null : state().portalDefinition(selectedPortal).orElse(null);
        if (portal == null) {
            mode = Mode.PORTALS;
            renderPortals();
            return;
        }
        contents.setItem(4, PlayerDashboardMenu.icon(
                Items.ENDER_EYE, Component.literal(portal.origin().dimension().identifier().getPath().replace('_', ' ')),
                Component.translatable("gui.rovenfall.admin.world.field.origin", portal.origin().auditSummary()),
                Component.translatable("gui.rovenfall.admin.world.field.destination", portal.destination().auditSummary()),
                Component.translatable("gui.rovenfall.admin.world.field.radius", portal.protectionRadiusChunks()),
                Component.translatable("gui.rovenfall.admin.world.field.cooldown", portal.cooldownMillis()),
                Component.translatable("gui.rovenfall.admin.world.field.safe_policy",
                        portalPolicy(portal.safeArrivalPolicy())),
                Component.translatable("gui.rovenfall.admin.world.field.combat", enabled(portal.allowCombat())),
                Component.literal(selectedPortal.toString())));
        if (canManagePortals(currentRole())) {
            contents.setItem(SECONDARY_SLOT, icon(
                    Items.WRITABLE_BOOK, "gui.rovenfall.admin.world.portal.edit", "gui.rovenfall.admin.world.form.portal_edit"));
            contents.setItem(DANGER_SLOT, icon(
                    Items.BARRIER, "gui.rovenfall.admin.world.portal.disable", "gui.rovenfall.admin.world.irreversible"));
        }
        renderBack();
    }

    private void renderWilderness() {
        var view = wildernessView();
        WildernessResetState resetState = view.resetState();
        contents.setItem(4, PlayerDashboardMenu.icon(
                Items.MAP, Component.translatable("gui.rovenfall.admin.world.wilderness"),
                Component.translatable("gui.rovenfall.admin.world.field.readiness", enabled(view.ready())),
                Component.translatable("gui.rovenfall.admin.world.field.topology",
                        enabled(view.hubLoaded()), enabled(view.wildernessLoaded()), enabled(view.safeHubArrival())),
                Component.translatable("gui.rovenfall.admin.world.field.wilderness_players", view.wildernessPlayers()),
                Component.translatable("gui.rovenfall.admin.world.field.locks",
                        enabled(view.encounterLocked()), enabled(view.lifecyclePending())),
                Component.translatable("gui.rovenfall.admin.world.field.warning",
                        resetState.warning().<Component>map(warning -> Component.translatable(
                                "gui.rovenfall.admin.world.field.warning_value",
                                warning.warningId().toString(), warning.expiresAtEpochMillis()))
                                .orElseGet(() -> Component.translatable("gui.rovenfall.admin.world.none"))),
                Component.translatable("gui.rovenfall.admin.world.field.active_operation",
                        resetState.activeOperation().<Component>map(operation -> Component.translatable(
                                "gui.rovenfall.admin.world.field.active_operation_value",
                                operationKind(operation.kind()), operation.transactionId().toString()))
                                .orElseGet(() -> Component.translatable("gui.rovenfall.admin.world.none")))));
        contents.setItem(CENTER_SLOT, icon(
                Items.FILLED_MAP, "gui.rovenfall.admin.world.evidence", "gui.rovenfall.admin.click"));
        if (canManageWilderness(currentRole())) {
            contents.setItem(20, icon(
                    Items.BELL, "gui.rovenfall.admin.world.wilderness.warn", "gui.rovenfall.admin.world.form.reason"));
            if (resetState.warning().isPresent()) {
                contents.setItem(24, icon(
                        Items.TNT, "gui.rovenfall.admin.world.wilderness.reset", "gui.rovenfall.admin.world.irreversible"));
            }
        }
        renderBack();
    }

    private void renderEvidence() {
        var resultPage = evidencePage();
        renderListHeader(Items.FILLED_MAP, "gui.rovenfall.admin.world.evidence", resultPage);
        for (int index = 0; index < resultPage.entries().size(); index++) {
            WildernessResetState.Evidence evidence = resultPage.entries().get(index).evidence();
            WildernessResetState.Operation operation = evidence.operation();
            contents.setItem(CONTENT_START + index, PlayerDashboardMenu.icon(
                    Items.FILLED_MAP, operationKind(operation.kind()),
                    Component.translatable("gui.rovenfall.admin.world.field.operation",
                            operationKind(operation.kind()), operationResult(evidence.result())),
                    Component.translatable("gui.rovenfall.admin.world.field.completed",
                            evidence.completedAtEpochMillis(), evidenceDetail(evidence.detail())),
                    Component.translatable("gui.rovenfall.admin.world.field.snapshot",
                            operation.snapshotId().toString()),
                    Component.translatable("gui.rovenfall.admin.world.field.recovery_snapshot",
                            operation.recoverySnapshotId().toString())));
        }
        renderPagination(resultPage.page(), resultPage.totalPages());
    }

    private void renderEvidenceDetail() {
        if (selectedEvidence == null) {
            mode = Mode.EVIDENCE;
            renderEvidence();
            return;
        }
        WildernessResetState.Operation operation = selectedEvidence.operation();
        contents.setItem(4, PlayerDashboardMenu.icon(
                Items.FILLED_MAP, operationKind(operation.kind()),
                Component.translatable("gui.rovenfall.admin.world.field.operation",
                        operationKind(operation.kind()), operationResult(selectedEvidence.result())),
                Component.translatable("gui.rovenfall.admin.world.field.completed",
                        selectedEvidence.completedAtEpochMillis(), evidenceDetail(selectedEvidence.detail())),
                Component.translatable("gui.rovenfall.admin.world.field.snapshot_evidence",
                        operation.snapshotId().toString(), operation.fileCount(), operation.byteCount(),
                        operation.sha256()),
                Component.translatable("gui.rovenfall.admin.world.field.recovery_evidence",
                        operation.recoverySnapshotId().toString(), operation.recoveryFileCount(),
                        operation.recoveryByteCount(), operation.recoverySha256())));
        if (canManageWilderness(currentRole())) {
            contents.setItem(CONFIRM_SLOT, icon(
                    Items.ENDER_EYE, "gui.rovenfall.admin.world.restore.target", "gui.rovenfall.admin.world.irreversible"));
            contents.setItem(CANCEL_SLOT, icon(
                    Items.TOTEM_OF_UNDYING, "gui.rovenfall.admin.world.restore.recovery",
                    "gui.rovenfall.admin.world.irreversible"));
        }
        renderBack();
    }

    private void renderForm() {
        ItemStack header = PlayerDashboardMenu.icon(
                Items.WRITABLE_BOOK, Component.translatable("gui.rovenfall.admin.world.form.title"),
                Component.translatable(formHint(formKind)),
                Component.translatable("gui.rovenfall.admin.world.form.submit"),
                formError.isBlank() ? Component.empty()
                        : Component.translatable("gui.rovenfall.admin.world.error", inputError(formError)));
        AdministrationFormMarker.write(header, new AdministrationFormMarker(formType(formKind), formDefaults(formKind)));
        if (!formError.isBlank()) {
            AdministrationFormMarker.writeError(header);
        }
        contents.setItem(4, header);
        renderBack();
    }

    private void renderPreview() {
        List<Component> lines = previewLines(pending);
        contents.setItem(4, PlayerDashboardMenu.icon(
                isIrreversible(pending) ? Items.TNT : Items.WRITABLE_BOOK,
                Component.translatable(isIrreversible(pending)
                        ? "gui.rovenfall.admin.world.preview.irreversible"
                        : "gui.rovenfall.admin.world.preview"),
                lines.toArray(Component[]::new)));
        if (AdministrationWorldActionService.allowed(currentRole(), pending)) {
            contents.setItem(CONFIRM_SLOT, icon(
                    Items.EMERALD, "gui.rovenfall.admin.world.confirm", "gui.rovenfall.admin.world.confirm_fresh"));
        }
        contents.setItem(CANCEL_SLOT, icon(
                Items.BARRIER, "gui.rovenfall.admin.world.cancel", "gui.rovenfall.admin.click"));
        renderBack();
    }

    private void renderResult() {
        boolean succeeded = result != null && result.succeeded();
        contents.setItem(4, PlayerDashboardMenu.icon(
                succeeded ? Items.EMERALD : Items.BARRIER,
                Component.translatable(succeeded
                        ? "gui.rovenfall.admin.world.result.success"
                        : "gui.rovenfall.admin.world.result.failed"),
                resultDetail(result == null ? "unknown" : result.detail()),
                Component.translatable("gui.rovenfall.admin.world.field.transaction",
                        result == null || result.transactionId() == null
                                ? Component.translatable("gui.rovenfall.admin.world.none")
                                : result.transactionId().toString())));
        contents.setItem(CONFIRM_SLOT, icon(
                Items.ARROW, "gui.rovenfall.admin.world.continue", "gui.rovenfall.admin.click"));
        renderBack();
    }

    private <T> void renderListHeader(
            Item item, String titleKey, AdministrationWorldViewService.Page<T> resultPage) {
        if (resultPage.status() != AdministrationWorldViewService.Status.SUCCESS) {
            denyAndClose();
            return;
        }
        ItemStack header = PlayerDashboardMenu.icon(
                item, Component.translatable(titleKey),
                Component.translatable("gui.rovenfall.admin.page", page + 1, Math.max(1, resultPage.totalPages())),
                Component.translatable("gui.rovenfall.admin.total", resultPage.totalEntries()),
                Component.translatable(resultPage.truncated()
                        ? "gui.rovenfall.admin.truncated" : "gui.rovenfall.admin.complete"),
                Component.translatable("gui.rovenfall.admin.query", query.isBlank() ? "*" : query));
        AdministrationFormMarker.writeSearch(header);
        contents.setItem(4, header);
        if (resultPage.entries().isEmpty()) {
            contents.setItem(22, icon(
                    Items.BARRIER, "gui.rovenfall.admin.empty", "gui.rovenfall.admin.search_hint"));
        }
    }

    private void renderPagination(int currentPage, int totalPages) {
        renderBack();
        if (currentPage > 0) {
            contents.setItem(PREVIOUS_SLOT, icon(
                    Items.ARROW, "gui.rovenfall.admin.previous", "gui.rovenfall.admin.click"));
        }
        if (currentPage + 1 < totalPages) {
            contents.setItem(NEXT_SLOT, icon(
                    Items.ARROW, "gui.rovenfall.admin.next", "gui.rovenfall.admin.click"));
        }
    }

    private void renderBack() {
        contents.setItem(BACK_SLOT, icon(
                Items.ARROW, "gui.rovenfall.admin.back", "gui.rovenfall.admin.click"));
    }

    private List<Component> previewLines(AdministrationWorldActionService.PendingAction action) {
        List<Component> lines = new ArrayList<>();
        if (action instanceof AdministrationWorldActionService.ClaimRoleAction value) {
            Claim before = value.expectedClaim().orElseThrow();
            ClaimRole beforeRole = before.trustedRoles().getOrDefault(value.playerId(), ClaimRole.VISITOR);
            lines.add(Component.translatable(value.remove()
                    ? "gui.rovenfall.admin.world.claim.untrust" : "gui.rovenfall.admin.world.claim.role"));
            lines.add(Component.translatable("gui.rovenfall.admin.world.preview.claim", claimName(value.key())));
            lines.add(Component.translatable("gui.rovenfall.admin.world.preview.role_change",
                    playerName(value.playerId()), Component.translatable(beforeRole.translationKey()),
                    Component.translatable((value.remove() ? ClaimRole.VISITOR : value.role()).translationKey())));
        } else if (action instanceof AdministrationWorldActionService.ClaimSettingsAction value) {
            ClaimSettings before = value.expectedClaim().orElseThrow().settings();
            lines.add(Component.translatable("gui.rovenfall.admin.world.claim.settings"));
            lines.add(Component.translatable("gui.rovenfall.admin.world.preview.claim", claimName(value.key())));
            lines.add(Component.translatable("gui.rovenfall.admin.world.preview.settings_change",
                    enabled(before.entryRestricted()), enabled(before.publicInteractions()),
                    enabled(value.settings().entryRestricted()), enabled(value.settings().publicInteractions())));
        } else if (action instanceof AdministrationWorldActionService.ClaimReclaimAction value) {
            lines.add(Component.translatable("gui.rovenfall.admin.world.claim.reclaim"));
            lines.add(Component.translatable("gui.rovenfall.admin.world.preview.reclaim",
                    claimName(value.key()), playerName(value.expectedClaim().orElseThrow().ownerId())));
        } else if (action instanceof AdministrationWorldActionService.RegionCreateAction value) {
            lines.add(Component.translatable("gui.rovenfall.admin.world.region.create"));
            lines.add(Component.translatable("gui.rovenfall.admin.world.preview.absent_to",
                    value.regionId().toString(), value.region().auditSummary()));
        } else if (action instanceof AdministrationWorldActionService.RegionEditAction value) {
            lines.add(Component.translatable("gui.rovenfall.admin.world.region.edit"));
            lines.add(Component.translatable("gui.rovenfall.admin.world.preview.change",
                    value.expectedRegion().orElseThrow().auditSummary(), value.region().auditSummary()));
        } else if (action instanceof AdministrationWorldActionService.RegionDeleteAction value) {
            lines.add(Component.translatable("gui.rovenfall.admin.world.region.delete"));
            lines.add(Component.translatable("gui.rovenfall.admin.world.preview.to_absent",
                    value.regionId().toString(), value.expectedRegion().orElseThrow().auditSummary()));
        } else if (action instanceof AdministrationWorldActionService.PortalCreateAction value) {
            lines.add(Component.translatable("gui.rovenfall.admin.world.portal.create"));
            lines.add(Component.translatable("gui.rovenfall.admin.world.preview.absent_to",
                    value.portalId().toString(), value.definition().auditSummary()));
        } else if (action instanceof AdministrationWorldActionService.PortalEditAction value) {
            lines.add(Component.translatable("gui.rovenfall.admin.world.portal.edit"));
            lines.add(Component.translatable("gui.rovenfall.admin.world.preview.change",
                    value.expectedPortal().orElseThrow().auditSummary(), value.definition().auditSummary()));
        } else if (action instanceof AdministrationWorldActionService.PortalDeleteAction value) {
            lines.add(Component.translatable("gui.rovenfall.admin.world.portal.disable"));
            lines.add(Component.translatable("gui.rovenfall.admin.world.preview.to_absent",
                    value.portalId().toString(), value.expectedPortal().orElseThrow().auditSummary()));
        } else if (action instanceof AdministrationWorldActionService.WildernessWarnAction) {
            lines.add(Component.translatable("gui.rovenfall.admin.world.wilderness.warn"));
            lines.add(Component.translatable("gui.rovenfall.admin.world.preview.warning_ttl",
                    WildernessResetService.WARNING_TTL_MILLIS / 1_000L));
        } else if (action instanceof AdministrationWorldActionService.WildernessResetAction value) {
            lines.add(Component.translatable("gui.rovenfall.admin.world.wilderness.reset"));
            lines.add(Component.translatable("gui.rovenfall.admin.world.preview.warning", value.warningId().toString()));
            lines.add(Component.translatable("gui.rovenfall.admin.world.preview.shutdown"));
        } else if (action instanceof AdministrationWorldActionService.WildernessRestoreAction value) {
            lines.add(Component.translatable("gui.rovenfall.admin.world.restore"));
            lines.add(Component.translatable("gui.rovenfall.admin.world.preview.snapshot", value.snapshotId().toString()));
            lines.add(Component.translatable("gui.rovenfall.admin.world.preview.shutdown"));
        }
        lines.add(Component.translatable("gui.rovenfall.admin.world.preview.reason", action.reason()));
        lines.add(Component.translatable("gui.rovenfall.admin.world.preview.transaction",
                action.transactionId().toString()));
        return lines;
    }

    private static boolean isIrreversible(AdministrationWorldActionService.PendingAction action) {
        return action instanceof AdministrationWorldActionService.ClaimReclaimAction
                || action instanceof AdministrationWorldActionService.RegionDeleteAction
                || action instanceof AdministrationWorldActionService.PortalDeleteAction
                || action instanceof AdministrationWorldActionService.WildernessResetAction
                || action instanceof AdministrationWorldActionService.WildernessRestoreAction;
    }

    private AdministrationWorldViewService.Page<AdministrationWorldViewService.ClaimRow> claimsPage() {
        return AdministrationWorldViewService.claims(state(), viewerId, authorizationOverride(), query, page);
    }

    private AdministrationWorldViewService.Page<AdministrationWorldViewService.RegionRow> regionsPage() {
        return AdministrationWorldViewService.regions(state(), viewerId, authorizationOverride(), query, page);
    }

    private AdministrationWorldViewService.Page<AdministrationWorldViewService.PortalRow> portalsPage() {
        return AdministrationWorldViewService.portals(state(), viewerId, authorizationOverride(), query, page);
    }

    private AdministrationWorldViewService.Page<AdministrationWorldViewService.EvidenceRow> evidencePage() {
        return AdministrationWorldViewService.evidence(state(), viewerId, authorizationOverride(), query, page);
    }

    private AdministrationWorldViewService.WildernessView wildernessView() {
        return AdministrationWorldViewService.wilderness(
                viewer.level().getServer(), viewerId, authorizationOverride());
    }

    private PlatformSavedData state() {
        return PlatformSavedData.get(viewer.level().getServer());
    }

    private boolean authorizationOverride() {
        return state().roleOf(viewerId).isEmpty();
    }

    private void denyAndClose() {
        viewer.sendSystemMessage(Component.translatable("gui.rovenfall.admin.denied"));
        viewer.closeContainer();
    }

    private static ItemStack icon(Item item, String title, String lore) {
        return PlayerDashboardMenu.icon(item, Component.translatable(title), Component.translatable(lore));
    }

    private static Component enabled(boolean value) {
        return Component.translatable(value
                ? "gui.rovenfall.player.enabled" : "gui.rovenfall.player.disabled");
    }

    private static Component optionalUuid(Optional<UUID> value) {
        return value.<Component>map(uuid -> Component.literal(uuid.toString()))
                .orElseGet(() -> Component.translatable("gui.rovenfall.admin.world.none"));
    }

    private Component claimName(org.dldyou.rovenfall.claims.ClaimKey key) {
        return Component.translatable("gui.rovenfall.admin.world.claim.location",
                key.dimension().identifier().getPath().replace('_', ' '), key.chunkX(), key.chunkZ());
    }

    private Component playerName(UUID playerId) {
        String name = state().playerRecord(playerId).flatMap(PlayerRecord::displayName).orElse("");
        return name.isBlank()
                ? Component.translatable("gui.rovenfall.player.unknown_player")
                : Component.literal(name);
    }

    private Component optionalPlayerName(Optional<UUID> playerId) {
        return playerId.<Component>map(this::playerName)
                .orElseGet(() -> Component.translatable("gui.rovenfall.admin.world.none"));
    }

    private List<Map.Entry<UUID, PlayerRecord>> claimTargets() {
        Claim claim = selectedClaim == null ? null : state().claim(selectedClaim).orElse(null);
        if (claim == null) {
            return List.of();
        }
        String normalized = query.strip().toLowerCase(java.util.Locale.ROOT);
        List<Map.Entry<UUID, PlayerRecord>> source = formKind == FormKind.CLAIM_UNTRUST
                ? claim.trustedRoles().keySet().stream()
                        .map(playerId -> Map.entry(
                                playerId, state().playerRecord(playerId).orElseGet(() -> new PlayerRecord(0L, 0L))))
                        .toList()
                : normalized.isBlank()
                        ? state().playerRecords(AdministrationReadViewService.MAX_SCANNED_ROWS)
                        : state().playerRecordsMatchingName(
                                normalized, AdministrationReadViewService.MAX_SCANNED_ROWS);
        return source.stream()
                .filter(entry -> normalized.isBlank() || entry.getValue().displayName().orElse("")
                        .toLowerCase(java.util.Locale.ROOT).contains(normalized))
                .sorted(Comparator.comparing(entry -> entry.getValue().displayName().orElse("")))
                .toList();
    }

    private List<net.minecraft.resources.Identifier> dimensions() {
        String normalized = query.strip().toLowerCase(java.util.Locale.ROOT);
        return server().levelKeys().stream().map(ResourceKey::identifier)
                .filter(id -> normalized.isBlank() || id.toString().contains(normalized)).sorted().toList();
    }

    private Optional<net.minecraft.resources.Identifier> selectedDimension(int slot) {
        if (slot < CONTENT_START || slot >= CONTENT_START + CONTENT_SIZE) {
            return Optional.empty();
        }
        List<net.minecraft.resources.Identifier> dimensions = dimensions();
        int index = page * CONTENT_SIZE + slot - CONTENT_START;
        return index < dimensions.size() ? Optional.of(dimensions.get(index)) : Optional.empty();
    }

    private net.minecraft.server.MinecraftServer server() {
        return viewer.level().getServer();
    }

    private static int pages(int entries) {
        return entries == 0 ? 0 : (entries + CONTENT_SIZE - 1) / CONTENT_SIZE;
    }

    private static AdministrationFormType formType(FormKind kind) {
        return switch (kind) {
            case CLAIM_ROLE -> AdministrationFormType.WORLD_CLAIM_ROLE;
            case CLAIM_UNTRUST -> AdministrationFormType.WORLD_CLAIM_UNTRUST;
            case CLAIM_SETTINGS -> AdministrationFormType.WORLD_CLAIM_SETTINGS;
            case CLAIM_RECLAIM -> AdministrationFormType.WORLD_CLAIM_RECLAIM;
            case REGION_CREATE -> AdministrationFormType.WORLD_REGION_CREATE;
            case REGION_EDIT -> AdministrationFormType.WORLD_REGION_EDIT;
            case REGION_DELETE -> AdministrationFormType.WORLD_REGION_DELETE;
            case PORTAL_CREATE -> AdministrationFormType.WORLD_PORTAL_CREATE;
            case PORTAL_EDIT -> AdministrationFormType.WORLD_PORTAL_EDIT;
            case PORTAL_DISABLE -> AdministrationFormType.WORLD_PORTAL_DISABLE;
            case WILDERNESS_WARN -> AdministrationFormType.WORLD_WILDERNESS_WARN;
            case WILDERNESS_RESET -> AdministrationFormType.WORLD_WILDERNESS_RESET;
            case WILDERNESS_RESTORE -> AdministrationFormType.WORLD_WILDERNESS_RESTORE;
        };
    }

    private List<String> formDefaults(FormKind kind) {
        if (kind == FormKind.REGION_EDIT) {
            return state().protectedRegion(selectedRegion).map(AdministrationWorldTypedForm::regionDefaults)
                    .orElseGet(() -> formType(kind).defaults());
        }
        if (kind == FormKind.PORTAL_EDIT) {
            return state().portalDefinition(selectedPortal).map(AdministrationWorldTypedForm::portalDefaults)
                    .orElseGet(() -> formType(kind).defaults());
        }
        return formType(kind).defaults();
    }

    private static Component portalPolicy(PortalDefinition.SafeArrivalPolicy policy) {
        return Component.translatable("gui.rovenfall.admin.world.portal.policy." + policy.getSerializedName());
    }

    private static Component operationKind(WildernessResetState.Kind kind) {
        return Component.translatable("gui.rovenfall.admin.world.wilderness.kind." + kind.getSerializedName());
    }

    private static Component operationResult(WildernessResetState.Result result) {
        return Component.translatable("gui.rovenfall.admin.world.wilderness.result." + result.getSerializedName());
    }

    private static Component evidenceDetail(String detail) {
        String suffix = switch (detail) {
            case "completed" -> "completed";
            case "artifact_validation_failed" -> "artifact_validation_failed";
            case "filesystem_apply_failed" -> "filesystem_apply_failed";
            default -> "unknown";
        };
        return Component.translatable("gui.rovenfall.admin.world.wilderness.detail." + suffix);
    }

    private static Component inputError(String error) {
        return Component.translatable("gui.rovenfall.admin.world.form.error."
                + ("query_too_long".equals(error) ? "query_too_long" : "invalid_form"));
    }

    private static Component resultDetail(String detail) {
        String suffix = switch (detail) {
            case "success" -> "success";
            case "duplicate_transaction" -> "duplicate";
            case "no_change" -> "no_change";
            case "stale_confirmation" -> "stale";
            case "unauthorized" -> "unauthorized";
            case "rate_limited" -> "rate_limited";
            case "read_only_schema" -> "read_only";
            case "dependency_locked", "locked" -> "locked";
            case "endpoint_unavailable", "topology_unavailable" -> "topology";
            case "warning_required" -> "warning";
            case "snapshot_not_found", "snapshot_failed" -> "snapshot";
            case "evacuation_failed", "evacuation_rollback_failed", "precommit_failed" -> "lifecycle";
            case "claim_not_found", "not_found" -> "not_found";
            case "origin_conflict", "claim_conflict", "protection_conflict" -> "conflict";
            case "limit_exceeded", "trust_limit_reached" -> "limit";
            default -> "invalid";
        };
        return Component.translatable("gui.rovenfall.admin.world.result.detail." + suffix);
    }

    private static String formHint(FormKind kind) {
        return switch (kind) {
            case CLAIM_ROLE -> "gui.rovenfall.admin.world.form.claim_role";
            case CLAIM_UNTRUST -> "gui.rovenfall.admin.world.form.claim_target";
            case CLAIM_SETTINGS -> "gui.rovenfall.admin.world.form.claim_settings";
            case CLAIM_RECLAIM, REGION_DELETE, PORTAL_DISABLE, WILDERNESS_WARN, WILDERNESS_RESET,
                    WILDERNESS_RESTORE -> "gui.rovenfall.admin.world.form.reason";
            case REGION_CREATE -> "gui.rovenfall.admin.world.form.region_create";
            case REGION_EDIT -> "gui.rovenfall.admin.world.form.region_edit";
            case PORTAL_CREATE -> "gui.rovenfall.admin.world.form.portal_create";
            case PORTAL_EDIT -> "gui.rovenfall.admin.world.form.portal_edit";
        };
    }

    private enum Mode {
        CLAIMS,
        CLAIM_DETAIL,
        REGIONS,
        REGION_DETAIL,
        PORTALS,
        PORTAL_DETAIL,
        WILDERNESS,
        EVIDENCE,
        EVIDENCE_DETAIL,
        CLAIM_TARGET_SELECT,
        REGION_DIMENSION_SELECT,
        PORTAL_ORIGIN_DIMENSION_SELECT,
        PORTAL_DESTINATION_DIMENSION_SELECT,
        FORM,
        PREVIEW,
        RESULT
    }

    private enum FormKind {
        CLAIM_ROLE,
        CLAIM_UNTRUST,
        CLAIM_SETTINGS,
        CLAIM_RECLAIM,
        REGION_CREATE,
        REGION_EDIT,
        REGION_DELETE,
        PORTAL_CREATE,
        PORTAL_EDIT,
        PORTAL_DISABLE,
        WILDERNESS_WARN,
        WILDERNESS_RESET,
        WILDERNESS_RESTORE
    }
}
