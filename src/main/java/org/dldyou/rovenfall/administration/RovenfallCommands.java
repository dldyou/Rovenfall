package org.dldyou.rovenfall.administration;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.logging.LogUtils;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.dldyou.rovenfall.activities.ActivityTrack;
import org.dldyou.rovenfall.activities.ActivityLevelReloadListener;
import org.dldyou.rovenfall.activities.ActivityChallengeReloadListener;
import org.dldyou.rovenfall.activities.DailyContractReloadListener;
import org.dldyou.rovenfall.activities.WeeklyExpeditionReloadListener;
import org.dldyou.rovenfall.careers.CareerCatalog;
import org.dldyou.rovenfall.careers.CareerDefinitionReloadListener;
import org.dldyou.rovenfall.economy.ShopInstance;
import org.dldyou.rovenfall.economy.ShopTemplateReloadListener;
import org.dldyou.rovenfall.economy.ShopTemplateSnapshot;
import org.dldyou.rovenfall.claims.ClaimConfig;
import org.dldyou.rovenfall.claims.ClaimKey;
import org.dldyou.rovenfall.claims.ClaimRegionPolicy;
import org.dldyou.rovenfall.claims.ClaimRole;
import org.dldyou.rovenfall.claims.ClaimSettings;
import org.dldyou.rovenfall.quest.QuestProgressRuntime;
import org.dldyou.rovenfall.rpg.RpgCommands;
import org.dldyou.rovenfall.world.ProtectedRegion;
import org.dldyou.rovenfall.world.PortalDefinition;
import org.dldyou.rovenfall.world.WorldTopology;
import org.slf4j.Logger;
import org.dldyou.rovenfall.worlds.Portal;
import org.dldyou.rovenfall.worlds.SafeArrivalResolver;
import org.dldyou.rovenfall.mobs.BossEvents;

public final class RovenfallCommands {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int AUDIT_PAGE_SIZE = 10;
    private static final int PROTECTED_REGION_PAGE_SIZE = 10;
    private static final int PORTAL_PAGE_SIZE = 10;

    private RovenfallCommands() {
    }

    public static void register(RegisterCommandsEvent event) {
        var playerHelpCommand = Commands.literal("help")
                .executes(context -> showHelp(context.getSource(), false));
        var adminHelpCommand = Commands.literal("help")
                .executes(context -> showHelp(context.getSource(), true));

        var roleCommand = Commands.literal("role")
                .then(Commands.literal("set")
                        .then(Commands.argument("player", EntityArgument.player())
                                .then(Commands.argument("role", StringArgumentType.word())
                                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(AdminRole.ids(), builder))
                                        .then(Commands.argument("reason", StringArgumentType.greedyString())
                                                .executes(context -> setRole(
                                                        context.getSource(),
                                                        EntityArgument.getPlayer(context, "player"),
                                                        StringArgumentType.getString(context, "role"),
                                                StringArgumentType.getString(context, "reason")))))));

        var helpCommand = Commands.literal("help")
                .executes(context -> showAdminHelp(context.getSource()));

        var guiCommand = Commands.literal("gui")
                .executes(context -> openAdministrationGui(context.getSource()));

