package org.dldyou.rovenfall.administration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import net.minecraft.resources.Identifier;
import org.dldyou.rovenfall.economy.ShopInstance;
import org.dldyou.rovenfall.economy.ShopTemplateSnapshot;
import org.junit.jupiter.api.Test;

class AdministrationEconomyFormParserTest {
    @Test
    void parsesBalanceAndNormalizesReason() {
        var result = AdministrationEconomyFormParser.parseBalance(" 42 |  grant for quest  ");

        assertTrue(result.isPresent());
        assertEquals(42, result.orElseThrow().amount());
        assertEquals("grant for quest", result.orElseThrow().reason());
    }

    @Test
    void parsesShopAccessAndReasonOnlyForms() {
        var shop = AdministrationEconomyFormParser.parseShopCreate("shops:market, rpg:starter | create market");
        var access = AdministrationEconomyFormParser.parseAccessDistance(
                ShopInstance.MAX_ACCESS_DISTANCE + " | widen access");
        var reason = AdministrationEconomyFormParser.parseReasonOnly(" | confirm removal");

        assertEquals(Optional.of(new AdministrationEconomyFormParser.ShopCreate(
                Identifier.parse("shops:market"), Identifier.parse("rpg:starter"), "create market")), shop);
        assertEquals(Optional.of(new AdministrationEconomyFormParser.AccessDistance(
                ShopInstance.MAX_ACCESS_DISTANCE, "widen access")), access);
        assertEquals(Optional.of(new AdministrationEconomyFormParser.ReasonOnly("confirm removal")), reason);
    }

    @Test
    void parsesOfferWithCommandCompatibleUnsetAndFiniteStock() {
        var result = AdministrationEconomyFormParser.parseOfferUpsert(
                "rpg:bread, minecraft:bread, 99, 12, none, 4, 10 | replenish shelf");

        assertTrue(result.isPresent());
        var offer = result.orElseThrow();
        assertEquals(Identifier.parse("rpg:bread"), offer.offerId());
        assertEquals(Identifier.parse("minecraft:bread"), offer.itemId());
        assertEquals(99, offer.count());
        assertEquals(Optional.of(12L), offer.buyPrice());
        assertEquals(Optional.empty(), offer.sellPrice());
        assertEquals(4, offer.stock());
        assertEquals(10, offer.maximumStock());
    }

    @Test
    void parsesUnlimitedOfferAndRestockSetOrClear() {
        var unlimited = AdministrationEconomyFormParser.parseOfferUpsert(
                "rpg:offer,minecraft:diamond,1,-1,100,-1,-1 | unlimited sale");
        var set = AdministrationEconomyFormParser.parseRestock(
                "rpg:offer,25," + ShopTemplateSnapshot.MAX_RESTOCK_INTERVAL_TICKS + " | schedule restock");
        var clear = AdministrationEconomyFormParser.parseRestock("rpg:offer,clear | stop restock");

        assertTrue(unlimited.isPresent());
        assertTrue(set.isPresent());
        assertEquals(Optional.of(25L), set.orElseThrow().amount());
        assertEquals(Optional.of(ShopTemplateSnapshot.MAX_RESTOCK_INTERVAL_TICKS), set.orElseThrow().intervalTicks());
        assertEquals(Optional.empty(), clear.orElseThrow().amount());
        assertEquals(Optional.empty(), clear.orElseThrow().intervalTicks());
    }

    @Test
    void rejectsMalformedOverflowAndOutOfBoundsValues() {
        String tooLongReason = "x".repeat(AdministrationService.MAX_REASON_LENGTH + 1);

        assertFalse(AdministrationEconomyFormParser.parseBalance("0 | invalid").isPresent());
        assertFalse(AdministrationEconomyFormParser.parseBalance("9223372036854775808 | overflow").isPresent());
        assertFalse(AdministrationEconomyFormParser.parseShopCreate("bad id, rpg:template | invalid id").isPresent());
        assertFalse(AdministrationEconomyFormParser.parseAccessDistance(
                (ShopInstance.MAX_ACCESS_DISTANCE + 1) + " | too far").isPresent());
        assertFalse(AdministrationEconomyFormParser.parseOfferUpsert(
                "rpg:o,minecraft:i,100,1,1,1,1 | count too high").isPresent());
        assertFalse(AdministrationEconomyFormParser.parseOfferUpsert(
                "rpg:o,minecraft:i,1,0,0,1,1 | prices invalid").isPresent());
        assertFalse(AdministrationEconomyFormParser.parseOfferUpsert(
                "rpg:o,minecraft:i,1,1,1,5,4 | stock inverted").isPresent());
        assertFalse(AdministrationEconomyFormParser.parseRestock(
                "rpg:o,1," + (ShopTemplateSnapshot.MAX_RESTOCK_INTERVAL_TICKS + 1) + " | interval too high").isPresent());
        assertFalse(AdministrationEconomyFormParser.parseRestock("rpg:o,clear,1 | malformed").isPresent());
        assertFalse(AdministrationEconomyFormParser.parseReasonOnly("reason without delimiter").isPresent());
        assertFalse(AdministrationEconomyFormParser.parseReasonOnly(" | " + tooLongReason).isPresent());
        assertFalse(AdministrationEconomyFormParser.parseBalance("1 | first | second").isPresent());
        assertFalse(AdministrationEconomyFormParser.parseBalance("1\n | line break").isPresent());
    }
}
