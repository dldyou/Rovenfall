package org.dldyou.rovenfall.rpg;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Server-owned limits for activity awards. */
public final class ActivityXpConfig {
    public static final int DEFAULT_MAX_AWARD = 100;
    public static final int DEFAULT_MAX_WINDOW_AWARDS = 20;
    public static final int DEFAULT_WINDOW_SECONDS = 60;
    public static final int DEFAULT_COOLDOWN_MILLIS = 1_000;
    public static final ModConfigSpec SPEC;
    private static final ModConfigSpec.IntValue MAX_AWARD;
    private static final ModConfigSpec.IntValue MAX_WINDOW_AWARDS;
    private static final ModConfigSpec.IntValue WINDOW_SECONDS;
    private static final ModConfigSpec.IntValue COOLDOWN_MILLIS;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        MAX_AWARD = builder.translation("config.rovenfall.rpg.activity_xp.max_award")
                .defineInRange("rpg.activity_xp.max_award", DEFAULT_MAX_AWARD, 1, Integer.MAX_VALUE);
        MAX_WINDOW_AWARDS = builder.translation("config.rovenfall.rpg.activity_xp.max_window_awards")
                .defineInRange("rpg.activity_xp.max_window_awards", DEFAULT_MAX_WINDOW_AWARDS, 1, RpgPlayerState.MAX_PROVENANCE);
        WINDOW_SECONDS = builder.translation("config.rovenfall.rpg.activity_xp.window_seconds")
                .defineInRange("rpg.activity_xp.window_seconds", DEFAULT_WINDOW_SECONDS, 1, 86_400);
        COOLDOWN_MILLIS = builder.translation("config.rovenfall.rpg.activity_xp.cooldown_millis")
                .defineInRange("rpg.activity_xp.cooldown_millis", DEFAULT_COOLDOWN_MILLIS, 0, 86_400_000);
        SPEC = builder.build();
    }

    private ActivityXpConfig() {}

    static Limits limits() {
        try {
            return new Limits(MAX_AWARD.get(), MAX_WINDOW_AWARDS.get(), WINDOW_SECONDS.get() * 1_000L, COOLDOWN_MILLIS.get());
        } catch (IllegalStateException ignored) {
            return new Limits(DEFAULT_MAX_AWARD, DEFAULT_MAX_WINDOW_AWARDS, DEFAULT_WINDOW_SECONDS * 1_000L, DEFAULT_COOLDOWN_MILLIS);
        }
    }

    record Limits(int maxAward, int maxWindowAwards, long windowMillis, long cooldownMillis) {}
}
