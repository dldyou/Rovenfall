package org.dldyou.rovenfall.administration;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WrittenBookContent;

final class AdminSearchBookView {
    static final int PAGE_SIZE = 6;

    private AdminSearchBookView() {
    }

    static void open(ServerPlayer player, AdminSearchService.Page result) {
        ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
        book.set(DataComponents.WRITTEN_BOOK_CONTENT, new WrittenBookContent(
                Filterable.passThrough("rovenfall:admin_search"),
                "rovenfall",
                0,
                pages(result).stream().map(Filterable::passThrough).toList(),
                false));
        int stateId = player.inventoryMenu.getStateId();
        ItemStack offhand = player.getOffhandItem().copy();
        player.connection.send(new ClientboundContainerSetSlotPacket(
                InventoryMenu.CONTAINER_ID, stateId, InventoryMenu.SHIELD_SLOT, book));
        player.openItemGui(book, InteractionHand.OFF_HAND);
        player.connection.send(new ClientboundContainerSetSlotPacket(
                InventoryMenu.CONTAINER_ID, stateId, InventoryMenu.SHIELD_SLOT, offhand));
    }

    static List<Component> pages(AdminSearchService.Page result) {
        List<Component> pages = new ArrayList<>();
        Component scope = result.scope() == null
                ? Component.translatable("gui.rovenfall.admin.search.unknown_scope")
                : Component.translatable(result.scope().translationKey());
        pages.add(Component.translatable(
                "gui.rovenfall.admin.search.summary",
                scope,
                result.query(),
                result.page() + 1,
                result.totalPages(),
                result.totalEntries(),
                result.entries().size()));
        if (result.entries().isEmpty()) {
            pages.add(Component.translatable("gui.rovenfall.admin.search.empty"));
            return List.copyOf(pages);
        }
        result.entries().forEach(row -> pages.add(row(row)));
        return List.copyOf(pages);
    }

    private static Component row(AdminSearchService.Row row) {
        if (row instanceof AdminSearchService.PlayerRow value) {
            Component role = value.adminRole()
                    .<Component>map(roleValue -> Component.translatable(roleValue.translationKey()))
                    .orElseGet(AdminSearchBookView::none);
            Component lastSeen = value.record()
                    .<Component>map(record -> Component.literal(
                            Instant.ofEpochMilli(record.lastSeenEpochMillis()).toString()))
                    .orElseGet(AdminSearchBookView::none);
            Component balance = value.balance()
                    .<Component>map(amount -> Component.literal(Long.toString(amount)))
                    .orElseGet(AdminSearchBookView::none);
            Component career = value.activeCareer()
                    .<Component>map(id -> Component.literal(id.toString()))
                    .orElseGet(AdminSearchBookView::none);
            return Component.translatable(
                    "gui.rovenfall.admin.search.player",
                    value.playerId().toString(), role, lastSeen, balance,
                    value.totalActivityExperience(), career, value.learnedCareers(), value.claims());
        }
        if (row instanceof AdminSearchService.BalanceRow value) {
            return Component.translatable(
                    "gui.rovenfall.admin.search.balance", value.playerId().toString(), value.balance());
        }
        if (row instanceof AdminSearchService.TransactionRow value) {
            return Component.translatable(
                    "gui.rovenfall.admin.search.transaction",
                    value.transactionId().toString(),
                    Component.translatable("economy_transaction_kind.rovenfall."
                            + value.receipt().kind().getSerializedName()),
                    value.receipt().playerId().toString(),
                    value.receipt().actorId().toString(),
                    value.receipt().amount(),
                    value.receipt().reversedBy().<Component>map(id -> Component.literal(id.toString()))
                            .orElseGet(AdminSearchBookView::none));
        }
        if (row instanceof AdminSearchService.ClaimRow value) {
            return Component.translatable(
                    "gui.rovenfall.admin.search.claim",
                    value.key().auditTarget(),
                    value.claim().ownerId().toString(),
                    value.claim().purchasePrice(),
                    value.claim().trustedRoles().size(),
                    booleanValue(value.claim().settings().entryRestricted()),
                    booleanValue(value.claim().settings().publicInteractions()),
                    value.claim().pendingTransferTo().<Component>map(id -> Component.literal(id.toString()))
                            .orElseGet(AdminSearchBookView::none));
        }
        if (row instanceof AdminSearchService.ShopRow value) {
            Component binding = value.shop().binding()
                    .<Component>map(bound -> Component.literal(
                            bound.dimension().identifier() + "@" + bound.position().toShortString()))
                    .orElseGet(AdminSearchBookView::none);
            return Component.translatable(
                    "gui.rovenfall.admin.search.shop",
                    value.shopId().toString(), value.shop().templateId().toString(),
                    binding, value.shop().offers().size(), value.shop().accessPolicy().maxDistance());
        }
        if (row instanceof AdminSearchService.DeniedRow value) {
            AuditEntry entry = value.entry();
            return Component.translatable(
                    "gui.rovenfall.admin.search.denied",
                    Instant.ofEpochMilli(entry.timestampEpochMillis()).toString(),
                    entry.actionType().toString(), entry.target(), entry.reason(),
                    entry.actorId().toString(), entry.transactionId().toString());
        }
        if (row instanceof AdminSearchService.AlertRow value) {
            EconomyAlert alert = value.alert();
            return Component.translatable(
                    "gui.rovenfall.admin.search.alert",
                    Instant.ofEpochMilli(alert.timestampEpochMillis()).toString(),
                    Component.translatable("economy_alert_type.rovenfall." + alert.type().getSerializedName()),
                    alert.playerId().toString(), alert.transactionId().toString(),
                    alert.observedValue(), alert.threshold());
        }
        throw new IllegalArgumentException("Unknown administrator search row");
    }

    private static Component none() {
        return Component.translatable("gui.rovenfall.admin.search.none");
    }

    private static Component booleanValue(boolean value) {
        return Component.translatable("gui.rovenfall.admin.search." + (value ? "yes" : "no"));
    }
}
