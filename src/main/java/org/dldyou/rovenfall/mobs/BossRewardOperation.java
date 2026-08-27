package org.dldyou.rovenfall.mobs;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.math.BigInteger;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

public record BossRewardOperation(
        UUID encounterId,
        Identifier bossId,
        UUID definitionFingerprint,
        UUID playerId,
        ResourceKey<Level> dimension,
        BlockPos center,
        long playerPoints,
        long totalPoints,
        long minimumPoints,
        int minimumShareBasisPoints,
        long currency,
        long experience,
        long cooldownUntilEpochMillis,
        long createdAtEpochMillis,
        List<ItemStack> items,
        Phase phase) {
    public static final int MAX_ITEM_STACKS = 256;
    private static final UUID ZERO_UUID = new UUID(0L, 0L);

    public static final Codec<BossRewardOperation> CODEC =
            RecordCodecBuilder.<BossRewardOperation>create(instance -> instance.group(
                    UUIDUtil.STRING_CODEC.fieldOf("encounter_id").forGetter(BossRewardOperation::encounterId),
                    Identifier.CODEC.fieldOf("boss_id").forGetter(BossRewardOperation::bossId),
                    UUIDUtil.STRING_CODEC.fieldOf("definition_fingerprint")
                            .forGetter(BossRewardOperation::definitionFingerprint),
                    UUIDUtil.STRING_CODEC.fieldOf("player_id").forGetter(BossRewardOperation::playerId),
                    Level.RESOURCE_KEY_CODEC.fieldOf("dimension").forGetter(BossRewardOperation::dimension),
                    BlockPos.CODEC.fieldOf("center").forGetter(BossRewardOperation::center),
                    Codec.LONG.fieldOf("player_points").forGetter(BossRewardOperation::playerPoints),
                    Codec.LONG.fieldOf("total_points").forGetter(BossRewardOperation::totalPoints),
                    Codec.LONG.fieldOf("minimum_points").forGetter(BossRewardOperation::minimumPoints),
                    Codec.INT.fieldOf("minimum_share_basis_points")
                            .forGetter(BossRewardOperation::minimumShareBasisPoints),
                    Codec.LONG.fieldOf("currency").forGetter(BossRewardOperation::currency),
                    Codec.LONG.fieldOf("experience").forGetter(BossRewardOperation::experience),
                    Codec.LONG.fieldOf("cooldown_until").forGetter(BossRewardOperation::cooldownUntilEpochMillis),
                    Codec.LONG.fieldOf("created_at").forGetter(BossRewardOperation::createdAtEpochMillis),
                    ItemStack.CODEC.listOf(0, MAX_ITEM_STACKS).optionalFieldOf("items", List.of())
                            .forGetter(BossRewardOperation::items),
                    Phase.CODEC.fieldOf("phase").forGetter(BossRewardOperation::phase)
            ).apply(instance, BossRewardOperation::new)).validate(operation -> operation.isValid()
                    ? DataResult.success(operation)
                    : DataResult.error(() -> "Invalid boss reward operation"));

    public BossRewardOperation {
        center = center == null ? null : center.immutable();
        items = items == null ? List.of() : items.stream().map(ItemStack::copy).toList();
    }

    @Override
    public List<ItemStack> items() {
        return items.stream().map(ItemStack::copy).toList();
    }

    public boolean isValid() {
        return encounterId != null && !ZERO_UUID.equals(encounterId)
                && bossId != null
                && definitionFingerprint != null && !ZERO_UUID.equals(definitionFingerprint)
                && playerId != null && !ZERO_UUID.equals(playerId)
                && dimension != null && center != null
                && playerPoints > 0 && playerPoints <= totalPoints
                && minimumPoints > 0
                && minimumShareBasisPoints >= 1 && minimumShareBasisPoints <= 10_000
                && qualifies(playerPoints, totalPoints, minimumPoints, minimumShareBasisPoints)
                && currency >= 0 && currency <= MobContentSnapshot.MAX_REWARD
                && experience >= 0 && experience <= MobContentSnapshot.MAX_REWARD
                && cooldownUntilEpochMillis >= createdAtEpochMillis && createdAtEpochMillis >= 0
                && items.size() <= MAX_ITEM_STACKS
                && items.stream().allMatch(item -> item != null && !item.isEmpty()
                        && ItemStack.validateStrict(item).error().isEmpty())
                && phase != null;
    }

    public BossRewardOperation atPhase(Phase next) {
        return new BossRewardOperation(
                encounterId, bossId, definitionFingerprint, playerId, dimension, center,
                playerPoints, totalPoints, minimumPoints, minimumShareBasisPoints,
                currency, experience, cooldownUntilEpochMillis, createdAtEpochMillis, items, next);
    }

    public static boolean qualifies(
            long playerPoints, long totalPoints, long minimumPoints, int minimumShareBasisPoints) {
        if (playerPoints < minimumPoints || totalPoints < playerPoints || playerPoints <= 0
                || minimumPoints <= 0 || minimumShareBasisPoints < 1 || minimumShareBasisPoints > 10_000) {
            return false;
        }
        return BigInteger.valueOf(playerPoints).multiply(BigInteger.valueOf(10_000L))
                .compareTo(BigInteger.valueOf(totalPoints)
                        .multiply(BigInteger.valueOf(minimumShareBasisPoints))) >= 0;
    }

    public enum Phase implements StringRepresentable {
        PENDING("pending"),
        CORE_APPLIED("core_applied"),
        COMPLETED("completed"),
        FAILED("failed");

        public static final Codec<Phase> CODEC = StringRepresentable.fromEnum(Phase::values);
        private final String serializedName;

        Phase(String serializedName) {
            this.serializedName = serializedName;
        }

        @Override
        public String getSerializedName() {
            return serializedName;
        }
    }
}
