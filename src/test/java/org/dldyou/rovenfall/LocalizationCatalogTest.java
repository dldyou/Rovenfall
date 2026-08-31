package org.dldyou.rovenfall;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

final class LocalizationCatalogTest {
    private static final Set<String> SUPPORTED_LOCALES = Set.of("en_us", "ko_kr", "ja_jp");
    private static final Pattern PLACEHOLDER = Pattern.compile("%(?:\\d+\\$)?[a-zA-Z]");
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
            "quest.rovenfall.first_steps",
            "quest.rovenfall.first_steps.description",
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
            "economy_transaction_kind.rovenfall.rpg_skill_payment",
            "economy_transaction_kind.rovenfall.boss_reward",
            "gui.rovenfall.player.title",
            "gui.rovenfall.player.home",
            "gui.rovenfall.player.previous",
            "gui.rovenfall.player.next",
            "gui.rovenfall.player.page",
            "gui.rovenfall.player.confirm",
            "gui.rovenfall.player.cancel",
            "gui.rovenfall.player.rate_limit",
            "gui.rovenfall.player.unknown_player",
            "gui.rovenfall.player.unknown_shop",
            "gui.rovenfall.player.unknown_activity",
            "gui.rovenfall.player.unknown_career",
            "gui.rovenfall.player.unknown_skill",
            "gui.rovenfall.player.unknown_item",
            "gui.rovenfall.inventory.inventory",
            "gui.rovenfall.inventory.overview",
            "gui.rovenfall.inventory.claims",
            "gui.rovenfall.inventory.skills",
            "gui.rovenfall.inventory.shops",
            "gui.rovenfall.inventory.admin",
            "gui.rovenfall.inventory.journey",
            "gui.rovenfall.inventory.current_tab",
            "gui.rovenfall.inventory.open_tab",
            "gui.rovenfall.menu.slot_position",
            "gui.rovenfall.menu.keyboard_usage",
            "gui.rovenfall.menu.card_position",
            "gui.rovenfall.menu.custom_keyboard_usage",
            "gui.rovenfall.claim.actions_locked",
            "gui.rovenfall.claim.owner_or_manager_required",
            "gui.rovenfall.claim.current_land",
            "gui.rovenfall.claim.current_location",
            "gui.rovenfall.claim.purchase_complete",
            "gui.rovenfall.claim.purchase_duplicate",
            "gui.rovenfall.claim.action_duplicate",
            "gui.rovenfall.shop.title",
            "gui.rovenfall.shop.confirm_title",
            "gui.rovenfall.shop.stock.unlimited",
            "gui.rovenfall.shop.binding.nearby",
            "gui.rovenfall.shop.result.buy",
            "gui.rovenfall.shop.result.sell",
            "gui.rovenfall.shop.result.duplicate",
            "gui.rovenfall.claim.title",
            "gui.rovenfall.claim.error.stale",
            "gui.rovenfall.claim.error.rate_limit",
            "gui.rovenfall.rpg.title",
            "gui.rovenfall.rpg.summary",
            "gui.rovenfall.rpg.unavailable_content",
            "gui.rovenfall.rpg.skill.ready",
            "gui.rovenfall.rpg.confirm.target",
            "gui.rovenfall.rpg.result.stale",
            "gui.rovenfall.rpg.result.rate_limit",
            "gui.rovenfall.rpg.result.pending",
            "gui.rovenfall.quest.title",
            "gui.rovenfall.quest.dashboard",
            "gui.rovenfall.quest.summary",
            "gui.rovenfall.quest.empty",
            "gui.rovenfall.quest.read_only",
            "gui.rovenfall.quest.status.available",
            "gui.rovenfall.quest.status.in_progress",
            "gui.rovenfall.quest.status.locked",
            "gui.rovenfall.quest.status.reward_pending",
            "gui.rovenfall.quest.status.completed",
            "gui.rovenfall.quest.status.unresolved",
            "gui.rovenfall.quest.status.definition_changed",
            "gui.rovenfall.quest.prerequisite",
            "gui.rovenfall.quest.reward.currency",
            "gui.rovenfall.quest.reward.activity_xp",
            "gui.rovenfall.quest.reward.unavailable",
            "gui.rovenfall.quest.next_step.none",
            "gui.rovenfall.quest.guide",
            "gui.rovenfall.quest.stale",
            "gui.rovenfall.quest.objective.activity",
            "gui.rovenfall.quest.objective.shop_trade",
            "gui.rovenfall.quest.objective.claim_purchase",
            "gui.rovenfall.quest.objective.boss_defeat",
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
            "/data/rovenfall/rovenfall/mob_content/foundation.json",
            "/data/rovenfall/rovenfall/quests/first_steps.json"
    );
    private static final Set<String> COMPACT_PLAYER_GUI_KEYS = Set.of(
            "gui.rovenfall.inventory.inventory",
            "gui.rovenfall.inventory.overview",
            "gui.rovenfall.inventory.claims",
            "gui.rovenfall.inventory.skills",
            "gui.rovenfall.inventory.shops",
            "gui.rovenfall.inventory.admin",
            "gui.rovenfall.inventory.journey",
            "gui.rovenfall.player.back",
            "gui.rovenfall.player.refresh",
            "gui.rovenfall.player.previous",
            "gui.rovenfall.player.next",
            "gui.rovenfall.player.confirm",
            "gui.rovenfall.player.cancel");
    private static final Set<String> TECHNICAL_GUI_KEYS = Set.of(
            "gui.rovenfall.admin.audit.transaction",
            "gui.rovenfall.admin.economy.field.transaction",
            "gui.rovenfall.admin.economy.field.uuid",
            "gui.rovenfall.admin.economy.preview.transaction",
            "gui.rovenfall.admin.operations.evidence",
            "gui.rovenfall.admin.rpg_boss.field.definition_id",
            "gui.rovenfall.admin.rpg_boss.field.encounter",
            "gui.rovenfall.admin.rpg_boss.field.transaction",
            "gui.rovenfall.admin.rpg_boss.field.uuid",
            "gui.rovenfall.admin.rpg_boss.preview.transaction",
            "gui.rovenfall.admin.world.field.area",
            "gui.rovenfall.admin.world.field.bounds",
            "gui.rovenfall.admin.world.field.radius",
            "gui.rovenfall.admin.world.field.transaction",
            "gui.rovenfall.admin.world.field.warning_value",
            "gui.rovenfall.admin.world.preview.transaction",
            "gui.rovenfall.quest.technical.quest_id",
            "gui.rovenfall.quest.technical.objective_id");

    @Test
    void supportedLanguageCatalogsHaveEqualKeySets() {
        Set<String> english = keys("en_us");
        assertEquals(english, keys("ko_kr"));
        assertEquals(english, keys("ja_jp"));
        assertTrue(english.containsAll(REQUIRED_KEYS));
    }

    @Test
    void supportedLanguageCatalogsHaveEqualPlaceholderSets() {
        var english = catalog("en_us");
        for (String locale : Set.of("ko_kr", "ja_jp")) {
            var localized = catalog(locale);
            for (String key : english.keySet()) {
                assertEquals(
                        placeholders(english.get(key).getAsString()),
                        placeholders(localized.get(key).getAsString()),
                        locale + " has different placeholders for " + key);
            }
        }
    }

    @Test
    void supportedLanguageCatalogsDoNotDeclareDuplicateKeys() {
        for (String locale : SUPPORTED_LOCALES) {
            String path = "/assets/rovenfall/lang/" + locale + ".json";
            var stream = LocalizationCatalogTest.class.getResourceAsStream(path);
            assertNotNull(stream, path);
            try (stream) {
                String json = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                var matcher = java.util.regex.Pattern.compile("(?m)^\\s*\"([^\"]+)\"\\s*:").matcher(json);
                Set<String> seen = new HashSet<>();
                while (matcher.find()) {
                    assertTrue(seen.add(matcher.group(1)),
                            locale + " declares a duplicate localization key: " + matcher.group(1));
                }
            } catch (java.io.IOException exception) {
                throw new AssertionError(exception);
            }
        }
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
        for (String locale : SUPPORTED_LOCALES) {
            Set<String> missing = new HashSet<>(definitionKeys);
            missing.removeAll(keys(locale));
            assertTrue(missing.isEmpty(), locale + " is missing shipped definition keys: " + missing);
        }
    }

    @Test
    void playerGuiLabelsArePresentAndCompactInEveryCatalog() {
        for (String locale : SUPPORTED_LOCALES) {
            var catalog = catalog(locale);
            catalog.entrySet().stream()
                    .filter(entry -> entry.getKey().startsWith("gui.rovenfall."))
                    .forEach(entry -> assertTrue(
                            entry.getValue().isJsonPrimitive()
                                    && !entry.getValue().getAsString().isBlank(),
                            locale + " has a blank GUI label: " + entry.getKey()));
            for (String key : COMPACT_PLAYER_GUI_KEYS) {
                String label = catalog.get(key).getAsString();
                assertTrue(label.codePointCount(0, label.length()) <= 18,
                        locale + " player GUI label is too long: " + key + " = " + label);
            }
        }
    }

    @Test
    void ordinaryGuiCatalogsUseNaturalTermsWithoutInternalJargon() {
        Set<String> removedIdentityKeys = Set.of(
                "gui.rovenfall.player.current_chunk",
                "gui.rovenfall.player.claim_location",
                "gui.rovenfall.claim.player_uuid",
                "gui.rovenfall.shop.offer_id",
                "gui.rovenfall.shop.binding",
                "gui.rovenfall.rpg.definition_revision",
                "gui.rovenfall.rpg.unresolved");
        for (String locale : SUPPORTED_LOCALES) {
            var catalog = catalog(locale);
            for (String key : removedIdentityKeys) {
                assertTrue(!catalog.has(key), locale + " still exposes internal player-GUI data: " + key);
            }
        }

        var korean = catalog("ko_kr");
        assertEquals("토지", korean.get("gui.rovenfall.player.claims").getAsString());
        assertEquals("토지", korean.get("gui.rovenfall.admin.domain.claims").getAsString());
        assertEquals("야생 관리", korean.get("gui.rovenfall.admin.world.wilderness").getAsString());
        assertEquals("처리 기록", korean.get("gui.rovenfall.admin.domain.audit").getAsString());
        assertEquals("운영 상태", korean.get("gui.rovenfall.admin.domain.metrics").getAsString());
        assertEquals("기술 정보", korean.get("gui.rovenfall.admin.advanced").getAsString());
        assertEquals("여정", korean.get("gui.rovenfall.inventory.journey").getAsString());
        assertEquals("다음 할 일", korean.get("gui.rovenfall.quest.dashboard").getAsString());

        var english = catalog("en_us");
        assertEquals("Land", english.get("gui.rovenfall.player.claims").getAsString());
        assertEquals("Wilderness Management", english.get("gui.rovenfall.admin.world.wilderness").getAsString());
        assertEquals("Technical information", english.get("gui.rovenfall.admin.advanced").getAsString());
        assertEquals("Journey", english.get("gui.rovenfall.inventory.journey").getAsString());
        assertEquals("Next Step", english.get("gui.rovenfall.quest.dashboard").getAsString());

        var japanese = catalog("ja_jp");
        assertEquals("土地", japanese.get("gui.rovenfall.player.claims").getAsString());
        assertEquals("荒野の管理", japanese.get("gui.rovenfall.admin.world.wilderness").getAsString());
        assertEquals("技術情報", japanese.get("gui.rovenfall.admin.advanced").getAsString());
        assertEquals("旅路", japanese.get("gui.rovenfall.inventory.journey").getAsString());
        assertEquals("次にすること", japanese.get("gui.rovenfall.quest.dashboard").getAsString());

        assertOrdinaryGuiAvoids(
                "ko_kr",
                Pattern.compile("영지|클레임|황무지|청크|UUID|(?<![A-Za-z_])ID(?![A-Za-z_])|리비전|수명 주기"));
        assertOrdinaryGuiAvoids(
                "en_us",
                Pattern.compile("(?i)\\bclaims?\\b|\\bchunk\\b|\\bUUID\\b|\\bID\\b|\\brevision\\b|\\blifecycle\\b"));
        assertOrdinaryGuiAvoids(
                "ja_jp",
                Pattern.compile("クレーム|チャンク|Wilderness|UUID|(?<![A-Za-z_])ID(?![A-Za-z_])|リビジョン|ライフサイクル"));
    }

    private static void assertOrdinaryGuiAvoids(String locale, Pattern rejectedTerms) {
        catalog(locale).entrySet().stream()
                .filter(entry -> entry.getKey().startsWith("gui.rovenfall."))
                .filter(entry -> !entry.getKey().contains(".form."))
                .filter(entry -> !TECHNICAL_GUI_KEYS.contains(entry.getKey()))
                .forEach(entry -> assertTrue(
                        !rejectedTerms.matcher(entry.getValue().getAsString()).find(),
                        locale + " GUI uses an internal or rejected term: "
                                + entry.getKey() + " = " + entry.getValue().getAsString()));
    }

    private static List<String> placeholders(String value) {
        var placeholders = new ArrayList<String>();
        var matcher = PLACEHOLDER.matcher(value);
        while (matcher.find()) {
            placeholders.add(matcher.group());
        }
        placeholders.sort(String::compareTo);
        return placeholders;
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
        return catalog(locale).keySet();
    }

    private static com.google.gson.JsonObject catalog(String locale) {
        String path = "/assets/rovenfall/lang/" + locale + ".json";
        var stream = LocalizationCatalogTest.class.getResourceAsStream(path);
        assertNotNull(stream, path);
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (java.io.IOException exception) {
            throw new AssertionError(exception);
        }
    }
}
