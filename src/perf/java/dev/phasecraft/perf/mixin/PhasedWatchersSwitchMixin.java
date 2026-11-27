package dev.phasecraft.perf.mixin;

import dev.phasecraft.perf.Switches;
import dev.phasecraft.phased.PhasedWatchers;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** MEASUREMENT ONLY: see {@link Switches}. */
@Mixin(PhasedWatchers.class)
abstract class PhasedWatchersSwitchMixin {
    @Inject(method = "onBlockChanged", at = @At("HEAD"), cancellable = true)
    private static void perf$hook(ServerLevel level, BlockPos pos, CallbackInfo ci) {
        if (!Switches.watchersHook) {
            ci.cancel();
        }
    }
}
