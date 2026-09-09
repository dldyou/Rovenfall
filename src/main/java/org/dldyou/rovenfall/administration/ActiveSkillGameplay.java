package org.dldyou.rovenfall.administration;

import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import org.dldyou.rovenfall.careers.CareerActiveSkillDefinition;
import org.dldyou.rovenfall.careers.CareerDefinitionReloadListener;

public final class ActiveSkillGameplay {
    private ActiveSkillGameplay() {
    }

    public static Optional<ActiveSkillService.Result> use(
            ServerPlayer player,
            int slot,
            long timestampEpochMillis) {
        if (player == null || player.connection == null) {
            return Optional.empty();
        }
        var retainedCatalog = CareerDefinitionReloadListener.snapshot(player.level().getServer());
        if (retainedCatalog.isEmpty()) {
            sendOverlay(player, Component.translatable(
                    "message.rovenfall.active_skill.catalog_unavailable"));
            return Optional.empty();
        }
        var state = PlatformSavedData.get(player.level().getServer());
        var result = ActiveSkillService.use(
                state,
                retainedCatalog.orElseThrow(),
                player.getUUID(),
                slot,
                timestampEpochMillis,
                BuiltInRegistries.MOB_EFFECT::containsKey,
                definition -> canApply(player, definition));
        if (result.status() == ActiveSkillService.Status.SUCCESS) {
            var evaluation = result.evaluation();
            CareerActiveSkillDefinition definition = evaluation.activeDefinition().orElseThrow();
            Holder.Reference<MobEffect> effect = BuiltInRegistries.MOB_EFFECT
                    .get(definition.effectId()).orElseThrow();
            player.addEffect(new MobEffectInstance(
                    effect, definition.durationTicks(), definition.amplifier()));
            String translationKey = evaluation.binding().orElseThrow().definition().translationKey();
            sendOverlay(player, Component.translatable(
                    "message.rovenfall.active_skill.used",
                    Component.translatable(translationKey),
                    definition.cooldownSeconds()));
        } else if (result.status() == ActiveSkillService.Status.COOLDOWN) {
            sendOverlay(player, Component.translatable(
                    "message.rovenfall.active_skill.cooldown",
                    result.evaluation().retryAfterSeconds()));
        } else {
            sendOverlay(player, Component.translatable(
                    "message.rovenfall.active_skill.denied",
                    Component.translatable(result.status().translationKey())));
        }
        return Optional.of(result);
    }

    private static boolean canApply(ServerPlayer player, CareerActiveSkillDefinition definition) {
        if (!player.isAffectedByPotions()) {
            return false;
        }
        Optional<Holder.Reference<MobEffect>> retainedEffect = BuiltInRegistries.MOB_EFFECT.get(definition.effectId());
        if (retainedEffect.isEmpty()) {
            return false;
        }
        MobEffectInstance current = player.getEffect(retainedEffect.orElseThrow());
        return current == null
                || current.getAmplifier() < definition.amplifier()
                || current.getAmplifier() == definition.amplifier()
                && current.getDuration() < definition.durationTicks();
    }

    private static void sendOverlay(ServerPlayer player, Component message) {
        if (player.connection != null) {
            player.sendOverlayMessage(message);
        }
    }
}
