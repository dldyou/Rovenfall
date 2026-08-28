package org.dldyou.rovenfall.administration;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.dldyou.rovenfall.Rovenfall;
import org.dldyou.rovenfall.mobs.BossEncounterRuntime;
import org.dldyou.rovenfall.mobs.BossEncounterSavedData;
import org.dldyou.rovenfall.mobs.BossEncounterState;
import org.dldyou.rovenfall.mobs.BossRewardOperation;
import org.dldyou.rovenfall.mobs.BossRewardSavedData;
import org.dldyou.rovenfall.mobs.MobContentReloadListener;
import org.dldyou.rovenfall.mobs.MobContentSnapshot;
import org.dldyou.rovenfall.rpg.RpgDefinitionReloadListener;
import org.dldyou.rovenfall.rpg.RpgPlayerSavedData;
import org.dldyou.rovenfall.rpg.RpgPlayerState;
import org.dldyou.rovenfall.rpg.RpgSkillService;
import org.dldyou.rovenfall.rpg.SkillResetPlan;
import org.dldyou.rovenfall.world.ProtectedRegion;

/** Click-time authorization and exact-state confirmation for RPG, content, and boss operations. */
final class AdministrationRpgBossActionService {
    private AdministrationRpgBossActionService() {
    }

    static Result execute(ServerPlayer actor, PendingAction action) {
        if (actor == null || action == null || actor.level().getServer() == null
                || !actor.level().getServer().isSameThread()) {
            return new Result(Status.FAILED, "invalid_request", action == null ? null : action.transactionId());
        }
        MinecraftServer server = actor.level().getServer();
        PlatformSavedData platform = PlatformSavedData.get(server);
        AdminRole role = AdministrationControlCenterMenu.resolveRole(actor).orElse(null);
        if (!allowed(role, action)) {
            auditRejected(platform, actor.getUUID(), action, "unauthorized");
            return new Result(Status.UNAUTHORIZED, "unauthorized", action.transactionId());
        }
        if (!fresh(server, action)) {
            auditRejected(platform, actor.getUUID(), action, "stale_confirmation");
            return new Result(Status.STALE_CONFIRMATION, "stale_confirmation", action.transactionId());
        }
        boolean override = platform.roleOf(actor.getUUID()).isEmpty();
        long now = Instant.now().toEpochMilli();
        if (action instanceof XpAction value) {
            return fromRpg(RpgAdministrationService.adjustActivityXp(
                    platform, RpgPlayerSavedData.get(server), RpgDefinitionReloadListener.snapshot(server),
                    actor.getUUID(), override, value.playerId(), value.activityId(), value.delta(), value.reason(),
                    now, value.transactionId()));
        }
        if (action instanceof PromotionAction value) {
            return fromRpg(RpgAdministrationService.recoverPromotion(
                    platform, RpgPlayerSavedData.get(server), RpgDefinitionReloadListener.snapshot(server),
                    actor.getUUID(), override, value.playerId(), value.careerId(), value.reason(),
                    now, value.transactionId()));
        }
        if (action instanceof SkillResetAction value) {
            return fromRpg(RpgAdministrationService.resetSkills(
                    platform, RpgPlayerSavedData.get(server), RpgDefinitionReloadListener.snapshot(server),
                    actor.getUUID(), override, value.playerId(), value.mode(), value.target(), value.reason(),
                    now, value.transactionId()));
        }
        if (action instanceof BossResetAction value) {
            return fromBoss(BossAdministrationService.reset(
                    server, actor.getUUID(), override, value.encounterId(), value.reason(),
                    now, value.transactionId()));
        }
        if (action instanceof BossRecoverAction value) {
            return fromBoss(BossAdministrationService.recover(
                    server, actor.getUUID(), override, value.reason(), now, value.transactionId()));
        }
        if (action instanceof ReloadAction value) {
            return fromReload(AdministrationContentReloadService.request(
                    server, actor.getUUID(), override, value.reason(), now, value.transactionId()));
        }
        return new Result(Status.FAILED, "unsupported_action", action.transactionId());
    }

