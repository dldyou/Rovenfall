package org.dldyou.rovenfall.rpg;

import com.mojang.logging.LogUtils;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import org.dldyou.rovenfall.administration.EconomyConfig;
import org.dldyou.rovenfall.administration.PlatformSavedData;
import org.dldyou.rovenfall.administration.RpgSkillOperation;
import org.dldyou.rovenfall.administration.RpgSkillPaymentService;
import org.slf4j.Logger;

/** Coordinates an exact reset plan across independently persisted economy and RPG roots. */
public final class RpgSkillResetCoordinator {
    private static final Logger LOGGER = LogUtils.getLogger();

    public enum Status {
        SUCCESS,
        PREPARATION_FAILED,
        PAYMENT_FAILED,
        RPG_FAILED,
        COMPLETION_FAILED
    }

    public record Result(
            Status status,
            RpgSkillService.Status rpgStatus,
            Optional<RpgSkillPaymentService.Status> paymentStatus,
            long cost,
            long balance,
            UUID transactionId) {
        public Result {
            paymentStatus = paymentStatus == null ? Optional.empty() : paymentStatus;
        }
    }

    private RpgSkillResetCoordinator() {
    }

    public static Result reset(
            MinecraftServer server,
            UUID playerId,
            SkillResetPlan.Mode mode,
            Identifier target,
            long timestampEpochMillis,
            UUID transactionId) {
        return reset(
                PlatformSavedData.get(server), RpgPlayerSavedData.get(server),
                RpgDefinitionReloadListener.snapshot(server), playerId, mode, target,
                ActivityXpConfig.skillResetCost(mode), timestampEpochMillis, transactionId,
                EconomyConfig.initialBalance(), EconomyConfig.maximumBalance());
    }

    static Result reset(
            PlatformSavedData platform,
            RpgPlayerSavedData rpg,
            RpgDefinitionSnapshot definitions,
            UUID playerId,
            SkillResetPlan.Mode mode,
            Identifier target,
            long cost,
            long timestampEpochMillis,
            UUID transactionId,
            long initialBalance,
            long maximumBalance) {
        RpgSkillOperation existing = platform.rpgSkillOperation(transactionId).orElse(null);
        SkillResetPlan plan;
        if (existing != null) {
            if (!existing.playerId().equals(playerId) || existing.mode() != mode
                    || !existing.target().equals(target) || existing.cost() != cost) {
                return new Result(Status.PAYMENT_FAILED, RpgSkillService.Status.STATE_CONFLICT,
                        Optional.of(RpgSkillPaymentService.Status.TRANSACTION_CONFLICT), cost,
                        platform.economyBalance(playerId).orElse(0L), transactionId);
            }
            if (existing.phase() == RpgSkillOperation.Phase.COMPLETED
                    && RpgSkillService.hasTransaction(rpg.state(playerId), transactionId)) {
                return new Result(Status.SUCCESS, RpgSkillService.Status.DUPLICATE,
                        Optional.of(RpgSkillPaymentService.Status.DUPLICATE_COMPLETED), cost,
                        platform.economyBalance(playerId).orElse(0L), transactionId);
            }
            plan = existing.plan().orElse(null);
            if (plan == null) {
                return new Result(Status.RPG_FAILED, RpgSkillService.Status.STATE_CONFLICT,
                        Optional.of(RpgSkillPaymentService.Status.DUPLICATE_COMPLETED), cost,
                        platform.economyBalance(playerId).orElse(0L), transactionId);
            }
        } else {
            RpgSkillService.ResetPreparation preparation = RpgSkillService.prepareReset(
                    rpg, definitions, playerId, mode, target);
            if (preparation.status() != RpgSkillService.Status.SUCCESS) {
                return new Result(Status.PREPARATION_FAILED, preparation.status(), Optional.empty(), cost,
                        platform.economyBalance(playerId).orElse(0L), transactionId);
            }
            plan = preparation.plan().orElseThrow();
        }
        RpgSkillPaymentService.Result payment = RpgSkillPaymentService.begin(
                platform, playerId, plan, cost, timestampEpochMillis, transactionId,
                initialBalance, maximumBalance);
        if (!paymentAccepted(payment.status())) {
            return new Result(Status.PAYMENT_FAILED, RpgSkillService.Status.SUCCESS,
                    Optional.of(payment.status()), cost, payment.balance(), transactionId);
        }
        SkillResetPlan paidPlan = payment.operation().flatMap(RpgSkillOperation::plan).orElse(plan);
        RpgSkillService.Result applied = RpgSkillService.applyReset(
                rpg, definitions, playerId, paidPlan, cost, timestampEpochMillis, transactionId);
        if (applied.status() != RpgSkillService.Status.SUCCESS
                && applied.status() != RpgSkillService.Status.DUPLICATE) {
            return new Result(Status.RPG_FAILED, applied.status(), Optional.of(payment.status()),
                    cost, payment.balance(), transactionId);
        }
        RpgSkillPaymentService.Result completed = RpgSkillPaymentService.complete(
                platform, playerId, transactionId, timestampEpochMillis);
        if (completed.status() != RpgSkillPaymentService.Status.SUCCESS
                && completed.status() != RpgSkillPaymentService.Status.DUPLICATE_COMPLETED) {
            return new Result(Status.COMPLETION_FAILED, applied.status(), Optional.of(completed.status()),
                    cost, completed.balance(), transactionId);
        }
        return new Result(Status.SUCCESS, applied.status(), Optional.of(completed.status()),
                cost, completed.balance(), transactionId);
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
        recoverPlayer(
                PlatformSavedData.get(server), RpgPlayerSavedData.get(server),
                RpgDefinitionReloadListener.snapshot(server), playerId, System.currentTimeMillis(),
                EconomyConfig.initialBalance(), EconomyConfig.maximumBalance());
    }

