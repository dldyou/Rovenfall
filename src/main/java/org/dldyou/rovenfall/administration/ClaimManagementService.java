package org.dldyou.rovenfall.administration;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.resources.Identifier;
import org.dldyou.rovenfall.Rovenfall;
import org.dldyou.rovenfall.claims.Claim;
import org.dldyou.rovenfall.claims.ClaimKey;
import org.dldyou.rovenfall.claims.ClaimMutationReceipt;
import org.dldyou.rovenfall.claims.ClaimRole;
import org.dldyou.rovenfall.claims.ClaimSettings;

public final class ClaimManagementService {
    private static final UUID ZERO_UUID = new UUID(0L, 0L);
    private static final long DENIED_AUDIT_INTERVAL_MILLIS = 1_000L;

    private ClaimManagementService() {
    }

    public static Result setRole(
            PlatformSavedData state,
            UUID actorId,
            boolean authorizationOverride,
            ClaimKey key,
            UUID targetId,
            ClaimRole role,
            String reason,
            long timestampEpochMillis,
            UUID transactionId) {
        String payload = targetId == null || role == null
                ? "invalid"
                : "player=" + targetId + ";role=" + role.getSerializedName();
        Result rejected = precheck(
                state, actorId, key, reason, timestampEpochMillis, transactionId,
                ClaimMutationReceipt.Kind.ROLE_SET, payload);
        if (rejected != null) {
            return rejected;
        }
        RoleEvaluation evaluation = evaluateSetRole(
                state, actorId, authorizationOverride, key, targetId, role);
        if (!evaluation.allowed()) {
            return denied(state, actorId, key, evaluation.status(), evaluation.status().id(),
                    payload, timestampEpochMillis, transactionId);
        }
        Claim claim = evaluation.claim().orElseThrow();
        if (!evaluation.wouldChange()) {
            return commitNoChange(
                    state, actorId, key, claim, reason, timestampEpochMillis, transactionId,
                    ClaimMutationReceipt.Kind.ROLE_SET, payload, "claim_role_no_change");
        }
        Claim updated = claim.withRole(targetId, role);
        return commitMutation(
                state, actorId, key, claim, updated, reason, timestampEpochMillis, transactionId,
                ClaimMutationReceipt.Kind.ROLE_SET, payload, "claim_role_set");
    }

    public static RoleEvaluation evaluateSetRole(
            PlatformSavedData state,
            UUID actorId,
            boolean authorizationOverride,
            ClaimKey key,
            UUID targetId,
            ClaimRole requestedRole) {
        if (state == null || actorId == null || key == null) {
            return roleEvaluation(Status.INVALID_REQUEST, Optional.empty(), ClaimRole.VISITOR,
                    ClaimRole.VISITOR, requestedRole, 0);
        }
        Optional<Claim> retained = state.claim(key);
        ClaimRole retainedActorRole = retained.map(claim -> claim.roleOf(actorId)).orElse(ClaimRole.VISITOR);
        ClaimRole retainedTargetRole = targetId == null
                ? ClaimRole.VISITOR
                : retained.map(claim -> claim.roleOf(targetId)).orElse(ClaimRole.VISITOR);
        if (!state.isWritable()) {
            return roleEvaluation(Status.READ_ONLY_SCHEMA, retained, retainedActorRole,
                    retainedTargetRole, requestedRole, retained.map(claim -> claim.trustedRoles().size()).orElse(0));
        }
        if (retained.isEmpty()) {
            return roleEvaluation(Status.CLAIM_NOT_FOUND, Optional.empty(), ClaimRole.VISITOR,
                    ClaimRole.VISITOR, requestedRole, 0);
        }
        Claim claim = retained.orElseThrow();
        ClaimRole actorRole = retainedActorRole;
        ClaimRole targetRole = retainedTargetRole;
        if (!canManage(state, claim, actorId, authorizationOverride)) {
            return roleEvaluation(Status.UNAUTHORIZED, retained, actorRole, targetRole,
                    requestedRole, claim.trustedRoles().size());
        }
        if (targetId == null || requestedRole == null || requestedRole == ClaimRole.OWNER
                || targetId.equals(claim.ownerId())) {
            return roleEvaluation(Status.INVALID_TARGET, retained, actorRole, targetRole,
                    requestedRole, claim.trustedRoles().size());
        }
        ClaimRole existing = claim.trustedRoles().get(targetId);
        if (existing == requestedRole) {
            return roleEvaluation(Status.NO_CHANGE, retained, actorRole, targetRole,
                    requestedRole, claim.trustedRoles().size());
        }
        if (existing == null && claim.trustedRoles().size() >= Claim.MAX_TRUSTED_PLAYERS) {
            return roleEvaluation(Status.TRUST_LIMIT_REACHED, retained, actorRole, targetRole,
                    requestedRole, claim.trustedRoles().size());
        }
        return roleEvaluation(Status.SUCCESS, retained, actorRole, targetRole,
                requestedRole, claim.trustedRoles().size());
    }

