package org.dldyou.rovenfall;

import com.mojang.authlib.GameProfile;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
import net.minecraft.world.level.Level;
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
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.UseItemOnBlockEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.BlockDropsEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.level.PistonEvent;
import net.neoforged.neoforge.event.level.block.BreakBlockEvent;
import org.dldyou.rovenfall.administration.EconomyConfig;
import org.dldyou.rovenfall.administration.AdminBridgeConfig;
import org.dldyou.rovenfall.administration.AdminHttpServer;
import org.dldyou.rovenfall.administration.EconomyService;
import org.dldyou.rovenfall.administration.EconomyReversalService;
import org.dldyou.rovenfall.administration.EconomyTransactionReceipt;
import org.dldyou.rovenfall.administration.AdministrationService;
import org.dldyou.rovenfall.administration.AdministrationControlCenterMenu;
import org.dldyou.rovenfall.administration.AdministrationEconomyMenu;
import org.dldyou.rovenfall.administration.AdministrationOperationsMenu;
import org.dldyou.rovenfall.administration.AdministrationReadViewService;
import org.dldyou.rovenfall.administration.AdministrationRpgBossMenu;
import org.dldyou.rovenfall.administration.AdministrationWorldMenu;
import org.dldyou.rovenfall.administration.AdminRole;
import org.dldyou.rovenfall.administration.AuditQuery;
import org.dldyou.rovenfall.administration.BossRewardService;
import org.dldyou.rovenfall.administration.BossAdministrationService;
import org.dldyou.rovenfall.administration.PlatformSavedData;
import org.dldyou.rovenfall.administration.OperationsMetricsService;
import org.dldyou.rovenfall.administration.PlayerRecordService;
import org.dldyou.rovenfall.administration.PlayerMenuNetwork;
import org.dldyou.rovenfall.administration.PlayerClaimMenu;
import org.dldyou.rovenfall.administration.PlayerDashboardMenu;
import org.dldyou.rovenfall.administration.PlayerPortalMenu;
import org.dldyou.rovenfall.administration.PlayerQuestMenu;
import org.dldyou.rovenfall.administration.PlayerRpgMenu;
import org.dldyou.rovenfall.administration.PlayerShopMenu;
import org.dldyou.rovenfall.administration.RovenfallInventoryClient;
import org.dldyou.rovenfall.administration.RovenfallAdministrationMenus;
import org.dldyou.rovenfall.administration.RpgAdministrationService;
import org.dldyou.rovenfall.administration.RovenfallCommands;
import org.dldyou.rovenfall.administration.ShopInstanceService;
import org.dldyou.rovenfall.administration.ShopTradeService;
import org.dldyou.rovenfall.administration.ClaimManagementService;
import org.dldyou.rovenfall.administration.ClaimPurchaseService;
import org.dldyou.rovenfall.administration.ClaimProtectionEvents;
import org.dldyou.rovenfall.administration.ClaimProtectionHooks;
import org.dldyou.rovenfall.administration.ClaimProtectionService;
import org.dldyou.rovenfall.administration.ProtectedRegionService;
import org.dldyou.rovenfall.administration.PortalEvents;
import org.dldyou.rovenfall.administration.PortalService;
import org.dldyou.rovenfall.administration.ManagedPortalService;
import org.dldyou.rovenfall.administration.PortalTravelService;
import org.dldyou.rovenfall.administration.RestartWildernessResetService;
import org.dldyou.rovenfall.administration.WildernessResetEvents;
import org.dldyou.rovenfall.administration.WildernessResetService;
import org.dldyou.rovenfall.administration.CareerPromotionService;
import org.dldyou.rovenfall.administration.CareerSkillService;
import org.dldyou.rovenfall.administration.ActiveSkillService;
import org.dldyou.rovenfall.administration.WorldCombatEvents;
import org.dldyou.rovenfall.administration.WorldCombatService;
import org.dldyou.rovenfall.administration.WorldTravelService;
import org.dldyou.rovenfall.administration.ActivityProgressionService;
import org.dldyou.rovenfall.administration.ActivityChallengeService;
import org.dldyou.rovenfall.administration.DailyContractService;
import org.dldyou.rovenfall.administration.WeeklyExpeditionService;
import org.dldyou.rovenfall.administration.BossEncounterService;
import org.dldyou.rovenfall.activities.ActivityChallengeReloadListener;
import org.dldyou.rovenfall.activities.DailyContractReloadListener;
import org.dldyou.rovenfall.activities.WeeklyExpeditionReloadListener;
import org.dldyou.rovenfall.activities.ActivityEvents;
import org.dldyou.rovenfall.activities.ActivityKind;
import org.dldyou.rovenfall.activities.ActivityLevelReloadListener;
import org.dldyou.rovenfall.activities.ActivityObservation;
import org.dldyou.rovenfall.activities.ActivityProvenance;
import org.dldyou.rovenfall.activities.ActivityRewardReloadListener;
import org.dldyou.rovenfall.activities.ActivityTrack;
import org.dldyou.rovenfall.careers.CareerDefinitionReloadListener;
import org.dldyou.rovenfall.client.ActiveSkillClient;
import org.dldyou.rovenfall.client.MobClient;
import org.dldyou.rovenfall.claims.ClaimConfig;
import org.dldyou.rovenfall.claims.ClaimKey;
import org.dldyou.rovenfall.claims.ClaimRole;
import org.dldyou.rovenfall.claims.ClaimSettings;
import org.dldyou.rovenfall.definition.TestDefinitionReloadListener;
import org.dldyou.rovenfall.economy.ShopTemplateReloadListener;
import org.dldyou.rovenfall.economy.ShopInstance;
import org.dldyou.rovenfall.exploration.ExplorationDefinitionReloadListener;
import org.dldyou.rovenfall.exploration.ExplorationDefinition;
import org.dldyou.rovenfall.exploration.ExplorationDefinitionSnapshot;
import org.dldyou.rovenfall.exploration.ExplorationDiscoveryService;
import org.dldyou.rovenfall.exploration.ExplorationPlayerSavedData;
import org.dldyou.rovenfall.exploration.ExplorationPlayerState;
import org.dldyou.rovenfall.exploration.ExplorationRuntime;
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
import org.dldyou.rovenfall.rpg.RpgDefinitionReloadListener;
import org.dldyou.rovenfall.rpg.RpgItemPaymentGameTestScenario;
import org.dldyou.rovenfall.quest.QuestDefinitionReloadListener;
import org.dldyou.rovenfall.quest.ActiveJourneyTrackerClient;
import org.dldyou.rovenfall.quest.ActiveJourneyTrackerNetwork;
import org.dldyou.rovenfall.quest.QuestPlayerSavedData;
import org.dldyou.rovenfall.quest.QuestPlayerState;
import org.dldyou.rovenfall.quest.QuestProgressRuntime;
import org.dldyou.rovenfall.quest.QuestProgressService;
import org.dldyou.rovenfall.world.ProtectedRegion;
import org.dldyou.rovenfall.world.PortalDefinition;
import org.dldyou.rovenfall.world.WorldTopology;
import org.dldyou.rovenfall.worlds.WorldConfig;
import org.dldyou.rovenfall.worlds.SafeArrivalResolver;
import org.dldyou.rovenfall.worlds.Portal;
import org.dldyou.rovenfall.network.ActiveSkillNetworking;
import org.dldyou.rovenfall.mobs.MobSpawnPolicy;
import org.dldyou.rovenfall.mobs.MobMutationEvents;
import org.dldyou.rovenfall.mobs.MobMutationReloadListener;
import org.dldyou.rovenfall.mobs.BossEvents;
import org.dldyou.rovenfall.mobs.MobMutationApplicator;
import org.dldyou.rovenfall.mobs.RovenfallEntityTypes;
import org.dldyou.rovenfall.items.RovenfallItems;

@Mod(Rovenfall.MOD_ID)
public final class Rovenfall {
    public static final String MOD_ID = "rovenfall";
    private final ShopTemplateReloadListener shopTemplates = new ShopTemplateReloadListener();
    private final MobContentReloadListener mobContent = new MobContentReloadListener();
    private final RpgDefinitionReloadListener rpgDefinitions = new RpgDefinitionReloadListener();
    private final QuestDefinitionReloadListener questDefinitions = new QuestDefinitionReloadListener();
    private final ExplorationDefinitionReloadListener explorationDefinitions =
            new ExplorationDefinitionReloadListener();
    private final ActivityRewardReloadListener activityRewards = new ActivityRewardReloadListener();
    private final ActivityLevelReloadListener activityLevels = new ActivityLevelReloadListener();
    private final ActivityChallengeReloadListener activityChallenges = new ActivityChallengeReloadListener();
    private final DailyContractReloadListener dailyContracts = new DailyContractReloadListener();
    private final WeeklyExpeditionReloadListener weeklyExpeditions = new WeeklyExpeditionReloadListener();
    private final CareerDefinitionReloadListener careerDefinitions = new CareerDefinitionReloadListener();
    private final MobMutationReloadListener mobMutations = new MobMutationReloadListener();

