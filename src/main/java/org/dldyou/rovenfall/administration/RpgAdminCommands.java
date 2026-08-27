package org.dldyou.rovenfall.administration;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.dldyou.rovenfall.rpg.ActivityXpConfig;
import org.dldyou.rovenfall.rpg.RpgAdministrationViewService;
import org.dldyou.rovenfall.rpg.RpgDefinitionReloadListener;
import org.dldyou.rovenfall.rpg.RpgDefinitionSnapshot;
import org.dldyou.rovenfall.rpg.RpgPlayerSavedData;
import org.dldyou.rovenfall.rpg.RpgPlayerState;
import org.dldyou.rovenfall.rpg.SkillResetPlan;

/** Offline-UUID-capable RPG diagnostics and role-checked recovery commands. */
final class RpgAdminCommands {
    private static final int PAGE_SIZE = 10;

    private RpgAdminCommands() {
    }

    static LiteralArgumentBuilder<CommandSourceStack> command() {
        var view = Commands.literal("view")
                .then(Commands.argument("player_id", UuidArgument.uuid())
                        .executes(context -> view(context.getSource(),
                                UuidArgument.getUuid(context, "player_id"), 0))
                        .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                .executes(context -> view(context.getSource(),
                                        UuidArgument.getUuid(context, "player_id"),
                                        IntegerArgumentType.getInteger(context, "page") - 1))));