    public static Result removeRole(
            PlatformSavedData state,
            UUID actorId,
            boolean authorizationOverride,
            ClaimKey key,
            UUID targetId,
            String reason,
            long timestampEpochMillis,
            UUID transactionId) {
        String payload = targetId == null ? "invalid" : "player=" + targetId;
        Result rejected = precheck(
                state, actorId, key, reason, timestampEpochMillis, transactionId,
                ClaimMutationReceipt.Kind.ROLE_REMOVE, payload);
        if (rejected != null) {
            return rejected;
        }
        Claim claim = state.claim(key).orElse(null);
        if (claim == null) {
            return denied(state, actorId, key, Status.CLAIM_NOT_FOUND, "claim_not_found",
                    payload, timestampEpochMillis, transactionId);
        }
        if (!canManage(state, claim, actorId, authorizationOverride)) {
            return denied(state, actorId, key, Status.UNAUTHORIZED, "unauthorized",
                    payload, timestampEpochMillis, transactionId);
        }
        if (targetId == null || targetId.equals(claim.ownerId())) {
            return denied(state, actorId, key, Status.INVALID_TARGET, "invalid_target",
                    payload, timestampEpochMillis, transactionId);
        }
        if (!claim.trustedRoles().containsKey(targetId)) {
            return commitNoChange(
                    state, actorId, key, claim, reason, timestampEpochMillis, transactionId,
                    ClaimMutationReceipt.Kind.ROLE_REMOVE, payload, "claim_role_remove_no_change");
        }
        return commitMutation(
                state, actorId, key, claim, claim.withoutRole(targetId), reason, timestampEpochMillis, transactionId,
                ClaimMutationReceipt.Kind.ROLE_REMOVE, payload, "claim_role_remove");
    }

    public static Result setSettings(
            PlatformSavedData state,
            UUID actorId,
            boolean authorizationOverride,
            ClaimKey key,
            ClaimSettings settings,
            String reason,
            long timestampEpochMillis,
            UUID transactionId) {
        String payload = settings == null
                ? "invalid"
                : "entry_restricted=" + settings.entryRestricted()
                        + ";public_interactions=" + settings.publicInteractions();
        Result rejected = precheck(
                state, actorId, key, reason, timestampEpochMillis, transactionId,
                ClaimMutationReceipt.Kind.SETTINGS_SET, payload);
        if (rejected != null) {
            return rejected;
        }
        Claim claim = state.claim(key).orElse(null);
        if (claim == null) {
            return denied(state, actorId, key, Status.CLAIM_NOT_FOUND, "claim_not_found",
                    payload, timestampEpochMillis, transactionId);
        }
        if (!canManage(state, claim, actorId, authorizationOverride)) {
            return denied(state, actorId, key, Status.UNAUTHORIZED, "unauthorized",
                    payload, timestampEpochMillis, transactionId);
        }
        if (settings == null) {
            return denied(state, actorId, key, Status.INVALID_REQUEST, "invalid_settings",
                    payload, timestampEpochMillis, transactionId);
        }
        if (claim.settings().equals(settings)) {
            return commitNoChange(
                    state, actorId, key, claim, reason, timestampEpochMillis, transactionId,
                    ClaimMutationReceipt.Kind.SETTINGS_SET, payload, "claim_settings_no_change");
        }
        return commitMutation(
                state, actorId, key, claim, claim.withSettings(settings), reason, timestampEpochMillis, transactionId,
                ClaimMutationReceipt.Kind.SETTINGS_SET, payload, "claim_settings_set");
    }

