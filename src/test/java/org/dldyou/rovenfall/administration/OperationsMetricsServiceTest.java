package org.dldyou.rovenfall.administration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.Identifier;
import org.dldyou.rovenfall.mobs.BossRewardOperation;
import org.dldyou.rovenfall.rpg.ActivityXpConfig;
import org.dldyou.rovenfall.rpg.RpgPlayerSavedData;
import org.dldyou.rovenfall.rpg.RpgPlayerState;
import org.dldyou.rovenfall.world.WorldTopology;
import org.junit.jupiter.api.Test;

final class OperationsMetricsServiceTest {
    private static final UUID VIEWER = id(1);
    private static final long NOW = 100_000;
    private static final ActivityXpConfig.ConfigSnapshot RPG_CONFIG =
            new ActivityXpConfig.ConfigSnapshot(10, 2, 60_000, 1_000, 10, 100, 200, 4, 1);

    @Test
    void snapshotIsRoleGatedWindowedDeduplicatedAndReadOnlyAcrossReload() {
        PlatformSavedData state = stateWithViewer();
        assertEquals(EconomyService.TransactionStatus.SUCCESS, EconomyService.award(
                state, id(2), EconomyConfig.DEFAULT_ALERT_AMOUNT, "metric", NOW - 100,
                id(10), 0, Long.MAX_VALUE).status());
        var belowRateThreshold = OperationsMetricsService.snapshot(
                state, new RpgPlayerSavedData().snapshot(), List.of(), List.of(),
                VIEWER, false, NOW, 60_000, RPG_CONFIG);
        assertEquals(0, belowRateThreshold.rateAlertCount());
        for (int index = 0; index < EconomyConfig.DEFAULT_ALERT_RATE - 1; index++) {
            assertEquals(EconomyService.TransactionStatus.SUCCESS, EconomyService.award(
                    state, id(2), 1, "rate metric", NOW - 90 + index,
                    id(100 + index), 0, Long.MAX_VALUE).status());
        }
        AuditEntry denied = audit(NOW - 50, id(11), "shop_trade_denied");
        state.commitAudit(denied);
        state.commitAudit(denied);
        state.commitAudit(new AuditEntry(
                NOW - 40, id(2), Identifier.parse("rovenfall:portal_travel_denied"), "target",
                Optional.empty(), Optional.empty(), "before", "after", "invalid_request", id(12)));
        CompoundTag persisted = (CompoundTag) PlatformSavedData.CODEC.encodeStart(NbtOps.INSTANCE, state).getOrThrow();
        persisted.putInt("schema_version", PlatformSavedData.CURRENT_SCHEMA_VERSION + 1);
        PlatformSavedData decoded = PlatformSavedData.CODEC.parse(NbtOps.INSTANCE, persisted).getOrThrow();
        assertFalse(decoded.isWritable());
        int audits = decoded.auditCount();

        BossRewardOperation pending = reward(BossRewardOperation.Phase.PENDING);
        var result = OperationsMetricsService.snapshot(
                decoded,
                new RpgPlayerSavedData.Snapshot(RpgPlayerSavedData.CURRENT_SCHEMA_VERSION,
                        Map.of(id(2), player(id(20), NOW - 25))),
                List.of(),
                List.of(Map.entry(id(30), pending), Map.entry(id(30), pending)),
                VIEWER, false, NOW, 60_000, RPG_CONFIG);

        assertEquals(OperationsMetricsService.Status.SUCCESS, result.status());
        assertEquals(EconomyConfig.DEFAULT_ALERT_RATE, result.economyTransactionCount());
        assertEquals(1, result.amountAlertCount());
        assertEquals(1, result.rateAlertCount());
        assertEquals(2, result.deniedRequestCount());
        assertEquals(1, result.malformedRequestCount());
        assertEquals(1, result.suspiciousRpgAwardCount());
        assertEquals(1, result.pendingRewardCount());
        assertEquals(0, result.pendingRecoveryCount());
        assertTrue(result.evidenceTransactionIds().contains(id(10)));
        assertTrue(result.evidenceTransactionIds().contains(id(11)));
        assertEquals(audits, decoded.auditCount());
        assertEquals(EconomyConfig.DEFAULT_ALERT_AMOUNT + EconomyConfig.DEFAULT_ALERT_RATE - 1,
                decoded.economyBalance(id(2)).orElseThrow());
        assertTrue(result.hasAnomaly());
        assertEquals(OperationsMetricsService.Status.UNAUTHORIZED, OperationsMetricsService.snapshot(
                decoded, new RpgPlayerSavedData().snapshot(), List.of(), List.of(), id(999), false,
                NOW, 60_000, RPG_CONFIG).status());
    }

