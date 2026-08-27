package org.dldyou.rovenfall.administration;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import org.dldyou.rovenfall.world.PortalDefinition;

record PortalState(
        Map<Identifier, PortalDefinition> definitions,
        Map<UUID, Map<Identifier, Long>> cooldowns,
        Map<UUID, TravelReceipt> receipts,
        Map<UUID, Long> combatTimestamps) {
    private static final UUID ZERO_UUID = new UUID(0L, 0L);
    static final int MAX_DEFINITIONS = 64;
    static final int MAX_RUNTIME_ENTRIES = 100_000;
    static final int MAX_COMBAT_ENTRIES = 10_000;
    static final PortalState EMPTY = new PortalState(Map.of(), Map.of(), Map.of(), Map.of());
    private static final Codec<Long> TIMESTAMP_CODEC = Codec.LONG.validate(value -> value < 0
            ? DataResult.error(() -> "Portal timestamp must be non-negative")
            : DataResult.success(value));
    private static final Codec<Map<Identifier, PortalDefinition>> DEFINITIONS_CODEC =
            DefinitionEntry.CODEC.listOf(0, MAX_DEFINITIONS)
                    .flatXmap(PortalState::definitionsFromEntries, PortalState::definitionEntries);
    private static final Codec<Map<UUID, Map<Identifier, Long>>> COOLDOWNS_CODEC =
            CooldownEntry.CODEC.listOf(0, MAX_RUNTIME_ENTRIES)
                    .flatXmap(PortalState::cooldownsFromEntries, PortalState::cooldownEntries);
    private static final Codec<Map<UUID, TravelReceipt>> RECEIPTS_CODEC =
            ReceiptEntry.CODEC.listOf(0, MAX_RUNTIME_ENTRIES)
                    .flatXmap(PortalState::receiptsFromEntries, PortalState::receiptEntries);
    private static final Codec<Map<UUID, Long>> COMBAT_CODEC =
            CombatEntry.CODEC.listOf(0, MAX_COMBAT_ENTRIES)
                    .flatXmap(PortalState::combatFromEntries, PortalState::combatEntries);
    static final Codec<PortalState> CODEC = RecordCodecBuilder.<PortalState>create(instance -> instance.group(
            DEFINITIONS_CODEC.optionalFieldOf("definitions", Map.of()).forGetter(PortalState::definitions),
            COOLDOWNS_CODEC.optionalFieldOf("cooldowns", Map.of()).forGetter(PortalState::cooldowns),
            RECEIPTS_CODEC.optionalFieldOf("receipts", Map.of()).forGetter(PortalState::receipts),
            COMBAT_CODEC.optionalFieldOf("combat_timestamps", Map.of()).forGetter(PortalState::combatTimestamps)
    ).apply(instance, PortalState::new)).validate(PortalState::validate);

    PortalState {
        definitions = Map.copyOf(definitions);
        Map<UUID, Map<Identifier, Long>> copiedCooldowns = new HashMap<>();
        cooldowns.forEach((player, entries) -> copiedCooldowns.put(player, Map.copyOf(entries)));
        cooldowns = Map.copyOf(copiedCooldowns);
        receipts = Map.copyOf(receipts);
        combatTimestamps = Map.copyOf(combatTimestamps);
    }

    private static DataResult<PortalState> validate(PortalState state) {
        long cooldownCount = state.cooldowns.values().stream().mapToLong(Map::size).sum();
        if (state.definitions.size() > MAX_DEFINITIONS) {
            return DataResult.error(() -> "Too many portal definitions");
        }
        if (cooldownCount > MAX_RUNTIME_ENTRIES || state.receipts.size() > MAX_RUNTIME_ENTRIES) {
            return DataResult.error(() -> "Too much portal runtime evidence");
        }
        if (state.combatTimestamps.size() > MAX_COMBAT_ENTRIES) {
            return DataResult.error(() -> "Too many portal combat timestamps");
        }
        long uniqueOrigins = state.definitions.values().stream().map(PortalDefinition::origin).distinct().count();
        return uniqueOrigins == state.definitions.size()
                ? DataResult.success(state)
                : DataResult.error(() -> "Duplicate portal origin");
    }

    private static DataResult<Map<Identifier, PortalDefinition>> definitionsFromEntries(
            List<DefinitionEntry> entries) {
        Map<Identifier, PortalDefinition> definitions = new LinkedHashMap<>();
        for (DefinitionEntry entry : entries) {
            if (definitions.putIfAbsent(entry.id(), entry.definition()) != null) {
                return DataResult.error(() -> "Duplicate portal definition ID " + entry.id());
            }
        }
        return DataResult.success(Map.copyOf(definitions));
    }

    private static DataResult<List<DefinitionEntry>> definitionEntries(
            Map<Identifier, PortalDefinition> definitions) {
        return DataResult.success(definitions.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new DefinitionEntry(entry.getKey(), entry.getValue()))
                .toList());
    }

    private static DataResult<Map<UUID, Map<Identifier, Long>>> cooldownsFromEntries(
            List<CooldownEntry> entries) {
        Map<UUID, Map<Identifier, Long>> cooldowns = new LinkedHashMap<>();
        for (CooldownEntry entry : entries) {
            if (ZERO_UUID.equals(entry.playerId())) {
                return DataResult.error(() -> "Portal cooldown has a zero player ID");
            }
            Map<Identifier, Long> playerCooldowns =
                    cooldowns.computeIfAbsent(entry.playerId(), ignored -> new LinkedHashMap<>());
            if (playerCooldowns.putIfAbsent(entry.portalId(), entry.deadlineEpochMillis()) != null) {
                return DataResult.error(() -> "Duplicate portal cooldown for "
                        + entry.playerId() + " and " + entry.portalId());
            }
        }
        Map<UUID, Map<Identifier, Long>> copied = new LinkedHashMap<>();
        cooldowns.forEach((playerId, playerCooldowns) -> copied.put(playerId, Map.copyOf(playerCooldowns)));
        return DataResult.success(Map.copyOf(copied));
    }

    private static DataResult<List<CooldownEntry>> cooldownEntries(
            Map<UUID, Map<Identifier, Long>> cooldowns) {
        return DataResult.success(cooldowns.entrySet().stream()
                .flatMap(player -> player.getValue().entrySet().stream()
                        .map(cooldown -> new CooldownEntry(
                                player.getKey(), cooldown.getKey(), cooldown.getValue())))
                .sorted(java.util.Comparator.comparing(CooldownEntry::playerId)
                        .thenComparing(CooldownEntry::portalId))
                .toList());
    }

    private static DataResult<Map<UUID, TravelReceipt>> receiptsFromEntries(List<ReceiptEntry> entries) {
        Map<UUID, TravelReceipt> receipts = new LinkedHashMap<>();
        for (ReceiptEntry entry : entries) {
            if (ZERO_UUID.equals(entry.transactionId())
                    || receipts.putIfAbsent(entry.transactionId(), entry.receipt()) != null) {
                return DataResult.error(() -> "Duplicate or zero portal receipt ID " + entry.transactionId());
            }
        }
        return DataResult.success(Map.copyOf(receipts));
    }

    private static DataResult<List<ReceiptEntry>> receiptEntries(Map<UUID, TravelReceipt> receipts) {
        return DataResult.success(receipts.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new ReceiptEntry(entry.getKey(), entry.getValue()))
                .toList());
    }

    private static DataResult<Map<UUID, Long>> combatFromEntries(List<CombatEntry> entries) {
        Map<UUID, Long> timestamps = new LinkedHashMap<>();
        for (CombatEntry entry : entries) {
            if (ZERO_UUID.equals(entry.playerId())
                    || timestamps.putIfAbsent(entry.playerId(), entry.timestampEpochMillis()) != null) {
                return DataResult.error(() -> "Duplicate or zero portal combat player ID " + entry.playerId());
            }
        }
        return DataResult.success(Map.copyOf(timestamps));
    }

    private static DataResult<List<CombatEntry>> combatEntries(Map<UUID, Long> timestamps) {
        return DataResult.success(timestamps.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new CombatEntry(entry.getKey(), entry.getValue()))
                .toList());
    }

    private record DefinitionEntry(Identifier id, PortalDefinition definition) {
        private static final Codec<DefinitionEntry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Identifier.CODEC.fieldOf("id").forGetter(DefinitionEntry::id),
                PortalDefinition.CODEC.fieldOf("definition").forGetter(DefinitionEntry::definition)
        ).apply(instance, DefinitionEntry::new));
    }

    private record CooldownEntry(UUID playerId, Identifier portalId, long deadlineEpochMillis) {
        private static final Codec<CooldownEntry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                UUIDUtil.STRING_CODEC.fieldOf("player").forGetter(CooldownEntry::playerId),
                Identifier.CODEC.fieldOf("portal").forGetter(CooldownEntry::portalId),
                TIMESTAMP_CODEC.fieldOf("deadline").forGetter(CooldownEntry::deadlineEpochMillis)
        ).apply(instance, CooldownEntry::new));
    }

    private record ReceiptEntry(UUID transactionId, TravelReceipt receipt) {
        private static final Codec<ReceiptEntry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                UUIDUtil.STRING_CODEC.fieldOf("transaction").forGetter(ReceiptEntry::transactionId),
                TravelReceipt.CODEC.fieldOf("receipt").forGetter(ReceiptEntry::receipt)
        ).apply(instance, ReceiptEntry::new));
    }

    private record CombatEntry(UUID playerId, long timestampEpochMillis) {
        private static final Codec<CombatEntry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                UUIDUtil.STRING_CODEC.fieldOf("player").forGetter(CombatEntry::playerId),
                TIMESTAMP_CODEC.fieldOf("timestamp").forGetter(CombatEntry::timestampEpochMillis)
        ).apply(instance, CombatEntry::new));
    }

    record TravelReceipt(
            UUID playerId,
            Identifier portalId,
            long completedAtEpochMillis,
            PortalDefinition.Endpoint destination) {
        static final Codec<TravelReceipt> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                UUIDUtil.STRING_CODEC.fieldOf("player").forGetter(TravelReceipt::playerId),
                Identifier.CODEC.fieldOf("portal").forGetter(TravelReceipt::portalId),
                TIMESTAMP_CODEC.fieldOf("completed_at").forGetter(TravelReceipt::completedAtEpochMillis),
                PortalDefinition.Endpoint.CODEC.fieldOf("destination").forGetter(TravelReceipt::destination)
        ).apply(instance, TravelReceipt::new));
    }
}
