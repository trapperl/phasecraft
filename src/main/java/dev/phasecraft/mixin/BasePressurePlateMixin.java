package dev.phasecraft.mixin;

import dev.phasecraft.layer.HiddenDimensions;
import dev.phasecraft.layer.HiddenLayer;
import dev.phasecraft.phased.Phased;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BasePressurePlateBlock;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A phased pressure plate counts the entities on its spot in the visible
 * world, by vanilla's own rules (every plate's signal strength goes through
 * getEntityCount, with the plate's touch box moved to its position).
 */
@Mixin(BasePressurePlateBlock.class)
abstract class BasePressurePlateMixin {
    @Inject(method = "getEntityCount", at = @At("HEAD"), cancellable = true)
    private static void phasecraft$countVisibleEntities(Level level, AABB box, Class<? extends Entity> type,
            CallbackInfoReturnable<Integer> cir) {
        if (!(level instanceof ServerLevel hidden) || !HiddenDimensions.isHidden(hidden.dimension())) {
            return;
        }
        BlockPos pos = BlockPos.containing(box.minX, box.minY, box.minZ);
        if (Phased.isPhased(hidden, pos, hidden.getBlockState(pos))) {
            cir.setReturnValue(BasePressurePlateAccessor.phasecraft$getEntityCount(HiddenLayer.of(hidden).visibleLevel(), box, type));
        }
    }
}
