package org.dldyou.rovenfall.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import org.dldyou.rovenfall.administration.ClaimProtectionHooks;
import org.dldyou.rovenfall.administration.ClaimProtectionService;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FlowingFluid.class)
abstract class FlowingFluidMixin {
    @Inject(method = "spreadTo", at = @At("HEAD"), cancellable = true)
    private void rovenfall$protectFluidSpread(
            LevelAccessor level,
            BlockPos target,
            BlockState targetState,
            Direction direction,
            FluidState fluidState,
            CallbackInfo callback) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        BlockPos source = target.relative(direction.getOpposite());
        if (!ClaimProtectionHooks.environmentMayModify(serverLevel, source, target)) {
            ClaimProtectionHooks.auditEnvironmentDenied(
                    serverLevel, target, ClaimProtectionService.Action.BUILD);
            callback.cancel();
        }
    }
}
