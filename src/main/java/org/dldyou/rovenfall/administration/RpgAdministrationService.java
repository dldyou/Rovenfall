package org.dldyou.rovenfall.administration;

import com.mojang.logging.LogUtils;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import org.dldyou.rovenfall.Rovenfall;
import org.dldyou.rovenfall.rpg.RpgAdministrativeMutationService;
import org.dldyou.rovenfall.rpg.RpgDefinitionReloadListener;
import org.dldyou.rovenfall.rpg.RpgDefinitionSnapshot;
import org.dldyou.rovenfall.rpg.RpgPlayerSavedData;
import org.dldyou.rovenfall.rpg.RpgPlayerState;
import org.dldyou.rovenfall.rpg.RpgSkillService;
import org.dldyou.rovenfall.rpg.SkillResetPlan;
import org.slf4j.Logger;

/** Role authorization and durable cross-root journal for administrative RPG mutations. */
public final class RpgAdministrationService {
    private static final UUID ZERO_UUID = new UUID(0L, 0L);
    private static final long DENIED_AUDIT_INTERVAL_MILLIS = 1_000L;
    private static final Logger LOGGER = LogUtils.getLogger();

    public enum Status {
        SUCCESS,
        UNAUTHORIZED,
        INVALID_REQUEST,
        INVALID_REASON,
        READ_ONLY,
        DUPLICATE,
        TRANSACTION_CONFLICT,
        UNKNOWN_ACTIVITY,
        UNKNOWN_CAREER,
        ALREADY_PROMOTED,
        MISSING_PARENT,
        PARENT_RANK_TOO_LOW,
        NOTHING_TO_RESET,
        STATE_CONFLICT,
        OVERFLOW,
        STATE_FULL,
        JOURNAL_FULL,
        RPG_FAILED,
        COMPLETION_FAILED
    }

    public record Result(
            Status status,
            UUID transactionId,
            long beforeAmount,
            long afterAmount,
            boolean auditRecorded) {
    }

    private RpgAdministrationService() {
    }

    public static boolean canView(PlatformSavedData platform, UUID actorId, boolean authorizationOverride) {
        return platform != null && actorId != null
                && (authorizationOverride || platform.hasAdminRole(actorId));
    }

    public static boolean canManage(PlatformSavedData platform, UUID actorId, boolean authorizationOverride) {
        if (platform == null || actorId == null) {
            return false;
        }
        AdminRole role = platform.roleOf(actorId).orElse(null);
        return authorizationOverride || role == AdminRole.MODERATOR
                || role == AdminRole.CONTENT_MANAGER || role == AdminRole.OWNER;
    }

    public static Result adjustActivityXp(
            PlatformSavedData platform,
            RpgPlayerSavedData rpg,
            RpgDefinitionSnapshot definitions,
            UUID actorId,
            boolean authorizationOverride,
            UUID playerId,
            Identifier activityId,
            long delta,
            String reason,
            long timestamp,
            UUID transactionId) {
        Result common = validateNewRequest(
                platform, rpg, actorId, authorizationOverride, playerId, activityId,
                delta, reason, timestamp, transactionId, RpgAdminOperation.Action.XP_ADJUST);
        if (common != null) {
            return common;
        }
        RpgAdminOperation existing = platform.rpgAdminOperation(transactionId).orElse(null);
        if (existing != null) {
            return resumeOrConflict(platform, rpg, definitions, transactionId, existing,
                    actorId, playerId, RpgAdminOperation.Action.XP_ADJUST, activityId, delta, reason, null);
        }
        if (definitions == null || definitions.activity(activityId).isEmpty()) {
            return denied(platform, actorId, playerId, RpgAdminOperation.Action.XP_ADJUST, activityId,
                    Status.UNKNOWN_ACTIVITY, timestamp, transactionId);
        }
        long before = rpg.state(playerId).activityXp().getOrDefault(activityId, 0L);
        final long after;
        try {
            after = Math.addExact(before, delta);
        } catch (ArithmeticException exception) {
            return denied(platform, actorId, playerId, RpgAdminOperation.Action.XP_ADJUST, activityId,
                    Status.OVERFLOW, timestamp, transactionId);
        }
        if (after < 0 || after > RpgPlayerState.MAX_XP) {
            return denied(platform, actorId, playerId, RpgAdminOperation.Action.XP_ADJUST, activityId,
                    Status.OVERFLOW, timestamp, transactionId);
        }
        RpgAdminOperation operation = new RpgAdminOperation(
                actorId, playerId, RpgAdminOperation.Action.XP_ADJUST, activityId,
                delta, before, Optional.empty(), reason, timestamp, RpgAdminOperation.Phase.PENDING);
        return beginAndApply(platform, rpg, definitions, transactionId, operation);
    }

