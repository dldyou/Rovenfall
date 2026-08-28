package org.dldyou.rovenfall.administration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import org.dldyou.rovenfall.economy.ShopInstance;
import org.dldyou.rovenfall.economy.ShopTemplate;
import org.dldyou.rovenfall.economy.ShopTemplateSnapshot;
import org.junit.jupiter.api.Test;

final class AdministrationEconomyActionServiceTest {
    @Test
    void onlyEconomyManagersAndOwnersCanSeeMutationControls() {
        assertFalse(AdministrationEconomyMenu.canManage(AdminRole.VIEWER));
        assertFalse(AdministrationEconomyMenu.canManage(AdminRole.MODERATOR));
        assertFalse(AdministrationEconomyMenu.canManage(AdminRole.CONTENT_MANAGER));
        assertTrue(AdministrationEconomyMenu.canManage(AdminRole.ECONOMY_MANAGER));
        assertTrue(AdministrationEconomyMenu.canManage(AdminRole.OWNER));
    }

    @Test
    void editingTheSameOfferItemPreservesItsExactComponents() {
        var components = DataComponentMap.builder().set(DataComponents.MAX_STACK_SIZE, 64).build();
        var patch = DataComponentPatch.builder()
                .set(DataComponents.CUSTOM_NAME, Component.literal("Exact component bread"))
                .build();
        ItemStack exact = new ItemStackTemplate(Holder.direct(Items.BREAD, components), 4, patch).create();

        ItemStack preserved = AdministrationEconomyMenu.copyOfferStack(exact, 2);

        assertTrue(ItemStack.isSameItemSameComponents(exact, preserved));
        assertTrue(preserved.getCount() == 2);
        assertTrue(preserved.get(DataComponents.CUSTOM_NAME)
                .equals(Component.literal("Exact component bread")));
    }


    @Test
    void balanceConfirmationIsBoundToItsPreviewedBalance() {
        PlatformSavedData state = new PlatformSavedData();
        UUID player = id(1);
        var action = new AdministrationEconomyActionService.BalanceAction(
                id(100), player, 10, true, Optional.empty(), "grant");

        assertTrue(AdministrationEconomyActionService.fresh(state, action));
        EconomyService.award(
                state, player, 1, "test", 1_000, id(101),
                1_000, 1_000_000);
        assertFalse(AdministrationEconomyActionService.fresh(state, action));
    }

    @Test
    void shopConfirmationIsBoundToExactInstanceSnapshot() {
        PlatformSavedData state = new PlatformSavedData();
        Identifier shopId = Identifier.parse("rovenfall:test");
        ShopInstance before = new ShopInstance(
                Identifier.parse("rovenfall:template"), Optional.empty(),
                ShopInstance.AccessPolicy.publicAccess(), Map.of());
        state.commitShopMutation(shopId, Optional.of(before), id(200), 1_000,
                audit(id(200), shopId));
        var action = new AdministrationEconomyActionService.ShopAccessAction(
                id(201), shopId, Optional.of(before), 12, "access");

        assertTrue(AdministrationEconomyActionService.fresh(state, action));
        ShopInstance changed = before.withAccessPolicy(new ShopInstance.AccessPolicy(10));
        state.commitShopMutation(shopId, Optional.of(changed), id(202), 2_000,
                audit(id(202), shopId));
        assertFalse(AdministrationEconomyActionService.fresh(state, action));
    }

    @Test
    void shopCreationIsBoundToThePreviewedTemplate() {
        PlatformSavedData state = new PlatformSavedData();
        Identifier shopId = Identifier.parse("rovenfall:new_shop");
        Identifier templateId = Identifier.parse("rovenfall:template");
        ShopTemplate previewed = template("shop.previewed");
        ShopTemplate changed = template("shop.changed");
        ShopTemplateSnapshot previewedSnapshot = snapshot(templateId, previewed);
        ShopTemplateSnapshot changedSnapshot = snapshot(templateId, changed);
        var action = new AdministrationEconomyActionService.ShopCreateAction(
                id(300), shopId, templateId, previewed, "create");

        assertTrue(AdministrationEconomyActionService.fresh(state, previewedSnapshot, action));
        assertFalse(AdministrationEconomyActionService.fresh(state, changedSnapshot, action));
        assertFalse(AdministrationEconomyActionService.fresh(state, ShopTemplateSnapshot.empty(), action));
    }

