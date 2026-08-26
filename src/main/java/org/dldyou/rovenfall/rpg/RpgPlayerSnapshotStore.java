package org.dldyou.rovenfall.rpg;

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

/** Atomic on-disk snapshots for RPG state, independent of the resettable wilderness. */
public final class RpgPlayerSnapshotStore {
    private static final long MAX_SNAPSHOT_BYTES = 64L * 1024L * 1024L;
    private final Path directory;

    public RpgPlayerSnapshotStore(Path directory) {
        this.directory = directory.toAbsolutePath().normalize();
    }

    public static RpgPlayerSnapshotStore forServer(MinecraftServer server) {
        return new RpgPlayerSnapshotStore(server.getWorldPath(LevelResource.ROOT)
                .resolve("rovenfall").resolve("snapshots").resolve("rpg"));
    }

    public void write(UUID snapshotId, RpgPlayerSavedData state) throws SnapshotException {
        if (snapshotId == null || state == null) {
            throw new SnapshotException("Snapshot ID and state are required");
        }
        if (!state.isWritable()) {
            throw new SnapshotException("Cannot snapshot an unsupported RPG schema");
        }
        Path temporaryFile = null;
        try {
            Files.createDirectories(directory);
            Path target = snapshotPath(snapshotId);
            if (Files.exists(target)) {
                throw new SnapshotException("Snapshot already exists");
            }
            var encoded = RpgPlayerSavedData.CODEC.encodeStart(NbtOps.INSTANCE, state).getOrThrow();
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

    public RpgPlayerSavedData read(UUID snapshotId) throws SnapshotException {
        if (snapshotId == null) {
            throw new SnapshotException("Snapshot ID is required");
        }
        try {
            Path source = snapshotPath(snapshotId);
            if (!Files.isRegularFile(source) || Files.size(source) > MAX_SNAPSHOT_BYTES) {
                throw new SnapshotException("Snapshot is missing or too large");
            }
            CompoundTag tag = NbtIo.readCompressed(source, NbtAccounter.create(MAX_SNAPSHOT_BYTES));
            RpgPlayerSavedData state = RpgPlayerSavedData.CODEC.parse(NbtOps.INSTANCE, tag).getOrThrow();
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

    public static final class SnapshotException extends Exception {
        public SnapshotException(String message) {
            super(message);
        }

        public SnapshotException(Throwable cause) {
            super(cause);
        }
    }
}
