package org.dldyou.rovenfall.mixin;

import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.dldyou.rovenfall.administration.PlayerRpgMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Makes RPG GUI clicks fail closed when their server-issued session state is stale. */
@Mixin(ServerGamePacketListenerImpl.class)
abstract class ServerGamePacketListenerImplMixin {
    @Unique
    private static final long ROVENFALL$RESYNC_COOLDOWN_TICKS = 20L;

    @Shadow
    public ServerPlayer player;
    @Unique
    private long rovenfall$lastRpgMenuResyncTick = Long.MIN_VALUE;

    @Inject(
            method = "handleContainerClick",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread("
                            + "Lnet/minecraft/network/protocol/Packet;"
                            + "Lnet/minecraft/network/PacketListener;"
                            + "Lnet/minecraft/server/level/ServerLevel;)V",
                    shift = At.Shift.AFTER),
            cancellable = true)
    private void rovenfall$rejectStaleRpgMenuClick(
            ServerboundContainerClickPacket packet, CallbackInfo callback) {
        if (!(player.containerMenu instanceof PlayerRpgMenu menu)) {
            return;
        }
        if (PlayerRpgMenu.isCurrentSession(
                menu.containerId, menu.getStateId(), packet.containerId(), packet.stateId())) {
            return;
        }
        long gameTime = player.level().getGameTime();
        if (menu.containerId == packet.containerId()
                && (rovenfall$lastRpgMenuResyncTick == Long.MIN_VALUE
                || gameTime - rovenfall$lastRpgMenuResyncTick >= ROVENFALL$RESYNC_COOLDOWN_TICKS)) {
            rovenfall$lastRpgMenuResyncTick = gameTime;
            menu.sendAllDataToRemote();
        }
        callback.cancel();
    }
}
