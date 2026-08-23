package org.dldyou.rovenfall.administration;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;
import org.dldyou.rovenfall.Rovenfall;
import org.dldyou.rovenfall.economy.ShopInstance;
import org.dldyou.rovenfall.economy.ShopTemplate;
import org.dldyou.rovenfall.economy.ShopTemplateSnapshot;

/** Server-thread-only mutation boundary for persisted shop instances. */
public final class ShopInstanceService {
    private static final UUID ZERO_UUID = new UUID(0, 0);
    private static final long DENIED_AUDIT_INTERVAL_MILLIS = 1_000;

    private ShopInstanceService() {
    }

    public static MutationResult create(
            PlatformSavedData state,
            ShopTemplateSnapshot templates,
            UUID actorId,
            boolean authorizationOverride,
            Identifier shopId,
            Identifier templateId,
            Optional<ShopInstance.Binding> binding,
            Predicate<ResourceKey<Level>> dimensionExists,
            ShopInstance.AccessPolicy accessPolicy,
            long gameTime,
            String reason,
            long timestampEpochMillis,
            UUID transactionId) {
        MutationResult rejected = precheck(
                state, actorId, authorizationOverride, shopId, reason, timestampEpochMillis, transactionId);
        if (rejected != null) {
            return rejected;
        }
        if (templates == null || templateId == null || binding == null || dimensionExists == null
                || accessPolicy == null || gameTime < 0 || !validBinding(binding, dimensionExists)) {
            return denied(state, actorId, shopId, Status.INVALID_REQUEST, "invalid_request",
                    timestampEpochMillis, transactionId);
        }
        if (state.shopInstance(shopId).isPresent()) {
            return denied(state, actorId, shopId, Status.SHOP_EXISTS, "shop_exists",
                    timestampEpochMillis, transactionId);
        }
        if (state.shopInstanceCount() >= ShopInstance.MAX_INSTANCES) {
            return denied(state, actorId, shopId, Status.SHOP_LIMIT_REACHED, "shop_limit_reached",
                    timestampEpochMillis, transactionId);
        }
        Optional<ShopTemplate> template = templates.get(templateId);
        if (template.isEmpty()) {
            return denied(state, actorId, shopId, Status.TEMPLATE_UNRESOLVED, "template_unresolved",
                    timestampEpochMillis, transactionId);
        }

        ShopInstance shop;
        try {
            shop = new ShopInstance(templateId, binding, accessPolicy, copyOffers(template.orElseThrow(), gameTime));
        } catch (ArithmeticException exception) {
            return denied(state, actorId, shopId, Status.INVALID_REQUEST, "invalid_restock_time",
                    timestampEpochMillis, transactionId);
        }
        if (ShopInstance.validate(shop).error().isPresent()) {
            return denied(state, actorId, shopId, Status.INVALID_REQUEST, "invalid_shop",
                    timestampEpochMillis, transactionId);
        }
        return commit(state, actorId, shopId, Optional.empty(), Optional.of(shop), reason,
                timestampEpochMillis, transactionId, "shop_instance_create");
    }

    public static MutationResult delete(
            PlatformSavedData state,
            UUID actorId,
            boolean authorizationOverride,
            Identifier shopId,
            String reason,
            long timestampEpochMillis,
            UUID transactionId) {
        MutationResult rejected = precheck(
                state, actorId, authorizationOverride, shopId, reason, timestampEpochMillis, transactionId);
        if (rejected != null) {
            return rejected;
        }
        Optional<ShopInstance> before = state.shopInstance(shopId);
        if (before.isEmpty()) {
            return denied(state, actorId, shopId, Status.SHOP_NOT_FOUND, "shop_not_found",
                    timestampEpochMillis, transactionId);
        }
        return commit(state, actorId, shopId, before, Optional.empty(), reason,
                timestampEpochMillis, transactionId, "shop_instance_delete");
    }

    static MutationResult setBinding(
            PlatformSavedData state,
            UUID actorId,
            boolean authorizationOverride,
            Identifier shopId,
            Optional<ShopInstance.Binding> binding,
            Predicate<ResourceKey<Level>> dimensionExists,
            String reason,
            long timestampEpochMillis,
            UUID transactionId) {
        MutationResult rejected = precheck(
                state, actorId, authorizationOverride, shopId, reason, timestampEpochMillis, transactionId);
        if (rejected != null) {
            return rejected;
        }
        if (binding == null || dimensionExists == null || !validBinding(binding, dimensionExists)) {
            return denied(state, actorId, shopId, Status.INVALID_REQUEST, "invalid_binding",
                    timestampEpochMillis, transactionId);
        }
        return editAfterPrecheck(state, actorId, shopId, reason, timestampEpochMillis,
                transactionId, ignored -> null, shop -> shop.withBinding(binding), "shop_instance_binding_set",
                shopId.toString(), shop -> bindingSummary(shop.binding()));
    }

