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
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import org.dldyou.rovenfall.Rovenfall;
import org.dldyou.rovenfall.claims.Claim;
import org.dldyou.rovenfall.claims.ClaimKey;
import org.dldyou.rovenfall.claims.ClaimMutationReceipt;
import org.dldyou.rovenfall.economy.ShopInstance;
import org.dldyou.rovenfall.world.ProtectedRegion;
import org.dldyou.rovenfall.world.PortalDefinition;
import org.dldyou.rovenfall.world.WorldTopology;

public final class PlatformSavedData extends SavedData {
    private static final UUID ZERO_UUID = new UUID(0L, 0L);
    public static final int CURRENT_SCHEMA_VERSION = 13;
    public static final int MAX_PROTECTED_REGIONS = 128;
    public static final int MAX_INDEXED_PROTECTED_CHUNKS = 131_072;
    public static final int MAX_AUDIT_PAGE_SIZE = 50;
    static final int MAX_ECONOMY_TRANSACTIONS = 250_000;
    static final int MAX_ECONOMY_ALERTS = 10_000;
    static final int MAX_RPG_SKILL_OPERATIONS = 10_000;
    static final int MAX_RPG_ADMIN_OPERATIONS = 10_000;
    static final int MAX_RATE_INDEX_PER_PLAYER = 10_000;
    static final long ECONOMY_TRANSACTION_RETENTION_MILLIS = Duration.ofDays(30).toMillis();
    private static final long NON_EXPIRING_RECEIPT = -1L;
    private static final Duration AUDIT_RETENTION = Duration.ofDays(30);
    private static final Comparator<AuditEntry> AUDIT_NEWEST_FIRST = Comparator
            .comparingLong(AuditEntry::timestampEpochMillis).reversed()
            .thenComparing(AuditEntry::transactionId);
    static final int MAX_AUDIT_ENTRIES = 100_000;
    static final int MAX_DENIED_AUDIT_ACTORS = 10_000;
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
    private static final Codec<Map<ClaimKey, Claim>> CLAIMS_CODEC = boundedClaimsCodec(Claim.MAX_CLAIMS);
    private static final Codec<Map<UUID, ClaimMutationReceipt>> CLAIM_RECEIPTS_CODEC =
            boundedClaimReceiptsCodec(MAX_ECONOMY_TRANSACTIONS);
    private static final Codec<Map<Identifier, ProtectedRegion>> PROTECTED_REGIONS_CODEC =
            boundedProtectedRegionsCodec(MAX_PROTECTED_REGIONS, MAX_INDEXED_PROTECTED_CHUNKS);
    private static final Codec<Map<UUID, RpgSkillOperation>> RPG_SKILL_OPERATIONS_CODEC =
            RpgSkillOperationEntry.CODEC.listOf(0, MAX_RPG_SKILL_OPERATIONS)
                    .flatXmap(PlatformSavedData::rpgSkillOperationsFromEntries,
                            PlatformSavedData::rpgSkillOperationEntries);
    private static final Codec<Map<UUID, RpgAdminOperation>> RPG_ADMIN_OPERATIONS_CODEC =
            RpgAdminOperationEntry.CODEC.listOf(0, MAX_RPG_ADMIN_OPERATIONS)
                    .flatXmap(PlatformSavedData::rpgAdminOperationsFromEntries,
                            PlatformSavedData::rpgAdminOperationEntries);

