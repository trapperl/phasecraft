package dev.phasecraft.phased;

import dev.phasecraft.PhaseCraftBlocks;
import dev.phasecraft.layer.HiddenDimensions;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * Which hidden blocks are phased: those standing on a phase block, or hanging
 * on its side. A phased block reacts to the visible world at its own spot.
 * Supported so far: buttons and levers (right-click the visible block at
 * their spot, {@link PhasedInput}) and pressure plates (visible entities on
 * their spot press them). Other phased blocks do nothing different yet.
 */
public final class Phased {
    private Phased() {}

    /** The block a block rests on: the one a button or lever is attached to, otherwise the one below. */
    public static BlockPos supportOf(BlockPos pos, BlockState state) {
        if (state.getBlock() instanceof FaceAttachedHorizontalDirectionalBlock) {
            Direction towardsSupport = switch (state.getValue(BlockStateProperties.ATTACH_FACE)) {
                case FLOOR -> Direction.DOWN;
                case CEILING -> Direction.UP;
                case WALL -> state.getValue(BlockStateProperties.HORIZONTAL_FACING).getOpposite();
            };
            return pos.relative(towardsSupport);
        }
        return pos.below();
    }

    /** Whether the block at pos, in a level that is a hidden layer, is phased. */
    public static boolean isPhased(Level level, BlockPos pos, BlockState state) {
        return level instanceof ServerLevel serverLevel && HiddenDimensions.isHidden(serverLevel.dimension())
                && isOnPhaseBlock(level, pos, state);
    }

    static boolean isOnPhaseBlock(BlockGetter level, BlockPos pos, BlockState state) {
        return !state.isAir() && level.getBlockState(supportOf(pos, state)).is(PhaseCraftBlocks.PHASE_BLOCK);
    }
}
