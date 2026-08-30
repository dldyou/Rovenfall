package org.dldyou.rovenfall.administration;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.dldyou.rovenfall.economy.ShopInstance;
import org.dldyou.rovenfall.economy.ShopTemplateSnapshot;
import org.dldyou.rovenfall.rpg.RpgPlayerState;
import org.dldyou.rovenfall.world.PortalDefinition;

/**
 * Wire-stable, client-facing descriptions of the ordinary values collected by an operator form.
 *
 * <p>Stable identifiers such as player UUIDs, definition IDs, and runtime IDs intentionally do
 * not appear here. The server menu owns those selections; this type only describes values a
 * person can set with a normal control.</p>
 */
public enum AdministrationFormType {
    ECONOMY_GRANT("economy-grant", fields(integer("amount", 1, Long.MAX_VALUE, "1"), reason())),
    ECONOMY_DEBIT("economy-debit", fields(integer("amount", 1, Long.MAX_VALUE, "1"), reason())),
    ECONOMY_SHOP_CREATE("economy-shop-create", fields(reason())),
    ECONOMY_SHOP_DELETE("economy-shop-delete", fields(reason())),
    ECONOMY_SHOP_BIND_HERE("economy-shop-bind-here", fields(reason())),
    ECONOMY_SHOP_UNBIND("economy-shop-unbind", fields(reason())),
    ECONOMY_SHOP_ACCESS("economy-shop-access", fields(integer(
            "access_distance", 1, ShopInstance.MAX_ACCESS_DISTANCE, "16"), reason())),
    ECONOMY_OFFER_UPSERT("economy-offer-upsert", fields(
            select("trade_direction", "both", "buy", "sell", "both"),
            optionalInteger("buy_price", 1, ShopTemplateSnapshot.MAX_PRICE, ""),
            optionalInteger("sell_price", 1, ShopTemplateSnapshot.MAX_PRICE, ""),
            select("stock_mode", "unlimited", "unlimited", "finite"),
            optionalInteger("stock", 0, ShopTemplateSnapshot.MAX_STOCK, ""),
            optionalInteger("maximum_stock", 0, ShopTemplateSnapshot.MAX_STOCK, ""),
            integer("count", 1, 99, "1"),
            reason())),
    ECONOMY_OFFER_REMOVE("economy-offer-remove", fields(reason())),
    ECONOMY_RESTOCK("economy-restock", fields(
            toggle("restock_enabled", false),
            integer("restock_amount", 1, ShopTemplateSnapshot.MAX_STOCK, "1"),
            integer("restock_interval_ticks", 1, ShopTemplateSnapshot.MAX_RESTOCK_INTERVAL_TICKS, "1200"),
            reason())),
    ECONOMY_REVERSE_STRICT("economy-reverse-strict", fields(reason())),
    ECONOMY_REVERSE_COMPENSATE("economy-reverse-compensate", fields(reason())),

    WORLD_CLAIM_ROLE("world-claim-role", fields(select("claim_role", "visitor", "visitor", "user", "builder", "manager"), reason())),
    WORLD_CLAIM_UNTRUST("world-claim-untrust", fields(reason())),
    WORLD_CLAIM_SETTINGS("world-claim-settings", fields(
            toggle("entry_restricted", false), toggle("public_interactions", false), reason())),
    WORLD_CLAIM_RECLAIM("world-claim-reclaim", fields(reason())),
    WORLD_REGION_CREATE("world-region-create", fields(positionXz("first_corner"), positionXz("second_corner"), reason())),
    WORLD_REGION_EDIT("world-region-edit", fields(positionXz("first_corner"), positionXz("second_corner"), reason())),
    WORLD_REGION_DELETE("world-region-delete", fields(reason())),
    WORLD_PORTAL_CREATE("world-portal-create", portalFields()),
    WORLD_PORTAL_EDIT("world-portal-edit", portalFields()),
    WORLD_PORTAL_DISABLE("world-portal-disable", fields(reason())),
    WORLD_WILDERNESS_WARN("world-wilderness-warn", fields(reason())),
    WORLD_WILDERNESS_RESET("world-wilderness-reset", fields(reason())),
    WORLD_WILDERNESS_RESTORE("world-wilderness-restore", fields(reason())),

    RPG_XP("rpg-xp", fields(integer("xp_delta", -RpgPlayerState.MAX_XP, RpgPlayerState.MAX_XP, "1"), rpgReason())),
    RPG_PROMOTION("rpg-promotion", fields(rpgReason())),
    RPG_SKILL_FULL_RESET("rpg-skill-full-reset", fields(rpgReason())),
    RPG_SKILL_BRANCH_RESET("rpg-skill-branch-reset", fields(rpgReason())),
    RPG_BOSS_RESET("rpg-boss-reset", fields(reason())),
    RPG_BOSS_RECOVER("rpg-boss-recover", fields(reason())),
    RPG_RELOAD("rpg-reload", fields(reason())),

    OPERATIONS_AUDIT_SEARCH("operations-audit-search", fields(
            select("audit_window", "month", "hour", "day", "week", "month"),
            text("audit_target", 0, AuditQuery.MAX_TARGET_PREFIX_LENGTH, ""))),
    OPERATIONS_EXPORT("operations-export", fields(
            select("audit_window", "day", "hour", "day", "week", "month"), reason())),
    OPERATIONS_SNAPSHOT_CREATE("operations-snapshot-create", fields(reason())),
    OPERATIONS_SNAPSHOT_RESTORE("operations-snapshot-restore", fields(reason()));

