package org.dldyou.rovenfall.administration;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import org.dldyou.rovenfall.Rovenfall;
import org.dldyou.rovenfall.mobs.BossEncounterRuntime;
import org.dldyou.rovenfall.mobs.BossEncounterSavedData;
import org.dldyou.rovenfall.mobs.BossEncounterState;
import org.dldyou.rovenfall.mobs.BossRewardSavedData;
import org.dldyou.rovenfall.rpg.RpgPlayerSavedData;

/** Owner-only, crash-resumable boss reset and recovery boundary. */
public final class BossAdministrationService {
    private static final UUID ZERO_UUID = new UUID(0L, 0L);
    private static final Identifier RESET_REQUESTED = action("boss_admin_reset_requested");
    private static final Identifier RESET_COMPLETED = action("boss_admin_reset_completed");
    private static final Identifier RECOVER_REQUESTED = action("boss_admin_recover_requested");
    private static final Identifier RECOVER_COMPLETED = action("boss_admin_recover_completed");

    private BossAdministrationService() {
    }

    public static boolean canView(PlatformSavedData state, UUID actorId, boolean authorizationOverride) {
        return state != null && actorId != null
                && (authorizationOverride || state.hasAdminRole(actorId));
    }

    public static boolean canRecover(PlatformSavedData state, UUID actorId, boolean authorizationOverride) {
        return state != null && actorId != null && (authorizationOverride
                || state.roleOf(actorId).orElse(null) == AdminRole.OWNER);
    }

    public static Result reset(
            MinecraftServer server,
            UUID actorId,
            boolean authorizationOverride,
            UUID encounterId,
            String reason,
            long timestamp,
            UUID transactionId) {
        PlatformSavedData platform = server == null ? null : PlatformSavedData.get(server);
        Status invalid = validate(platform, actorId, authorizationOverride, encounterId, reason, timestamp, transactionId);
        if (invalid != null) {
            return new Result(invalid, transactionId, false);
        }
        if (!BossEncounterSavedData.get(server).isWritable()) {
            return new Result(Status.READ_ONLY, transactionId, false);
        }
        String target = encounterId.toString();
        RequestState request = request(platform, actorId, RESET_REQUESTED, RESET_COMPLETED,
                target, reason, timestamp, transactionId, resetBefore(server, encounterId));
        if (request.status() != null) {
            return new Result(request.status(), transactionId, request.auditRecorded());
        }

        BossEncounterState encounter = BossEncounterSavedData.get(server).encounter(encounterId).orElse(null);
        if (encounter != null && encounter.stage() == BossEncounterState.Stage.REWARD_PENDING) {
            if (!BossRewardSavedData.get(server).isWritable()
                    || !RpgPlayerSavedData.get(server).isWritable()) {
                return new Result(Status.READ_ONLY, transactionId, true);
            }
            BossEncounterRuntime.recover(server, timestamp);
            if (BossEncounterSavedData.get(server).encounter(encounterId)
                    .filter(candidate -> candidate.stage() == BossEncounterState.Stage.REWARD_PENDING)
                    .isPresent()) {
                return new Result(Status.REWARDS_PENDING, transactionId, true);
            }
        }
        boolean complete = BossEncounterRuntime.reset(server, encounterId, timestamp);
        if (!complete) {
            return new Result(Status.RESET_FAILED, transactionId, true);
        }
        complete(platform, actorId, RESET_COMPLETED, target, reason, timestamp, transactionId,
                request.before(), "encounter=absent;arena=absent");
        return new Result(Status.SUCCESS, transactionId, true);
    }

    public static Result recover(
            MinecraftServer server,
            UUID actorId,
            boolean authorizationOverride,
            String reason,
            long timestamp,
            UUID transactionId) {
        PlatformSavedData platform = server == null ? null : PlatformSavedData.get(server);
        Status invalid = validate(platform, actorId, authorizationOverride, ZERO_UUID, reason, timestamp, transactionId);
        if (invalid != null) {
            return new Result(invalid, transactionId, false);
        }
        if (!BossEncounterSavedData.get(server).isWritable()
                || !BossRewardSavedData.get(server).isWritable()
                || !RpgPlayerSavedData.get(server).isWritable()) {
            return new Result(Status.READ_ONLY, transactionId, false);
        }
        String before = recoverySummary(server);
        RequestState request = request(platform, actorId, RECOVER_REQUESTED, RECOVER_COMPLETED,
                "all", reason, timestamp, transactionId, before);
        if (request.status() != null) {
            return new Result(request.status(), transactionId, request.auditRecorded());
        }
        BossRewardService.recover(server, timestamp);
        BossEncounterRuntime.recover(server, timestamp);
        if (hasOutstandingRecovery(server)) {
            return new Result(Status.RECOVERY_PENDING, transactionId, true);
        }
        complete(platform, actorId, RECOVER_COMPLETED, "all", reason, timestamp, transactionId,
                request.before(), recoverySummary(server));
        return new Result(Status.SUCCESS, transactionId, true);
    }

    private static Status validate(
            PlatformSavedData platform, UUID actorId, boolean authorizationOverride, UUID target,
            String reason, long timestamp, UUID transactionId) {
        if (platform == null || actorId == null || target == null || timestamp < 0
                || transactionId == null || ZERO_UUID.equals(transactionId)) {
            return Status.INVALID_REQUEST;
        }
        if (!canRecover(platform, actorId, authorizationOverride)) {
            denied(platform, actorId, target.toString(), "unauthorized", timestamp, transactionId);
            return Status.UNAUTHORIZED;
        }
        if (!platform.isWritable()) {
            return Status.READ_ONLY;
        }
        if (reason == null || reason.isBlank() || reason.length() > AdministrationService.MAX_REASON_LENGTH) {
            denied(platform, actorId, target.toString(), "invalid_reason", timestamp, transactionId);
            return Status.INVALID_REASON;
        }
        return null;
    }

