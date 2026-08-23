package org.dldyou.rovenfall.administration;

import com.mojang.logging.LogUtils;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import org.dldyou.rovenfall.Rovenfall;
import org.slf4j.Logger;

public final class EconomyService {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final UUID ZERO_UUID = new UUID(0L, 0L);
    private static final Set<TransactionStatus> REPORTED_LOGIN_FAILURES = ConcurrentHashMap.newKeySet();
    private static final long DENIED_AUDIT_INTERVAL_MILLIS = 1_000L;
    private static final Identifier ACCOUNT_CREATE = action("economy_account_create");

    private EconomyService() {
    }

    static TransactionResult createAccount(
            PlatformSavedData state,
            UUID playerId,
            long initialBalance,
            long maximumBalance,
            long timestampEpochMillis,
            UUID transactionId) {
        if (!state.isWritable()) {
            return result(TransactionStatus.READ_ONLY_SCHEMA, 0, 0, transactionId, false);
        }
        if (playerId == null) {
            return result(TransactionStatus.INVALID_INPUT, 0, 0, transactionId, false);
        }
        if (timestampEpochMillis < 0) {
            return result(TransactionStatus.INVALID_INPUT, 0, 0, transactionId, false);
        }
        if (!validTransactionId(transactionId)) {
            return result(TransactionStatus.INVALID_TRANSACTION, 0, 0, transactionId, false);
        }
        if (state.hasEconomyTransaction(transactionId, timestampEpochMillis)) {
            long balance = state.economyBalance(playerId).orElse(0L);
            return result(TransactionStatus.DUPLICATE_TRANSACTION, balance, balance, transactionId, false);
        }
        Optional<Long> existing = state.economyBalance(playerId);
        if (existing.isPresent()) {
            return result(TransactionStatus.ACCOUNT_EXISTS, existing.get(), existing.get(), transactionId, false);
        }
        if (!EconomyConfig.isValid(initialBalance, maximumBalance)) {
            return result(TransactionStatus.INVALID_CONFIGURATION, 0, 0, transactionId, false);
        }
        if (!state.canCommitEconomyTransaction(transactionId, timestampEpochMillis)) {
            return result(TransactionStatus.TRANSACTION_LEDGER_FULL, 0, 0, transactionId, false);
        }

        state.commitEconomyTransaction(playerId, initialBalance, transactionId, timestampEpochMillis, auditEntry(
                timestampEpochMillis,
                AdministrationService.SYSTEM_ACTOR,
                ACCOUNT_CREATE,
                playerId,
                "none",
                Long.toString(initialBalance),
                "initial_balance",
                transactionId));
        return result(TransactionStatus.SUCCESS, initialBalance, initialBalance, transactionId, true);
    }

    static TransactionResult adminGrant(
            PlatformSavedData state,
            UUID actorId,
            boolean authorizationOverride,
            UUID playerId,
            long amount,
            String reason,
            long timestampEpochMillis,
            UUID transactionId,
            long initialBalance,
            long maximumBalance) {
        return mutate(state, actorId, authorizationOverride, playerId, amount, reason, timestampEpochMillis,
                transactionId, initialBalance, maximumBalance, Operation.ADMIN_GRANT);
    }

    static TransactionResult adminDebit(
            PlatformSavedData state,
            UUID actorId,
            boolean authorizationOverride,
            UUID playerId,
            long amount,
            String reason,
            long timestampEpochMillis,
            UUID transactionId,
            long initialBalance,
            long maximumBalance) {
        return mutate(state, actorId, authorizationOverride, playerId, amount, reason, timestampEpochMillis,
                transactionId, initialBalance, maximumBalance, Operation.ADMIN_DEBIT);
    }

    public static TransactionResult award(
            PlatformSavedData state,
            UUID playerId,
            long amount,
            String reason,
            long timestampEpochMillis,
            UUID transactionId,
            long initialBalance,
            long maximumBalance) {
        return mutate(state, AdministrationService.SYSTEM_ACTOR, true, playerId, amount, reason,
                timestampEpochMillis, transactionId, initialBalance, maximumBalance, Operation.AWARD);
    }

    public static TransactionResult debit(
            PlatformSavedData state,
            UUID playerId,
            long amount,
            String reason,
            long timestampEpochMillis,
            UUID transactionId,
            long initialBalance,
            long maximumBalance) {
        return mutate(state, AdministrationService.SYSTEM_ACTOR, true, playerId, amount, reason,
                timestampEpochMillis, transactionId, initialBalance, maximumBalance, Operation.DEBIT);
    }