    private final String wireId;
    private final List<Field> fields;

    AdministrationFormType(String wireId, List<Field> fields) {
        this.wireId = wireId;
        this.fields = List.copyOf(fields);
        if (wireId.isBlank() || this.fields.stream().anyMatch(field -> !field.validDefinition())) {
            throw new IllegalArgumentException("Invalid administration form definition");
        }
    }

    public String wireId() {
        return wireId;
    }

    public List<Field> fields() {
        return fields;
    }

    public List<String> defaults() {
        return fields.stream().map(Field::defaultValue).toList();
    }

    public boolean accepts(List<String> values) {
        if (values == null || values.size() != fields.size()) {
            return false;
        }
        for (int index = 0; index < fields.size(); index++) {
            if (!fields.get(index).accepts(values.get(index))) {
                return false;
            }
        }
        return true;
    }

    public static Optional<AdministrationFormType> fromWireId(String wireId) {
        if (wireId == null) {
            return Optional.empty();
        }
        return Arrays.stream(values()).filter(type -> type.wireId.equals(wireId)).findFirst();
    }

    private static List<Field> fields(Field... fields) {
        return List.of(fields);
    }

    private static List<Field> portalFields() {
        return fields(
                position("origin"),
                position("destination"),
                integer("radius_chunks", 0, PortalDefinition.MAX_PROTECTION_RADIUS_CHUNKS, "0"),
                integer("cooldown_millis", 0, PortalDefinition.MAX_COOLDOWN_MILLIS, "0"),
                select("safe_arrival", "nearest_safe", "exact", "nearest_safe"),
                toggle("allow_combat", false),
                reason());
    }

    private static Field text(String name, long minimum, long maximum, String defaultValue) {
        return new Field(name, FieldKind.TEXT, minimum, maximum, List.of(), defaultValue);
    }

    private static Field integer(String name, long minimum, long maximum, String defaultValue) {
        return new Field(name, FieldKind.INTEGER, minimum, maximum, List.of(), defaultValue);
    }

    private static Field optionalInteger(String name, long minimum, long maximum, String defaultValue) {
        return new Field(name, FieldKind.OPTIONAL_INTEGER, minimum, maximum, List.of(), defaultValue);
    }

    private static Field position(String name) {
        return new Field(name, FieldKind.POSITION, 0, 0, List.of(), "");
    }

    private static Field positionXz(String name) {
        return new Field(name, FieldKind.POSITION_XZ, 0, 0, List.of(), "");
    }

    private static Field toggle(String name, boolean defaultValue) {
        return new Field(name, FieldKind.TOGGLE, 0, 1, List.of("false", "true"), Boolean.toString(defaultValue));
    }

    private static Field select(String name, String defaultValue, String... options) {
        return new Field(name, FieldKind.SELECT, 0, 0, List.of(options), defaultValue);
    }

    private static Field reason() {
        return text("reason", 0, AdministrationService.MAX_REASON_LENGTH, "");
    }

    private static Field rpgReason() {
        return text("reason", 0, RpgAdminOperation.MAX_REASON_LENGTH, "");
    }

    public enum FieldKind {
        TEXT,
        INTEGER,
        OPTIONAL_INTEGER,
        TOGGLE,
        SELECT,
        POSITION,
        POSITION_XZ
    }

    /** A bounded value specification with a translation key derived from its stable field name. */
    public record Field(
            String name,
            FieldKind kind,
            long minimum,
            long maximum,
            List<String> options,
            String defaultValue) {
        public Field {
            options = options == null ? List.of() : List.copyOf(options);
        }

        public String translationKey() {
            return "gui.rovenfall.admin.form.field." + name;
        }

        boolean validDefinition() {
            if (name == null || !name.matches("[a-z0-9_]+") || kind == null || defaultValue == null) {
                return false;
            }
            return switch (kind) {
                case TEXT, INTEGER, OPTIONAL_INTEGER -> minimum <= maximum && options.isEmpty() && accepts(defaultValue);
                case TOGGLE -> options.equals(List.of("false", "true")) && accepts(defaultValue);
                case SELECT -> !options.isEmpty() && options.stream().allMatch(option -> option != null && !option.isBlank())
                        && accepts(defaultValue);
                case POSITION, POSITION_XZ -> options.isEmpty() && accepts(defaultValue);
            };
        }

        boolean accepts(String value) {
            if (value == null || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0
                    || value.indexOf('\u2028') >= 0 || value.indexOf('\u2029') >= 0) {
                return false;
            }
            return switch (kind) {
                case TEXT -> value.length() >= minimum && value.length() <= maximum;
                case INTEGER -> boundedLong(value).isPresent();
                case OPTIONAL_INTEGER -> value.isEmpty() || boundedLong(value).isPresent();
                case TOGGLE, SELECT -> options.contains(value);
                case POSITION -> value.isEmpty() || value.matches("-?\\d+,-?\\d+,-?\\d+");
                case POSITION_XZ -> value.isEmpty() || value.matches("-?\\d+,-?\\d+");
            };
        }

        private Optional<Long> boundedLong(String value) {
            try {
                long parsed = Long.parseLong(value);
                return parsed >= minimum && parsed <= maximum ? Optional.of(parsed) : Optional.empty();
            } catch (NumberFormatException exception) {
                return Optional.empty();
            }
        }
    }
}
