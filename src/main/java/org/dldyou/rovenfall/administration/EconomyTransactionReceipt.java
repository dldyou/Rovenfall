package org.dldyou.rovenfall.administration;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.item.ItemStack;
import org.dldyou.rovenfall.claims.ClaimKey;
import org.dldyou.rovenfall.economy.ShopInstance;

public record EconomyTransactionReceipt(
        long timestampEpochMillis,
        UUID actorId,
        UUID playerId,
        Kind kind,
        long amount,
        Optional<ClaimKey> claim,
        Optional<Identifier> shopId,
        Optional<Identifier> offerId,
        Optional<ItemStack> item,
        int quantity,
        Optional<ShopInstance.Stock> stockBefore,
        Optional<ShopInstance.Stock> stockAfter,
        Optional<UUID> originalTransactionId,
        Optional<UUID> reversedBy,
        Optional<UUID> invalidatedByRestore,
        CompensationDecision compensationDecision) {
    public static final Codec<EconomyTransactionReceipt> CODEC = RecordCodecBuilder.<EconomyTransactionReceipt>create(instance -> instance.group(
            Codec.LONG.fieldOf("timestamp").forGetter(EconomyTransactionReceipt::timestampEpochMillis),
            UUIDUtil.STRING_CODEC.fieldOf("actor").forGetter(EconomyTransactionReceipt::actorId),
            UUIDUtil.STRING_CODEC.fieldOf("player").forGetter(EconomyTransactionReceipt::playerId),
            Kind.CODEC.fieldOf("kind").forGetter(EconomyTransactionReceipt::kind),
            Codec.LONG.fieldOf("amount").forGetter(EconomyTransactionReceipt::amount),
            ClaimKey.CODEC.optionalFieldOf("claim").forGetter(EconomyTransactionReceipt::claim),
            Identifier.CODEC.optionalFieldOf("shop").forGetter(EconomyTransactionReceipt::shopId),
            Identifier.CODEC.optionalFieldOf("offer").forGetter(EconomyTransactionReceipt::offerId),
            ItemStack.CODEC.optionalFieldOf("item").forGetter(EconomyTransactionReceipt::item),
            Codec.INT.optionalFieldOf("quantity", 0).forGetter(EconomyTransactionReceipt::quantity),
            ShopInstance.Stock.CODEC.optionalFieldOf("stock_before").forGetter(EconomyTransactionReceipt::stockBefore),
            ShopInstance.Stock.CODEC.optionalFieldOf("stock_after").forGetter(EconomyTransactionReceipt::stockAfter),
            UUIDUtil.STRING_CODEC.optionalFieldOf("original_transaction").forGetter(EconomyTransactionReceipt::originalTransactionId),
            UUIDUtil.STRING_CODEC.optionalFieldOf("reversed_by").forGetter(EconomyTransactionReceipt::reversedBy),
            UUIDUtil.STRING_CODEC.optionalFieldOf("invalidated_by_restore").forGetter(EconomyTransactionReceipt::invalidatedByRestore),
            CompensationDecision.CODEC.optionalFieldOf("compensation", CompensationDecision.NONE)
                    .forGetter(EconomyTransactionReceipt::compensationDecision)
    ).apply(instance, EconomyTransactionReceipt::new)).validate(EconomyTransactionReceipt::validate);

    public EconomyTransactionReceipt {
        claim = claim == null ? Optional.empty() : claim;
        shopId = shopId == null ? Optional.empty() : shopId;
        offerId = offerId == null ? Optional.empty() : offerId;
        item = item == null ? Optional.empty() : item.map(ItemStack::copy);
        stockBefore = stockBefore == null ? Optional.empty() : stockBefore;
        stockAfter = stockAfter == null ? Optional.empty() : stockAfter;
        originalTransactionId = originalTransactionId == null ? Optional.empty() : originalTransactionId;
        reversedBy = reversedBy == null ? Optional.empty() : reversedBy;
        invalidatedByRestore = invalidatedByRestore == null ? Optional.empty() : invalidatedByRestore;
        compensationDecision = compensationDecision == null ? CompensationDecision.NONE : compensationDecision;
    }

    @Override
    public Optional<ItemStack> item() {
        return item.map(ItemStack::copy);
    }

    public boolean isTrade() {
        return kind == Kind.PURCHASE || kind == Kind.SALE;
    }

    EconomyTransactionReceipt withReversedBy(UUID transactionId) {
        return new EconomyTransactionReceipt(
                timestampEpochMillis, actorId, playerId, kind, amount, claim, shopId, offerId, item, quantity,
                stockBefore, stockAfter, originalTransactionId, Optional.of(transactionId), invalidatedByRestore,
                compensationDecision);
    }

    EconomyTransactionReceipt invalidatedByRestore(UUID transactionId) {
        return new EconomyTransactionReceipt(
                timestampEpochMillis, actorId, playerId, kind, amount, claim, shopId, offerId, item, quantity,
                stockBefore, stockAfter, originalTransactionId, reversedBy, Optional.of(transactionId),
                compensationDecision);
    }

    private static DataResult<EconomyTransactionReceipt> validate(EconomyTransactionReceipt value) {
        if (value == null || value.timestampEpochMillis < 0 || value.actorId == null || value.playerId == null
                || value.kind == null || value.amount < 0 || value.quantity < 0 || value.compensationDecision == null) {
            return DataResult.error(() -> "Economy transaction receipt is invalid");
        }
        if (value.isTrade()) {
            if (value.amount < 1 || value.shopId.isEmpty() || value.offerId.isEmpty() || value.item.isEmpty()
                    || value.item.orElseThrow().isEmpty() || value.quantity < 1
                    || value.stockBefore.isEmpty() || value.stockAfter.isEmpty()
                    || ItemStack.validateStrict(value.item.orElseThrow()).error().isPresent()) {
                return DataResult.error(() -> "Shop transaction receipt is incomplete");
            }
        } else if (value.shopId.isPresent() || value.offerId.isPresent() || value.item.isPresent() || value.quantity != 0
                || value.stockBefore.isPresent() || value.stockAfter.isPresent()) {
            return DataResult.error(() -> "Non-shop transaction receipt contains shop evidence");
        }
        if (value.kind == Kind.CLAIM_PURCHASE) {
            if (value.amount < 1 || value.claim.isEmpty()) {
                return DataResult.error(() -> "Claim purchase receipt is incomplete");
            }
        } else if (value.kind == Kind.CLAIM_SALE) {
            if (value.claim.isEmpty()) {
                return DataResult.error(() -> "Claim sale receipt is incomplete");
            }
        } else if (value.claim.isPresent()) {
            return DataResult.error(() -> "Non-claim transaction receipt contains claim evidence");
        }
        if (value.kind == Kind.REVERSAL && value.originalTransactionId.isEmpty()) {
            return DataResult.error(() -> "Reversal receipt is missing its original transaction");
        }
        if (value.kind != Kind.REVERSAL && value.originalTransactionId.isPresent()) {
            return DataResult.error(() -> "Only reversal receipts may reference compensation");
        }
        if (value.kind != Kind.REVERSAL && value.compensationDecision != CompensationDecision.NONE) {
            return DataResult.error(() -> "Only reversal receipts may record compensation");
        }
        return DataResult.success(value);
    }

    public enum Kind implements StringRepresentable {
        ACCOUNT_CREATE("account_create"),
        ADMIN_GRANT("admin_grant"),
        ADMIN_DEBIT("admin_debit"),
        AWARD("award"),
        DEBIT("debit"),
        PURCHASE("purchase"),
        SALE("sale"),
        CLAIM_PURCHASE("claim_purchase"),
        CLAIM_SALE("claim_sale"),
        RPG_SKILL_PAYMENT("rpg_skill_payment"),
        BOSS_REWARD("boss_reward"),
        REVERSAL("reversal");

        static final Codec<Kind> CODEC = StringRepresentable.fromEnum(Kind::values);
        private final String id;

        Kind(String id) {
            this.id = id;
        }

        @Override
        public String getSerializedName() {
            return id;
        }
    }

    public enum CompensationDecision implements StringRepresentable {
        NONE("none"),
        REFUND_WITHOUT_ITEMS_OR_STOCK("refund_without_items_or_stock");

        static final Codec<CompensationDecision> CODEC = StringRepresentable.fromEnum(CompensationDecision::values);
        private final String id;

        CompensationDecision(String id) {
            this.id = id;
        }

        @Override
        public String getSerializedName() {
            return id;
        }
    }
}
