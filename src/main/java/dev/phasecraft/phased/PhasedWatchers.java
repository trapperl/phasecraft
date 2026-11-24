package dev.phasecraft.phased;

import dev.phasecraft.layer.HiddenDimensions;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ComparatorBlock;
import net.minecraft.world.level.block.ObserverBlock;
import net.minecraft.world.level.block.TripWireBlock;
import net.minecraft.world.level.block.TripWireHookBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

/**
 * Phased blocks that watch the visible world (phase block, third step; the
 * person approved, 2026-11-23).
 *
 * <ul>
 *   <li><b>Observers:</b> a phased observer watches the visible block in
 *       front of it: when that block changes, it pulses, as for a hidden
 *       change there.</li>
 *   <li><b>Comparators:</b> a phased comparator reads the visible block
 *       behind it (a chest's fill level, a cake, a composter...), also
 *       through one visible solid block, and an item frame there, by
 *       vanilla's rules ({@code ComparatorBlockMixin}). It reads only what
 *       blocks hold, never visible redstone power: the phase block carries
 *       no power. The hidden reading still counts; the higher one wins.</li>
 *   <li><b>Tripwire:</b> a hidden string whose line ends at a hook on a
 *       phase block is tripped by visible players, mobs and items at the
 *       string's spot ({@code TripWireBlockMixin}, and
 *       {@link PhasedInput}'s per-tick check while it is released).</li>
 * </ul>
 */
public final class PhasedWatchers {
    private static final Direction[] DIRECTIONS = Direction.values();
    /** Vanilla's longest tripwire line: a hook at most 41 blocks from the other. */
    private static final int MAX_LINE = 42;

    private PhasedWatchers() {}

    private static @Nullable ServerLevel hiddenOf(ServerLevel visible) {
        return HiddenDimensions.hiddenOf(visible.dimension()).map(visible.getServer()::getLevel).orElse(null);
    }

    private static @Nullable ServerLevel visibleOf(ServerLevel hidden) {
        return HiddenDimensions.visibleOf(hidden.dimension()).map(hidden.getServer()::getLevel).orElse(null);
    }

    /** The hidden block at pos, or air if its chunk is not loaded (never loads one). */
    private static BlockState hiddenAt(ServerLevel hidden, BlockPos pos) {
        LevelChunk chunk = hidden.getChunkSource().getChunkNow(SectionPos.blockToSectionCoord(pos.getX()),
                SectionPos.blockToSectionCoord(pos.getZ()));
        return chunk == null ? Blocks.AIR.defaultBlockState() : chunk.getBlockState(pos);
    }

    /** A visible block changed (any server block change; the hook ignores hidden levels). */
    public static void onBlockChanged(ServerLevel level, BlockPos pos) {
        if (HiddenDimensions.isHidden(level.dimension())) {
            return;
        }
        ServerLevel hidden = hiddenOf(level);
        if (hidden == null) {
            return;
        }
        for (Direction facing : DIRECTIONS) {
            BlockPos at = pos.relative(facing.getOpposite());
            BlockState state = hiddenAt(hidden, at);
            if (state.getBlock() instanceof ObserverBlock observer && state.getValue(ObserverBlock.FACING) == facing
                    && !state.getValue(ObserverBlock.POWERED) && Phased.isOnPhaseBlock(hidden, at, state)
                    && !hidden.getBlockTicks().hasScheduledTick(at, observer)) {
                hidden.scheduleTick(at, observer, 2);
            }
        }
        refreshComparators(hidden, pos);
    }

    /** What a visible container (or other block with a reading) holds changed: phased comparators reading it look again. */
    public static void onOutputSignal(ServerLevel level, BlockPos pos) {
        if (HiddenDimensions.isHidden(level.dimension())) {
            return;
        }
        ServerLevel hidden = hiddenOf(level);
        if (hidden != null) {
            refreshComparators(hidden, pos);
        }
    }

    /** Phased comparators whose reading may involve the visible block at pos (right behind them, or one further). */
    private static void refreshComparators(ServerLevel hidden, BlockPos pos) {
        for (Direction away : Direction.Plane.HORIZONTAL) {
            for (int distance = 1; distance <= 2; distance++) {
                BlockPos at = pos.relative(away, distance);
                BlockState state = hiddenAt(hidden, at);
                if (state.getBlock() instanceof ComparatorBlock comparator
                        && state.getValue(ComparatorBlock.FACING) == away.getOpposite()
                        && Phased.isOnPhaseBlock(hidden, at, state)
                        && !hidden.getBlockTicks().hasScheduledTick(at, comparator)) {
                    hidden.scheduleTick(at, comparator, 2);
                }
            }
        }
    }

    /**
     * What a phased comparator at pos in a hidden level reads from the visible world behind it, by vanilla's
     * rules: a block with a reading right behind; or, behind a solid block, the block or item frame after it.
     * 0 if the comparator is not phased.
     */
    public static int comparatorReading(ServerLevel hidden, BlockPos pos, BlockState state) {
        if (!HiddenDimensions.isHidden(hidden.dimension()) || !Phased.isOnPhaseBlock(hidden, pos, state)) {
            return 0;
        }
        ServerLevel visible = visibleOf(hidden);
        Direction back = state.getValue(ComparatorBlock.FACING);
        BlockPos target = pos.relative(back);
        if (visible == null || !visible.isLoaded(target)) {
            return 0;
        }
        BlockState behind = visible.getBlockState(target);
        if (behind.hasAnalogOutputSignal()) {
            return behind.getAnalogOutputSignal(visible, target, back.getOpposite());
        }
        if (!behind.isRedstoneConductor(visible, target)) {
            return 0;
        }
        target = target.relative(back);
        if (!visible.isLoaded(target)) {
            return 0;
        }
        BlockState further = visible.getBlockState(target);
        int reading = further.hasAnalogOutputSignal() ? further.getAnalogOutputSignal(visible, target, back.getOpposite()) : 0;
        List<ItemFrame> frames = visible.getEntitiesOfClass(ItemFrame.class, new AABB(target), frame -> frame.getDirection() == back);
        if (frames.size() == 1) {
            reading = Math.max(reading, frames.get(0).getAnalogOutput());
        }
        return reading;
    }

    /** The visible level a hidden string at pos watches, if its line ends at a hook on a phase block; otherwise null. */
    public static @Nullable ServerLevel phasedStringWatches(ServerLevel hidden, BlockPos pos) {
        if (!HiddenDimensions.isHidden(hidden.dimension())) {
            return null;
        }
        for (Direction away : Direction.Plane.HORIZONTAL) {
            for (int distance = 1; distance < MAX_LINE; distance++) {
                BlockPos at = pos.relative(away, distance);
                if (!hidden.isLoaded(at)) {
                    break;
                }
                BlockState state = hidden.getBlockState(at);
                if (state.getBlock() instanceof TripWireHookBlock) {
                    if (state.getValue(TripWireHookBlock.FACING) == away.getOpposite() && Phased.isOnPhaseBlock(hidden, at, state)) {
                        return visibleOf(hidden);
                    }
                    break;
                }
                if (!(state.getBlock() instanceof TripWireBlock)) {
                    break;
                }
            }
        }
        return null;
    }
}
