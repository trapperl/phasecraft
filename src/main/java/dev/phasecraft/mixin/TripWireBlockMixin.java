package dev.phasecraft.mixin;

import dev.phasecraft.phased.PhasedWatchers;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.TripWireBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A hidden string whose line ends at a hook on a phase block counts the
 * visible entities at its spot (see PhasedWatchers), by vanilla's rules.
 */
@Mixin(TripWireBlock.class)
abstract class TripWireBlockMixin {
    @Shadow
    private void checkPressed(Level level, BlockPos pos, List<? extends Entity> entities) {
        throw new AssertionError();
    }

    @Inject(method = "checkPressed(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)V", at = @At("HEAD"), cancellable = true)
    private void phasecraft$countVisibleEntities(Level level, BlockPos pos, CallbackInfo ci) {
        if (!(level instanceof ServerLevel hidden)) {
            return;
        }
        ServerLevel visible = PhasedWatchers.phasedStringWatches(hidden, pos);
        if (visible != null) {
            BlockState state = hidden.getBlockState(pos);
            checkPressed(hidden, pos, visible.getEntities((Entity) null, state.getShape(hidden, pos).bounds().move(pos)));
            ci.cancel();
        }
    }
}
