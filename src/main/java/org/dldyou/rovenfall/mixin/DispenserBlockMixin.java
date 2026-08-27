package org.dldyou.rovenfall.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.dldyou.rovenfall.administration.ClaimProtectionHooks;
import org.dldyou.rovenfall.administration.ClaimProtectionService;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(DispenserBlock.class)
abstract class DispenserBlockMixin {
    @Inject(method = "dispenseFrom", at = @At("HEAD"), cancellable = true)
    private void rovenfall$protectDispenseTarget(
            ServerLevel level,
            BlockState state,
            BlockPos source,
            CallbackInfo callback) {
        Direction facing = state.getValue(DispenserBlock.FACING);
        BlockPos target = source.relative(facing);
        if (!ClaimProtectionHooks.environmentMayModify(level, source, target)) {
            ClaimProtectionHooks.auditEnvironmentDenied(
                    level, target, ClaimProtectionService.Action.BUILD);
            callback.cancel();
        }
    }
}
