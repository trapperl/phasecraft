package dev.phasecraft.link;

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
 * Phase port: the only place power crosses between layers.
 *
 * <p>A port gives out, in its own layer and to every side like a redstone
 * block, the power that reaches its position in the other layer: from a
 * signal source standing there, or from that position's neighbours there.
 * Other ports are ignored, so ports cannot pass power back and forth or hold
 * each other on. To send power down into the hidden layer, place the port in
 * the hidden layer; to send it up, place it in the visible layer.</p>
 */
public class PhasePortBlock extends Block {
    public static final IntegerProperty POWER = BlockStateProperties.POWER;

    public PhasePortBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(POWER, 0));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(POWER);
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

    /** Re-reads the other layer and updates this port's power; neighbours here react by vanilla rules. */
    static void refresh(Level level, BlockPos pos, BlockState state) {
        if (!(level instanceof ServerLevel serverLevel) || !(state.getBlock() instanceof PhasePortBlock)) {
            return;
        }
        int input = PhaseLink.portInput(serverLevel, pos);
        if (input != state.getValue(POWER)) {
            level.setBlock(pos, state.setValue(POWER, input), Block.UPDATE_ALL);
        }
    }
}
