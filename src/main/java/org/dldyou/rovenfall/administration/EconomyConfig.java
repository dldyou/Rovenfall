package org.dldyou.rovenfall.administration;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class EconomyConfig {
    public static final long DEFAULT_INITIAL_BALANCE = 0L;
    public static final long DEFAULT_MAXIMUM_BALANCE = Long.MAX_VALUE;
    public static final ModConfigSpec SPEC;
    private static final ModConfigSpec.LongValue INITIAL_BALANCE;
    private static final ModConfigSpec.LongValue MAXIMUM_BALANCE;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        INITIAL_BALANCE = builder
                .translation("config.rovenfall.economy.initial_balance")
                .defineInRange("economy.initial_balance", DEFAULT_INITIAL_BALANCE, 0L, Long.MAX_VALUE);
        MAXIMUM_BALANCE = builder
                .translation("config.rovenfall.economy.maximum_balance")
                .defineInRange("economy.maximum_balance", DEFAULT_MAXIMUM_BALANCE, 0L, Long.MAX_VALUE);
        SPEC = builder.build();
    }

    private EconomyConfig() {
    }

    public static long initialBalance() {
        return INITIAL_BALANCE.getAsLong();
    }

    public static long maximumBalance() {
        return MAXIMUM_BALANCE.getAsLong();
    }
}
