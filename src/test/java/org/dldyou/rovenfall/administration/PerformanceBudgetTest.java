package org.dldyou.rovenfall.administration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.dldyou.rovenfall.claims.Claim;
import org.dldyou.rovenfall.claims.ClaimKey;
import org.dldyou.rovenfall.economy.ShopInstance;
import org.dldyou.rovenfall.mobs.BossEncounterSavedData;
import org.dldyou.rovenfall.mobs.BossEncounterState;
import org.dldyou.rovenfall.mobs.BossRewardOperation;
import org.dldyou.rovenfall.mobs.BossRewardSavedData;
import org.dldyou.rovenfall.mobs.MobMutationRuntime;
import org.dldyou.rovenfall.rpg.ActivityDefinition;
import org.dldyou.rovenfall.rpg.ActivityXpAwardService;
import org.dldyou.rovenfall.rpg.RpgDefinitionSnapshot;
import org.dldyou.rovenfall.rpg.RpgPlayerSavedData;
import org.dldyou.rovenfall.rpg.RpgPlayerState;
import org.dldyou.rovenfall.rpg.RpgSkillNetwork;
import org.dldyou.rovenfall.rpg.RpgSkillPayloads;
import org.dldyou.rovenfall.world.PortalDefinition;
import org.dldyou.rovenfall.world.ProtectedRegion;
import org.dldyou.rovenfall.world.WorldTopology;
import org.junit.jupiter.api.Test;

final class PerformanceBudgetTest {
    private static final UUID OPERATOR = id(1);
    private static final Identifier PORTAL_ID = Identifier.parse("rovenfall:performance_route");
    private static final Identifier MINING = Identifier.parse("rovenfall:mining");
    private static final PortalDefinition.Endpoint ORIGIN =
            new PortalDefinition.Endpoint(WorldTopology.HUB, new BlockPos(16_000, 70, 16_000));
    private static final PortalDefinition.Endpoint DESTINATION =
            new PortalDefinition.Endpoint(WorldTopology.WILDERNESS, new BlockPos(32_000, 80, 32_000));
    private static final RpgDefinitionSnapshot RPG_DEFINITIONS = RpgDefinitionSnapshot.compile(
            List.of(new RpgDefinitionSnapshot.ActivitySource(
                    Identifier.parse("rovenfall:performance_activities"), "test", MINING,
                    new ActivityDefinition("activity.rovenfall.mining", List.of(1_000L)))),
            List.of(), List.of());

    @Test
    void twentyAndFiftyPlayerScenariosExerciseEveryTargetDomainAndWriteDiagnostics() {
        ScenarioResult twenty = runScenario(20);
        ScenarioResult fifty = runScenario(50);

        assertScenario(twenty);
        assertScenario(fifty);
        writeReport(twenty, fifty);
    }

    @Test
    void structuralBudgetsRemainExplicitAndFailClosed() {
        assertEquals(50, PlatformSavedData.MAX_AUDIT_PAGE_SIZE);
        assertEquals(50, EconomyObservabilityService.MAX_PAGE_SIZE);
        assertEquals(50_000, BossAdministrationViewService.MAX_MUTATION_SCAN_ENTITIES);
        assertEquals(10_000, BossAdministrationViewService.MAX_MUTATION_ROWS);
        assertEquals(100_000, PlatformSavedData.MAX_AUDIT_ENTRIES);
        assertEquals(10_000, PlatformSavedData.MAX_RATE_INDEX_PER_PLAYER);
        assertEquals(100_000, Claim.MAX_CLAIMS);
        assertEquals(4_096, ShopInstance.MAX_INSTANCES);
        assertEquals(100_000, PortalState.MAX_RUNTIME_ENTRIES);
        assertEquals(100_000, RpgPlayerSavedData.MAX_PLAYERS);
        assertEquals(256, RpgPlayerState.MAX_PROVENANCE);
        assertEquals(32, BossEncounterSavedData.MAX_ACTIVE_ENCOUNTERS);
        assertEquals(1_024, BossEncounterState.MAX_CONTRIBUTORS);
        assertEquals(8, MobMutationRuntime.MAX_MUTATIONS_PER_MOB);
        assertEquals(20, RpgSkillNetwork.MAX_REQUESTS_PER_SECOND);
        assertEquals(128, RpgSkillPayloads.MAX_ACTIVATE_PACKET_BYTES);
        assertEquals(64, RpgSkillPayloads.MAX_STATE_SYNC_PACKET_BYTES);
        assertEquals(10_000, BossRewardSavedData.MAX_OPERATIONS);
        assertEquals(8, WildernessResetStore.MAX_SNAPSHOTS);
        assertEquals(64, WildernessResetState.MAX_EVIDENCE);
    }

