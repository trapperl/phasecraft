package dev.phasecraft.shifter;

import dev.phasecraft.edit.MachineRights;
import dev.phasecraft.layer.HiddenDimensions;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.redstone.Orientation;
import net.minecraft.world.phys.BlockHitResult;
import org.jspecify.annotations.Nullable;

/**
 * The line shifter (the person's idea, 2026-11-25: "'pushing' phase blocks in a row or column would be an intuitive
 * fix"), placed in the hidden layer. It faces a way, as a piston does. When hidden power reaches it, it takes the line
 * of visible blocks in front of it out of the visible world, one block at a time from the far end, and holds them
 * ({@link LineShifterBlockEntity}); when the power goes, it puts them back one at a time from the near end, so the
 * line grows back out of it. Each block is taken or put back as a phase shifter would ({@link Shifting}).
 *
 * <ul>
 *   <li>The line is the one a piston in its place could push: the blocks in front of it up to the first air, or the
 *       first block a piston would break (a torch, a flower, water...), which stays. Twelve at most: with a
 *       thirteenth, or an immovable block (obsidian, bedrock...) in the line, it does nothing, as a piston does.
 *       Unlike a piston, it takes blocks with contents (chests...) with their contents, and it moves only the
 *       straight line (slime and honey are taken like any block, and pull nothing along).</li>
 *   <li>Speed: one block every 1 to 4 redstone ticks (its delay, set by clicking it, as on a repeater). The first
 *       block goes the moment the power arrives.</li>
 *   <li>Turned around mid-move (the power goes while it is taking, or comes while it is putting back), it stops and
 *       goes the other way at the same speed: putting back what it took, nearest first, or taking the line again
 *       from its new far end. The rhythm holds: the next block moves one step after the last one.</li>
 *   <li>Putting back breaks a visible block in a held spot, and lifts a mob or player standing there onto the block,
 *       as a phase shifter does; where it must wait (no room above them), the blocks behind it wait their turn.</li>
 *   <li>Removed, it puts back everything it holds, or drops what cannot go back: never lost, never doubled.</li>
 *   <li>A comparator behind it reads how many blocks it holds (0 to 12), as it reads how full a chest is, so a
 *       build can wait for a line to be all gone, or all back, before the next part moves.</li>
 *   <li>Where claims guard the line, it acts with the rights of whoever placed it ({@link MachineRights}): a block
 *       it may not take is an immovable block to it (nothing moves), and it waits to put back where it may not.</li>
 *   <li>Passes no power on, as a piston does not. Silent in the visible world; placed in the visible layer it does
 *       nothing.</li>
 * </ul>
 */
public class LineShifterBlock extends Block implements EntityBlock {
    public static final EnumProperty<Direction> FACING = BlockStateProperties.FACING;
    public static final BooleanProperty POWERED = BlockStateProperties.POWERED;
    /** TEMPORARY trace for the OPAC line shifter test's rare stall; remove once found. */
    public static String TRACE = "", TRACE_LINE = "", TRACE_STEP = "";
    public static final IntegerProperty DELAY = BlockStateProperties.DELAY;
    private static final int DELAY_MAX = 4;

