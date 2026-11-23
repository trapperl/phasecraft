package dev.phasecraft.mixin;

import dev.phasecraft.phased.PhasedLights;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.BlockLightEngine;
import net.minecraft.world.level.lighting.LightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Phased light in vanilla's block light engine: a position also emits the
 * light of a phased hidden block at the same spot (the brighter of the two),
 * and lighting a chunk from scratch also starts from its phased lights.
 */
@Mixin(BlockLightEngine.class)
abstract class BlockLightEnginePhasedMixin {
    private Level phasecraft$level() {
        BlockGetter level = ((LightEngineAccessor) (Object) this).phasecraft$chunkSource().getLevel();
        return level instanceof Level found ? found : null;
    }

    @Inject(method = "getEmission", at = @At("RETURN"), cancellable = true)
    private void phasecraft$addPhasedLight(long pos, BlockState state, CallbackInfoReturnable<Integer> cir) {
        int own = cir.getReturnValueI();
        if (own >= 15) {
            return;
        }
        Level level = phasecraft$level();
        if (level != null) {
            int phased = PhasedLights.emission(level, pos);
            if (phased > own) {
                cir.setReturnValue(phased);
            }
        }
    }

    @Inject(method = "propagateLightSources", at = @At("TAIL"))
    private void phasecraft$phasedLightSources(ChunkPos chunk, CallbackInfo ci) {
        Level level = phasecraft$level();
        if (level != null) {
            PhasedLights.forEachIn(level, chunk, pos -> ((LightEngine<?, ?>) (Object) this).checkBlock(BlockPos.of(pos)));
        }
    }
}
