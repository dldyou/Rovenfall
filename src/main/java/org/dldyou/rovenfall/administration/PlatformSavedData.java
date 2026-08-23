package org.dldyou.rovenfall.administration;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
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

public final class PlatformSavedData extends SavedData {
    public static final int CURRENT_SCHEMA_VERSION = 1;
    public static final int MAX_AUDIT_PAGE_SIZE = 50;
    private static final Duration AUDIT_RETENTION = Duration.ofDays(30);
    private static final Codec<Map<UUID, AdminRole>> ADMIN_ROLES_CODEC = Codec.unboundedMap(UUIDUtil.STRING_CODEC, AdminRole.CODEC);

    public static final Codec<PlatformSavedData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.optionalFieldOf("schema_version", 0).forGetter(data -> data.schemaVersion),
            ADMIN_ROLES_CODEC.optionalFieldOf("admin_roles", Map.of()).forGetter(data -> data.adminRoles),
            AuditEntry.CODEC.listOf().optionalFieldOf("audit_entries", List.of()).forGetter(data -> data.auditEntries)
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
    private final Map<UUID, Long> lastDeniedAuditByActor = new HashMap<>();

    public PlatformSavedData() {
        this(CURRENT_SCHEMA_VERSION, Map.of(), List.of(), true);
    }

    private PlatformSavedData(int schemaVersion, Map<UUID, AdminRole> adminRoles, List<AuditEntry> auditEntries, boolean writable) {
        this.schemaVersion = schemaVersion;
        this.writable = writable;
        this.adminRoles = new HashMap<>(adminRoles);
        this.auditEntries = new ArrayList<>(auditEntries);
    }

    private static PlatformSavedData decode(int schemaVersion, Map<UUID, AdminRole> adminRoles, List<AuditEntry> auditEntries) {
        var migration = PlatformDataMigrations.migrate(
                schemaVersion,
                adminRoles,
                auditEntries,
                CURRENT_SCHEMA_VERSION
        );
        var state = migration.state();
        return new PlatformSavedData(
                state.schemaVersion(),
                state.adminRoles(),
                state.auditEntries(),
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
        appendAudit(auditEntry);
    }

    boolean appendDeniedAudit(AuditEntry auditEntry, long minimumIntervalMillis) {
        Long previous = lastDeniedAuditByActor.get(auditEntry.actorId());
        if (previous != null && auditEntry.timestampEpochMillis() - previous < minimumIntervalMillis) {
            return false;
        }
        lastDeniedAuditByActor.put(auditEntry.actorId(), auditEntry.timestampEpochMillis());
        appendAudit(auditEntry);
        return true;
    }

    private void appendAudit(AuditEntry auditEntry) {
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
