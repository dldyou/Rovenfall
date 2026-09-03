package org.dldyou.rovenfall.careers;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;

public record CareerPromotionReceipt(
        long timestampEpochMillis,
        UUID transactionId,
        UUID playerId,
        Identifier careerId,
        long promotionCost,
        int promotionSkillPoints,
        Optional<Identifier> previousActiveCareer,
        Set<Identifier> resetCareers) {
    private static final UUID ZERO_UUID = new UUID(0L, 0L);
    private static final Codec<Set<Identifier>> RESET_CODEC = Identifier.CODEC
            .listOf(0, CareerCatalog.MAX_DEFINITIONS)
            .flatXmap(CareerPromotionReceipt::resetFromList, CareerPromotionReceipt::resetToList);
    public static final Codec<CareerPromotionReceipt> CODEC = RecordCodecBuilder
            .<CareerPromotionReceipt>create(instance -> instance.group(
                    Codec.LONG.fieldOf("timestamp").forGetter(CareerPromotionReceipt::timestampEpochMillis),
                    UUIDUtil.STRING_CODEC.fieldOf("transaction_id")
                            .forGetter(CareerPromotionReceipt::transactionId),
                    UUIDUtil.STRING_CODEC.fieldOf("player").forGetter(CareerPromotionReceipt::playerId),
                    Identifier.CODEC.fieldOf("career").forGetter(CareerPromotionReceipt::careerId),
                    Codec.LONG.fieldOf("promotion_cost").forGetter(CareerPromotionReceipt::promotionCost),
                    Codec.intRange(0, CareerDefinition.MAX_PROMOTION_SKILL_POINTS)
                            .optionalFieldOf("promotion_skill_points", 0)
                            .forGetter(CareerPromotionReceipt::promotionSkillPoints),
                    Identifier.CODEC.optionalFieldOf("previous_active")
                            .forGetter(CareerPromotionReceipt::previousActiveCareer),
                    RESET_CODEC.optionalFieldOf("reset_careers", Set.of())
                            .forGetter(CareerPromotionReceipt::resetCareers)
            ).apply(instance, CareerPromotionReceipt::new))
            .validate(CareerPromotionReceipt::validate);

    public CareerPromotionReceipt {
        previousActiveCareer = previousActiveCareer == null ? Optional.empty() : previousActiveCareer;
        resetCareers = resetCareers == null ? Set.of() : Set.copyOf(resetCareers);
    }

    private static DataResult<CareerPromotionReceipt> validate(CareerPromotionReceipt receipt) {
        if (receipt == null || receipt.timestampEpochMillis < 0 || receipt.transactionId == null
                || ZERO_UUID.equals(receipt.transactionId) || receipt.playerId == null
                || ZERO_UUID.equals(receipt.playerId)
                || receipt.careerId == null || receipt.promotionCost < 0
                || receipt.promotionCost > CareerDefinition.MAX_PROMOTION_COST
                || receipt.promotionSkillPoints < 0
                || receipt.promotionSkillPoints > CareerDefinition.MAX_PROMOTION_SKILL_POINTS
                || receipt.resetCareers.contains(receipt.careerId)) {
            return DataResult.error(() -> "career promotion receipt is invalid");
        }
        return DataResult.success(receipt);
    }

    public boolean matches(UUID requestedPlayerId, Identifier requestedCareerId) {
        return playerId.equals(requestedPlayerId) && careerId.equals(requestedCareerId);
    }

    private static DataResult<Set<Identifier>> resetFromList(List<Identifier> values) {
        Set<Identifier> result = new HashSet<>();
        for (Identifier value : values) {
            if (!result.add(value)) {
                return DataResult.error(() -> "duplicate reset career " + value);
            }
        }
        return DataResult.success(Set.copyOf(result));
    }

    private static DataResult<List<Identifier>> resetToList(Set<Identifier> values) {
        List<Identifier> sorted = new ArrayList<>(values);
        sorted.sort(Identifier::compareTo);
        return DataResult.success(List.copyOf(sorted));
    }
}
