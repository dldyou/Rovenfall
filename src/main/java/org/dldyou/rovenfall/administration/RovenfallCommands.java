package org.dldyou.rovenfall.administration;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.logging.LogUtils;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
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
import org.dldyou.rovenfall.economy.ShopInstance;
import org.dldyou.rovenfall.economy.ShopTemplateReloadListener;
import org.dldyou.rovenfall.economy.ShopTemplateSnapshot;
import org.dldyou.rovenfall.claims.ClaimConfig;
import org.dldyou.rovenfall.claims.ClaimKey;
import org.dldyou.rovenfall.claims.ClaimRegionPolicy;
import org.dldyou.rovenfall.claims.ClaimRole;
import org.dldyou.rovenfall.claims.ClaimSettings;
import org.dldyou.rovenfall.rpg.RpgCommands;
import org.dldyou.rovenfall.world.ProtectedRegion;
import org.dldyou.rovenfall.world.PortalDefinition;
import org.dldyou.rovenfall.world.WorldTopology;
import org.slf4j.Logger;

public final class RovenfallCommands {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int AUDIT_PAGE_SIZE = 10;
    private static final int PROTECTED_REGION_PAGE_SIZE = 10;
    private static final int PORTAL_PAGE_SIZE = 10;

    private RovenfallCommands() {
    }

    public static void register(RegisterCommandsEvent event) {
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

        var wildernessCommand = Commands.literal("wilderness")
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

        var playerShopCommand = Commands.literal("shop")
                .then(tradeCommand("buy", ShopTradeService.Direction.BUY))
                .then(tradeCommand("sell", ShopTradeService.Direction.SELL));
        var playerClaimCommand = Commands.literal("claim")
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

        event.getDispatcher().register(Commands.literal("rovenfall")
                .then(Commands.literal("menu")
                        .executes(context -> openPlayerMenu(context.getSource())))
                .then(Commands.literal("inventory")
                        .executes(context -> openCharacterInventory(context.getSource())))
                .then(playerShopCommand)
                .then(playerClaimCommand)
                .then(RpgCommands.careerCommand())
                .then(RpgCommands.skillCommand())
                .then(portalCommand)
                .then(Commands.literal("admin")
                        .requires(RovenfallCommands::canUseAdministration)
                        .then(guiCommand)
                        .then(helpCommand)
                        .then(roleCommand)
                        .then(economyCommand)
                        .then(operationsCommand)
                        .then(adminShopCommand)
                        .then(adminClaimCommand)
                        .then(protectedRegionCommand)
                        .then(RpgAdminCommands.command())
                        .then(BossAdminCommands.command())
                        .then(auditCommand)
                        .then(snapshotCommand)
                        .then(wildernessCommand)));
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
                "quantity", IntegerArgumentType.integer(1, ShopTradeService.MAX_TRADE_QUANTITY)).then(transaction);
        var offer = Commands.argument("offer_id", IdentifierArgument.id()).then(quantity);
        var shop = Commands.argument("shop_id", IdentifierArgument.id()).then(offer);
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