    public Rovenfall(IEventBus modBus, ModContainer modContainer) {
        RovenfallMobEntities.register(modBus);
        RovenfallAdministrationMenus.register(modBus);
        RovenfallItems.register(modBus);
        RovenfallEntityTypes.register(modBus);
        modContainer.registerConfig(ModConfig.Type.SERVER, EconomyConfig.SPEC);
        modContainer.registerConfig(ModConfig.Type.SERVER, ActivityXpConfig.SPEC, "rovenfall-rpg-server.toml");
        modContainer.registerConfig(
                ModConfig.Type.SERVER, AdminBridgeConfig.SPEC, "rovenfall-admin-server.toml");
        modContainer.registerConfig(ModConfig.Type.SERVER, ClaimConfig.SPEC, "rovenfall-claims-server.toml");
        modContainer.registerConfig(ModConfig.Type.SERVER, WorldConfig.SPEC, "rovenfall-worlds-server.toml");
        modBus.addListener(this::registerGameTests);
        modBus.addListener(RpgSkillNetwork::registerPayloads);
        modBus.addListener(PlayerMenuNetwork::registerPayloads);
        modBus.addListener(ActiveJourneyTrackerNetwork::registerPayloads);
        modBus.addListener(ActiveSkillNetworking::registerPayloads);
        if (FMLEnvironment.getDist() == Dist.CLIENT) {
            RpgSkillClient.register(modBus);
            RovenfallMobClient.register(modBus);
            RovenfallInventoryClient.register(modBus);
            ActiveJourneyTrackerClient.register(modBus);
            ActiveSkillClient.register(modBus);
            MobClient.register(modBus);
        }
        NeoForge.EVENT_BUS.addListener(RovenfallCommands::register);
        NeoForge.EVENT_BUS.addListener(EconomyService::onPlayerLoggedIn);
        NeoForge.EVENT_BUS.addListener(RpgSkillResetCoordinator::onPlayerLoggedIn);
        NeoForge.EVENT_BUS.addListener(PlayerCareerPromotionService::onPlayerLoggedIn);
        NeoForge.EVENT_BUS.addListener(RpgAdministrationService::onPlayerLoggedIn);
        NeoForge.EVENT_BUS.addListener(RpgSkillNetwork::onPlayerLoggedIn);
        NeoForge.EVENT_BUS.addListener(RpgSkillNetwork::onPlayerLoggedOut);
        NeoForge.EVENT_BUS.addListener(PlayerMenuNetwork::onPlayerLoggedOut);
        NeoForge.EVENT_BUS.addListener(ActiveJourneyTrackerNetwork::onPlayerLoggedIn);
        NeoForge.EVENT_BUS.addListener(ActiveJourneyTrackerNetwork::onPlayerLoggedOut);
        NeoForge.EVENT_BUS.addListener(ActiveJourneyTrackerNetwork::onServerTick);
        NeoForge.EVENT_BUS.addListener(ActiveJourneyTrackerNetwork::onDatapackSync);
        NeoForge.EVENT_BUS.addListener(PlayerRecordService::onPlayerLoggedIn);
        PortalEvents.register(NeoForge.EVENT_BUS);
        WildernessResetEvents.register(NeoForge.EVENT_BUS);
        ClaimProtectionEvents.register(NeoForge.EVENT_BUS);
        RpgSkillEvents.register(NeoForge.EVENT_BUS);
        RovenfallMobRuntime.register(NeoForge.EVENT_BUS);
        MobMutationRuntime.register(NeoForge.EVENT_BUS);
        BossEncounterRuntime.register(NeoForge.EVENT_BUS);
        QuestProgressRuntime.register(NeoForge.EVENT_BUS);
        ExplorationRuntime.register(NeoForge.EVENT_BUS);
        AdminHttpServer.register(NeoForge.EVENT_BUS);
        WorldCombatEvents.register(NeoForge.EVENT_BUS);
        WorldTravelService.register(NeoForge.EVENT_BUS);
        RestartWildernessResetService.register(NeoForge.EVENT_BUS);
        ActivityEvents.register(NeoForge.EVENT_BUS);
        MobSpawnPolicy.registerGameplayEvents(NeoForge.EVENT_BUS);
        MobMutationEvents.register(NeoForge.EVENT_BUS);
        BossEvents.register(NeoForge.EVENT_BUS);
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
        var landAtlasEnvironment = event.registerEnvironment(
                id("land_atlas"), new TestEnvironmentDefinition.AllOf(List.of()));
        var portalExplorerEnvironment = event.registerEnvironment(
                id("portal_explorer"), new TestEnvironmentDefinition.AllOf(List.of()));
        var questEnvironment = event.registerEnvironment(
                id("quest"), new TestEnvironmentDefinition.AllOf(List.of()));
        var testData = new TestData<>(environment, Identifier.withDefaultNamespace("empty"), 1, 0, true);
        var questTestData = new TestData<>(
                questEnvironment, Identifier.withDefaultNamespace("empty"), 1, 0, true);
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
                var journeyBefore = QuestPlayerSavedData.get(player.level().getServer())
                        .state(player.getUUID());
                player.containerMenu.clicked(17, 0, ContainerInput.PICKUP, player);
                helper.assertTrue(player.containerMenu instanceof PlayerQuestMenu
                                && ((net.minecraft.world.inventory.ChestMenu) player.containerMenu)
                                        .getRowCount() == 6,
                        "Journey card did not open the custom quest board");
                int questState = player.containerMenu.getStateId();
                player.connection.handleContainerClick(playerMenuClick(
                        player.containerMenu, 10, questState, ContainerInput.QUICK_MOVE));
                player.connection.handleContainerClick(playerMenuClick(
                        player.containerMenu, 10, questState - 1, ContainerInput.PICKUP));
                helper.assertTrue(player.containerMenu instanceof PlayerQuestMenu
                                && QuestPlayerSavedData.get(player.level().getServer())
                                        .state(player.getUUID()).equals(journeyBefore),
                        "Rejected quest-board input changed quest state");
                player.containerMenu.clicked(49, 0, ContainerInput.PICKUP, player);
                helper.assertTrue(player.containerMenu instanceof PlayerRpgMenu,
                        "First-journey activity guidance did not deep-link to RPG progress");
                helper.assertTrue(QuestPlayerSavedData.get(player.level().getServer())
                                .state(player.getUUID()).equals(journeyBefore),
                        "Opening quest guidance mutated player quest evidence");
                player.containerMenu.clicked(45, 0, ContainerInput.PICKUP, player);
                helper.assertTrue(player.containerMenu instanceof PlayerDashboardMenu,
                        "Guidance target did not return to the overview");
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
        event.registerTest(id("player_contract_navigation"), new FunctionGameTestInstance(
                BuiltinTestFunctions.ALWAYS_PASS,
                new TestData<>(questEnvironment, Identifier.withDefaultNamespace("empty"), 10, 0, true)) {
            @Override
            public void run(GameTestHelper helper) {
                var player = helper.makeMockServerPlayerInLevel();
                var quests = QuestPlayerSavedData.get(player.level().getServer());
                var before = quests.state(player.getUUID());
                PlayerQuestMenu.open(player);
                helper.assertTrue(player.containerMenu instanceof PlayerQuestMenu
                                && quests.state(player.getUUID()).equals(before),
                        "Opening the story journey mutated repeatable contract state");

                player.containerMenu.clicked(46, 0, ContainerInput.PICKUP, player);
                helper.runAfterDelay(1, () -> {
                    var assigned = quests.state(player.getUUID());
                    long visibleCards = java.util.stream.IntStream.of(20, 22, 24)
                            .filter(slot -> player.containerMenu.getSlot(slot).hasItem())
                            .count();
                    helper.assertTrue(player.containerMenu instanceof PlayerQuestMenu
                                    && assigned.contracts().size() == 3
                                    && assigned.initializedContractWindows().size() == 2
                                    && visibleCards == 3,
                            "Journey requests were not assigned and projected as three bounded custom cards");
                    player.discard();
                    helper.succeed();
                });
            }
        });
        event.registerTest(id("player_active_journey_tracker"), new FunctionGameTestInstance(
                BuiltinTestFunctions.ALWAYS_PASS,
                new TestData<>(questEnvironment, Identifier.withDefaultNamespace("empty"), 20, 0, true)) {
            @Override
            public void run(GameTestHelper helper) {
                var player = helper.makeMockServerPlayerInLevel();
                var quests = QuestPlayerSavedData.get(player.level().getServer());
                PlayerQuestMenu.open(player);
                player.containerMenu.clicked(10, 0, ContainerInput.PICKUP, player);
                helper.assertTrue(player.containerMenu instanceof PlayerQuestMenu,
                        "Journey card did not open its custom detail screen");

                helper.runAfterDelay(1, () -> {
                    player.containerMenu.clicked(49, 0, ContainerInput.PICKUP, player);
                    var tracked = quests.state(player.getUUID()).trackedJourney();
                    helper.assertTrue(tracked.flatMap(QuestPlayerState.TrackedJourney::storyQuestId).isPresent()
                                    && tracked.orElseThrow().contractKey().isEmpty(),
                            "One-click journey selection did not persist one server-owned story tracker");
                    var persisted = QuestPlayerSavedData.CODEC.parse(
                            NbtOps.INSTANCE,
                            QuestPlayerSavedData.CODEC.encodeStart(NbtOps.INSTANCE, quests).getOrThrow())
                            .getOrThrow();
                    helper.assertTrue(persisted.state(player.getUUID()).trackedJourney().equals(tracked),
                            "Active journey selection did not survive persistence");

                    helper.runAfterDelay(1, () -> {
                        player.containerMenu.clicked(49, 0, ContainerInput.PICKUP, player);
                        helper.assertTrue(quests.state(player.getUUID()).trackedJourney().isEmpty(),
                                "Clicking the selected journey again did not clear its tracker");
                        player.discard();
                        helper.succeed();
                    });
                });
            }
        });
        event.registerTest(id("player_exploration_journal"), new FunctionGameTestInstance(
                BuiltinTestFunctions.ALWAYS_PASS,
                new TestData<>(questEnvironment, Identifier.withDefaultNamespace("empty"), 10, 0, true)) {
            @Override
            public void run(GameTestHelper helper) {
                var player = helper.makeMockServerPlayerInLevel();
                var server = player.level().getServer();
                var exploration = ExplorationPlayerSavedData.get(server);
                var rpg = RpgPlayerSavedData.get(server);
                Identifier discoveryId = id("gametest_discovery");
                ExplorationDefinitionSnapshot definitions = ExplorationDefinitionSnapshot.compile(List.of(
                        new ExplorationDefinitionSnapshot.Source(
                                id("gametest/discovery.json"), "gametest", discoveryId,
                                new ExplorationDefinition(
                                        "discovery.rovenfall.hub_arrival",
                                        "discovery.rovenfall.hub_arrival.description",
                                        1, player.level().dimension(), player.blockPosition(), 4,
                                        false, Optional.of(7L)))));
                long beforeXp = rpg.state(player.getUUID()).activityXp()
                        .getOrDefault(ExplorationDiscoveryService.EXPLORATION_ACTIVITY, 0L);
                long now = Math.max(1L, System.currentTimeMillis());
                var observed = ExplorationDiscoveryService.observe(
                        exploration, definitions, rpg, RpgDefinitionReloadListener.snapshot(server),
                        player.getUUID(), player.level().dimension(), player.blockPosition(), now, now);
                ExplorationPlayerState after = exploration.state(player.getUUID());
                helper.assertTrue(observed.status() == ExplorationDiscoveryService.Status.SUCCESS
                                && observed.discovered() == 1 && observed.rewardsApplied() == 1
                                && after.discovery(discoveryId).flatMap(
                                        ExplorationPlayerState.DiscoveryReceipt::rewardOperation)
                                        .filter(operation -> operation.phase()
                                                == ExplorationPlayerState.RewardOperation.Phase.APPLIED)
                                        .isPresent()
                                && rpg.state(player.getUUID()).activityXp()
                                        .getOrDefault(ExplorationDiscoveryService.EXPLORATION_ACTIVITY, 0L)
                                        == beforeXp + 7L,
                        "Server-observed exploration did not persist one versioned receipt and reward");

                var duplicate = ExplorationDiscoveryService.observe(
                        exploration, definitions, rpg, RpgDefinitionReloadListener.snapshot(server),
                        player.getUUID(), player.level().dimension(), player.blockPosition(), now + 1L, now + 1L);
                helper.assertTrue(duplicate.status() == ExplorationDiscoveryService.Status.NO_CHANGE
                                && exploration.state(player.getUUID()).equals(after)
                                && rpg.state(player.getUUID()).activityXp()
                                        .getOrDefault(ExplorationDiscoveryService.EXPLORATION_ACTIVITY, 0L)
                                        == beforeXp + 7L,
                        "Duplicate exploration entry changed the receipt or awarded XP twice");

                PlayerQuestMenu.open(player);
                player.containerMenu.clicked(47, 0, ContainerInput.PICKUP, player);
                helper.runAfterDelay(1, () -> {
                    helper.assertTrue(player.containerMenu instanceof PlayerQuestMenu
                                    && player.containerMenu.getSlot(10).hasItem()
                                    && exploration.state(player.getUUID()).equals(after),
                            "Journey exploration journal did not open as a read-only custom card page");
                    player.discard();
                    helper.succeed();
                });
            }
        });
        event.registerTest(id("player_land_atlas_navigation"), new FunctionGameTestInstance(
                BuiltinTestFunctions.ALWAYS_PASS,
                new TestData<>(landAtlasEnvironment, Identifier.withDefaultNamespace("empty"), 10, 0, true)) {
            @Override
            public void run(GameTestHelper helper) {
                var player = helper.makeMockServerPlayerInLevel();
                var platform = PlatformSavedData.get(player.level().getServer());
                int claimsBefore = platform.claimCount();
                int auditsBefore = platform.auditCount();
                PlayerClaimMenu.open(player);
                helper.assertTrue(player.containerMenu instanceof PlayerClaimMenu,
                        "Land atlas did not open from the player inventory flow");
                player.containerMenu.clicked(16, 0, ContainerInput.PICKUP, player);
                helper.assertTrue(player.containerMenu instanceof PlayerClaimMenu,
                        "Available-land atlas did not remain in the custom land menu");
                int navigationSlot = java.util.stream.IntStream.range(9, 45)
                        .filter(slot -> player.containerMenu.getSlot(slot).hasItem())
                        .skip(1)
                        .findFirst()
                        .orElse(-1);
                helper.assertTrue(navigationSlot >= 0,
                        "Available-land atlas did not provide a bounded navigation target");
                helper.runAfterDelay(1, () -> {
                    player.containerMenu.clicked(navigationSlot, 0, ContainerInput.PICKUP, player);
                    helper.assertTrue(player.containerMenu == player.inventoryMenu,
                            "Starting native land navigation did not close the atlas");
                    helper.assertTrue(platform.claimCount() == claimsBefore && platform.auditCount() == auditsBefore,
                            "Read-only atlas navigation mutated land or audit state");
                    player.discard();
                    helper.succeed();
                });
            }
        });
        event.registerTest(id("player_portal_explorer_travel"), new FunctionGameTestInstance(
                BuiltinTestFunctions.ALWAYS_PASS,
                new TestData<>(portalExplorerEnvironment, Identifier.withDefaultNamespace("empty"), 20, 0, true)) {
            @Override
            public void run(GameTestHelper helper) {
                var level = helper.getLevel();
                var platform = PlatformSavedData.get(level.getServer());
                var player = helper.makeMockServerPlayerInLevel();
                BlockPos origin = helper.absolutePos(new BlockPos(1, 2, 1));
                BlockPos destination = helper.absolutePos(new BlockPos(5, 2, 5));
                level.setBlock(destination.below(), Blocks.STONE.defaultBlockState(), 3);
                level.setBlock(destination, Blocks.AIR.defaultBlockState(), 3);
                level.setBlock(destination.above(), Blocks.AIR.defaultBlockState(), 3);
                player.setPos(origin.getX() + 0.5D, origin.getY(), origin.getZ() + 0.5D);

                Identifier portalId = id("gametest_explorer_" + UUID.randomUUID());
                long timestamp = System.currentTimeMillis();
                PortalDefinition definition = new PortalDefinition(
                        AdministrationService.SYSTEM_ACTOR,
                        new PortalDefinition.Endpoint(level.dimension(), origin),
                        new PortalDefinition.Endpoint(level.dimension(), destination),
                        0,
                        5_000L,
                        PortalDefinition.SafeArrivalPolicy.EXACT,
                        true);
                helper.assertTrue(PortalService.create(
                                platform,
                                AdministrationService.SYSTEM_ACTOR,
                                true,
                                portalId,
                                definition,
                                endpoint -> level.dimension().equals(endpoint.dimension())
                                        && level.isInWorldBounds(endpoint.position()),
                                "gametest portal explorer",
                                timestamp,
                                UUID.randomUUID()).status() == PortalService.Status.SUCCESS,
                        "Portal explorer GameTest setup failed");
                long auditUntil = Math.addExact(timestamp, 60_000L);
                AuditQuery portalAuditQuery = new AuditQuery(
                        timestamp,
                        auditUntil,
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(portalId.toString()),
                        Optional.empty());
                int portalAuditsBeforeNavigation = platform.auditPage(portalAuditQuery, 0, 1).totalEntries();
                long cooldownBeforeNavigation = platform.portalCooldownUntil(player.getUUID(), portalId);
                Runnable cleanup = () -> {
                    if (platform.portalDefinition(portalId).isPresent()) {
                        PortalService.delete(
                                platform,
                                AdministrationService.SYSTEM_ACTOR,
                                true,
                                portalId,
                                "gametest portal explorer cleanup",
                                System.currentTimeMillis(),
                                UUID.randomUUID());
                    }
                    player.discard();
                };

                try {
                    PlayerDashboardMenu.open(player);
                    player.containerMenu.clicked(14, 0, ContainerInput.PICKUP, player);
                    helper.assertTrue(player.containerMenu instanceof PlayerPortalMenu,
                            "Portal card did not open the custom portal explorer");
                    player.containerMenu.clicked(9, 0, ContainerInput.PICKUP, player);
                    helper.runAfterDelay(1, () -> {
                        try {
                            player.containerMenu.clicked(49, 0, ContainerInput.PICKUP, player);
                            helper.assertTrue(player.containerMenu == player.inventoryMenu,
                                    "Starting native portal navigation did not close the explorer");
                            helper.assertTrue(platform.portalDefinition(portalId).equals(Optional.of(definition))
                                            && platform.auditPage(portalAuditQuery, 0, 1).totalEntries()
                                            == portalAuditsBeforeNavigation
                                            && platform.portalCooldownUntil(player.getUUID(), portalId)
                                            == cooldownBeforeNavigation,
                                    "Read-only portal navigation mutated its definition, cooldown, or audit evidence");

                            PlayerPortalMenu.open(player);
                            player.containerMenu.clicked(9, 0, ContainerInput.PICKUP, player);
                            helper.runAfterDelay(1, () -> {
                                try {
                                    player.containerMenu.clicked(50, 0, ContainerInput.PICKUP, player);
                                    helper.assertTrue(player.containerMenu == player.inventoryMenu,
                                            "Successful portal travel did not close the explorer");
                                    helper.assertTrue(player.blockPosition().equals(destination),
                                            "Portal explorer travel did not use the server-resolved safe arrival");
                                    helper.assertTrue(
                                            platform.portalCooldownUntil(player.getUUID(), portalId) > timestamp,
                                            "Portal explorer travel did not persist cooldown receipt evidence");
                                    AuditQuery travelAuditQuery = new AuditQuery(
                                            timestamp,
                                            auditUntil,
                                            Optional.of(player.getUUID()),
                                            Optional.of(id("portal_travel")),
                                            Optional.of(portalId.toString()),
                                            Optional.empty());
                                    var travelAudits = platform.auditPage(travelAuditQuery, 0, 2);
                                    helper.assertTrue(travelAudits.totalEntries() == 1,
                                            "Portal explorer travel did not append exactly one matching audit entry");
                                    var travelAudit = travelAudits.entries().getFirst();
                                    helper.assertTrue(travelAudit.target().equals(portalId.toString())
                                                    && travelAudit.position().equals(Optional.of(destination)),
                                            "Portal explorer travel audit did not preserve its target and arrival");
                                } finally {
                                    cleanup.run();
                                }
                                helper.assertTrue(platform.portalDefinition(portalId).isEmpty(),
                                        "Portal explorer GameTest cleanup failed");
                                helper.succeed();
                            });
                        } catch (RuntimeException | Error exception) {
                            cleanup.run();
                            throw exception;
                        }
                    });
                } catch (RuntimeException | Error exception) {
                    cleanup.run();
                    throw exception;
                }
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
                helper.assertTrue(player.containerMenu.getType() == RovenfallAdministrationMenus.HOME.get(),
                        "Administration home still used the vanilla chest menu type");
                helper.assertTrue(AdministrationEconomyMenu.open(
                                player, AdministrationReadViewService.Domain.PLAYERS)
                                && player.containerMenu.getType() == RovenfallAdministrationMenus.ECONOMY.get(),
                        "Economy administration did not use its custom menu type");
                helper.assertTrue(AdministrationWorldMenu.open(
                                player, AdministrationReadViewService.Domain.CLAIMS)
                                && player.containerMenu.getType() == RovenfallAdministrationMenus.WORLD.get(),
                        "World administration did not use its custom menu type");
                helper.assertTrue(AdministrationRpgBossMenu.open(
                                player, AdministrationReadViewService.Domain.RPG)
                                && player.containerMenu.getType() == RovenfallAdministrationMenus.RPG_BOSS.get(),
                        "RPG administration did not use its custom menu type");
                helper.assertTrue(AdministrationOperationsMenu.open(
                                player, AdministrationReadViewService.Domain.AUDIT)
                                && player.containerMenu.getType() == RovenfallAdministrationMenus.OPERATIONS.get(),
                        "Operations administration did not use its custom menu type");
                helper.assertTrue(AdministrationControlCenterMenu.open(player),
                        "Could not return to the administration control center");
                player.containerMenu.clicked(10, 0, ContainerInput.PICKUP, player);
                helper.assertTrue(player.containerMenu instanceof AdministrationWorldMenu,
                        "Owner could not open the typed claims administration view");
                helper.assertTrue(PlayerMenuNetwork.isPlayerMenu(player.containerMenu),
                        "World administration view was not protected by player-menu session validation");

                helper.assertTrue(AdministrationService.changeRole(
                                platform, AdministrationService.SYSTEM_ACTOR, true, player.getUUID(),
                                AdminRole.ECONOMY_MANAGER.getSerializedName(), "gametest cross-domain demotion",
                                System.currentTimeMillis(), UUID.randomUUID()).status()
                                == AdministrationService.RoleChangeStatus.SUCCESS,
                        "Could not change the GameTest role by UUID");
                helper.runAfterDelay(1, () -> {
                    if (player.containerMenu instanceof AdministrationWorldMenu) {
                        player.containerMenu.clicked(53, 0, ContainerInput.PICKUP, player);
                    }
                    helper.assertTrue(player.containerMenu == player.inventoryMenu,
                            "A role change did not invalidate the now-forbidden open claims view");
                    player.discard();
                    helper.succeed();
                });
            }
        });
        event.registerTest(id("admin_rpg_gui_xp_and_role_revalidation"), new FunctionGameTestInstance(
                BuiltinTestFunctions.ALWAYS_PASS,
                new TestData<>(environment, Identifier.withDefaultNamespace("empty"), 40, 0, true)) {
            @Override
            public void run(GameTestHelper helper) {
                var server = helper.getLevel().getServer();
                var player = helper.makeMockServerPlayerInLevel();
                var platform = PlatformSavedData.get(server);
                var rpg = RpgPlayerSavedData.get(server);
                Identifier combat = id("combat");
                helper.assertTrue(AdministrationService.changeRole(
                                platform, AdministrationService.SYSTEM_ACTOR, true, player.getUUID(),
                                AdminRole.MODERATOR.getSerializedName(), "gametest rpg gui",
                                System.currentTimeMillis(), UUID.randomUUID()).status()
                                == AdministrationService.RoleChangeStatus.SUCCESS,
                        "Could not assign the RPG-GUI moderator role");
                helper.assertTrue(ActivityXpAwardService.awardBossReward(
                                rpg, RpgDefinitionReloadListener.snapshot(server), player.getUUID(), combat,
                                10, System.currentTimeMillis(), UUID.randomUUID(), "gametest:rpg_gui_seed").status()
                                == ActivityXpAwardService.Status.SUCCESS,
                        "Could not seed activity XP for the RPG-GUI test");
                helper.assertTrue(AdministrationControlCenterMenu.open(player),
                        "Moderator could not open the administration control center");
                player.containerMenu.clicked(13, 0, ContainerInput.PICKUP, player);
                helper.assertTrue(player.containerMenu instanceof AdministrationRpgBossMenu,
                        "RPG domain did not open the typed RPG administration menu");
                helper.assertTrue(PlayerMenuNetwork.isPlayerMenu(player.containerMenu),
                        "RPG administration menu was not protected by player-menu session validation");
                helper.assertTrue(((AdministrationRpgBossMenu) player.containerMenu)
                                .applyTextInput(player, player.getUUID().toString()),
                        "Could not search the RPG player by exact server UUID");
                player.containerMenu.clicked(9, 0, ContainerInput.PICKUP, player);
                helper.runAfterDelay(1, () -> {
                    player.containerMenu.clicked(46, 0, ContainerInput.PICKUP, player);
                    helper.runAfterDelay(1, () -> {
                        player.containerMenu.clicked(9, 0, ContainerInput.PICKUP, player);
                        helper.assertTrue(((AdministrationRpgBossMenu) player.containerMenu)
                                        .applyTextInput(player, "5 | gametest xp correction"),
                                "XP adjustment form did not produce a server preview");
                        helper.runAfterDelay(1, () -> {
                            player.containerMenu.clicked(31, 0, ContainerInput.PICKUP, player);
                            helper.assertTrue(rpg.state(player.getUUID()).activityXp().getOrDefault(combat, 0L) == 15,
                                    "Confirmed RPG GUI adjustment did not commit through the RPG service");
                            helper.assertTrue(platform.recentAuditEntries(30).stream()
                                            .anyMatch(entry -> entry.actionType().getPath().equals("rpg_admin_xp_adjust")
                                                    && entry.reason().equals("gametest xp correction")),
                                    "Confirmed RPG GUI adjustment did not retain audit evidence");
                            helper.runAfterDelay(1, () -> {
                                player.containerMenu.clicked(31, 0, ContainerInput.PICKUP, player);
                                helper.runAfterDelay(1, () -> {
                                    player.containerMenu.clicked(9, 0, ContainerInput.PICKUP, player);
                                    helper.assertTrue(((AdministrationRpgBossMenu) player.containerMenu)
                                                    .applyTextInput(player, "5 | gametest revoked preview"),
                                            "Second XP adjustment did not reach preview");
                                    helper.assertTrue(AdministrationService.changeRole(
                                                    platform, AdministrationService.SYSTEM_ACTOR, true,
                                                    player.getUUID(), AdminRole.CONTENT_MANAGER.getSerializedName(),
                                                    "gametest rpg mutation revocation", System.currentTimeMillis(),
                                                    UUID.randomUUID()).status()
                                                    == AdministrationService.RoleChangeStatus.SUCCESS,
                                            "Could not revoke XP mutation authority before confirmation");
                                    helper.runAfterDelay(20, () -> {
                                        player.containerMenu.clicked(31, 0, ContainerInput.PICKUP, player);
                                        helper.assertTrue(rpg.state(player.getUUID()).activityXp()
                                                        .getOrDefault(combat, 0L) == 15,
                                                "A stale role preview committed an unauthorized XP adjustment");
                                        helper.assertTrue(platform.recentAuditEntries(30).stream()
                                                        .anyMatch(entry -> entry.actionType().getPath()
                                                                .equals("admin_gui_unauthorized_denied")),
                                                "Rejected stale-role confirmation did not retain denial evidence");
                                        player.discard();
                                        helper.succeed();
                                    });
                                });
                            });
                        });
                    });
                });
            }
        });
        event.registerTest(id("admin_world_gui_reclaim"), new FunctionGameTestInstance(
                BuiltinTestFunctions.ALWAYS_PASS,
                new TestData<>(environment, Identifier.withDefaultNamespace("empty"), 30, 0, true)) {
            @Override
            public void run(GameTestHelper helper) {
                var server = helper.getLevel().getServer();
                var player = helper.makeMockServerPlayerInLevel();
                var platform = PlatformSavedData.get(server);
                int chunk = 500_000 + Math.floorMod(UUID.randomUUID().hashCode(), 100_000);
                ClaimKey key = new ClaimKey(WorldTopology.HUB, chunk, chunk);
                helper.assertTrue(AdministrationService.changeRole(
                                platform, AdministrationService.SYSTEM_ACTOR, true, player.getUUID(),
                                AdminRole.OWNER.getSerializedName(), "gametest world gui",
                                System.currentTimeMillis(), UUID.randomUUID()).status()
                                == AdministrationService.RoleChangeStatus.SUCCESS,
                        "Could not assign the world-GUI owner role");
                helper.assertTrue(EconomyService.award(
                                platform, player.getUUID(), 5_000, "gametest claim funds",
                                System.currentTimeMillis(), UUID.randomUUID(), 0, Long.MAX_VALUE).status()
                                == EconomyService.TransactionStatus.SUCCESS,
                        "Could not seed claim funds");
                helper.assertTrue(ClaimPurchaseService.purchase(
                                platform, player.getUUID(), WorldTopology.HUB, WorldTopology.HUB,
                                key.auditPosition(), ignored -> true, ignored -> false,
                                1_000, 0, 64, System.currentTimeMillis(), UUID.randomUUID()).status()
                                == ClaimPurchaseService.Status.SUCCESS,
                        "Could not prepare the claim for GUI reclaim");
                long balanceBefore = platform.economyBalance(player.getUUID()).orElseThrow();
                helper.assertTrue(AdministrationWorldMenu.open(
                                player, org.dldyou.rovenfall.administration.AdministrationReadViewService.Domain.CLAIMS),
                        "Could not open the claim world-administration view");
                helper.assertTrue(((AdministrationWorldMenu) player.containerMenu)
                                .applyTextInput(player, key.auditTarget()),
                        "Could not search the claim by its server key");
                player.containerMenu.clicked(9, 0, ContainerInput.PICKUP, player);
                helper.runAfterDelay(1, () -> {
                    player.containerMenu.clicked(52, 0, ContainerInput.PICKUP, player);
                    helper.assertTrue(((AdministrationWorldMenu) player.containerMenu)
                                    .applyTextInput(player, " | gametest abandoned claim"),
                            "Reclaim form did not produce an irreversible preview");
                    helper.runAfterDelay(1, () -> {
                        player.containerMenu.clicked(31, 0, ContainerInput.PICKUP, player);
                        helper.assertTrue(platform.claim(key).isEmpty(),
                                "Confirmed GUI reclaim did not remove the claim");
                        helper.assertTrue(platform.economyBalance(player.getUUID()).orElseThrow() == balanceBefore,
                                "Administrative reclaim unexpectedly refunded the claim owner");
                        helper.assertTrue(platform.recentAuditEntries(20).stream()
                                        .anyMatch(entry -> entry.actionType().getPath().equals("claim_reclaim")
                                                && entry.target().equals(key.auditTarget())
                                                && entry.reason().equals("gametest abandoned claim")),
                                "Confirmed GUI reclaim did not retain audit evidence");
                        player.discard();
                        helper.succeed();
                    });
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
                player.containerMenu.clicked(46, 0, ContainerInput.PICKUP, player);
                helper.runAfterDelay(1, () -> {
                    helper.assertTrue(player.containerMenu.getSlot(9).hasItem(),
                            "The one-click shop template selector did not render a template");
                    player.containerMenu.clicked(9, 0, ContainerInput.PICKUP, player);
                    String structured = org.dldyou.rovenfall.administration.AdministrationStructuredFormCodec.encode(
                                    org.dldyou.rovenfall.administration.AdministrationFormType.ECONOMY_SHOP_CREATE,
                                    List.of("gametest gui create"))
                            .orElseThrow();
                    helper.assertTrue(((AdministrationEconomyMenu) player.containerMenu)
                                    .applyTextInput(player, structured),
                            "The typed shop create form did not produce a server preview");
                    helper.runAfterDelay(1, () -> {
                        player.containerMenu.clicked(31, 0, ContainerInput.PICKUP, player);
                        var createAudit = platform.recentAuditEntries(20).stream()
                                .filter(entry -> entry.actionType().getPath().equals("shop_instance_create"))
                                .filter(entry -> entry.reason().equals("gametest gui create"))
                                .findFirst().orElseThrow();
                        String expectedShopId = "rovenfall:managed/shop/"
                                + createAudit.transactionId().toString().replace("-", "");
                        helper.assertTrue(createAudit.target().equals(expectedShopId),
                                "The server-generated shop ID did not derive from the action transaction");
                        Identifier guiShopId = Identifier.parse(createAudit.target());
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
        event.registerTest(id("admin_operations_gui_snapshot_and_role_revalidation"),
                new FunctionGameTestInstance(
                        BuiltinTestFunctions.ALWAYS_PASS,
                        new TestData<>(environment, Identifier.withDefaultNamespace("empty"), 100, 0, true)) {
                    @Override
                    public void run(GameTestHelper helper) {
                        var server = helper.getLevel().getServer();
                        var player = helper.makeMockServerPlayerInLevel();
                        var deniedPlayer = helper.makeMockServerPlayerInLevel();
                        var platform = PlatformSavedData.get(server);
                        long startedAt = System.currentTimeMillis();
                        helper.assertTrue(AdministrationService.changeRole(
                                        platform, AdministrationService.SYSTEM_ACTOR, true, player.getUUID(),
                                        AdminRole.OWNER.getSerializedName(), "gametest operations gui",
                                        startedAt, UUID.randomUUID()).status()
                                        == AdministrationService.RoleChangeStatus.SUCCESS,
                                "Could not assign the operations-GUI owner role");
                        helper.assertTrue(AdministrationService.changeRole(
                                        platform, AdministrationService.SYSTEM_ACTOR, true, deniedPlayer.getUUID(),
                                        AdminRole.OWNER.getSerializedName(), "gametest operations role guard",
                                        startedAt, UUID.randomUUID()).status()
                                        == AdministrationService.RoleChangeStatus.SUCCESS,
                                "Could not assign the export-guard owner role");
                        helper.assertTrue(AdministrationOperationsMenu.open(
                                        player,
                                        org.dldyou.rovenfall.administration.AdministrationReadViewService.Domain.AUDIT),
                                "Owner could not open the inventory audit view");
                        helper.assertTrue(PlayerMenuNetwork.isPlayerMenu(player.containerMenu),
                                "Operations administration view was not protected by player-menu validation");
                        player.containerMenu.clicked(49, 0, ContainerInput.PICKUP, player);

                        helper.runAfterDelay(1, () -> {
                            player.containerMenu.clicked(46, 0, ContainerInput.PICKUP, player);
                            helper.assertTrue(((AdministrationOperationsMenu) player.containerMenu)
                                            .applyTextInput(player, " | gametest stale snapshot"),
                                    "Snapshot form did not produce a server preview");
                            helper.assertTrue(AdministrationService.changeRole(
                                            platform, AdministrationService.SYSTEM_ACTOR, true, UUID.randomUUID(),
                                            AdminRole.VIEWER.getSerializedName(), "gametest concurrent platform change",
                                            System.currentTimeMillis(), UUID.randomUUID()).status()
                                            == AdministrationService.RoleChangeStatus.SUCCESS,
                                    "Could not create the concurrent platform change");
                            helper.runAfterDelay(1, () -> {
                                player.containerMenu.clicked(31, 0, ContainerInput.PICKUP, player);
                                helper.assertTrue(platform.recentAuditEntries(30).stream()
                                                .anyMatch(entry -> entry.actorId().equals(player.getUUID())
                                                        && entry.actionType().getPath()
                                                                .equals("admin_gui_stale_confirmation_denied")
                                                        && entry.reason().equals("stale_confirmation")),
                                        "Stale snapshot confirmation was not rejected with audit evidence");
                                helper.runAfterDelay(1, () -> {
                                    player.containerMenu.clicked(31, 0, ContainerInput.PICKUP, player);
                                    helper.runAfterDelay(50, () -> {
                                        player.containerMenu.clicked(46, 0, ContainerInput.PICKUP, player);
                                        helper.assertTrue(((AdministrationOperationsMenu) player.containerMenu)
                                                        .applyTextInput(player, " | gametest platform snapshot"),
                                                "Refreshed snapshot form did not produce a preview");
                                        helper.runAfterDelay(1, () -> {
                                            player.containerMenu.clicked(31, 0, ContainerInput.PICKUP, player);
                                            helper.assertTrue(platform.recentAuditEntries(40).stream()
                                                            .anyMatch(entry -> entry.actorId().equals(player.getUUID())
                                                                    && entry.actionType().getPath()
                                                                            .equals("platform_snapshot_create")
                                                                    && entry.reason()
                                                                            .equals("gametest platform snapshot")),
                                                    "Confirmed snapshot did not commit through the snapshot service");
                                            helper.runAfterDelay(1, () -> {
                                                player.containerMenu.clicked(31, 0, ContainerInput.PICKUP, player);
                                                helper.assertTrue(player.containerMenu.getSlot(9).hasItem(),
                                                        "Created snapshot was not listed from audit evidence");
                                                helper.runAfterDelay(1, () -> {
                                                    helper.assertTrue(AdministrationOperationsMenu.open(
                                                                    deniedPlayer,
                                                                    org.dldyou.rovenfall.administration
                                                                            .AdministrationReadViewService.Domain.AUDIT),
                                                            "Second owner could not open the audit view");
                                                    deniedPlayer.containerMenu.clicked(
                                                            46, 0, ContainerInput.PICKUP, deniedPlayer);
                                                    long until = Math.max(1L, System.currentTimeMillis() - 1L);
                                                    long since = Math.max(
                                                            0L, until
                                                                    - org.dldyou.rovenfall.administration.AuditQuery
                                                                            .MAX_WINDOW_MILLIS);
                                                    String exportInput = "since=" + since + " until=" + until
                                                            + " | gametest revoked export";
                                                    helper.assertTrue(((AdministrationOperationsMenu)
                                                                    deniedPlayer.containerMenu)
                                                                    .applyTextInput(deniedPlayer, exportInput),
                                                            "Audit export form did not produce a preview");
                                                    helper.assertTrue(AdministrationService.changeRole(
                                                                    platform,
                                                                    AdministrationService.SYSTEM_ACTOR,
                                                                    true,
                                                                    deniedPlayer.getUUID(),
                                                                    AdminRole.VIEWER.getSerializedName(),
                                                                    "gametest export revocation",
                                                                    System.currentTimeMillis(),
                                                                    UUID.randomUUID()).status()
                                                                    == AdministrationService.RoleChangeStatus.SUCCESS,
                                                            "Could not revoke export authority before confirmation");
                                                    helper.runAfterDelay(1, () -> {
                                                        deniedPlayer.containerMenu.clicked(
                                                                31, 0, ContainerInput.PICKUP, deniedPlayer);
                                                        helper.assertTrue(platform.recentAuditEntries(50).stream()
                                                                        .anyMatch(entry -> entry.actorId()
                                                                                        .equals(deniedPlayer.getUUID())
                                                                                && entry.actionType().getPath()
                                                                                        .equals("audit_export_denied")
                                                                                && entry.reason()
                                                                                        .equals("unauthorized")),
                                                                "Revoked export confirmation lacked denial evidence");
                                                        helper.assertTrue(platform.recentAuditEntries(50).stream()
                                                                        .noneMatch(entry -> entry.actorId()
                                                                                        .equals(deniedPlayer.getUUID())
                                                                                && entry.actionType().getPath()
                                                                                        .equals("audit_export")
                                                                                && entry.reason().equals(
                                                                                        "gametest revoked export")),
                                                                "Revoked owner exported audit data");
                                                        player.discard();
                                                        deniedPlayer.discard();
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
                var bossEvidence = QuestProgressService.evidence(restartTransactionId, completedRestart);
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
                                && bossEvidence.isPresent()
                                && bossEvidence.orElseThrow().kind().getSerializedName().equals("boss_defeat")
                                && bossEvidence.orElseThrow().target().equals(Optional.of(restartBossId))
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
        event.registerTest(id("rpg_item_payment"), new FunctionGameTestInstance(
                BuiltinTestFunctions.ALWAYS_PASS, testData) {
            @Override
            public void run(GameTestHelper helper) {
                var server = helper.getLevel().getServer();
                var definitions = RpgDefinitionReloadListener.snapshot(server);
                var rpg = RpgPlayerSavedData.get(server);
                var platform = PlatformSavedData.get(server);
                var player = FakePlayerFactory.get(
                        helper.getLevel(), new GameProfile(UUID.randomUUID(), "[RpgItemPayment]"));
                long timestamp = System.currentTimeMillis();

                helper.assertTrue(EconomyService.award(
                                platform, player.getUUID(), 2_000, "gametest item payment account", timestamp,
                                UUID.randomUUID(), EconomyConfig.initialBalance(), EconomyConfig.maximumBalance())
                                .status() == EconomyService.TransactionStatus.SUCCESS,
                        "Could not create the RPG item-payment economy fixture");
                helper.assertTrue(CareerProgressionService.promote(
                                rpg, definitions, player.getUUID(), id("novice"), timestamp + 1,
                                UUID.randomUUID(), "gametest:item_payment:novice").status()
                                == CareerProgressionService.Status.SUCCESS,
                        "Could not promote the RPG item-payment fixture to novice");
                for (int index = 0; index < 60; index++) {
                    helper.assertTrue(ActivityXpAwardService.award(
                                    rpg, definitions, player.getUUID(), id("combat"), 10,
                                    timestamp + (index + 1L) * 4_000L, UUID.randomUUID(),
                                    "gametest:item_payment:combat_" + index).status()
                                    == ActivityXpAwardService.Status.SUCCESS,
                            "Could not satisfy the RPG item-payment activity prerequisite");
                }
                helper.assertTrue(RpgSkillService.learn(
                                rpg, definitions, player.getUUID(), id("sturdy_body"),
                                timestamp + 245_000L, UUID.randomUUID(), "gametest:item_payment").status()
                                == RpgSkillService.Status.SUCCESS
                                && RpgSkillService.learn(
                                rpg, definitions, player.getUUID(), id("sturdy_body"),
                                timestamp + 245_001L, UUID.randomUUID(), "gametest:item_payment").status()
                                == RpgSkillService.Status.SUCCESS,
                        "Could not learn the RPG item-payment skill prerequisite");

                long beforeBalance = platform.economyBalance(player.getUUID()).orElseThrow();
                var denied = PlayerCareerPromotionService.promote(
                        player, id("warrior"), timestamp + 250_000L);
                helper.assertTrue(denied.status() == PlayerCareerPromotionService.Status.ITEM_PAYMENT_FAILED
                                && platform.economyBalance(player.getUUID()).orElseThrow() == beforeBalance
                                && !rpg.state(player.getUUID()).careers().containsKey(id("warrior")),
                        "Insufficient items changed currency or RPG progression");

                player.getInventory().add(new ItemStack(Items.IRON_INGOT, 8));
                var promoted = PlayerCareerPromotionService.promote(
                        player, id("warrior"), timestamp + 250_001L);
                long afterBalance = platform.economyBalance(player.getUUID()).orElseThrow();
                int remainingIron = player.getInventory().getNonEquipmentItems().stream()
                        .filter(stack -> stack.is(Items.IRON_INGOT))
                        .mapToInt(ItemStack::getCount).sum();
                helper.assertTrue(promoted.status() == PlayerCareerPromotionService.Status.SUCCESS,
                        "Item-backed promotion failed with status " + promoted.status()
                                + " and payment " + promoted.paymentStatus());
                helper.assertTrue(afterBalance == beforeBalance - 100,
                        "Item-backed promotion balance was " + afterBalance + " after " + beforeBalance);
                helper.assertTrue(remainingIron == 0,
                        "Item-backed promotion left " + remainingIron + " iron ingots");
                helper.assertTrue(rpg.state(player.getUUID()).careers().containsKey(id("warrior")),
                        "Item-backed promotion did not commit warrior progression");

                player.getInventory().add(new ItemStack(Items.IRON_INGOT, 8));
                var replay = PlayerCareerPromotionService.promote(
                        player, id("warrior"), timestamp + 250_002L);
                helper.assertTrue(replay.status() == PlayerCareerPromotionService.Status.SUCCESS
                                && platform.economyBalance(player.getUUID()).orElseThrow() == afterBalance
                                && player.getInventory().getNonEquipmentItems().stream()
                                        .filter(stack -> stack.is(Items.IRON_INGOT))
                                        .mapToInt(ItemStack::getCount).sum() == 8
                                && platform.rpgSkillOperation(replay.transactionId()).orElseThrow().phase()
                                        == org.dldyou.rovenfall.administration.RpgSkillOperation.Phase.COMPLETED,
                        "Promotion replay charged currency or newly acquired items twice");

                for (int index = 0; index < 100; index++) {
                    helper.assertTrue(ActivityXpAwardService.award(
                                    rpg, definitions, player.getUUID(), id("combat"), 10,
                                    timestamp + 260_000L + index * 4_000L, UUID.randomUUID(),
                                    "gametest:item_payment:warrior_" + index).status()
                                    == ActivityXpAwardService.Status.SUCCESS,
                            "Could not rank the RPG item-payment warrior fixture");
                }
                helper.assertTrue(RpgSkillService.learn(
                                rpg, definitions, player.getUUID(), id("power_strike"),
                                timestamp + 665_000L, UUID.randomUUID(), "gametest:item_payment").status()
                                == RpgSkillService.Status.SUCCESS,
                        "Could not learn the item-backed reset fixture skill");
                UUID resetTransaction = UUID.randomUUID();
                long beforeResetBalance = platform.economyBalance(player.getUUID()).orElseThrow();
                var deniedReset = RpgSkillResetCoordinator.reset(
                        player, org.dldyou.rovenfall.rpg.SkillResetPlan.Mode.BRANCH, id("power_strike"),
                        timestamp + 665_001L, resetTransaction);
                helper.assertTrue(deniedReset.status() == RpgSkillResetCoordinator.Status.ITEM_PAYMENT_FAILED
                                && platform.economyBalance(player.getUUID()).orElseThrow() == beforeResetBalance
                                && rpg.state(player.getUUID()).careers().get(id("warrior"))
                                        .learnedSkills().containsKey(id("power_strike")),
                        "Insufficient reset items changed currency or learned skills");

                player.getInventory().add(new ItemStack(Items.LAPIS_LAZULI, 4));
                var reset = RpgSkillResetCoordinator.reset(
                        player, org.dldyou.rovenfall.rpg.SkillResetPlan.Mode.BRANCH, id("power_strike"),
                        timestamp + 665_002L, resetTransaction);
                long afterResetBalance = platform.economyBalance(player.getUUID()).orElseThrow();
                helper.assertTrue(reset.status() == RpgSkillResetCoordinator.Status.SUCCESS
                                && afterResetBalance == beforeResetBalance
                                        - ActivityXpConfig.skillResetCost(
                                                org.dldyou.rovenfall.rpg.SkillResetPlan.Mode.BRANCH)
                                && !rpg.state(player.getUUID()).careers().get(id("warrior"))
                                        .learnedSkills().containsKey(id("power_strike"))
                                && player.getInventory().getNonEquipmentItems().stream()
                                        .filter(stack -> stack.is(Items.LAPIS_LAZULI))
                                        .mapToInt(ItemStack::getCount).sum() == 0,
                        "Combined item and currency reset was not committed exactly once");
                player.getInventory().add(new ItemStack(Items.LAPIS_LAZULI, 4));
                var resetReplay = RpgSkillResetCoordinator.reset(
                        player, org.dldyou.rovenfall.rpg.SkillResetPlan.Mode.BRANCH, id("power_strike"),
                        timestamp + 665_003L, resetTransaction);
                helper.assertTrue(resetReplay.status() == RpgSkillResetCoordinator.Status.SUCCESS
                                && platform.economyBalance(player.getUUID()).orElseThrow() == afterResetBalance
                                && player.getInventory().getNonEquipmentItems().stream()
                                        .filter(stack -> stack.is(Items.LAPIS_LAZULI))
                                        .mapToInt(ItemStack::getCount).sum() == 4,
                        "Reset replay charged currency or newly acquired items twice");
                helper.assertTrue(RpgItemPaymentGameTestScenario.platformRootSavedFirst(
                                player, id("guardian"), Identifier.parse("minecraft:iron_ingot"), 2,
                                timestamp + 665_004L),
                        "Platform-first item payment was not recovered exactly once");
                helper.assertTrue(RpgItemPaymentGameTestScenario.rpgRootSavedFirst(
                                player, id("recovery_evidence"), Identifier.parse("minecraft:iron_ingot"), 2,
                                timestamp + 665_005L),
                        "RPG-first item payment was not recovered exactly once");
                helper.assertTrue(RpgItemPaymentGameTestScenario.orphanPromotionRollsBack(
                                player, id("guardian"), Identifier.parse("minecraft:iron_ingot"), 2,
                                timestamp + 665_006L),
                        "Orphaned item escrow was not rolled back on recovery");
                helper.assertTrue(RpgItemPaymentGameTestScenario.expiredRpgRootPreservesMarkerForManualRecovery(
                                player, id("berserker"),
                                Identifier.parse("minecraft:iron_ingot"), 2,
                                timestamp - 31L * 24 * 60 * 60 * 1_000),
                        "Expired RPG-first item marker was not preserved for manual reconciliation");
                player.discard();
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
                var questState = QuestPlayerSavedData.get(server).state(naturalMiner.getUUID());
                helper.assertTrue(questState.quests().get(id("first_steps"))
                                .objectiveProgress().getOrDefault(id("first_steps/activity"), 0L) == 1,
                        "Server-observed mining outcome did not advance the quest");
                var retainedActivity = RpgPlayerSavedData.get(server)
                        .questActivityEvidenceFor(
                                naturalMiner.getUUID(),
                                RpgPlayerSavedData.MAX_QUEST_ACTIVITY_EVIDENCE_BATCH_SIZE)
                        .getFirst();
                helper.assertTrue(questState.processedEvidence().containsKey(retainedActivity.getKey()),
                        "Quest progress did not retain the owner evidence transaction");
                helper.assertTrue(retainedActivity.getValue().acknowledgedAtEpochMillis().isPresent(),
                        "Terminal quest delivery did not acknowledge the RPG outbox evidence");
                QuestProgressRuntime.acceptActivityEvidence(
                        server, naturalMiner.getUUID(), retainedActivity.getKey());
                helper.assertTrue(QuestPlayerSavedData.get(server).state(naturalMiner.getUUID())
                                .quests().get(id("first_steps")).objectiveProgress()
                                .getOrDefault(id("first_steps/activity"), 0L) == 1,
                        "Duplicate activity delivery advanced the quest twice");

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
        event.registerTest(id("quest_server_outcomes"), new FunctionGameTestInstance(
                BuiltinTestFunctions.ALWAYS_PASS, questTestData) {
            @Override
            public void run(GameTestHelper helper) {
                var server = helper.getLevel().getServer();
                var platform = PlatformSavedData.get(server);
                var rpg = RpgPlayerSavedData.get(server);
                var quests = QuestPlayerSavedData.get(server);
                var player = (net.minecraft.server.level.ServerPlayer) helper.makeMockServerPlayer(
                        net.minecraft.world.level.GameType.SURVIVAL);
                long timestamp = System.currentTimeMillis();
                var nether = server.getLevel(net.minecraft.world.level.Level.NETHER);
                helper.assertTrue(nether != null, "Nether was unavailable for quest activity evidence");

                BlockPos miningPosition = new BlockPos(21_000, 64, 21_000);
                NeoForge.EVENT_BUS.post(new BlockDropsEvent(
                        nether, miningPosition, Blocks.DIAMOND_ORE.defaultBlockState(), null,
                        new ArrayList<>(), player, ItemStack.EMPTY));
                var afterActivity = quests.state(player.getUUID()).quests().get(id("first_steps"));
                helper.assertTrue(afterActivity != null
                                && afterActivity.objectiveProgress()
                                        .getOrDefault(id("first_steps/activity"), 0L) == 1,
                        "Server activity did not enter first-steps quest progress");

                Identifier shopId = id("quest_outcome_" + UUID.randomUUID());
                Identifier offerId = id("foundation_bread");
                helper.assertTrue(ShopInstanceService.create(
                                platform, ShopTemplateReloadListener.snapshot(server),
                                AdministrationService.SYSTEM_ACTOR, true, shopId, id("foundation"),
                                Optional.empty(), key -> server.getLevel(key) != null,
                                ShopInstance.AccessPolicy.publicAccess(), server.overworld().getGameTime(),
                                "gametest quest shop", timestamp, UUID.randomUUID()).status()
                                == ShopInstanceService.Status.SUCCESS,
                        "Quest outcome shop setup failed");
                helper.assertTrue(EconomyService.award(
                                platform, player.getUUID(), 2_000, "gametest quest account", timestamp + 1,
                                UUID.randomUUID(), 0, Long.MAX_VALUE).status()
                                == EconomyService.TransactionStatus.SUCCESS,
                        "Quest outcome account setup failed");
                var offer = platform.shopInstance(shopId).orElseThrow().offers().get(offerId);
                UUID shopTransaction = UUID.randomUUID();
                helper.assertTrue(ShopTradeService.trade(
                                platform, player, new ShopTradeService.TradeRequest(
                                        shopId, offerId, ShopTradeService.Direction.BUY, 1,
                                        offer.item(), offer.buyPrice().orElseThrow(), shopTransaction),
                                server.overworld().getGameTime(), timestamp + 2).status()
                                == ShopTradeService.Status.SUCCESS,
                        "Server shop outcome did not commit");
                helper.assertTrue(quests.state(player.getUUID()).quests().get(id("first_steps"))
                                .objectiveProgress().getOrDefault(id("first_steps/shop_trade"), 0L) == 1,
                        "Committed shop receipt did not enter quest progress");

                int chunkX = 6_000;
                int chunkZ = 6_000;
                ClaimKey claimKey = new ClaimKey(player.level().dimension(), chunkX, chunkZ);
                while (platform.claim(claimKey).isPresent()) {
                    claimKey = new ClaimKey(player.level().dimension(), ++chunkX, chunkZ);
                }
                BlockPos claimPosition = new BlockPos((chunkX << 4) + 8, 70, (chunkZ << 4) + 8);
                long balanceBeforeClaim = platform.economyBalance(player.getUUID()).orElseThrow();
                UUID claimTransaction = UUID.randomUUID();
                var claim = ClaimPurchaseService.purchase(
                        platform, player.getUUID(), player.level().dimension(), player.level().dimension(),
                        claimPosition, ignored -> true, ignored -> false,
                        1_000, 250, 4, timestamp + 3, claimTransaction);
                helper.assertTrue(claim.status() == ClaimPurchaseService.Status.SUCCESS,
                        "Server land outcome did not commit");
                QuestProgressRuntime.acceptEconomyEvidence(server, claimTransaction);

                var completed = quests.state(player.getUUID()).quests().get(id("first_steps"));
                helper.assertTrue(completed != null && completed.completion().isPresent()
                                && completed.pendingReward().isEmpty()
                                && completed.objectiveProgress()
                                        .getOrDefault(id("first_steps/claim_purchase"), 0L) == 1,
                        "Three server-owned outcomes did not complete the quest");
                helper.assertTrue(platform.economyBalance(player.getUUID()).orElseThrow()
                                == balanceBeforeClaim - 1_000 + 100,
                        "Quest currency reward was not applied exactly once");
                helper.assertTrue(rpg.state(player.getUUID()).activityXp().getOrDefault(id("mining"), 0L) == 11,
                        "Quest activity XP reward was not applied exactly once");
                helper.assertTrue(rpg.questRewardReceiptCount() > 0,
                        "RPG owner root did not retain the quest reward receipt");

                long finalBalance = platform.economyBalance(player.getUUID()).orElseThrow();
                long finalXp = rpg.state(player.getUUID()).activityXp().getOrDefault(id("mining"), 0L);
                QuestProgressRuntime.acceptEconomyEvidence(server, shopTransaction);
                QuestProgressRuntime.acceptEconomyEvidence(server, claimTransaction);
                helper.assertTrue(platform.economyBalance(player.getUUID()).orElseThrow() == finalBalance
                                && rpg.state(player.getUUID()).activityXp()
                                        .getOrDefault(id("mining"), 0L) == finalXp,
                        "Duplicate owner evidence paid quest rewards twice");

                var persistedQuests = QuestPlayerSavedData.CODEC.parse(
                        NbtOps.INSTANCE, QuestPlayerSavedData.CODEC.encodeStart(
                                NbtOps.INSTANCE, quests).getOrThrow()).getOrThrow();
                var persistedRpg = RpgPlayerSavedData.CODEC.parse(
                        NbtOps.INSTANCE, RpgPlayerSavedData.CODEC.encodeStart(
                                NbtOps.INSTANCE, rpg).getOrThrow()).getOrThrow();
                helper.assertTrue(persistedQuests.state(player.getUUID()).quests().get(id("first_steps"))
                                .completion().isPresent()
                                && persistedRpg.state(player.getUUID()).activityXp()
                                        .getOrDefault(id("mining"), 0L) == finalXp
                                && persistedRpg.questRewardReceiptCount() == rpg.questRewardReceiptCount(),
                        "Quest completion or RPG reward receipt did not survive persistence");

                ShopInstanceService.delete(
                        platform, AdministrationService.SYSTEM_ACTOR, true, shopId,
                        "gametest quest cleanup", timestamp + 4, UUID.randomUUID());
                player.discard();
                helper.succeed();
            }
        });
        event.registerTest(id("wilderness_definition"), new FunctionGameTestInstance(
                BuiltinTestFunctions.ALWAYS_PASS, testData) {
            @Override
            public void run(GameTestHelper helper) {
                helper.assertTrue(
                        helper.getLevel().getServer().getResourceManager().getResource(
                                id("dimension/wilderness.json")).isPresent(),
                        "Wilderness dimension definition was not packaged in the Rovenfall data pack");
                helper.succeed();
            }
        });
        event.registerTest(id("mob_spawn_boundaries"), new FunctionGameTestInstance(
                BuiltinTestFunctions.ALWAYS_PASS, testData) {
            @Override
            public void run(GameTestHelper helper) {
                BlockPos position = new BlockPos(0, 70, 0);

                helper.assertTrue(MobSpawnPolicy.allowedOrdinarySpawn(
                                WorldCombatService.WILDERNESS_DIMENSION,
                                net.minecraft.world.entity.EntitySpawnReason.NATURAL),
                        "Wilderness natural spawn policy rejected an ordinary Rovenfall mob");
                helper.assertTrue(!MobSpawnPolicy.allowedOrdinarySpawn(
                                helper.getLevel().dimension(),
                                net.minecraft.world.entity.EntitySpawnReason.NATURAL),
                        "Hub natural spawn policy allowed an ordinary Rovenfall mob");

                List<net.minecraft.world.entity.EntityType<? extends net.minecraft.world.entity.monster.Monster>>
                        ordinaryTypes = List.of(
                                RovenfallEntityTypes.ASHEN_STALKER.get(),
                                RovenfallEntityTypes.RUNEBOUND_ARCHER.get(),
                                RovenfallEntityTypes.MIREFANG.get(),
                                RovenfallEntityTypes.CINDER_WISP.get(),
                                RovenfallEntityTypes.FROSTBOUND_REAVER.get(),
                                RovenfallEntityTypes.TIDEBOUND_RAIDER.get(),
                                RovenfallEntityTypes.DEEPSTONE_HUSK.get());
                for (var entityType : ordinaryTypes) {
                    var hubMob = entityType.create(
                            helper.getLevel(), net.minecraft.world.entity.EntitySpawnReason.NATURAL);
                    helper.assertTrue(hubMob != null, "Rovenfall ordinary entity type was not registered");
                    helper.assertTrue(MobSpawnPolicy.isRovenfallOrdinaryMob(hubMob),
                            "Registered ordinary entity was missing from spawn policy");
                    var hubSpawn = new net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent(
                            hubMob,
                            helper.getLevel(),
                            position.getX(), position.getY(), position.getZ(),
                            helper.getLevel().getCurrentDifficultyAt(position),
                            net.minecraft.world.entity.EntitySpawnReason.NATURAL,
                            null,
                            null);
                    NeoForge.EVENT_BUS.post(hubSpawn);
                    helper.assertTrue(hubSpawn.isSpawnCancelled(),
                            "Rovenfall ordinary mob was not blocked from Hub natural spawn");
                }

                var unmanagedBoss = RovenfallEntityTypes.ARENA_WARDEN.get().create(
                        helper.getLevel(), net.minecraft.world.entity.EntitySpawnReason.COMMAND);
                helper.assertTrue(unmanagedBoss != null, "Arena Warden entity type was not registered");
                var bossSpawn = new net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent(
                        unmanagedBoss,
                        helper.getLevel(),
                        position.getX(), position.getY(), position.getZ(),
                        helper.getLevel().getCurrentDifficultyAt(position),
                        net.minecraft.world.entity.EntitySpawnReason.COMMAND,
                        null,
                        null);
                NeoForge.EVENT_BUS.post(bossSpawn);
                helper.assertTrue(bossSpawn.isSpawnCancelled(),
                        "Unmanaged /summon-style Arena Warden bypassed encounter ownership");
                helper.succeed();
            }
        });
        event.registerTest(id("hunting_crafting"), new FunctionGameTestInstance(
                BuiltinTestFunctions.ALWAYS_PASS, testData) {
            @Override
            public void run(GameTestHelper helper) {
                var server = helper.getLevel().getServer();
                var mireguardRecipe = net.minecraft.resources.ResourceKey.create(
                        net.minecraft.core.registries.Registries.RECIPE, id("mireguard_tonic"));
                var cinderwardRecipe = net.minecraft.resources.ResourceKey.create(
                        net.minecraft.core.registries.Registries.RECIPE, id("cinderward_tonic"));
                var ashveilRecipe = net.minecraft.resources.ResourceKey.create(
                        net.minecraft.core.registries.Registries.RECIPE, id("ashveil_tonic"));
                var runewardRecipe = net.minecraft.resources.ResourceKey.create(
                        net.minecraft.core.registries.Registries.RECIPE, id("runeward_tonic"));
                var froststepRecipe = net.minecraft.resources.ResourceKey.create(
                        net.minecraft.core.registries.Registries.RECIPE, id("froststep_tonic"));
                var tidebreathRecipe = net.minecraft.resources.ResourceKey.create(
                        net.minecraft.core.registries.Registries.RECIPE, id("tidebreath_tonic"));
                var deepsightRecipe = net.minecraft.resources.ResourceKey.create(
                        net.minecraft.core.registries.Registries.RECIPE, id("deepsight_tonic"));
                var frontierStewRecipe = net.minecraft.resources.ResourceKey.create(
                        net.minecraft.core.registries.Registries.RECIPE, id("frontier_stew"));
                var frontierFeedRecipe = net.minecraft.resources.ResourceKey.create(
                        net.minecraft.core.registries.Registries.RECIPE, id("frontier_feed"));
                var highlandCheeseRecipe = net.minecraft.resources.ResourceKey.create(
                        net.minecraft.core.registries.Registries.RECIPE, id("highland_cheese"));
                var mirefangDaggerRecipe = net.minecraft.resources.ResourceKey.create(
                        net.minecraft.core.registries.Registries.RECIPE, id("mirefang_dagger"));
                var cinderbrandRecipe = net.minecraft.resources.ResourceKey.create(
                        net.minecraft.core.registries.Registries.RECIPE, id("cinderbrand"));
                var challengeSigilRecipe = net.minecraft.resources.ResourceKey.create(
                        net.minecraft.core.registries.Registries.RECIPE, id("warden_challenge_sigil"));
                var wardenbreakerRecipe = net.minecraft.resources.ResourceKey.create(
                        net.minecraft.core.registries.Registries.RECIPE, id("wardenbreaker"));
                var wardenRelicLoot = net.minecraft.resources.ResourceKey.create(
                        net.minecraft.core.registries.Registries.LOOT_TABLE, id("gameplay/warden_relic"));
                helper.assertTrue(server.getRecipeManager().byKey(mireguardRecipe).isPresent(),
                        "Mireguard Tonic recipe was not loaded");
                helper.assertTrue(server.getRecipeManager().byKey(cinderwardRecipe).isPresent(),
                        "Cinderward Tonic recipe was not loaded");
                helper.assertTrue(server.getRecipeManager().byKey(ashveilRecipe).isPresent(),
                        "Ashveil Tonic recipe was not loaded");
                helper.assertTrue(server.getRecipeManager().byKey(runewardRecipe).isPresent(),
                        "Runeward Tonic recipe was not loaded");
                helper.assertTrue(server.getRecipeManager().byKey(froststepRecipe).isPresent(),
                        "Froststep Tonic recipe was not loaded");
                helper.assertTrue(server.getRecipeManager().byKey(tidebreathRecipe).isPresent(),
                        "Tidebreath Tonic recipe was not loaded");
                helper.assertTrue(server.getRecipeManager().byKey(deepsightRecipe).isPresent(),
                        "Deepsight Tonic recipe was not loaded");
                helper.assertTrue(server.getRecipeManager().byKey(frontierStewRecipe).isPresent(),
                        "Frontier Stew recipe was not loaded");
                helper.assertTrue(server.getRecipeManager().byKey(frontierFeedRecipe).isPresent(),
                        "Frontier Feed recipe was not loaded");
                helper.assertTrue(server.getRecipeManager().byKey(highlandCheeseRecipe).isPresent(),
                        "Highland Cheese recipe was not loaded");
                helper.assertTrue(server.getRecipeManager().byKey(mirefangDaggerRecipe).isPresent(),
                        "Mirefang Dagger recipe was not loaded");
                helper.assertTrue(server.getRecipeManager().byKey(cinderbrandRecipe).isPresent(),
                        "Cinderbrand recipe was not loaded");
                helper.assertTrue(server.getRecipeManager().byKey(challengeSigilRecipe).isPresent(),
                        "Warden Challenge Sigil recipe was not loaded");
                helper.assertTrue(server.getRecipeManager().byKey(wardenbreakerRecipe).isPresent(),
                        "Wardenbreaker recipe was not loaded");

                for (String advancementPath : List.of(
                        "wilderness/root",
                        "wilderness/beneath_the_frontier",
                        "wilderness/frontier_feast",
                        "wilderness/highland_herd",
                        "wilderness/highland_provisions",
                        "wilderness/hunt_the_frontier",
                        "wilderness/frontier_alchemist",
                        "wilderness/challenge_the_warden",
                        "wilderness/warden_defeated",
                        "wilderness/warden_relic_reward",
                        "wilderness/forge_the_wardenbreaker")) {
                    helper.assertTrue(server.getAdvancements().get(id(advancementPath)) != null,
                            "Wilderness advancement was not loaded: " + advancementPath);
                }
                helper.assertTrue(server.getAdvancements().get(id("wilderness/hunt_the_frontier"))
                                .value().criteria().keySet().equals(Set.of(
                                        "ashen_stalker", "runebound_archer", "mirefang", "cinder_wisp",
                                        "frostbound_reaver", "tidebound_raider", "deepstone_husk")),
                        "Wilderness hunt advancement did not require all seven custom mobs");
                helper.assertTrue(server.getAdvancements().get(id("wilderness/frontier_alchemist"))
                                .value().criteria().keySet().equals(Set.of(
                                        "mireguard_tonic", "cinderward_tonic", "ashveil_tonic", "runeward_tonic",
                                        "froststep_tonic", "tidebreath_tonic", "deepsight_tonic")),
                        "Frontier Alchemist advancement did not require all seven hunting tonics");
                helper.assertTrue(server.getAdvancements().get(id("wilderness/beneath_the_frontier"))
                                .value().criteria().keySet().equals(Set.of(
                                        "dripstone_caves", "lush_caves", "sulfur_caves", "deep_dark")),
                        "Beneath the Frontier advancement did not require all four cave biomes");
                var relicRewardAdvancement = server.getAdvancements().get(id("wilderness/warden_relic_reward"));
                helper.assertTrue(relicRewardAdvancement.value().display().isEmpty()
                                && relicRewardAdvancement.value().rewards().loot().contains(wardenRelicLoot)
                                && relicRewardAdvancement.value().rewards().recipes().contains(wardenbreakerRecipe),
                        "Hidden Warden relic advancement did not retain its personal loot reward");

                var ashenStalker = RovenfallEntityTypes.ASHEN_STALKER.get().create(
                        helper.getLevel(), net.minecraft.world.entity.EntitySpawnReason.COMMAND);
                var runeboundArcher = RovenfallEntityTypes.RUNEBOUND_ARCHER.get().create(
                        helper.getLevel(), net.minecraft.world.entity.EntitySpawnReason.COMMAND);
                var frostboundReaver = RovenfallEntityTypes.FROSTBOUND_REAVER.get().create(
                        helper.getLevel(), net.minecraft.world.entity.EntitySpawnReason.COMMAND);
                var tideboundRaider = RovenfallEntityTypes.TIDEBOUND_RAIDER.get().create(
                        helper.getLevel(), net.minecraft.world.entity.EntitySpawnReason.COMMAND);
                var deepstoneHusk = RovenfallEntityTypes.DEEPSTONE_HUSK.get().create(
                        helper.getLevel(), net.minecraft.world.entity.EntitySpawnReason.COMMAND);
                helper.assertTrue(ashenStalker != null && runeboundArcher != null
                                && frostboundReaver != null && tideboundRaider != null && deepstoneHusk != null,
                        "Hunting loot test entities could not be created");
                helper.assertTrue(lootTableCanDrop(
                                helper, "ashen_stalker", ashenStalker, RovenfallItems.ASHEN_RESIDUE.get()),
                        "Ashen Stalker loot table did not produce Ashen Residue");
                helper.assertTrue(lootTableCanDrop(
                                helper, "runebound_archer", runeboundArcher,
                                RovenfallItems.RUNEBOUND_FRAGMENT.get()),
                        "Runebound Archer loot table did not produce a Runebound Fragment");
                helper.assertTrue(lootTableCanDrop(
                                helper, "frostbound_reaver", frostboundReaver,
                                RovenfallItems.FROSTBOUND_SHARD.get()),
                        "Frostbound Reaver loot table did not produce a Frostbound Shard");
                helper.assertTrue(lootTableCanDrop(
                                helper, "tidebound_raider", tideboundRaider,
                                RovenfallItems.TIDEBOUND_SCALE.get()),
                        "Tidebound Raider loot table did not produce a Tidebound Scale");
                helper.assertTrue(lootTableCanDrop(
                                helper, "deepstone_husk", deepstoneHusk,
                                RovenfallItems.DEEPSTONE_CORE.get()),
                        "Deepstone Husk loot table did not produce a Deepstone Core");

                var consumer = net.minecraft.world.entity.EntityTypes.COW.create(
                        helper.getLevel(), net.minecraft.world.entity.EntitySpawnReason.COMMAND);
                helper.assertTrue(consumer != null, "Consumable GameTest entity could not be created");
                ItemStack frontierStew = RovenfallItems.FRONTIER_STEW.toStack();
                var frontierFood = frontierStew.get(net.minecraft.core.component.DataComponents.FOOD);
                helper.assertTrue(frontierFood != null && frontierFood.nutrition() == 10,
                        "Frontier Stew did not retain its native stew nutrition");
                helper.assertTrue(frontierStew.get(net.minecraft.core.component.DataComponents.CONSUMABLE) != null,
                        "Frontier Stew was missing its consumable component");
                ItemStack frontierStewRemainder = frontierStew.finishUsingItem(helper.getLevel(), consumer);
                var frontierRegeneration = consumer.getEffect(net.minecraft.world.effect.MobEffects.REGENERATION);
                helper.assertTrue(frontierRegeneration != null && frontierRegeneration.getDuration() == 20 * 5,
                        "Frontier Stew did not grant five seconds of regeneration");
                helper.assertTrue(frontierStewRemainder.getItem() == Items.BOWL,
                        "Consumed Frontier Stew did not return its bowl");
                ItemStack highlandCheese = RovenfallItems.HIGHLAND_CHEESE.toStack();
                var highlandFood = highlandCheese.get(net.minecraft.core.component.DataComponents.FOOD);
                helper.assertTrue(highlandFood != null && highlandFood.nutrition() == 6,
                        "Highland Cheese did not retain its configured nutrition");
                highlandCheese.finishUsingItem(helper.getLevel(), consumer);
                var cheeseJumpBoost = consumer.getEffect(net.minecraft.world.effect.MobEffects.JUMP_BOOST);
                helper.assertTrue(cheeseJumpBoost != null && cheeseJumpBoost.getDuration() == 20 * 45,
                        "Highland Cheese did not grant forty-five seconds of Jump Boost");
                consumer.addEffect(new net.minecraft.world.effect.MobEffectInstance(
                        net.minecraft.world.effect.MobEffects.POISON, 20 * 30));
                ItemStack mireguard = RovenfallItems.MIREGUARD_TONIC.toStack(2);
                helper.assertTrue(mireguard.get(net.minecraft.core.component.DataComponents.CONSUMABLE) != null,
                        "Mireguard Tonic was missing its consumable component");
                ItemStack remainingMireguard = mireguard.finishUsingItem(helper.getLevel(), consumer);
                helper.assertTrue(!consumer.hasEffect(net.minecraft.world.effect.MobEffects.POISON),
                        "Mireguard Tonic did not cure poison on the server");
                helper.assertTrue(consumer.hasEffect(net.minecraft.world.effect.MobEffects.REGENERATION),
                        "Mireguard Tonic did not grant regeneration on the server");
                helper.assertTrue(remainingMireguard.getCount() == 1,
                        "Mireguard Tonic did not consume exactly one item");

                ItemStack cinderward = RovenfallItems.CINDERWARD_TONIC.toStack();
                helper.assertTrue(cinderward.get(net.minecraft.core.component.DataComponents.CONSUMABLE) != null,
                        "Cinderward Tonic was missing its consumable component");
                ItemStack cinderwardRemainder = cinderward.finishUsingItem(helper.getLevel(), consumer);
                var fireResistance = consumer.getEffect(net.minecraft.world.effect.MobEffects.FIRE_RESISTANCE);
                helper.assertTrue(fireResistance != null && fireResistance.getDuration() == 20 * 60 * 3,
                        "Cinderward Tonic did not grant three minutes of fire resistance");
                helper.assertTrue(cinderwardRemainder.getItem() == Items.GLASS_BOTTLE,
                        "Consumed hunting tonic did not return its glass bottle");

                ItemStack ashveil = RovenfallItems.ASHVEIL_TONIC.toStack();
                ashveil.finishUsingItem(helper.getLevel(), consumer);
                var speed = consumer.getEffect(net.minecraft.world.effect.MobEffects.SPEED);
                var invisibility = consumer.getEffect(net.minecraft.world.effect.MobEffects.INVISIBILITY);
                helper.assertTrue(speed != null && speed.getDuration() == 20 * 60,
                        "Ashveil Tonic did not grant one minute of speed");
                helper.assertTrue(invisibility != null && invisibility.getDuration() == 20 * 15,
                        "Ashveil Tonic did not grant fifteen seconds of invisibility");

                ItemStack runeward = RovenfallItems.RUNEWARD_TONIC.toStack();
                runeward.finishUsingItem(helper.getLevel(), consumer);
                var absorption = consumer.getEffect(net.minecraft.world.effect.MobEffects.ABSORPTION);
                var resistance = consumer.getEffect(net.minecraft.world.effect.MobEffects.RESISTANCE);
                helper.assertTrue(absorption != null && absorption.getDuration() == 20 * 60 * 2,
                        "Runeward Tonic did not grant two minutes of absorption");
                helper.assertTrue(resistance != null && resistance.getDuration() == 20 * 30,
                        "Runeward Tonic did not grant thirty seconds of resistance");

                consumer.addEffect(new net.minecraft.world.effect.MobEffectInstance(
                        net.minecraft.world.effect.MobEffects.SLOWNESS, 20 * 30));
                ItemStack froststep = RovenfallItems.FROSTSTEP_TONIC.toStack();
                froststep.finishUsingItem(helper.getLevel(), consumer);
                var jumpBoost = consumer.getEffect(net.minecraft.world.effect.MobEffects.JUMP_BOOST);
                var slowFalling = consumer.getEffect(net.minecraft.world.effect.MobEffects.SLOW_FALLING);
                helper.assertTrue(!consumer.hasEffect(net.minecraft.world.effect.MobEffects.SLOWNESS),
                        "Froststep Tonic did not cure slowness on the server");
                helper.assertTrue(jumpBoost != null
                                && jumpBoost.getDuration() == 20 * 60 * 2
                                && jumpBoost.getAmplifier() == 1,
                        "Froststep Tonic did not grant two minutes of Jump Boost II");
                helper.assertTrue(slowFalling != null && slowFalling.getDuration() == 20 * 30,
                        "Froststep Tonic did not grant thirty seconds of slow falling");

                consumer.addEffect(new net.minecraft.world.effect.MobEffectInstance(
                        net.minecraft.world.effect.MobEffects.MINING_FATIGUE, 20 * 30));
                ItemStack tidebreath = RovenfallItems.TIDEBREATH_TONIC.toStack();
                tidebreath.finishUsingItem(helper.getLevel(), consumer);
                var waterBreathing = consumer.getEffect(net.minecraft.world.effect.MobEffects.WATER_BREATHING);
                var dolphinsGrace = consumer.getEffect(net.minecraft.world.effect.MobEffects.DOLPHINS_GRACE);
                helper.assertTrue(!consumer.hasEffect(net.minecraft.world.effect.MobEffects.MINING_FATIGUE),
                        "Tidebreath Tonic did not cure mining fatigue on the server");
                helper.assertTrue(waterBreathing != null && waterBreathing.getDuration() == 20 * 60 * 3,
                        "Tidebreath Tonic did not grant three minutes of water breathing");
                helper.assertTrue(dolphinsGrace != null && dolphinsGrace.getDuration() == 20 * 45,
                        "Tidebreath Tonic did not grant forty-five seconds of Dolphin's Grace");

                consumer.addEffect(new net.minecraft.world.effect.MobEffectInstance(
                        net.minecraft.world.effect.MobEffects.DARKNESS, 20 * 30));
                ItemStack deepsight = RovenfallItems.DEEPSIGHT_TONIC.toStack();
                deepsight.finishUsingItem(helper.getLevel(), consumer);
                var nightVision = consumer.getEffect(net.minecraft.world.effect.MobEffects.NIGHT_VISION);
                var haste = consumer.getEffect(net.minecraft.world.effect.MobEffects.HASTE);
                helper.assertTrue(!consumer.hasEffect(net.minecraft.world.effect.MobEffects.DARKNESS),
                        "Deepsight Tonic did not cure darkness on the server");
                helper.assertTrue(nightVision != null && nightVision.getDuration() == 20 * 60 * 5,
                        "Deepsight Tonic did not grant five minutes of night vision");
                helper.assertTrue(haste != null && haste.getDuration() == 20 * 60 * 2 && haste.getAmplifier() == 1,
                        "Deepsight Tonic did not grant two minutes of Haste II");

                var attacker = net.minecraft.world.entity.EntityTypes.COW.create(
                        helper.getLevel(), net.minecraft.world.entity.EntitySpawnReason.COMMAND);
                var target = net.minecraft.world.entity.EntityTypes.COW.create(
                        helper.getLevel(), net.minecraft.world.entity.EntitySpawnReason.COMMAND);
                helper.assertTrue(attacker != null && target != null,
                        "Hunting weapon GameTest entities could not be created");
                ItemStack mirefangDagger = RovenfallItems.MIREFANG_DAGGER.toStack();
                mirefangDagger.postHurtEnemy(target, attacker);
                var poison = target.getEffect(net.minecraft.world.effect.MobEffects.POISON);
                helper.assertTrue(poison != null && poison.getDuration() == 20 * 3,
                        "Mirefang Dagger did not apply three seconds of poison on the server");
                target.removeEffect(net.minecraft.world.effect.MobEffects.POISON);

                ItemStack cinderbrand = RovenfallItems.CINDERBRAND.toStack();
                cinderbrand.postHurtEnemy(target, attacker);
                helper.assertTrue(target.getRemainingFireTicks() >= 20 * 4,
                        "Cinderbrand did not ignite its target for four seconds on the server");
                ItemStack wardenbreaker = RovenfallItems.WARDENBREAKER.toStack();
                wardenbreaker.postHurtEnemy(target, attacker);
                var weakness = target.getEffect(net.minecraft.world.effect.MobEffects.WEAKNESS);
                helper.assertTrue(weakness != null && weakness.getDuration() == 20 * 5,
                        "Wardenbreaker did not weaken its target for five seconds on the server");
                helper.assertTrue(mirefangDagger.isDamageableItem()
                                && cinderbrand.isDamageableItem()
                                && wardenbreaker.isDamageableItem(),
                        "Hunting weapons did not retain native weapon durability");

                var player = (net.minecraft.server.level.ServerPlayer) helper.makeMockServerPlayer(
                        net.minecraft.world.level.GameType.SURVIVAL);
                var challengeAdvancement = server.getAdvancements().get(id("wilderness/challenge_the_warden"));
                boolean challengeCompletedBefore = player.getAdvancements()
                        .getOrStartProgress(challengeAdvancement).isDone();
                ItemStack challengeSigils = RovenfallItems.WARDEN_CHALLENGE_SIGIL.toStack(2);
                player.setItemInHand(InteractionHand.MAIN_HAND, challengeSigils);
                var wrongWorldUse = challengeSigils.use(helper.getLevel(), player, InteractionHand.MAIN_HAND);
                helper.assertTrue(wrongWorldUse == net.minecraft.world.InteractionResult.FAIL,
                        "Challenge Sigil did not reject use outside the Wilderness");
                helper.assertTrue(challengeSigils.getCount() == 2,
                        "Rejected Challenge Sigil use consumed an item");
                helper.assertTrue(player.getAdvancements().getOrStartProgress(challengeAdvancement).isDone()
                                == challengeCompletedBefore,
                        "Rejected Challenge Sigil use awarded its advancement");
                var relicProgress = new net.minecraft.advancements.AdvancementProgress();
                relicProgress.update(relicRewardAdvancement.value().requirements());
                helper.assertTrue(relicProgress.grantProgress("rewarded") && relicProgress.isDone(),
                        "Warden relic criterion did not complete on its first grant");
                helper.assertTrue(!relicProgress.grantProgress("rewarded"),
                        "Warden relic criterion accepted a duplicate completion");
                var relicParams = new net.minecraft.world.level.storage.loot.LootParams.Builder(helper.getLevel())
                        .withParameter(net.minecraft.world.level.storage.loot.parameters.LootContextParams.THIS_ENTITY,
                                player)
                        .withParameter(net.minecraft.world.level.storage.loot.parameters.LootContextParams.ORIGIN,
                                player.position())
                        .create(net.minecraft.world.level.storage.loot.parameters.LootContextParamSets.ADVANCEMENT_REWARD);
                var relicDrops = server.reloadableRegistries().getLootTable(wardenRelicLoot)
                        .getRandomItems(relicParams, 0L);
                helper.assertTrue(relicDrops.size() == 1
                                && relicDrops.getFirst().getItem() == RovenfallItems.WARDEN_CORE.get()
                                && relicDrops.getFirst().getCount() == 1,
                        "Warden relic loot did not grant exactly one Warden Core");
                helper.succeed();
            }
        });
        event.registerTest(id("frontier_feed"), new FunctionGameTestInstance(
                BuiltinTestFunctions.ALWAYS_PASS, testData) {
            @Override
            public void run(GameTestHelper helper) {
                var level = helper.getLevel();
                var player = (net.minecraft.server.level.ServerPlayer) helper.makeMockServerPlayer(
                        net.minecraft.world.level.GameType.SURVIVAL);
                var state = PlatformSavedData.get(level.getServer());
                var parentA = helper.spawnWithNoFreeWill(EntityTypes.GOAT, new BlockPos(1, 2, 0));
                var parentB = helper.spawnWithNoFreeWill(EntityTypes.GOAT, new BlockPos(2, 2, 0));
                ItemStack feed = RovenfallItems.FRONTIER_FEED.toStack(2);
                long farmingBefore = state.activityExperience(player.getUUID(), ActivityTrack.FARMING);

                helper.assertTrue(feed.interactLivingEntity(player, parentA, InteractionHand.MAIN_HAND)
                                == net.minecraft.world.InteractionResult.SUCCESS_SERVER,
                        "Frontier Feed did not ready the first eligible animal");
                helper.assertTrue(feed.interactLivingEntity(player, parentB, InteractionHand.MAIN_HAND)
                                == net.minecraft.world.InteractionResult.SUCCESS_SERVER,
                        "Frontier Feed did not ready the second eligible animal");
                helper.assertTrue(feed.isEmpty() && parentA.isInLove() && parentB.isInLove(),
                        "Frontier Feed did not consume exactly one item per eligible animal");
                helper.assertTrue(state.activityExperience(player.getUUID(), ActivityTrack.FARMING) == farmingBefore,
                        "Frontier Feed awarded farming experience before breeding completed");

                ItemStack repeatedFeed = RovenfallItems.FRONTIER_FEED.toStack();
                helper.assertTrue(repeatedFeed.interactLivingEntity(player, parentA, InteractionHand.MAIN_HAND)
                                == net.minecraft.world.InteractionResult.PASS
                                && repeatedFeed.getCount() == 1,
                        "Frontier Feed was consumed by an animal already ready to breed");
                var child = helper.spawnWithNoFreeWill(EntityTypes.GOAT, new BlockPos(3, 2, 0));
                NeoForge.EVENT_BUS.post(new net.neoforged.neoforge.event.entity.living.BabyEntitySpawnEvent(
                        parentA, parentB, child));
                helper.assertTrue(
                        state.activityExperience(player.getUUID(), ActivityTrack.FARMING) == farmingBefore + 12,
                        "Completed goat breeding did not award twelve farming experience");

                var rabbit = helper.spawnWithNoFreeWill(EntityTypes.RABBIT, new BlockPos(4, 2, 0));
                ItemStack rejectedFeed = RovenfallItems.FRONTIER_FEED.toStack();
                helper.assertTrue(rejectedFeed.interactLivingEntity(player, rabbit, InteractionHand.MAIN_HAND)
                                == net.minecraft.world.InteractionResult.PASS
                                && rejectedFeed.getCount() == 1
                                && !rabbit.isInLove(),
                        "Frontier Feed affected an animal outside its data tag");

                var automatedGoat = helper.spawnWithNoFreeWill(EntityTypes.GOAT, new BlockPos(5, 2, 0));
                ItemStack automatedFeed = RovenfallItems.FRONTIER_FEED.toStack();
                var fakePlayer = FakePlayerFactory.getMinecraft(level);
                helper.assertTrue(automatedFeed.interactLivingEntity(
                                fakePlayer, automatedGoat, InteractionHand.MAIN_HAND)
                                == net.minecraft.world.InteractionResult.FAIL
                                && automatedFeed.getCount() == 1
                                && !automatedGoat.isInLove(),
                        "Automated player bypassed Frontier Feed ownership validation");
                helper.succeed();
            }
        });
        event.registerTest(id("mob_mutation_composition"), new FunctionGameTestInstance(
                BuiltinTestFunctions.ALWAYS_PASS, testData) {
            @Override
            public void run(GameTestHelper helper) {
                var server = helper.getLevel().getServer();
                var catalog = MobMutationReloadListener.snapshot(server).orElseThrow();
                helper.assertTrue(catalog.size() == 4, "Built-in mob mutation catalog did not load exactly four entries");
                helper.assertTrue(MobMutationEvents.eligibleSpawn(
                                WorldCombatService.WILDERNESS_DIMENSION,
                                net.minecraft.world.entity.EntitySpawnReason.NATURAL,
                                false,
                                false),
                        "Eligible Wilderness natural spawn was rejected by mutation policy");
                helper.assertTrue(!MobMutationEvents.eligibleSpawn(
                                WorldCombatService.WILDERNESS_DIMENSION,
                                net.minecraft.world.entity.EntitySpawnReason.COMMAND,
                                false,
                                false),
                        "Command spawn was accepted by mutation policy");
                var mutation = catalog.get(id("ashen")).orElseThrow();
                var zombie = net.minecraft.world.entity.EntityTypes.ZOMBIE.create(
                        helper.getLevel(), net.minecraft.world.entity.EntitySpawnReason.NATURAL);
                helper.assertTrue(zombie != null, "Mutation GameTest zombie could not be created");
                float previousHealth = zombie.getMaxHealth();
                helper.assertTrue(MobMutationApplicator.apply(zombie, mutation, false),
                        "Validated mutation did not compose onto an eligible mob");
                helper.assertTrue(MobMutationApplicator.mutationId(zombie).equals(Optional.of(id("ashen"))),
                        "Mutation ID marker was not persisted on the mob");
                helper.assertTrue(zombie.hasGlowingTag() && zombie.hasCustomName(),
                        "Mutation did not expose its visible marker");
                helper.assertTrue(zombie.getMaxHealth() > previousHealth,
                        "Mutation attribute composition did not change max health");
                helper.assertTrue(!MobMutationApplicator.apply(zombie, mutation, false),
                        "Mutation was applied twice to the same live mob");

                var bulwark = catalog.get(id("bulwark")).orElseThrow();
                var creeper = net.minecraft.world.entity.EntityTypes.CREEPER.create(
                        helper.getLevel(), net.minecraft.world.entity.EntitySpawnReason.NATURAL);
                helper.assertTrue(creeper != null, "Bulwark mutation GameTest creeper could not be created");
                float bulwarkHealth = creeper.getMaxHealth();
                double bulwarkSpeed = creeper.getAttributeValue(
                        net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED);
                double bulwarkResistance = creeper.getAttributeValue(
                        net.minecraft.world.entity.ai.attributes.Attributes.KNOCKBACK_RESISTANCE);
                helper.assertTrue(MobMutationApplicator.apply(creeper, bulwark, false),
                        "Bulwark mutation did not compose onto its eligible mob");
                helper.assertTrue(creeper.getMaxHealth() > bulwarkHealth
                                && creeper.getAttributeValue(
                                        net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED) < bulwarkSpeed
                                && creeper.getAttributeValue(
                                        net.minecraft.world.entity.ai.attributes.Attributes.KNOCKBACK_RESISTANCE)
                                        > bulwarkResistance,
                        "Bulwark mutation did not become tougher, slower, and knockback resistant");

                var frenzied = catalog.get(id("frenzied")).orElseThrow();
                var caveSpider = net.minecraft.world.entity.EntityTypes.CAVE_SPIDER.create(
                        helper.getLevel(), net.minecraft.world.entity.EntitySpawnReason.NATURAL);
                helper.assertTrue(caveSpider != null, "Frenzied mutation GameTest cave spider could not be created");
                double frenziedSpeed = caveSpider.getAttributeValue(
                        net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED);
                double frenziedDamage = caveSpider.getAttributeValue(
                        net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE);
                helper.assertTrue(MobMutationApplicator.apply(caveSpider, frenzied, false),
                        "Frenzied mutation did not compose onto its eligible mob");
                helper.assertTrue(caveSpider.getAttributeValue(
                                        net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED) > frenziedSpeed
                                && caveSpider.getAttributeValue(
                                        net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE) > frenziedDamage,
                        "Frenzied mutation did not become faster and more damaging");

                var commandZombie = net.minecraft.world.entity.EntityTypes.ZOMBIE.create(
                        helper.getLevel(), net.minecraft.world.entity.EntitySpawnReason.COMMAND);
                helper.assertTrue(commandZombie != null, "Command-spawned mutation GameTest zombie could not be created");
                var commandSpawn = new net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent(
                        commandZombie,
                        helper.getLevel(),
                        0, 70, 0,
                        helper.getLevel().getCurrentDifficultyAt(new BlockPos(0, 70, 0)),
                        net.minecraft.world.entity.EntitySpawnReason.COMMAND,
                        null,
                        null);
                NeoForge.EVENT_BUS.post(commandSpawn);
                helper.assertTrue(MobMutationApplicator.mutationId(commandZombie).isEmpty(),
                        "Command-spawned vanilla mob received a Wilderness mutation");
                helper.succeed();
            }
        });
        event.registerTest(id("boss_encounter_persistence"), new FunctionGameTestInstance(
                BuiltinTestFunctions.ALWAYS_PASS, testData) {
            @Override
            public void run(GameTestHelper helper) {
                PlatformSavedData state = new PlatformSavedData();
                UUID encounterId = UUID.randomUUID();
                UUID playerId = UUID.randomUUID();
                BlockPos origin = new BlockPos(90_000, 72, 90_000);
                long timestamp = System.currentTimeMillis();
                var started = BossEncounterService.start(
                        state,
                        AdministrationService.SYSTEM_ACTOR,
                        true,
                        WorldCombatService.WILDERNESS_DIMENSION,
                        origin,
                        24,
                        "gametest boss",
                        timestamp,
                        encounterId,
                        ignored -> true);
                helper.assertTrue(started.status() == BossEncounterService.StartStatus.SUCCESS,
                        "Boss encounter did not enter INTRO");
                helper.assertTrue(BossEncounterService.activate(state, encounterId, timestamp + 3_000),
                        "Boss encounter did not enter ACTIVE");
                var boss = state.bossEncounter().orElseThrow();
                helper.assertTrue(BossEncounterService.recordContribution(
                                state, encounterId, boss.bossId(), playerId, origin, 100, timestamp + 3_100),
                        "Server-observed boss contribution was rejected");
                helper.assertTrue(BossEncounterService.observePhase(state, encounterId, 2, timestamp + 3_200),
                        "Boss phase transition was not committed");
                helper.assertTrue(BossEncounterService.beginRewards(state, encounterId, timestamp + 4_000),
                        "Boss death did not enter REWARDING");
                var rewards = BossEncounterService.settleRewards(state, timestamp + 4_100);
                helper.assertTrue(rewards.size() == 1
                                && rewards.getFirst().status() == BossEncounterService.RewardStatus.SUCCESS,
                        "Qualifying boss contributor did not receive one personal reward");
                var restored = PlatformSavedData.CODEC.parse(
                        NbtOps.INSTANCE,
                        PlatformSavedData.CODEC.encodeStart(NbtOps.INSTANCE, state).getOrThrow()).getOrThrow();
                helper.assertTrue(restored.bossEncounter().orElseThrow().status()
                                == org.dldyou.rovenfall.mobs.BossEncounter.Status.DEFEATED,
                        "Boss lifecycle did not survive persistence");
                helper.assertTrue(restored.bossState().rewardReadyAt(playerId) > timestamp + 4_100,
                        "Personal boss reward cooldown did not survive persistence");
                helper.succeed();
            }
        });
        event.registerTest(id("safe_arrival"), new FunctionGameTestInstance(
                BuiltinTestFunctions.ALWAYS_PASS, testData) {
            @Override
            public void run(GameTestHelper helper) {
                BlockPos center = helper.getLevel().getRespawnData().pos();
                var found = SafeArrivalResolver.resolve(helper.getLevel(), center, 2, ignored -> true);
                helper.assertTrue(found.status() == SafeArrivalResolver.Status.FOUND,
                        "Safe arrival resolver did not find the flat test surface");
                var denied = SafeArrivalResolver.resolve(helper.getLevel(), center, 2, ignored -> false);
                helper.assertTrue(denied.status() == SafeArrivalResolver.Status.NOT_FOUND,
                        "Safe arrival resolver ignored destination authorization");
                helper.succeed();
            }
        });
        event.registerTest(id("portal_region_protection"), new FunctionGameTestInstance(
                BuiltinTestFunctions.ALWAYS_PASS, testData) {
            @Override
            public void run(GameTestHelper helper) {
                var level = helper.getLevel().getServer().getLevel(Level.NETHER);
                helper.assertTrue(level != null, "Nether level was unavailable for portal protection GameTest");
                var state = PlatformSavedData.get(level.getServer());
                Identifier portalId = id("gametest_region_portal_" + UUID.randomUUID());
                BlockPos origin = new BlockPos(30_000, 70, 30_000);
                long timestamp = System.currentTimeMillis();
                for (Identifier staleId : state.portalIds().stream()
                        .filter(value -> value.getPath().startsWith("gametest_region_portal_")
                                || value.getPath().startsWith("gametest_portal_")
                                || value.getPath().startsWith("gametest_travel_portal_"))
                        .toList()) {
                    ManagedPortalService.delete(
                            state,
                            AdministrationService.SYSTEM_ACTOR,
                            true,
                            staleId,
                            "gametest stale portal cleanup",
                            timestamp++,
                            UUID.randomUUID());
                }
                Portal portal = new Portal(
                        level.dimension(), origin, level.dimension(), origin.offset(100, 0, 100), 8, 8, 0);
                helper.assertTrue(ManagedPortalService.create(
                                state,
                                AdministrationService.SYSTEM_ACTOR,
                                true,
                                portalId,
                                portal,
                                key -> level.getServer().getLevel(key) != null,
                                "gametest portal protection",
                                timestamp,
                                UUID.randomUUID()).status() == ManagedPortalService.Status.SUCCESS,
                        "GameTest portal creation failed");
                helper.assertTrue(!ClaimProtectionHooks.systemMayModify(level, origin),
                        "System block changes were allowed at the protected portal origin");
                helper.assertTrue(!ClaimProtectionHooks.environmentMayModify(
                                level, origin.offset(20, 0, 0), origin),
                        "Environment changes entered the protected portal region");
                helper.assertTrue(ClaimProtectionHooks.systemMayModify(level, origin.offset(8, 0, 8)),
                        "Portal protection used its candidate chunk instead of the exact circular radius");
                helper.assertTrue(ManagedPortalService.delete(
                                state,
                                AdministrationService.SYSTEM_ACTOR,
                                true,
                                portalId,
                                "gametest portal cleanup",
                                timestamp + 1,
                                UUID.randomUUID()).status() == ManagedPortalService.Status.SUCCESS,
                        "GameTest portal cleanup failed");
                helper.assertTrue(ClaimProtectionHooks.systemMayModify(level, origin),
                        "Deleted portal protection remained active");
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
        event.registerTest(id("activity_progression"), new FunctionGameTestInstance(
                BuiltinTestFunctions.ALWAYS_PASS, testData) {
            @Override
            public void run(GameTestHelper helper) {
                var level = helper.getLevel();
                var server = level.getServer();
                Identifier plains = Identifier.withDefaultNamespace("plains");
                var listener = server.getServerResources().managers()
                        .getListener(ActivityRewardReloadListener.KEY);
                helper.assertTrue(listener != null,
                        "Rovenfall activity reward listener was not retained");
                helper.assertTrue(listener.size() == 80,
                        "Built-in Rovenfall activity reward catalog was incomplete");
                var levelListener = server.getServerResources().managers()
                        .getListener(ActivityLevelReloadListener.KEY);
                helper.assertTrue(levelListener != null && levelListener.size() == 7,
                        "Built-in Rovenfall activity level curves were incomplete");
                helper.assertTrue(ActivityLevelReloadListener.get(server, ActivityTrack.EXPLORATION)
                                .orElseThrow().progress(300).level() == 2,
                        "Activity level curve did not resolve cumulative experience");
                var careers = CareerDefinitionReloadListener.snapshot(server).orElseThrow();
                helper.assertTrue(careers.size() == 10,
                        "Built-in Rovenfall career graph was incomplete");
                helper.assertTrue(careers.skillIds().size() == 20,
                        "Built-in Rovenfall career skill trees were incomplete");
                helper.assertTrue(careers.activeSkillIds().size() == 20,
                        "Built-in Rovenfall active skill definitions were incomplete");
                helper.assertTrue(careers.definition(id("warrior")).orElseThrow().promotionSkillPoints() == 1,
                        "Career promotion skill-point reward was not loaded");
                helper.assertTrue(careers.ancestors(id("warrior")).equals(Set.of(id("adventurer"))),
                        "Built-in career parent graph was not compiled");
                helper.assertTrue(careers.ancestors(id("scout")).equals(Set.of(id("adventurer"))),
                        "Scout career parent graph was not compiled");
                helper.assertTrue(careers.ancestors(id("vanguard")).equals(
                                Set.of(id("adventurer"), id("warrior"))),
                        "Tier-three career specialization graph was not compiled");
                helper.assertTrue(careers.conflictingLearnedCareers(
                                id("slayer"), Set.of(id("adventurer"), id("warrior"), id("vanguard")))
                                .equals(Set.of(id("vanguard"))),
                        "Tier-three sibling specialization reset was not compiled");
                var challenges = ActivityChallengeReloadListener.snapshot(server).orElseThrow();
                helper.assertTrue(challenges.size() == 10,
                        "Built-in Rovenfall activity challenge catalog was incomplete");
                var firstSteps = challenges.get(id("first_steps"));
                helper.assertTrue(firstSteps != null && firstSteps.currencyReward() == 100,
                        "First Steps challenge definition was not loaded");
                var legend = challenges.get(id("legend_of_rovenfall"));
                helper.assertTrue(legend != null
                                && legend.activityLevelRequirements().size() == 7
                                && legend.currencyReward() == 2_500,
                        "Legend of Rovenfall challenge definition was not loaded");
                var contracts = DailyContractReloadListener.snapshot(server).orElseThrow();
                helper.assertTrue(contracts.size() == 16,
                        "Built-in Rovenfall daily contract catalog was incomplete");
                var ironRush = contracts.get(id("iron_rush"));
                helper.assertTrue(ironRush != null && ironRush.requiredExperience() == 48,
                        "Iron Rush daily contract definition was not loaded");
                var wardenTrial = contracts.get(id("warden_trial"));
                helper.assertTrue(wardenTrial != null
                                && wardenTrial.targetId().equals(id("arena_warden"))
                                && wardenTrial.requiredExperience() == 80
                                && wardenTrial.currencyReward() == 300,
                        "Warden Trial daily contract definition was not loaded");
                var runeBreaker = contracts.get(id("rune_breaker"));
                helper.assertTrue(runeBreaker != null
                                && runeBreaker.targetId().equals(id("runebound_archer"))
                                && runeBreaker.requiredExperience() == 160
                                && runeBreaker.currencyReward() == 190,
                        "Rune Breaker daily contract definition was not loaded");
                var frozenFront = contracts.get(id("frozen_front"));
                helper.assertTrue(frozenFront != null
                                && frozenFront.targetId().equals(id("frostbound_reaver"))
                                && frozenFront.requiredExperience() == 180
                                && frozenFront.currencyReward() == 200,
                        "Frozen Front daily contract definition was not loaded");
                var sunkenPatrol = contracts.get(id("sunken_patrol"));
                helper.assertTrue(sunkenPatrol != null
                                && sunkenPatrol.targetId().equals(id("tidebound_raider"))
                                && sunkenPatrol.requiredExperience() == 210
                                && sunkenPatrol.currencyReward() == 210,
                        "Sunken Patrol daily contract definition was not loaded");
                var depthsWatch = contracts.get(id("depths_watch"));
                helper.assertTrue(depthsWatch != null
                                && depthsWatch.targetId().equals(id("deepstone_husk"))
                                && depthsWatch.requiredExperience() == 210
                                && depthsWatch.currencyReward() == 220,
                        "Depths Watch daily contract definition was not loaded");
                var frontierFeast = contracts.get(id("frontier_feast"));
                helper.assertTrue(frontierFeast != null
                                && frontierFeast.kind() == ActivityKind.COOKING_RESULT
                                && frontierFeast.targetId().equals(id("frontier_stew"))
                                && frontierFeast.requiredExperience() == 40
                                && frontierFeast.currencyReward() == 130,
                        "Frontier Feast daily contract definition was not loaded");
                var highlandHerd = contracts.get(id("highland_herd"));
                helper.assertTrue(highlandHerd != null
                                && highlandHerd.kind() == ActivityKind.BREEDING_COMPLETION
                                && highlandHerd.targetId().equals(Identifier.withDefaultNamespace("goat"))
                                && highlandHerd.requiredExperience() == 48
                                && highlandHerd.currencyReward() == 140,
                        "Highland Herd daily contract definition was not loaded");
                var highlandProvisions = contracts.get(id("highland_provisions"));
                helper.assertTrue(highlandProvisions != null
                                && highlandProvisions.kind() == ActivityKind.COOKING_RESULT
                                && highlandProvisions.targetId().equals(id("highland_cheese"))
                                && highlandProvisions.requiredExperience() == 48
                                && highlandProvisions.currencyReward() == 150,
                        "Highland Provisions daily contract definition was not loaded");
                var expeditions = WeeklyExpeditionReloadListener.snapshot(server).orElseThrow();
                helper.assertTrue(expeditions.size() == 5,
                        "Built-in Rovenfall weekly expedition catalog was incomplete");
                var supplyLines = expeditions.get(id("supply_lines"));
                helper.assertTrue(supplyLines != null
                                && supplyLines.dailyContractRequirements().size() == 6
                                && supplyLines.dailyContractRequirements().get(id("frontier_feast")) == 2
                                && supplyLines.dailyContractRequirements().get(id("highland_herd")) == 2
                                && supplyLines.dailyContractRequirements().get(id("highland_provisions")) == 2
                                && supplyLines.currencyReward() == 1_000,
                        "Supply Lines weekly expedition definition was not loaded");
                var wildernessCampaign = expeditions.get(id("wilderness_campaign"));
                helper.assertTrue(wildernessCampaign != null
                                && wildernessCampaign.dailyContractRequirements().size() == 16
                                && wildernessCampaign.currencyReward() == 3_900,
                        "Expanded Wilderness Campaign definition was not loaded");
                var frontierAnomalies = expeditions.get(id("frontier_anomalies"));
                helper.assertTrue(frontierAnomalies != null
                                && frontierAnomalies.dailyContractRequirements().size() == 5
                                && frontierAnomalies.currencyReward() == 1_450,
                        "Expanded Frontier Anomalies definition was not loaded");
                var wardenOath = expeditions.get(id("warden_oath"));
                helper.assertTrue(wardenOath != null
                                && wardenOath.dailyContractRequirements().equals(
                                        Map.of(id("warden_trial"), 3))
                                && wardenOath.currencyReward() == 1_000,
                        "Warden's Oath weekly expedition definition was not loaded");
                var reward = ActivityRewardReloadListener.get(
                        server, ActivityKind.EXPLORATION_DISCOVERY, plains);
                helper.assertTrue(reward.isPresent(),
                        "Built-in plains discovery reward was not loaded");
                helper.assertTrue(ActivityRewardReloadListener.get(
                                server,
                                ActivityKind.NATURAL_RESOURCE_BREAK,
                                Identifier.withDefaultNamespace("ancient_debris")).isPresent(),
                        "Expedition mining rewards were not loaded");
                helper.assertTrue(ActivityRewardReloadListener.get(
                                server,
                                ActivityKind.HUNTING_CONTRIBUTION,
                                id("runebound_archer")).isPresent(),
                        "Rovenfall mob hunting rewards were not loaded");
                helper.assertTrue(ActivityRewardReloadListener.get(
                                server,
                                ActivityKind.HUNTING_CONTRIBUTION,
                                id("cinder_wisp")).isPresent(),
                        "Cinder Wisp hunting rewards were not loaded");
                helper.assertTrue(ActivityRewardReloadListener.get(
                                server,
                                ActivityKind.HUNTING_CONTRIBUTION,
                                id("frostbound_reaver")).isPresent(),
                        "Frostbound Reaver hunting rewards were not loaded");
                helper.assertTrue(ActivityRewardReloadListener.get(
                                server,
                                ActivityKind.HUNTING_CONTRIBUTION,
                                id("tidebound_raider")).isPresent(),
                        "Tidebound Raider hunting rewards were not loaded");
                helper.assertTrue(ActivityRewardReloadListener.get(
                                server,
                                ActivityKind.HUNTING_CONTRIBUTION,
                                id("deepstone_husk")).isPresent(),
                        "Deepstone Husk hunting rewards were not loaded");
                helper.assertTrue(ActivityRewardReloadListener.get(
                                server,
                                ActivityKind.EXPLORATION_DISCOVERY,
                                Identifier.withDefaultNamespace("sulfur_caves")).isPresent(),
                        "Sulfur cave exploration rewards were not loaded");
                helper.assertTrue(ActivityRewardReloadListener.get(
                                server,
                                ActivityKind.COOKING_RESULT,
                                id("frontier_stew")).orElseThrow().definition().experience() == 5,
                        "Frontier Stew cooking reward was not loaded");
                helper.assertTrue(ActivityRewardReloadListener.get(
                                server,
                                ActivityKind.COOKING_RESULT,
                                id("highland_cheese")).orElseThrow().definition().experience() == 4,
                        "Highland Cheese cooking reward was not loaded");
                helper.assertTrue(ActivityRewardReloadListener.get(
                                server,
                                ActivityKind.BREEDING_COMPLETION,
                                Identifier.withDefaultNamespace("goat"))
                                .orElseThrow().definition().experience() == 12,
                        "Goat breeding farming reward was not loaded");

                UUID playerId = UUID.randomUUID();
                UUID evidenceId = UUID.randomUUID();
                long timestamp = System.currentTimeMillis();
                var observation = new ActivityObservation(
                        evidenceId,
                        timestamp,
                        playerId,
                        ActivityTrack.EXPLORATION,
                        ActivityKind.EXPLORATION_DISCOVERY,
                        Level.OVERWORLD,
                        0,
                        0,
                        plains,
                        "biome:" + plains,
                        1,
                        ActivityProvenance.explorationDiscovery());
                var state = PlatformSavedData.get(server);
                var result = ActivityProgressionService.award(
                        state, observation, reward.orElseThrow());
                helper.assertTrue(result.status() == ActivityProgressionService.Status.SUCCESS,
                        "Server-observed activity evidence did not award exploration experience");
                helper.assertTrue(ActivityProgressionService.award(
                                state, observation, reward.orElseThrow()).status()
                                == ActivityProgressionService.Status.DUPLICATE_EVIDENCE,
                        "Activity evidence replay was not idempotent");
                UUID careerPlayerId = UUID.randomUUID();
                UUID careerTransactionId = UUID.randomUUID();
                helper.assertTrue(CareerPromotionService.evaluate(
                                state, careers, careerPlayerId, id("adventurer"), Map.of()).allowed(),
                        "Free root career was not explainable as available");
                helper.assertTrue(CareerPromotionService.promote(
                                state, careers, careerPlayerId, id("adventurer"), Map.of(),
                                timestamp + 1, careerTransactionId).status()
                                == CareerPromotionService.Status.SUCCESS,
                        "Free root career promotion was not committed");
                helper.assertTrue(CareerPromotionService.promote(
                                state, careers, careerPlayerId, id("adventurer"), Map.of(),
                                timestamp + 2, careerTransactionId).status()
                                == CareerPromotionService.Status.DUPLICATE_TRANSACTION,
                        "Career promotion retry was not idempotent");
                UUID challengePlayerId = UUID.randomUUID();
                var challengeLevels = Map.of(
                        ActivityTrack.EXPLORATION, 1,
                        ActivityTrack.HUNTING, 1);
                var challengeClaim = ActivityChallengeService.claim(
                        state,
                        challengePlayerId,
                        id("first_steps"),
                        firstSteps,
                        challengeLevels,
                        timestamp + 3,
                        EconomyConfig.DEFAULT_INITIAL_BALANCE,
                        EconomyConfig.DEFAULT_MAXIMUM_BALANCE);
                helper.assertTrue(challengeClaim.status() == ActivityChallengeService.Status.SUCCESS,
                        "Eligible activity challenge reward was not committed");
                helper.assertTrue(ActivityChallengeService.claim(
                                state,
                                challengePlayerId,
                                id("first_steps"),
                                firstSteps,
                                challengeLevels,
                                timestamp + 4,
                                EconomyConfig.DEFAULT_INITIAL_BALANCE,
                                EconomyConfig.DEFAULT_MAXIMUM_BALANCE).status()
                                == ActivityChallengeService.Status.ALREADY_CLAIMED,
                        "Activity challenge reward replay was not idempotent");
                UUID contractPlayerId = UUID.randomUUID();
                long contractTimestamp = DailyContractService.periodStart(timestamp) + 60_000;
                var ironReward = ActivityRewardReloadListener.get(
                        server,
                        ActivityKind.NATURAL_RESOURCE_BREAK,
                        Identifier.withDefaultNamespace("iron_ore")).orElseThrow();
                UUID contractEvidenceId = UUID.randomUUID();
                var contractObservation = new ActivityObservation(
                        contractEvidenceId,
                        contractTimestamp,
                        contractPlayerId,
                        ActivityTrack.MINING,
                        ActivityKind.NATURAL_RESOURCE_BREAK,
                        WorldCombatService.WILDERNESS_DIMENSION,
                        0,
                        0,
                        Identifier.withDefaultNamespace("iron_ore"),
                        "resource:minecraft:iron_ore:daily_contract",
                        16,
                        new ActivityProvenance(true, false, false));
                helper.assertTrue(ActivityProgressionService.award(
                                state, contractObservation, ironReward).awarded(),
                        "Daily contract activity evidence was not awarded");
                var contractClaim = DailyContractService.claim(
                        state,
                        contractPlayerId,
                        id("iron_rush"),
                        ironRush,
                        contractTimestamp + 1,
                        EconomyConfig.DEFAULT_INITIAL_BALANCE,
                        EconomyConfig.DEFAULT_MAXIMUM_BALANCE);
                helper.assertTrue(contractClaim.status() == DailyContractService.Status.SUCCESS,
                        "Eligible daily contract reward was not committed");
                helper.assertTrue(DailyContractService.claim(
                                state,
                                contractPlayerId,
                                id("iron_rush"),
                                ironRush,
                                contractTimestamp + 2,
                                EconomyConfig.DEFAULT_INITIAL_BALANCE,
                                EconomyConfig.DEFAULT_MAXIMUM_BALANCE).status()
                                == DailyContractService.Status.ALREADY_CLAIMED,
                        "Daily contract reward replay was not idempotent");
                UUID expeditionPlayerId = UUID.randomUUID();
                long expeditionWeekStart = WeeklyExpeditionService.periodStart(timestamp);
                for (var requirement : supplyLines.dailyContractRequirements().entrySet()) {
                    for (int day = 0; day < requirement.getValue(); day++) {
                        long dayStart = expeditionWeekStart
                                + day * DailyContractService.PERIOD_MILLIS;
                        helper.assertTrue(EconomyService.award(
                                        state,
                                        expeditionPlayerId,
                                        1,
                                        "gametest daily contract completion evidence",
                                        dayStart + 1_000,
                                        DailyContractService.transactionId(
                                                expeditionPlayerId, requirement.getKey(), dayStart),
                                        EconomyConfig.DEFAULT_INITIAL_BALANCE,
                                        EconomyConfig.DEFAULT_MAXIMUM_BALANCE).status()
                                        == EconomyService.TransactionStatus.SUCCESS,
                                "Weekly expedition daily completion evidence was not committed");
                    }
                }
                long expeditionTimestamp = expeditionWeekStart
                        + 2 * DailyContractService.PERIOD_MILLIS + 60_000;
                var expeditionClaim = WeeklyExpeditionService.claim(
                        state,
                        expeditionPlayerId,
                        id("supply_lines"),
                        supplyLines,
                        expeditionTimestamp,
                        EconomyConfig.DEFAULT_INITIAL_BALANCE,
                        EconomyConfig.DEFAULT_MAXIMUM_BALANCE);
                helper.assertTrue(expeditionClaim.status() == WeeklyExpeditionService.Status.SUCCESS,
                        "Eligible weekly expedition reward was not committed");
                helper.assertTrue(WeeklyExpeditionService.claim(
                                state,
                                expeditionPlayerId,
                                id("supply_lines"),
                                supplyLines,
                                expeditionTimestamp + 1,
                                EconomyConfig.DEFAULT_INITIAL_BALANCE,
                                EconomyConfig.DEFAULT_MAXIMUM_BALANCE).status()
                                == WeeklyExpeditionService.Status.ALREADY_CLAIMED,
                        "Weekly expedition reward replay was not idempotent");
                var decoded = PlatformSavedData.CODEC.parse(NbtOps.INSTANCE,
                        PlatformSavedData.CODEC.encodeStart(NbtOps.INSTANCE, state).getOrThrow()).getOrThrow();
                helper.assertTrue(decoded.activityExperience(playerId, ActivityTrack.EXPLORATION) == 30,
                        "Activity experience did not survive persistence");
                helper.assertTrue(decoded.activityEvidence(evidenceId).isPresent(),
                        "Activity evidence receipt did not survive persistence");
                helper.assertTrue(decoded.activeCareer(careerPlayerId).equals(Optional.of(id("adventurer"))),
                        "Active career did not survive persistence");
                helper.assertTrue(decoded.careerPromotionReceipt(careerTransactionId).isPresent(),
                        "Career promotion receipt did not survive persistence");
                helper.assertTrue(decoded.economyBalance(challengePlayerId).orElseThrow() == 100,
                        "Activity challenge reward did not survive persistence");
                helper.assertTrue(decoded.economyReceipt(challengeClaim.evaluation().transactionId()).isPresent(),
                        "Activity challenge completion evidence did not survive persistence");
                helper.assertTrue(decoded.economyBalance(contractPlayerId).orElseThrow() == 80,
                        "Daily contract reward did not survive persistence");
                helper.assertTrue(decoded.economyReceipt(contractClaim.evaluation().transactionId()).isPresent(),
                        "Daily contract completion evidence did not survive persistence");
                helper.assertTrue(decoded.economyBalance(expeditionPlayerId).orElseThrow() == 1_012,
                        "Weekly expedition reward did not survive persistence");
                helper.assertTrue(decoded.economyReceipt(
                                expeditionClaim.evaluation().transactionId()).isPresent(),
                        "Weekly expedition completion evidence did not survive persistence");

                var player = (net.minecraft.server.level.ServerPlayer) helper.makeMockServerPlayer(
                        net.minecraft.world.level.GameType.SURVIVAL);
                UUID gameplayCareerTransaction = UUID.randomUUID();
                helper.assertTrue(CareerPromotionService.promote(
                                state, careers, player.getUUID(), id("adventurer"), Map.of(),
                                timestamp + 3, gameplayCareerTransaction).status()
                                == CareerPromotionService.Status.SUCCESS,
                        "GameTest player could not select the all-track root career");
                long careerBefore = state.playerCareerState(player.getUUID()).experience(id("adventurer"));
                long cookingBefore = state.activityExperience(player.getUUID(), ActivityTrack.COOKING);
                NeoForge.EVENT_BUS.post(new PlayerEvent.ItemCraftedEvent(
                        player,
                        RovenfallItems.FRONTIER_STEW.toStack(2),
                        new net.minecraft.world.SimpleContainer(5)));
                helper.assertTrue(
                        state.activityExperience(player.getUUID(), ActivityTrack.COOKING) == cookingBefore + 10,
                        "Crafting did not create quantity-aware Frontier Stew cooking evidence");
                NeoForge.EVENT_BUS.post(new PlayerEvent.ItemCraftedEvent(
                        player,
                        RovenfallItems.HIGHLAND_CHEESE.toStack(2),
                        new net.minecraft.world.SimpleContainer(3)));
                helper.assertTrue(
                        state.activityExperience(player.getUUID(), ActivityTrack.COOKING) == cookingBefore + 18,
                        "Crafting did not create quantity-aware Highland Cheese cooking evidence");
                NeoForge.EVENT_BUS.post(new PlayerEvent.ItemSmeltedEvent(
                        player, new ItemStack(Items.COOKED_BEEF, 3), 3));
                helper.assertTrue(
                        state.activityExperience(player.getUUID(), ActivityTrack.COOKING) == cookingBefore + 24,
                        "Furnace extraction did not create quantity-aware cooking evidence");

                var activityLevel = server.getLevel(Level.NETHER);
                helper.assertTrue(activityLevel != null,
                        "Nether level was unavailable for activity event tests");
                BlockPos naturalOre = new BlockPos(45_000, 64, 45_000);
                activityLevel.setBlock(naturalOre, Blocks.DIAMOND_ORE.defaultBlockState(), 3);
                long miningBefore = state.activityExperience(player.getUUID(), ActivityTrack.MINING);
                var naturalBreak = new BreakBlockEvent(
                        activityLevel, naturalOre, Blocks.DIAMOND_ORE.defaultBlockState(), player);
                NeoForge.EVENT_BUS.post(naturalBreak);
                helper.assertTrue(!naturalBreak.isCanceled(),
                        "Non-Hub natural ore break was canceled");
                helper.assertTrue(
                        state.activityExperience(player.getUUID(), ActivityTrack.MINING) == miningBefore + 8,
                        "Natural diamond ore did not create mining evidence");

                BlockPos placedOre = naturalOre.east();
                activityLevel.setBlock(placedOre, Blocks.DIAMOND_ORE.defaultBlockState(), 3);
                state.observeActivityResourcePlacement(activityLevel.dimension(), placedOre, true);
                NeoForge.EVENT_BUS.post(new BreakBlockEvent(
                        activityLevel, placedOre, Blocks.DIAMOND_ORE.defaultBlockState(), player));
                helper.assertTrue(
                        state.activityExperience(player.getUUID(), ActivityTrack.MINING) == miningBefore + 8,
                        "Player-placed diamond ore awarded mining experience");
                helper.assertTrue(!state.isActivityResourcePlayerPlaced(activityLevel.dimension(), placedOre),
                        "Broken player-placed ore provenance was not cleared");

                BlockPos buildingPos = naturalOre.south(2);
                activityLevel.setBlock(buildingPos, Blocks.AIR.defaultBlockState(), 3);
                var buildingSnapshot = net.neoforged.neoforge.common.util.BlockSnapshot.create(
                        activityLevel.dimension(), activityLevel, buildingPos);
                activityLevel.setBlock(buildingPos, Blocks.COBBLESTONE.defaultBlockState(), 3);
                long buildingBefore = state.activityExperience(player.getUUID(), ActivityTrack.BUILDING);
                var placeEvent = new BlockEvent.EntityPlaceEvent(
                        buildingSnapshot, activityLevel.getBlockState(buildingPos.below()), player);
                NeoForge.EVENT_BUS.post(placeEvent);
                helper.assertTrue(!placeEvent.isCanceled(),
                        "Unclaimed GameTest building placement was canceled");
                helper.assertTrue(
                        state.activityExperience(player.getUUID(), ActivityTrack.BUILDING) == buildingBefore + 1,
                        "Cobblestone placement did not create building evidence");

                BlockPos cropPos = naturalOre.south(3);
                var matureWheat = Blocks.WHEAT.defaultBlockState()
                        .setValue(net.minecraft.world.level.block.CropBlock.AGE,
                                net.minecraft.world.level.block.CropBlock.MAX_AGE);
                activityLevel.setBlock(cropPos, matureWheat, 3);
                long farmingBefore = state.activityExperience(player.getUUID(), ActivityTrack.FARMING);
                NeoForge.EVENT_BUS.post(new BreakBlockEvent(activityLevel, cropPos, matureWheat, player));
                helper.assertTrue(
                        state.activityExperience(player.getUUID(), ActivityTrack.FARMING) == farmingBefore + 4,
                        "Mature wheat harvest did not create farming evidence");

                var parentA = helper.spawnWithNoFreeWill(EntityTypes.COW, new BlockPos(1, 2, 0));
                var parentB = helper.spawnWithNoFreeWill(EntityTypes.COW, new BlockPos(2, 2, 0));
                var child = helper.spawnWithNoFreeWill(EntityTypes.COW, new BlockPos(3, 2, 0));
                parentA.setInLove(player);
                parentB.setInLove(player);
                NeoForge.EVENT_BUS.post(new net.neoforged.neoforge.event.entity.living.BabyEntitySpawnEvent(
                        parentA, parentB, child));
                helper.assertTrue(
                        state.activityExperience(player.getUUID(), ActivityTrack.FARMING) == farmingBefore + 14,
                        "Player-caused cow breeding did not create farming evidence");

                long combatBefore = state.activityExperience(player.getUUID(), ActivityTrack.COMBAT);
                long huntingBefore = state.activityExperience(player.getUUID(), ActivityTrack.HUNTING);
                var zombie = helper.spawnWithNoFreeWill(EntityTypes.ZOMBIE, new BlockPos(0, 2, 0));
                var playerDamage = level.damageSources().playerAttack(player);
                var damageContainer = new net.neoforged.neoforge.common.damagesource.DamageContainer(
                        playerDamage, 20.0F);
                damageContainer.captureInflictedDamage();
                NeoForge.EVENT_BUS.post(new net.neoforged.neoforge.event.entity.living.LivingDamageEvent.Post(
                        zombie, damageContainer));
                var deathEvent = new net.neoforged.neoforge.event.entity.living.LivingDeathEvent(
                        zombie, playerDamage);
                NeoForge.EVENT_BUS.post(deathEvent);
                helper.assertTrue(!deathEvent.isCanceled(),
                        "Synthetic completed zombie death was canceled");
                helper.assertTrue(
                        state.activityExperience(player.getUUID(), ActivityTrack.COMBAT) == combatBefore + 20,
                        "Applied zombie damage did not create capped combat contribution evidence");
                helper.assertTrue(
                        state.activityExperience(player.getUUID(), ActivityTrack.HUNTING) == huntingBefore + 20,
                        "Zombie death did not award the recorded hunting contribution");
                helper.assertTrue(
                        state.playerCareerState(player.getUUID()).experience(id("adventurer"))
                                == careerBefore + 87,
                        "Server activity events did not atomically advance the active career");
                var decodedEvents = PlatformSavedData.CODEC.parse(
                        NbtOps.INSTANCE,
                        PlatformSavedData.CODEC.encodeStart(NbtOps.INSTANCE, state).getOrThrow()).getOrThrow();
                helper.assertTrue(
                        decodedEvents.playerCareerState(player.getUUID()).experience(id("adventurer"))
                                == careerBefore + 87,
                        "Activity-derived career experience did not survive persistence");

                long activeSkillTrainingStart = Math.max(0, timestamp - 18L * 61_000L);
                for (int index = 0; index < 18; index++) {
                    long observedAt = activeSkillTrainingStart + index * 61_000L;
                    var training = new ActivityObservation(
                            UUID.randomUUID(),
                            observedAt,
                            player.getUUID(),
                            ActivityTrack.EXPLORATION,
                            ActivityKind.EXPLORATION_DISCOVERY,
                            Level.OVERWORLD,
                            0,
                            0,
                            plains,
                            "gametest:active_skill_training_" + index,
                            1,
                            ActivityProvenance.explorationDiscovery());
                    helper.assertTrue(ActivityProgressionService.award(
                                    state, training, reward.orElseThrow(), careers).status()
                                    == ActivityProgressionService.Status.SUCCESS,
                            "Active skill training evidence was not awarded");
                }
                long activeSkillTimestamp = timestamp + 10_000;
                UUID activeSkillUnlockTransaction = UUID.randomUUID();
                helper.assertTrue(CareerSkillService.unlock(
                                state,
                                careers,
                                player.getUUID(),
                                id("well_traveled"),
                                activeSkillTimestamp,
                                activeSkillUnlockTransaction).status()
                                == CareerSkillService.Status.SUCCESS,
                        "GameTest player could not unlock an active career skill");
                helper.assertTrue(ActiveSkillService.equip(
                                state,
                                careers,
                                player.getUUID(),
                                1,
                                id("well_traveled"),
                                activeSkillTimestamp + 1).status()
                                == ActiveSkillService.Status.SUCCESS,
                        "Unlocked active skill could not be equipped");
                var activeUse = ActiveSkillService.use(
                        state,
                        careers,
                        player.getUUID(),
                        1,
                        activeSkillTimestamp + 2,
                        net.minecraft.core.registries.BuiltInRegistries.MOB_EFFECT::containsKey);
                helper.assertTrue(activeUse.status() == ActiveSkillService.Status.SUCCESS,
                        "Server-authoritative active skill use failed");
                helper.assertTrue(activeUse.evaluation().activeDefinition().orElseThrow().effectId()
                                .equals(Identifier.withDefaultNamespace("speed")),
                        "Active skill use did not retain its validated status effect");
                helper.assertTrue(ActiveSkillService.use(
                                state,
                                careers,
                                player.getUUID(),
                                1,
                                activeSkillTimestamp + 3,
                                net.minecraft.core.registries.BuiltInRegistries.MOB_EFFECT::containsKey).status()
                                == ActiveSkillService.Status.COOLDOWN,
                        "Immediate active skill packet replay bypassed cooldown validation");
                var decodedActiveSkill = PlatformSavedData.CODEC.parse(
                        NbtOps.INSTANCE,
                        PlatformSavedData.CODEC.encodeStart(NbtOps.INSTANCE, state).getOrThrow()).getOrThrow();
                helper.assertTrue(decodedActiveSkill.playerCareerState(player.getUUID())
                                .activeSkills().slot(1).equals(Optional.of(id("well_traveled"))),
                        "Active skill slot did not survive persistence");
                helper.assertTrue(decodedActiveSkill.playerCareerState(player.getUUID())
                                .activeSkills().cooldownReadyAt(id("well_traveled"))
                                > activeSkillTimestamp + 3,
                        "Active skill cooldown did not survive persistence");
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

                var outfitter = ShopTemplateReloadListener.get(
                        helper.getLevel().getServer(), id("wilderness_outfitter"));
                helper.assertTrue(outfitter.isPresent(), "Wilderness Outfitter shop template was not loaded");
                helper.assertTrue(outfitter.orElseThrow().offers().size() == 21,
                        "Wilderness Outfitter did not retain all twenty-one offers");
                var frontierStew = outfitter.orElseThrow().offers().stream()
                        .filter(candidate -> candidate.id().equals(id("outfitter_frontier_stew")))
                        .findFirst()
                        .orElseThrow();
                helper.assertTrue(frontierStew.item().getItem() == RovenfallItems.FRONTIER_STEW.get()
                                && frontierStew.item().get(net.minecraft.core.component.DataComponents.FOOD) != null
                                && frontierStew.buyPrice().orElseThrow() == 24L
                                && frontierStew.sellPrice().orElseThrow() == 12L
                                && frontierStew.stock().initial().orElseThrow() == 8L
                                && frontierStew.stock().maximum().orElseThrow() == 16L,
                        "Wilderness Outfitter Frontier Stew policy was not retained");
                var frontierFeed = outfitter.orElseThrow().offers().stream()
                        .filter(candidate -> candidate.id().equals(id("outfitter_frontier_feed")))
                        .findFirst()
                        .orElseThrow();
                helper.assertTrue(frontierFeed.item().getItem() == RovenfallItems.FRONTIER_FEED.get()
                                && frontierFeed.item().getCount() == 4
                                && frontierFeed.buyPrice().orElseThrow() == 12L
                                && frontierFeed.sellPrice().orElseThrow() == 6L
                                && frontierFeed.stock().initial().orElseThrow() == 16L
                                && frontierFeed.stock().maximum().orElseThrow() == 32L
                                && frontierFeed.stock().restockAmount().orElseThrow() == 4L
                                && frontierFeed.stock().restockIntervalTicks().orElseThrow() == 1_200L,
                        "Wilderness Outfitter Frontier Feed policy was not retained");
                var highlandCheeseOffer = outfitter.orElseThrow().offers().stream()
                        .filter(candidate -> candidate.id().equals(id("outfitter_highland_cheese")))
                        .findFirst()
                        .orElseThrow();
                helper.assertTrue(highlandCheeseOffer.item().getItem() == RovenfallItems.HIGHLAND_CHEESE.get()
                                && highlandCheeseOffer.item().getCount() == 2
                                && highlandCheeseOffer.buyPrice().orElseThrow() == 20L
                                && highlandCheeseOffer.sellPrice().orElseThrow() == 10L
                                && highlandCheeseOffer.stock().initial().orElseThrow() == 8L
                                && highlandCheeseOffer.stock().maximum().orElseThrow() == 16L
                                && highlandCheeseOffer.stock().restockAmount().orElseThrow() == 2L
                                && highlandCheeseOffer.stock().restockIntervalTicks().orElseThrow() == 2_400L,
                        "Wilderness Outfitter Highland Cheese policy was not retained");
                var mireguard = outfitter.orElseThrow().offers().stream()
                        .filter(candidate -> candidate.id().equals(id("outfitter_mireguard_tonic")))
                        .findFirst()
                        .orElseThrow();
                helper.assertTrue(mireguard.item().getItem() == RovenfallItems.MIREGUARD_TONIC.get(),
                        "Wilderness Outfitter did not resolve the custom Mireguard Tonic item");
                helper.assertTrue(mireguard.item().get(net.minecraft.core.component.DataComponents.CONSUMABLE) != null,
                        "Wilderness Outfitter tonic lost its default consumable component");
                helper.assertTrue(mireguard.buyPrice().orElseThrow() == 28L
                                && mireguard.sellPrice().orElseThrow() == 14L,
                        "Wilderness Outfitter tonic prices were not retained");
                helper.assertTrue(mireguard.stock().initial().orElseThrow() == 4L
                                && mireguard.stock().maximum().orElseThrow() == 12L
                                && mireguard.stock().restockAmount().orElseThrow() == 2L
                                && mireguard.stock().restockIntervalTicks().orElseThrow() == 2_400L,
                        "Wilderness Outfitter tonic stock policy was not retained");
                var cinderCore = outfitter.orElseThrow().offers().stream()
                        .filter(candidate -> candidate.id().equals(id("outfitter_cinder_core")))
                        .findFirst()
                        .orElseThrow();
                helper.assertTrue(cinderCore.item().getItem() == RovenfallItems.CINDER_CORE.get()
                                && cinderCore.buyPrice().isEmpty()
                                && cinderCore.sellPrice().orElseThrow() == 14L
                                && cinderCore.stock().unlimited(),
                        "Wilderness Outfitter Cinder Core buyback policy was not retained");
                var ashveil = outfitter.orElseThrow().offers().stream()
                        .filter(candidate -> candidate.id().equals(id("outfitter_ashveil_tonic")))
                        .findFirst()
                        .orElseThrow();
                helper.assertTrue(ashveil.item().getItem() == RovenfallItems.ASHVEIL_TONIC.get()
                                && ashveil.buyPrice().orElseThrow() == 32L
                                && ashveil.sellPrice().orElseThrow() == 16L,
                        "Wilderness Outfitter Ashveil Tonic policy was not retained");
                var runeboundFragment = outfitter.orElseThrow().offers().stream()
                        .filter(candidate -> candidate.id().equals(id("outfitter_runebound_fragment")))
                        .findFirst()
                        .orElseThrow();
                helper.assertTrue(runeboundFragment.item().getItem() == RovenfallItems.RUNEBOUND_FRAGMENT.get()
                                && runeboundFragment.buyPrice().isEmpty()
                                && runeboundFragment.sellPrice().orElseThrow() == 12L
                                && runeboundFragment.stock().unlimited(),
                        "Wilderness Outfitter Runebound Fragment buyback policy was not retained");
                var froststep = outfitter.orElseThrow().offers().stream()
                        .filter(candidate -> candidate.id().equals(id("outfitter_froststep_tonic")))
                        .findFirst()
                        .orElseThrow();
                helper.assertTrue(froststep.item().getItem() == RovenfallItems.FROSTSTEP_TONIC.get()
                                && froststep.buyPrice().orElseThrow() == 38L
                                && froststep.sellPrice().orElseThrow() == 19L,
                        "Wilderness Outfitter Froststep Tonic policy was not retained");
                var frostboundShard = outfitter.orElseThrow().offers().stream()
                        .filter(candidate -> candidate.id().equals(id("outfitter_frostbound_shard")))
                        .findFirst()
                        .orElseThrow();
                helper.assertTrue(frostboundShard.item().getItem() == RovenfallItems.FROSTBOUND_SHARD.get()
                                && frostboundShard.buyPrice().isEmpty()
                                && frostboundShard.sellPrice().orElseThrow() == 11L
                                && frostboundShard.stock().unlimited(),
                        "Wilderness Outfitter Frostbound Shard buyback policy was not retained");
                var tidebreath = outfitter.orElseThrow().offers().stream()
                        .filter(candidate -> candidate.id().equals(id("outfitter_tidebreath_tonic")))
                        .findFirst()
                        .orElseThrow();
                helper.assertTrue(tidebreath.item().getItem() == RovenfallItems.TIDEBREATH_TONIC.get()
                                && tidebreath.buyPrice().orElseThrow() == 42L
                                && tidebreath.sellPrice().orElseThrow() == 21L,
                        "Wilderness Outfitter Tidebreath Tonic policy was not retained");
                var tideboundScale = outfitter.orElseThrow().offers().stream()
                        .filter(candidate -> candidate.id().equals(id("outfitter_tidebound_scale")))
                        .findFirst()
                        .orElseThrow();
                helper.assertTrue(tideboundScale.item().getItem() == RovenfallItems.TIDEBOUND_SCALE.get()
                                && tideboundScale.buyPrice().isEmpty()
                                && tideboundScale.sellPrice().orElseThrow() == 13L
                                && tideboundScale.stock().unlimited(),
                        "Wilderness Outfitter Tidebound Scale buyback policy was not retained");
                var deepsight = outfitter.orElseThrow().offers().stream()
                        .filter(candidate -> candidate.id().equals(id("outfitter_deepsight_tonic")))
                        .findFirst()
                        .orElseThrow();
                helper.assertTrue(deepsight.item().getItem() == RovenfallItems.DEEPSIGHT_TONIC.get()
                                && deepsight.buyPrice().orElseThrow() == 44L
                                && deepsight.sellPrice().orElseThrow() == 22L,
                        "Wilderness Outfitter Deepsight Tonic policy was not retained");
                var deepstoneCore = outfitter.orElseThrow().offers().stream()
                        .filter(candidate -> candidate.id().equals(id("outfitter_deepstone_core")))
                        .findFirst()
                        .orElseThrow();
                helper.assertTrue(deepstoneCore.item().getItem() == RovenfallItems.DEEPSTONE_CORE.get()
                                && deepstoneCore.buyPrice().isEmpty()
                                && deepstoneCore.sellPrice().orElseThrow() == 15L
                                && deepstoneCore.stock().unlimited(),
                        "Wilderness Outfitter Deepstone Core buyback policy was not retained");
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
        event.addRetainedListener(QuestDefinitionReloadListener.KEY, questDefinitions);
        event.addRetainedListener(ExplorationDefinitionReloadListener.KEY, explorationDefinitions);
        event.addRetainedListener(ActivityRewardReloadListener.KEY, activityRewards);
        event.addRetainedListener(ActivityLevelReloadListener.KEY, activityLevels);
        event.addRetainedListener(ActivityChallengeReloadListener.KEY, activityChallenges);
        event.addRetainedListener(DailyContractReloadListener.KEY, dailyContracts);
        event.addRetainedListener(WeeklyExpeditionReloadListener.KEY, weeklyExpeditions);
        event.addRetainedListener(CareerDefinitionReloadListener.KEY, careerDefinitions);
        event.addRetainedListener(MobMutationReloadListener.KEY, mobMutations);
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

    private static boolean lootTableCanDrop(
            GameTestHelper helper,
            String entityPath,
            net.minecraft.world.entity.Entity entity,
            net.minecraft.world.item.Item expectedItem) {
        var key = net.minecraft.resources.ResourceKey.create(
                net.minecraft.core.registries.Registries.LOOT_TABLE,
                id("entities/" + entityPath));
        var params = new net.minecraft.world.level.storage.loot.LootParams.Builder(helper.getLevel())
                .withParameter(net.minecraft.world.level.storage.loot.parameters.LootContextParams.THIS_ENTITY,
                        entity)
                .withParameter(net.minecraft.world.level.storage.loot.parameters.LootContextParams.ORIGIN,
                        entity.position())
                .withParameter(net.minecraft.world.level.storage.loot.parameters.LootContextParams.DAMAGE_SOURCE,
                        helper.getLevel().damageSources().generic())
                .create(net.minecraft.world.level.storage.loot.parameters.LootContextParamSets.ENTITY);
        var table = helper.getLevel().getServer().reloadableRegistries().getLootTable(key);
        return java.util.stream.LongStream.range(0, 64).anyMatch(seed ->
                table.getRandomItems(params, seed).stream()
                        .anyMatch(stack -> stack.getItem() == expectedItem));
    }
}
