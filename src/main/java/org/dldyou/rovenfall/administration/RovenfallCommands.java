package org.dldyou.rovenfall.administration;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.time.Instant;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

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
                                                                false)))))));

        event.getDispatcher().register(Commands.literal("rovenfall")
                .then(Commands.literal("admin")
                        .requires(RovenfallCommands::canUseAdministration)
                        .then(roleCommand)
                        .then(economyCommand)
                        .then(auditCommand)
                        .then(snapshotCommand)));
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
            case UNAUTHORIZED -> failure(source, "command.rovenfall.admin.snapshot.error.unauthorized");
            case INVALID_REASON -> failure(source, "command.rovenfall.admin.error.invalid_reason", AdministrationService.MAX_REASON_LENGTH);
            case READ_ONLY_SCHEMA -> failure(source, "command.rovenfall.admin.error.read_only_schema", state.schemaVersion());
            case SNAPSHOT_UNAVAILABLE -> failure(source, "command.rovenfall.admin.snapshot.error.unavailable", snapshotId.toString());
            case TRANSACTION_LEDGER_FULL -> failure(
                    source, "command.rovenfall.admin.snapshot.error.transaction_ledger_full");
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
}