    /** Moderator-or-owner administrative removal without an economy refund. */
    public static Result reclaim(
            PlatformSavedData state,
            UUID actorId,
            boolean authorizationOverride,
            ClaimKey key,
            String reason,
            long timestampEpochMillis,
            UUID transactionId) {
        String payload = "reclaim=admin";
        Result rejected = precheck(
                state, actorId, key, reason, timestampEpochMillis, transactionId,
                ClaimMutationReceipt.Kind.RECLAIM, payload);
        if (rejected != null) {
            return rejected;
        }
        Claim claim = state.claim(key).orElse(null);
        if (claim == null) {
            return denied(state, actorId, key, Status.CLAIM_NOT_FOUND, "claim_not_found",
                    payload, timestampEpochMillis, transactionId);
        }
        AdminRole role = state.roleOf(actorId).orElse(null);
        if (!authorizationOverride && role != AdminRole.MODERATOR && role != AdminRole.OWNER) {
            return denied(state, actorId, key, Status.UNAUTHORIZED, "owner_required",
                    payload, timestampEpochMillis, transactionId);
        }
        ClaimMutationReceipt receipt = new ClaimMutationReceipt(
                timestampEpochMillis, actorId, key, ClaimMutationReceipt.Kind.RECLAIM, payload);
        state.commitClaimReclaim(key, claim, transactionId, timestampEpochMillis, receipt,
                auditEntry(timestampEpochMillis, actorId, action("claim_reclaim"), key,
                        summary(claim) + ";operation=" + payload,
                        "unowned;operation=" + payload,
                        validReason(reason).orElseThrow(), transactionId));
        return result(Status.SUCCESS, transactionId, 0, state.economyBalance(actorId).orElse(0L), true);
    }

    public static Result offerTransfer(
            PlatformSavedData state,
            UUID actorId,
            ClaimKey key,
            UUID recipientId,
            String reason,
            long timestampEpochMillis,
            UUID transactionId) {
        String payload = recipientId == null ? "invalid" : "recipient=" + recipientId;
        Result rejected = precheck(
                state, actorId, key, reason, timestampEpochMillis, transactionId,
                ClaimMutationReceipt.Kind.TRANSFER_OFFER, payload);
        if (rejected != null) {
            return rejected;
        }
        Claim claim = state.claim(key).orElse(null);
        if (claim == null) {
            return denied(state, actorId, key, Status.CLAIM_NOT_FOUND, "claim_not_found",
                    payload, timestampEpochMillis, transactionId);
        }
        if (!claim.ownerId().equals(actorId)) {
            return denied(state, actorId, key, Status.UNAUTHORIZED, "owner_required",
                    payload, timestampEpochMillis, transactionId);
        }
        if (recipientId == null || recipientId.equals(actorId)) {
            return denied(state, actorId, key, Status.INVALID_TARGET, "invalid_target",
                    payload, timestampEpochMillis, transactionId);
        }
        if (claim.pendingTransferTo().equals(Optional.of(recipientId))) {
            return commitNoChange(
                    state, actorId, key, claim, reason, timestampEpochMillis, transactionId,
                    ClaimMutationReceipt.Kind.TRANSFER_OFFER, payload, "claim_transfer_offer_no_change");
        }
        return commitMutation(
                state, actorId, key, claim, claim.withPendingTransfer(recipientId), reason,
                timestampEpochMillis, transactionId, ClaimMutationReceipt.Kind.TRANSFER_OFFER,
                payload, "claim_transfer_offer");
    }

    public static Result cancelTransfer(
            PlatformSavedData state,
            UUID actorId,
            ClaimKey key,
            String reason,
            long timestampEpochMillis,
            UUID transactionId) {
        String payload = "owner=" + actorId;
        Result rejected = precheck(
                state, actorId, key, reason, timestampEpochMillis, transactionId,
                ClaimMutationReceipt.Kind.TRANSFER_CANCEL, payload);
        if (rejected != null) {
            return rejected;
        }
        Claim claim = state.claim(key).orElse(null);
        if (claim == null) {
            return denied(state, actorId, key, Status.CLAIM_NOT_FOUND, "claim_not_found",
                    payload, timestampEpochMillis, transactionId);
        }
        if (!claim.ownerId().equals(actorId)) {
            return denied(state, actorId, key, Status.UNAUTHORIZED, "owner_required",
                    payload, timestampEpochMillis, transactionId);
        }
        if (claim.pendingTransferTo().isEmpty()) {
            return commitNoChange(
                    state, actorId, key, claim, reason, timestampEpochMillis, transactionId,
                    ClaimMutationReceipt.Kind.TRANSFER_CANCEL, payload, "claim_transfer_cancel_no_change");
        }
        return commitMutation(
                state, actorId, key, claim, claim.withoutPendingTransfer(), reason,
                timestampEpochMillis, transactionId, ClaimMutationReceipt.Kind.TRANSFER_CANCEL,
                payload, "claim_transfer_cancel");
    }

