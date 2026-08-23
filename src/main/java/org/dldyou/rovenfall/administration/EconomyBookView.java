package org.dldyou.rovenfall.administration;

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

final class EconomyBookView {
    static final int PAGE_SIZE = 8;

    private EconomyBookView() {
    }

    static void open(ServerPlayer player, List<Component> pages) {
        ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
        book.set(DataComponents.WRITTEN_BOOK_CONTENT, new WrittenBookContent(
                Filterable.passThrough("rovenfall:economy"), "rovenfall", 0,
                pages.stream().map(Filterable::passThrough).toList(), false));
        int stateId = player.inventoryMenu.getStateId();
        ItemStack offhand = player.getOffhandItem().copy();
        player.connection.send(new ClientboundContainerSetSlotPacket(
                InventoryMenu.CONTAINER_ID, stateId, InventoryMenu.SHIELD_SLOT, book));
        player.openItemGui(book, InteractionHand.OFF_HAND);
        player.connection.send(new ClientboundContainerSetSlotPacket(
                InventoryMenu.CONTAINER_ID, stateId, InventoryMenu.SHIELD_SLOT, offhand));
    }

    static List<Component> balances(EconomyObservabilityService.Page<EconomyObservabilityService.BalanceRow> page) {
        List<Component> pages = summary("gui.rovenfall.admin.economy.balances", page);
        page.entries().forEach(row -> pages.add(Component.translatable(
                "gui.rovenfall.admin.economy.balance", row.playerId().toString(), row.balance())));
        return List.copyOf(pages);
    }

    static List<Component> transactions(EconomyObservabilityService.Page<EconomyObservabilityService.TransactionRow> page) {
        List<Component> pages = summary("gui.rovenfall.admin.economy.transactions", page);
        page.entries().forEach(row -> pages.add(Component.translatable(
                "gui.rovenfall.admin.economy.transaction", row.transactionId().toString(),
                Component.translatable("economy_transaction_kind.rovenfall."
                        + row.receipt().kind().getSerializedName()),
                row.receipt().playerId().toString(), row.receipt().amount(),
                row.receipt().reversedBy().<Component>map(id -> Component.literal(id.toString()))
                        .orElseGet(() -> Component.translatable("gui.rovenfall.admin.economy.none")),
                row.receipt().invalidatedByRestore().<Component>map(id -> Component.literal(id.toString()))
                        .orElseGet(() -> Component.translatable("gui.rovenfall.admin.economy.none")))));
        return List.copyOf(pages);
    }

    static List<Component> shops(EconomyObservabilityService.Page<EconomyObservabilityService.ShopRow> page) {
        List<Component> pages = summary("gui.rovenfall.admin.economy.shops", page);
        page.entries().forEach(row -> pages.add(Component.translatable(
                "gui.rovenfall.admin.economy.shop", row.shopId().toString(), row.offerId().toString(), row.item().getHoverName(),
                row.stock().unlimited()
                        ? Component.translatable("gui.rovenfall.admin.economy.stock_unlimited")
                        : Component.translatable("gui.rovenfall.admin.economy.stock_finite",
                                row.stock().current(), row.stock().maximum()))));
        return List.copyOf(pages);
    }

    static List<Component> alerts(EconomyObservabilityService.Page<EconomyAlert> page) {
        List<Component> pages = summary("gui.rovenfall.admin.economy.alerts", page);
        page.entries().forEach(alert -> pages.add(Component.translatable(
                "gui.rovenfall.admin.economy.alert",
                Component.translatable("economy_alert_type.rovenfall." + alert.type().getSerializedName()),
                alert.playerId().toString(), alert.transactionId().toString(),
                alert.observedValue(), alert.threshold())));
        return List.copyOf(pages);
    }

    private static ArrayList<Component> summary(String titleKey, EconomyObservabilityService.Page<?> page) {
        ArrayList<Component> pages = new ArrayList<>();
        pages.add(Component.translatable("gui.rovenfall.admin.economy.summary", Component.translatable(titleKey),
                page.page() + 1, page.totalPages(), page.totalEntries(), page.entries().size()));
        if (page.entries().isEmpty()) {
            pages.add(Component.translatable("gui.rovenfall.admin.economy.empty"));
        }
        return pages;
    }
}
