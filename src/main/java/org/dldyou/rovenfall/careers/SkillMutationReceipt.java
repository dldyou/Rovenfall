package org.dldyou.rovenfall.careers;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.util.StringRepresentable;

public record SkillMutationReceipt(
        long timestampEpochMillis,
        UUID transactionId,
        UUID playerId,
        Identifier careerId,
        Optional<Identifier> skillId,
        Operation operation,
        int rankBefore,
        int rankAfter,
        int spentPointsBefore,
        int spentPointsAfter,
        long currencyCost) {
    private static final UUID ZERO_UUID = new UUID(0L, 0L);
    public static final Codec<SkillMutationReceipt> CODEC = RecordCodecBuilder
            .<SkillMutationReceipt>create(instance -> instance.group(
                    Codec.LONG.fieldOf("timestamp").forGetter(SkillMutationReceipt::timestampEpochMillis),
                    UUIDUtil.STRING_CODEC.fieldOf("transaction_id")
                            .forGetter(SkillMutationReceipt::transactionId),
                    UUIDUtil.STRING_CODEC.fieldOf("player").forGetter(SkillMutationReceipt::playerId),
                    Identifier.CODEC.fieldOf("career").forGetter(SkillMutationReceipt::careerId),
                    Identifier.CODEC.optionalFieldOf("skill").forGetter(SkillMutationReceipt::skillId),
                    Operation.CODEC.fieldOf("operation").forGetter(SkillMutationReceipt::operation),
                    Codec.intRange(0, CareerSkillDefinition.MAX_RANK).fieldOf("rank_before")
                            .forGetter(SkillMutationReceipt::rankBefore),
                    Codec.intRange(0, CareerSkillDefinition.MAX_RANK).fieldOf("rank_after")
                            .forGetter(SkillMutationReceipt::rankAfter),
                    Codec.intRange(0, CareerProgress.MAX_SKILL_POINTS).fieldOf("spent_points_before")
                            .forGetter(SkillMutationReceipt::spentPointsBefore),
                    Codec.intRange(0, CareerProgress.MAX_SKILL_POINTS).fieldOf("spent_points_after")
                            .forGetter(SkillMutationReceipt::spentPointsAfter),
                    Codec.LONG.fieldOf("currency_cost").forGetter(SkillMutationReceipt::currencyCost)
            ).apply(instance, SkillMutationReceipt::new))
            .validate(SkillMutationReceipt::validate);

    public SkillMutationReceipt {
        skillId = skillId == null ? Optional.empty() : skillId;
    }

    private static DataResult<SkillMutationReceipt> validate(SkillMutationReceipt receipt) {
        if (receipt == null || receipt.timestampEpochMillis < 0 || receipt.transactionId == null
                || ZERO_UUID.equals(receipt.transactionId) || receipt.playerId == null
                || ZERO_UUID.equals(receipt.playerId) || receipt.careerId == null
                || receipt.operation == null || receipt.rankBefore < 0 || receipt.rankAfter < 0
                || receipt.spentPointsBefore < 0 || receipt.spentPointsAfter < 0
                || receipt.currencyCost < 0 || receipt.currencyCost > CareerDefinition.MAX_PROMOTION_COST) {
            return DataResult.error(() -> "career skill mutation receipt is invalid");
        }
        if (receipt.operation == Operation.UNLOCK
                && (receipt.skillId.isEmpty() || receipt.rankAfter != receipt.rankBefore + 1
                || receipt.spentPointsAfter <= receipt.spentPointsBefore || receipt.currencyCost != 0)) {
            return DataResult.error(() -> "career skill unlock receipt is invalid");
        }
        if (receipt.operation == Operation.RESET
                && (receipt.skillId.isPresent() || receipt.rankBefore != 0 || receipt.rankAfter != 0
                || receipt.spentPointsBefore < 1 || receipt.spentPointsAfter != 0)) {
            return DataResult.error(() -> "career skill reset receipt is invalid");
        }
        return DataResult.success(receipt);
    }

    public boolean matches(
            UUID requestedPlayer,
            Identifier requestedCareer,
            Optional<Identifier> requestedSkill,
            Operation requestedOperation) {
        return playerId.equals(requestedPlayer)
                && careerId.equals(requestedCareer)
                && skillId.equals(requestedSkill)
                && operation == requestedOperation;
    }

    public enum Operation implements StringRepresentable {
        UNLOCK("unlock"),
        RESET("reset");

        private static final Codec<Operation> CODEC = StringRepresentable.fromEnum(Operation::values);
        private final String id;

        Operation(String id) {
            this.id = id;
        }

        @Override
        public String getSerializedName() {
            return id;
        }
    }
}
