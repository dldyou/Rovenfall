package org.dldyou.rovenfall.administration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

final class AdministrationPlayerHeadTest {
    @Test
    void visibleNameIsFriendlyWhileTheProfileCarriesSelectionIdentity() {
        UUID playerId = UUID.fromString("12345678-1234-5678-9abc-def012345678");
        var named = AdministrationPlayerHead.decorate(head(), playerId, "  Mina  ");
        assertEquals("Mina", named.get(DataComponents.PROFILE).name().orElseThrow());

        var unknown = AdministrationPlayerHead.decorate(head(), playerId, " ");
        assertEquals(playerId, unknown.get(DataComponents.PROFILE).partialProfile().id());
    }

    @Test
    void missingSelectionIdentityIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> AdministrationPlayerHead.create(null, "Mina"));
        assertThrows(IllegalArgumentException.class, () -> AdministrationPlayerHead.decorate(null, UUID.randomUUID(), "Mina"));
    }

    private static ItemStack head() {
        return new ItemStackTemplate(
                Holder.direct(Items.PLAYER_HEAD,
                        DataComponentMap.builder().set(DataComponents.MAX_STACK_SIZE, 64).build()),
                1,
                DataComponentPatch.EMPTY).create();
    }
}
