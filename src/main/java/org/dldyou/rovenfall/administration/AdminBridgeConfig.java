package org.dldyou.rovenfall.administration;

import net.neoforged.fml.config.IConfigSpec;
import net.neoforged.neoforge.common.ModConfigSpec;

/** Local-only HTTP bridge settings for the external operations console. */
public final class AdminBridgeConfig {
    public static final boolean DEFAULT_ENABLED = true;
    public static final int DEFAULT_PORT = 8_765;
    public static final IConfigSpec SPEC;
    private static final ModConfigSpec.BooleanValue ENABLED;
    private static final ModConfigSpec.IntValue PORT;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        ENABLED = builder
                .comment("Serve the local Rovenfall administration console on 127.0.0.1.")
                .translation("config.rovenfall.admin_bridge.enabled")
                .define("admin_bridge.enabled", DEFAULT_ENABLED);
        PORT = builder
                .comment("Loopback TCP port used by the Rovenfall administration console.")
                .translation("config.rovenfall.admin_bridge.port")
                .defineInRange("admin_bridge.port", DEFAULT_PORT, 1_024, 65_535);
        SPEC = builder.build();
    }

    private AdminBridgeConfig() {
    }

    static boolean enabled() {
        try {
            return ENABLED.getAsBoolean();
        } catch (IllegalStateException exception) {
            return DEFAULT_ENABLED;
        }
    }

    static int port() {
        try {
            return PORT.getAsInt();
        } catch (IllegalStateException exception) {
            return DEFAULT_PORT;
        }
    }
}
