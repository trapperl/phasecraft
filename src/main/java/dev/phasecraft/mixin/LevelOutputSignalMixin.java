package dev.phasecraft.mixin;

import dev.phasecraft.phased.PhasedWatchers;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** What a visible container holds changed: phased comparators reading it look again (see PhasedWatchers). */
@Mixin(Level.class)
abstract class LevelOutputSignalMixin {
    @Inject(method = "updateNeighbourForOutputSignal", at = @At("HEAD"))
    private void phasecraft$tellPhasedComparators(BlockPos pos, Block changedBlock, CallbackInfo ci) {
        if ((Object) this instanceof ServerLevel level) {
            PhasedWatchers.onOutputSignal(level, pos);
        }
    }
}
