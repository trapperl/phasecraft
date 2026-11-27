package dev.phasecraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.phasecraft.edit.MachineOwners;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A hidden machine moved by a piston in the hidden layer (a phase piston on a flying machine's engine) keeps its
 * owner (see MachineOwners): each block a piston moves starts moving here.
 */
@Mixin(PistonBaseBlock.class)
abstract class PistonBaseBlockMixin {
    @WrapOperation(method = "moveBlocks", at = @At(value = "INVOKE", ordinal = 0,
            target = "Lnet/minecraft/world/level/block/piston/MovingPistonBlock;newMovingBlockEntity(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/Direction;ZZ)Lnet/minecraft/world/level/block/entity/BlockEntity;"))
    private BlockEntity phasecraft$ownerMovesAlong(BlockPos to, BlockState movingState, BlockState moved, Direction direction,
            boolean extending, boolean isSourcePiston, Operation<BlockEntity> original, Level level) {
        MachineOwners.onMoved(level, to.relative(extending ? direction.getOpposite() : direction), to, moved);
        return original.call(to, movingState, moved, direction, extending, isSourcePiston);
    }
}