    private static TransactionResult mutate(
            PlatformSavedData state,
            UUID actorId,
            boolean authorizationOverride,
            UUID playerId,
            long amount,
            String reason,
            long timestampEpochMillis,
            UUID transactionId,
            long initialBalance,
            long maximumBalance,
            Operation operation) {
        if (!state.isWritable()) {
            return result(TransactionStatus.READ_ONLY_SCHEMA, 0, 0, transactionId, false);
        }
        if (actorId == null || playerId == null) {
            return result(TransactionStatus.INVALID_INPUT, 0, 0, transactionId, false);
        }
        if (timestampEpochMillis < 0) {
            return result(TransactionStatus.INVALID_INPUT, 0, 0, transactionId, false);
        }
        if (operation.administratorOnly && !canManageEconomy(state, actorId, authorizationOverride)) {
            return denied(state, actorId, playerId, operation, TransactionStatus.UNAUTHORIZED,
                    "unauthorized", timestampEpochMillis, transactionId, currentBalance(state, playerId, initialBalance));
        }
        if (!validTransactionId(transactionId)) {
            return denied(state, actorId, playerId, operation, TransactionStatus.INVALID_TRANSACTION,
                    "invalid_transaction", timestampEpochMillis, transactionId, currentBalance(state, playerId, initialBalance));
        }
        long beforeBalance = currentBalance(state, playerId, initialBalance);
        if (state.hasEconomyTransaction(transactionId, timestampEpochMillis)) {
            return result(TransactionStatus.DUPLICATE_TRANSACTION, beforeBalance, beforeBalance, transactionId, false);
        }
        if (amount <= 0) {
            return denied(state, actorId, playerId, operation, TransactionStatus.INVALID_AMOUNT,
                    "invalid_amount", timestampEpochMillis, transactionId, beforeBalance);
        }
        Optional<String> validReason = validReason(reason);
        if (validReason.isEmpty()) {
            return denied(state, actorId, playerId, operation, TransactionStatus.INVALID_REASON,
                    "invalid_reason", timestampEpochMillis, transactionId, beforeBalance);
        }
        if (!EconomyConfig.isValid(initialBalance, maximumBalance)) {
            return denied(state, actorId, playerId, operation, TransactionStatus.INVALID_CONFIGURATION,
                    "invalid_configuration", timestampEpochMillis, transactionId, beforeBalance);
        }
        if (!state.canCommitEconomyTransaction(transactionId, timestampEpochMillis)) {
            return denied(state, actorId, playerId, operation, TransactionStatus.TRANSACTION_LEDGER_FULL,
                    "transaction_ledger_full", timestampEpochMillis, transactionId, beforeBalance);
        }

        long afterBalance;
        if (operation.credit) {
            try {
                afterBalance = Math.addExact(beforeBalance, amount);
            } catch (ArithmeticException exception) {
                return denied(state, actorId, playerId, operation, TransactionStatus.OVERFLOW,
                        "overflow", timestampEpochMillis, transactionId, beforeBalance);
            }
            if (afterBalance > maximumBalance) {
                return denied(state, actorId, playerId, operation, TransactionStatus.MAXIMUM_EXCEEDED,
                        "maximum_exceeded", timestampEpochMillis, transactionId, beforeBalance);
            }
        } else {
            if (amount > beforeBalance) {
                return denied(state, actorId, playerId, operation, TransactionStatus.INSUFFICIENT_FUNDS,
                        "insufficient_funds", timestampEpochMillis, transactionId, beforeBalance);
            }
            afterBalance = Math.subtractExact(beforeBalance, amount);
        }

        state.commitEconomyTransaction(playerId, afterBalance, transactionId, timestampEpochMillis, auditEntry(
                timestampEpochMillis,
                actorId,
                operation.successAction,
                playerId,
                Long.toString(beforeBalance),
                Long.toString(afterBalance),
                validReason.get(),
                transactionId));
        return result(TransactionStatus.SUCCESS, beforeBalance, afterBalance, transactionId, true);
    }

    private static TransactionResult denied(
            PlatformSavedData state,
            UUID actorId,
            UUID playerId,
            Operation operation,
            TransactionStatus status,
            String denialReason,
            long timestampEpochMillis,
            UUID transactionId,
            long balance) {
        UUID auditTransactionId = validTransactionId(transactionId) ? transactionId : UUID.randomUUID();
        boolean audited = state.appendDeniedAudit(auditEntry(
                timestampEpochMillis,
                actorId,
                operation.deniedAction,
                playerId,
                Long.toString(balance),
                Long.toString(balance),
                denialReason,
                auditTransactionId), DENIED_AUDIT_INTERVAL_MILLIS);
        return result(status, balance, balance, transactionId, audited);
    }

