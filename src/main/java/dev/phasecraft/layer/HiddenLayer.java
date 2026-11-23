package dev.phasecraft.layer;

import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A visible dimension and its hidden layer: a real dimension at the same
 * coordinates, where Minecraft itself runs redstone, ticks and pistons.
 *
 * <p>Writing here never touches the visible world. Cross-layer effects (power
 * and pistons reaching across) are separate and not implemented yet.</p>
 */
public final class HiddenLayer {
    private final ServerLevel visible;
    private final ServerLevel hidden;

    private HiddenLayer(ServerLevel visible, ServerLevel hidden) {
        this.visible = visible;
        this.hidden = hidden;
    }

    /**
     * The layer pair for a level, given either side of it.
     *
     * @throws IllegalArgumentException if the level has no hidden layer (a modded dimension)
     * @throws IllegalStateException if the hidden dimension is not loaded (PhaseCraft's data pack missing)
     */
    public static HiddenLayer of(ServerLevel level) {
        ResourceKey<Level> key = level.dimension();
        if (HiddenDimensions.isHidden(key)) {
            ResourceKey<Level> visibleKey = HiddenDimensions.visibleOf(key).orElseThrow();
            return new HiddenLayer(require(level, visibleKey), level);
        }
        ResourceKey<Level> hiddenKey = HiddenDimensions.hiddenOf(key).orElseThrow(() ->
                new IllegalArgumentException(key.identifier() + " has no hidden layer"));
        return new HiddenLayer(level, require(level, hiddenKey));
    }

    private static ServerLevel require(ServerLevel any, ResourceKey<Level> key) {
        return Objects.requireNonNull(any.getServer().getLevel(key), () ->
                key.identifier() + " is not loaded; is PhaseCraft's data pack enabled?");
    }

    /** The hidden dimension. */
    public ServerLevel level() {
        return hidden;
    }

    /** The visible dimension it shadows. */
    public ServerLevel visibleLevel() {
        return visible;
    }

    /** Whether a position is inside the hidden dimension's height range (same as the visible one's). */
    public boolean contains(BlockPos pos) {
        return !hidden.isOutsideBuildHeight(pos);
    }

    /** The hidden block state at a position; air if nothing is there. */
    public BlockState getBlock(BlockPos pos) {
        return hidden.getBlockState(pos);
    }

    /**
     * Places a hidden block like /setblock does: neighbours in the hidden layer react by vanilla rules.
     *
     * @throws IllegalArgumentException if the position is outside the world's height range
     */
    public void setBlock(BlockPos pos, BlockState state) {
        if (!contains(pos)) {
            throw new IllegalArgumentException(pos.toShortString() + " is outside the world height ("
                    + hidden.getMinY() + " to " + hidden.getMaxY() + ")");
        }
        hidden.setBlock(pos, state, Block.UPDATE_ALL);
    }

    public void removeBlock(BlockPos pos) {
        setBlock(pos, Blocks.AIR.defaultBlockState());
    }
}
