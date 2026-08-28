package org.dldyou.rovenfall.administration;

import java.util.Optional;
import net.minecraft.resources.Identifier;
import org.dldyou.rovenfall.economy.ShopInstance;
import org.dldyou.rovenfall.economy.ShopTemplateSnapshot;

/**
 * Bounded, server-side parsing for the single-line values submitted by the administration GUI.
 *
 * <p>Every form uses one {@code fields | reason} delimiter. Comma-separated fields are trimmed;
 * prices and stock use {@code -1} (or {@code none}) for an unset value, matching the command
 * boundary. This class only parses and validates values; callers still authorize and mutate via
 * the owning services.</p>
 */
final class AdministrationEconomyFormParser {
    private static final int MAX_INPUT_LENGTH = AdministrationTextInputMenu.MAX_INPUT_LENGTH;
    private static final String UNSET = "none";

    private AdministrationEconomyFormParser() {
    }

    static Optional<Balance> parseBalance(String input) {
        return parse(input, 1, fields -> {
            long amount = boundedLong(fields.values()[0], 1, Long.MAX_VALUE);
            return amount < 1 ? Optional.empty() : Optional.of(new Balance(amount, fields.reason()));
        });
    }

    static Optional<ShopCreate> parseShopCreate(String input) {
        return parse(input, 2, fields -> {
            String[] values = fields.values();
            return identifiers(values, (first, second) -> new ShopCreate(first, second, fields.reason()));
        });
    }

    static Optional<AccessDistance> parseAccessDistance(String input) {
        return parse(input, 1, fields -> {
            long distance = boundedLong(fields.values()[0], 1, ShopInstance.MAX_ACCESS_DISTANCE);
            return distance < 1 ? Optional.empty() : Optional.of(new AccessDistance((int) distance, fields.reason()));
        });
    }

    static Optional<OfferUpsert> parseOfferUpsert(String input) {
        return parse(input, 7, fields -> {
            String[] values = fields.values();
            Optional<Identifier> offerId = identifier(values[0]);
            Optional<Identifier> itemId = identifier(values[1]);
            long count = boundedLong(values[2], 1, 99);
            if (!validPrice(values[3]) || !validPrice(values[4])) {
                return Optional.empty();
            }
            Optional<Long> buyPrice = price(values[3]);
            Optional<Long> sellPrice = price(values[4]);
            long stock = boundedLong(values[5], -1, ShopTemplateSnapshot.MAX_STOCK);
            long maximumStock = boundedLong(values[6], -1, ShopTemplateSnapshot.MAX_STOCK);
            if (offerId.isEmpty() || itemId.isEmpty() || count < 1
                    || buyPrice.isEmpty() && sellPrice.isEmpty()
                    || stock == Long.MIN_VALUE || maximumStock == Long.MIN_VALUE
                    || (stock == -1) != (maximumStock == -1)
                    || stock != -1 && stock > maximumStock) {
                return Optional.empty();
            }
            return Optional.of(new OfferUpsert(
                    offerId.orElseThrow(), itemId.orElseThrow(), (int) count,
                    buyPrice, sellPrice, stock, maximumStock, fields.reason()));
        });
    }

