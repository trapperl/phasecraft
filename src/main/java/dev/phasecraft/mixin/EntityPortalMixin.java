package dev.phasecraft.mixin;

import dev.phasecraft.layer.HiddenLayerGuard;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Portal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Portals in the hidden layer do nothing: they would lead into the real
 * Nether or End and build a visible portal there. Every portal block (Nether
 * portal, End portal, End gateway) goes through setAsInsidePortal, which no
 * entity overrides (canUsePortal is overridden, e.g. by thrown projectiles).
 */
@Mixin(Entity.class)
abstract class EntityPortalMixin {
    @Inject(method = "setAsInsidePortal", at = @At("HEAD"), cancellable = true)
    private void phasecraft$noPortalsInHiddenLayer(Portal portal, BlockPos pos, CallbackInfo ci) {
        if (HiddenLayerGuard.inHiddenLayer((Entity) (Object) this)) {
            ci.cancel();
        }
    }
}