    private static RequestState request(
            PlatformSavedData platform, UUID actorId, Identifier requestedAction, Identifier completedAction,
            String target, String reason, long timestamp, UUID transactionId, String before) {
        UUID completionId = completionId(transactionId);
        AuditEntry completed = platform.auditTransaction(completionId).orElse(null);
        AuditEntry existing = platform.auditTransaction(transactionId).orElse(null);
        if (existing != null && !matches(existing, actorId, requestedAction, target, reason)) {
            return new RequestState(Status.TRANSACTION_CONFLICT, false, before);
        }
        if (completed != null) {
            if (!matches(completed, actorId, completedAction, target, reason)) {
                return new RequestState(Status.TRANSACTION_CONFLICT, false, before);
            }
            return new RequestState(Status.DUPLICATE, true, completed.beforeValue());
        }
        if (existing != null) {
            return new RequestState(null, true, existing.beforeValue());
        }
        if (requestedAction.equals(RESET_REQUESTED) && before.startsWith("missing")) {
            return new RequestState(Status.NOT_FOUND, false, before);
        }
        platform.commitAudit(new AuditEntry(
                timestamp, actorId, requestedAction, target, Optional.empty(), Optional.empty(),
                before, "pending", reason, transactionId));
        return new RequestState(null, true, before);
    }

    private static void complete(
            PlatformSavedData platform, UUID actorId, Identifier action, String target, String reason,
            long timestamp, UUID transactionId, String before, String after) {
        UUID completionId = completionId(transactionId);
        if (platform.auditTransaction(completionId).isEmpty()) {
            platform.commitAudit(new AuditEntry(
                    timestamp, actorId, action, target, Optional.empty(), Optional.empty(),
                    before, after, reason, completionId));
        }
    }

    private static boolean hasOutstandingRecovery(MinecraftServer server) {
        return !BossRewardSavedData.get(server).pendingOperations().isEmpty()
                || BossEncounterSavedData.get(server).activeEncounters().stream()
                        .anyMatch(encounter -> encounter.stage() == BossEncounterState.Stage.REWARD_PENDING)
                || orphanArenaCount(server) > 0;
    }

    private static String resetBefore(MinecraftServer server, UUID encounterId) {
        return BossEncounterSavedData.get(server).encounter(encounterId)
                .map(encounter -> "boss=" + encounter.bossId() + ";stage=" + encounter.stage().getSerializedName()
                        + ";phase=" + encounter.phaseIndex() + ";participants="
                        + encounter.contributions().size())
                .orElse("missing");
    }

    private static String recoverySummary(MinecraftServer server) {
        long pending = BossRewardSavedData.get(server).pendingOperations().size();
        return "encounters=" + BossEncounterSavedData.get(server).activeCount()
                + ";pending_rewards=" + pending + ";orphan_arenas=" + orphanArenaCount(server);
    }

    private static long orphanArenaCount(MinecraftServer server) {
        java.util.Set<Identifier> active = BossEncounterSavedData.get(server).activeEncounters().stream()
                .map(BossEncounterState::encounterId)
                .map(BossEncounterRuntime::regionId)
                .collect(java.util.stream.Collectors.toSet());
        return PlatformSavedData.get(server).protectedRegions().stream()
                .filter(entry -> BossEncounterRuntime.isOwnedArenaRegion(
                        server, entry.getKey(), entry.getValue()))
                .filter(entry -> !active.contains(entry.getKey()))
                .count();
    }

    private static boolean matches(
            AuditEntry entry, UUID actorId, Identifier action, String target, String reason) {
        return entry.actorId().equals(actorId) && entry.actionType().equals(action)
                && entry.target().equals(target) && entry.reason().equals(reason);
    }

    private static UUID completionId(UUID transactionId) {
        return UUID.nameUUIDFromBytes(("boss-admin-completion:" + transactionId)
                .getBytes(StandardCharsets.UTF_8));
    }

    private static void denied(
            PlatformSavedData platform, UUID actorId, String target, String reason,
            long timestamp, UUID transactionId) {
        platform.appendDeniedAudit(new AuditEntry(
                timestamp, actorId, action("boss_admin_denied"), target,
                Optional.empty(), Optional.empty(), "unchanged", "unchanged", reason,
                transactionId == null || ZERO_UUID.equals(transactionId) ? UUID.randomUUID() : transactionId), 1_000L);
    }

    private static Identifier action(String path) {
        return Identifier.fromNamespaceAndPath(Rovenfall.MOD_ID, path);
    }

    public enum Status {
        SUCCESS,
        DUPLICATE,
        UNAUTHORIZED,
        INVALID_REQUEST,
        INVALID_REASON,
        READ_ONLY,
        NOT_FOUND,
        REWARDS_PENDING,
        RECOVERY_PENDING,
        RESET_FAILED,
        TRANSACTION_CONFLICT
    }

    public record Result(Status status, UUID transactionId, boolean auditRecorded) {
    }

    private record RequestState(Status status, boolean auditRecorded, String before) {
    }
}
