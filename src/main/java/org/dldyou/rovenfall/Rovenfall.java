package org.dldyou.rovenfall;

import com.mojang.authlib.GameProfile;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.nio.charset.StandardCharsets;
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
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.HashedStack;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
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
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.entity.projectile.hurtingprojectile.SmallFireball;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.AddServerReloadListenersEvent;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.event.entity.EntityInvulnerabilityCheckEvent;
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent;
import net.neoforged.neoforge.event.entity.living.BabyEntitySpawnEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.AdvancementEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.entity.player.UseItemOnBlockEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.BlockDropsEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.level.PistonEvent;
import net.neoforged.neoforge.event.level.block.BreakBlockEvent;
import org.dldyou.rovenfall.administration.EconomyConfig;
import org.dldyou.rovenfall.administration.EconomyService;
import org.dldyou.rovenfall.administration.EconomyReversalService;
import org.dldyou.rovenfall.administration.EconomyTransactionReceipt;
import org.dldyou.rovenfall.administration.AdministrationService;
import org.dldyou.rovenfall.administration.AdministrationControlCenterMenu;
import org.dldyou.rovenfall.administration.AdministrationEconomyMenu;
import org.dldyou.rovenfall.administration.AdminRole;
import org.dldyou.rovenfall.administration.BossRewardService;
import org.dldyou.rovenfall.administration.BossAdministrationService;
import org.dldyou.rovenfall.administration.PlatformSavedData;
import org.dldyou.rovenfall.administration.OperationsMetricsService;
import org.dldyou.rovenfall.administration.PlayerRecordService;
import org.dldyou.rovenfall.administration.PlayerMenuNetwork;
import org.dldyou.rovenfall.administration.PlayerClaimMenu;
import org.dldyou.rovenfall.administration.PlayerDashboardMenu;
import org.dldyou.rovenfall.administration.PlayerRpgMenu;
import org.dldyou.rovenfall.administration.PlayerShopMenu;
import org.dldyou.rovenfall.administration.RovenfallInventoryClient;
import org.dldyou.rovenfall.administration.RpgAdministrationService;
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
import org.dldyou.rovenfall.administration.WildernessResetEvents;
import org.dldyou.rovenfall.administration.WildernessResetService;
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
import org.dldyou.rovenfall.mobs.BossEncounterRuntime;
import org.dldyou.rovenfall.mobs.BossEncounterSavedData;
import org.dldyou.rovenfall.mobs.BossEncounterState;
import org.dldyou.rovenfall.mobs.BossRewardOperation;
import org.dldyou.rovenfall.mobs.BossRewardSavedData;
import org.dldyou.rovenfall.mobs.MobMutationRuntime;
import org.dldyou.rovenfall.mobs.RovenfallMobClient;
import org.dldyou.rovenfall.mobs.RovenfallMobEntities;
import org.dldyou.rovenfall.mobs.RovenfallMobRuntime;
import org.dldyou.rovenfall.rpg.RpgDefinitionReloadListener;
import org.dldyou.rovenfall.rpg.ActivityXpConfig;
import org.dldyou.rovenfall.rpg.ActivityXpAwardService;
import org.dldyou.rovenfall.rpg.ActivityWorldSavedData;
import org.dldyou.rovenfall.rpg.RpgActivityEvents;
import org.dldyou.rovenfall.rpg.RpgActiveSkillRuntime;
import org.dldyou.rovenfall.rpg.RpgActiveSkillService;
import org.dldyou.rovenfall.rpg.RpgPlayerSavedData;
import org.dldyou.rovenfall.rpg.CareerProgressionService;
import org.dldyou.rovenfall.rpg.RpgSkillService;
import org.dldyou.rovenfall.rpg.SkillDefinition;
import org.dldyou.rovenfall.rpg.RpgSkillEvents;
import org.dldyou.rovenfall.rpg.RpgSkillClient;
import org.dldyou.rovenfall.rpg.RpgSkillNetwork;
import org.dldyou.rovenfall.rpg.RpgSkillResetCoordinator;
import org.dldyou.rovenfall.rpg.PlayerCareerPromotionService;
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
        RovenfallMobEntities.register(modBus);
        modContainer.registerConfig(ModConfig.Type.SERVER, EconomyConfig.SPEC);
        modContainer.registerConfig(ModConfig.Type.SERVER, ActivityXpConfig.SPEC, "rovenfall-rpg-server.toml");
        modContainer.registerConfig(ModConfig.Type.SERVER, ClaimConfig.SPEC, "rovenfall-claims-server.toml");
        modBus.addListener(this::registerGameTests);
        modBus.addListener(RpgSkillNetwork::registerPayloads);
        modBus.addListener(PlayerMenuNetwork::registerPayloads);
        if (FMLEnvironment.getDist() == Dist.CLIENT) {
            RpgSkillClient.register(modBus);
            RovenfallMobClient.register(modBus);
            RovenfallInventoryClient.register();
        }
        NeoForge.EVENT_BUS.addListener(RovenfallCommands::register);
        NeoForge.EVENT_BUS.addListener(EconomyService::onPlayerLoggedIn);
        NeoForge.EVENT_BUS.addListener(RpgSkillResetCoordinator::onPlayerLoggedIn);
        NeoForge.EVENT_BUS.addListener(PlayerCareerPromotionService::onPlayerLoggedIn);
        NeoForge.EVENT_BUS.addListener(RpgAdministrationService::onPlayerLoggedIn);
        NeoForge.EVENT_BUS.addListener(RpgSkillNetwork::onPlayerLoggedIn);
        NeoForge.EVENT_BUS.addListener(RpgSkillNetwork::onPlayerLoggedOut);
        NeoForge.EVENT_BUS.addListener(PlayerMenuNetwork::onPlayerLoggedOut);
        NeoForge.EVENT_BUS.addListener(PlayerRecordService::onPlayerLoggedIn);
        PortalEvents.register(NeoForge.EVENT_BUS);
        WildernessResetEvents.register(NeoForge.EVENT_BUS);
        ClaimProtectionEvents.register(NeoForge.EVENT_BUS);
        RpgSkillEvents.register(NeoForge.EVENT_BUS);
        RovenfallMobRuntime.register(NeoForge.EVENT_BUS);
        MobMutationRuntime.register(NeoForge.EVENT_BUS);
        BossEncounterRuntime.register(NeoForge.EVENT_BUS);
        NeoForge.EVENT_BUS.addListener(this::addServerReloadListeners);
        NeoForge.EVENT_BUS.addListener(shopTemplates::onDefaultDataComponentsBound);
        NeoForge.EVENT_BUS.addListener(RpgActivityEvents::onDamage);
        // Observe managed encounter identity before the LOWEST-priority boss handler finalizes it.
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGH, RpgActivityEvents::onDeath);
        NeoForge.EVENT_BUS.addListener(RpgActivityEvents::onServerTick);
        NeoForge.EVENT_BUS.addListener(RpgActivityEvents::onCrafted);
        NeoForge.EVENT_BUS.addListener(RpgActivityEvents::onSmelted);
        NeoForge.EVENT_BUS.addListener(RpgActivityEvents::onAdvancement);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, RpgActivityEvents::onPlace);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, RpgActivityEvents::onBlockDrops);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, RpgActivityEvents::onBreeding);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, RpgActivityEvents::onPistonPre);
    }

    private void registerGameTests(RegisterGameTestsEvent event) {
        var environment = event.registerEnvironment(id("empty"), new TestEnvironmentDefinition.AllOf(List.of()));
        var activeSkillEnvironment = event.registerEnvironment(
                id("active_skill"), new TestEnvironmentDefinition.AllOf(List.of()));
        var testData = new TestData<>(environment, Identifier.withDefaultNamespace("empty"), 1, 0, true);
        event.registerTest(id("foundation"), new FunctionGameTestInstance(BuiltinTestFunctions.ALWAYS_PASS, testData));
        event.registerTest(id("player_gui_navigation"), new FunctionGameTestInstance(
                BuiltinTestFunctions.ALWAYS_PASS,
                new TestData<>(environment, Identifier.withDefaultNamespace("empty"), 10, 0, true)) {
            @Override
            public void run(GameTestHelper helper) {
                var player = helper.makeMockServerPlayerInLevel();
                PlayerDashboardMenu.open(player);
                helper.assertTrue(player.containerMenu instanceof PlayerDashboardMenu
                                && ((net.minecraft.world.inventory.ChestMenu) player.containerMenu).getRowCount() == 3,
                        "Inventory overview did not open as the expected three-row menu");

                int currentState = player.containerMenu.getStateId();
                player.connection.handleContainerClick(playerMenuClick(
                        player.containerMenu, 13, currentState, ContainerInput.QUICK_MOVE));
                helper.assertTrue(player.containerMenu instanceof PlayerDashboardMenu,
                        "Non-primary packet input invoked a player-menu action");
                player.connection.handleContainerClick(playerMenuClick(
                        player.containerMenu, 13, currentState - 1, ContainerInput.PICKUP));
                helper.assertTrue(player.containerMenu instanceof PlayerDashboardMenu,
                        "A stale container-state packet invoked a player-menu action");
                player.connection.handleContainerClick(playerMenuClick(
                        player.containerMenu, 13, player.containerMenu.getStateId(), ContainerInput.PICKUP));
                helper.assertTrue(player.containerMenu instanceof PlayerClaimMenu
                                && ((net.minecraft.world.inventory.ChestMenu) player.containerMenu).getRowCount() == 6,
                        "Claim tab did not open as the expected six-row menu");
                player.containerMenu.clicked(45, 0, ContainerInput.PICKUP, player);

                helper.assertTrue(player.containerMenu instanceof PlayerDashboardMenu,
                        "Claim back action did not reopen the overview");
                player.containerMenu.clicked(16, 0, ContainerInput.PICKUP, player);
                helper.assertTrue(player.containerMenu instanceof PlayerRpgMenu
                                && ((net.minecraft.world.inventory.ChestMenu) player.containerMenu).getRowCount() == 6,
                        "RPG tab did not open as the expected six-row menu");
                player.containerMenu.clicked(45, 0, ContainerInput.PICKUP, player);

                helper.assertTrue(player.containerMenu instanceof PlayerDashboardMenu,
                        "RPG back action did not reopen the overview");
                player.containerMenu.clicked(10, 0, ContainerInput.PICKUP, player);
                helper.runAfterDelay(1, () -> {
                    player.containerMenu.clicked(15, 0, ContainerInput.PICKUP, player);
                    helper.assertTrue(player.containerMenu instanceof PlayerShopMenu
                                    && ((net.minecraft.world.inventory.ChestMenu) player.containerMenu).getRowCount() == 6,
                            "Shop action did not open as the expected six-row menu");
                    player.containerMenu.clicked(45, 0, ContainerInput.PICKUP, player);
                    helper.assertTrue(player.containerMenu instanceof PlayerDashboardMenu,
                            "Shop back action did not reopen the overview");
                    player.discard();
                    helper.succeed();
                });
            }
        });
        event.registerTest(id("admin_gui_role_revalidation"), new FunctionGameTestInstance(
                BuiltinTestFunctions.ALWAYS_PASS,
                new TestData<>(environment, Identifier.withDefaultNamespace("empty"), 10, 0, true)) {
            @Override
            public void run(GameTestHelper helper) {
                var server = helper.getLevel().getServer();
                var player = helper.makeMockServerPlayerInLevel();
                var platform = PlatformSavedData.get(server);
                helper.assertTrue(AdministrationService.changeRole(
                                platform, AdministrationService.SYSTEM_ACTOR, true, player.getUUID(),
                                AdminRole.OWNER.getSerializedName(), "gametest admin gui",
                                System.currentTimeMillis(), UUID.randomUUID()).status()
                                == AdministrationService.RoleChangeStatus.SUCCESS,
                        "Could not assign the GameTest owner role by UUID");
                helper.assertTrue(AdministrationControlCenterMenu.open(player)
                                && player.containerMenu instanceof AdministrationControlCenterMenu,
                        "Authorized owner could not open the administration control center");
                player.containerMenu.clicked(10, 0, ContainerInput.PICKUP, player);
                helper.assertTrue(player.containerMenu instanceof AdministrationControlCenterMenu,
                        "Owner could not open the claims administration view");

                helper.assertTrue(AdministrationService.changeRole(
                                platform, AdministrationService.SYSTEM_ACTOR, true, player.getUUID(),
                                AdminRole.ECONOMY_MANAGER.getSerializedName(), "gametest cross-domain demotion",
                                System.currentTimeMillis(), UUID.randomUUID()).status()
                                == AdministrationService.RoleChangeStatus.SUCCESS,
                        "Could not change the GameTest role by UUID");
                helper.runAfterDelay(1, () -> {
                    if (player.containerMenu instanceof AdministrationControlCenterMenu) {
                        player.containerMenu.clicked(53, 0, ContainerInput.PICKUP, player);
                    }
                    helper.assertTrue(player.containerMenu == player.inventoryMenu,
                            "A role change did not invalidate the now-forbidden open claims view");
                    player.discard();
                    helper.succeed();
                });
            }
        });
        event.registerTest(id("admin_economy_gui_role_revalidation"), new FunctionGameTestInstance(
                BuiltinTestFunctions.ALWAYS_PASS,
                new TestData<>(environment, Identifier.withDefaultNamespace("empty"), 10, 0, true)) {
            @Override
            public void run(GameTestHelper helper) {
                var server = helper.getLevel().getServer();
                var player = helper.makeMockServerPlayerInLevel();
                var platform = PlatformSavedData.get(server);
                helper.assertTrue(AdministrationService.changeRole(
                                platform, AdministrationService.SYSTEM_ACTOR, true, player.getUUID(),
                                AdminRole.OWNER.getSerializedName(), "gametest economy gui",
                                System.currentTimeMillis(), UUID.randomUUID()).status()
                                == AdministrationService.RoleChangeStatus.SUCCESS,
                        "Could not assign the GameTest owner role by UUID");
                helper.assertTrue(AdministrationControlCenterMenu.open(player),
                        "Owner could not open the administration control center");
                player.containerMenu.clicked(11, 0, ContainerInput.PICKUP, player);
                helper.assertTrue(player.containerMenu instanceof AdministrationEconomyMenu,
                        "Shops domain did not open the typed economy administration menu");
                helper.assertTrue(player.containerMenu.getSlot(46).hasItem(),
                        "Owner could not see the shop create mutation control");
                Identifier guiShopId = id("gui_" + UUID.randomUUID());
                player.containerMenu.clicked(46, 0, ContainerInput.PICKUP, player);
                helper.assertTrue(((AdministrationEconomyMenu) player.containerMenu).applyTextInput(
                                player, guiShopId + ",rovenfall:foundation | gametest gui create"),
                        "The shop create form did not produce a server preview");
                helper.runAfterDelay(1, () -> {
                    player.containerMenu.clicked(31, 0, ContainerInput.PICKUP, player);
                    helper.assertTrue(platform.shopInstance(guiShopId).isPresent(),
                            "Confirmed GUI shop create did not call the audited shop service");
                    helper.assertTrue(AdministrationService.changeRole(
                                    platform, AdministrationService.SYSTEM_ACTOR, true, player.getUUID(),
                                    AdminRole.VIEWER.getSerializedName(), "gametest read-only demotion",
                                    System.currentTimeMillis(), UUID.randomUUID()).status()
                                    == AdministrationService.RoleChangeStatus.SUCCESS,
                            "Could not demote the GameTest owner to viewer");
                    helper.assertTrue(AdministrationEconomyMenu.open(
                                    player, org.dldyou.rovenfall.administration.AdministrationReadViewService.Domain.SHOPS),
                            "Viewer could not reopen the read-only shops view");
                    helper.assertTrue(player.containerMenu instanceof AdministrationEconomyMenu
                                    && !player.containerMenu.getSlot(46).hasItem(),
                            "Viewer retained a shop mutation control after demotion");
                    helper.assertTrue(AdministrationService.changeRole(
                                    platform, AdministrationService.SYSTEM_ACTOR, true, player.getUUID(),
                                    AdminRole.CONTENT_MANAGER.getSerializedName(), "gametest cross-domain change",
                                    System.currentTimeMillis(), UUID.randomUUID()).status()
                                    == AdministrationService.RoleChangeStatus.SUCCESS,
                            "Could not move the GameTest viewer to content manager");
                    helper.runAfterDelay(1, () -> {
                        if (player.containerMenu instanceof AdministrationEconomyMenu) {
                            player.containerMenu.clicked(53, 0, ContainerInput.PICKUP, player);
                        }
                        helper.assertTrue(player.containerMenu == player.inventoryMenu,
                                "A cross-domain role change did not close the shops administration view");
                        player.discard();
                        helper.succeed();
                    });
                });
            }
        });
        event.registerTest(id("admin_economy_gui_balance_and_reversal"), new FunctionGameTestInstance(
                BuiltinTestFunctions.ALWAYS_PASS,
                new TestData<>(environment, Identifier.withDefaultNamespace("empty"), 65, 0, true)) {
            @Override
            public void run(GameTestHelper helper) {
                var server = helper.getLevel().getServer();
                var player = helper.makeMockServerPlayerInLevel();
                var platform = PlatformSavedData.get(server);
                helper.assertTrue(AdministrationService.changeRole(
                                platform, AdministrationService.SYSTEM_ACTOR, true, player.getUUID(),
                                AdminRole.OWNER.getSerializedName(), "gametest economy actions",
                                System.currentTimeMillis(), UUID.randomUUID()).status()
                                == AdministrationService.RoleChangeStatus.SUCCESS,
                        "Could not assign the GameTest owner role");
                helper.assertTrue(platform.playerRecord(player.getUUID()).isPresent(),
                        "Mock login did not persist the target player record");
                long before = platform.economyBalance(player.getUUID()).orElse(EconomyConfig.initialBalance());
                helper.assertTrue(AdministrationEconomyMenu.open(
                                player, org.dldyou.rovenfall.administration.AdministrationReadViewService.Domain.PLAYERS),
                        "Could not open the player economy administration view");
                helper.assertTrue(((AdministrationEconomyMenu) player.containerMenu)
                                .applyTextInput(player, player.getUUID().toString()),
                        "Could not search the player by server UUID");
                player.containerMenu.clicked(9, 0, ContainerInput.PICKUP, player);
                helper.runAfterDelay(1, () -> {
                    player.containerMenu.clicked(20, 0, ContainerInput.PICKUP, player);
                    helper.assertTrue(((AdministrationEconomyMenu) player.containerMenu)
                                    .applyTextInput(player, "10 | gametest gui grant"),
                            "Balance form did not produce a preview");
                    helper.runAfterDelay(1, () -> {
                        player.containerMenu.clicked(31, 0, ContainerInput.PICKUP, player);
                        helper.assertTrue(platform.economyBalance(player.getUUID()).orElseThrow() == before + 10,
                                "Confirmed GUI grant did not update the balance");
                        UUID grantId = platform.recentAuditEntries(20).stream()
                                .filter(entry -> entry.actionType().getPath().equals("economy_admin_grant"))
                                .filter(entry -> entry.target().equals(player.getUUID().toString()))
                                .map(org.dldyou.rovenfall.administration.AuditEntry::transactionId)
                                .findFirst().orElseThrow();
                        helper.runAfterDelay(20, () -> {
                            helper.assertTrue(AdministrationEconomyMenu.open(
                                            player,
                                            org.dldyou.rovenfall.administration.AdministrationReadViewService.Domain.RECEIPTS),
                                    "Could not open the receipt administration view");
                            helper.assertTrue(((AdministrationEconomyMenu) player.containerMenu)
                                            .applyTextInput(player, grantId.toString()),
                                    "Could not search the grant receipt by transaction ID");
                            player.containerMenu.clicked(9, 0, ContainerInput.PICKUP, player);
                            helper.runAfterDelay(1, () -> {
                                player.containerMenu.clicked(31, 0, ContainerInput.PICKUP, player);
                                helper.assertTrue(((AdministrationEconomyMenu) player.containerMenu)
                                                .applyTextInput(player, " | gametest gui reversal"),
                                        "Reversal form did not produce a preview");
                                helper.assertTrue(EconomyService.award(
                                                platform, player.getUUID(), 1, "gametest concurrent balance change",
                                                System.currentTimeMillis(), UUID.randomUUID(),
                                                EconomyConfig.initialBalance(), EconomyConfig.maximumBalance()).status()
                                                == EconomyService.TransactionStatus.SUCCESS,
                                        "Could not create the concurrent balance change");
                                helper.runAfterDelay(1, () -> {
                                    player.containerMenu.clicked(31, 0, ContainerInput.PICKUP, player);
                                    helper.assertTrue(
                                            platform.economyBalance(player.getUUID()).orElseThrow() == before + 11,
                                            "Stale GUI reversal changed the concurrent balance");
                                    helper.assertTrue(platform.economyReceipt(grantId)
                                                    .flatMap(EconomyTransactionReceipt::reversedBy).isEmpty(),
                                            "Stale GUI reversal linked the original receipt");
                                    helper.assertTrue(platform.recentAuditEntries(20).stream()
                                                    .anyMatch(entry -> entry.reason().equals("stale_confirmation")),
                                            "Stale GUI reversal was not audited");
                                    helper.runAfterDelay(1, () -> {
                                        player.containerMenu.clicked(31, 0, ContainerInput.PICKUP, player);
                                        helper.runAfterDelay(1, () -> {
                                            player.containerMenu.clicked(31, 0, ContainerInput.PICKUP, player);
                                            helper.assertTrue(((AdministrationEconomyMenu) player.containerMenu)
                                                            .applyTextInput(player, " | gametest refreshed reversal"),
                                                    "Refreshed reversal did not produce a preview");
                                            helper.runAfterDelay(20, () -> {
                                                player.containerMenu.clicked(31, 0, ContainerInput.PICKUP, player);
                                                helper.assertTrue(
                                                        platform.economyBalance(player.getUUID()).orElseThrow()
                                                                == before + 1,
                                                        "Refreshed GUI reversal did not preserve the concurrent award");
                                                helper.assertTrue(platform.economyReceipt(grantId)
                                                                .flatMap(EconomyTransactionReceipt::reversedBy).isPresent(),
                                                        "GUI reversal did not link the original receipt");
                                                player.discard();
                                                helper.succeed();
                                            });
                                        });
                                    });
                                });
                            });
                        });
                    });
                });
            }
        });
        event.registerTest(id("admin_economy_gui_stale_and_unauthorized"), new FunctionGameTestInstance(
                BuiltinTestFunctions.ALWAYS_PASS,
                new TestData<>(environment, Identifier.withDefaultNamespace("empty"), 20, 0, true)) {
            @Override
            public void run(GameTestHelper helper) {
                var server = helper.getLevel().getServer();
                var player = helper.makeMockServerPlayerInLevel();
                var deniedPlayer = helper.makeMockServerPlayerInLevel();
                var platform = PlatformSavedData.get(server);
                Identifier staleShop = id("stale_gui_" + UUID.randomUUID());
                Identifier deniedShop = id("denied_gui_" + UUID.randomUUID());
                helper.assertTrue(AdministrationService.changeRole(
                                platform, AdministrationService.SYSTEM_ACTOR, true, player.getUUID(),
                                AdminRole.OWNER.getSerializedName(), "gametest guarded actions",
                                System.currentTimeMillis(), UUID.randomUUID()).status()
                                == AdministrationService.RoleChangeStatus.SUCCESS,
                        "Could not assign the guarded-action owner role");
                helper.assertTrue(AdministrationService.changeRole(
                                platform, AdministrationService.SYSTEM_ACTOR, true, deniedPlayer.getUUID(),
                                AdminRole.OWNER.getSerializedName(), "gametest unauthorized actor",
                                System.currentTimeMillis(), UUID.randomUUID()).status()
                                == AdministrationService.RoleChangeStatus.SUCCESS,
                        "Could not assign the authorization-test owner role");
                for (Identifier shopId : List.of(staleShop, deniedShop)) {
                    helper.assertTrue(ShopInstanceService.create(
                                    platform, ShopTemplateReloadListener.snapshot(server),
                                    AdministrationService.SYSTEM_ACTOR, true, shopId, id("foundation"),
                                    Optional.empty(), key -> server.getLevel(key) != null,
                                    ShopInstance.AccessPolicy.publicAccess(), server.overworld().getGameTime(),
                                    "gametest guarded shop", System.currentTimeMillis(), UUID.randomUUID()).status()
                                    == ShopInstanceService.Status.SUCCESS,
                            "Could not prepare guarded GUI shop " + shopId);
                }
                helper.assertTrue(AdministrationEconomyMenu.open(
                                player, org.dldyou.rovenfall.administration.AdministrationReadViewService.Domain.SHOPS),
                        "Could not open the guarded shops view");
                helper.assertTrue(((AdministrationEconomyMenu) player.containerMenu)
                                .applyTextInput(player, staleShop.toString()),
                        "Could not search the stale-test shop");
                player.containerMenu.clicked(9, 0, ContainerInput.PICKUP, player);
                helper.runAfterDelay(1, () -> {
                    player.containerMenu.clicked(13, 0, ContainerInput.PICKUP, player);
                    helper.assertTrue(((AdministrationEconomyMenu) player.containerMenu)
                                    .applyTextInput(player, "12 | gametest stale access"),
                            "Access form did not produce a stale-test preview");
                    helper.assertTrue(ShopInstanceService.delete(
                                    platform, AdministrationService.SYSTEM_ACTOR, true, staleShop,
                                    "gametest concurrent delete", System.currentTimeMillis(), UUID.randomUUID()).status()
                                    == ShopInstanceService.Status.SUCCESS,
                            "Could not create the concurrent shop change");
                    helper.runAfterDelay(1, () -> {
                        player.containerMenu.clicked(31, 0, ContainerInput.PICKUP, player);
                        helper.assertTrue(platform.shopInstance(staleShop).isEmpty(),
                                "Stale GUI confirmation recreated or overwrote the deleted shop");
                        helper.assertTrue(platform.recentAuditEntries(20).stream()
                                        .anyMatch(entry -> entry.reason().equals("stale_confirmation")),
                                "Stale GUI confirmation was not audited");
                        helper.runAfterDelay(1, () -> {
                            helper.assertTrue(AdministrationEconomyMenu.open(
                                            deniedPlayer,
                                            org.dldyou.rovenfall.administration.AdministrationReadViewService.Domain.SHOPS),
                                    "Could not reopen shops for the authorization test");
                            helper.assertTrue(((AdministrationEconomyMenu) deniedPlayer.containerMenu)
                                            .applyTextInput(deniedPlayer, deniedShop.toString()),
                                    "Could not search the authorization-test shop");
                            deniedPlayer.containerMenu.clicked(9, 0, ContainerInput.PICKUP, deniedPlayer);
                            helper.runAfterDelay(1, () -> {
                                deniedPlayer.containerMenu.clicked(10, 0, ContainerInput.PICKUP, deniedPlayer);
                                helper.assertTrue(((AdministrationEconomyMenu) deniedPlayer.containerMenu)
                                                .applyTextInput(deniedPlayer, " | gametest unauthorized delete"),
                                        "Delete form did not produce an authorization-test preview");
                                helper.assertTrue(AdministrationService.changeRole(
                                                platform, AdministrationService.SYSTEM_ACTOR, true, deniedPlayer.getUUID(),
                                                AdminRole.VIEWER.getSerializedName(), "gametest confirmation demotion",
                                                System.currentTimeMillis(), UUID.randomUUID()).status()
                                                == AdministrationService.RoleChangeStatus.SUCCESS,
                                    "Could not demote the confirmation actor");
                                helper.runAfterDelay(1, () -> {
                                    deniedPlayer.containerMenu.clicked(31, 0, ContainerInput.PICKUP, deniedPlayer);
                                    helper.assertTrue(platform.shopInstance(deniedShop).isPresent(),
                                            "Demoted actor deleted a shop through a crafted confirmation click");
                                    helper.assertTrue(platform.recentAuditEntries(20).stream()
                                                    .anyMatch(entry -> entry.reason().equals("unauthorized")),
                                            "Unauthorized GUI confirmation was not audited");
                                    player.discard();
                                    deniedPlayer.discard();
                                    helper.succeed();
                                });
                            });
                        });
                    });
                });
            }
        });
        event.registerTest(id("operations_metrics_snapshot"), new FunctionGameTestInstance(
                BuiltinTestFunctions.ALWAYS_PASS, testData) {
            @Override
            public void run(GameTestHelper helper) {
                var server = helper.getLevel().getServer();
                var platform = PlatformSavedData.get(server);
                int auditCount = platform.auditCount();
                var result = OperationsMetricsService.snapshot(
                        server, AdministrationService.SYSTEM_ACTOR, true,
                        System.currentTimeMillis(), OperationsMetricsService.DEFAULT_WINDOW_MILLIS);
                helper.assertTrue(result.status() == OperationsMetricsService.Status.SUCCESS,
                        "Operations metrics did not read the live server-owned stores");
                helper.assertTrue(result.scannedRpgPlayers() <= OperationsMetricsService.MAX_RPG_PLAYERS,
                        "Operations metrics exceeded the RPG player scan cap");
                helper.assertTrue(platform.auditCount() == auditCount,
                        "Read-only operations metrics mutated platform state");
                helper.succeed();
            }
        });
        event.registerTest(id("wilderness_reset_preflight"), new FunctionGameTestInstance(
                BuiltinTestFunctions.ALWAYS_PASS, testData) {
            @Override
            public void run(GameTestHelper helper) {
                var server = helper.getLevel().getServer();
                helper.assertTrue(WildernessResetService.findSafeHubArrival(server.overworld()).isPresent(),
                        "Hub has no safe reset evacuation destination");
                var state = PlatformSavedData.get(server);
                var rpg = RpgPlayerSavedData.get(server);
                UUID preservedPlayer = UUID.randomUUID();
                UUID warningId = UUID.randomUUID();
                long now = System.currentTimeMillis();
                helper.assertTrue(ActivityXpAwardService.award(
                                rpg, RpgDefinitionReloadListener.snapshot(server), preservedPlayer,
                                id("combat"), 1, now, UUID.randomUUID(), "wilderness-preflight")
                                .status() == ActivityXpAwardService.Status.SUCCESS,
                        "Could not create isolated RPG preservation evidence");
                var preservedRpgState = rpg.state(preservedPlayer);
                helper.assertTrue(WildernessResetService.warn(
                                state, AdministrationService.SYSTEM_ACTOR, true,
                                "gametest unavailable topology", now, warningId).status()
                                == WildernessResetService.Status.SUCCESS,
                        "Wilderness reset warning preflight failed");
                var rejected = WildernessResetService.reset(
                        server, AdministrationService.SYSTEM_ACTOR, true, warningId,
                        "gametest unavailable topology", now + 1, UUID.randomUUID());
                helper.assertTrue(rejected.status() == WildernessResetService.Status.TOPOLOGY_UNAVAILABLE,
                        "Missing Wilderness topology did not fail closed");
                helper.assertTrue(!state.isWildernessOperationLocked(),
                        "Failed Wilderness preflight left an operation lock");
                helper.assertTrue(rpg.state(preservedPlayer).equals(preservedRpgState),
                        "Failed Wilderness preflight changed global RPG state");
                helper.succeed();
            }
        });
        event.registerTest(id("mob_content_definitions"), new FunctionGameTestInstance(
                BuiltinTestFunctions.ALWAYS_PASS, testData) {
            @Override
            public void run(GameTestHelper helper) {
                var snapshot = MobContentReloadListener.snapshot(helper.getLevel().getServer());
                helper.assertTrue(snapshot.size() == 11, "Built-in mob content catalog did not load atomically");
                helper.assertTrue(snapshot.mob(id("grove_stalker")).orElseThrow().loot()
                                .equals(id("grove_stalker_loot")),
                        "Custom mob reward reference was not preserved");
                helper.assertTrue(snapshot.mutation(id("volatile")).orElseThrow().eligibleEntityTypes().size() == 5,
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
        event.registerTest(id("ordinary_custom_mobs"), new FunctionGameTestInstance(
                BuiltinTestFunctions.ALWAYS_PASS, testData) {
            @Override
            public void run(GameTestHelper helper) {
                var level = helper.getLevel();
                var snapshot = MobContentReloadListener.snapshot(level.getServer());
                var groveDefinition = snapshot.mob(RovenfallMobEntities.GROVE_STALKER_ID).orElseThrow();
                var beetleDefinition = snapshot.mob(RovenfallMobEntities.OREBOUND_BEETLE_ID).orElseThrow();
                helper.assertTrue(groveDefinition.spawn().orElseThrow().dimension().equals(WorldTopology.WILDERNESS)
                                && beetleDefinition.spawn().orElseThrow().dimension().equals(WorldTopology.WILDERNESS),
                        "Ordinary mob spawn rules are not restricted to the Wilderness");

                var grove = RovenfallMobEntities.GROVE_STALKER.get().create(level, EntitySpawnReason.COMMAND);
                var beetle = RovenfallMobEntities.OREBOUND_BEETLE.get().create(level, EntitySpawnReason.COMMAND);
                var groveTarget = EntityTypes.COW.create(level, EntitySpawnReason.COMMAND);
                var beetleTarget = EntityTypes.COW.create(level, EntitySpawnReason.COMMAND);
                helper.assertTrue(grove != null && beetle != null && groveTarget != null && beetleTarget != null,
                        "Could not construct ordinary custom mob combat fixtures");

                RovenfallMobRuntime.applyDefinition(grove, groveDefinition, false);
                RovenfallMobRuntime.applyDefinition(beetle, beetleDefinition, false);
                helper.assertTrue(grove.getAttributeValue(Attributes.MAX_HEALTH) == 30.0
                                && grove.getAttributeValue(Attributes.ATTACK_DAMAGE) == 6.0
                                && beetle.getAttributeValue(Attributes.MAX_HEALTH) == 24.0
                                && beetle.getAttributeValue(Attributes.ATTACK_DAMAGE) == 5.0
                                && grove.getExperienceReward(level, null) == 12
                                && beetle.getExperienceReward(level, null) == 10,
                        "Data-driven ordinary mob attributes were not applied");

                float groveTargetHealth = groveTarget.getHealth();
                float beetleTargetHealth = beetleTarget.getHealth();
                helper.assertTrue(grove.doHurtTarget(level, groveTarget)
                                && beetle.doHurtTarget(level, beetleTarget)
                                && Math.abs(groveTarget.getHealth() - (groveTargetHealth - 6.0F)) < 0.01F
                                && Math.abs(beetleTarget.getHealth() - (beetleTargetHealth - 5.0F)) < 0.01F,
                        "Ordinary custom mob combat damage did not match definitions");

                grove.setHealth(11.0F);
                RovenfallMobRuntime.applyDefinition(grove, groveDefinition, true);
                helper.assertTrue(grove.getHealth() == 11.0F,
                        "Reloading a persisted custom mob healed it unexpectedly");

                BlockPos hubPosition = helper.absolutePos(new BlockPos(1, 2, 1));
                grove.snapTo(hubPosition.getX() + 0.5, hubPosition.getY(), hubPosition.getZ() + 0.5, 0, 0);
                helper.assertTrue(!level.addFreshEntity(grove),
                        "A Wilderness-only custom mob was admitted to the Hub");
                var rewardPlayer = helper.makeMockServerPlayer(net.minecraft.world.level.GameType.SURVIVAL);
                helper.assertTrue(RovenfallMobRuntime.isEligibleRewardPlayer(rewardPlayer)
                                && !RovenfallMobRuntime.isEligibleRewardPlayer(FakePlayerFactory.getMinecraft(level)),
                        "Custom mob reward player eligibility did not distinguish real and fake players");
                rewardPlayer.discard();
                beetle.discard();
                groveTarget.discard();
                beetleTarget.discard();
                helper.succeed();
            }
        });
        event.registerTest(id("mutation_modifiers"), new FunctionGameTestInstance(
                BuiltinTestFunctions.ALWAYS_PASS, testData) {
            @Override
            public void run(GameTestHelper helper) {
                var level = helper.getLevel();
                var mutation = MobContentReloadListener.snapshot(level.getServer())
                        .mutation(id("volatile")).orElseThrow();
                var stacked = new MobContentCatalog.MutationDefinition(
                        id("volatile_stacked"), mutation.translationKey(), mutation.eligibleEntityTypes(),
                        mutation.attributes(), mutation.behaviorModifiers(), mutation.markerTranslationKey(),
                        mutation.spawn(), 125, mutation.bonusLoot());
                var zombie = EntityTypes.ZOMBIE.create(level, EntitySpawnReason.COMMAND);
                helper.assertTrue(zombie != null, "Could not create mutation test mob");

                var maxHealth = zombie.getAttribute(Attributes.MAX_HEALTH);
                helper.assertTrue(maxHealth != null, "Mutation test mob has no max-health attribute");
                double originalMaximum = zombie.getMaxHealth();
                int originalModifiers = maxHealth.getModifiers().size();
                MobMutationRuntime.applyMutations(zombie, List.of(mutation, stacked), false);
                helper.assertTrue(MobMutationRuntime.mutationIds(zombie).size() == 2
                                && maxHealth.getModifiers().size() == originalModifiers + 2
                                && zombie.getMaxHealth() > originalMaximum
                                && zombie.getHealth() == zombie.getMaxHealth()
                                && zombie.hasGlowingTag()
                                && zombie.getCustomName() != null
                                && zombie.isCustomNameVisible(),
                        "Stacked mutation identity or attributes were not applied");

                zombie.setHealth(13.0F);
                MobMutationRuntime.applyMutations(zombie, List.of(mutation, stacked), true);
                helper.assertTrue(zombie.getHealth() == 13.0F
                                && maxHealth.getModifiers().size() == originalModifiers + 2,
                        "Persisted mutation reload healed the mob or duplicated modifiers");

                BlockPos hubPosition = helper.absolutePos(new BlockPos(1, 2, 1));
                zombie.snapTo(hubPosition.getX() + 0.5, hubPosition.getY(), hubPosition.getZ() + 0.5, 0, 0);
                helper.assertTrue(level.addFreshEntity(zombie), "Could not add mutation fixture to the Hub");
                helper.assertTrue(MobMutationRuntime.mutationIds(zombie).isEmpty()
                                && maxHealth.getModifiers().size() == originalModifiers
                                && zombie.getMaxHealth() == originalMaximum
                                && !zombie.hasGlowingTag()
                                && zombie.getCustomName() == null,
                        "Hub admission did not clean persisted mutation state");
                zombie.discard();
                helper.succeed();
            }
        });
        event.registerTest(id("boss_encounter_lifecycle"), new FunctionGameTestInstance(
                BuiltinTestFunctions.ALWAYS_PASS, testData) {
            @Override
            public void run(GameTestHelper helper) {
                var server = helper.getLevel().getServer();
                var level = helper.getLevel();
                var snapshot = MobContentReloadListener.snapshot(server);
                var bossDefinition = snapshot.boss(id("rift_warden")).orElseThrow();
                long timestamp = System.currentTimeMillis();
                UUID encounterId = UUID.randomUUID();

                var started = BossEncounterRuntime.start(server, bossDefinition.id(), timestamp, encounterId);
                helper.assertTrue(started.status() == BossEncounterRuntime.StartStatus.TOPOLOGY_UNAVAILABLE,
                        "Boss encounter bypassed the missing Wilderness topology");

                BlockPos center = helper.absolutePos(new BlockPos(1, 2, 1));
                var boss = EntityTypes.IRON_GOLEM.create(level, EntitySpawnReason.COMMAND);
                helper.assertTrue(boss != null, "Could not construct boss lifecycle fixture");
                boss.snapTo(center.getX() + 0.5D, center.getY(), center.getZ() + 0.5D, 0, 0);
                helper.assertTrue(level.addFreshEntity(boss), "Could not register boss lifecycle fixture");
                var protectedRegion = new ProtectedRegion(
                        AdministrationService.SYSTEM_ACTOR, level.dimension(), center.getX() >> 4,
                        center.getZ() >> 4, center.getX() >> 4, center.getZ() >> 4);
                var reserved = ProtectedRegionService.create(
                        PlatformSavedData.get(server), AdministrationService.SYSTEM_ACTOR, true,
                        BossEncounterRuntime.regionId(encounterId), protectedRegion, "gametest boss arena",
                        timestamp + 1, encounterId);
                helper.assertTrue(reserved.status() == ProtectedRegionService.Status.SUCCESS,
                        "Could not reserve boss lifecycle arena fixture");
                var encounter = BossEncounterState.start(
                        encounterId, bossDefinition.id(), UUID.randomUUID(), boss.getUUID(), level.dimension(),
                        center, protectedRegion, timestamp, level.getGameTime());
                helper.assertTrue(BossEncounterSavedData.get(server).put(encounter),
                        "Could not persist boss lifecycle fixture");
                UUID adminTransaction = UUID.randomUUID();
                var adminReset = BossAdministrationService.reset(
                        server, AdministrationService.SYSTEM_ACTOR, true, encounterId,
                        "gametest safe boss reset", timestamp + 2, adminTransaction);
                var adminReplay = BossAdministrationService.reset(
                        server, AdministrationService.SYSTEM_ACTOR, true, encounterId,
                        "gametest safe boss reset", timestamp + 3, adminTransaction);
                helper.assertTrue(adminReset.status() == BossAdministrationService.Status.SUCCESS
                                && adminReplay.status() == BossAdministrationService.Status.DUPLICATE
                                && !boss.isAlive()
                                && BossEncounterSavedData.get(server).encounter(encounterId).isEmpty()
                                && PlatformSavedData.get(server).protectedRegion(
                                        BossEncounterRuntime.regionId(encounterId)).isEmpty(),
                        "Audited boss reset was not safe and idempotent");

                UUID completionId = UUID.randomUUID();
                var completedBoss = EntityTypes.IRON_GOLEM.create(level, EntitySpawnReason.COMMAND);
                helper.assertTrue(completedBoss != null, "Could not construct boss completion fixture");
                completedBoss.snapTo(center.getX() + 0.5D, center.getY(), center.getZ() + 0.5D, 0, 0);
                helper.assertTrue(level.addFreshEntity(completedBoss), "Could not register boss completion fixture");
                completedBoss.getPersistentData().putString("rovenfall:boss_encounter", completionId.toString());
                var completionReservation = ProtectedRegionService.create(
                        PlatformSavedData.get(server), AdministrationService.SYSTEM_ACTOR, true,
                        BossEncounterRuntime.regionId(completionId), protectedRegion, "gametest boss completion",
                        timestamp + 3, completionId);
                helper.assertTrue(completionReservation.status() == ProtectedRegionService.Status.SUCCESS,
                        "Could not reserve boss completion arena fixture");
                var completionState = BossEncounterState.start(
                        completionId, bossDefinition.id(), UUID.randomUUID(), completedBoss.getUUID(),
                        level.dimension(), center, protectedRegion, timestamp, level.getGameTime());
                helper.assertTrue(BossEncounterSavedData.get(server).put(completionState),
                        "Could not persist boss completion fixture");
                var decoy = EntityTypes.IRON_GOLEM.create(level, EntitySpawnReason.COMMAND);
                helper.assertTrue(decoy != null, "Could not construct deceptive boss fixture");
                decoy.snapTo(center.getX() + 1.5D, center.getY(), center.getZ() + 0.5D, 0, 0);
                helper.assertTrue(level.addFreshEntity(decoy), "Could not register deceptive boss fixture");
                decoy.getPersistentData().putString("rovenfall:boss_encounter", completionId.toString());
                decoy.hurtServer(level, level.damageSources().genericKill(), Float.MAX_VALUE);
                helper.assertTrue(BossEncounterSavedData.get(server).encounter(completionId).isPresent()
                                && PlatformSavedData.get(server).protectedRegion(
                                        BossEncounterRuntime.regionId(completionId)).isPresent(),
                        "A deceptive tagged death completed the real boss encounter");
                helper.assertTrue(BossEncounterRuntime.reset(server, completionId, timestamp + 4),
                        "Could not clean the deceptive death fixture");

                UUID orphanId = UUID.randomUUID();
                var orphan = ProtectedRegionService.create(
                        PlatformSavedData.get(server), AdministrationService.SYSTEM_ACTOR, true,
                        BossEncounterRuntime.regionId(orphanId), protectedRegion, "gametest orphan arena",
                        timestamp + 5, orphanId);
                helper.assertTrue(orphan.status() == ProtectedRegionService.Status.SUCCESS,
                        "Could not construct orphan arena recovery fixture");
                UUID recoveryTransaction = UUID.randomUUID();
                var adminRecovery = BossAdministrationService.recover(
                        server, AdministrationService.SYSTEM_ACTOR, true,
                        "gametest stuck encounter recovery", timestamp + 6, recoveryTransaction);
                var recoveryReplay = BossAdministrationService.recover(
                        server, AdministrationService.SYSTEM_ACTOR, true,
                        "gametest stuck encounter recovery", timestamp + 7, recoveryTransaction);
                helper.assertTrue(PlatformSavedData.get(server).protectedRegion(
                                BossEncounterRuntime.regionId(orphanId)).isEmpty()
                                && (adminRecovery.status() == BossAdministrationService.Status.SUCCESS
                                        && recoveryReplay.status() == BossAdministrationService.Status.DUPLICATE
                                    || adminRecovery.status() == BossAdministrationService.Status.RECOVERY_PENDING
                                        && recoveryReplay.status()
                                            == BossAdministrationService.Status.RECOVERY_PENDING),
                        "Audited recovery did not remove an orphan arena idempotently");

                UUID unrelatedId = UUID.randomUUID();
                UUID unrelatedTransaction = UUID.randomUUID();
                var unrelated = ProtectedRegionService.create(
                        PlatformSavedData.get(server), AdministrationService.SYSTEM_ACTOR, true,
                        BossEncounterRuntime.regionId(unrelatedId), protectedRegion, "gametest unrelated arena",
                        timestamp + 7, unrelatedTransaction);
                helper.assertTrue(unrelated.status() == ProtectedRegionService.Status.SUCCESS,
                        "Could not construct unrelated protected-region fixture");
                BossEncounterRuntime.recover(server, timestamp + 8);
                helper.assertTrue(PlatformSavedData.get(server).protectedRegion(
                                BossEncounterRuntime.regionId(unrelatedId)).isPresent(),
                        "Recovery removed a protected region without a matching encounter reservation");
                ProtectedRegionService.delete(
                        PlatformSavedData.get(server), AdministrationService.SYSTEM_ACTOR, true,
                        BossEncounterRuntime.regionId(unrelatedId), "gametest fixture cleanup",
                        timestamp + 9, UUID.randomUUID());
                helper.succeed();
            }
        });
        event.registerTest(id("boss_reward_recovery"), new FunctionGameTestInstance(
                BuiltinTestFunctions.ALWAYS_PASS, testData) {
            @Override
            public void run(GameTestHelper helper) {
                var server = helper.getLevel().getServer();
                var level = helper.getLevel();
                var snapshot = MobContentReloadListener.snapshot(server);
                var boss = snapshot.boss(id("rift_warden")).orElseThrow();
                var mob = snapshot.mob(boss.mob()).orElseThrow();
                var arena = snapshot.arena(boss.arena()).orElseThrow();
                var contribution = snapshot.contributionRule(boss.contributionRule()).orElseThrow();
                var loot = snapshot.loot(boss.loot()).orElseThrow();
                var player = (net.minecraft.server.level.ServerPlayer) helper.makeMockServerPlayer(
                        net.minecraft.world.level.GameType.SURVIVAL);
                BlockPos center = helper.absolutePos(new BlockPos(1, 2, 1));
                player.snapTo(center.getX() + 0.5D, center.getY(), center.getZ() + 0.5D, 0, 0);
                long timestamp = System.currentTimeMillis();
                UUID encounterId = UUID.randomUUID();
                var reservation = new ProtectedRegion(
                        AdministrationService.SYSTEM_ACTOR, level.dimension(), center.getX() >> 4,
                        center.getZ() >> 4, center.getX() >> 4, center.getZ() >> 4);
                var encounter = BossEncounterState.start(
                        encounterId, boss.id(), BossEncounterRuntime.definitionFingerprint(
                                boss, arena, mob, contribution, loot), UUID.randomUUID(), level.dimension(),
                        center, reservation, timestamp, level.getGameTime())
                        .contribute(player.getUUID(), contribution.minimumPoints(),
                                contribution.maximumContributors(), timestamp + 1);
                long balanceBefore = PlatformSavedData.get(server).economyBalance(player.getUUID()).orElse(0L);
                long xpBefore = RpgPlayerSavedData.get(server).state(player.getUUID())
                        .activityXp().getOrDefault(id("hunting"), 0L);

                var prepared = BossRewardService.prepare(
                        server, encounter, boss, mob, contribution, loot, timestamp + 2);
                UUID transactionId = BossRewardService.transactionId(encounterId, player.getUUID());
                helper.assertTrue(prepared.status().durable()
                                && BossRewardSavedData.get(server).operation(transactionId).isPresent()
                                && PlatformSavedData.get(server).economyBalance(player.getUUID()).orElse(0L)
                                        == balanceBefore + loot.currency()
                                && RpgPlayerSavedData.get(server).state(player.getUUID())
                                        .activityXp().getOrDefault(id("hunting"), 0L)
                                        == xpBefore + loot.experience(),
                        "Qualified boss contribution did not durably award economy and RPG progression");

                BossRewardService.recover(server, timestamp + 3);
                var replay = BossRewardService.prepare(
                        server, encounter, boss, mob, contribution, loot, timestamp + 4);
                helper.assertTrue(replay.status().durable()
                                && PlatformSavedData.get(server).economyBalance(player.getUUID()).orElse(0L)
                                        == balanceBefore + loot.currency()
                                && RpgPlayerSavedData.get(server).state(player.getUUID())
                                        .activityXp().getOrDefault(id("hunting"), 0L)
                                        == xpBefore + loot.experience(),
                        "Boss reward replay duplicated currency or progression");

                UUID cooldownEncounterId = UUID.randomUUID();
                var cooldownEncounter = BossEncounterState.start(
                        cooldownEncounterId, boss.id(), encounter.definitionFingerprint(), UUID.randomUUID(),
                        level.dimension(), center, reservation, timestamp + 5, level.getGameTime())
                        .contribute(player.getUUID(), contribution.minimumPoints(),
                                contribution.maximumContributors(), timestamp + 5);
                var cooldown = BossRewardService.prepare(
                        server, cooldownEncounter, boss, mob, contribution, loot, timestamp + 6);
                var cooldownAudit = PlatformSavedData.get(server).auditPage(0, 50).entries().stream()
                        .filter(entry -> entry.target().equals(player.getUUID().toString()))
                        .map(entry -> entry.actionType() + ":" + entry.reason()).toList();
                helper.assertTrue(cooldown.status() == BossRewardService.PreparationStatus.NO_QUALIFIED_PLAYERS
                                && BossRewardSavedData.get(server).operation(BossRewardService.transactionId(
                                        cooldownEncounterId, player.getUUID())).isEmpty()
                                && PlatformSavedData.get(server).auditPage(0, 50).entries().stream()
                                        .anyMatch(entry -> entry.actionType().equals(id("boss_reward_denied"))
                                                && entry.target().equals(player.getUUID().toString())
                                                && entry.reason().equals("personal_cooldown")),
                        "Personal boss cooldown did not deny and audit the replay: status="
                                + cooldown.status() + ", audit=" + cooldownAudit);

                var restartPlayer = helper.makeMockServerPlayerInLevel();
                restartPlayer.snapTo(center.getX() + 1.5D, center.getY(), center.getZ() + 0.5D, 0, 0);
                UUID restartEncounterId = UUID.randomUUID();
                UUID restartTransactionId = BossRewardService.transactionId(
                        restartEncounterId, restartPlayer.getUUID());
                Identifier restartBossId = id("boss_reward_restart_fixture");
                long restartCreatedAt = timestamp + 100;
                long restartCooldownUntil = restartCreatedAt + 60_000;
                var restartPending = new BossRewardOperation(
                        restartEncounterId, restartBossId, UUID.randomUUID(), restartPlayer.getUUID(),
                        level.dimension(), center, 25, 100, 10, 2_500,
                        40, 30, restartCooldownUntil, restartCreatedAt,
                        List.of(new ItemStack(Items.BREAD, 2)), BossRewardOperation.Phase.PENDING);
                var rewards = BossRewardSavedData.get(server);
                helper.assertTrue(rewards.putBatch(
                                java.util.Map.of(restartTransactionId, restartPending), restartCreatedAt)
                                == BossRewardSavedData.BatchStatus.SUCCESS,
                        "Could not persist the restart recovery reward fixture");
                var registryOps = RegistryOps.create(NbtOps.INSTANCE, level.registryAccess());
                var persistedRewards = BossRewardSavedData.CODEC.parse(
                        registryOps,
                        BossRewardSavedData.CODEC.encodeStart(registryOps, rewards).getOrThrow()).getOrThrow();
                var persistedPending = persistedRewards.operation(restartTransactionId).orElseThrow();
                helper.assertTrue(persistedPending.phase() == BossRewardOperation.Phase.PENDING
                                && persistedPending.items().size() == 1
                                && persistedPending.items().getFirst().getCount() == 2
                                && persistedRewards.cooldownUntil(
                                        restartBossId, restartPlayer.getUUID(), null, restartCreatedAt)
                                        == restartCooldownUntil,
                        "Pending boss reward evidence did not survive the simulated restart");
                server.overworld().getDataStorage().set(BossRewardSavedData.TYPE, persistedRewards);
                rewards = persistedRewards;

                long restartBalanceBefore = PlatformSavedData.get(server)
                        .economyBalance(restartPlayer.getUUID()).orElse(0L);
                long restartXpBefore = RpgPlayerSavedData.get(server).state(restartPlayer.getUUID())
                        .activityXp().getOrDefault(id("hunting"), 0L);
                String restartAuditEvidence = "currency=" + restartPending.currency()
                        + ",xp=" + restartPending.experience() + ",items=" + restartPending.items().size();
                long restartAuditBefore = PlatformSavedData.get(server).auditPage(0, 50).entries().stream()
                        .filter(entry -> entry.actionType().equals(id("boss_reward_completed"))
                                && entry.target().equals(restartPlayer.getUUID().toString())
                                && entry.afterValue().equals(restartAuditEvidence))
                        .count();
                UUID itemEntityId = UUID.nameUUIDFromBytes(
                        ("boss_reward_item:" + restartTransactionId + ":0")
                                .getBytes(StandardCharsets.UTF_8));

                BossRewardService.recover(server, restartCreatedAt + 1);
                var completedRestart = rewards.operation(restartTransactionId).orElseThrow();
                var deliveredItem = level.getEntity(itemEntityId);
                long restartBalanceAfter = PlatformSavedData.get(server)
                        .economyBalance(restartPlayer.getUUID()).orElse(0L);
                long restartXpAfter = RpgPlayerSavedData.get(server).state(restartPlayer.getUUID())
                        .activityXp().getOrDefault(id("hunting"), 0L);
                long restartAuditAfter = PlatformSavedData.get(server).auditPage(0, 50).entries().stream()
                        .filter(entry -> entry.actionType().equals(id("boss_reward_completed"))
                                && entry.target().equals(restartPlayer.getUUID().toString())
                                && entry.afterValue().equals(restartAuditEvidence))
                        .count();
                helper.assertTrue(completedRestart.phase() == BossRewardOperation.Phase.COMPLETED
                                && restartBalanceAfter == restartBalanceBefore + restartPending.currency()
                                && restartXpAfter == restartXpBefore + restartPending.experience()
                                && deliveredItem instanceof net.minecraft.world.entity.item.ItemEntity item
                                && item.getItem().is(Items.BREAD) && item.getItem().getCount() == 2
                                && rewards.cooldownUntil(
                                        restartBossId, restartPlayer.getUUID(), null, restartCreatedAt + 1)
                                        == restartCooldownUntil
                                && restartAuditAfter == restartAuditBefore + 1
                                && rewards.pendingOperations().stream()
                                        .noneMatch(entry -> entry.getKey().equals(restartTransactionId)),
                        "Restart recovery did not complete every durable boss reward leg exactly once: phase="
                                + completedRestart.phase() + ",balance=" + restartBalanceAfter + "/"
                                + (restartBalanceBefore + restartPending.currency()) + ",xp=" + restartXpAfter + "/"
                                + (restartXpBefore + restartPending.experience()) + ",item=" + deliveredItem
                                + ",cooldown=" + rewards.cooldownUntil(
                                        restartBossId, restartPlayer.getUUID(), null, restartCreatedAt + 1)
                                + "/" + restartCooldownUntil + ",audit=" + restartAuditAfter + "/"
                                + (restartAuditBefore + 1) + ",pending=" + rewards.pendingOperations().size());
                server.getPlayerList().remove(restartPlayer);

                helper.assertTrue(rewards.putBatch(
                                java.util.Map.of(restartTransactionId, restartPending), restartCreatedAt + 2)
                                == BossRewardSavedData.BatchStatus.DUPLICATE,
                        "Exact boss reward retry did not retain its transaction identity");
                BossRewardService.recover(server, restartCreatedAt + 3);
                helper.assertTrue(rewards.operation(restartTransactionId).orElseThrow().phase()
                                        == BossRewardOperation.Phase.COMPLETED
                                && PlatformSavedData.get(server).economyBalance(restartPlayer.getUUID()).orElse(0L)
                                        == restartBalanceAfter
                                && RpgPlayerSavedData.get(server).state(restartPlayer.getUUID())
                                        .activityXp().getOrDefault(id("hunting"), 0L) == restartXpAfter
                                && level.getEntity(itemEntityId) == deliveredItem
                                && rewards.cooldownUntil(
                                        restartBossId, restartPlayer.getUUID(), null, restartCreatedAt + 3)
                                        == restartCooldownUntil
                                && PlatformSavedData.get(server).auditPage(0, 50).entries().stream()
                                        .filter(entry -> entry.actionType().equals(id("boss_reward_completed"))
                                                && entry.target().equals(restartPlayer.getUUID().toString())
                                                && entry.afterValue().equals(restartAuditEvidence))
                                        .count() == restartAuditAfter
                                && rewards.pendingOperations().stream()
                                        .noneMatch(entry -> entry.getKey().equals(restartTransactionId)),
                        "Boss reward retry duplicated currency, XP, item, cooldown, audit, or cleanup");
                deliveredItem.discard();

                var recoveryPlayer = (net.minecraft.server.level.ServerPlayer) helper.makeMockServerPlayer(
                        net.minecraft.world.level.GameType.SURVIVAL);
                recoveryPlayer.snapTo(center.getX() + 0.5D, center.getY(), center.getZ() + 1.5D, 0, 0);
                UUID recoveryEncounterId = UUID.randomUUID();
                var recoveryArena = new MobContentCatalog.ArenaPolicy(
                        id("boss_reward_recovery_arena"), level.dimension(), center, 1, 2, 20);
                var recoveryBoss = new MobContentCatalog.BossDefinition(
                        id("boss_reward_recovery"), "boss.rovenfall.rift_warden", mob.id(), recoveryArena.id(),
                        contribution.id(), loot.id(), boss.rewardCooldownTicks(), boss.phases());
                var recoveryReservation = BossEncounterRuntime.regionFor(recoveryArena);
                var recoveryRegion = ProtectedRegionService.create(
                        PlatformSavedData.get(server), AdministrationService.SYSTEM_ACTOR, true,
                        BossEncounterRuntime.regionId(recoveryEncounterId), recoveryReservation,
                        "gametest pending boss reward", timestamp + 7, recoveryEncounterId);
                helper.assertTrue(recoveryRegion.status() == ProtectedRegionService.Status.SUCCESS,
                        "Could not reserve the pending boss reward arena fixture");
                UUID recoveryFingerprint = BossEncounterRuntime.definitionFingerprint(
                        recoveryBoss, recoveryArena, mob, contribution, loot);
                var recoveryEncounter = BossEncounterState.start(
                        recoveryEncounterId, recoveryBoss.id(), recoveryFingerprint, UUID.randomUUID(),
                        level.dimension(), center, recoveryReservation, timestamp + 7, level.getGameTime())
                        .contribute(recoveryPlayer.getUUID(), contribution.minimumPoints(),
                                contribution.maximumContributors(), timestamp + 7)
                        .markRewardPending(new BossEncounterState.RewardPlan(
                                recoveryBoss, recoveryArena, mob, contribution, loot));
                helper.assertTrue(BossEncounterSavedData.get(server).put(recoveryEncounter),
                        "Could not persist the pending boss reward recovery fixture");

                var safePendingReset = BossAdministrationService.reset(
                        server, AdministrationService.SYSTEM_ACTOR, true, recoveryEncounterId,
                        "gametest pending reward reset", timestamp + 8, UUID.randomUUID());

                helper.assertTrue(safePendingReset.status() == BossAdministrationService.Status.SUCCESS
                                && BossEncounterSavedData.get(server).encounter(recoveryEncounterId).isEmpty()
                                && BossRewardSavedData.get(server).operation(BossRewardService.transactionId(
                                        recoveryEncounterId, recoveryPlayer.getUUID())).isPresent(),
                        "Administrator reset lost reward intent before durable reward evidence existed");
                recoveryPlayer.discard();
                player.discard();
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
                helper.assertTrue(active.kind() == SkillDefinition.Kind.ACTIVE && active.cooldownTicks().isPresent()
                                && active.activeEffect().orElseThrow().target()
                                == SkillDefinition.TargetType.LIVING_ENTITY,
                        "Active skill metadata was not preserved");
                var passive = snapshot.skill(id("battle_fury")).orElseThrow();
                helper.assertTrue(passive.kind() == SkillDefinition.Kind.PASSIVE
                                && passive.passiveEffect().orElseThrow().type()
                                == SkillDefinition.EffectType.DAMAGE_DEALT,
                        "Passive skill effect metadata was not preserved");
                helper.succeed();
            }
        });
        event.registerTest(id("rpg_active_skill"), new FunctionGameTestInstance(
                BuiltinTestFunctions.ALWAYS_PASS,
                new TestData<>(activeSkillEnvironment, Identifier.withDefaultNamespace("empty"), 10, 0, true)) {
            @Override
            public void run(GameTestHelper helper) {
                var server = helper.getLevel().getServer();
                var definitions = RpgDefinitionReloadListener.snapshot(server);
                var state = RpgPlayerSavedData.get(server);
                var player = helper.makeMockServerPlayerInLevel();
                var activeSkillLevel = helper.getLevel();
                BlockPos playerPosition = helper.absolutePos(new BlockPos(1, 2, 1));
                player.setPos(
                        playerPosition.getX() + 0.5, playerPosition.getY(), playerPosition.getZ() + 0.5);
                helper.assertTrue(AdministrationService.changeRole(
                                PlatformSavedData.get(server), AdministrationService.SYSTEM_ACTOR, true,
                                player.getUUID(), AdminRole.MODERATOR.getSerializedName(),
                                "gametest active skill", System.currentTimeMillis(), UUID.randomUUID()).status()
                                == AdministrationService.RoleChangeStatus.SUCCESS,
                        "Could not grant the GameTest actor a protected-region override");
                helper.assertTrue(PlatformSavedData.get(server).roleOf(player.getUUID()).orElse(null)
                                == AdminRole.MODERATOR,
                        "Could not grant the GameTest actor a protected-region override");
                var target = helper.spawn(EntityTypes.COW, new BlockPos(3, 2, 1));
                long timestamp = System.currentTimeMillis();

                helper.assertTrue(CareerProgressionService.promote(
                                state, definitions, player.getUUID(), id("novice"), timestamp,
                                UUID.randomUUID(), "gametest:active:promote_novice").status()
                                == CareerProgressionService.Status.SUCCESS,
                        "Could not promote the active-skill fixture to novice");
                for (int index = 0; index < 60; index++) {
                    helper.assertTrue(ActivityXpAwardService.award(
                                    state, definitions, player.getUUID(), id("combat"), 10,
                                    timestamp + (index + 1L) * 4_000L, UUID.randomUUID(),
                                    "gametest:active:novice_" + index).status()
                                    == ActivityXpAwardService.Status.SUCCESS,
                            "Could not rank the novice active-skill fixture");
                }
                helper.assertTrue(RpgSkillService.learn(
                                state, definitions, player.getUUID(), id("sturdy_body"),
                                timestamp + 245_000, UUID.randomUUID(), "gametest:active").status()
                                == RpgSkillService.Status.SUCCESS
                                && RpgSkillService.learn(
                                state, definitions, player.getUUID(), id("sturdy_body"),
                                timestamp + 245_001, UUID.randomUUID(), "gametest:active").status()
                                == RpgSkillService.Status.SUCCESS,
                        "Could not learn the power-strike prerequisite");
                helper.assertTrue(CareerProgressionService.promote(
                                state, definitions, player.getUUID(), id("warrior"), timestamp + 246_000,
                                UUID.randomUUID(), "gametest:active:promote_warrior").status()
                                == CareerProgressionService.Status.SUCCESS,
                        "Could not promote the active-skill fixture to warrior");
                for (int index = 0; index < 100; index++) {
                    helper.assertTrue(ActivityXpAwardService.award(
                                    state, definitions, player.getUUID(), id("combat"), 10,
                                    timestamp + 250_000L + index * 4_000L, UUID.randomUUID(),
                                    "gametest:active:warrior_" + index).status()
                                    == ActivityXpAwardService.Status.SUCCESS,
                            "Could not rank the warrior active-skill fixture");
                }
                helper.assertTrue(RpgSkillService.learn(
                                state, definitions, player.getUUID(), id("power_strike"),
                                timestamp + 650_000, UUID.randomUUID(), "gametest:active").status()
                                == RpgSkillService.Status.SUCCESS,
                        "Could not learn power strike");
                helper.assertTrue(RpgActiveSkillService.assignSlot(
                                state, definitions, player.getUUID(), 0, Optional.of(id("power_strike")),
                                4, timestamp + 651_000, UUID.randomUUID(), "gametest:active").status()
                                == RpgActiveSkillService.Status.SUCCESS,
                        "Could not bind power strike");

                helper.runAfterDelay(1, () -> {
                    try {
                        long gameTime = activeSkillLevel.getGameTime();
                    helper.assertTrue(player.isAlive() && !player.isSpectator(),
                            "Power-strike actor was not an eligible live player");
                    helper.assertTrue(activeSkillLevel.getEntity(target.getId()) == target && target.isAlive(),
                            "Power-strike target was not registered and alive");
                    helper.assertTrue(player.distanceToSqr(target) <= 36.0,
                            "Power-strike target was outside server range");
                    helper.assertTrue(player.hasLineOfSight(target),
                            "Power-strike target was not visible to the server");
                    var activated = RpgActiveSkillService.activate(
                            state,
                            definitions,
                            RpgDefinitionReloadListener.revision(server),
                            player.getUUID(),
                            new RpgActiveSkillService.ActivationRequest(
                                    RpgDefinitionReloadListener.revision(server),
                                    1,
                                    0,
                                    activeSkillLevel.dimension().identifier(),
                                    target.getId()),
                            4,
                            gameTime,
                            RpgActiveSkillRuntime.gateway(player));
                    helper.assertTrue(activated.status() == RpgActiveSkillService.Status.SUCCESS,
                            "Server did not activate the bound power strike: " + activated.status());
                    helper.assertTrue(state.state(player.getUUID()).cooldowns().get(id("power_strike"))
                                    == gameTime + 160,
                            "Power-strike cooldown was not committed");
                    float boosted = RpgActiveSkillRuntime.modifyDamage(
                            player, target, activeSkillLevel.dimension().identifier(), gameTime, 10F);
                    helper.assertTrue(Math.abs(boosted - 12.5F) < 0.001F,
                            "Power strike did not apply its rank-scaled server effect");
                    helper.assertTrue(RpgActiveSkillRuntime.modifyDamage(
                                    player, target, activeSkillLevel.dimension().identifier(), gameTime, 10F) == 10F,
                            "Power strike was applied more than once");

                    helper.assertTrue(RpgSkillService.learn(
                                    state, definitions, player.getUUID(), id("power_strike"),
                                    timestamp + 652_000, UUID.randomUUID(), "gametest:active").status()
                                    == RpgSkillService.Status.SUCCESS,
                            "Could not learn the shield-wall prerequisite");
                    helper.assertTrue(CareerProgressionService.promote(
                                    state, definitions, player.getUUID(), id("guardian"), timestamp + 653_000,
                                    UUID.randomUUID(), "gametest:active:promote_guardian").status()
                                    == CareerProgressionService.Status.SUCCESS,
                            "Could not promote the active-skill fixture to guardian");
                    for (int index = 0; index < 30; index++) {
                        helper.assertTrue(ActivityXpAwardService.award(
                                        state, definitions, player.getUUID(), id("combat"), 10,
                                        timestamp + 654_000L + index * 4_000L, UUID.randomUUID(),
                                        "gametest:active:guardian_" + index).status()
                                        == ActivityXpAwardService.Status.SUCCESS,
                                "Could not rank the guardian active-skill fixture");
                    }
                    helper.assertTrue(RpgSkillService.learn(
                                    state, definitions, player.getUUID(), id("shield_wall"),
                                    timestamp + 775_000, UUID.randomUUID(), "gametest:active").status()
                                    == RpgSkillService.Status.SUCCESS,
                            "Could not learn shield wall");
                    helper.assertTrue(RpgActiveSkillService.assignSlot(
                                    state, definitions, player.getUUID(), 1, Optional.of(id("shield_wall")),
                                    4, timestamp + 776_000, UUID.randomUUID(), "gametest:active").status()
                                    == RpgActiveSkillService.Status.SUCCESS,
                            "Could not bind shield wall");
                    var shield = RpgActiveSkillService.activate(
                            state,
                            definitions,
                            RpgDefinitionReloadListener.revision(server),
                            player.getUUID(),
                            new RpgActiveSkillService.ActivationRequest(
                                    RpgDefinitionReloadListener.revision(server),
                                    2,
                                    1,
                                    activeSkillLevel.dimension().identifier(),
                                    target.getId()),
                            4,
                            gameTime,
                            RpgActiveSkillRuntime.gateway(player));
                    helper.assertTrue(shield.status() == RpgActiveSkillService.Status.SUCCESS,
                            "Self-target shield wall was rejected while looking at an entity: " + shield.status());
                    float reduced = RpgActiveSkillRuntime.modifyDamage(
                            null, player, activeSkillLevel.dimension().identifier(), gameTime, 10F);
                    helper.assertTrue(Math.abs(reduced - 8F) < 0.001F,
                            "Shield wall did not reduce incoming damage");
                    helper.assertTrue(RpgActiveSkillRuntime.modifyDamage(
                                    null, player, activeSkillLevel.dimension().identifier(), gameTime + 100, 10F) == 10F,
                            "Shield wall did not expire at its server-defined duration");
                        helper.succeed();
                    } finally {
                        target.discard();
                        server.getPlayerList().remove(player);
                    }
                });
            }
        });
        event.registerTest(id("rpg_activity_xp"), new FunctionGameTestInstance(BuiltinTestFunctions.ALWAYS_PASS, testData) {
            @Override
            public void run(GameTestHelper helper) {
                var server = helper.getLevel().getServer();
                var state = RpgPlayerSavedData.get(server);
                UUID player = UUID.randomUUID();
                UUID transactionId = UUID.randomUUID();
                var result = ActivityXpAwardService.award(state,
                        RpgDefinitionReloadListener.snapshot(server), player, id("combat"), 1,
                        System.currentTimeMillis(), transactionId, "gametest:combat");
                helper.assertTrue(result.status() == ActivityXpAwardService.Status.SUCCESS,
                        "Activity XP was not committed");
                helper.assertTrue(state.state(player).provenance().size() == 1
                                && state.state(player).provenance().getFirst().transactionId().equals(transactionId),
                        "Activity XP provenance was not recorded");
                helper.assertTrue(RpgActivityEvents.blockActivity(Blocks.DIAMOND_ORE.defaultBlockState())
                                .equals(Optional.of(id("mining"))),
                        "Pickaxe-minable block was not classified as mining");
                helper.assertTrue(RpgActivityEvents.blockActivity(Blocks.WHEAT.defaultBlockState()
                                .setValue(BlockStateProperties.AGE_7, 7)).equals(Optional.of(id("farming")))
                                && RpgActivityEvents.blockActivity(Blocks.WHEAT.defaultBlockState()).isEmpty(),
                        "Mature and immature crops did not follow the farming policy");
                var naturalMiner = (net.minecraft.server.level.ServerPlayer) helper.makeMockServerPlayer(
                        net.minecraft.world.level.GameType.SURVIVAL);
                var nether = server.getLevel(net.minecraft.world.level.Level.NETHER);
                helper.assertTrue(nether != null, "Nether was unavailable for the activity policy test");
                BlockPos miningPosition = new BlockPos(12_345, 64, 12_345);
                NeoForge.EVENT_BUS.post(new BlockDropsEvent(
                        nether, miningPosition, Blocks.DIAMOND_ORE.defaultBlockState(), null,
                        new ArrayList<>(), naturalMiner, ItemStack.EMPTY));
                helper.assertTrue(RpgPlayerSavedData.get(server).state(naturalMiner.getUUID())
                                .activityXp().getOrDefault(id("mining"), 0L) == 1,
                        "Validated natural ore break did not award mining XP");

                var worldState = ActivityWorldSavedData.get(server);
                BlockPos syntheticPosition = miningPosition.east();
                helper.assertTrue(worldState.markSynthetic(nether.dimension(), syntheticPosition),
                        "Synthetic resource marker was not recorded");
                NeoForge.EVENT_BUS.post(new BlockDropsEvent(
                        nether, syntheticPosition, Blocks.DIAMOND_ORE.defaultBlockState(), null,
                        new ArrayList<>(), naturalMiner, ItemStack.EMPTY));
                helper.assertTrue(RpgPlayerSavedData.get(server).state(naturalMiner.getUUID())
                                .activityXp().getOrDefault(id("mining"), 0L) == 1,
                        "Player-placed ore awarded mining XP");

                var fakeMiner = FakePlayerFactory.getMinecraft(nether);
                long fakeBefore = RpgPlayerSavedData.get(server).state(fakeMiner.getUUID())
                        .activityXp().getOrDefault(id("mining"), 0L);
                NeoForge.EVENT_BUS.post(new BlockDropsEvent(
                        nether, miningPosition.west(), Blocks.DIAMOND_ORE.defaultBlockState(), null,
                        new ArrayList<>(), fakeMiner, ItemStack.EMPTY));
                helper.assertTrue(RpgPlayerSavedData.get(server).state(fakeMiner.getUUID())
                                .activityXp().getOrDefault(id("mining"), 0L) == fakeBefore,
                        "Synthetic fake-player action awarded mining XP");

                BlockPos protectedPosition = server.overworld().getRespawnData().pos();
                NeoForge.EVENT_BUS.post(new BlockDropsEvent(
                        server.overworld(), protectedPosition, Blocks.DIAMOND_ORE.defaultBlockState(), null,
                        new ArrayList<>(), naturalMiner, ItemStack.EMPTY));
                helper.assertTrue(RpgPlayerSavedData.get(server).state(naturalMiner.getUUID())
                                .activityXp().getOrDefault(id("mining"), 0L) == 1,
                        "Protected-region action awarded mining XP");

                var cookingPlayer = (net.minecraft.server.level.ServerPlayer) helper.makeMockServerPlayer(
                        net.minecraft.world.level.GameType.SURVIVAL);
                NeoForge.EVENT_BUS.post(new PlayerEvent.ItemCraftedEvent(
                        cookingPlayer, new ItemStack(Items.BREAD), new SimpleContainer(1)));
                NeoForge.EVENT_BUS.post(new PlayerEvent.ItemSmeltedEvent(
                        cookingPlayer, new ItemStack(Items.COOKED_BEEF), 1));
                helper.assertTrue(state.state(cookingPlayer.getUUID()).activityXp()
                                .getOrDefault(id("cooking"), 0L) == 2,
                        "Completed food crafting and smelting events did not award cooking XP");
                long fakeCookingBefore = state.state(fakeMiner.getUUID()).activityXp()
                        .getOrDefault(id("cooking"), 0L);
                NeoForge.EVENT_BUS.post(new PlayerEvent.ItemCraftedEvent(
                        fakeMiner, new ItemStack(Items.BREAD), new SimpleContainer(1)));
                helper.assertTrue(state.state(fakeMiner.getUUID()).activityXp()
                                .getOrDefault(id("cooking"), 0L) == fakeCookingBefore,
                        "Fake-player crafting event awarded cooking XP");

                var explorer = (net.minecraft.server.level.ServerPlayer) helper.makeMockServerPlayer(
                        net.minecraft.world.level.GameType.SURVIVAL);
                var ordinaryAdvancement = server.getAdvancements().get(
                        Identifier.withDefaultNamespace("story/mine_stone"));
                var explorationAdvancement = server.getAdvancements().get(
                        Identifier.withDefaultNamespace("adventure/adventuring_time"));
                helper.assertTrue(ordinaryAdvancement != null && explorationAdvancement != null,
                        "Built-in advancement fixtures were unavailable");
                NeoForge.EVENT_BUS.post(new AdvancementEvent.AdvancementEarnEvent(explorer, ordinaryAdvancement));
                NeoForge.EVENT_BUS.post(new AdvancementEvent.AdvancementEarnEvent(explorer, explorationAdvancement));
                NeoForge.EVENT_BUS.post(new AdvancementEvent.AdvancementEarnEvent(explorer, explorationAdvancement));
                helper.assertTrue(state.state(explorer.getUUID()).activityXp()
                                .getOrDefault(id("exploration"), 0L) == 1,
                        "Exploration whitelist or first-discovery policy was bypassed");

                var hunter = (net.minecraft.server.level.ServerPlayer) helper.makeMockServerPlayer(
                        net.minecraft.world.level.GameType.SURVIVAL);
                var target = EntityTypes.COW.create(nether, EntitySpawnReason.COMMAND);
                helper.assertTrue(target != null, "Combat target could not be created");
                target.setPos(12_350.5D, 64, 12_350.5D);
                target.setHealth(2F);
                nether.addFreshEntity(target);
                helper.assertTrue(target.hurtServer(
                                nether, nether.damageSources().playerAttack(hunter), 4F),
                        "Server combat fixture did not apply damage");
                helper.assertTrue(state.state(hunter.getUUID()).activityXp()
                                .getOrDefault(id("combat"), 0L) == 1,
                        "Applied server damage did not award combat XP");
                helper.assertTrue(state.state(hunter.getUUID()).activityXp()
                                .getOrDefault(id("hunting"), 0L) == 1,
                        "Recorded damage contribution did not award hunting XP on death");

                var canceledTarget = EntityTypes.COW.create(nether, EntitySpawnReason.COMMAND);
                helper.assertTrue(canceledTarget != null, "Canceled-death target could not be created");
                canceledTarget.setPos(12_352.5D, 64, 12_350.5D);
                canceledTarget.setHealth(2F);
                nether.addFreshEntity(canceledTarget);
                var deathCancellation = new DeathCancellationFixture(canceledTarget.getUUID());
                NeoForge.EVENT_BUS.register(deathCancellation);
                try {
                    helper.assertTrue(canceledTarget.hurtServer(
                                    nether, nether.damageSources().playerAttack(hunter), 4F),
                            "Canceled-death fixture did not enter the server damage pipeline");
                } finally {
                    NeoForge.EVENT_BUS.unregister(deathCancellation);
                    canceledTarget.discard();
                }
                helper.assertTrue(state.state(hunter.getUUID()).activityXp()
                                .getOrDefault(id("hunting"), 0L) == 1,
                        "Canceled death awarded hunting XP");

                var projectileHunter = (net.minecraft.server.level.ServerPlayer) helper.makeMockServerPlayer(
                        net.minecraft.world.level.GameType.SURVIVAL);
                var projectileTarget = EntityTypes.COW.create(nether, EntitySpawnReason.COMMAND);
                helper.assertTrue(projectileTarget != null, "Projectile combat target could not be created");
                projectileTarget.setPos(12_354.5D, 64, 12_350.5D);
                nether.addFreshEntity(projectileTarget);
                var combatArrow = new Arrow(nether, projectileHunter, new ItemStack(Items.ARROW), null);
                helper.assertTrue(projectileTarget.hurtServer(
                                nether, nether.damageSources().arrow(combatArrow, projectileHunter), 2F),
                        "Server projectile damage fixture did not apply damage");
                helper.assertTrue(state.state(projectileHunter.getUUID()).activityXp()
                                .getOrDefault(id("combat"), 0L) == 1,
                        "Player-owned projectile damage did not award combat XP");

                BlockPos pistonBase = miningPosition.south(8);
                BlockPos pistonOre = pistonBase.east();
                BlockPos pistonDestination = pistonOre.east();
                nether.setBlock(pistonBase,
                        Blocks.PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING, Direction.EAST), 3);
                nether.setBlock(pistonOre, Blocks.DIAMOND_ORE.defaultBlockState(), 3);
                helper.assertTrue(worldState.markSynthetic(nether.dimension(), pistonOre),
                        "Piston source marker was not recorded");
                var activityPiston = new PistonEvent.Pre(
                        nether, pistonBase, Direction.EAST, PistonEvent.PistonMoveType.EXTEND);
                NeoForge.EVENT_BUS.post(activityPiston);
                helper.assertTrue(!activityPiston.isCanceled(), "Ordinary activity piston move was canceled");
                helper.assertTrue(worldState.consumeSynthetic(nether.dimension(), pistonDestination),
                        "Piston event did not propagate the synthetic-resource marker");
                worldState.consumeSynthetic(nether.dimension(), pistonOre);

                var farmer = (net.minecraft.server.level.ServerPlayer) helper.makeMockServerPlayer(
                        net.minecraft.world.level.GameType.SURVIVAL);
                BlockPos farmPosition = miningPosition.south(4);
                NeoForge.EVENT_BUS.post(new BlockDropsEvent(
                        nether, farmPosition, Blocks.WHEAT.defaultBlockState()
                                .setValue(BlockStateProperties.AGE_7, 7), null,
                        new ArrayList<>(), farmer, ItemStack.EMPTY));
                NeoForge.EVENT_BUS.post(new BlockDropsEvent(
                        nether, farmPosition.east(), Blocks.WHEAT.defaultBlockState(), null,
                        new ArrayList<>(), farmer, ItemStack.EMPTY));
                helper.assertTrue(state.state(farmer.getUUID()).activityXp()
                                .getOrDefault(id("farming"), 0L) == 1,
                        "Mature and immature crop events violated farming XP policy");
                var canceledHarvest = new BlockDropsEvent(
                        nether, farmPosition.west(), Blocks.WHEAT.defaultBlockState()
                                .setValue(BlockStateProperties.AGE_7, 7), null,
                        new ArrayList<>(), farmer, ItemStack.EMPTY);
                canceledHarvest.setCanceled(true);
                NeoForge.EVENT_BUS.post(canceledHarvest);
                helper.assertTrue(state.state(farmer.getUUID()).activityXp()
                                .getOrDefault(id("farming"), 0L) == 1,
                        "Canceled harvest event awarded farming XP");

                var parentA = EntityTypes.COW.create(nether, EntitySpawnReason.COMMAND);
                var parentB = EntityTypes.COW.create(nether, EntitySpawnReason.COMMAND);
                var child = EntityTypes.COW.create(nether, EntitySpawnReason.BREEDING);
                helper.assertTrue(parentA != null && parentB != null && child != null,
                        "Breeding fixtures could not be created");
                parentA.setPos(12_360.5D, 64, 12_360.5D);
                parentB.setPos(12_361.5D, 64, 12_360.5D);
                child.setPos(12_360.5D, 64, 12_361.5D);
                parentA.setInLove(farmer);
                NeoForge.EVENT_BUS.post(new BabyEntitySpawnEvent(parentA, parentB, child));
                helper.assertTrue(state.state(farmer.getUUID()).activityXp()
                                .getOrDefault(id("farming"), 0L) == 2,
                        "Validated breeding completion did not award farming XP");
                var roundTrip = RpgPlayerSavedData.CODEC.parse(NbtOps.INSTANCE,
                        RpgPlayerSavedData.CODEC.encodeStart(NbtOps.INSTANCE, state).getOrThrow()).getOrThrow();
                helper.assertTrue(roundTrip.state(player).activityXp().get(id("combat")) == 1,
                        "Activity XP did not survive codec round-trip");
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
                BlockSnapshot ownerPlacementSnapshot = BlockSnapshot.create(
                        level.dimension(), level, ownedPosition);
                level.setBlock(ownedPosition, Blocks.STONE.defaultBlockState(), 3);
                var ownerPlacement = new BlockEvent.EntityPlaceEvent(
                        ownerPlacementSnapshot, Blocks.DIRT.defaultBlockState(), owner);
                NeoForge.EVENT_BUS.post(ownerPlacement);
                helper.assertTrue(!ownerPlacement.isCanceled(), "Owner placement event was canceled");
                helper.assertTrue(RpgPlayerSavedData.get(server).state(owner.getUUID()).activityXp()
                                .getOrDefault(id("building"), 0L) == 1,
                        "Validated claim placement did not award building XP");

                BlockPos visitorPlacementPosition = ownedPosition.east();
                BlockSnapshot visitorPlacementSnapshot = BlockSnapshot.create(
                        level.dimension(), level, visitorPlacementPosition);
                level.setBlock(visitorPlacementPosition, Blocks.STONE.defaultBlockState(), 3);
                var visitorPlacement = new BlockEvent.EntityPlaceEvent(
                        visitorPlacementSnapshot, Blocks.DIRT.defaultBlockState(), visitor);
                NeoForge.EVENT_BUS.post(visitorPlacement);
                helper.assertTrue(visitorPlacement.isCanceled(), "Visitor placement event was allowed");
                helper.assertTrue(RpgPlayerSavedData.get(server).state(visitor.getUUID()).activityXp()
                                .getOrDefault(id("building"), 0L) == 0,
                        "Denied claim placement awarded building XP");
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

    private static ServerboundContainerClickPacket playerMenuClick(
            net.minecraft.world.inventory.AbstractContainerMenu menu,
            int slot,
            int stateId,
            ContainerInput input) {
        return new ServerboundContainerClickPacket(
                menu.containerId,
                stateId,
                (short) slot,
                (byte) 0,
                input,
                new Int2ObjectOpenHashMap<>(),
                HashedStack.EMPTY);
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

    private record DeathCancellationFixture(UUID targetId) {
        @SubscribeEvent(priority = EventPriority.HIGHEST)
        public void cancelDeath(LivingDeathEvent event) {
            if (event.getEntity().getUUID().equals(targetId)) {
                event.setCanceled(true);
            }
        }
    }
}