    static void recoverPlayer(
            PlatformSavedData platform,
            RpgPlayerSavedData rpg,
            RpgDefinitionSnapshot definitions,
            UUID playerId,
            long timestampEpochMillis,
            long initialBalance,
            long maximumBalance) {
        if (!platform.isWritable() || !rpg.isWritable()) {
            LOGGER.error("RPG skill reset recovery is disabled because persistence is read-only");
            return;
        }
        for (var entry : platform.pendingRpgSkillOperations(playerId)) {
            RpgSkillOperation operation = entry.getValue();
            SkillResetPlan plan = operation.plan().orElse(null);
            if (plan == null) {
                LOGGER.error("Pending RPG skill reset {} has no plan", entry.getKey());
                continue;
            }
            RpgSkillService.Result applied = RpgSkillService.applyReset(
                    rpg, definitions, playerId, plan,
                    operation.cost(), operation.timestampEpochMillis(), entry.getKey());
            if (applied.status() == RpgSkillService.Status.SUCCESS
                    || applied.status() == RpgSkillService.Status.DUPLICATE) {
                RpgSkillPaymentService.Result completed = RpgSkillPaymentService.complete(
                        platform, playerId, entry.getKey(), timestampEpochMillis);
                if (completed.status() != RpgSkillPaymentService.Status.SUCCESS
                        && completed.status() != RpgSkillPaymentService.Status.DUPLICATE_COMPLETED) {
                    LOGGER.error("Could not complete recovered RPG skill reset {} ({})",
                            entry.getKey(), completed.status());
                }
            } else {
                LOGGER.error("Could not apply recovered RPG skill reset {} ({})", entry.getKey(), applied.status());
            }
        }

        for (RpgPlayerState.ProgressionProvenance evidence : rpg.state(playerId).careerProvenance()) {
            if (evidence.kind() != RpgPlayerState.ProgressionProvenance.Kind.SKILL_RESET
                    || platform.rpgSkillOperation(evidence.transactionId()).isPresent()) {
                continue;
            }
            SkillResetPlan.Mode mode = parseMode(evidence.source()).orElse(null);
            if (mode == null) {
                LOGGER.error("RPG skill reset {} has invalid recovery evidence", evidence.transactionId());
                continue;
            }
            RpgSkillPaymentService.Result recovered = RpgSkillPaymentService.recoverCompleted(
                    platform, playerId, mode, evidence.target(), evidence.amount(), evidence.timestamp(),
                    evidence.transactionId(), initialBalance, maximumBalance);
            if (recovered.status() != RpgSkillPaymentService.Status.SUCCESS
                    && recovered.status() != RpgSkillPaymentService.Status.DUPLICATE_COMPLETED) {
                LOGGER.error("Could not recover RPG skill reset payment {} ({})",
                        evidence.transactionId(), recovered.status());
            }
        }
    }

    private static boolean paymentAccepted(RpgSkillPaymentService.Status status) {
        return status == RpgSkillPaymentService.Status.SUCCESS
                || status == RpgSkillPaymentService.Status.DUPLICATE_PENDING
                || status == RpgSkillPaymentService.Status.DUPLICATE_COMPLETED;
    }

    private static Optional<SkillResetPlan.Mode> parseMode(String source) {
        if (source == null || !source.startsWith("skill_reset:")) {
            return Optional.empty();
        }
        String value = source.substring("skill_reset:".length()).toUpperCase(Locale.ROOT);
        try {
            return Optional.of(SkillResetPlan.Mode.valueOf(value));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private static Result result(
            Status status,
            RpgSkillService.Status rpgStatus,
            RpgSkillPaymentService.Status paymentStatus,
            SkillResetPlan.Mode mode,
            long balance,
            UUID transactionId) {
        return new Result(status, rpgStatus, Optional.ofNullable(paymentStatus),
                mode == null ? 0 : ActivityXpConfig.skillResetCost(mode), balance, transactionId);
    }
}
