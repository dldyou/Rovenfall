package org.dldyou.rovenfall.mobs;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import org.dldyou.rovenfall.Rovenfall;

public final class BossRewardSavedData extends SavedData {
    public static final int CURRENT_SCHEMA_VERSION = 1;
    public static final int MAX_OPERATIONS = 10_000;
    private static final long COMPLETED_RETENTION_MILLIS = Duration.ofDays(30).toMillis();
    private static final Codec<Map<UUID, BossRewardOperation>> OPERATIONS_CODEC =
            OperationEntry.CODEC.listOf(0, MAX_OPERATIONS)
                    .flatXmap(BossRewardSavedData::operationsFromEntries,
                            BossRewardSavedData::operationEntries);

    public static final Codec<BossRewardSavedData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.optionalFieldOf("schema_version", 0).forGetter(data -> data.schemaVersion),
            OPERATIONS_CODEC.optionalFieldOf("operations", Map.of()).forGetter(data -> data.operations)
    ).apply(instance, BossRewardSavedData::decode));

    public static final SavedDataType<BossRewardSavedData> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(Rovenfall.MOD_ID, "boss_reward_state"),
            BossRewardSavedData::new,
            CODEC);

    private static final Map<Integer, UnaryOperator<PersistedState>> MIGRATIONS = Map.of(
            0, state -> state.atVersion(1));

    private final int schemaVersion;
    private final boolean writable;
    private final Map<UUID, BossRewardOperation> operations;

    public BossRewardSavedData() {
        this(CURRENT_SCHEMA_VERSION, Map.of(), true);
    }

    private BossRewardSavedData(
            int schemaVersion, Map<UUID, BossRewardOperation> operations, boolean writable) {
        this.schemaVersion = schemaVersion;
        this.operations = new LinkedHashMap<>(operations);
        this.writable = writable;
    }

    private static BossRewardSavedData decode(
            int schemaVersion, Map<UUID, BossRewardOperation> operations) {
        PersistedState original = new PersistedState(schemaVersion, operations);
        if (schemaVersion < 0 || schemaVersion > CURRENT_SCHEMA_VERSION) {
            return new BossRewardSavedData(schemaVersion, operations, false);
        }
        PersistedState candidate = original;
        while (candidate.schemaVersion() < CURRENT_SCHEMA_VERSION) {
            UnaryOperator<PersistedState> migration = MIGRATIONS.get(candidate.schemaVersion());
            if (migration == null) {
                return new BossRewardSavedData(original.schemaVersion(), original.operations(), false);
            }
            int expected = candidate.schemaVersion() + 1;
            candidate = migration.apply(candidate);
            if (candidate.schemaVersion() != expected) {
                return new BossRewardSavedData(original.schemaVersion(), original.operations(), false);
            }
        }
        return new BossRewardSavedData(candidate.schemaVersion(), candidate.operations(), true);
    }

    public static BossRewardSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    public int schemaVersion() {
        return schemaVersion;
    }

    public boolean isWritable() {
        return writable;
    }

    public int operationCount() {
        return operations.size();
    }

    public Optional<BossRewardOperation> operation(UUID transactionId) {
        return Optional.ofNullable(operations.get(transactionId));
    }

    /** Immutable, deterministically ordered evidence for bounded administrator views. */
    public List<Map.Entry<UUID, BossRewardOperation>> operations() {
        return operations.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> Map.entry(entry.getKey(), entry.getValue()))
                .toList();
    }

    public List<Map.Entry<UUID, BossRewardOperation>> pendingOperations() {
        return operations.entrySet().stream()
                .filter(entry -> entry.getValue().phase() == BossRewardOperation.Phase.PENDING
                        || entry.getValue().phase() == BossRewardOperation.Phase.CORE_APPLIED)
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> Map.entry(entry.getKey(), entry.getValue()))
                .toList();
    }

    public long cooldownUntil(Identifier bossId, UUID playerId, UUID exceptTransactionId, long timestamp) {
        return operations.entrySet().stream()
                .filter(entry -> exceptTransactionId == null || !entry.getKey().equals(exceptTransactionId))
                .map(Map.Entry::getValue)
                .filter(operation -> operation.phase() != BossRewardOperation.Phase.FAILED)
                .filter(operation -> operation.bossId().equals(bossId) && operation.playerId().equals(playerId))
                .mapToLong(BossRewardOperation::cooldownUntilEpochMillis)
                .filter(deadline -> deadline > timestamp)
                .max()
                .orElse(0L);
    }

    public BatchStatus putBatch(Map<UUID, BossRewardOperation> requested, long timestamp) {
        if (!writable || requested == null || timestamp < 0
                || requested.values().stream().anyMatch(operation -> operation == null || !operation.isValid())) {
            return BatchStatus.INVALID;
        }
        for (var entry : requested.entrySet()) {
            if (entry.getKey() == null || entry.getKey().equals(new UUID(0L, 0L))) {
                return BatchStatus.INVALID;
            }
            BossRewardOperation existing = operations.get(entry.getKey());
            if (existing != null && !sameIdentity(existing, entry.getValue())) {
                return BatchStatus.CONFLICT;
            }
            if (existing == null && entry.getValue().phase() != BossRewardOperation.Phase.PENDING) {
                return BatchStatus.INVALID;
            }
        }
        trimCompleted(timestamp);
        long newCount = requested.keySet().stream().filter(id -> !operations.containsKey(id)).count();
        if (newCount > MAX_OPERATIONS - operations.size()) {
            return BatchStatus.FULL;
        }
        boolean changed = false;
        for (var entry : requested.entrySet()) {
            if (!operations.containsKey(entry.getKey())) {
                operations.put(entry.getKey(), entry.getValue());
                changed = true;
            }
        }
        if (changed) {
            setDirty();
            return BatchStatus.SUCCESS;
        }
        return BatchStatus.DUPLICATE;
    }

    public boolean update(UUID transactionId, BossRewardOperation expected, BossRewardOperation replacement) {
        if (!writable || transactionId == null || expected == null || replacement == null
                || !replacement.isValid() || !expected.equals(operations.get(transactionId))
                || !sameIdentity(expected, replacement) || expected.equals(replacement)
                || !validTransition(expected.phase(), replacement.phase())) {
            return false;
        }
        operations.put(transactionId, replacement);
        setDirty();
        return true;
    }

    private void trimCompleted(long timestamp) {
        long cutoff = timestamp <= COMPLETED_RETENTION_MILLIS ? 0 : timestamp - COMPLETED_RETENTION_MILLIS;
        boolean changed = operations.entrySet().removeIf(entry -> {
            BossRewardOperation operation = entry.getValue();
            return (operation.phase() == BossRewardOperation.Phase.COMPLETED
                    || operation.phase() == BossRewardOperation.Phase.FAILED)
                    && operation.createdAtEpochMillis() < cutoff
                    && operation.cooldownUntilEpochMillis() <= timestamp;
        });
        if (changed) {
            setDirty();
        }
    }

    private static boolean sameIdentity(BossRewardOperation first, BossRewardOperation second) {
        return first.encounterId().equals(second.encounterId())
                && first.bossId().equals(second.bossId())
                && first.definitionFingerprint().equals(second.definitionFingerprint())
                && first.playerId().equals(second.playerId())
                && first.dimension().equals(second.dimension())
                && first.center().equals(second.center())
                && first.playerPoints() == second.playerPoints()
                && first.totalPoints() == second.totalPoints()
                && first.minimumPoints() == second.minimumPoints()
                && first.minimumShareBasisPoints() == second.minimumShareBasisPoints()
                && first.currency() == second.currency()
                && first.experience() == second.experience()
                && first.cooldownUntilEpochMillis() == second.cooldownUntilEpochMillis()
                && first.createdAtEpochMillis() == second.createdAtEpochMillis()
                && itemsMatch(first.items(), second.items());
    }

    private static boolean validTransition(
            BossRewardOperation.Phase current, BossRewardOperation.Phase replacement) {
        return current == BossRewardOperation.Phase.PENDING
                && (replacement == BossRewardOperation.Phase.CORE_APPLIED
                        || replacement == BossRewardOperation.Phase.FAILED)
                || current == BossRewardOperation.Phase.CORE_APPLIED
                        && replacement == BossRewardOperation.Phase.COMPLETED;
    }

    private static boolean itemsMatch(List<ItemStack> first, List<ItemStack> second) {
        if (first.size() != second.size()) {
            return false;
        }
        for (int index = 0; index < first.size(); index++) {
            if (!ItemStack.matches(first.get(index), second.get(index))) {
                return false;
            }
        }
        return true;
    }

    private static DataResult<Map<UUID, BossRewardOperation>> operationsFromEntries(
            List<OperationEntry> entries) {
        Map<UUID, BossRewardOperation> result = new LinkedHashMap<>();
        for (OperationEntry entry : entries) {
            if (entry == null || entry.operation() == null || !entry.operation().isValid()
                    || entry.id().equals(new UUID(0L, 0L))
                    || result.putIfAbsent(entry.id(), entry.operation()) != null) {
                return DataResult.error(() -> "Duplicate or invalid boss reward operation");
            }
        }
        return DataResult.success(Map.copyOf(result));
    }

    private static DataResult<List<OperationEntry>> operationEntries(
            Map<UUID, BossRewardOperation> operations) {
        return DataResult.success(operations.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new OperationEntry(entry.getKey(), entry.getValue()))
                .toList());
    }

    public enum BatchStatus {
        SUCCESS, DUPLICATE, INVALID, CONFLICT, FULL
    }

    private record OperationEntry(UUID id, BossRewardOperation operation) {
        private static final Codec<OperationEntry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                UUIDUtil.STRING_CODEC.fieldOf("id").forGetter(OperationEntry::id),
                BossRewardOperation.CODEC.fieldOf("operation").forGetter(OperationEntry::operation)
        ).apply(instance, OperationEntry::new));
    }

    private record PersistedState(int schemaVersion, Map<UUID, BossRewardOperation> operations) {
        private PersistedState {
            operations = Map.copyOf(operations);
        }

        private PersistedState atVersion(int version) {
            return new PersistedState(version, operations);
        }
    }
}