    static MutationResult setAccessPolicy(
            PlatformSavedData state,
            UUID actorId,
            boolean authorizationOverride,
            Identifier shopId,
            ShopInstance.AccessPolicy accessPolicy,
            String reason,
            long timestampEpochMillis,
            UUID transactionId) {
        MutationResult rejected = precheck(
                state, actorId, authorizationOverride, shopId, reason, timestampEpochMillis, transactionId);
        if (rejected != null) {
            return rejected;
        }
        if (accessPolicy == null) {
            return denied(state, actorId, shopId, Status.INVALID_REQUEST, "invalid_access_policy",
                    timestampEpochMillis, transactionId);
        }
        return editAfterPrecheck(state, actorId, shopId, reason, timestampEpochMillis,
                transactionId, ignored -> null, shop -> shop.withAccessPolicy(accessPolicy), "shop_instance_access_set",
                shopId.toString(), shop -> "max_distance=" + shop.accessPolicy().maxDistance());
    }

    static MutationResult putOffer(
            PlatformSavedData state,
            UUID actorId,
            boolean authorizationOverride,
            Identifier shopId,
            Identifier offerId,
            ShopInstance.Offer offer,
            String reason,
            long timestampEpochMillis,
            UUID transactionId) {
        MutationResult rejected = precheck(
                state, actorId, authorizationOverride, shopId, reason, timestampEpochMillis, transactionId);
        if (rejected != null) {
            return rejected;
        }
        if (offerId == null || offer == null) {
            return denied(state, actorId, shopId, Status.INVALID_REQUEST, "invalid_offer",
                    timestampEpochMillis, transactionId);
        }
        return editAfterPrecheck(state, actorId, shopId, reason, timestampEpochMillis,
                transactionId,
                shop -> !shop.offers().containsKey(offerId) && shop.offers().size() >= ShopInstance.MAX_OFFERS
                        ? Status.OFFER_LIMIT_REACHED
                        : null,
                shop -> shop.withOffer(offerId, offer), "shop_instance_offer_set",
                shopId + "/" + offerId, shop -> offerSummary(shop.offers().get(offerId)));
    }

    static MutationResult removeOffer(
            PlatformSavedData state,
            UUID actorId,
            boolean authorizationOverride,
            Identifier shopId,
            Identifier offerId,
            String reason,
            long timestampEpochMillis,
            UUID transactionId) {
        MutationResult rejected = precheck(
                state, actorId, authorizationOverride, shopId, reason, timestampEpochMillis, transactionId);
        if (rejected != null) {
            return rejected;
        }
        if (offerId == null) {
            return denied(state, actorId, shopId, Status.INVALID_REQUEST, "invalid_offer",
                    timestampEpochMillis, transactionId);
        }
        return editAfterPrecheck(state, actorId, shopId, reason, timestampEpochMillis,
                transactionId,
                shop -> shop.offers().containsKey(offerId) ? null : Status.OFFER_NOT_FOUND,
                shop -> shop.withoutOffer(offerId), "shop_instance_offer_remove",
                shopId + "/" + offerId, shop -> offerSummary(shop.offers().get(offerId)));
    }