        var historyAll = Commands.argument("player_id", UuidArgument.uuid())
                .executes(context -> history(context.getSource(),
                        UuidArgument.getUuid(context, "player_id"), Optional.empty(), false, 0))
                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                        .executes(context -> history(context.getSource(),
                                UuidArgument.getUuid(context, "player_id"), Optional.empty(), false,
                                IntegerArgumentType.getInteger(context, "page") - 1)));
        var historySuspicious = Commands.literal("suspicious")
                .then(Commands.argument("player_id", UuidArgument.uuid())
                        .executes(context -> history(context.getSource(),
                                UuidArgument.getUuid(context, "player_id"), Optional.empty(), true, 0))
                        .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                .executes(context -> history(context.getSource(),
                                        UuidArgument.getUuid(context, "player_id"), Optional.empty(), true,
                                        IntegerArgumentType.getInteger(context, "page") - 1))));
        var historyActivity = Commands.literal("activity")
                .then(Commands.argument("player_id", UuidArgument.uuid())
                        .then(Commands.argument("activity", IdentifierArgument.id())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                        definitions(context.getSource()).activities().keySet().stream()
                                                .map(Identifier::toString), builder))
                                .executes(context -> history(context.getSource(),
                                        UuidArgument.getUuid(context, "player_id"),
                                        Optional.of(IdentifierArgument.getId(context, "activity")), false, 0))
                                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                        .executes(context -> history(context.getSource(),
                                                UuidArgument.getUuid(context, "player_id"),
                                                Optional.of(IdentifierArgument.getId(context, "activity")), false,
                                                IntegerArgumentType.getInteger(context, "page") - 1)))));
        var history = Commands.literal("history").then(historyAll).then(historySuspicious).then(historyActivity);

        var xp = Commands.literal("xp")
                .then(xpMutation("add", 1))
                .then(xpMutation("remove", -1));
        var promotion = Commands.literal("promotion")
                .then(Commands.literal("recover")
                        .then(Commands.argument("player_id", UuidArgument.uuid())
                                .then(Commands.argument("career", IdentifierArgument.id())
                                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                                definitions(context.getSource()).careers().keySet().stream()
                                                        .map(Identifier::toString), builder))
                                        .then(Commands.argument("transaction_id", UuidArgument.uuid())
                                                .then(Commands.argument("reason", StringArgumentType.greedyString())
                                                        .executes(context -> recoverPromotion(
                                                                context.getSource(),
                                                                UuidArgument.getUuid(context, "player_id"),
                                                                IdentifierArgument.getId(context, "career"),
                                                                UuidArgument.getUuid(context, "transaction_id"),
                                                                StringArgumentType.getString(context, "reason"))))))));
        var skill = Commands.literal("skill")
                .then(Commands.literal("reset")
                        .then(resetMutation("branch", SkillResetPlan.Mode.BRANCH))
                        .then(resetMutation("full", SkillResetPlan.Mode.FULL)));

        return Commands.literal("rpg")
                .then(view)
                .then(history)
                .then(Commands.literal("config").executes(context -> config(context.getSource())))
                .then(xp)
                .then(promotion)
                .then(skill);
    }

    private static LiteralArgumentBuilder<CommandSourceStack> xpMutation(String literal, int sign) {
        return Commands.literal(literal)
                .then(Commands.argument("player_id", UuidArgument.uuid())
                        .then(Commands.argument("activity", IdentifierArgument.id())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                        definitions(context.getSource()).activities().keySet().stream()
                                                .map(Identifier::toString), builder))
                                .then(Commands.argument("amount", LongArgumentType.longArg(1, RpgPlayerState.MAX_XP))
                                        .then(Commands.argument("transaction_id", UuidArgument.uuid())
                                                .then(Commands.argument("reason", StringArgumentType.greedyString())
                                                        .executes(context -> adjustXp(
                                                                context.getSource(),
                                                                UuidArgument.getUuid(context, "player_id"),
                                                                IdentifierArgument.getId(context, "activity"),
                                                                LongArgumentType.getLong(context, "amount") * sign,
                                                                UuidArgument.getUuid(context, "transaction_id"),
                                                                StringArgumentType.getString(context, "reason"))))))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> resetMutation(
            String literal,
            SkillResetPlan.Mode mode) {
        return Commands.literal(literal)
                .then(Commands.argument("player_id", UuidArgument.uuid())
                        .then(Commands.argument("target", IdentifierArgument.id())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                        (mode == SkillResetPlan.Mode.BRANCH
                                                ? definitions(context.getSource()).skills().keySet()
                                                : definitions(context.getSource()).careers().keySet())
                                                .stream().map(Identifier::toString), builder))
                                .then(Commands.argument("transaction_id", UuidArgument.uuid())
                                        .then(Commands.argument("reason", StringArgumentType.greedyString())
                                                .executes(context -> reset(
                                                        context.getSource(),
                                                        UuidArgument.getUuid(context, "player_id"),
                                                        mode,
                                                        IdentifierArgument.getId(context, "target"),
                                                        UuidArgument.getUuid(context, "transaction_id"),
                                                        StringArgumentType.getString(context, "reason")))))));
    }

    private static int view(CommandSourceStack source, UUID playerId, int page) {
        PlatformSavedData platform = PlatformSavedData.get(source.getServer());
        if (!RpgAdministrationService.canView(
                platform, RovenfallCommands.actorId(source), RovenfallCommands.authorizationOverride(source, platform))) {
            return failure(source, "command.rovenfall.admin.rpg.error.unauthorized");
        }
        RpgDefinitionSnapshot definitions = definitions(source);
        RpgPlayerSavedData rpg = RpgPlayerSavedData.get(source.getServer());
        var result = RpgAdministrationViewService.progression(rpg, definitions, playerId, page, PAGE_SIZE);
        source.sendSuccess(() -> Component.translatable(
                "command.rovenfall.admin.rpg.view.summary",
                playerId,
                result.page() + 1,
                result.totalPages(),
                result.totalEntries(),
                result.activeCareer().map(Object::toString).orElse("none"),
                rpg.schemaVersion(),
                rpg.isWritable()), false);
        if (result.entries().isEmpty()) {
            return success(source, "command.rovenfall.admin.rpg.view.empty");
        }
        for (var entry : result.entries()) {
            String key = "command.rovenfall.admin.rpg.view." + entry.kind().name().toLowerCase(java.util.Locale.ROOT);
            source.sendSuccess(() -> Component.translatable(
                    key,
                    definitionName(definitions, entry.id()),
                    entry.owner().map(Object::toString).orElse("none"),
                    entry.value(),
                    entry.rank(),
                    entry.points()), false);
        }
        return 1;
    }

    private static int history(
            CommandSourceStack source,
            UUID playerId,
            Optional<Identifier> activity,
            boolean suspiciousOnly,
            int page) {
        PlatformSavedData platform = PlatformSavedData.get(source.getServer());
        if (!RpgAdministrationService.canView(
                platform, RovenfallCommands.actorId(source), RovenfallCommands.authorizationOverride(source, platform))) {
            return failure(source, "command.rovenfall.admin.rpg.error.unauthorized");
        }
        RpgDefinitionSnapshot definitions = definitions(source);
        var result = RpgAdministrationViewService.awardHistory(
                RpgPlayerSavedData.get(source.getServer()), playerId, activity, suspiciousOnly,
                page, PAGE_SIZE, ActivityXpConfig.snapshot());
        source.sendSuccess(() -> Component.translatable(
                "command.rovenfall.admin.rpg.history.summary",
                playerId, result.page() + 1, result.totalPages(), result.totalEntries(), suspiciousOnly), false);
        if (result.entries().isEmpty()) {
            return success(source, "command.rovenfall.admin.rpg.history.empty");
        }
        for (var entry : result.entries()) {
            var evidence = entry.evidence();
            source.sendSuccess(() -> Component.translatable(
                    "command.rovenfall.admin.rpg.history.entry",
                    Instant.ofEpochMilli(evidence.timestamp()).toString(),
                    definitionName(definitions, evidence.target()),
                    evidence.amount(),
                    evidence.source(),
                    evidence.transactionId(),
                    suspicionText(entry.suspicions())), false);
        }
        return 1;
    }

    private static int config(CommandSourceStack source) {
        PlatformSavedData platform = PlatformSavedData.get(source.getServer());
        if (!RpgAdministrationService.canView(
                platform, RovenfallCommands.actorId(source), RovenfallCommands.authorizationOverride(source, platform))) {
            return failure(source, "command.rovenfall.admin.rpg.error.unauthorized");
        }
        ActivityXpConfig.ConfigSnapshot config = ActivityXpConfig.snapshot();
        RpgPlayerSavedData rpg = RpgPlayerSavedData.get(source.getServer());
        RpgDefinitionSnapshot definitions = definitions(source);
        source.sendSuccess(() -> Component.translatable(
                "command.rovenfall.admin.rpg.config.progression",
                config.maxAward(), config.maxWindowAwards(), config.windowMillis(), config.cooldownMillis(),
                config.combatTargetXpCap()), false);
        source.sendSuccess(() -> Component.translatable(
                "command.rovenfall.admin.rpg.config.skills",
                config.branchResetCost(), config.fullResetCost(), config.activeSkillSlots(),
                config.explorationAdvancementCount()), false);
        return success(source, "command.rovenfall.admin.rpg.config.state",
                rpg.schemaVersion(), rpg.isWritable(), RpgDefinitionReloadListener.revision(source.getServer()),
                definitions.activities().size(), definitions.careers().size(), definitions.skills().size());
    }

    private static int adjustXp(
            CommandSourceStack source,
            UUID playerId,
            Identifier activity,
            long delta,
            UUID transactionId,
            String reason) {
        PlatformSavedData platform = PlatformSavedData.get(source.getServer());
        return mutationResult(source, RpgAdministrationService.adjustActivityXp(
                platform,
                RpgPlayerSavedData.get(source.getServer()),
                definitions(source),
                RovenfallCommands.actorId(source),
                RovenfallCommands.authorizationOverride(source, platform),
                playerId,
                activity,
                delta,
                reason,
                Instant.now().toEpochMilli(),
                transactionId));
    }

    private static int recoverPromotion(
            CommandSourceStack source,
            UUID playerId,
            Identifier career,
            UUID transactionId,
            String reason) {
        PlatformSavedData platform = PlatformSavedData.get(source.getServer());
        return mutationResult(source, RpgAdministrationService.recoverPromotion(
                platform,
                RpgPlayerSavedData.get(source.getServer()),
                definitions(source),
                RovenfallCommands.actorId(source),
                RovenfallCommands.authorizationOverride(source, platform),
                playerId,
                career,
                reason,
                Instant.now().toEpochMilli(),
                transactionId));
    }

    private static int reset(
            CommandSourceStack source,
            UUID playerId,
            SkillResetPlan.Mode mode,
            Identifier target,
            UUID transactionId,
            String reason) {
        PlatformSavedData platform = PlatformSavedData.get(source.getServer());
        return mutationResult(source, RpgAdministrationService.resetSkills(
                platform,
                RpgPlayerSavedData.get(source.getServer()),
                definitions(source),
                RovenfallCommands.actorId(source),
                RovenfallCommands.authorizationOverride(source, platform),
                playerId,
                mode,
                target,
                reason,
                Instant.now().toEpochMilli(),
                transactionId));
    }

    private static int mutationResult(CommandSourceStack source, RpgAdministrationService.Result result) {
        if (result.status() == RpgAdministrationService.Status.SUCCESS) {
            return success(source, "command.rovenfall.admin.rpg.mutation.success",
                    result.beforeAmount(), result.afterAmount(), result.transactionId());
        }
        if (result.status() == RpgAdministrationService.Status.DUPLICATE) {
            return success(source, "command.rovenfall.admin.rpg.mutation.duplicate", result.transactionId());
        }
        return failure(source, "command.rovenfall.admin.rpg.error." +
                result.status().name().toLowerCase(java.util.Locale.ROOT));
    }

    private static RpgDefinitionSnapshot definitions(CommandSourceStack source) {
        return RpgDefinitionReloadListener.snapshot(source.getServer());
    }

    private static Component definitionName(RpgDefinitionSnapshot definitions, Identifier id) {
        return definitions.activity(id).<Component>map(value -> Component.translatable(value.translationKey()))
                .or(() -> definitions.career(id).<Component>map(value -> Component.translatable(value.translationKey())))
                .or(() -> definitions.skill(id).<Component>map(value -> Component.translatable(value.translationKey())))
                .orElseGet(() -> Component.literal(id.toString()));
    }

    private static Component suspicionText(java.util.Set<RpgAdministrationViewService.Suspicion> suspicions) {
        if (suspicions.isEmpty()) {
            return Component.translatable("command.rovenfall.admin.rpg.history.normal");
        }
        Component result = Component.empty();
        boolean first = true;
        for (var suspicion : suspicions.stream().sorted().toList()) {
            if (!first) {
                result = result.copy().append(Component.literal(", "));
            }
            result = result.copy().append(Component.translatable(
                    "command.rovenfall.admin.rpg.history.suspicion." +
                            suspicion.name().toLowerCase(java.util.Locale.ROOT)));
            first = false;
        }
        return result;
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