        var auditCommand = Commands.literal("audit")
                .then(Commands.literal("list")
                        .executes(context -> listAudit(context.getSource(), 0))
                        .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                .executes(context -> listAudit(
                                        context.getSource(),
                                        IntegerArgumentType.getInteger(context, "page") - 1))))
                .then(Commands.literal("gui")
                        .executes(context -> openAuditGui(context.getSource(), 0))
                        .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                .executes(context -> openAuditGui(
                                        context.getSource(),
                                        IntegerArgumentType.getInteger(context, "page") - 1))))
                .then(Commands.literal("search")
                        .then(Commands.argument("query", StringArgumentType.greedyString())
                                .executes(context -> searchAudit(
                                        context.getSource(), 0,
                                        StringArgumentType.getString(context, "query"))))
                        .then(Commands.literal("page")
                                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                        .then(Commands.argument("query", StringArgumentType.greedyString())
                                                .executes(context -> searchAudit(
                                                        context.getSource(),
                                                        IntegerArgumentType.getInteger(context, "page") - 1,
                                                        StringArgumentType.getString(context, "query")))))))
                .then(Commands.literal("export")
                        .then(Commands.argument("transaction_id", UuidArgument.uuid())
                                .then(Commands.argument("reason", StringArgumentType.string())
                                        .then(Commands.argument("query", StringArgumentType.greedyString())
                                                .executes(context -> exportAudit(
                                                        context.getSource(),
                                                        UuidArgument.getUuid(context, "transaction_id"),
                                                        StringArgumentType.getString(context, "reason"),
                                                        StringArgumentType.getString(context, "query")))))));

        var searchCommand = Commands.literal("search")
                .then(Commands.argument("scope", StringArgumentType.word())
                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                AdminSearchService.Scope.ids(), builder))
                        .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                .then(Commands.argument("query", StringArgumentType.greedyString())
                                        .executes(context -> openAdminSearch(
                                                context.getSource(),
                                                StringArgumentType.getString(context, "scope"),
                                                IntegerArgumentType.getInteger(context, "page") - 1,
                                                StringArgumentType.getString(context, "query"))))));

        var reverseCommand = Commands.literal("reverse")
                .then(Commands.argument("original_transaction_id", UuidArgument.uuid())
                        .then(Commands.argument("reversal_transaction_id", UuidArgument.uuid())
                                .then(Commands.argument("reason", StringArgumentType.greedyString())
                                        .executes(context -> reverseTargetedTransaction(
                                                context.getSource(),
                                                UuidArgument.getUuid(context, "original_transaction_id"),
                                                UuidArgument.getUuid(context, "reversal_transaction_id"),
                                                StringArgumentType.getString(context, "reason"))))));

        var snapshotCommand = Commands.literal("snapshot")
                .then(Commands.literal("create")
                        .then(Commands.argument("reason", StringArgumentType.greedyString())
                                .executes(context -> createSnapshot(
                                        context.getSource(),
                                        StringArgumentType.getString(context, "reason")))))
                .then(Commands.literal("restore")
                        .then(Commands.argument("snapshot_id", UuidArgument.uuid())
                                .then(Commands.argument("transaction_id", UuidArgument.uuid())
                                        .then(Commands.argument("reason", StringArgumentType.greedyString())
                                                .executes(context -> restoreSnapshot(
                                                        context.getSource(),
                                                        UuidArgument.getUuid(context, "snapshot_id"),
                                                        UuidArgument.getUuid(context, "transaction_id"),
                                                        StringArgumentType.getString(context, "reason")))))));

        var restartWildernessCommand = Commands.literal("wilderness-restart")
                .requires(RovenfallCommands::canResetWilderness)
                .then(Commands.literal("reset")
                        .then(Commands.literal("warn")
                                .then(Commands.argument("reason", StringArgumentType.greedyString())
                                        .executes(context -> warnWildernessReset(
                                                context.getSource(),
                                                StringArgumentType.getString(context, "reason")))))
                        .then(Commands.literal("irreversible")
                                .then(Commands.argument("warning_id", UuidArgument.uuid())
                                        .then(Commands.argument("transaction_id", UuidArgument.uuid())
                                                .then(Commands.argument("reason", StringArgumentType.greedyString())
                                                        .executes(context -> resetWilderness(
                                                                context.getSource(),
                                                                UuidArgument.getUuid(context, "warning_id"),
                                                                UuidArgument.getUuid(context, "transaction_id"),
                                                                StringArgumentType.getString(context, "reason"))))))))
                .then(Commands.literal("restore")
                        .then(Commands.literal("irreversible")
                                .then(Commands.argument("snapshot_id", UuidArgument.uuid())
                                        .then(Commands.argument("transaction_id", UuidArgument.uuid())
                                                .then(Commands.argument("reason", StringArgumentType.greedyString())
                                                        .executes(context -> restoreWilderness(
                                                                context.getSource(),
                                                                UuidArgument.getUuid(context, "snapshot_id"),
                                                                UuidArgument.getUuid(context, "transaction_id"),
                                                                StringArgumentType.getString(context, "reason"))))))));

        var wildernessCommand = Commands.literal("wilderness")
                .then(Commands.literal("reset")
                        .then(Commands.literal("confirm")
                                .then(Commands.argument("reason", StringArgumentType.greedyString())
                                        .executes(context -> resetWilderness(
                                                context.getSource(),
                                                StringArgumentType.getString(context, "reason"))))))
                .then(Commands.literal("status")
                        .executes(context -> viewWildernessReset(context.getSource(), null))
                        .then(Commands.argument("operation_id", UuidArgument.uuid())
                                .executes(context -> viewWildernessReset(
                                        context.getSource(),
                                        UuidArgument.getUuid(context, "operation_id")))));

        var arenaBossCommand = Commands.literal("arena-boss")
                .then(Commands.literal("start")
                        .then(Commands.argument(
                                        "radius",
                                        IntegerArgumentType.integer(
                                                org.dldyou.rovenfall.mobs.BossEncounter.MIN_RADIUS,
                                                org.dldyou.rovenfall.mobs.BossEncounter.MAX_RADIUS))
                                .then(Commands.argument("encounter_id", UuidArgument.uuid())
                                        .then(Commands.argument("reason", StringArgumentType.greedyString())
                                                .executes(context -> startBossEncounter(
                                                        context.getSource(),
                                                        IntegerArgumentType.getInteger(context, "radius"),
                                                        UuidArgument.getUuid(context, "encounter_id"),
                                                        StringArgumentType.getString(context, "reason")))))))
                .then(Commands.literal("status")
                        .executes(context -> viewBossEncounter(context.getSource())));

        var economyCommand = Commands.literal("economy")
                .then(Commands.literal("grant")
                        .then(Commands.argument("player", EntityArgument.player())
                                .then(Commands.argument("amount", LongArgumentType.longArg(1))
                                        .then(Commands.argument("transaction_id", UuidArgument.uuid())
                                                .then(Commands.argument("reason", StringArgumentType.greedyString())
                                                        .executes(context -> changeBalance(
                                                                context.getSource(),
                                                                EntityArgument.getPlayer(context, "player"),
                                                                LongArgumentType.getLong(context, "amount"),
                                                                UuidArgument.getUuid(context, "transaction_id"),
                                                                StringArgumentType.getString(context, "reason"),
                                                                true)))))))
                .then(Commands.literal("debit")
                        .then(Commands.argument("player", EntityArgument.player())
                                .then(Commands.argument("amount", LongArgumentType.longArg(1))
                                        .then(Commands.argument("transaction_id", UuidArgument.uuid())
                                                .then(Commands.argument("reason", StringArgumentType.greedyString())
                                                        .executes(context -> changeBalance(
                                                                context.getSource(),
                                                                EntityArgument.getPlayer(context, "player"),
                                                                LongArgumentType.getLong(context, "amount"),
                                                                UuidArgument.getUuid(context, "transaction_id"),
                                                                StringArgumentType.getString(context, "reason"),
                                                                false)))))))
                .then(Commands.literal("view")
                        .then(economyViewCommand("balances", EconomyView.BALANCES))
                        .then(economyViewCommand("transactions", EconomyView.TRANSACTIONS))
                        .then(economyViewCommand("shops", EconomyView.SHOPS))
                        .then(economyViewCommand("alerts", EconomyView.ALERTS)))
                .then(Commands.literal("reverse")
                        .then(Commands.argument("player", EntityArgument.player())
                                .then(Commands.argument("original_transaction_id", UuidArgument.uuid())
                                        .then(Commands.argument("reversal_transaction_id", UuidArgument.uuid())
                                                .then(Commands.argument("decision", StringArgumentType.word())
                                                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                                                new String[]{"strict", "refund_without_items_or_stock"}, builder))
                                                        .then(Commands.argument("reason", StringArgumentType.greedyString())
                                                                .executes(context -> reverseTransaction(
                                                                        context.getSource(),
                                                                        EntityArgument.getPlayer(context, "player"),
                                                                        UuidArgument.getUuid(context, "original_transaction_id"),
                                                                        UuidArgument.getUuid(context, "reversal_transaction_id"),
                                                                        StringArgumentType.getString(context, "decision"),
                                                                        StringArgumentType.getString(context, "reason")))))))));

        var operationsCommand = Commands.literal("operations")
                .executes(context -> openOperations(
                        context.getSource(), OperationsMetricsService.DEFAULT_WINDOW_MILLIS))
                .then(Commands.argument("window_minutes", IntegerArgumentType.integer(
                                1, (int) (OperationsMetricsService.MAX_WINDOW_MILLIS / 60_000L)))
                        .executes(context -> openOperations(
                                context.getSource(),
                                IntegerArgumentType.getInteger(context, "window_minutes") * 60_000L)));

        var setOfferReason = Commands.argument("reason", StringArgumentType.greedyString())
                .executes(context -> setShopOffer(
                        context.getSource(),
                        IdentifierArgument.getId(context, "shop_id"),
                        IdentifierArgument.getId(context, "offer_id"),
                        ItemArgument.getItem(context, "item").createItemStack(
                                IntegerArgumentType.getInteger(context, "count")),
                        LongArgumentType.getLong(context, "buy_price"),
                        LongArgumentType.getLong(context, "sell_price"),
                        LongArgumentType.getLong(context, "stock"),
                        LongArgumentType.getLong(context, "maximum_stock"),
                        UuidArgument.getUuid(context, "transaction_id"),
                        StringArgumentType.getString(context, "reason")));
        var setOfferTransaction = Commands.argument("transaction_id", UuidArgument.uuid()).then(setOfferReason);
        var setOfferMaximum = Commands.argument(
                "maximum_stock", LongArgumentType.longArg(-1, ShopTemplateSnapshot.MAX_STOCK))
                .then(setOfferTransaction);
        var setOfferStock = Commands.argument("stock", LongArgumentType.longArg(-1, ShopTemplateSnapshot.MAX_STOCK))
                .then(setOfferMaximum);
        var setOfferSell = Commands.argument(
                "sell_price", LongArgumentType.longArg(-1, ShopTemplateSnapshot.MAX_PRICE)).then(setOfferStock);
        var setOfferBuy = Commands.argument(
                "buy_price", LongArgumentType.longArg(-1, ShopTemplateSnapshot.MAX_PRICE)).then(setOfferSell);
        var setOfferCount = Commands.argument("count", IntegerArgumentType.integer(1, 99)).then(setOfferBuy);
        var setOfferItem = Commands.argument("item", ItemArgument.item(event.getBuildContext())).then(setOfferCount);
        var setOfferId = Commands.argument("offer_id", IdentifierArgument.id()).then(setOfferItem);
        var setOfferShop = Commands.argument("shop_id", IdentifierArgument.id()).then(setOfferId);
        var setOfferCommand = Commands.literal("set").then(setOfferShop);

        var removeOfferReason = Commands.argument("reason", StringArgumentType.greedyString())
                .executes(context -> removeShopOffer(
                        context.getSource(),
                        IdentifierArgument.getId(context, "shop_id"),
                        IdentifierArgument.getId(context, "offer_id"),
                        UuidArgument.getUuid(context, "transaction_id"),
                        StringArgumentType.getString(context, "reason")));
        var removeOfferTransaction = Commands.argument("transaction_id", UuidArgument.uuid()).then(removeOfferReason);
        var removeOfferId = Commands.argument("offer_id", IdentifierArgument.id()).then(removeOfferTransaction);
        var removeOfferShop = Commands.argument("shop_id", IdentifierArgument.id()).then(removeOfferId);
        var removeOfferCommand = Commands.literal("remove").then(removeOfferShop);

        var restockSetReason = Commands.argument("reason", StringArgumentType.greedyString())
                .executes(context -> setShopRestock(
                        context.getSource(),
                        IdentifierArgument.getId(context, "shop_id"),
                        IdentifierArgument.getId(context, "offer_id"),
                        Optional.of(LongArgumentType.getLong(context, "restock_amount")),
                        Optional.of(LongArgumentType.getLong(context, "restock_interval_ticks")),
                        UuidArgument.getUuid(context, "transaction_id"),
                        StringArgumentType.getString(context, "reason")));
        var restockSetTransaction = Commands.argument("transaction_id", UuidArgument.uuid()).then(restockSetReason);
        var restockSetInterval = Commands.argument(
                "restock_interval_ticks",
                LongArgumentType.longArg(1, ShopTemplateSnapshot.MAX_RESTOCK_INTERVAL_TICKS))
                .then(restockSetTransaction);
        var restockSetAmount = Commands.argument(
                "restock_amount", LongArgumentType.longArg(1, ShopTemplateSnapshot.MAX_STOCK))
                .then(restockSetInterval);
        var restockSetOffer = Commands.argument("offer_id", IdentifierArgument.id()).then(restockSetAmount);
        var restockSetShop = Commands.argument("shop_id", IdentifierArgument.id()).then(restockSetOffer);
        var restockSetCommand = Commands.literal("set").then(restockSetShop);

        var restockClearReason = Commands.argument("reason", StringArgumentType.greedyString())
                .executes(context -> setShopRestock(
                        context.getSource(),
                        IdentifierArgument.getId(context, "shop_id"),
                        IdentifierArgument.getId(context, "offer_id"),
                        Optional.empty(),
                        Optional.empty(),
                        UuidArgument.getUuid(context, "transaction_id"),
                        StringArgumentType.getString(context, "reason")));
        var restockClearTransaction = Commands.argument("transaction_id", UuidArgument.uuid()).then(restockClearReason);
        var restockClearOffer = Commands.argument("offer_id", IdentifierArgument.id()).then(restockClearTransaction);
        var restockClearShop = Commands.argument("shop_id", IdentifierArgument.id()).then(restockClearOffer);
        var restockClearCommand = Commands.literal("clear").then(restockClearShop);
        var restockCommand = Commands.literal("restock").then(restockSetCommand).then(restockClearCommand);

        var adminShopCommand = Commands.literal("shop")
                .then(Commands.literal("create")
                        .then(Commands.argument("shop_id", IdentifierArgument.id())
                                .then(Commands.argument("template_id", IdentifierArgument.id())
                                        .suggests(RovenfallCommands::suggestShopTemplateIds)
                                        .then(Commands.argument("transaction_id", UuidArgument.uuid())
                                                .then(Commands.argument("reason", StringArgumentType.greedyString())
                                                        .executes(context -> createShop(
                                                                context.getSource(),
                                                                IdentifierArgument.getId(context, "shop_id"),
                                                                IdentifierArgument.getId(context, "template_id"),
                                                                UuidArgument.getUuid(context, "transaction_id"),
                                                                StringArgumentType.getString(context, "reason"))))))))
                .then(Commands.literal("delete")
                        .then(Commands.argument("shop_id", IdentifierArgument.id())
                                .then(Commands.argument("transaction_id", UuidArgument.uuid())
                                        .then(Commands.argument("reason", StringArgumentType.greedyString())
                                                .executes(context -> deleteShop(
                                                        context.getSource(),
                                                        IdentifierArgument.getId(context, "shop_id"),
                                                        UuidArgument.getUuid(context, "transaction_id"),
                                                        StringArgumentType.getString(context, "reason")))))))
                .then(Commands.literal("bind")
                        .then(Commands.argument("shop_id", IdentifierArgument.id())
                                .then(Commands.argument("dimension", DimensionArgument.dimension())
                                        .then(Commands.argument("position", BlockPosArgument.blockPos())
                                                .then(Commands.argument("transaction_id", UuidArgument.uuid())
                                                        .then(Commands.argument("reason", StringArgumentType.greedyString())
                                                                .executes(context -> bindShop(
                                                                        context.getSource(),
                                                                        IdentifierArgument.getId(context, "shop_id"),
                                                                        new ShopInstance.Binding(
                                                                                DimensionArgument.getDimension(context, "dimension").dimension(),
                                                                                BlockPosArgument.getBlockPos(context, "position")),
                                                                        UuidArgument.getUuid(context, "transaction_id"),
                                                                        StringArgumentType.getString(context, "reason")))))))))
                .then(Commands.literal("unbind")
                        .then(Commands.argument("shop_id", IdentifierArgument.id())
                                .then(Commands.argument("transaction_id", UuidArgument.uuid())
                                        .then(Commands.argument("reason", StringArgumentType.greedyString())
                                                .executes(context -> unbindShop(
                                                        context.getSource(),
                                                        IdentifierArgument.getId(context, "shop_id"),
                                                        UuidArgument.getUuid(context, "transaction_id"),
                                                        StringArgumentType.getString(context, "reason")))))))
                .then(Commands.literal("access")
                        .then(Commands.argument("shop_id", IdentifierArgument.id())
                                .then(Commands.argument("max_distance", IntegerArgumentType.integer(
                                                1, ShopInstance.MAX_ACCESS_DISTANCE))
                                        .then(Commands.argument("transaction_id", UuidArgument.uuid())
                                                .then(Commands.argument("reason", StringArgumentType.greedyString())
                                                        .executes(context -> setShopAccess(
                                                                context.getSource(),
                                                                IdentifierArgument.getId(context, "shop_id"),
                                                                IntegerArgumentType.getInteger(context, "max_distance"),
                                                                UuidArgument.getUuid(context, "transaction_id"),
                                                                StringArgumentType.getString(context, "reason"))))))))
                .then(Commands.literal("offer")
                        .then(setOfferCommand)
                        .then(removeOfferCommand)
                        .then(restockCommand));

        var portalCreateReason = Commands.argument("reason", StringArgumentType.greedyString())
                .executes(context -> createPortal(
                        context.getSource(),
                        IdentifierArgument.getId(context, "portal_id"),
                        DimensionArgument.getDimension(context, "destination_dimension"),
                        BlockPosArgument.getBlockPos(context, "destination_position"),
                        IntegerArgumentType.getInteger(context, "protection_radius"),
                        IntegerArgumentType.getInteger(context, "search_radius"),
                        IntegerArgumentType.getInteger(context, "cooldown_seconds"),
                        UuidArgument.getUuid(context, "transaction_id"),
                        StringArgumentType.getString(context, "reason")));
        var portalCreateTransaction = Commands.argument("transaction_id", UuidArgument.uuid())
                .then(portalCreateReason);
        var portalCreateCooldown = Commands.argument(
                "cooldown_seconds", IntegerArgumentType.integer(0, Portal.MAX_COOLDOWN_SECONDS))
                .then(portalCreateTransaction);
        var portalCreateSearch = Commands.argument(
                "search_radius", IntegerArgumentType.integer(0, SafeArrivalResolver.MAX_SEARCH_RADIUS))
                .then(portalCreateCooldown);
        var portalCreateProtection = Commands.argument(
                "protection_radius", IntegerArgumentType.integer(0, Portal.MAX_PROTECTION_RADIUS))
                .then(portalCreateSearch);
        var portalCreatePosition = Commands.argument("destination_position", BlockPosArgument.blockPos())
                .then(portalCreateProtection);
        var portalCreateDimension = Commands.argument("destination_dimension", DimensionArgument.dimension())
                .then(portalCreatePosition);
        var portalCreateId = Commands.argument("portal_id", IdentifierArgument.id()).then(portalCreateDimension);

        var portalDeleteReason = Commands.argument("reason", StringArgumentType.greedyString())
                .executes(context -> deletePortal(
                        context.getSource(),
                        IdentifierArgument.getId(context, "portal_id"),
                        UuidArgument.getUuid(context, "transaction_id"),
                        StringArgumentType.getString(context, "reason")));
        var portalDeleteTransaction = Commands.argument("transaction_id", UuidArgument.uuid())
                .then(portalDeleteReason);
        var portalDeleteId = Commands.argument("portal_id", IdentifierArgument.id())
                .suggests(RovenfallCommands::suggestPortalIds)
                .then(portalDeleteTransaction);
        var portalInfoId = Commands.argument("portal_id", IdentifierArgument.id())
                .suggests(RovenfallCommands::suggestPortalIds)
                .executes(context -> viewManagedPortal(
                        context.getSource(), IdentifierArgument.getId(context, "portal_id")));
        var adminPortalCommand = Commands.literal("portal")
                .then(Commands.literal("create").then(portalCreateId))
                .then(Commands.literal("delete").then(portalDeleteId))
                .then(Commands.literal("info").then(portalInfoId));

        var playerShopCommand = Commands.literal("shop")
                .then(Commands.literal("info")
                        .then(Commands.argument("shop_id", IdentifierArgument.id())
                                .suggests(RovenfallCommands::suggestShopIds)
                                .executes(context -> viewShop(
                                        context.getSource(),
                                        IdentifierArgument.getId(context, "shop_id")))))
                .then(tradeCommand("buy", ShopTradeService.Direction.BUY))
                .then(tradeCommand("sell", ShopTradeService.Direction.SELL));
        var playerPortalCommand = Commands.literal("travel")
                .executes(context -> useManagedPortal(context.getSource(), null))
                .then(Commands.argument("portal_id", IdentifierArgument.id())
                        .suggests(RovenfallCommands::suggestPortalIds)
                        .executes(context -> useManagedPortal(
                                context.getSource(), IdentifierArgument.getId(context, "portal_id"))));
        var claimExplainCommand = Commands.literal("explain")
                .then(Commands.literal("buy")
                        .executes(context -> explainClaimPurchase(context.getSource())))
                .then(Commands.literal("action")
                        .then(Commands.argument("action", StringArgumentType.word())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                        ClaimProtectionService.Action.ids(), builder))
                                .executes(context -> explainClaimAction(
                                        context.getSource(), StringArgumentType.getString(context, "action")))))
                .then(Commands.literal("role")
                        .then(Commands.argument("player", EntityArgument.player())
                                .then(Commands.argument("role", StringArgumentType.word())
                                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                                ClaimRole.ids(), builder))
                                        .executes(context -> explainClaimRole(
                                                context.getSource(),
                                                EntityArgument.getPlayer(context, "player"),
                                                StringArgumentType.getString(context, "role"))))));
        var playerClaimCommand = Commands.literal("claim")
                .then(claimExplainCommand)
                .then(Commands.literal("buy")
                        .executes(context -> buyClaim(context.getSource())))
                .then(Commands.literal("info")
                        .executes(context -> viewClaim(context.getSource(), currentClaimKey(context.getSource()))))
                .then(Commands.literal("trust")
                        .then(Commands.argument("player", EntityArgument.player())
                                .then(Commands.argument("role", StringArgumentType.word())
                                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                                ClaimRole.ids(), builder))
                                        .executes(context -> setClaimRole(
                                                context.getSource(), currentClaimKey(context.getSource()),
                                                EntityArgument.getPlayer(context, "player"),
                                                StringArgumentType.getString(context, "role"), false,
                                                "player claim trust")))))
                .then(Commands.literal("untrust")
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(context -> removeClaimRole(
                                        context.getSource(), currentClaimKey(context.getSource()),
                                        EntityArgument.getPlayer(context, "player"), false,
                                        "player claim untrust"))))
                .then(Commands.literal("settings")
                        .then(Commands.argument("entry_restricted", BoolArgumentType.bool())
                                .then(Commands.argument("public_interactions", BoolArgumentType.bool())
                                        .executes(context -> setClaimSettings(
                                                context.getSource(), currentClaimKey(context.getSource()),
                                                BoolArgumentType.getBool(context, "entry_restricted"),
                                                BoolArgumentType.getBool(context, "public_interactions"), false,
                                                "player claim settings")))))
                .then(Commands.literal("transfer")
                        .then(Commands.literal("offer")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(context -> offerClaimTransfer(
                                                context.getSource(), currentClaimKey(context.getSource()),
                                                EntityArgument.getPlayer(context, "player")))))
                        .then(Commands.literal("cancel")
                                .executes(context -> cancelClaimTransfer(
                                        context.getSource(), currentClaimKey(context.getSource())))
                                .then(Commands.argument("dimension", DimensionArgument.dimension())
                                        .then(Commands.argument("chunk_x", IntegerArgumentType.integer())
                                                .then(Commands.argument("chunk_z", IntegerArgumentType.integer())
                                                        .executes(context -> cancelClaimTransfer(
                                                                context.getSource(),
                                                                new ClaimKey(
                                                                        DimensionArgument.getDimension(
                                                                                context, "dimension").dimension(),
                                                                        IntegerArgumentType.getInteger(
                                                                                context, "chunk_x"),
                                                                        IntegerArgumentType.getInteger(
                                                                                context, "chunk_z"))))))))
                        .then(Commands.literal("accept")
                                .executes(context -> acceptClaimTransfer(
                                        context.getSource(), currentClaimKey(context.getSource())))
                                .then(Commands.argument("dimension", DimensionArgument.dimension())
                                        .then(Commands.argument("chunk_x", IntegerArgumentType.integer())
                                                .then(Commands.argument("chunk_z", IntegerArgumentType.integer())
                                                        .executes(context -> acceptClaimTransfer(
                                                                context.getSource(),
                                                                new ClaimKey(
                                                                        DimensionArgument.getDimension(
                                                                                context, "dimension").dimension(),
                                                                        IntegerArgumentType.getInteger(
                                                                                context, "chunk_x"),
                                                                        IntegerArgumentType.getInteger(
                                                                                context, "chunk_z")))))))))
                .then(Commands.literal("sell")
                        .executes(context -> sellClaim(context.getSource(), currentClaimKey(context.getSource()))));

        var adminClaimCommand = Commands.literal("claim")
                .then(adminClaimInfoCommand())
                .then(adminClaimTrustCommand())
                .then(adminClaimUntrustCommand())
                .then(adminClaimSettingsCommand());

        var protectedRegionCommand = Commands.literal("region")
                .then(protectedRegionMutationCommand("create", true))
                .then(protectedRegionMutationCommand("edit", false))
                .then(Commands.literal("delete")
                        .then(Commands.argument("region_id", IdentifierArgument.id())
                                .then(Commands.argument("reason", StringArgumentType.greedyString())
                                        .executes(context -> deleteProtectedRegion(
                                                context.getSource(),
                                                IdentifierArgument.getId(context, "region_id"),
                                                StringArgumentType.getString(context, "reason"))))))
                .then(Commands.literal("info")
                        .then(Commands.argument("region_id", IdentifierArgument.id())
                                .executes(context -> viewProtectedRegion(
                                        context.getSource(),
                                        IdentifierArgument.getId(context, "region_id")))))
                .then(Commands.literal("list")
                        .executes(context -> listProtectedRegions(context.getSource(), 0))
                        .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                .executes(context -> listProtectedRegions(
                                        context.getSource(),
                                        IntegerArgumentType.getInteger(context, "page") - 1))));

        var portalCommand = Commands.literal("portal")
                .then(Commands.literal("use")
                        .then(Commands.argument("portal_id", IdentifierArgument.id())
                                .executes(context -> usePortal(
                                        context.getSource(), IdentifierArgument.getId(context, "portal_id")))))
                .then(portalMutationCommand("create", true))
                .then(portalMutationCommand("edit", false))
                .then(Commands.literal("delete")
                        .requires(RovenfallCommands::canManagePortals)
                        .then(Commands.argument("portal_id", IdentifierArgument.id())
                                .then(Commands.argument("reason", StringArgumentType.greedyString())
                                        .executes(context -> deletePortal(
                                                context.getSource(),
                                                IdentifierArgument.getId(context, "portal_id"),
                                                StringArgumentType.getString(context, "reason"))))))
                .then(Commands.literal("info")
                        .then(Commands.argument("portal_id", IdentifierArgument.id())
                                .executes(context -> viewPortal(
                                        context.getSource(), IdentifierArgument.getId(context, "portal_id")))))
                .then(Commands.literal("list")
                        .executes(context -> listPortals(context.getSource(), 0))
                        .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                .executes(context -> listPortals(
                                        context.getSource(), IntegerArgumentType.getInteger(context, "page") - 1))));

        var playerActivityCommand = Commands.literal("activity")
                .executes(context -> viewActivity(context.getSource()))
                .then(Commands.literal("info")
                        .executes(context -> viewActivity(context.getSource())));

        var playerChallengeCommand = Commands.literal("challenge")
                .executes(context -> viewChallenges(context.getSource()))
                .then(Commands.literal("list")
                        .executes(context -> viewChallenges(context.getSource())))
                .then(Commands.literal("info")
                        .then(Commands.argument("challenge_id", IdentifierArgument.id())
                                .suggests(RovenfallCommands::suggestChallengeIds)
                                .executes(context -> viewChallenge(
                                        context.getSource(),
                                        IdentifierArgument.getId(context, "challenge_id")))))
                .then(Commands.literal("claim")
                        .then(Commands.argument("challenge_id", IdentifierArgument.id())
                                .suggests(RovenfallCommands::suggestChallengeIds)
                                .executes(context -> claimChallenge(
                                        context.getSource(),
                                        IdentifierArgument.getId(context, "challenge_id")))));

        var playerContractCommand = Commands.literal("contract")
                .executes(context -> viewDailyContracts(context.getSource()))
                .then(Commands.literal("list")
                        .executes(context -> viewDailyContracts(context.getSource())))
                .then(Commands.literal("info")
                        .then(Commands.argument("contract_id", IdentifierArgument.id())
                                .suggests(RovenfallCommands::suggestDailyContractIds)
                                .executes(context -> viewDailyContract(
                                        context.getSource(),
                                        IdentifierArgument.getId(context, "contract_id")))))
                .then(Commands.literal("claim")
                        .then(Commands.argument("contract_id", IdentifierArgument.id())
                                .suggests(RovenfallCommands::suggestDailyContractIds)
                                .executes(context -> claimDailyContract(
                                        context.getSource(),
                                        IdentifierArgument.getId(context, "contract_id")))));

        var playerExpeditionCommand = Commands.literal("expedition")
                .executes(context -> viewWeeklyExpeditions(context.getSource()))
                .then(Commands.literal("list")
                        .executes(context -> viewWeeklyExpeditions(context.getSource())))
                .then(Commands.literal("info")
                        .then(Commands.argument("expedition_id", IdentifierArgument.id())
                                .suggests(RovenfallCommands::suggestWeeklyExpeditionIds)
                                .executes(context -> viewWeeklyExpedition(
                                        context.getSource(),
                                        IdentifierArgument.getId(context, "expedition_id")))))
                .then(Commands.literal("claim")
                        .then(Commands.argument("expedition_id", IdentifierArgument.id())
                                .suggests(RovenfallCommands::suggestWeeklyExpeditionIds)
                                .executes(context -> claimWeeklyExpedition(
                                        context.getSource(),
                                        IdentifierArgument.getId(context, "expedition_id")))));

        var playerCareerCommand = Commands.literal("profession")
                .executes(context -> viewCareer(context.getSource()))
                .then(Commands.literal("info")
                        .executes(context -> viewCareer(context.getSource())))
                .then(Commands.literal("explain")
                        .then(Commands.argument("career_id", IdentifierArgument.id())
                                .suggests(RovenfallCommands::suggestCareerIds)
                                .executes(context -> explainCareerPromotion(
                                        context.getSource(),
                                        IdentifierArgument.getId(context, "career_id")))))
                .then(Commands.literal("promote")
                        .then(Commands.argument("career_id", IdentifierArgument.id())
                                .suggests(RovenfallCommands::suggestCareerIds)
                                .then(Commands.argument("transaction_id", UuidArgument.uuid())
                                        .then(Commands.literal("confirm")
                                                .executes(context -> promoteCareer(
                                                        context.getSource(),
                                                        IdentifierArgument.getId(context, "career_id"),
                                                        UuidArgument.getUuid(context, "transaction_id")))))));

        var playerSkillCommand = Commands.literal("ability")
                .executes(context -> viewSkills(context.getSource(), null))
                .then(Commands.literal("info")
                        .executes(context -> viewSkills(context.getSource(), null))
                        .then(Commands.argument("career_id", IdentifierArgument.id())
                                .suggests(RovenfallCommands::suggestCareerIds)
                                .executes(context -> viewSkills(
                                        context.getSource(),
                                        IdentifierArgument.getId(context, "career_id")))))
                .then(Commands.literal("explain")
                        .then(Commands.argument("skill_id", IdentifierArgument.id())
                                .suggests(RovenfallCommands::suggestSkillIds)
                                .executes(context -> explainSkillUnlock(
                                        context.getSource(),
                                        IdentifierArgument.getId(context, "skill_id")))))
                .then(Commands.literal("unlock")
                        .then(Commands.argument("skill_id", IdentifierArgument.id())
                                .suggests(RovenfallCommands::suggestSkillIds)
                                .then(Commands.argument("transaction_id", UuidArgument.uuid())
                                        .then(Commands.literal("confirm")
                                                .executes(context -> unlockSkill(
                                                        context.getSource(),
                                                        IdentifierArgument.getId(context, "skill_id"),
                                                        UuidArgument.getUuid(context, "transaction_id")))))))
                .then(Commands.literal("reset")
                        .then(Commands.literal("explain")
                                .then(Commands.argument("career_id", IdentifierArgument.id())
                                        .suggests(RovenfallCommands::suggestCareerIds)
                                        .executes(context -> explainSkillReset(
                                                context.getSource(),
                                                IdentifierArgument.getId(context, "career_id")))))
                        .then(Commands.argument("career_id", IdentifierArgument.id())
                                .suggests(RovenfallCommands::suggestCareerIds)
                                .then(Commands.argument("transaction_id", UuidArgument.uuid())
                                        .then(Commands.literal("confirm")
                                                .executes(context -> resetSkills(
                                                        context.getSource(),
                                                        IdentifierArgument.getId(context, "career_id"),
                                                        UuidArgument.getUuid(context, "transaction_id")))))))
                .then(Commands.literal("slots")
                        .executes(context -> viewActiveSkillSlots(context.getSource())))
                .then(Commands.literal("equip")
                        .then(Commands.argument("slot", IntegerArgumentType.integer(1, 4))
                                .then(Commands.argument("skill_id", IdentifierArgument.id())
                                        .suggests(RovenfallCommands::suggestActiveSkillIds)
                                        .executes(context -> equipActiveSkill(
                                                context.getSource(),
                                                IntegerArgumentType.getInteger(context, "slot"),
                                                IdentifierArgument.getId(context, "skill_id"))))))
                .then(Commands.literal("clear")
                        .then(Commands.argument("slot", IntegerArgumentType.integer(1, 4))
                                .executes(context -> clearActiveSkill(
                                        context.getSource(),
                                        IntegerArgumentType.getInteger(context, "slot")))))
                .then(Commands.literal("use")
                        .then(Commands.argument("slot", IntegerArgumentType.integer(1, 4))
                                .executes(context -> useActiveSkill(
                                        context.getSource(),
                                        IntegerArgumentType.getInteger(context, "slot")))));

        event.getDispatcher().register(Commands.literal("rovenfall")
                .executes(context -> showHelp(context.getSource(), false))
                .then(playerHelpCommand)
                .then(Commands.literal("menu")
                        .executes(context -> openPlayerMenu(context.getSource())))
                .then(Commands.literal("inventory")
                        .executes(context -> openCharacterInventory(context.getSource())))
                .then(playerShopCommand)
                .then(playerPortalCommand)
                .then(playerClaimCommand)
                .then(RpgCommands.careerCommand())
                .then(RpgCommands.skillCommand())
                .then(portalCommand)
                .then(playerActivityCommand)
                .then(playerChallengeCommand)
                .then(playerContractCommand)
                .then(playerExpeditionCommand)
                .then(playerCareerCommand)
                .then(playerSkillCommand)
                .then(Commands.literal("admin")
                        .requires(RovenfallCommands::canUseAdministration)
                        .then(guiCommand)
                        .then(helpCommand)
                        .then(roleCommand)
                        .then(economyCommand)
                        .then(operationsCommand)
                        .then(adminShopCommand)
                        .then(adminPortalCommand)
                        .then(adminClaimCommand)
                        .then(protectedRegionCommand)
                        .then(RpgAdminCommands.command())
                        .then(BossAdminCommands.command())
                        .then(searchCommand)
                        .then(reverseCommand)
                        .then(auditCommand)
                        .then(snapshotCommand)
                        .then(wildernessCommand)
                        .then(restartWildernessCommand)
                        .then(arenaBossCommand)));
    }

    private static int openPlayerMenu(CommandSourceStack source) throws CommandSyntaxException {
        PlayerDashboardMenu.open(source.getPlayerOrException());
        return 1;
    }

    private static int openCharacterInventory(CommandSourceStack source) throws CommandSyntaxException {
        return PlayerMenuNetwork.sendInventorySummary(source.getPlayerOrException(), true) ? 1 : 0;
    }

    private static int openAdministrationGui(CommandSourceStack source) throws CommandSyntaxException {
        return AdministrationControlCenterMenu.open(source.getPlayerOrException()) ? 1 : 0;
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack>
            portalMutationCommand(String literal, boolean create) {
        return Commands.literal(literal)
                .requires(RovenfallCommands::canManagePortals)
                .then(Commands.argument("portal_id", IdentifierArgument.id())
                        .then(Commands.argument("origin_dimension", DimensionArgument.dimension())
                                .then(Commands.argument("origin_position", BlockPosArgument.blockPos())
                                        .then(Commands.argument("destination_dimension", DimensionArgument.dimension())
                                                .then(Commands.argument("destination_position", BlockPosArgument.blockPos())
                                                        .then(Commands.argument("radius_chunks", IntegerArgumentType.integer(
                                                                        0, PortalDefinition.MAX_PROTECTION_RADIUS_CHUNKS))
                                                                .then(Commands.argument("cooldown_seconds", LongArgumentType.longArg(
                                                                                0, PortalDefinition.MAX_COOLDOWN_MILLIS / 1_000L))
                                                                        .then(Commands.argument("safe_policy", StringArgumentType.word())
                                                                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                                                                        java.util.Arrays.stream(PortalDefinition.SafeArrivalPolicy.values())
                                                                                                .map(PortalDefinition.SafeArrivalPolicy::getSerializedName),
                                                                                        builder))
                                                                                .then(Commands.argument("allow_combat", BoolArgumentType.bool())
                                                                                        .then(Commands.argument("reason", StringArgumentType.greedyString())
                                                                                                .executes(context -> mutatePortal(
                                                                                                        context.getSource(),
                                                                                                        IdentifierArgument.getId(context, "portal_id"),
                                                                                                        new PortalDefinition.Endpoint(
                                                                                                                DimensionArgument.getDimension(context, "origin_dimension").dimension(),
                                                                                                                BlockPosArgument.getBlockPos(context, "origin_position")),
                                                                                                        new PortalDefinition.Endpoint(
                                                                                                                DimensionArgument.getDimension(context, "destination_dimension").dimension(),
                                                                                                                BlockPosArgument.getBlockPos(context, "destination_position")),
                                                                                                        IntegerArgumentType.getInteger(context, "radius_chunks"),
                                                                                                        LongArgumentType.getLong(context, "cooldown_seconds") * 1_000L,
                                                                                                        StringArgumentType.getString(context, "safe_policy"),
                                                                                                        BoolArgumentType.getBool(context, "allow_combat"),
                                                                                                        StringArgumentType.getString(context, "reason"),
                                                                                                        create))))))))))));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack>
            protectedRegionMutationCommand(String literal, boolean create) {
        return Commands.literal(literal)
                .then(Commands.argument("region_id", IdentifierArgument.id())
                        .then(Commands.argument("dimension", DimensionArgument.dimension())
                                .then(Commands.argument("min_chunk_x", IntegerArgumentType.integer())
                                        .then(Commands.argument("min_chunk_z", IntegerArgumentType.integer())
                                                .then(Commands.argument("max_chunk_x", IntegerArgumentType.integer())
                                                        .then(Commands.argument(
                                                                        "max_chunk_z",
                                                                        IntegerArgumentType.integer())
                                                                .then(Commands.argument(
                                                                                "reason",
                                                                                StringArgumentType.greedyString())
                                                                        .executes(context -> mutateProtectedRegion(
                                                                                context.getSource(),
                                                                                IdentifierArgument.getId(
                                                                                        context, "region_id"),
                                                                                DimensionArgument.getDimension(
                                                                                                context, "dimension")
                                                                                        .dimension(),
                                                                                IntegerArgumentType.getInteger(
                                                                                        context, "min_chunk_x"),
                                                                                IntegerArgumentType.getInteger(
                                                                                        context, "min_chunk_z"),
                                                                                IntegerArgumentType.getInteger(
                                                                                        context, "max_chunk_x"),
                                                                                IntegerArgumentType.getInteger(
                                                                                        context, "max_chunk_z"),
                                                                                StringArgumentType.getString(
                                                                                        context, "reason"),
                                                                                create)))))))));
    }


    private static int showHelp(CommandSourceStack source, boolean administrator) {
        String prefix = administrator
                ? "command.rovenfall.admin.help."
                : "command.rovenfall.help.";
        String[] sections = administrator
                ? new String[]{"header", "search", "audit", "roles", "economy", "shops", "worlds",
                        "claims", "reverse", "snapshot", "destructive"}
                : new String[]{
                        "header", "shop", "portal", "claim", "activity", "challenge", "contract", "expedition",
                        "career", "skill"};
        for (String section : sections) {
            source.sendSuccess(() -> Component.translatable(prefix + section), false);
        }
        return 1;
    }

    private static int startBossEncounter(
            CommandSourceStack source, int radius, UUID encounterId, String reason) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        PlatformSavedData state = PlatformSavedData.get(source.getServer());
        var result = BossEncounterService.start(
                state,
                actorId(source),
                authorizationOverride(source, state),
                player.level().dimension(),
                player.blockPosition(),
                radius,
                reason,
                Instant.now().toEpochMilli(),
                encounterId,
                encounter -> BossEvents.spawnManaged(source.getLevel(), encounter));
        return switch (result.status()) {
            case SUCCESS -> {
                var encounter = result.encounter().orElseThrow();
                source.sendSuccess(() -> Component.translatable(
                        "command.rovenfall.admin.boss.start.success",
                        encounter.encounterId().toString(),
                        encounter.origin().toShortString(),
                        encounter.radius()), true);
                yield 1;
            }
            case DUPLICATE_TRANSACTION -> {
                source.sendSuccess(() -> Component.translatable(
                        "command.rovenfall.admin.boss.start.duplicate", encounterId.toString()), false);
                yield 1;
            }
            case TRANSACTION_ID_CONFLICT -> failure(
                    source, "command.rovenfall.admin.boss.error.transaction_id_conflict");
            case UNAUTHORIZED -> failure(source, "command.rovenfall.admin.boss.error.unauthorized");
            case INVALID_REQUEST -> failure(source, "command.rovenfall.admin.boss.error.invalid_request");
            case INVALID_REASON -> failure(
                    source, "command.rovenfall.admin.error.invalid_reason", AdministrationService.MAX_REASON_LENGTH);
            case ENCOUNTER_ACTIVE -> failure(source, "command.rovenfall.admin.boss.error.active");
            case TRANSACTION_LEDGER_FULL -> failure(
                    source, "command.rovenfall.admin.boss.error.transaction_ledger_full");
            case SPAWN_FAILED -> failure(source, "command.rovenfall.admin.boss.error.spawn_failed");
            case READ_ONLY_SCHEMA -> failure(
                    source, "command.rovenfall.admin.error.read_only_schema", state.schemaVersion());
        };
    }

    private static int viewBossEncounter(CommandSourceStack source) {
        var encounter = PlatformSavedData.get(source.getServer()).bossEncounter();
        if (encounter.isEmpty()) {
            return failure(source, "command.rovenfall.admin.boss.status.none");
        }
        var value = encounter.orElseThrow();
        source.sendSuccess(() -> Component.translatable(
                "command.rovenfall.admin.boss.status",
                value.encounterId().toString(),
                value.status().name().toLowerCase(java.util.Locale.ROOT),
                value.phase(),
                value.dimension().identifier().toString(),
                value.origin().toShortString(),
                value.radius(),
                value.contributions().size(),
                value.settledPlayers().size()), false);
        return 1;
    }

    private static int viewActivity(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        PlatformSavedData state = PlatformSavedData.get(source.getServer());
        source.sendSuccess(() -> Component.translatable("command.rovenfall.activity.header"), false);
        for (ActivityTrack track : ActivityTrack.values()) {
            long experience = state.activityExperience(player.getUUID(), track);
            var definition = ActivityLevelReloadListener.get(source.getServer(), track);
            if (definition.isEmpty()) {
                source.sendSuccess(() -> Component.translatable(
                        "command.rovenfall.activity.line_unavailable",
                        Component.translatable(track.translationKey()),
                        experience), false);
                continue;
            }
            var progress = definition.orElseThrow().progress(experience);
            if (progress.maximum()) {
                source.sendSuccess(() -> Component.translatable(
                        "command.rovenfall.activity.line_max",
                        Component.translatable(track.translationKey()),
                        progress.level(),
                        progress.totalExperience()), false);
            } else {
                source.sendSuccess(() -> Component.translatable(
                        "command.rovenfall.activity.line",
                        Component.translatable(track.translationKey()),
                        progress.level(),
                        progress.experienceIntoLevel(),
                        progress.experienceForNextLevel(),
                        progress.totalExperience()), false);
            }
        }
        return 1;
    }

    private static int viewChallenges(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        PlatformSavedData state = PlatformSavedData.get(source.getServer());
        var definitions = ActivityChallengeReloadListener.snapshot(source.getServer()).orElse(Map.of());
        if (definitions.isEmpty()) {
            return failure(source, "command.rovenfall.challenge.error.catalog_unavailable");
        }
        Optional<Map<ActivityTrack, Integer>> retainedLevels = activityLevels(
                source, state, player.getUUID());
        if (retainedLevels.isEmpty()) {
            return failure(source, "command.rovenfall.challenge.error.activity_levels_unavailable");
        }
        Map<ActivityTrack, Integer> levels = retainedLevels.orElseThrow();
        source.sendSuccess(() -> Component.translatable(
                "command.rovenfall.challenge.header", definitions.size()), false);
        definitions.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            var evaluation = ActivityChallengeService.evaluate(
                    state, player.getUUID(), entry.getKey(), entry.getValue(), levels);
            source.sendSuccess(() -> Component.translatable(
                    "command.rovenfall.challenge.line",
                    Component.translatable(entry.getValue().translationKey()),
                    entry.getKey().toString(),
                    Component.translatable(evaluation.status().translationKey()),
                    entry.getValue().currencyReward()), false);
        });
        return 1;
    }

    private static int viewChallenge(
            CommandSourceStack source,
            Identifier challengeId) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        PlatformSavedData state = PlatformSavedData.get(source.getServer());
        var retainedDefinitions = ActivityChallengeReloadListener.snapshot(source.getServer());
        if (retainedDefinitions.isEmpty() || retainedDefinitions.orElseThrow().isEmpty()) {
            return failure(source, "command.rovenfall.challenge.error.catalog_unavailable");
        }
        var definitions = retainedDefinitions.orElseThrow();
        var definition = definitions.get(challengeId);
        if (definition == null) {
            return failure(source, "command.rovenfall.challenge.error.not_found", challengeId.toString());
        }
        Optional<Map<ActivityTrack, Integer>> retainedLevels = activityLevels(
                source, state, player.getUUID());
        if (retainedLevels.isEmpty()) {
            return failure(source, "command.rovenfall.challenge.error.activity_levels_unavailable");
        }
        var evaluation = ActivityChallengeService.evaluate(
                state, player.getUUID(), challengeId, definition, retainedLevels.orElseThrow());
        source.sendSuccess(() -> Component.translatable(
                "command.rovenfall.challenge.info.header",
                Component.translatable(definition.translationKey()),
                challengeId.toString()), false);
        source.sendSuccess(() -> Component.translatable(
                "command.rovenfall.challenge.info.summary",
                Component.translatable(definition.descriptionTranslationKey()),
                Component.translatable(evaluation.status().translationKey()),
                definition.currencyReward()), false);
        evaluation.requirements().forEach(requirement -> source.sendSuccess(() -> Component.translatable(
                "command.rovenfall.challenge.info.requirement",
                Component.translatable(requirement.track().translationKey()),
                requirement.currentLevel(),
                requirement.requiredLevel(),
                careerCondition(requirement.met())), false));
        return 1;
    }

    private static int claimChallenge(
            CommandSourceStack source,
            Identifier challengeId) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        PlatformSavedData state = PlatformSavedData.get(source.getServer());
        var retainedDefinitions = ActivityChallengeReloadListener.snapshot(source.getServer());
        if (retainedDefinitions.isEmpty() || retainedDefinitions.orElseThrow().isEmpty()) {
            return failure(source, "command.rovenfall.challenge.error.catalog_unavailable");
        }
        var definitions = retainedDefinitions.orElseThrow();
        var definition = definitions.get(challengeId);
        if (definition == null) {
            return failure(source, "command.rovenfall.challenge.error.not_found", challengeId.toString());
        }
        Optional<Map<ActivityTrack, Integer>> retainedLevels = activityLevels(
                source, state, player.getUUID());
        if (retainedLevels.isEmpty()) {
            return failure(source, "command.rovenfall.challenge.error.activity_levels_unavailable");
        }
        var result = ActivityChallengeService.claim(
                state,
                player.getUUID(),
                challengeId,
                definition,
                retainedLevels.orElseThrow(),
                Instant.now().toEpochMilli(),
                EconomyConfig.initialBalance(),
                EconomyConfig.maximumBalance());
        return switch (result.status()) {
            case SUCCESS -> {
                source.sendSuccess(() -> Component.translatable(
                        "command.rovenfall.challenge.claim.success",
                        Component.translatable(definition.translationKey()),
                        result.awardedCurrency(),
                        result.balance()), false);
                yield 1;
            }
            case ALREADY_CLAIMED -> {
                source.sendSuccess(() -> Component.translatable(
                        "command.rovenfall.challenge.claim.already_claimed",
                        Component.translatable(definition.translationKey())), false);
                yield 1;
            }
            case REQUIREMENTS_NOT_MET -> failure(
                    source,
                    "command.rovenfall.challenge.claim.requirements_not_met",
                    Component.translatable(definition.translationKey()));
            case READ_ONLY_SCHEMA -> failure(
                    source, "command.rovenfall.admin.error.read_only_schema", state.schemaVersion());
            case TRANSACTION_CONFLICT -> failure(
                    source, "command.rovenfall.challenge.error.transaction_conflict");
            case REWARD_FAILED -> failure(
                    source,
                    "command.rovenfall.challenge.error.reward_failed",
                    result.economyStatus().map(value -> value.name().toLowerCase(java.util.Locale.ROOT))
                            .orElse("unknown"));
            case INVALID_REQUEST -> failure(source, "command.rovenfall.challenge.error.invalid_request");
            case CLAIMABLE -> failure(source, "command.rovenfall.challenge.error.invalid_request");
        };
    }

    private static int viewDailyContracts(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        PlatformSavedData state = PlatformSavedData.get(source.getServer());
        var definitions = DailyContractReloadListener.snapshot(source.getServer()).orElse(Map.of());
        if (definitions.isEmpty()) {
            return failure(source, "command.rovenfall.contract.error.catalog_unavailable");
        }
        long timestamp = Instant.now().toEpochMilli();
        String reset = Instant.ofEpochMilli(
                DailyContractService.periodStart(timestamp) + DailyContractService.PERIOD_MILLIS).toString();
        source.sendSuccess(() -> Component.translatable(
                "command.rovenfall.contract.header", definitions.size(), reset), false);
        definitions.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            var evaluation = DailyContractService.evaluate(
                    state, player.getUUID(), entry.getKey(), entry.getValue(), timestamp);
            source.sendSuccess(() -> Component.translatable(
                    "command.rovenfall.contract.line",
                    Component.translatable(entry.getValue().translationKey()),
                    entry.getKey().toString(),
                    evaluation.progressExperience(),
                    evaluation.requiredExperience(),
                    Component.translatable(evaluation.status().translationKey()),
                    entry.getValue().currencyReward()), false);
        });
        return 1;
    }

    private static int viewDailyContract(
            CommandSourceStack source,
            Identifier contractId) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        PlatformSavedData state = PlatformSavedData.get(source.getServer());
        var retainedDefinitions = DailyContractReloadListener.snapshot(source.getServer());
        if (retainedDefinitions.isEmpty() || retainedDefinitions.orElseThrow().isEmpty()) {
            return failure(source, "command.rovenfall.contract.error.catalog_unavailable");
        }
        var definition = retainedDefinitions.orElseThrow().get(contractId);
        if (definition == null) {
            return failure(source, "command.rovenfall.contract.error.not_found", contractId.toString());
        }
        var evaluation = DailyContractService.evaluate(
                state, player.getUUID(), contractId, definition, Instant.now().toEpochMilli());
        source.sendSuccess(() -> Component.translatable(
                "command.rovenfall.contract.info.header",
                Component.translatable(definition.translationKey()),
                contractId.toString()), false);
        source.sendSuccess(() -> Component.translatable(
                "command.rovenfall.contract.info.summary",
                Component.translatable(definition.descriptionTranslationKey()),
                Component.translatable(evaluation.status().translationKey()),
                definition.currencyReward()), false);
        source.sendSuccess(() -> Component.translatable(
                "command.rovenfall.contract.info.objective",
                definition.targetId().toString(),
                Component.translatable(definition.kind().track().translationKey()),
                evaluation.progressExperience(),
                evaluation.requiredExperience(),
                Instant.ofEpochMilli(evaluation.nextResetEpochMillis()).toString()), false);
        return 1;
    }

    private static int claimDailyContract(
            CommandSourceStack source,
            Identifier contractId) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        PlatformSavedData state = PlatformSavedData.get(source.getServer());
        var retainedDefinitions = DailyContractReloadListener.snapshot(source.getServer());
        if (retainedDefinitions.isEmpty() || retainedDefinitions.orElseThrow().isEmpty()) {
            return failure(source, "command.rovenfall.contract.error.catalog_unavailable");
        }
        var definition = retainedDefinitions.orElseThrow().get(contractId);
        if (definition == null) {
            return failure(source, "command.rovenfall.contract.error.not_found", contractId.toString());
        }
        var result = DailyContractService.claim(
                state,
                player.getUUID(),
                contractId,
                definition,
                Instant.now().toEpochMilli(),
                EconomyConfig.initialBalance(),
                EconomyConfig.maximumBalance());
        return switch (result.status()) {
            case SUCCESS -> {
                source.sendSuccess(() -> Component.translatable(
                        "command.rovenfall.contract.claim.success",
                        Component.translatable(definition.translationKey()),
                        result.awardedCurrency(),
                        result.balance()), false);
                yield 1;
            }
            case ALREADY_CLAIMED -> {
                source.sendSuccess(() -> Component.translatable(
                        "command.rovenfall.contract.claim.already_claimed",
                        Component.translatable(definition.translationKey()),
                        Instant.ofEpochMilli(result.evaluation().nextResetEpochMillis()).toString()), false);
                yield 1;
            }
            case IN_PROGRESS -> failure(
                    source,
                    "command.rovenfall.contract.claim.in_progress",
                    Component.translatable(definition.translationKey()),
                    result.evaluation().progressExperience(),
                    result.evaluation().requiredExperience());
            case READ_ONLY_SCHEMA -> failure(
                    source, "command.rovenfall.admin.error.read_only_schema", state.schemaVersion());
            case TRANSACTION_CONFLICT -> failure(
                    source, "command.rovenfall.contract.error.transaction_conflict");
            case REWARD_FAILED -> failure(
                    source,
                    "command.rovenfall.contract.error.reward_failed",
                    result.economyStatus().map(value -> value.name().toLowerCase(java.util.Locale.ROOT))
                            .orElse("unknown"));
            case INVALID_REQUEST, CLAIMABLE -> failure(
                    source, "command.rovenfall.contract.error.invalid_request");
        };
    }

    private static int viewWeeklyExpeditions(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        PlatformSavedData state = PlatformSavedData.get(source.getServer());
        var definitions = WeeklyExpeditionReloadListener.snapshot(source.getServer()).orElse(Map.of());
        if (definitions.isEmpty()) {
            return failure(source, "command.rovenfall.expedition.error.catalog_unavailable");
        }
        long timestamp = Instant.now().toEpochMilli();
        String reset = Instant.ofEpochMilli(
                WeeklyExpeditionService.periodStart(timestamp) + WeeklyExpeditionService.PERIOD_MILLIS).toString();
        source.sendSuccess(() -> Component.translatable(
                "command.rovenfall.expedition.header", definitions.size(), reset), false);
        definitions.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            var evaluation = WeeklyExpeditionService.evaluate(
                    state, player.getUUID(), entry.getKey(), entry.getValue(), timestamp);
            long met = evaluation.requirements().stream()
                    .filter(WeeklyExpeditionService.Requirement::met)
                    .count();
            source.sendSuccess(() -> Component.translatable(
                    "command.rovenfall.expedition.line",
                    Component.translatable(entry.getValue().translationKey()),
                    entry.getKey().toString(),
                    met,
                    evaluation.requirements().size(),
                    Component.translatable(evaluation.status().translationKey()),
                    entry.getValue().currencyReward()), false);
        });
        return 1;
    }

    private static int viewWeeklyExpedition(
            CommandSourceStack source,
            Identifier expeditionId) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        PlatformSavedData state = PlatformSavedData.get(source.getServer());
        var retainedDefinitions = WeeklyExpeditionReloadListener.snapshot(source.getServer());
        if (retainedDefinitions.isEmpty() || retainedDefinitions.orElseThrow().isEmpty()) {
            return failure(source, "command.rovenfall.expedition.error.catalog_unavailable");
        }
        var definition = retainedDefinitions.orElseThrow().get(expeditionId);
        if (definition == null) {
            return failure(source, "command.rovenfall.expedition.error.not_found", expeditionId.toString());
        }
        var dailyContracts = DailyContractReloadListener.snapshot(source.getServer()).orElse(Map.of());
        var evaluation = WeeklyExpeditionService.evaluate(
                state, player.getUUID(), expeditionId, definition, Instant.now().toEpochMilli());
        source.sendSuccess(() -> Component.translatable(
                "command.rovenfall.expedition.info.header",
                Component.translatable(definition.translationKey()),
                expeditionId.toString()), false);
        source.sendSuccess(() -> Component.translatable(
                "command.rovenfall.expedition.info.summary",
                Component.translatable(definition.descriptionTranslationKey()),
                Component.translatable(evaluation.status().translationKey()),
                definition.currencyReward(),
                Instant.ofEpochMilli(evaluation.nextResetEpochMillis()).toString()), false);
        evaluation.requirements().forEach(requirement -> {
            var dailyContract = dailyContracts.get(requirement.dailyContractId());
            Component dailyContractName = dailyContract == null
                    ? Component.literal(requirement.dailyContractId().toString())
                    : Component.translatable(dailyContract.translationKey());
            source.sendSuccess(() -> Component.translatable(
                    "command.rovenfall.expedition.info.requirement",
                    dailyContractName,
                    requirement.dailyContractId().toString(),
                    requirement.currentCompletions(),
                    requirement.requiredCompletions()), false);
        });
        return 1;
    }

    private static int claimWeeklyExpedition(
            CommandSourceStack source,
            Identifier expeditionId) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        PlatformSavedData state = PlatformSavedData.get(source.getServer());
        var retainedDefinitions = WeeklyExpeditionReloadListener.snapshot(source.getServer());
        if (retainedDefinitions.isEmpty() || retainedDefinitions.orElseThrow().isEmpty()) {
            return failure(source, "command.rovenfall.expedition.error.catalog_unavailable");
        }
        var definition = retainedDefinitions.orElseThrow().get(expeditionId);
        if (definition == null) {
            return failure(source, "command.rovenfall.expedition.error.not_found", expeditionId.toString());
        }
        var result = WeeklyExpeditionService.claim(
                state,
                player.getUUID(),
                expeditionId,
                definition,
                Instant.now().toEpochMilli(),
                EconomyConfig.initialBalance(),
                EconomyConfig.maximumBalance());
        return switch (result.status()) {
            case SUCCESS -> {
                source.sendSuccess(() -> Component.translatable(
                        "command.rovenfall.expedition.claim.success",
                        Component.translatable(definition.translationKey()),
                        result.awardedCurrency(),
                        result.balance()), false);
                yield 1;
            }
            case ALREADY_CLAIMED -> {
                source.sendSuccess(() -> Component.translatable(
                        "command.rovenfall.expedition.claim.already_claimed",
                        Component.translatable(definition.translationKey()),
                        Instant.ofEpochMilli(result.evaluation().nextResetEpochMillis()).toString()), false);
                yield 1;
            }
            case IN_PROGRESS -> {
                long met = result.evaluation().requirements().stream()
                        .filter(WeeklyExpeditionService.Requirement::met)
                        .count();
                yield failure(
                        source,
                        "command.rovenfall.expedition.claim.in_progress",
                        Component.translatable(definition.translationKey()),
                        met,
                        result.evaluation().requirements().size());
            }
            case READ_ONLY_SCHEMA -> failure(
                    source, "command.rovenfall.admin.error.read_only_schema", state.schemaVersion());
            case TRANSACTION_CONFLICT -> failure(
                    source, "command.rovenfall.expedition.error.transaction_conflict");
            case REWARD_FAILED -> failure(
                    source,
                    "command.rovenfall.expedition.error.reward_failed",
                    result.economyStatus().map(value -> value.name().toLowerCase(java.util.Locale.ROOT))
                            .orElse("unknown"));
            case INVALID_REQUEST, CLAIMABLE -> failure(
                    source, "command.rovenfall.expedition.error.invalid_request");
        };
    }

    private static int viewCareer(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        PlatformSavedData state = PlatformSavedData.get(source.getServer());
        Optional<CareerCatalog> retainedCatalog = CareerDefinitionReloadListener.snapshot(source.getServer());
        if (retainedCatalog.isEmpty()) {
            return failure(source, "command.rovenfall.career.error.catalog_unavailable");
        }
        CareerCatalog catalog = retainedCatalog.orElseThrow();
        var careers = state.playerCareerState(player.getUUID());
        source.sendSuccess(() -> Component.translatable("command.rovenfall.career.info.header"), false);
        careers.activeCareer().ifPresentOrElse(
                careerId -> source.sendSuccess(() -> Component.translatable(
                        "command.rovenfall.career.info.active",
                        careerName(catalog, careerId),
                        careerId.toString()), false),
                () -> source.sendSuccess(() -> Component.translatable(
                        "command.rovenfall.career.info.active.none"), false));
        source.sendSuccess(() -> Component.translatable(
                "command.rovenfall.career.info.learned.header", careers.learnedCareers().size()), false);
        careers.learnedCareers().stream().sorted().forEach(careerId -> {
            long experience = careers.experience(careerId);
            var definition = catalog.definition(careerId);
            if (definition.isPresent()) {
                source.sendSuccess(() -> Component.translatable(
                        "command.rovenfall.career.info.learned.line",
                        Component.translatable(definition.orElseThrow().translationKey()),
                        careerId.toString(),
                        definition.orElseThrow().level(experience),
                        experience), false);
            } else {
                source.sendSuccess(() -> Component.translatable(
                        "command.rovenfall.career.info.learned.unresolved",
                        careerId.toString(),
                        experience), false);
            }
        });
        source.sendSuccess(() -> Component.translatable(
                "command.rovenfall.career.info.catalog.header", catalog.size()), false);
        catalog.ids().stream().sorted().forEach(careerId -> {
            var definition = catalog.definition(careerId).orElseThrow();
            source.sendSuccess(() -> Component.translatable(
                    "command.rovenfall.career.info.catalog.line",
                    Component.translatable(definition.translationKey()),
                    careerId.toString(),
                    definition.tier()), false);
        });
        return 1;
    }

    private static int explainCareerPromotion(
            CommandSourceStack source,
            Identifier targetCareer) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        PlatformSavedData state = PlatformSavedData.get(source.getServer());
        Optional<CareerCatalog> retainedCatalog = CareerDefinitionReloadListener.snapshot(source.getServer());
        if (retainedCatalog.isEmpty()) {
            return failure(source, "command.rovenfall.career.error.catalog_unavailable");
        }
        Optional<Map<ActivityTrack, Integer>> retainedLevels = activityLevels(source, state, player.getUUID());
        if (retainedLevels.isEmpty()) {
            return failure(source, "command.rovenfall.career.error.activity_levels_unavailable");
        }
        CareerCatalog catalog = retainedCatalog.orElseThrow();
        var evaluation = CareerPromotionService.evaluate(
                state, catalog, player.getUUID(), targetCareer, retainedLevels.orElseThrow());
        sendCareerEvaluation(source, catalog, evaluation);
        return evaluation.allowed() ? 1 : 0;
    }

    private static int promoteCareer(
            CommandSourceStack source,
            Identifier targetCareer,
            UUID transactionId) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        PlatformSavedData state = PlatformSavedData.get(source.getServer());
        Optional<CareerCatalog> retainedCatalog = CareerDefinitionReloadListener.snapshot(source.getServer());
        if (retainedCatalog.isEmpty()) {
            return failure(source, "command.rovenfall.career.error.catalog_unavailable");
        }
        Optional<Map<ActivityTrack, Integer>> retainedLevels = activityLevels(source, state, player.getUUID());
        if (retainedLevels.isEmpty()) {
            return failure(source, "command.rovenfall.career.error.activity_levels_unavailable");
        }
        CareerCatalog catalog = retainedCatalog.orElseThrow();
        var result = CareerPromotionService.promote(
                state,
                catalog,
                player.getUUID(),
                targetCareer,
                retainedLevels.orElseThrow(),
                Instant.now().toEpochMilli(),
                transactionId);
        return switch (result.status()) {
            case SUCCESS -> {
                var evaluation = result.evaluation().orElseThrow();
                source.sendSuccess(() -> Component.translatable(
                        "command.rovenfall.career.promote.success",
                        careerName(catalog, targetCareer),
                        evaluation.promotionCost(),
                        state.economyBalance(player.getUUID()).orElse(0L),
                        transactionId), false);
                yield 1;
            }
            case DUPLICATE_TRANSACTION -> {
                source.sendSuccess(() -> Component.translatable(
                        "command.rovenfall.career.promote.duplicate",
                        transactionId,
                        careerName(catalog, targetCareer)), false);
                yield 1;
            }
            default -> failure(
                    source,
                    "command.rovenfall.career.promote.error",
                    Component.translatable(result.status().evaluationTranslationKey()),
                    result.status().id(),
                    transactionId);
        };
    }

    private static void sendCareerEvaluation(
            CommandSourceStack source,
            CareerCatalog catalog,
            CareerPromotionService.PromotionEvaluation evaluation) {
        Component verdict = Component.translatable(evaluation.allowed()
                ? "command.rovenfall.career.explain.verdict.allowed"
                : "command.rovenfall.career.explain.verdict.denied");
        int tier = evaluation.definition().map(definition -> definition.tier()).orElse(0);
        source.sendSuccess(() -> Component.translatable(
                "command.rovenfall.career.explain.summary",
                careerName(catalog, evaluation.targetCareer()),
                evaluation.targetCareer(),
                tier,
                verdict,
                Component.translatable(evaluation.status().evaluationTranslationKey()),
                evaluation.status().id(),
                evaluation.promotionCost(),
                evaluation.balance()), false);
        evaluation.parentRequirements().forEach(requirement -> source.sendSuccess(() -> Component.translatable(
                "command.rovenfall.career.explain.parent",
                careerName(catalog, requirement.careerId()),
                requirement.careerId(),
                careerCondition(requirement.met())), false));
        evaluation.activityRequirements().forEach(requirement -> source.sendSuccess(() -> Component.translatable(
                "command.rovenfall.career.explain.activity",
                Component.translatable(requirement.track().translationKey()),
                requirement.currentLevel(),
                requirement.requiredLevel(),
                careerCondition(requirement.met())), false));
        if (evaluation.resetCareers().isEmpty()) {
            source.sendSuccess(() -> Component.translatable(
                    "command.rovenfall.career.explain.reset.none"), false);
        } else {
            source.sendSuccess(() -> Component.translatable(
                    "command.rovenfall.career.explain.reset.header"), false);
            evaluation.resetCareers().stream().sorted().forEach(careerId -> source.sendSuccess(
                    () -> Component.translatable(
                            "command.rovenfall.career.explain.reset.line",
                            careerName(catalog, careerId),
                            careerId.toString()), false));
        }
    }

    private static Optional<Map<ActivityTrack, Integer>> activityLevels(
            CommandSourceStack source,
            PlatformSavedData state,
            UUID playerId) {
        Map<ActivityTrack, Integer> levels = new EnumMap<>(ActivityTrack.class);
        for (ActivityTrack track : ActivityTrack.values()) {
            var definition = ActivityLevelReloadListener.get(source.getServer(), track);
            if (definition.isEmpty()) {
                return Optional.empty();
            }
            levels.put(track, definition.orElseThrow().progress(
                    state.activityExperience(playerId, track)).level());
        }
        return Optional.of(Map.copyOf(levels));
    }

    private static Component careerName(CareerCatalog catalog, Identifier careerId) {
        return catalog.definition(careerId)
                .<Component>map(definition -> Component.translatable(definition.translationKey()))
                .orElseGet(() -> Component.literal(careerId.toString()));
    }

    private static Component careerCondition(boolean met) {
        return Component.translatable(met
                ? "command.rovenfall.career.condition.met"
                : "command.rovenfall.career.condition.unmet");
    }

    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestCareerIds(
            com.mojang.brigadier.context.CommandContext<CommandSourceStack> context,
            com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggest(
                CareerDefinitionReloadListener.snapshot(context.getSource().getServer())
                        .stream()
                        .flatMap(catalog -> catalog.ids().stream())
                        .sorted()
                        .map(Identifier::toString)
                        .toList(),
                builder);
    }

    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions>
            suggestChallengeIds(
                    com.mojang.brigadier.context.CommandContext<CommandSourceStack> context,
                    com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggest(
                ActivityChallengeReloadListener.snapshot(context.getSource().getServer())
                        .stream()
                        .flatMap(definitions -> definitions.keySet().stream())
                        .sorted()
                        .map(Identifier::toString)
                        .toList(),
                builder);
    }

    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions>
            suggestDailyContractIds(
                    com.mojang.brigadier.context.CommandContext<CommandSourceStack> context,
                    com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggest(
                DailyContractReloadListener.snapshot(context.getSource().getServer())
                        .stream()
                        .flatMap(definitions -> definitions.keySet().stream())
                        .sorted()
                        .map(Identifier::toString)
                        .toList(),
                builder);
    }

    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions>
            suggestWeeklyExpeditionIds(
                    com.mojang.brigadier.context.CommandContext<CommandSourceStack> context,
                    com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggest(
                WeeklyExpeditionReloadListener.snapshot(context.getSource().getServer())
                        .stream()
                        .flatMap(definitions -> definitions.keySet().stream())
                        .sorted()
                        .map(Identifier::toString)
                        .toList(),
                builder);
    }

    private static int viewSkills(
            CommandSourceStack source,
            Identifier requestedCareer) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        PlatformSavedData state = PlatformSavedData.get(source.getServer());
        Optional<CareerCatalog> retainedCatalog = CareerDefinitionReloadListener.snapshot(source.getServer());
        if (retainedCatalog.isEmpty()) {
            return failure(source, "command.rovenfall.career.error.catalog_unavailable");
        }
        CareerCatalog catalog = retainedCatalog.orElseThrow();
        var careers = state.playerCareerState(player.getUUID());
        Identifier careerId = requestedCareer;
        if (careerId == null) {
            if (careers.activeCareer().isEmpty()) {
                return failure(source, "command.rovenfall.skill.error.no_active_career");
            }
            careerId = careers.activeCareer().orElseThrow();
        }
        if (!careers.learnedCareers().contains(careerId)) {
            return failure(source, "command.rovenfall.skill.error.career_not_learned", careerId);
        }
        var definition = catalog.definition(careerId);
        if (definition.isEmpty()) {
            return failure(source, "command.rovenfall.skill.error.career_unresolved", careerId);
        }
        var progress = careers.progress(careerId);
        int level = definition.orElseThrow().level(progress.experience());
        int earned = progress.earnedSkillPoints(definition.orElseThrow());
        int available = progress.availableSkillPoints(definition.orElseThrow());
        Identifier displayedCareer = careerId;
        source.sendSuccess(() -> Component.translatable(
                "command.rovenfall.skill.info.header",
                careerName(catalog, displayedCareer),
                displayedCareer,
                level,
                progress.experience(),
                earned,
                progress.spentSkillPoints(),
                available), false);
        if (progress.skillRanks().isEmpty()) {
            source.sendSuccess(() -> Component.translatable(
                    "command.rovenfall.skill.info.none"), false);
        } else {
            progress.skillRanks().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
                var binding = catalog.skill(entry.getKey());
                if (binding.isPresent()) {
                    source.sendSuccess(() -> Component.translatable(
                            "command.rovenfall.skill.info.line",
                            skillName(catalog, entry.getKey()),
                            entry.getKey(),
                            entry.getValue(),
                            binding.orElseThrow().definition().maximumRank()), false);
                } else {
                    source.sendSuccess(() -> Component.translatable(
                            "command.rovenfall.skill.info.unresolved",
                            entry.getKey(),
                            entry.getValue()), false);
                }
            });
        }
        return 1;
    }

    private static int explainSkillUnlock(
            CommandSourceStack source,
            Identifier skillId) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        Optional<CareerCatalog> retainedCatalog = CareerDefinitionReloadListener.snapshot(source.getServer());
        if (retainedCatalog.isEmpty()) {
            return failure(source, "command.rovenfall.career.error.catalog_unavailable");
        }
        CareerCatalog catalog = retainedCatalog.orElseThrow();
        var evaluation = CareerSkillService.evaluateUnlock(
                PlatformSavedData.get(source.getServer()), catalog, player.getUUID(), skillId);
        sendSkillEvaluation(source, catalog, evaluation);
        return evaluation.allowed() ? 1 : 0;
    }

    private static int explainSkillReset(
            CommandSourceStack source,
            Identifier careerId) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        Optional<CareerCatalog> retainedCatalog = CareerDefinitionReloadListener.snapshot(source.getServer());
        if (retainedCatalog.isEmpty()) {
            return failure(source, "command.rovenfall.career.error.catalog_unavailable");
        }
        CareerCatalog catalog = retainedCatalog.orElseThrow();
        var evaluation = CareerSkillService.evaluateReset(
                PlatformSavedData.get(source.getServer()), catalog, player.getUUID(), careerId);
        sendSkillEvaluation(source, catalog, evaluation);
        return evaluation.allowed() ? 1 : 0;
    }

    private static int unlockSkill(
            CommandSourceStack source,
            Identifier skillId,
            UUID transactionId) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        Optional<CareerCatalog> retainedCatalog = CareerDefinitionReloadListener.snapshot(source.getServer());
        if (retainedCatalog.isEmpty()) {
            return failure(source, "command.rovenfall.career.error.catalog_unavailable");
        }
        CareerCatalog catalog = retainedCatalog.orElseThrow();
        PlatformSavedData state = PlatformSavedData.get(source.getServer());
        var result = CareerSkillService.unlock(
                state, catalog, player.getUUID(), skillId, Instant.now().toEpochMilli(), transactionId);
        return switch (result.status()) {
            case SUCCESS -> {
                var evaluation = result.evaluation().orElseThrow();
                int rank = state.playerCareerState(player.getUUID())
                        .progress(evaluation.careerId()).skillRank(skillId);
                source.sendSuccess(() -> Component.translatable(
                        "command.rovenfall.skill.unlock.success",
                        skillName(catalog, skillId),
                        rank,
                        evaluation.maximumRank(),
                        transactionId), false);
                yield 1;
            }
            case DUPLICATE_TRANSACTION -> {
                source.sendSuccess(() -> Component.translatable(
                        "command.rovenfall.skill.unlock.duplicate", transactionId, skillName(catalog, skillId)), false);
                yield 1;
            }
            default -> failure(
                    source,
                    "command.rovenfall.skill.mutation.error",
                    Component.translatable(result.status().evaluationTranslationKey()),
                    result.status().id(),
                    transactionId);
        };
    }

    private static int resetSkills(
            CommandSourceStack source,
            Identifier careerId,
            UUID transactionId) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        Optional<CareerCatalog> retainedCatalog = CareerDefinitionReloadListener.snapshot(source.getServer());
        if (retainedCatalog.isEmpty()) {
            return failure(source, "command.rovenfall.career.error.catalog_unavailable");
        }
        CareerCatalog catalog = retainedCatalog.orElseThrow();
        PlatformSavedData state = PlatformSavedData.get(source.getServer());
        var result = CareerSkillService.reset(
                state, catalog, player.getUUID(), careerId, Instant.now().toEpochMilli(), transactionId);
        return switch (result.status()) {
            case SUCCESS -> {
                var evaluation = result.evaluation().orElseThrow();
                source.sendSuccess(() -> Component.translatable(
                        "command.rovenfall.skill.reset.success",
                        careerName(catalog, careerId),
                        evaluation.spentPoints(),
                        evaluation.currencyCost(),
                        state.economyBalance(player.getUUID()).orElse(0L),
                        transactionId), false);
                yield 1;
            }
            case DUPLICATE_TRANSACTION -> {
                source.sendSuccess(() -> Component.translatable(
                        "command.rovenfall.skill.reset.duplicate",
                        transactionId,
                        careerName(catalog, careerId)), false);
                yield 1;
            }
            default -> failure(
                    source,
                    "command.rovenfall.skill.mutation.error",
                    Component.translatable(result.status().evaluationTranslationKey()),
                    result.status().id(),
                    transactionId);
        };
    }

    private static void sendSkillEvaluation(
            CommandSourceStack source,
            CareerCatalog catalog,
            CareerSkillService.Evaluation evaluation) {
        Component verdict = Component.translatable(evaluation.allowed()
                ? "command.rovenfall.skill.explain.verdict.allowed"
                : "command.rovenfall.skill.explain.verdict.denied");
        if (evaluation.operation() == org.dldyou.rovenfall.careers.SkillMutationReceipt.Operation.RESET) {
            source.sendSuccess(() -> Component.translatable(
                    "command.rovenfall.skill.explain.reset",
                    careerName(catalog, evaluation.careerId()),
                    evaluation.careerId(),
                    verdict,
                    Component.translatable(evaluation.status().evaluationTranslationKey()),
                    evaluation.status().id(),
                    evaluation.spentPoints(),
                    evaluation.currencyCost(),
                    evaluation.balance()), false);
            return;
        }
        Identifier skillId = evaluation.skillId().orElse(null);
        source.sendSuccess(() -> Component.translatable(
                "command.rovenfall.skill.explain.unlock",
                skillName(catalog, skillId),
                String.valueOf(skillId),
                evaluation.careerId() == null
                        ? Component.literal("unknown")
                        : careerName(catalog, evaluation.careerId()),
                evaluation.rank(),
                evaluation.maximumRank(),
                verdict,
                Component.translatable(evaluation.status().evaluationTranslationKey()),
                evaluation.status().id(),
                evaluation.pointCost(),
                evaluation.availablePoints()), false);
        evaluation.prerequisites().forEach(requirement -> source.sendSuccess(() -> Component.translatable(
                "command.rovenfall.skill.explain.prerequisite",
                skillName(catalog, requirement.skillId()),
                requirement.skillId(),
                careerCondition(requirement.met())), false));
        evaluation.binding().ifPresent(binding -> {
            source.sendSuccess(() -> Component.translatable(
                    "command.rovenfall.skill.explain.scope",
                    Component.translatable("career_skill_scope.rovenfall."
                            + binding.definition().scope().getSerializedName())), false);
            binding.definition().effects().forEach(effect -> {
                String magnitude = java.math.BigDecimal.valueOf(
                                effect.magnitudePerRankBasisPoints(), 2)
                        .stripTrailingZeros().toPlainString();
                if (effect.track().isPresent()) {
                    source.sendSuccess(() -> Component.translatable(
                            "command.rovenfall.skill.explain.effect.track",
                            magnitude,
                            Component.translatable(effect.track().orElseThrow().translationKey())), false);
                } else {
                    source.sendSuccess(() -> Component.translatable(
                            "command.rovenfall.skill.explain.effect.all", magnitude), false);
                }
            });
            binding.definition().active().ifPresent(active -> source.sendSuccess(() -> Component.translatable(
                    "command.rovenfall.skill.explain.active",
                    active.effectId(),
                    active.durationTicks(),
                    active.amplifier() + 1,
                    active.cooldownSeconds()), false));
        });
    }

    private static int viewActiveSkillSlots(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        Optional<CareerCatalog> retainedCatalog = CareerDefinitionReloadListener.snapshot(source.getServer());
        if (retainedCatalog.isEmpty()) {
            return failure(source, "command.rovenfall.career.error.catalog_unavailable");
        }
        CareerCatalog catalog = retainedCatalog.orElseThrow();
        var activeSkills = PlatformSavedData.get(source.getServer())
                .playerCareerState(player.getUUID()).activeSkills();
        long now = Instant.now().toEpochMilli();
        source.sendSuccess(() -> Component.translatable("command.rovenfall.active_skill.slots.header"), false);
        for (int slot = 1; slot <= org.dldyou.rovenfall.careers.ActiveSkillState.SLOT_COUNT; slot++) {
            Optional<Identifier> retainedSkill = activeSkills.slot(slot);
            if (retainedSkill.isEmpty()) {
                int displayedSlot = slot;
                source.sendSuccess(() -> Component.translatable(
                        "command.rovenfall.active_skill.slots.empty", displayedSlot), false);
                continue;
            }
            Identifier skillId = retainedSkill.orElseThrow();
            long remainingMillis = Math.max(0, activeSkills.cooldownReadyAt(skillId) - now);
            long remainingSeconds = remainingMillis == 0 ? 0 : 1 + (remainingMillis - 1) / 1_000;
            int displayedSlot = slot;
            source.sendSuccess(() -> Component.translatable(
                    remainingSeconds == 0
                            ? "command.rovenfall.active_skill.slots.ready"
                            : "command.rovenfall.active_skill.slots.cooldown",
                    displayedSlot,
                    skillName(catalog, skillId),
                    skillId,
                    remainingSeconds), false);
        }
        return 1;
    }

    private static int equipActiveSkill(
            CommandSourceStack source,
            int slot,
            Identifier skillId) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        Optional<CareerCatalog> retainedCatalog = CareerDefinitionReloadListener.snapshot(source.getServer());
        if (retainedCatalog.isEmpty()) {
            return failure(source, "command.rovenfall.career.error.catalog_unavailable");
        }
        CareerCatalog catalog = retainedCatalog.orElseThrow();
        var result = ActiveSkillService.equip(
                PlatformSavedData.get(source.getServer()),
                catalog,
                player.getUUID(),
                slot,
                skillId,
                Instant.now().toEpochMilli());
        if (result.status() != ActiveSkillService.Status.SUCCESS) {
            return failure(
                    source,
                    "command.rovenfall.active_skill.mutation.error",
                    Component.translatable(result.status().translationKey()),
                    result.status().id());
        }
        source.sendSuccess(() -> Component.translatable(
                "command.rovenfall.active_skill.equip.success",
                skillName(catalog, skillId),
                slot), false);
        return 1;
    }

    private static int clearActiveSkill(
            CommandSourceStack source,
            int slot) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        PlatformSavedData state = PlatformSavedData.get(source.getServer());
        Identifier skillId = state.playerCareerState(player.getUUID())
                .activeSkills().slot(slot).orElse(null);
        var result = ActiveSkillService.clear(
                state, player.getUUID(), slot, Instant.now().toEpochMilli());
        if (result.status() != ActiveSkillService.Status.SUCCESS) {
            return failure(
                    source,
                    "command.rovenfall.active_skill.mutation.error",
                    Component.translatable(result.status().translationKey()),
                    result.status().id());
        }
        Optional<CareerCatalog> catalog = CareerDefinitionReloadListener.snapshot(source.getServer());
        source.sendSuccess(() -> Component.translatable(
                "command.rovenfall.active_skill.clear.success",
                slot,
                catalog.<Component>map(value -> skillName(value, skillId))
                        .orElseGet(() -> Component.literal(String.valueOf(skillId)))), false);
        return 1;
    }

    private static int useActiveSkill(
            CommandSourceStack source,
            int slot) throws CommandSyntaxException {
        return ActiveSkillGameplay.use(
                        source.getPlayerOrException(), slot, Instant.now().toEpochMilli())
                .filter(result -> result.status() == ActiveSkillService.Status.SUCCESS)
                .map(ignored -> 1)
                .orElse(0);
    }

    private static Component skillName(CareerCatalog catalog, Identifier skillId) {
        if (skillId == null) {
            return Component.literal("unknown");
        }
        return catalog.skill(skillId)
                .<Component>map(binding -> Component.translatable(binding.definition().translationKey()))
                .orElseGet(() -> Component.literal(skillId.toString()));
    }

    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestSkillIds(
            com.mojang.brigadier.context.CommandContext<CommandSourceStack> context,
            com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggest(
                CareerDefinitionReloadListener.snapshot(context.getSource().getServer())
                        .stream()
                        .flatMap(catalog -> catalog.skillIds().stream())
                        .sorted()
                        .map(Identifier::toString)
                        .toList(),
                builder);
    }

    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions>
            suggestActiveSkillIds(
                    com.mojang.brigadier.context.CommandContext<CommandSourceStack> context,
                    com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggest(
                CareerDefinitionReloadListener.snapshot(context.getSource().getServer())
                        .stream()
                        .flatMap(catalog -> catalog.activeSkillIds().stream())
                        .sorted()
                        .map(Identifier::toString)
                        .toList(),
                builder);
    }

    private static int useManagedPortal(
            CommandSourceStack source, Identifier portalId) throws CommandSyntaxException {
        var result = WorldTravelService.transit(
                source.getPlayerOrException(), portalId, Instant.now().toEpochMilli());
        return switch (result.status()) {
            case SUCCESS -> {
                if (portalId == null) {
                    String destination = result.destination().orElseThrow().equals(WorldCombatService.WILDERNESS_DIMENSION)
                            ? "command.rovenfall.portal.success.wilderness"
                            : "command.rovenfall.portal.success.hub";
                    source.sendSuccess(() -> Component.translatable(destination), false);
                } else {
                    source.sendSuccess(() -> Component.translatable(
                            "command.rovenfall.portal.success.custom",
                            result.portalId().orElseThrow().toString(),
                            result.destination().orElseThrow().identifier().toString()), false);
                }
                yield 1;
            }
            case COOLDOWN -> failure(
                    source, "command.rovenfall.portal.error.cooldown", result.retryAfterSeconds());
            case RATE_LIMITED -> failure(source, "command.rovenfall.portal.error.rate_limited");
            case IN_COMBAT -> failure(
                    source, "command.rovenfall.portal.error.in_combat", result.retryAfterSeconds());
            case RESET_PENDING -> failure(source, "command.rovenfall.portal.error.reset_pending");
            case MOUNTED -> failure(source, "command.rovenfall.portal.error.mounted");
            case PORTAL_NOT_FOUND -> failure(
                    source, "command.rovenfall.portal.error.not_found", portalId == null ? "" : portalId.toString());
            case UNSUPPORTED_ORIGIN -> failure(source, "command.rovenfall.portal.error.unsupported_origin");
            case OUTSIDE_PORTAL_RING -> failure(source, "command.rovenfall.portal.error.outside_ring");
            case DESTINATION_UNAVAILABLE -> failure(source, "command.rovenfall.portal.error.destination_unavailable");
            case NO_SAFE_ARRIVAL -> failure(source, "command.rovenfall.portal.error.no_safe_arrival");
            case TELEPORT_REJECTED -> failure(source, "command.rovenfall.portal.error.teleport_rejected");
            case READ_ONLY_SCHEMA -> failure(source, "command.rovenfall.admin.error.read_only_schema",
                    PlatformSavedData.get(source.getServer()).schemaVersion());
            case INVALID_REQUEST -> failure(source, "command.rovenfall.portal.error.invalid_request");
        };
    }

    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestPortalIds(
            com.mojang.brigadier.context.CommandContext<CommandSourceStack> context,
            com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggest(
                PlatformSavedData.get(context.getSource().getServer()).portalIds().stream()
                        .sorted()
                        .map(Identifier::toString)
                        .toList(),
                builder);
    }

    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestShopTemplateIds(
            com.mojang.brigadier.context.CommandContext<CommandSourceStack> context,
            com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggest(
                ShopTemplateReloadListener.snapshot(context.getSource().getServer()).templates().keySet().stream()
                        .sorted()
                        .map(Identifier::toString)
                        .toList(),
                builder);
    }

    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestShopIds(
            com.mojang.brigadier.context.CommandContext<CommandSourceStack> context,
            com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggest(
                PlatformSavedData.get(context.getSource().getServer()).shopInstancesView().keySet().stream()
                        .sorted()
                        .map(Identifier::toString)
                        .toList(),
                builder);
    }

    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestShopOfferIds(
            com.mojang.brigadier.context.CommandContext<CommandSourceStack> context,
            com.mojang.brigadier.suggestion.SuggestionsBuilder builder,
            ShopTradeService.Direction direction) {
        Identifier shopId = IdentifierArgument.getId(context, "shop_id");
        return SharedSuggestionProvider.suggest(
                PlatformSavedData.get(context.getSource().getServer()).shopInstance(shopId).stream()
                        .flatMap(shop -> shop.offers().entrySet().stream())
                        .filter(entry -> direction == ShopTradeService.Direction.BUY
                                ? entry.getValue().buyPrice().isPresent()
                                : entry.getValue().sellPrice().isPresent())
                        .map(Map.Entry::getKey)
                        .sorted()
                        .map(Identifier::toString)
                        .toList(),
                builder);
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> tradeCommand(
            String literal,
            ShopTradeService.Direction direction) {
        var transaction = Commands.argument("transaction_id", UuidArgument.uuid())
                .executes(context -> trade(
                        context.getSource(),
                        IdentifierArgument.getId(context, "shop_id"),
                        IdentifierArgument.getId(context, "offer_id"),
                        direction,
                        IntegerArgumentType.getInteger(context, "quantity"),
                        UuidArgument.getUuid(context, "transaction_id")));
        var quantity = Commands.argument(
                        "quantity", IntegerArgumentType.integer(1, ShopTradeService.MAX_TRADE_QUANTITY))
                .executes(context -> trade(
                        context.getSource(),
                        IdentifierArgument.getId(context, "shop_id"),
                        IdentifierArgument.getId(context, "offer_id"),
                        direction,
                        IntegerArgumentType.getInteger(context, "quantity"),
                        UUID.randomUUID()))
                .then(transaction);
        var offer = Commands.argument("offer_id", IdentifierArgument.id())
                .suggests((context, builder) -> suggestShopOfferIds(context, builder, direction))
                .then(quantity);
        var shop = Commands.argument("shop_id", IdentifierArgument.id())
                .suggests(RovenfallCommands::suggestShopIds)
                .then(offer);
        return Commands.literal(literal).then(shop);
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> economyViewCommand(
            String literal, EconomyView view) {
        return Commands.literal(literal)
                .executes(context -> openEconomyGui(context.getSource(), view, 0))
                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                        .executes(context -> openEconomyGui(
                                context.getSource(), view, IntegerArgumentType.getInteger(context, "page") - 1)));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> adminClaimInfoCommand() {
        return Commands.literal("info")
                .then(Commands.argument("dimension", DimensionArgument.dimension())
                        .then(Commands.argument("chunk_x", IntegerArgumentType.integer())
                                .then(Commands.argument("chunk_z", IntegerArgumentType.integer())
                                        .executes(context -> viewClaim(
                                                context.getSource(),
                                                new ClaimKey(
                                                        DimensionArgument.getDimension(context, "dimension").dimension(),
                                                        IntegerArgumentType.getInteger(context, "chunk_x"),
                                                        IntegerArgumentType.getInteger(context, "chunk_z")))))));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> adminClaimTrustCommand() {
        return Commands.literal("trust")
                .then(Commands.argument("dimension", DimensionArgument.dimension())
                        .then(Commands.argument("chunk_x", IntegerArgumentType.integer())
                                .then(Commands.argument("chunk_z", IntegerArgumentType.integer())
                                        .then(Commands.argument("player", EntityArgument.player())
                                                .then(Commands.argument("role", StringArgumentType.word())
                                                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                                                ClaimRole.ids(), builder))
                                                        .executes(context -> setClaimRole(
                                                                context.getSource(),
                                                                new ClaimKey(
                                                                        DimensionArgument.getDimension(
                                                                                context, "dimension").dimension(),
                                                                        IntegerArgumentType.getInteger(
                                                                                context, "chunk_x"),
                                                                        IntegerArgumentType.getInteger(
                                                                                context, "chunk_z")),
                                                                EntityArgument.getPlayer(context, "player"),
                                                                StringArgumentType.getString(context, "role"),
                                                                true,
                                                                "administrator claim trust")))))));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> adminClaimUntrustCommand() {
        return Commands.literal("untrust")
                .then(Commands.argument("dimension", DimensionArgument.dimension())
                        .then(Commands.argument("chunk_x", IntegerArgumentType.integer())
                                .then(Commands.argument("chunk_z", IntegerArgumentType.integer())
                                        .then(Commands.argument("player", EntityArgument.player())
                                                .executes(context -> removeClaimRole(
                                                        context.getSource(),
                                                        new ClaimKey(
                                                                DimensionArgument.getDimension(
                                                                        context, "dimension").dimension(),
                                                                IntegerArgumentType.getInteger(context, "chunk_x"),
                                                                IntegerArgumentType.getInteger(context, "chunk_z")),
                                                        EntityArgument.getPlayer(context, "player"),
                                                        true,
                                                        "administrator claim untrust"))))));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> adminClaimSettingsCommand() {
        return Commands.literal("settings")
                .then(Commands.argument("dimension", DimensionArgument.dimension())
                        .then(Commands.argument("chunk_x", IntegerArgumentType.integer())
                                .then(Commands.argument("chunk_z", IntegerArgumentType.integer())
                                        .then(Commands.argument("entry_restricted", BoolArgumentType.bool())
                                                .then(Commands.argument(
                                                                "public_interactions", BoolArgumentType.bool())
                                                        .executes(context -> setClaimSettings(
                                                                context.getSource(),
                                                                new ClaimKey(
                                                                        DimensionArgument.getDimension(
                                                                                context, "dimension").dimension(),
                                                                        IntegerArgumentType.getInteger(
                                                                                context, "chunk_x"),
                                                                        IntegerArgumentType.getInteger(
                                                                                context, "chunk_z")),
                                                                BoolArgumentType.getBool(context, "entry_restricted"),
                                                                BoolArgumentType.getBool(context, "public_interactions"),
                                                                true,
                                                                "administrator claim settings")))))));
    }

    private static int setRole(CommandSourceStack source, net.minecraft.server.level.ServerPlayer target, String roleId, String reason) {
        PlatformSavedData state = PlatformSavedData.get(source.getServer());
        UUID transactionId = UUID.randomUUID();

        var result = AdministrationService.changeRole(
                state,
                actorId(source),
                authorizationOverride(source, state),
                target.getUUID(),
                roleId,
                reason,
                Instant.now().toEpochMilli(),
                transactionId
        );

        return switch (result.status()) {
            case SUCCESS -> {
                AdminRole role = state.roleOf(target.getUUID()).orElseThrow();
                source.sendSuccess(() -> Component.translatable(
                        "command.rovenfall.admin.role.set.success",
                        target.getDisplayName(),
                        Component.translatable(role.translationKey()),
                        result.transactionId().toString()), true);
                yield 1;
            }
            case UNAUTHORIZED -> failure(source, "command.rovenfall.admin.error.unauthorized");
            case INVALID_ROLE -> failure(source, "command.rovenfall.admin.error.invalid_role", roleId);
            case INVALID_REASON -> failure(source, "command.rovenfall.admin.error.invalid_reason", AdministrationService.MAX_REASON_LENGTH);
            case NO_CHANGE -> failure(source, "command.rovenfall.admin.role.set.no_change", target.getDisplayName());
            case READ_ONLY_SCHEMA -> failure(source, "command.rovenfall.admin.error.read_only_schema", state.schemaVersion());
        };
    }

    private static int createShop(
            CommandSourceStack source,
            Identifier shopId,
            Identifier templateId,
            UUID transactionId,
            String reason) {
        PlatformSavedData state = PlatformSavedData.get(source.getServer());
        return shopResult(source, state, ShopInstanceService.create(
                state,
                ShopTemplateReloadListener.snapshot(source.getServer()),
                actorId(source),
                authorizationOverride(source, state),
                shopId,
                templateId,
                Optional.empty(),
                key -> source.getServer().getLevel(key) != null,
                ShopInstance.AccessPolicy.publicAccess(),
                source.getServer().overworld().getGameTime(),
                reason,
                Instant.now().toEpochMilli(),
                transactionId), shopId);
    }

    private static int createPortal(
            CommandSourceStack source,
            Identifier portalId,
            net.minecraft.server.level.ServerLevel destination,
            BlockPos destinationPosition,
            int protectionRadius,
            int searchRadius,
            int cooldownSeconds,
            UUID transactionId,
            String reason) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        Portal portal = new Portal(
                player.level().dimension(),
                player.blockPosition(),
                destination.dimension(),
                destinationPosition,
                protectionRadius,
                searchRadius,
                cooldownSeconds);
        PlatformSavedData state = PlatformSavedData.get(source.getServer());
        return portalResult(source, state, ManagedPortalService.create(
                state,
                actorId(source),
                authorizationOverride(source, state),
                portalId,
                portal,
                key -> source.getServer().getLevel(key) != null,
                reason,
                Instant.now().toEpochMilli(),
                transactionId), portalId);
    }

    private static int deletePortal(
            CommandSourceStack source, Identifier portalId, UUID transactionId, String reason) {
        PlatformSavedData state = PlatformSavedData.get(source.getServer());
        return portalResult(source, state, ManagedPortalService.delete(
                state,
                actorId(source),
                authorizationOverride(source, state),
                portalId,
                reason,
                Instant.now().toEpochMilli(),
                transactionId), portalId);
    }

    private static int viewManagedPortal(CommandSourceStack source, Identifier portalId) {
        Optional<Portal> portal = PlatformSavedData.get(source.getServer()).portal(portalId);
        if (portal.isEmpty()) {
            return failure(source, "command.rovenfall.admin.portal.error.not_found", portalId.toString());
        }
        Portal value = portal.orElseThrow();
        source.sendSuccess(() -> Component.translatable(
                "command.rovenfall.admin.portal.info",
                portalId.toString(),
                value.originDimension().identifier().toString(),
                value.origin().toShortString(),
                value.destinationDimension().identifier().toString(),
                value.destination().toShortString(),
                value.protectionRadius(),
                value.safeSearchRadius(),
                value.cooldownSeconds()), false);
        return 1;
    }

    private static int portalResult(
            CommandSourceStack source,
            PlatformSavedData state,
            ManagedPortalService.MutationResult result,
            Identifier portalId) {
        return switch (result.status()) {
            case SUCCESS -> {
                source.sendSuccess(() -> Component.translatable(
                        "command.rovenfall.admin.portal.success",
                        portalId.toString(),
                        result.transactionId().toString()), true);
                yield 1;
            }
            case DUPLICATE_TRANSACTION -> {
                source.sendSuccess(() -> Component.translatable(
                        "command.rovenfall.admin.portal.duplicate", result.transactionId().toString()), false);
                yield 1;
            }
            case UNAUTHORIZED -> failure(source, "command.rovenfall.admin.portal.error.unauthorized");
            case INVALID_REQUEST -> failure(source, "command.rovenfall.admin.portal.error.invalid_request");
            case INVALID_TRANSACTION -> failure(source, "command.rovenfall.admin.portal.error.invalid_transaction");
            case INVALID_REASON -> failure(
                    source, "command.rovenfall.admin.error.invalid_reason", AdministrationService.MAX_REASON_LENGTH);
            case READ_ONLY_SCHEMA -> failure(
                    source, "command.rovenfall.admin.error.read_only_schema", state.schemaVersion());
            case TRANSACTION_LEDGER_FULL -> failure(
                    source, "command.rovenfall.admin.portal.error.transaction_ledger_full");
            case PORTAL_LIMIT_REACHED -> failure(source, "command.rovenfall.admin.portal.error.limit");
            case PORTAL_EXISTS -> failure(
                    source, "command.rovenfall.admin.portal.error.exists", portalId.toString());
            case PORTAL_NOT_FOUND -> failure(
                    source, "command.rovenfall.admin.portal.error.not_found", portalId.toString());
            case DIMENSION_UNAVAILABLE -> failure(
                    source, "command.rovenfall.admin.portal.error.dimension_unavailable");
        };
    }

    private static int deleteShop(
            CommandSourceStack source, Identifier shopId, UUID transactionId, String reason) {
        PlatformSavedData state = PlatformSavedData.get(source.getServer());
        return shopResult(source, state, ShopInstanceService.delete(
                state, actorId(source), authorizationOverride(source, state), shopId, reason,
                Instant.now().toEpochMilli(), transactionId), shopId);
    }

    private static int bindShop(
            CommandSourceStack source,
            Identifier shopId,
            ShopInstance.Binding binding,
            UUID transactionId,
            String reason) {
        PlatformSavedData state = PlatformSavedData.get(source.getServer());
        return shopResult(source, state, ShopInstanceService.setBinding(
                state, actorId(source), authorizationOverride(source, state), shopId, Optional.of(binding),
                key -> source.getServer().getLevel(key) != null, reason, Instant.now().toEpochMilli(), transactionId), shopId);
    }

    private static int unbindShop(
            CommandSourceStack source, Identifier shopId, UUID transactionId, String reason) {
        PlatformSavedData state = PlatformSavedData.get(source.getServer());
        return shopResult(source, state, ShopInstanceService.setBinding(
                state, actorId(source), authorizationOverride(source, state), shopId, Optional.empty(),
                key -> source.getServer().getLevel(key) != null, reason, Instant.now().toEpochMilli(), transactionId), shopId);
    }

    private static int setShopAccess(
            CommandSourceStack source,
            Identifier shopId,
            int maxDistance,
            UUID transactionId,
            String reason) {
        PlatformSavedData state = PlatformSavedData.get(source.getServer());
        return shopResult(source, state, ShopInstanceService.setAccessPolicy(
                state, actorId(source), authorizationOverride(source, state), shopId,
                new ShopInstance.AccessPolicy(maxDistance), reason, Instant.now().toEpochMilli(), transactionId), shopId);
    }

    private static int setShopOffer(
            CommandSourceStack source,
            Identifier shopId,
            Identifier offerId,
            net.minecraft.world.item.ItemStack item,
            long buyPrice,
            long sellPrice,
            long stock,
            long maximumStock,
            UUID transactionId,
            String reason) {
        ShopInstance.Stock stockPolicy;
        if (stock == -1 && maximumStock == -1) {
            stockPolicy = ShopInstance.Stock.unlimitedStock();
        } else {
            stockPolicy = ShopInstance.Stock.finite(stock, maximumStock);
        }
        Optional<Long> buy = buyPrice == -1 ? Optional.empty() : Optional.of(buyPrice);
        Optional<Long> sell = sellPrice == -1 ? Optional.empty() : Optional.of(sellPrice);
        PlatformSavedData state = PlatformSavedData.get(source.getServer());
        return shopResult(source, state, ShopInstanceService.putOffer(
                state, actorId(source), authorizationOverride(source, state), shopId, offerId,
                new ShopInstance.Offer(item, buy, sell, stockPolicy), reason,
                Instant.now().toEpochMilli(), transactionId), shopId);
    }

    private static int removeShopOffer(
            CommandSourceStack source,
            Identifier shopId,
            Identifier offerId,
            UUID transactionId,
            String reason) {
        PlatformSavedData state = PlatformSavedData.get(source.getServer());
        return shopResult(source, state, ShopInstanceService.removeOffer(
                state, actorId(source), authorizationOverride(source, state), shopId, offerId, reason,
                Instant.now().toEpochMilli(), transactionId), shopId);
    }

    private static int setShopRestock(
            CommandSourceStack source,
            Identifier shopId,
            Identifier offerId,
            Optional<Long> restockAmount,
            Optional<Long> restockIntervalTicks,
            UUID transactionId,
            String reason) {
        PlatformSavedData state = PlatformSavedData.get(source.getServer());
        return shopResult(source, state, ShopInstanceService.setRestockPolicy(
                state,
                actorId(source),
                authorizationOverride(source, state),
                shopId,
                offerId,
                restockAmount,
                restockIntervalTicks,
                source.getServer().overworld().getGameTime(),
                reason,
                Instant.now().toEpochMilli(),
                transactionId), shopId);
    }

    private static int shopResult(
            CommandSourceStack source,
            PlatformSavedData state,
            ShopInstanceService.MutationResult result,
            Identifier shopId) {
        return switch (result.status()) {
            case SUCCESS -> {
                source.sendSuccess(() -> Component.translatable(
                        "command.rovenfall.admin.shop.success", shopId.toString(), result.transactionId().toString()), true);
                yield 1;
            }
            case DUPLICATE_TRANSACTION -> {
                source.sendSuccess(() -> Component.translatable(
                        "command.rovenfall.admin.shop.duplicate", result.transactionId().toString()), false);
                yield 1;
            }
            case UNAUTHORIZED -> failure(source, "command.rovenfall.admin.shop.error.unauthorized");
            case INVALID_TRANSACTION -> failure(source, "command.rovenfall.admin.shop.error.invalid_transaction");
            case INVALID_REASON -> failure(
                    source, "command.rovenfall.admin.error.invalid_reason", AdministrationService.MAX_REASON_LENGTH);
            case READ_ONLY_SCHEMA -> failure(
                    source, "command.rovenfall.admin.error.read_only_schema", state.schemaVersion());
            case TRANSACTION_LEDGER_FULL -> failure(
                    source, "command.rovenfall.admin.shop.error.transaction_ledger_full");
            case SHOP_LIMIT_REACHED -> failure(source, "command.rovenfall.admin.shop.error.shop_limit");
            case SHOP_EXISTS -> failure(source, "command.rovenfall.admin.shop.error.exists", shopId.toString());
            case SHOP_NOT_FOUND -> failure(source, "command.rovenfall.admin.shop.error.not_found", shopId.toString());
            case TEMPLATE_UNRESOLVED -> failure(source, "command.rovenfall.admin.shop.error.template_unresolved");
            case DEPENDENCY_LOCKED -> failure(source, "command.rovenfall.admin.shop.error.dependency_locked");
            case OFFER_LIMIT_REACHED -> failure(source, "command.rovenfall.admin.shop.error.offer_limit");
            case OFFER_NOT_FOUND -> failure(source, "command.rovenfall.admin.shop.error.offer_not_found");
            case INVALID_REQUEST -> failure(source, "command.rovenfall.admin.shop.error.invalid_request");
        };
    }

    private static int viewShop(CommandSourceStack source, Identifier shopId) {
        PlatformSavedData state = PlatformSavedData.get(source.getServer());
        Optional<ShopInstance> existing = state.shopInstance(shopId);
        if (existing.isEmpty()) {
            return failure(source, "command.rovenfall.shop.error.not_found");
        }

        ShopInstance shop = existing.orElseThrow();
        Component templateName = ShopTemplateReloadListener.get(source.getServer(), shop.templateId())
                .<Component>map(template -> Component.translatable(template.translationKey()))
                .orElseGet(() -> Component.literal(shop.templateId().toString()));
        source.sendSuccess(() -> Component.translatable(
                "command.rovenfall.shop.info.header",
                shopId.toString(),
                templateName,
                shop.offers().size()), false);
        shop.offers().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            ShopInstance.Offer offer = entry.getValue();
            var item = offer.item();
            Component buyPrice = shopPrice(offer.buyPrice());
            Component sellPrice = shopPrice(offer.sellPrice());
            Component stock = offer.stock().unlimited()
                    ? Component.translatable("command.rovenfall.shop.info.stock_unlimited")
                    : Component.translatable(
                            "command.rovenfall.shop.info.stock_finite",
                            offer.stock().current(),
                            offer.stock().maximum());
            source.sendSuccess(() -> Component.translatable(
                    "command.rovenfall.shop.info.offer",
                    entry.getKey().toString(),
                    item.getHoverName(),
                    item.getCount(),
                    buyPrice,
                    sellPrice,
                    stock), false);
        });
        return 1;
    }

    private static Component shopPrice(Optional<Long> price) {
        return price.<Component>map(value -> Component.literal(Long.toString(value)))
                .orElseGet(() -> Component.translatable("command.rovenfall.shop.info.unavailable"));
    }

    private static int trade(
            CommandSourceStack source,
            Identifier shopId,
            Identifier offerId,
            ShopTradeService.Direction direction,
            int quantity,
            UUID transactionId) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        PlatformSavedData state = PlatformSavedData.get(source.getServer());
        Optional<ShopInstance.Offer> offer = state.shopInstance(shopId)
                .map(ShopInstance::offers)
                .map(offers -> offers.get(offerId));
        net.minecraft.world.item.ItemStack expectedItem = offer
                .map(ShopInstance.Offer::item)
                .orElse(net.minecraft.world.item.ItemStack.EMPTY);
        long expectedPrice = offer.flatMap(value -> direction == ShopTradeService.Direction.BUY
                        ? value.buyPrice()
                        : value.sellPrice())
                .orElse(-1L);
        var result = ShopTradeService.trade(
                state,
                player,
                new ShopTradeService.TradeRequest(
                        shopId, offerId, direction, quantity, expectedItem, expectedPrice, transactionId),
                source.getLevel().getGameTime(),
                Instant.now().toEpochMilli());
        return switch (result.status()) {
            case SUCCESS -> {
                source.sendSuccess(() -> Component.translatable(
                        direction == ShopTradeService.Direction.BUY
                                ? "command.rovenfall.shop.buy.success"
                                : "command.rovenfall.shop.sell.success",
                        quantity,
                        offerId.toString(),
                        transactionId.toString()), false);
                yield 1;
            }
            case DUPLICATE_TRANSACTION -> {
                source.sendSuccess(() -> Component.translatable(
                        "command.rovenfall.shop.duplicate", transactionId.toString()), false);
                yield 1;
            }
            case TRANSACTION_ID_CONFLICT -> failure(
                    source, "command.rovenfall.shop.error.transaction_id_conflict");
            case INVALID_REQUEST, INVALID_TRANSACTION -> failure(source, "command.rovenfall.shop.error.invalid_request");
            case READ_ONLY_SCHEMA -> failure(source, "command.rovenfall.shop.error.read_only");
            case TRANSACTION_LEDGER_FULL -> failure(source, "command.rovenfall.shop.error.ledger_full");
            case SHOP_NOT_FOUND, OFFER_NOT_FOUND -> failure(source, "command.rovenfall.shop.error.not_found");
            case OFFER_UNAVAILABLE -> failure(source, "command.rovenfall.shop.error.offer_unavailable");
            case DEPENDENCY_LOCKED -> failure(source, "command.rovenfall.shop.error.busy");
            case ACCESS_DENIED -> failure(source, "command.rovenfall.shop.error.access_denied");
            case STALE_OFFER -> failure(source, "command.rovenfall.shop.error.stale_offer");
            case ACCOUNT_NOT_FOUND -> failure(source, "command.rovenfall.shop.error.account_missing");
            case OVERFLOW, MAXIMUM_BALANCE_EXCEEDED -> failure(source, "command.rovenfall.shop.error.overflow");
            case INSUFFICIENT_FUNDS -> failure(source, "command.rovenfall.shop.error.insufficient_funds");
            case INSUFFICIENT_STOCK -> failure(source, "command.rovenfall.shop.error.insufficient_stock");
            case STOCK_CAPACITY_EXCEEDED -> failure(source, "command.rovenfall.shop.error.stock_capacity");
            case INSUFFICIENT_ITEMS -> failure(source, "command.rovenfall.shop.error.insufficient_items");
            case INSUFFICIENT_SPACE -> failure(source, "command.rovenfall.shop.error.insufficient_space");
            case INVENTORY_UPDATE_FAILED -> failure(source, "command.rovenfall.shop.error.inventory_update");
        };
    }

    private static int mutatePortal(
            CommandSourceStack source,
            Identifier portalId,
            PortalDefinition.Endpoint origin,
            PortalDefinition.Endpoint destination,
            int protectionRadiusChunks,
            long cooldownMillis,
            String requestedPolicy,
            boolean allowCombat,
            String reason,
            boolean create) {
        PortalDefinition.SafeArrivalPolicy policy = java.util.Arrays.stream(
                        PortalDefinition.SafeArrivalPolicy.values())
                .filter(candidate -> candidate.getSerializedName().equals(requestedPolicy))
                .findFirst().orElse(null);
        if (policy == null) {
            return failure(source, "command.rovenfall.portal.error.invalid_policy");
        }
        PlatformSavedData state = PlatformSavedData.get(source.getServer());
        UUID actor = actorId(source);
        PortalDefinition definition = new PortalDefinition(
                actor, origin, destination, protectionRadiusChunks, cooldownMillis, policy, allowCombat);
        java.util.function.Predicate<PortalDefinition.Endpoint> available = endpoint -> {
            var level = source.getServer().getLevel(endpoint.dimension());
            return level != null && level.isInWorldBounds(endpoint.position())
                    && level.getWorldBorder().isWithinBounds(endpoint.position());
        };
        PortalService.MutationResult result = create
                ? PortalService.create(
                        state, actor, hasNativeOwnerPermission(source), portalId, definition, available,
                        reason, Instant.now().toEpochMilli(), UUID.randomUUID())
                : PortalService.edit(
                        state, actor, hasNativeOwnerPermission(source), portalId, definition, available,
                        reason, Instant.now().toEpochMilli(), UUID.randomUUID());
        if (result.status() == PortalService.Status.SUCCESS) {
            source.sendSuccess(() -> Component.translatable(
                    create ? "command.rovenfall.portal.create.success" : "command.rovenfall.portal.edit.success",
                    portalId.toString()), true);
            return 1;
        }
        return portalMutationFailure(source, result.status(), portalId);
    }

    private static int deletePortal(CommandSourceStack source, Identifier portalId, String reason) {
        PlatformSavedData state = PlatformSavedData.get(source.getServer());
        PortalService.MutationResult result = PortalService.delete(
                state, actorId(source), hasNativeOwnerPermission(source), portalId, reason,
                Instant.now().toEpochMilli(), UUID.randomUUID());
        if (result.status() == PortalService.Status.SUCCESS) {
            source.sendSuccess(() -> Component.translatable(
                    "command.rovenfall.portal.delete.success", portalId.toString()), true);
            return 1;
        }
        return portalMutationFailure(source, result.status(), portalId);
    }

    private static int viewPortal(CommandSourceStack source, Identifier portalId) {
        PortalDefinition definition = PlatformSavedData.get(source.getServer()).portalDefinition(portalId).orElse(null);
        if (definition == null) {
            return failure(source, "command.rovenfall.portal.error.not_found", portalId.toString());
        }
        source.sendSuccess(() -> Component.translatable(
                "command.rovenfall.portal.info",
                portalId.toString(),
                definition.origin().auditSummary(),
                definition.destination().auditSummary(),
                definition.protectionRadiusChunks(),
                definition.cooldownMillis() / 1_000L,
                definition.safeArrivalPolicy().getSerializedName(),
                definition.allowCombat()), false);
        return 1;
    }

    private static int listPortals(CommandSourceStack source, int page) {
        var portals = PlatformSavedData.get(source.getServer()).portalDefinitions();
        int totalPages = Math.max(1, (portals.size() + PORTAL_PAGE_SIZE - 1) / PORTAL_PAGE_SIZE);
        if (page < 0 || page >= totalPages) {
            return failure(source, "command.rovenfall.portal.error.page", totalPages);
        }
        if (portals.isEmpty()) {
            source.sendSuccess(() -> Component.translatable("command.rovenfall.portal.list.empty"), false);
            return 1;
        }
        source.sendSuccess(() -> Component.translatable(
                "command.rovenfall.portal.list.header", page + 1, totalPages, portals.size()), false);
        int start = page * PORTAL_PAGE_SIZE;
        portals.subList(start, Math.min(start + PORTAL_PAGE_SIZE, portals.size())).forEach(entry ->
                source.sendSuccess(() -> Component.translatable(
                        "command.rovenfall.portal.list.entry",
                        entry.getKey().toString(),
                        entry.getValue().origin().auditSummary(),
                        entry.getValue().destination().auditSummary()), false));
        return 1;
    }

    private static int usePortal(CommandSourceStack source, Identifier portalId) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        long now = Instant.now().toEpochMilli();
        PortalTravelService.TravelResult result = PortalTravelService.travel(
                PlatformSavedData.get(source.getServer()), player, portalId, now, UUID.randomUUID());
        if (result.status() == PortalTravelService.Status.SUCCESS) {
            source.sendSuccess(() -> Component.translatable(
                    "portal.rovenfall.travel.success", portalId.toString()), false);
            return 1;
        }
        if (result.status() == PortalTravelService.Status.COOLDOWN
                || result.status() == PortalTravelService.Status.COMBAT_LOCKED) {
            long seconds = Math.max(1L, (result.retryAtEpochMillis() - now + 999L) / 1_000L);
            return failure(source,
                    "portal.rovenfall.travel.error." + result.status().name().toLowerCase(java.util.Locale.ROOT),
                    seconds);
        }
        return failure(source,
                "portal.rovenfall.travel.error." + result.status().name().toLowerCase(java.util.Locale.ROOT));
    }

    private static int portalMutationFailure(
            CommandSourceStack source, PortalService.Status status, Identifier portalId) {
        return switch (status) {
            case INVALID_REASON -> failure(
                    source, "command.rovenfall.admin.error.invalid_reason", AdministrationService.MAX_REASON_LENGTH);
            case READ_ONLY_SCHEMA -> failure(
                    source, "command.rovenfall.admin.error.read_only_schema",
                    PlatformSavedData.get(source.getServer()).schemaVersion());
            case NOT_FOUND -> failure(source, "command.rovenfall.portal.error.not_found", portalId.toString());
            case SUCCESS -> throw new IllegalStateException("Successful portal result reached failure handling");
            default -> failure(source,
                    "command.rovenfall.portal.error." + status.name().toLowerCase(java.util.Locale.ROOT));
        };
    }

    private static int mutateProtectedRegion(
            CommandSourceStack source,
            Identifier regionId,
            ResourceKey<Level> dimension,
            int minChunkX,
            int minChunkZ,
            int maxChunkX,
            int maxChunkZ,
            String reason,
            boolean create) {
        PlatformSavedData state = PlatformSavedData.get(source.getServer());
        UUID actorId = actorId(source);
        var region = new ProtectedRegion(
                actorId, dimension, minChunkX, minChunkZ, maxChunkX, maxChunkZ);
        var result = create
                ? ProtectedRegionService.create(
                        state, actorId, authorizationOverride(source, state), regionId, region,
                        reason, Instant.now().toEpochMilli(), UUID.randomUUID())
                : ProtectedRegionService.edit(
                        state, actorId, authorizationOverride(source, state), regionId, region,
                        reason, Instant.now().toEpochMilli(), UUID.randomUUID());
        if (result.status() == ProtectedRegionService.Status.SUCCESS) {
            source.sendSuccess(() -> Component.translatable(
                    create
                            ? "command.rovenfall.admin.region.create.success"
                            : "command.rovenfall.admin.region.edit.success",
                    regionId.toString()), true);
            return 1;
        }
        return protectedRegionFailure(source, result.status());
    }

    private static int deleteProtectedRegion(
            CommandSourceStack source,
            Identifier regionId,
            String reason) {
        PlatformSavedData state = PlatformSavedData.get(source.getServer());
        var result = ProtectedRegionService.delete(
                state,
                actorId(source),
                authorizationOverride(source, state),
                regionId,
                reason,
                Instant.now().toEpochMilli(),
                UUID.randomUUID());
        if (result.status() == ProtectedRegionService.Status.SUCCESS) {
            source.sendSuccess(() -> Component.translatable(
                    "command.rovenfall.admin.region.delete.success", regionId.toString()), true);
            return 1;
        }
        return protectedRegionFailure(source, result.status());
    }

    private static int viewProtectedRegion(CommandSourceStack source, Identifier regionId) {
        ProtectedRegion region = PlatformSavedData.get(source.getServer()).protectedRegion(regionId).orElse(null);
        if (region == null) {
            return failure(source, "command.rovenfall.admin.region.error.not_found");
        }
        source.sendSuccess(() -> Component.translatable(
                "command.rovenfall.admin.region.info",
                regionId.toString(),
                region.dimension().identifier().toString(),
                region.minChunkX(),
                region.minChunkZ(),
                region.maxChunkX(),
                region.maxChunkZ(),
                region.administratorId().toString()), false);
        return 1;
    }

    private static int listProtectedRegions(CommandSourceStack source, int page) {
        var regions = PlatformSavedData.get(source.getServer()).protectedRegions();
        int totalPages = Math.max(1, (regions.size() + PROTECTED_REGION_PAGE_SIZE - 1)
                / PROTECTED_REGION_PAGE_SIZE);
        long offset = (long) page * PROTECTED_REGION_PAGE_SIZE;
        var pageEntries = offset >= regions.size()
                ? java.util.List.<java.util.Map.Entry<Identifier, ProtectedRegion>>of()
                : regions.subList((int) offset, Math.min((int) offset + PROTECTED_REGION_PAGE_SIZE, regions.size()));
        source.sendSuccess(() -> Component.translatable(
                "command.rovenfall.admin.region.list.header", regions.size(), page + 1, totalPages), false);
        pageEntries.forEach(entry -> source.sendSuccess(() -> Component.translatable(
                "command.rovenfall.admin.region.list.entry",
                entry.getKey().toString(),
                entry.getValue().dimension().identifier().toString(),
                entry.getValue().minChunkX(),
                entry.getValue().minChunkZ(),
                entry.getValue().maxChunkX(),
                entry.getValue().maxChunkZ()), false));
        return 1;
    }

    private static int protectedRegionFailure(
            CommandSourceStack source,
            ProtectedRegionService.Status status) {
        return switch (status) {
            case INVALID_REQUEST, INVALID_TRANSACTION, DUPLICATE_TRANSACTION -> failure(
                    source, "command.rovenfall.admin.region.error.invalid_request");
            case INVALID_REASON -> failure(
                    source, "command.rovenfall.admin.error.invalid_reason", AdministrationService.MAX_REASON_LENGTH);
            case READ_ONLY_SCHEMA -> failure(source, "command.rovenfall.admin.error.read_only_schema",
                    PlatformSavedData.get(source.getServer()).schemaVersion());
            case UNAUTHORIZED -> failure(source, "command.rovenfall.admin.region.error.unauthorized");
            case ALREADY_EXISTS -> failure(source, "command.rovenfall.admin.region.error.already_exists");
            case NOT_FOUND -> failure(source, "command.rovenfall.admin.region.error.not_found");
            case LIMIT_EXCEEDED -> failure(source, "command.rovenfall.admin.region.error.limit");
            case DEPENDENCY_LOCKED -> failure(source, "command.rovenfall.admin.region.error.dependency_locked");
            case SUCCESS -> throw new IllegalStateException("Successful protected region result reached failure handling");
        };
    }

    private static int explainClaimPurchase(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        PlatformSavedData state = PlatformSavedData.get(source.getServer());
        ClaimKey key = ClaimKey.at(player.level().dimension(), player.blockPosition());
        var evaluation = ClaimPurchaseService.evaluatePurchase(
                state,
                player.getUUID(),
                source.getServer().overworld().dimension(),
                player.level().dimension(),
                player.blockPosition(),
                ignored -> true,
                candidate -> isProtectedClaimRegion(source, candidate),
                ClaimConfig.basePrice(),
                ClaimConfig.priceIncrease(),
                ClaimConfig.ownershipCap());
        Component verdict = Component.translatable(evaluation.allowed()
                ? "command.rovenfall.claim.explain.verdict.allowed"
                : "command.rovenfall.claim.explain.verdict.denied");
        Component rule = Component.translatable(evaluation.status().evaluationTranslationKey());
        String owner = evaluation.ownerId().map(UUID::toString).orElse("-");
        source.sendSuccess(() -> Component.translatable(
                "command.rovenfall.claim.explain.buy",
                verdict,
                rule,
                evaluation.status().id(),
                key.dimension().identifier().toString(),
                key.chunkX(),
                key.chunkZ(),
                evaluation.price(),
                evaluation.balance(),
                evaluation.ownedClaims(),
                evaluation.ownershipCap(),
                owner), false);
        return 1;
    }

    private static int explainClaimAction(
            CommandSourceStack source, String actionId) throws CommandSyntaxException {
        ClaimProtectionService.Action action = ClaimProtectionService.Action.fromId(actionId).orElse(null);
        if (action == null) {
            return failure(source, "command.rovenfall.claim.explain.error.invalid_action");
        }
        ServerPlayer player = source.getPlayerOrException();
        PlatformSavedData state = PlatformSavedData.get(source.getServer());
        var hub = source.getServer().overworld();
        ClaimKey key = ClaimKey.at(player.level().dimension(), player.blockPosition());
        var decision = ClaimProtectionService.evaluate(
                state,
                player.getUUID(),
                authorizationOverride(source, state),
                hub.dimension(),
                hub.getRespawnData().pos(),
                ClaimConfig.protectedSpawnRadiusChunks(),
                state.isPortalProtected(player.level().dimension(), player.blockPosition())
                        || action != ClaimProtectionService.Action.ENTITY
                        && state.isBossArenaProtected(player.level().dimension(), player.blockPosition()),
                key,
                action);
        Component verdict = Component.translatable(decision.allowed()
                ? "command.rovenfall.claim.explain.verdict.allowed"
                : "command.rovenfall.claim.explain.verdict.denied");
        String owner = decision.claim().map(claim -> claim.ownerId().toString()).orElse("-");
        source.sendSuccess(() -> Component.translatable(
                "command.rovenfall.claim.explain.action",
                verdict,
                Component.translatable(decision.reason().translationKey()),
                decision.reason().id(),
                Component.translatable(action.translationKey()),
                Component.translatable(decision.role().translationKey()),
                key.dimension().identifier().toString(),
                key.chunkX(),
                key.chunkZ(),
                owner), false);
        return 1;
    }

    private static int explainClaimRole(
            CommandSourceStack source,
            ServerPlayer target,
            String roleId) throws CommandSyntaxException {
        ClaimRole requestedRole = ClaimRole.fromId(roleId).filter(value -> value != ClaimRole.OWNER).orElse(null);
        if (requestedRole == null) {
            return failure(source, "command.rovenfall.claim.error.invalid_role");
        }
        PlatformSavedData state = PlatformSavedData.get(source.getServer());
        ClaimKey key = currentClaimKey(source);
        var evaluation = ClaimManagementService.evaluateSetRole(
                state,
                actorId(source),
                authorizationOverride(source, state),
                key,
                target.getUUID(),
                requestedRole);
        Component verdict = Component.translatable(evaluation.allowed()
                ? "command.rovenfall.claim.explain.verdict.allowed"
                : "command.rovenfall.claim.explain.verdict.denied");
        String owner = evaluation.claim().map(claim -> claim.ownerId().toString()).orElse("-");
        source.sendSuccess(() -> Component.translatable(
                "command.rovenfall.claim.explain.role",
                verdict,
                Component.translatable(evaluation.status().evaluationTranslationKey()),
                evaluation.status().id(),
                target.getDisplayName(),
                Component.translatable(evaluation.currentTargetRole().translationKey()),
                Component.translatable(requestedRole.translationKey()),
                Component.translatable(evaluation.actorRole().translationKey()),
                evaluation.trustedPlayers(),
                evaluation.trustLimit(),
                owner), false);
        return 1;
    }

    private static int buyClaim(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        UUID transactionId = UUID.randomUUID();
        var result = ClaimPurchaseService.purchase(
                PlatformSavedData.get(source.getServer()),
                player.getUUID(),
                WorldTopology.HUB,
                player.level().dimension(),
                player.blockPosition(),
                ignored -> true,
                candidate -> isProtectedClaimRegion(source, candidate),
                ClaimConfig.basePrice(),
                ClaimConfig.priceIncrease(),
                ClaimConfig.ownershipCap(),
                Instant.now().toEpochMilli(),
                transactionId);
        return switch (result.status()) {
            case SUCCESS -> {
                QuestProgressRuntime.acceptEconomyEvidence(source.getServer(), transactionId);
                var claim = result.claim().orElseThrow();
                source.sendSuccess(() -> Component.translatable(
                        "command.rovenfall.claim.buy.success",
                        claim.chunkX(), claim.chunkZ(), result.price(), result.balance()), false);
                yield 1;
            }
            case DUPLICATE_TRANSACTION -> {
                source.sendSuccess(() -> Component.translatable(
                        "command.rovenfall.claim.buy.duplicate", transactionId), false);
                yield 1;
            }
            case TRANSACTION_ID_CONFLICT -> failure(source, "command.rovenfall.claim.error.transaction_id_conflict");
            case INVALID_REQUEST, INVALID_TRANSACTION -> failure(source, "command.rovenfall.claim.error.invalid_request");
            case READ_ONLY_SCHEMA -> failure(source, "command.rovenfall.claim.error.read_only");
            case NOT_IN_HUB -> failure(source, "command.rovenfall.claim.error.not_in_hub");
            case INELIGIBLE_CHUNK -> failure(source, "command.rovenfall.claim.error.ineligible");
            case PROTECTED_CHUNK -> failure(source, "command.rovenfall.claim.error.protected");
            case ALREADY_CLAIMED -> failure(source, "command.rovenfall.claim.error.already_claimed");
            case OWNERSHIP_CAP_REACHED -> failure(source, "command.rovenfall.claim.error.cap");
            case ACCOUNT_NOT_FOUND -> failure(source, "command.rovenfall.claim.error.account_missing");
            case INVALID_CONFIGURATION, PRICE_OVERFLOW -> failure(
                    source, "command.rovenfall.claim.error.invalid_configuration");
            case INSUFFICIENT_FUNDS -> failure(
                    source, "command.rovenfall.claim.error.insufficient_funds", result.price(), result.balance());
            case TRANSACTION_LEDGER_FULL -> failure(source, "command.rovenfall.claim.error.ledger_full");
        };
    }

    private static ClaimKey currentClaimKey(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        return ClaimKey.at(player.level().dimension(), player.blockPosition());
    }

    private static int viewClaim(CommandSourceStack source, ClaimKey key) {
        PlatformSavedData state = PlatformSavedData.get(source.getServer());
        var claim = state.claim(key).orElse(null);
        if (claim == null) {
            return failure(source, "command.rovenfall.claim.error.not_found", key.chunkX(), key.chunkZ());
        }
        Component actorRole = Component.translatable(claim.roleOf(actorId(source)).translationKey());
        String transferTarget = claim.pendingTransferTo().map(UUID::toString).orElse("-");
        source.sendSuccess(() -> Component.translatable(
                "command.rovenfall.claim.info",
                key.dimension().identifier().toString(),
                key.chunkX(),
                key.chunkZ(),
                claim.ownerId().toString(),
                actorRole,
                claim.trustedRoles().size(),
                claim.settings().entryRestricted(),
                claim.settings().publicInteractions(),
                transferTarget,
                claim.purchasePrice()), false);
        return 1;
    }

    private static int setClaimRole(
            CommandSourceStack source,
            ClaimKey key,
            ServerPlayer target,
            String roleId,
            boolean broadcast,
            String reason) {
        ClaimRole role = ClaimRole.fromId(roleId).filter(value -> value != ClaimRole.OWNER).orElse(null);
        if (role == null) {
            return failure(source, "command.rovenfall.claim.error.invalid_role");
        }
        PlatformSavedData state = PlatformSavedData.get(source.getServer());
        var result = ClaimManagementService.setRole(
                state,
                actorId(source),
                authorizationOverride(source, state),
                key,
                target.getUUID(),
                role,
                reason,
                Instant.now().toEpochMilli(),
                UUID.randomUUID());
        return claimMutationResult(
                source, state, result, broadcast, "command.rovenfall.claim.trust.success",
                target.getDisplayName(), Component.translatable(role.translationKey()));
    }

    private static int removeClaimRole(
            CommandSourceStack source,
            ClaimKey key,
            ServerPlayer target,
            boolean broadcast,
            String reason) {
        PlatformSavedData state = PlatformSavedData.get(source.getServer());
        var result = ClaimManagementService.removeRole(
                state,
                actorId(source),
                authorizationOverride(source, state),
                key,
                target.getUUID(),
                reason,
                Instant.now().toEpochMilli(),
                UUID.randomUUID());
        return claimMutationResult(
                source, state, result, broadcast, "command.rovenfall.claim.untrust.success",
                target.getDisplayName());
    }

    private static int setClaimSettings(
            CommandSourceStack source,
            ClaimKey key,
            boolean entryRestricted,
            boolean publicInteractions,
            boolean broadcast,
            String reason) {
        PlatformSavedData state = PlatformSavedData.get(source.getServer());
        var result = ClaimManagementService.setSettings(
                state,
                actorId(source),
                authorizationOverride(source, state),
                key,
                new ClaimSettings(entryRestricted, publicInteractions),
                reason,
                Instant.now().toEpochMilli(),
                UUID.randomUUID());
        return claimMutationResult(
                source, state, result, broadcast, "command.rovenfall.claim.settings.success",
                entryRestricted, publicInteractions);
    }

    private static int offerClaimTransfer(
            CommandSourceStack source, ClaimKey key, ServerPlayer recipient) {
        PlatformSavedData state = PlatformSavedData.get(source.getServer());
        var result = ClaimManagementService.offerTransfer(
                state,
                actorId(source),
                key,
                recipient.getUUID(),
                "player claim transfer offer",
                Instant.now().toEpochMilli(),
                UUID.randomUUID());
        return claimMutationResult(
                source, state, result, false, "command.rovenfall.claim.transfer.offer.success",
                recipient.getDisplayName());
    }

    private static int cancelClaimTransfer(CommandSourceStack source, ClaimKey key) {
        PlatformSavedData state = PlatformSavedData.get(source.getServer());
        var result = ClaimManagementService.cancelTransfer(
                state,
                actorId(source),
                key,
                "player claim transfer cancel",
                Instant.now().toEpochMilli(),
                UUID.randomUUID());
        return claimMutationResult(
                source, state, result, false, "command.rovenfall.claim.transfer.cancel.success",
                key.chunkX(), key.chunkZ());
    }

    private static int acceptClaimTransfer(CommandSourceStack source, ClaimKey key) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        PlatformSavedData state = PlatformSavedData.get(source.getServer());
        var result = ClaimManagementService.acceptTransfer(
                state,
                player.getUUID(),
                key,
                candidate -> isProtectedClaimRegion(source, candidate),
                ClaimConfig.ownershipCap(),
                "player claim transfer accept",
                Instant.now().toEpochMilli(),
                UUID.randomUUID());
        return claimMutationResult(
                source, state, result, false, "command.rovenfall.claim.transfer.accept.success",
                key.chunkX(), key.chunkZ());
    }

    private static boolean isProtectedClaimRegion(CommandSourceStack source, ClaimKey key) {
        var hub = source.getServer().overworld();
        return PlatformSavedData.get(source.getServer()).isProtectedRegion(key)
                || PlatformSavedData.get(source.getServer()).isAdministratorProtected(key)
                || ClaimRegionPolicy.isProtectedHubRegion(
                key,
                WorldTopology.HUB,
                hub.getRespawnData().pos(),
                ClaimConfig.protectedSpawnRadiusChunks());
    }

    private static int sellClaim(CommandSourceStack source, ClaimKey key) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        PlatformSavedData state = PlatformSavedData.get(source.getServer());
        var result = ClaimManagementService.sell(
                state,
                player.getUUID(),
                key,
                ClaimConfig.saleRefundPercent(),
                EconomyConfig.maximumBalance(),
                "player claim sale",
                Instant.now().toEpochMilli(),
                UUID.randomUUID());
        return switch (result.status()) {
            case SUCCESS -> {
                source.sendSuccess(() -> Component.translatable(
                        "command.rovenfall.claim.sell.success", result.amount(), result.balance()), false);
                yield 1;
            }
            case DUPLICATE_TRANSACTION -> {
                source.sendSuccess(() -> Component.translatable(
                        "command.rovenfall.claim.duplicate", result.transactionId().toString()), false);
                yield 1;
            }
            default -> claimMutationFailure(source, state, result);
        };
    }

    private static int claimMutationResult(
            CommandSourceStack source,
            PlatformSavedData state,
            ClaimManagementService.Result result,
            boolean broadcast,
            String successKey,
            Object... successArguments) {
        return switch (result.status()) {
            case SUCCESS -> {
                source.sendSuccess(() -> Component.translatable(successKey, successArguments), broadcast);
                yield 1;
            }
            case DUPLICATE_TRANSACTION -> {
                source.sendSuccess(() -> Component.translatable(
                        "command.rovenfall.claim.duplicate", result.transactionId().toString()), false);
                yield 1;
            }
            case NO_CHANGE -> {
                source.sendSuccess(() -> Component.translatable("command.rovenfall.claim.no_change"), false);
                yield 1;
            }
            default -> claimMutationFailure(source, state, result);
        };
    }

    private static int claimMutationFailure(
            CommandSourceStack source,
            PlatformSavedData state,
            ClaimManagementService.Result result) {
        return switch (result.status()) {
            case TRANSACTION_ID_CONFLICT -> failure(source, "command.rovenfall.claim.error.transaction_id_conflict");
            case INVALID_REQUEST, INVALID_TRANSACTION -> failure(
                    source, "command.rovenfall.claim.error.invalid_request");
            case INVALID_REASON -> failure(
                    source, "command.rovenfall.admin.error.invalid_reason", AdministrationService.MAX_REASON_LENGTH);
            case READ_ONLY_SCHEMA -> failure(
                    source, "command.rovenfall.admin.error.read_only_schema", state.schemaVersion());
            case UNAUTHORIZED -> failure(source, "command.rovenfall.claim.error.unauthorized");
            case CLAIM_NOT_FOUND -> failure(source, "command.rovenfall.claim.error.not_found");
            case INVALID_TARGET -> failure(source, "command.rovenfall.claim.error.invalid_target");
            case TRUST_LIMIT_REACHED -> failure(
                    source, "command.rovenfall.claim.error.trust_limit", org.dldyou.rovenfall.claims.Claim.MAX_TRUSTED_PLAYERS);
            case PROTECTED_CHUNK -> failure(source, "command.rovenfall.claim.error.protected");
            case OWNERSHIP_CAP_REACHED -> failure(source, "command.rovenfall.claim.error.cap");
            case TRANSFER_NOT_PENDING -> failure(source, "command.rovenfall.claim.error.transfer_not_pending");
            case TRANSFER_PENDING -> failure(source, "command.rovenfall.claim.error.transfer_pending");
            case PURCHASE_PRICE_UNAVAILABLE -> failure(
                    source, "command.rovenfall.claim.error.purchase_price_unavailable");
            case ACCOUNT_NOT_FOUND -> failure(source, "command.rovenfall.claim.error.account_missing");
            case OVERFLOW, MAXIMUM_BALANCE_EXCEEDED -> failure(source, "command.rovenfall.claim.error.balance_limit");
            case TRANSACTION_LEDGER_FULL -> failure(source, "command.rovenfall.claim.error.ledger_full");
            case SUCCESS, DUPLICATE_TRANSACTION, NO_CHANGE -> throw new IllegalStateException(
                    "Successful claim result reached failure handling");
        };
    }

    private static int listAudit(CommandSourceStack source, int page) {
        PlatformSavedData.AuditPage result = PlatformSavedData.get(source.getServer()).auditPage(page, AUDIT_PAGE_SIZE);
        return sendAuditPage(source, result);
    }

    private static int searchAudit(CommandSourceStack source, int page, String queryText) {
        long now = Instant.now().toEpochMilli();
        try {
            AuditQuery query = AuditQuery.parse(
                    queryText, Math.max(0, now - AuditQuery.MAX_WINDOW_MILLIS), now, false);
            PlatformSavedData.AuditPage result = PlatformSavedData.get(source.getServer())
                    .auditPage(query, page, AUDIT_PAGE_SIZE);
            return sendAuditPage(source, result);
        } catch (IllegalArgumentException exception) {
            return failure(source, "command.rovenfall.admin.audit.error.invalid_query");
        }
    }

    private static int sendAuditPage(CommandSourceStack source, PlatformSavedData.AuditPage result) {
        if (result.entries().isEmpty()) {
            source.sendSuccess(() -> Component.translatable(
                    "command.rovenfall.admin.audit.empty", result.page() + 1), false);
            return 1;
        }

        source.sendSuccess(() -> Component.translatable(
                "command.rovenfall.admin.audit.header",
                result.page() + 1,
                result.totalPages(),
                result.totalEntries()), false);
        for (AuditEntry entry : result.entries()) {
            source.sendSuccess(() -> Component.translatable(
                    "command.rovenfall.admin.audit.entry",
                    Instant.ofEpochMilli(entry.timestampEpochMillis()).toString(),
                    entry.actionType().toString(),
                    entry.target(),
                    entry.beforeValue(),
                    entry.afterValue(),
                    entry.actorId().toString(),
                    entry.transactionId().toString(),
                    entry.reason()), false);
        }
        return result.entries().size();
    }

    private static int exportAudit(
            CommandSourceStack source, UUID transactionId, String reason, String queryText) {
        long now = Instant.now().toEpochMilli();
        final AuditQuery query;
        try {
            query = AuditQuery.parse(queryText, 0, now, true);
        } catch (IllegalArgumentException exception) {
            PlatformSavedData state = PlatformSavedData.get(source.getServer());
            AuditExportService.auditInvalidQuery(state, actorId(source), queryText, now, transactionId);
            return failure(source, "command.rovenfall.admin.audit.error.invalid_query");
        }

        PlatformSavedData state = PlatformSavedData.get(source.getServer());
        AuditExportService.Result result = AuditExportService.export(
                state, AuditExportStore.forServer(source.getServer()), actorId(source),
                authorizationOverride(source, state), query, reason, now, transactionId);
        return switch (result.status()) {
            case SUCCESS -> {
                source.sendSuccess(() -> Component.translatable(
                        "command.rovenfall.admin.audit.export.success",
                        result.rows(), result.bytes(), result.path().orElseThrow().toString(), transactionId.toString()), true);
                yield 1;
            }
            case DUPLICATE -> {
                source.sendSuccess(() -> Component.translatable(
                        "command.rovenfall.admin.audit.export.duplicate", transactionId.toString()), false);
                yield 1;
            }
            case UNAUTHORIZED -> failure(source, "command.rovenfall.admin.audit.export.error.unauthorized");
            case READ_ONLY_SCHEMA -> failure(
                    source, "command.rovenfall.admin.error.read_only_schema", state.schemaVersion());
            case INVALID_REASON -> failure(
                    source, "command.rovenfall.admin.error.invalid_reason", AdministrationService.MAX_REASON_LENGTH);
            case TRANSACTION_CONFLICT -> failure(
                    source, "command.rovenfall.admin.audit.export.error.transaction_conflict");
            case LIMIT_EXCEEDED -> failure(
                    source, "command.rovenfall.admin.audit.export.error.limit",
                    AuditExportService.MAX_EXPORT_ROWS, AuditExportService.MAX_EXPORT_BYTES);
            case WRITE_FAILED -> failure(source, "command.rovenfall.admin.audit.export.error.write_failed");
            case INVALID_REQUEST -> failure(source, "command.rovenfall.admin.audit.error.invalid_query");
        };
    }

    private static int changeBalance(
            CommandSourceStack source,
            ServerPlayer target,
            long amount,
            UUID transactionId,
            String reason,
            boolean grant) {
        PlatformSavedData state = PlatformSavedData.get(source.getServer());
        var result = grant
                ? EconomyService.adminGrant(
                        state, actorId(source), authorizationOverride(source, state), target.getUUID(), amount, reason,
                        Instant.now().toEpochMilli(), transactionId,
                        EconomyConfig.initialBalance(), EconomyConfig.maximumBalance())
                : EconomyService.adminDebit(
                        state, actorId(source), authorizationOverride(source, state), target.getUUID(), amount, reason,
                        Instant.now().toEpochMilli(), transactionId,
                        EconomyConfig.initialBalance(), EconomyConfig.maximumBalance());

        return switch (result.status()) {
            case SUCCESS -> {
                source.sendSuccess(() -> Component.translatable(
                        grant
                                ? "command.rovenfall.admin.economy.grant.success"
                                : "command.rovenfall.admin.economy.debit.success",
                        target.getDisplayName(), amount, result.balance(), result.transactionId().toString()), true);
                yield 1;
            }
            case DUPLICATE_TRANSACTION -> {
                source.sendSuccess(() -> Component.translatable(
                        "command.rovenfall.admin.economy.duplicate",
                        result.transactionId().toString(), target.getDisplayName(), result.balance()), false);
                yield 1;
            }
            case TRANSACTION_ID_CONFLICT -> failure(
                    source, "command.rovenfall.admin.economy.error.transaction_id_conflict");
            case UNAUTHORIZED -> failure(source, "command.rovenfall.admin.economy.error.unauthorized");
            case INVALID_TRANSACTION -> failure(source, "command.rovenfall.admin.economy.error.invalid_transaction");
            case INVALID_AMOUNT -> failure(source, "command.rovenfall.admin.economy.error.invalid_amount");
            case INVALID_REASON -> failure(
                    source, "command.rovenfall.admin.error.invalid_reason", AdministrationService.MAX_REASON_LENGTH);
            case INVALID_CONFIGURATION -> failure(source, "command.rovenfall.admin.economy.error.invalid_configuration");
            case TRANSACTION_LEDGER_FULL -> failure(source, "command.rovenfall.admin.economy.error.transaction_ledger_full");
            case OVERFLOW -> failure(source, "command.rovenfall.admin.economy.error.overflow");
            case MAXIMUM_EXCEEDED -> failure(
                    source, "command.rovenfall.admin.economy.error.maximum_exceeded", EconomyConfig.maximumBalance());
            case INSUFFICIENT_FUNDS -> failure(
                    source, "command.rovenfall.admin.economy.error.insufficient_funds", result.balance(), amount);
            case READ_ONLY_SCHEMA -> failure(
                    source, "command.rovenfall.admin.error.read_only_schema", state.schemaVersion());
            case ACCOUNT_EXISTS, INVALID_INPUT -> failure(source, "command.rovenfall.admin.economy.error.invalid_request");
        };
    }

    private static int openAuditGui(CommandSourceStack source, int page) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        PlatformSavedData.AuditPage result = PlatformSavedData.get(source.getServer()).auditPage(page, AuditBookView.PAGE_SIZE);
        AuditBookView.open(player, result);
        return 1;
    }

    private static int openAdminSearch(
            CommandSourceStack source,
            String scopeId,
            int page,
            String query) {
        Optional<AdminSearchService.Scope> retainedScope = AdminSearchService.Scope.parse(scopeId);
        if (retainedScope.isEmpty()) {
            return failure(source, "command.rovenfall.admin.search.error.invalid_scope", scopeId);
        }
        PlatformSavedData state = PlatformSavedData.get(source.getServer());
        var result = AdminSearchService.search(
                state,
                actorId(source),
                authorizationOverride(source, state),
                retainedScope.orElseThrow(),
                query,
                page,
                AdminSearchBookView.PAGE_SIZE);
        return switch (result.status()) {
            case SUCCESS -> {
                ServerPlayer player = source.getPlayer();
                if (player != null) {
                    AdminSearchBookView.open(player, result);
                } else {
                    AdminSearchBookView.pages(result).forEach(component ->
                            source.sendSuccess(() -> component, false));
                }
                yield 1;
            }
            case UNAUTHORIZED -> failure(source, "command.rovenfall.admin.error.unauthorized");
            case INVALID_SCOPE -> failure(
                    source, "command.rovenfall.admin.search.error.invalid_scope", scopeId);
            case INVALID_QUERY -> failure(
                    source, "command.rovenfall.admin.search.error.invalid_query",
                    AdminSearchService.MAX_QUERY_LENGTH);
            case INVALID_PAGE -> failure(source, "command.rovenfall.admin.search.error.invalid_page");
        };
    }

    private static int reverseTargetedTransaction(
            CommandSourceStack source,
            UUID originalTransactionId,
            UUID reversalTransactionId,
            String reason) {
        PlatformSavedData state = PlatformSavedData.get(source.getServer());
        var result = TargetedReversalService.reverse(
                state,
                actorId(source),
                authorizationOverride(source, state),
                originalTransactionId,
                reason,
                Instant.now().toEpochMilli(),
                reversalTransactionId);
        return switch (result.status()) {
            case SUCCESS -> {
                Component domain = result.domain()
                        .<Component>map(value -> Component.translatable(value.translationKey()))
                        .orElseGet(() -> Component.translatable("targeted_reversal_domain.rovenfall.unknown"));
                source.sendSuccess(() -> Component.translatable(
                        "command.rovenfall.admin.reverse.success",
                        domain,
                        originalTransactionId,
                        reversalTransactionId), true);
                yield 1;
            }
            case DUPLICATE_TRANSACTION -> {
                source.sendSuccess(() -> Component.translatable(
                        "command.rovenfall.admin.reverse.duplicate", reversalTransactionId), false);
                yield 1;
            }
            case TRANSACTION_ID_CONFLICT -> failure(
                    source, "command.rovenfall.admin.reverse.error.transaction_id_conflict");
            case ALREADY_REVERSED -> failure(
                    source, "command.rovenfall.admin.reverse.error.already_reversed");
            case UNAUTHORIZED -> failure(source, "command.rovenfall.admin.reverse.error.unauthorized");
            case INVALID_REQUEST -> failure(source, "command.rovenfall.admin.reverse.error.invalid_request");
            case INVALID_TRANSACTION -> failure(
                    source, "command.rovenfall.admin.reverse.error.invalid_transaction");
            case INVALID_REASON -> failure(source, "command.rovenfall.admin.reverse.error.invalid_reason");
            case READ_ONLY_SCHEMA -> failure(
                    source, "command.rovenfall.admin.reverse.error.read_only_schema");
            case TRANSACTION_LEDGER_FULL -> failure(
                    source, "command.rovenfall.admin.reverse.error.transaction_ledger_full");
            case DEPENDENCY_LOCKED -> failure(
                    source, "command.rovenfall.admin.reverse.error.dependency_locked");
            case ORIGINAL_NOT_REVERSIBLE -> failure(
                    source, "command.rovenfall.admin.reverse.error.original_not_reversible");
            case CURRENT_STATE_MISMATCH -> failure(
                    source, "command.rovenfall.admin.reverse.error.current_state_mismatch");
        };
    }

    private static int openEconomyGui(
            CommandSourceStack source, EconomyView view, int page) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        PlatformSavedData state = PlatformSavedData.get(source.getServer());
        UUID actorId = player.getUUID();
        boolean override = authorizationOverride(source, state);
        switch (view) {
            case BALANCES -> EconomyBookView.open(player, EconomyBookView.balances(
                    EconomyObservabilityService.balances(state, actorId, override, page, EconomyBookView.PAGE_SIZE)));
            case TRANSACTIONS -> EconomyBookView.open(player, EconomyBookView.transactions(
                    EconomyObservabilityService.transactions(state, actorId, override, page, EconomyBookView.PAGE_SIZE)));
            case SHOPS -> EconomyBookView.open(player, EconomyBookView.shops(
                    EconomyObservabilityService.shops(state, actorId, override, page, EconomyBookView.PAGE_SIZE)));
            case ALERTS -> EconomyBookView.open(player, EconomyBookView.alerts(
                    EconomyObservabilityService.alerts(state, actorId, override, page, EconomyBookView.PAGE_SIZE)));
        }
        return 1;
    }

    private static int openOperations(CommandSourceStack source, long windowMillis) {
        PlatformSavedData state = PlatformSavedData.get(source.getServer());
        var result = OperationsMetricsService.snapshot(
                source.getServer(), actorId(source), authorizationOverride(source, state),
                Instant.now().toEpochMilli(), windowMillis);
        if (result.status() == OperationsMetricsService.Status.UNAUTHORIZED) {
            return failure(source, "command.rovenfall.admin.operations.error.unauthorized");
        }
        if (result.status() != OperationsMetricsService.Status.SUCCESS) {
            return failure(source, "command.rovenfall.admin.operations.error.invalid_request");
        }
        source.sendSuccess(() -> Component.translatable(
                "command.rovenfall.admin.operations.summary",
                Instant.ofEpochMilli(result.generatedAtEpochMillis()).toString(),
                result.windowMillis() / 60_000L,
                result.economyTransactionCount(), result.deniedRequestCount(),
                result.suspiciousRpgAwardCount(), result.activeEncounterCount(),
                result.pendingRewardCount(), result.pendingRecoveryCount()), false);
        source.sendSuccess(() -> Component.translatable(
                "command.rovenfall.admin.operations.anomalies",
                result.amountAlertCount(), result.rateAlertCount(), result.malformedRequestCount(),
                result.evidenceTransactionIds().isEmpty()
                        ? Component.translatable("gui.rovenfall.admin.economy.none")
                        : Component.literal(result.evidenceTransactionIds().toString())), false);
        ServerPlayer player = source.getPlayer();
        if (player != null) {
            EconomyBookView.open(player, EconomyBookView.operations(result));
        }
        if (result.hasAnomaly()) {
            LOGGER.warn("Rovenfall operations anomalies at {} (window={}ms, evidence={}): inspect audit, economy alerts, RPG history, and boss rewards",
                    result.generatedAtEpochMillis(), result.windowMillis(), result.evidenceTransactionIds());
        }
        return 1;
    }

    private static int reverseTransaction(
            CommandSourceStack source,
            ServerPlayer target,
            UUID originalTransactionId,
            UUID reversalTransactionId,
            String requestedDecision,
            String reason) {
        EconomyTransactionReceipt.CompensationDecision decision = switch (requestedDecision) {
            case "strict" -> EconomyTransactionReceipt.CompensationDecision.NONE;
            case "refund_without_items_or_stock" ->
                    EconomyTransactionReceipt.CompensationDecision.REFUND_WITHOUT_ITEMS_OR_STOCK;
            default -> null;
        };
        if (decision == null) {
            return failure(source, "command.rovenfall.admin.economy.reversal.error.invalid_decision");
        }
        PlatformSavedData state = PlatformSavedData.get(source.getServer());
        var result = EconomyReversalService.reverse(
                state, target, actorId(source), authorizationOverride(source, state), originalTransactionId,
                decision, reason, Instant.now().toEpochMilli(), reversalTransactionId);
        return switch (result.status()) {
            case SUCCESS -> {
                source.sendSuccess(() -> Component.translatable(
                        "command.rovenfall.admin.economy.reversal.success",
                        originalTransactionId, reversalTransactionId), true);
                yield 1;
            }
            case DUPLICATE_TRANSACTION -> {
                source.sendSuccess(() -> Component.translatable(
                        "command.rovenfall.admin.economy.duplicate", reversalTransactionId,
                        target.getDisplayName(), state.economyBalance(target.getUUID()).orElse(0L)), false);
                yield 1;
            }
            case TRANSACTION_ID_CONFLICT -> failure(
                    source, "command.rovenfall.admin.economy.reversal.error.transaction_id_conflict");
            case COMPENSATION_REQUIRED -> failure(
                    source, "command.rovenfall.admin.economy.reversal.error.compensation_required");
            case ALREADY_REVERSED -> failure(source, "command.rovenfall.admin.economy.reversal.error.already_reversed");
            case UNAUTHORIZED -> failure(source, "command.rovenfall.admin.economy.error.unauthorized");
            case INVALID_REASON -> failure(
                    source, "command.rovenfall.admin.error.invalid_reason", AdministrationService.MAX_REASON_LENGTH);
            case READ_ONLY_SCHEMA -> failure(
                    source, "command.rovenfall.admin.error.read_only_schema", state.schemaVersion());
            case TRANSACTION_LEDGER_FULL -> failure(
                    source, "command.rovenfall.admin.economy.error.transaction_ledger_full");
            case DEPENDENCY_LOCKED -> failure(source, "command.rovenfall.admin.shop.error.dependency_locked");
            case INSUFFICIENT_FUNDS -> failure(
                    source, "command.rovenfall.admin.economy.reversal.error.insufficient_funds");
            case MAXIMUM_BALANCE_EXCEEDED -> failure(
                    source, "command.rovenfall.admin.economy.error.maximum_exceeded", EconomyConfig.maximumBalance());
            case INSUFFICIENT_SPACE -> failure(
                    source, "command.rovenfall.admin.economy.reversal.error.insufficient_space");
            case INVALID_REQUEST, INVALID_TRANSACTION, ORIGINAL_NOT_REVERSIBLE, TARGET_MISMATCH,
                    ACCOUNT_NOT_FOUND, OVERFLOW, SHOP_MISMATCH, EXACT_ITEMS_UNAVAILABLE,
                    STOCK_INVERSE_UNAVAILABLE, INVENTORY_UPDATE_FAILED -> failure(
                    source, "command.rovenfall.admin.economy.reversal.error.failed",
                    result.status().name().toLowerCase(java.util.Locale.ROOT));
        };
    }

    private static int resetWilderness(CommandSourceStack source, String reason) {
        PlatformSavedData state = PlatformSavedData.get(source.getServer());
        UUID operationId = UUID.randomUUID();
        UUID snapshotId = UUID.randomUUID();
        var result = RestartWildernessResetService.schedule(
                source.getServer(),
                actorId(source),
                authorizationOverride(source, state),
                source.getDisplayName(),
                reason,
                Instant.now().toEpochMilli(),
                operationId,
                snapshotId);
        return switch (result.status()) {
            case SUCCESS -> {
                source.sendSuccess(() -> Component.translatable(
                        "command.rovenfall.admin.wilderness.reset.scheduled",
                        result.operationId().toString(),
                        result.snapshotId().toString(),
                        result.evacuatedPlayers()), true);
                yield 1;
            }
            case UNAUTHORIZED -> failure(source, "command.rovenfall.admin.wilderness.error.unauthorized");
            case INVALID_REASON -> failure(
                    source, "command.rovenfall.admin.error.invalid_reason", AdministrationService.MAX_REASON_LENGTH);
            case READ_ONLY_SCHEMA -> failure(
                    source, "command.rovenfall.admin.error.read_only_schema", state.schemaVersion());
            case TRANSACTION_LEDGER_FULL -> failure(
                    source, "command.rovenfall.admin.wilderness.error.transaction_ledger_full");
            case RESET_PENDING -> failure(source, "command.rovenfall.admin.wilderness.error.pending");
            case DIMENSION_UNAVAILABLE -> failure(
                    source, "command.rovenfall.admin.wilderness.error.dimension_unavailable");
            case NO_SAFE_HUB_ARRIVAL -> failure(
                    source, "command.rovenfall.admin.wilderness.error.no_safe_hub_arrival");
            case EVACUATION_FAILED -> failure(
                    source, "command.rovenfall.admin.wilderness.error.evacuation_failed");
            case SAVE_FAILED -> failure(source, "command.rovenfall.admin.wilderness.error.save_failed");
            case SNAPSHOT_FAILED -> failure(source, "command.rovenfall.admin.wilderness.error.snapshot_failed");
            case STORAGE_ERROR -> failure(source, "command.rovenfall.admin.wilderness.error.storage_error");
            case INVALID_REQUEST, INVALID_TRANSACTION, DUPLICATE_TRANSACTION -> failure(
                    source, "command.rovenfall.admin.wilderness.error.invalid_request");
        };
    }

    private static int viewWildernessReset(CommandSourceStack source, UUID operationId) {
        Optional<RestartWildernessResetService.Operation> operation;
        try {
            operation = operationId == null
                    ? RestartWildernessResetService.pendingOperation(source.getServer())
                    : RestartWildernessResetService.operation(source.getServer(), operationId);
        } catch (RestartWildernessResetService.StorageException exception) {
            return failure(source, "command.rovenfall.admin.wilderness.error.storage_error");
        }
        if (operation.isEmpty()) {
            if (operationId == null) {
                source.sendSuccess(() -> Component.translatable(
                        "command.rovenfall.admin.wilderness.status.none"), false);
                return 1;
            }
            return failure(source, "command.rovenfall.admin.wilderness.status.not_found", operationId.toString());
        }

        RestartWildernessResetService.Operation value = operation.orElseThrow();
        String completed = value.completedAtEpochMillis() < 0
                ? "-"
                : Instant.ofEpochMilli(value.completedAtEpochMillis()).toString();
        String failureCode = value.failureCode().isEmpty() ? "-" : value.failureCode();
        source.sendSuccess(() -> Component.translatable(
                "command.rovenfall.admin.wilderness.status.info",
                value.operationId().toString(),
                value.snapshotId().toString(),
                Component.translatable(value.phase().translationKey()),
                value.evacuatedPlayers(),
                Instant.ofEpochMilli(value.requestedAtEpochMillis()).toString(),
                completed,
                RestartWildernessResetService.backupRelativePath(value.snapshotId()),
                failureCode,
                value.reason()), false);
        return 1;
    }

    private static int createSnapshot(CommandSourceStack source, String reason) {
        PlatformSavedData state = PlatformSavedData.get(source.getServer());
        UUID snapshotId = UUID.randomUUID();
        var result = AdministrationService.createSnapshot(
                state,
                PlatformSnapshotStore.forServer(source.getServer()),
                actorId(source),
                authorizationOverride(source, state),
                reason,
                Instant.now().toEpochMilli(),
                UUID.randomUUID(),
                snapshotId
        );

        return switch (result.status()) {
            case SUCCESS -> {
                source.sendSuccess(() -> Component.translatable(
                        "command.rovenfall.admin.snapshot.create.success",
                        result.snapshotId().toString(),
                        result.transactionId().toString()), true);
                yield 1;
            }
            case UNAUTHORIZED -> failure(source, "command.rovenfall.admin.snapshot.error.unauthorized");
            case INVALID_REASON -> failure(source, "command.rovenfall.admin.error.invalid_reason", AdministrationService.MAX_REASON_LENGTH);
            case READ_ONLY_SCHEMA -> failure(source, "command.rovenfall.admin.error.read_only_schema", state.schemaVersion());
            case STORAGE_ERROR -> failure(source, "command.rovenfall.admin.snapshot.error.write_failed");
        };
    }

    private static int restoreSnapshot(
            CommandSourceStack source, UUID snapshotId, UUID transactionId, String reason) {
        PlatformSavedData state = PlatformSavedData.get(source.getServer());
        var result = AdministrationService.restoreSnapshot(
                state,
                PlatformSnapshotStore.forServer(source.getServer()),
                actorId(source),
                authorizationOverride(source, state),
                snapshotId,
                reason,
                Instant.now().toEpochMilli(),
                transactionId,
                UUID.randomUUID()
        );

        return switch (result.status()) {
            case SUCCESS -> {
                source.sendSuccess(() -> Component.translatable(
                        "command.rovenfall.admin.snapshot.restore.success",
                        result.snapshotId().toString(),
                        result.safetySnapshotId().toString(),
                        result.transactionId().toString()), true);
                yield 1;
            }
            case DUPLICATE_TRANSACTION -> {
                source.sendSuccess(() -> Component.translatable(
                        "command.rovenfall.admin.snapshot.restore.duplicate", result.transactionId().toString()), false);
                yield 1;
            }
            case UNAUTHORIZED -> failure(source, "command.rovenfall.admin.snapshot.error.unauthorized");
            case INVALID_TRANSACTION -> failure(
                    source, "command.rovenfall.admin.snapshot.error.invalid_transaction");
            case INVALID_REASON -> failure(source, "command.rovenfall.admin.error.invalid_reason", AdministrationService.MAX_REASON_LENGTH);
            case READ_ONLY_SCHEMA -> failure(source, "command.rovenfall.admin.error.read_only_schema", state.schemaVersion());
            case SNAPSHOT_UNAVAILABLE -> failure(source, "command.rovenfall.admin.snapshot.error.unavailable", snapshotId.toString());
            case STALE_SNAPSHOT -> failure(
                    source, "command.rovenfall.admin.snapshot.error.unavailable", snapshotId.toString());
            case TRANSACTION_LEDGER_FULL -> failure(
                    source, "command.rovenfall.admin.snapshot.error.transaction_ledger_full");
            case TRANSACTION_EVIDENCE_CONFLICT -> failure(
                    source, "command.rovenfall.admin.snapshot.error.transaction_evidence_conflict");
            case DEPENDENCY_LOCKED -> failure(
                    source, "command.rovenfall.admin.snapshot.error.dependency_locked");
            case SAFETY_SNAPSHOT_FAILED -> failure(source, "command.rovenfall.admin.snapshot.error.safety_failed");
        };
    }

    private static int warnWildernessReset(CommandSourceStack source, String reason) {
        PlatformSavedData state = PlatformSavedData.get(source.getServer());
        UUID warningId = UUID.randomUUID();
        var result = WildernessResetService.warn(
                state, actorId(source), hasNativeOwnerPermission(source), reason,
                Instant.now().toEpochMilli(), warningId);
        if (result.status() == WildernessResetService.Status.SUCCESS) {
            source.getServer().getPlayerList().broadcastSystemMessage(
                    Component.translatable("wilderness.rovenfall.reset.warning"), false);
            source.sendSuccess(() -> Component.translatable(
                    "command.rovenfall.admin.wilderness.reset.warning.success",
                    warningId.toString(), WildernessResetService.WARNING_TTL_MILLIS / 1_000L), true);
            return 1;
        }
        return wildernessFailure(source, state, result.status());
    }

    private static int resetWilderness(
            CommandSourceStack source, UUID warningId, UUID transactionId, String reason) {
        PlatformSavedData state = PlatformSavedData.get(source.getServer());
        var result = WildernessResetService.reset(
                source.getServer(), actorId(source), hasNativeOwnerPermission(source), warningId, reason,
                Instant.now().toEpochMilli(), transactionId);
        if (result.status() == WildernessResetService.Status.SUCCESS) {
            source.getServer().getPlayerList().broadcastSystemMessage(
                    Component.translatable("wilderness.rovenfall.reset.shutdown"), false);
            source.sendSuccess(() -> Component.translatable(
                    "command.rovenfall.admin.wilderness.reset.staged",
                    result.snapshotId().toString(), transactionId.toString()), true);
            return 1;
        }
        return wildernessFailure(source, state, result.status());
    }

    private static int restoreWilderness(
            CommandSourceStack source, UUID snapshotId, UUID transactionId, String reason) {
        PlatformSavedData state = PlatformSavedData.get(source.getServer());
        var result = WildernessResetService.restore(
                source.getServer(), actorId(source), hasNativeOwnerPermission(source), snapshotId, reason,
                Instant.now().toEpochMilli(), transactionId);
        if (result.status() == WildernessResetService.Status.SUCCESS) {
            source.getServer().getPlayerList().broadcastSystemMessage(
                    Component.translatable("wilderness.rovenfall.restore.shutdown"), false);
            source.sendSuccess(() -> Component.translatable(
                    "command.rovenfall.admin.wilderness.restore.staged",
                    snapshotId.toString(), result.snapshotId().toString(), transactionId.toString()), true);
            return 1;
        }
        return wildernessFailure(source, state, result.status());
    }

    private static int wildernessFailure(
            CommandSourceStack source, PlatformSavedData state, WildernessResetService.Status status) {
        return switch (status) {
            case UNAUTHORIZED -> failure(source, "command.rovenfall.admin.wilderness.error.unauthorized");
            case INVALID_REASON -> failure(source, "command.rovenfall.admin.error.invalid_reason",
                    AdministrationService.MAX_REASON_LENGTH);
            case READ_ONLY_SCHEMA -> failure(
                    source, "command.rovenfall.admin.error.read_only_schema", state.schemaVersion());
            case WARNING_REQUIRED -> failure(source, "command.rovenfall.admin.wilderness.error.warning_required");
            case LOCKED -> failure(source, "command.rovenfall.admin.wilderness.error.locked");
            case TOPOLOGY_UNAVAILABLE -> failure(source, "command.rovenfall.admin.wilderness.error.topology");
            case SNAPSHOT_NOT_FOUND -> failure(source, "command.rovenfall.admin.wilderness.error.snapshot_not_found");
            case SNAPSHOT_FAILED -> failure(source, "command.rovenfall.admin.wilderness.error.snapshot_failed");
            case EVACUATION_FAILED -> failure(source, "command.rovenfall.admin.wilderness.error.evacuation_failed");
            case EVACUATION_ROLLBACK_FAILED -> failure(
                    source, "command.rovenfall.admin.wilderness.error.evacuation_rollback_failed");
            case PRECOMMIT_FAILED -> failure(source, "command.rovenfall.admin.wilderness.error.precommit_failed");
            case DUPLICATE_TRANSACTION -> failure(source, "command.rovenfall.admin.wilderness.error.duplicate");
            case INVALID_REQUEST, INVALID_TRANSACTION ->
                    failure(source, "command.rovenfall.admin.wilderness.error.invalid_request");
            case SUCCESS -> 1;
        };
    }

    private static int showAdminHelp(CommandSourceStack source) {
        PlatformSavedData state = PlatformSavedData.get(source.getServer());
        var player = source.getPlayer();
        AdminRole role = player == null
                ? AdminRole.OWNER
                : state.roleOf(player.getUUID()).orElse(AdminRole.OWNER);
        source.sendSuccess(() -> Component.translatable(
                "command.rovenfall.admin.help.header",
                Component.translatable(role.translationKey())), false);
        source.sendSuccess(() -> Component.translatable(
                "command.rovenfall.admin.help.diagnostics"), false);
        source.sendSuccess(() -> Component.translatable(
                "command.rovenfall.admin.help.privacy"), false);
        source.sendSuccess(() -> Component.translatable(
                "command.rovenfall.admin.help.role." + role.getSerializedName()), false);
        return 1;
    }

    private static boolean canUseAdministration(CommandSourceStack source) {
        PlatformSavedData state = PlatformSavedData.get(source.getServer());
        var player = source.getPlayer();
        return canUseAdministration(
                state,
                player == null ? null : player.getUUID(),
                hasNativeOwnerPermission(source));
    }

    static boolean canUseAdministration(PlatformSavedData state, UUID playerId, boolean nativeOwnerPermission) {
        if (playerId == null) {
            return nativeOwnerPermission;
        }
        return state.hasAdminRole(playerId) || (!state.hasAnyAdminRoles() && nativeOwnerPermission);
    }

    private static boolean hasNativeOwnerPermission(CommandSourceStack source) {
        return source.permissions().hasPermission(Permissions.COMMANDS_OWNER);
    }

    private static boolean canManagePortals(CommandSourceStack source) {
        if (hasNativeOwnerPermission(source)) {
            return true;
        }
        var player = source.getPlayer();
        AdminRole role = player == null
                ? null
                : PlatformSavedData.get(source.getServer()).roleOf(player.getUUID()).orElse(null);
        return role == AdminRole.CONTENT_MANAGER || role == AdminRole.OWNER;
    }

    private static boolean canResetWilderness(CommandSourceStack source) {
        if (hasNativeOwnerPermission(source)) {
            return true;
        }
        var player = source.getPlayer();
        return player != null
                && PlatformSavedData.get(source.getServer()).roleOf(player.getUUID()).orElse(null) == AdminRole.OWNER;
    }

    static UUID actorId(CommandSourceStack source) {
        var actor = source.getPlayer();
        return actor == null ? AdministrationService.SYSTEM_ACTOR : actor.getUUID();
    }

    static boolean authorizationOverride(CommandSourceStack source, PlatformSavedData state) {
        return hasNativeOwnerPermission(source) && (source.getPlayer() == null || !state.hasAnyAdminRoles());
    }

    private static int failure(CommandSourceStack source, String translationKey, Object... arguments) {
        source.sendFailure(Component.translatable(translationKey, arguments));
        return 0;
    }

    private enum EconomyView {
        BALANCES,
        TRANSACTIONS,
        SHOPS,
        ALERTS
    }
}
