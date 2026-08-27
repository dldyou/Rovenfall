package org.dldyou.rovenfall.administration;

import com.mojang.serialization.Codec;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.dldyou.rovenfall.world.WorldTopology;

final class WildernessResetStore {
    static final int MAX_SNAPSHOTS = 8;
    private static final long MAX_FILES = 1_000_000L;
    private static final long MAX_MANIFEST_BYTES = 1L * 1024L * 1024L;
    private final Path root;

    WildernessResetStore(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    static WildernessResetStore forServer(MinecraftServer server) {
        return new WildernessResetStore(server.getWorldPath(LevelResource.ROOT)
                .resolve("rovenfall").resolve("wilderness-resets"));
    }

    SnapshotEvidence createSnapshot(UUID snapshotId, Path wildernessPath) throws StoreException {
        Path target = snapshotWorld(snapshotId);
        Path temporary = snapshotDirectory(snapshotId).resolveSibling(snapshotId + ".tmp");
        try {
            requireWorldDirectory(wildernessPath);
            Files.createDirectories(root.resolve("snapshots"));
            if (Files.exists(target) || Files.exists(temporary) || snapshotCount() >= MAX_SNAPSHOTS) {
                throw new StoreException("snapshot_unavailable");
            }
            copyTree(wildernessPath, temporary.resolve("world"));
            SnapshotEvidence evidence = inspectTree(temporary.resolve("world"));
            moveAtomic(temporary, snapshotDirectory(snapshotId));
            return evidence;
        } catch (IOException | StoreException exception) {
            deleteQuietly(temporary);
            throw new StoreException(exception);
        }
    }

    void discardSnapshot(UUID snapshotId) {
        deleteQuietly(snapshotDirectory(snapshotId));
    }

    SnapshotEvidence snapshotEvidence(UUID snapshotId) throws StoreException {
        return inspectTree(snapshotWorld(snapshotId));
    }

    void prepareReset(WildernessResetState.Operation operation) throws StoreException {
        prepareEmptyStaging(operation.transactionId());
    }

    void prepareRestore(WildernessResetState.Operation operation) throws StoreException {
        Path source = snapshotWorld(operation.snapshotId());
        try {
            SnapshotEvidence evidence = inspectTree(source);
            if (!evidence.matches(operation)) {
                throw new StoreException("snapshot_evidence_mismatch");
            }
            Path staging = stagingWorld(operation.transactionId());
            if (Files.exists(stagingDirectory(operation.transactionId()))) {
                throw new StoreException("staging_exists");
            }
            Files.createDirectories(stagingDirectory(operation.transactionId()));
            copyTree(source, staging);
            if (!inspectTree(staging).matches(operation)) {
                throw new StoreException("staging_evidence_mismatch");
            }
        } catch (IOException | StoreException exception) {
            deleteQuietly(stagingDirectory(operation.transactionId()));
            throw new StoreException(exception);
        }
    }

    void writePending(WildernessResetState.Operation operation) throws StoreException {
        if (!Files.isDirectory(stagingWorld(operation.transactionId()))
                || Files.exists(retiredWorld(operation.transactionId()))) {
            throw new StoreException("staging_missing");
        }
        writeAtomic(pendingPath(), WildernessResetState.Operation.CODEC, operation);
    }

    Optional<LifecycleResult> applyPending(Path wildernessPath) throws StoreException {
        if (!Files.isRegularFile(pendingPath())) {
            return Optional.empty();
        }
        WildernessResetState.Operation operation = read(pendingPath(), WildernessResetState.Operation.CODEC);
        Path target = wildernessPath.toAbsolutePath().normalize();
        Path staging = stagingWorld(operation.transactionId());
        Path retired = retiredWorld(operation.transactionId());
        ensureUnder(target, target.getParent());
        try {
            Files.createDirectories(retired.getParent());
            if (Files.exists(retired) && Files.exists(target) && !Files.exists(staging)) {
                markApplied(operation);
                return Optional.of(new LifecycleResult(operation, true, "completed"));
            }
            if (!Files.exists(retired)) {
                requireWorldDirectory(target);
                moveAtomic(target, retired);
            }
            try {
                if (!Files.exists(target)) {
                    moveAtomic(staging, target);
                }
            } catch (IOException applyFailure) {
                if (!Files.exists(target) && Files.exists(retired)) {
                    moveAtomic(retired, target);
                }
                throw applyFailure;
            }
            markApplied(operation);
            return Optional.of(new LifecycleResult(operation, true, "completed"));
        } catch (IOException exception) {
            if (Files.exists(target)) {
                markFailed(operation, "filesystem_apply_failed");
                return Optional.of(new LifecycleResult(operation, false, "filesystem_apply_failed"));
            }
            throw new StoreException(exception);
        }
    }

    Optional<LifecycleResult> lifecycleResult() throws StoreException {
        Path applied = appliedPath();
        Path failed = failedPath();
        if (Files.isRegularFile(applied)) {
            WildernessResetState.Operation operation = read(applied, WildernessResetState.Operation.CODEC);
            return Optional.of(new LifecycleResult(operation, true, "completed"));
        }
        if (Files.isRegularFile(failed)) {
            WildernessResetState.Operation operation = read(failed, WildernessResetState.Operation.CODEC);
            return Optional.of(new LifecycleResult(operation, false, "filesystem_apply_failed"));
        }
        return Optional.empty();
    }

    void acknowledgeLifecycleResult(boolean succeeded) throws StoreException {
        deleteFile(succeeded ? appliedPath() : failedPath());
    }

    boolean hasPending() {
        return Files.isRegularFile(pendingPath()) || Files.isRegularFile(appliedPath()) || Files.isRegularFile(failedPath());
    }

    void cleanupStaging(UUID transactionId) {
        deleteQuietly(stagingDirectory(transactionId));
    }

    void cleanupCommittedSwap(UUID transactionId) {
        deleteQuietly(stagingDirectory(transactionId));
        deleteQuietly(root.resolve("retired").resolve(transactionId.toString()).normalize());
    }

    private void prepareEmptyStaging(UUID transactionId) throws StoreException {
        Path directory = stagingDirectory(transactionId);
        try {
            if (Files.exists(directory)) {
                throw new StoreException("staging_exists");
            }
            Files.createDirectories(stagingWorld(transactionId));
        } catch (IOException exception) {
            deleteQuietly(directory);
            throw new StoreException(exception);
        }
    }

    private long snapshotCount() throws IOException {
        Path snapshots = root.resolve("snapshots");
        if (!Files.isDirectory(snapshots)) {
            return 0;
        }
        try (Stream<Path> paths = Files.list(snapshots)) {
            return paths.filter(Files::isDirectory).count();
        }
    }

    private void markApplied(WildernessResetState.Operation operation) throws StoreException {
        writeAtomic(appliedPath(), WildernessResetState.Operation.CODEC, operation);
        deleteFile(pendingPath());
    }

    private void markFailed(WildernessResetState.Operation operation, String detail) throws StoreException {
        writeAtomic(failedPath(), WildernessResetState.Operation.CODEC, operation);
        deleteFile(pendingPath());
    }

    private static void requireWorldDirectory(Path path) throws StoreException {
        if (path == null || !Files.isDirectory(path) || Files.isSymbolicLink(path)) {
            throw new StoreException("wilderness_unavailable");
        }
    }

    private static void copyTree(Path source, Path target) throws IOException, StoreException {
        try (Stream<Path> paths = Files.walk(source)) {
            List<Path> entries = paths.sorted().toList();
            if (entries.size() > MAX_FILES) {
                throw new StoreException("world_file_limit");
            }
            for (Path entry : entries) {
                if (Files.isSymbolicLink(entry)) {
                    throw new StoreException("world_symlink_rejected");
                }
                Path destination = target.resolve(source.relativize(entry).toString()).normalize();
                ensureUnder(destination, target);
                if (Files.isDirectory(entry)) {
                    Files.createDirectories(destination);
                } else if (Files.isRegularFile(entry)) {
                    Files.createDirectories(destination.getParent());
                    Files.copy(entry, destination, StandardCopyOption.COPY_ATTRIBUTES);
                } else {
                    throw new StoreException("world_entry_rejected");
                }
            }
        }
    }

    private static SnapshotEvidence inspectTree(Path source) throws StoreException {
        requireWorldDirectory(source);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long files = 0;
            long bytes = 0;
            try (Stream<Path> paths = Files.walk(source)) {
                for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
                    if (Files.isSymbolicLink(path) || ++files > MAX_FILES) {
                        throw new StoreException("snapshot_entry_rejected");
                    }
                    byte[] name = source.relativize(path).toString().replace('\\', '/').getBytes(StandardCharsets.UTF_8);
                    digest.update(name);
                    digest.update((byte) 0);
                    try (InputStream input = Files.newInputStream(path)) {
                        byte[] buffer = new byte[8192];
                        int read;
                        while ((read = input.read(buffer)) >= 0) {
                            if (read > 0) {
                                digest.update(buffer, 0, read);
                                bytes = Math.addExact(bytes, read);
                            }
                        }
                    }
                }
            }
            return new SnapshotEvidence(files, bytes, HexFormat.of().formatHex(digest.digest()));
        } catch (IOException | NoSuchAlgorithmException | ArithmeticException exception) {
            throw new StoreException(exception);
        }
    }

    private <T> void writeAtomic(Path target, Codec<T> codec, T value) throws StoreException {
        Path temporary = null;
        try {
            Files.createDirectories(target.getParent());
            if (Files.exists(target)) {
                throw new StoreException("operation_already_pending");
            }
            var encoded = codec.encodeStart(NbtOps.INSTANCE, value).getOrThrow();
            if (!(encoded instanceof CompoundTag tag)) {
                throw new StoreException("manifest_invalid");
            }
            temporary = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".tmp");
            NbtIo.writeCompressed(tag, temporary);
            if (Files.size(temporary) > MAX_MANIFEST_BYTES) {
                throw new StoreException("manifest_too_large");
            }
            moveAtomic(temporary, target);
            temporary = null;
        } catch (IOException | RuntimeException exception) {
            throw new StoreException(exception);
        } finally {
            if (temporary != null) {
                deleteQuietly(temporary);
            }
        }
    }

    private static <T> T read(Path source, Codec<T> codec) throws StoreException {
        try {
            if (!Files.isRegularFile(source) || Files.size(source) > MAX_MANIFEST_BYTES) {
                throw new StoreException("manifest_missing_or_large");
            }
            CompoundTag tag = NbtIo.readCompressed(source, NbtAccounter.create(MAX_MANIFEST_BYTES));
            return codec.parse(NbtOps.INSTANCE, tag).getOrThrow();
        } catch (IOException | RuntimeException exception) {
            throw new StoreException(exception);
        }
    }

    private static void moveAtomic(Path source, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            throw new IOException("Atomic move is not supported for Wilderness reset", exception);
        }
    }

    private static void ensureUnder(Path candidate, Path directory) throws StoreException {
        Path normalizedDirectory = directory.toAbsolutePath().normalize();
        if (!candidate.toAbsolutePath().normalize().startsWith(normalizedDirectory)) {
            throw new StoreException("path_escape");
        }
    }

    private static void deleteFile(Path path) throws StoreException {
        try {
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            throw new StoreException(exception);
        }
    }

    private static void deleteQuietly(Path directory) {
        if (directory == null || !Files.exists(directory)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException ignored) {
        }
    }

    private Path snapshotDirectory(UUID snapshotId) {
        return root.resolve("snapshots").resolve(snapshotId.toString()).normalize();
    }

    private Path snapshotWorld(UUID snapshotId) {
        return snapshotDirectory(snapshotId).resolve("world");
    }

    private Path stagingDirectory(UUID transactionId) {
        return root.resolve("staging").resolve(transactionId.toString()).normalize();
    }

    private Path stagingWorld(UUID transactionId) {
        return stagingDirectory(transactionId).resolve("world");
    }

    private Path retiredWorld(UUID transactionId) {
        return root.resolve("retired").resolve(transactionId.toString()).resolve("world").normalize();
    }

    private Path pendingPath() {
        return root.resolve("pending.nbt");
    }

    private Path appliedPath() {
        return root.resolve("applied.nbt");
    }

    private Path failedPath() {
        return root.resolve("failed.nbt");
    }

    record SnapshotEvidence(long fileCount, long byteCount, String sha256) {
        boolean matches(WildernessResetState.Operation operation) {
            return fileCount == operation.fileCount() && byteCount == operation.byteCount()
                    && sha256.equals(operation.sha256());
        }
    }

    record LifecycleResult(WildernessResetState.Operation operation, boolean succeeded, String detail) {
    }

    static final class StoreException extends Exception {
        StoreException(String message) {
            super(message);
        }

        StoreException(Throwable cause) {
            super(cause);
        }
    }
}
