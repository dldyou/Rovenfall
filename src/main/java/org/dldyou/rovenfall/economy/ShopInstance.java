package org.dldyou.rovenfall.economy;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

public record ShopInstance(
        Identifier templateId,
        Optional<Binding> binding,
        AccessPolicy accessPolicy,
        Map<Identifier, Offer> offers) {
    public static final int MAX_INSTANCES = 4_096;
    public static final int MAX_OFFERS = 256;
    public static final int MAX_ACCESS_DISTANCE = 256;
    public static final int DEFAULT_ACCESS_DISTANCE = 8;

    private static final Codec<Map<Identifier, Offer>> OFFERS_CODEC = OfferEntry.CODEC
            .listOf(0, MAX_OFFERS)
            .flatXmap(ShopInstance::offersFromEntries, ShopInstance::offerEntries);

    public static final Codec<ShopInstance> CODEC = RecordCodecBuilder.<ShopInstance>create(instance -> instance.group(
            Identifier.CODEC.fieldOf("template_id").forGetter(ShopInstance::templateId),
            Binding.CODEC.optionalFieldOf("binding").forGetter(ShopInstance::binding),
            AccessPolicy.CODEC.optionalFieldOf("access", AccessPolicy.publicAccess()).forGetter(ShopInstance::accessPolicy),
            OFFERS_CODEC.optionalFieldOf("offers", Map.of()).forGetter(ShopInstance::offers)
    ).apply(instance, ShopInstance::new)).validate((ShopInstance value) -> ShopInstance.validate(value));

    public ShopInstance {
        binding = binding == null ? Optional.empty() : binding;
        accessPolicy = accessPolicy == null ? AccessPolicy.publicAccess() : accessPolicy;
        offers = offers == null ? Map.of() : Map.copyOf(offers);
    }

    public ShopInstance withBinding(Optional<Binding> updatedBinding) {
        return new ShopInstance(templateId, updatedBinding, accessPolicy, offers);
    }

    public ShopInstance withAccessPolicy(AccessPolicy updatedPolicy) {
        return new ShopInstance(templateId, binding, updatedPolicy, offers);
    }

    public ShopInstance withOffer(Identifier offerId, Offer offer) {
        var updated = new java.util.HashMap<>(offers);
        updated.put(offerId, offer);
        return new ShopInstance(templateId, binding, accessPolicy, updated);
    }

    public ShopInstance withoutOffer(Identifier offerId) {
        var updated = new java.util.HashMap<>(offers);
        updated.remove(offerId);
        return new ShopInstance(templateId, binding, accessPolicy, updated);
    }

    private static DataResult<Map<Identifier, Offer>> offersFromEntries(List<OfferEntry> entries) {
        Map<Identifier, Offer> offers = new LinkedHashMap<>();
        for (OfferEntry entry : entries) {
            if (offers.putIfAbsent(entry.id(), entry.offer()) != null) {
                return DataResult.error(() -> "Duplicate shop offer ID " + entry.id());
            }
        }
        return DataResult.success(Map.copyOf(offers));
    }

    private static DataResult<List<OfferEntry>> offerEntries(Map<Identifier, Offer> offers) {
        return DataResult.success(offers.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new OfferEntry(entry.getKey(), entry.getValue()))
                .toList());
    }

    private record OfferEntry(Identifier id, Offer offer) {
        private static final Codec<OfferEntry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Identifier.CODEC.fieldOf("id").forGetter(OfferEntry::id),
                Offer.CODEC.fieldOf("offer").forGetter(OfferEntry::offer)
        ).apply(instance, OfferEntry::new));
    }

    public static DataResult<ShopInstance> validate(ShopInstance shop) {
        if (shop == null || shop.templateId() == null || shop.accessPolicy() == null || shop.offers() == null) {
            return DataResult.error(() -> "Shop instance has missing fields");
        }
        if (shop.binding().isPresent() && (shop.binding().orElseThrow().dimension() == null
                || shop.binding().orElseThrow().position() == null)) {
            return DataResult.error(() -> "Shop binding has missing fields");
        }
        if (shop.offers().size() > MAX_OFFERS) {
            return DataResult.error(() -> "Shop instance exceeds " + MAX_OFFERS + " offers");
        }
        DataResult<AccessPolicy> policy = AccessPolicy.validate(shop.accessPolicy());
        if (policy.error().isPresent()) {
            return DataResult.error(() -> policy.error().orElseThrow().message());
        }
        for (Map.Entry<Identifier, Offer> entry : shop.offers().entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                return DataResult.error(() -> "Shop instance has a null offer");
            }
            DataResult<Offer> offer = Offer.validate(entry.getValue());
            if (offer.error().isPresent()) {
                return DataResult.error(() -> "Offer " + entry.getKey() + ": "
                        + offer.error().orElseThrow().message());
            }
        }
        return DataResult.success(shop);
    }

    public record Binding(ResourceKey<Level> dimension, BlockPos position) {
        public static final Codec<Binding> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Level.RESOURCE_KEY_CODEC.fieldOf("dimension").forGetter(Binding::dimension),
                BlockPos.CODEC.fieldOf("position").forGetter(Binding::position)
        ).apply(instance, Binding::new));
    }

    public record AccessPolicy(int maxDistance) {
        public static final Codec<AccessPolicy> CODEC = RecordCodecBuilder.<AccessPolicy>create(instance -> instance.group(
                Codec.INT.fieldOf("max_distance").forGetter(AccessPolicy::maxDistance)
        ).apply(instance, AccessPolicy::new)).validate((AccessPolicy value) -> AccessPolicy.validate(value));

        public static AccessPolicy publicAccess() {
            return new AccessPolicy(DEFAULT_ACCESS_DISTANCE);
        }

        static DataResult<AccessPolicy> validate(AccessPolicy policy) {
            if (policy == null || policy.maxDistance < 1 || policy.maxDistance > MAX_ACCESS_DISTANCE) {
                return DataResult.error(() -> "Access distance must be between 1 and " + MAX_ACCESS_DISTANCE);
            }
            return DataResult.success(policy);
        }
    }

    public record Offer(
            ItemStack item,
            Optional<Long> buyPrice,
            Optional<Long> sellPrice,
            Stock stock) {
        public static final Codec<Offer> CODEC = RecordCodecBuilder.<Offer>create(instance -> instance.group(
                ItemStack.CODEC.fieldOf("item").forGetter(Offer::item),
                Codec.LONG.optionalFieldOf("buy_price").forGetter(Offer::buyPrice),
                Codec.LONG.optionalFieldOf("sell_price").forGetter(Offer::sellPrice),
                Stock.CODEC.fieldOf("stock").forGetter(Offer::stock)
        ).apply(instance, Offer::new)).validate((Offer value) -> Offer.validate(value));

        public Offer {
            item = item == null ? ItemStack.EMPTY : item.copy();
            buyPrice = buyPrice == null ? Optional.empty() : buyPrice;
            sellPrice = sellPrice == null ? Optional.empty() : sellPrice;
        }

        @Override
        public ItemStack item() {
            return item.copy();
        }

        static DataResult<Offer> validate(Offer offer) {
            if (offer == null || offer.item == null || offer.item.isEmpty()
                    || offer.item.getCount() < 1 || offer.item.getCount() > offer.item.getMaxStackSize()) {
                return DataResult.error(() -> "Exact item stack is invalid");
            }
            if (ItemStack.validateStrict(offer.item).error().isPresent()) {
                return DataResult.error(() -> "Exact item stack failed strict validation");
            }
            if (offer.buyPrice == null || offer.sellPrice == null
                    || offer.buyPrice.isEmpty() && offer.sellPrice.isEmpty()) {
                return DataResult.error(() -> "Offer requires a buy or sell price");
            }
            if (offer.buyPrice.filter(Offer::invalidPrice).isPresent()
                    || offer.sellPrice.filter(Offer::invalidPrice).isPresent()) {
                return DataResult.error(() -> "Offer price must be between 1 and " + ShopTemplateSnapshot.MAX_PRICE);
            }
            DataResult<Stock> stockResult = Stock.validate(offer.stock);
            return stockResult.error().isPresent()
                    ? DataResult.error(() -> stockResult.error().orElseThrow().message())
                    : DataResult.success(offer);
        }

        private static boolean invalidPrice(long price) {
            return price < 1 || price > ShopTemplateSnapshot.MAX_PRICE;
        }
    }

    public record Stock(
            boolean unlimited,
            long current,
            long maximum,
            Optional<Long> restockAmount,
            Optional<Long> restockIntervalTicks,
            long nextRestockGameTime) {
        public static final Codec<Stock> CODEC = RecordCodecBuilder.<Stock>create(instance -> instance.group(
                Codec.BOOL.fieldOf("unlimited").forGetter(Stock::unlimited),
                Codec.LONG.optionalFieldOf("current", 0L).forGetter(Stock::current),
                Codec.LONG.optionalFieldOf("maximum", 0L).forGetter(Stock::maximum),
                Codec.LONG.optionalFieldOf("restock_amount").forGetter(Stock::restockAmount),
                Codec.LONG.optionalFieldOf("restock_interval_ticks").forGetter(Stock::restockIntervalTicks),
                Codec.LONG.optionalFieldOf("next_restock_game_time", 0L).forGetter(Stock::nextRestockGameTime)
        ).apply(instance, Stock::new)).validate((Stock value) -> Stock.validate(value));

        public Stock {
            restockAmount = restockAmount == null ? Optional.empty() : restockAmount;
            restockIntervalTicks = restockIntervalTicks == null ? Optional.empty() : restockIntervalTicks;
        }

        public static Stock unlimitedStock() {
            return new Stock(true, 0, 0, Optional.empty(), Optional.empty(), 0);
        }

        public static Stock finite(long current, long maximum) {
            return new Stock(false, current, maximum, Optional.empty(), Optional.empty(), 0);
        }

        static DataResult<Stock> validate(Stock stock) {
            if (stock == null) {
                return DataResult.error(() -> "Stock policy is missing");
            }
            if (stock.unlimited) {
                return stock.current == 0 && stock.maximum == 0 && stock.restockAmount.isEmpty()
                        && stock.restockIntervalTicks.isEmpty() && stock.nextRestockGameTime == 0
                        ? DataResult.success(stock)
                        : DataResult.error(() -> "Unlimited stock cannot define finite stock fields");
            }
            if (stock.current < 0 || stock.maximum < 0 || stock.current > stock.maximum
                    || stock.maximum > ShopTemplateSnapshot.MAX_STOCK) {
                return DataResult.error(() -> "Finite stock must satisfy 0 <= current <= maximum <= "
                        + ShopTemplateSnapshot.MAX_STOCK);
            }
            if (stock.restockAmount.isPresent() != stock.restockIntervalTicks.isPresent()) {
                return DataResult.error(() -> "Restock amount and interval must be defined together");
            }
            if (stock.restockAmount.isEmpty()) {
                return stock.nextRestockGameTime == 0
                        ? DataResult.success(stock)
                        : DataResult.error(() -> "Stock without restock cannot define a next restock time");
            }
            long amount = stock.restockAmount.orElseThrow();
            long interval = stock.restockIntervalTicks.orElseThrow();
            return amount >= 1 && amount <= stock.maximum && interval >= 1
                    && interval <= ShopTemplateSnapshot.MAX_RESTOCK_INTERVAL_TICKS
                    && stock.nextRestockGameTime >= 0
                    ? DataResult.success(stock)
                    : DataResult.error(() -> "Restock policy is invalid");
        }
    }
}
