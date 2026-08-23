package org.dldyou.rovenfall.administration;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
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
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.dldyou.rovenfall.economy.ShopInstance;
import org.dldyou.rovenfall.economy.ShopTemplateReloadListener;
import org.dldyou.rovenfall.economy.ShopTemplateSnapshot;

public final class RovenfallCommands {
    private static final int AUDIT_PAGE_SIZE = 10;

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
                                        IntegerArgumentType.getInteger(context, "page") - 1))));

        var snapshotCommand = Commands.literal("snapshot")
                .then(Commands.literal("create")
                        .then(Commands.argument("reason", StringArgumentType.greedyString())
                                .executes(context -> createSnapshot(
                                        context.getSource(),
                                        StringArgumentType.getString(context, "reason")))))
                .then(Commands.literal("restore")
                        .then(Commands.argument("snapshot_id", UuidArgument.uuid())
                                .then(Commands.argument("reason", StringArgumentType.greedyString())
                                        .executes(context -> restoreSnapshot(
                                                context.getSource(),
                                                UuidArgument.getUuid(context, "snapshot_id"),
                                                StringArgumentType.getString(context, "reason"))))));

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

        event.getDispatcher().register(Commands.literal("rovenfall")
                .then(playerShopCommand)
                .then(Commands.literal("admin")
                        .requires(RovenfallCommands::canUseAdministration)
                        .then(roleCommand)
                        .then(economyCommand)
                        .then(adminShopCommand)
                        .then(auditCommand)
                        .then(snapshotCommand)));
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

    private static int listAudit(CommandSourceStack source, int page) {
        PlatformSavedData.AuditPage result = PlatformSavedData.get(source.getServer()).auditPage(page, AUDIT_PAGE_SIZE);
        if (result.entries().isEmpty()) {
            source.sendSuccess(() -> Component.translatable("command.rovenfall.admin.audit.empty", page + 1), false);
            return 1;
        }

        source.sendSuccess(() -> Component.translatable(
                "command.rovenfall.admin.audit.header",
                page + 1,
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

    private static int restoreSnapshot(CommandSourceStack source, UUID snapshotId, String reason) {
        PlatformSavedData state = PlatformSavedData.get(source.getServer());
        var result = AdministrationService.restoreSnapshot(
                state,
                PlatformSnapshotStore.forServer(source.getServer()),
                actorId(source),
                authorizationOverride(source, state),
                snapshotId,
                reason,
                Instant.now().toEpochMilli(),
                UUID.randomUUID(),
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
            case TRANSACTION_LEDGER_FULL -> failure(
                    source, "command.rovenfall.admin.snapshot.error.transaction_ledger_full");
            case TRANSACTION_EVIDENCE_CONFLICT -> failure(
                    source, "command.rovenfall.admin.snapshot.error.transaction_evidence_conflict");
            case DEPENDENCY_LOCKED -> failure(
                    source, "command.rovenfall.admin.snapshot.error.dependency_locked");
            case SAFETY_SNAPSHOT_FAILED -> failure(source, "command.rovenfall.admin.snapshot.error.safety_failed");
        };
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

    private static UUID actorId(CommandSourceStack source) {
        var actor = source.getPlayer();
        return actor == null ? AdministrationService.SYSTEM_ACTOR : actor.getUUID();
    }

    private static boolean authorizationOverride(CommandSourceStack source, PlatformSavedData state) {
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
