package org.dldyou.rovenfall.administration;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import org.dldyou.rovenfall.Rovenfall;
import org.dldyou.rovenfall.economy.ShopInstance;

public final class PlatformSavedData extends SavedData {
    private static final UUID ZERO_UUID = new UUID(0L, 0L);
    public static final int CURRENT_SCHEMA_VERSION = 5;
    public static final int MAX_AUDIT_PAGE_SIZE = 50;
    static final int MAX_ECONOMY_TRANSACTIONS = 250_000;
    static final int MAX_ECONOMY_ALERTS = 10_000;
    static final int MAX_RATE_INDEX_PER_PLAYER = 10_000;
    static final long ECONOMY_TRANSACTION_RETENTION_MILLIS = Duration.ofDays(30).toMillis();
    private static final Duration AUDIT_RETENTION = Duration.ofDays(30);
    private static final Codec<Map<UUID, AdminRole>> ADMIN_ROLES_CODEC = Codec.unboundedMap(UUIDUtil.STRING_CODEC, AdminRole.CODEC);
    private static final Codec<Map<UUID, PlayerRecord>> PLAYER_RECORDS_CODEC =
            Codec.unboundedMap(UUIDUtil.STRING_CODEC, PlayerRecord.CODEC);
    private static final Codec<Long> BALANCE_CODEC = Codec.LONG.validate(balance -> balance < 0
            ? DataResult.error(() -> "Balance must be non-negative")
            : DataResult.success(balance));
    private static final Codec<Map<UUID, Long>> ECONOMY_BALANCES_CODEC =
            Codec.unboundedMap(UUIDUtil.STRING_CODEC, BALANCE_CODEC);
    private static final Codec<Long> TRANSACTION_TIMESTAMP_CODEC = Codec.LONG.validate(timestamp -> timestamp < 0
            ? DataResult.error(() -> "Economy transaction timestamp must be non-negative")
            : DataResult.success(timestamp));
    private static final Codec<Map<UUID, Long>> ECONOMY_TRANSACTIONS_CODEC =
            boundedTransactionLedgerCodec(MAX_ECONOMY_TRANSACTIONS);
    private static final Codec<Map<Identifier, ShopInstance>> SHOP_INSTANCES_CODEC =
            boundedShopInstancesCodec(ShopInstance.MAX_INSTANCES);
    private static final Codec<Map<UUID, EconomyTransactionReceipt>> ECONOMY_RECEIPTS_CODEC =
            boundedReceiptsCodec(MAX_ECONOMY_TRANSACTIONS);
    private static final Codec<List<EconomyAlert>> ECONOMY_ALERTS_CODEC =
            EconomyAlert.CODEC.listOf(0, MAX_ECONOMY_ALERTS);

