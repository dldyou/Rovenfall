package org.dldyou.rovenfall.claims;

import net.neoforged.fml.config.IConfigSpec;
import net.neoforged.neoforge.common.ModConfigSpec;

public final class ClaimConfig {
    public static final long DEFAULT_BASE_PRICE = 1_000L;
    public static final long DEFAULT_PRICE_INCREASE = 250L;
    public static final int DEFAULT_OWNERSHIP_CAP = 64;
    public static final IConfigSpec SPEC;
    private static final ModConfigSpec.LongValue BASE_PRICE;
    private static final ModConfigSpec.LongValue PRICE_INCREASE;
    private static final ModConfigSpec.IntValue OWNERSHIP_CAP;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        BASE_PRICE = builder
                .translation("config.rovenfall.claims.base_price")
                .defineInRange("claims.base_price", DEFAULT_BASE_PRICE, 1L, Long.MAX_VALUE);
        PRICE_INCREASE = builder
                .translation("config.rovenfall.claims.price_increase")
                .defineInRange("claims.price_increase", DEFAULT_PRICE_INCREASE, 0L, Long.MAX_VALUE);
        OWNERSHIP_CAP = builder
                .translation("config.rovenfall.claims.ownership_cap")
                .defineInRange("claims.ownership_cap", DEFAULT_OWNERSHIP_CAP, 1, Claim.MAX_CLAIMS);
        SPEC = builder.build();
    }

    private ClaimConfig() {
    }

    public static long basePrice() {
        return value(BASE_PRICE, DEFAULT_BASE_PRICE);
    }

    public static long priceIncrease() {
        return value(PRICE_INCREASE, DEFAULT_PRICE_INCREASE);
    }

    public static int ownershipCap() {
        try {
            return OWNERSHIP_CAP.getAsInt();
        } catch (IllegalStateException exception) {
            return DEFAULT_OWNERSHIP_CAP;
        }
    }

    private static long value(ModConfigSpec.LongValue configured, long fallback) {
        try {
            return configured.getAsLong();
        } catch (IllegalStateException exception) {
            return fallback;
        }
    }
}
