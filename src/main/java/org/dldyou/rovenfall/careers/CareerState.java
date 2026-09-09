package org.dldyou.rovenfall.careers;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;

public record CareerState(
        Map<UUID, PlayerCareerState> players,
        Map<UUID, CareerPromotionReceipt> promotionReceipts,
        Map<UUID, SkillMutationReceipt> skillReceipts) {
    public static final int MAX_PLAYERS = 1_000_000;
    public static final int MAX_RECEIPTS = 250_000;
    private static final Codec<Map<UUID, PlayerCareerState>> PLAYERS_CODEC = Codec.unboundedMap(
            UUIDUtil.STRING_CODEC, PlayerCareerState.CODEC).validate(values ->
                    values.size() > MAX_PLAYERS
                            ? DataResult.error(() -> "career player count exceeds " + MAX_PLAYERS)
                            : DataResult.success(values));
    private static final Codec<Map<UUID, CareerPromotionReceipt>> RECEIPTS_CODEC = Codec.unboundedMap(
            UUIDUtil.STRING_CODEC, CareerPromotionReceipt.CODEC).validate(CareerState::validateReceipts);
    private static final Codec<Map<UUID, SkillMutationReceipt>> SKILL_RECEIPTS_CODEC = Codec.unboundedMap(
            UUIDUtil.STRING_CODEC, SkillMutationReceipt.CODEC).validate(CareerState::validateSkillReceipts);
    public static final Codec<CareerState> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            PLAYERS_CODEC.optionalFieldOf("players", Map.of()).forGetter(CareerState::players),
            RECEIPTS_CODEC.optionalFieldOf("promotion_receipts", Map.of()).forGetter(CareerState::promotionReceipts),
            SKILL_RECEIPTS_CODEC.optionalFieldOf("skill_receipts", Map.of()).forGetter(CareerState::skillReceipts)
    ).apply(instance, CareerState::new));

    public CareerState {
        players = players == null ? Map.of() : Map.copyOf(players);
        promotionReceipts = promotionReceipts == null ? Map.of() : Map.copyOf(promotionReceipts);
        skillReceipts = skillReceipts == null ? Map.of() : Map.copyOf(skillReceipts);
    }

    public static CareerState empty() {
        return new CareerState(Map.of(), Map.of(), Map.of());
    }

    private static DataResult<Map<UUID, CareerPromotionReceipt>> validateReceipts(
            Map<UUID, CareerPromotionReceipt> receipts) {
        if (receipts.size() > MAX_RECEIPTS) {
            return DataResult.error(() -> "career promotion receipt count exceeds " + MAX_RECEIPTS);
        }
        for (var entry : receipts.entrySet()) {
            if (entry.getValue() == null || !entry.getKey().equals(entry.getValue().transactionId())) {
                return DataResult.error(() -> "career promotion receipt key does not match " + entry.getKey());
            }
        }
        return DataResult.success(receipts);
    }

    private static DataResult<Map<UUID, SkillMutationReceipt>> validateSkillReceipts(
            Map<UUID, SkillMutationReceipt> receipts) {
        if (receipts.size() > MAX_RECEIPTS) {
            return DataResult.error(() -> "career skill receipt count exceeds " + MAX_RECEIPTS);
        }
        for (var entry : receipts.entrySet()) {
            if (entry.getValue() == null || !entry.getKey().equals(entry.getValue().transactionId())) {
                return DataResult.error(() -> "career skill receipt key does not match " + entry.getKey());
            }
        }
        return DataResult.success(receipts);
    }
}
