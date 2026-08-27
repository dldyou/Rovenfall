package org.dldyou.rovenfall;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class LocalizationCatalogTest {
    private static final Set<String> REQUIRED_KEYS = Set.of(
            "admin_role.rovenfall.viewer",
            "admin_role.rovenfall.moderator",
            "admin_role.rovenfall.economy_manager",
            "admin_role.rovenfall.content_manager",
            "admin_role.rovenfall.owner",
            "shop_template.rovenfall.foundation",
            "command.rovenfall.admin.role.set.success",
            "command.rovenfall.admin.economy.grant.success",
            "command.rovenfall.admin.economy.error.insufficient_funds",
            "command.rovenfall.admin.shop.success",
            "command.rovenfall.admin.shop.error.invalid_request",
            "command.rovenfall.admin.snapshot.error.dependency_locked",
            "command.rovenfall.admin.snapshot.restore.duplicate",
            "command.rovenfall.admin.snapshot.error.invalid_transaction",
            "config.rovenfall.economy.initial_balance",
            "config.rovenfall.claims.sale_refund_percent",
            "config.rovenfall.claims.protected_spawn_radius_chunks",
            "claim_role.rovenfall.manager",
            "economy_transaction_kind.rovenfall.claim_sale",
            "command.rovenfall.claim.transfer.accept.success",
            "command.rovenfall.claim.sell.success",
            "activity.rovenfall.combat",
            "activity.rovenfall.cooking",
            "activity.rovenfall.mining",
            "activity.rovenfall.exploration",
            "activity.rovenfall.hunting",
            "activity.rovenfall.building",
            "activity.rovenfall.farming",
            "career.rovenfall.novice",
            "career.rovenfall.warrior",
            "career.rovenfall.guardian",
            "career.rovenfall.berserker",
            "skill.rovenfall.sturdy_body",
            "skill.rovenfall.power_strike",
            "skill.rovenfall.shield_wall",
            "skill.rovenfall.battle_fury",
            "message.rovenfall.claim.denied.build",
            "message.rovenfall.claim.denied.interact",
            "message.rovenfall.claim.denied.entity",
            "message.rovenfall.claim.denied.entry",
            "command.rovenfall.admin.region.create.success",
            "command.rovenfall.admin.region.edit.success",
            "command.rovenfall.admin.region.delete.success",
            "command.rovenfall.admin.region.info",
            "command.rovenfall.admin.region.list.header",
            "command.rovenfall.admin.region.list.entry",
            "command.rovenfall.admin.region.error.invalid_request",
            "command.rovenfall.admin.region.error.unauthorized",
            "command.rovenfall.admin.region.error.already_exists",
            "command.rovenfall.admin.region.error.not_found",
            "command.rovenfall.admin.region.error.limit",
            "command.rovenfall.admin.region.error.dependency_locked",
            "command.rovenfall.portal.create.success",
            "command.rovenfall.portal.edit.success",
            "command.rovenfall.portal.delete.success",
            "command.rovenfall.portal.info",
            "command.rovenfall.portal.error.unauthorized",
            "portal.rovenfall.travel.success",
            "portal.rovenfall.travel.error.cooldown",
            "portal.rovenfall.travel.error.combat_locked",
            "portal.rovenfall.travel.error.unsafe_destination",
            "command.rovenfall.admin.audit.header",
            "command.rovenfall.admin.audit.error.invalid_query",
            "command.rovenfall.admin.audit.export.success",
            "command.rovenfall.admin.audit.export.error.limit",
            "gui.rovenfall.admin.audit.summary",
            "gui.rovenfall.admin.audit.reason",
            "gui.rovenfall.admin.operations.summary",
            "gui.rovenfall.player.title",
            "gui.rovenfall.player.home",
            "gui.rovenfall.player.unavailable",
            "gui.rovenfall.shop.title",
            "gui.rovenfall.shop.confirm_title",
            "gui.rovenfall.shop.stock.unlimited",
            "command.rovenfall.admin.operations.summary",
            "command.rovenfall.admin.operations.anomalies",
            "command.rovenfall.admin.help.header",
            "command.rovenfall.admin.help.diagnostics",
            "command.rovenfall.admin.help.privacy",
            "command.rovenfall.admin.help.role.viewer",
            "command.rovenfall.admin.help.role.moderator",
            "command.rovenfall.admin.help.role.economy_manager",
            "command.rovenfall.admin.help.role.content_manager",
            "command.rovenfall.admin.help.role.owner",
            "mob.rovenfall.grove_stalker",
            "mob.rovenfall.orebound_beetle",
            "mob.rovenfall.rift_warden_vessel",
            "mutation.rovenfall.volatile",
            "mutation_marker.rovenfall.volatile",
            "boss.rovenfall.rift_warden",
            "boss_phase.rovenfall.rift_warden.one",
            "boss_phase.rovenfall.rift_warden.two",
            "boss_pattern.rovenfall.rift_warden.sweep",
            "boss_pattern.rovenfall.rift_warden.barrage",
            "boss_pattern.rovenfall.rift_warden.shockwave",
            "boss_pattern.rovenfall.rift_warden.summon",
            "message.rovenfall.boss.started",
            "message.rovenfall.boss.phase",
            "message.rovenfall.boss.telegraph",
            "message.rovenfall.boss.ended",
            "message.rovenfall.boss.reward_received",
            "command.rovenfall.admin.boss.list.header",
            "command.rovenfall.admin.boss.info",
            "command.rovenfall.admin.boss.participants.header",
            "command.rovenfall.admin.boss.rewards.header",
            "command.rovenfall.admin.boss.cooldowns.header",
            "command.rovenfall.admin.boss.mutations.header",
            "command.rovenfall.admin.boss.mutations.truncated",
            "command.rovenfall.admin.boss.mutation.success",
            "command.rovenfall.admin.boss.error.rewards_pending",
            "command.rovenfall.admin.boss.error.recovery_pending",
            "command.rovenfall.admin.boss.error.transaction_conflict"
    );
    private static final Set<String> BALANCE_DEFINITION_PATHS = Set.of(
            "/data/rovenfall/rovenfall/activities/building.json",
            "/data/rovenfall/rovenfall/activities/combat.json",
            "/data/rovenfall/rovenfall/activities/cooking.json",
            "/data/rovenfall/rovenfall/activities/exploration.json",
            "/data/rovenfall/rovenfall/activities/farming.json",
            "/data/rovenfall/rovenfall/activities/hunting.json",
            "/data/rovenfall/rovenfall/activities/mining.json",
            "/data/rovenfall/rovenfall/careers/berserker.json",
            "/data/rovenfall/rovenfall/careers/guardian.json",
            "/data/rovenfall/rovenfall/careers/novice.json",
            "/data/rovenfall/rovenfall/careers/warrior.json",
            "/data/rovenfall/rovenfall/skills/battle_fury.json",
            "/data/rovenfall/rovenfall/skills/power_strike.json",
            "/data/rovenfall/rovenfall/skills/shield_wall.json",
            "/data/rovenfall/rovenfall/skills/sturdy_body.json",
            "/data/rovenfall/rovenfall/shop_templates/foundation.json",
            "/data/rovenfall/rovenfall/mob_content/foundation.json"
    );

    @Test
    void supportedLanguageCatalogsHaveEqualKeySets() {
        Set<String> english = keys("en_us");
        assertEquals(english, keys("ko_kr"));
        assertEquals(english, keys("ja_jp"));
        assertTrue(english.containsAll(REQUIRED_KEYS));
    }

    @Test
    void shippedBalanceDefinitionTranslationKeysExistInEveryCatalog() {
        Set<String> definitionKeys = new HashSet<>();
        for (String path : BALANCE_DEFINITION_PATHS) {
            var stream = LocalizationCatalogTest.class.getResourceAsStream(path);
            assertNotNull(stream, path);
            try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                collectTranslationKeys(JsonParser.parseReader(reader), definitionKeys);
            } catch (java.io.IOException exception) {
                throw new AssertionError(exception);
            }
        }
        assertTrue(!definitionKeys.isEmpty(), "No shipped translation keys were discovered");
        for (String locale : Set.of("en_us", "ko_kr", "ja_jp")) {
            Set<String> missing = new HashSet<>(definitionKeys);
            missing.removeAll(keys(locale));
            assertTrue(missing.isEmpty(), locale + " is missing shipped definition keys: " + missing);
        }
    }

    private static void collectTranslationKeys(com.google.gson.JsonElement element, Set<String> keys) {
        if (element.isJsonArray()) {
            element.getAsJsonArray().forEach(child -> collectTranslationKeys(child, keys));
            return;
        }
        if (!element.isJsonObject()) {
            return;
        }
        for (var entry : element.getAsJsonObject().entrySet()) {
            if (entry.getKey().endsWith("translation_key") && entry.getValue().isJsonPrimitive()) {
                keys.add(entry.getValue().getAsString());
            }
            collectTranslationKeys(entry.getValue(), keys);
        }
    }

    private static Set<String> keys(String locale) {
        String path = "/assets/rovenfall/lang/" + locale + ".json";
        var stream = LocalizationCatalogTest.class.getResourceAsStream(path);
        assertNotNull(stream, path);
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject().keySet();
        } catch (java.io.IOException exception) {
            throw new AssertionError(exception);
        }
    }
}
