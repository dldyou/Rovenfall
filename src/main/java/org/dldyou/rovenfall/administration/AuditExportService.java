package org.dldyou.rovenfall.administration;

import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.Identifier;
import org.dldyou.rovenfall.Rovenfall;

final class AuditExportService {
    static final int MAX_EXPORT_ROWS = 10_000;
    static final long MAX_EXPORT_BYTES = 8L * 1024L * 1024L;
    private static final long DENIED_AUDIT_INTERVAL_MILLIS = 1_000L;
    private static final Identifier EXPORT = action("audit_export");
    private static final Identifier EXPORT_DENIED = action("audit_export_denied");

    private AuditExportService() {
    }

    static Result export(
            PlatformSavedData state,
            AuditExportStore store,
            UUID actorId,
            boolean authorizationOverride,
            AuditQuery query,
            String reason,
            long timestampEpochMillis,
            UUID transactionId) {
        if (state == null || store == null || actorId == null || query == null || !validTransaction(transactionId)
                || timestampEpochMillis < 0 || query.untilEpochMillis() >= timestampEpochMillis) {
            return new Result(Status.INVALID_REQUEST, transactionId, Optional.empty(), 0, 0);
        }
        if (!state.isWritable()) {
            return new Result(Status.READ_ONLY_SCHEMA, transactionId, Optional.empty(), 0, 0);
        }

        if (!authorizationOverride && state.roleOf(actorId).orElse(null) != AdminRole.OWNER) {
            denied(state, actorId, query, "unauthorized", timestampEpochMillis, transactionId);
            return new Result(Status.UNAUTHORIZED, transactionId, Optional.empty(), 0, 0);
        }

        Optional<AuditEntry> existing = state.auditTransaction(transactionId);
        if (existing.isPresent()) {
            AuditEntry entry = existing.orElseThrow();
            if (entry.actionType().equals(EXPORT) && entry.actorId().equals(actorId)
                    && entry.beforeValue().equals(query.canonical())
                    && entry.reason().equals(normalizeReason(reason).orElse(""))) {
                return restoreRecordedExport(state, store, query, entry, transactionId);
            }
            return new Result(Status.TRANSACTION_CONFLICT, transactionId, Optional.empty(), 0, 0);
        }

        Optional<String> normalizedReason = normalizeReason(reason);
        if (normalizedReason.isEmpty()) {
            denied(state, actorId, query, "invalid_reason", timestampEpochMillis, transactionId);
            return new Result(Status.INVALID_REASON, transactionId, Optional.empty(), 0, 0);
        }

        PlatformSavedData.AuditSelection selection = state.selectAudit(query, MAX_EXPORT_ROWS);
        if (selection.totalEntries() > MAX_EXPORT_ROWS) {
            denied(state, actorId, query, "row_limit", timestampEpochMillis, transactionId);
            return new Result(Status.LIMIT_EXCEEDED, transactionId, Optional.empty(), selection.totalEntries(), 0);
        }
        byte[] contents = jsonLines(selection.entries());
        if (contents.length > MAX_EXPORT_BYTES) {
            denied(state, actorId, query, "byte_limit", timestampEpochMillis, transactionId);
            return new Result(Status.LIMIT_EXCEEDED, transactionId, Optional.empty(), selection.totalEntries(), contents.length);
        }

        try {
            AuditExportStore.WriteResult write = store.write(transactionId, contents, false);
            String evidence = "rows=" + selection.totalEntries()
                    + ";bytes=" + contents.length
                    + ";sha256=" + sha256(contents)
                    + ";file=" + write.path().getFileName();
            state.commitAudit(new AuditEntry(
                    timestampEpochMillis, actorId, EXPORT, "audit_export:" + transactionId,
                    Optional.empty(), Optional.empty(), query.canonical(), evidence,
                    normalizedReason.orElseThrow(), transactionId));
            return new Result(Status.SUCCESS, transactionId, Optional.of(write.path()),
                    selection.totalEntries(), contents.length);
        } catch (AuditExportStore.ExportException exception) {
            denied(state, actorId, query, "write_failed", timestampEpochMillis, transactionId);
            return new Result(Status.WRITE_FAILED, transactionId, Optional.empty(), selection.totalEntries(), contents.length);
        }
    }

    static void auditInvalidQuery(
            PlatformSavedData state, UUID actorId, String queryText, long timestampEpochMillis, UUID transactionId) {
        if (state == null || actorId == null || !state.isWritable() || !validTransaction(transactionId)
                || timestampEpochMillis < 0 || state.auditTransaction(transactionId).isPresent()) {
            return;
        }
        byte[] queryBytes = (queryText == null ? "" : queryText).getBytes(StandardCharsets.UTF_8);
        String evidence = "length=" + queryBytes.length + ";sha256=" + sha256(queryBytes);
        state.appendDeniedAudit(new AuditEntry(
                timestampEpochMillis, actorId, EXPORT_DENIED, "audit_export:" + transactionId,
                Optional.empty(), Optional.empty(), evidence, "denied", "invalid_query", transactionId),
                DENIED_AUDIT_INTERVAL_MILLIS);
    }

