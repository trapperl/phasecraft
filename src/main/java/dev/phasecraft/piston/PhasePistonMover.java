package dev.phasecraft.piston;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.piston.MovingPistonBlock;
import net.minecraft.world.level.block.piston.PistonStructureResolver;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.redstone.ExperimentalRedstoneUtils;
import net.minecraft.world.level.redstone.Orientation;

/**
 * Moves blocks in the <em>visible</em> level for a phase piston whose body is
 * in the hidden level at the same coordinates.
 *
 * <p>This is vanilla's {@code PistonBaseBlock.moveBlocks} (26.3) with one
 * change: no piston arm is placed. Which blocks move, push limits, immovable
 * blocks, slime/honey, drops and animation all come from vanilla's
 * {@link PistonStructureResolver} and moving-block entities, run on the
 * visible level. Re-check against vanilla when updating Minecraft.</p>
 */
final class PhasePistonMover {
    private PhasePistonMover() {}

    /** Whether vanilla rules would let a piston at pistonPos push (extending) or pull (retracting) in this level. */
    static boolean canMove(Level level, BlockPos pistonPos, Direction direction, boolean extending) {
        return new PistonStructureResolver(level, pistonPos, direction, extending).resolve();
    }

    static boolean moveBlocks(Level level, BlockPos pistonPos, Direction direction, boolean extending) {
        PistonStructureResolver resolver = new PistonStructureResolver(level, pistonPos, direction, extending);
        if (!resolver.resolve()) {
            return false;
        }

        Map<BlockPos, BlockState> deleteAfterMove = Maps.newHashMap();
        List<BlockPos> toPush = resolver.getToPush();
        List<BlockState> toPushShapes = Lists.newArrayList();
        for (BlockPos pos : toPush) {
            BlockState state = level.getBlockState(pos);
            toPushShapes.add(state);
            deleteAfterMove.put(pos, state);
        }

        List<BlockPos> toDestroy = resolver.getToDestroy();
        BlockState[] toUpdate = new BlockState[toPush.size() + toDestroy.size()];
        Direction pushDirection = extending ? direction : direction.getOpposite();
        int updateIndex = 0;

        for (int i = toDestroy.size() - 1; i >= 0; i--) {
            BlockPos pos = toDestroy.get(i);
            BlockState state = level.getBlockState(pos);
            BlockEntity blockEntity = state.hasBlockEntity() ? level.getBlockEntity(pos) : null;
            Block.dropResources(state, level, pos, blockEntity);
            if (!state.is(BlockTags.FIRE) && level.isClientSide()) {
                level.levelEvent(2001, pos, Block.getId(state));
            }
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 18);
            level.gameEvent(GameEvent.BLOCK_DESTROY, pos, GameEvent.Context.of(state));
            toUpdate[updateIndex++] = state;
        }

        List<PhasePistonAnimation.Moving> moving = new ArrayList<>();
        for (int i = toPush.size() - 1; i >= 0; i--) {
            BlockPos pos = toPush.get(i);
            BlockState blockState = level.getBlockState(pos);
            pos = pos.relative(pushDirection);
            deleteAfterMove.remove(pos);
            BlockState actualState = Blocks.MOVING_PISTON.defaultBlockState().setValue(BlockStateProperties.FACING, direction);
            level.setBlock(pos, actualState, 324);
            level.setBlockEntity(MovingPistonBlock.newMovingBlockEntity(pos, actualState, toPushShapes.get(i), direction, extending, false));
            moving.add(new PhasePistonAnimation.Moving(pos.immutable(), toPushShapes.get(i), direction, extending));
            toUpdate[updateIndex++] = blockState;
        }
        if (level instanceof ServerLevel serverLevel) {
            PhasePistonAnimation.send(serverLevel, moving);
        }

        // Vanilla places the moving piston arm here. A phase piston's arm is not in the visible layer.

        BlockState air = Blocks.AIR.defaultBlockState();
        for (BlockPos pos : deleteAfterMove.keySet()) {
            level.setBlock(pos, air, 82);
        }
        for (Entry<BlockPos, BlockState> entry : deleteAfterMove.entrySet()) {
            BlockPos pos = entry.getKey();
            entry.getValue().updateIndirectNeighbourShapes(level, pos, 2);
            air.updateNeighbourShapes(level, pos, 2);
            air.updateIndirectNeighbourShapes(level, pos, 2);
        }

        Orientation orientation = ExperimentalRedstoneUtils.initialOrientation(level, resolver.getPushDirection(), null);
        updateIndex = 0;
        for (int i = toDestroy.size() - 1; i >= 0; i--) {
            BlockState state = toUpdate[updateIndex++];
            BlockPos pos = toDestroy.get(i);
            if (level instanceof ServerLevel serverLevel) {
                state.affectNeighborsAfterRemoval(serverLevel, pos, false);
            }
            state.updateIndirectNeighbourShapes(level, pos, 2);
            level.updateNeighborsAt(pos, state.getBlock(), orientation);
        }
        for (int i = toPush.size() - 1; i >= 0; i--) {
            level.updateNeighborsAt(toPush.get(i), toUpdate[updateIndex++].getBlock(), orientation);
        }
        return true;
    }
}
