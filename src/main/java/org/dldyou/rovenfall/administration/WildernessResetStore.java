package org.dldyou.rovenfall.administration;

import com.mojang.serialization.Codec;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.dldyou.rovenfall.rpg.ActivityWorldSavedData;
import org.dldyou.rovenfall.world.WorldTopology;

final class WildernessResetStore {
    static final int MAX_SNAPSHOTS = 8;
    private static final String ACTIVITY_MARKERS_FILE = "activity-markers.nbt";
    private static final long MAX_FILES = 1_000_000L;
    private static final long MAX_OPERATION_MANIFEST_BYTES = 1L * 1024L * 1024L;
    private static final long MAX_ACTIVITY_MARKERS_BYTES = 32L * 1024L * 1024L;
    private final Path root;
    private final Path trustedWorldRoot;

    WildernessResetStore(Path root) {
        this(root, root.toAbsolutePath().normalize().getParent());
    }

    private WildernessResetStore(Path root, Path trustedWorldRoot) {
        this.root = root.toAbsolutePath().normalize();
        this.trustedWorldRoot = trustedWorldRoot.toAbsolutePath().normalize();
    }

    static WildernessResetStore forServer(MinecraftServer server) {
        Path worldRoot = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        return new WildernessResetStore(
                worldRoot.resolve("rovenfall").resolve("wilderness-resets"), worldRoot);
    }

    SnapshotEvidence createSnapshot(
            UUID snapshotId,
            Path wildernessPath,
            ActivityWorldSavedData.DimensionSnapshot activityMarkers) throws StoreException {
        Path target = snapshotWorld(snapshotId);
        Path temporary = snapshotDirectory(snapshotId).resolveSibling(snapshotId + ".tmp");
        try {
            requireManagedPath(root);
            requireManagedPath(wildernessPath);
            requireManagedPath(target);
            requireManagedPath(temporary);
            requireWorldDirectory(wildernessPath);
            Files.createDirectories(root.resolve("snapshots"));
            if (Files.exists(snapshotDirectory(snapshotId))
                    || Files.exists(temporary)
                    || snapshotCount() >= MAX_SNAPSHOTS) {
                throw new StoreException("snapshot_unavailable");
            }
            copyTree(wildernessPath, temporary.resolve("world"));
            writeAtomic(temporary.resolve(ACTIVITY_MARKERS_FILE),
                    ActivityWorldSavedData.DimensionSnapshot.CODEC, activityMarkers, MAX_ACTIVITY_MARKERS_BYTES);
            SnapshotEvidence evidence = inspectTree(temporary);
            moveAtomic(temporary, snapshotDirectory(snapshotId));
            return evidence;
        } catch (IOException | StoreException exception) {
            deleteQuietly(temporary);
            throw new StoreException(exception);
        }
    }

    SnapshotEvidence createSnapshot(UUID snapshotId, Path wildernessPath) throws StoreException {
        return createSnapshot(snapshotId, wildernessPath,
                ActivityWorldSavedData.DimensionSnapshot.empty(WorldTopology.WILDERNESS));
    }

    void discardSnapshot(UUID snapshotId) {
        deleteQuietly(snapshotDirectory(snapshotId));
    }

    SnapshotEvidence snapshotEvidence(UUID snapshotId) throws StoreException {
        requireManagedPath(snapshotDirectory(snapshotId));
        return inspectTree(snapshotDirectory(snapshotId));
    }

    ActivityWorldSavedData.DimensionSnapshot activityMarkers(UUID snapshotId) throws StoreException {
        Path source = snapshotDirectory(snapshotId).resolve(ACTIVITY_MARKERS_FILE).normalize();
        requireManagedPath(source);
        return read(source, ActivityWorldSavedData.DimensionSnapshot.CODEC, MAX_ACTIVITY_MARKERS_BYTES);
    }