    public static Result recoverPromotion(
            PlatformSavedData platform,
            RpgPlayerSavedData rpg,
            RpgDefinitionSnapshot definitions,
            UUID actorId,
            boolean authorizationOverride,
            UUID playerId,
            Identifier careerId,
            String reason,
            long timestamp,
            UUID transactionId) {
        Result common = validateNewRequest(
                platform, rpg, actorId, authorizationOverride, playerId, careerId,
                0, reason, timestamp, transactionId, RpgAdminOperation.Action.PROMOTION_RECOVERY);
        if (common != null) {
            return common;
        }
        RpgAdminOperation existing = platform.rpgAdminOperation(transactionId).orElse(null);
        if (existing != null) {
            return resumeOrConflict(platform, rpg, definitions, transactionId, existing,
                    actorId, playerId, RpgAdminOperation.Action.PROMOTION_RECOVERY,
                    careerId, 0, reason, null);
        }
        RpgPlayerState current = rpg.state(playerId);
        RpgAdministrativeMutationService.Status validation =
                RpgAdministrativeMutationService.validatePromotionRecovery(definitions, current, careerId);
        if (validation != RpgAdministrativeMutationService.Status.SUCCESS) {
            return denied(platform, actorId, playerId, RpgAdminOperation.Action.PROMOTION_RECOVERY, careerId,
                    mapApplied(validation), timestamp, transactionId);
        }
        RpgAdminOperation operation = new RpgAdminOperation(
                actorId, playerId, RpgAdminOperation.Action.PROMOTION_RECOVERY, careerId,
                0, 0, Optional.empty(), reason, timestamp, RpgAdminOperation.Phase.PENDING);
        return beginAndApply(platform, rpg, definitions, transactionId, operation);
    }

