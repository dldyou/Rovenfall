package org.dldyou.rovenfall.administration;

import java.time.Instant;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundTrackedWaypointPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.waypoints.Waypoint;
import net.minecraft.world.waypoints.WaypointStyleAssets;
import org.dldyou.rovenfall.claims.Claim;
import org.dldyou.rovenfall.claims.ClaimConfig;
import org.dldyou.rovenfall.claims.ClaimKey;
import org.dldyou.rovenfall.claims.ClaimRegionPolicy;
import org.dldyou.rovenfall.claims.ClaimRole;
import org.dldyou.rovenfall.claims.ClaimSettings;
import org.dldyou.rovenfall.quest.QuestProgressRuntime;
import org.dldyou.rovenfall.world.WorldTopology;

/** Server-owned land atlas and existing claim lifecycle exposed through the custom inventory UI. */
public final class PlayerClaimMenu extends ChestMenu implements AdministrationTextInputMenu {
    static final int MAX_CANDIDATES = 36;
    static final int MAX_CANDIDATE_SCAN = MAX_CANDIDATES * 4;
    static final int PAGE_SIZE = 36;
    private static final int MENU_SIZE = 54;
    private static final int CONTENT_START = 9;
    private static final int BACK_SLOT = 45;
    private static final int ADD_SLOT = 47;
    private static final int PREVIOUS_SLOT = 48;
    private static final int NEXT_SLOT = 50;
    private static final int REFRESH_SLOT = 53;
    private static final int NAVIGATE_SLOT = 29;
    private static final int CLEAR_NAVIGATION_SLOT = 31;
    static final UUID NAVIGATION_MARKER_ID = UUID.fromString("8ae6f57f-b7d2-4a9c-a86a-5914f629c06f");

    enum Page {
        ATLAS_HOME,
        ATLAS_LIST,
        OVERVIEW,
        TRUSTED,
        ROLE,
        SETTINGS,
        CANDIDATES,
        CONFIRM
    }

    enum PermissionAction {
        VIEW_TRUSTED,
        MANAGE_TRUST,
        MANAGE_SETTINGS,
        OFFER_TRANSFER,
        CANCEL_TRANSFER,
        ACCEPT_TRANSFER,
        SELL
    }

    private enum CandidatePurpose {
        TRUST,
        TRANSFER
    }

    enum ConfirmationKind {
        PURCHASE,
        TRANSFER_OFFER,
        TRANSFER_ACCEPT,
        TRANSFER_CANCEL,
        SALE;

