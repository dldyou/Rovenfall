package org.dldyou.rovenfall.administration;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.UnaryOperator;
import net.minecraft.resources.Identifier;
import org.dldyou.rovenfall.claims.Claim;
import org.dldyou.rovenfall.claims.ClaimKey;
import org.dldyou.rovenfall.claims.ClaimMutationReceipt;
import org.dldyou.rovenfall.economy.ShopInstance;
import org.dldyou.rovenfall.world.ProtectedRegion;

final class PlatformDataMigrations {
    private static final Map<Integer, UnaryOperator<PersistedState>> MIGRATIONS = Map.ofEntries(
            Map.entry(0, state -> state.atVersion(1)),
            Map.entry(1, state -> state.atVersion(2)),
            Map.entry(2, state -> state.atVersion(3)),
            Map.entry(3, state -> state.atVersion(4)),
            Map.entry(4, state -> state.atVersion(5)),
            Map.entry(5, state -> state.atVersion(6)),
            Map.entry(6, PlatformDataMigrations::migrateClaimsToSeven),
            Map.entry(7, state -> state.atVersion(8)),
            Map.entry(8, state -> state.atVersion(9)),
            Map.entry(9, state -> state.atVersion(10)),
            Map.entry(10, state -> state.atVersion(11)),
            Map.entry(11, state -> state.atVersion(12))
    );

    private PlatformDataMigrations() {
    }

    private static PersistedState migrateClaimsToSeven(PersistedState state) {
        Map<ClaimKey, EconomyTransactionReceipt> latestPurchases = new java.util.HashMap<>();
        state.economyReceipts().values().stream()
                .filter(receipt -> receipt.kind() == EconomyTransactionReceipt.Kind.CLAIM_PURCHASE)
                .filter(receipt -> receipt.claim().isPresent())
                .filter(receipt -> receipt.invalidatedByRestore().isEmpty())
                .forEach(receipt -> latestPurchases.merge(
                        receipt.claim().orElseThrow(), receipt,
                        (first, second) -> first.timestampEpochMillis() >= second.timestampEpochMillis()
                                ? first
                                : second));
        Map<ClaimKey, Claim> migratedClaims = new java.util.HashMap<>();
        state.claims().forEach((key, claim) -> {
            long purchasePrice = claim.purchasePrice();
            if (purchasePrice < 1) {
                EconomyTransactionReceipt receipt = latestPurchases.get(key);
                purchasePrice = receipt != null && receipt.playerId().equals(claim.ownerId())
                        ? receipt.amount()
                        : 0L;
            }
            migratedClaims.put(key, new Claim(
                    claim.ownerId(), purchasePrice, claim.trustedRoles(), claim.settings(), claim.pendingTransferTo()));
        });
        return new PersistedState(
                7, state.adminRoles(), state.auditEntries(), state.playerRecords(), state.economyBalances(),
                state.economyTransactions(), state.shopInstances(), state.economyReceipts(), state.economyAlerts(),
                migratedClaims, state.claimReceipts(), state.protectedRegions(), state.portalState(),
                state.wildernessResetState(), state.rpgSkillOperations(), state.rpgAdminOperations());
    }

    static MigrationResult migrate(
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
            int targetVersion) {
        PersistedState original = new PersistedState(
                schemaVersion, adminRoles, auditEntries, playerRecords, economyBalances, economyTransactions,
                shopInstances, economyReceipts, economyAlerts, claims, claimReceipts, protectedRegions, portalState,
                wildernessResetState, rpgSkillOperations, rpgAdminOperations);
        if (schemaVersion < 0 || schemaVersion > targetVersion) {
            return MigrationResult.readOnly(original);
        }

        PersistedState candidate = original;
        while (candidate.schemaVersion() < targetVersion) {
            UnaryOperator<PersistedState> migration = MIGRATIONS.get(candidate.schemaVersion());
            if (migration == null) {
                return MigrationResult.readOnly(original);
            }

            int expectedVersion = candidate.schemaVersion() + 1;
            candidate = migration.apply(candidate);
            if (candidate.schemaVersion() != expectedVersion) {
                return MigrationResult.readOnly(original);
            }
        }
        return new MigrationResult(candidate, true);
    }

    record PersistedState(
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
        PersistedState {
            adminRoles = Map.copyOf(adminRoles);
            auditEntries = List.copyOf(auditEntries);
            playerRecords = Map.copyOf(playerRecords);
            economyBalances = Map.copyOf(economyBalances);
            economyTransactions = Map.copyOf(economyTransactions);
            shopInstances = Map.copyOf(shopInstances);
            economyReceipts = Map.copyOf(economyReceipts);
            economyAlerts = List.copyOf(economyAlerts);
            claims = Map.copyOf(claims);
            claimReceipts = Map.copyOf(claimReceipts);
            protectedRegions = Map.copyOf(protectedRegions);
            portalState = portalState == null ? PortalState.EMPTY : portalState;
            wildernessResetState = wildernessResetState == null ? WildernessResetState.EMPTY : wildernessResetState;
            rpgSkillOperations = Map.copyOf(rpgSkillOperations);
            rpgAdminOperations = Map.copyOf(rpgAdminOperations);
        }

        PersistedState atVersion(int version) {
            return new PersistedState(
                    version, adminRoles, auditEntries, playerRecords, economyBalances, economyTransactions,
                    shopInstances, economyReceipts, economyAlerts, claims, claimReceipts, protectedRegions, portalState,
                    wildernessResetState, rpgSkillOperations, rpgAdminOperations);
        }
    }

    record MigrationResult(PersistedState state, boolean writable) {
        static MigrationResult readOnly(PersistedState state) {
            return new MigrationResult(state, false);
        }
    }
}
