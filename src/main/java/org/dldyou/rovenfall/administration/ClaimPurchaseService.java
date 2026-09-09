package org.dldyou.rovenfall.administration;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.dldyou.rovenfall.Rovenfall;
import org.dldyou.rovenfall.claims.Claim;
import org.dldyou.rovenfall.claims.ClaimKey;
import org.dldyou.rovenfall.world.WorldTopology;

public final class ClaimPurchaseService {
    private static final UUID ZERO_UUID = new UUID(0L, 0L);
    private static final long DENIED_AUDIT_INTERVAL_MILLIS = 1_000L;
    private static final Identifier PURCHASE = action("claim_purchase");
    private static final Identifier PURCHASE_DENIED = action("claim_purchase_denied");

    private ClaimPurchaseService() {
    }

    public static PurchaseResult purchase(
            PlatformSavedData state,
            UUID playerId,
            ResourceKey<Level> hubDimension,
            ResourceKey<Level> playerDimension,
            BlockPos playerPosition,
            Predicate<ClaimKey> isEligible,
            Predicate<ClaimKey> isProtected,
            long basePrice,
            long priceIncrease,
            int ownershipCap,
            long timestampEpochMillis,
            UUID transactionId) {
        if (state == null) {
            return result(Status.INVALID_REQUEST, Optional.empty(), 0, 0, transactionId, false);
        }
        if (!state.isWritable()) {
            return result(Status.READ_ONLY_SCHEMA, Optional.empty(), 0, 0, transactionId, false);
        }
        if (playerId == null || hubDimension == null || playerDimension == null || playerPosition == null
                || isEligible == null || isProtected == null || timestampEpochMillis < 0) {
            return result(Status.INVALID_REQUEST, Optional.empty(), 0, 0, transactionId, false);
        }
        if (!validTransactionId(transactionId)) {
            return denied(state, playerId, Optional.empty(), playerPosition, Status.INVALID_TRANSACTION,
                    "invalid_transaction", 0, 0, timestampEpochMillis, transactionId);
        }

        ClaimKey key = ClaimKey.at(playerDimension, playerPosition);
        Optional<EconomyTransactionReceipt> retainedReceipt = state.economyReceipt(transactionId);
        if (retainedReceipt.isPresent()) {
            long balance = state.economyBalance(playerId).orElse(0L);
            EconomyTransactionReceipt receipt = retainedReceipt.orElseThrow();
            return receipt.kind() == EconomyTransactionReceipt.Kind.CLAIM_PURCHASE
                    && receipt.actorId().equals(playerId)
                    && receipt.playerId().equals(playerId)
                    && receipt.claim().equals(Optional.of(key))
                    ? result(Status.DUPLICATE_TRANSACTION, Optional.of(key), receipt.amount(), balance,
                            transactionId, false)
                    : denied(state, playerId, Optional.of(key), playerPosition, Status.TRANSACTION_ID_CONFLICT,
                            "transaction_id_conflict", 0, balance, timestampEpochMillis, transactionId);
        }
        if (state.hasEconomyTransaction(transactionId, timestampEpochMillis)) {
            long balance = state.economyBalance(playerId).orElse(0L);
            return denied(state, playerId, Optional.of(key), playerPosition, Status.TRANSACTION_ID_CONFLICT,
                    "transaction_id_conflict", 0, balance, timestampEpochMillis, transactionId);
        }
        PurchaseEvaluation evaluation = evaluatePurchase(
                state, playerId, hubDimension, playerDimension, playerPosition,
                isEligible, isProtected, basePrice, priceIncrease, ownershipCap);
        if (!evaluation.allowed()) {
            return denied(state, playerId, evaluation.claim(), playerPosition, evaluation.status(),
                    evaluation.status().id(), evaluation.price(), evaluation.balance(),
                    timestampEpochMillis, transactionId);
        }
        long beforeBalance = evaluation.balance();
        long purchasePrice = evaluation.price();
        if (!state.canCommitEconomyTransaction(transactionId, timestampEpochMillis)) {
            return denied(state, playerId, Optional.of(key), playerPosition, Status.TRANSACTION_LEDGER_FULL,
                    "transaction_ledger_full", purchasePrice, beforeBalance, timestampEpochMillis, transactionId);
        }

        long afterBalance = Math.subtractExact(beforeBalance, purchasePrice);
        EconomyTransactionReceipt receipt = new EconomyTransactionReceipt(
                timestampEpochMillis, playerId, playerId, EconomyTransactionReceipt.Kind.CLAIM_PURCHASE,
                purchasePrice, Optional.of(key), Optional.empty(), Optional.empty(), Optional.empty(), 0,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                EconomyTransactionReceipt.CompensationDecision.NONE);
        List<EconomyAlert> alerts = EconomyMonitoringService.evaluate(
                state, transactionId, receipt, EconomyConfig.alertThresholds());
        state.commitClaimPurchase(
                playerId, afterBalance, key, new Claim(playerId, purchasePrice), transactionId, timestampEpochMillis,
                receipt, alerts, auditEntry(
                        timestampEpochMillis, playerId, PURCHASE, key, playerPosition,
                        "unowned;balance=" + beforeBalance,
                        "owner=" + playerId + ";balance=" + afterBalance,
                        "claim_purchase", transactionId));
        EconomyMonitoringService.publish(alerts);
        return result(Status.SUCCESS, Optional.of(key), purchasePrice, afterBalance, transactionId, true);
    }

