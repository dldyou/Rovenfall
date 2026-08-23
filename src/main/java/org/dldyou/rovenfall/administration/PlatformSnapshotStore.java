package org.dldyou.rovenfall.administration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
        try {
            Path source = snapshotPath(snapshotId);
            if (!Files.isRegularFile(source) || Files.size(source) > MAX_SNAPSHOT_BYTES) {
                throw new SnapshotException("Snapshot is missing or too large");
            }

            CompoundTag tag = NbtIo.readCompressed(source, NbtAccounter.create(MAX_SNAPSHOT_BYTES));
            PlatformSavedData state = PlatformSavedData.CODEC.parse(NbtOps.INSTANCE, tag).getOrThrow();
            if (!state.isWritable()) {
                throw new SnapshotException("Snapshot schema is unsupported");
            }
            return state;
        } catch (IOException | RuntimeException exception) {
            throw new SnapshotException(exception);
        }
    }

    private Path snapshotPath(UUID snapshotId) {
        return directory.resolve(snapshotId + ".nbt");
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