    static Optional<Restock> parseRestock(String input) {
        try {
            Optional<Parts> parts = split(input);
            if (parts.isEmpty()) {
                return Optional.empty();
            }
            Parts fields = parts.orElseThrow();
            String[] values = fields.values();
            if (values.length == 2 && "clear".equalsIgnoreCase(values[1])) {
                return identifier(values[0]).map(id -> new Restock(id, Optional.empty(), Optional.empty(), fields.reason()));
            }
            if (values.length != 3) {
                return Optional.empty();
            }
            Optional<Identifier> offerId = identifier(values[0]);
            long amount = boundedLong(values[1], 1, ShopTemplateSnapshot.MAX_STOCK);
            long interval = boundedLong(values[2], 1, ShopTemplateSnapshot.MAX_RESTOCK_INTERVAL_TICKS);
            if (offerId.isEmpty() || amount < 1 || interval < 1) {
                return Optional.empty();
            }
            return Optional.of(new Restock(
                    offerId.orElseThrow(), Optional.of(amount), Optional.of(interval), fields.reason()));
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    static Optional<ReasonOnly> parseReasonOnly(String input) {
        try {
            Optional<Parts> parts = split(input);
            if (parts.isEmpty() || parts.orElseThrow().values().length != 0) {
                return Optional.empty();
            }
            return Optional.of(new ReasonOnly(parts.orElseThrow().reason()));
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    private static <T> Optional<T> parse(String input, int fieldCount, FieldParser<T> parser) {
        try {
            Optional<Parts> parts = split(input);
            if (parts.isEmpty() || parts.orElseThrow().values().length != fieldCount) {
                return Optional.empty();
            }
            return parser.parse(parts.orElseThrow());
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    private static Optional<Parts> split(String input) {
        if (input == null || input.length() > MAX_INPUT_LENGTH || hasLineBreak(input)) {
            return Optional.empty();
        }
        int delimiter = input.indexOf('|');
        if (delimiter < 0 || delimiter != input.lastIndexOf('|')) {
            return Optional.empty();
        }
        String fieldText = input.substring(0, delimiter).strip();
        String reason = input.substring(delimiter + 1).strip();
        if (reason.isEmpty() || reason.length() > AdministrationService.MAX_REASON_LENGTH
                || hasLineBreak(reason)) {
            return Optional.empty();
        }
        if (fieldText.isEmpty()) {
            return Optional.of(new Parts(new String[0], reason));
        }
        String[] fields = fieldText.split(",", -1);
        for (int index = 0; index < fields.length; index++) {
            fields[index] = fields[index].strip();
            if (fields[index].isEmpty() || hasLineBreak(fields[index])) {
                return Optional.empty();
            }
        }
        return Optional.of(new Parts(fields, reason));
    }

    private static boolean hasLineBreak(String value) {
        return value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0
                || value.indexOf('\u2028') >= 0 || value.indexOf('\u2029') >= 0;
    }

    private static long boundedLong(String value, long minimum, long maximum) {
        try {
            long parsed = Long.parseLong(value);
            return parsed >= minimum && parsed <= maximum ? parsed : Long.MIN_VALUE;
        } catch (NumberFormatException exception) {
            return Long.MIN_VALUE;
        }
    }

    private static Optional<Long> price(String value) {
        if ("-1".equals(value) || UNSET.equalsIgnoreCase(value)) {
            return Optional.empty();
        }
        long parsed = boundedLong(value, 1, ShopTemplateSnapshot.MAX_PRICE);
        return parsed == Long.MIN_VALUE ? Optional.empty() : Optional.of(parsed);
    }

    private static boolean validPrice(String value) {
        return "-1".equals(value) || UNSET.equalsIgnoreCase(value)
                || boundedLong(value, 1, ShopTemplateSnapshot.MAX_PRICE) != Long.MIN_VALUE;
    }

    private static Optional<Identifier> identifier(String value) {
        try {
            Identifier parsed = Identifier.tryParse(value);
            return parsed == null ? Optional.empty() : Optional.of(parsed);
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    private static <T> Optional<T> identifiers(String[] values, IdentifierFactory<T> factory) {
        Optional<Identifier> first = identifier(values[0]);
        Optional<Identifier> second = identifier(values[1]);
        return first.isPresent() && second.isPresent()
                ? Optional.of(factory.create(first.orElseThrow(), second.orElseThrow()))
                : Optional.empty();
    }

    @FunctionalInterface
    private interface FieldParser<T> {
        Optional<T> parse(Parts fields);
    }

    @FunctionalInterface
    private interface IdentifierFactory<T> {
        T create(Identifier first, Identifier second);
    }

    private record Parts(String[] values, String reason) {
        private Parts {
            values = values.clone();
        }

        @Override
        public String[] values() {
            return values.clone();
        }
    }

    record Balance(long amount, String reason) {
    }

    record ShopCreate(Identifier shopId, Identifier templateId, String reason) {
    }

    record AccessDistance(int distance, String reason) {
    }

    record OfferUpsert(
            Identifier offerId,
            Identifier itemId,
            int count,
            Optional<Long> buyPrice,
            Optional<Long> sellPrice,
            long stock,
            long maximumStock,
            String reason) {
    }

    record Restock(
            Identifier offerId,
            Optional<Long> amount,
            Optional<Long> intervalTicks,
            String reason) {
    }

    record ReasonOnly(String reason) {
    }
}