        String translationKey() {
            return "gui.rovenfall.claim.action." + name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    private final ServerPlayer viewer;
    private final UUID viewerId;
    private final SimpleContainer contents;
    private Page page = Page.ATLAS_HOME;
    private int pageIndex;
    private ClaimKey viewedKey;
    private Optional<Claim> viewedClaim = Optional.empty();
    private boolean selectedCurrentLand;
    private Page detailReturnPage = Page.ATLAS_HOME;
    private ClaimAtlasView.Section atlasSection;
    private String atlasQuery = "";
    private ClaimAtlasView atlasView;
    private List<ClaimAtlasView.Row> displayedLands = List.of();
    private UUID selectedPlayer;
    private CandidatePurpose candidatePurpose;
    private List<UUID> displayedPlayers = List.of();
    private Confirmation confirmation;
    private long lastHandledGameTime = Long.MIN_VALUE;

    private PlayerClaimMenu(
            int containerId,
            Inventory inventory,
            ServerPlayer viewer,
            SimpleContainer contents) {
        super(MenuType.GENERIC_9x6, containerId, inventory, contents, 6);
        this.viewer = viewer;
        this.viewerId = viewer.getUUID();
        this.contents = contents;
        render();
        PlayerMenuNetwork.seedMenuSession(this, UUID.randomUUID());
    }

    public static void open(ServerPlayer player) {
        player.openMenu(new SimpleMenuProvider(
                (containerId, inventory, viewer) -> new PlayerClaimMenu(
                        containerId, inventory, (ServerPlayer) viewer, new SimpleContainer(MENU_SIZE)),
                Component.translatable("gui.rovenfall.claim.title")))
                .ifPresent(ignored -> PlayerMenuNetwork.sendMenuIdentity(player));
    }

    @Override
    public boolean applyTextInput(ServerPlayer player, String input) {
        if (player == null
                || !viewerId.equals(player.getUUID())
                || page != Page.ATLAS_LIST
                || atlasSection == null
                || atlasSection == ClaimAtlasView.Section.AVAILABLE
                || input == null
                || input.length() > ClaimAtlasView.MAX_QUERY_LENGTH
                || input.indexOf('\n') >= 0
                || input.indexOf('\r') >= 0) {
            return false;
        }
        atlasQuery = input.strip();
        pageIndex = 0;
        render();
        return true;
    }

    @Override
    public void clicked(int slotIndex, int buttonNum, ContainerInput input, Player player) {
        if (!(player instanceof ServerPlayer serverPlayer)
                || !viewerId.equals(serverPlayer.getUUID())
                || slotIndex < 0
                || slotIndex >= MENU_SIZE
                || !PlayerMenuNetwork.isPrimaryAction(buttonNum, input)) {
            return;
        }
        long gameTime = viewer.level().getGameTime();
        if (!isActionSlot(slotIndex)
                || !PlayerDashboardMenu.canHandleClick(lastHandledGameTime, gameTime)) {
            return;
        }
        lastHandledGameTime = gameTime;
        if (slotIndex == REFRESH_SLOT) {
            refresh();
            return;
        }
        if (slotIndex == BACK_SLOT) {
            back();
            return;
        }
        if (page == Page.ATLAS_HOME) {
            handleAtlasHome(slotIndex);
            return;
        }
        if (page == Page.ATLAS_LIST) {
            handleAtlasList(slotIndex);
            return;
        }
        if (!detailSelectionIsCurrent()) {
            stale();
            return;
        }
        if (slotIndex == PREVIOUS_SLOT && page == Page.TRUSTED) {
            pageIndex = Math.max(0, pageIndex - 1);
            render();
            return;
        }
        if (slotIndex == NEXT_SLOT && page == Page.TRUSTED) {
            pageIndex = Math.min(Integer.MAX_VALUE - 1, pageIndex + 1);
            render();
            return;
        }
        switch (page) {
            case ATLAS_HOME, ATLAS_LIST -> {
            }
            case OVERVIEW -> handleOverview(slotIndex);
            case TRUSTED -> handleTrusted(slotIndex);
            case ROLE -> handleRole(slotIndex);
            case SETTINGS -> handleSettings(slotIndex);
            case CANDIDATES -> handleCandidate(slotIndex);
            case CONFIRM -> handleConfirmation(slotIndex);
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slotIndex) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        return player.isAlive() && viewerId.equals(player.getUUID());
    }

    static EnumSet<PermissionAction> allowedActions(
            PlatformSavedData state, UUID actorId, Claim claim) {
        EnumSet<PermissionAction> actions = EnumSet.of(PermissionAction.VIEW_TRUSTED);
        if (ClaimManagementService.canManage(state, claim, actorId, false)) {
            actions.add(PermissionAction.MANAGE_TRUST);
            actions.add(PermissionAction.MANAGE_SETTINGS);
        }
        if (claim.ownerId().equals(actorId)) {
            actions.add(claim.pendingTransferTo().isPresent()
                    ? PermissionAction.CANCEL_TRANSFER
                    : PermissionAction.OFFER_TRANSFER);
            if (claim.pendingTransferTo().isEmpty()) {
                actions.add(PermissionAction.SELL);
            }
        }
        if (claim.pendingTransferTo().filter(actorId::equals).isPresent()) {
            actions.add(PermissionAction.ACCEPT_TRANSFER);
        }
        return actions;
    }

    static boolean canViewDetails(PlatformSavedData state, UUID actorId, Claim claim) {
        return state != null && actorId != null && claim != null
                && (!claim.settings().entryRestricted()
                        || claim.ownerId().equals(actorId)
                        || claim.trustedRoles().containsKey(actorId)
                        || claim.pendingTransferTo().filter(actorId::equals).isPresent()
                        || ClaimManagementService.canManage(state, claim, actorId, false));
    }

    static List<UUID> boundedCandidateIds(
            Collection<UUID> onlinePlayers, UUID ownerId, Set<UUID> excluded) {
        if (onlinePlayers == null || ownerId == null || excluded == null) {
            return List.of();
        }
        Set<UUID> candidates = new LinkedHashSet<>();
        int inspected = 0;
        for (UUID id : onlinePlayers) {
            if (inspected++ >= MAX_CANDIDATE_SCAN || candidates.size() >= MAX_CANDIDATES) {
                break;
            }
            if (id != null && !id.equals(ownerId) && !excluded.contains(id)) {
                candidates.add(id);
            }
        }
        return candidates.stream().sorted().toList();
    }

    static boolean confirmationIsCurrent(
            ClaimKey currentKey,
            Optional<Claim> currentClaim,
            Confirmation confirmation,
            Optional<Long> currentAmount) {
        return confirmation != null
                && (!confirmation.requiresCurrentPosition() || confirmation.key().equals(currentKey))
                && confirmation.expectedClaim().equals(currentClaim)
                && (confirmation.kind() != ConfirmationKind.PURCHASE
                        && confirmation.kind() != ConfirmationKind.SALE
                        || currentAmount.equals(Optional.of(confirmation.amount())));
    }

    private boolean isActionSlot(int slot) {
        if (slot == BACK_SLOT || slot == REFRESH_SLOT) {
            return true;
        }
        return switch (page) {
            case ATLAS_HOME -> slot == 10 || slot == 12 || slot == 14 || slot == 16
                    || slot == CLEAR_NAVIGATION_SLOT;
            case ATLAS_LIST -> slot == PREVIOUS_SLOT || slot == NEXT_SLOT
                    || slot >= CONTENT_START && slot - CONTENT_START < displayedLands.size();
            case OVERVIEW -> slot == 19 || slot == 21 || slot == 23 || slot == 25
                    || slot == NAVIGATE_SLOT || slot == 31 || slot == 33;
            case TRUSTED -> slot == ADD_SLOT || slot == PREVIOUS_SLOT || slot == NEXT_SLOT
                    || slot >= CONTENT_START && slot < CONTENT_START + PAGE_SIZE;
            case ROLE -> slot == 19 || slot == 21 || slot == 23 || slot == 25 || slot == 31;
            case SETTINGS -> slot == 20 || slot == 24;
            case CANDIDATES -> slot >= CONTENT_START && slot < CONTENT_START + PAGE_SIZE;
            case CONFIRM -> slot == 29 || slot == 33;
        };
    }

    private void handleAtlasHome(int slot) {
        switch (slot) {
            case 10 -> {
                viewedKey = currentKey();
                selectedCurrentLand = true;
                detailReturnPage = Page.ATLAS_HOME;
                page = Page.OVERVIEW;
                render();
            }
            case 12 -> openAtlas(ClaimAtlasView.Section.OWNED);
            case 14 -> openAtlas(ClaimAtlasView.Section.NEARBY);
            case 16 -> openAtlas(ClaimAtlasView.Section.AVAILABLE);
            case CLEAR_NAVIGATION_SLOT -> clearNavigation();
            default -> {
            }
        }
    }

    private void openAtlas(ClaimAtlasView.Section section) {
        atlasSection = section;
        atlasQuery = "";
        pageIndex = 0;
        page = Page.ATLAS_LIST;
        render();
    }

    private void handleAtlasList(int slot) {
        ClaimAtlasView fresh = createAtlasView();
        if (slot == PREVIOUS_SLOT) {
            pageIndex = Math.max(0, pageIndex - 1);
            render();
            return;
        }
        if (slot == NEXT_SLOT) {
            pageIndex = Math.min(Math.max(0, fresh.totalPages() - 1), pageIndex + 1);
            render();
            return;
        }
        int index = slot - CONTENT_START;
        if (atlasView == null || !atlasView.equals(fresh)
                || index < 0 || index >= displayedLands.size()) {
            staleAtlas();
            return;
        }
        ClaimAtlasView.Row selected = displayedLands.get(index);
        if (!selected.actionable()) {
            navigateTo(selected.key());
            return;
        }
        viewedKey = selected.key();
        viewedClaim = selected.expectedClaim();
        selectedCurrentLand = selected.current();
        detailReturnPage = Page.ATLAS_LIST;
        page = Page.OVERVIEW;
        render();
    }

    private void handleOverview(int slot) {
        PlatformSavedData state = platform();
        Claim claim = state.claim(viewedKey).orElse(null);
        if (claim != null && !canViewDetails(state, viewerId, claim)) {
            rejectUnauthorized("view_private_land");
            return;
        }
        if (slot == NAVIGATE_SLOT) {
            navigateTo(viewedKey);
            return;
        }
        if (claim == null) {
            if (slot == 31
                    && currentKey().equals(viewedKey)
                    && WorldTopology.allowsClaims(viewedKey.dimension())
                    && !isProtected(viewedKey)) {
                Optional<Long> price = purchasePrice(state);
                if (price.isEmpty()) {
                    message("command.rovenfall.claim.error.invalid_configuration");
                    render();
                    return;
                }
                confirm(ConfirmationKind.PURCHASE, null, price.orElseThrow(), null);
            }
            return;
        }

        EnumSet<PermissionAction> actions = allowedActions(state, viewerId, claim);
        switch (slot) {
            case 19 -> {
                if (actions.contains(PermissionAction.VIEW_TRUSTED)) {
                    page = Page.TRUSTED;
                    pageIndex = 0;
                    render();
                }
            }
            case 21 -> {
                if (actions.contains(PermissionAction.MANAGE_SETTINGS)) {
                    page = Page.SETTINGS;
                    render();
                } else {
                    rejectUnauthorized("open_settings");
                }
            }
            case 23 -> {
                if (actions.contains(PermissionAction.MANAGE_TRUST)) {
                    candidatePurpose = CandidatePurpose.TRUST;
                    page = Page.CANDIDATES;
                    render();
                } else {
                    rejectUnauthorized("open_trust");
                }
            }
            case 25 -> {
                if (actions.contains(PermissionAction.OFFER_TRANSFER)) {
                    candidatePurpose = CandidatePurpose.TRANSFER;
                    page = Page.CANDIDATES;
                    render();
                } else if (actions.contains(PermissionAction.CANCEL_TRANSFER)) {
                    confirm(ConfirmationKind.TRANSFER_CANCEL, claim, 0, claim.pendingTransferTo().orElse(null));
                } else if (actions.contains(PermissionAction.ACCEPT_TRANSFER)) {
                    confirm(ConfirmationKind.TRANSFER_ACCEPT, claim, 0, viewerId);
                } else {
                    rejectUnauthorized("transfer");
                }
            }
            case 33 -> {
                if (actions.contains(PermissionAction.SELL) && claim.purchasePrice() > 0) {
                    confirm(ConfirmationKind.SALE, claim, ClaimManagementService.refund(
                            claim.purchasePrice(), ClaimConfig.saleRefundPercent()), null);
                } else if (!claim.ownerId().equals(viewerId)) {
                    rejectUnauthorized("sale");
                }
            }
            default -> {
            }
        }
    }

    private void handleTrusted(int slot) {
        Claim claim = platform().claim(viewedKey).orElse(null);
        if (claim == null) {
            stale();
            return;
        }
        EnumSet<PermissionAction> actions = allowedActions(platform(), viewerId, claim);
        if (slot == ADD_SLOT) {
            if (actions.contains(PermissionAction.MANAGE_TRUST)) {
                candidatePurpose = CandidatePurpose.TRUST;
                page = Page.CANDIDATES;
                render();
            } else {
                rejectUnauthorized("open_trust");
            }
            return;
        }
        int index = slot - CONTENT_START;
        if (index < 0 || index >= displayedPlayers.size()) {
            render();
            return;
        }
        selectedPlayer = displayedPlayers.get(index);
        page = Page.ROLE;
        render();
    }

    private void handleRole(int slot) {
        Claim claim = platform().claim(viewedKey).orElse(null);
        if (claim == null || selectedPlayer == null || !claim.trustedRoles().containsKey(selectedPlayer)) {
            stale();
            return;
        }
        if (!allowedActions(platform(), viewerId, claim).contains(PermissionAction.MANAGE_TRUST)) {
            rejectUnauthorized("manage_role");
            return;
        }
        ClaimRole role = switch (slot) {
            case 19 -> ClaimRole.MANAGER;
            case 21 -> ClaimRole.BUILDER;
            case 23 -> ClaimRole.USER;
            case 25 -> ClaimRole.VISITOR;
            default -> null;
        };
        if (slot != 31 && claim.trustedRoles().get(selectedPlayer) == role) {
            message("command.rovenfall.claim.no_change");
            render();
            return;
        }
        if (!beginMutation()) {
            return;
        }
        ClaimManagementService.Result result = slot == 31
                ? ClaimManagementService.removeRole(
                        platform(), viewerId, false, viewedKey, selectedPlayer,
                        "player claim GUI untrust", now(), UUID.randomUUID())
                : ClaimManagementService.setRole(
                        platform(), viewerId, false, viewedKey, selectedPlayer, role,
                        "player claim GUI role", now(), UUID.randomUUID());
        showMutationResult(result);
        if (result.status() == ClaimManagementService.Status.SUCCESS
                || result.status() == ClaimManagementService.Status.NO_CHANGE) {
            if (slot == 31) {
                selectedPlayer = null;
                page = Page.TRUSTED;
            }
        }
        render();
    }

    private void handleSettings(int slot) {
        Claim claim = platform().claim(viewedKey).orElse(null);
        if (claim == null) {
            stale();
            return;
        }
        ClaimSettings settings = claim.settings();
        ClaimSettings updated = switch (slot) {
            case 20 -> new ClaimSettings(!settings.entryRestricted(), settings.publicInteractions());
            case 24 -> new ClaimSettings(settings.entryRestricted(), !settings.publicInteractions());
            default -> settings;
        };
        if (!beginMutation()) {
            return;
        }
        ClaimManagementService.Result result = ClaimManagementService.setSettings(
                platform(), viewerId, false, viewedKey, updated,
                "player claim GUI settings", now(), UUID.randomUUID());
        showMutationResult(result);
        render();
    }

    private void handleCandidate(int slot) {
        int index = slot - CONTENT_START;
        if (index < 0 || index >= displayedPlayers.size()) {
            render();
            return;
        }
        UUID target = displayedPlayers.get(index);
        Claim claim = platform().claim(viewedKey).orElse(null);
        if (claim == null || candidatePurpose == null) {
            stale();
            return;
        }
        if (candidatePurpose == CandidatePurpose.TRANSFER) {
            if (!allowedActions(platform(), viewerId, claim).contains(PermissionAction.OFFER_TRANSFER)) {
                rejectUnauthorized("transfer_offer");
                return;
            }
            confirm(ConfirmationKind.TRANSFER_OFFER, claim, 0, target);
            return;
        }
        if (claim.trustedRoles().containsKey(target)) {
            stale();
            return;
        }
        if (!beginMutation()) {
            return;
        }
        ClaimManagementService.Result result = ClaimManagementService.setRole(
                platform(), viewerId, false, viewedKey, target, ClaimRole.USER,
                "player claim GUI trust", now(), UUID.randomUUID());
        selectedPlayer = target;
        showMutationResult(result);
        page = result.status() == ClaimManagementService.Status.SUCCESS
                || result.status() == ClaimManagementService.Status.NO_CHANGE
                ? Page.ROLE
                : Page.TRUSTED;
        render();
    }

    private void handleConfirmation(int slot) {
        if (slot == 29) {
            confirmation = null;
            page = Page.OVERVIEW;
            render();
            return;
        }
        if (slot != 33 || confirmation == null) {
            return;
        }
        Optional<Long> currentAmount = switch (confirmation.kind()) {
            case PURCHASE -> purchasePrice(platform());
            case SALE -> platform().claim(viewedKey).map(claim -> ClaimManagementService.refund(
                    claim.purchasePrice(), ClaimConfig.saleRefundPercent()));
            default -> Optional.empty();
        };
        if (!confirmationIsCurrent(
                currentKey(), platform().claim(viewedKey), confirmation, currentAmount)) {
            stale();
            return;
        }
        if (!beginMutation()) {
            return;
        }
        Confirmation action = confirmation;
        confirmation = null;
        switch (action.kind()) {
            case PURCHASE -> executePurchase();
            case TRANSFER_OFFER -> showMutationResult(ClaimManagementService.offerTransfer(
                    platform(), viewerId, viewedKey, action.targetId(),
                    "player claim GUI transfer offer", now(), UUID.randomUUID()));
            case TRANSFER_ACCEPT -> showMutationResult(ClaimManagementService.acceptTransfer(
                    platform(), viewerId, viewedKey, this::isProtected, ClaimConfig.ownershipCap(),
                    "player claim GUI transfer accept", now(), UUID.randomUUID()));
            case TRANSFER_CANCEL -> showMutationResult(ClaimManagementService.cancelTransfer(
                    platform(), viewerId, viewedKey,
                    "player claim GUI transfer cancel", now(), UUID.randomUUID()));
            case SALE -> showMutationResult(ClaimManagementService.sell(
                    platform(), viewerId, viewedKey, ClaimConfig.saleRefundPercent(),
                    EconomyConfig.maximumBalance(), "player claim GUI sale", now(), UUID.randomUUID()));
        }
        resetDetailToOverview();
        render();
    }

    private void executePurchase() {
        ClaimPurchaseService.PurchaseResult result = ClaimPurchaseService.purchase(
                platform(), viewerId, WorldTopology.HUB, viewer.level().dimension(), viewer.blockPosition(),
                ignored -> true, this::isProtected,
                ClaimConfig.basePrice(), ClaimConfig.priceIncrease(), ClaimConfig.ownershipCap(),
                now(), UUID.randomUUID());
        if (result.status() == ClaimPurchaseService.Status.SUCCESS) {
            QuestProgressRuntime.acceptEconomyEvidence(viewer.level().getServer(), result.transactionId());
            message("gui.rovenfall.claim.purchase_complete", result.price(), result.balance());
        } else if (result.status() == ClaimPurchaseService.Status.DUPLICATE_TRANSACTION) {
            message("gui.rovenfall.claim.purchase_duplicate");
        } else {
            message(purchaseErrorTranslationKey(result.status()), result.price(), result.balance());
        }
    }

    private void showMutationResult(ClaimManagementService.Result result) {
        switch (result.status()) {
            case SUCCESS -> message("gui.rovenfall.claim.updated");
            case DUPLICATE_TRANSACTION -> message("gui.rovenfall.claim.action_duplicate");
            case NO_CHANGE -> message("command.rovenfall.claim.no_change");
            default -> message(mutationErrorTranslationKey(result.status()));
        }
    }

    static String purchaseErrorTranslationKey(ClaimPurchaseService.Status status) {
        return switch (status) {
            case TRANSACTION_ID_CONFLICT -> "command.rovenfall.claim.error.transaction_id_conflict";
            case INVALID_REQUEST, INVALID_TRANSACTION -> "command.rovenfall.claim.error.invalid_request";
            case READ_ONLY_SCHEMA -> "command.rovenfall.claim.error.read_only";
            case NOT_IN_HUB -> "command.rovenfall.claim.error.not_in_hub";
            case INELIGIBLE_CHUNK -> "command.rovenfall.claim.error.ineligible";
            case PROTECTED_CHUNK -> "command.rovenfall.claim.error.protected";
            case ALREADY_CLAIMED -> "command.rovenfall.claim.error.already_claimed";
            case OWNERSHIP_CAP_REACHED -> "command.rovenfall.claim.error.cap";
            case ACCOUNT_NOT_FOUND -> "command.rovenfall.claim.error.account_missing";
            case INVALID_CONFIGURATION, PRICE_OVERFLOW -> "command.rovenfall.claim.error.invalid_configuration";
            case INSUFFICIENT_FUNDS -> "command.rovenfall.claim.error.insufficient_funds";
            case TRANSACTION_LEDGER_FULL -> "command.rovenfall.claim.error.ledger_full";
            case SUCCESS, DUPLICATE_TRANSACTION -> "command.rovenfall.claim.buy.duplicate";
        };
    }

    static String mutationErrorTranslationKey(ClaimManagementService.Status status) {
        return switch (status) {
            case TRANSACTION_ID_CONFLICT -> "command.rovenfall.claim.error.transaction_id_conflict";
            case INVALID_REQUEST, INVALID_TRANSACTION -> "command.rovenfall.claim.error.invalid_request";
            case INVALID_REASON -> "command.rovenfall.claim.error.invalid_request";
            case READ_ONLY_SCHEMA -> "command.rovenfall.claim.error.read_only";
            case UNAUTHORIZED -> "command.rovenfall.claim.error.unauthorized";
            case CLAIM_NOT_FOUND -> "command.rovenfall.claim.error.not_found";
            case INVALID_TARGET -> "command.rovenfall.claim.error.invalid_target";
            case TRUST_LIMIT_REACHED -> "command.rovenfall.claim.error.trust_limit";
            case PROTECTED_CHUNK -> "command.rovenfall.claim.error.protected";
            case OWNERSHIP_CAP_REACHED -> "command.rovenfall.claim.error.cap";
            case TRANSFER_NOT_PENDING -> "command.rovenfall.claim.error.transfer_not_pending";
            case TRANSFER_PENDING -> "command.rovenfall.claim.error.transfer_pending";
            case PURCHASE_PRICE_UNAVAILABLE -> "command.rovenfall.claim.error.purchase_price_unavailable";
            case ACCOUNT_NOT_FOUND -> "command.rovenfall.claim.error.account_missing";
            case OVERFLOW, MAXIMUM_BALANCE_EXCEEDED -> "command.rovenfall.claim.error.balance_limit";
            case TRANSACTION_LEDGER_FULL -> "command.rovenfall.claim.error.ledger_full";
            case SUCCESS, DUPLICATE_TRANSACTION, NO_CHANGE -> "command.rovenfall.claim.no_change";
        };
    }

    private void confirm(ConfirmationKind kind, Claim expectedClaim, long amount, UUID targetId) {
        confirmation = new Confirmation(
                kind, viewedKey, Optional.ofNullable(expectedClaim), amount, targetId,
                kind == ConfirmationKind.PURCHASE);
        page = Page.CONFIRM;
        render();
    }

    private void back() {
        switch (page) {
            case ATLAS_HOME -> {
                PlayerDashboardMenu.open(viewer);
                return;
            }
            case ATLAS_LIST -> resetToAtlasHome();
            case OVERVIEW -> page = detailReturnPage;
            case TRUSTED, SETTINGS -> page = Page.OVERVIEW;
            case ROLE -> page = Page.TRUSTED;
            case CANDIDATES -> page = candidatePurpose == CandidatePurpose.TRUST
                    ? Page.TRUSTED
                    : Page.OVERVIEW;
            case CONFIRM -> page = candidatePurpose == CandidatePurpose.TRANSFER
                    && confirmation != null && confirmation.kind() == ConfirmationKind.TRANSFER_OFFER
                    ? Page.CANDIDATES
                    : Page.OVERVIEW;
        }
        confirmation = null;
        render();
    }

    private void render() {
        contents.clearContent();
        if (page != Page.ATLAS_HOME && page != Page.ATLAS_LIST) {
            if (viewedKey == null) {
                viewedKey = currentKey();
                selectedCurrentLand = true;
            }
            viewedClaim = platform().claim(viewedKey);
        }
        switch (page) {
            case ATLAS_HOME -> renderAtlasHome();
            case ATLAS_LIST -> renderAtlasList();
            case OVERVIEW -> renderOverview();
            case TRUSTED -> renderTrusted();
            case ROLE -> renderRole();
            case SETTINGS -> renderSettings();
            case CANDIDATES -> renderCandidates();
            case CONFIRM -> renderConfirmation();
        }
        contents.setItem(REFRESH_SLOT, PlayerDashboardMenu.icon(
                Items.CLOCK,
                Component.translatable("gui.rovenfall.player.refresh"),
                Component.translatable("gui.rovenfall.player.click")));
        broadcastChanges();
    }

    private void renderAtlasHome() {
        PlatformSavedData state = platform();
        ClaimKey current = currentKey();
        Claim currentClaim = state.claim(current).orElse(null);
        contents.setItem(4, PlayerDashboardMenu.icon(
                Items.MAP,
                Component.translatable("gui.rovenfall.claim.atlas.title"),
                Component.translatable("gui.rovenfall.claim.atlas.summary"),
                Component.translatable("gui.rovenfall.claim.atlas.no_commands")));
        contents.setItem(10, PlayerDashboardMenu.icon(
                Items.GRASS_BLOCK,
                Component.translatable("gui.rovenfall.claim.atlas.current"),
                currentClaim == null
                        ? Component.translatable("gui.rovenfall.player.claim.unclaimed")
                        : currentClaim.ownerId().equals(viewerId)
                                ? Component.translatable("gui.rovenfall.player.claim.owned")
                                : Component.translatable("gui.rovenfall.player.claim.other"),
                Component.translatable("gui.rovenfall.player.click")));
        contents.setItem(12, PlayerDashboardMenu.icon(
                Items.CHEST,
                Component.translatable("gui.rovenfall.claim.atlas.owned"),
                Component.translatable("gui.rovenfall.player.owned_claims", state.claimCount(viewerId)),
                Component.translatable("gui.rovenfall.player.click")));
        contents.setItem(14, PlayerDashboardMenu.icon(
                Items.SPYGLASS,
                Component.translatable("gui.rovenfall.claim.atlas.nearby"),
                Component.translatable(
                        "gui.rovenfall.claim.atlas.radius", ClaimAtlasView.NEARBY_RADIUS * 16),
                Component.translatable("gui.rovenfall.player.click")));
        contents.setItem(16, PlayerDashboardMenu.icon(
                Items.MAP,
                Component.translatable("gui.rovenfall.claim.atlas.available"),
                Component.translatable(
                        "gui.rovenfall.claim.atlas.radius", ClaimAtlasView.NEARBY_RADIUS * 16),
                Component.translatable("gui.rovenfall.player.click")));
        contents.setItem(CLEAR_NAVIGATION_SLOT, PlayerDashboardMenu.icon(
                Items.BARRIER,
                Component.translatable("gui.rovenfall.claim.atlas.navigation.clear"),
                Component.translatable("gui.rovenfall.player.click")));
        addBack();
    }

    private void renderAtlasList() {
        atlasView = createAtlasView();
        pageIndex = atlasView.page();
        displayedLands = atlasView.entries();
        List<Component> headerLore = new java.util.ArrayList<>();
        headerLore.add(Component.translatable(
                "gui.rovenfall.player.page",
                atlasView.totalEntries() == 0 ? 0 : atlasView.page() + 1,
                atlasView.totalPages(),
                atlasView.totalEntries()));
        if (atlasSection != ClaimAtlasView.Section.AVAILABLE) {
            headerLore.add(Component.translatable("gui.rovenfall.claim.atlas.search.hint"));
        }
        if (atlasView.truncated()) {
            headerLore.add(Component.translatable("gui.rovenfall.claim.atlas.truncated"));
        }
        ItemStack header = PlayerDashboardMenu.icon(
                Items.MAP,
                Component.translatable(sectionTranslationKey(atlasSection)),
                headerLore.toArray(Component[]::new));
        if (atlasSection != ClaimAtlasView.Section.AVAILABLE) {
            AdministrationFormMarker.writeSearch(header);
        }
        contents.setItem(4, header);
        for (int offset = 0; offset < displayedLands.size(); offset++) {
            contents.setItem(CONTENT_START + offset, atlasRowIcon(displayedLands.get(offset)));
        }
        if (displayedLands.isEmpty()) {
            contents.setItem(22, PlayerDashboardMenu.icon(
                    Items.BARRIER,
                    Component.translatable(atlasQuery.isBlank()
                            ? "gui.rovenfall.claim.atlas.empty"
                            : "gui.rovenfall.claim.atlas.search.empty")));
        }
        if (atlasView.page() > 0) {
            contents.setItem(PREVIOUS_SLOT, PlayerDashboardMenu.icon(
                    Items.ARROW, Component.translatable("gui.rovenfall.player.previous")));
        }
        if (atlasView.page() + 1 < atlasView.totalPages()) {
            contents.setItem(NEXT_SLOT, PlayerDashboardMenu.icon(
                    Items.ARROW, Component.translatable("gui.rovenfall.player.next")));
        }
        addBack();
    }

    private ItemStack atlasRowIcon(ClaimAtlasView.Row row) {
        ItemStack stack = PlayerDashboardMenu.icon(
                row.relation() == ClaimAtlasView.Relation.AVAILABLE ? Items.MAP : Items.GRASS_BLOCK,
                atlasRowName(row),
                Component.translatable(relationTranslationKey(row.relation())),
                row.distanceChunks() < 0
                        ? Component.translatable("gui.rovenfall.claim.atlas.other_world")
                        : Component.translatable(
                                "gui.rovenfall.claim.atlas.distance",
                                Math.min(Integer.MAX_VALUE, (long) row.distanceChunks() * 16L)),
                row.direction()
                        .<Component>map(direction -> Component.translatable(
                                "gui.rovenfall.claim.atlas.direction",
                                Component.translatable(directionTranslationKey(direction))))
                        .orElseGet(Component::empty),
                row.current()
                        ? Component.translatable("gui.rovenfall.claim.atlas.here")
                        : Component.empty(),
                Component.translatable(row.actionable()
                        ? row.relation() == ClaimAtlasView.Relation.AVAILABLE
                                ? "gui.rovenfall.claim.atlas.action.purchase"
                                : row.relation() == ClaimAtlasView.Relation.TRANSFER_PENDING
                                        ? "gui.rovenfall.claim.atlas.action.review_transfer"
                                        : "gui.rovenfall.claim.atlas.action.manage"
                        : "gui.rovenfall.claim.atlas.action.navigate"),
                Component.translatable(
                        "gui.rovenfall.claim.atlas.technical.position",
                        row.key().dimension().identifier().toString(), row.key().chunkX(), row.key().chunkZ()));
        return stack;
    }

    private Component atlasRowName(ClaimAtlasView.Row row) {
        return switch (row.relation()) {
            case OWNER -> Component.translatable("gui.rovenfall.claim.atlas.my_land");
            case TRUSTED -> Component.translatable(
                    "gui.rovenfall.claim.atlas.trusted_land",
                    row.ownerName().<Component>map(Component::literal)
                            .orElseGet(() -> Component.translatable("gui.rovenfall.player.unknown_player")));
            case TRANSFER_PENDING -> Component.translatable(
                    "gui.rovenfall.claim.atlas.transfer_land",
                    row.ownerName().<Component>map(Component::literal)
                            .orElseGet(() -> Component.translatable("gui.rovenfall.player.unknown_player")));
            case MODERATED -> Component.translatable(
                    "gui.rovenfall.claim.atlas.moderated_land",
                    row.ownerName().<Component>map(Component::literal)
                            .orElseGet(() -> Component.translatable("gui.rovenfall.player.unknown_player")));
            case PUBLIC -> Component.translatable(
                    "gui.rovenfall.claim.atlas.public_land",
                    row.ownerName().<Component>map(Component::literal)
                            .orElseGet(() -> Component.translatable("gui.rovenfall.player.unknown_player")));
            case AVAILABLE -> Component.translatable("gui.rovenfall.claim.atlas.available_land");
        };
    }

    private static String sectionTranslationKey(ClaimAtlasView.Section section) {
        return "gui.rovenfall.claim.atlas.section."
                + section.name().toLowerCase(java.util.Locale.ROOT);
    }

    private static String relationTranslationKey(ClaimAtlasView.Relation relation) {
        return "gui.rovenfall.claim.atlas.relation."
                + relation.name().toLowerCase(java.util.Locale.ROOT);
    }

    private static String directionTranslationKey(ClaimAtlasView.Direction direction) {
        return "gui.rovenfall.claim.atlas.direction."
                + direction.name().toLowerCase(java.util.Locale.ROOT);
    }

    private ClaimAtlasView createAtlasView() {
        return ClaimAtlasView.create(
                platform(), currentKey(), atlasSection, viewerId, atlasQuery, pageIndex,
                this::isProtected, this::playerDisplayName);
    }

    private void renderOverview() {
        PlatformSavedData state = platform();
        Claim claim = state.claim(viewedKey).orElse(null);
        boolean currentPosition = currentKey().equals(viewedKey);
        contents.setItem(4, PlayerDashboardMenu.icon(
                Items.GRASS_BLOCK,
                Component.translatable(currentPosition
                        ? "gui.rovenfall.claim.current_land"
                        : "gui.rovenfall.claim.atlas.selected_land"),
                Component.translatable(currentPosition
                        ? "gui.rovenfall.claim.current_location"
                        : "gui.rovenfall.claim.atlas.selected_location"),
                Component.translatable("gui.rovenfall.player.owned_claims", state.claimCount(viewerId)),
                Component.translatable("gui.rovenfall.player.balance", state.economyBalance(viewerId).orElse(0L)),
                Component.translatable(
                        "gui.rovenfall.claim.atlas.technical.position",
                        viewedKey.dimension().identifier().toString(), viewedKey.chunkX(), viewedKey.chunkZ())));
        addBack();
        if (!currentPosition) {
            contents.setItem(NAVIGATE_SLOT, PlayerDashboardMenu.icon(
                    Items.COMPASS,
                    Component.translatable("gui.rovenfall.claim.atlas.navigation.start"),
                    Component.translatable("gui.rovenfall.claim.atlas.navigation.locator"),
                    Component.translatable("gui.rovenfall.player.click")));
        }

        if (claim != null && !canViewDetails(state, viewerId, claim)) {
            contents.setItem(13, PlayerDashboardMenu.icon(
                    Items.BARRIER,
                    Component.translatable("gui.rovenfall.claim.atlas.private_land"),
                    Component.translatable("gui.rovenfall.claim.atlas.private_details")));
            return;
        }

        if (claim == null) {
            boolean claimableWorld = WorldTopology.allowsClaims(viewedKey.dimension());
            boolean protectedRegion = claimableWorld && isProtected(viewedKey);
            Optional<Long> price = purchasePrice(state);
            contents.setItem(13, PlayerDashboardMenu.icon(
                    protectedRegion ? Items.BARRIER : Items.MAP,
                    Component.translatable(!claimableWorld
                            ? "command.rovenfall.claim.error.not_in_hub"
                            : protectedRegion
                                    ? "gui.rovenfall.player.claim.protected"
                                    : "gui.rovenfall.player.claim.unclaimed"),
                    price.<Component>map(value -> Component.translatable("gui.rovenfall.claim.purchase_price", value))
                            .orElseGet(() -> Component.translatable(
                                    "command.rovenfall.claim.error.invalid_configuration"))));
            if (claimableWorld && !protectedRegion && price.isPresent() && currentPosition) {
                contents.setItem(31, PlayerDashboardMenu.icon(
                        Items.GOLD_INGOT,
                        Component.translatable("gui.rovenfall.claim.purchase"),
                        Component.translatable("gui.rovenfall.claim.purchase_price", price.orElseThrow()),
                        Component.translatable("gui.rovenfall.claim.confirm_required")));
            } else if (claimableWorld && !protectedRegion && price.isPresent()) {
                contents.setItem(31, PlayerDashboardMenu.icon(
                        Items.BARRIER,
                        Component.translatable("gui.rovenfall.claim.atlas.purchase_here"),
                        Component.translatable("gui.rovenfall.claim.atlas.purchase_here_hint")));
            }
            return;
        }

        EnumSet<PermissionAction> actions = allowedActions(state, viewerId, claim);
        contents.setItem(10, PlayerDashboardMenu.icon(
                Items.PLAYER_HEAD,
                Component.translatable("gui.rovenfall.claim.owner"),
                playerName(claim.ownerId()),
                Component.translatable("gui.rovenfall.player.role",
                        Component.translatable(claim.roleOf(viewerId).translationKey()))));
        contents.setItem(12, PlayerDashboardMenu.icon(
                Items.GOLD_INGOT,
                Component.translatable("gui.rovenfall.claim.value"),
                Component.translatable("gui.rovenfall.claim.purchase_price", claim.purchasePrice()),
                Component.translatable("gui.rovenfall.claim.sale_refund",
                        claim.purchasePrice() > 0
                                ? ClaimManagementService.refund(
                                        claim.purchasePrice(), ClaimConfig.saleRefundPercent())
                                : 0L)));
        contents.setItem(14, settingsIcon(claim.settings()));
        contents.setItem(16, PlayerDashboardMenu.icon(
                Items.ENDER_PEARL,
                Component.translatable("gui.rovenfall.claim.transfer"),
                claim.pendingTransferTo()
                        .<Component>map(id -> Component.translatable(
                                "gui.rovenfall.claim.transfer_pending", playerName(id)))
                        .orElseGet(() -> Component.translatable("gui.rovenfall.claim.transfer_none"))));

        if (actions.contains(PermissionAction.VIEW_TRUSTED)) {
            contents.setItem(19, PlayerDashboardMenu.icon(
                    Items.BOOK,
                    Component.translatable("gui.rovenfall.claim.trusted"),
                    Component.translatable("gui.rovenfall.claim.trusted_count", claim.trustedRoles().size()),
                    Component.translatable("gui.rovenfall.player.click")));
        }
        if (actions.contains(PermissionAction.MANAGE_SETTINGS)) {
            contents.setItem(21, PlayerDashboardMenu.icon(
                    Items.REPEATER,
                    Component.translatable("gui.rovenfall.claim.manage_settings"),
                    Component.translatable("gui.rovenfall.player.click")));
        }
        if (actions.contains(PermissionAction.MANAGE_TRUST)) {
            contents.setItem(23, PlayerDashboardMenu.icon(
                    Items.NAME_TAG,
                    Component.translatable("gui.rovenfall.claim.add_trusted"),
                    Component.translatable("gui.rovenfall.claim.candidate_limit", MAX_CANDIDATES),
                    Component.translatable("gui.rovenfall.player.click")));
        }
        if (actions.contains(PermissionAction.OFFER_TRANSFER)
                || actions.contains(PermissionAction.CANCEL_TRANSFER)
                || actions.contains(PermissionAction.ACCEPT_TRANSFER)) {
            String key = actions.contains(PermissionAction.OFFER_TRANSFER)
                    ? "gui.rovenfall.claim.transfer_offer"
                    : actions.contains(PermissionAction.CANCEL_TRANSFER)
                            ? "gui.rovenfall.claim.transfer_cancel"
                            : "gui.rovenfall.claim.transfer_accept";
            contents.setItem(25, PlayerDashboardMenu.icon(
                    Items.ENDER_EYE,
                    Component.translatable(key),
                    Component.translatable("gui.rovenfall.claim.confirm_required")));
        }
        if (actions.contains(PermissionAction.SELL) && claim.purchasePrice() > 0) {
            contents.setItem(33, PlayerDashboardMenu.icon(
                    Items.EMERALD,
                    Component.translatable("gui.rovenfall.claim.sell"),
                    Component.translatable("gui.rovenfall.claim.sale_refund",
                            ClaimManagementService.refund(
                                    claim.purchasePrice(), ClaimConfig.saleRefundPercent())),
                    Component.translatable("gui.rovenfall.claim.confirm_required")));
        }
        if (actions.stream().noneMatch(PlayerClaimMenu::isManagementAction)) {
            contents.setItem(31, PlayerDashboardMenu.icon(
                    Items.BARRIER,
                    Component.translatable("gui.rovenfall.claim.actions_locked"),
                    Component.translatable("gui.rovenfall.claim.owner_or_manager_required")));
        }
    }

    private static boolean isManagementAction(PermissionAction action) {
        return action != PermissionAction.VIEW_TRUSTED;
    }

    private void renderTrusted() {
        Claim claim = platform().claim(viewedKey).orElse(null);
        if (claim == null) {
            renderReturnPage();
            return;
        }
        List<Map.Entry<UUID, ClaimRole>> trusted = claim.trustedRoles().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .toList();
        pageIndex = boundedPage(pageIndex, trusted.size());
        displayedPlayers = pageEntries(trusted).stream().map(entry -> entry.getKey()).toList();
        contents.setItem(4, PlayerDashboardMenu.icon(
                Items.BOOK,
                Component.translatable("gui.rovenfall.claim.trusted"),
                Component.translatable("gui.rovenfall.claim.trusted_count", trusted.size()),
                pageLine(trusted.size())));
        for (int offset = 0; offset < displayedPlayers.size(); offset++) {
            UUID playerId = displayedPlayers.get(offset);
            ClaimRole role = claim.trustedRoles().get(playerId);
            contents.setItem(CONTENT_START + offset, playerIcon(playerId,
                    Component.translatable("gui.rovenfall.player.role", Component.translatable(role.translationKey())),
                    Component.translatable("gui.rovenfall.player.click")));
        }
        if (allowedActions(platform(), viewerId, claim).contains(PermissionAction.MANAGE_TRUST)) {
            contents.setItem(ADD_SLOT, PlayerDashboardMenu.icon(
                    Items.NAME_TAG,
                    Component.translatable("gui.rovenfall.claim.add_trusted"),
                    Component.translatable("gui.rovenfall.player.click")));
        }
        addPaging(trusted.size());
        addBack();
    }

    private void renderRole() {
        Claim claim = platform().claim(viewedKey).orElse(null);
        ClaimRole current = claim == null || selectedPlayer == null
                ? null
                : claim.trustedRoles().get(selectedPlayer);
        if (current == null) {
            page = Page.TRUSTED;
            renderTrusted();
            return;
        }
        contents.setItem(4, playerIcon(selectedPlayer,
                Component.translatable("gui.rovenfall.player.role", Component.translatable(current.translationKey()))));
        if (allowedActions(platform(), viewerId, claim).contains(PermissionAction.MANAGE_TRUST)) {
            roleButton(19, ClaimRole.MANAGER, current);
            roleButton(21, ClaimRole.BUILDER, current);
            roleButton(23, ClaimRole.USER, current);
            roleButton(25, ClaimRole.VISITOR, current);
            contents.setItem(31, PlayerDashboardMenu.icon(
                    Items.BARRIER,
                    Component.translatable("gui.rovenfall.claim.untrust"),
                    Component.translatable("gui.rovenfall.player.click")));
        }
        addBack();
    }

    private void renderSettings() {
        Claim claim = platform().claim(viewedKey).orElse(null);
        if (claim == null) {
            renderReturnPage();
            return;
        }
        contents.setItem(4, settingsIcon(claim.settings()));
        if (allowedActions(platform(), viewerId, claim).contains(PermissionAction.MANAGE_SETTINGS)) {
            contents.setItem(20, toggleIcon(
                    "gui.rovenfall.player.entry_restricted", claim.settings().entryRestricted()));
            contents.setItem(24, toggleIcon(
                    "gui.rovenfall.player.public_interactions", claim.settings().publicInteractions()));
        }
        addBack();
    }

    private void renderCandidates() {
        Claim claim = platform().claim(viewedKey).orElse(null);
        if (claim == null || candidatePurpose == null) {
            renderReturnPage();
            return;
        }
        Set<UUID> excluded = candidatePurpose == CandidatePurpose.TRUST
                ? claim.trustedRoles().keySet()
                : Set.of();
        displayedPlayers = boundedCandidateIds(
                viewer.level().getServer().getPlayerList().getPlayers().stream()
                        .map(ServerPlayer::getUUID)
                        .toList(),
                claim.ownerId(), excluded);
        contents.setItem(4, PlayerDashboardMenu.icon(
                Items.PLAYER_HEAD,
                Component.translatable(candidatePurpose == CandidatePurpose.TRUST
                        ? "gui.rovenfall.claim.select_trusted"
                        : "gui.rovenfall.claim.select_transfer"),
                Component.translatable("gui.rovenfall.claim.candidate_count", displayedPlayers.size(), MAX_CANDIDATES)));
        for (int offset = 0; offset < displayedPlayers.size(); offset++) {
            UUID playerId = displayedPlayers.get(offset);
            contents.setItem(CONTENT_START + offset, playerIcon(
                    playerId, Component.translatable("gui.rovenfall.player.click")));
        }
        if (displayedPlayers.isEmpty()) {
            contents.setItem(22, PlayerDashboardMenu.icon(
                    Items.BARRIER,
                    Component.translatable("gui.rovenfall.claim.no_candidates")));
        }
        addBack();
    }

    private void renderConfirmation() {
        if (confirmation == null) {
            page = Page.OVERVIEW;
            renderOverview();
            return;
        }
        contents.setItem(4, PlayerDashboardMenu.icon(
                Items.PAPER,
                Component.translatable("gui.rovenfall.claim.confirm_title"),
                Component.translatable(confirmation.kind().translationKey()),
                confirmation.amount() > 0
                        ? Component.translatable("gui.rovenfall.claim.amount", confirmation.amount())
                        : Component.empty(),
                confirmation.targetId() != null
                        ? playerName(confirmation.targetId())
                        : Component.empty()));
        contents.setItem(29, PlayerDashboardMenu.icon(
                Items.BARRIER,
                Component.translatable("gui.rovenfall.player.cancel"),
                Component.translatable("gui.rovenfall.player.click")));
        contents.setItem(33, PlayerDashboardMenu.icon(
                Items.EMERALD,
                Component.translatable("gui.rovenfall.player.confirm"),
                Component.translatable("gui.rovenfall.player.click")));
        addBack();
    }

    private ItemStack playerIcon(UUID playerId, Component... lore) {
        return PlayerDashboardMenu.icon(Items.PLAYER_HEAD, playerName(playerId), lore);
    }

    private Component playerName(UUID playerId) {
        ServerPlayer player = viewer.level().getServer().getPlayerList().getPlayer(playerId);
        if (player != null) {
            return player.getDisplayName();
        }
        return platform().playerRecord(playerId)
                .flatMap(PlayerRecord::displayName)
                .<Component>map(Component::literal)
                .orElseGet(() -> Component.translatable("gui.rovenfall.player.unknown_player"));
    }

    private ItemStack settingsIcon(ClaimSettings settings) {
        return PlayerDashboardMenu.icon(
                Items.OAK_DOOR,
                Component.translatable("gui.rovenfall.player.claim_settings"),
                Component.translatable("gui.rovenfall.player.entry_restricted", enabled(settings.entryRestricted())),
                Component.translatable("gui.rovenfall.player.public_interactions",
                        enabled(settings.publicInteractions())));
    }

    private ItemStack toggleIcon(String key, boolean enabled) {
        return PlayerDashboardMenu.icon(
                enabled ? Items.EMERALD : Items.COAL,
                Component.translatable(key, enabled(enabled)),
                Component.translatable("gui.rovenfall.claim.toggle"),
                Component.translatable("gui.rovenfall.player.click"));
    }

    private void roleButton(int slot, ClaimRole role, ClaimRole current) {
        contents.setItem(slot, PlayerDashboardMenu.icon(
                role == current ? Items.EMERALD : Items.NAME_TAG,
                Component.translatable(role.translationKey()),
                Component.translatable(role == current
                        ? "gui.rovenfall.claim.current_role"
                        : "gui.rovenfall.claim.set_role")));
    }

    private void addBack() {
        contents.setItem(BACK_SLOT, PlayerDashboardMenu.icon(
                Items.ARROW,
                Component.translatable("gui.rovenfall.player.back"),
                Component.translatable("gui.rovenfall.player.click")));
    }

    private void addPaging(int entries) {
        if (pageIndex > 0) {
            contents.setItem(PREVIOUS_SLOT, PlayerDashboardMenu.icon(
                    Items.ARROW, Component.translatable("gui.rovenfall.player.previous")));
        }
        if ((long) (pageIndex + 1) * PAGE_SIZE < entries) {
            contents.setItem(NEXT_SLOT, PlayerDashboardMenu.icon(
                    Items.ARROW, Component.translatable("gui.rovenfall.player.next")));
        }
    }

    private List<Map.Entry<UUID, ClaimRole>> pageEntries(List<Map.Entry<UUID, ClaimRole>> entries) {
        int from = Math.min(entries.size(), pageIndex * PAGE_SIZE);
        int to = Math.min(entries.size(), from + PAGE_SIZE);
        return entries.subList(from, to);
    }

    private Component pageLine(int entries) {
        int pages = entries == 0 ? 0 : (entries + PAGE_SIZE - 1) / PAGE_SIZE;
        return Component.translatable(
                "gui.rovenfall.player.page", entries == 0 ? 0 : pageIndex + 1, pages, entries);
    }

    private static int boundedPage(int page, int entries) {
        int lastPage = entries == 0 ? 0 : (entries - 1) / PAGE_SIZE;
        return Math.clamp(page, 0, lastPage);
    }

    private Optional<Long> purchasePrice(PlatformSavedData state) {
        return ClaimPurchaseService.calculatePrice(
                ClaimConfig.basePrice(), ClaimConfig.priceIncrease(), state.claimCount(viewerId));
    }

    private boolean isProtected(ClaimKey key) {
        return platform().isProtectedRegion(key)
                || ClaimRegionPolicy.isProtectedHubRegion(
                        key,
                        WorldTopology.HUB,
                        viewer.level().getServer().overworld().getRespawnData().pos(),
                        ClaimConfig.protectedSpawnRadiusChunks());
    }

    private PlatformSavedData platform() {
        return PlatformSavedData.get(viewer.level().getServer());
    }

    private ClaimKey currentKey() {
        return ClaimKey.at(viewer.level().dimension(), viewer.blockPosition());
    }

    private boolean detailSelectionIsCurrent() {
        return viewedKey != null
                && platform().claim(viewedKey).equals(viewedClaim)
                && (!selectedCurrentLand || currentKey().equals(viewedKey));
    }

    private void staleAtlas() {
        message("gui.rovenfall.claim.atlas.stale");
        render();
    }

    private void navigateTo(ClaimKey key) {
        if (key == null || !viewer.level().dimension().equals(key.dimension())) {
            message("gui.rovenfall.claim.atlas.navigation.other_world");
            return;
        }
        viewer.connection.send(navigationPacket(key));
        message("gui.rovenfall.claim.atlas.navigation.started");
        viewer.closeContainer();
    }

    private void clearNavigation() {
        viewer.connection.send(clearNavigationPacket());
        message("gui.rovenfall.claim.atlas.navigation.cleared");
    }

    static ClientboundTrackedWaypointPacket navigationPacket(ClaimKey key) {
        Waypoint.Icon icon = new Waypoint.Icon();
        icon.style = WaypointStyleAssets.BOWTIE;
        icon.color = Optional.of(0xE8B94E);
        return ClientboundTrackedWaypointPacket.addWaypointChunk(
                NAVIGATION_MARKER_ID, icon, new ChunkPos(key.chunkX(), key.chunkZ()));
    }

    static ClientboundTrackedWaypointPacket clearNavigationPacket() {
        return ClientboundTrackedWaypointPacket.removeWaypoint(NAVIGATION_MARKER_ID);
    }

    private Optional<String> playerDisplayName(UUID playerId) {
        ServerPlayer player = viewer.level().getServer().getPlayerList().getPlayer(playerId);
        if (player != null) {
            return Optional.of(player.getDisplayName().getString());
        }
        return platform().playerRecord(playerId).flatMap(PlayerRecord::displayName);
    }

    private void resetDetailToOverview() {
        page = Page.OVERVIEW;
        pageIndex = 0;
        selectedPlayer = null;
        candidatePurpose = null;
        displayedPlayers = List.of();
        confirmation = null;
    }

    private void renderReturnPage() {
        page = detailReturnPage;
        pageIndex = page == Page.ATLAS_LIST ? pageIndex : 0;
        selectedPlayer = null;
        candidatePurpose = null;
        displayedPlayers = List.of();
        confirmation = null;
        if (page == Page.ATLAS_LIST && atlasSection != null) {
            renderAtlasList();
        } else {
            page = Page.ATLAS_HOME;
            renderAtlasHome();
        }
    }

    private void resetToAtlasHome() {
        page = Page.ATLAS_HOME;
        pageIndex = 0;
        viewedKey = null;
        viewedClaim = Optional.empty();
        selectedCurrentLand = false;
        detailReturnPage = Page.ATLAS_HOME;
        atlasSection = null;
        atlasQuery = "";
        atlasView = null;
        displayedLands = List.of();
        selectedPlayer = null;
        candidatePurpose = null;
        displayedPlayers = List.of();
        confirmation = null;
    }

    private void refresh() {
        if (page != Page.ATLAS_HOME && page != Page.ATLAS_LIST) {
            page = detailReturnPage;
            selectedPlayer = null;
            candidatePurpose = null;
            displayedPlayers = List.of();
            confirmation = null;
        }
        render();
    }

    private void stale() {
        message("gui.rovenfall.claim.error.stale");
        page = detailReturnPage;
        selectedPlayer = null;
        candidatePurpose = null;
        displayedPlayers = List.of();
        confirmation = null;
        render();
    }

    private boolean beginMutation() {
        if (PlayerMenuNetwork.beginMutation(viewerId, viewer.level().getGameTime())) {
            return true;
        }
        message("gui.rovenfall.claim.error.rate_limit");
        return false;
    }

    private void rejectUnauthorized(String payload) {
        if (!beginMutation()) {
            return;
        }
        showMutationResult(ClaimManagementService.rejectUnauthorizedIntent(
                platform(), viewerId, viewedKey, "gui=" + payload, now()));
        resetDetailToOverview();
        render();
    }

    private void message(String translationKey, Object... arguments) {
        viewer.sendOverlayMessage(Component.translatable(translationKey, arguments));
    }

    private static Component enabled(boolean enabled) {
        return Component.translatable(enabled
                ? "gui.rovenfall.player.enabled"
                : "gui.rovenfall.player.disabled");
    }

    private static long now() {
        return Instant.now().toEpochMilli();
    }

    record Confirmation(
            ConfirmationKind kind,
            ClaimKey key,
            Optional<Claim> expectedClaim,
            long amount,
            UUID targetId,
            boolean requiresCurrentPosition) {
        Confirmation(
                ConfirmationKind kind,
                ClaimKey key,
                Optional<Claim> expectedClaim,
                long amount,
                UUID targetId) {
            this(kind, key, expectedClaim, amount, targetId, kind == ConfirmationKind.PURCHASE);
        }

        Confirmation {
            expectedClaim = expectedClaim == null ? Optional.empty() : expectedClaim;
        }
    }
}