    private static ScenarioResult runScenario(int playerCount) {
        PlatformSavedData platform = new PlatformSavedData();
        RpgPlayerSavedData rpg = new RpgPlayerSavedData();
        BossRewardSavedData bossRewards = new BossRewardSavedData();
        assertEquals(AdministrationService.RoleChangeStatus.SUCCESS, AdministrationService.changeRole(
                platform, AdministrationService.SYSTEM_ACTOR, true, OPERATOR,
                "owner", "performance fixture", 1_000, id(900_000 + playerCount)).status());
        PortalDefinition portal = new PortalDefinition(
                OPERATOR, ORIGIN, DESTINATION, 0, 0,
                PortalDefinition.SafeArrivalPolicy.EXACT, true);
        assertEquals(PortalService.Status.SUCCESS, PortalService.create(
                platform, OPERATOR, false, PORTAL_ID, portal, ignored -> true,
                "performance fixture", 1_100, id(901_000 + playerCount)).status());

        List<UUID> players = new ArrayList<>(playerCount);
        for (int index = 0; index < playerCount; index++) {
            players.add(id(10_000L * playerCount + index + 1));
        }

        long start = System.nanoTime();
        for (int index = 0; index < playerCount; index++) {
            assertEquals(EconomyService.TransactionStatus.SUCCESS, EconomyService.award(
                    platform, players.get(index), 100, "performance fixture", 2_000 + index,
                    transaction(playerCount, 1, index), 0, Long.MAX_VALUE).status());
        }
        long economyNanos = System.nanoTime() - start;

        start = System.nanoTime();
        for (int index = 0; index < playerCount; index++) {
            BlockPos position = new BlockPos((1_000 + index) << 4, 70, 0);
            assertEquals(ClaimPurchaseService.Status.SUCCESS, ClaimPurchaseService.purchase(
                    platform, players.get(index), WorldTopology.HUB, WorldTopology.HUB, position,
                    ignored -> true, ignored -> false, 1, 0, 1, 3_000 + index,
                    transaction(playerCount, 2, index)).status());
            assertTrue(ClaimProtectionService.evaluate(
                    platform, players.get(index), false, WorldTopology.HUB, BlockPos.ZERO, 0,
                    ClaimKey.at(WorldTopology.HUB, position), ClaimProtectionService.Action.BUILD).allowed());
        }
        long claimsNanos = System.nanoTime() - start;

        start = System.nanoTime();
        for (int index = 0; index < playerCount; index++) {
            assertEquals(ActivityXpAwardService.Status.SUCCESS, ActivityXpAwardService.award(
                    rpg, RPG_DEFINITIONS, players.get(index), MINING, 1, 4_000 + index,
                    transaction(playerCount, 3, index), "mining:performance:" + index).status());
        }
        long rpgNanos = System.nanoTime() - start;

        start = System.nanoTime();
        PortalTravelService.Gateway gateway = new PortalTravelService.Gateway() {
            @Override
            public boolean dimensionAvailable(ResourceKey<Level> dimension) {
                return dimension.equals(WorldTopology.WILDERNESS);
            }

            @Override
            public Optional<BlockPos> safeDestination(PortalDefinition definition) {
                return Optional.of(DESTINATION.position());
            }

            @Override
            public boolean teleport(ResourceKey<Level> dimension, BlockPos destination) {
                return true;
            }
        };
        for (int index = 0; index < playerCount; index++) {
            assertEquals(PortalTravelService.Status.SUCCESS, PortalTravelService.travel(
                    platform, players.get(index), WorldTopology.HUB, Vec3.atCenterOf(ORIGIN.position()),
                    PORTAL_ID, 5_000 + index, transaction(playerCount, 4, index), gateway).status());
        }
        long portalsNanos = System.nanoTime() - start;

        start = System.nanoTime();
        UUID encounterId = id(800_000 + playerCount);
        ProtectedRegion arena = new ProtectedRegion(
                AdministrationService.SYSTEM_ACTOR, WorldTopology.WILDERNESS,
                1_999, 1_999, 2_001, 2_001);
        BossEncounterState encounter = BossEncounterState.start(
                encounterId, Identifier.parse("rovenfall:performance_boss"), id(800_001), id(800_002),
                WorldTopology.WILDERNESS, DESTINATION.position(), arena, 6_000, 120);
        int mutationEvaluations = 0;
        for (int index = 0; index < playerCount; index++) {
            encounter = encounter.contribute(players.get(index), 1, playerCount, 6_000 + index);
            MobMutationRuntime.selectionBucket(
                    players.get(index), Identifier.parse("rovenfall:performance_mutation"));
            mutationEvaluations++;
            UUID transactionId = transaction(playerCount, 5, index);
            BossRewardOperation reward = new BossRewardOperation(
                    encounterId, Identifier.parse("rovenfall:performance_boss"), id(800_001),
                    players.get(index), WorldTopology.WILDERNESS, DESTINATION.position(),
                    25, 100, 10, 2_500, 1, 1, 7_000, 6_000 + index, List.of(),
                    BossRewardOperation.Phase.PENDING);
            assertEquals(BossRewardSavedData.BatchStatus.SUCCESS,
                    bossRewards.putBatch(Map.of(transactionId, reward), 6_000 + index));
        }
        long bossesNanos = System.nanoTime() - start;

        start = System.nanoTime();
        int auditsBeforeRead = platform.auditCount();
        var balances = EconomyObservabilityService.balances(platform, OPERATOR, false, 0, 50);
        var audits = platform.auditPage(0, 50);
        long administrationNanos = System.nanoTime() - start;

        assertEquals(auditsBeforeRead, platform.auditCount());
        return new ScenarioResult(
                playerCount, platform.economyAccountCount(), platform.claimCount(), rpg.playerCount(),
                playerCount, encounter.contributions().size(), mutationEvaluations,
                bossRewards.pendingOperations().size(), balances.entries().size(), audits.entries().size(),
                economyNanos, claimsNanos, rpgNanos, portalsNanos, bossesNanos, administrationNanos);
    }

