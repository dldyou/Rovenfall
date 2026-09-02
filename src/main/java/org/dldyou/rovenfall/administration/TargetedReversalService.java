package org.dldyou.rovenfall.administration;

import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.Identifier;
import org.dldyou.rovenfall.Rovenfall;
import org.dldyou.rovenfall.claims.Claim;
import org.dldyou.rovenfall.careers.PlayerCareerState;
import org.dldyou.rovenfall.economy.ShopInstance;

/** Conservative inverse boundary for claim, shop, career, and skill mutations. */
public final class TargetedReversalService {
    private static final UUID ZERO_UUID = new UUID(0L, 0L);
    private static final long DENIED_AUDIT_INTERVAL_MILLIS = 1_000L;

    private TargetedReversalService() {
    }

    static Result reverse(
            PlatformSavedData state,
            UUID actorId,
            boolean authorizationOverride,
            UUID originalTransactionId,
            String reason,
            long timestampEpochMillis,
            UUID reversalTransactionId) {
        if (state == null) {
            return result(Status.INVALID_REQUEST, Optional.empty(), reversalTransactionId, false);
        }
        if (!state.isWritable()) {
            return result(Status.READ_ONLY_SCHEMA, Optional.empty(), reversalTransactionId, false);
        }
        if (actorId == null || originalTransactionId == null || timestampEpochMillis < 0) {
            return result(Status.INVALID_REQUEST, Optional.empty(), reversalTransactionId, false);
        }
        if (!(authorizationOverride || state.hasAdminRole(actorId))) {
            return denied(state, actorId, originalTransactionId, Optional.empty(), reversalTransactionId,
                    Status.UNAUTHORIZED, "unauthorized", timestampEpochMillis);
        }
        if (!validTransactionId(reversalTransactionId)) {
            return denied(state, actorId, originalTransactionId, Optional.empty(), reversalTransactionId,
                    Status.INVALID_TRANSACTION, "invalid_transaction", timestampEpochMillis);
        }
        Optional<String> validReason = validReason(reason);
        if (validReason.isEmpty()) {
            return denied(state, actorId, originalTransactionId, Optional.empty(), reversalTransactionId,
                    Status.INVALID_REASON, "invalid_reason", timestampEpochMillis);
        }

        Optional<TargetedReversalState.ClaimEvidence> claim = state.claimReversalEvidence(originalTransactionId);
        if (claim.isPresent()) {
            return reverseClaim(
                    state, actorId, authorizationOverride, originalTransactionId, reversalTransactionId,
                    timestampEpochMillis, validReason.orElseThrow(), claim.orElseThrow());
        }
        Optional<TargetedReversalState.ShopEvidence> shop = state.shopReversalEvidence(originalTransactionId);
        if (shop.isPresent()) {
            return reverseShop(
                    state, actorId, authorizationOverride, originalTransactionId, reversalTransactionId,
                    timestampEpochMillis, validReason.orElseThrow(), shop.orElseThrow());
        }
        Optional<TargetedReversalState.CareerEvidence> career = state.careerReversalEvidence(originalTransactionId);
        if (career.isPresent()) {
            return reverseCareer(
                    state, actorId, authorizationOverride, originalTransactionId, reversalTransactionId,
                    timestampEpochMillis, validReason.orElseThrow(), career.orElseThrow());
        }
        return denied(state, actorId, originalTransactionId, Optional.empty(), reversalTransactionId,
                Status.ORIGINAL_NOT_REVERSIBLE, "original_not_reversible", timestampEpochMillis);
    }

