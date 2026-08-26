package org.dldyou.rovenfall;

import com.mojang.authlib.GameProfile;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.gametest.framework.BuiltinTestFunctions;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.entity.projectile.hurtingprojectile.SmallFireball;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.AddServerReloadListenersEvent;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.event.entity.EntityInvulnerabilityCheckEvent;
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.entity.player.UseItemOnBlockEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.level.PistonEvent;
import net.neoforged.neoforge.event.level.block.BreakBlockEvent;
import org.dldyou.rovenfall.administration.EconomyConfig;
import org.dldyou.rovenfall.administration.EconomyService;
import org.dldyou.rovenfall.administration.EconomyReversalService;
import org.dldyou.rovenfall.administration.EconomyTransactionReceipt;
import org.dldyou.rovenfall.administration.AdministrationService;
import org.dldyou.rovenfall.administration.PlatformSavedData;
import org.dldyou.rovenfall.administration.PlayerRecordService;
import org.dldyou.rovenfall.administration.RovenfallCommands;
import org.dldyou.rovenfall.administration.ShopInstanceService;
import org.dldyou.rovenfall.administration.ShopTradeService;
import org.dldyou.rovenfall.administration.ClaimManagementService;
import org.dldyou.rovenfall.administration.ClaimPurchaseService;
import org.dldyou.rovenfall.administration.ClaimProtectionEvents;
import org.dldyou.rovenfall.administration.ClaimProtectionService;
import org.dldyou.rovenfall.administration.ProtectedRegionService;
import org.dldyou.rovenfall.administration.PortalEvents;
import org.dldyou.rovenfall.administration.PortalService;
import org.dldyou.rovenfall.administration.PortalTravelService;
import org.dldyou.rovenfall.claims.ClaimConfig;
import org.dldyou.rovenfall.claims.ClaimKey;
import org.dldyou.rovenfall.claims.ClaimRole;
import org.dldyou.rovenfall.claims.ClaimSettings;
import org.dldyou.rovenfall.definition.TestDefinitionReloadListener;
import org.dldyou.rovenfall.economy.ShopTemplateReloadListener;
import org.dldyou.rovenfall.economy.ShopInstance;
import org.dldyou.rovenfall.mobs.MobContentReloadListener;
import org.dldyou.rovenfall.mobs.MobContentCatalog;
import org.dldyou.rovenfall.mobs.MobContentSnapshot;
import org.dldyou.rovenfall.rpg.RpgDefinitionReloadListener;
import org.dldyou.rovenfall.rpg.RpgPlayerSavedData;
import org.dldyou.rovenfall.rpg.RpgPlayerStateService;
import org.dldyou.rovenfall.rpg.SkillDefinition;
import org.dldyou.rovenfall.world.ProtectedRegion;
import org.dldyou.rovenfall.world.PortalDefinition;
import org.dldyou.rovenfall.world.WorldTopology;

@Mod(Rovenfall.MOD_ID)
public final class Rovenfall {
    public static final String MOD_ID = "rovenfall";
    private final ShopTemplateReloadListener shopTemplates = new ShopTemplateReloadListener();
    private final MobContentReloadListener mobContent = new MobContentReloadListener();
    private final RpgDefinitionReloadListener rpgDefinitions = new RpgDefinitionReloadListener();

