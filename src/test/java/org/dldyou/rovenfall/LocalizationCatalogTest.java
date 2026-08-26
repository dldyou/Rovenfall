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
            "command.rovenfall.admin.audit.header",
            "gui.rovenfall.admin.audit.summary",
            "gui.rovenfall.admin.audit.reason"
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
