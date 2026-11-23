package dev.phasecraft.client.mixin;

import dev.phasecraft.client.ClientEditing;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Editing mode: while it is on, the crosshair picks in the hidden layer and
 * left, right and middle clicks are sent there instead of to the visible world.
 */
@Mixin(Minecraft.class)
abstract class MinecraftEditingMixin {
    @Shadow
    private int rightClickDelay;

    @Inject(method = "pick", at = @At("TAIL"))
    private void phasecraft$pickHidden(float partialTicks, CallbackInfo ci) {
        ClientEditing.afterPick((Minecraft) (Object) this, partialTicks);
    }

    @Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
    private void phasecraft$breakHidden(CallbackInfoReturnable<Boolean> cir) {
        if (ClientEditing.active((Minecraft) (Object) this)) {
            ClientEditing.attack((Minecraft) (Object) this);
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
    private void phasecraft$noVisibleBreaking(boolean down, CallbackInfo ci) {
        if (ClientEditing.active((Minecraft) (Object) this)) {
            ClientEditing.continueAttack((Minecraft) (Object) this, down);
            ci.cancel();
        }
    }

    @Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
    private void phasecraft$useHidden(CallbackInfo ci) {
        if (ClientEditing.active((Minecraft) (Object) this)) {
            rightClickDelay = 4; // vanilla's repeat rate while the button is held
            ClientEditing.use((Minecraft) (Object) this);
            ci.cancel();
        }
    }

    @Inject(method = "pickBlockOrEntity", at = @At("HEAD"), cancellable = true)
    private void phasecraft$pickHidden(CallbackInfo ci) {
        Minecraft minecraft = (Minecraft) (Object) this;
        if (ClientEditing.active(minecraft)) {
            ClientEditing.pick(minecraft, minecraft.hasControlDown());
            ci.cancel();
        }
    }
}
