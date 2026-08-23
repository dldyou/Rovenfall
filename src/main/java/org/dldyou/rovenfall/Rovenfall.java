package org.dldyou.rovenfall;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.gametest.framework.BuiltinTestFunctions;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
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
import org.dldyou.rovenfall.administration.ShopTradeService;
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
                helper.assertTrue(EconomyService.award(
                                decodedTrade, player.getUUID(), 1, "gametest persisted retry",
                                timestamp + 3, purchaseId, 0, Long.MAX_VALUE).status()
                                == EconomyService.TransactionStatus.DUPLICATE_TRANSACTION,
                        "Committed trade retry ID did not survive persistence");

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
    }

    private void addServerReloadListeners(AddServerReloadListenersEvent event) {
        event.addRetainedListener(TestDefinitionReloadListener.KEY, new TestDefinitionReloadListener());
        event.addRetainedListener(ShopTemplateReloadListener.KEY, shopTemplates);
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }
}
