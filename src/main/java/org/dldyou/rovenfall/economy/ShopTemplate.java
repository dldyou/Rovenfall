package org.dldyou.rovenfall.economy;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import java.util.Optional;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;

public record ShopTemplate(String translationKey, List<Offer> offers) {
    public static final int MAX_OFFERS = 256;

    public static final Codec<ShopTemplate> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.string(1, 160).fieldOf("translation_key").forGetter(ShopTemplate::translationKey),
            Offer.CODEC.listOf().fieldOf("offers").forGetter(ShopTemplate::offers)
    ).apply(instance, ShopTemplate::new));

    public ShopTemplate {
        offers = List.copyOf(offers);
    }

    public record Offer(
            Identifier id,
            ItemStackTemplate stackTemplate,
            Optional<Long> buyPrice,
            Optional<Long> sellPrice,
            StockPolicy stock) {

        public static final Codec<Offer> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Identifier.CODEC.fieldOf("id").forGetter(Offer::id),
                ItemStackTemplate.CODEC.fieldOf("item").forGetter(Offer::stackTemplate),
                Codec.LONG.optionalFieldOf("buy_price").forGetter(Offer::buyPrice),
                Codec.LONG.optionalFieldOf("sell_price").forGetter(Offer::sellPrice),
                StockPolicy.CODEC.fieldOf("stock").forGetter(Offer::stock)
        ).apply(instance, Offer::new));

        public ItemStack item() {
            return stackTemplate.create();
        }
    }

    public record StockPolicy(
            boolean unlimited,
            Optional<Long> initial,
            Optional<Long> maximum,
            Optional<Long> restockAmount,
            Optional<Long> restockIntervalTicks) {

        public static final Codec<StockPolicy> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.BOOL.optionalFieldOf("unlimited", false).forGetter(StockPolicy::unlimited),
                Codec.LONG.optionalFieldOf("initial").forGetter(StockPolicy::initial),
                Codec.LONG.optionalFieldOf("maximum").forGetter(StockPolicy::maximum),
                Codec.LONG.optionalFieldOf("restock_amount").forGetter(StockPolicy::restockAmount),
                Codec.LONG.optionalFieldOf("restock_interval_ticks").forGetter(StockPolicy::restockIntervalTicks)
        ).apply(instance, StockPolicy::new));
    }
}
