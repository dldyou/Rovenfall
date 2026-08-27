package org.dldyou.rovenfall.administration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class AuditExportServiceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void ownerExportsExactQueryAsEscapedBoundedJsonLinesAndRetryIsIdempotent() throws Exception {
        PlatformSavedData state = new PlatformSavedData();
        UUID owner = id(1);
        AdministrationService.changeRole(
                state, AdministrationService.SYSTEM_ACTOR, true, owner, "owner", "bootstrap", 100, id(10));
        UUID selectedTransaction = id(11);
        state.commitAudit(new AuditEntry(
                200, owner, action("test"), "player:\"one", Optional.empty(), Optional.empty(),
                "before\nline", "after", "quoted \"reason\"", selectedTransaction));
        AuditQuery query = AuditQuery.parse(
                "since=150 until=250 transaction=" + selectedTransaction, 0, 0, true);
        UUID exportTransaction = id(12);
        AuditExportStore store = new AuditExportStore(temporaryDirectory.resolve("audit"));

        AuditExportService.Result first = AuditExportService.export(
                state, store, owner, false, query, "incident review", 300, exportTransaction);

        assertEquals(AuditExportService.Status.SUCCESS, first.status());
        assertEquals(1, first.rows());
        assertTrue(first.path().orElseThrow().normalize().startsWith(temporaryDirectory.toAbsolutePath().normalize()));
        String contents = Files.readString(first.path().orElseThrow(), StandardCharsets.UTF_8);
        assertEquals(1, contents.lines().count());
        var json = JsonParser.parseString(contents).getAsJsonObject();
        assertEquals("player:\"one", json.get("target").getAsString());
        assertEquals("before\nline", json.get("before").getAsString());
        assertEquals(exportTransaction, state.auditPage(0, 1).entries().getFirst().transactionId());

        Files.delete(first.path().orElseThrow());
        AuditExportService.Result missingRetry = AuditExportService.export(
                state, store, owner, false, query, "incident review", 301, exportTransaction);
        assertEquals(AuditExportService.Status.DUPLICATE, missingRetry.status());
        assertEquals(contents, Files.readString(missingRetry.path().orElseThrow(), StandardCharsets.UTF_8));

        Files.writeString(missingRetry.path().orElseThrow(), "tampered", StandardCharsets.UTF_8);
        AuditExportService.Result tamperedRetry = AuditExportService.export(
                state, store, owner, false, query, "incident review", 302, exportTransaction);
        assertEquals(AuditExportService.Status.DUPLICATE, tamperedRetry.status());
        assertEquals(contents, Files.readString(tamperedRetry.path().orElseThrow(), StandardCharsets.UTF_8));
    }

    @Test
    void nonOwnerIsDeniedWithoutWritingAndDenialIsAudited() {
        PlatformSavedData state = new PlatformSavedData();
        UUID viewer = id(20);
        AdministrationService.changeRole(
                state, AdministrationService.SYSTEM_ACTOR, true, viewer, "viewer", "bootstrap", 100, id(21));
        AuditQuery query = AuditQuery.parse("since=0 until=200", 0, 0, true);
        UUID transaction = id(22);

        AuditExportService.Result result = AuditExportService.export(
                state, new AuditExportStore(temporaryDirectory.resolve("audit")), viewer, false,
                query, "not allowed", 300, transaction);

        assertEquals(AuditExportService.Status.UNAUTHORIZED, result.status());
        assertFalse(Files.exists(temporaryDirectory.resolve("audit").resolve("audit-" + transaction + ".jsonl")));
        assertEquals(action("audit_export_denied"), state.auditPage(0, 1).entries().getFirst().actionType());
    }

    @Test
    void transactionReuseWithDifferentQueryFailsClosed() {
        PlatformSavedData state = new PlatformSavedData();
        UUID owner = id(30);
        AdministrationService.changeRole(
                state, AdministrationService.SYSTEM_ACTOR, true, owner, "owner", "bootstrap", 100, id(31));
        UUID transaction = id(32);
        AuditExportStore store = new AuditExportStore(temporaryDirectory.resolve("audit"));
        AuditQuery firstQuery = AuditQuery.parse("since=0 until=100", 0, 0, true);
        AuditQuery differentQuery = AuditQuery.parse("since=0 until=101", 0, 0, true);

        assertEquals(AuditExportService.Status.SUCCESS, AuditExportService.export(
                state, store, owner, false, firstQuery, "review", 200, transaction).status());
        assertEquals(AuditExportService.Status.TRANSACTION_CONFLICT, AuditExportService.export(
                state, store, owner, false, differentQuery, "review", 201, transaction).status());
    }

    @Test
    void exportRejectsMoreThanTheRowCapBeforeWriting() {
        PlatformSavedData state = ownerState(id(40));
        for (int index = 0; index <= AuditExportService.MAX_EXPORT_ROWS; index++) {
            state.commitAudit(new AuditEntry(
                    1_000 + index, id(40), action("test"), "target:" + index,
                    Optional.empty(), Optional.empty(), "before", "after", "reason", id(10_000 + index)));
        }
        AuditQuery query = AuditQuery.parse("since=1000 until=20000 action=rovenfall:test", 0, 0, true);
        UUID transaction = id(41);

        AuditExportService.Result result = AuditExportService.export(
                state, new AuditExportStore(temporaryDirectory.resolve("rows")), id(40), false,
                query, "bounded export", 30_000, transaction);

        assertEquals(AuditExportService.Status.LIMIT_EXCEEDED, result.status());
        assertEquals(AuditExportService.MAX_EXPORT_ROWS + 1, result.rows());
        assertFalse(Files.exists(temporaryDirectory.resolve("rows").resolve("audit-" + transaction + ".jsonl")));
    }

    @Test
    void exportRejectsOversizedBytesBelowTheRowCap() {
        PlatformSavedData state = ownerState(id(50));
        String largeValue = "x".repeat(1_000);
        for (int index = 0; index < 8_500; index++) {
            state.commitAudit(new AuditEntry(
                    1_000 + index, id(50), action("test"), "target:" + index,
                    Optional.empty(), Optional.empty(), largeValue, "after", "reason", id(20_000 + index)));
        }
        AuditQuery query = AuditQuery.parse("since=1000 until=20000 action=rovenfall:test", 0, 0, true);
        UUID transaction = id(51);

        AuditExportService.Result result = AuditExportService.export(
                state, new AuditExportStore(temporaryDirectory.resolve("bytes")), id(50), false,
                query, "bounded export", 30_000, transaction);

        assertEquals(AuditExportService.Status.LIMIT_EXCEEDED, result.status());
        assertTrue(result.bytes() > AuditExportService.MAX_EXPORT_BYTES);
        assertFalse(Files.exists(temporaryDirectory.resolve("bytes").resolve("audit-" + transaction + ".jsonl")));
    }

    @Test
    void malformedQueryDenialStoresOnlyLengthAndHash() {
        PlatformSavedData state = ownerState(id(60));
        UUID transaction = id(61);
        String malformed = "not-a-filter private text";

        AuditExportService.auditInvalidQuery(state, id(60), malformed, 500, transaction);

        AuditEntry audit = state.auditPage(0, 1).entries().getFirst();
        assertEquals(action("audit_export_denied"), audit.actionType());
        assertEquals(transaction, audit.transactionId());
        assertEquals("invalid_query", audit.reason());
        assertTrue(audit.beforeValue().matches("length=25;sha256=[0-9a-f]{64}"));
        assertFalse(audit.beforeValue().contains("private"));
    }

    @Test
    void zeroTransactionAndNonPastWindowAreRejectedWithoutWriting() {
        PlatformSavedData state = ownerState(id(70));
        AuditExportStore store = new AuditExportStore(temporaryDirectory.resolve("invalid"));

        assertEquals(AuditExportService.Status.INVALID_REQUEST, AuditExportService.export(
                state, store, id(70), false,
                AuditQuery.parse("since=0 until=100", 0, 0, true), "review", 100, new UUID(0, 0)).status());
        assertEquals(AuditExportService.Status.INVALID_REQUEST, AuditExportService.export(
                state, store, id(70), false,
                AuditQuery.parse("since=0 until=100", 0, 0, true), "review", 100, id(71)).status());
        assertFalse(Files.exists(temporaryDirectory.resolve("invalid")));
    }

    @Test
    void demotedOwnerCannotRepairARecordedExport() throws Exception {
        PlatformSavedData state = ownerState(id(80));
        AuditQuery query = AuditQuery.parse("since=0 until=200", 0, 0, true);
        UUID transaction = id(81);
        AuditExportStore store = new AuditExportStore(temporaryDirectory.resolve("demoted"));
        AuditExportService.Result exported = AuditExportService.export(
                state, store, id(80), false, query, "review", 300, transaction);
        Path path = exported.path().orElseThrow();
        Files.delete(path);
        AdministrationService.changeRole(
                state, AdministrationService.SYSTEM_ACTOR, true, id(80), "viewer", "demotion", 400, id(82));

        AuditExportService.Result retry = AuditExportService.export(
                state, store, id(80), false, query, "review", 500, transaction);

        assertEquals(AuditExportService.Status.UNAUTHORIZED, retry.status());
        assertFalse(Files.exists(path));
        AuditEntry denial = state.auditPage(0, 1).entries().getFirst();
        assertEquals(action("audit_export_denied"), denial.actionType());
        assertEquals("audit_export:" + transaction, denial.target());
        assertFalse(transaction.equals(denial.transactionId()));
    }

    private static PlatformSavedData ownerState(UUID owner) {
        PlatformSavedData state = new PlatformSavedData();
        AdministrationService.changeRole(
                state, AdministrationService.SYSTEM_ACTOR, true, owner, "owner", "bootstrap", 100, id(30_000));
        return state;
    }

    private static Identifier action(String path) {
        return Identifier.fromNamespaceAndPath("rovenfall", path);
    }

    private static UUID id(long value) {
        return new UUID(0L, value);
    }
}