    static MutationResult setRestockPolicy(
            PlatformSavedData state,
            UUID actorId,
            boolean authorizationOverride,
            Identifier shopId,
            Identifier offerId,
            Optional<Long> restockAmount,
            Optional<Long> restockIntervalTicks,
            long gameTime,
            String reason,
            long timestampEpochMillis,
            UUID transactionId) {
        MutationResult rejected = precheck(
                state, actorId, authorizationOverride, shopId, reason, timestampEpochMillis, transactionId);
        if (rejected != null) {
            return rejected;
        }
        if (offerId == null || restockAmount == null || restockIntervalTicks == null || gameTime < 0
                || restockAmount.isPresent() != restockIntervalTicks.isPresent()) {
            return denied(state, actorId, shopId, Status.INVALID_REQUEST, "invalid_restock_policy",
                    timestampEpochMillis, transactionId);
        }
        Optional<ShopInstance> existingShop = state.shopInstance(shopId);
        if (existingShop.isEmpty()) {
            return denied(state, actorId, shopId, Status.SHOP_NOT_FOUND, "shop_not_found",
                    timestampEpochMillis, transactionId);
        }
        ShopInstance.Offer existingOffer = existingShop.orElseThrow().offers().get(offerId);
        if (existingOffer == null) {
            return denied(state, actorId, shopId, Status.OFFER_NOT_FOUND, "offer_not_found",
                    timestampEpochMillis, transactionId);
        }
        ShopInstance.Stock previousStock = existingOffer.stock();
        if (previousStock.unlimited()) {
            return denied(state, actorId, shopId, Status.INVALID_REQUEST, "unlimited_stock_cannot_restock",
                    timestampEpochMillis, transactionId);
        }
        long nextRestock;
        try {
            nextRestock = restockIntervalTicks.isPresent()
                    ? Math.addExact(gameTime, restockIntervalTicks.orElseThrow())
                    : 0;
        } catch (ArithmeticException exception) {
            return denied(state, actorId, shopId, Status.INVALID_REQUEST, "invalid_restock_time",
                    timestampEpochMillis, transactionId);
        }
        ShopInstance.Stock updatedStock = new ShopInstance.Stock(
                false,
                previousStock.current(),
                previousStock.maximum(),
                restockAmount,
                restockIntervalTicks,
                nextRestock);
        ShopInstance.Offer updatedOffer = new ShopInstance.Offer(
                existingOffer.item(), existingOffer.buyPrice(), existingOffer.sellPrice(), updatedStock);
        return editAfterPrecheck(
                state,
                actorId,
                shopId,
                reason,
                timestampEpochMillis,
                transactionId,
                ignored -> null,
                shop -> shop.withOffer(offerId, updatedOffer),
                restockAmount.isEmpty()
                        ? "shop_instance_offer_restock_clear"
                        : "shop_instance_offer_restock_set",
                shopId + "/" + offerId,
                shop -> offerSummary(shop.offers().get(offerId)));
    }

    private static MutationResult editAfterPrecheck(
            PlatformSavedData state,
            UUID actorId,
            Identifier shopId,
            String reason,
            long timestampEpochMillis,
            UUID transactionId,
            Function<ShopInstance, Status> guard,
            UnaryOperator<ShopInstance> edit,
            String action,
            String auditTarget,
            Function<ShopInstance, String> auditSummary) {
        Optional<ShopInstance> before = state.shopInstance(shopId);
        if (before.isEmpty()) {
            return denied(state, actorId, shopId, Status.SHOP_NOT_FOUND, "shop_not_found",
                    timestampEpochMillis, transactionId);
        }
        Status guardedStatus = guard.apply(before.orElseThrow());
        if (guardedStatus != null) {
            return denied(state, actorId, shopId, guardedStatus,
                    guardedStatus == Status.OFFER_LIMIT_REACHED ? "offer_limit_reached" : "offer_not_found",
                    timestampEpochMillis, transactionId);
        }
        ShopInstance after = edit.apply(before.orElseThrow());
        if (ShopInstance.validate(after).error().isPresent()) {
            return denied(state, actorId, shopId, Status.INVALID_REQUEST, "invalid_shop",
                    timestampEpochMillis, transactionId);
        }
        return commit(state, actorId, shopId, before, Optional.of(after), reason,
                timestampEpochMillis, transactionId, action, auditTarget,
                auditSummary.apply(before.orElseThrow()), auditSummary.apply(after));
    }

    private static MutationResult precheck(
            PlatformSavedData state,
            UUID actorId,
            boolean authorizationOverride,
            Identifier shopId,
            String reason,
            long timestampEpochMillis,
            UUID transactionId) {
        if (!state.isWritable()) {
            return result(Status.READ_ONLY_SCHEMA, transactionId, false);
        }
        if (actorId == null || shopId == null || timestampEpochMillis < 0) {
            return invalidWithoutAudit(transactionId);
        }
        if (!EconomyService.canManageEconomy(state, actorId, authorizationOverride)) {
            return denied(state, actorId, shopId, Status.UNAUTHORIZED, "unauthorized",
                    timestampEpochMillis, transactionId);
        }
        if (!validTransactionId(transactionId)) {
            return denied(state, actorId, shopId, Status.INVALID_TRANSACTION, "invalid_transaction",
                    timestampEpochMillis, transactionId);
        }
        if (state.hasTransaction(transactionId, timestampEpochMillis)) {
            return result(Status.DUPLICATE_TRANSACTION, transactionId, false);
        }
        if (validReason(reason).isEmpty()) {
            return denied(state, actorId, shopId, Status.INVALID_REASON, "invalid_reason",
                    timestampEpochMillis, transactionId);
        }
        if (state.isShopLocked(shopId)) {
            return denied(state, actorId, shopId, Status.DEPENDENCY_LOCKED, "dependency_locked",
                    timestampEpochMillis, transactionId);
        }
        if (!state.canCommitTransaction(transactionId, timestampEpochMillis)) {
            return denied(state, actorId, shopId, Status.TRANSACTION_LEDGER_FULL, "transaction_ledger_full",
                    timestampEpochMillis, transactionId);
        }
        return null;
    }

