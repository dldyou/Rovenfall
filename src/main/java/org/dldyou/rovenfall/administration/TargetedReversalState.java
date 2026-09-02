package org.dldyou.rovenfall.administration;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.util.StringRepresentable;
import org.dldyou.rovenfall.claims.Claim;
import org.dldyou.rovenfall.claims.ClaimKey;
import org.dldyou.rovenfall.careers.PlayerCareerState;
import org.dldyou.rovenfall.economy.ShopInstance;

/** Exact before/after evidence for safe, domain-scoped administrator reversals. */
record TargetedReversalState(
        Map<UUID, ClaimEvidence> claims,
        Map<UUID, ShopEvidence> shops,
        Map<UUID, CareerEvidence> careers) {
    static final int MAX_EVIDENCE = PlatformSavedData.MAX_ECONOMY_TRANSACTIONS;
    private static final UUID ZERO_UUID = new UUID(0L, 0L);
    private static final Codec<Map<UUID, ClaimEvidence>> CLAIMS_CODEC = Codec.unboundedMap(
            UUIDUtil.STRING_CODEC, ClaimEvidence.CODEC).validate(TargetedReversalState::validateClaimMap);
    private static final Codec<Map<UUID, ShopEvidence>> SHOPS_CODEC = Codec.unboundedMap(
            UUIDUtil.STRING_CODEC, ShopEvidence.CODEC).validate(TargetedReversalState::validateShopMap);
    private static final Codec<Map<UUID, CareerEvidence>> CAREERS_CODEC = Codec.unboundedMap(
            UUIDUtil.STRING_CODEC, CareerEvidence.CODEC).validate(TargetedReversalState::validateCareerMap);
    static final Codec<TargetedReversalState> CODEC = RecordCodecBuilder
            .<TargetedReversalState>create(instance -> instance.group(
                    CLAIMS_CODEC.optionalFieldOf("claims", Map.of()).forGetter(TargetedReversalState::claims),
                    SHOPS_CODEC.optionalFieldOf("shops", Map.of()).forGetter(TargetedReversalState::shops),
                    CAREERS_CODEC.optionalFieldOf("careers", Map.of()).forGetter(TargetedReversalState::careers)
            ).apply(instance, TargetedReversalState::new))
            .validate(TargetedReversalState::validate);

    TargetedReversalState {
        claims = claims == null ? Map.of() : Map.copyOf(claims);
        shops = shops == null ? Map.of() : Map.copyOf(shops);
        careers = careers == null ? Map.of() : Map.copyOf(careers);
    }

    static TargetedReversalState empty() {
        return new TargetedReversalState(Map.of(), Map.of(), Map.of());
    }

    private static DataResult<TargetedReversalState> validate(TargetedReversalState state) {
        long size = (long) state.claims.size() + state.shops.size() + state.careers.size();
        if (size > MAX_EVIDENCE) {
            return DataResult.error(() -> "targeted reversal evidence exceeds " + MAX_EVIDENCE);
        }
        Set<UUID> ids = new HashSet<>(state.claims.keySet());
        if (state.shops.keySet().stream().anyMatch(id -> !ids.add(id))
                || state.careers.keySet().stream().anyMatch(id -> !ids.add(id))) {
            return DataResult.error(() -> "targeted reversal transaction is present in multiple domains");
        }
        return DataResult.success(state);
    }

    private static DataResult<Map<UUID, ClaimEvidence>> validateClaimMap(Map<UUID, ClaimEvidence> values) {
        return validateMap(values, ClaimEvidence::transactionId, "claim");
    }

    private static DataResult<Map<UUID, ShopEvidence>> validateShopMap(Map<UUID, ShopEvidence> values) {
        return validateMap(values, ShopEvidence::transactionId, "shop");
    }

    private static DataResult<Map<UUID, CareerEvidence>> validateCareerMap(Map<UUID, CareerEvidence> values) {
        return validateMap(values, CareerEvidence::transactionId, "career");
    }

    private static <T> DataResult<Map<UUID, T>> validateMap(
            Map<UUID, T> values,
            java.util.function.Function<T, UUID> transactionId,
            String domain) {
        if (values.size() > MAX_EVIDENCE) {
            return DataResult.error(() -> domain + " reversal evidence exceeds " + MAX_EVIDENCE);
        }
        for (var entry : values.entrySet()) {
            if (entry.getValue() == null || !entry.getKey().equals(transactionId.apply(entry.getValue()))) {
                return DataResult.error(() -> domain + " reversal evidence key does not match " + entry.getKey());
            }
        }
        return DataResult.success(values);
    }

    enum Domain implements StringRepresentable {
        CLAIM("claim"),
        CLAIM_PERMISSION("claim_permission"),
        SHOP("shop"),
        CAREER("career"),
        SKILL("skill");

        static final Codec<Domain> CODEC = StringRepresentable.fromEnum(Domain::values);
        private final String id;

        Domain(String id) {
            this.id = id;
        }

        @Override
        public String getSerializedName() {
            return id;
        }

        String translationKey() {
            return "targeted_reversal_domain.rovenfall." + id;
        }
    }

    record ClaimEvidence(
            long timestampEpochMillis,
            UUID transactionId,
            UUID actorId,
            Domain domain,
            ClaimKey claimKey,
            Optional<Claim> before,
            Optional<Claim> after,
            Optional<UUID> balancePlayerId,
            Optional<Long> balanceBefore,
            Optional<Long> balanceAfter,
            Optional<UUID> reversedBy) {
        static final Codec<ClaimEvidence> CODEC = RecordCodecBuilder
                .<ClaimEvidence>create(instance -> instance.group(
                        Codec.LONG.fieldOf("timestamp").forGetter(ClaimEvidence::timestampEpochMillis),
                        UUIDUtil.STRING_CODEC.fieldOf("transaction_id").forGetter(ClaimEvidence::transactionId),
                        UUIDUtil.STRING_CODEC.fieldOf("actor").forGetter(ClaimEvidence::actorId),
                        Domain.CODEC.fieldOf("domain").forGetter(ClaimEvidence::domain),
                        ClaimKey.CODEC.fieldOf("claim").forGetter(ClaimEvidence::claimKey),
                        Claim.CODEC.optionalFieldOf("before").forGetter(ClaimEvidence::before),
                        Claim.CODEC.optionalFieldOf("after").forGetter(ClaimEvidence::after),
                        UUIDUtil.STRING_CODEC.optionalFieldOf("balance_player")
                                .forGetter(ClaimEvidence::balancePlayerId),
                        Codec.LONG.optionalFieldOf("balance_before").forGetter(ClaimEvidence::balanceBefore),
                        Codec.LONG.optionalFieldOf("balance_after").forGetter(ClaimEvidence::balanceAfter),
                        UUIDUtil.STRING_CODEC.optionalFieldOf("reversed_by").forGetter(ClaimEvidence::reversedBy)
                ).apply(instance, ClaimEvidence::new))
                .validate(ClaimEvidence::validate);

        ClaimEvidence {
            before = optional(before);
            after = optional(after);
            balancePlayerId = optional(balancePlayerId);
            balanceBefore = optional(balanceBefore);
            balanceAfter = optional(balanceAfter);
            reversedBy = optional(reversedBy);
        }

        ClaimEvidence withReversedBy(UUID reversalId) {
            return new ClaimEvidence(
                    timestampEpochMillis, transactionId, actorId, domain, claimKey, before, after,
                    balancePlayerId, balanceBefore, balanceAfter, Optional.of(reversalId));
        }

        private static DataResult<ClaimEvidence> validate(ClaimEvidence value) {
            boolean balanced = value.balancePlayerId.isPresent() == value.balanceBefore.isPresent()
                    && value.balanceBefore.isPresent() == value.balanceAfter.isPresent();
            if (value.timestampEpochMillis < 0 || invalidId(value.transactionId) || value.actorId == null
                    || value.domain != Domain.CLAIM && value.domain != Domain.CLAIM_PERMISSION
                    || value.claimKey == null || value.before.equals(value.after)
                    || value.before.isEmpty() && value.after.isEmpty() || !balanced
                    || value.balanceBefore.filter(balance -> balance < 0).isPresent()
                    || value.balanceAfter.filter(balance -> balance < 0).isPresent()
                    || value.reversedBy.filter(TargetedReversalState::invalidId).isPresent()) {
                return DataResult.error(() -> "claim targeted reversal evidence is invalid");
            }
            return DataResult.success(value);
        }
    }

    record ShopEvidence(
            long timestampEpochMillis,
            UUID transactionId,
            UUID actorId,
            Identifier shopId,
            Optional<ShopInstance> before,
            Optional<ShopInstance> after,
            Optional<UUID> reversedBy) {
        static final Codec<ShopEvidence> CODEC = RecordCodecBuilder
                .<ShopEvidence>create(instance -> instance.group(
                        Codec.LONG.fieldOf("timestamp").forGetter(ShopEvidence::timestampEpochMillis),
                        UUIDUtil.STRING_CODEC.fieldOf("transaction_id").forGetter(ShopEvidence::transactionId),
                        UUIDUtil.STRING_CODEC.fieldOf("actor").forGetter(ShopEvidence::actorId),
                        Identifier.CODEC.fieldOf("shop").forGetter(ShopEvidence::shopId),
                        ShopInstance.CODEC.optionalFieldOf("before").forGetter(ShopEvidence::before),
                        ShopInstance.CODEC.optionalFieldOf("after").forGetter(ShopEvidence::after),
                        UUIDUtil.STRING_CODEC.optionalFieldOf("reversed_by").forGetter(ShopEvidence::reversedBy)
                ).apply(instance, ShopEvidence::new))
                .validate(ShopEvidence::validate);

        ShopEvidence {
            before = optional(before);
            after = optional(after);
            reversedBy = optional(reversedBy);
        }

        ShopEvidence withReversedBy(UUID reversalId) {
            return new ShopEvidence(
                    timestampEpochMillis, transactionId, actorId, shopId,
                    before, after, Optional.of(reversalId));
        }

        private static DataResult<ShopEvidence> validate(ShopEvidence value) {
            if (value.timestampEpochMillis < 0 || invalidId(value.transactionId) || value.actorId == null
                    || value.shopId == null || value.before.equals(value.after)
                    || value.before.isEmpty() && value.after.isEmpty()
                    || value.reversedBy.filter(TargetedReversalState::invalidId).isPresent()) {
                return DataResult.error(() -> "shop targeted reversal evidence is invalid");
            }
            return DataResult.success(value);
        }
    }

    record CareerEvidence(
            long timestampEpochMillis,
            UUID transactionId,
            UUID actorId,
            Domain domain,
            UUID playerId,
            Optional<PlayerCareerState> before,
            Optional<PlayerCareerState> after,
            Optional<Long> balanceBefore,
            Optional<Long> balanceAfter,
            Optional<UUID> reversedBy) {
        static final Codec<CareerEvidence> CODEC = RecordCodecBuilder
                .<CareerEvidence>create(instance -> instance.group(
                        Codec.LONG.fieldOf("timestamp").forGetter(CareerEvidence::timestampEpochMillis),
                        UUIDUtil.STRING_CODEC.fieldOf("transaction_id").forGetter(CareerEvidence::transactionId),
                        UUIDUtil.STRING_CODEC.fieldOf("actor").forGetter(CareerEvidence::actorId),
                        Domain.CODEC.fieldOf("domain").forGetter(CareerEvidence::domain),
                        UUIDUtil.STRING_CODEC.fieldOf("player").forGetter(CareerEvidence::playerId),
                        PlayerCareerState.CODEC.optionalFieldOf("before").forGetter(CareerEvidence::before),
                        PlayerCareerState.CODEC.optionalFieldOf("after").forGetter(CareerEvidence::after),
                        Codec.LONG.optionalFieldOf("balance_before").forGetter(CareerEvidence::balanceBefore),
                        Codec.LONG.optionalFieldOf("balance_after").forGetter(CareerEvidence::balanceAfter),
                        UUIDUtil.STRING_CODEC.optionalFieldOf("reversed_by").forGetter(CareerEvidence::reversedBy)
                ).apply(instance, CareerEvidence::new))
                .validate(CareerEvidence::validate);

        CareerEvidence {
            before = optional(before);
            after = optional(after);
            balanceBefore = optional(balanceBefore);
            balanceAfter = optional(balanceAfter);
            reversedBy = optional(reversedBy);
        }

        CareerEvidence withReversedBy(UUID reversalId) {
            return new CareerEvidence(
                    timestampEpochMillis, transactionId, actorId, domain, playerId,
                    before, after, balanceBefore, balanceAfter, Optional.of(reversalId));
        }

        private static DataResult<CareerEvidence> validate(CareerEvidence value) {
            boolean balanced = value.balanceBefore.isPresent() == value.balanceAfter.isPresent();
            if (value.timestampEpochMillis < 0 || invalidId(value.transactionId) || value.actorId == null
                    || value.domain != Domain.CAREER && value.domain != Domain.SKILL
                    || invalidId(value.playerId) || value.before.equals(value.after)
                    || value.before.isEmpty() && value.after.isEmpty() || !balanced
                    || value.balanceBefore.filter(balance -> balance < 0).isPresent()
                    || value.balanceAfter.filter(balance -> balance < 0).isPresent()
                    || value.reversedBy.filter(TargetedReversalState::invalidId).isPresent()) {
                return DataResult.error(() -> "career targeted reversal evidence is invalid");
            }
            return DataResult.success(value);
        }
    }

    private static boolean invalidId(UUID id) {
        return id == null || ZERO_UUID.equals(id);
    }

    private static <T> Optional<T> optional(Optional<T> value) {
        return value == null ? Optional.empty() : value;
    }
}
