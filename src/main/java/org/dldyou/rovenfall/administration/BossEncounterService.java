package org.dldyou.rovenfall.administration;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.dldyou.rovenfall.Rovenfall;
import org.dldyou.rovenfall.mobs.BossEncounter;
import org.dldyou.rovenfall.mobs.BossState;

public final class BossEncounterService {
    public static final long REWARD_AMOUNT = 250L;
    public static final long REWARD_COOLDOWN_MILLIS = 6L * 60 * 60 * 1_000;
    public static final double MINIMUM_CONTRIBUTION = 20.0;
    public static final double MINIMUM_CONTRIBUTION_SHARE = 0.05;
    public static final int PLAYER_CHALLENGE_RADIUS = 24;
    static final String PLAYER_CHALLENGE_REASON = "player_challenge_sigil";
    private static final UUID ZERO_UUID = new UUID(0, 0);
    private static final long DENIED_AUDIT_INTERVAL_MILLIS = 1_000L;

    private BossEncounterService() {
    }

    public static StartResult startPlayerChallenge(
            PlatformSavedData state,
            UUID actorId,
            ResourceKey<Level> dimension,
            BlockPos origin,
            long timestampEpochMillis,
            UUID encounterId,
            Predicate<BossEncounter> spawner) {
        return start(
                state,
                actorId,
                actorId != null,
                dimension,
                origin,
                PLAYER_CHALLENGE_RADIUS,
                PLAYER_CHALLENGE_REASON,
                timestampEpochMillis,
                encounterId,
                spawner);
    }

    public static StartResult start(
            PlatformSavedData state,
            UUID actorId,
            boolean authorizationOverride,
            ResourceKey<Level> dimension,
            BlockPos origin,
            int radius,
            String reason,
            long timestampEpochMillis,
            UUID encounterId,
            Predicate<BossEncounter> spawner) {
        if (!state.isWritable()) {
            return new StartResult(StartStatus.READ_ONLY_SCHEMA, Optional.empty(), encounterId, false);
        }
        if (!canManage(state, actorId, authorizationOverride)) {
            return deniedStart(state, actorId, dimension, origin, radius, reason, timestampEpochMillis,
                    encounterId, StartStatus.UNAUTHORIZED, "unauthorized");
        }
        if (dimension == null || origin == null || !Level.isInSpawnableBounds(origin)
                || !dimension.equals(WorldCombatService.WILDERNESS_DIMENSION)
                || radius < BossEncounter.MIN_RADIUS || radius > BossEncounter.MAX_RADIUS
                || timestampEpochMillis < 0 || encounterId == null || ZERO_UUID.equals(encounterId)
                || spawner == null) {
            return deniedStart(state, actorId, dimension, origin, radius, reason, timestampEpochMillis,
                    encounterId, StartStatus.INVALID_REQUEST, "invalid_request");
        }
        String normalizedReason = reason == null ? "" : reason.strip();
        if (normalizedReason.isEmpty() || normalizedReason.length() > AdministrationService.MAX_REASON_LENGTH) {
            return deniedStart(state, actorId, dimension, origin, radius, reason, timestampEpochMillis,
                    encounterId, StartStatus.INVALID_REASON, "invalid_reason");
        }
        Optional<BossEncounter> current = state.bossEncounter();
        if (current.filter(value -> value.encounterId().equals(encounterId)).isPresent()) {
            return new StartResult(StartStatus.DUPLICATE_TRANSACTION, current, encounterId, false);
        }
        if (state.hasTransaction(encounterId, timestampEpochMillis)) {
            return deniedStart(state, actorId, dimension, origin, radius, normalizedReason, timestampEpochMillis,
                    encounterId, StartStatus.TRANSACTION_ID_CONFLICT, "transaction_id_conflict");
        }
        if (current.map(BossEncounter::active).orElse(false)) {
            return deniedStart(state, actorId, dimension, origin, radius, normalizedReason, timestampEpochMillis,
                    encounterId, StartStatus.ENCOUNTER_ACTIVE, "encounter_active");
        }
        if (!state.canCommitBossStart(encounterId, timestampEpochMillis)) {
            return deniedStart(state, actorId, dimension, origin, radius, normalizedReason, timestampEpochMillis,
                    encounterId, StartStatus.TRANSACTION_LEDGER_FULL, "transaction_ledger_full");
        }

        UUID bossId = UUID.nameUUIDFromBytes(("rovenfall:boss:" + encounterId)
                .getBytes(StandardCharsets.UTF_8));
        BossEncounter encounter = new BossEncounter(
                encounterId,
                bossId,
                dimension,
                origin,
                radius,
                BossEncounter.Status.INTRO,
                0,
                timestampEpochMillis,
                timestampEpochMillis,
                Map.of(),
                java.util.Set.of());
        if (!spawner.test(encounter)) {
            return deniedStart(state, actorId, dimension, origin, radius, normalizedReason, timestampEpochMillis,
                    encounterId, StartStatus.SPAWN_FAILED, "spawn_failed");
        }
        BossState updated = state.bossState().withEncounter(encounter);
        state.commitBossStart(updated, encounterId, timestampEpochMillis, audit(
                timestampEpochMillis,
                actorId,
                "boss_started",
                encounter.encounterId().toString(),
                encounter.dimension(),
                encounter.origin(),
                current.map(value -> value.status().name().toLowerCase()).orElse("none"),
                "intro",
                normalizedReason,
                encounterId));
        return new StartResult(StartStatus.SUCCESS, Optional.of(encounter), encounterId, true);
    }