    static boolean fresh(MinecraftServer server, PendingAction action) {
        if (server == null || action == null) {
            return false;
        }
        if (action instanceof RpgAction value) {
            if (!RpgPlayerSavedData.get(server).state(value.playerId()).equals(value.expectedPlayerState())
                    || RpgDefinitionReloadListener.revision(server) != value.expectedDefinitionRevision()) {
                return false;
            }
            if (action instanceof SkillResetAction reset) {
                RpgSkillService.ResetPreparation prepared = RpgSkillService.prepareReset(
                        RpgPlayerSavedData.get(server), RpgDefinitionReloadListener.snapshot(server),
                        reset.playerId(), reset.mode(), reset.target());
                return prepared.status() == RpgSkillService.Status.SUCCESS
                        && prepared.plan().equals(Optional.of(reset.expectedPlan()));
            }
            return true;
        }
        if (action instanceof BossResetAction value) {
            return bossResetEvidence(server, value.encounterId()).equals(value.expectedEvidence());
        }
        if (action instanceof BossRecoverAction value) {
            return bossRecoveryEvidence(server).equals(value.expectedEvidence());
        }
        if (action instanceof ReloadAction value) {
            return RpgDefinitionReloadListener.revision(server) == value.expectedRpgRevision()
                    && MobContentReloadListener.snapshot(server) == value.expectedMobSnapshot()
                    && AdministrationContentReloadService.snapshot(server).status()
                            != AdministrationContentReloadService.Status.IN_PROGRESS;
        }
        return false;
    }

    static boolean allowed(AdminRole role, PendingAction action) {
        if (role == null || action == null) {
            return false;
        }
        if (action instanceof XpAction) {
            return role == AdminRole.MODERATOR || role == AdminRole.OWNER;
        }
        if (action instanceof PromotionAction || action instanceof SkillResetAction || action instanceof ReloadAction) {
            return role == AdminRole.CONTENT_MANAGER || role == AdminRole.OWNER;
        }
        if (action instanceof BossResetAction || action instanceof BossRecoverAction) {
            return role == AdminRole.OWNER;
        }
        return false;
    }

    static BossResetEvidence bossResetEvidence(MinecraftServer server, UUID encounterId) {
        Optional<BossEncounterState> encounter = BossEncounterSavedData.get(server).encounter(encounterId);
        Optional<ProtectedRegion> arena = PlatformSavedData.get(server)
                .protectedRegion(BossEncounterRuntime.regionId(encounterId));
        List<Map.Entry<UUID, BossRewardOperation>> rewards = BossRewardSavedData.get(server).operations().stream()
                .filter(entry -> entry.getValue().encounterId().equals(encounterId))
                .toList();
        return new BossResetEvidence(encounter, arena, rewards);
    }

    static BossRecoveryEvidence bossRecoveryEvidence(MinecraftServer server) {
        List<BossEncounterState> encounters = BossEncounterSavedData.get(server).activeEncounters();
        List<Map.Entry<UUID, BossRewardOperation>> pending = BossRewardSavedData.get(server).pendingOperations();
        List<Map.Entry<Identifier, ProtectedRegion>> orphanArenas = PlatformSavedData.get(server).protectedRegions()
                .stream()
                .filter(entry -> BossEncounterRuntime.isOwnedArenaRegion(server, entry.getKey(), entry.getValue()))
                .filter(entry -> encounters.stream().noneMatch(encounter ->
                        BossEncounterRuntime.regionId(encounter.encounterId()).equals(entry.getKey())))
                .toList();
        return new BossRecoveryEvidence(encounters, pending, orphanArenas);
    }

    private static Result fromRpg(RpgAdministrationService.Result result) {
        return switch (result.status()) {
            case SUCCESS -> new Result(Status.SUCCESS, "success", result.transactionId());
            case DUPLICATE -> new Result(Status.DUPLICATE, "duplicate_transaction", result.transactionId());
            case UNAUTHORIZED -> new Result(Status.UNAUTHORIZED, "unauthorized", result.transactionId());
            default -> new Result(Status.FAILED,
                    result.status().name().toLowerCase(java.util.Locale.ROOT), result.transactionId());
        };
    }

    private static Result fromBoss(BossAdministrationService.Result result) {
        return switch (result.status()) {
            case SUCCESS -> new Result(Status.SUCCESS, "success", result.transactionId());
            case DUPLICATE -> new Result(Status.DUPLICATE, "duplicate_transaction", result.transactionId());
            case UNAUTHORIZED -> new Result(Status.UNAUTHORIZED, "unauthorized", result.transactionId());
            default -> new Result(Status.FAILED,
                    result.status().name().toLowerCase(java.util.Locale.ROOT), result.transactionId());
        };
    }

