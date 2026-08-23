package org.dldyou.rovenfall.administration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

final class AuditBookViewTest {
    @Test
    void oneEntryProducesBoundedReadOnlyPagesWithEveryRequiredField() {
        UUID actor = id(1);
        UUID transaction = id(2);
        AuditEntry entry = new AuditEntry(
                1_000,
                actor,
                Identifier.fromNamespaceAndPath("rovenfall", "role_change"),
                "player:target",
                Optional.empty(),
                Optional.empty(),
                "viewer",
                "moderator",
                "approved request",
                transaction);

        List<Component> pages = AuditBookView.pages(new PlatformSavedData.AuditPage(0, 1, 1, List.of(entry)));

        assertEquals(4, pages.size());
        String rendered = pages.stream().map(Component::getString).reduce("", String::concat);
        assertTrue(rendered.contains("1970-01-01T00:00:01Z"));
        assertTrue(rendered.contains("rovenfall:role_change"));
        assertTrue(rendered.contains("player:target"));
        assertTrue(rendered.contains(actor.toString()));
        assertTrue(rendered.contains(transaction.toString()));
        assertTrue(rendered.contains("approved request"));
        pages.stream().flatMap(page -> page.toFlatList().stream())
                .forEach(component -> assertNull(component.getStyle().getClickEvent()));
    }

    @Test
    void emptyAndOutOfRangePagesProduceSafeEmptyView() {
        List<Component> pages = AuditBookView.pages(new PlatformSavedData.AuditPage(7, 2, 6, List.of()));

        assertEquals(2, pages.size());
        TranslatableContents empty = (TranslatableContents) pages.getLast().getContents();
        assertEquals("gui.rovenfall.admin.audit.empty", empty.getKey());
        assertEquals(8, empty.getArgs()[0]);
    }

    private static UUID id(long value) {
        return new UUID(0, value);
    }
}
