package dev.phasecraft.perf.mixin;

import dev.phasecraft.link.PhaseLink;
import dev.phasecraft.perf.Switches;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** MEASUREMENT ONLY: see {@link Switches}. */
@Mixin(PhaseLink.class)
abstract class PhaseLinkSwitchMixin {
    @Inject(method = "onBlockChanged", at = @At("HEAD"), cancellable = true)
    private static void perf$hook(ServerLevel level, BlockPos pos, BlockState oldState, BlockState newState, CallbackInfo ci) {
        if (!Switches.linkHook) {
            ci.cancel();
        }
    }

    @Inject(method = "upAt", at = @At("HEAD"), cancellable = true)
    private static void perf$bridge(ServerLevel visible, BlockPos pos, CallbackInfoReturnable<Integer> cir) {
        if (!Switches.bridge) {
            cir.setReturnValue(0);
        }
    }

    @Inject(method = "followTicking", at = @At("HEAD"), cancellable = true)
    private static void perf$follow(ServerLevel level, LevelChunk chunk, FullChunkStatus oldStatus, FullChunkStatus newStatus,
            CallbackInfo ci) {
        if (!Switches.followTicking) {
            ci.cancel();
        }
    }
}
