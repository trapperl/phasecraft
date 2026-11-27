package dev.phasecraft.perf.mixin;

import dev.phasecraft.layer.HiddenDimensions;
import dev.phasecraft.perf.Switches;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** MEASUREMENT ONLY: counts block changes where PhaseCraft's own hook hears them (ServerLevelPhaseLinkMixin). */
@Mixin(ServerLevel.class)
abstract class BlockChangeCounterMixin {
    @Inject(method = "updatePOIOnBlockStateChange", at = @At("HEAD"))
    private void perf$count(BlockPos pos, BlockState oldState, BlockState newState, CallbackInfo ci) {
        if (HiddenDimensions.isHidden(((ServerLevel) (Object) this).dimension())) {
            Switches.hiddenChanges++;
        } else {
            Switches.visibleChanges++;
        }
    }
}