    public LineShifterBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.UP).setValue(POWERED, false).setValue(DELAY, 1));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, POWERED, DELAY);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getNearestLookingDirection().getOpposite());
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new LineShifterBlockEntity(pos, state);
    }

    @Override
    protected boolean hasAnalogOutputSignal(BlockState state) {
        return true;
    }

    /** How many blocks it holds (a comparator hears of each change: the block entity's setChanged tells it). */
    @Override
    protected int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos, Direction direction) {
        return level.getBlockEntity(pos) instanceof LineShifterBlockEntity entity ? entity.holding() : 0;
    }

    /** Clicking it sets its delay, as on a repeater. */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!player.getAbilities().mayBuild) {
            return InteractionResult.PASS;
        }
        level.setBlockAndUpdate(pos, state.cycle(DELAY));
        return InteractionResult.SUCCESS;
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        if (!oldState.is(this)) {
            update(level, pos, state);
        }
    }

    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block block,
            @Nullable Orientation orientation, boolean movedByPiston) {
        update(level, pos, state);
    }

    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (level.getBlockEntity(pos) instanceof LineShifterBlockEntity entity) {
            step(level, pos, state, entity);
        }
    }

    /** Follows the hidden power: starts taking the line when it arrives, starts putting it back when it goes. */
    private void update(Level level, BlockPos pos, BlockState state) {
        if (!(level instanceof ServerLevel hidden) || !HiddenDimensions.isHidden(hidden.dimension())
                || !(hidden.getBlockEntity(pos) instanceof LineShifterBlockEntity entity)) {
            return;
        }
        boolean powered = hidden.hasNeighborSignal(pos);
        if (powered == state.getValue(POWERED)) {
            return;
        }
        BlockState now = state.setValue(POWERED, powered);
        hidden.setBlock(pos, now, Block.UPDATE_CLIENTS);
        ServerLevel visible = Shifting.visibleOf(hidden);
        entity.setTodo(powered && visible != null
                ? line(visible, pos, now.getValue(FACING), entity, MachineRights.of(hidden, pos)) : 0);
        TRACE = "update: powered=" + powered + " visible=" + (visible != null) + " todo=" + entity.todo() + " nextStep=" + entity.nextStep()
                + " time=" + hidden.getGameTime() + " facing=" + now.getValue(FACING) + " [" + TRACE_LINE + "]";
        step(hidden, pos, now, entity);
        TRACE += " -> holding=" + entity.holding() + " todo=" + entity.todo() + " [" + TRACE_STEP + "]";
    }

    /**
     * How far the line in front reaches (the farthest distance to take), as a piston would see it: up to the first
     * air or fragile block, stopping before a spot it already holds a block for. 0 if there is nothing to take, or if
     * a piston would refuse: more than {@link LineShifterBlockEntity#MAX} blocks counting those it holds, an
     * immovable block, a block a claim does not let it take (see {@link MachineRights}), or a spot not loaded.
     */
    static int line(ServerLevel visible, BlockPos pos, Direction facing, LineShifterBlockEntity entity, MachineRights rights) {
        int count = entity.holding();
        int far = 0;
        TRACE_LINE = "";
        for (int d = 1; d <= LineShifterBlockEntity.MAX + 1; d++) {
            if (d <= LineShifterBlockEntity.MAX && entity.heldAt(d) != null) {
                TRACE_LINE += " d" + d + " held";
                break;
            }
            BlockPos at = pos.relative(facing, d);
            if (!visible.isLoaded(at)) {
                TRACE_LINE += " d" + d + " notLoaded";
                return 0;
            }
            Shifting.Reaction reaction = Shifting.reaction(visible, at, visible.getBlockState(at));
            if (reaction == Shifting.Reaction.NONE || reaction == Shifting.Reaction.FRAGILE) {
                TRACE_LINE += " d" + d + " " + reaction;
                break;
            }
            if (reaction == Shifting.Reaction.IMMOVABLE) {
                TRACE_LINE += " d" + d + " immovable";
                return 0;
            }
            if (!rights.mayBreak(visible, at)) {
                TRACE_LINE += " d" + d + " refused";
                return 0;
            }
            if (++count > LineShifterBlockEntity.MAX) {
                TRACE_LINE += " d" + d + " tooMany";
                return 0;
            }
            far = d;
        }
        return far;
    }

    /** Moves the next block, if it is its time, and books the step after. */
    private void step(ServerLevel hidden, BlockPos pos, BlockState state, LineShifterBlockEntity entity) {
        ServerLevel visible = Shifting.visibleOf(hidden);
        if (visible == null) {
            return;
        }
        boolean taking = state.getValue(POWERED);
        TRACE_STEP = "taking=" + taking + " todo=" + entity.todo() + " next=" + entity.nextStep() + " time=" + hidden.getGameTime();
        if (taking ? entity.todo() == 0 : entity.nearestHeld() == 0) {
            return;
        }
        long time = hidden.getGameTime();
        if (entity.nextStep() > time + 2 * DELAY_MAX) {
            entity.setNextStep(time);                   // game time went back: never wait more than a step
        }
        if (time < entity.nextStep()) {
            hidden.scheduleTick(pos, this, (int) (entity.nextStep() - time));
            return;
        }
        int every = 2 * state.getValue(DELAY);
        Direction facing = state.getValue(FACING);
        if (taking) {
            int d = entity.todo();
            if (entity.heldAt(d) == null) {
                // A block changed since the line was seen (broken, or now immovable) is left where it is.
                Shifting.Held held = Shifting.take(visible, pos.relative(facing, d), MachineRights.of(hidden, pos));
                if (held != null) {
                    entity.setHeld(d, held);
                }
            }
            entity.setTodo(d - 1);
            entity.setNextStep(time + every);
            if (d > 1) {
                hidden.scheduleTick(pos, this, every);
            }
        } else {
            int d = entity.nearestHeld();
            if (Shifting.putBack(visible, pos.relative(facing, d), entity.heldAt(d), MachineRights.of(hidden, pos))) {
                entity.setHeld(d, null);
                entity.setNextStep(time + every);
                if (entity.nearestHeld() != 0) {
                    hidden.scheduleTick(pos, this, every);
                }
            } else {
                hidden.scheduleTick(pos, this, 1);
            }
        }
    }

    /** The line shifter is being removed: what it holds goes back, nearest first, or drops where it cannot. */
    static void release(LineShifterBlockEntity entity, BlockState state) {
        if (!(entity.getLevel() instanceof ServerLevel hidden) || !state.hasProperty(FACING)) {
            return;
        }
        ServerLevel visible = Shifting.visibleOf(hidden);
        MachineRights rights = MachineRights.of(hidden, entity.getBlockPos());
        for (int d = 1; d <= LineShifterBlockEntity.MAX; d++) {
            Shifting.Held held = entity.heldAt(d);
            if (held != null) {
                entity.setHeld(d, null);
                if (visible != null) {
                    Shifting.putBackOrDrop(visible, entity.getBlockPos().relative(state.getValue(FACING), d), held, rights);
                }
            }
        }
        entity.setTodo(0);
    }
}
