package dev.phasecraft.link;

import dev.phasecraft.layer.HiddenDimensions;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.redstone.Orientation;
import org.jspecify.annotations.Nullable;

/**
 * Phase port: the only place power crosses between layers. It belongs in the
 * hidden layer, where it works both ways at its spot, with nothing showing in
 * the visible world (the person, 2026-11-23: no block from the hidden layer
 * may show there).
 *
 * <ul>
 *   <li><b>Down</b> ({@link #POWER}): the power reaching its position in the
 *       other layer (a signal source standing there, or that position's
 *       neighbours there), given out in its own layer to every side like a
 *       redstone block.</li>
 *   <li><b>Up</b> ({@link #UP}, hidden ports only): the hidden power reaching
 *       the port, given out in the visible world at its spot, like an
 *       invisible redstone block standing there.</li>
 * </ul>
 *
 * <p>Other ports are ignored both ways, and reading the visible world for
 * down ignores what ports put up, so ports cannot pass power back and forth
 * or hold themselves or each other on. A port placed in the visible layer
 * still works as before (it gives out hidden power there), but shows.</p>
 */
public class PhasePortBlock extends Block {
    public static final IntegerProperty POWER = BlockStateProperties.POWER;
    /** Hidden power reaching a hidden port, given out in the visible world at its spot. */
    public static final IntegerProperty UP = IntegerProperty.create("up", 0, 15);

    public PhasePortBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(POWER, 0).setValue(UP, 0));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(POWER, UP);
    }

    @Override
    protected boolean isSignalSource(BlockState state) {
        return true;
    }

    @Override
    protected int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        return state.getValue(POWER);
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        refresh(level, pos, state);
    }

    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block block,
            @Nullable Orientation orientation, boolean movedByPiston) {
        refresh(level, pos, state);
    }

    @Override
    protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos pos, boolean movedByPiston) {
        if (state.getValue(UP) > 0) {
            PhaseLink.queueVisibleUpdate(level, pos);
        }
    }

    /**
     * Re-reads both sides and updates this port: down from the other layer (neighbours here react
     * by vanilla rules), and, in a hidden layer, up from the hidden power reaching it (the visible
     * neighbours of its spot hear about it at the end of the tick).
     */
    static void refresh(Level level, BlockPos pos, BlockState state) {
        if (!(level instanceof ServerLevel serverLevel) || !(state.getBlock() instanceof PhasePortBlock)) {
            return;
        }
        int down = PhaseLink.portInput(serverLevel, pos);
        int up = HiddenDimensions.isHidden(serverLevel.dimension()) ? hiddenPowerReaching(serverLevel, pos) : 0;
        if (down != state.getValue(POWER) || up != state.getValue(UP)) {
            boolean upChanged = up != state.getValue(UP);
            level.setBlock(pos, state.setValue(POWER, down).setValue(UP, up), Block.UPDATE_ALL);
            if (upChanged) {
                PhaseLink.queueVisibleUpdate(serverLevel, pos);
            }
        }
    }

    /** The power hidden neighbours give into this position, ignoring other ports. */
    private static int hiddenPowerReaching(ServerLevel hidden, BlockPos pos) {
        int best = 0;
        for (Direction direction : Direction.values()) {
            BlockPos neighbour = pos.relative(direction);
            if (!(hidden.getBlockState(neighbour).getBlock() instanceof PhasePortBlock)) {
                best = Math.max(best, hidden.getSignal(neighbour, direction));
            }
        }
        return Math.min(best, 15);
    }
}
