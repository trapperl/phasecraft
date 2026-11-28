package dev.phasecraft.test.mixin;

import dev.phasecraft.test.client.TrailerState;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The trailer's camera has a field of view of its own for every frame (a zoom, a dolly zoom): the option is a whole
 * number of degrees, which steps. Nothing changes unless the trailer is being rendered ({@link TrailerState#fov}).
 */
@Mixin(Camera.class)
abstract class TrailerFovMixin {
    @Inject(method = "calculateFov", at = @At("RETURN"), cancellable = true)
    private void phasecraftTest$trailerFov(float partialTicks, CallbackInfoReturnable<Float> cir) {
        if (TrailerState.fov > 0) {
            cir.setReturnValue(TrailerState.fov);
        }
    }
}
