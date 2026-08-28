package org.dldyou.rovenfall.administration;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.dldyou.rovenfall.Rovenfall;
import org.dldyou.rovenfall.economy.ShopInstance;
import org.dldyou.rovenfall.economy.ShopTemplate;
import org.dldyou.rovenfall.economy.ShopTemplateReloadListener;
import org.dldyou.rovenfall.economy.ShopTemplateSnapshot;

/** Executes server-created, snapshot-bound administration confirmations through existing services. */
final class AdministrationEconomyActionService {
    private static final long STALE_AUDIT_INTERVAL_MILLIS = 1_000L;

    private AdministrationEconomyActionService() {
    }

    static Result execute(ServerPlayer actor, PendingAction action) {
        if (actor == null || action == null || actor.level().getServer() == null
                || !actor.level().getServer().isSameThread()) {
            return new Result(Status.FAILED, "invalid_request", null);
        }
        var server = actor.level().getServer();
        PlatformSavedData state = PlatformSavedData.get(server);
        Optional<AdminRole> role = AdministrationControlCenterMenu.resolveRole(actor);
        if (role.isEmpty() || !AdministrationEconomyMenu.canManage(role.orElseThrow())) {
            auditRejected(state, actor.getUUID(), action, "unauthorized");
            return new Result(Status.UNAUTHORIZED, "unauthorized", action.transactionId());
        }
        ShopTemplateSnapshot templates = ShopTemplateReloadListener.snapshot(server);
        if (!fresh(state, templates, action)) {
            auditRejected(state, actor.getUUID(), action, "stale_confirmation");
            return new Result(Status.STALE_CONFIRMATION, "stale_confirmation", action.transactionId());
        }
        boolean override = state.roleOf(actor.getUUID()).isEmpty();
        long now = Instant.now().toEpochMilli();
        if (action instanceof BalanceAction value) {
            EconomyService.TransactionResult result = value.grant()
                    ? EconomyService.adminGrant(
                            state, actor.getUUID(), override, value.playerId(), value.amount(), value.reason(), now,
                            value.transactionId(), EconomyConfig.initialBalance(), EconomyConfig.maximumBalance())
                    : EconomyService.adminDebit(
                            state, actor.getUUID(), override, value.playerId(), value.amount(), value.reason(), now,
                            value.transactionId(), EconomyConfig.initialBalance(), EconomyConfig.maximumBalance());
            return fromEconomy(result);
        }
        if (action instanceof ShopCreateAction value) {
            return fromShop(ShopInstanceService.create(
                    state, templates, actor.getUUID(), override,
                    value.shopId(), value.templateId(), Optional.empty(), key -> server.getLevel(key) != null,
                    ShopInstance.AccessPolicy.publicAccess(), server.overworld().getGameTime(), value.reason(), now,
                    value.transactionId()));
        }
        if (action instanceof ShopDeleteAction value) {
            return fromShop(ShopInstanceService.delete(
                    state, actor.getUUID(), override, value.shopId(), value.reason(), now, value.transactionId()));
        }
        if (action instanceof ShopBindingAction value) {
            return fromShop(ShopInstanceService.setBinding(
                    state, actor.getUUID(), override, value.shopId(), value.binding(),
                    key -> server.getLevel(key) != null, value.reason(), now, value.transactionId()));
        }
        if (action instanceof ShopAccessAction value) {
            return fromShop(ShopInstanceService.setAccessPolicy(
                    state, actor.getUUID(), override, value.shopId(),
                    new ShopInstance.AccessPolicy(value.maxDistance()), value.reason(), now, value.transactionId()));
        }
        if (action instanceof ShopOfferAction value) {
            return fromShop(ShopInstanceService.putOffer(
                    state, actor.getUUID(), override, value.shopId(), value.offerId(), value.offer(), value.reason(),
                    now, value.transactionId()));
        }
        if (action instanceof ShopOfferRemoveAction value) {
            return fromShop(ShopInstanceService.removeOffer(
                    state, actor.getUUID(), override, value.shopId(), value.offerId(), value.reason(), now,
                    value.transactionId()));
        }
        if (action instanceof ShopRestockAction value) {
            return fromShop(ShopInstanceService.setRestockPolicy(
                    state, actor.getUUID(), override, value.shopId(), value.offerId(), value.amount(),
                    value.intervalTicks(), server.overworld().getGameTime(), value.reason(), now,
                    value.transactionId()));
        }
        if (action instanceof ReceiptReversalAction value) {
            ServerPlayer target = server.getPlayerList().getPlayer(value.playerId());
            if (target == null) {
                auditRejected(state, actor.getUUID(), action, "target_offline");
                return new Result(Status.TARGET_OFFLINE, "target_offline", value.transactionId());
            }
            if (value.expectedReceipt().isTrade()
                    && !sameInventory(value.expectedInventory(), target.getInventory().getNonEquipmentItems())) {
                auditRejected(state, actor.getUUID(), action, "stale_confirmation");
                return new Result(Status.STALE_CONFIRMATION, "stale_confirmation", value.transactionId());
            }
            EconomyReversalService.Result result = EconomyReversalService.reverse(
                    state, target, actor.getUUID(), override, value.originalTransactionId(), value.decision(),
                    value.reason(), now, value.transactionId());
            return fromReversal(result);
        }
        return new Result(Status.FAILED, "unsupported_action", action.transactionId());
    }

