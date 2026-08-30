package org.dldyou.rovenfall.administration;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Strict length-prefixed encoding for structured administration values carried by {@code AdminQuery}. */
public final class AdministrationStructuredFormCodec {
    private static final String PREFIX = "rf-form/1/";
    public static final int MAX_MESSAGE_LENGTH = AdministrationTextInputMenu.MAX_INPUT_LENGTH;
    public static final int MAX_FIELD_LENGTH = AuditQuery.MAX_TEXT_LENGTH;

    private AdministrationStructuredFormCodec() {
    }

    public static Optional<String> encode(AdministrationFormType type, List<String> values) {
        if (type == null || !type.accepts(values)) {
            return Optional.empty();
        }
        StringBuilder encoded = new StringBuilder(PREFIX)
                .append(type.wireId()).append('/').append(values.size()).append('/');
        for (String value : values) {
            if (value.length() > MAX_FIELD_LENGTH || hasLineBreak(value)) {
                return Optional.empty();
            }
            encoded.append(value.length()).append(':').append(value);
            if (encoded.length() > MAX_MESSAGE_LENGTH) {
                return Optional.empty();
            }
        }
        return Optional.of(encoded.toString());
    }

    public static Optional<Decoded> decode(String input) {
        if (input == null || input.length() > MAX_MESSAGE_LENGTH || hasLineBreak(input)
                || !input.startsWith(PREFIX)) {
            return Optional.empty();
        }
        int cursor = PREFIX.length();
        int typeEnd = input.indexOf('/', cursor);
        if (typeEnd < cursor) {
            return Optional.empty();
        }
        Optional<AdministrationFormType> type = AdministrationFormType.fromWireId(input.substring(cursor, typeEnd));
        if (type.isEmpty()) {
            return Optional.empty();
        }
        int countEnd = input.indexOf('/', typeEnd + 1);
        if (countEnd < typeEnd + 1) {
            return Optional.empty();
        }
        Optional<Integer> count = decimal(input, typeEnd + 1, countEnd, type.orElseThrow().fields().size());
        if (count.isEmpty() || count.orElseThrow() != type.orElseThrow().fields().size()) {
            return Optional.empty();
        }
        cursor = countEnd + 1;
        List<String> values = new ArrayList<>(count.orElseThrow());
        for (int index = 0; index < count.orElseThrow(); index++) {
            int separator = input.indexOf(':', cursor);
            if (separator < cursor) {
                return Optional.empty();
            }
            Optional<Integer> length = decimal(input, cursor, separator, MAX_FIELD_LENGTH);
            if (length.isEmpty() || separator + 1 > input.length() - length.orElseThrow()) {
                return Optional.empty();
            }
            int end = separator + 1 + length.orElseThrow();
            String value = input.substring(separator + 1, end);
            if (hasLineBreak(value)) {
                return Optional.empty();
            }
            values.add(value);
            cursor = end;
        }
        if (cursor != input.length() || !type.orElseThrow().accepts(values)) {
            return Optional.empty();
        }
        return Optional.of(new Decoded(type.orElseThrow(), values));
    }

    public static Optional<List<String>> decode(AdministrationFormType expectedType, String input) {
        return decode(input).filter(decoded -> decoded.type() == expectedType).map(Decoded::values);
    }

    private static Optional<Integer> decimal(String input, int start, int end, int maximum) {
        if (start >= end || end - start > 10) {
            return Optional.empty();
        }
        long value = 0;
        for (int index = start; index < end; index++) {
            char character = input.charAt(index);
            if (character < '0' || character > '9') {
                return Optional.empty();
            }
            value = value * 10 + character - '0';
            if (value > maximum) {
                return Optional.empty();
            }
        }
        return Optional.of((int) value);
    }

    private static boolean hasLineBreak(String value) {
        return value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0
                || value.indexOf('\u2028') >= 0 || value.indexOf('\u2029') >= 0;
    }

    public record Decoded(AdministrationFormType type, List<String> values) {
        public Decoded {
            values = List.copyOf(values);
        }
    }
}
