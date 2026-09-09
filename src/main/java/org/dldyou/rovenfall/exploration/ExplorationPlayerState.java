package org.dldyou.rovenfall.exploration;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.util.StringRepresentable;

/** Immutable player-owned exploration journal. */
public record ExplorationPlayerState(Map<Identifier, DiscoveryReceipt> discoveries) {
    public static final int MAX_DISCOVERIES = 256;
    public static final ExplorationPlayerState EMPTY = new ExplorationPlayerState(Map.of());
    private static final UUID ZERO_UUID = new UUID(0L, 0L);

    private static final Codec<Map<Identifier, DiscoveryReceipt>> DISCOVERIES_CODEC = Entry.CODEC
            .listOf(0, MAX_DISCOVERIES)
            .flatXmap(ExplorationPlayerState::fromEntries, ExplorationPlayerState::toEntries);
    public static final Codec<ExplorationPlayerState> CODEC = RecordCodecBuilder
            .<ExplorationPlayerState>create(instance -> instance.group(
                    DISCOVERIES_CODEC.optionalFieldOf("discoveries", Map.of())
                            .forGetter(ExplorationPlayerState::discoveries)
            ).apply(instance, ExplorationPlayerState::new)).validate(ExplorationPlayerState::validate);

    public ExplorationPlayerState {
        discoveries = Collections.unmodifiableMap(new TreeMap<>(discoveries));
    }

    public Optional<DiscoveryReceipt> discovery(Identifier id) {
        return Optional.ofNullable(discoveries.get(id));
    }

    public boolean isValid() {
        return discoveries != null && discoveries.size() <= MAX_DISCOVERIES
                && discoveries.entrySet().stream().allMatch(entry ->
                        entry.getKey() != null && entry.getValue() != null && entry.getValue().isValid());
    }

    private static DataResult<ExplorationPlayerState> validate(ExplorationPlayerState state) {
        return state != null && state.isValid()
                ? DataResult.success(state)
                : DataResult.error(() -> "Exploration player state is invalid");
    }

    private static DataResult<Map<Identifier, DiscoveryReceipt>> fromEntries(List<Entry> entries) {
        Map<Identifier, DiscoveryReceipt> result = new LinkedHashMap<>();
        for (Entry entry : entries) {
            if (result.putIfAbsent(entry.id(), entry.receipt()) != null) {
                return DataResult.error(() -> "Duplicate exploration discovery ID " + entry.id());
            }
        }
        return DataResult.success(Map.copyOf(result));
    }

    private static DataResult<List<Entry>> toEntries(Map<Identifier, DiscoveryReceipt> discoveries) {
        return DataResult.success(discoveries.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(entry -> new Entry(entry.getKey(), entry.getValue())).toList());
    }

