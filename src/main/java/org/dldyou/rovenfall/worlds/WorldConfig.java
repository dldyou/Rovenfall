package org.dldyou.rovenfall.worlds;

import net.neoforged.fml.config.IConfigSpec;
import net.neoforged.neoforge.common.ModConfigSpec;

public final class WorldConfig {
    public static final boolean DEFAULT_HUB_PVP_ENABLED = false;
    public static final boolean DEFAULT_WILDERNESS_PVP_ENABLED = true;
    public static final int DEFAULT_PORTAL_ACTIVATION_RADIUS = 8;
    public static final int DEFAULT_PORTAL_SEARCH_RADIUS = 48;
    public static final int DEFAULT_PORTAL_COOLDOWN_SECONDS = 5;
    public static final int DEFAULT_PORTAL_COMBAT_LOCK_SECONDS = 10;
    public static final IConfigSpec SPEC;
    private static final ModConfigSpec.BooleanValue HUB_PVP_ENABLED;
    private static final ModConfigSpec.BooleanValue WILDERNESS_PVP_ENABLED;
    private static final ModConfigSpec.IntValue PORTAL_ACTIVATION_RADIUS;
    private static final ModConfigSpec.IntValue PORTAL_SEARCH_RADIUS;
    private static final ModConfigSpec.IntValue PORTAL_COOLDOWN_SECONDS;
    private static final ModConfigSpec.IntValue PORTAL_COMBAT_LOCK_SECONDS;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        HUB_PVP_ENABLED = builder
                .translation("config.rovenfall.worlds.hub_pvp_enabled")
                .define("worlds.hub_pvp_enabled", DEFAULT_HUB_PVP_ENABLED);
        WILDERNESS_PVP_ENABLED = builder
                .translation("config.rovenfall.worlds.wilderness_pvp_enabled")
                .define("worlds.wilderness_pvp_enabled", DEFAULT_WILDERNESS_PVP_ENABLED);
        PORTAL_ACTIVATION_RADIUS = builder
                .translation("config.rovenfall.worlds.portal_activation_radius")
                .defineInRange("worlds.portal_activation_radius", DEFAULT_PORTAL_ACTIVATION_RADIUS, 1, 64);
        PORTAL_SEARCH_RADIUS = builder
                .translation("config.rovenfall.worlds.portal_search_radius")
                .defineInRange(
                        "worlds.portal_search_radius",
                        DEFAULT_PORTAL_SEARCH_RADIUS,
                        0,
                        SafeArrivalResolver.MAX_SEARCH_RADIUS);
        PORTAL_COOLDOWN_SECONDS = builder
                .translation("config.rovenfall.worlds.portal_cooldown_seconds")
                .defineInRange("worlds.portal_cooldown_seconds", DEFAULT_PORTAL_COOLDOWN_SECONDS, 0, 3_600);
        PORTAL_COMBAT_LOCK_SECONDS = builder
                .translation("config.rovenfall.worlds.portal_combat_lock_seconds")
                .defineInRange(
                        "worlds.portal_combat_lock_seconds", DEFAULT_PORTAL_COMBAT_LOCK_SECONDS, 0, 300);
        SPEC = builder.build();
    }

    private WorldConfig() {
    }

    public static boolean hubPvpEnabled() {
        return value(HUB_PVP_ENABLED, DEFAULT_HUB_PVP_ENABLED);
    }

    public static boolean wildernessPvpEnabled() {
        return value(WILDERNESS_PVP_ENABLED, DEFAULT_WILDERNESS_PVP_ENABLED);
    }

    public static int portalActivationRadius() {
        return value(PORTAL_ACTIVATION_RADIUS, DEFAULT_PORTAL_ACTIVATION_RADIUS);
    }

    public static int portalSearchRadius() {
        return value(PORTAL_SEARCH_RADIUS, DEFAULT_PORTAL_SEARCH_RADIUS);
    }

    public static int portalCooldownSeconds() {
        return value(PORTAL_COOLDOWN_SECONDS, DEFAULT_PORTAL_COOLDOWN_SECONDS);
    }

    public static int portalCombatLockSeconds() {
        return value(PORTAL_COMBAT_LOCK_SECONDS, DEFAULT_PORTAL_COMBAT_LOCK_SECONDS);
    }

    private static boolean value(ModConfigSpec.BooleanValue configured, boolean fallback) {
        try {
            return configured.getAsBoolean();
        } catch (IllegalStateException exception) {
            return fallback;
        }
    }

    private static int value(ModConfigSpec.IntValue configured, int fallback) {
        try {
            return configured.getAsInt();
        } catch (IllegalStateException exception) {
            return fallback;
        }
    }
}
