package org.dldyou.rovenfall.quest;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;

/** Immutable, server-owned quest evidence for one player. */
public record QuestPlayerState(Map<Identifier, QuestEntry> quests) {
    private static final UUID ZERO_UUID = new UUID(0L, 0L);
    public static final int MAX_QUESTS = 4_096;
    public static final int MAX_OBJECTIVES_PER_QUEST = QuestDefinition.MAX_OBJECTIVES;
    public static final long MAX_OBJECTIVE_PROGRESS = QuestDefinition.MAX_REQUIRED_COUNT;
    public static final int MAX_DEFINITION_VERSION = QuestDefinition.MAX_VERSION;

    private static final Codec<Map<Identifier, QuestEntry>> QUESTS_CODEC = QuestEntryMapEntry.CODEC
            .listOf(0, MAX_QUESTS)
            .flatXmap(QuestPlayerState::questsFromEntries, QuestPlayerState::questEntries);

    public static final Codec<QuestPlayerState> CODEC = RecordCodecBuilder.<QuestPlayerState>create(instance -> instance.group(
            QUESTS_CODEC.optionalFieldOf("quests", Map.of()).forGetter(QuestPlayerState::quests)
    ).apply(instance, QuestPlayerState::new)).validate(QuestPlayerState::validate);

    public static final QuestPlayerState EMPTY = new QuestPlayerState(Map.of());

    public QuestPlayerState {
        quests = Map.copyOf(quests);
    }

    public boolean isValid() {
        return validationError(this).isEmpty();
    }

    /** IDs retained in player evidence but no longer present in the active datapack snapshot. */
    public Set<Identifier> unresolvedQuestIds(QuestDefinitionSnapshot definitions) {
        if (definitions == null) {
            return Set.copyOf(quests.keySet());
        }
        return quests.keySet().stream()
                .filter(id -> definitions.quest(id).isEmpty())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /** IDs whose retained evidence was completed against a different definition version. */
    public Set<Identifier> definitionChangedQuestIds(QuestDefinitionSnapshot definitions) {
        if (definitions == null) {
            return Set.of();
        }
        return quests.entrySet().stream()
                .filter(entry -> definitions.quest(entry.getKey())
                        .map(definition -> definition.version() != entry.getValue().definitionVersion())
                        .orElse(false))
                .map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static DataResult<QuestPlayerState> validate(QuestPlayerState state) {
        Optional<String> error = validationError(state);
        return error.isEmpty() ? DataResult.success(state) : DataResult.error(error::orElseThrow);
    }

    private static Optional<String> validationError(QuestPlayerState state) {
        if (state.quests().size() > MAX_QUESTS) {
            return Optional.of("Quest player state exceeds the quest limit");
        }
        Set<UUID> transactions = java.util.HashSet.newHashSet(state.quests().size());
        for (Map.Entry<Identifier, QuestEntry> entry : state.quests().entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null || !entry.getValue().isValid()) {
                return Optional.of("Quest player state contains invalid quest evidence");
            }
            if (entry.getValue().completion().isPresent()
                    && !transactions.add(entry.getValue().completion().orElseThrow().transactionId())) {
                return Optional.of("Quest player state contains a duplicate completion transaction");
            }
        }
        return Optional.empty();
    }

    private static DataResult<Map<Identifier, QuestEntry>> questsFromEntries(List<QuestEntryMapEntry> entries) {
        Map<Identifier, QuestEntry> result = new LinkedHashMap<>();
        for (QuestEntryMapEntry entry : entries) {
            if (result.putIfAbsent(entry.id(), entry.value()) != null) {
                return DataResult.error(() -> "Duplicate quest ID " + entry.id());
            }
        }
        return DataResult.success(Map.copyOf(result));
    }

    private static DataResult<List<QuestEntryMapEntry>> questEntries(Map<Identifier, QuestEntry> values) {
        return DataResult.success(values.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new QuestEntryMapEntry(entry.getKey(), entry.getValue()))
                .toList());
    }