    public static boolean activate(PlatformSavedData state, UUID encounterId, long timestampEpochMillis) {
        BossEncounter current = matching(state, encounterId).orElse(null);
        if (current == null || current.status() != BossEncounter.Status.INTRO) {
            return false;
        }
        BossEncounter updated = current.withStatus(BossEncounter.Status.ACTIVE, 1, timestampEpochMillis);
        state.commitBossTransition(state.bossState().withEncounter(updated), audit(
                timestampEpochMillis,
                AdministrationService.SYSTEM_ACTOR,
                "boss_phase_changed",
                encounterId.toString(),
                current.dimension(),
                current.origin(),
                "intro:0",
                "active:1",
                "intro_complete",
                UUID.randomUUID()));
        return true;
    }

    public static boolean observePhase(
            PlatformSavedData state, UUID encounterId, int phase, long timestampEpochMillis) {
        BossEncounter current = matching(state, encounterId).orElse(null);
        if (current == null || current.status() != BossEncounter.Status.ACTIVE
                || phase <= current.phase() || phase > 3) {
            return false;
        }
        BossEncounter updated = current.withStatus(BossEncounter.Status.ACTIVE, phase, timestampEpochMillis);
        state.commitBossTransition(state.bossState().withEncounter(updated), audit(
                timestampEpochMillis,
                AdministrationService.SYSTEM_ACTOR,
                "boss_phase_changed",
                encounterId.toString(),
                current.dimension(),
                current.origin(),
                Integer.toString(current.phase()),
                Integer.toString(phase),
                "health_threshold",
                UUID.randomUUID()));
        return true;
    }

    public static boolean recordContribution(
            PlatformSavedData state,
            UUID encounterId,
            UUID bossId,
            UUID playerId,
            BlockPos playerPosition,
            double amount,
            long timestampEpochMillis) {
        BossEncounter current = matching(state, encounterId).orElse(null);
        if (current == null || current.status() != BossEncounter.Status.ACTIVE
                || !current.bossId().equals(bossId) || playerId == null || playerPosition == null
                || !current.protects(current.dimension(), playerPosition)
                || !Double.isFinite(amount) || amount <= 0
                || current.contributions().size() >= BossEncounter.MAX_CONTRIBUTORS
                && !current.contributions().containsKey(playerId)) {
            return false;
        }
        BossEncounter updated = current.withContribution(
                playerId, Math.min(amount, BossEncounter.MAX_CONTRIBUTION_PER_PLAYER), timestampEpochMillis);
        state.updateBossEncounter(state.bossState().withEncounter(updated));
        return true;
    }

