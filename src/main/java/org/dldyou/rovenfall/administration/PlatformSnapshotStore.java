package org.dldyou.rovenfall.administration;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

final class PlatformSnapshotStore {
    private static final long MAX_SNAPSHOT_BYTES = 64L * 1024L * 1024L;
    private final Path directory;

    PlatformSnapshotStore(Path directory) {
        this.directory = directory.toAbsolutePath().normalize();
    }

    static PlatformSnapshotStore forServer(MinecraftServer server) {
        return new PlatformSnapshotStore(server.getWorldPath(LevelResource.ROOT)
                .resolve("rovenfall")
                .resolve("snapshots")
                .resolve("platform"));
    }

    void write(UUID snapshotId, PlatformSavedData state) throws SnapshotException {
        Path temporaryFile = null;
        try {
            Files.createDirectories(directory);
            Path target = snapshotPath(snapshotId);
            if (Files.exists(target)) {
                throw new SnapshotException("Snapshot already exists");
            }

            var encoded = PlatformSavedData.CODEC.encodeStart(NbtOps.INSTANCE, state).getOrThrow();
            if (!(encoded instanceof CompoundTag tag)) {
                throw new SnapshotException("Snapshot root is not a compound tag");
            }

            temporaryFile = Files.createTempFile(directory, snapshotId + ".", ".tmp");
            NbtIo.writeCompressed(tag, temporaryFile);
            if (Files.size(temporaryFile) > MAX_SNAPSHOT_BYTES) {
                throw new SnapshotException("Snapshot is too large");
            }
            NbtIo.readCompressed(temporaryFile, NbtAccounter.create(MAX_SNAPSHOT_BYTES));
            Files.move(temporaryFile, target, StandardCopyOption.ATOMIC_MOVE);
            temporaryFile = null;
        } catch (IOException | RuntimeException exception) {
            throw new SnapshotException(exception);
        } finally {
            if (temporaryFile != null) {
                try {
                    Files.deleteIfExists(temporaryFile);
                } catch (IOException ignored) {
                }
            }
        }
    }

    PlatformSavedData read(UUID snapshotId) throws SnapshotException {
        return readValidated(snapshotId).state();
    }

    ValidatedSnapshot readValidated(UUID snapshotId) throws SnapshotException {
        try {
            Path source = snapshotPath(snapshotId);
            if (!Files.isRegularFile(source)) {
                throw new SnapshotException("Snapshot is missing or too large");
            }
            byte[] content = boundedBytes(source);
            CompoundTag tag = NbtIo.readCompressed(
                    new ByteArrayInputStream(content), NbtAccounter.create(MAX_SNAPSHOT_BYTES));
            PlatformSavedData state = PlatformSavedData.CODEC.parse(NbtOps.INSTANCE, tag).getOrThrow();
            if (!state.isWritable()) {
                throw new SnapshotException("Snapshot schema is unsupported");
            }
            return new ValidatedSnapshot(state, new Evidence(content.length, sha256(content)));
        } catch (IOException | RuntimeException exception) {
            throw new SnapshotException(exception);
        }
    }

    /**
     * Returns a bounded fingerprint of the current encoded platform state without creating a snapshot.
     * The temporary encoding is decoded before it is hashed, matching the validation used for snapshots.
     */
    Evidence liveEvidence(PlatformSavedData state) throws SnapshotException {
        if (state == null) {
            throw new SnapshotException("Platform state is missing");
        }
        Path temporaryFile = null;
        try {
            Files.createDirectories(directory);
            temporaryFile = Files.createTempFile(directory, "live.", ".tmp");
            writeEncoded(state, temporaryFile);
            return evidence(temporaryFile);
        } catch (IOException | RuntimeException exception) {
            throw new SnapshotException(exception);
        } finally {
            if (temporaryFile != null) {
                try {
                    Files.deleteIfExists(temporaryFile);
                } catch (IOException ignored) {
                }
            }
        }
    }

    /** Returns evidence only after the named snapshot has passed normal bounded decode validation. */
    Evidence snapshotEvidence(UUID snapshotId) throws SnapshotException {
        if (snapshotId == null) {
            throw new SnapshotException("Snapshot id is missing");
        }
        return readValidated(snapshotId).evidence();
    }

    private static void writeEncoded(PlatformSavedData state, Path target) throws IOException, SnapshotException {
        var encoded = PlatformSavedData.CODEC.encodeStart(NbtOps.INSTANCE, state).getOrThrow();
        if (!(encoded instanceof CompoundTag tag)) {
            throw new SnapshotException("Snapshot root is not a compound tag");
        }
        NbtIo.writeCompressed(tag, target);
        if (Files.size(target) > MAX_SNAPSHOT_BYTES) {
            throw new SnapshotException("Snapshot is too large");
        }
        NbtIo.readCompressed(target, NbtAccounter.create(MAX_SNAPSHOT_BYTES));
    }

    private static Evidence evidence(Path source) throws IOException, SnapshotException {
        byte[] content = boundedBytes(source);
        return new Evidence(content.length, sha256(content));
    }

    private static byte[] boundedBytes(Path source) throws IOException, SnapshotException {
        byte[] content;
        try (InputStream input = Files.newInputStream(source)) {
            content = input.readNBytes(Math.toIntExact(MAX_SNAPSHOT_BYTES + 1L));
        }
        if (content.length > MAX_SNAPSHOT_BYTES) {
            throw new SnapshotException("Snapshot is missing or too large");
        }
        return content;
    }

    private static String sha256(byte[] bytes) throws SnapshotException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new SnapshotException(exception);
        }
    }

    private Path snapshotPath(UUID snapshotId) {
        return directory.resolve(snapshotId + ".nbt");
    }

    record Evidence(long bytes, String sha256) {
        Evidence {
            if (bytes < 0 || bytes > MAX_SNAPSHOT_BYTES || sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("Invalid snapshot evidence");
            }
        }
    }

    record ValidatedSnapshot(PlatformSavedData state, Evidence evidence) {
        ValidatedSnapshot {
            if (state == null || evidence == null) {
                throw new IllegalArgumentException("Validated snapshot is incomplete");
            }
        }
    }

    static final class SnapshotException extends Exception {
        SnapshotException(String message) {
            super(message);
        }

        SnapshotException(Throwable cause) {
            super(cause);
        }
    }
}