    private static Result reverseClaim(
            PlatformSavedData state,
            UUID actorId,
            boolean authorizationOverride,
            UUID originalTransactionId,
            UUID reversalTransactionId,
            long timestampEpochMillis,
            String reason,
            TargetedReversalState.ClaimEvidence evidence) {
        TargetedReversalState.Domain domain = evidence.domain();
        Optional<TargetedReversalState.Domain> retainedDomain = Optional.of(domain);
        if (!canManage(state, actorId, authorizationOverride, domain, evidence.balancePlayerId().isPresent())) {
            return denied(state, actorId, originalTransactionId, retainedDomain, reversalTransactionId,
                    Status.UNAUTHORIZED, "unauthorized", timestampEpochMillis);
        }
        Result precheck = precheck(
                state, actorId, originalTransactionId, retainedDomain,
                evidence.reversedBy(), reversalTransactionId, timestampEpochMillis);
        if (precheck != null) {
            return precheck;
        }
        Optional<Claim> currentClaim = state.claim(evidence.claimKey());
        Optional<Long> currentBalance = evidence.balancePlayerId().flatMap(state::economyBalance);
        if (!currentClaim.equals(evidence.after()) || !currentBalance.equals(evidence.balanceAfter())) {
            return denied(state, actorId, originalTransactionId, retainedDomain, reversalTransactionId,
                    Status.CURRENT_STATE_MISMATCH, "current_state_mismatch", timestampEpochMillis);
        }
        state.commitTargetedClaimReversal(
                originalTransactionId,
                reversalTransactionId,
                timestampEpochMillis,
                evidence,
                new AuditEntry(
                        timestampEpochMillis,
                        actorId,
                        action("targeted_transaction_reversal"),
                        "transaction:" + originalTransactionId,
                        Optional.of(evidence.claimKey().dimension().identifier()),
                        Optional.of(evidence.claimKey().auditPosition()),
                        summary(domain, "after", evidence.balanceAfter()),
                        summary(domain, "before", evidence.balanceBefore()),
                        reason,
                        reversalTransactionId));
        return result(Status.SUCCESS, retainedDomain, reversalTransactionId, true);
    }

    private static Result reverseShop(
            PlatformSavedData state,
            UUID actorId,
            boolean authorizationOverride,
            UUID originalTransactionId,
            UUID reversalTransactionId,
            long timestampEpochMillis,
            String reason,
            TargetedReversalState.ShopEvidence evidence) {
        TargetedReversalState.Domain domain = TargetedReversalState.Domain.SHOP;
        Optional<TargetedReversalState.Domain> retainedDomain = Optional.of(domain);
        if (!canManage(state, actorId, authorizationOverride, domain, false)) {
            return denied(state, actorId, originalTransactionId, retainedDomain, reversalTransactionId,
                    Status.UNAUTHORIZED, "unauthorized", timestampEpochMillis);
        }
        Result precheck = precheck(
                state, actorId, originalTransactionId, retainedDomain,
                evidence.reversedBy(), reversalTransactionId, timestampEpochMillis);
        if (precheck != null) {
            return precheck;
        }
        Optional<ShopInstance> currentShop = state.shopInstance(evidence.shopId());
        if (!currentShop.equals(evidence.after())) {
            return denied(state, actorId, originalTransactionId, retainedDomain, reversalTransactionId,
                    Status.CURRENT_STATE_MISMATCH, "current_state_mismatch", timestampEpochMillis);
        }

        Optional<ShopInstanceService.DependencyLease> lease = currentShop.isPresent()
                ? ShopInstanceService.tryAcquireDependencyLock(state, evidence.shopId())
                : Optional.empty();
        if (currentShop.isPresent() && lease.isEmpty() || currentShop.isEmpty() && state.isShopLocked(evidence.shopId())) {
            return denied(state, actorId, originalTransactionId, retainedDomain, reversalTransactionId,
                    Status.DEPENDENCY_LOCKED, "dependency_locked", timestampEpochMillis);
        }
        try (var ignored = lease.orElse(null)) {
            if (!state.shopInstance(evidence.shopId()).equals(evidence.after())) {
                return denied(state, actorId, originalTransactionId, retainedDomain, reversalTransactionId,
                        Status.CURRENT_STATE_MISMATCH, "current_state_mismatch", timestampEpochMillis);
            }
            Optional<ShopInstance.Binding> binding = evidence.after().flatMap(ShopInstance::binding)
                    .or(() -> evidence.before().flatMap(ShopInstance::binding));
            state.commitTargetedShopReversal(
                    originalTransactionId,
                    reversalTransactionId,
                    timestampEpochMillis,
                    evidence,
                    new AuditEntry(
                            timestampEpochMillis,
                            actorId,
                            action("targeted_transaction_reversal"),
                            "transaction:" + originalTransactionId,
                            binding.map(value -> value.dimension().identifier()),
                            binding.map(ShopInstance.Binding::position),
                            summary(domain, "after", Optional.empty()),
                            summary(domain, "before", Optional.empty()),
                            reason,
                            reversalTransactionId));
        }
        return result(Status.SUCCESS, retainedDomain, reversalTransactionId, true);
    }