    private static void assertScenario(ScenarioResult result) {
        assertEquals(result.players(), result.accounts());
        assertEquals(result.players(), result.claims());
        assertEquals(result.players(), result.rpgPlayers());
        assertEquals(result.players(), result.portalTravels());
        assertEquals(result.players(), result.bossContributors());
        assertEquals(result.players(), result.mutationEvaluations());
        assertEquals(result.players(), result.pendingBossRewards());
        assertEquals(result.players(), result.balanceRows());
        assertTrue(result.auditRows() > 0 && result.auditRows() <= PlatformSavedData.MAX_AUDIT_PAGE_SIZE);
        assertTrue(result.totalNanos() >= 0); // Diagnostic only; never a machine-specific time gate.
    }

    private static void writeReport(ScenarioResult twenty, ScenarioResult fifty) {
        String target = System.getProperty("rovenfall.performanceReport");
        if (target == null || target.isBlank()) {
            return;
        }
        Path report = Path.of(target);
        try {
            Files.createDirectories(report.getParent());
            Files.writeString(report, report(twenty, fifty), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new AssertionError("Could not write performance budget report", exception);
        }
    }

    private static String report(ScenarioResult twenty, ScenarioResult fifty) {
        return """
                # Rovenfall performance budget gate

                Deterministic structural assertions passed for the repository-native 20/50-player load fixture.
                Wall-clock measurements are diagnostics from this runner and never fail the build.

                | Players | Accounts | Claims | RPG | Portal travels | Boss contributors | Mutation evaluations | Boss rewards | Admin balance rows | Admin audit rows | Economy ms | Claims ms | RPG ms | Portals ms | Boss ms | Admin ms | Total ms |
                | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
                %s
                %s

                Structural budgets: admin pages 50 rows; boss tick encounters 32; skill requests 20/player/s;
                activate/state packets 128/64 bytes; recovery journals 10,000 boss operations,
                8 Wilderness snapshots, and 64 Wilderness evidence records.
                """.formatted(twenty.markdown(), fifty.markdown());
    }

    private static UUID transaction(int players, int domain, int index) {
        return id(players * 100_000L + domain * 10_000L + index + 1);
    }

    private static UUID id(long value) {
        return new UUID(0L, value);
    }

    private record ScenarioResult(
            int players, int accounts, int claims, int rpgPlayers, int portalTravels,
            int bossContributors, int mutationEvaluations, int pendingBossRewards, int balanceRows, int auditRows,
            long economyNanos, long claimsNanos, long rpgNanos,
            long portalsNanos, long bossesNanos, long administrationNanos) {
        long totalNanos() {
            return economyNanos + claimsNanos + rpgNanos + portalsNanos + bossesNanos + administrationNanos;
        }

        String markdown() {
            return "| %d | %d | %d | %d | %d | %d | %d | %d | %d | %d | %.3f | %.3f | %.3f | %.3f | %.3f | %.3f | %.3f |".formatted(
                    players, accounts, claims, rpgPlayers, portalTravels, bossContributors,
                    mutationEvaluations, pendingBossRewards, balanceRows, auditRows,
                    millis(economyNanos), millis(claimsNanos), millis(rpgNanos), millis(portalsNanos),
                    millis(bossesNanos), millis(administrationNanos), millis(totalNanos()));
        }

        private static double millis(long nanos) {
            return nanos / 1_000_000.0D;
        }
    }
}
