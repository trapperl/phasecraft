package dev.phasecraft.test.mixin;

import dev.phasecraft.test.client.TrailerState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A shader pack moves its water, leaves and clouds by Iris's clock, which is the real one: a trailer frame that
 * takes a second to draw would be a second on in the video. While the trailer is rendered the clock is the
 * video's ({@link TrailerState#clock}, seconds), so the pack's motion runs at the video's speed whatever a frame
 * costs, and a shot rendered twice (with and without the hidden layer) gets the same water and clouds both times.
 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.uniforms.SystemTimeUniforms$Timer", remap = false)
abstract class TrailerIrisClockMixin {
    @Shadow(remap = false) private float frameTimeCounter;
    @Shadow(remap = false) private float lastFrameTime;

    @Inject(method = "beginFrame", at = @At("TAIL"), remap = false)
    private void phasecraftTest$trailerClock(long frameStartTime, CallbackInfo ci) {
        if (TrailerState.clock >= 0) {
            this.frameTimeCounter = TrailerState.clock;
            this.lastFrameTime = TrailerState.frameSeconds;
        }
    }
}
