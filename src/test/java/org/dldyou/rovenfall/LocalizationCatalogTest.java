package org.dldyou.rovenfall;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
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
            "command.rovenfall.admin.audit.header",
            "gui.rovenfall.admin.audit.summary",
            "gui.rovenfall.admin.audit.reason",
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
            "boss_pattern.rovenfall.rift_warden.summon"
    );

    @Test
    void supportedLanguageCatalogsHaveEqualKeySets() {
        Set<String> english = keys("en_us");
        assertEquals(english, keys("ko_kr"));
        assertEquals(english, keys("ja_jp"));
        assertTrue(english.containsAll(REQUIRED_KEYS));
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
