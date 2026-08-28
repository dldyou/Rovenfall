package org.dldyou.rovenfall.administration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class AdministrationOperationsFormParserTest {
    private static final long NOW = AuditQuery.MAX_WINDOW_MILLIS + 5_000L;

    @Test
    void parsesAuditSearchWithTheBoundedDefaultWindow() {
        UUID actor = new UUID(0L, 7L);

        var form = AdministrationOperationsFormParser.parseAuditSearch(
                " action=rovenfall:test actor=" + actor + " ", NOW);

        assertEquals(Optional.of(new AdministrationOperationsFormParser.AuditSearchForm(AuditQuery.parse(
                "action=rovenfall:test actor=" + actor, 5_000L, NOW, false))), form);
    }

    @Test
    void parsesExplicitExportAndReasonOnlyForms() {
        var export = AdministrationOperationsFormParser.parseExport(
                " since=1000 until=2000 action=rovenfall:test |  incident review  ", NOW);
        var reason = AdministrationOperationsFormParser.parseReasonOnly(" |  confirm export ");

        assertEquals(Optional.of(new AdministrationOperationsFormParser.ExportForm(
                AuditQuery.parse("since=1000 until=2000 action=rovenfall:test", 0L, NOW, true),
                "incident review")), export);
        assertEquals(Optional.of(new AdministrationOperationsFormParser.ReasonForm("confirm export")), reason);
    }

    @Test
    void rejectsMalformedOverflowAndMultilineInput() {
        String tooLongReason = "x".repeat(AdministrationService.MAX_REASON_LENGTH + 1);

        assertFalse(AdministrationOperationsFormParser.parseAuditSearch("", NOW).isPresent());
        assertFalse(AdministrationOperationsFormParser.parseAuditSearch(
                "x".repeat(AuditQuery.MAX_TEXT_LENGTH + 1), NOW).isPresent());
        assertFalse(AdministrationOperationsFormParser.parseAuditSearch("action=rovenfall:test\u2028", NOW).isPresent());
        assertFalse(AdministrationOperationsFormParser.parseAuditSearch("until=1", -1L).isPresent());
        assertFalse(AdministrationOperationsFormParser.parseExport(
                "action=rovenfall:test | reason", NOW).isPresent());
        assertFalse(AdministrationOperationsFormParser.parseExport(
                "since=1 until=2 | reason | extra", NOW).isPresent());
        assertFalse(AdministrationOperationsFormParser.parseExport(
                "since=1 until=2\n | reason", NOW).isPresent());
        assertFalse(AdministrationOperationsFormParser.parseExport(
                "since=1 until=2 | reason\u2029", NOW).isPresent());
        assertFalse(AdministrationOperationsFormParser.parseExport(
                "since=1 until=2 | " + tooLongReason, NOW).isPresent());
        assertFalse(AdministrationOperationsFormParser.parseExport(
                "x".repeat(AdministrationTextInputMenu.MAX_INPUT_LENGTH + 1), NOW).isPresent());
        assertFalse(AdministrationOperationsFormParser.parseReasonOnly("value | reason").isPresent());
        assertFalse(AdministrationOperationsFormParser.parseReasonOnly(" | first | second").isPresent());
        assertFalse(AdministrationOperationsFormParser.parseReasonOnly(" | reason\r").isPresent());
        assertTrue(AdministrationOperationsFormParser.parseReasonOnly(" | ok").isPresent());
    }
}
