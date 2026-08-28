package org.dldyou.rovenfall.rpg;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.dldyou.rovenfall.administration.CareerPromotionPaymentService;
import org.dldyou.rovenfall.administration.RpgSkillPaymentService;

/** Player-facing career and skill commands. All mutations remain in server-owned service boundaries. */
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

    public static LiteralArgumentBuilder<CommandSourceStack> skillCommand() {
        return Commands.literal("skill")
                .requires(source -> source.getPlayer() != null)
                .then(Commands.literal("learn")
                        .then(Commands.argument("skill", IdentifierArgument.id())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                        RpgDefinitionReloadListener.snapshot(context.getSource().getServer())
                                                .skills().keySet().stream().map(Identifier::toString),
                                        builder))
                                .executes(context -> learn(
                                        context.getSource(), IdentifierArgument.getId(context, "skill")))))
                .then(Commands.literal("bind")
                        .then(Commands.argument("slot", IntegerArgumentType.integer(
                                        1, RpgPlayerState.MAX_ACTIVE_SKILL_SLOTS))
                                .then(Commands.argument("skill", IdentifierArgument.id())
                                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                                RpgDefinitionReloadListener.snapshot(context.getSource().getServer())
                                                        .skills().entrySet().stream()
                                                        .filter(entry -> entry.getValue().kind()
                                                                == SkillDefinition.Kind.ACTIVE)
                                                        .map(entry -> entry.getKey().toString()),
                                                builder))
                                        .executes(context -> assignSlot(
                                                context.getSource(),
                                                IntegerArgumentType.getInteger(context, "slot") - 1,
                                                Optional.of(IdentifierArgument.getId(context, "skill")))))))
                .then(Commands.literal("unbind")
                        .then(Commands.argument("slot", IntegerArgumentType.integer(
                                        1, RpgPlayerState.MAX_ACTIVE_SKILL_SLOTS))
                                .executes(context -> assignSlot(
                                        context.getSource(),
                                        IntegerArgumentType.getInteger(context, "slot") - 1,
                                        Optional.empty()))))
                .then(Commands.literal("reset")
                        .then(Commands.literal("branch")
                                .then(Commands.argument("skill", IdentifierArgument.id())
                                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                                RpgDefinitionReloadListener.snapshot(context.getSource().getServer())
                                                        .skills().keySet().stream().map(Identifier::toString),
                                                builder))
                                        .executes(context -> reset(
                                                context.getSource(), SkillResetPlan.Mode.BRANCH,
                                                IdentifierArgument.getId(context, "skill")))))
                        .then(Commands.literal("full")
                                .then(Commands.argument("career", IdentifierArgument.id())
                                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                                RpgDefinitionReloadListener.snapshot(context.getSource().getServer())
                                                        .careers().keySet().stream().map(Identifier::toString),
                                                builder))
                                        .executes(context -> reset(
                                                context.getSource(), SkillResetPlan.Mode.FULL,
                                                IdentifierArgument.getId(context, "career"))))));
    }

    private static int promote(CommandSourceStack source, Identifier careerId) throws CommandSyntaxException {
        var coordinated = PlayerCareerPromotionService.promote(
                source.getPlayerOrException(), careerId, Instant.now().toEpochMilli());
        if (coordinated.status() == PlayerCareerPromotionService.Status.PAYMENT_FAILED) {
            if (coordinated.paymentStatus().orElse(null)
                    == CareerPromotionPaymentService.Status.INSUFFICIENT_FUNDS) {
                return failure(source, "command.rovenfall.career.promote.error.funds",
                        coordinated.cost(), coordinated.balance());
            }
            return failure(source, "command.rovenfall.career.error.failed",
                    coordinated.paymentStatus().map(value -> value.name().toLowerCase(java.util.Locale.ROOT))
                            .orElse("payment_failed"));
        }
        if (coordinated.status() == PlayerCareerPromotionService.Status.ITEM_PAYMENT_FAILED) {
            return failure(source, "command.rovenfall.career.promote.error.items");
        }
        if (coordinated.status() == PlayerCareerPromotionService.Status.COMPLETION_FAILED) {
            return failure(source, "command.rovenfall.career.error.failed", "completion_failed");
        }
        var result = coordinated.promotion();
        return switch (result.status()) {
            case SUCCESS, DUPLICATE -> success(source, "command.rovenfall.career.promote.success",
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

    private static int learn(CommandSourceStack source, Identifier skillId) throws CommandSyntaxException {
        var result = RpgSkillService.learn(
                RpgPlayerSavedData.get(source.getServer()),
                RpgDefinitionReloadListener.snapshot(source.getServer()),
                source.getPlayerOrException().getUUID(), skillId, Instant.now().toEpochMilli(),
                UUID.randomUUID(), "player_command");
        return switch (result.status()) {
            case SUCCESS -> success(source, "command.rovenfall.skill.learn.success",
                    skillName(source, skillId), result.skillRank(), result.remainingPoints());
            case PREREQUISITE_NOT_MET -> failure(source, "command.rovenfall.skill.learn.error.prerequisite",
                    skillName(source, result.blocker().orElseThrow()), result.requiredRank(), result.actualRank());
            case INSUFFICIENT_POINTS -> failure(source, "command.rovenfall.skill.learn.error.points",
                    result.remainingPoints());
            case MAX_RANK -> failure(source, "command.rovenfall.skill.learn.error.max_rank",
                    skillName(source, skillId), result.skillRank());
            case CAREER_NOT_PROMOTED -> failure(source, "command.rovenfall.skill.learn.error.career");
            case UNKNOWN_SKILL -> failure(source, "command.rovenfall.skill.error.unknown", skillId);
            case READ_ONLY -> failure(source, "command.rovenfall.skill.error.read_only");
            case DUPLICATE, STATE_FULL, INVALID_REQUEST, UNKNOWN_CAREER, NOTHING_TO_RESET,
                    STATE_CONFLICT, OVERFLOW -> failure(source, "command.rovenfall.skill.error.failed",
                    result.status().name().toLowerCase(java.util.Locale.ROOT));
        };
    }

    private static int reset(
            CommandSourceStack source, SkillResetPlan.Mode mode, Identifier target) throws CommandSyntaxException {
        var result = RpgSkillResetCoordinator.reset(
                source.getPlayerOrException(), mode, target, Instant.now().toEpochMilli(), UUID.randomUUID());
        if (result.status() == RpgSkillResetCoordinator.Status.SUCCESS) {
            return success(source, "command.rovenfall.skill.reset.success",
                    result.cost(), result.balance(), result.transactionId());
        }
        if (result.status() == RpgSkillResetCoordinator.Status.ITEM_PAYMENT_FAILED) {
            return failure(source, "command.rovenfall.skill.reset.error.items");
        }
        if (result.paymentStatus().orElse(null) == RpgSkillPaymentService.Status.INSUFFICIENT_FUNDS) {
            return failure(source, "command.rovenfall.skill.reset.error.funds", result.cost(), result.balance());
        }
        if (result.rpgStatus() == RpgSkillService.Status.NOTHING_TO_RESET) {
            return failure(source, "command.rovenfall.skill.reset.error.empty");
        }
        if (result.rpgStatus() == RpgSkillService.Status.UNKNOWN_SKILL
                || result.rpgStatus() == RpgSkillService.Status.UNKNOWN_CAREER) {
            return failure(source, "command.rovenfall.skill.error.unknown", target);
        }
        return failure(source, "command.rovenfall.skill.error.failed",
                result.status().name().toLowerCase(java.util.Locale.ROOT));
    }

    private static int assignSlot(
            CommandSourceStack source,
            int slot,
            Optional<Identifier> skill) throws CommandSyntaxException {
        var player = source.getPlayerOrException();
        var result = RpgActiveSkillService.assignSlot(
                RpgPlayerSavedData.get(source.getServer()),
                RpgDefinitionReloadListener.snapshot(source.getServer()),
                player.getUUID(),
                slot,
                skill,
                ActivityXpConfig.activeSkillSlots(),
                Instant.now().toEpochMilli(),
                UUID.randomUUID(),
                "player_command");
        if (result.status() == RpgActiveSkillService.Status.SUCCESS) {
            RpgSkillNetwork.sync(player);
            return skill.isPresent()
                    ? success(source, "command.rovenfall.skill.bind.success",
                            slot + 1, skillName(source, skill.orElseThrow()))
                    : success(source, "command.rovenfall.skill.unbind.success", slot + 1);
        }
        return switch (result.status()) {
            case INVALID_SLOT, INVALID_REQUEST -> failure(
                    source, "command.rovenfall.skill.bind.error.slot", slot + 1,
                    ActivityXpConfig.activeSkillSlots());
            case UNKNOWN_SKILL -> failure(
                    source, "command.rovenfall.skill.error.unknown", skill.orElse(null));
            case SKILL_NOT_ACTIVE -> failure(source, "command.rovenfall.skill.bind.error.not_active");
            case EFFECT_UNAVAILABLE -> failure(source, "command.rovenfall.skill.bind.error.effect");
            case NOT_LEARNED -> failure(source, "command.rovenfall.skill.bind.error.not_learned");
            case INACTIVE_CAREER -> failure(source, "command.rovenfall.skill.bind.error.career");
            case NOTHING_BOUND -> failure(source, "command.rovenfall.skill.unbind.error.empty", slot + 1);
            case READ_ONLY -> failure(source, "command.rovenfall.skill.error.read_only");
            case DUPLICATE, STATE_FULL, STALE_DEFINITIONS, WRONG_DIMENSION,
                    INVALID_TARGET, COOLDOWN -> failure(source, "command.rovenfall.skill.error.failed",
                    result.status().name().toLowerCase(java.util.Locale.ROOT));
            case SUCCESS -> 1;
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

    private static Component skillName(CommandSourceStack source, Identifier skillId) {
        return RpgDefinitionReloadListener.snapshot(source.getServer()).skill(skillId)
                .<Component>map(definition -> Component.translatable(definition.translationKey()))
                .orElseGet(() -> Component.literal(skillId.toString()));
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