    SnapshotEvidence validateOrMigrateSnapshot(UUID snapshotId, SnapshotEvidence recorded) throws StoreException {
        if (recorded == null) {
            throw new StoreException("snapshot_evidence_missing");
        }
        Path directory = snapshotDirectory(snapshotId);
        Path markers = directory.resolve(ACTIVITY_MARKERS_FILE).normalize();
        requireManagedPath(directory);
        SnapshotEvidence combined = inspectTree(directory);
        if (Files.isRegularFile(markers, LinkOption.NOFOLLOW_LINKS)) {
            ActivityWorldSavedData.DimensionSnapshot snapshot = activityMarkers(snapshotId);
            if (!snapshot.dimension().equals(WorldTopology.WILDERNESS)) {
                throw new StoreException("activity_marker_dimension_mismatch");
            }
            if (combined.equals(recorded)) {
                return combined;
            }
            SnapshotEvidence legacy = inspectTree(snapshotWorld(snapshotId));
            if (legacy.equals(recorded) && snapshot.positions().isEmpty()) {
                return combined;
            }
            throw new StoreException("snapshot_evidence_mismatch");
        }
        SnapshotEvidence legacy = inspectTree(snapshotWorld(snapshotId));
        if (!legacy.equals(recorded)) {
            throw new StoreException("snapshot_evidence_mismatch");
        }
        writeAtomic(markers, ActivityWorldSavedData.DimensionSnapshot.CODEC,
                ActivityWorldSavedData.DimensionSnapshot.empty(WorldTopology.WILDERNESS),
                MAX_ACTIVITY_MARKERS_BYTES);
        return inspectTree(directory);
    }

    void prepareReset(WildernessResetState.Operation operation) throws StoreException {
        prepareEmptyStaging(operation.transactionId());
    }

    void prepareRestore(WildernessResetState.Operation operation) throws StoreException {
        Path sourceDirectory = snapshotDirectory(operation.snapshotId());
        Path source = sourceDirectory.resolve("world");
        try {
            requireManagedPath(sourceDirectory);
            requireManagedPath(stagingDirectory(operation.transactionId()));
            validateOrMigrateSnapshot(operation.snapshotId(), targetEvidence(operation));
            SnapshotEvidence worldEvidence = inspectTree(source);
            Path staging = stagingWorld(operation.transactionId());
            if (Files.exists(stagingDirectory(operation.transactionId()))) {
                throw new StoreException("staging_exists");
            }
            Files.createDirectories(stagingDirectory(operation.transactionId()));
            copyTree(source, staging);
            if (!inspectTree(staging).equals(worldEvidence)) {
                throw new StoreException("staging_evidence_mismatch");
            }
        } catch (IOException | StoreException exception) {
            deleteQuietly(stagingDirectory(operation.transactionId()));
            throw new StoreException(exception);
        }
    }

    void writePending(WildernessResetState.Operation operation) throws StoreException {
        requireManagedPath(stagingWorld(operation.transactionId()));
        requireManagedPath(retiredWorld(operation.transactionId()));
        requireManagedPath(pendingPath());
        if (!Files.isDirectory(stagingWorld(operation.transactionId()))
                || Files.exists(retiredWorld(operation.transactionId()))) {
            throw new StoreException("staging_missing");
        }
        validatePreparedArtifacts(operation, stagingWorld(operation.transactionId()));
        writeAtomic(pendingPath(), WildernessResetState.Operation.CODEC, operation);
    }

