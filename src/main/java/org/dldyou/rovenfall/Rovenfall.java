package org.dldyou.rovenfall;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.gametest.framework.BuiltinTestFunctions;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.resources.Identifier;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddServerReloadListenersEvent;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import org.dldyou.rovenfall.administration.EconomyConfig;
import org.dldyou.rovenfall.administration.EconomyService;
import org.dldyou.rovenfall.administration.AdministrationService;
import org.dldyou.rovenfall.administration.PlatformSavedData;
import org.dldyou.rovenfall.administration.PlayerRecordService;
import org.dldyou.rovenfall.administration.RovenfallCommands;
import org.dldyou.rovenfall.administration.ShopInstanceService;
import org.dldyou.rovenfall.definition.TestDefinitionReloadListener;
import org.dldyou.rovenfall.economy.ShopTemplateReloadListener;
import org.dldyou.rovenfall.economy.ShopInstance;

@Mod(Rovenfall.MOD_ID)
public final class Rovenfall {
    public static final String MOD_ID = "rovenfall";
    private final ShopTemplateReloadListener shopTemplates = new ShopTemplateReloadListener();

    public Rovenfall(IEventBus modBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.SERVER, EconomyConfig.SPEC);
        modBus.addListener(this::registerGameTests);
        NeoForge.EVENT_BUS.addListener(RovenfallCommands::register);
        NeoForge.EVENT_BUS.addListener(EconomyService::onPlayerLoggedIn);
        NeoForge.EVENT_BUS.addListener(PlayerRecordService::onPlayerLoggedIn);
        NeoForge.EVENT_BUS.addListener(this::addServerReloadListeners);
        NeoForge.EVENT_BUS.addListener(shopTemplates::onDefaultDataComponentsBound);
    }

    private void registerGameTests(RegisterGameTestsEvent event) {
        var environment = event.registerEnvironment(id("empty"), new TestEnvironmentDefinition.AllOf(List.of()));
        var testData = new TestData<>(environment, Identifier.withDefaultNamespace("empty"), 1, 0, true);
        event.registerTest(id("foundation"), new FunctionGameTestInstance(BuiltinTestFunctions.ALWAYS_PASS, testData));
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
    }

    private void addServerReloadListeners(AddServerReloadListenersEvent event) {
        event.addRetainedListener(TestDefinitionReloadListener.KEY, new TestDefinitionReloadListener());
        event.addRetainedListener(ShopTemplateReloadListener.KEY, shopTemplates);
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }
}