    private static long currentBalance(PlatformSavedData state, UUID playerId, long initialBalance) {
        return state.economyBalance(playerId).orElse(Math.max(0, initialBalance));
    }

    static boolean canManageEconomy(
            PlatformSavedData state, UUID actorId, boolean authorizationOverride) {
        AdminRole role = state.roleOf(actorId).orElse(null);
        return authorizationOverride || role == AdminRole.ECONOMY_MANAGER || role == AdminRole.OWNER;
    }

    private static boolean validTransactionId(UUID transactionId) {
        return transactionId != null && !ZERO_UUID.equals(transactionId);
    }

    private static Optional<String> validReason(String reason) {
        String normalized = reason == null ? "" : reason.strip();
        return normalized.isEmpty() || normalized.length() > AdministrationService.MAX_REASON_LENGTH
                ? Optional.empty()
                : Optional.of(normalized);
    }

    private static AuditEntry auditEntry(
            long timestampEpochMillis,
            UUID actorId,
            Identifier action,
            UUID playerId,
            String beforeValue,
            String afterValue,
            String reason,
            UUID transactionId) {
        return new AuditEntry(
                timestampEpochMillis,
                actorId,
                action,
                playerId.toString(),
                Optional.empty(),
                Optional.empty(),
                beforeValue,
                afterValue,
                reason,
                transactionId);
    }

    private static TransactionResult result(
            TransactionStatus status,
            long beforeBalance,
            long balance,
            UUID transactionId,
            boolean auditRecorded) {
        return new TransactionResult(status, beforeBalance, balance, transactionId, auditRecorded);
    }

    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        MinecraftServer server = player.level().getServer();
        UUID playerId = player.getUUID();
        long timestampEpochMillis = System.currentTimeMillis();
        UUID transactionId = UUID.randomUUID();
        Runnable update = () -> {
            TransactionResult result = createAccount(
                    PlatformSavedData.get(server),
                    playerId,
                    EconomyConfig.initialBalance(),
                    EconomyConfig.maximumBalance(),
                    timestampEpochMillis,
                    transactionId);
            if (shouldReportLoginFailure(result.status(), REPORTED_LOGIN_FAILURES)) {
                LOGGER.error("Could not create a player economy account during login ({}). "
                        + "Further login failures of this type will be suppressed.", result.status());
            }
        };
        if (server.isSameThread()) {
            update.run();
        } else {
            server.execute(update);
        }
    }

    static boolean shouldReportLoginFailure(TransactionStatus status, Set<TransactionStatus> reportedFailures) {
        return switch (status) {
            case SUCCESS, ACCOUNT_EXISTS, DUPLICATE_TRANSACTION -> false;
            default -> reportedFailures.add(status);
        };
    }

    private enum Operation {
        ADMIN_GRANT(true, true, "economy_admin_grant"),
        ADMIN_DEBIT(true, false, "economy_admin_debit"),
        AWARD(false, true, "economy_award"),
        DEBIT(false, false, "economy_debit");

        private final boolean administratorOnly;
        private final boolean credit;
        private final Identifier successAction;
        private final Identifier deniedAction;

        Operation(boolean administratorOnly, boolean credit, String actionPath) {
            this.administratorOnly = administratorOnly;
            this.credit = credit;
            this.successAction = action(actionPath);
            this.deniedAction = action(actionPath + "_denied");
        }
    }

    private static Identifier action(String path) {
        return Identifier.fromNamespaceAndPath(Rovenfall.MOD_ID, path);
    }

    public enum TransactionStatus {
        SUCCESS,
        ACCOUNT_EXISTS,
        DUPLICATE_TRANSACTION,
        UNAUTHORIZED,
        INVALID_INPUT,
        INVALID_TRANSACTION,
        INVALID_AMOUNT,
        INVALID_REASON,
        INVALID_CONFIGURATION,
        TRANSACTION_LEDGER_FULL,
        OVERFLOW,
        MAXIMUM_EXCEEDED,
        INSUFFICIENT_FUNDS,
        READ_ONLY_SCHEMA
    }

    public record TransactionResult(
            TransactionStatus status,
            long beforeBalance,
            long balance,
            UUID transactionId,
            boolean auditRecorded) {
    }
}