    Optional<LifecycleResult> applyPending(Path wildernessPath) throws StoreException {
        requireManagedPath(pendingPath());
        if (!Files.isRegularFile(pendingPath())) {
            return Optional.empty();
        }
        WildernessResetState.Operation operation = read(pendingPath(), WildernessResetState.Operation.CODEC);
        Path target = wildernessPath.toAbsolutePath().normalize();
        Path staging = stagingWorld(operation.transactionId());
        Path retired = retiredWorld(operation.transactionId());
        requireManagedPath(target);
        requireManagedPath(staging);
        requireManagedPath(retired);
        Optional<LifecycleResult> existing = lifecycleResult();
        if (existing.isPresent()) {
            LifecycleResult result = existing.orElseThrow();
            if (!result.operation().equals(operation)) {
                throw new StoreException("lifecycle_operation_mismatch");
            }
            deleteFile(pendingPath());
            return existing;
        }
        try {
            Files.createDirectories(retired.getParent());
            if (Files.exists(retired) && Files.exists(target) && !Files.exists(staging)) {
                validateAppliedWorld(operation, target);
                markApplied(operation);
                return Optional.of(new LifecycleResult(operation, true, "completed"));
            }
            if (Files.exists(retired) && Files.exists(target)) {
                throw new StoreException("ambiguous_swap_state");
            }
            try {
                validatePreparedArtifacts(operation, staging);
            } catch (StoreException validationFailure) {
                if (!Files.exists(retired) && Files.exists(target)) {
                    markFailed(operation, "artifact_validation_failed");
                    return Optional.of(new LifecycleResult(operation, false, "artifact_validation_failed"));
                }
                throw validationFailure;
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

    private void validatePreparedArtifacts(WildernessResetState.Operation operation, Path staging)
            throws StoreException {
        validateOrMigrateOperationSnapshots(operation);
        SnapshotEvidence stagingEvidence = inspectTree(staging);
        if (operation.kind() == WildernessResetState.Kind.RESET) {
            if (stagingEvidence.fileCount() != 0L || stagingEvidence.byteCount() != 0L) {
                throw new StoreException("reset_staging_not_empty");
            }
            return;
        }
        SnapshotEvidence sourceEvidence = inspectTree(snapshotWorld(operation.snapshotId()));
        if (!stagingEvidence.equals(sourceEvidence)) {
            throw new StoreException("staging_evidence_mismatch");
        }
    }

    private void validateAppliedWorld(WildernessResetState.Operation operation, Path appliedWorld)
            throws StoreException {
        SnapshotEvidence appliedEvidence = inspectTree(appliedWorld);
        if (operation.kind() == WildernessResetState.Kind.RESET) {
            if (appliedEvidence.fileCount() != 0L || appliedEvidence.byteCount() != 0L) {
                throw new StoreException("reset_world_not_empty");
            }
            return;
        }
        if (!appliedEvidence.equals(inspectTree(snapshotWorld(operation.snapshotId())))) {
            throw new StoreException("applied_world_evidence_mismatch");
        }
    }

    void validateOrMigrateOperationSnapshots(WildernessResetState.Operation operation) throws StoreException {
        validateOrMigrateSnapshot(operation.snapshotId(), targetEvidence(operation));
        if (!operation.recoverySnapshotId().equals(operation.snapshotId())) {
            validateOrMigrateSnapshot(operation.recoverySnapshotId(), recoveryEvidence(operation));
        }
    }

    private static SnapshotEvidence targetEvidence(WildernessResetState.Operation operation) {
        return new SnapshotEvidence(operation.fileCount(), operation.byteCount(), operation.sha256());
    }

    private static SnapshotEvidence recoveryEvidence(WildernessResetState.Operation operation) {
        return new SnapshotEvidence(
                operation.recoveryFileCount(), operation.recoveryByteCount(), operation.recoverySha256());
    }

    Optional<LifecycleResult> lifecycleResult() throws StoreException {
        Path applied = appliedPath();
        Path failed = failedPath();
        requireManagedPath(applied);
        requireManagedPath(failed);
        if (Files.exists(applied, LinkOption.NOFOLLOW_LINKS)
                && Files.exists(failed, LinkOption.NOFOLLOW_LINKS)) {
            throw new StoreException("conflicting_lifecycle_results");
        }
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
        try {
            requireManagedPath(root);
            return Files.isRegularFile(pendingPath())
                    || Files.isRegularFile(appliedPath())
                    || Files.isRegularFile(failedPath());
        } catch (StoreException exception) {
            return true;
        }
    }

    void cleanupStaging(UUID transactionId) {
        deleteQuietly(stagingDirectory(transactionId));
    }

    void cleanupCommittedSwap(UUID transactionId) {
        deleteQuietly(stagingDirectory(transactionId));
        deleteQuietly(root.resolve("retired").resolve(transactionId.toString()).normalize());
    }

    void discardUnrecordedSnapshots(Set<UUID> retainedSnapshotIds) throws StoreException {
        if (retainedSnapshotIds == null) {
            throw new StoreException("retained_snapshot_ids_missing");
        }
        Path snapshots = root.resolve("snapshots").normalize();
        requireManagedPath(snapshots);
        if (!Files.isDirectory(snapshots)) {
            return;
        }
        try (Stream<Path> entries = Files.list(snapshots)) {
            for (Path entry : entries.toList()) {
                requireManagedPath(entry);
                String name = entry.getFileName().toString();
                boolean temporary = name.endsWith(".tmp");
                String identifier = temporary ? name.substring(0, name.length() - 4) : name;
                UUID snapshotId;
                try {
                    snapshotId = UUID.fromString(identifier);
                } catch (IllegalArgumentException ignored) {
                    continue;
                }
                if (temporary || !retainedSnapshotIds.contains(snapshotId)) {
                    deleteQuietly(entry);
                }
            }
        } catch (IOException exception) {
            throw new StoreException(exception);
        }
    }

    private void prepareEmptyStaging(UUID transactionId) throws StoreException {
        Path directory = stagingDirectory(transactionId);
        try {
            requireManagedPath(directory);
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
        try {
            requireManagedPath(snapshots);
        } catch (StoreException exception) {
            throw new IOException(exception);
        }
        if (!Files.isDirectory(snapshots)) {
            return 0;
        }
        try (Stream<Path> paths = Files.list(snapshots)) {
            return paths.filter(Files::isDirectory).count();
        }
    }

    private void markApplied(WildernessResetState.Operation operation) throws StoreException {
        writeLifecycleResult(appliedPath(), operation);
        deleteFile(pendingPath());
    }

    private void markFailed(WildernessResetState.Operation operation, String detail) throws StoreException {
        writeLifecycleResult(failedPath(), operation);
        deleteFile(pendingPath());
    }

    private void writeLifecycleResult(Path target, WildernessResetState.Operation operation) throws StoreException {
        requireManagedPath(target);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            WildernessResetState.Operation retained = read(target, WildernessResetState.Operation.CODEC);
            if (!retained.equals(operation)) {
                throw new StoreException("lifecycle_operation_mismatch");
            }
            return;
        }
        writeAtomic(target, WildernessResetState.Operation.CODEC, operation);
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
                for (Path path : paths.sorted().toList()) {
                    if (path.equals(source)) {
                        continue;
                    }
                    if (Files.isSymbolicLink(path)) {
                        throw new StoreException("snapshot_entry_rejected");
                    }
                    if (Files.isDirectory(path)) {
                        continue;
                    }
                    if (!Files.isRegularFile(path) || ++files > MAX_FILES) {
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
        writeAtomic(target, codec, value, MAX_OPERATION_MANIFEST_BYTES);
    }

    private <T> void writeAtomic(Path target, Codec<T> codec, T value, long maximumBytes) throws StoreException {
        Path temporary = null;
        try {
            requireManagedPath(target);
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
            if (Files.size(temporary) > maximumBytes) {
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
        return read(source, codec, MAX_OPERATION_MANIFEST_BYTES);
    }

    private static <T> T read(Path source, Codec<T> codec, long maximumBytes) throws StoreException {
        try {
            if (!Files.isRegularFile(source) || Files.size(source) > maximumBytes) {
                throw new StoreException("manifest_missing_or_large");
            }
            CompoundTag tag = NbtIo.readCompressed(source, NbtAccounter.create(maximumBytes));
            return codec.parse(NbtOps.INSTANCE, tag).getOrThrow();
        } catch (IOException | RuntimeException exception) {
            throw new StoreException(exception);
        }
    }

    private void moveAtomic(Path source, Path target) throws IOException {
        try {
            requireManagedPath(source);
            requireManagedPath(target);
        } catch (StoreException exception) {
            throw new IOException(exception);
        }
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

    private void deleteFile(Path path) throws StoreException {
        requireManagedPath(path);
        try {
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            throw new StoreException(exception);
        }
    }

    private void deleteQuietly(Path directory) {
        if (directory == null || !Files.exists(directory)) {
            return;
        }
        try {
            requireManagedPath(directory);
        } catch (StoreException exception) {
            return;
        }
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException ignored) {
        }
    }

    private void requireManagedPath(Path candidate) throws StoreException {
        if (candidate == null) {
            throw new StoreException("managed_path_missing");
        }
        Path normalized = candidate.toAbsolutePath().normalize();
        if (!normalized.startsWith(trustedWorldRoot)) {
            throw new StoreException("path_escape");
        }
        Path current = trustedWorldRoot;
        try {
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                rejectLinkOrReparsePoint(current);
            }
            for (Path component : trustedWorldRoot.relativize(normalized)) {
                current = current.resolve(component);
                if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                    rejectLinkOrReparsePoint(current);
                }
            }
        } catch (IOException exception) {
            throw new StoreException(exception);
        }
    }

    private static void rejectLinkOrReparsePoint(Path path) throws IOException, StoreException {
        BasicFileAttributes attributes = Files.readAttributes(
                path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        Path followed = path.toRealPath();
        Path notFollowed = path.toRealPath(LinkOption.NOFOLLOW_LINKS);
        if (attributes.isSymbolicLink() || attributes.isOther() || !followed.equals(notFollowed)) {
            throw new StoreException("managed_path_link_rejected");
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
