package org.dldyou.rovenfall.mobs;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;

public record BossState(Optional<BossEncounter> encounter, Map<UUID, Long> rewardReadyAtByPlayer) {
    public static final int MAX_COOLDOWNS = 1_000_000;
    private static final Codec<Map<UUID, Long>> COOLDOWNS_CODEC = Codec.unboundedMap(
            UUIDUtil.STRING_CODEC,
            Codec.LONG.validate(value -> value < 0
                    ? DataResult.error(() -> "boss reward cooldown is negative")
                    : DataResult.success(value)))
            .validate(values -> values.size() > MAX_COOLDOWNS
                    ? DataResult.error(() -> "boss reward cooldown count exceeds " + MAX_COOLDOWNS)
                    : DataResult.success(values));
    public static final Codec<BossState> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            BossEncounter.CODEC.optionalFieldOf("encounter").forGetter(BossState::encounter),
            COOLDOWNS_CODEC.optionalFieldOf("reward_ready_at", Map.of()).forGetter(BossState::rewardReadyAtByPlayer)
    ).apply(instance, BossState::new));

    public BossState {
        encounter = encounter == null ? Optional.empty() : encounter;
        rewardReadyAtByPlayer = rewardReadyAtByPlayer == null ? Map.of() : Map.copyOf(rewardReadyAtByPlayer);
    }

    public static BossState empty() {
        return new BossState(Optional.empty(), Map.of());
    }

    public long rewardReadyAt(UUID playerId) {
        return rewardReadyAtByPlayer.getOrDefault(playerId, 0L);
    }

    public BossState withEncounter(BossEncounter updated) {
        return new BossState(Optional.ofNullable(updated), rewardReadyAtByPlayer);
    }

    public BossState withReward(UUID playerId, long readyAt, BossEncounter updatedEncounter) {
        Map<UUID, Long> cooldowns = new HashMap<>(rewardReadyAtByPlayer);
        cooldowns.put(playerId, readyAt);
        return new BossState(Optional.of(updatedEncounter), cooldowns);
    }
}