    public record QuestEntry(
            int definitionVersion,
            Map<Identifier, Long> objectiveProgress,
            Optional<CompletionReceipt> completion) {
        private static final Codec<Integer> VERSION_CODEC = Codec.intRange(1, MAX_DEFINITION_VERSION);
        private static final Codec<Map<Identifier, Long>> OBJECTIVE_PROGRESS_MAP_CODEC = ObjectiveProgressEntry.CODEC
                .listOf(0, MAX_OBJECTIVES_PER_QUEST)
                .flatXmap(QuestEntry::objectivesFromEntries, QuestEntry::objectiveEntries);

        public static final Codec<QuestEntry> CODEC = RecordCodecBuilder.<QuestEntry>create(instance -> instance.group(
                VERSION_CODEC.fieldOf("definition_version").forGetter(QuestEntry::definitionVersion),
                OBJECTIVE_PROGRESS_MAP_CODEC.optionalFieldOf("objective_progress", Map.of())
                        .forGetter(QuestEntry::objectiveProgress),
                CompletionReceipt.CODEC.optionalFieldOf("completion").forGetter(QuestEntry::completion)
        ).apply(instance, QuestEntry::new)).validate(QuestEntry::validate);

        public QuestEntry {
            objectiveProgress = Map.copyOf(objectiveProgress);
            completion = completion == null ? Optional.empty() : completion;
        }

        public boolean isValid() {
            return validationError(this).isEmpty();
        }

        private static DataResult<QuestEntry> validate(QuestEntry entry) {
            Optional<String> error = validationError(entry);
            return error.isEmpty() ? DataResult.success(entry) : DataResult.error(error::orElseThrow);
        }

        private static Optional<String> validationError(QuestEntry entry) {
            if (entry.definitionVersion() < 1 || entry.definitionVersion() > MAX_DEFINITION_VERSION
                    || entry.objectiveProgress().size() > MAX_OBJECTIVES_PER_QUEST) {
                return Optional.of("Quest entry exceeds a bound");
            }
            for (Map.Entry<Identifier, Long> objective : entry.objectiveProgress().entrySet()) {
                if (objective.getKey() == null || objective.getValue() == null
                        || objective.getValue() < 0 || objective.getValue() > MAX_OBJECTIVE_PROGRESS) {
                    return Optional.of("Quest objective progress is invalid");
                }
            }
            if (entry.completion().isPresent()) {
                CompletionReceipt receipt = entry.completion().orElseThrow();
                if (!receipt.isValid() || receipt.definitionVersion() != entry.definitionVersion()) {
                    return Optional.of("Quest completion receipt is invalid or has a different definition version");
                }
            }
            return Optional.empty();
        }

        private static DataResult<Map<Identifier, Long>> objectivesFromEntries(List<ObjectiveProgressEntry> entries) {
            Map<Identifier, Long> result = new LinkedHashMap<>();
            for (ObjectiveProgressEntry entry : entries) {
                if (result.putIfAbsent(entry.id(), entry.progress()) != null) {
                    return DataResult.error(() -> "Duplicate quest objective ID " + entry.id());
                }
            }
            return DataResult.success(Map.copyOf(result));
        }

        private static DataResult<List<ObjectiveProgressEntry>> objectiveEntries(Map<Identifier, Long> values) {
            return DataResult.success(values.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .map(entry -> new ObjectiveProgressEntry(entry.getKey(), entry.getValue()))
                    .toList());
        }
    }

    public record CompletionReceipt(int definitionVersion, UUID transactionId, long completedAtEpochMillis) {
        private static final Codec<Integer> VERSION_CODEC = Codec.intRange(1, MAX_DEFINITION_VERSION);
        private static final Codec<Long> EPOCH_CODEC = Codec.LONG.validate(value ->
                value >= 0 ? DataResult.success(value)
                        : DataResult.error(() -> "Quest completion epoch must be non-negative"));
        public static final Codec<CompletionReceipt> CODEC = RecordCodecBuilder.<CompletionReceipt>create(instance -> instance.group(
                VERSION_CODEC.fieldOf("definition_version").forGetter(CompletionReceipt::definitionVersion),
                UUIDUtil.STRING_CODEC.fieldOf("transaction_id").forGetter(CompletionReceipt::transactionId),
                EPOCH_CODEC.fieldOf("completed_at_epoch_millis").forGetter(CompletionReceipt::completedAtEpochMillis)
        ).apply(instance, CompletionReceipt::new)).validate(CompletionReceipt::validate);

        public boolean isValid() {
            return definitionVersion >= 1 && definitionVersion <= MAX_DEFINITION_VERSION
                    && transactionId != null && !ZERO_UUID.equals(transactionId) && completedAtEpochMillis >= 0;
        }

        private static DataResult<CompletionReceipt> validate(CompletionReceipt receipt) {
            return receipt.isValid()
                    ? DataResult.success(receipt)
                    : DataResult.error(() -> "Quest completion receipt is invalid");
        }
    }

    private record QuestEntryMapEntry(Identifier id, QuestEntry value) {
        private static final Codec<QuestEntryMapEntry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Identifier.CODEC.fieldOf("id").forGetter(QuestEntryMapEntry::id),
                QuestEntry.CODEC.fieldOf("value").forGetter(QuestEntryMapEntry::value)
        ).apply(instance, QuestEntryMapEntry::new));
    }

    private record ObjectiveProgressEntry(Identifier id, long progress) {
        private static final Codec<Long> PROGRESS_CODEC = Codec.LONG.validate(value ->
                value >= 0 && value <= MAX_OBJECTIVE_PROGRESS
                        ? DataResult.success(value)
                        : DataResult.error(() -> "Quest objective progress must be between 0 and "
                                + MAX_OBJECTIVE_PROGRESS));
        private static final Codec<ObjectiveProgressEntry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Identifier.CODEC.fieldOf("id").forGetter(ObjectiveProgressEntry::id),
                PROGRESS_CODEC.fieldOf("progress").forGetter(ObjectiveProgressEntry::progress)
        ).apply(instance, ObjectiveProgressEntry::new));
    }
}
