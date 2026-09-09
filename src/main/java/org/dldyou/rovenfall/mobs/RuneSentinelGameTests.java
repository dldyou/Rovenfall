package org.dldyou.rovenfall.mobs;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import org.dldyou.rovenfall.administration.AdministrationService;
import org.dldyou.rovenfall.administration.PlatformSavedData;
import org.dldyou.rovenfall.administration.ProtectedRegionService;
import org.dldyou.rovenfall.world.ProtectedRegion;
import java.util.UUID;
import net.minecraft.resources.Identifier;
import org.dldyou.rovenfall.world.WorldTopology;

public final class RuneSentinelGameTests {
    private RuneSentinelGameTests() {}

    public static void runeStrike(GameTestHelper helper) {
        var level = helper.getLevel().getServer().getLevel(WorldTopology.WILDERNESS);
        helper.assertTrue(level != null, "Wilderness missing");
        var mob = RovenfallMobEntities.RUNE_SENTINEL.get().create(level, EntitySpawnReason.COMMAND);
        var player = (ServerPlayer) helper.makeMockServerPlayer(GameType.SURVIVAL);
        var connection = new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
        var channel = new io.netty.channel.embedded.EmbeddedChannel(connection);
        player.connection = new net.minecraft.server.network.ServerGamePacketListenerImpl(
                level.getServer(), connection, player,
                net.minecraft.server.network.CommonListenerCookie.createInitial(player.getGameProfile(), false));
        player.connection.markClientLoaded();
        player.setServerLevel(level);
        var state = PlatformSavedData.get(level.getServer());
        var regionId = Identifier.fromNamespaceAndPath("rovenfall", "sentinel_test_" + UUID.randomUUID());
        try {
            for (BlockPos pos : BlockPos.betweenClosed(240, 90, 240, 253, 94, 248)) {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
            }
            mob.setPos(244, 90, 244);
            player.setPos(248, 90, 244);
            mob.setTarget(player);
            var definition = MobContentReloadListener.mob(level.getServer(), RovenfallMobEntities.RUNE_SENTINEL_ID)
                    .orElseThrow();
            RovenfallMobRuntime.applyDefinition(mob, definition, false);
            helper.assertTrue(mob.getMaxHealth() == 40 && mob.getExperienceReward(level, player) == 18,
                    "Sentinel data-driven health/XP not installed");
            var goal = mob.new RuneStrikeGoal();
            helper.assertTrue(goal.canUse(), "Eligible target could not start rune strike");
            goal.start();
            float health = player.getHealth();
            for (int tick = 0; tick < 27; tick++) goal.tick();
            helper.assertTrue(player.getHealth() == health, "Strike hit before warning elapsed");
            // Dodge the original mark while remaining in acquisition range.
            player.setPos(248, 90, 247);
            goal.tick();
            helper.assertTrue(player.getHealth() == health, "Rune strike followed a dodging target");
            for (int tick = 0; tick < 24; tick++) goal.tick();
            helper.assertTrue(!goal.canContinueToUse(), "Recovery did not finish");
            goal.stop();
            helper.assertTrue(!goal.canUse(), "Completed strike bypassed cooldown");

            var hit = mob.new RuneStrikeGoal();
            helper.assertTrue(hit.canUse(), "Stationary target was not eligible");
            hit.start();
            for (int tick = 0; tick < 28; tick++) hit.tick();
            helper.assertTrue(player.getHealth() < health, "Stationary target took no strike damage");
            float afterHit = player.getHealth();
            for (int tick = 0; tick < 24; tick++) hit.tick();
            helper.assertTrue(player.getHealth() == afterHit, "Recovery dealt repeated damage");
            hit.stop();

            var cancelled = mob.new RuneStrikeGoal();
            helper.assertTrue(cancelled.canUse(), "Second cast unavailable");
            cancelled.start();
            mob.setTarget(null);
            helper.assertTrue(!cancelled.canContinueToUse(), "Cast survived target loss");
            for (int tick = 0; tick < 28; tick++) cancelled.tick();
            helper.assertTrue(player.getHealth() == afterHit, "Cancelled cast dealt damage");
            cancelled.stop();
            mob.setTarget(player);

            // Save an armed cast. Loading must preserve health but require a complete new warning.
            mob.setHealth(17);
            var armed = mob.new RuneStrikeGoal();
            helper.assertTrue(armed.canUse(), "Persistence fixture could not arm");
            armed.start();
            for (int tick = 0; tick < 10; tick++) armed.tick();
            var saved = net.minecraft.world.level.storage.TagValueOutput.createWithContext(
                    net.minecraft.util.ProblemReporter.DISCARDING, level.registryAccess());
            mob.saveWithoutId(saved);
            var restored = RovenfallMobEntities.RUNE_SENTINEL.get().create(level, EntitySpawnReason.LOAD);
            restored.load(net.minecraft.world.level.storage.TagValueInput.create(
                    net.minecraft.util.ProblemReporter.DISCARDING, level.registryAccess(), saved.buildResult()));
            RovenfallMobRuntime.applyDefinition(restored, definition, true);
            helper.assertTrue(restored.getHealth() == 17, "Load healed sentinel");
            restored.setTarget(player);
            var fresh = restored.new RuneStrikeGoal();
            helper.assertTrue(fresh.canUse(), "Reloaded sentinel could not start fresh warning");
            fresh.start();
            for (int tick = 0; tick < 27; tick++) fresh.tick();
            helper.assertTrue(player.getHealth() == afterHit, "Reload resumed an armed strike without warning");
            fresh.stop();
            restored.discard();
            armed.stop();

            // The victim is across a chunk boundary; protection of the attacker alone is insufficient.
            mob.setPos(253, 90, 244);
            player.setPos(257, 90, 244);
            var guarded = mob.new RuneStrikeGoal();
            helper.assertTrue(guarded.canUse(), "Boundary fixture could not arm");
            guarded.start();
            var reservation = ProtectedRegionService.create(state, AdministrationService.SYSTEM_ACTOR, true,
                    regionId, new ProtectedRegion(AdministrationService.SYSTEM_ACTOR, level.dimension(), 16, 15, 16, 15),
                    "sentinel target boundary test", System.currentTimeMillis(), UUID.randomUUID());
            helper.assertTrue(reservation.status() == ProtectedRegionService.Status.SUCCESS,
                    "Could not protect victim chunk");
            helper.assertTrue(!guarded.canContinueToUse(), "Victim protection did not cancel armed cast");
            for (int tick = 0; tick < 28; tick++) guarded.tick();
            helper.assertTrue(player.getHealth() == afterHit, "Protected victim took rune damage");
            guarded.stop();
            helper.assertTrue(!mob.new RuneStrikeGoal().canUse(), "Protected victim accepted for new cast");
            player.setServerLevel(helper.getLevel());
            helper.assertTrue(!mob.new RuneStrikeGoal().canUse(), "Cross-dimension target was accepted");
            var hubMob = RovenfallMobEntities.RUNE_SENTINEL.get().create(helper.getLevel(), EntitySpawnReason.COMMAND);
            hubMob.setPos(helper.absoluteVec(new net.minecraft.world.phys.Vec3(1, 2, 1)));
            helper.assertTrue(!helper.getLevel().addFreshEntity(hubMob), "Sentinel spawned in Hub");
            hubMob.discard();
            helper.succeed();
        } finally {
            ProtectedRegionService.delete(state, AdministrationService.SYSTEM_ACTOR, true, regionId,
                    "sentinel fixture cleanup", System.currentTimeMillis(), UUID.randomUUID());
            player.setServerLevel(helper.getLevel());
            player.discard();
            mob.discard();
            channel.finishAndReleaseAll();
        }
    }
}