    public static Result resetSkills(
            PlatformSavedData platform,
            RpgPlayerSavedData rpg,
            RpgDefinitionSnapshot definitions,
            UUID actorId,
            boolean authorizationOverride,
            UUID playerId,
            SkillResetPlan.Mode mode,
            Identifier target,
            String reason,
            long timestamp,
            UUID transactionId) {
        Result common = validateNewRequest(
                platform, rpg, actorId, authorizationOverride, playerId, target,
                0, reason, timestamp, transactionId, RpgAdminOperation.Action.SKILL_RESET);
        if (common != null) {
            return common;
        }
        if (mode == null) {
            return denied(platform, actorId, playerId, RpgAdminOperation.Action.SKILL_RESET, target,
                    Status.INVALID_REQUEST, timestamp, transactionId);
        }
        RpgAdminOperation existing = platform.rpgAdminOperation(transactionId).orElse(null);
        if (existing != null) {
            return resumeOrConflict(platform, rpg, definitions, transactionId, existing,
                    actorId, playerId, RpgAdminOperation.Action.SKILL_RESET, target, 0, reason, mode);
        }
        if (definitions == null) {
            return denied(platform, actorId, playerId, RpgAdminOperation.Action.SKILL_RESET, target,
                    Status.INVALID_REQUEST, timestamp, transactionId);
        }
        RpgSkillService.ResetPreparation preparation = RpgSkillService.prepareReset(
                rpg, definitions, playerId, mode, target);
        if (preparation.status() == RpgSkillService.Status.NOTHING_TO_RESET) {
            return denied(platform, actorId, playerId, RpgAdminOperation.Action.SKILL_RESET, target,
                    Status.NOTHING_TO_RESET, timestamp, transactionId);
        }
        if (preparation.status() != RpgSkillService.Status.SUCCESS) {
            return denied(platform, actorId, playerId, RpgAdminOperation.Action.SKILL_RESET, target,
                    mapPreparation(preparation.status()), timestamp, transactionId);
        }
        RpgAdminOperation operation = new RpgAdminOperation(
                actorId, playerId, RpgAdminOperation.Action.SKILL_RESET, target,
                0, 0, preparation.plan(), reason, timestamp, RpgAdminOperation.Phase.PENDING);
        return beginAndApply(platform, rpg, definitions, transactionId, operation);
    }

    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        MinecraftServer server = player.level().getServer();
        UUID playerId = player.getUUID();
        server.execute(() -> recoverPlayer(server, playerId));
    }

    static void recoverPlayer(MinecraftServer server, UUID playerId) {
        PlatformSavedData platform = PlatformSavedData.get(server);
        RpgPlayerSavedData rpg = RpgPlayerSavedData.get(server);
        RpgDefinitionSnapshot definitions = RpgDefinitionReloadListener.snapshot(server);
        for (var entry : platform.pendingRpgAdminOperations(playerId)) {
            Result result = applyPending(platform, rpg, definitions, entry.getKey(), entry.getValue());
            if (result.status() != Status.SUCCESS && result.status() != Status.DUPLICATE) {
                LOGGER.error("Could not recover RPG admin operation {} ({})", entry.getKey(), result.status());
            }
        }
    }

    private static Result validateNewRequest(
            PlatformSavedData platform,
            RpgPlayerSavedData rpg,
            UUID actorId,
            boolean authorizationOverride,
            UUID playerId,
            Identifier target,
            long delta,
            String reason,
            long timestamp,
            UUID transactionId,
            RpgAdminOperation.Action action) {
        if (platform == null || rpg == null || actorId == null || playerId == null || ZERO_UUID.equals(playerId)
                || target == null || timestamp < 0 || transactionId == null || ZERO_UUID.equals(transactionId)
                || action == null || action != RpgAdminOperation.Action.XP_ADJUST && delta != 0) {
            return result(Status.INVALID_REQUEST, transactionId, 0, 0, false);
        }
        if (!canManage(platform, actorId, authorizationOverride, action)) {
            return denied(platform, actorId, playerId, action, target,
                    Status.UNAUTHORIZED, timestamp, transactionId);
        }
        if (!platform.isWritable() || !rpg.isWritable()) {
            return result(Status.READ_ONLY, transactionId, 0, 0, false);
        }
        if (reason == null || reason.isBlank() || reason.length() > RpgAdminOperation.MAX_REASON_LENGTH) {
            return denied(platform, actorId, playerId, action, target,
                    Status.INVALID_REASON, timestamp, transactionId);
        }
        if (action == RpgAdminOperation.Action.XP_ADJUST && delta == 0) {
            return denied(platform, actorId, playerId, action, target,
                    Status.INVALID_REQUEST, timestamp, transactionId);
        }
        if (platform.pendingRpgAdminOperations(playerId).stream()
                .anyMatch(entry -> !entry.getKey().equals(transactionId))) {
            return denied(platform, actorId, playerId, action, target,
                    Status.STATE_CONFLICT, timestamp, transactionId);
        }
        return null;
    }

    private static boolean canManage(
            PlatformSavedData platform,
            UUID actorId,
            boolean authorizationOverride,
            RpgAdminOperation.Action action) {
        if (authorizationOverride) {
            return true;
        }
        AdminRole role = platform.roleOf(actorId).orElse(null);
        return role == AdminRole.OWNER || switch (action) {
            case XP_ADJUST -> role == AdminRole.MODERATOR;
            case PROMOTION_RECOVERY, SKILL_RESET -> role == AdminRole.CONTENT_MANAGER;
        };
    }

    private static Result resumeOrConflict(
            PlatformSavedData platform,
            RpgPlayerSavedData rpg,
            RpgDefinitionSnapshot definitions,
            UUID transactionId,
            RpgAdminOperation existing,
            UUID actorId,
            UUID playerId,
            RpgAdminOperation.Action action,
            Identifier target,
            long delta,
            String reason,
            SkillResetPlan.Mode resetMode) {
        boolean matches = existing.actorId().equals(actorId)
                && existing.playerId().equals(playerId)
                && existing.action() == action
                && existing.target().equals(target)
                && existing.delta() == delta
                && existing.reason().equals(reason)
                && (resetMode == null || existing.resetPlan().map(SkillResetPlan::mode).orElse(null) == resetMode);
        if (!matches) {
            return result(Status.TRANSACTION_CONFLICT, transactionId, 0, 0, false);
        }
        if (existing.phase() == RpgAdminOperation.Phase.COMPLETED) {
            return result(Status.DUPLICATE, transactionId,
                    existing.expectedBefore(), expectedAfter(existing), true);
        }
        return applyPending(platform, rpg, definitions, transactionId, existing);
    }

    private static Result beginAndApply(
            PlatformSavedData platform,
            RpgPlayerSavedData rpg,
            RpgDefinitionSnapshot definitions,
            UUID transactionId,
            RpgAdminOperation operation) {
        PlatformSavedData.RpgAdminOperationBeginResult begin =
                platform.beginRpgAdminOperation(transactionId, operation);
        return switch (begin.status()) {
            case SUCCESS, DUPLICATE -> applyPending(platform, rpg, definitions, transactionId,
                    begin.operation().orElseThrow());
            case CONFLICT -> result(Status.TRANSACTION_CONFLICT, transactionId, 0, 0, false);
            case FULL -> result(Status.JOURNAL_FULL, transactionId, 0, 0, false);
            case INVALID -> result(Status.INVALID_REQUEST, transactionId, 0, 0, false);
        };
    }

    private static Result applyPending(
            PlatformSavedData platform,
            RpgPlayerSavedData rpg,
            RpgDefinitionSnapshot definitions,
            UUID transactionId,
            RpgAdminOperation operation) {
        String source = "admin:" + operation.actorId();
        RpgAdministrativeMutationService.Result applied = switch (operation.action()) {
            case XP_ADJUST -> RpgAdministrativeMutationService.adjustActivityXp(
                    rpg, definitions, operation.playerId(), operation.target(), operation.delta(),
                    operation.expectedBefore(), operation.timestampEpochMillis(), transactionId, source);
            case PROMOTION_RECOVERY -> RpgAdministrativeMutationService.recoverPromotion(
                    rpg, definitions, operation.playerId(), operation.target(),
                    operation.timestampEpochMillis(), transactionId, source);
            case SKILL_RESET -> RpgAdministrativeMutationService.applySkillReset(
                    rpg, definitions, operation.playerId(), operation.resetPlan().orElseThrow(),
                    operation.timestampEpochMillis(), transactionId, source);
        };
        if (applied.status() != RpgAdministrativeMutationService.Status.SUCCESS
                && applied.status() != RpgAdministrativeMutationService.Status.DUPLICATE) {
            return result(mapApplied(applied.status()), transactionId,
                    applied.beforeAmount(), applied.afterAmount(), false);
        }
        AuditEntry audit = audit(operation, transactionId);
        if (!platform.completeRpgAdminOperation(transactionId, operation, audit)) {
            return result(Status.COMPLETION_FAILED, transactionId,
                    operation.expectedBefore(), expectedAfter(operation), false);
        }
        return result(Status.SUCCESS, transactionId,
                operation.expectedBefore(), expectedAfter(operation), true);
    }

    private static AuditEntry audit(RpgAdminOperation operation, UUID transactionId) {
        String before;
        String after;
        if (operation.action() == RpgAdminOperation.Action.XP_ADJUST) {
            before = "xp=" + operation.expectedBefore();
            after = "xp=" + expectedAfter(operation) + ";delta=" + operation.delta();
        } else if (operation.action() == RpgAdminOperation.Action.PROMOTION_RECOVERY) {
            before = "absent";
            after = "promoted=" + operation.target();
        } else {
            SkillResetPlan plan = operation.resetPlan().orElseThrow();
            before = "learned=" + plan.removedSkills().size() + ";refund=" + plan.refundedPoints();
            after = "removed=" + plan.removedSkills().size() + ";refund=" + plan.refundedPoints();
        }
        return new AuditEntry(
                operation.timestampEpochMillis(),
                operation.actorId(),
                action(operation.action()),
                operation.playerId().toString() + ":" + operation.target(),
                Optional.empty(),
                Optional.empty(),
                before,
                after,
                operation.reason(),
                transactionId);
    }

    private static Result denied(
            PlatformSavedData platform,
            UUID actorId,
            UUID playerId,
            RpgAdminOperation.Action action,
            Identifier target,
            Status status,
            long timestamp,
            UUID transactionId) {
        UUID auditId = transactionId == null || ZERO_UUID.equals(transactionId) ? UUID.randomUUID() : transactionId;
        boolean recorded = platform != null && platform.isWritable() && actorId != null
                && playerId != null && target != null && action != null && timestamp >= 0
                && platform.appendDeniedAudit(new AuditEntry(
                        timestamp,
                        actorId,
                        Identifier.fromNamespaceAndPath(Rovenfall.MOD_ID,
                                "rpg_admin_" + action.getSerializedName() + "_denied"),
                        playerId + ":" + target,
                        Optional.empty(),
                        Optional.empty(),
                        "unchanged",
                        "unchanged",
                        status.name().toLowerCase(Locale.ROOT),
                        auditId), DENIED_AUDIT_INTERVAL_MILLIS);
        return result(status, transactionId, 0, 0, recorded);
    }

    private static Identifier action(RpgAdminOperation.Action action) {
        return Identifier.fromNamespaceAndPath(Rovenfall.MOD_ID,
                "rpg_admin_" + action.getSerializedName());
    }

    private static long expectedAfter(RpgAdminOperation operation) {
        if (operation.action() != RpgAdminOperation.Action.XP_ADJUST) {
            return operation.resetPlan().map(SkillResetPlan::refundedPoints).orElse(0L);
        }
        try {
            return Math.addExact(operation.expectedBefore(), operation.delta());
        } catch (ArithmeticException exception) {
            return operation.expectedBefore();
        }
    }

    private static Status mapPreparation(RpgSkillService.Status status) {
        return switch (status) {
            case UNKNOWN_SKILL -> Status.INVALID_REQUEST;
            case UNKNOWN_CAREER -> Status.UNKNOWN_CAREER;
            case READ_ONLY -> Status.READ_ONLY;
            case NOTHING_TO_RESET -> Status.NOTHING_TO_RESET;
            case OVERFLOW -> Status.OVERFLOW;
            default -> Status.STATE_CONFLICT;
        };
    }

    private static Status mapApplied(RpgAdministrativeMutationService.Status status) {
        return switch (status) {
            case INVALID_REQUEST -> Status.INVALID_REQUEST;
            case READ_ONLY -> Status.READ_ONLY;
            case DUPLICATE -> Status.DUPLICATE;
            case UNKNOWN_ACTIVITY -> Status.UNKNOWN_ACTIVITY;
            case UNKNOWN_CAREER -> Status.UNKNOWN_CAREER;
            case ALREADY_PROMOTED -> Status.ALREADY_PROMOTED;
            case MISSING_PARENT -> Status.MISSING_PARENT;
            case PARENT_RANK_TOO_LOW -> Status.PARENT_RANK_TOO_LOW;
            case NOTHING_TO_RESET -> Status.NOTHING_TO_RESET;
            case STATE_CONFLICT -> Status.STATE_CONFLICT;
            case OVERFLOW -> Status.OVERFLOW;
            case STATE_FULL -> Status.STATE_FULL;
            case SUCCESS -> Status.SUCCESS;
        };
    }

    private static Result result(
            Status status, UUID transactionId, long before, long after, boolean auditRecorded) {
        return new Result(status, transactionId, before, after, auditRecorded);
    }
}
