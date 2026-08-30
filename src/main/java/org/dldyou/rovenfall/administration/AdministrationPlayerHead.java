package org.dldyou.rovenfall.administration;

import java.util.UUID;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ResolvableProfile;

/** Human-facing player head presentation; UUIDs remain profile data rather than visible copy. */
final class AdministrationPlayerHead {
    private AdministrationPlayerHead() {
    }

    static ItemStack create(UUID playerId, String displayName, Component... lore) {
        if (playerId == null) {
            throw new IllegalArgumentException("playerId is required");
        }
        String name = displayName == null ? "" : displayName.strip();
        ItemStack head = PlayerDashboardMenu.icon(
                net.minecraft.world.item.Items.PLAYER_HEAD,
                name.isBlank()
                        ? Component.translatable("gui.rovenfall.player.unknown_player")
                        : Component.literal(name),
                lore);
        return decorate(head, playerId, name);
    }

    static ItemStack decorate(ItemStack head, UUID playerId, String displayName) {
        if (head == null || head.isEmpty() || playerId == null) {
            throw new IllegalArgumentException("head and playerId are required");
        }
        String name = displayName == null ? "" : displayName.strip();
        head.set(DataComponents.PROFILE, name.isBlank()
                ? ResolvableProfile.createUnresolved(playerId)
                : ResolvableProfile.createUnresolved(name));
        return head;
    }
}