    public static boolean auditBoundaryReturn(
            PlatformSavedData state, UUID encounterId, BlockPos from, long timestampEpochMillis) {
        BossEncounter current = matching(state, encounterId).orElse(null);
        if (current == null || !current.active() || from == null) {
            return false;
        }
        state.commitBossTransition(state.bossState(), audit(
                timestampEpochMillis,
                AdministrationService.SYSTEM_ACTOR,
                "boss_boundary_returned",
                encounterId.toString(),
                current.dimension(),
                current.origin(),
                from.toShortString(),
                current.origin().toShortString(),
                "arena_leash",
                UUID.randomUUID()));
        return true;
    }

    public static boolean auditRecovered(
            PlatformSavedData state, UUID encounterId, long timestampEpochMillis) {
        BossEncounter current = matching(state, encounterId).orElse(null);
        if (current == null || !current.active()) {
            return false;
        }
        state.commitBossTransition(state.bossState(), audit(
                timestampEpochMillis,
                AdministrationService.SYSTEM_ACTOR,
                "boss_recovered",
                encounterId.toString(),
                current.dimension(),
                current.origin(),
                current.status().name().toLowerCase(),
                current.status().name().toLowerCase(),
                "entity_reloaded",
                UUID.randomUUID()));
        return true;
    }

    public static boolean beginRewards(PlatformSavedData state, UUID encounterId, long timestampEpochMillis) {
        BossEncounter current = matching(state, encounterId).orElse(null);
        if (current == null || current.status() != BossEncounter.Status.ACTIVE) {
            return false;
        }
        BossEncounter updated = current.withStatus(
                BossEncounter.Status.REWARDING, current.phase(), timestampEpochMillis);
        state.commitBossTransition(state.bossState().withEncounter(updated), audit(
                timestampEpochMillis,
                AdministrationService.SYSTEM_ACTOR,
                "boss_defeated",
                encounterId.toString(),
                current.dimension(),
                current.origin(),
                "active",
                "rewarding",
                "boss_death",
                UUID.randomUUID()));
        return true;
    }

