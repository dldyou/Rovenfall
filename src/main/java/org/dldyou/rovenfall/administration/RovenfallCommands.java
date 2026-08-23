package org.dldyou.rovenfall.administration;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import java.time.Instant;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
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
                                        IntegerArgumentType.getInteger(context, "page") - 1))));

        event.getDispatcher().register(Commands.literal("rovenfall")
                .then(Commands.literal("admin")
                        .requires(RovenfallCommands::canUseAdministration)
                        .then(roleCommand)
                        .then(auditCommand)));
    }

    private static int setRole(CommandSourceStack source, net.minecraft.server.level.ServerPlayer target, String roleId, String reason) {
        PlatformSavedData state = PlatformSavedData.get(source.getServer());
        var actor = source.getPlayer();
        UUID actorId = actor == null ? AdministrationService.SYSTEM_ACTOR : actor.getUUID();
        boolean override = hasNativeOwnerPermission(source) && (actor == null || !state.hasAnyAdminRoles());
        UUID transactionId = UUID.randomUUID();

        var result = AdministrationService.changeRole(
                state,
                actorId,
                override,
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

    private static boolean canUseAdministration(CommandSourceStack source) {
        PlatformSavedData state = PlatformSavedData.get(source.getServer());
        var player = source.getPlayer();
        if (player == null) {
            return hasNativeOwnerPermission(source);
        }
        return state.hasAdminRole(player.getUUID()) || (!state.hasAnyAdminRoles() && hasNativeOwnerPermission(source));
    }

    private static boolean hasNativeOwnerPermission(CommandSourceStack source) {
        return source.permissions().hasPermission(Permissions.COMMANDS_OWNER);
    }

    private static int failure(CommandSourceStack source, String translationKey, Object... arguments) {
        source.sendFailure(Component.translatable(translationKey, arguments));
        return 0;
    }
}
