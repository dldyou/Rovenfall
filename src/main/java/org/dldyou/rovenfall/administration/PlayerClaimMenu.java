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
import org.dldyou.rovenfall.claims.Claim;
import org.dldyou.rovenfall.claims.ClaimConfig;
import org.dldyou.rovenfall.claims.ClaimKey;
import org.dldyou.rovenfall.claims.ClaimRegionPolicy;
import org.dldyou.rovenfall.claims.ClaimRole;
import org.dldyou.rovenfall.claims.ClaimSettings;
import org.dldyou.rovenfall.world.WorldTopology;

/** Server-owned current-chunk claim lifecycle exposed through a native menu. */
public final class PlayerClaimMenu extends ChestMenu {
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

    enum Page {
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
    private Page page = Page.OVERVIEW;
    private int pageIndex;
    private ClaimKey viewedKey;
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
                Component.translatable("gui.rovenfall.claim.title")));
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
            resetToOverview();
            render();
            return;
        }
        if (slotIndex == BACK_SLOT) {
            back();
            return;
        }
        if (!currentKey().equals(viewedKey)) {
            stale();
            return;
        }
        if (slotIndex == PREVIOUS_SLOT && page == Page.TRUSTED) {
            pageIndex = Math.max(0, pageIndex - 1);
            render();
            return;
        }
        if (slotIndex == NEXT_SLOT && page == Page.TRUSTED) {
            pageIndex++;
            render();
            return;
        }
        switch (page) {
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
                && confirmation.key().equals(currentKey)
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
            case OVERVIEW -> slot == 19 || slot == 21 || slot == 23 || slot == 25 || slot == 31 || slot == 33;
            case TRUSTED -> slot == ADD_SLOT || slot == PREVIOUS_SLOT || slot == NEXT_SLOT
                    || slot >= CONTENT_START && slot < CONTENT_START + PAGE_SIZE;
            case ROLE -> slot == 19 || slot == 21 || slot == 23 || slot == 25 || slot == 31;
            case SETTINGS -> slot == 20 || slot == 24;
            case CANDIDATES -> slot >= CONTENT_START && slot < CONTENT_START + PAGE_SIZE;
            case CONFIRM -> slot == 29 || slot == 33;
        };
    }

    private void handleOverview(int slot) {
        PlatformSavedData state = platform();
        Claim claim = state.claim(viewedKey).orElse(null);
        if (claim == null) {
            if (slot == 31 && WorldTopology.allowsClaims(viewedKey.dimension()) && !isProtected(viewedKey)) {
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
        resetToOverview();
        render();
    }

    private void executePurchase() {
        ClaimPurchaseService.PurchaseResult result = ClaimPurchaseService.purchase(
                platform(), viewerId, WorldTopology.HUB, viewer.level().dimension(), viewer.blockPosition(),
                ignored -> true, this::isProtected,
                ClaimConfig.basePrice(), ClaimConfig.priceIncrease(), ClaimConfig.ownershipCap(),
                now(), UUID.randomUUID());
        if (result.status() == ClaimPurchaseService.Status.SUCCESS) {
            message("command.rovenfall.claim.buy.success",
                    result.claim().orElseThrow().chunkX(), result.claim().orElseThrow().chunkZ(),
                    result.price(), result.balance());
        } else if (result.status() == ClaimPurchaseService.Status.DUPLICATE_TRANSACTION) {
            message("command.rovenfall.claim.buy.duplicate", result.transactionId().toString());
        } else {
            message(purchaseErrorTranslationKey(result.status()), result.price(), result.balance());
        }
    }

    private void showMutationResult(ClaimManagementService.Result result) {
        switch (result.status()) {
            case SUCCESS -> message("gui.rovenfall.claim.updated");
            case DUPLICATE_TRANSACTION -> message(
                    "command.rovenfall.claim.duplicate", result.transactionId().toString());
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
        confirmation = new Confirmation(kind, viewedKey, Optional.ofNullable(expectedClaim), amount, targetId);
        page = Page.CONFIRM;
        render();
    }

    private void back() {
        switch (page) {
            case OVERVIEW -> {
                PlayerDashboardMenu.open(viewer);
                return;
            }
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
        viewedKey = currentKey();
        switch (page) {
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

    private void renderOverview() {
        PlatformSavedData state = platform();
        Claim claim = state.claim(viewedKey).orElse(null);
        contents.setItem(4, PlayerDashboardMenu.icon(
                Items.GRASS_BLOCK,
                Component.translatable("gui.rovenfall.player.current_chunk"),
                Component.translatable("gui.rovenfall.player.claim_location",
                        viewedKey.dimension().identifier().toString(), viewedKey.chunkX(), viewedKey.chunkZ()),
                Component.translatable("gui.rovenfall.player.owned_claims", state.claimCount(viewerId)),
                Component.translatable("gui.rovenfall.player.balance", state.economyBalance(viewerId).orElse(0L))));
        addBack();

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
            if (claimableWorld && !protectedRegion && price.isPresent()) {
                contents.setItem(31, PlayerDashboardMenu.icon(
                        Items.GOLD_INGOT,
                        Component.translatable("gui.rovenfall.claim.purchase"),
                        Component.translatable("gui.rovenfall.claim.purchase_price", price.orElseThrow()),
                        Component.translatable("gui.rovenfall.claim.confirm_required")));
            }
            return;
        }

        EnumSet<PermissionAction> actions = allowedActions(state, viewerId, claim);
        contents.setItem(10, PlayerDashboardMenu.icon(
                Items.PLAYER_HEAD,
                Component.translatable("gui.rovenfall.claim.owner"),
                Component.translatable("gui.rovenfall.claim.player_uuid", claim.ownerId().toString()),
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
                                "gui.rovenfall.claim.transfer_pending", id.toString()))
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
            resetToOverview();
            renderOverview();
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
            resetToOverview();
            renderOverview();
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
            resetToOverview();
            renderOverview();
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
                        ? Component.translatable("gui.rovenfall.claim.player_uuid", confirmation.targetId().toString())
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
        ServerPlayer player = viewer.level().getServer().getPlayerList().getPlayer(playerId);
        Component name = player == null
                ? Component.literal(playerId.toString())
                : player.getDisplayName();
        Component[] completeLore = new Component[lore.length + 1];
        completeLore[0] = Component.translatable("gui.rovenfall.claim.player_uuid", playerId.toString());
        System.arraycopy(lore, 0, completeLore, 1, lore.length);
        return PlayerDashboardMenu.icon(Items.PLAYER_HEAD, name, completeLore);
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

    private void resetToOverview() {
        page = Page.OVERVIEW;
        pageIndex = 0;
        selectedPlayer = null;
        candidatePurpose = null;
        displayedPlayers = List.of();
        confirmation = null;
    }

    private void stale() {
        message("gui.rovenfall.claim.error.stale");
        resetToOverview();
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
        resetToOverview();
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
            UUID targetId) {
        Confirmation {
            expectedClaim = expectedClaim == null ? Optional.empty() : expectedClaim;
        }
    }
}