    public static PurchaseEvaluation evaluatePurchase(
            PlatformSavedData state,
            UUID playerId,
            ResourceKey<Level> hubDimension,
            ResourceKey<Level> playerDimension,
            BlockPos playerPosition,
            Predicate<ClaimKey> isEligible,
            Predicate<ClaimKey> isProtected,
            long basePrice,
            long priceIncrease,
            int ownershipCap) {
        if (state == null || playerId == null || hubDimension == null || playerDimension == null
                || playerPosition == null || isEligible == null || isProtected == null) {
            return evaluation(Status.INVALID_REQUEST, Optional.empty(), Optional.empty(), 0, 0, 0, ownershipCap);
        }

        ClaimKey key = ClaimKey.at(playerDimension, playerPosition);
        int ownedClaims = state.claimCount(playerId);
        long balance = state.economyBalance(playerId).orElse(0L);
        if (!state.isWritable()) {
            return evaluation(Status.READ_ONLY_SCHEMA, Optional.of(key),
                    state.claim(key).map(Claim::ownerId), 0, balance, ownedClaims, ownershipCap);
        }
        if (!validConfiguration(basePrice, priceIncrease, ownershipCap)) {
            return evaluation(Status.INVALID_CONFIGURATION, Optional.of(key), Optional.empty(),
                    0, balance, ownedClaims, ownershipCap);
        }
        Optional<Long> price = calculatePrice(basePrice, priceIncrease, ownedClaims);
        long displayedPrice = price.orElse(0L);
        if (!WorldTopology.HUB.equals(hubDimension) || !WorldTopology.allowsClaims(playerDimension)) {
            return evaluation(Status.NOT_IN_HUB, Optional.of(key), Optional.empty(),
                    displayedPrice, balance, ownedClaims, ownershipCap);
        }
        if (isProtected.test(key)) {
            return evaluation(Status.PROTECTED_CHUNK, Optional.of(key), Optional.empty(),
                    displayedPrice, balance, ownedClaims, ownershipCap);
        }
        if (!isEligible.test(key)) {
            return evaluation(Status.INELIGIBLE_CHUNK, Optional.of(key), Optional.empty(),
                    displayedPrice, balance, ownedClaims, ownershipCap);
        }
        Optional<Claim> retained = state.claim(key);
        if (retained.isPresent()) {
            return evaluation(Status.ALREADY_CLAIMED, Optional.of(key),
                    retained.map(Claim::ownerId), displayedPrice, balance, ownedClaims, ownershipCap);
        }
        if (ownedClaims >= ownershipCap || state.claimCount() >= Claim.MAX_CLAIMS) {
            return evaluation(Status.OWNERSHIP_CAP_REACHED, Optional.of(key), Optional.empty(),
                    displayedPrice, balance, ownedClaims, ownershipCap);
        }
        if (price.isEmpty()) {
            return evaluation(Status.PRICE_OVERFLOW, Optional.of(key), Optional.empty(),
                    0, balance, ownedClaims, ownershipCap);
        }
        if (state.economyBalance(playerId).isEmpty()) {
            return evaluation(Status.ACCOUNT_NOT_FOUND, Optional.of(key), Optional.empty(),
                    displayedPrice, 0, ownedClaims, ownershipCap);
        }
        if (displayedPrice > balance) {
            return evaluation(Status.INSUFFICIENT_FUNDS, Optional.of(key), Optional.empty(),
                    displayedPrice, balance, ownedClaims, ownershipCap);
        }
        return evaluation(Status.SUCCESS, Optional.of(key), Optional.empty(),
                displayedPrice, balance, ownedClaims, ownershipCap);
    }

