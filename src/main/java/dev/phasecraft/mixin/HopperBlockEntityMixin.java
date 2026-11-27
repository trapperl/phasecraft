package dev.phasecraft.mixin;

import dev.phasecraft.phased.PhasedItems;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.Hopper;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A phased hopper pulls from the visible world above it: vanilla's pull, run against the visible level, where claims
 * let it (see PhasedItems).
 */
@Mixin(HopperBlockEntity.class)
abstract class HopperBlockEntityMixin {
    @Inject(method = "suckInItems", at = @At("HEAD"), cancellable = true)
    private static void phasecraft$pullFromVisible(Level level, Hopper hopper, CallbackInfoReturnable<Boolean> cir) {
        if (hopper instanceof HopperBlockEntity entity) {
            ServerLevel visible = PhasedItems.visibleFor(level, entity.getBlockPos(), entity.getBlockState());
            if (visible != null) {
                cir.setReturnValue(PhasedItems.mayPull((ServerLevel) level, entity.getBlockPos(), visible)
                        && HopperBlockEntity.suckInItems(visible, hopper));
            }
        }
    }
}
