package org.dldyou.rovenfall.administration;

import java.util.Optional;

/** Strict bounded parsing for administration operations search and export forms. */
final class AdministrationOperationsFormParser {
    private AdministrationOperationsFormParser() {
    }

    static Optional<AuditSearchForm> parseAuditSearch(String input, long nowEpochMillis) {
        if (!validAuditText(input) || nowEpochMillis < 0) {
            return Optional.empty();
        }
        try {
            long defaultSince = Math.max(0L, nowEpochMillis - AuditQuery.MAX_WINDOW_MILLIS);
            return Optional.of(new AuditSearchForm(
                    AuditQuery.parse(input, defaultSince, nowEpochMillis, false)));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    static Optional<ExportForm> parseExport(String input, long nowEpochMillis) {
        if (input == null || input.length() > AdministrationTextInputMenu.MAX_INPUT_LENGTH
                || nowEpochMillis < 0 || hasLineBreak(input)) {
            return Optional.empty();
        }
        int delimiter = input.indexOf('|');
        if (delimiter < 0 || delimiter != input.lastIndexOf('|')) {
            return Optional.empty();
        }
        String queryText = input.substring(0, delimiter).strip();
        String reason = input.substring(delimiter + 1).strip();
        if (!validAuditText(queryText) || !validReason(reason)) {
            return Optional.empty();
        }
        try {
            return Optional.of(new ExportForm(AuditQuery.parse(queryText, 0L, nowEpochMillis, true), reason));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    static Optional<ReasonForm> parseReasonOnly(String input) {
        if (input == null || input.length() > AdministrationTextInputMenu.MAX_INPUT_LENGTH || hasLineBreak(input)) {
            return Optional.empty();
        }
        int delimiter = input.indexOf('|');
        if (delimiter < 0 || delimiter != input.lastIndexOf('|') || !input.substring(0, delimiter).strip().isEmpty()) {
            return Optional.empty();
        }
        String reason = input.substring(delimiter + 1).strip();
        return validReason(reason) ? Optional.of(new ReasonForm(reason)) : Optional.empty();
    }

    private static boolean validAuditText(String value) {
        return value != null && !value.isBlank() && value.length() <= AuditQuery.MAX_TEXT_LENGTH && !hasLineBreak(value);
    }

    private static boolean validReason(String value) {
        return !value.isEmpty() && value.length() <= AdministrationService.MAX_REASON_LENGTH;
    }

    private static boolean hasLineBreak(String value) {
        return value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0
                || value.indexOf('\u2028') >= 0 || value.indexOf('\u2029') >= 0;
    }

    record AuditSearchForm(AuditQuery query) {
    }

    record ExportForm(AuditQuery query, String reason) {
    }

    record ReasonForm(String reason) {
    }
}
