package org.dldyou.rovenfall.administration;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

final class AuditExportStore {
    private final Path directory;

    AuditExportStore(Path directory) {
        this.directory = directory.toAbsolutePath().normalize();
    }

    static AuditExportStore forServer(MinecraftServer server) {
        return new AuditExportStore(server.getWorldPath(LevelResource.ROOT)
                .resolve("rovenfall").resolve("exports").resolve("audit"));
    }

    WriteResult write(UUID transactionId, byte[] contents, boolean repairRecordedExport) throws ExportException {
        Path temporary = null;
        try {
            Files.createDirectories(directory);
            Path target = path(transactionId);
            if (Files.exists(target)) {
                return existing(target, contents, repairRecordedExport);
            }
            temporary = Files.createTempFile(directory, transactionId + ".", ".tmp");
            Files.write(temporary, contents);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
                temporary = null;
                return new WriteResult(target, false);
            } catch (FileAlreadyExistsException exception) {
                return existing(target, contents, repairRecordedExport);
            } catch (AtomicMoveNotSupportedException exception) {
                throw new ExportException("Atomic audit export is not supported", exception);
            }
        } catch (IOException exception) {
            throw new ExportException("Could not write audit export", exception);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                }
            }
        }
    }

    private WriteResult existing(Path target, byte[] contents, boolean repairRecordedExport)
            throws IOException, ExportException {
        if (Files.isRegularFile(target) && Files.size(target) <= AuditExportService.MAX_EXPORT_BYTES
                && Arrays.equals(Files.readAllBytes(target), contents)) {
            return new WriteResult(target, true);
        }
        if (!repairRecordedExport) {
            throw new ExportException("Existing audit export does not match this transaction");
        }
        Path temporary = Files.createTempFile(directory, target.getFileName() + ".", ".repair.tmp");
        try {
            Files.write(temporary, contents);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return new WriteResult(target, true);
        } catch (AtomicMoveNotSupportedException exception) {
            throw new ExportException("Atomic audit export repair is not supported", exception);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private Path path(UUID transactionId) {
        return directory.resolve("audit-" + transactionId + ".jsonl");
    }

    record WriteResult(Path path, boolean existing) {
    }

    static final class ExportException extends Exception {
        ExportException(String message) {
            super(message);
        }

        ExportException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
