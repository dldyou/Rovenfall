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
import net.minecraft.world.inventory.MenuType;
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
    private AdministrationWorldActionService.PendingAction pending;
    private AdministrationWorldActionService.Result result;
    private long lastHandledGameTime = Long.MIN_VALUE;

    private AdministrationWorldMenu(
            int containerId,
            Inventory inventory,
            ServerPlayer viewer,
            SimpleContainer contents,
            AdministrationReadViewService.Domain entryDomain) {
        super(MenuType.GENERIC_9x6, containerId, inventory, contents, 6);
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
                Component.translatable("gui.rovenfall.admin.world.title")));
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
        if (mode != Mode.CLAIMS && mode != Mode.REGIONS && mode != Mode.PORTALS && mode != Mode.EVIDENCE) {
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
            enterForm(FormKind.CLAIM_ROLE, Mode.CLAIM_DETAIL);
        } else if (slot == SECONDARY_SLOT) {
            enterForm(FormKind.CLAIM_UNTRUST, Mode.CLAIM_DETAIL);
        } else if (slot == TERTIARY_SLOT) {
            enterForm(FormKind.CLAIM_SETTINGS, Mode.CLAIM_DETAIL);
        } else if (slot == DANGER_SLOT) {
            enterForm(FormKind.CLAIM_RECLAIM, Mode.CLAIM_DETAIL);
        }
    }

    private void clickRegions(int slot) {
        if (slot == PRIMARY_SLOT && canManageRegions(currentRole())) {
            enterForm(FormKind.REGION_CREATE, Mode.REGIONS);
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
            enterForm(FormKind.PORTAL_CREATE, Mode.PORTALS);
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

    private boolean parseForm(String input) {
        PlatformSavedData state = state();
        UUID transactionId = UUID.randomUUID();
        pending = switch (formKind) {
            case CLAIM_ROLE -> AdministrationWorldFormParser.parseClaimRole(input)
                    .map(value -> new AdministrationWorldActionService.ClaimRoleAction(
                            transactionId, selectedClaim, state.claim(selectedClaim), value.playerId(), value.role(),
                            false, value.reason()))
                    .orElse(null);
            case CLAIM_UNTRUST -> AdministrationWorldFormParser.parseClaimTarget(input)
                    .map(value -> new AdministrationWorldActionService.ClaimRoleAction(
                            transactionId, selectedClaim, state.claim(selectedClaim), value.playerId(),
                            ClaimRole.VISITOR, true, value.reason()))
                    .orElse(null);
            case CLAIM_SETTINGS -> AdministrationWorldFormParser.parseClaimSettings(input)
                    .map(value -> new AdministrationWorldActionService.ClaimSettingsAction(
                            transactionId, selectedClaim, state.claim(selectedClaim),
                            new ClaimSettings(value.entryRestricted(), value.publicInteractions()), value.reason()))
                    .orElse(null);
            case CLAIM_RECLAIM -> reason(input)
                    .map(value -> new AdministrationWorldActionService.ClaimReclaimAction(
                            transactionId, selectedClaim, state.claim(selectedClaim), value))
                    .orElse(null);
            case REGION_CREATE -> AdministrationWorldFormParser.parseRegionCreate(input)
                    .map(value -> new AdministrationWorldActionService.RegionCreateAction(
                            transactionId, value.regionId(), new ProtectedRegion(
                                    viewerId, dimension(value.dimensionId()), value.minChunkX(), value.minChunkZ(),
                                    value.maxChunkX(), value.maxChunkZ()), value.reason()))
                    .orElse(null);
            case REGION_EDIT -> AdministrationWorldFormParser.parseRegionEdit(input)
                    .map(value -> new AdministrationWorldActionService.RegionEditAction(
                            transactionId, selectedRegion, state.protectedRegion(selectedRegion), new ProtectedRegion(
                                    viewerId, dimension(value.dimensionId()), value.minChunkX(), value.minChunkZ(),
                                    value.maxChunkX(), value.maxChunkZ()), value.reason()))
                    .orElse(null);
            case REGION_DELETE -> reason(input)
                    .map(value -> new AdministrationWorldActionService.RegionDeleteAction(
                            transactionId, selectedRegion, state.protectedRegion(selectedRegion), value))
                    .orElse(null);
            case PORTAL_CREATE -> AdministrationWorldFormParser.parsePortalCreate(input)
                    .map(value -> new AdministrationWorldActionService.PortalCreateAction(
                            transactionId, value.portalId(), portal(value), value.reason()))
                    .orElse(null);
            case PORTAL_EDIT -> AdministrationWorldFormParser.parsePortalEdit(input)
                    .map(value -> new AdministrationWorldActionService.PortalEditAction(
                            transactionId, selectedPortal, state.portalDefinition(selectedPortal), portal(value),
                            value.reason()))
                    .orElse(null);
            case PORTAL_DISABLE -> reason(input)
                    .map(value -> new AdministrationWorldActionService.PortalDeleteAction(
                            transactionId, selectedPortal, state.portalDefinition(selectedPortal), value))
                    .orElse(null);
            case WILDERNESS_WARN -> reason(input)
                    .map(value -> new AdministrationWorldActionService.WildernessWarnAction(
                            transactionId, state.wildernessResetState(), value))
                    .orElse(null);
            case WILDERNESS_RESET -> reason(input).flatMap(value -> state.wildernessResetState().warning()
                    .map(warning -> new AdministrationWorldActionService.WildernessResetAction(
                            transactionId, state.wildernessResetState(), warning.warningId(), value)))
                    .orElse(null);
            case WILDERNESS_RESTORE -> reason(input)
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
                    Items.GRASS_BLOCK, Component.literal(row.key().auditTarget()),
                    Component.translatable("gui.rovenfall.admin.world.field.owner", claim.ownerId().toString()),
                    Component.translatable("gui.rovenfall.admin.world.field.trusted", claim.trustedRoles().size()),
                    Component.translatable("gui.rovenfall.admin.world.field.flags",
                            enabled(claim.settings().entryRestricted()), enabled(claim.settings().publicInteractions())),
                    Component.translatable("gui.rovenfall.admin.world.field.transfer",
                            optionalUuid(claim.pendingTransferTo()))));
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
                Component.literal(selectedClaim.auditTarget()),
                Component.translatable("gui.rovenfall.admin.world.field.owner", claim.ownerId().toString()),
                Component.translatable("gui.rovenfall.admin.world.field.price", claim.purchasePrice()),
                Component.translatable("gui.rovenfall.admin.world.field.flags",
                        enabled(claim.settings().entryRestricted()), enabled(claim.settings().publicInteractions())),
                Component.translatable("gui.rovenfall.admin.world.field.transfer",
                        optionalUuid(claim.pendingTransferTo()))));
        List<Map.Entry<UUID, ClaimRole>> allTrusted = claim.trustedRoles().entrySet().stream()
                .sorted(Map.Entry.comparingByKey()).toList();
        int trustedPages = allTrusted.isEmpty() ? 0 : (allTrusted.size() + CONTENT_SIZE - 1) / CONTENT_SIZE;
        long offset = (long) page * CONTENT_SIZE;
        List<Map.Entry<UUID, ClaimRole>> trusted = offset >= allTrusted.size()
                ? List.of()
                : allTrusted.subList((int) offset, Math.min(allTrusted.size(), (int) offset + CONTENT_SIZE));
        for (int index = 0; index < trusted.size(); index++) {
            Map.Entry<UUID, ClaimRole> entry = trusted.get(index);
            contents.setItem(CONTENT_START + index, PlayerDashboardMenu.icon(
                    Items.PLAYER_HEAD, Component.literal(entry.getKey().toString()),
                    Component.translatable("gui.rovenfall.admin.world.field.claim_role",
                            Component.translatable(entry.getValue().translationKey()))));
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
                    Items.STRUCTURE_VOID, Component.literal(row.regionId().toString()),
                    Component.translatable("gui.rovenfall.admin.world.field.dimension",
                            region.dimension().identifier().toString()),
                    Component.translatable("gui.rovenfall.admin.world.field.bounds",
                            region.minChunkX(), region.minChunkZ(), region.maxChunkX(), region.maxChunkZ()),
                    Component.translatable("gui.rovenfall.admin.world.field.administrator",
                            region.administratorId().toString())));
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
                Items.STRUCTURE_VOID, Component.literal(selectedRegion.toString()),
                Component.translatable("gui.rovenfall.admin.world.field.dimension",
                        region.dimension().identifier().toString()),
                Component.translatable("gui.rovenfall.admin.world.field.bounds",
                        region.minChunkX(), region.minChunkZ(), region.maxChunkX(), region.maxChunkZ()),
                Component.translatable("gui.rovenfall.admin.world.field.area", region.areaChunks()),
                Component.translatable("gui.rovenfall.admin.world.field.administrator",
                        region.administratorId().toString())));
        if (canManageRegions(currentRole())) {
            contents.setItem(SECONDARY_SLOT, icon(
                    Items.WRITABLE_BOOK, "gui.rovenfall.admin.world.region.edit", "gui.rovenfall.admin.world.form.region_edit"));
            contents.setItem(DANGER_SLOT, icon(
                    Items.BARRIER, "gui.rovenfall.admin.world.region.delete", "gui.rovenfall.admin.world.irreversible"));
        }
        renderBack();
    }

    private void renderPortals() {
        var resultPage = portalsPage();
        renderListHeader(Items.ENDER_PEARL, "gui.rovenfall.admin.world.portals", resultPage);
        for (int index = 0; index < resultPage.entries().size(); index++) {
            var row = resultPage.entries().get(index);
            PortalDefinition portal = row.definition();
            contents.setItem(CONTENT_START + index, PlayerDashboardMenu.icon(
                    Items.ENDER_PEARL, Component.literal(row.portalId().toString()),
                    Component.translatable("gui.rovenfall.admin.world.field.origin", portal.origin().auditSummary()),
                    Component.translatable("gui.rovenfall.admin.world.field.destination",
                            portal.destination().auditSummary()),
                    Component.translatable("gui.rovenfall.admin.world.field.portal_policy",
                            portalPolicy(portal.safeArrivalPolicy()), portal.cooldownMillis(),
                            enabled(portal.allowCombat()))));
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
                Items.ENDER_EYE, Component.literal(selectedPortal.toString()),
                Component.translatable("gui.rovenfall.admin.world.field.origin", portal.origin().auditSummary()),
                Component.translatable("gui.rovenfall.admin.world.field.destination", portal.destination().auditSummary()),
                Component.translatable("gui.rovenfall.admin.world.field.radius", portal.protectionRadiusChunks()),
                Component.translatable("gui.rovenfall.admin.world.field.cooldown", portal.cooldownMillis()),
                Component.translatable("gui.rovenfall.admin.world.field.safe_policy",
                        portalPolicy(portal.safeArrivalPolicy())),
                Component.translatable("gui.rovenfall.admin.world.field.combat", enabled(portal.allowCombat()))));
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
                    Items.FILLED_MAP, Component.literal(operation.transactionId().toString()),
                    Component.translatable("gui.rovenfall.admin.world.field.operation",
                            operationKind(operation.kind()), operationResult(evidence.result())),
                    Component.translatable("gui.rovenfall.admin.world.field.snapshot",
                            operation.snapshotId().toString()),
                    Component.translatable("gui.rovenfall.admin.world.field.recovery_snapshot",
                            operation.recoverySnapshotId().toString()),
                    Component.translatable("gui.rovenfall.admin.world.field.completed",
                            evidence.completedAtEpochMillis(), evidenceDetail(evidence.detail()))));
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
                Items.FILLED_MAP, Component.literal(operation.transactionId().toString()),
                Component.translatable("gui.rovenfall.admin.world.field.operation",
                        operationKind(operation.kind()), operationResult(selectedEvidence.result())),
                Component.translatable("gui.rovenfall.admin.world.field.snapshot_evidence",
                        operation.snapshotId().toString(), operation.fileCount(), operation.byteCount(),
                        operation.sha256()),
                Component.translatable("gui.rovenfall.admin.world.field.recovery_evidence",
                        operation.recoverySnapshotId().toString(), operation.recoveryFileCount(),
                        operation.recoveryByteCount(), operation.recoverySha256()),
                Component.translatable("gui.rovenfall.admin.world.field.completed",
                        selectedEvidence.completedAtEpochMillis(), evidenceDetail(selectedEvidence.detail()))));
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
        contents.setItem(4, PlayerDashboardMenu.icon(
                Items.WRITABLE_BOOK, Component.translatable("gui.rovenfall.admin.world.form.title"),
                Component.translatable(formHint(formKind)),
                Component.translatable("gui.rovenfall.admin.world.form.submit"),
                formError.isBlank() ? Component.empty()
                        : Component.translatable("gui.rovenfall.admin.world.error", inputError(formError))));
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
        contents.setItem(4, PlayerDashboardMenu.icon(
                item, Component.translatable(titleKey),
                Component.translatable("gui.rovenfall.admin.page", page + 1, Math.max(1, resultPage.totalPages())),
                Component.translatable("gui.rovenfall.admin.total", resultPage.totalEntries()),
                Component.translatable(resultPage.truncated()
                        ? "gui.rovenfall.admin.truncated" : "gui.rovenfall.admin.complete"),
                Component.translatable("gui.rovenfall.admin.query", query.isBlank() ? "*" : query)));
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
            lines.add(Component.translatable("gui.rovenfall.admin.world.preview.claim", value.key().auditTarget()));
            lines.add(Component.translatable("gui.rovenfall.admin.world.preview.role_change",
                    value.playerId().toString(), Component.translatable(beforeRole.translationKey()),
                    Component.translatable((value.remove() ? ClaimRole.VISITOR : value.role()).translationKey())));
        } else if (action instanceof AdministrationWorldActionService.ClaimSettingsAction value) {
            ClaimSettings before = value.expectedClaim().orElseThrow().settings();
            lines.add(Component.translatable("gui.rovenfall.admin.world.claim.settings"));
            lines.add(Component.translatable("gui.rovenfall.admin.world.preview.claim", value.key().auditTarget()));
            lines.add(Component.translatable("gui.rovenfall.admin.world.preview.settings_change",
                    enabled(before.entryRestricted()), enabled(before.publicInteractions()),
                    enabled(value.settings().entryRestricted()), enabled(value.settings().publicInteractions())));
        } else if (action instanceof AdministrationWorldActionService.ClaimReclaimAction value) {
            lines.add(Component.translatable("gui.rovenfall.admin.world.claim.reclaim"));
            lines.add(Component.translatable("gui.rovenfall.admin.world.preview.reclaim",
                    value.key().auditTarget(), value.expectedClaim().orElseThrow().ownerId().toString()));
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
        lines.add(Component.translatable("gui.rovenfall.admin.world.preview.transaction",
                action.transactionId().toString()));
        lines.add(Component.translatable("gui.rovenfall.admin.world.preview.reason", action.reason()));
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
