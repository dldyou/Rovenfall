package org.dldyou.rovenfall.rpg;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.common.ModConfigSpec;

/** Server-owned limits for activity awards. */
public final class ActivityXpConfig {
    public static final int DEFAULT_MAX_AWARD = 100;
    public static final int DEFAULT_MAX_WINDOW_AWARDS = 20;
    public static final int DEFAULT_WINDOW_SECONDS = 60;
    public static final int DEFAULT_COOLDOWN_MILLIS = 1_000;
    public static final int DEFAULT_COMBAT_TARGET_XP_CAP = 10;
    public static final List<String> DEFAULT_EXPLORATION_ADVANCEMENTS = List.of(
            "minecraft:adventure/adventuring_time",
            "minecraft:nether/explore_nether");
    public static final ModConfigSpec SPEC;
    private static final ModConfigSpec.IntValue MAX_AWARD;
    private static final ModConfigSpec.IntValue MAX_WINDOW_AWARDS;
    private static final ModConfigSpec.IntValue WINDOW_SECONDS;
    private static final ModConfigSpec.IntValue COOLDOWN_MILLIS;
    private static final ModConfigSpec.IntValue COMBAT_TARGET_XP_CAP;
    private static final ModConfigSpec.ConfigValue<List<? extends String>> EXPLORATION_ADVANCEMENTS;

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
        COMBAT_TARGET_XP_CAP = builder.translation("config.rovenfall.rpg.activity_xp.combat_target_xp_cap")
                .defineInRange("rpg.activity_xp.combat_target_xp_cap", DEFAULT_COMBAT_TARGET_XP_CAP, 1, 10_000);
        EXPLORATION_ADVANCEMENTS = builder.translation(
                        "config.rovenfall.rpg.activity_xp.exploration_advancements")
                .defineListAllowEmpty("rpg.activity_xp.exploration_advancements", DEFAULT_EXPLORATION_ADVANCEMENTS,
                        DEFAULT_EXPLORATION_ADVANCEMENTS::getFirst, ActivityXpConfig::validIdentifier);
        SPEC = builder.build();
    }

    private ActivityXpConfig() {}

    static Limits limits() {
        try {
            return new Limits(MAX_AWARD.get(), MAX_WINDOW_AWARDS.get(), WINDOW_SECONDS.get() * 1_000L,
                    COOLDOWN_MILLIS.get(), COMBAT_TARGET_XP_CAP.get());
        } catch (IllegalStateException ignored) {
            return new Limits(DEFAULT_MAX_AWARD, DEFAULT_MAX_WINDOW_AWARDS,
                    DEFAULT_WINDOW_SECONDS * 1_000L, DEFAULT_COOLDOWN_MILLIS, DEFAULT_COMBAT_TARGET_XP_CAP);
        }
    }

    static boolean isExplorationAdvancement(Identifier advancementId) {
        return advancementId != null && explorationAdvancements().contains(advancementId);
    }

    static Set<Identifier> explorationAdvancements() {
        List<? extends String> configured;
        try {
            configured = EXPLORATION_ADVANCEMENTS.get();
        } catch (IllegalStateException ignored) {
            configured = DEFAULT_EXPLORATION_ADVANCEMENTS;
        }
        Set<Identifier> result = new LinkedHashSet<>();
        for (String value : configured) {
            try {
                result.add(Identifier.parse(value));
            } catch (RuntimeException ignored) {
                // The config validator rejects these; fail closed if an external source bypasses correction.
            }
        }
        return Set.copyOf(result);
    }

    private static boolean validIdentifier(Object value) {
        if (!(value instanceof String text)) {
            return false;
        }
        try {
            Identifier.parse(text);
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    record Limits(
            int maxAward,
            int maxWindowAwards,
            long windowMillis,
            long cooldownMillis,
            int combatTargetXpCap) {}
}
