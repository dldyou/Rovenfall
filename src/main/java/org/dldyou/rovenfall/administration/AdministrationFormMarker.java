package org.dldyou.rovenfall.administration;

import java.util.List;
import java.util.Optional;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/** Stores a bounded form type and defaults on a presentation item without replacing unrelated custom data. */
public record AdministrationFormMarker(AdministrationFormType type, List<String> defaults) {
    private static final String MARKER_KEY = "rovenfall_admin_form";
    private static final String SEARCH_KEY = "rovenfall_admin_search";
    private static final String ERROR_KEY = "rovenfall_admin_form_error";
    private static final String TYPE_KEY = "type";
    private static final String DEFAULTS_KEY = "defaults";

    public AdministrationFormMarker {
        defaults = defaults == null ? List.of() : List.copyOf(defaults);
        if (type == null || !type.accepts(defaults)) {
            throw new IllegalArgumentException("Invalid administration form marker");
        }
    }

    public static boolean write(ItemStack stack, AdministrationFormMarker marker) {
        if (stack == null || stack.isEmpty() || marker == null) {
            return false;
        }
        CompoundTag data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        CompoundTag encoded = new CompoundTag();
        encoded.putString(TYPE_KEY, marker.type.wireId());
        ListTag defaults = new ListTag();
        for (String value : marker.defaults) {
            if (value.length() > AdministrationStructuredFormCodec.MAX_FIELD_LENGTH) {
                return false;
            }
            defaults.add(StringTag.valueOf(value));
        }
        encoded.put(DEFAULTS_KEY, defaults);
        data.put(MARKER_KEY, encoded);
        CustomData.set(DataComponents.CUSTOM_DATA, stack, data);
        return true;
    }

    public static Optional<AdministrationFormMarker> read(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return Optional.empty();
        }
        CompoundTag data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        Optional<CompoundTag> encoded = data.getCompound(MARKER_KEY);
        if (encoded.isEmpty() || encoded.orElseThrow().size() != 2) {
            return Optional.empty();
        }
        CompoundTag marker = encoded.orElseThrow();
        Optional<String> wireId = marker.getString(TYPE_KEY);
        Optional<ListTag> encodedDefaults = marker.getList(DEFAULTS_KEY);
        if (wireId.isEmpty() || encodedDefaults.isEmpty()) {
            return Optional.empty();
        }
        Optional<AdministrationFormType> type = AdministrationFormType.fromWireId(wireId.orElseThrow());
        if (type.isEmpty() || encodedDefaults.orElseThrow().size() != type.orElseThrow().fields().size()) {
            return Optional.empty();
        }
        java.util.ArrayList<String> defaults = new java.util.ArrayList<>(encodedDefaults.orElseThrow().size());
        for (Tag tag : encodedDefaults.orElseThrow()) {
            if (!(tag instanceof StringTag stringTag) || stringTag.value().length() > AdministrationStructuredFormCodec.MAX_FIELD_LENGTH) {
                return Optional.empty();
            }
            defaults.add(stringTag.value());
        }
        if (!type.orElseThrow().accepts(defaults)) {
            return Optional.empty();
        }
        return Optional.of(new AdministrationFormMarker(type.orElseThrow(), defaults));
    }

    public static boolean writeSearch(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        CompoundTag data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        data.putString(SEARCH_KEY, "1");
        CustomData.set(DataComponents.CUSTOM_DATA, stack, data);
        return true;
    }

    public static boolean hasSearch(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        return stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY)
                .copyTag().getString(SEARCH_KEY).filter("1"::equals).isPresent();
    }

    public static boolean writeError(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        CompoundTag data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        data.putString(ERROR_KEY, "1");
        CustomData.set(DataComponents.CUSTOM_DATA, stack, data);
        return true;
    }

    public static boolean hasError(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        return stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY)
                .copyTag().getString(ERROR_KEY).filter("1"::equals).isPresent();
    }
}
