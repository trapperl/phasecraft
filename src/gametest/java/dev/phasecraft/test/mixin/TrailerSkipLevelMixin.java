package dev.phasecraft.test.mixin;

import dev.phasecraft.test.client.TrailerState;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * While the trailer is rendered the game's own frames (one a client tick, as the test waits) draw no world: under
 * the trailer's shader pack a frame is costly, and only the frames the trailer draws itself are kept.
 */
@Mixin(GameRenderer.class)
abstract class TrailerSkipLevelMixin {
    @Inject(method = "renderLevel", at = @At("HEAD"), cancellable = true)
    private void phasecraftTest$skipLevel(CallbackInfo ci) {
        if (TrailerState.skipLevel) {
            ci.cancel();
        }
    }
}
