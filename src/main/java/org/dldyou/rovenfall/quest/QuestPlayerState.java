package org.dldyou.rovenfall.quest;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.util.StringRepresentable;

/** Immutable, server-owned quest evidence for one player. */
public record QuestPlayerState(
        Map<Identifier, QuestEntry> quests,
        Map<UUID, ProcessedEvidence> processedEvidence,
        Map<ContractKey, QuestEntry> contracts,
        Set<ContractWindow> initializedContractWindows) {
    private static final UUID ZERO_UUID = new UUID(0L, 0L);
    public static final int MAX_QUESTS = 4_096;
    public static final int MAX_OBJECTIVES_PER_QUEST = QuestDefinition.MAX_OBJECTIVES;
    public static final long MAX_OBJECTIVE_PROGRESS = QuestDefinition.MAX_REQUIRED_COUNT;
    public static final int MAX_DEFINITION_VERSION = QuestDefinition.MAX_VERSION;
    public static final int MAX_PROCESSED_EVIDENCE = 4_096;
    public static final int MAX_CONTRACTS = 256;
    public static final int MAX_INITIALIZED_CONTRACT_WINDOWS = 160;

    private static final Codec<Map<Identifier, QuestEntry>> QUESTS_CODEC = QuestEntryMapEntry.CODEC
            .listOf(0, MAX_QUESTS)
            .flatXmap(QuestPlayerState::questsFromEntries, QuestPlayerState::questEntries);
    private static final Codec<Map<UUID, ProcessedEvidence>> PROCESSED_EVIDENCE_CODEC = ProcessedEvidenceEntry.CODEC
            .listOf(0, MAX_PROCESSED_EVIDENCE)
            .flatXmap(QuestPlayerState::processedFromEntries, QuestPlayerState::processedEntries);
    private static final Codec<Map<ContractKey, QuestEntry>> CONTRACTS_CODEC = ContractEntry.CODEC
            .listOf(0, MAX_CONTRACTS)
            .flatXmap(QuestPlayerState::contractsFromEntries, QuestPlayerState::contractEntries);
    private static final Codec<Set<ContractWindow>> INITIALIZED_CONTRACT_WINDOWS_CODEC = ContractWindow.CODEC
            .listOf(0, MAX_INITIALIZED_CONTRACT_WINDOWS)
            .flatXmap(QuestPlayerState::windowsFromEntries, QuestPlayerState::windowEntries);

    public static final Codec<QuestPlayerState> CODEC = RecordCodecBuilder.<QuestPlayerState>create(instance -> instance.group(
            QUESTS_CODEC.optionalFieldOf("quests", Map.of()).forGetter(QuestPlayerState::quests),
            PROCESSED_EVIDENCE_CODEC.optionalFieldOf("processed_evidence", Map.of())
                    .forGetter(QuestPlayerState::processedEvidence),
            CONTRACTS_CODEC.optionalFieldOf("contracts", Map.of()).forGetter(QuestPlayerState::contracts),
            INITIALIZED_CONTRACT_WINDOWS_CODEC.optionalFieldOf("initialized_contract_windows", Set.of())
                    .forGetter(QuestPlayerState::initializedContractWindows)
    ).apply(instance, QuestPlayerState::new)).validate(QuestPlayerState::validate);

    public static final QuestPlayerState EMPTY = new QuestPlayerState(Map.of(), Map.of(), Map.of(), Set.of());

    public QuestPlayerState {
        NavigableMap<Identifier, QuestEntry> orderedQuests = new TreeMap<>(quests);
        quests = Collections.unmodifiableNavigableMap(orderedQuests);
        processedEvidence = Map.copyOf(processedEvidence);
        contracts = Collections.unmodifiableNavigableMap(new TreeMap<>(contracts));
        initializedContractWindows = Collections.unmodifiableNavigableSet(
                new TreeSet<>(initializedContractWindows));
    }

    public QuestPlayerState(
            Map<Identifier, QuestEntry> quests,
            Map<UUID, ProcessedEvidence> processedEvidence) {
        this(quests, processedEvidence, Map.of(), Set.of());
    }

    public QuestPlayerState(Map<Identifier, QuestEntry> quests) {
        this(quests, Map.of(), Map.of(), Set.of());
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
                        .map(definition -> definition.contract().isPresent()
                                || definition.version() != entry.getValue().definitionVersion())
                        .orElse(false))
                .map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static DataResult<QuestPlayerState> validate(QuestPlayerState state) {
        Optional<String> error = validationError(state);
        return error.isEmpty() ? DataResult.success(state) : DataResult.error(error::orElseThrow);
    }

    private static Optional<String> validationError(QuestPlayerState state) {
        if (state.quests().size() > MAX_QUESTS || state.processedEvidence().size() > MAX_PROCESSED_EVIDENCE
                || state.contracts().size() > MAX_CONTRACTS
                || state.initializedContractWindows().size() > MAX_INITIALIZED_CONTRACT_WINDOWS) {
            return Optional.of("Quest player state exceeds the quest limit");
        }
        Set<UUID> transactions = java.util.HashSet.newHashSet(state.quests().size() + state.contracts().size());
        for (Map.Entry<Identifier, QuestEntry> entry : state.quests().entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null || !entry.getValue().isValid()) {
                return Optional.of("Quest player state contains invalid quest evidence");
            }
            if (!addTransactions(entry.getValue(), transactions)) {
                return Optional.of("Quest player state contains a duplicate quest transaction");
            }
        }
        Map<ContractWindow, Integer> contractsPerWindow = new TreeMap<>();
        for (Map.Entry<ContractKey, QuestEntry> entry : state.contracts().entrySet()) {
            ContractKey key = entry.getKey();
            if (key == null || !key.isValid() || entry.getValue() == null || !entry.getValue().isValid()
                    || !state.initializedContractWindows().contains(key.window())) {
                return Optional.of("Quest player state contains invalid contract evidence");
            }
            int count = contractsPerWindow.merge(key.window(), 1, Integer::sum);
            if (count > key.window().cadence().slots()) {
                return Optional.of("Quest player state exceeds the contract slots for a window");
            }
            if (!addTransactions(entry.getValue(), transactions)) {
                return Optional.of("Quest player state contains a duplicate quest transaction");
            }
        }
        if (state.initializedContractWindows().stream().anyMatch(window -> window == null || !window.isValid())) {
            return Optional.of("Quest player state contains an invalid initialized contract window");
        }
        for (Map.Entry<UUID, ProcessedEvidence> entry : state.processedEvidence().entrySet()) {
            if (entry.getKey() == null || ZERO_UUID.equals(entry.getKey())
                    || entry.getValue() == null || !entry.getValue().isValid()) {
                return Optional.of("Quest player state contains invalid processed evidence");
            }
        }
        return Optional.empty();
    }

    private static boolean addTransactions(QuestEntry entry, Set<UUID> transactions) {
        if (entry.completion().isPresent()
                && !transactions.add(entry.completion().orElseThrow().transactionId())) {
            return false;
        }
        return entry.pendingReward().isEmpty()
                || transactions.add(entry.pendingReward().orElseThrow().transactionId());
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

    private static DataResult<Map<UUID, ProcessedEvidence>> processedFromEntries(
            List<ProcessedEvidenceEntry> entries) {
        Map<UUID, ProcessedEvidence> result = new LinkedHashMap<>();
        for (ProcessedEvidenceEntry entry : entries) {
            ProcessedEvidence evidence = new ProcessedEvidence(
                    entry.timestamp(), entry.kind(), entry.ownerEvidenceMissingSinceEpochMillis());
            if (ZERO_UUID.equals(entry.id()) || !evidence.isValid()
                    || result.putIfAbsent(entry.id(), evidence) != null) {
                return DataResult.error(() -> "Duplicate or zero processed quest evidence ID " + entry.id());
            }
        }
        return DataResult.success(Map.copyOf(result));
    }

    private static DataResult<List<ProcessedEvidenceEntry>> processedEntries(
            Map<UUID, ProcessedEvidence> values) {
        return DataResult.success(values.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new ProcessedEvidenceEntry(
                        entry.getKey(), entry.getValue().timestampEpochMillis(), entry.getValue().kind(),
                        entry.getValue().ownerEvidenceMissingSinceEpochMillis()))
                .toList());
    }

    private static DataResult<Map<ContractKey, QuestEntry>> contractsFromEntries(List<ContractEntry> entries) {
        Map<ContractKey, QuestEntry> result = new TreeMap<>();
        for (ContractEntry entry : entries) {
            if (result.putIfAbsent(entry.key(), entry.value()) != null) {
                return DataResult.error(() -> "Duplicate contract key " + entry.key());
            }
        }
        return DataResult.success(Collections.unmodifiableNavigableMap(new TreeMap<>(result)));
    }

    private static DataResult<List<ContractEntry>> contractEntries(Map<ContractKey, QuestEntry> values) {
        return DataResult.success(values.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new ContractEntry(entry.getKey(), entry.getValue()))
                .toList());
    }

    private static DataResult<Set<ContractWindow>> windowsFromEntries(List<ContractWindow> entries) {
        Set<ContractWindow> result = new TreeSet<>();
        for (ContractWindow entry : entries) {
            if (!result.add(entry)) {
                return DataResult.error(() -> "Duplicate initialized contract window " + entry);
            }
        }
        return DataResult.success(Collections.unmodifiableNavigableSet(new TreeSet<>(result)));
    }

    private static DataResult<List<ContractWindow>> windowEntries(Set<ContractWindow> values) {
        return DataResult.success(values.stream().sorted().toList());
    }

    public record ContractWindow(QuestDefinition.Cadence cadence, long windowStartEpochDay)
            implements Comparable<ContractWindow> {
        private static final Codec<Long> EPOCH_DAY_CODEC = Codec.LONG.validate(value ->
                value >= LocalDate.MIN.toEpochDay() && value <= LocalDate.MAX.toEpochDay()
                        ? DataResult.success(value)
                        : DataResult.error(() -> "Contract window epoch day is invalid"));
        public static final Codec<ContractWindow> CODEC = RecordCodecBuilder.<ContractWindow>create(instance ->
                instance.group(
                        QuestDefinition.Cadence.CODEC.fieldOf("cadence").forGetter(ContractWindow::cadence),
                        EPOCH_DAY_CODEC.fieldOf("window_start_epoch_day")
                                .forGetter(ContractWindow::windowStartEpochDay)
                ).apply(instance, ContractWindow::new)).validate(ContractWindow::validate);

        public boolean isValid() {
            if (cadence == null || windowStartEpochDay < LocalDate.MIN.toEpochDay()
                    || windowStartEpochDay > LocalDate.MAX.toEpochDay()) {
                return false;
            }
            return cadence != QuestDefinition.Cadence.WEEKLY
                    || LocalDate.ofEpochDay(windowStartEpochDay).getDayOfWeek() == DayOfWeek.MONDAY;
        }

        @Override
        public int compareTo(ContractWindow other) {
            int cadenceOrder = cadence.compareTo(other.cadence);
            return cadenceOrder != 0 ? cadenceOrder
                    : Long.compare(windowStartEpochDay, other.windowStartEpochDay);
        }

        private static DataResult<ContractWindow> validate(ContractWindow window) {
            return window.isValid() ? DataResult.success(window)
                    : DataResult.error(() -> "Contract window is invalid or weekly start is not Monday");
        }
    }

    public record ContractKey(ContractWindow window, Identifier templateId) implements Comparable<ContractKey> {
        public static final Codec<ContractKey> CODEC = RecordCodecBuilder.<ContractKey>create(instance ->
                instance.group(
                        ContractWindow.CODEC.fieldOf("window").forGetter(ContractKey::window),
                        Identifier.CODEC.fieldOf("template_id").forGetter(ContractKey::templateId)
                ).apply(instance, ContractKey::new)).validate(ContractKey::validate);

        public boolean isValid() {
            return window != null && window.isValid() && templateId != null;
        }

        @Override
        public int compareTo(ContractKey other) {
            int windowOrder = window.compareTo(other.window);
            return windowOrder != 0 ? windowOrder : templateId.compareTo(other.templateId);
        }

        private static DataResult<ContractKey> validate(ContractKey key) {
            return key.isValid() ? DataResult.success(key)
                    : DataResult.error(() -> "Contract key is invalid");
        }
    }

    public record ProcessedEvidence(
            long timestampEpochMillis,
            Optional<QuestDefinition.Kind> kind,
            Optional<Long> ownerEvidenceMissingSinceEpochMillis) {
        public ProcessedEvidence {
            kind = kind == null ? Optional.empty() : kind;
            ownerEvidenceMissingSinceEpochMillis = ownerEvidenceMissingSinceEpochMillis == null
                    ? Optional.empty()
                    : ownerEvidenceMissingSinceEpochMillis;
        }

        public ProcessedEvidence(long timestampEpochMillis, QuestDefinition.Kind kind) {
            this(timestampEpochMillis, Optional.ofNullable(kind), Optional.empty());
        }

        public boolean isValid() {
            return timestampEpochMillis >= 0 && kind != null
                    && ownerEvidenceMissingSinceEpochMillis != null
                    && ownerEvidenceMissingSinceEpochMillis
                            .filter(value -> value < timestampEpochMillis).isEmpty();
        }

        ProcessedEvidence ownerEvidenceMissingSince(Optional<Long> timestamp) {
            return new ProcessedEvidence(timestampEpochMillis, kind, timestamp);
        }
    }

    public record QuestEntry(
            int definitionVersion,
            Map<Identifier, Long> objectiveProgress,
            Optional<RewardOperation> pendingReward,
            Optional<CompletionReceipt> completion) {
        private static final Codec<Integer> VERSION_CODEC = Codec.intRange(1, MAX_DEFINITION_VERSION);
        private static final Codec<Map<Identifier, Long>> OBJECTIVE_PROGRESS_MAP_CODEC = ObjectiveProgressEntry.CODEC
                .listOf(0, MAX_OBJECTIVES_PER_QUEST)
                .flatXmap(QuestEntry::objectivesFromEntries, QuestEntry::objectiveEntries);

        public static final Codec<QuestEntry> CODEC = RecordCodecBuilder.<QuestEntry>create(instance -> instance.group(
                VERSION_CODEC.fieldOf("definition_version").forGetter(QuestEntry::definitionVersion),
                OBJECTIVE_PROGRESS_MAP_CODEC.optionalFieldOf("objective_progress", Map.of())
                        .forGetter(QuestEntry::objectiveProgress),
                RewardOperation.CODEC.optionalFieldOf("pending_reward").forGetter(QuestEntry::pendingReward),
                CompletionReceipt.CODEC.optionalFieldOf("completion").forGetter(QuestEntry::completion)
        ).apply(instance, QuestEntry::new)).validate(QuestEntry::validate);

        public QuestEntry {
            objectiveProgress = Map.copyOf(objectiveProgress);
            pendingReward = pendingReward == null ? Optional.empty() : pendingReward;
            completion = completion == null ? Optional.empty() : completion;
        }

        public QuestEntry(
                int definitionVersion,
                Map<Identifier, Long> objectiveProgress,
                Optional<CompletionReceipt> completion) {
            this(definitionVersion, objectiveProgress, Optional.empty(), completion);
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
            if (entry.pendingReward().isPresent()) {
                RewardOperation reward = entry.pendingReward().orElseThrow();
                if (!reward.isValid() || reward.definitionVersion() != entry.definitionVersion()
                        || entry.completion().isPresent()) {
                    return Optional.of("Quest pending reward is invalid or conflicts with completion");
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

    public record RewardOperation(
            int definitionVersion,
            UUID transactionId,
            long currency,
            Optional<Identifier> activity,
            long activityXp,
            long startedAtEpochMillis,
            Phase phase) {
        private static final Codec<Long> EPOCH_CODEC = Codec.LONG.validate(value -> value >= 0
                ? DataResult.success(value) : DataResult.error(() -> "Quest reward epoch must be non-negative"));
        private static final Codec<Long> CURRENCY_CODEC = Codec.LONG.validate(value ->
                value >= 0 && value <= QuestDefinition.MAX_CURRENCY_REWARD ? DataResult.success(value)
                        : DataResult.error(() -> "Quest captured currency exceeds its bound"));
        private static final Codec<Long> XP_CODEC = Codec.LONG.validate(value ->
                value >= 0 && value <= QuestDefinition.MAX_ACTIVITY_XP_REWARD ? DataResult.success(value)
                        : DataResult.error(() -> "Quest captured activity XP exceeds its bound"));
        public static final Codec<RewardOperation> CODEC = RecordCodecBuilder.<RewardOperation>create(instance ->
                instance.group(
                        Codec.intRange(1, MAX_DEFINITION_VERSION).fieldOf("definition_version")
                                .forGetter(RewardOperation::definitionVersion),
                        UUIDUtil.STRING_CODEC.fieldOf("transaction_id").forGetter(RewardOperation::transactionId),
                        CURRENCY_CODEC.fieldOf("currency")
                                .forGetter(RewardOperation::currency),
                        Identifier.CODEC.optionalFieldOf("activity").forGetter(RewardOperation::activity),
                        XP_CODEC.fieldOf("activity_xp")
                                .forGetter(RewardOperation::activityXp),
                        EPOCH_CODEC.fieldOf("started_at_epoch_millis").forGetter(RewardOperation::startedAtEpochMillis),
                        Phase.CODEC.fieldOf("phase").forGetter(RewardOperation::phase)
                ).apply(instance, RewardOperation::new)).validate(RewardOperation::validate);

        public RewardOperation {
            activity = activity == null ? Optional.empty() : activity;
        }

        public boolean isValid() {
            return definitionVersion >= 1 && definitionVersion <= MAX_DEFINITION_VERSION
                    && transactionId != null && !ZERO_UUID.equals(transactionId)
                    && currency >= 0 && currency <= QuestDefinition.MAX_CURRENCY_REWARD
                    && activityXp >= 0 && activityXp <= QuestDefinition.MAX_ACTIVITY_XP_REWARD
                    && ((activity.isEmpty() && activityXp == 0) || (activity.isPresent() && activityXp > 0))
                    && startedAtEpochMillis >= 0 && phase != null;
        }

        public RewardOperation atPhase(Phase next) {
            return new RewardOperation(definitionVersion, transactionId, currency, activity, activityXp,
                    startedAtEpochMillis, next);
        }

        private static DataResult<RewardOperation> validate(RewardOperation operation) {
            return operation.isValid() ? DataResult.success(operation)
                    : DataResult.error(() -> "Quest reward operation is invalid");
        }

        public enum Phase implements StringRepresentable {
            CAPTURED("captured"), CURRENCY_APPLIED("currency_applied"), XP_APPLIED("xp_applied"),
            AUDIT_APPLIED("audit_applied");

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

    public record CompletionReceipt(
            int definitionVersion,
            UUID transactionId,
            long completedAtEpochMillis,
            Optional<RewardOperation> rewardOperation) {
        private static final Codec<Integer> VERSION_CODEC = Codec.intRange(1, MAX_DEFINITION_VERSION);
        private static final Codec<Long> EPOCH_CODEC = Codec.LONG.validate(value ->
                value >= 0 ? DataResult.success(value)
                        : DataResult.error(() -> "Quest completion epoch must be non-negative"));
        public static final Codec<CompletionReceipt> CODEC = RecordCodecBuilder.<CompletionReceipt>create(instance -> instance.group(
                VERSION_CODEC.fieldOf("definition_version").forGetter(CompletionReceipt::definitionVersion),
                UUIDUtil.STRING_CODEC.fieldOf("transaction_id").forGetter(CompletionReceipt::transactionId),
                EPOCH_CODEC.fieldOf("completed_at_epoch_millis").forGetter(CompletionReceipt::completedAtEpochMillis),
                RewardOperation.CODEC.optionalFieldOf("reward_operation")
                        .forGetter(CompletionReceipt::rewardOperation)
        ).apply(instance, CompletionReceipt::new)).validate(CompletionReceipt::validate);

        /** Legacy completion records predate the captured reward intent. */
        public CompletionReceipt(int definitionVersion, UUID transactionId, long completedAtEpochMillis) {
            this(definitionVersion, transactionId, completedAtEpochMillis, Optional.empty());
        }

        public CompletionReceipt(
                int definitionVersion,
                UUID transactionId,
                long completedAtEpochMillis,
                RewardOperation rewardOperation) {
            this(definitionVersion, transactionId, completedAtEpochMillis, Optional.ofNullable(rewardOperation));
        }

        public CompletionReceipt {
            rewardOperation = rewardOperation == null ? Optional.empty() : rewardOperation;
        }

        public boolean isValid() {
            return definitionVersion >= 1 && definitionVersion <= MAX_DEFINITION_VERSION
                    && transactionId != null && !ZERO_UUID.equals(transactionId) && completedAtEpochMillis >= 0
                    && (rewardOperation.isEmpty() || rewardOperation.filter(operation ->
                            operation.isValid()
                                    && operation.definitionVersion() == definitionVersion
                                    && operation.transactionId().equals(transactionId)
                                    && completedAtEpochMillis >= operation.startedAtEpochMillis())
                            .isPresent());
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

    private record ContractEntry(ContractKey key, QuestEntry value) {
        private static final Codec<ContractEntry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                ContractKey.CODEC.fieldOf("key").forGetter(ContractEntry::key),
                QuestEntry.CODEC.fieldOf("value").forGetter(ContractEntry::value)
        ).apply(instance, ContractEntry::new));
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

    private record ProcessedEvidenceEntry(
            UUID id,
            long timestamp,
            Optional<QuestDefinition.Kind> kind,
            Optional<Long> ownerEvidenceMissingSinceEpochMillis) {
        private static final Codec<ProcessedEvidenceEntry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                UUIDUtil.STRING_CODEC.fieldOf("id").forGetter(ProcessedEvidenceEntry::id),
                Codec.LONG.fieldOf("timestamp").forGetter(ProcessedEvidenceEntry::timestamp),
                QuestDefinition.Kind.CODEC.optionalFieldOf("kind").forGetter(ProcessedEvidenceEntry::kind),
                Codec.LONG.optionalFieldOf("owner_evidence_missing_since_epoch_millis")
                        .forGetter(ProcessedEvidenceEntry::ownerEvidenceMissingSinceEpochMillis)
        ).apply(instance, ProcessedEvidenceEntry::new));
    }
}