    static boolean fresh(PlatformSavedData state, PendingAction action) {
        return fresh(state, ShopTemplateSnapshot.empty(), action);
    }

    static boolean fresh(PlatformSavedData state, ShopTemplateSnapshot templates, PendingAction action) {
        if (state == null || action == null) {
            return false;
        }
        if (action instanceof BalanceAction value) {
            return state.economyBalance(value.playerId()).equals(value.expectedBalance());
        }
        if (action instanceof ShopCreateAction value) {
            return templates != null && state.shopInstance(value.shopId()).isEmpty()
                    && templates.get(value.templateId()).equals(Optional.of(value.expectedTemplate()));
        }
        if (action instanceof ShopAction value) {
            return state.shopInstance(value.shopId()).equals(value.expectedShop());
        }
        if (action instanceof ReceiptReversalAction value) {
            return state.economyReceipt(value.originalTransactionId()).equals(Optional.of(value.expectedReceipt()))
                    && state.economyBalance(value.playerId()).equals(value.expectedBalance())
                    && (!value.expectedReceipt().isTrade()
                            || value.expectedReceipt().shopId()
                                    .map(shopId -> state.shopInstance(shopId).equals(value.expectedShop()))
                                    .orElse(false))
                    && value.expectedReceipt().playerId().equals(value.playerId())
                    && value.expectedReceipt().reversedBy().isEmpty()
                    && value.expectedReceipt().invalidatedByRestore().isEmpty();
        }
        return false;
    }

    static boolean sameInventory(List<ItemStack> expected, List<ItemStack> current) {
        if (expected == null || current == null || expected.size() != current.size()) {
            return false;
        }
        for (int index = 0; index < expected.size(); index++) {
            if (!ItemStack.matches(expected.get(index), current.get(index))) {
                return false;
            }
        }
        return true;
    }

    private static Result fromEconomy(EconomyService.TransactionResult result) {
        return switch (result.status()) {
            case SUCCESS -> new Result(Status.SUCCESS, "success", result.transactionId());
            case DUPLICATE_TRANSACTION -> new Result(Status.DUPLICATE, "duplicate_transaction", result.transactionId());
            case UNAUTHORIZED -> new Result(Status.UNAUTHORIZED, "unauthorized", result.transactionId());
            default -> new Result(Status.FAILED, result.status().name().toLowerCase(java.util.Locale.ROOT),
                    result.transactionId());
        };
    }

    private static Result fromShop(ShopInstanceService.MutationResult result) {
        return switch (result.status()) {
            case SUCCESS -> new Result(Status.SUCCESS, "success", result.transactionId());
            case DUPLICATE_TRANSACTION -> new Result(Status.DUPLICATE, "duplicate_transaction", result.transactionId());
            case UNAUTHORIZED -> new Result(Status.UNAUTHORIZED, "unauthorized", result.transactionId());
            default -> new Result(Status.FAILED, result.status().name().toLowerCase(java.util.Locale.ROOT),
                    result.transactionId());
        };
    }

    private static Result fromReversal(EconomyReversalService.Result result) {
        return switch (result.status()) {
            case SUCCESS -> new Result(Status.SUCCESS, "success", result.transactionId());
            case DUPLICATE_TRANSACTION -> new Result(Status.DUPLICATE, "duplicate_transaction", result.transactionId());
            case UNAUTHORIZED -> new Result(Status.UNAUTHORIZED, "unauthorized", result.transactionId());
            default -> new Result(Status.FAILED, result.status().name().toLowerCase(java.util.Locale.ROOT),
                    result.transactionId());
        };
    }

