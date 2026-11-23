package dev.phasecraft.mixin;

import dev.phasecraft.link.PhaseLink;
import dev.phasecraft.overlay.OverlaySync;
import dev.phasecraft.phased.PhasedInput;
import dev.phasecraft.phased.PhasedLights;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tells the cross-layer link and the overlay sync about every block change on the server.
 *
 * <p>Level.setBlock calls updatePOIOnBlockStateChange after every successful
 * change. The hook must be at HEAD: the method returns early for blocks that
 * are not points of interest, i.e. almost all of them.</p>
 */
@Mixin(ServerLevel.class)
abstract class ServerLevelPhaseLinkMixin {
    @Inject(method = "updatePOIOnBlockStateChange", at = @At("HEAD"))
    private void phasecraft$notifyOtherLayer(BlockPos pos, BlockState oldState, BlockState newState, CallbackInfo ci) {
        ServerLevel level = (ServerLevel) (Object) this;
        PhaseLink.onBlockChanged(level, pos, oldState, newState);
        OverlaySync.onBlockChanged(level, pos, newState);
        PhasedInput.onBlockChanged(level, pos, oldState, newState);
        PhasedLights.onBlockChanged(level, pos, oldState, newState);
    }
}