    private static Result reverseCareer(
            PlatformSavedData state,
            UUID actorId,
            boolean authorizationOverride,
            UUID originalTransactionId,
            UUID reversalTransactionId,
            long timestampEpochMillis,
            String reason,
            TargetedReversalState.CareerEvidence evidence) {
        TargetedReversalState.Domain domain = evidence.domain();
        Optional<TargetedReversalState.Domain> retainedDomain = Optional.of(domain);
        if (!canManage(state, actorId, authorizationOverride, domain, evidence.balanceAfter().isPresent())) {
            return denied(state, actorId, originalTransactionId, retainedDomain, reversalTransactionId,
                    Status.UNAUTHORIZED, "unauthorized", timestampEpochMillis);
        }
        Result precheck = precheck(
                state, actorId, originalTransactionId, retainedDomain,
                evidence.reversedBy(), reversalTransactionId, timestampEpochMillis);
        if (precheck != null) {
            return precheck;
        }
        Optional<PlayerCareerState> currentCareer = Optional.ofNullable(
                state.playerCareersView().get(evidence.playerId()));
        Optional<Long> currentBalance = state.economyBalance(evidence.playerId());
        if (!currentCareer.equals(evidence.after())
                || evidence.balanceAfter().isPresent() && !currentBalance.equals(evidence.balanceAfter())) {
            return denied(state, actorId, originalTransactionId, retainedDomain, reversalTransactionId,
                    Status.CURRENT_STATE_MISMATCH, "current_state_mismatch", timestampEpochMillis);
        }
        state.commitTargetedCareerReversal(
                originalTransactionId,
                reversalTransactionId,
                timestampEpochMillis,
                evidence,
                new AuditEntry(
                        timestampEpochMillis,
                        actorId,
                        action("targeted_transaction_reversal"),
                        "transaction:" + originalTransactionId,
                        Optional.empty(),
                        Optional.empty(),
                        summary(domain, "after", evidence.balanceAfter()),
                        summary(domain, "before", evidence.balanceBefore()),
                        reason,
                        reversalTransactionId));
        return result(Status.SUCCESS, retainedDomain, reversalTransactionId, true);
    }

    private static Result precheck(
            PlatformSavedData state,
            UUID actorId,
            UUID originalTransactionId,
            Optional<TargetedReversalState.Domain> domain,
            Optional<UUID> reversedBy,
            UUID reversalTransactionId,
            long timestampEpochMillis) {
        if (reversedBy.equals(Optional.of(reversalTransactionId))) {
            return result(Status.DUPLICATE_TRANSACTION, domain, reversalTransactionId, false);
        }
        if (reversedBy.isPresent()) {
            return denied(state, actorId, originalTransactionId, domain, reversalTransactionId,
                    Status.ALREADY_REVERSED, "already_reversed", timestampEpochMillis);
        }
        if (state.hasTransaction(reversalTransactionId, timestampEpochMillis)) {
            return denied(state, actorId, originalTransactionId, domain, reversalTransactionId,
                    Status.TRANSACTION_ID_CONFLICT, "transaction_id_conflict", timestampEpochMillis);
        }
        if (!state.hasTransaction(originalTransactionId, timestampEpochMillis)) {
            return denied(state, actorId, originalTransactionId, domain, reversalTransactionId,
                    Status.ORIGINAL_NOT_REVERSIBLE, "original_not_reversible", timestampEpochMillis);
        }
        if (!state.canCommitTransaction(reversalTransactionId, timestampEpochMillis)) {
            return denied(state, actorId, originalTransactionId, domain, reversalTransactionId,
                    Status.TRANSACTION_LEDGER_FULL, "transaction_ledger_full", timestampEpochMillis);
        }
        return null;
    }