    private static MutationResult commit(
            PlatformSavedData state,
            UUID actorId,
            Identifier shopId,
            Optional<ShopInstance> before,
            Optional<ShopInstance> after,
            String reason,
            long timestampEpochMillis,
            UUID transactionId,
            String action) {
        return commit(state, actorId, shopId, before, after, reason, timestampEpochMillis, transactionId,
                action, shopId.toString(), summary(before), summary(after));
    }

    private static MutationResult commit(
            PlatformSavedData state,
            UUID actorId,
            Identifier shopId,
            Optional<ShopInstance> before,
            Optional<ShopInstance> after,
            String reason,
            long timestampEpochMillis,
            UUID transactionId,
            String action,
            String auditTarget,
            String beforeValue,
            String afterValue) {
        Optional<ShopInstance.Binding> binding = after.flatMap(ShopInstance::binding)
                .or(() -> before.flatMap(ShopInstance::binding));
        state.commitShopMutation(shopId, after, transactionId, timestampEpochMillis, new AuditEntry(
                timestampEpochMillis,
                actorId,
                action(action),
                auditTarget,
                binding.map(value -> value.dimension().identifier()),
                binding.map(ShopInstance.Binding::position),
                beforeValue,
                afterValue,
                validReason(reason).orElseThrow(),
                transactionId));
        return result(Status.SUCCESS, transactionId, true);
    }

    private static MutationResult denied(
            PlatformSavedData state,
            UUID actorId,
            Identifier shopId,
            Status status,
            String reason,
            long timestampEpochMillis,
            UUID transactionId) {
        if (actorId == null || shopId == null || timestampEpochMillis < 0) {
            return result(status, transactionId, false);
        }
        UUID evidenceId = validTransactionId(transactionId) ? transactionId : UUID.randomUUID();
        Optional<ShopInstance.Binding> binding = state.shopInstance(shopId).flatMap(ShopInstance::binding);
        boolean audited = state.appendDeniedAudit(new AuditEntry(
                timestampEpochMillis,
                actorId,
                action("shop_instance_mutation_denied"),
                shopId.toString(),
                binding.map(value -> value.dimension().identifier()),
                binding.map(ShopInstance.Binding::position),
                summary(state.shopInstance(shopId)),
                summary(state.shopInstance(shopId)),
                reason,
                evidenceId), DENIED_AUDIT_INTERVAL_MILLIS);
        return result(status, transactionId, audited);
    }

    private static Map<Identifier, ShopInstance.Offer> copyOffers(ShopTemplate template, long gameTime) {
        Map<Identifier, ShopInstance.Offer> offers = new LinkedHashMap<>();
        for (ShopTemplate.Offer source : template.offers()) {
            ShopTemplate.StockPolicy stock = source.stock();
            ShopInstance.Stock runtimeStock;
            if (stock.unlimited()) {
                runtimeStock = ShopInstance.Stock.unlimitedStock();
            } else {
                long nextRestock = stock.restockIntervalTicks().isPresent()
                        ? Math.addExact(gameTime, stock.restockIntervalTicks().orElseThrow())
                        : 0;
                runtimeStock = new ShopInstance.Stock(
                        false,
                        stock.initial().orElseThrow(),
                        stock.maximum().orElseThrow(),
                        stock.restockAmount(),
                        stock.restockIntervalTicks(),
                        nextRestock);
            }
            offers.put(source.id(), new ShopInstance.Offer(
                    source.item(), source.buyPrice(), source.sellPrice(), runtimeStock));
        }
        return Map.copyOf(offers);
    }

