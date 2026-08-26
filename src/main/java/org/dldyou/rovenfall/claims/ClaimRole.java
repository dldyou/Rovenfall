package org.dldyou.rovenfall.claims;

import com.mojang.serialization.Codec;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import net.minecraft.util.StringRepresentable;

public enum ClaimRole implements StringRepresentable {
    OWNER("owner", 4),
    MANAGER("manager", 3),
    BUILDER("builder", 2),
    USER("user", 1),
    VISITOR("visitor", 0);

    public static final Codec<ClaimRole> CODEC = StringRepresentable.fromEnum(ClaimRole::values);
    private final String id;
    private final int authority;

    ClaimRole(String id, int authority) {
        this.id = id;
        this.authority = authority;
    }

    public boolean atLeast(ClaimRole required) {
        return authority >= required.authority;
    }

    public String translationKey() {
        return "claim_role.rovenfall." + id;
    }

    public static Optional<ClaimRole> fromId(String id) {
        if (id == null) {
            return Optional.empty();
        }
        String normalized = id.toLowerCase(Locale.ROOT);
        return Arrays.stream(values()).filter(role -> role.id.equals(normalized)).findFirst();
    }

    public static String[] ids() {
        return Arrays.stream(values()).filter(role -> role != OWNER).map(ClaimRole::getSerializedName)
                .toArray(String[]::new);
    }

    @Override
    public String getSerializedName() {
        return id;
    }
}
