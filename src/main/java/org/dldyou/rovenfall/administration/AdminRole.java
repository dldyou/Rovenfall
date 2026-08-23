package org.dldyou.rovenfall.administration;

import com.mojang.serialization.Codec;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import net.minecraft.util.StringRepresentable;

public enum AdminRole implements StringRepresentable {
    VIEWER("viewer"),
    MODERATOR("moderator"),
    ECONOMY_MANAGER("economy_manager"),
    CONTENT_MANAGER("content_manager"),
    OWNER("owner");

    public static final Codec<AdminRole> CODEC = StringRepresentable.fromEnum(AdminRole::values);

    private final String id;

    AdminRole(String id) {
        this.id = id;
    }

    public static Optional<AdminRole> parse(String id) {
        return Arrays.stream(values()).filter(role -> role.id.equals(id)).findFirst();
    }

    public static List<String> ids() {
        return Arrays.stream(values()).map(AdminRole::getSerializedName).toList();
    }

    @Override
    public String getSerializedName() {
        return id;
    }

    public String translationKey() {
        return "admin_role.rovenfall." + id;
    }
}