    static byte[] jsonLines(List<AuditEntry> entries) {
        StringBuilder result = new StringBuilder();
        for (AuditEntry entry : entries) {
            JsonObject json = new JsonObject();
            json.addProperty("timestamp", entry.timestampEpochMillis());
            json.addProperty("actor", entry.actorId().toString());
            json.addProperty("action", entry.actionType().toString());
            json.addProperty("target", entry.target());
            entry.dimension().ifPresent(value -> json.addProperty("dimension", value.toString()));
            entry.position().ifPresent(value -> {
                json.addProperty("x", value.getX());
                json.addProperty("y", value.getY());
                json.addProperty("z", value.getZ());
            });
            json.addProperty("before", entry.beforeValue());
            json.addProperty("after", entry.afterValue());
            json.addProperty("reason", entry.reason());
            json.addProperty("transaction", entry.transactionId().toString());
            result.append(json).append('\n');
        }
        return result.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void denied(
            PlatformSavedData state, UUID actorId, AuditQuery query, String denial,
            long timestampEpochMillis, UUID transactionId) {
        UUID auditTransactionId = state.auditTransaction(transactionId).isEmpty()
                ? transactionId
                : UUID.nameUUIDFromBytes(("audit_export_denied:" + transactionId + ":" + actorId + ":"
                        + timestampEpochMillis).getBytes(StandardCharsets.UTF_8));
        state.appendDeniedAudit(new AuditEntry(
                timestampEpochMillis, actorId, EXPORT_DENIED, "audit_export:" + transactionId,
                Optional.empty(), Optional.empty(), query.canonical(), "denied", denial, auditTransactionId),
                DENIED_AUDIT_INTERVAL_MILLIS);
    }

    private static Optional<String> normalizeReason(String reason) {
        String normalized = reason == null ? "" : reason.strip();
        return normalized.isEmpty() || normalized.length() > AdministrationService.MAX_REASON_LENGTH
                ? Optional.empty()
                : Optional.of(normalized);
    }

    private static String sha256(byte[] contents) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(contents));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static Result restoreRecordedExport(
            PlatformSavedData state, AuditExportStore store, AuditQuery query, AuditEntry audit, UUID transactionId) {
        Optional<ExportEvidence> evidence = ExportEvidence.parse(audit.afterValue(), transactionId);
        if (evidence.isEmpty()) {
            return new Result(Status.TRANSACTION_CONFLICT, transactionId, Optional.empty(), 0, 0);
        }
        PlatformSavedData.AuditSelection selection = state.selectAudit(query, MAX_EXPORT_ROWS);
        if (selection.totalEntries() > MAX_EXPORT_ROWS) {
            return new Result(Status.TRANSACTION_CONFLICT, transactionId, Optional.empty(), selection.totalEntries(), 0);
        }
        byte[] contents = jsonLines(selection.entries());
        ExportEvidence expected = evidence.orElseThrow();
        if (contents.length != expected.bytes() || selection.totalEntries() != expected.rows()
                || !sha256(contents).equals(expected.sha256())) {
            return new Result(Status.TRANSACTION_CONFLICT, transactionId, Optional.empty(),
                    selection.totalEntries(), contents.length);
        }
        try {
            Path path = store.write(transactionId, contents, true).path();
            return new Result(Status.DUPLICATE, transactionId, Optional.of(path), expected.rows(), expected.bytes());
        } catch (AuditExportStore.ExportException exception) {
            return new Result(Status.WRITE_FAILED, transactionId, Optional.empty(), expected.rows(), expected.bytes());
        }
    }

    private static boolean validTransaction(UUID transactionId) {
        return transactionId != null && (transactionId.getMostSignificantBits() != 0L
                || transactionId.getLeastSignificantBits() != 0L);
    }

    private static Identifier action(String path) {
        return Identifier.fromNamespaceAndPath(Rovenfall.MOD_ID, path);
    }

    enum Status {
        SUCCESS,
        DUPLICATE,
        UNAUTHORIZED,
        READ_ONLY_SCHEMA,
        INVALID_REASON,
        INVALID_REQUEST,
        TRANSACTION_CONFLICT,
        LIMIT_EXCEEDED,
        WRITE_FAILED
    }

    record Result(Status status, UUID transactionId, Optional<Path> path, int rows, long bytes) {
        Result {
            path = path == null ? Optional.empty() : path;
        }
    }

    private record ExportEvidence(int rows, long bytes, String sha256) {
        private static Optional<ExportEvidence> parse(String value, UUID transactionId) {
            if (value == null) {
                return Optional.empty();
            }
            String[] parts = value.split(";", -1);
            if (parts.length != 4
                    || !parts[0].startsWith("rows=")
                    || !parts[1].startsWith("bytes=")
                    || !parts[2].startsWith("sha256=")
                    || !parts[3].equals("file=audit-" + transactionId + ".jsonl")) {
                return Optional.empty();
            }
            try {
                int rows = Integer.parseInt(parts[0].substring("rows=".length()));
                long bytes = Long.parseLong(parts[1].substring("bytes=".length()));
                String sha256 = parts[2].substring("sha256=".length());
                if (rows < 0 || rows > MAX_EXPORT_ROWS || bytes < 0 || bytes > MAX_EXPORT_BYTES
                        || !sha256.matches("[0-9a-f]{64}")) {
                    return Optional.empty();
                }
                return Optional.of(new ExportEvidence(rows, bytes, sha256));
            } catch (NumberFormatException exception) {
                return Optional.empty();
            }
        }
    }
}
