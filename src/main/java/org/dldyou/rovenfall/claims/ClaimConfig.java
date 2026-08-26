package org.dldyou.rovenfall.claims;

import net.neoforged.fml.config.IConfigSpec;
import net.neoforged.neoforge.common.ModConfigSpec;

public final class ClaimConfig {
    public static final long DEFAULT_BASE_PRICE = 1_000L;
    public static final long DEFAULT_PRICE_INCREASE = 250L;
    public static final int DEFAULT_OWNERSHIP_CAP = 64;
    public static final int DEFAULT_SALE_REFUND_PERCENT = 50;
    public static final int DEFAULT_PROTECTED_SPAWN_RADIUS_CHUNKS = 2;
    public static final IConfigSpec SPEC;
    private static final ModConfigSpec.LongValue BASE_PRICE;
    private static final ModConfigSpec.LongValue PRICE_INCREASE;
    private static final ModConfigSpec.IntValue OWNERSHIP_CAP;
    private static final ModConfigSpec.IntValue SALE_REFUND_PERCENT;
    private static final ModConfigSpec.IntValue PROTECTED_SPAWN_RADIUS_CHUNKS;

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
        SALE_REFUND_PERCENT = builder
                .translation("config.rovenfall.claims.sale_refund_percent")
                .defineInRange("claims.sale_refund_percent", DEFAULT_SALE_REFUND_PERCENT, 0, 100);
        PROTECTED_SPAWN_RADIUS_CHUNKS = builder
                .translation("config.rovenfall.claims.protected_spawn_radius_chunks")
                .defineInRange("claims.protected_spawn_radius_chunks", DEFAULT_PROTECTED_SPAWN_RADIUS_CHUNKS, 0, 64);
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

    public static int saleRefundPercent() {
        try {
            return SALE_REFUND_PERCENT.getAsInt();
        } catch (IllegalStateException exception) {
            return DEFAULT_SALE_REFUND_PERCENT;
        }
    }

    public static int protectedSpawnRadiusChunks() {
        try {
            return PROTECTED_SPAWN_RADIUS_CHUNKS.getAsInt();
        } catch (IllegalStateException exception) {
            return DEFAULT_PROTECTED_SPAWN_RADIUS_CHUNKS;
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
