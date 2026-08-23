package org.dldyou.rovenfall.administration;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    public static final int CURRENT_SCHEMA_VERSION = 4;
    public static final int MAX_AUDIT_PAGE_SIZE = 50;
    static final int MAX_ECONOMY_TRANSACTIONS = 250_000;
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

    public static final Codec<PlatformSavedData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.optionalFieldOf("schema_version", 0).forGetter(data -> data.schemaVersion),
            ADMIN_ROLES_CODEC.optionalFieldOf("admin_roles", Map.of()).forGetter(data -> data.adminRoles),
            AuditEntry.CODEC.listOf().optionalFieldOf("audit_entries", List.of()).forGetter(data -> data.auditEntries),
            PLAYER_RECORDS_CODEC.optionalFieldOf("player_records", Map.of()).forGetter(data -> data.playerRecords),
            ECONOMY_BALANCES_CODEC.optionalFieldOf("economy_balances", Map.of()).forGetter(data -> data.economyBalances),
            ECONOMY_TRANSACTIONS_CODEC.optionalFieldOf("economy_transactions", Map.of())
                    .forGetter(data -> data.economyTransactions),
            SHOP_INSTANCES_CODEC.optionalFieldOf("shop_instances", Map.of()).forGetter(data -> data.shopInstances)
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
    private final java.util.Set<Identifier> shopDependencyLocks = new HashSet<>();
    private final Map<UUID, Long> lastDeniedAuditByActor = new HashMap<>();

    public PlatformSavedData() {
        this(CURRENT_SCHEMA_VERSION, Map.of(), List.of(), Map.of(), Map.of(), Map.of(), Map.of(), true);
    }

    private PlatformSavedData(
            int schemaVersion,
            Map<UUID, AdminRole> adminRoles,
            List<AuditEntry> auditEntries,
            Map<UUID, PlayerRecord> playerRecords,
            Map<UUID, Long> economyBalances,
            Map<UUID, Long> economyTransactions,
            Map<Identifier, ShopInstance> shopInstances,
            boolean writable) {
        this.schemaVersion = schemaVersion;
        this.writable = writable;
        this.adminRoles = new HashMap<>(adminRoles);
        this.auditEntries = new ArrayList<>(auditEntries);
        this.playerRecords = new HashMap<>(playerRecords);
        this.economyBalances = new HashMap<>(economyBalances);
        this.economyTransactions = new HashMap<>(economyTransactions);
        this.shopInstances = new HashMap<>(shopInstances);
    }

    private static PlatformSavedData decode(
            int schemaVersion,
            Map<UUID, AdminRole> adminRoles,
            List<AuditEntry> auditEntries,
            Map<UUID, PlayerRecord> playerRecords,
            Map<UUID, Long> economyBalances,
            Map<UUID, Long> economyTransactions,
            Map<Identifier, ShopInstance> shopInstances) {
        var migration = PlatformDataMigrations.migrate(
                schemaVersion,
                adminRoles,
                auditEntries,
                playerRecords,
                economyBalances,
                economyTransactions,
                shopInstances,
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

    Optional<Map<UUID, Long>> prepareTransactionRestore(
            PlatformSavedData snapshot, UUID transactionId, long timestampEpochMillis) {
        return mergeRestoreTransactions(
                economyTransactions,
                snapshot.economyTransactions,
                transactionId,
                timestampEpochMillis,
                ECONOMY_TRANSACTION_RETENTION_MILLIS,
                MAX_ECONOMY_TRANSACTIONS);
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
            Map<UUID, Long> restoredEconomyTransactions,
            AuditEntry auditEntry) {
        adminRoles.clear();
        adminRoles.putAll(snapshot.adminRoles);
        playerRecords.clear();
        playerRecords.putAll(snapshot.playerRecords);
        economyBalances.clear();
        economyBalances.putAll(snapshot.economyBalances);
        economyTransactions.clear();
        economyTransactions.putAll(restoredEconomyTransactions);
        shopInstances.clear();
        shopInstances.putAll(snapshot.shopInstances);
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

    boolean hasEconomyTransaction(UUID transactionId, long timestampEpochMillis) {
        return hasTransaction(transactionId, timestampEpochMillis);
    }

    boolean canCommitEconomyTransaction(UUID transactionId, long timestampEpochMillis) {
        return canCommitTransaction(transactionId, timestampEpochMillis);
    }

    void commitEconomyTransaction(
            UUID playerId,
            long balance,
            UUID transactionId,
            long timestampEpochMillis,
            AuditEntry auditEntry) {
        if (economyTransactions.size() >= MAX_ECONOMY_TRANSACTIONS) {
            trimExpiredEconomyTransactions(
                    economyTransactions, timestampEpochMillis, ECONOMY_TRANSACTION_RETENTION_MILLIS);
        }
        economyBalances.put(playerId, balance);
        economyTransactions.put(transactionId, timestampEpochMillis);
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
}
