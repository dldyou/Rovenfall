package org.dldyou.rovenfall.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FireBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.dldyou.rovenfall.administration.ClaimProtectionHooks;
import org.dldyou.rovenfall.administration.ClaimProtectionService;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FireBlock.class)
abstract class FireBlockMixin {
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void rovenfall$protectFireTick(
            BlockState state,
            ServerLevel level,
            BlockPos position,
            RandomSource random,
            CallbackInfo callback) {
        if (!ClaimProtectionHooks.systemMayModify(level, position)) {
            ClaimProtectionHooks.auditEnvironmentDenied(
                    level, position, ClaimProtectionService.Action.BUILD);
            callback.cancel();
        }
    }

    @Inject(method = "checkBurnOut", at = @At("HEAD"), cancellable = true)
    private void rovenfall$protectBurnOut(
            Level level,
            BlockPos target,
            int chance,
            RandomSource random,
            int age,
            Direction face,
            CallbackInfo callback) {
        if (level instanceof ServerLevel serverLevel
                && !ClaimProtectionHooks.systemMayModify(serverLevel, target)) {
            ClaimProtectionHooks.auditEnvironmentDenied(
                    serverLevel, target, ClaimProtectionService.Action.BUILD);
            callback.cancel();
        }
    }

    @Redirect(
            method = "tick",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerLevel;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z"))
    private boolean rovenfall$protectFirePlacement(
            ServerLevel level,
            BlockPos target,
            BlockState state,
            int flags) {
        if (!ClaimProtectionHooks.systemMayModify(level, target)) {
            ClaimProtectionHooks.auditEnvironmentDenied(
                    level, target, ClaimProtectionService.Action.BUILD);
            return false;
        }
        return level.setBlock(target, state, flags);
    }
}