    public static Result acceptTransfer(
            PlatformSavedData state,
            UUID recipientId,
            ClaimKey key,
            Predicate<ClaimKey> isProtected,
            int ownershipCap,
            String reason,
            long timestampEpochMillis,
            UUID transactionId) {
        String payload = "recipient=" + recipientId;
        Result rejected = precheck(
                state, recipientId, key, reason, timestampEpochMillis, transactionId,
                ClaimMutationReceipt.Kind.TRANSFER_ACCEPT, payload);
        if (rejected != null) {
            return rejected;
        }
        Claim claim = state.claim(key).orElse(null);
        if (claim == null) {
            return denied(state, recipientId, key, Status.CLAIM_NOT_FOUND, "claim_not_found",
                    payload, timestampEpochMillis, transactionId);
        }
        if (recipientId == null || isProtected == null || ownershipCap < 1 || ownershipCap > Claim.MAX_CLAIMS) {
            return denied(state, recipientId, key, Status.INVALID_REQUEST, "invalid_transfer",
                    payload, timestampEpochMillis, transactionId);
        }
        if (!claim.pendingTransferTo().equals(Optional.of(recipientId))) {
            return denied(state, recipientId, key, Status.TRANSFER_NOT_PENDING, "transfer_not_pending",
                    payload, timestampEpochMillis, transactionId);
        }
        if (isProtected.test(key)) {
            return denied(state, recipientId, key, Status.PROTECTED_CHUNK, "protected_chunk",
                    payload, timestampEpochMillis, transactionId);
        }
        if (state.claimCount(recipientId) >= ownershipCap) {
            return denied(state, recipientId, key, Status.OWNERSHIP_CAP_REACHED, "ownership_cap_reached",
                    payload, timestampEpochMillis, transactionId);
        }
        return commitMutation(
                state, recipientId, key, claim, claim.transferredTo(recipientId), reason,
                timestampEpochMillis, transactionId, ClaimMutationReceipt.Kind.TRANSFER_ACCEPT,
                payload, "claim_transfer_accept");
    }

