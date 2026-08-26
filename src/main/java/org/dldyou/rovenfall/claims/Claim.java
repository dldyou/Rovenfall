package org.dldyou.rovenfall.claims;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;

public record Claim(
        UUID ownerId,
        long purchasePrice,
        Map<UUID, ClaimRole> trustedRoles,
        ClaimSettings settings,
        Optional<UUID> pendingTransferTo) {
    public static final int MAX_CLAIMS = 100_000;
    public static final int MAX_TRUSTED_PLAYERS = 128;
    private static final Codec<Map<UUID, ClaimRole>> TRUSTED_ROLES_CODEC = TrustedEntry.CODEC
            .listOf(0, MAX_TRUSTED_PLAYERS)
            .flatXmap(Claim::trustedRolesFromEntries, Claim::trustedRoleEntries);
    public static final Codec<Claim> CODEC = RecordCodecBuilder.<Claim>create(instance -> instance.group(
            UUIDUtil.STRING_CODEC.fieldOf("owner").forGetter(Claim::ownerId),
            Codec.LONG.optionalFieldOf("purchase_price", 0L).forGetter(Claim::purchasePrice),
            TRUSTED_ROLES_CODEC.optionalFieldOf("trusted_roles", Map.of()).forGetter(Claim::trustedRoles),
            ClaimSettings.CODEC.optionalFieldOf("settings", ClaimSettings.defaults()).forGetter(Claim::settings),
            UUIDUtil.STRING_CODEC.optionalFieldOf("pending_transfer_to").forGetter(Claim::pendingTransferTo)
    ).apply(instance, Claim::new)).validate(Claim::validate);

    public Claim {
        trustedRoles = trustedRoles == null ? Map.of() : Map.copyOf(trustedRoles);
        settings = settings == null ? ClaimSettings.defaults() : settings;
        pendingTransferTo = pendingTransferTo == null ? Optional.empty() : pendingTransferTo;
    }

    public Claim(UUID ownerId, long purchasePrice) {
        this(ownerId, purchasePrice, Map.of(), ClaimSettings.defaults(), Optional.empty());
    }

    public ClaimRole roleOf(UUID playerId) {
        return ownerId.equals(playerId) ? ClaimRole.OWNER : trustedRoles.getOrDefault(playerId, ClaimRole.VISITOR);
    }

    public Claim withRole(UUID playerId, ClaimRole role) {
        Map<UUID, ClaimRole> updated = new java.util.HashMap<>(trustedRoles);
        updated.put(playerId, role);
        return new Claim(ownerId, purchasePrice, updated, settings, pendingTransferTo);
    }

    public Claim withoutRole(UUID playerId) {
        Map<UUID, ClaimRole> updated = new java.util.HashMap<>(trustedRoles);
        updated.remove(playerId);
        return new Claim(ownerId, purchasePrice, updated, settings, pendingTransferTo);
    }

    public Claim withSettings(ClaimSettings updatedSettings) {
        return new Claim(ownerId, purchasePrice, trustedRoles, updatedSettings, pendingTransferTo);
    }

    public Claim withPendingTransfer(UUID recipientId) {
        return new Claim(ownerId, purchasePrice, trustedRoles, settings, Optional.of(recipientId));
    }

    public Claim withoutPendingTransfer() {
        return new Claim(ownerId, purchasePrice, trustedRoles, settings, Optional.empty());
    }

    public Claim transferredTo(UUID recipientId) {
        return new Claim(recipientId, purchasePrice);
    }

    private static DataResult<Claim> validate(Claim claim) {
        if (claim == null || claim.ownerId == null || claim.purchasePrice < 0 || claim.settings == null
                || claim.trustedRoles == null || claim.pendingTransferTo == null
                || claim.trustedRoles.size() > MAX_TRUSTED_PLAYERS
                || claim.trustedRoles.containsKey(claim.ownerId)
                || claim.trustedRoles.values().stream().anyMatch(role -> role == null || role == ClaimRole.OWNER)
                || claim.pendingTransferTo.filter(claim.ownerId::equals).isPresent()) {
            return DataResult.error(() -> "Claim ownership, trust, settings, or transfer state is invalid");
        }
        return DataResult.success(claim);
    }

    private static DataResult<Map<UUID, ClaimRole>> trustedRolesFromEntries(List<TrustedEntry> entries) {
        Map<UUID, ClaimRole> roles = new LinkedHashMap<>();
        for (TrustedEntry entry : entries) {
            if (roles.putIfAbsent(entry.playerId(), entry.role()) != null) {
                return DataResult.error(() -> "Duplicate trusted player " + entry.playerId());
            }
        }
        return DataResult.success(Map.copyOf(roles));
    }

    private static DataResult<List<TrustedEntry>> trustedRoleEntries(Map<UUID, ClaimRole> roles) {
        return DataResult.success(roles.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new TrustedEntry(entry.getKey(), entry.getValue()))
                .toList());
    }

    private record TrustedEntry(UUID playerId, ClaimRole role) {
        private static final Codec<TrustedEntry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                UUIDUtil.STRING_CODEC.fieldOf("player").forGetter(TrustedEntry::playerId),
                ClaimRole.CODEC.fieldOf("role").forGetter(TrustedEntry::role)
        ).apply(instance, TrustedEntry::new));
    }
}