    public static final Codec<PlatformSavedData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.optionalFieldOf("schema_version", 0).forGetter(data -> data.schemaVersion),
            ADMIN_ROLES_CODEC.optionalFieldOf("admin_roles", Map.of()).forGetter(data -> data.adminRoles),
            AuditEntry.CODEC.listOf().optionalFieldOf("audit_entries", List.of()).forGetter(data -> data.auditEntries),
            PLAYER_RECORDS_CODEC.optionalFieldOf("player_records", Map.of()).forGetter(data -> data.playerRecords),
            ECONOMY_BALANCES_CODEC.optionalFieldOf("economy_balances", Map.of()).forGetter(data -> data.economyBalances),
            ECONOMY_TRANSACTIONS_CODEC.optionalFieldOf("economy_transactions", Map.of())
                    .forGetter(data -> data.economyTransactions),
            SHOP_INSTANCES_CODEC.optionalFieldOf("shop_instances", Map.of()).forGetter(data -> data.shopInstances),
            ECONOMY_RECEIPTS_CODEC.optionalFieldOf("economy_receipts", Map.of()).forGetter(data -> data.economyReceipts),
            ECONOMY_ALERTS_CODEC.optionalFieldOf("economy_alerts", List.of()).forGetter(data -> data.economyAlerts)
    ).apply(instance, PlatformSavedData::decode));

    public static final SavedDataType<PlatformSavedData> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(Rovenfall.MOD_ID, "platform"),
            PlatformSavedData::new,
            CODEC
    );

    private final int schemaVersion;
    private final boolean writable;
    private final Map<UUID, AdminRole> adminRoles;
    private final List<AuditEntry> auditEntries;
    private final Map<UUID, PlayerRecord> playerRecords;
    private final Map<UUID, Long> economyBalances;
    private final Map<UUID, Long> economyTransactions;
    private final Map<Identifier, ShopInstance> shopInstances;
    private final Map<UUID, EconomyTransactionReceipt> economyReceipts;
    private final List<EconomyAlert> economyAlerts;
    private final Map<UUID, ArrayDeque<Long>> recentTransactionsByPlayer = new HashMap<>();
    private final java.util.Set<Identifier> shopDependencyLocks = new HashSet<>();
    private final Map<UUID, Long> lastDeniedAuditByActor = new HashMap<>();

    public PlatformSavedData() {
        this(CURRENT_SCHEMA_VERSION, Map.of(), List.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), List.of(), true);
    }

    private PlatformSavedData(
            int schemaVersion,
            Map<UUID, AdminRole> adminRoles,
            List<AuditEntry> auditEntries,
            Map<UUID, PlayerRecord> playerRecords,
            Map<UUID, Long> economyBalances,
            Map<UUID, Long> economyTransactions,
            Map<Identifier, ShopInstance> shopInstances,
            Map<UUID, EconomyTransactionReceipt> economyReceipts,
            List<EconomyAlert> economyAlerts,
            boolean writable) {
        this.schemaVersion = schemaVersion;
        this.writable = writable;
        this.adminRoles = new HashMap<>(adminRoles);
        this.auditEntries = new ArrayList<>(auditEntries);
        this.playerRecords = new HashMap<>(playerRecords);
        this.economyBalances = new HashMap<>(economyBalances);
        this.economyTransactions = new HashMap<>(economyTransactions);
        this.shopInstances = new HashMap<>(shopInstances);
        this.economyReceipts = new HashMap<>(economyReceipts);
        this.economyAlerts = new ArrayList<>(economyAlerts);
        rebuildRecentTransactionIndex();
    }

    private static PlatformSavedData decode(
            int schemaVersion,
            Map<UUID, AdminRole> adminRoles,
            List<AuditEntry> auditEntries,
            Map<UUID, PlayerRecord> playerRecords,
            Map<UUID, Long> economyBalances,
            Map<UUID, Long> economyTransactions,
            Map<Identifier, ShopInstance> shopInstances,
            Map<UUID, EconomyTransactionReceipt> economyReceipts,
            List<EconomyAlert> economyAlerts) {
        var migration = PlatformDataMigrations.migrate(
                schemaVersion,
                adminRoles,
                auditEntries,
                playerRecords,
                economyBalances,
                economyTransactions,
                shopInstances,
                economyReceipts,
                economyAlerts,
                CURRENT_SCHEMA_VERSION
        );
        var state = migration.state();
        return new PlatformSavedData(
                state.schemaVersion(),
                state.adminRoles(),
                state.auditEntries(),
                state.playerRecords(),
                state.economyBalances(),
                state.economyTransactions(),
                state.shopInstances(),
                state.economyReceipts(),
                state.economyAlerts(),
                migration.writable()
        );
    }

    public static PlatformSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    public int schemaVersion() {
        return schemaVersion;
    }

    public boolean isWritable() {
        return writable;
    }

    public boolean hasAnyAdminRoles() {
        return !adminRoles.isEmpty();
    }

    public boolean hasAdminRole(UUID playerId) {
        return adminRoles.containsKey(playerId);
    }

    public Optional<AdminRole> roleOf(UUID playerId) {
        return Optional.ofNullable(adminRoles.get(playerId));
    }

    public Optional<PlayerRecord> playerRecord(UUID playerId) {
        return Optional.ofNullable(playerRecords.get(playerId));
    }

    public int playerRecordCount() {
        return playerRecords.size();
    }

    public Optional<Long> economyBalance(UUID playerId) {
        return Optional.ofNullable(economyBalances.get(playerId));
    }

    public int economyAccountCount() {
        return economyBalances.size();
    }

    public Optional<ShopInstance> shopInstance(Identifier shopId) {
        return Optional.ofNullable(shopInstances.get(shopId));
    }

    public int shopInstanceCount() {
        return shopInstances.size();
    }

    public Optional<EconomyTransactionReceipt> economyReceipt(UUID transactionId) {
        return Optional.ofNullable(economyReceipts.get(transactionId));
    }

    Map<UUID, Long> economyBalancesView() {
        return Map.copyOf(economyBalances);
    }

    Map<Identifier, ShopInstance> shopInstancesView() {
        return Map.copyOf(shopInstances);
    }

    Map<UUID, EconomyTransactionReceipt> economyReceiptsView() {
        return Map.copyOf(economyReceipts);
    }

    List<EconomyAlert> economyAlertsView() {
        return List.copyOf(economyAlerts);
    }

    int recentTransactionCount(UUID playerId, long timestampEpochMillis, long windowMillis) {
        ArrayDeque<Long> timestamps = recentTransactionsByPlayer.get(playerId);
        if (timestamps == null) {
            return 0;
        }
        long cutoff = timestampEpochMillis <= windowMillis ? 0 : timestampEpochMillis - windowMillis;
        int count = 0;
        for (long timestamp : timestamps) {
            if (timestamp >= cutoff && timestamp <= timestampEpochMillis) {
                count++;
            }
        }
        return count;
    }

    public int auditCount() {
        return auditEntries.size();
    }

    public AuditPage auditPage(int page, int pageSize) {
        if (page < 0 || pageSize < 1 || pageSize > MAX_AUDIT_PAGE_SIZE) {
            throw new IllegalArgumentException("Invalid audit page request");
        }

        int totalEntries = auditEntries.size();
        int totalPages = totalEntries == 0 ? 0 : (totalEntries + pageSize - 1) / pageSize;
        long offset = (long) page * pageSize;
        if (offset >= totalEntries) {
            return new AuditPage(page, totalPages, totalEntries, List.of());
        }

        int newestIndex = totalEntries - 1 - (int) offset;
        int oldestIndex = Math.max(-1, newestIndex - pageSize);
        List<AuditEntry> entries = new ArrayList<>(newestIndex - oldestIndex);
        for (int index = newestIndex; index > oldestIndex; index--) {
            entries.add(auditEntries.get(index));
        }
        return new AuditPage(page, totalPages, totalEntries, entries);
    }

    void commitRoleChange(UUID targetId, AdminRole role, AuditEntry auditEntry) {
        adminRoles.put(targetId, role);
        commitAudit(auditEntry);
    }

    RestorePreparation prepareTransactionRestore(
            PlatformSavedData snapshot, UUID transactionId, long timestampEpochMillis) {
        Optional<Map<UUID, Long>> transactions = mergeRestoreTransactions(
                economyTransactions,
                snapshot.economyTransactions,
                transactionId,
                timestampEpochMillis,
                ECONOMY_TRANSACTION_RETENTION_MILLIS,
                MAX_ECONOMY_TRANSACTIONS);
        if (transactions.isEmpty()) {
            return new RestorePreparation(RestorePreparationStatus.LEDGER_FULL, Optional.empty());
        }
        Set<UUID> retainedReceiptIds = retainedReceiptIds(
                snapshot.economyReceipts, transactions.orElseThrow().keySet());
        retainedReceiptIds.addAll(retainedReceiptIds(
                economyReceipts, transactions.orElseThrow().keySet()));
        Map<UUID, EconomyTransactionReceipt> receipts = new HashMap<>();
        for (Map.Entry<UUID, EconomyTransactionReceipt> entry : snapshot.economyReceipts.entrySet()) {
            if (retainedReceiptIds.contains(entry.getKey())) {
                receipts.put(entry.getKey(), entry.getValue());
            }
        }
        for (Map.Entry<UUID, EconomyTransactionReceipt> entry : economyReceipts.entrySet()) {
            if (!retainedReceiptIds.contains(entry.getKey())) {
                continue;
            }
            EconomyTransactionReceipt authoritative = receipts.get(entry.getKey());
            if (authoritative != null && !sameReceiptEvidence(authoritative, entry.getValue())) {
                return new RestorePreparation(RestorePreparationStatus.EVIDENCE_CONFLICT, Optional.empty());
            }
            if (authoritative == null) {
                receipts.put(entry.getKey(), entry.getValue().invalidatedByRestore(transactionId));
            }
        }
        if (receipts.size() > MAX_ECONOMY_TRANSACTIONS) {
            return new RestorePreparation(RestorePreparationStatus.LEDGER_FULL, Optional.empty());
        }
        if (receiptLinkError(receipts).isPresent()) {
            return new RestorePreparation(RestorePreparationStatus.EVIDENCE_CONFLICT, Optional.empty());
        }
        List<EconomyAlert> alerts = new ArrayList<>(economyAlerts);
        for (EconomyAlert alert : snapshot.economyAlerts) {
            if (!alerts.contains(alert)) {
                alerts.add(alert);
            }
        }
        alerts.removeIf(alert -> !transactions.orElseThrow().containsKey(alert.transactionId()));
        alerts.sort(Comparator.comparingLong(EconomyAlert::timestampEpochMillis)
                .thenComparing(EconomyAlert::transactionId)
                .thenComparing(alert -> alert.type().getSerializedName()));
        if (alerts.size() > MAX_ECONOMY_ALERTS) {
            alerts = new ArrayList<>(alerts.subList(alerts.size() - MAX_ECONOMY_ALERTS, alerts.size()));
        }
        return new RestorePreparation(RestorePreparationStatus.SUCCESS, Optional.of(new RestoreEconomyEvidence(
                transactions.orElseThrow(), receipts, alerts)));
    }

    static Optional<Map<UUID, Long>> mergeRestoreTransactions(
            Map<UUID, Long> currentTransactions,
            Map<UUID, Long> snapshotTransactions,
            UUID transactionId,
            long timestampEpochMillis,
            long retentionMillis,
            int maximumEntries) {
        Optional<Map<UUID, Long>> merged = mergeEconomyTransactions(
                currentTransactions,
                snapshotTransactions,
                timestampEpochMillis,
                retentionMillis,
                maximumEntries);
        if (merged.isEmpty()) {
            return Optional.empty();
        }
        Map<UUID, Long> withRestore = new HashMap<>(merged.orElseThrow());
        withRestore.put(transactionId, timestampEpochMillis);
        trimExpiredEconomyTransactions(withRestore, timestampEpochMillis, retentionMillis);
        return withRestore.size() <= maximumEntries
                ? Optional.of(Map.copyOf(withRestore))
                : Optional.empty();
    }

    void commitRestore(
            PlatformSavedData snapshot,
            RestoreEconomyEvidence restoredEconomyEvidence,
            AuditEntry auditEntry) {
        adminRoles.clear();
        adminRoles.putAll(snapshot.adminRoles);
        playerRecords.clear();
        playerRecords.putAll(snapshot.playerRecords);
        economyBalances.clear();
        economyBalances.putAll(snapshot.economyBalances);
        economyTransactions.clear();
        economyTransactions.putAll(restoredEconomyEvidence.transactions);
        shopInstances.clear();
        shopInstances.putAll(snapshot.shopInstances);
        economyReceipts.clear();
        economyReceipts.putAll(restoredEconomyEvidence.receipts);
        economyAlerts.clear();
        economyAlerts.addAll(restoredEconomyEvidence.alerts);
        rebuildRecentTransactionIndex();
        commitAudit(auditEntry);
    }

    boolean hasTransaction(UUID transactionId, long timestampEpochMillis) {
        return ledgerContains(
                economyTransactions, transactionId, timestampEpochMillis, ECONOMY_TRANSACTION_RETENTION_MILLIS);
    }

    boolean canCommitTransaction(UUID transactionId, long timestampEpochMillis) {
        return ledgerHasCapacity(
                economyTransactions,
                transactionId,
                timestampEpochMillis,
                ECONOMY_TRANSACTION_RETENTION_MILLIS,
                MAX_ECONOMY_TRANSACTIONS);
    }

    boolean canCommitReceiptTransaction(UUID transactionId, long timestampEpochMillis) {
        if (economyReceipts.containsKey(transactionId)
                || !canCommitTransaction(transactionId, timestampEpochMillis)) {
            return false;
        }
        return economyReceipts.size() < MAX_ECONOMY_TRANSACTIONS
                || retainedReceiptCountAfterCommit(transactionId, Optional.empty(), timestampEpochMillis)
                        <= MAX_ECONOMY_TRANSACTIONS;
    }

    boolean canCommitReversalTransaction(
            UUID transactionId, UUID originalTransactionId, long timestampEpochMillis) {
        if (economyReceipts.containsKey(transactionId)
                || !canCommitTransaction(transactionId, timestampEpochMillis)) {
            return false;
        }
        return economyReceipts.size() < MAX_ECONOMY_TRANSACTIONS
                || retainedReceiptCountAfterCommit(
                        transactionId, Optional.of(originalTransactionId), timestampEpochMillis)
                        <= MAX_ECONOMY_TRANSACTIONS;
    }

    boolean hasEconomyTransaction(UUID transactionId, long timestampEpochMillis) {
        return hasTransaction(transactionId, timestampEpochMillis);
    }

    boolean canCommitEconomyTransaction(UUID transactionId, long timestampEpochMillis) {
        return canCommitReceiptTransaction(transactionId, timestampEpochMillis);
    }

    void commitEconomyTransaction(
            UUID playerId,
            long balance,
            UUID transactionId,
            long timestampEpochMillis,
            EconomyTransactionReceipt receipt,
            List<EconomyAlert> alerts,
            AuditEntry auditEntry) {
        validateEvidence(playerId, transactionId, timestampEpochMillis, receipt, alerts);
        if (economyReceipts.containsKey(transactionId)
                || !canCommitReceiptTransaction(transactionId, timestampEpochMillis)) {
            throw new IllegalStateException("Economy transaction receipt cannot be committed");
        }
        prepareLedgerForCommit(timestampEpochMillis);
        economyBalances.put(playerId, balance);
        economyTransactions.put(transactionId, timestampEpochMillis);
        commitReceiptEvidence(transactionId, receipt, alerts);
        commitAudit(auditEntry);
    }

    void commitShopMutation(
            Identifier shopId,
            Optional<ShopInstance> shop,
            UUID transactionId,
            long timestampEpochMillis,
            AuditEntry auditEntry) {
        if (economyTransactions.size() >= MAX_ECONOMY_TRANSACTIONS) {
            trimExpiredEconomyTransactions(
                    economyTransactions, timestampEpochMillis, ECONOMY_TRANSACTION_RETENTION_MILLIS);
        }
        if (shop.isPresent()) {
            shopInstances.put(shopId, shop.orElseThrow());
        } else {
            shopInstances.remove(shopId);
        }
        economyTransactions.put(transactionId, timestampEpochMillis);
        commitAudit(auditEntry);
    }

    void commitShopTrade(
            UUID playerId,
            long balance,
            Identifier shopId,
            ShopInstance shop,
            UUID transactionId,
            long timestampEpochMillis,
            EconomyTransactionReceipt receipt,
            List<EconomyAlert> alerts,
            AuditEntry auditEntry) {
        validateEvidence(playerId, transactionId, timestampEpochMillis, receipt, alerts);
        if (economyReceipts.containsKey(transactionId)
                || !canCommitReceiptTransaction(transactionId, timestampEpochMillis)) {
            throw new IllegalStateException("Shop transaction receipt cannot be committed");
        }
        prepareLedgerForCommit(timestampEpochMillis);
        Long previousBalance = economyBalances.get(playerId);
        ShopInstance previousShop = shopInstances.get(shopId);
        Long previousTransaction = economyTransactions.get(transactionId);
        int previousAuditSize = auditEntries.size();
        try {
            economyBalances.put(playerId, balance);
            shopInstances.put(shopId, shop);
            economyTransactions.put(transactionId, timestampEpochMillis);
            commitReceiptEvidence(transactionId, receipt, alerts);
            commitAudit(auditEntry);
            if (economyTransactions.size() > MAX_ECONOMY_TRANSACTIONS) {
                trimExpiredEconomyTransactions(
                        economyTransactions, timestampEpochMillis, ECONOMY_TRANSACTION_RETENTION_MILLIS);
            }
        } catch (RuntimeException exception) {
            restoreEntry(economyBalances, playerId, previousBalance);
            restoreEntry(shopInstances, shopId, previousShop);
            restoreEntry(economyTransactions, transactionId, previousTransaction);
            if (auditEntries.size() > previousAuditSize) {
                auditEntries.subList(previousAuditSize, auditEntries.size()).clear();
            }
            setDirty();
            throw exception;
        }
    }

    private static boolean sameReceiptEvidence(
            EconomyTransactionReceipt first, EconomyTransactionReceipt second) {
        boolean itemMatches = first.item().isEmpty() && second.item().isEmpty()
                || first.item().isPresent() && second.item().isPresent()
                && net.minecraft.world.item.ItemStack.matches(first.item().orElseThrow(), second.item().orElseThrow());
        return first.timestampEpochMillis() == second.timestampEpochMillis()
                && first.actorId().equals(second.actorId())
                && first.playerId().equals(second.playerId())
                && first.kind() == second.kind()
                && first.amount() == second.amount()
                && first.shopId().equals(second.shopId())
                && first.offerId().equals(second.offerId())
                && itemMatches
                && first.quantity() == second.quantity()
                && first.stockBefore().equals(second.stockBefore())
                && first.stockAfter().equals(second.stockAfter())
                && first.originalTransactionId().equals(second.originalTransactionId())
                && first.compensationDecision() == second.compensationDecision();
    }

    void commitEconomyReversal(
            UUID playerId,
            long balance,
            Optional<Map.Entry<Identifier, ShopInstance>> changedShop,
            UUID originalTransactionId,
            UUID reversalTransactionId,
            long timestampEpochMillis,
            EconomyTransactionReceipt reversalReceipt,
            List<EconomyAlert> alerts,
            AuditEntry auditEntry) {
        validateEvidence(playerId, reversalTransactionId, timestampEpochMillis, reversalReceipt, alerts);
        EconomyTransactionReceipt original = economyReceipts.get(originalTransactionId);
        if (original == null || !hasTransaction(originalTransactionId, timestampEpochMillis)
                || original.reversedBy().isPresent() || original.invalidatedByRestore().isPresent()
                || !original.playerId().equals(playerId)
                || !reversalReceipt.originalTransactionId().equals(Optional.of(originalTransactionId))) {
            throw new IllegalStateException("Original transaction is not reversible");
        }
        if (economyReceipts.containsKey(reversalTransactionId)
                || !canCommitReversalTransaction(
                        reversalTransactionId, originalTransactionId, timestampEpochMillis)) {
            throw new IllegalStateException("Reversal transaction receipt cannot be committed");
        }
        prepareLedgerForCommit(timestampEpochMillis);
        economyBalances.put(playerId, balance);
        changedShop.ifPresent(entry -> shopInstances.put(entry.getKey(), entry.getValue()));
        economyTransactions.put(reversalTransactionId, timestampEpochMillis);
        economyReceipts.put(originalTransactionId, original.withReversedBy(reversalTransactionId));
        commitReceiptEvidence(reversalTransactionId, reversalReceipt, alerts);
        commitAudit(auditEntry);
    }

    private void commitReceiptEvidence(
            UUID transactionId, EconomyTransactionReceipt receipt, List<EconomyAlert> alerts) {
        if (economyReceipts.putIfAbsent(transactionId, receipt) != null) {
            throw new IllegalStateException("Economy transaction receipt already exists");
        }
        ArrayDeque<Long> timestamps = recentTransactionsByPlayer.computeIfAbsent(
                receipt.playerId(), ignored -> new ArrayDeque<>());
        timestamps.addLast(receipt.timestampEpochMillis());
        while (timestamps.size() > MAX_RATE_INDEX_PER_PLAYER) {
            timestamps.removeFirst();
        }
        economyAlerts.addAll(alerts);
        while (economyAlerts.size() > MAX_ECONOMY_ALERTS) {
            economyAlerts.removeFirst();
        }
        if (economyAlerts.size() >= MAX_ECONOMY_ALERTS) {
            long cutoff = cutoff(receipt.timestampEpochMillis(), ECONOMY_TRANSACTION_RETENTION_MILLIS);
            economyAlerts.removeIf(alert -> alert.timestampEpochMillis() < cutoff);
        }
        pruneReceiptsToActiveEvidence(receipt.timestampEpochMillis());
    }

    private void prepareLedgerForCommit(long timestampEpochMillis) {
        if (economyTransactions.size() >= MAX_ECONOMY_TRANSACTIONS) {
            trimExpiredEconomyTransactions(
                    economyTransactions, timestampEpochMillis, ECONOMY_TRANSACTION_RETENTION_MILLIS);
        }
    }

    private int retainedReceiptCountAfterCommit(
            UUID transactionId, Optional<UUID> reversalOriginalId, long timestampEpochMillis) {
        Set<UUID> activeTransactions = new HashSet<>();
        long cutoff = cutoff(timestampEpochMillis, ECONOMY_TRANSACTION_RETENTION_MILLIS);
        economyTransactions.forEach((id, timestamp) -> {
            if (timestamp >= cutoff) {
                activeTransactions.add(id);
            }
        });
        activeTransactions.add(transactionId);
        Set<UUID> retainedReceipts = retainedReceiptIds(economyReceipts, activeTransactions);
        reversalOriginalId.ifPresent(retainedReceipts::add);
        retainedReceipts.add(transactionId);
        return retainedReceipts.size();
    }

    private void pruneReceiptsToActiveEvidence(long timestampEpochMillis) {
        if (economyReceipts.size() <= MAX_ECONOMY_TRANSACTIONS) {
            return;
        }
        Set<UUID> activeTransactions = new HashSet<>();
        long cutoff = cutoff(timestampEpochMillis, ECONOMY_TRANSACTION_RETENTION_MILLIS);
        economyTransactions.forEach((id, timestamp) -> {
            if (timestamp >= cutoff) {
                activeTransactions.add(id);
            }
        });
        economyReceipts.keySet().retainAll(retainedReceiptIds(economyReceipts, activeTransactions));
    }

    static Set<UUID> retainedReceiptIds(
            Map<UUID, EconomyTransactionReceipt> receipts,
            java.util.Collection<UUID> activeTransactionIds) {
        Set<UUID> retained = new HashSet<>();
        for (UUID transactionId : activeTransactionIds) {
            EconomyTransactionReceipt receipt = receipts.get(transactionId);
            if (receipt != null) {
                retained.add(transactionId);
                if (receipt.kind() == EconomyTransactionReceipt.Kind.REVERSAL) {
                    receipt.originalTransactionId().ifPresent(retained::add);
                }
            }
        }
        return retained;
    }

    private static void validateEvidence(
            UUID playerId,
            UUID transactionId,
            long timestampEpochMillis,
            EconomyTransactionReceipt receipt,
            List<EconomyAlert> alerts) {
        if (receipt == null || alerts == null || !receipt.playerId().equals(playerId)
                || receipt.timestampEpochMillis() != timestampEpochMillis
                || alerts.stream().anyMatch(alert -> !alert.playerId().equals(playerId)
                        || !alert.transactionId().equals(transactionId)
                        || alert.timestampEpochMillis() != timestampEpochMillis)) {
            throw new IllegalArgumentException("Transaction evidence does not match the committed mutation");
        }
    }

    private static <K, V> void restoreEntry(Map<K, V> values, K key, V previousValue) {
        if (previousValue == null) {
            values.remove(key);
        } else {
            values.put(key, previousValue);
        }
    }

    synchronized boolean tryLockShop(Identifier shopId) {
        return shopDependencyLocks.add(shopId);
    }

    synchronized void unlockShop(Identifier shopId) {
        shopDependencyLocks.remove(shopId);
    }

    synchronized boolean isShopLocked(Identifier shopId) {
        return shopDependencyLocks.contains(shopId);
    }

    synchronized boolean hasShopLocks() {
        return !shopDependencyLocks.isEmpty();
    }

    static Codec<Map<UUID, Long>> boundedTransactionLedgerCodec(int maximumEntries) {
        return Codec.unboundedMap(UUIDUtil.STRING_CODEC, TRANSACTION_TIMESTAMP_CODEC).validate(transactions ->
                transactions.containsKey(ZERO_UUID)
                        ? DataResult.error(() -> "Economy transaction ledger contains the zero UUID")
                        : transactions.size() > maximumEntries
                                ? DataResult.error(() ->
                                        "Economy transaction ledger exceeds " + maximumEntries + " entries")
                                : DataResult.success(Map.copyOf(transactions)));
    }

    static Codec<Map<Identifier, ShopInstance>> boundedShopInstancesCodec(int maximumEntries) {
        return ShopInstanceEntry.CODEC.listOf(0, maximumEntries)
                .flatXmap(PlatformSavedData::shopsFromEntries, PlatformSavedData::shopEntries);
    }

    static Codec<Map<UUID, EconomyTransactionReceipt>> boundedReceiptsCodec(int maximumEntries) {
        return ReceiptEntry.CODEC.listOf(0, maximumEntries)
                .flatXmap(PlatformSavedData::receiptsFromEntries, PlatformSavedData::receiptEntries);
    }

    private static DataResult<Map<UUID, EconomyTransactionReceipt>> receiptsFromEntries(List<ReceiptEntry> entries) {
        Map<UUID, EconomyTransactionReceipt> receipts = new LinkedHashMap<>();
        for (ReceiptEntry entry : entries) {
            if (ZERO_UUID.equals(entry.id()) || receipts.putIfAbsent(entry.id(), entry.receipt()) != null) {
                return DataResult.error(() -> "Duplicate or zero economy receipt ID " + entry.id());
            }
        }
        Optional<String> linkError = receiptLinkError(receipts);
        if (linkError.isPresent()) {
            return DataResult.error(linkError::orElseThrow);
        }
        return DataResult.success(Map.copyOf(receipts));
    }

    private static Optional<String> receiptLinkError(Map<UUID, EconomyTransactionReceipt> receipts) {
        for (Map.Entry<UUID, EconomyTransactionReceipt> entry : receipts.entrySet()) {
            EconomyTransactionReceipt receipt = entry.getValue();
            if (receipt.kind() == EconomyTransactionReceipt.Kind.REVERSAL) {
                UUID originalId = receipt.originalTransactionId().orElseThrow();
                EconomyTransactionReceipt original = receipts.get(originalId);
                if (receipt.reversedBy().isPresent()
                        || original == null
                        || original.kind() == EconomyTransactionReceipt.Kind.REVERSAL
                        || !original.reversedBy().equals(Optional.of(entry.getKey()))
                        || !original.playerId().equals(receipt.playerId())) {
                    return Optional.of("Economy reversal receipt has an invalid original link " + entry.getKey());
                }
            }
            Optional<UUID> reversedBy = receipt.reversedBy();
            if (reversedBy.isPresent()) {
                EconomyTransactionReceipt reversal = receipts.get(reversedBy.orElseThrow());
                if (reversal == null || reversal.kind() != EconomyTransactionReceipt.Kind.REVERSAL
                        || !reversal.originalTransactionId().equals(Optional.of(entry.getKey()))
                        || !reversal.playerId().equals(receipt.playerId())) {
                    return Optional.of("Economy receipt has an invalid reversal link " + entry.getKey());
                }
            }
        }
        return Optional.empty();
    }

    private static DataResult<List<ReceiptEntry>> receiptEntries(Map<UUID, EconomyTransactionReceipt> receipts) {
        return DataResult.success(receipts.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new ReceiptEntry(entry.getKey(), entry.getValue()))
                .toList());
    }

    private record ReceiptEntry(UUID id, EconomyTransactionReceipt receipt) {
        private static final Codec<ReceiptEntry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                UUIDUtil.STRING_CODEC.fieldOf("id").forGetter(ReceiptEntry::id),
                EconomyTransactionReceipt.CODEC.fieldOf("receipt").forGetter(ReceiptEntry::receipt)
        ).apply(instance, ReceiptEntry::new));
    }

    private static DataResult<Map<Identifier, ShopInstance>> shopsFromEntries(List<ShopInstanceEntry> entries) {
        Map<Identifier, ShopInstance> shops = new LinkedHashMap<>();
        for (ShopInstanceEntry entry : entries) {
            if (shops.putIfAbsent(entry.id(), entry.shop()) != null) {
                return DataResult.error(() -> "Duplicate shop instance ID " + entry.id());
            }
        }
        return DataResult.success(Map.copyOf(shops));
    }

    private static DataResult<List<ShopInstanceEntry>> shopEntries(Map<Identifier, ShopInstance> shops) {
        return DataResult.success(shops.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new ShopInstanceEntry(entry.getKey(), entry.getValue()))
                .toList());
    }

    private record ShopInstanceEntry(Identifier id, ShopInstance shop) {
        private static final Codec<ShopInstanceEntry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Identifier.CODEC.fieldOf("id").forGetter(ShopInstanceEntry::id),
                ShopInstance.CODEC.fieldOf("shop").forGetter(ShopInstanceEntry::shop)
        ).apply(instance, ShopInstanceEntry::new));
    }

    static boolean ledgerContains(
            Map<UUID, Long> transactions,
            UUID transactionId,
            long timestampEpochMillis,
            long retentionMillis) {
        Long committedAt = transactions.get(transactionId);
        return committedAt != null && committedAt >= cutoff(timestampEpochMillis, retentionMillis);
    }

    static boolean ledgerHasCapacity(
            Map<UUID, Long> transactions,
            UUID transactionId,
            long timestampEpochMillis,
            long retentionMillis,
            int maximumEntries) {
        if (transactions.containsKey(transactionId) || transactions.size() < maximumEntries) {
            return true;
        }
        long cutoff = cutoff(timestampEpochMillis, retentionMillis);
        return transactions.values().stream().anyMatch(timestamp -> timestamp < cutoff);
    }

    static Optional<Map<UUID, Long>> mergeEconomyTransactions(
            Map<UUID, Long> currentTransactions,
            Map<UUID, Long> snapshotTransactions,
            long timestampEpochMillis,
            long retentionMillis,
            int maximumEntries) {
        Map<UUID, Long> merged = new HashMap<>(currentTransactions);
        snapshotTransactions.forEach((transactionId, timestamp) ->
                merged.merge(transactionId, timestamp, Math::max));
        trimExpiredEconomyTransactions(merged, timestampEpochMillis, retentionMillis);
        return merged.size() <= maximumEntries ? Optional.of(Map.copyOf(merged)) : Optional.empty();
    }

    static void trimExpiredEconomyTransactions(
            Map<UUID, Long> transactions,
            long timestampEpochMillis,
            long retentionMillis) {
        long cutoff = cutoff(timestampEpochMillis, retentionMillis);
        transactions.entrySet().removeIf(entry -> entry.getValue() < cutoff);
    }

    private static long cutoff(long timestampEpochMillis, long retentionMillis) {
        return timestampEpochMillis <= retentionMillis ? 0 : timestampEpochMillis - retentionMillis;
    }

    private void rebuildRecentTransactionIndex() {
        recentTransactionsByPlayer.clear();
        economyReceipts.values().stream()
                .sorted(Comparator.comparingLong(EconomyTransactionReceipt::timestampEpochMillis))
                .forEach(receipt -> {
                    ArrayDeque<Long> timestamps = recentTransactionsByPlayer.computeIfAbsent(
                            receipt.playerId(), ignored -> new ArrayDeque<>());
                    timestamps.addLast(receipt.timestampEpochMillis());
                    while (timestamps.size() > MAX_RATE_INDEX_PER_PLAYER) {
                        timestamps.removeFirst();
                    }
                });
    }

    boolean commitPlayerLogin(UUID playerId, long timestampEpochMillis) {
        PlayerRecord previous = playerRecords.get(playerId);
        PlayerRecord updated = previous == null
                ? new PlayerRecord(timestampEpochMillis, timestampEpochMillis)
                : previous.observe(timestampEpochMillis);
        if (updated.equals(previous)) {
            return false;
        }
        playerRecords.put(playerId, updated);
        setDirty();
        return true;
    }

    boolean appendDeniedAudit(AuditEntry auditEntry, long minimumIntervalMillis) {
        Long previous = lastDeniedAuditByActor.get(auditEntry.actorId());
        if (previous != null && auditEntry.timestampEpochMillis() - previous < minimumIntervalMillis) {
            return false;
        }
        lastDeniedAuditByActor.put(auditEntry.actorId(), auditEntry.timestampEpochMillis());
        commitAudit(auditEntry);
        return true;
    }

    void commitAudit(AuditEntry auditEntry) {
        auditEntries.add(auditEntry);
        long cutoff = auditEntry.timestampEpochMillis() - AUDIT_RETENTION.toMillis();
        auditEntries.removeIf(entry -> entry.timestampEpochMillis() < cutoff);
        setDirty();
    }

    public record AuditPage(int page, int totalPages, int totalEntries, List<AuditEntry> entries) {
        public AuditPage {
            entries = List.copyOf(entries);
        }
    }

    enum RestorePreparationStatus {
        SUCCESS,
        LEDGER_FULL,
        EVIDENCE_CONFLICT
    }

    record RestorePreparation(RestorePreparationStatus status, Optional<RestoreEconomyEvidence> evidence) {
    }

    record RestoreEconomyEvidence(
            Map<UUID, Long> transactions,
            Map<UUID, EconomyTransactionReceipt> receipts,
            List<EconomyAlert> alerts) {
        RestoreEconomyEvidence {
            transactions = Map.copyOf(transactions);
            receipts = Map.copyOf(receipts);
            alerts = List.copyOf(alerts);
        }
    }
}
