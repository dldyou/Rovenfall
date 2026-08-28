package org.dldyou.rovenfall.mixin;

import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.dldyou.rovenfall.administration.PlayerMenuNetwork;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Makes every player GUI click fail closed when its server-issued session state is stale. */
@Mixin(ServerGamePacketListenerImpl.class)
abstract class ServerGamePacketListenerImplMixin {
    @Unique
    private static final long ROVENFALL$RESYNC_COOLDOWN_TICKS = 20L;

    @Shadow
    public ServerPlayer player;
    @Unique
    private long rovenfall$lastPlayerMenuResyncTick = Long.MIN_VALUE;

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
    private void rovenfall$rejectStalePlayerMenuClick(
            ServerboundContainerClickPacket packet, CallbackInfo callback) {
        var menu = player.containerMenu;
        if (!PlayerMenuNetwork.isPlayerMenu(menu)) {
            return;
        }
        if (PlayerMenuNetwork.isCurrentSession(
                menu.containerId, menu.getStateId(), packet.containerId(), packet.stateId())) {
            return;
        }
        long gameTime = player.level().getGameTime();
        if (menu.containerId == packet.containerId()
                && (rovenfall$lastPlayerMenuResyncTick == Long.MIN_VALUE
                || gameTime - rovenfall$lastPlayerMenuResyncTick >= ROVENFALL$RESYNC_COOLDOWN_TICKS)) {
            rovenfall$lastPlayerMenuResyncTick = gameTime;
            menu.sendAllDataToRemote();
        }
        callback.cancel();
    }
}