    private static boolean canManage(
            PlatformSavedData state,
            UUID actorId,
            boolean authorizationOverride,
            TargetedReversalState.Domain domain,
            boolean changesBalance) {
        if (authorizationOverride) {
            return true;
        }
        AdminRole role = state.roleOf(actorId).orElse(null);
        if (role == AdminRole.OWNER) {
            return true;
        }
        return switch (domain) {
            case CLAIM_PERMISSION -> role == AdminRole.MODERATOR;
            case CLAIM -> !changesBalance && role == AdminRole.MODERATOR;
            case SHOP -> role == AdminRole.ECONOMY_MANAGER;
            case CAREER, SKILL -> role == AdminRole.CONTENT_MANAGER;
        };
    }

    private static Result denied(
            PlatformSavedData state,
            UUID actorId,
            UUID originalTransactionId,
            Optional<TargetedReversalState.Domain> domain,
            UUID reversalTransactionId,
            Status status,
            String reason,
            long timestampEpochMillis) {
        if (state == null || actorId == null || originalTransactionId == null || timestampEpochMillis < 0) {
            return result(status, domain, reversalTransactionId, false);
        }
        UUID auditId = validTransactionId(reversalTransactionId) ? reversalTransactionId : UUID.randomUUID();
        boolean audited = state.appendDeniedAudit(new AuditEntry(
                timestampEpochMillis,
                actorId,
                action("targeted_transaction_reversal_denied"),
                "transaction:" + originalTransactionId,
                Optional.empty(),
                Optional.empty(),
                "unchanged",
                "unchanged",
                reason,
                auditId), DENIED_AUDIT_INTERVAL_MILLIS);
        return result(status, domain, reversalTransactionId, audited);
    }

    private static Optional<String> validReason(String reason) {
        String normalized = reason == null ? "" : reason.strip();
        return normalized.isEmpty() || normalized.length() > AdministrationService.MAX_REASON_LENGTH
                ? Optional.empty()
                : Optional.of(normalized);
    }

    private static boolean validTransactionId(UUID id) {
        return id != null && !ZERO_UUID.equals(id);
    }

    private static String summary(
            TargetedReversalState.Domain domain,
            String state,
            Optional<Long> balance) {
        return "domain=" + domain.getSerializedName() + ";state=" + state
                + balance.map(value -> ";balance=" + value).orElse("");
    }

    private static Identifier action(String path) {
        return Identifier.fromNamespaceAndPath(Rovenfall.MOD_ID, path);
    }

    private static Result result(
            Status status,
            Optional<TargetedReversalState.Domain> domain,
            UUID transactionId,
            boolean auditRecorded) {
        return new Result(status, domain == null ? Optional.empty() : domain, transactionId, auditRecorded);
    }

    public enum Status {
        SUCCESS,
        DUPLICATE_TRANSACTION,
        TRANSACTION_ID_CONFLICT,
        ALREADY_REVERSED,
        UNAUTHORIZED,
        INVALID_REQUEST,
        INVALID_TRANSACTION,
        INVALID_REASON,
        READ_ONLY_SCHEMA,
        TRANSACTION_LEDGER_FULL,
        DEPENDENCY_LOCKED,
        ORIGINAL_NOT_REVERSIBLE,
        CURRENT_STATE_MISMATCH
    }

    public record Result(
            Status status,
            Optional<TargetedReversalState.Domain> domain,
            UUID transactionId,
            boolean auditRecorded) {
        public Result {
            domain = domain == null ? Optional.empty() : domain;
        }
    }
}
