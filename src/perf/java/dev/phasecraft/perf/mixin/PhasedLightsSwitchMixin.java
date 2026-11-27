package dev.phasecraft.perf.mixin;

import dev.phasecraft.perf.Switches;
import dev.phasecraft.phased.PhasedLights;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** MEASUREMENT ONLY: see {@link Switches}. */
@Mixin(PhasedLights.class)
abstract class PhasedLightsSwitchMixin {
    @Inject(method = "onBlockChanged", at = @At("HEAD"), cancellable = true)
    private static void perf$hook(ServerLevel level, BlockPos pos, BlockState oldState, BlockState newState, CallbackInfo ci) {
        if (!Switches.lightsHook) {
            ci.cancel();
        }
    }
}
