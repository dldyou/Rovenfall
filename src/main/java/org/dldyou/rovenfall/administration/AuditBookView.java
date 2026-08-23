package org.dldyou.rovenfall.administration;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
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

final class AuditBookView {
    static final int PAGE_SIZE = 5;

    private AuditBookView() {
    }

    static void open(ServerPlayer player, PlatformSavedData.AuditPage auditPage) {
        ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
        book.set(DataComponents.WRITTEN_BOOK_CONTENT, new WrittenBookContent(
                Filterable.passThrough("rovenfall:audit"),
                "rovenfall",
                0,
                pages(auditPage).stream().map(Filterable::passThrough).toList(),
                false
        ));

        int stateId = player.inventoryMenu.getStateId();
        ItemStack offhandItem = player.getOffhandItem().copy();
        // The native book screen copies its pages when opened, so this client-only slot swap
        // avoids changing the server inventory or introducing a custom network protocol.
        player.connection.send(new ClientboundContainerSetSlotPacket(
                InventoryMenu.CONTAINER_ID, stateId, InventoryMenu.SHIELD_SLOT, book));
        player.openItemGui(book, InteractionHand.OFF_HAND);
        player.connection.send(new ClientboundContainerSetSlotPacket(
                InventoryMenu.CONTAINER_ID, stateId, InventoryMenu.SHIELD_SLOT, offhandItem));
    }

    static List<Component> pages(PlatformSavedData.AuditPage auditPage) {
        List<Component> pages = new ArrayList<>();
        pages.add(Component.translatable("gui.rovenfall.admin.audit.summary",
                auditPage.page() + 1,
                auditPage.totalPages(),
                auditPage.totalEntries(),
                auditPage.entries().size()));

        if (auditPage.entries().isEmpty()) {
            pages.add(Component.translatable("gui.rovenfall.admin.audit.empty", auditPage.page() + 1));
            return List.copyOf(pages);
        }

        for (int index = 0; index < auditPage.entries().size(); index++) {
            AuditEntry entry = auditPage.entries().get(index);
            Component heading = heading(index + 1, auditPage.entries().size());
            pages.add(heading.copy()
                    .append(field("gui.rovenfall.admin.audit.timestamp", Instant.ofEpochMilli(entry.timestampEpochMillis()).toString()))
                    .append(field("gui.rovenfall.admin.audit.action", entry.actionType().toString()))
                    .append(field("gui.rovenfall.admin.audit.target", entry.target())));
            pages.add(heading.copy()
                    .append(field("gui.rovenfall.admin.audit.actor", entry.actorId().toString()))
                    .append(field("gui.rovenfall.admin.audit.transaction", entry.transactionId().toString())));
            pages.add(heading.copy()
                    .append(field("gui.rovenfall.admin.audit.reason", entry.reason())));
        }
        return List.copyOf(pages);
    }

    private static Component heading(int entry, int entryCount) {
        return Component.translatable("gui.rovenfall.admin.audit.entry", entry, entryCount)
                .withStyle(ChatFormatting.BOLD)
                .append("\n");
    }

    private static Component field(String translationKey, String value) {
        return Component.literal("\n")
                .append(Component.translatable(translationKey).withStyle(ChatFormatting.DARK_GRAY))
                .append("\n")
                .append(Component.literal(value));
    }
}