    @Test
    void receiptReversalIsBoundToItsPreviewedBalance() {
        PlatformSavedData state = new PlatformSavedData();
        UUID player = id(4);
        UUID grantId = id(400);
        assertTrue(EconomyService.adminGrant(
                        state, AdministrationService.SYSTEM_ACTOR, true, player, 25, "grant",
                        1_000, grantId, 0, 1_000_000).status()
                        == EconomyService.TransactionStatus.SUCCESS);
        EconomyTransactionReceipt receipt = state.economyReceipt(grantId).orElseThrow();
        var action = new AdministrationEconomyActionService.ReceiptReversalAction(
                id(401), grantId, player, receipt, state.economyBalance(player),
                List.of(), Optional.empty(),
                EconomyTransactionReceipt.CompensationDecision.NONE, "reverse");

        assertTrue(AdministrationEconomyActionService.fresh(state, action));
        EconomyService.award(state, player, 1, "concurrent award", 2_000, id(402), 0, 1_000_000);
        assertFalse(AdministrationEconomyActionService.fresh(state, action));
    }

    @Test
    void tradeReversalInventorySnapshotUsesExactSlotsAndComponents() {
        List<ItemStack> expected = java.util.stream.IntStream.range(0, 36)
                .mapToObj(ignored -> ItemStack.EMPTY).toList();
        List<ItemStack> current = ShopTradeService.copyInventory(expected);
        assertTrue(AdministrationEconomyActionService.sameInventory(expected, current));

        var components = DataComponentMap.builder().set(DataComponents.MAX_STACK_SIZE, 64).build();
        current.set(4, new ItemStackTemplate(
                Holder.direct(Items.BREAD, components), 1, DataComponentPatch.EMPTY).create());
        assertFalse(AdministrationEconomyActionService.sameInventory(expected, current));
    }

    @Test
    void onlySupportedEconomyReceiptKindsExposeReversal() {
        assertTrue(EconomyReversalService.isReversibleKind(EconomyTransactionReceipt.Kind.ADMIN_GRANT));
        assertTrue(EconomyReversalService.isReversibleKind(EconomyTransactionReceipt.Kind.PURCHASE));
        assertFalse(EconomyReversalService.isReversibleKind(EconomyTransactionReceipt.Kind.ACCOUNT_CREATE));
        assertFalse(EconomyReversalService.isReversibleKind(EconomyTransactionReceipt.Kind.RPG_SKILL_PAYMENT));
        assertFalse(EconomyReversalService.isReversibleKind(EconomyTransactionReceipt.Kind.REVERSAL));
    }

    private static ShopTemplateSnapshot snapshot(Identifier id, ShopTemplate template) {
        return ShopTemplateSnapshot.compile(List.of(new ShopTemplateSnapshot.Source(
                Identifier.fromNamespaceAndPath(id.getNamespace(), "shop_templates/" + id.getPath()),
                "test", id, template)));
    }

    private static ShopTemplate template(String translationKey) {
        var components = DataComponentMap.builder().set(DataComponents.MAX_STACK_SIZE, 64).build();
        var stack = new ItemStackTemplate(Holder.direct(Items.BREAD, components), 1, DataComponentPatch.EMPTY);
        var stock = new ShopTemplate.StockPolicy(
                true, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        var offer = new ShopTemplate.Offer(
                Identifier.parse("rovenfall:bread"), stack, Optional.of(1L), Optional.empty(), stock);
        return new ShopTemplate(translationKey, List.of(offer));
    }

    private static AuditEntry audit(UUID transactionId, Identifier target) {
        return new AuditEntry(
                1_000, AdministrationService.SYSTEM_ACTOR,
                Identifier.parse("rovenfall:test"), target.toString(), Optional.empty(), Optional.empty(),
                "before", "after", "test", transactionId);
    }

    private static UUID id(long value) {
        return new UUID(0L, value);
    }
}
