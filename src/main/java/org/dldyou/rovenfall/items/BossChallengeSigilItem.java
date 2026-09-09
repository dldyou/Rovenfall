package org.dldyou.rovenfall.items;

import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.util.FakePlayer;
import org.dldyou.rovenfall.Rovenfall;
import org.dldyou.rovenfall.administration.BossEncounterService;
import org.dldyou.rovenfall.administration.PlatformSavedData;
import org.dldyou.rovenfall.mobs.BossEvents;

public final class BossChallengeSigilItem extends Item {
    private static final Identifier CHALLENGE_ADVANCEMENT = Identifier.fromNamespaceAndPath(
            Rovenfall.MOD_ID, "wilderness/challenge_the_warden");

    public BossChallengeSigilItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (!(level instanceof ServerLevel serverLevel)
                || !(player instanceof ServerPlayer serverPlayer)
                || serverPlayer instanceof FakePlayer) {
            return InteractionResult.FAIL;
        }

        ItemStack stack = player.getItemInHand(hand);
        var result = BossEncounterService.startPlayerChallenge(
                PlatformSavedData.get(serverLevel.getServer()),
                serverPlayer.getUUID(),
                serverLevel.dimension(),
                serverPlayer.blockPosition(),
                System.currentTimeMillis(),
                UUID.randomUUID(),
                encounter -> BossEvents.spawnManaged(serverLevel, encounter));
        sendFeedback(serverPlayer, result.status());
        if (result.status() != BossEncounterService.StartStatus.SUCCESS) {
            return InteractionResult.FAIL;
        }

        stack.consume(1, serverPlayer);
        serverPlayer.awardStat(Stats.ITEM_USED.get(this));
        var advancement = serverLevel.getServer().getAdvancements().get(CHALLENGE_ADVANCEMENT);
        if (advancement != null) {
            serverPlayer.getAdvancements().award(advancement, "summoned");
        }
        return InteractionResult.SUCCESS_SERVER;
    }

    private static void sendFeedback(
            ServerPlayer player, BossEncounterService.StartStatus status) {
        if (player.connection == null) {
            return;
        }
        String key = switch (status) {
            case SUCCESS -> "message.rovenfall.boss.challenge_started";
            case ENCOUNTER_ACTIVE -> "message.rovenfall.boss.challenge_active";
            case INVALID_REQUEST -> "message.rovenfall.boss.challenge_wrong_world";
            case SPAWN_FAILED -> "message.rovenfall.boss.challenge_spawn_failed";
            default -> "message.rovenfall.boss.challenge_unavailable";
        };
        player.sendOverlayMessage(Component.translatable(key));
    }
}
