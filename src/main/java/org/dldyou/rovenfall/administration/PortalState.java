package org.dldyou.rovenfall.administration;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.HashMap;
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
    static final int MAX_DEFINITIONS = 64;
    static final int MAX_RUNTIME_ENTRIES = 100_000;
    static final int MAX_COMBAT_ENTRIES = 10_000;
    static final PortalState EMPTY = new PortalState(Map.of(), Map.of(), Map.of(), Map.of());
    private static final Codec<Long> TIMESTAMP_CODEC = Codec.LONG.validate(value -> value < 0
            ? DataResult.error(() -> "Portal timestamp must be non-negative")
            : DataResult.success(value));
    private static final Codec<Map<Identifier, PortalDefinition>> DEFINITIONS_CODEC =
            Codec.unboundedMap(Identifier.CODEC, PortalDefinition.CODEC);
    private static final Codec<Map<Identifier, Long>> PLAYER_COOLDOWNS_CODEC =
            Codec.unboundedMap(Identifier.CODEC, TIMESTAMP_CODEC);
    private static final Codec<Map<UUID, Map<Identifier, Long>>> COOLDOWNS_CODEC =
            Codec.unboundedMap(UUIDUtil.STRING_CODEC, PLAYER_COOLDOWNS_CODEC);
    private static final Codec<Map<UUID, TravelReceipt>> RECEIPTS_CODEC =
            Codec.unboundedMap(UUIDUtil.STRING_CODEC, TravelReceipt.CODEC);
    private static final Codec<Map<UUID, Long>> COMBAT_CODEC =
            Codec.unboundedMap(UUIDUtil.STRING_CODEC, TIMESTAMP_CODEC);
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
