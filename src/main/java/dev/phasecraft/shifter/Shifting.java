package dev.phasecraft.shifter;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.phasecraft.layer.HiddenDimensions;
import dev.phasecraft.phased.PhasedItems;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.Container;
import net.minecraft.world.Containers;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.jspecify.annotations.Nullable;

/**
 * Taking one visible block out of the visible world and putting it back, shared by the phase shifter (its own spot)
 * and the line shifter (the line in front of it). Nothing is lost or doubled, nothing is heard.
 */
public final class Shifting {
    private Shifting() {}

    /** A visible block held out of the visible world, with what its own block entity held (a chest's items...). */
    public record Held(BlockState state, @Nullable CompoundTag data) {
        public static final Codec<Held> CODEC = RecordCodecBuilder.create(i -> i.group(
                BlockState.CODEC.fieldOf("state").forGetter(Held::state),
                CompoundTag.CODEC.optionalFieldOf("data").forGetter(h -> Optional.ofNullable(h.data())))
                .apply(i, (state, data) -> new Held(state, data.orElse(null))));
    }

    /** What a piston pushing into a block would do with it. */
    public enum Reaction {
        /** Nothing there to move (air, outside the world): a pushed line ends before it. */
        NONE,
        /** A piston would break it (torches, flowers, fluids...): a pushed line ends before it; it is left alone. */
        FRAGILE,
        /** A piston could push it (or it has contents, which a shifter takes too). */
        TAKE,
        /** A piston cannot move it (obsidian, bedrock, an extended piston...): a pushed line containing it fails. */
        IMMOVABLE
    }

    /**
     * Vanilla's PistonBaseBlock.isPushable, sorted into the four outcomes, without its last rule ("no block
     * entities"): pistons refuse blocks with contents only because they would carry them somewhere else; a shifter
     * always puts a block back at its own spot.
     */
    public static Reaction reaction(ServerLevel visible, BlockPos pos, BlockState state) {
        if (state.isAir() || visible.isOutsideBuildHeight(pos)) {
            return Reaction.NONE;
        }
        if (state.is(Blocks.PISTON) || state.is(Blocks.STICKY_PISTON)) {
            return state.getValue(PistonBaseBlock.EXTENDED) ? Reaction.IMMOVABLE : Reaction.TAKE;
        }
        if (state.getDestroySpeed(visible, pos) == -1.0F || !visible.getWorldBorder().isWithinBounds(pos)) {
            return Reaction.IMMOVABLE;
        }
        return switch (state.getPistonPushReaction()) {
            case IMMOVEABLE -> Reaction.IMMOVABLE;
            case POPPED -> Reaction.FRAGILE;
            default -> Reaction.TAKE;
        };
    }

    public static @Nullable ServerLevel visibleOf(ServerLevel hidden) {
        return HiddenDimensions.visibleOf(hidden.dimension()).map(key -> hidden.getServer().getLevel(key)).orElse(null);
    }

    /** Takes the visible block at pos if a shifter may (see {@link #reaction}): what it took, or null. */
    public static @Nullable Held take(ServerLevel visible, BlockPos pos) {
        if (!visible.isLoaded(pos)) {
            return null;
        }
        BlockState state = visible.getBlockState(pos);
        if (reaction(visible, pos, state) != Reaction.TAKE) {
            return null;
        }
        BlockEntity contents = visible.getBlockEntity(pos);
        Held held = new Held(state, contents == null ? null : contents.saveWithoutMetadata(visible.registryAccess()));
        // Skipping block entity side effects: a chest taken away does not spill its items.
        visible.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL | Block.UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS);
        return held;
    }

    /**
     * Puts a held block back at pos, breaking a visible block in its spot first (the person, 2026-11-24: "I think it
     * should break the main world block"): it drops as if broken with no tool, contents included, silently. Returns
     * false, changing nothing, while a mob or player stands there, the block there is unbreakable (bedrock, a moving
     * piston...) or the spot is not loaded: the caller waits and tries again.
     */
    public static boolean putBack(ServerLevel visible, BlockPos pos, Held held) {
        if (!visible.isLoaded(pos) || !visible.isUnobstructed(held.state(), pos, CollisionContext.empty())) {
            return false;
        }
        BlockState there = visible.getBlockState(pos);
        if (!there.canBeReplaced()) {
            if (there.getDestroySpeed(visible, pos) < 0.0F) {
                return false;
            }
            PhasedItems.silently(visible, () -> visible.destroyBlock(pos, true));
        }
        visible.setBlock(pos, Block.updateFromNeighbourShapes(held.state(), visible, pos), Block.UPDATE_ALL);
        BlockEntity contents = visible.getBlockEntity(pos);
        if (held.data() != null && contents != null) {
            contents.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING, visible.registryAccess(), held.data()));
            contents.setChanged();
        }
        return true;
    }

    /** Puts a held block back at pos, or, if it cannot, drops it there as an item with its contents. */
    public static void putBackOrDrop(ServerLevel visible, BlockPos pos, Held held) {
        if (putBack(visible, pos, held)) {
            return;
        }
        BlockEntity contents = null;
        if (held.data() != null && held.state().getBlock() instanceof EntityBlock block) {
            contents = block.newBlockEntity(pos, held.state());
            if (contents != null) {
                contents.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING, visible.registryAccess(), held.data()));
            }
        }
        if (contents instanceof Container container) {
            Containers.dropContents(visible, pos, container);
        }
        Block.dropResources(held.state(), visible, pos, contents);
    }
}
