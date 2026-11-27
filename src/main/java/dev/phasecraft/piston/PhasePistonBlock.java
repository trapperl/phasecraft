package dev.phasecraft.piston;

import dev.phasecraft.edit.MachineRights;
import dev.phasecraft.layer.HiddenDimensions;
import dev.phasecraft.layer.HiddenLayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.SignalGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.level.redstone.Orientation;
import org.jspecify.annotations.Nullable;

/**
 * Phase piston: placed in the hidden layer, it moves <em>visible</em> blocks.
 *
 * <p>It is powered like a vanilla piston, by redstone in its own (hidden)
 * layer, including vanilla's quasi-connectivity. When it extends it pushes
 * the visible blocks in front of its position by vanilla rules (limit 12,
 * obsidian and other immovable blocks stop it, slime and honey drag
 * neighbours). It has no arm in either layer: where a vanilla piston's head
 * would be, the visible space is left empty. A sticky phase piston pulls the
 * visible block back when it retracts. Placed in the visible layer, it does
 * nothing.</p>
 *
 * <p>Where claims guard the visible world it acts with the rights of whoever
 * placed it ({@link MachineRights}): a block it may not take from its spot,
 * or put into the next, is an immovable block to it: it does not extend, and
 * a sticky one pulls nothing back.</p>
 */
public class PhasePistonBlock extends DirectionalBlock {
    public static final BooleanProperty EXTENDED = BlockStateProperties.EXTENDED;
    private static final int EXTEND = 0;
    private static final int RETRACT = 1;

    private final boolean sticky;

    public PhasePistonBlock(boolean sticky, BlockBehaviour.Properties properties) {
        super(properties);
        this.sticky = sticky;
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(EXTENDED, false));
    }

    public boolean isSticky() {
        return sticky;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, EXTENDED);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getNearestLookingDirection().getOpposite());
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity by, ItemStack itemStack) {
        checkIfExtend(level, pos, state);
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        if (!oldState.is(state.getBlock())) {
            checkIfExtend(level, pos, state);
        }
    }

    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block block,
            @Nullable Orientation orientation, boolean movedByPiston) {
        checkIfExtend(level, pos, state);
    }

    /** The visible level this piston moves blocks in, or null if it is not in a hidden layer. */
    private static @Nullable ServerLevel visibleLevel(Level level) {
        if (!(level instanceof ServerLevel serverLevel) || !HiddenDimensions.isHidden(serverLevel.dimension())) {
            return null;
        }
        return HiddenLayer.of(serverLevel).visibleLevel();
    }

    /** What this piston may change where claims guard the visible world: what whoever placed it may. */
    private static MachineRights rights(Level hidden, BlockPos pos) {
        return MachineRights.of((ServerLevel) hidden, pos);
    }

    private void checkIfExtend(Level level, BlockPos pos, BlockState state) {
        ServerLevel visible = visibleLevel(level);
        if (visible == null) {
            return;
        }
        Direction direction = state.getValue(FACING);
        boolean powered = hasNeighborSignal(level, pos, direction);
        if (powered && !state.getValue(EXTENDED)) {
            if (visible.isLoaded(pos.relative(direction)) && PhasePistonMover.canMove(visible, pos, direction, true, rights(level, pos))) {
                level.blockEvent(pos, this, EXTEND, direction.get3DDataValue());
            }
        } else if (!powered && state.getValue(EXTENDED)) {
            level.blockEvent(pos, this, RETRACT, direction.get3DDataValue());
        }
    }

    /** Vanilla's piston power rule (PistonBaseBlock.getNeighborSignal), including quasi-connectivity. */
    private static boolean hasNeighborSignal(SignalGetter level, BlockPos pos, Direction pushDirection) {
        for (Direction direction : Direction.values()) {
            if (direction != pushDirection && level.hasSignal(pos.relative(direction), direction)) {
                return true;
            }
        }
        if (level.hasSignal(pos, Direction.DOWN)) {
            return true;
        }
        BlockPos above = pos.above();
        for (Direction direction : Direction.values()) {
            if (direction != Direction.DOWN && level.hasSignal(above.relative(direction), direction)) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected boolean triggerEvent(BlockState state, Level level, BlockPos pos, int event, int data) {
        ServerLevel visible = visibleLevel(level);
        if (visible == null) {
            return false;
        }
        Direction direction = state.getValue(FACING);
        boolean powered = hasNeighborSignal(level, pos, direction);
        if (event == EXTEND) {
            if (!powered || !PhasePistonMover.moveBlocks(visible, pos, direction, true, rights(level, pos))) {
                return false;
            }
            level.setBlock(pos, state.setValue(EXTENDED, true), Block.UPDATE_ALL);
            // Heard in the hidden layer only: hidden blocks make no sound in the visible world.
            level.playSound(null, pos, SoundEvents.PISTON_EXTEND, SoundSource.BLOCKS, 0.5F,
                    level.getRandom().nextFloat() * 0.25F + 0.6F);
            return true;
        }
        if (powered) {
            return false;
        }
        if (sticky) {
            pullBack(visible, pos, direction, rights(level, pos));
        }
        level.setBlock(pos, state.setValue(EXTENDED, false), Block.UPDATE_ALL);
        level.playSound(null, pos, SoundEvents.PISTON_CONTRACT, SoundSource.BLOCKS, 0.5F,
                level.getRandom().nextFloat() * 0.15F + 0.6F);
        return true;
    }

    /** Vanilla's sticky retraction rule, on the visible level: pull the block two in front back by one. */
    private static void pullBack(ServerLevel visible, BlockPos pos, Direction direction, MachineRights rights) {
        BlockPos twoAhead = pos.relative(direction, 2);
        BlockState there = visible.getBlockState(twoAhead);
        if (there.is(Blocks.MOVING_PISTON)
                && visible.getBlockEntity(twoAhead) instanceof PistonMovingBlockEntity moving
                && moving.getDirection() == direction
                && moving.isExtending()) {
            // Still sliding out from this push: let it land there (vanilla's short-pulse behaviour).
            moving.finalTick();
            return;
        }
        if (!there.isAir()
                && PistonBaseBlock.isPushable(there, visible, twoAhead, direction.getOpposite(), false, direction)
                && (there.getPistonPushReaction() == PushReaction.PUSH_PULL || there.is(Blocks.PISTON) || there.is(Blocks.STICKY_PISTON))) {
            PhasePistonMover.moveBlocks(visible, pos, direction, false, rights);
        }
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }
}
