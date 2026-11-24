package dev.phasecraft.mixin;

import dev.phasecraft.phased.PhasedWatchers;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ComparatorBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A phased comparator also reads the visible block behind it (see PhasedWatchers); the higher reading wins. */
@Mixin(ComparatorBlock.class)
abstract class ComparatorBlockMixin {
    @Inject(method = "getInputSignal", at = @At("RETURN"), cancellable = true)
    private void phasecraft$readVisible(Level level, BlockPos pos, BlockState state, CallbackInfoReturnable<Integer> cir) {
        if (level instanceof ServerLevel serverLevel) {
            int visible = PhasedWatchers.comparatorReading(serverLevel, pos, state);
            if (visible > cir.getReturnValueI()) {
                cir.setReturnValue(visible);
            }
        }
    }
}