    public static Result sell(
            PlatformSavedData state,
            UUID ownerId,
            ClaimKey key,
            int refundPercent,
            long maximumBalance,
            String reason,
            long timestampEpochMillis,
            UUID transactionId) {
        if (!state.isWritable()) {
            return result(Status.READ_ONLY_SCHEMA, transactionId, 0, 0, false);
        }
        if (ownerId == null || key == null || timestampEpochMillis < 0) {
            return result(Status.INVALID_REQUEST, transactionId, 0, 0, false);
        }
        if (!validTransactionId(transactionId)) {
            return denied(state, ownerId, key, Status.INVALID_TRANSACTION, "invalid_transaction",
                    timestampEpochMillis, transactionId);
        }
        Optional<EconomyTransactionReceipt> retained = state.economyReceipt(transactionId);
        if (retained.isPresent()) {
            EconomyTransactionReceipt receipt = retained.orElseThrow();
            long balance = state.economyBalance(ownerId).orElse(0L);
            return receipt.kind() == EconomyTransactionReceipt.Kind.CLAIM_SALE
                    && receipt.actorId().equals(ownerId)
                    && receipt.playerId().equals(ownerId)
                    && receipt.claim().equals(Optional.of(key))
                    ? result(Status.DUPLICATE_TRANSACTION, transactionId, receipt.amount(), balance, false)
                    : denied(state, ownerId, key, Status.TRANSACTION_ID_CONFLICT, "transaction_id_conflict",
                            timestampEpochMillis, transactionId);
        }
        if (state.claimReceipt(transactionId).isPresent()
                || state.hasTransaction(transactionId, timestampEpochMillis)) {
            return denied(state, ownerId, key, Status.TRANSACTION_ID_CONFLICT, "transaction_id_conflict",
                    timestampEpochMillis, transactionId);
        }
        Optional<String> validReason = validReason(reason);
        Claim claim = state.claim(key).orElse(null);
        if (validReason.isEmpty() || refundPercent < 0 || refundPercent > 100 || maximumBalance < 0) {
            return denied(state, ownerId, key, Status.INVALID_REQUEST, "invalid_sale", timestampEpochMillis,
                    transactionId);
        }
        if (claim == null) {
            return denied(state, ownerId, key, Status.CLAIM_NOT_FOUND, "claim_not_found", timestampEpochMillis,
                    transactionId);
        }
        if (!claim.ownerId().equals(ownerId)) {
            return denied(state, ownerId, key, Status.UNAUTHORIZED, "owner_required", timestampEpochMillis,
                    transactionId);
        }
        if (claim.pendingTransferTo().isPresent()) {
            return denied(state, ownerId, key, Status.TRANSFER_PENDING, "transfer_pending", timestampEpochMillis,
                    transactionId);
        }
        if (claim.purchasePrice() < 1) {
            return denied(state, ownerId, key, Status.PURCHASE_PRICE_UNAVAILABLE, "purchase_price_unavailable",
                    timestampEpochMillis, transactionId);
        }
        long beforeBalance = state.economyBalance(ownerId).orElse(-1L);
        if (beforeBalance < 0) {
            return denied(state, ownerId, key, Status.ACCOUNT_NOT_FOUND, "account_not_found", timestampEpochMillis,
                    transactionId);
        }
        long refund = refund(claim.purchasePrice(), refundPercent);
        long afterBalance;
        try {
            afterBalance = Math.addExact(beforeBalance, refund);
        } catch (ArithmeticException exception) {
            return denied(state, ownerId, key, Status.OVERFLOW, "overflow", timestampEpochMillis, transactionId);
        }
        if (afterBalance > maximumBalance) {
            return denied(state, ownerId, key, Status.MAXIMUM_BALANCE_EXCEEDED, "maximum_balance_exceeded",
                    timestampEpochMillis, transactionId);
        }
        if (!state.canCommitReceiptTransaction(transactionId, timestampEpochMillis)) {
            return denied(state, ownerId, key, Status.TRANSACTION_LEDGER_FULL, "transaction_ledger_full",
                    timestampEpochMillis, transactionId);
        }
        EconomyTransactionReceipt receipt = new EconomyTransactionReceipt(
                timestampEpochMillis, ownerId, ownerId, EconomyTransactionReceipt.Kind.CLAIM_SALE,
                refund, Optional.of(key), Optional.empty(), Optional.empty(), Optional.empty(), 0,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                EconomyTransactionReceipt.CompensationDecision.NONE);
        List<EconomyAlert> alerts = EconomyMonitoringService.evaluate(
                state, transactionId, receipt, EconomyConfig.alertThresholds());
        state.commitClaimSale(ownerId, afterBalance, key, transactionId, timestampEpochMillis, receipt, alerts,
                auditEntry(timestampEpochMillis, ownerId, action("claim_sale"), key,
                        summary(claim) + ";balance=" + beforeBalance,
                        "unowned;balance=" + afterBalance, validReason.orElseThrow(), transactionId));
        EconomyMonitoringService.publish(alerts);
        return result(Status.SUCCESS, transactionId, refund, afterBalance, true);
    }