    static Optional<Long> calculatePrice(long basePrice, long priceIncrease, int ownedClaims) {
        if (basePrice < 1 || priceIncrease < 0 || ownedClaims < 0) {
            return Optional.empty();
        }
        try {
            return Optional.of(Math.addExact(basePrice, Math.multiplyExact(priceIncrease, (long) ownedClaims)));
        } catch (ArithmeticException exception) {
            return Optional.empty();
        }
    }

    private static boolean validConfiguration(long basePrice, long priceIncrease, int ownershipCap) {
        return basePrice >= 1 && priceIncrease >= 0 && ownershipCap >= 1 && ownershipCap <= Claim.MAX_CLAIMS;
    }

    private static PurchaseResult denied(
            PlatformSavedData state,
            UUID playerId,
            Optional<ClaimKey> key,
            BlockPos position,
            Status status,
            String reason,
            long price,
            long balance,
            long timestampEpochMillis,
            UUID transactionId) {
        UUID auditId = validTransactionId(transactionId) ? transactionId : UUID.randomUUID();
        boolean audited = state.appendDeniedAudit(auditEntry(
                timestampEpochMillis, playerId, PURCHASE_DENIED, key.orElse(null), position,
                "balance=" + balance, "balance=" + balance, reason, auditId), DENIED_AUDIT_INTERVAL_MILLIS);
        return result(status, key, price, balance, transactionId, audited);
    }

    private static AuditEntry auditEntry(
            long timestampEpochMillis,
            UUID actorId,
            Identifier action,
            ClaimKey key,
            BlockPos position,
            String before,
            String after,
            String reason,
            UUID transactionId) {
        return new AuditEntry(
                timestampEpochMillis,
                actorId,
                action,
                key == null ? actorId.toString() : key.auditTarget(),
                key == null ? Optional.empty() : Optional.of(key.dimension().identifier()),
                Optional.ofNullable(position),
                before,
                after,
                reason,
                transactionId);
    }

    private static boolean validTransactionId(UUID transactionId) {
        return transactionId != null && !ZERO_UUID.equals(transactionId);
    }

    private static Identifier action(String path) {
        return Identifier.fromNamespaceAndPath(Rovenfall.MOD_ID, path);
    }

    private static PurchaseResult result(
            Status status,
            Optional<ClaimKey> claim,
            long price,
            long balance,
            UUID transactionId,
            boolean auditRecorded) {
        return new PurchaseResult(status, claim, price, balance, transactionId, auditRecorded);
    }

    private static PurchaseEvaluation evaluation(
            Status status,
            Optional<ClaimKey> claim,
            Optional<UUID> ownerId,
            long price,
            long balance,
            int ownedClaims,
            int ownershipCap) {
        return new PurchaseEvaluation(status, claim, ownerId, price, balance, ownedClaims, ownershipCap);
    }

    public enum Status {
        SUCCESS,
        DUPLICATE_TRANSACTION,
        TRANSACTION_ID_CONFLICT,
        INVALID_REQUEST,
        INVALID_TRANSACTION,
        READ_ONLY_SCHEMA,
        NOT_IN_HUB,
        INELIGIBLE_CHUNK,
        PROTECTED_CHUNK,
        ALREADY_CLAIMED,
        OWNERSHIP_CAP_REACHED,
        ACCOUNT_NOT_FOUND,
        INVALID_CONFIGURATION,
        PRICE_OVERFLOW,
        INSUFFICIENT_FUNDS,
        TRANSACTION_LEDGER_FULL;

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        public String evaluationTranslationKey() {
            return "claim_purchase_evaluation.rovenfall." + id();
        }
    }

    public record PurchaseEvaluation(
            Status status,
            Optional<ClaimKey> claim,
            Optional<UUID> ownerId,
            long price,
            long balance,
            int ownedClaims,
            int ownershipCap) {
        public PurchaseEvaluation {
            claim = claim == null ? Optional.empty() : claim;
            ownerId = ownerId == null ? Optional.empty() : ownerId;
        }

        public boolean allowed() {
            return status == Status.SUCCESS;
        }
    }

    public record PurchaseResult(
            Status status,
            Optional<ClaimKey> claim,
            long price,
            long balance,
            UUID transactionId,
            boolean auditRecorded) {
    }
}