    public record DiscoveryReceipt(
            int definitionVersion,
            long discoveredAtEpochMillis,
            UUID transactionId,
            Optional<RewardOperation> rewardOperation) {
        private static final Codec<Long> EPOCH_CODEC = Codec.LONG.validate(value -> value > 0
                ? DataResult.success(value)
                : DataResult.error(() -> "Exploration discovery timestamp must be positive"));
        public static final Codec<DiscoveryReceipt> CODEC = RecordCodecBuilder
                .<DiscoveryReceipt>create(instance -> instance.group(
                        Codec.intRange(1, ExplorationDefinition.MAX_VERSION).fieldOf("definition_version")
                                .forGetter(DiscoveryReceipt::definitionVersion),
                        EPOCH_CODEC.fieldOf("discovered_at_epoch_millis")
                                .forGetter(DiscoveryReceipt::discoveredAtEpochMillis),
                        UUIDUtil.STRING_CODEC.fieldOf("transaction_id").forGetter(DiscoveryReceipt::transactionId),
                        RewardOperation.CODEC.optionalFieldOf("reward_operation")
                                .forGetter(DiscoveryReceipt::rewardOperation)
                ).apply(instance, DiscoveryReceipt::new)).validate(DiscoveryReceipt::validate);

        public DiscoveryReceipt {
            rewardOperation = rewardOperation == null ? Optional.empty() : rewardOperation;
        }

        public boolean isValid() {
            return definitionVersion >= 1 && definitionVersion <= ExplorationDefinition.MAX_VERSION
                    && discoveredAtEpochMillis > 0 && transactionId != null && !ZERO_UUID.equals(transactionId)
                    && rewardOperation != null && rewardOperation.filter(operation ->
                            !operation.isValid() || !operation.transactionId().equals(transactionId)
                                    || operation.startedAtEpochMillis() != discoveredAtEpochMillis).isEmpty();
        }

        public DiscoveryReceipt atVersion(int version) {
            return new DiscoveryReceipt(version, discoveredAtEpochMillis, transactionId, rewardOperation);
        }

        public DiscoveryReceipt withReward(RewardOperation reward) {
            return new DiscoveryReceipt(definitionVersion, discoveredAtEpochMillis, transactionId,
                    Optional.ofNullable(reward));
        }

        private static DataResult<DiscoveryReceipt> validate(DiscoveryReceipt receipt) {
            return receipt != null && receipt.isValid()
                    ? DataResult.success(receipt)
                    : DataResult.error(() -> "Exploration discovery receipt is invalid");
        }
    }

    /** Captures reward intent so later definition reloads cannot alter recovery. */
    public record RewardOperation(
            UUID transactionId,
            long amount,
            long startedAtEpochMillis,
            Phase phase) {
        private static final Codec<Long> AMOUNT_CODEC = Codec.LONG.validate(value ->
                value >= 1 && value <= ExplorationDefinition.MAX_ACTIVITY_XP
                        ? DataResult.success(value)
                        : DataResult.error(() -> "Exploration reward amount exceeds its bound"));
        public static final Codec<RewardOperation> CODEC = RecordCodecBuilder
                .<RewardOperation>create(instance -> instance.group(
                        UUIDUtil.STRING_CODEC.fieldOf("transaction_id").forGetter(RewardOperation::transactionId),
                        AMOUNT_CODEC.fieldOf("amount")
                                .forGetter(RewardOperation::amount),
                        Codec.LONG.fieldOf("started_at_epoch_millis")
                                .forGetter(RewardOperation::startedAtEpochMillis),
                        Phase.CODEC.fieldOf("phase").forGetter(RewardOperation::phase)
                ).apply(instance, RewardOperation::new)).validate(RewardOperation::validate);

        public boolean isValid() {
            return transactionId != null && !ZERO_UUID.equals(transactionId)
                    && amount >= 1 && amount <= ExplorationDefinition.MAX_ACTIVITY_XP
                    && startedAtEpochMillis > 0 && phase != null;
        }

        public RewardOperation applied() {
            return new RewardOperation(transactionId, amount, startedAtEpochMillis, Phase.APPLIED);
        }

        private static DataResult<RewardOperation> validate(RewardOperation operation) {
            return operation != null && operation.isValid()
                    ? DataResult.success(operation)
                    : DataResult.error(() -> "Exploration reward operation is invalid");
        }

        public enum Phase implements StringRepresentable {
            CAPTURED("captured"), APPLIED("applied");

            public static final Codec<Phase> CODEC = StringRepresentable.fromEnum(Phase::values);
            private final String id;

            Phase(String id) {
                this.id = id;
            }

            @Override
            public String getSerializedName() {
                return id;
            }
        }
    }

    private record Entry(Identifier id, DiscoveryReceipt receipt) {
        private static final Codec<Entry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Identifier.CODEC.fieldOf("id").forGetter(Entry::id),
                DiscoveryReceipt.CODEC.fieldOf("receipt").forGetter(Entry::receipt)
        ).apply(instance, Entry::new));
    }
}