    private static String summary(Optional<ShopInstance> shop) {
        return shop.map(value -> {
            String binding = value.binding()
                    .map(bound -> bound.dimension().identifier() + "@" + bound.position().toShortString())
                    .orElse("none");
            int fingerprint = 1;
            for (var entry : value.offers().entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
                ShopInstance.Offer offer = entry.getValue();
                ShopInstance.Stock stock = offer.stock();
                fingerprint = 31 * fingerprint + Objects.hash(
                        entry.getKey(),
                        net.minecraft.world.item.ItemStack.hashItemAndComponents(offer.item()),
                        offer.item().getCount(),
                        offer.buyPrice(),
                        offer.sellPrice(),
                        stock);
            }
            return "template=" + value.templateId() + ";binding=" + binding + ";range="
                    + value.accessPolicy().maxDistance() + ";offers=" + value.offers().size()
                    + ";fingerprint=" + Integer.toUnsignedString(fingerprint, 16);
        }).orElse("none");
    }

    private static String bindingSummary(Optional<ShopInstance.Binding> binding) {
        return binding.map(value -> value.dimension().identifier() + "@" + value.position().toShortString())
                .orElse("none");
    }

    private static String offerSummary(ShopInstance.Offer offer) {
        if (offer == null) {
            return "none";
        }
        var item = offer.item();
        ShopInstance.Stock stock = offer.stock();
        return "item=" + BuiltInRegistries.ITEM.getKey(item.getItem()) + "x" + item.getCount()
                + ";components=" + Integer.toUnsignedString(net.minecraft.world.item.ItemStack.hashItemAndComponents(item), 16)
                + ";buy=" + offer.buyPrice().map(String::valueOf).orElse("none")
                + ";sell=" + offer.sellPrice().map(String::valueOf).orElse("none")
                + ";unlimited=" + stock.unlimited() + ";current=" + stock.current()
                + ";maximum=" + stock.maximum()
                + ";restock_amount=" + stock.restockAmount().map(String::valueOf).orElse("none")
                + ";restock_interval=" + stock.restockIntervalTicks().map(String::valueOf).orElse("none")
                + ";next_restock=" + stock.nextRestockGameTime();
    }

    private static Optional<String> validReason(String reason) {
        String normalized = reason == null ? "" : reason.strip();
        return normalized.isEmpty() || normalized.length() > AdministrationService.MAX_REASON_LENGTH
                ? Optional.empty()
                : Optional.of(normalized);
    }

    private static boolean validBinding(
            Optional<ShopInstance.Binding> binding,
            Predicate<ResourceKey<Level>> dimensionExists) {
        if (binding.isEmpty()) {
            return true;
        }
        ShopInstance.Binding value = binding.orElseThrow();
        return value != null && value.dimension() != null && value.position() != null
                && dimensionExists.test(value.dimension());
    }

    private static boolean validTransactionId(UUID transactionId) {
        return transactionId != null && !ZERO_UUID.equals(transactionId);
    }

    private static MutationResult invalidWithoutAudit(UUID transactionId) {
        return result(Status.INVALID_REQUEST, transactionId, false);
    }

    private static MutationResult result(Status status, UUID transactionId, boolean audited) {
        return new MutationResult(status, transactionId, audited);
    }

    private static Identifier action(String path) {
        return Identifier.fromNamespaceAndPath(Rovenfall.MOD_ID, path);
    }

    public static Optional<DependencyLease> tryAcquireDependencyLock(
            PlatformSavedData state, Identifier shopId) {
        if (state == null || shopId == null || !state.isWritable() || state.shopInstance(shopId).isEmpty()
                || !state.tryLockShop(shopId)) {
            return Optional.empty();
        }
        return Optional.of(new DependencyLease(state, shopId));
    }

    public static final class DependencyLease implements AutoCloseable {
        private final PlatformSavedData state;
        private final Identifier shopId;
        private final AtomicBoolean closed = new AtomicBoolean();

        private DependencyLease(PlatformSavedData state, Identifier shopId) {
            this.state = state;
            this.shopId = shopId;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                state.unlockShop(shopId);
            }
        }
    }

    public enum Status {
        SUCCESS,
        DUPLICATE_TRANSACTION,
        UNAUTHORIZED,
        INVALID_REQUEST,
        INVALID_TRANSACTION,
        INVALID_REASON,
        READ_ONLY_SCHEMA,
        TRANSACTION_LEDGER_FULL,
        SHOP_LIMIT_REACHED,
        SHOP_EXISTS,
        SHOP_NOT_FOUND,
        TEMPLATE_UNRESOLVED,
        DEPENDENCY_LOCKED,
        OFFER_LIMIT_REACHED,
        OFFER_NOT_FOUND
    }

    public record MutationResult(Status status, UUID transactionId, boolean auditRecorded) {
    }
}
