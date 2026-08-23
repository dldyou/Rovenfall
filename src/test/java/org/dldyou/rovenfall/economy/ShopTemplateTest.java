package org.dldyou.rovenfall.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

final class ShopTemplateTest {
    @Test
    void codecRejectsARecordWithoutItsTranslationKey() {
        var json = JsonParser.parseString("""
                {
                  "offers": []
                }
                """);

        assertTrue(ShopTemplate.CODEC.parse(JsonOps.INSTANCE, json).error().isPresent());
    }

    @Test
    void compilationRejectsDuplicateIdsAndInvalidCollectionsWithFileEvidence() {
        ShopTemplate valid = validTemplate();
        var duplicate = assertThrows(ShopTemplateSnapshot.ValidationException.class, () -> ShopTemplateSnapshot.compile(List.of(
                source("one.json", "rovenfall:test", valid),
                source("two.json", "rovenfall:test", valid))));
        assertTrue(duplicate.getMessage().contains("rovenfall:one.json"));
        assertTrue(duplicate.getMessage().contains("rovenfall:test"));

        assertInvalid(new ShopTemplate("shop_template.rovenfall.empty", List.of()), "offer count");
        ShopTemplate.Offer offer = valid.offers().getFirst();
        assertInvalid(new ShopTemplate("shop_template.rovenfall.duplicate", List.of(offer, offer)), "duplicate offer ID");

        List<ShopTemplate.Offer> tooMany = new ArrayList<>();
        for (int index = 0; index <= ShopTemplate.MAX_OFFERS; index++) {
            tooMany.add(new ShopTemplate.Offer(
                    id("offer_" + index), stack(1), Optional.of(1L), Optional.empty(), unlimited()));
        }
        assertInvalid(new ShopTemplate("shop_template.rovenfall.large", tooMany), "offer count");
    }

    @Test
    void compilationRejectsInvalidTranslationStackPricesAndStock() {
        assertInvalid(new ShopTemplate("Invalid key", validTemplate().offers()), "invalid translation key");
        assertInvalid(template(new ShopTemplate.Offer(
                id("oversized"), stack(99), Optional.of(1L), Optional.empty(), unlimited())), "invalid exact item stack");
        assertInvalid(template(new ShopTemplate.Offer(
                id("no_price"), stack(1), Optional.empty(), Optional.empty(), unlimited())), "requires a buy or sell price");
        assertInvalid(template(new ShopTemplate.Offer(
                id("bad_price"), stack(1), Optional.of(0L), Optional.empty(), unlimited())), "buy price");
        assertInvalid(template(new ShopTemplate.Offer(
                id("overflow_price"), stack(1), Optional.empty(), Optional.of(Long.MAX_VALUE), unlimited())), "sell price");
        assertInvalid(template(new ShopTemplate.Offer(
                id("unlimited_fields"), stack(1), Optional.of(1L), Optional.empty(),
                new ShopTemplate.StockPolicy(true, Optional.of(1L), Optional.empty(), Optional.empty(), Optional.empty()))),
                "unlimited stock");
        assertInvalid(template(new ShopTemplate.Offer(
                id("missing_max"), stack(1), Optional.of(1L), Optional.empty(),
                new ShopTemplate.StockPolicy(false, Optional.of(1L), Optional.empty(), Optional.empty(), Optional.empty()))),
                "requires initial and maximum");
        assertInvalid(template(new ShopTemplate.Offer(
                id("bad_stock"), stack(1), Optional.of(1L), Optional.empty(),
                finite(11, 10, Optional.empty(), Optional.empty()))), "finite stock");
        assertInvalid(template(new ShopTemplate.Offer(
                id("partial_restock"), stack(1), Optional.of(1L), Optional.empty(),
                finite(1, 10, Optional.of(1L), Optional.empty()))), "defined together");
        assertInvalid(template(new ShopTemplate.Offer(
                id("long_restock"), stack(1), Optional.of(1L), Optional.empty(),
                finite(1, 10, Optional.of(1L), Optional.of(ShopTemplateSnapshot.MAX_RESTOCK_INTERVAL_TICKS + 1)))),
                "restock interval");
    }

    @Test
    void buyAndSellPricesAreIndependentlyOptional() {
        ShopTemplate sellOnly = template(new ShopTemplate.Offer(
                id("sell_only"), stack(1), Optional.empty(), Optional.of(5L), unlimited()));

        assertEquals(1, ShopTemplateSnapshot.compile(List.of(
                source("sell_only.json", "rovenfall:sell_only", sellOnly))).validateBoundItems().size());
    }

    @Test
    void failedReplacementPreservesPriorImmutableSnapshot() {
        ShopTemplateStore store = new ShopTemplateStore();
        ShopTemplateSnapshot installed = store.replace(List.of(source("valid.json", "rovenfall:valid", validTemplate())));
        ItemStack firstRead = installed.get(id("valid")).orElseThrow().offers().getFirst().item();
        firstRead.setCount(1);
        assertEquals(4, installed.get(id("valid")).orElseThrow().offers().getFirst().item().getCount());

        assertThrows(ShopTemplateSnapshot.ValidationException.class, () -> store.replace(List.of(
                source("invalid.json", "rovenfall:invalid", template(new ShopTemplate.Offer(
                        id("oversized"), stack(99), Optional.of(1L), Optional.empty(), unlimited()))))));

        assertNotSame(ShopTemplateSnapshot.empty(), store.current());
        assertTrue(store.current().get(id("valid")).isPresent());
        assertFalse(store.current().get(id("invalid")).isPresent());
    }

    private static ShopTemplate validTemplate() {
        return template(new ShopTemplate.Offer(
                id("bread"), stack(4), Optional.of(12L), Optional.of(6L),
                finite(10, 20, Optional.of(2L), Optional.of(1_200L))));
    }

    private static ShopTemplate template(ShopTemplate.Offer offer) {
        return new ShopTemplate("shop_template.rovenfall.test", List.of(offer));
    }

    private static ItemStackTemplate stack(int count) {
        var components = DataComponentMap.builder().set(DataComponents.MAX_STACK_SIZE, 64).build();
        return new ItemStackTemplate(Holder.direct(Items.BREAD, components), count, DataComponentPatch.EMPTY);
    }

    private static ShopTemplate.StockPolicy unlimited() {
        return new ShopTemplate.StockPolicy(
                true, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    private static ShopTemplate.StockPolicy finite(
            long initial,
            long maximum,
            Optional<Long> restockAmount,
            Optional<Long> restockInterval) {
        return new ShopTemplate.StockPolicy(
                false, Optional.of(initial), Optional.of(maximum), restockAmount, restockInterval);
    }

    private static ShopTemplateSnapshot.Source source(String file, String templateId, ShopTemplate template) {
        return new ShopTemplateSnapshot.Source(
                Identifier.fromNamespaceAndPath("rovenfall", file), "test", Identifier.parse(templateId), template);
    }

    private static void assertInvalid(ShopTemplate template, String expectedCause) {
        var exception = assertThrows(ShopTemplateSnapshot.ValidationException.class, () ->
                ShopTemplateSnapshot.compile(List.of(source("test.json", "rovenfall:test", template))).validateBoundItems());
        assertTrue(exception.getMessage().contains(expectedCause), exception.getMessage());
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("rovenfall", path);
    }
}
