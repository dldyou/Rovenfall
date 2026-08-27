package org.dldyou.rovenfall.rpg;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.time.Instant;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/** Player-facing career commands. All mutations remain inside {@link CareerProgressionService}. */
public final class RpgCommands {
    private RpgCommands() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> careerCommand() {
        return Commands.literal("career")
                .requires(source -> source.getPlayer() != null)
                .then(Commands.literal("promote")
                        .then(Commands.argument("career", IdentifierArgument.id())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                        RpgDefinitionReloadListener.snapshot(context.getSource().getServer())
                                                .careers().keySet().stream().map(Identifier::toString),
                                        builder))
                                .executes(context -> promote(
                                        context.getSource(), IdentifierArgument.getId(context, "career")))))
                .then(Commands.literal("switch")
                        .then(Commands.argument("career", IdentifierArgument.id())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                        RpgDefinitionReloadListener.snapshot(context.getSource().getServer())
                                                .careers().keySet().stream().map(Identifier::toString),
                                        builder))
                                .executes(context -> switchActive(
                                        context.getSource(), IdentifierArgument.getId(context, "career")))));
    }

    private static int promote(CommandSourceStack source, Identifier careerId) throws CommandSyntaxException {
        var result = CareerProgressionService.promote(
                RpgPlayerSavedData.get(source.getServer()),
                RpgDefinitionReloadListener.snapshot(source.getServer()),
                source.getPlayerOrException().getUUID(),
                careerId,
                Instant.now().toEpochMilli(),
                UUID.randomUUID(),
                "player_command");
        return switch (result.status()) {
            case SUCCESS -> success(source, "command.rovenfall.career.promote.success",
                    careerName(source, careerId), result.transactionId());
            case MISSING_PARENT -> failure(source, "command.rovenfall.career.promote.error.missing_parent",
                    careerName(source, result.blocker().orElseThrow()));
            case PARENT_RANK_TOO_LOW -> failure(
                    source, "command.rovenfall.career.promote.error.parent_rank",
                    careerName(source, result.blocker().orElseThrow()), result.requiredLevel(), result.actualLevel());
            case ACTIVITY_LEVEL_TOO_LOW -> failure(
                    source, "command.rovenfall.career.promote.error.activity_level",
                    definitionName(source, result.blocker().orElseThrow()), result.requiredLevel(), result.actualLevel());
            case ALREADY_PROMOTED -> failure(
                    source, "command.rovenfall.career.promote.error.already", careerName(source, careerId));
            case UNKNOWN_CAREER -> failure(source, "command.rovenfall.career.error.unknown", careerId);
            case READ_ONLY -> failure(source, "command.rovenfall.career.error.read_only");
            case STATE_FULL -> failure(source, "command.rovenfall.career.error.state_full");
            case DUPLICATE -> failure(source, "command.rovenfall.career.error.duplicate");
            case INVALID_REQUEST, ALREADY_ACTIVE, CAREER_NOT_PROMOTED -> failure(
                    source, "command.rovenfall.career.error.failed",
                    result.status().name().toLowerCase(java.util.Locale.ROOT));
        };
    }

    private static int switchActive(CommandSourceStack source, Identifier careerId) throws CommandSyntaxException {
        var result = CareerProgressionService.switchActive(
                RpgPlayerSavedData.get(source.getServer()),
                RpgDefinitionReloadListener.snapshot(source.getServer()),
                source.getPlayerOrException().getUUID(),
                careerId,
                Instant.now().toEpochMilli(),
                UUID.randomUUID(),
                "player_command");
        return switch (result.status()) {
            case SUCCESS -> success(source, "command.rovenfall.career.switch.success",
                    careerName(source, careerId), result.transactionId());
            case CAREER_NOT_PROMOTED -> failure(
                    source, "command.rovenfall.career.switch.error.not_promoted", careerName(source, careerId));
            case ALREADY_ACTIVE -> failure(
                    source, "command.rovenfall.career.switch.error.already", careerName(source, careerId));
            case UNKNOWN_CAREER -> failure(source, "command.rovenfall.career.error.unknown", careerId);
            case READ_ONLY -> failure(source, "command.rovenfall.career.error.read_only");
            case STATE_FULL -> failure(source, "command.rovenfall.career.error.state_full");
            case DUPLICATE -> failure(source, "command.rovenfall.career.error.duplicate");
            case INVALID_REQUEST, ALREADY_PROMOTED, MISSING_PARENT, PARENT_RANK_TOO_LOW,
                    ACTIVITY_LEVEL_TOO_LOW -> failure(
                    source, "command.rovenfall.career.error.failed",
                    result.status().name().toLowerCase(java.util.Locale.ROOT));
        };
    }

    private static Component careerName(CommandSourceStack source, Identifier careerId) {
        return RpgDefinitionReloadListener.snapshot(source.getServer()).career(careerId)
                .<Component>map(definition -> Component.translatable(definition.translationKey()))
                .orElseGet(() -> Component.literal(careerId.toString()));
    }

    private static Component definitionName(CommandSourceStack source, Identifier definitionId) {
        return RpgDefinitionReloadListener.snapshot(source.getServer()).activity(definitionId)
                .<Component>map(definition -> Component.translatable(definition.translationKey()))
                .orElseGet(() -> Component.literal(definitionId.toString()));
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