    public static List<RewardResult> settleRewards(PlatformSavedData state, long timestampEpochMillis) {
        BossEncounter snapshot = state.bossEncounter().orElse(null);
        if (snapshot == null || snapshot.status() != BossEncounter.Status.REWARDING) {
            return List.of();
        }
        List<RewardResult> results = new ArrayList<>();
        snapshot.contributions().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            BossEncounter current = state.bossEncounter().orElseThrow();
            if (current.settledPlayers().contains(entry.getKey())) {
                return;
            }
            double total = current.totalContribution();
            if (entry.getValue() < MINIMUM_CONTRIBUTION
                    || total <= 0 || entry.getValue() / total < MINIMUM_CONTRIBUTION_SHARE) {
                results.add(denyReward(state, current, entry.getKey(), entry.getValue(), total,
                        timestampEpochMillis, RewardStatus.INELIGIBLE, "insufficient_contribution"));
            } else if (state.bossState().rewardReadyAt(entry.getKey()) > timestampEpochMillis) {
                results.add(denyReward(state, current, entry.getKey(), entry.getValue(), total,
                        timestampEpochMillis, RewardStatus.COOLDOWN, "reward_cooldown"));
            } else {
                results.add(grantReward(state, current, entry.getKey(), entry.getValue(), total,
                        timestampEpochMillis));
            }
        });

        BossEncounter current = state.bossEncounter().orElseThrow();
        if (current.status() == BossEncounter.Status.REWARDING
                && current.settledPlayers().containsAll(current.contributions().keySet())) {
            BossEncounter completed = current.withStatus(
                    BossEncounter.Status.DEFEATED, current.phase(), timestampEpochMillis);
            state.commitBossTransition(state.bossState().withEncounter(completed), audit(
                    timestampEpochMillis,
                    AdministrationService.SYSTEM_ACTOR,
                    "boss_rewards_completed",
                    current.encounterId().toString(),
                    current.dimension(),
                    current.origin(),
                    "rewarding",
                    "defeated",
                    "all_contributors_settled",
                    UUID.randomUUID()));
        }
        return List.copyOf(results);
    }

    private static RewardResult grantReward(
            PlatformSavedData state,
            BossEncounter encounter,
            UUID playerId,
            double contribution,
            double totalContribution,
            long timestampEpochMillis) {
        UUID transactionId = rewardTransactionId(encounter.encounterId(), playerId);
        Optional<EconomyTransactionReceipt> retained = state.economyReceipt(transactionId);
        if (retained.isPresent()) {
            EconomyTransactionReceipt receipt = retained.orElseThrow();
            RewardStatus status = receipt.playerId().equals(playerId)
                    && receipt.kind() == EconomyTransactionReceipt.Kind.AWARD
                    && receipt.amount() == REWARD_AMOUNT
                    ? RewardStatus.DUPLICATE_TRANSACTION
                    : RewardStatus.TRANSACTION_ID_CONFLICT;
            return denyReward(state, encounter, playerId, contribution, totalContribution,
                    timestampEpochMillis, status, status.name().toLowerCase());
        }
        if (!state.canCommitEconomyTransaction(transactionId, timestampEpochMillis)) {
            return denyReward(state, encounter, playerId, contribution, totalContribution,
                    timestampEpochMillis, RewardStatus.TRANSACTION_LEDGER_FULL, "transaction_ledger_full");
        }
        long before = state.economyBalance(playerId).orElse(safeInitialBalance());
        long after;
        try {
            after = Math.addExact(before, REWARD_AMOUNT);
        } catch (ArithmeticException exception) {
            return denyReward(state, encounter, playerId, contribution, totalContribution,
                    timestampEpochMillis, RewardStatus.BALANCE_LIMIT, "balance_overflow");
        }
        if (after > safeMaximumBalance()) {
            return denyReward(state, encounter, playerId, contribution, totalContribution,
                    timestampEpochMillis, RewardStatus.BALANCE_LIMIT, "maximum_balance_exceeded");
        }
        long readyAt = timestampEpochMillis + REWARD_COOLDOWN_MILLIS;
        BossEncounter settled = encounter.settle(playerId, timestampEpochMillis);
        BossState updatedState = state.bossState().withReward(playerId, readyAt, settled);
        EconomyTransactionReceipt receipt = new EconomyTransactionReceipt(
                timestampEpochMillis,
                AdministrationService.SYSTEM_ACTOR,
                playerId,
                EconomyTransactionReceipt.Kind.AWARD,
                REWARD_AMOUNT,
                Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), 0,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                EconomyTransactionReceipt.CompensationDecision.NONE);
        List<EconomyAlert> alerts = EconomyMonitoringService.evaluate(
                state, transactionId, receipt, EconomyConfig.alertThresholds());
        state.commitBossReward(
                playerId,
                after,
                updatedState,
                transactionId,
                timestampEpochMillis,
                receipt,
                alerts,
                audit(timestampEpochMillis,
                        AdministrationService.SYSTEM_ACTOR,
                        "boss_reward_granted",
                        playerId.toString(),
                        encounter.dimension(),
                        encounter.origin(),
                        Long.toString(before),
                        Long.toString(after),
                        rewardEvidence(encounter, contribution, totalContribution, readyAt),
                        transactionId));
        EconomyMonitoringService.publish(alerts);
        return new RewardResult(RewardStatus.SUCCESS, playerId, REWARD_AMOUNT, readyAt, transactionId, true);
    }

    private static RewardResult denyReward(
            PlatformSavedData state,
            BossEncounter encounter,
            UUID playerId,
            double contribution,
            double totalContribution,
            long timestampEpochMillis,
            RewardStatus status,
            String reason) {
        BossEncounter settled = encounter.settle(playerId, timestampEpochMillis);
        UUID transactionId = rewardTransactionId(encounter.encounterId(), playerId);
        state.commitBossTransition(state.bossState().withEncounter(settled), audit(
                timestampEpochMillis,
                AdministrationService.SYSTEM_ACTOR,
                "boss_reward_denied",
                playerId.toString(),
                encounter.dimension(),
                encounter.origin(),
                "unsettled",
                "settled_without_reward",
                reason + ";" + rewardEvidence(
                        encounter, contribution, totalContribution, state.bossState().rewardReadyAt(playerId)),
                transactionId));
        return new RewardResult(
                status,
                playerId,
                0,
                state.bossState().rewardReadyAt(playerId),
                transactionId,
                true);
    }

    private static Optional<BossEncounter> matching(PlatformSavedData state, UUID encounterId) {
        if (state == null || encounterId == null) {
            return Optional.empty();
        }
        return state.bossEncounter().filter(encounter -> encounter.encounterId().equals(encounterId));
    }

    private static boolean canManage(
            PlatformSavedData state, UUID actorId, boolean authorizationOverride) {
        if (authorizationOverride || AdministrationService.SYSTEM_ACTOR.equals(actorId)) {
            return true;
        }
        AdminRole role = state.roleOf(actorId).orElse(null);
        return role == AdminRole.CONTENT_MANAGER || role == AdminRole.OWNER;
    }

    private static StartResult deniedStart(
            PlatformSavedData state,
            UUID actorId,
            ResourceKey<Level> dimension,
            BlockPos origin,
            int radius,
            String reason,
            long timestampEpochMillis,
            UUID encounterId,
            StartStatus status,
            String denialReason) {
        UUID safeActor = actorId == null ? AdministrationService.SYSTEM_ACTOR : actorId;
        UUID safeTransaction = encounterId == null || ZERO_UUID.equals(encounterId) ? UUID.randomUUID() : encounterId;
        long safeTimestamp = Math.max(0, timestampEpochMillis);
        boolean audited = state.appendDeniedAudit(audit(
                safeTimestamp,
                safeActor,
                "boss_start_denied",
                safeTransaction.toString(),
                dimension,
                origin,
                "unchanged",
                "unchanged",
                denialReason + ";radius=" + radius + ";reason=" + String.valueOf(reason),
                safeTransaction), DENIED_AUDIT_INTERVAL_MILLIS);
        return new StartResult(status, Optional.empty(), encounterId, audited);
    }

    private static UUID rewardTransactionId(UUID encounterId, UUID playerId) {
        return UUID.nameUUIDFromBytes(("rovenfall:boss_reward:" + encounterId + ":" + playerId)
                .getBytes(StandardCharsets.UTF_8));
    }

    private static long safeInitialBalance() {
        try {
            return EconomyConfig.initialBalance();
        } catch (IllegalStateException exception) {
            return EconomyConfig.DEFAULT_INITIAL_BALANCE;
        }
    }

    private static long safeMaximumBalance() {
        try {
            return EconomyConfig.maximumBalance();
        } catch (IllegalStateException exception) {
            return EconomyConfig.DEFAULT_MAXIMUM_BALANCE;
        }
    }

    private static String rewardEvidence(
            BossEncounter encounter, double contribution, double totalContribution, long readyAt) {
        return "encounter=" + encounter.encounterId()
                + ";contribution=" + contribution
                + ";total=" + totalContribution
                + ";ready_at=" + readyAt;
    }

    private static AuditEntry audit(
            long timestampEpochMillis,
            UUID actorId,
            String action,
            String target,
            ResourceKey<Level> dimension,
            BlockPos position,
            String before,
            String after,
            String reason,
            UUID transactionId) {
        return new AuditEntry(
                timestampEpochMillis,
                actorId,
                Identifier.fromNamespaceAndPath(Rovenfall.MOD_ID, action),
                target,
                dimension == null ? Optional.empty() : Optional.of(dimension.identifier()),
                position == null ? Optional.empty() : Optional.of(position.immutable()),
                before,
                after,
                reason,
                transactionId);
    }

    public enum StartStatus {
        SUCCESS,
        DUPLICATE_TRANSACTION,
        TRANSACTION_ID_CONFLICT,
        UNAUTHORIZED,
        INVALID_REQUEST,
        INVALID_REASON,
        ENCOUNTER_ACTIVE,
        TRANSACTION_LEDGER_FULL,
        SPAWN_FAILED,
        READ_ONLY_SCHEMA
    }

    public enum RewardStatus {
        SUCCESS,
        INELIGIBLE,
        COOLDOWN,
        DUPLICATE_TRANSACTION,
        TRANSACTION_ID_CONFLICT,
        TRANSACTION_LEDGER_FULL,
        BALANCE_LIMIT
    }

    public record StartResult(
            StartStatus status,
            Optional<BossEncounter> encounter,
            UUID transactionId,
            boolean auditRecorded) {
        public StartResult {
            encounter = encounter == null ? Optional.empty() : encounter;
        }
    }

    public record RewardResult(
            RewardStatus status,
            UUID playerId,
            long amount,
            long readyAtEpochMillis,
            UUID transactionId,
            boolean auditRecorded) {
    }
}
