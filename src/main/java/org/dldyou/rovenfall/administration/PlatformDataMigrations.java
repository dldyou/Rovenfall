package org.dldyou.rovenfall.administration;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.UnaryOperator;

final class PlatformDataMigrations {
    private static final Map<Integer, UnaryOperator<PersistedState>> MIGRATIONS = Map.of(
            0, state -> state.atVersion(1),
            1, state -> state.atVersion(2)
    );

    private PlatformDataMigrations() {
    }

    static MigrationResult migrate(
            int schemaVersion,
            Map<UUID, AdminRole> adminRoles,
            List<AuditEntry> auditEntries,
            Map<UUID, PlayerRecord> playerRecords,
            int targetVersion) {
        PersistedState original = new PersistedState(schemaVersion, adminRoles, auditEntries, playerRecords);
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
            Map<UUID, PlayerRecord> playerRecords) {
        PersistedState {
            adminRoles = Map.copyOf(adminRoles);
            auditEntries = List.copyOf(auditEntries);
            playerRecords = Map.copyOf(playerRecords);
        }

        PersistedState atVersion(int version) {
            return new PersistedState(version, adminRoles, auditEntries, playerRecords);
        }
    }

    record MigrationResult(PersistedState state, boolean writable) {
        static MigrationResult readOnly(PersistedState state) {
            return new MigrationResult(state, false);
        }
    }
}
