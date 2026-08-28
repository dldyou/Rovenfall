package org.dldyou.rovenfall.administration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.dldyou.rovenfall.rpg.RpgPlayerState;
import org.junit.jupiter.api.Test;

class AdministrationRpgBossFormParserTest {
    @Test
    void parsesBoundedPositiveAndNegativeXpWithNormalizedReason() {
        var positive = AdministrationRpgBossFormParser.parseXp(
                RpgPlayerState.MAX_XP + " |  reward correction  ");
        var negative = AdministrationRpgBossFormParser.parseXp(
                (-RpgPlayerState.MAX_XP) + " | rollback correction");
        var reason = AdministrationRpgBossFormParser.parseReasonOnly(" | confirm reset");

        assertEquals(Optional.of(new AdministrationRpgBossFormParser.XpForm(
                RpgPlayerState.MAX_XP, "reward correction")), positive);
        assertEquals(Optional.of(new AdministrationRpgBossFormParser.XpForm(
                -RpgPlayerState.MAX_XP, "rollback correction")), negative);
        assertEquals(Optional.of(new AdministrationRpgBossFormParser.ReasonForm("confirm reset")), reason);
    }

    @Test
    void rejectsZeroOverflowNewlinesAndMalformedDelimiters() {
        String tooLongReason = "x".repeat(AdministrationService.MAX_REASON_LENGTH + 1);
        String atLimit = "1 | ok" + " ".repeat(AdministrationTextInputMenu.MAX_INPUT_LENGTH - "1 | ok".length());

        assertTrue(AdministrationRpgBossFormParser.parseXp(atLimit).isPresent());
        assertFalse(AdministrationRpgBossFormParser.parseXp(atLimit + " ").isPresent());
        assertFalse(AdministrationRpgBossFormParser.parseXp("0 | zero").isPresent());
        assertFalse(AdministrationRpgBossFormParser.parseXp(
                (RpgPlayerState.MAX_XP + 1) + " | too large").isPresent());
        assertFalse(AdministrationRpgBossFormParser.parseXp("9223372036854775808 | overflow").isPresent());
        assertFalse(AdministrationRpgBossFormParser.parseXp("1 | first | second").isPresent());
        assertFalse(AdministrationRpgBossFormParser.parseXp("1\n | newline").isPresent());
        assertFalse(AdministrationRpgBossFormParser.parseReasonOnly("unexpected | reason").isPresent());
        assertFalse(AdministrationRpgBossFormParser.parseReasonOnly(" | " + tooLongReason).isPresent());
    }
}