    @Test
    void queryCapsRpgEvidenceAtFiftyPlayersAndHandlesZeroState() {
        Map<UUID, RpgPlayerState> players = new LinkedHashMap<>();
        for (int index = 0; index <= OperationsMetricsService.MAX_RPG_PLAYERS; index++) {
            players.put(id(100 + index), player(id(1_000 + index), NOW));
        }
        var capped = OperationsMetricsService.snapshot(
                stateWithViewer(),
                new RpgPlayerSavedData.Snapshot(RpgPlayerSavedData.CURRENT_SCHEMA_VERSION, players),
                List.of(), List.of(), VIEWER, false, NOW, 60_000, RPG_CONFIG);

        assertEquals(OperationsMetricsService.MAX_RPG_PLAYERS, capped.scannedRpgPlayers());
        assertEquals(OperationsMetricsService.MAX_RPG_PLAYERS, capped.suspiciousRpgAwardCount());
        assertTrue(capped.rpgTruncated());

        var empty = OperationsMetricsService.snapshot(
                stateWithViewer(), new RpgPlayerSavedData().snapshot(), List.of(), List.of(),
                VIEWER, false, NOW, OperationsMetricsService.DEFAULT_WINDOW_MILLIS, RPG_CONFIG);
        assertEquals(OperationsMetricsService.Status.SUCCESS, empty.status());
        assertFalse(empty.hasAnomaly());
    }

    private static PlatformSavedData stateWithViewer() {
        PlatformSavedData state = new PlatformSavedData();
        AdministrationService.changeRole(
                state, AdministrationService.SYSTEM_ACTOR, true, VIEWER, "viewer", "bootstrap", 1, id(9_000));
        return state;
    }

    private static RpgPlayerState player(UUID transactionId, long timestamp) {
        // Initialize the outer codec before its nested provenance codec to avoid the record's JVM init cycle.
        RpgPlayerState ignored = RpgPlayerState.EMPTY;
        var evidence = new RpgPlayerState.ProgressionProvenance(
                RpgPlayerState.ProgressionProvenance.Kind.ACTIVITY_XP,
                Identifier.parse("rovenfall:mining"), 11, timestamp, transactionId, "operations-test");
        return new RpgPlayerState(Map.of(), Map.of(), Optional.empty(), Map.of(), Map.of(), Set.of(),
                List.of(evidence), List.of(), 0);
    }

    private static BossRewardOperation reward(BossRewardOperation.Phase phase) {
        return new BossRewardOperation(
                id(40), Identifier.parse("rovenfall:test_boss"), id(41), id(42),
                WorldTopology.WILDERNESS, new BlockPos(4_096, 96, 4_096),
                25, 100, 10, 2_500, 50, 500, NOW + 1_000, NOW - 1_000, List.of(), phase);
    }

    private static AuditEntry audit(long timestamp, UUID transactionId, String action) {
        return new AuditEntry(timestamp, id(2), Identifier.fromNamespaceAndPath("rovenfall", action), "target",
                Optional.empty(), Optional.empty(), "before", "after", "reason", transactionId);
    }

    private static UUID id(long value) {
        return new UUID(0L, value);
    }
}
