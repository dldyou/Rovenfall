package org.dldyou.rovenfall.administration;

import java.util.Optional;
import org.dldyou.rovenfall.rpg.RpgPlayerState;

/** Strict bounded parsing for the few values that cannot be selected as inventory rows. */
final class AdministrationRpgBossFormParser {
    private AdministrationRpgBossFormParser() {
    }

    static Optional<XpForm> parseXp(String input) {
        return split(input, true).flatMap(parts -> {
            try {
                long delta = Long.parseLong(parts.field());
                return delta != 0 && delta >= -RpgPlayerState.MAX_XP && delta <= RpgPlayerState.MAX_XP
                        ? Optional.of(new XpForm(delta, parts.reason())) : Optional.empty();
            } catch (NumberFormatException exception) {
                return Optional.empty();
            }
        });
    }

    static Optional<ReasonForm> parseReasonOnly(String input) {
        return split(input, false).map(parts -> new ReasonForm(parts.reason()));
    }

    private static Optional<Parts> split(String input, boolean fieldRequired) {
        if (input == null || input.length() > AdministrationTextInputMenu.MAX_INPUT_LENGTH || hasLineBreak(input)) {
            return Optional.empty();
        }
        int delimiter = input.indexOf('|');
        if (delimiter < 0 || delimiter != input.lastIndexOf('|')) {
            return Optional.empty();
        }
        String field = input.substring(0, delimiter).strip();
        String reason = input.substring(delimiter + 1).strip();
        if ((fieldRequired && field.isEmpty()) || (!fieldRequired && !field.isEmpty()) || reason.isEmpty()
                || reason.length() > AdministrationService.MAX_REASON_LENGTH) {
            return Optional.empty();
        }
        return Optional.of(new Parts(field, reason));
    }

    private static boolean hasLineBreak(String value) {
        return value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0
                || value.indexOf('\u2028') >= 0 || value.indexOf('\u2029') >= 0;
    }

    private record Parts(String field, String reason) {
    }

    record XpForm(long delta, String reason) {
    }

    record ReasonForm(String reason) {
    }
}
