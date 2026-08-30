package org.dldyou.rovenfall.administration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import org.junit.jupiter.api.Test;

final class AdministrationStructuredFormCodecTest {
    @Test
    void everyFormDefinitionHasStableWireIdAndValidDefaults() {
        for (AdministrationFormType type : AdministrationFormType.values()) {
            assertEquals(type, AdministrationFormType.fromWireId(type.wireId()).orElseThrow());
            assertTrue(type.accepts(type.defaults()));
            assertTrue(type.fields().stream().allMatch(field -> !field.translationKey().isBlank()));
        }
    }

    @Test
    void roundTripsLengthPrefixedValuesWithoutIdentifiers() {
        List<String> values = List.of("both", "12", "", "finite", "3", "8", "2", "weekly adjustment");
        String encoded = AdministrationStructuredFormCodec.encode(AdministrationFormType.ECONOMY_OFFER_UPSERT, values)
                .orElseThrow();

        assertFalse(encoded.contains("minecraft:"));
        assertEquals(values, AdministrationStructuredFormCodec.decode(
                AdministrationFormType.ECONOMY_OFFER_UPSERT, encoded).orElseThrow());
    }

    @Test
    void rejectsBoundaryViolationsMalformedLengthsUnknownTypesAndTrailingData() {
        String valid = AdministrationStructuredFormCodec.encode(
                AdministrationFormType.ECONOMY_GRANT, List.of("1", "reason")).orElseThrow();

        assertFalse(AdministrationStructuredFormCodec.encode(
                AdministrationFormType.ECONOMY_GRANT, List.of("0", "reason")).isPresent());
        assertFalse(AdministrationStructuredFormCodec.encode(
                AdministrationFormType.ECONOMY_GRANT, List.of("1", "x".repeat(AdministrationService.MAX_REASON_LENGTH + 1))).isPresent());
        assertFalse(AdministrationStructuredFormCodec.decode(valid + "x").isPresent());
        assertFalse(AdministrationStructuredFormCodec.decode("rf-form/1/unknown/0/").isPresent());
        assertFalse(AdministrationStructuredFormCodec.decode("rf-form/1/economy-grant/2/99999999999:").isPresent());
        assertFalse(AdministrationStructuredFormCodec.decode("rf-form/1/economy-grant/3/1:11:21:x").isPresent());
        assertFalse(AdministrationStructuredFormCodec.decode("rf-form/1/economy-grant/2/1:1x:reason").isPresent());
        assertFalse(AdministrationStructuredFormCodec.decode(valid.replace("1:1", "2:1")).isPresent());
        assertFalse(AdministrationStructuredFormCodec.decode(valid + "\n").isPresent());
    }

    @Test
    void markerRoundTripsAndPreservesUnrelatedCustomData() {
        ItemStack stack = paper();
        CompoundTag original = new CompoundTag();
        original.putString("kept", "value");
        CustomData.set(DataComponents.CUSTOM_DATA, stack, original);
        AdministrationFormMarker marker = new AdministrationFormMarker(
                AdministrationFormType.ECONOMY_GRANT, List.of("10", ""));

        assertTrue(AdministrationFormMarker.write(stack, marker));
        assertEquals(marker, AdministrationFormMarker.read(stack).orElseThrow());
        assertEquals("value", stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY)
                .copyTag().getStringOr("kept", ""));

        assertTrue(AdministrationFormMarker.writeSearch(stack));
        assertTrue(AdministrationFormMarker.hasSearch(stack));
        assertEquals(marker, AdministrationFormMarker.read(stack).orElseThrow());
        assertTrue(AdministrationFormMarker.writeError(stack));
        assertTrue(AdministrationFormMarker.hasError(stack));
        assertEquals(marker, AdministrationFormMarker.read(stack).orElseThrow());
        assertEquals("value", stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY)
                .copyTag().getStringOr("kept", ""));
    }

    @Test
    void markerRejectsUnknownMalformedExtraAndOversizedValues() {
        ItemStack stack = paper();
        CompoundTag data = new CompoundTag();
        CompoundTag marker = new CompoundTag();
        marker.putString("type", "unknown");
        data.put("rovenfall_admin_form", marker);
        CustomData.set(DataComponents.CUSTOM_DATA, stack, data);
        assertTrue(AdministrationFormMarker.read(stack).isEmpty());

        marker.putString("type", AdministrationFormType.ECONOMY_GRANT.wireId());
        marker.putString("unexpected", "x");
        CustomData.set(DataComponents.CUSTOM_DATA, stack, data);
        assertTrue(AdministrationFormMarker.read(stack).isEmpty());

        net.minecraft.nbt.ListTag defaults = new net.minecraft.nbt.ListTag();
        defaults.add(net.minecraft.nbt.StringTag.valueOf("1"));
        defaults.add(net.minecraft.nbt.StringTag.valueOf(""));
        defaults.add(net.minecraft.nbt.StringTag.valueOf("extra"));
        marker.remove("unexpected");
        marker.put("defaults", defaults);
        CustomData.set(DataComponents.CUSTOM_DATA, stack, data);
        assertTrue(AdministrationFormMarker.read(stack).isEmpty());
    }

    private static ItemStack paper() {
        return new ItemStackTemplate(
                Holder.direct(Items.PAPER, DataComponentMap.builder().set(DataComponents.MAX_STACK_SIZE, 64).build()),
                1, DataComponentPatch.EMPTY).create();
    }
}