    private static Result precheck(
            PlatformSavedData state,
            UUID actorId,
            ClaimKey key,
            String reason,
            long timestampEpochMillis,
            UUID transactionId,
            ClaimMutationReceipt.Kind operation,
            String payload) {
        if (!state.isWritable()) {
            return result(Status.READ_ONLY_SCHEMA, transactionId, 0, 0, false);
        }
        if (actorId == null || key == null || operation == null || payload == null || timestampEpochMillis < 0) {
            return result(Status.INVALID_REQUEST, transactionId, 0, 0, false);
        }
        if (!validTransactionId(transactionId)) {
            return denied(state, actorId, key, Status.INVALID_TRANSACTION, "invalid_transaction",
                    payload, timestampEpochMillis, transactionId);
        }
        Optional<ClaimMutationReceipt> retained = state.claimReceipt(transactionId);
        if (retained.isPresent()) {
            return retained.orElseThrow().matches(actorId, key, operation, payload)
                    ? result(Status.DUPLICATE_TRANSACTION, transactionId, 0, 0, false)
                    : denied(state, actorId, key, Status.TRANSACTION_ID_CONFLICT, "transaction_id_conflict",
                            payload, timestampEpochMillis, transactionId);
        }
        if (state.economyReceipt(transactionId).isPresent()
                || state.hasTransaction(transactionId, timestampEpochMillis)) {
            return denied(state, actorId, key, Status.TRANSACTION_ID_CONFLICT, "transaction_id_conflict",
                    payload, timestampEpochMillis, transactionId);
        }
        if (validReason(reason).isEmpty()) {
            return denied(state, actorId, key, Status.INVALID_REASON, "invalid_reason",
                    payload, timestampEpochMillis, transactionId);
        }
        if (!state.canCommitClaimTransaction(transactionId, timestampEpochMillis)) {
            return denied(state, actorId, key, Status.TRANSACTION_LEDGER_FULL, "transaction_ledger_full",
                    payload, timestampEpochMillis, transactionId);
        }
        return null;
    }

    private static Result commitMutation(
            PlatformSavedData state,
            UUID actorId,
            ClaimKey key,
            Claim before,
            Claim after,
            String reason,
            long timestampEpochMillis,
            UUID transactionId,
            ClaimMutationReceipt.Kind operation,
            String payload,
            String action) {
        ClaimMutationReceipt receipt = new ClaimMutationReceipt(
                timestampEpochMillis, actorId, key, operation, payload);
        state.commitClaimMutation(key, after, transactionId, timestampEpochMillis, receipt,
                auditEntry(timestampEpochMillis, actorId, action(action), key,
                        summary(before) + ";operation=" + payload,
                        summary(after) + ";operation=" + payload,
                        validReason(reason).orElseThrow(), transactionId));
        return result(Status.SUCCESS, transactionId, 0, state.economyBalance(actorId).orElse(0L), true);
    }

    private static Result commitNoChange(
            PlatformSavedData state,
            UUID actorId,
            ClaimKey key,
            Claim claim,
            String reason,
            long timestampEpochMillis,
            UUID transactionId,
            ClaimMutationReceipt.Kind operation,
            String payload,
            String action) {
        ClaimMutationReceipt receipt = new ClaimMutationReceipt(
                timestampEpochMillis, actorId, key, operation, payload);
        String evidence = summary(claim) + ";operation=" + payload;
        state.commitClaimMutation(key, claim, transactionId, timestampEpochMillis, receipt,
                auditEntry(timestampEpochMillis, actorId, action(action), key,
                        evidence, evidence, validReason(reason).orElseThrow(), transactionId));
        return result(Status.NO_CHANGE, transactionId, 0, state.economyBalance(actorId).orElse(0L), true);
    }

    static boolean canManage(
            PlatformSavedData state, Claim claim, UUID actorId, boolean authorizationOverride) {
        if (claim.roleOf(actorId).atLeast(ClaimRole.MANAGER)) {
            return true;
        }
        AdminRole adminRole = state.roleOf(actorId).orElse(null);
        return authorizationOverride || adminRole == AdminRole.MODERATOR || adminRole == AdminRole.OWNER;
    }

    static Result rejectUnauthorizedIntent(
            PlatformSavedData state,
            UUID actorId,
            ClaimKey key,
            String payload,
            long timestampEpochMillis) {
        if (!state.isWritable()) {
            return result(Status.READ_ONLY_SCHEMA, null, 0, 0, false);
        }
        return denied(state, actorId, key, Status.UNAUTHORIZED, "unauthorized",
                payload, timestampEpochMillis, UUID.randomUUID());
    }

    private static Result denied(
            PlatformSavedData state,
            UUID actorId,
            ClaimKey key,
            Status status,
            String reason,
            long timestampEpochMillis,
            UUID transactionId) {
        return denied(state, actorId, key, status, reason, "", timestampEpochMillis, transactionId);
    }