    public static final Codec<PlatformSavedData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.optionalFieldOf("schema_version", 0).forGetter(data -> data.schemaVersion),
            ADMIN_ROLES_CODEC.optionalFieldOf("admin_roles", Map.of()).forGetter(data -> data.adminRoles),
            AuditEntry.CODEC.listOf(0, MAX_AUDIT_ENTRIES).optionalFieldOf("audit_entries", List.of())
                    .forGetter(data -> List.copyOf(data.auditEntries)),
            PLAYER_RECORDS_CODEC.optionalFieldOf("player_records", Map.of()).forGetter(data -> data.playerRecords),
            ECONOMY_BALANCES_CODEC.optionalFieldOf("economy_balances", Map.of()).forGetter(data -> data.economyBalances),
            ECONOMY_TRANSACTIONS_CODEC.optionalFieldOf("economy_transactions", Map.of())
                    .forGetter(data -> data.economyTransactions),
            SHOP_INSTANCES_CODEC.optionalFieldOf("shop_instances", Map.of()).forGetter(data -> data.shopInstances),
            ECONOMY_RECEIPTS_CODEC.optionalFieldOf("economy_receipts", Map.of()).forGetter(data -> data.economyReceipts),
            ECONOMY_ALERTS_CODEC.optionalFieldOf("economy_alerts", List.of()).forGetter(data -> data.economyAlerts),
            CLAIMS_CODEC.optionalFieldOf("claims", Map.of()).forGetter(data -> data.claims),
            CLAIM_RECEIPTS_CODEC.optionalFieldOf("claim_receipts", Map.of()).forGetter(data -> data.claimReceipts),
            PROTECTED_REGIONS_CODEC.optionalFieldOf("protected_regions", Map.of())
                    .forGetter(data -> data.protectedRegions),
            PortalState.CODEC.optionalFieldOf("portal_state", PortalState.EMPTY).forGetter(PlatformSavedData::portalState),
            WildernessResetState.CODEC.optionalFieldOf("wilderness_reset", WildernessResetState.EMPTY)
                    .forGetter(PlatformSavedData::wildernessResetState),
            RPG_SKILL_OPERATIONS_CODEC.optionalFieldOf("rpg_skill_operations", Map.of())
                    .forGetter(data -> data.rpgSkillOperations),
            RPG_ADMIN_OPERATIONS_CODEC.optionalFieldOf("rpg_admin_operations", Map.of())
                    .forGetter(data -> data.rpgAdminOperations)
    ).apply(instance, PlatformSavedData::decode));

    public static final SavedDataType<PlatformSavedData> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(Rovenfall.MOD_ID, "platform"),
            PlatformSavedData::new,
            CODEC
    );

    private final int schemaVersion;
    private final boolean writable;
    private final Map<UUID, AdminRole> adminRoles;
    private final ArrayDeque<AuditEntry> auditEntries;
    private final Map<UUID, PlayerRecord> playerRecords;
    private final Map<UUID, Long> economyBalances;
    private final Map<UUID, Long> economyTransactions;
    private final Map<Identifier, ShopInstance> shopInstances;
    private final Map<UUID, EconomyTransactionReceipt> economyReceipts;
    private final List<EconomyAlert> economyAlerts;
    private final Map<ClaimKey, Claim> claims;
    private final Map<UUID, ClaimMutationReceipt> claimReceipts;
    private final Map<Identifier, ProtectedRegion> protectedRegions;
    private final Map<Identifier, PortalDefinition> portalDefinitions;
    private final Map<UUID, Map<Identifier, Long>> portalCooldowns;
    private final Map<UUID, PortalState.TravelReceipt> portalTravelReceipts;
    private final Map<UUID, Long> portalCombatTimestamps;
    private WildernessResetState wildernessResetState;
    private final Map<UUID, RpgSkillOperation> rpgSkillOperations;
    private final Map<UUID, RpgAdminOperation> rpgAdminOperations;
    private final Map<PortalDefinition.Endpoint, Identifier> portalOriginIndex = new HashMap<>();
    private final Map<ClaimKey, Set<Identifier>> protectedRegionIndex = new HashMap<>();
    private final Map<UUID, Integer> claimCountsByOwner = new HashMap<>();
    private final Map<UUID, ArrayDeque<Long>> recentTransactionsByPlayer = new HashMap<>();
    private final Map<UUID, Long> receiptEvictionTimes = new HashMap<>();
    private final PriorityQueue<ReceiptExpiry> receiptExpiryQueue = new PriorityQueue<>(
            Comparator.comparingLong(ReceiptExpiry::timestampEpochMillis)
                    .thenComparing(ReceiptExpiry::reversal, Comparator.reverseOrder())
                    .thenComparing(ReceiptExpiry::transactionId));
    private final PriorityQueue<PortalReceiptExpiry> portalReceiptExpiryQueue = new PriorityQueue<>(
            Comparator.comparingLong(PortalReceiptExpiry::expiryEpochMillis)
                    .thenComparing(PortalReceiptExpiry::transactionId));
    private final PriorityQueue<PortalCooldownExpiry> portalCooldownExpiryQueue = new PriorityQueue<>(
            Comparator.comparingLong(PortalCooldownExpiry::deadlineEpochMillis)
                    .thenComparing(PortalCooldownExpiry::playerId)
                    .thenComparing(PortalCooldownExpiry::portalId));
    private int activePortalCooldownCount;
    private final java.util.Set<Identifier> shopDependencyLocks = new HashSet<>();
    private final Map<UUID, Long> lastDeniedAuditByActor = new LinkedHashMap<>(16, 0.75F, true);

    public PlatformSavedData() {
        this(CURRENT_SCHEMA_VERSION, Map.of(), List.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), List.of(),
                Map.of(), Map.of(), Map.of(), PortalState.EMPTY, WildernessResetState.EMPTY, Map.of(), Map.of(), true);
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
            Map<ClaimKey, Claim> claims,
            Map<UUID, ClaimMutationReceipt> claimReceipts,
            Map<Identifier, ProtectedRegion> protectedRegions,
            PortalState portalState,
            WildernessResetState wildernessResetState,
            Map<UUID, RpgSkillOperation> rpgSkillOperations,
            Map<UUID, RpgAdminOperation> rpgAdminOperations,
            boolean writable) {
        this.schemaVersion = schemaVersion;
        this.writable = writable;
        this.adminRoles = new HashMap<>(adminRoles);
        this.auditEntries = new ArrayDeque<>(auditEntries);
        while (this.auditEntries.size() > MAX_AUDIT_ENTRIES) {
            this.auditEntries.removeFirst();
        }
        this.playerRecords = new HashMap<>(playerRecords);
        this.economyBalances = new HashMap<>(economyBalances);
        this.economyTransactions = new HashMap<>(economyTransactions);
        this.shopInstances = new HashMap<>(shopInstances);
        this.economyReceipts = new HashMap<>(economyReceipts);
        this.economyAlerts = new ArrayList<>(economyAlerts);
        this.claims = new HashMap<>(claims);
        this.claimReceipts = new HashMap<>(claimReceipts);
        this.protectedRegions = new HashMap<>(protectedRegions);
        this.portalDefinitions = new HashMap<>(portalState.definitions());
        this.portalCooldowns = new HashMap<>();
        portalState.cooldowns().forEach((player, cooldowns) ->
                this.portalCooldowns.put(player, new HashMap<>(cooldowns)));
        this.portalTravelReceipts = new HashMap<>(portalState.receipts());
        this.portalCombatTimestamps = new HashMap<>(portalState.combatTimestamps());
        this.wildernessResetState = wildernessResetState == null ? WildernessResetState.EMPTY : wildernessResetState;
        this.rpgSkillOperations = new HashMap<>(rpgSkillOperations);
        this.rpgAdminOperations = new HashMap<>(rpgAdminOperations);
        trimCompletedRpgAdminOperations(0L);
        rebuildRecentTransactionIndex();
        rebuildReceiptExpiryIndex();
        rebuildClaimOwnerIndex();
        rebuildProtectedRegionIndex();
        rebuildPortalOriginIndex();
        rebuildPortalEvidenceIndexes();
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
            List<EconomyAlert> economyAlerts,
            Map<ClaimKey, Claim> claims,
            Map<UUID, ClaimMutationReceipt> claimReceipts,
            Map<Identifier, ProtectedRegion> protectedRegions,
            PortalState portalState,
            WildernessResetState wildernessResetState,
            Map<UUID, RpgSkillOperation> rpgSkillOperations,
            Map<UUID, RpgAdminOperation> rpgAdminOperations) {
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
                claims,
                claimReceipts,
                protectedRegions,
                portalState,
                wildernessResetState,
                rpgSkillOperations,
                rpgAdminOperations,
                CURRENT_SCHEMA_VERSION
        );
        var state = migration.state();
        boolean rpgSkillEvidenceValid = rpgSkillEvidenceValid(
                state.rpgSkillOperations(), state.economyReceipts(), state.economyTransactions());
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
                state.claims(),
                state.claimReceipts(),
                state.protectedRegions(),
                state.portalState(),
                state.wildernessResetState(),
                state.rpgSkillOperations(),
                state.rpgAdminOperations(),
                migration.writable() && rpgSkillEvidenceValid
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

    /** Missing platform evidence may only be reconstructed while its transaction tombstone is retained. */
    public static boolean isEconomyRecoveryWindow(long transactionTimestamp, long currentTimestamp) {
        if (transactionTimestamp < 0 || currentTimestamp < 0) {
            return false;
        }
        return currentTimestamp < transactionTimestamp
                || currentTimestamp <= ECONOMY_TRANSACTION_RETENTION_MILLIS
                || transactionTimestamp >= currentTimestamp - ECONOMY_TRANSACTION_RETENTION_MILLIS;
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

    /** Immutable, deterministically ordered player projection for bounded administration queries. */
    public List<Map.Entry<UUID, PlayerRecord>> playerRecords() {
        return playerRecords(Integer.MAX_VALUE);
    }

    public List<Map.Entry<UUID, PlayerRecord>> playerRecords(int maximumEntries) {
        if (maximumEntries < 1) {
            throw new IllegalArgumentException("Player record query must be bounded");
        }
        return playerRecords.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .limit(maximumEntries)
                .map(entry -> Map.entry(entry.getKey(), entry.getValue()))
                .toList();
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

    public Optional<Claim> claim(ClaimKey key) {
        return Optional.ofNullable(claims.get(key));
    }

    public int claimCount(UUID ownerId) {
        return claimCountsByOwner.getOrDefault(ownerId, 0);
    }

    public int claimCount() {
        return claims.size();
    }

    /** Immutable, deterministically ordered claim projection for bounded administration queries. */
    public List<Map.Entry<ClaimKey, Claim>> claims() {
        return claims(Integer.MAX_VALUE);
    }

    public List<Map.Entry<ClaimKey, Claim>> claims(int maximumEntries) {
        if (maximumEntries < 1) {
            throw new IllegalArgumentException("Claim query must be bounded");
        }
        return claims.entrySet().stream()
                .sorted(Comparator.comparing(entry -> entry.getKey().auditTarget()))
                .limit(maximumEntries)
                .map(entry -> Map.entry(entry.getKey(), entry.getValue()))
                .toList();
    }

    public Optional<ClaimMutationReceipt> claimReceipt(UUID transactionId) {
        return Optional.ofNullable(claimReceipts.get(transactionId));
    }

    public Optional<ProtectedRegion> protectedRegion(Identifier regionId) {
        return Optional.ofNullable(protectedRegions.get(regionId));
    }

    public Set<Identifier> protectedRegionsAt(ClaimKey key) {
        Set<Identifier> regionIds = protectedRegionIndex.get(key);
        return regionIds == null ? Set.of() : Set.copyOf(regionIds);
    }

    public boolean isProtectedRegion(ClaimKey key) {
        return protectedRegionIndex.containsKey(key);
    }

    public int protectedRegionCount() {
        return protectedRegions.size();
    }

    public List<Map.Entry<Identifier, ProtectedRegion>> protectedRegions() {
        return protectedRegions.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> Map.entry(entry.getKey(), entry.getValue()))
                .toList();
    }

    public Optional<PortalDefinition> portalDefinition(Identifier portalId) {
        return Optional.ofNullable(portalDefinitions.get(portalId));
    }

    public Optional<Identifier> portalAt(PortalDefinition.Endpoint origin) {
        return Optional.ofNullable(portalOriginIndex.get(origin));
    }

    public List<Map.Entry<Identifier, PortalDefinition>> portalDefinitions() {
        return portalDefinitions(Integer.MAX_VALUE);
    }

    public List<Map.Entry<Identifier, PortalDefinition>> portalDefinitions(int maximumEntries) {
        if (maximumEntries < 1) {
            throw new IllegalArgumentException("Portal query must be bounded");
        }
        return portalDefinitions.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .limit(maximumEntries)
                .map(entry -> Map.entry(entry.getKey(), entry.getValue()))
                .toList();
    }

    public int portalDefinitionCount() {
        return portalDefinitions.size();
    }

    public long portalCooldownUntil(UUID playerId, Identifier portalId) {
        return portalCooldowns.getOrDefault(playerId, Map.of()).getOrDefault(portalId, 0L);
    }

    Optional<PortalState.TravelReceipt> portalTravelReceipt(UUID transactionId) {
        return Optional.ofNullable(portalTravelReceipts.get(transactionId));
    }

    public Optional<Long> portalCombatTimestamp(UUID playerId) {
        return Optional.ofNullable(portalCombatTimestamps.get(playerId));
    }

    public WildernessResetState wildernessResetState() {
        return wildernessResetState;
    }

    public Optional<RpgSkillOperation> rpgSkillOperation(UUID transactionId) {
        return Optional.ofNullable(rpgSkillOperations.get(transactionId));
    }

    public List<Map.Entry<UUID, RpgSkillOperation>> pendingRpgSkillOperations(UUID playerId) {
        return rpgSkillOperations(playerId).stream()
                .filter(entry -> entry.getValue().phase() == RpgSkillOperation.Phase.PENDING)
                .toList();
    }

    public List<Map.Entry<UUID, RpgSkillOperation>> rpgSkillOperations(UUID playerId) {
        return rpgSkillOperations.entrySet().stream()
                .filter(entry -> entry.getValue().playerId().equals(playerId))
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> Map.entry(entry.getKey(), entry.getValue()))
                .toList();
    }

    public Optional<RpgAdminOperation> rpgAdminOperation(UUID transactionId) {
        return Optional.ofNullable(rpgAdminOperations.get(transactionId));
    }

    public List<Map.Entry<UUID, RpgAdminOperation>> pendingRpgAdminOperations(UUID playerId) {
        if (playerId == null || ZERO_UUID.equals(playerId)) {
            return List.of();
        }
        return rpgAdminOperations.entrySet().stream()
                .filter(entry -> entry.getValue().playerId().equals(playerId))
                .filter(entry -> entry.getValue().phase() == RpgAdminOperation.Phase.PENDING)
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> Map.entry(entry.getKey(), entry.getValue()))
                .toList();
    }

    public boolean isWildernessOperationLocked() {
        return wildernessResetState.activeOperation().isPresent();
    }

    boolean isPortalProtectionRegion(Identifier regionId) {
        return portalDefinitions.keySet().stream().anyMatch(portalId ->
                PortalDefinition.originProtectionId(portalId).equals(regionId)
                        || PortalDefinition.destinationProtectionId(portalId).equals(regionId));
    }

    boolean overlapsPortalProtection(Identifier regionId, ProtectedRegion region) {
        if (region == null || isPortalProtectionRegion(regionId)) {
            return isPortalProtectionRegion(regionId);
        }
        for (int chunkX = region.minChunkX(); chunkX <= region.maxChunkX(); chunkX++) {
            for (int chunkZ = region.minChunkZ(); chunkZ <= region.maxChunkZ(); chunkZ++) {
                for (Identifier indexedId : protectedRegionsAt(new ClaimKey(region.dimension(), chunkX, chunkZ))) {
                    if (isPortalProtectionRegion(indexedId)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    boolean portalProtectionIntact(Identifier portalId, PortalDefinition definition) {
        Identifier originId = PortalDefinition.originProtectionId(portalId);
        Identifier destinationId = PortalDefinition.destinationProtectionId(portalId);
        ProtectedRegion origin = definition.protectedRegion(definition.origin());
        ProtectedRegion destination = definition.protectedRegion(definition.destination());
        if (!origin.equals(protectedRegions.get(originId)) || !destination.equals(protectedRegions.get(destinationId))) {
            return false;
        }
        Set<Identifier> owned = Set.of(originId, destinationId);
        for (ProtectedRegion region : List.of(origin, destination)) {
            for (int chunkX = region.minChunkX(); chunkX <= region.maxChunkX(); chunkX++) {
                for (int chunkZ = region.minChunkZ(); chunkZ <= region.maxChunkZ(); chunkZ++) {
                    if (!owned.containsAll(protectedRegionsAt(new ClaimKey(region.dimension(), chunkX, chunkZ)))) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    boolean canStoreProtectedRegion(Identifier regionId, ProtectedRegion region) {
        if (regionId == null || region == null || !region.isValid()) {
            return false;
        }
        if (!protectedRegions.containsKey(regionId) && protectedRegions.size() >= MAX_PROTECTED_REGIONS) {
            return false;
        }
        long indexed = protectedRegions.entrySet().stream()
                .filter(entry -> !entry.getKey().equals(regionId))
                .mapToLong(entry -> entry.getValue().areaChunks())
                .sum();
        return indexed + region.areaChunks() <= MAX_INDEXED_PROTECTED_CHUNKS;
    }

    private PortalState portalState() {
        return new PortalState(portalDefinitions, portalCooldowns, portalTravelReceipts, portalCombatTimestamps);
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

    List<Map.Entry<Identifier, ShopInstance>> shopInstances(int maximumEntries) {
        if (maximumEntries < 1) {
            throw new IllegalArgumentException("Shop query must be bounded");
        }
        return shopInstances.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .limit(maximumEntries)
                .map(entry -> Map.entry(entry.getKey(), entry.getValue()))
                .toList();
    }

    Map<UUID, EconomyTransactionReceipt> economyReceiptsView() {
        return Map.copyOf(economyReceipts);
    }

    List<Map.Entry<UUID, EconomyTransactionReceipt>> economyReceipts(int maximumEntries) {
        if (maximumEntries < 1) {
            throw new IllegalArgumentException("Receipt query must be bounded");
        }
        return economyReceipts.entrySet().stream()
                .sorted(Comparator.<Map.Entry<UUID, EconomyTransactionReceipt>>comparingLong(
                                entry -> entry.getValue().timestampEpochMillis())
                        .reversed().thenComparing(Map.Entry::getKey))
                .limit(maximumEntries)
                .map(entry -> Map.entry(entry.getKey(), entry.getValue()))
                .toList();
    }

    int economyReceiptCount() {
        return economyReceipts.size();
    }

    List<EconomyAlert> economyAlertsView() {
        return List.copyOf(economyAlerts);
    }

    int economyAlertCount() {
        return economyAlerts.size();
    }

    List<EconomyAlert> recentEconomyAlerts(int maximumEntries) {
        if (maximumEntries < 1) {
            throw new IllegalArgumentException("Alert query must be bounded");
        }
        int from = Math.max(0, economyAlerts.size() - maximumEntries);
        List<EconomyAlert> result = new ArrayList<>(economyAlerts.subList(from, economyAlerts.size()));
        result.sort(Comparator.comparingLong(EconomyAlert::timestampEpochMillis).reversed()
                .thenComparing(EconomyAlert::transactionId)
                .thenComparing(alert -> alert.type().getSerializedName()));
        return List.copyOf(result);
    }

    List<AuditEntry> auditEntriesView() {
        return List.copyOf(auditEntries);
    }

    int pendingRecoveryOperationCount() {
        int pending = wildernessResetState.activeOperation().isPresent() ? 1 : 0;
        pending += (int) rpgSkillOperations.values().stream()
                .filter(operation -> operation.phase() == RpgSkillOperation.Phase.PENDING)
                .count();
        pending += (int) rpgAdminOperations.values().stream()
                .filter(operation -> operation.phase() == RpgAdminOperation.Phase.PENDING)
                .count();
        return pending;
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

    public List<AuditEntry> recentAuditEntries(int maximumEntries) {
        if (maximumEntries < 1) {
            throw new IllegalArgumentException("Audit query must be bounded");
        }
        List<AuditEntry> entries = new ArrayList<>(Math.min(maximumEntries, auditEntries.size()));
        var iterator = auditEntries.descendingIterator();
        while (iterator.hasNext() && entries.size() < maximumEntries) {
            entries.add(iterator.next());
        }
        return List.copyOf(entries);
    }

    boolean hasAuditTransaction(UUID transactionId) {
        return auditTransaction(transactionId).isPresent();
    }

    public Optional<AuditEntry> auditTransaction(UUID transactionId) {
        return transactionId == null ? Optional.empty() : auditEntries.stream()
                .filter(entry -> transactionId.equals(entry.transactionId()))
                .findFirst();
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

        var iterator = auditEntries.descendingIterator();
        for (long index = 0; index < offset && iterator.hasNext(); index++) {
            iterator.next();
        }
        List<AuditEntry> entries = new ArrayList<>(Math.min(pageSize, totalEntries - (int) offset));
        while (iterator.hasNext() && entries.size() < pageSize) {
            entries.add(iterator.next());
        }
        return new AuditPage(page, totalPages, totalEntries, entries);
    }

    public AuditPage auditPage(AuditQuery query, int page, int pageSize) {
        if (query == null || page < 0 || pageSize < 1 || pageSize > MAX_AUDIT_PAGE_SIZE) {
            throw new IllegalArgumentException("Invalid audit page request");
        }

        long offset = (long) page * pageSize;
        List<AuditEntry> matches = auditEntries.stream()
                .filter(query::matches)
                .sorted(AUDIT_NEWEST_FIRST)
                .toList();
        List<AuditEntry> entries = offset >= matches.size()
                ? List.of()
                : matches.subList((int) offset, (int) Math.min(offset + pageSize, matches.size()));
        int totalPages = matches.isEmpty() ? 0 : (matches.size() + pageSize - 1) / pageSize;
        return new AuditPage(page, totalPages, matches.size(), entries);
    }

    AuditSelection selectAudit(AuditQuery query, int maximumEntries) {
        if (query == null || maximumEntries < 1) {
            throw new IllegalArgumentException("Invalid audit selection request");
        }
        List<AuditEntry> matches = auditEntries.stream()
                .filter(query::matches)
                .sorted(AUDIT_NEWEST_FIRST)
                .toList();
        return new AuditSelection(matches.size(), matches.subList(0, Math.min(maximumEntries, matches.size())));
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
        if (!rpgSkillEvidenceValid(
                snapshot.rpgSkillOperations, receipts, transactions.orElseThrow())) {
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
        claims.clear();
        claims.putAll(snapshot.claims);
        claimReceipts.clear();
        claimReceipts.putAll(snapshot.claimReceipts);
        protectedRegions.clear();
        protectedRegions.putAll(snapshot.protectedRegions);
        portalDefinitions.clear();
        portalDefinitions.putAll(snapshot.portalDefinitions);
        portalCooldowns.clear();
        snapshot.portalCooldowns.forEach((player, cooldowns) ->
                portalCooldowns.put(player, new HashMap<>(cooldowns)));
        portalTravelReceipts.clear();
        portalTravelReceipts.putAll(snapshot.portalTravelReceipts);
        portalCombatTimestamps.clear();
        portalCombatTimestamps.putAll(snapshot.portalCombatTimestamps);
        rpgSkillOperations.clear();
        rpgSkillOperations.putAll(snapshot.rpgSkillOperations);
        rebuildRecentTransactionIndex();
        rebuildReceiptExpiryIndex();
        rebuildClaimOwnerIndex();
        rebuildProtectedRegionIndex();
        rebuildPortalOriginIndex();
        rebuildPortalEvidenceIndexes();
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
        return hasReceiptCapacity(timestampEpochMillis, MAX_ECONOMY_TRANSACTIONS);
    }

    boolean canCommitClaimTransaction(UUID transactionId, long timestampEpochMillis) {
        if (claimReceipts.containsKey(transactionId) || !canCommitTransaction(transactionId, timestampEpochMillis)) {
            return false;
        }
        if (claimReceipts.size() < MAX_ECONOMY_TRANSACTIONS) {
            return true;
        }
        return claimReceipts.keySet().stream().anyMatch(id -> !hasTransaction(id, timestampEpochMillis));
    }

    boolean canCommitReversalTransaction(
            UUID transactionId, UUID originalTransactionId, long timestampEpochMillis) {
        if (economyReceipts.containsKey(transactionId)
                || !canCommitTransaction(transactionId, timestampEpochMillis)) {
            return false;
        }
        return hasReceiptCapacity(timestampEpochMillis, MAX_ECONOMY_TRANSACTIONS);
    }

    boolean hasEconomyTransaction(UUID transactionId, long timestampEpochMillis) {
        return hasTransaction(transactionId, timestampEpochMillis);
    }

    boolean canCommitEconomyTransaction(UUID transactionId, long timestampEpochMillis) {
        return canCommitReceiptTransaction(transactionId, timestampEpochMillis);
    }

    boolean canCommitRpgSkillPayment(UUID transactionId, long timestampEpochMillis) {
        if (rpgSkillOperations.containsKey(transactionId)
                || !canCommitReceiptTransaction(transactionId, timestampEpochMillis)) {
            return false;
        }
        long cutoff = timestampEpochMillis <= ECONOMY_TRANSACTION_RETENTION_MILLIS
                ? 0
                : timestampEpochMillis - ECONOMY_TRANSACTION_RETENTION_MILLIS;
        long retained = rpgSkillOperations.values().stream()
                .filter(operation -> operation.phase() == RpgSkillOperation.Phase.PENDING
                        || operation.timestampEpochMillis() >= cutoff)
                .count();
        return retained < MAX_RPG_SKILL_OPERATIONS;
    }

    RpgAdminOperationBeginResult beginRpgAdminOperation(UUID transactionId, RpgAdminOperation operation) {
        if (!writable || transactionId == null || ZERO_UUID.equals(transactionId) || operation == null
                || operation.phase() != RpgAdminOperation.Phase.PENDING
                || !RpgAdminOperation.CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, operation)
                        .result().isPresent()) {
            return new RpgAdminOperationBeginResult(RpgAdminOperationBeginStatus.INVALID, Optional.empty());
        }
        RpgAdminOperation existing = rpgAdminOperations.get(transactionId);
        if (existing != null) {
            return new RpgAdminOperationBeginResult(existing.equals(operation)
                    ? RpgAdminOperationBeginStatus.DUPLICATE
                    : RpgAdminOperationBeginStatus.CONFLICT, Optional.of(existing));
        }
        trimCompletedRpgAdminOperations(operation.timestampEpochMillis());
        if (rpgAdminOperations.size() >= MAX_RPG_ADMIN_OPERATIONS) {
            return new RpgAdminOperationBeginResult(RpgAdminOperationBeginStatus.FULL, Optional.empty());
        }
        rpgAdminOperations.put(transactionId, operation);
        setDirty();
        return new RpgAdminOperationBeginResult(RpgAdminOperationBeginStatus.SUCCESS, Optional.of(operation));
    }

    boolean completeRpgAdminOperation(
            UUID transactionId, RpgAdminOperation expected, AuditEntry auditEntry) {
        if (!writable || transactionId == null || ZERO_UUID.equals(transactionId) || expected == null
                || auditEntry == null || !transactionId.equals(auditEntry.transactionId())) {
            return false;
        }
        RpgAdminOperation current = rpgAdminOperations.get(transactionId);
        if (current == null || !current.equals(expected) || current.phase() != RpgAdminOperation.Phase.PENDING) {
            return false;
        }
        rpgAdminOperations.put(transactionId, current.completed());
        trimCompletedRpgAdminOperations(auditEntry.timestampEpochMillis());
        commitAudit(auditEntry);
        return true;
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

    void commitRpgSkillPayment(
            UUID playerId,
            long balance,
            UUID transactionId,
            long timestampEpochMillis,
            EconomyTransactionReceipt receipt,
            List<EconomyAlert> alerts,
            RpgSkillOperation operation,
            AuditEntry auditEntry) {
        validateEvidence(playerId, transactionId, timestampEpochMillis, receipt, alerts);
        if (!operation.playerId().equals(playerId)
                || operation.timestampEpochMillis() != timestampEpochMillis
                || receipt.kind() != operationReceiptKind(operation)
                || !canCommitRpgSkillPayment(transactionId, timestampEpochMillis)) {
            throw new IllegalStateException("Paid RPG operation cannot be committed");
        }
        prepareLedgerForCommit(timestampEpochMillis);
        trimCompletedRpgSkillOperations(timestampEpochMillis);
        economyBalances.put(playerId, balance);
        economyTransactions.put(transactionId, timestampEpochMillis);
        rpgSkillOperations.put(transactionId, operation);
        commitReceiptEvidence(transactionId, receipt, alerts);
        commitAudit(auditEntry);
    }

    void completeRpgSkillOperation(UUID transactionId, RpgSkillOperation expected, AuditEntry auditEntry) {
        RpgSkillOperation current = rpgSkillOperations.get(transactionId);
        EconomyTransactionReceipt receipt = economyReceipts.get(transactionId);
        if (current == null || !current.equals(expected)
                || current.phase() != RpgSkillOperation.Phase.PENDING
                || receipt == null || receipt.kind() != operationReceiptKind(current)
                || !receipt.actorId().equals(AdministrationService.SYSTEM_ACTOR)
                || !receipt.playerId().equals(current.playerId()) || receipt.amount() != current.cost()) {
            throw new IllegalStateException("Paid RPG operation cannot be completed");
        }
        rpgSkillOperations.put(transactionId, current.completed());
        EconomyTransactionReceipt retained = economyReceipts.get(transactionId);
        recordReceiptExpiry(transactionId, receiptExpiry(retained.timestampEpochMillis()));
        commitAudit(auditEntry);
    }

    void commitClaimPurchase(
            UUID playerId,
            long balance,
            ClaimKey claimKey,
            Claim claim,
            UUID transactionId,
            long timestampEpochMillis,
            EconomyTransactionReceipt receipt,
            List<EconomyAlert> alerts,
            AuditEntry auditEntry) {
        validateEvidence(playerId, transactionId, timestampEpochMillis, receipt, alerts);
        if (claims.containsKey(claimKey)
                || !WorldTopology.allowsClaims(claimKey.dimension())
                || economyReceipts.containsKey(transactionId)
                || !canCommitReceiptTransaction(transactionId, timestampEpochMillis)
                || receipt.kind() != EconomyTransactionReceipt.Kind.CLAIM_PURCHASE
                || !receipt.claim().equals(Optional.of(claimKey))
                || !claim.ownerId().equals(playerId)) {
            throw new IllegalStateException("Claim purchase cannot be committed");
        }
        prepareLedgerForCommit(timestampEpochMillis);
        economyBalances.put(playerId, balance);
        claims.put(claimKey, claim);
        claimCountsByOwner.merge(playerId, 1, Math::addExact);
        economyTransactions.put(transactionId, timestampEpochMillis);
        commitReceiptEvidence(transactionId, receipt, alerts);
        commitAudit(auditEntry);
    }

    void commitClaimMutation(
            ClaimKey claimKey,
            Claim claim,
            UUID transactionId,
            long timestampEpochMillis,
            ClaimMutationReceipt receipt,
            AuditEntry auditEntry) {
        if (!canCommitClaimTransaction(transactionId, timestampEpochMillis)
                || !receipt.claim().equals(claimKey)
                || receipt.timestampEpochMillis() != timestampEpochMillis
                || claims.get(claimKey) == null) {
            throw new IllegalStateException("Claim mutation cannot be committed");
        }
        prepareLedgerForCommit(timestampEpochMillis);
        claimReceipts.keySet().retainAll(economyTransactions.keySet());
        replaceClaim(claimKey, claim);
        economyTransactions.put(transactionId, timestampEpochMillis);
        claimReceipts.put(transactionId, receipt);
        commitAudit(auditEntry);
    }

    void commitProtectedRegionMutation(
            Identifier regionId,
            Optional<ProtectedRegion> region,
            AuditEntry auditEntry) {
        if (region.isPresent()) {
            protectedRegions.put(regionId, region.orElseThrow());
        } else {
            protectedRegions.remove(regionId);
        }
        rebuildProtectedRegionIndex();
        commitAudit(auditEntry);
    }

    void commitPortalMutation(
            Identifier portalId,
            Optional<PortalDefinition> definition,
            AuditEntry auditEntry) {
        Identifier originId = PortalDefinition.originProtectionId(portalId);
        Identifier destinationId = PortalDefinition.destinationProtectionId(portalId);
        if (definition.isPresent()) {
            PortalDefinition retained = definition.orElseThrow();
            portalDefinitions.put(portalId, retained);
            protectedRegions.put(originId, retained.protectedRegion(retained.origin()));
            protectedRegions.put(destinationId, retained.protectedRegion(retained.destination()));
        } else {
            portalDefinitions.remove(portalId);
            protectedRegions.remove(originId);
            protectedRegions.remove(destinationId);
        }
        rebuildPortalOriginIndex();
        rebuildProtectedRegionIndex();
        commitAudit(auditEntry);
    }

    void commitWildernessWarning(WildernessResetState.Warning warning, AuditEntry auditEntry) {
        wildernessResetState = wildernessResetState.withWarning(warning);
        commitAudit(auditEntry);
    }

    void commitWildernessOperation(WildernessResetState.Operation operation, AuditEntry auditEntry) {
        wildernessResetState = wildernessResetState.withOperation(operation);
        commitAudit(auditEntry);
    }

    void completeWildernessOperation(WildernessResetState.Evidence evidence, AuditEntry auditEntry) {
        wildernessResetState = wildernessResetState.complete(evidence);
        commitAudit(auditEntry);
    }

    void abortWildernessOperation(AuditEntry auditEntry) {
        wildernessResetState = wildernessResetState.clearActive();
        commitAudit(auditEntry);
    }

    boolean hasPortalTravelReceipt(UUID transactionId, long timestampEpochMillis) {
        if (portalTravelReceipts.containsKey(transactionId)) {
            return true;
        }
        trimPortalEvidence(timestampEpochMillis);
        return false;
    }

    Optional<PortalTravelReservation> reservePortalTravel(
            UUID playerId,
            Identifier portalId,
            long cooldownUntil,
            UUID transactionId,
            long timestampEpochMillis,
            PortalDefinition.Endpoint destination) {
        trimPortalEvidence(timestampEpochMillis);
        boolean addsCooldown = cooldownUntil > timestampEpochMillis;
        if (transactionId == null
                || portalTravelReceipts.containsKey(transactionId)
                || portalTravelReceipts.size() >= PortalState.MAX_RUNTIME_ENTRIES
                || (addsCooldown && activePortalCooldownCount >= PortalState.MAX_RUNTIME_ENTRIES)) {
            return Optional.empty();
        }
        PortalState.TravelReceipt receipt =
                new PortalState.TravelReceipt(playerId, portalId, timestampEpochMillis, destination);
        portalTravelReceipts.put(transactionId, receipt);
        if (addsCooldown) {
            Long previous = portalCooldowns.computeIfAbsent(playerId, ignored -> new HashMap<>())
                    .putIfAbsent(portalId, cooldownUntil);
            if (previous != null) {
                portalTravelReceipts.remove(transactionId);
                return Optional.empty();
            }
            activePortalCooldownCount++;
        }
        return Optional.of(new PortalTravelReservation(
                playerId, portalId, cooldownUntil, transactionId, receipt, addsCooldown));
    }

    void rollbackPortalTravel(PortalTravelReservation reservation) {
        portalTravelReceipts.remove(reservation.transactionId(), reservation.receipt());
        if (!reservation.cooldownAdded()) {
            return;
        }
        Map<Identifier, Long> cooldowns = portalCooldowns.get(reservation.playerId());
        if (cooldowns != null
                && cooldowns.remove(reservation.portalId(), reservation.cooldownUntilEpochMillis())) {
            activePortalCooldownCount--;
            if (cooldowns.isEmpty()) {
                portalCooldowns.remove(reservation.playerId());
            }
        }
    }

    void completePortalTravel(PortalTravelReservation reservation, AuditEntry auditEntry) {
        long receiptExpiry = portalReceiptExpiry(reservation.receipt().completedAtEpochMillis());
        if (receiptExpiry != NON_EXPIRING_RECEIPT) {
            portalReceiptExpiryQueue.add(
                    new PortalReceiptExpiry(reservation.transactionId(), receiptExpiry));
        }
        if (reservation.cooldownAdded()) {
            portalCooldownExpiryQueue.add(new PortalCooldownExpiry(
                    reservation.playerId(), reservation.portalId(), reservation.cooldownUntilEpochMillis()));
        }
        commitAudit(auditEntry);
    }

    void recordPortalCombat(UUID playerId, long timestampEpochMillis) {
        if (playerId == null || timestampEpochMillis < 0) {
            return;
        }
        if (!portalCombatTimestamps.containsKey(playerId)
                && portalCombatTimestamps.size() >= PortalState.MAX_COMBAT_ENTRIES) {
            UUID oldest = portalCombatTimestamps.entrySet().stream()
                    .min(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse(null);
            portalCombatTimestamps.remove(oldest);
        }
        portalCombatTimestamps.put(playerId, timestampEpochMillis);
        setDirty();
    }

    void commitClaimSale(
            UUID playerId,
            long balance,
            ClaimKey claimKey,
            UUID transactionId,
            long timestampEpochMillis,
            EconomyTransactionReceipt receipt,
            List<EconomyAlert> alerts,
            AuditEntry auditEntry) {
        validateEvidence(playerId, transactionId, timestampEpochMillis, receipt, alerts);
        Claim claim = claims.get(claimKey);
        if (claim == null || !claim.ownerId().equals(playerId)
                || economyReceipts.containsKey(transactionId)
                || !canCommitReceiptTransaction(transactionId, timestampEpochMillis)
                || receipt.kind() != EconomyTransactionReceipt.Kind.CLAIM_SALE
                || !receipt.claim().equals(Optional.of(claimKey))) {
            throw new IllegalStateException("Claim sale cannot be committed");
        }
        prepareLedgerForCommit(timestampEpochMillis);
        economyBalances.put(playerId, balance);
        replaceClaim(claimKey, null);
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
            while (auditEntries.size() > previousAuditSize) {
                auditEntries.removeLast();
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
                && first.claim().equals(second.claim())
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
        registerReceiptExpiry(transactionId, receipt);
        trimReceiptCapacity(receipt.timestampEpochMillis(), MAX_ECONOMY_TRANSACTIONS);
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
    }

    private void prepareLedgerForCommit(long timestampEpochMillis) {
        if (economyTransactions.size() >= MAX_ECONOMY_TRANSACTIONS) {
            trimExpiredEconomyTransactions(
                    economyTransactions, timestampEpochMillis, ECONOMY_TRANSACTION_RETENTION_MILLIS);
        }
    }

    private static boolean rpgSkillEvidenceValid(
            Map<UUID, RpgSkillOperation> operations,
            Map<UUID, EconomyTransactionReceipt> receipts,
            Map<UUID, Long> transactions) {
        for (Map.Entry<UUID, RpgSkillOperation> entry : operations.entrySet()) {
            RpgSkillOperation operation = entry.getValue();
            EconomyTransactionReceipt receipt = receipts.get(entry.getKey());
            Long timestamp = transactions.get(entry.getKey());
            if (receipt == null || receipt.kind() != operationReceiptKind(operation)
                    || !receipt.actorId().equals(AdministrationService.SYSTEM_ACTOR)
                    || !receipt.playerId().equals(operation.playerId()) || receipt.amount() != operation.cost()
                    || timestamp == null || timestamp != operation.timestampEpochMillis()
                    || receipt.timestampEpochMillis() != operation.timestampEpochMillis()) {
                return false;
            }
        }
        return receipts.entrySet().stream()
                .filter(entry -> entry.getValue().kind() == EconomyTransactionReceipt.Kind.RPG_SKILL_PAYMENT
                        || entry.getValue().kind() == EconomyTransactionReceipt.Kind.CAREER_PROMOTION_PAYMENT)
                .allMatch(entry -> operations.containsKey(entry.getKey()));
    }

    private static EconomyTransactionReceipt.Kind operationReceiptKind(RpgSkillOperation operation) {
        return switch (operation.kind()) {
            case SKILL_RESET -> EconomyTransactionReceipt.Kind.RPG_SKILL_PAYMENT;
            case CAREER_PROMOTION -> EconomyTransactionReceipt.Kind.CAREER_PROMOTION_PAYMENT;
        };
    }

    private void trimCompletedRpgSkillOperations(long timestampEpochMillis) {
        long cutoff = timestampEpochMillis <= ECONOMY_TRANSACTION_RETENTION_MILLIS
                ? 0
                : timestampEpochMillis - ECONOMY_TRANSACTION_RETENTION_MILLIS;
        rpgSkillOperations.entrySet().stream()
                .filter(entry -> entry.getValue().phase() == RpgSkillOperation.Phase.COMPLETED)
                .filter(entry -> entry.getValue().timestampEpochMillis() < cutoff)
                .map(Map.Entry::getKey)
                .toList()
                .forEach(this::evictReceipt);
    }

    private void trimCompletedRpgAdminOperations(long timestampEpochMillis) {
        long cutoff = timestampEpochMillis <= ECONOMY_TRANSACTION_RETENTION_MILLIS
                ? 0
                : timestampEpochMillis - ECONOMY_TRANSACTION_RETENTION_MILLIS;
        rpgAdminOperations.entrySet().stream()
                .filter(entry -> entry.getValue().phase() == RpgAdminOperation.Phase.COMPLETED)
                .filter(entry -> entry.getValue().timestampEpochMillis() < cutoff)
                .map(Map.Entry::getKey)
                .toList()
                .forEach(rpgAdminOperations::remove);
        if (rpgAdminOperations.size() <= MAX_RPG_ADMIN_OPERATIONS) {
            return;
        }
        rpgAdminOperations.entrySet().stream()
                .filter(entry -> entry.getValue().phase() == RpgAdminOperation.Phase.COMPLETED)
                .sorted(Comparator.comparingLong((Map.Entry<UUID, RpgAdminOperation> entry) ->
                        entry.getValue().timestampEpochMillis()).thenComparing(Map.Entry::getKey))
                .limit(rpgAdminOperations.size() - MAX_RPG_ADMIN_OPERATIONS)
                .map(Map.Entry::getKey)
                .toList()
                .forEach(rpgAdminOperations::remove);
    }

    boolean hasReceiptCapacity(long timestampEpochMillis, int maximumEntries) {
        if (economyReceipts.size() < maximumEntries) {
            return true;
        }
        ReceiptExpiry expiry = nextCurrentReceiptExpiry();
        return expiry != null && expiry.timestampEpochMillis() <= timestampEpochMillis;
    }

    void trimReceiptCapacity(long timestampEpochMillis, int maximumEntries) {
        while (economyReceipts.size() > maximumEntries) {
            ReceiptExpiry expiry = nextCurrentReceiptExpiry();
            if (expiry == null || expiry.timestampEpochMillis() > timestampEpochMillis) {
                throw new IllegalStateException("Economy receipt capacity cannot be maintained");
            }
            receiptExpiryQueue.remove();
            if (evictReceipt(expiry.transactionId())) {
                setDirty();
            }
        }
    }

    private ReceiptExpiry nextCurrentReceiptExpiry() {
        while (!receiptExpiryQueue.isEmpty()) {
            ReceiptExpiry expiry = receiptExpiryQueue.peek();
            if (receiptEvictionTimes.getOrDefault(expiry.transactionId(), Long.MIN_VALUE)
                    == expiry.timestampEpochMillis()) {
                return expiry;
            }
            receiptExpiryQueue.remove();
        }
        return null;
    }

    private void registerReceiptExpiry(UUID transactionId, EconomyTransactionReceipt receipt) {
        RpgSkillOperation operation = rpgSkillOperations.get(transactionId);
        long expiry = operation != null && operation.phase() == RpgSkillOperation.Phase.PENDING
                ? NON_EXPIRING_RECEIPT
                : receiptExpiry(receipt.timestampEpochMillis());
        recordReceiptExpiry(transactionId, expiry);
        if (receipt.kind() != EconomyTransactionReceipt.Kind.REVERSAL) {
            return;
        }
        UUID originalId = receipt.originalTransactionId().orElseThrow();
        long pairExpiry = combineReceiptExpiry(
                receiptEvictionTimes.getOrDefault(originalId, expiry), expiry);
        recordReceiptExpiry(originalId, pairExpiry);
        EconomyTransactionReceipt original = economyReceipts.get(originalId);
        if (original != null && original.reversedBy().equals(Optional.of(transactionId))) {
            recordReceiptExpiry(transactionId, pairExpiry);
        }
    }

    private void recordReceiptExpiry(UUID transactionId, long timestampEpochMillis) {
        EconomyTransactionReceipt receipt = economyReceipts.get(transactionId);
        if (receipt == null) {
            return;
        }
        Long previous = receiptEvictionTimes.put(transactionId, timestampEpochMillis);
        if (previous != null && previous == timestampEpochMillis) {
            return;
        }
        if (timestampEpochMillis == NON_EXPIRING_RECEIPT) {
            return;
        }
        receiptExpiryQueue.add(new ReceiptExpiry(
                transactionId, timestampEpochMillis,
                receipt.kind() == EconomyTransactionReceipt.Kind.REVERSAL));
    }

    private boolean evictReceipt(UUID transactionId) {
        EconomyTransactionReceipt receipt = economyReceipts.get(transactionId);
        if (receipt == null) {
            receiptEvictionTimes.remove(transactionId);
            return false;
        }
        if (receipt.kind() == EconomyTransactionReceipt.Kind.REVERSAL) {
            UUID originalId = receipt.originalTransactionId().orElseThrow();
            EconomyTransactionReceipt original = economyReceipts.get(originalId);
            if (original != null && original.reversedBy().equals(Optional.of(transactionId))) {
                removeReceipt(originalId);
            }
        } else {
            receipt.reversedBy().ifPresent(this::removeReceipt);
        }
        removeReceipt(transactionId);
        return true;
    }

    private void removeReceipt(UUID transactionId) {
        RpgSkillOperation operation = rpgSkillOperations.get(transactionId);
        if (operation != null && operation.phase() == RpgSkillOperation.Phase.PENDING) {
            throw new IllegalStateException("Pending paid RPG operation receipt cannot be evicted");
        }
        economyReceipts.remove(transactionId);
        receiptEvictionTimes.remove(transactionId);
        rpgSkillOperations.remove(transactionId);
    }

    private void rebuildReceiptExpiryIndex() {
        receiptEvictionTimes.clear();
        receiptExpiryQueue.clear();
        economyReceipts.forEach((transactionId, receipt) -> {
            RpgSkillOperation operation = rpgSkillOperations.get(transactionId);
            long expiry = operation != null && operation.phase() == RpgSkillOperation.Phase.PENDING
                    ? NON_EXPIRING_RECEIPT
                    : receiptExpiry(receipt.timestampEpochMillis());
            receiptEvictionTimes.put(transactionId, expiry);
        });
        economyReceipts.forEach((transactionId, receipt) -> {
            if (receipt.kind() == EconomyTransactionReceipt.Kind.REVERSAL) {
                UUID originalId = receipt.originalTransactionId().orElseThrow();
                receiptEvictionTimes.computeIfPresent(originalId, (ignored, originalExpiry) ->
                        combineReceiptExpiry(originalExpiry, receiptEvictionTimes.get(transactionId)));
            }
        });
        economyReceipts.forEach((transactionId, receipt) -> receipt.reversedBy().ifPresent(reversalId -> {
            long pairExpiry = combineReceiptExpiry(
                    receiptEvictionTimes.get(transactionId),
                    receiptEvictionTimes.getOrDefault(reversalId, Long.MIN_VALUE));
            receiptEvictionTimes.put(transactionId, pairExpiry);
            receiptEvictionTimes.computeIfPresent(reversalId, (ignored, reversalExpiry) -> pairExpiry);
        }));
        receiptEvictionTimes.forEach((transactionId, expiry) -> {
            EconomyTransactionReceipt receipt = economyReceipts.get(transactionId);
            if (expiry != NON_EXPIRING_RECEIPT) {
                receiptExpiryQueue.add(new ReceiptExpiry(
                        transactionId, expiry, receipt.kind() == EconomyTransactionReceipt.Kind.REVERSAL));
            }
        });
    }

    private static long receiptExpiry(long timestampEpochMillis) {
        try {
            return Math.addExact(timestampEpochMillis, ECONOMY_TRANSACTION_RETENTION_MILLIS + 1);
        } catch (ArithmeticException exception) {
            return NON_EXPIRING_RECEIPT;
        }
    }

    private static long combineReceiptExpiry(long first, long second) {
        return first == NON_EXPIRING_RECEIPT || second == NON_EXPIRING_RECEIPT
                ? NON_EXPIRING_RECEIPT
                : Math.max(first, second);
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

    private void replaceClaim(ClaimKey key, Claim replacement) {
        Claim previous = claims.get(key);
        if (previous != null && (replacement == null || !previous.ownerId().equals(replacement.ownerId()))) {
            claimCountsByOwner.computeIfPresent(previous.ownerId(), (ignored, count) -> count == 1 ? null : count - 1);
        }
        if (replacement == null) {
            claims.remove(key);
            return;
        }
        claims.put(key, replacement);
        if (previous == null || !previous.ownerId().equals(replacement.ownerId())) {
            claimCountsByOwner.merge(replacement.ownerId(), 1, Math::addExact);
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

    static Codec<Map<ClaimKey, Claim>> boundedClaimsCodec(int maximumEntries) {
        return ClaimEntry.CODEC.listOf(0, maximumEntries)
                .flatXmap(PlatformSavedData::claimsFromEntries, PlatformSavedData::claimEntries);
    }

    static Codec<Map<UUID, ClaimMutationReceipt>> boundedClaimReceiptsCodec(int maximumEntries) {
        return ClaimReceiptEntry.CODEC.listOf(0, maximumEntries)
                .flatXmap(PlatformSavedData::claimReceiptsFromEntries, PlatformSavedData::claimReceiptEntries);
    }

    static Codec<Map<Identifier, ProtectedRegion>> boundedProtectedRegionsCodec(
            int maximumRegions,
            int maximumIndexedChunks) {
        return ProtectedRegionEntry.CODEC.listOf(0, maximumRegions)
                .flatXmap(
                        entries -> protectedRegionsFromEntries(entries, maximumIndexedChunks),
                        PlatformSavedData::protectedRegionEntries);
    }

    private static DataResult<Map<Identifier, ProtectedRegion>> protectedRegionsFromEntries(
            List<ProtectedRegionEntry> entries,
            int maximumIndexedChunks) {
        Map<Identifier, ProtectedRegion> regions = new LinkedHashMap<>();
        long indexedChunks = 0;
        for (ProtectedRegionEntry entry : entries) {
            if (regions.putIfAbsent(entry.id(), entry.region()) != null) {
                return DataResult.error(() -> "Duplicate protected region ID " + entry.id());
            }
            indexedChunks += entry.region().areaChunks();
            if (indexedChunks > maximumIndexedChunks) {
                return DataResult.error(() -> "Protected region index exceeds " + maximumIndexedChunks + " chunks");
            }
        }
        return DataResult.success(Map.copyOf(regions));
    }

    private static DataResult<List<ProtectedRegionEntry>> protectedRegionEntries(
            Map<Identifier, ProtectedRegion> regions) {
        return DataResult.success(regions.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new ProtectedRegionEntry(entry.getKey(), entry.getValue()))
                .toList());
    }

    private record ProtectedRegionEntry(Identifier id, ProtectedRegion region) {
        private static final Codec<ProtectedRegionEntry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Identifier.CODEC.fieldOf("id").forGetter(ProtectedRegionEntry::id),
                ProtectedRegion.CODEC.fieldOf("region").forGetter(ProtectedRegionEntry::region)
        ).apply(instance, ProtectedRegionEntry::new));
    }

    private static DataResult<Map<UUID, RpgSkillOperation>> rpgSkillOperationsFromEntries(
            List<RpgSkillOperationEntry> entries) {
        Map<UUID, RpgSkillOperation> operations = new LinkedHashMap<>();
        for (RpgSkillOperationEntry entry : entries) {
            if (ZERO_UUID.equals(entry.id()) || operations.putIfAbsent(entry.id(), entry.operation()) != null) {
                return DataResult.error(() -> "Duplicate or zero RPG skill operation ID " + entry.id());
            }
        }
        return DataResult.success(Map.copyOf(operations));
    }

    private static DataResult<List<RpgSkillOperationEntry>> rpgSkillOperationEntries(
            Map<UUID, RpgSkillOperation> operations) {
        return DataResult.success(operations.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new RpgSkillOperationEntry(entry.getKey(), entry.getValue()))
                .toList());
    }

    private record RpgSkillOperationEntry(UUID id, RpgSkillOperation operation) {
        private static final Codec<RpgSkillOperationEntry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                UUIDUtil.STRING_CODEC.fieldOf("id").forGetter(RpgSkillOperationEntry::id),
                RpgSkillOperation.CODEC.fieldOf("operation").forGetter(RpgSkillOperationEntry::operation)
        ).apply(instance, RpgSkillOperationEntry::new));
    }

    private static DataResult<Map<UUID, RpgAdminOperation>> rpgAdminOperationsFromEntries(
            List<RpgAdminOperationEntry> entries) {
        Map<UUID, RpgAdminOperation> operations = new LinkedHashMap<>();
        for (RpgAdminOperationEntry entry : entries) {
            if (ZERO_UUID.equals(entry.id()) || operations.putIfAbsent(entry.id(), entry.operation()) != null) {
                return DataResult.error(() -> "Duplicate or zero RPG admin operation ID " + entry.id());
            }
        }
        return DataResult.success(Map.copyOf(operations));
    }

    private static DataResult<List<RpgAdminOperationEntry>> rpgAdminOperationEntries(
            Map<UUID, RpgAdminOperation> operations) {
        return DataResult.success(operations.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new RpgAdminOperationEntry(entry.getKey(), entry.getValue()))
                .toList());
    }

    private record RpgAdminOperationEntry(UUID id, RpgAdminOperation operation) {
        private static final Codec<RpgAdminOperationEntry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                UUIDUtil.STRING_CODEC.fieldOf("id").forGetter(RpgAdminOperationEntry::id),
                RpgAdminOperation.CODEC.fieldOf("operation").forGetter(RpgAdminOperationEntry::operation)
        ).apply(instance, RpgAdminOperationEntry::new));
    }

    private static DataResult<Map<UUID, ClaimMutationReceipt>> claimReceiptsFromEntries(
            List<ClaimReceiptEntry> entries) {
        Map<UUID, ClaimMutationReceipt> receipts = new LinkedHashMap<>();
        for (ClaimReceiptEntry entry : entries) {
            if (ZERO_UUID.equals(entry.id()) || receipts.putIfAbsent(entry.id(), entry.receipt()) != null) {
                return DataResult.error(() -> "Duplicate or zero claim receipt ID " + entry.id());
            }
        }
        return DataResult.success(Map.copyOf(receipts));
    }

    private static DataResult<List<ClaimReceiptEntry>> claimReceiptEntries(
            Map<UUID, ClaimMutationReceipt> receipts) {
        return DataResult.success(receipts.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new ClaimReceiptEntry(entry.getKey(), entry.getValue()))
                .toList());
    }

    private record ClaimReceiptEntry(UUID id, ClaimMutationReceipt receipt) {
        private static final Codec<ClaimReceiptEntry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                UUIDUtil.STRING_CODEC.fieldOf("id").forGetter(ClaimReceiptEntry::id),
                ClaimMutationReceipt.CODEC.fieldOf("receipt").forGetter(ClaimReceiptEntry::receipt)
        ).apply(instance, ClaimReceiptEntry::new));
    }

    private static DataResult<Map<ClaimKey, Claim>> claimsFromEntries(List<ClaimEntry> entries) {
        Map<ClaimKey, Claim> claims = new LinkedHashMap<>();
        for (ClaimEntry entry : entries) {
            if (!WorldTopology.allowsClaims(entry.key().dimension())) {
                return DataResult.error(() -> "Claim is outside the Hub " + entry.key().auditTarget());
            }
            if (claims.putIfAbsent(entry.key(), entry.claim()) != null) {
                return DataResult.error(() -> "Duplicate claim key " + entry.key().auditTarget());
            }
        }
        return DataResult.success(Map.copyOf(claims));
    }

    private static DataResult<List<ClaimEntry>> claimEntries(Map<ClaimKey, Claim> claims) {
        return DataResult.success(claims.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(ClaimKey::auditTarget)))
                .map(entry -> new ClaimEntry(entry.getKey(), entry.getValue()))
                .toList());
    }

    private record ClaimEntry(ClaimKey key, Claim claim) {
        private static final Codec<ClaimEntry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                ClaimKey.CODEC.fieldOf("key").forGetter(ClaimEntry::key),
                Claim.CODEC.fieldOf("claim").forGetter(ClaimEntry::claim)
        ).apply(instance, ClaimEntry::new));
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
                        || (receipt.invalidatedByRestore().isEmpty()
                                && !original.reversedBy().equals(Optional.of(entry.getKey())))
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

    private void rebuildClaimOwnerIndex() {
        claimCountsByOwner.clear();
        claims.values().forEach(claim -> claimCountsByOwner.merge(claim.ownerId(), 1, Math::addExact));
    }

    private void rebuildProtectedRegionIndex() {
        protectedRegionIndex.clear();
        protectedRegions.forEach((regionId, region) -> {
            int width = region.maxChunkX() - region.minChunkX() + 1;
            int height = region.maxChunkZ() - region.minChunkZ() + 1;
            for (int offsetX = 0; offsetX < width; offsetX++) {
                for (int offsetZ = 0; offsetZ < height; offsetZ++) {
                    ClaimKey key = new ClaimKey(
                            region.dimension(), region.minChunkX() + offsetX, region.minChunkZ() + offsetZ);
                    protectedRegionIndex.computeIfAbsent(key, ignored -> new HashSet<>()).add(regionId);
                }
            }
        });
    }

    private void rebuildPortalOriginIndex() {
        portalOriginIndex.clear();
        portalDefinitions.forEach((portalId, definition) -> portalOriginIndex.put(definition.origin(), portalId));
    }

    private void rebuildPortalEvidenceIndexes() {
        portalReceiptExpiryQueue.clear();
        portalTravelReceipts.forEach((transactionId, receipt) -> {
            long expiry = portalReceiptExpiry(receipt.completedAtEpochMillis());
            if (expiry != NON_EXPIRING_RECEIPT) {
                portalReceiptExpiryQueue.add(new PortalReceiptExpiry(transactionId, expiry));
            }
        });
        portalCooldownExpiryQueue.clear();
        activePortalCooldownCount = 0;
        portalCooldowns.forEach((playerId, cooldowns) -> cooldowns.forEach((portalId, deadline) -> {
            activePortalCooldownCount++;
            portalCooldownExpiryQueue.add(new PortalCooldownExpiry(playerId, portalId, deadline));
        }));
    }

    private void trimPortalEvidence(long timestampEpochMillis) {
        boolean changed = false;
        while (!portalReceiptExpiryQueue.isEmpty()
                && portalReceiptExpiryQueue.peek().expiryEpochMillis() <= timestampEpochMillis) {
            PortalReceiptExpiry expiry = portalReceiptExpiryQueue.remove();
            PortalState.TravelReceipt receipt = portalTravelReceipts.get(expiry.transactionId());
            if (receipt != null
                    && portalReceiptExpiry(receipt.completedAtEpochMillis()) == expiry.expiryEpochMillis()) {
                portalTravelReceipts.remove(expiry.transactionId());
                changed = true;
            }
        }
        while (!portalCooldownExpiryQueue.isEmpty()
                && portalCooldownExpiryQueue.peek().deadlineEpochMillis() <= timestampEpochMillis) {
            PortalCooldownExpiry expiry = portalCooldownExpiryQueue.remove();
            Map<Identifier, Long> cooldowns = portalCooldowns.get(expiry.playerId());
            if (cooldowns == null) {
                continue;
            }
            Long deadline = cooldowns.get(expiry.portalId());
            if (deadline != null && deadline == expiry.deadlineEpochMillis()) {
                cooldowns.remove(expiry.portalId());
                activePortalCooldownCount--;
                if (cooldowns.isEmpty()) {
                    portalCooldowns.remove(expiry.playerId());
                }
                changed = true;
            }
        }
        if (changed) {
            setDirty();
        }
    }

    private static long portalReceiptExpiry(long timestampEpochMillis) {
        return receiptExpiry(timestampEpochMillis);
    }

    boolean commitPlayerLogin(UUID playerId, long timestampEpochMillis) {
        return commitPlayerLogin(playerId, null, timestampEpochMillis);
    }

    boolean commitPlayerLogin(UUID playerId, String displayName, long timestampEpochMillis) {
        PlayerRecord previous = playerRecords.get(playerId);
        PlayerRecord updated = previous == null
                ? new PlayerRecord(timestampEpochMillis, timestampEpochMillis, Optional.ofNullable(displayName))
                : previous.observe(timestampEpochMillis, displayName);
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
        if (lastDeniedAuditByActor.size() > MAX_DENIED_AUDIT_ACTORS) {
            var iterator = lastDeniedAuditByActor.keySet().iterator();
            iterator.next();
            iterator.remove();
        }
        commitAudit(auditEntry);
        return true;
    }

    void commitAudit(AuditEntry auditEntry) {
        auditEntries.addLast(auditEntry);
        long cutoff = auditEntry.timestampEpochMillis() - AUDIT_RETENTION.toMillis();
        while (!auditEntries.isEmpty() && auditEntries.getFirst().timestampEpochMillis() < cutoff) {
            auditEntries.removeFirst();
        }
        while (auditEntries.size() > MAX_AUDIT_ENTRIES) {
            auditEntries.removeFirst();
        }
        setDirty();
    }

    public record AuditPage(int page, int totalPages, int totalEntries, List<AuditEntry> entries) {
        public AuditPage {
            entries = List.copyOf(entries);
        }
    }

    record AuditSelection(int totalEntries, List<AuditEntry> entries) {
        AuditSelection {
            entries = List.copyOf(entries);
        }
    }

    public enum RpgAdminOperationBeginStatus {
        SUCCESS,
        DUPLICATE,
        CONFLICT,
        FULL,
        INVALID
    }

    public record RpgAdminOperationBeginResult(
            RpgAdminOperationBeginStatus status, Optional<RpgAdminOperation> operation) {
        public RpgAdminOperationBeginResult {
            operation = operation == null ? Optional.empty() : operation;
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

    private record ReceiptExpiry(UUID transactionId, long timestampEpochMillis, boolean reversal) {
    }

    record PortalTravelReservation(
            UUID playerId,
            Identifier portalId,
            long cooldownUntilEpochMillis,
            UUID transactionId,
            PortalState.TravelReceipt receipt,
            boolean cooldownAdded) {
    }

    private record PortalReceiptExpiry(UUID transactionId, long expiryEpochMillis) {
    }

    private record PortalCooldownExpiry(UUID playerId, Identifier portalId, long deadlineEpochMillis) {
    }
}
