package org.dldyou.rovenfall.administration;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import java.time.Instant;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.network.chat.Component;

/** Readable mob/boss diagnostics and owner-only safe recovery commands. */
final class BossAdminCommands {
    private static final int PAGE_SIZE = 10;

    private BossAdminCommands() {
    }

    static LiteralArgumentBuilder<CommandSourceStack> command() {
        var list = Commands.literal("list")
                .executes(context -> encounters(context.getSource(), 0))
                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                        .executes(context -> encounters(context.getSource(),
                                IntegerArgumentType.getInteger(context, "page") - 1)));
        var mutations = Commands.literal("mutations")
                .executes(context -> mutations(context.getSource(), 0))
                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                        .executes(context -> mutations(context.getSource(),
                                IntegerArgumentType.getInteger(context, "page") - 1)));
        var info = Commands.literal("info")
                .then(Commands.argument("encounter_id", UuidArgument.uuid())
                        .executes(context -> info(context.getSource(),
                                UuidArgument.getUuid(context, "encounter_id"))));
        var participants = Commands.literal("participants")
                .then(Commands.argument("encounter_id", UuidArgument.uuid())
                        .executes(context -> participants(context.getSource(),
                                UuidArgument.getUuid(context, "encounter_id"), 0))
                        .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                .executes(context -> participants(context.getSource(),
                                        UuidArgument.getUuid(context, "encounter_id"),
                                        IntegerArgumentType.getInteger(context, "page") - 1))));
        var rewards = Commands.literal("rewards")
                .then(Commands.argument("encounter_id", UuidArgument.uuid())
                        .executes(context -> rewards(context.getSource(),
                                UuidArgument.getUuid(context, "encounter_id"), 0))
                        .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                .executes(context -> rewards(context.getSource(),
                                        UuidArgument.getUuid(context, "encounter_id"),
                                        IntegerArgumentType.getInteger(context, "page") - 1))));
        var cooldowns = Commands.literal("cooldowns")
                .then(Commands.argument("player_id", UuidArgument.uuid())
                        .executes(context -> cooldowns(context.getSource(),
                                UuidArgument.getUuid(context, "player_id"), 0))
                        .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                .executes(context -> cooldowns(context.getSource(),
                                        UuidArgument.getUuid(context, "player_id"),
                                        IntegerArgumentType.getInteger(context, "page") - 1))));
        var reset = Commands.literal("reset")
                .then(Commands.argument("encounter_id", UuidArgument.uuid())
                        .then(Commands.argument("transaction_id", UuidArgument.uuid())
                                .then(Commands.argument("reason", StringArgumentType.greedyString())
                                        .executes(context -> reset(
                                                context.getSource(),
                                                UuidArgument.getUuid(context, "encounter_id"),
                                                UuidArgument.getUuid(context, "transaction_id"),
                                                StringArgumentType.getString(context, "reason"))))));
        var recover = Commands.literal("recover")
                .then(Commands.argument("transaction_id", UuidArgument.uuid())
                        .then(Commands.argument("reason", StringArgumentType.greedyString())
                                .executes(context -> recover(
                                        context.getSource(),
                                        UuidArgument.getUuid(context, "transaction_id"),
                                        StringArgumentType.getString(context, "reason")))));
        return Commands.literal("boss")
                .then(list).then(info).then(participants).then(rewards).then(cooldowns)
                .then(mutations).then(reset).then(recover);
    }

    private static int encounters(CommandSourceStack source, int page) {
        if (!canView(source)) {
            return failure(source, "command.rovenfall.admin.boss.error.unauthorized");
        }
        var result = BossAdministrationViewService.encounters(source.getServer(), page, PAGE_SIZE);
        header(source, "command.rovenfall.admin.boss.list.header", result);
        for (var row : result.entries()) {
            source.sendSuccess(() -> Component.translatable(
                    "command.rovenfall.admin.boss.list.entry",
                    row.encounterId(), row.bossId(), row.stage().getSerializedName(), row.phaseIndex(),
                    row.participantCount(), row.arenaProtected(), row.dimension(), row.center().toShortString()), false);
        }
        return 1;
    }

    private static int info(CommandSourceStack source, UUID encounterId) {
        if (!canView(source)) {
            return failure(source, "command.rovenfall.admin.boss.error.unauthorized");
        }
        var row = BossAdministrationViewService.encounter(source.getServer(), encounterId).orElse(null);
        if (row == null) {
            return failure(source, "command.rovenfall.admin.boss.error.not_found");
        }
        return success(source, "command.rovenfall.admin.boss.info",
                row.encounterId(), row.bossId(), row.entityId(), row.stage().getSerializedName(), row.phaseIndex(),
                row.patternId().map(Object::toString).orElse("none"), row.participantCount(),
                row.arenaProtected(), row.dimension(), row.center().toShortString(),
                Instant.ofEpochMilli(row.startedAtEpochMillis()),
                Instant.ofEpochMilli(row.lastParticipantAtEpochMillis()));
    }

    private static int participants(CommandSourceStack source, UUID encounterId, int page) {
        if (!canView(source)) {
            return failure(source, "command.rovenfall.admin.boss.error.unauthorized");
        }
        var result = BossAdministrationViewService.participants(source.getServer(), encounterId, page, PAGE_SIZE);
        header(source, "command.rovenfall.admin.boss.participants.header", result, encounterId);
        for (var row : result.entries()) {
            source.sendSuccess(() -> Component.translatable(
                    "command.rovenfall.admin.boss.participants.entry",
                    row.playerId(), row.points(), row.totalPoints()), false);
        }
        return 1;
    }

    private static int rewards(CommandSourceStack source, UUID encounterId, int page) {
        if (!canView(source)) {
            return failure(source, "command.rovenfall.admin.boss.error.unauthorized");
        }
        var result = BossAdministrationViewService.rewards(source.getServer(), encounterId, page, PAGE_SIZE);
        header(source, "command.rovenfall.admin.boss.rewards.header", result, encounterId);
        for (var row : result.entries()) {
            source.sendSuccess(() -> Component.translatable(
                    "command.rovenfall.admin.boss.rewards.entry",
                    row.transactionId(), row.playerId(), row.points(), row.totalPoints(), row.currency(),
                    row.experience(), row.itemStacks(), row.phase().getSerializedName(),
                    Instant.ofEpochMilli(row.cooldownUntilEpochMillis())), false);
        }
        return 1;
    }

    private static int cooldowns(CommandSourceStack source, UUID playerId, int page) {
        if (!canView(source)) {
            return failure(source, "command.rovenfall.admin.boss.error.unauthorized");
        }
        var result = BossAdministrationViewService.cooldowns(source.getServer(), playerId, page, PAGE_SIZE);
        header(source, "command.rovenfall.admin.boss.cooldowns.header", result, playerId);
        for (var row : result.entries()) {
            source.sendSuccess(() -> Component.translatable(
                    "command.rovenfall.admin.boss.cooldowns.entry",
                    row.bossId(), Instant.ofEpochMilli(row.deadlineEpochMillis()),
                    row.phase().getSerializedName(), row.transactionId()), false);
        }
        return 1;
    }

    private static int mutations(CommandSourceStack source, int page) {
        if (!canView(source)) {
            return failure(source, "command.rovenfall.admin.boss.error.unauthorized");
        }
        var result = BossAdministrationViewService.activeMutations(source.getServer(), page, PAGE_SIZE);
        header(source, "command.rovenfall.admin.boss.mutations.header", result);
        if (result.truncated()) {
            source.sendSuccess(() -> Component.translatable(
                    "command.rovenfall.admin.boss.mutations.truncated",
                    BossAdministrationViewService.MAX_MUTATION_SCAN_ENTITIES,
                    BossAdministrationViewService.MAX_MUTATION_ROWS), false);
        }
        for (var row : result.entries()) {
            source.sendSuccess(() -> Component.translatable(
                    "command.rovenfall.admin.boss.mutations.entry",
                    row.entityId(), row.entityType(), row.dimension(), row.position().toShortString(),
                    row.mutations().stream().map(Object::toString).sorted()
                            .collect(java.util.stream.Collectors.joining(", "))), false);
        }
        return 1;
    }

    private static int reset(CommandSourceStack source, UUID encounterId, UUID transactionId, String reason) {
        PlatformSavedData platform = PlatformSavedData.get(source.getServer());
        return mutationResult(source, BossAdministrationService.reset(
                source.getServer(), RovenfallCommands.actorId(source),
                RovenfallCommands.authorizationOverride(source, platform), encounterId, reason,
                Instant.now().toEpochMilli(), transactionId));
    }

    private static int recover(CommandSourceStack source, UUID transactionId, String reason) {
        PlatformSavedData platform = PlatformSavedData.get(source.getServer());
        return mutationResult(source, BossAdministrationService.recover(
                source.getServer(), RovenfallCommands.actorId(source),
                RovenfallCommands.authorizationOverride(source, platform), reason,
                Instant.now().toEpochMilli(), transactionId));
    }

    private static int mutationResult(CommandSourceStack source, BossAdministrationService.Result result) {
        if (result.status() == BossAdministrationService.Status.SUCCESS) {
            return success(source, "command.rovenfall.admin.boss.mutation.success", result.transactionId());
        }
        if (result.status() == BossAdministrationService.Status.DUPLICATE) {
            return success(source, "command.rovenfall.admin.boss.mutation.duplicate", result.transactionId());
        }
        return failure(source, "command.rovenfall.admin.boss.error."
                + result.status().name().toLowerCase(java.util.Locale.ROOT));
    }

    private static boolean canView(CommandSourceStack source) {
        PlatformSavedData platform = PlatformSavedData.get(source.getServer());
        return BossAdministrationService.canView(
                platform, RovenfallCommands.actorId(source),
                RovenfallCommands.authorizationOverride(source, platform));
    }

    private static void header(
            CommandSourceStack source, String key, BossAdministrationViewService.Page<?> page,
            Object... prefix) {
        Object[] arguments = new Object[prefix.length + 3];
        System.arraycopy(prefix, 0, arguments, 0, prefix.length);
        arguments[prefix.length] = page.page() + 1;
        arguments[prefix.length + 1] = page.totalPages();
        arguments[prefix.length + 2] = page.totalEntries();
        source.sendSuccess(() -> Component.translatable(key, arguments), false);
    }

    private static int success(CommandSourceStack source, String key, Object... arguments) {
        source.sendSuccess(() -> Component.translatable(key, arguments), false);
        return 1;
    }

    private static int failure(CommandSourceStack source, String key, Object... arguments) {
        source.sendFailure(Component.translatable(key, arguments));
        return 0;
    }
}