    private static Result denied(
            PlatformSavedData state,
            UUID actorId,
            ClaimKey key,
            Status status,
            String reason,
            String operationPayload,
            long timestampEpochMillis,
            UUID transactionId) {
        if (actorId == null) {
            return result(status, transactionId, 0, 0, false);
        }
        UUID auditId = validTransactionId(transactionId) ? transactionId : UUID.randomUUID();
        String evidence = state.claim(key).map(ClaimManagementService::summary).orElse("unowned")
                + (operationPayload.isEmpty() ? "" : ";operation=" + operationPayload);
        boolean audited = state.appendDeniedAudit(auditEntry(
                timestampEpochMillis, actorId, action("claim_mutation_denied"), key,
                evidence, evidence, reason, auditId),
                DENIED_AUDIT_INTERVAL_MILLIS);
        return result(status, transactionId, 0, state.economyBalance(actorId).orElse(0L), audited);
    }

    private static AuditEntry auditEntry(
            long timestampEpochMillis,
            UUID actorId,
            Identifier action,
            ClaimKey key,
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
                key == null ? Optional.empty() : Optional.of(key.auditPosition()),
                before,
                after,
                reason,
                transactionId);
    }

    private static String summary(Claim claim) {
        return "owner=" + claim.ownerId()
                + ";trusted=" + claim.trustedRoles().size()
                + ";entry_restricted=" + claim.settings().entryRestricted()
                + ";public_interactions=" + claim.settings().publicInteractions()
                + ";pending_transfer=" + claim.pendingTransferTo().map(UUID::toString).orElse("none");
    }

    static long refund(long purchasePrice, int percent) {
        long whole = Math.multiplyExact(purchasePrice / 100, percent);
        long remainder = Math.multiplyExact(purchasePrice % 100, percent) / 100;
        return Math.addExact(whole, remainder);
    }

    private static Optional<String> validReason(String reason) {
        String normalized = reason == null ? "" : reason.strip();
        return normalized.isEmpty() || normalized.length() > AdministrationService.MAX_REASON_LENGTH
                ? Optional.empty()
                : Optional.of(normalized);
    }

    private static boolean validTransactionId(UUID transactionId) {
        return transactionId != null && !ZERO_UUID.equals(transactionId);
    }

    private static Identifier action(String path) {
        return Identifier.fromNamespaceAndPath(Rovenfall.MOD_ID, path);
    }

    private static Result result(
            Status status, UUID transactionId, long amount, long balance, boolean auditRecorded) {
        return new Result(status, transactionId, amount, balance, auditRecorded);
    }

    private static RoleEvaluation roleEvaluation(
            Status status,
            Optional<Claim> claim,
            ClaimRole actorRole,
            ClaimRole currentTargetRole,
            ClaimRole requestedRole,
            int trustedPlayers) {
        return new RoleEvaluation(
                status, claim, actorRole, currentTargetRole,
                Optional.ofNullable(requestedRole), trustedPlayers, Claim.MAX_TRUSTED_PLAYERS);
    }

    public enum Status {
        SUCCESS,
        DUPLICATE_TRANSACTION,
        TRANSACTION_ID_CONFLICT,
        NO_CHANGE,
        INVALID_REQUEST,
        INVALID_TRANSACTION,
        INVALID_REASON,
        READ_ONLY_SCHEMA,
        UNAUTHORIZED,
        CLAIM_NOT_FOUND,
        INVALID_TARGET,
        TRUST_LIMIT_REACHED,
        PROTECTED_CHUNK,
        OWNERSHIP_CAP_REACHED,
        TRANSFER_NOT_PENDING,
        TRANSFER_PENDING,
        PURCHASE_PRICE_UNAVAILABLE,
        ACCOUNT_NOT_FOUND,
        OVERFLOW,
        MAXIMUM_BALANCE_EXCEEDED,
        TRANSACTION_LEDGER_FULL;

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        public String evaluationTranslationKey() {
            return "claim_role_evaluation.rovenfall." + id();
        }
    }

    public record RoleEvaluation(
            Status status,
            Optional<Claim> claim,
            ClaimRole actorRole,
            ClaimRole currentTargetRole,
            Optional<ClaimRole> requestedRole,
            int trustedPlayers,
            int trustLimit) {
        public RoleEvaluation {
            claim = claim == null ? Optional.empty() : claim;
            requestedRole = requestedRole == null ? Optional.empty() : requestedRole;
        }

        public boolean allowed() {
            return status == Status.SUCCESS || status == Status.NO_CHANGE;
        }

        public boolean wouldChange() {
            return status == Status.SUCCESS;
        }
    }

    public record Result(
            Status status,
            UUID transactionId,
            long amount,
            long balance,
            boolean auditRecorded) {
    }
}