    private static void auditRejected(
            PlatformSavedData state, UUID actorId, PendingAction action, String reason) {
        if (!state.isWritable()) {
            return;
        }
        long now = Instant.now().toEpochMilli();
        state.appendDeniedAudit(new AuditEntry(
                now, actorId, Identifier.fromNamespaceAndPath(Rovenfall.MOD_ID, "admin_gui_" + reason + "_denied"),
                action.target(), Optional.empty(), Optional.empty(), "unchanged", "unchanged",
                reason, action.transactionId()), STALE_AUDIT_INTERVAL_MILLIS);
    }

    sealed interface PendingAction permits BalanceAction, ShopCreateAction, ShopAction, ReceiptReversalAction {
        UUID transactionId();

        String reason();

        String target();
    }

    sealed interface ShopAction extends PendingAction permits ShopDeleteAction, ShopBindingAction,
            ShopAccessAction, ShopOfferAction, ShopOfferRemoveAction, ShopRestockAction {
        Identifier shopId();

        Optional<ShopInstance> expectedShop();

        @Override
        default String target() {
            return shopId().toString();
        }
    }

    record BalanceAction(
            UUID transactionId,
            UUID playerId,
            long amount,
            boolean grant,
            Optional<Long> expectedBalance,
            String reason) implements PendingAction {
        BalanceAction {
            expectedBalance = expectedBalance == null ? Optional.empty() : expectedBalance;
        }

        @Override
        public String target() {
            return playerId.toString();
        }
    }

    record ShopCreateAction(
            UUID transactionId,
            Identifier shopId,
            Identifier templateId,
            ShopTemplate expectedTemplate,
            String reason) implements PendingAction {
        @Override
        public String target() {
            return shopId.toString();
        }
    }

    record ShopDeleteAction(
            UUID transactionId,
            Identifier shopId,
            Optional<ShopInstance> expectedShop,
            String reason) implements ShopAction {
    }

    record ShopBindingAction(
            UUID transactionId,
            Identifier shopId,
            Optional<ShopInstance> expectedShop,
            Optional<ShopInstance.Binding> binding,
            String reason) implements ShopAction {
    }

    record ShopAccessAction(
            UUID transactionId,
            Identifier shopId,
            Optional<ShopInstance> expectedShop,
            int maxDistance,
            String reason) implements ShopAction {
    }

    record ShopOfferAction(
            UUID transactionId,
            Identifier shopId,
            Optional<ShopInstance> expectedShop,
            Identifier offerId,
            ShopInstance.Offer offer,
            String reason) implements ShopAction {
    }

    record ShopOfferRemoveAction(
            UUID transactionId,
            Identifier shopId,
            Optional<ShopInstance> expectedShop,
            Identifier offerId,
            String reason) implements ShopAction {
    }

    record ShopRestockAction(
            UUID transactionId,
            Identifier shopId,
            Optional<ShopInstance> expectedShop,
            Identifier offerId,
            Optional<Long> amount,
            Optional<Long> intervalTicks,
            String reason) implements ShopAction {
    }

    record ReceiptReversalAction(
            UUID transactionId,
            UUID originalTransactionId,
            UUID playerId,
            EconomyTransactionReceipt expectedReceipt,
            Optional<Long> expectedBalance,
            List<ItemStack> expectedInventory,
            Optional<ShopInstance> expectedShop,
            EconomyTransactionReceipt.CompensationDecision decision,
            String reason) implements PendingAction {
        ReceiptReversalAction {
            expectedBalance = expectedBalance == null ? Optional.empty() : expectedBalance;
            expectedInventory = expectedInventory == null
                    ? List.of() : ShopTradeService.copyInventory(expectedInventory);
            expectedShop = expectedShop == null ? Optional.empty() : expectedShop;
        }

        @Override
        public List<ItemStack> expectedInventory() {
            return ShopTradeService.copyInventory(expectedInventory);
        }

        @Override
        public String target() {
            return originalTransactionId.toString();
        }
    }

    enum Status {
        SUCCESS,
        DUPLICATE,
        STALE_CONFIRMATION,
        UNAUTHORIZED,
        TARGET_OFFLINE,
        FAILED
    }

    record Result(Status status, String detail, UUID transactionId) {
        boolean succeeded() {
            return status == Status.SUCCESS || status == Status.DUPLICATE;
        }
    }
}