    public Rovenfall(IEventBus modBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.SERVER, EconomyConfig.SPEC);
        modContainer.registerConfig(ModConfig.Type.SERVER, ClaimConfig.SPEC, "rovenfall-claims-server.toml");
        modBus.addListener(this::registerGameTests);
        NeoForge.EVENT_BUS.addListener(RovenfallCommands::register);
        NeoForge.EVENT_BUS.addListener(EconomyService::onPlayerLoggedIn);
        NeoForge.EVENT_BUS.addListener(PlayerRecordService::onPlayerLoggedIn);
        PortalEvents.register(NeoForge.EVENT_BUS);
        ClaimProtectionEvents.register(NeoForge.EVENT_BUS);
        NeoForge.EVENT_BUS.addListener(this::addServerReloadListeners);
        NeoForge.EVENT_BUS.addListener(shopTemplates::onDefaultDataComponentsBound);
    }

    private void registerGameTests(RegisterGameTestsEvent event) {
        var environment = event.registerEnvironment(id("empty"), new TestEnvironmentDefinition.AllOf(List.of()));
        var testData = new TestData<>(environment, Identifier.withDefaultNamespace("empty"), 1, 0, true);
        event.registerTest(id("foundation"), new FunctionGameTestInstance(BuiltinTestFunctions.ALWAYS_PASS, testData));
        event.registerTest(id("mob_content_definitions"), new FunctionGameTestInstance(
                BuiltinTestFunctions.ALWAYS_PASS, testData) {
            @Override
            public void run(GameTestHelper helper) {
                var snapshot = MobContentReloadListener.snapshot(helper.getLevel().getServer());
                helper.assertTrue(snapshot.size() == 11, "Built-in mob content catalog did not load atomically");
                helper.assertTrue(snapshot.mob(id("grove_stalker")).orElseThrow().loot()
                                .equals(id("grove_stalker_loot")),
                        "Custom mob reward reference was not preserved");
                helper.assertTrue(snapshot.mutation(id("volatile")).orElseThrow().eligibleEntityTypes().size() == 3,
                        "Mutation eligibility definition was not loaded");
                var boss = snapshot.boss(id("rift_warden")).orElseThrow();
                helper.assertTrue(boss.phases().size() == 2 && boss.phases().get(1).patterns().size() == 2,
                        "Boss phase and pattern definitions were not loaded");
                helper.assertTrue(snapshot.arena(boss.arena()).isPresent()
                                && snapshot.contributionRule(boss.contributionRule()).isPresent()
                                && snapshot.loot(boss.loot()).isPresent(),
                        "Boss references were not resolved in the installed snapshot");
                try {
                    var invalidBoss = new MobContentCatalog.BossDefinition(
                            id("invalid_boss"), "boss.rovenfall.invalid", id("missing_mob"),
                            id("missing_arena"), id("missing_rule"), id("missing_loot"), 0, List.of());
                    var invalidCatalog = new MobContentCatalog(
                            List.of(), List.of(), List.of(), List.of(), List.of(), List.of(invalidBoss));
                    MobContentSnapshot.compile(List.of(new MobContentSnapshot.Source(
                            id("invalid_gametest.json"), "gametest", id("invalid_gametest"),
                            invalidCatalog)));
                    helper.fail("Invalid mob definitions were accepted");
                } catch (MobContentSnapshot.ValidationException expected) {
                    helper.assertTrue(snapshot == MobContentReloadListener.snapshot(helper.getLevel().getServer()),
                            "Invalid candidate replaced the last valid mob content snapshot");
                }
                helper.succeed();
            }
        });
        event.registerTest(id("rpg_definitions"), new FunctionGameTestInstance(BuiltinTestFunctions.ALWAYS_PASS, testData) {
            @Override
            public void run(GameTestHelper helper) {
                var snapshot = RpgDefinitionReloadListener.snapshot(helper.getLevel().getServer());
                helper.assertTrue(snapshot.activities().size() == 7,
                        "All built-in activity definitions were not loaded");
                helper.assertTrue(snapshot.careers().size() == 4,
                        "All built-in career definitions were not loaded");
                helper.assertTrue(snapshot.skills().size() == 4,
                        "All built-in skill definitions were not loaded");
                var guardian = snapshot.career(id("guardian")).orElseThrow();
                helper.assertTrue(guardian.tier() == 3 && guardian.parents().equals(List.of(id("warrior"))),
                        "N-tier career lineage was not preserved");
                var active = snapshot.skill(id("power_strike")).orElseThrow();
                helper.assertTrue(active.kind() == SkillDefinition.Kind.ACTIVE && active.cooldownTicks().isPresent(),
                        "Active skill metadata was not preserved");
                helper.succeed();
            }
        });
        event.registerTest(id("rpg_player_state_persistence"), new FunctionGameTestInstance(
                BuiltinTestFunctions.ALWAYS_PASS, testData) {
            @Override
            public void run(GameTestHelper helper) {
                var server = helper.getLevel().getServer();
                var state = RpgPlayerSavedData.get(server);
                UUID playerId = UUID.randomUUID();
                var result = RpgPlayerStateService.awardActivityXp(
                        state, RpgDefinitionReloadListener.snapshot(server), playerId,
                        Identifier.fromNamespaceAndPath(MOD_ID, "combat"), 25, "gametest", 1);
                helper.assertTrue(result.status() == RpgPlayerStateService.Status.SUCCESS,
                        "RPG activity XP was not committed");
                var encoded = RpgPlayerSavedData.CODEC.encodeStart(NbtOps.INSTANCE, state).getOrThrow();
                var decoded = RpgPlayerSavedData.CODEC.parse(NbtOps.INSTANCE, encoded).getOrThrow();
                helper.assertTrue(decoded.state(playerId).activityXp().getOrDefault(
                        Identifier.fromNamespaceAndPath(MOD_ID, "combat"), 0L) == 25,
                        "RPG player state did not survive the persistence round trip");
                helper.assertTrue(decoded.snapshot().player(playerId).isPresent(),
                        "RPG player state snapshot did not expose the player");
                helper.succeed();
            }
        });
        event.registerTest(id("economy_account"), new FunctionGameTestInstance(BuiltinTestFunctions.ALWAYS_PASS, testData) {
            @Override
            public void run(GameTestHelper helper) {
                UUID playerId = UUID.randomUUID();
                var result = EconomyService.award(
                        PlatformSavedData.get(helper.getLevel().getServer()),
                        playerId,
                        1,
                        "gametest",
                        System.currentTimeMillis(),
                        UUID.randomUUID(),
                        0,
                        Long.MAX_VALUE);
                helper.assertTrue(result.status() == EconomyService.TransactionStatus.SUCCESS,
                        "Economy award was not committed");
                helper.assertTrue(result.balance() == 1, "Economy balance did not use the server result");
                helper.succeed();
            }
        });
        event.registerTest(id("claim_purchase_atomicity"), new FunctionGameTestInstance(
                BuiltinTestFunctions.ALWAYS_PASS, testData) {
            @Override
            public void run(GameTestHelper helper) {
                var server = helper.getLevel().getServer();
                var state = PlatformSavedData.get(server);
                var player = (net.minecraft.server.level.ServerPlayer) helper.makeMockServerPlayer(
                        net.minecraft.world.level.GameType.SURVIVAL);
                int chunkX = 1_000;
                int chunkZ = 1_000;
                while (state.claim(new ClaimKey(player.level().dimension(), chunkX, chunkZ)).isPresent()) {
                    chunkX++;
                }
                BlockPos position = new BlockPos((chunkX << 4) + 8, 70, (chunkZ << 4) + 8);
                player.setPos(position.getX() + 0.5, position.getY(), position.getZ() + 0.5);
                long timestamp = System.currentTimeMillis();
                helper.assertTrue(EconomyService.award(
                        state, player.getUUID(), 2_000, "gametest claim seed", timestamp,
                        UUID.randomUUID(), 0, Long.MAX_VALUE).status() == EconomyService.TransactionStatus.SUCCESS,
                        "Claim GameTest account setup failed");
                UUID transactionId = UUID.randomUUID();
                var purchase = ClaimPurchaseService.purchase(
                        state, player.getUUID(), player.level().dimension(), player.level().dimension(),
                        player.blockPosition(), ignored -> true, ignored -> false,
                        1_000, 250, 4, timestamp + 1, transactionId);
                helper.assertTrue(purchase.status() == ClaimPurchaseService.Status.SUCCESS,
                        "Hub claim purchase was not committed");
                ClaimKey key = purchase.claim().orElseThrow();
                helper.assertTrue(state.claim(key).orElseThrow().ownerId().equals(player.getUUID()),
                        "Claim owner did not use the server player identity");
                helper.assertTrue(state.economyBalance(player.getUUID()).orElseThrow() == 1_000,
                        "Claim purchase did not debit the server price");
                var decoded = PlatformSavedData.CODEC.parse(NbtOps.INSTANCE,
                        PlatformSavedData.CODEC.encodeStart(NbtOps.INSTANCE, state).getOrThrow()).getOrThrow();
                helper.assertTrue(decoded.claim(key).orElseThrow().ownerId().equals(player.getUUID()),
                        "Claim ownership did not survive persistence");
                helper.assertTrue(decoded.economyReceipt(transactionId).orElseThrow().claim().equals(Optional.of(key)),
                        "Claim transaction evidence did not survive persistence");
                helper.succeed();
            }
        });
        event.registerTest(id("claim_management_atomicity"), new FunctionGameTestInstance(
                BuiltinTestFunctions.ALWAYS_PASS, testData) {
            @Override
            public void run(GameTestHelper helper) {
                var state = PlatformSavedData.get(helper.getLevel().getServer());
                var owner = (net.minecraft.server.level.ServerPlayer) helper.makeMockServerPlayer(
                        net.minecraft.world.level.GameType.SURVIVAL);
                UUID recipientId = UUID.randomUUID();
                UUID trustedId = UUID.randomUUID();
                int chunkX = 2_000;
                int chunkZ = 2_000;
                ClaimKey key = new ClaimKey(owner.level().dimension(), chunkX, chunkZ);
                while (state.claim(key).isPresent()) {
                    key = new ClaimKey(owner.level().dimension(), ++chunkX, chunkZ);
                }
                long timestamp = System.currentTimeMillis();
                helper.assertTrue(EconomyService.award(
                        state, owner.getUUID(), 2_000, "gametest claim owner seed", timestamp,
                        UUID.randomUUID(), 0, Long.MAX_VALUE).status() == EconomyService.TransactionStatus.SUCCESS,
                        "Claim management owner account setup failed");
                helper.assertTrue(EconomyService.award(
                        state, recipientId, 1, "gametest claim recipient seed", timestamp + 1,
                        UUID.randomUUID(), 0, Long.MAX_VALUE).status() == EconomyService.TransactionStatus.SUCCESS,
                        "Claim management recipient account setup failed");
                var purchase = ClaimPurchaseService.purchase(
                        state, owner.getUUID(), owner.level().dimension(), owner.level().dimension(),
                        new BlockPos((chunkX << 4) + 8, 70, (chunkZ << 4) + 8), ignored -> true, ignored -> false,
                        1_000, 250, 4, timestamp + 2, UUID.randomUUID());
                helper.assertTrue(purchase.status() == ClaimPurchaseService.Status.SUCCESS,
                        "Claim management test purchase failed");

                UUID roleTransaction = UUID.randomUUID();
                helper.assertTrue(ClaimManagementService.setRole(
                        state, owner.getUUID(), false, key, trustedId, ClaimRole.BUILDER,
                        "gametest role", timestamp + 3, roleTransaction).status()
                        == ClaimManagementService.Status.SUCCESS, "Claim role was not committed");
                helper.assertTrue(ClaimManagementService.setSettings(
                        state, owner.getUUID(), false, key, new ClaimSettings(true, false),
                        "gametest settings", timestamp + 4, UUID.randomUUID()).status()
                        == ClaimManagementService.Status.SUCCESS, "Claim settings were not committed");
                helper.assertTrue(ClaimManagementService.offerTransfer(
                        state, owner.getUUID(), key, recipientId, "gametest offer", timestamp + 5,
                        UUID.randomUUID()).status() == ClaimManagementService.Status.SUCCESS,
                        "Claim transfer offer was not committed");
                helper.assertTrue(ClaimManagementService.acceptTransfer(
                        state, recipientId, key, ignored -> false, 4, "gametest accept", timestamp + 6,
                        UUID.randomUUID()).status() == ClaimManagementService.Status.SUCCESS,
                        "Claim transfer acceptance was not committed");

                UUID saleTransaction = UUID.randomUUID();
                var sale = ClaimManagementService.sell(
                        state, recipientId, key, 50, Long.MAX_VALUE, "gametest sale", timestamp + 7,
                        saleTransaction);
                helper.assertTrue(sale.status() == ClaimManagementService.Status.SUCCESS,
                        "Claim sale was not committed");
                helper.assertTrue(sale.amount() == 500 && sale.balance() == 501,
                        "Claim sale did not use the stored purchase price");
                var decoded = PlatformSavedData.CODEC.parse(NbtOps.INSTANCE,
                        PlatformSavedData.CODEC.encodeStart(NbtOps.INSTANCE, state).getOrThrow()).getOrThrow();
                helper.assertTrue(decoded.claim(key).isEmpty(), "Sold claim reappeared after persistence");
                helper.assertTrue(decoded.claimReceipt(roleTransaction).isPresent(),
                        "Claim mutation receipt did not survive persistence");
                helper.assertTrue(decoded.economyReceipt(saleTransaction).orElseThrow().claim().equals(Optional.of(key)),
                        "Claim sale evidence did not survive persistence");
                helper.succeed();
            }
        });
        event.registerTest(id("claim_protection_matrix"), new FunctionGameTestInstance(
                BuiltinTestFunctions.ALWAYS_PASS, testData) {
            @Override
            public void run(GameTestHelper helper) {
                var level = helper.getLevel();
                var server = level.getServer();
                var state = PlatformSavedData.get(server);
                var owner = (net.minecraft.server.level.ServerPlayer) helper.makeMockServerPlayer(
                        net.minecraft.world.level.GameType.SURVIVAL);
                var manager = (net.minecraft.server.level.ServerPlayer) helper.makeMockServerPlayer(
                        net.minecraft.world.level.GameType.SURVIVAL);
                var builder = (net.minecraft.server.level.ServerPlayer) helper.makeMockServerPlayer(
                        net.minecraft.world.level.GameType.SURVIVAL);
                var user = (net.minecraft.server.level.ServerPlayer) helper.makeMockServerPlayer(
                        net.minecraft.world.level.GameType.SURVIVAL);
                var visitor = (net.minecraft.server.level.ServerPlayer) helper.makeMockServerPlayer(
                        net.minecraft.world.level.GameType.SURVIVAL);
                var otherOwner = (net.minecraft.server.level.ServerPlayer) helper.makeMockServerPlayer(
                        net.minecraft.world.level.GameType.SURVIVAL);

                int chunkX = 4_000;
                int chunkZ = 4_000;
                ClaimKey ownerKey = new ClaimKey(level.dimension(), chunkX, chunkZ);
                ClaimKey sameOwnerKey = new ClaimKey(level.dimension(), chunkX + 1, chunkZ);
                ClaimKey otherOwnerKey = new ClaimKey(level.dimension(), chunkX + 2, chunkZ);
                while (state.claim(ownerKey).isPresent()
                        || state.claim(sameOwnerKey).isPresent()
                        || state.claim(otherOwnerKey).isPresent()) {
                    chunkX += 4;
                    ownerKey = new ClaimKey(level.dimension(), chunkX, chunkZ);
                    sameOwnerKey = new ClaimKey(level.dimension(), chunkX + 1, chunkZ);
                    otherOwnerKey = new ClaimKey(level.dimension(), chunkX + 2, chunkZ);
                }
                long timestamp = System.currentTimeMillis();
                helper.assertTrue(EconomyService.award(
                        state, owner.getUUID(), 5_000, "gametest protection owner seed", timestamp,
                        UUID.randomUUID(), 0, Long.MAX_VALUE).status() == EconomyService.TransactionStatus.SUCCESS,
                        "Protection owner account setup failed");
                helper.assertTrue(EconomyService.award(
                        state, otherOwner.getUUID(), 2_000, "gametest protection neighbor seed", timestamp + 1,
                        UUID.randomUUID(), 0, Long.MAX_VALUE).status() == EconomyService.TransactionStatus.SUCCESS,
                        "Protection neighbor account setup failed");

                ClaimKey[] keys = {ownerKey, sameOwnerKey, otherOwnerKey};
                net.minecraft.server.level.ServerPlayer[] purchasers = {owner, owner, otherOwner};
                for (int index = 0; index < keys.length; index++) {
                    ClaimKey key = keys[index];
                    var purchase = ClaimPurchaseService.purchase(
                            state,
                            purchasers[index].getUUID(),
                            level.dimension(),
                            level.dimension(),
                            new BlockPos((key.chunkX() << 4) + 8, 70, (key.chunkZ() << 4) + 8),
                            ignored -> true,
                            ignored -> false,
                            1_000,
                            250,
                            4,
                            timestamp + 2 + index,
                            UUID.randomUUID());
                    helper.assertTrue(purchase.status() == ClaimPurchaseService.Status.SUCCESS,
                            "Protection claim setup failed at index " + index);
                }

                UUID[] trustedPlayers = {manager.getUUID(), builder.getUUID(), user.getUUID()};
                ClaimRole[] roles = {ClaimRole.MANAGER, ClaimRole.BUILDER, ClaimRole.USER};
                for (int index = 0; index < roles.length; index++) {
                    helper.assertTrue(ClaimManagementService.setRole(
                            state, owner.getUUID(), false, ownerKey, trustedPlayers[index], roles[index],
                            "gametest protection role", timestamp + 8 + index, UUID.randomUUID()).status()
                            == ClaimManagementService.Status.SUCCESS,
                            "Protection role setup failed for " + roles[index]);
                }
                helper.assertTrue(ClaimManagementService.setSettings(
                        state, owner.getUUID(), false, ownerKey, new ClaimSettings(true, false),
                        "gametest restricted entry", timestamp + 12, UUID.randomUUID()).status()
                        == ClaimManagementService.Status.SUCCESS,
                        "Protection settings setup failed");

                var hub = server.overworld();
                ClaimKey retainedOwnerKey = ownerKey;
                java.util.function.BiFunction<UUID, ClaimProtectionService.Action, ClaimProtectionService.Decision>
                        access = (actor, action) -> ClaimProtectionService.evaluate(
                                state, actor, false, hub.dimension(), hub.getRespawnData().pos(),
                                ClaimConfig.protectedSpawnRadiusChunks(), retainedOwnerKey, action);
                helper.assertTrue(access.apply(owner.getUUID(), ClaimProtectionService.Action.BUILD).allowed(),
                        "Owner build access was denied");
                helper.assertTrue(access.apply(manager.getUUID(), ClaimProtectionService.Action.BUILD).allowed(),
                        "Manager build access was denied");
                helper.assertTrue(access.apply(builder.getUUID(), ClaimProtectionService.Action.BUILD).allowed(),
                        "Builder build access was denied");
                helper.assertTrue(!access.apply(user.getUUID(), ClaimProtectionService.Action.BUILD).allowed(),
                        "User received build access");
                helper.assertTrue(access.apply(user.getUUID(), ClaimProtectionService.Action.INTERACT).allowed(),
                        "User interaction access was denied");
                helper.assertTrue(!access.apply(visitor.getUUID(), ClaimProtectionService.Action.INTERACT).allowed(),
                        "Visitor received private interaction access");
                helper.assertTrue(!access.apply(visitor.getUUID(), ClaimProtectionService.Action.ENTRY).allowed(),
                        "Visitor entered restricted land");

                ClaimKey protectedKey = ClaimKey.at(hub.dimension(), hub.getRespawnData().pos());
                helper.assertTrue(!ClaimProtectionService.evaluate(
                        state, visitor.getUUID(), false, hub.dimension(), hub.getRespawnData().pos(),
                        ClaimConfig.protectedSpawnRadiusChunks(), protectedKey,
                        ClaimProtectionService.Action.BUILD).allowed(),
                        "Visitor modified the protected Hub region");
                helper.assertTrue(ClaimProtectionService.evaluate(
                        state, visitor.getUUID(), true, hub.dimension(), hub.getRespawnData().pos(),
                        ClaimConfig.protectedSpawnRadiusChunks(), protectedKey,
                        ClaimProtectionService.Action.BUILD).allowed(),
                        "Administrator override was denied in the protected Hub region");
                helper.assertTrue(ClaimProtectionService.environmentMayModify(
                        state, hub.dimension(), hub.getRespawnData().pos(),
                        ClaimConfig.protectedSpawnRadiusChunks(), ownerKey, sameOwnerKey),
                        "Same-owner boundary blocked an environmental update");
                helper.assertTrue(!ClaimProtectionService.environmentMayModify(
                        state, hub.dimension(), hub.getRespawnData().pos(),
                        ClaimConfig.protectedSpawnRadiusChunks(), ownerKey, otherOwnerKey),
                        "Cross-owner boundary allowed an environmental update");

                BlockPos ownedPosition = new BlockPos(
                        (ownerKey.chunkX() << 4) + 8, 70, (ownerKey.chunkZ() << 4) + 8);
                var ownerBreak = new BreakBlockEvent(
                        level, ownedPosition, level.getBlockState(ownedPosition), owner);
                NeoForge.EVENT_BUS.post(ownerBreak);
                helper.assertTrue(!ownerBreak.isCanceled(), "Owner break event was canceled");
                var fakePlayer = FakePlayerFactory.getMinecraft(level);
                var fakeBreak = new BreakBlockEvent(
                        level, ownedPosition, level.getBlockState(ownedPosition), fakePlayer);
                NeoForge.EVENT_BUS.post(fakeBreak);
                helper.assertTrue(fakeBreak.isCanceled(), "Untrusted fake-player break event was allowed");
                var spoofedOwner = FakePlayerFactory.get(
                        level, new GameProfile(owner.getUUID(), "[SpoofedOwner]"));
                var spoofedBreak = new BreakBlockEvent(
                        level, ownedPosition, level.getBlockState(ownedPosition), spoofedOwner);
                NeoForge.EVENT_BUS.post(spoofedBreak);
                helper.assertTrue(spoofedBreak.isCanceled(), "Fake player spoofed the claim owner's UUID");

                BlockPos sameOwnerSource = new BlockPos(
                        (ownerKey.chunkX() << 4) + 15, 70, (ownerKey.chunkZ() << 4) + 8);
                var sameOwnerFluid = new BlockEvent.FluidPlaceBlockEvent(
                        level, sameOwnerSource.east(), sameOwnerSource, Blocks.WATER.defaultBlockState());
                NeoForge.EVENT_BUS.post(sameOwnerFluid);
                helper.assertTrue(!sameOwnerFluid.isCanceled(),
                        "Same-owner fluid boundary was canceled");
                BlockPos crossOwnerSource = new BlockPos(
                        (sameOwnerKey.chunkX() << 4) + 15, 70, (sameOwnerKey.chunkZ() << 4) + 8);
                BlockPos crossOwnerTarget = crossOwnerSource.east();
                var crossOwnerFluid = new BlockEvent.FluidPlaceBlockEvent(
                        level, crossOwnerTarget, crossOwnerSource, Blocks.WATER.defaultBlockState());
                NeoForge.EVENT_BUS.post(crossOwnerFluid);
                helper.assertTrue(crossOwnerFluid.isCanceled(),
                        "Cross-owner fluid boundary was allowed");

                BlockPos bucketClicked = crossOwnerSource.above();
                level.setBlock(bucketClicked, Blocks.STONE.defaultBlockState(), 3);
                owner.setPos(bucketClicked.getX() + 3.5, bucketClicked.getY() - 1.0, bucketClicked.getZ() + 0.5);
                owner.setYRot(90.0F);
                owner.setYHeadRot(90.0F);
                owner.setXRot(0.0F);
                owner.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.WATER_BUCKET));
                Vec3 bucketStart = owner.getEyePosition();
                var bucketHit = level.clip(new ClipContext(
                        bucketStart,
                        bucketStart.add(owner.getViewVector(1.0F).scale(owner.blockInteractionRange())),
                        ClipContext.Block.OUTLINE,
                        ClipContext.Fluid.NONE,
                        owner));
                helper.assertTrue(bucketHit instanceof BlockHitResult blockHit
                                && blockHit.getBlockPos().equals(bucketClicked),
                        "Bucket test ray did not hit the boundary block");
                var bucketUse = new PlayerInteractEvent.RightClickItem(owner, InteractionHand.MAIN_HAND);
                NeoForge.EVENT_BUS.post(bucketUse);
                helper.assertTrue(bucketUse.isCanceled(),
                        "Bucket placement leaked into a different owner's claim");

                owner.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.FLINT_AND_STEEL));
                var fireHit = new BlockHitResult(
                        Vec3.atCenterOf(bucketClicked), Direction.EAST, bucketClicked, false);
                var fireUse = new UseItemOnBlockEvent(
                        new UseOnContext(owner, InteractionHand.MAIN_HAND, fireHit),
                        UseItemOnBlockEvent.UsePhase.ITEM_BEFORE_BLOCK);
                NeoForge.EVENT_BUS.post(fireUse);
                helper.assertTrue(fireUse.isCanceled(),
                        "Direct fire placement leaked into a different owner's claim");

                var playerFireball = new SmallFireball(level, owner, new Vec3(1.0, 0.0, 0.0));
                var fireballImpact = new ProjectileImpactEvent(playerFireball, fireHit);
                NeoForge.EVENT_BUS.post(fireballImpact);
                helper.assertTrue(fireballImpact.isCanceled() && playerFireball.isRemoved(),
                        "Fireball ignition leaked across an actual hit face");

                BlockPos chestSource = crossOwnerSource.above(2);
                BlockPos chestTarget = chestSource.east();
                var chestBase = Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH);
                level.setBlock(chestSource, chestBase.setValue(ChestBlock.TYPE, ChestType.LEFT), 2);
                level.setBlock(chestTarget, chestBase.setValue(ChestBlock.TYPE, ChestType.RIGHT), 2);
                owner.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                var chestUse = new PlayerInteractEvent.RightClickBlock(
                        owner,
                        InteractionHand.MAIN_HAND,
                        chestSource,
                        new BlockHitResult(Vec3.atCenterOf(chestSource), Direction.UP, chestSource, false));
                NeoForge.EVENT_BUS.post(chestUse);
                helper.assertTrue(chestUse.isCanceled(),
                        "Cross-owner double chest interaction was allowed");
                var chestBreak = new BreakBlockEvent(
                        level, chestSource, level.getBlockState(chestSource), owner);
                NeoForge.EVENT_BUS.post(chestBreak);
                helper.assertTrue(chestBreak.isCanceled(),
                        "Cross-owner double chest break was allowed");

                var visitorArrow = new Arrow(level, visitor, new ItemStack(Items.ARROW), null);
                var projectileImpact = new ProjectileImpactEvent(
                        visitorArrow,
                        new BlockHitResult(Vec3.atCenterOf(ownedPosition), Direction.UP, ownedPosition, false));
                NeoForge.EVENT_BUS.post(projectileImpact);
                helper.assertTrue(projectileImpact.isCanceled() && visitorArrow.isRemoved(),
                        "Visitor projectile interaction in private land was allowed");

                owner.setPos(ownedPosition.getX() + 0.5, ownedPosition.getY(), ownedPosition.getZ() + 0.5);
                var automatedArrow = new Arrow(EntityTypes.ARROW, level);
                var automatedEntityImpact = new ProjectileImpactEvent(
                        automatedArrow, new EntityHitResult(owner));
                NeoForge.EVENT_BUS.post(automatedEntityImpact);
                helper.assertTrue(automatedEntityImpact.isCanceled() && automatedArrow.isRemoved(),
                        "Automated projectile damage in private land was allowed");

                BlockPos dispenserSource = crossOwnerSource.above(4);
                BlockPos dispenserTarget = dispenserSource.east();
                var dispenserState = Blocks.DISPENSER.defaultBlockState()
                        .setValue(DispenserBlock.FACING, Direction.EAST);
                level.setBlock(dispenserSource, dispenserState, 2);
                level.setBlock(dispenserTarget, Blocks.AIR.defaultBlockState(), 2);
                var dispenser = (DispenserBlockEntity) level.getBlockEntity(dispenserSource);
                helper.assertTrue(dispenser != null, "Dispenser test setup failed");
                dispenser.setItem(0, new ItemStack(Items.WATER_BUCKET));
                dispenserState.tick(level, dispenserSource, level.getRandom());
                helper.assertTrue(dispenser.getItem(0).is(Items.WATER_BUCKET)
                                && level.getFluidState(dispenserTarget).isEmpty(),
                        "Dispenser fluid placement leaked into a different owner's claim");

                BlockPos emptyPistonSource = crossOwnerSource.above(6);
                BlockPos emptyPistonArm = emptyPistonSource.east();
                level.setBlock(emptyPistonSource,
                        Blocks.PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING, Direction.EAST), 2);
                level.setBlock(emptyPistonArm, Blocks.AIR.defaultBlockState(), 2);
                var emptyPiston = new PistonEvent.Pre(
                        level, emptyPistonSource, Direction.EAST, PistonEvent.PistonMoveType.EXTEND);
                NeoForge.EVENT_BUS.post(emptyPiston);
                helper.assertTrue(emptyPiston.isCanceled(),
                        "Empty piston head leaked into a different owner's claim");

                owner.setPos(ownedPosition.getX() + 0.5, ownedPosition.getY(), ownedPosition.getZ() + 0.5);
                var entityDamage = new EntityInvulnerabilityCheckEvent(
                        owner, level.damageSources().playerAttack(visitor), false);
                NeoForge.EVENT_BUS.post(entityDamage);
                helper.assertTrue(entityDamage.isInvulnerable(),
                        "Visitor damage to an entity in private land was allowed");

                level.setBlock(crossOwnerSource,
                        Blocks.PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING, Direction.EAST), 3);
                level.setBlock(crossOwnerTarget, Blocks.STONE.defaultBlockState(), 3);
                var piston = new PistonEvent.Pre(
                        level, crossOwnerSource, Direction.EAST, PistonEvent.PistonMoveType.EXTEND);
                NeoForge.EVENT_BUS.post(piston);
                helper.assertTrue(piston.isCanceled(), "Cross-owner piston move was allowed");

                var affectedBlocks = new ArrayList<>(List.of(ownedPosition, crossOwnerTarget));
                var explosion = new ServerExplosion(
                        level, owner, null, null, Vec3.atCenterOf(ownedPosition), 4.0F, false,
                        Explosion.BlockInteraction.DESTROY);
                NeoForge.EVENT_BUS.post(new ExplosionEvent.Detonate(
                        level, explosion, new ArrayList<>(), affectedBlocks));
                helper.assertTrue(affectedBlocks.contains(ownedPosition),
                        "Owner explosion was removed from the owner's claim");
                helper.assertTrue(!affectedBlocks.contains(crossOwnerTarget),
                        "Owner explosion leaked into a different owner's claim");
                helper.succeed();
            }
        });
        event.registerTest(id("world_topology_and_protected_regions"), new FunctionGameTestInstance(
                BuiltinTestFunctions.ALWAYS_PASS, testData) {
            @Override
            public void run(GameTestHelper helper) {
                var level = helper.getLevel();
                var server = level.getServer();
                var state = PlatformSavedData.get(server);
                helper.assertTrue(server.getLevel(WorldTopology.HUB) == server.overworld(),
                        "Hub identity did not resolve to the permanent Overworld");
                var wildernessResource = server.getResourceManager().getResource(
                        Identifier.fromNamespaceAndPath(MOD_ID, "dimension/wilderness.json")).orElse(null);
                helper.assertTrue(wildernessResource != null,
                        "The rovenfall:wilderness dimension data was not shipped");
                try (var reader = wildernessResource.openAsReader()) {
                    LevelStem.CODEC.parse(
                            RegistryOps.create(JsonOps.INSTANCE, level.registryAccess()),
                            JsonParser.parseReader(reader)).getOrThrow();
                } catch (java.io.IOException exception) {
                    throw new AssertionError("Could not read the Wilderness dimension data", exception);
                }

                int chunkX = 6_000;
                int chunkZ = 6_000;
                ClaimKey portalKey = new ClaimKey(WorldTopology.HUB, chunkX, chunkZ);
                while (state.isProtectedRegion(portalKey) || state.claim(portalKey).isPresent()) {
                    chunkX += 2;
                    portalKey = new ClaimKey(WorldTopology.HUB, chunkX, chunkZ);
                }
                Identifier regionId = id("gametest_portal_ring_" + UUID.randomUUID());
                long timestamp = System.currentTimeMillis();
                var created = ProtectedRegionService.create(
                        state,
                        AdministrationService.SYSTEM_ACTOR,
                        true,
                        regionId,
                        new ProtectedRegion(
                                AdministrationService.SYSTEM_ACTOR,
                                WorldTopology.HUB,
                                portalKey.chunkX(),
                                portalKey.chunkZ(),
                                portalKey.chunkX(),
                                portalKey.chunkZ()),
                        "gametest portal ring",
                        timestamp,
                        UUID.randomUUID());
                helper.assertTrue(created.status() == ProtectedRegionService.Status.SUCCESS,
                        "Portal-ring protected region was not created");

                var visitor = (net.minecraft.server.level.ServerPlayer) helper.makeMockServerPlayer(
                        net.minecraft.world.level.GameType.SURVIVAL);
                BlockPos portalPosition = new BlockPos(
                        (portalKey.chunkX() << 4) + 8, 70, (portalKey.chunkZ() << 4) + 8);
                var portalBreak = new BreakBlockEvent(
                        level, portalPosition, level.getBlockState(portalPosition), visitor);
                NeoForge.EVENT_BUS.post(portalBreak);
                helper.assertTrue(portalBreak.isCanceled(),
                        "Visitor block destruction was allowed in a protected portal ring");

                var affectedBlocks = new ArrayList<>(List.of(portalPosition));
                var explosion = new ServerExplosion(
                        level, visitor, null, null, Vec3.atCenterOf(portalPosition), 4.0F, false,
                        Explosion.BlockInteraction.DESTROY);
                NeoForge.EVENT_BUS.post(new ExplosionEvent.Detonate(
                        level, explosion, new ArrayList<>(), affectedBlocks));
                helper.assertTrue(affectedBlocks.isEmpty(),
                        "Explosion block damage was allowed in a protected portal ring");

                ClaimKey wildernessGround = new ClaimKey(WorldTopology.WILDERNESS, 20, 20);
                helper.assertTrue(ClaimProtectionService.evaluate(
                        state,
                        visitor.getUUID(),
                        false,
                        WorldTopology.HUB,
                        server.overworld().getRespawnData().pos(),
                        ClaimConfig.protectedSpawnRadiusChunks(),
                        wildernessGround,
                        ClaimProtectionService.Action.BUILD).allowed(),
                        "Ordinary Wilderness gathering/building was denied");
                var wildernessClaim = ClaimPurchaseService.purchase(
                        state,
                        visitor.getUUID(),
                        WorldTopology.HUB,
                        WorldTopology.WILDERNESS,
                        wildernessGround.auditPosition(),
                        ignored -> true,
                        ignored -> false,
                        1_000,
                        0,
                        64,
                        timestamp + 1,
                        UUID.randomUUID());
                helper.assertTrue(wildernessClaim.status() == ClaimPurchaseService.Status.NOT_IN_HUB,
                        "A player claim was allowed in the Wilderness");

                var loaded = PlatformSavedData.CODEC.parse(
                        NbtOps.INSTANCE,
                        PlatformSavedData.CODEC.encodeStart(NbtOps.INSTANCE, state).getOrThrow()).getOrThrow();
                helper.assertTrue(loaded.isProtectedRegion(portalKey),
                        "Protected region index was not rebuilt after persistence round-trip");
                helper.assertTrue(loaded.auditPage(0, 10).entries().stream()
                                .anyMatch(entry -> entry.target().equals(regionId.toString())),
                        "Protected region mutation audit did not survive persistence");

                var deleted = ProtectedRegionService.delete(
                        state,
                        AdministrationService.SYSTEM_ACTOR,
                        true,
                        regionId,
                        "gametest cleanup",
                        timestamp + 2_000,
                        UUID.randomUUID());
                helper.assertTrue(deleted.status() == ProtectedRegionService.Status.SUCCESS,
                        "Portal-ring protected region cleanup failed");
                helper.succeed();
            }
        });
        event.registerTest(id("definition_reload"), new FunctionGameTestInstance(BuiltinTestFunctions.ALWAYS_PASS, testData) {
            @Override
            public void run(GameTestHelper helper) {
                var listener = helper.getLevel().getServer().getServerResources().managers()
                        .getListener(TestDefinitionReloadListener.KEY);
                helper.assertTrue(listener != null, "Rovenfall definition reload listener was not retained");
                helper.assertTrue(
                        listener.snapshot().get(id("foundation")).isPresent(),
                        "Built-in Rovenfall test definition was not loaded");
                helper.succeed();
            }
        });
        event.registerTest(id("shop_template_reload"), new FunctionGameTestInstance(BuiltinTestFunctions.ALWAYS_PASS, testData) {
            @Override
            public void run(GameTestHelper helper) {
                var template = ShopTemplateReloadListener.get(helper.getLevel().getServer(), id("foundation"));
                helper.assertTrue(template.isPresent(), "Built-in Rovenfall shop template was not loaded");
                var offer = template.orElseThrow().offers().getFirst();
                var item = offer.item();
                helper.assertTrue(item.getCount() == 4, "Shop offer item count was not retained");
                helper.assertTrue(!item.getComponentsPatch().isEmpty(), "Shop offer item components were not retained");
                helper.assertTrue(item.getMaxStackSize() == 16, "Shop offer exact max-stack component was not retained");
                helper.assertTrue(offer.buyPrice().orElseThrow() == 12L, "Shop offer buy price was not retained");
                helper.assertTrue(offer.sellPrice().orElseThrow() == 6L, "Shop offer sell price was not retained");
                helper.succeed();
            }
        });
        event.registerTest(id("shop_instance_persistence"), new FunctionGameTestInstance(BuiltinTestFunctions.ALWAYS_PASS, testData) {
            @Override
            public void run(GameTestHelper helper) {
                var server = helper.getLevel().getServer();
                var state = PlatformSavedData.get(server);
                Identifier shopId = id("gametest_" + UUID.randomUUID());
                var created = ShopInstanceService.create(
                        state,
                        ShopTemplateReloadListener.snapshot(server),
                        AdministrationService.SYSTEM_ACTOR,
                        true,
                        shopId,
                        id("foundation"),
                        Optional.empty(),
                        key -> server.getLevel(key) != null,
                        ShopInstance.AccessPolicy.publicAccess(),
                        server.overworld().getGameTime(),
                        "gametest create",
                        System.currentTimeMillis(),
                        UUID.randomUUID());
                helper.assertTrue(created.status() == ShopInstanceService.Status.SUCCESS,
                        "Shop instance was not created from the loaded template");
                var offer = state.shopInstance(shopId).orElseThrow().offers().get(id("foundation_bread"));
                helper.assertTrue(offer.item().getCount() == 4,
                        "Shop instance did not retain exact template item count");
                helper.assertTrue(!offer.item().getComponentsPatch().isEmpty(),
                        "Shop instance did not retain exact template item components");
                var encoded = ShopInstance.CODEC.encodeStart(
                        NbtOps.INSTANCE, state.shopInstance(shopId).orElseThrow()).getOrThrow();
                var decoded = ShopInstance.CODEC.parse(NbtOps.INSTANCE, encoded).getOrThrow();
                helper.assertTrue(decoded.offers().get(id("foundation_bread")).item().getCount() == 4,
                        "Shop offer entry-list codec did not round-trip the exact stack");
                var encodedState = PlatformSavedData.CODEC.encodeStart(NbtOps.INSTANCE, state).getOrThrow();
                var decodedState = PlatformSavedData.CODEC.parse(NbtOps.INSTANCE, encodedState).getOrThrow();
                helper.assertTrue(decodedState.shopInstance(shopId).orElseThrow().offers()
                                .get(id("foundation_bread")).item().getCount() == 4,
                        "Saved shop instance did not round-trip the exact stack");
                CompoundTag duplicate = ((CompoundTag) encoded).copy();
                ListTag encodedOffers = duplicate.getListOrEmpty("offers");
                encodedOffers.add(encodedOffers.getFirst().copy());
                helper.assertTrue(ShopInstance.CODEC.parse(NbtOps.INSTANCE, duplicate).error().isPresent(),
                        "Shop offer entry-list codec accepted a duplicate offer ID");
                var deleted = ShopInstanceService.delete(
                        state,
                        AdministrationService.SYSTEM_ACTOR,
                        true,
                        shopId,
                        "gametest cleanup",
                        System.currentTimeMillis() + 1_500,
                        UUID.randomUUID());
                helper.assertTrue(deleted.status() == ShopInstanceService.Status.SUCCESS,
                        "Shop instance cleanup failed");
                helper.succeed();
            }
        });
        event.registerTest(id("shop_trade_atomicity"), new FunctionGameTestInstance(BuiltinTestFunctions.ALWAYS_PASS, testData) {
            @Override
            public void run(GameTestHelper helper) {
                var server = helper.getLevel().getServer();
                var state = PlatformSavedData.get(server);
                var player = (net.minecraft.server.level.ServerPlayer) helper.makeMockServerPlayer(
                        net.minecraft.world.level.GameType.SURVIVAL);
                Identifier shopId = id("trade_" + UUID.randomUUID());
                Identifier offerId = id("foundation_bread");
                long timestamp = System.currentTimeMillis();
                var created = ShopInstanceService.create(
                        state,
                        ShopTemplateReloadListener.snapshot(server),
                        AdministrationService.SYSTEM_ACTOR,
                        true,
                        shopId,
                        id("foundation"),
                        Optional.of(new ShopInstance.Binding(
                                player.level().dimension(), net.minecraft.core.BlockPos.containing(player.position()))),
                        key -> server.getLevel(key) != null,
                        ShopInstance.AccessPolicy.publicAccess(),
                        server.overworld().getGameTime(),
                        "gametest trade create",
                        timestamp,
                        UUID.randomUUID());
                helper.assertTrue(created.status() == ShopInstanceService.Status.SUCCESS,
                        "Trade GameTest shop setup failed");
                var account = EconomyService.award(
                        state, player.getUUID(), 100, "gametest trade seed", timestamp + 1,
                        UUID.randomUUID(), 0, Long.MAX_VALUE);
                helper.assertTrue(account.status() == EconomyService.TransactionStatus.SUCCESS,
                        "Trade GameTest account setup failed");
                var offer = state.shopInstance(shopId).orElseThrow().offers().get(offerId);
                UUID purchaseId = UUID.randomUUID();
                var purchase = ShopTradeService.trade(
                        state,
                        player,
                        new ShopTradeService.TradeRequest(
                                shopId, offerId, ShopTradeService.Direction.BUY, 1,
                                offer.item(), offer.buyPrice().orElseThrow(), purchaseId),
                        server.overworld().getGameTime(),
                        timestamp + 2);
                helper.assertTrue(purchase.status() == ShopTradeService.Status.SUCCESS,
                        "Server-player purchase did not commit");
                helper.assertTrue(state.economyBalance(player.getUUID()).orElseThrow() == 88,
                        "Purchase did not use the server price");
                helper.assertTrue(player.getInventory().getNonEquipmentItems().stream()
                                .filter(stack -> net.minecraft.world.item.ItemStack.isSameItemSameComponents(stack, offer.item()))
                                .mapToInt(net.minecraft.world.item.ItemStack::getCount).sum() == 4,
                        "Purchase did not grant the exact offer stack");

                var sale = ShopTradeService.trade(
                        state,
                        player,
                        new ShopTradeService.TradeRequest(
                                shopId, offerId, ShopTradeService.Direction.SELL, 1,
                                offer.item(), offer.sellPrice().orElseThrow(), UUID.randomUUID()),
                        server.overworld().getGameTime(),
                        timestamp + 3);
                helper.assertTrue(sale.status() == ShopTradeService.Status.SUCCESS,
                        "Server-player sale did not commit");
                helper.assertTrue(state.economyBalance(player.getUUID()).orElseThrow() == 94,
                        "Sale did not use the server price");
                var encodedTrade = PlatformSavedData.CODEC.encodeStart(NbtOps.INSTANCE, state).getOrThrow();
                var decodedTrade = PlatformSavedData.CODEC.parse(NbtOps.INSTANCE, encodedTrade).getOrThrow();
                helper.assertTrue(decodedTrade.economyBalance(player.getUUID()).orElseThrow() == 94,
                        "Committed trade balance did not survive persistence");
                helper.assertTrue(decodedTrade.shopInstance(shopId).orElseThrow().offers().get(offerId)
                                .stock().current() == 10,
                        "Committed trade stock did not survive persistence");
                var crossPayloadRetry = EconomyService.award(
                        decodedTrade, player.getUUID(), 1, "gametest persisted retry",
                        timestamp + 3, purchaseId, 0, Long.MAX_VALUE);
                helper.assertTrue(
                        crossPayloadRetry.status() == EconomyService.TransactionStatus.TRANSACTION_ID_CONFLICT,
                        "Persisted trade ID was accepted for a different economy payload");
                helper.assertTrue(decodedTrade.economyBalance(player.getUUID()).orElseThrow() == 94,
                        "Conflicting persisted retry changed the balance");
                helper.assertTrue(decodedTrade.shopInstance(shopId).orElseThrow().offers().get(offerId)
                                .stock().current() == 10,
                        "Conflicting persisted retry changed shop stock");
                helper.assertTrue(decodedTrade.economyReceipt(purchaseId).orElseThrow().kind()
                                == EconomyTransactionReceipt.Kind.PURCHASE,
                        "Conflicting persisted retry replaced the purchase receipt");

                var replay = ShopTradeService.trade(
                        state,
                        player,
                        new ShopTradeService.TradeRequest(
                                shopId, offerId, ShopTradeService.Direction.BUY, 1,
                                offer.item(), offer.buyPrice().orElseThrow(), purchaseId),
                        server.overworld().getGameTime(),
                        timestamp + 4);
                helper.assertTrue(replay.status() == ShopTradeService.Status.DUPLICATE_TRANSACTION,
                        "Purchase retry was not idempotent");
                helper.assertTrue(state.economyBalance(player.getUUID()).orElseThrow() == 94,
                        "Purchase retry changed the balance");

                player.setPos(player.getX() + 100, player.getY(), player.getZ());
                var denied = ShopTradeService.trade(
                        state,
                        player,
                        new ShopTradeService.TradeRequest(
                                shopId, offerId, ShopTradeService.Direction.BUY, 1,
                                offer.item(), offer.buyPrice().orElseThrow(), UUID.randomUUID()),
                        server.overworld().getGameTime(),
                        timestamp + 1_500);
                helper.assertTrue(denied.status() == ShopTradeService.Status.ACCESS_DENIED,
                        "Bound shop accepted a distant player");
                ShopInstanceService.delete(
                        state,
                        AdministrationService.SYSTEM_ACTOR,
                        true,
                        shopId,
                        "gametest trade cleanup",
                        timestamp + 3_000,
                        UUID.randomUUID());
                helper.succeed();
            }
        });
        event.registerTest(id("economy_purchase_reversal"), new FunctionGameTestInstance(BuiltinTestFunctions.ALWAYS_PASS, testData) {
            @Override
            public void run(GameTestHelper helper) {
                var server = helper.getLevel().getServer();
                var state = PlatformSavedData.get(server);
                var player = (net.minecraft.server.level.ServerPlayer) helper.makeMockServerPlayer(
                        net.minecraft.world.level.GameType.SURVIVAL);
                Identifier shopId = id("reversal_" + UUID.randomUUID());
                Identifier offerId = id("foundation_bread");
                long timestamp = System.currentTimeMillis();
                helper.assertTrue(ShopInstanceService.create(
                        state, ShopTemplateReloadListener.snapshot(server), AdministrationService.SYSTEM_ACTOR, true,
                        shopId, id("foundation"), Optional.empty(), key -> server.getLevel(key) != null,
                        ShopInstance.AccessPolicy.publicAccess(), server.overworld().getGameTime(),
                        "gametest reversal create", timestamp, UUID.randomUUID()).status()
                        == ShopInstanceService.Status.SUCCESS, "Reversal shop setup failed");
                helper.assertTrue(EconomyService.award(
                        state, player.getUUID(), 100, "gametest reversal seed", timestamp + 1,
                        UUID.randomUUID(), 0, Long.MAX_VALUE).status() == EconomyService.TransactionStatus.SUCCESS,
                        "Reversal account setup failed");
                var offer = state.shopInstance(shopId).orElseThrow().offers().get(offerId);
                UUID purchaseId = UUID.randomUUID();
                helper.assertTrue(ShopTradeService.trade(
                        state, player, new ShopTradeService.TradeRequest(
                                shopId, offerId, ShopTradeService.Direction.BUY, 1,
                                offer.item(), offer.buyPrice().orElseThrow(), purchaseId),
                        server.overworld().getGameTime(), timestamp + 2).status() == ShopTradeService.Status.SUCCESS,
                        "Reversal purchase setup failed");
                UUID reversalId = UUID.randomUUID();
                helper.assertTrue(EconomyReversalService.reverse(
                        state, player, AdministrationService.SYSTEM_ACTOR, true, purchaseId,
                        EconomyTransactionReceipt.CompensationDecision.NONE, "gametest exact reversal",
                        timestamp + 3, reversalId).status() == EconomyReversalService.Status.SUCCESS,
                        "Exact purchase reversal failed");
                helper.assertTrue(state.economyBalance(player.getUUID()).orElseThrow() == 100,
                        "Purchase reversal did not restore balance");
                helper.assertTrue(state.shopInstance(shopId).orElseThrow().offers().get(offerId).stock().current() == 10,
                        "Purchase reversal did not restore stock");
                helper.assertTrue(player.getInventory().getNonEquipmentItems().stream()
                                .noneMatch(stack -> net.minecraft.world.item.ItemStack.isSameItemSameComponents(
                                        stack, offer.item())),
                        "Purchase reversal did not reclaim the exact granted stack");
                helper.assertTrue(state.economyReceipt(purchaseId).orElseThrow().reversedBy()
                                .equals(Optional.of(reversalId)),
                        "Purchase receipt was not linked directly to its reversal");
                var decoded = PlatformSavedData.CODEC.parse(NbtOps.INSTANCE,
                        PlatformSavedData.CODEC.encodeStart(NbtOps.INSTANCE, state).getOrThrow()).getOrThrow();
                helper.assertTrue(decoded.economyReceipt(purchaseId).orElseThrow().item().orElseThrow().getCount() == 4,
                        "Exact purchase receipt evidence did not survive persistence");
                helper.assertTrue(decoded.economyReceipt(purchaseId).orElseThrow().reversedBy()
                                .equals(Optional.of(reversalId)),
                        "Reversal status did not survive persistence");
                ShopInstanceService.delete(
                        state, AdministrationService.SYSTEM_ACTOR, true, shopId,
                        "gametest reversal cleanup", timestamp + 4, UUID.randomUUID());
                helper.succeed();
            }
        });
        event.registerTest(id("portal_safe_arrival"), new FunctionGameTestInstance(
                BuiltinTestFunctions.ALWAYS_PASS, testData) {
            @Override
            public void run(GameTestHelper helper) {
                var level = helper.getLevel();
                var state = PlatformSavedData.get(level.getServer());
                BlockPos origin = helper.absolutePos(new BlockPos(1, 2, 1));
                BlockPos destination = helper.absolutePos(new BlockPos(4, 2, 4));
                BlockPos unsafeDestination = helper.absolutePos(new BlockPos(6, 2, 6));
                level.setBlock(origin, Blocks.OBSIDIAN.defaultBlockState(), 3);
                level.setBlock(destination.below(), Blocks.STONE.defaultBlockState(), 3);
                level.setBlock(destination, Blocks.AIR.defaultBlockState(), 3);
                level.setBlock(destination.above(), Blocks.AIR.defaultBlockState(), 3);
                level.setBlock(unsafeDestination.below(), Blocks.STONE.defaultBlockState(), 3);
                level.setBlock(unsafeDestination, Blocks.STONE.defaultBlockState(), 3);
                level.setBlock(unsafeDestination.above(), Blocks.AIR.defaultBlockState(), 3);

                Identifier portalId = id("gametest_portal_" + UUID.randomUUID());
                long timestamp = System.currentTimeMillis();
                PortalDefinition definition = new PortalDefinition(
                        AdministrationService.SYSTEM_ACTOR,
                        new PortalDefinition.Endpoint(level.dimension(), origin),
                        new PortalDefinition.Endpoint(level.dimension(), destination),
                        0,
                        5_000,
                        PortalDefinition.SafeArrivalPolicy.EXACT,
                        true);
                helper.assertTrue(PortalService.create(
                        state,
                        AdministrationService.SYSTEM_ACTOR,
                        true,
                        portalId,
                        definition,
                        endpoint -> level.dimension().equals(endpoint.dimension())
                                && level.isInWorldBounds(endpoint.position()),
                        "gametest portal",
                        timestamp,
                        UUID.randomUUID()).status() == PortalService.Status.SUCCESS,
                        "Portal GameTest setup failed");

                UUID safePlayer = UUID.randomUUID();
                helper.assertTrue(PortalTravelService.resolveSafeDestination(
                                level, state, safePlayer, portalId, definition).equals(Optional.of(destination)),
                        "Server blocks did not produce the configured safe arrival");

                PortalDefinition unsafe = new PortalDefinition(
                        AdministrationService.SYSTEM_ACTOR,
                        definition.origin(),
                        new PortalDefinition.Endpoint(level.dimension(), unsafeDestination),
                        0,
                        5_000,
                        PortalDefinition.SafeArrivalPolicy.EXACT,
                        true);
                helper.assertTrue(PortalService.edit(
                        state,
                        AdministrationService.SYSTEM_ACTOR,
                        true,
                        portalId,
                        unsafe,
                        endpoint -> level.dimension().equals(endpoint.dimension())
                                && level.isInWorldBounds(endpoint.position()),
                        "gametest unsafe target",
                        timestamp + 2_000,
                        UUID.randomUUID()).status() == PortalService.Status.SUCCESS,
                        "Unsafe-target portal edit failed");
                var blocked = (net.minecraft.server.level.ServerPlayer) helper.makeMockServerPlayer(
                        net.minecraft.world.level.GameType.SURVIVAL);
                blocked.setPos(origin.getX() + 0.5, origin.getY(), origin.getZ() + 0.5);
                var blockedUse = new PlayerInteractEvent.RightClickBlock(
                        blocked,
                        InteractionHand.MAIN_HAND,
                        origin,
                        new BlockHitResult(Vec3.atCenterOf(origin), Direction.UP, origin, false));
                NeoForge.EVENT_BUS.post(blockedUse);
                helper.assertTrue(blockedUse.isCanceled(), "Unsafe portal interaction was not consumed");
                helper.assertTrue(blocked.blockPosition().equals(origin),
                        "Unsafe destination changed the player position");
                helper.assertTrue(state.portalCooldownUntil(blocked.getUUID(), portalId) == 0,
                        "Unsafe destination applied a partial cooldown");
                helper.assertTrue(PortalTravelService.resolveSafeDestination(
                                level, state, blocked.getUUID(), portalId, unsafe).isEmpty(),
                        "Obstructed server blocks were accepted as a safe arrival");

                helper.assertTrue(PortalService.delete(
                        state,
                        AdministrationService.SYSTEM_ACTOR,
                        true,
                        portalId,
                        "gametest cleanup",
                        timestamp + 4_000,
                        UUID.randomUUID()).status() == PortalService.Status.SUCCESS,
                        "Portal GameTest cleanup failed");
                helper.succeed();
            }
        });
    }

    private void addServerReloadListeners(AddServerReloadListenersEvent event) {
        event.addRetainedListener(TestDefinitionReloadListener.KEY, new TestDefinitionReloadListener());
        event.addRetainedListener(ShopTemplateReloadListener.KEY, shopTemplates);
        event.addRetainedListener(MobContentReloadListener.KEY, mobContent);
        event.addRetainedListener(RpgDefinitionReloadListener.KEY, rpgDefinitions);
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }
}
