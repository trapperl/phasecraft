package dev.phasecraft.shifter;

import dev.phasecraft.layer.HiddenDimensions;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.redstone.Orientation;
import org.jspecify.annotations.Nullable;

/**
 * The phase shifter (the person's idea, 2026-11-23), placed in the hidden layer.
 * When hidden power reaches it, it takes the visible block at its own spot out
 * of the visible world and holds it ({@link PhaseShifterBlockEntity}); when the
 * power goes, it puts that block back, exactly as it was.
 *
 * <ul>
 *   <li>It takes what a piston could push without breaking, and also blocks
 *       with contents (chests, furnaces, barrels...: what is in them, and their
 *       name, go and come back with them; nothing spills). Not air, fluids,
 *       fragile blocks (torches, flowers...) or immovable blocks (obsidian,
 *       bedrock...). Pistons refuse blocks with contents only because they
 *       would carry them somewhere else; the shifter always puts a block back
 *       at its own spot.</li>
 *   <li>It takes only when the power arrives, so a block placed in the gap
 *       while it is powered stays.</li>
 *   <li>Putting back waits, retrying every tick, while the spot is taken by a
 *       block or by a mob or player standing there.</li>
 *   <li>Removed while holding a block, it puts the block back if the spot is
 *       free, or drops it there as an item, with its contents: never lost,
 *       never doubled.</li>
 *   <li>Put back, the block takes the shape its neighbours give it now, as a
 *       placed block does: half of a double chest joins its other half again,
 *       or stands alone if that half is gone.</li>
 *   <li>Silent in the visible world, and nothing shows: the block is held,
 *       not placed in the hidden layer. Placed in the visible layer it does
 *       nothing.</li>
 * </ul>
 */
public class PhaseShifterBlock extends Block implements EntityBlock {
    public static final BooleanProperty POWERED = BlockStateProperties.POWERED;

    public PhaseShifterBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(POWERED, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(POWERED);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new PhaseShifterBlockEntity(pos, state);
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        update(level, pos, state);
    }

    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block block,
            @Nullable Orientation orientation, boolean movedByPiston) {
        update(level, pos, state);
    }

    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (!state.getValue(POWERED) && level.getBlockEntity(pos) instanceof PhaseShifterBlockEntity entity
                && entity.holding() != null && !putBack(level, pos, entity)) {
            level.scheduleTick(pos, this, 1);
        }
    }

    /** Follows the hidden power: takes the visible block when it arrives, puts it back when it goes. */
    private void update(Level level, BlockPos pos, BlockState state) {
        if (!(level instanceof ServerLevel hidden) || !HiddenDimensions.isHidden(hidden.dimension())
                || !(hidden.getBlockEntity(pos) instanceof PhaseShifterBlockEntity entity)) {
            return;
        }
        boolean powered = hidden.hasNeighborSignal(pos);
        if (powered == state.getValue(POWERED)) {
            return;
        }
        hidden.setBlock(pos, state.setValue(POWERED, powered), Block.UPDATE_CLIENTS);
        if (powered) {
            take(hidden, pos, entity);
        } else if (entity.holding() != null && !putBack(hidden, pos, entity)) {
            hidden.scheduleTick(pos, this, 1);
        }
    }

    private static void take(ServerLevel hidden, BlockPos pos, PhaseShifterBlockEntity entity) {
        ServerLevel visible = Shifting.visibleOf(hidden);
        if (visible == null || entity.holding() != null) {
            return;
        }
        Shifting.Held held = Shifting.take(visible, pos);
        if (held != null) {
            entity.setHeld(held);
        }
    }

    /** Puts the held block back (see {@link Shifting#putBack}): false if it has to wait. */
    private static boolean putBack(ServerLevel hidden, BlockPos pos, PhaseShifterBlockEntity entity) {
        ServerLevel visible = Shifting.visibleOf(hidden);
        Shifting.Held held = entity.holding();
        if (visible == null || held == null || !Shifting.putBack(visible, pos, held)) {
            return false;
        }
        entity.setHeld(null);
        return true;
    }

    /** The shifter is being removed: what it holds goes back to its spot, or drops there as an item if it cannot. */
    static void release(PhaseShifterBlockEntity entity) {
        if (!(entity.getLevel() instanceof ServerLevel hidden) || entity.holding() == null) {
            return;
        }
        Shifting.Held held = entity.holding();
        entity.setHeld(null);
        ServerLevel visible = Shifting.visibleOf(hidden);
        if (visible != null) {
            Shifting.putBackOrDrop(visible, entity.getBlockPos(), held);
        }
    }
}