    private static Result fromReload(AdministrationContentReloadService.Result result) {
        return switch (result.status()) {
            case REQUESTED -> new Result(Status.REQUESTED, "reload_requested", result.transactionId());
            case DUPLICATE -> new Result(Status.DUPLICATE, "duplicate_transaction", result.transactionId());
            case UNAUTHORIZED -> new Result(Status.UNAUTHORIZED, "unauthorized", result.transactionId());
            default -> new Result(Status.FAILED,
                    result.status().name().toLowerCase(java.util.Locale.ROOT), result.transactionId());
        };
    }

    private static void auditRejected(
            PlatformSavedData state, UUID actorId, PendingAction action, String reason) {
        if (!state.isWritable()) {
            return;
        }
        long now = Instant.now().toEpochMilli();
        state.appendDeniedAudit(new AuditEntry(
                now, actorId, Identifier.fromNamespaceAndPath(Rovenfall.MOD_ID, "admin_gui_" + reason + "_denied"),
                action.targetText(), Optional.empty(), Optional.empty(), "unchanged", "unchanged",
                reason, action.transactionId()), 1_000L);
    }

    sealed interface PendingAction permits RpgAction, BossResetAction, BossRecoverAction, ReloadAction {
        UUID transactionId();

        String reason();

        String targetText();
    }

    sealed interface RpgAction extends PendingAction permits XpAction, PromotionAction, SkillResetAction {
        UUID playerId();

        RpgPlayerState expectedPlayerState();

        long expectedDefinitionRevision();

        @Override
        default String targetText() {
            return playerId().toString();
        }
    }

    record XpAction(
            UUID transactionId,
            UUID playerId,
            RpgPlayerState expectedPlayerState,
            long expectedDefinitionRevision,
            Identifier activityId,
            long delta,
            String reason) implements RpgAction {
    }

    record PromotionAction(
            UUID transactionId,
            UUID playerId,
            RpgPlayerState expectedPlayerState,
            long expectedDefinitionRevision,
            Identifier careerId,
            String reason) implements RpgAction {
    }

    record SkillResetAction(
            UUID transactionId,
            UUID playerId,
            RpgPlayerState expectedPlayerState,
            long expectedDefinitionRevision,
            SkillResetPlan.Mode mode,
            Identifier target,
            SkillResetPlan expectedPlan,
            String reason) implements RpgAction {
    }

    record BossResetAction(
            UUID transactionId,
            UUID encounterId,
            BossResetEvidence expectedEvidence,
            String reason) implements PendingAction {
        @Override
        public String targetText() {
            return encounterId.toString();
        }
    }

    record BossRecoverAction(
            UUID transactionId,
            BossRecoveryEvidence expectedEvidence,
            String reason) implements PendingAction {
        @Override
        public String targetText() {
            return "all";
        }
    }

    record ReloadAction(
            UUID transactionId,
            long expectedRpgRevision,
            MobContentSnapshot expectedMobSnapshot,
            String reason) implements PendingAction {
        @Override
        public String targetText() {
            return "definitions";
        }
    }

    record BossResetEvidence(
            Optional<BossEncounterState> encounter,
            Optional<ProtectedRegion> arena,
            List<Map.Entry<UUID, BossRewardOperation>> rewards) {
        BossResetEvidence {
            encounter = encounter == null ? Optional.empty() : encounter;
            arena = arena == null ? Optional.empty() : arena;
            rewards = List.copyOf(rewards);
        }
    }

    record BossRecoveryEvidence(
            List<BossEncounterState> encounters,
            List<Map.Entry<UUID, BossRewardOperation>> pendingRewards,
            List<Map.Entry<Identifier, ProtectedRegion>> orphanArenas) {
        BossRecoveryEvidence {
            encounters = List.copyOf(encounters);
            pendingRewards = List.copyOf(pendingRewards);
            orphanArenas = List.copyOf(orphanArenas);
        }
    }

    enum Status {
        SUCCESS,
        REQUESTED,
        DUPLICATE,
        STALE_CONFIRMATION,
        UNAUTHORIZED,
        FAILED
    }

    record Result(Status status, String detail, UUID transactionId) {
        boolean succeeded() {
            return status == Status.SUCCESS || status == Status.REQUESTED || status == Status.DUPLICATE;
        }
    }
}
