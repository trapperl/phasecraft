package dev.phasecraft.shifter;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.phasecraft.edit.MachineRights;
import dev.phasecraft.layer.HiddenDimensions;
import dev.phasecraft.phased.PhasedItems;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
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

    /**
     * Takes the visible block at pos if a shifter may (see {@link #reaction}), and a claim there lets the shifter
     * break it (see {@link MachineRights}): what it took, or null.
     */
    public static @Nullable Held take(ServerLevel visible, BlockPos pos, MachineRights rights) {
        if (!visible.isLoaded(pos)) {
            return null;
        }
        BlockState state = visible.getBlockState(pos);
        if (reaction(visible, pos, state) != Reaction.TAKE || !rights.mayBreak(visible, pos)) {
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
     * should break the main world block"): it drops as if broken with no tool, contents included, silently. A mob or
     * player in the way is lifted to stand on it, as a piston pushing up would move them (the person, 2026-11-26,
     * choosing this over waiting). Returns false, changing nothing, while one in the way has no room above, the block
     * there is unbreakable (bedrock, a moving piston...), the spot is not loaded, or a claim there refuses the
     * shifter (placing its block, or breaking the one in the spot; see {@link MachineRights}): the caller waits and
     * tries again. A block that cannot stand there any more (its neighbours turn it into air: a sign whose wall is
     * gone) is dropped there instead, and counts as put back.
     */
    public static boolean putBack(ServerLevel visible, BlockPos pos, Held held, MachineRights rights) {
        if (!visible.isLoaded(pos)) {
            return false;
        }
        BlockState there = visible.getBlockState(pos);
        if (!there.canBeReplaced() && there.getDestroySpeed(visible, pos) < 0.0F) {
            return false;
        }
        if (!rights.mayPlace(visible, pos) || (!there.canBeReplaced() && !rights.mayBreak(visible, pos))) {
            return false;
        }
        // As placed: connected to its neighbours (a fence's arms), which is the shape a mob must be lifted clear of.
        BlockState placed = Block.updateFromNeighbourShapes(held.state(), visible, pos);
        if (placed.isAir()) {
            // It cannot stand there any more (a sign whose wall is gone): it drops as an item, as it would the
            // moment it was placed, with what it held. What stands in the spot is left alone.
            drop(visible, pos, held);
            return true;
        }
        if (!liftOutOfTheWay(visible, pos, placed)) {
            return false;
        }
        if (!there.canBeReplaced()) {
            PhasedItems.silently(visible, () -> visible.destroyBlock(pos, true));
        }
        visible.setBlock(pos, placed, Block.UPDATE_ALL);
        BlockEntity contents = visible.getBlockEntity(pos);
        if (held.data() != null && contents != null) {
            contents.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING, visible.registryAccess(), held.data()));
            contents.setChanged();
        }
        return true;
    }

    /** No block but the one at pos (to be broken) collides with the box an entity sweeps through on its way up. */
    private static boolean clearWayUp(ServerLevel visible, BlockPos pos, Entity e, AABB way) {
        VoxelShape box = Shapes.create(way);
        CollisionContext context = CollisionContext.of(e);
        for (BlockPos at : BlockPos.betweenClosed(BlockPos.containing(way.minX, way.minY, way.minZ),
                BlockPos.containing(way.maxX, way.maxY, way.maxZ))) {
            if (at.equals(pos)) {
                continue;
            }
            VoxelShape shape = visible.getBlockState(at).getCollisionShape(visible, at, context);
            if (!shape.isEmpty() && Shapes.joinIsNotEmpty(shape.move(at), box, BooleanOp.AND)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Lifts every mob or player (whatever blocks building, spectators aside, as {@code Level.isUnobstructed} counts
     * them) whose box meets the block's collision shape at pos, to stand on the shape's top. A rider is not lifted on
     * its own: its vehicle, lifted, carries it. False, lifting none, if one would not fit there (a ceiling above it,
     * or anything on the way up: a slab over the next column it half stands in), or stands lower than the block, only
     * its head in the spot: it is never lifted through the block. The block now in the spot does not count: it is
     * broken first.
     */
    private static boolean liftOutOfTheWay(ServerLevel visible, BlockPos pos, BlockState state) {
        VoxelShape shape = state.getCollisionShape(visible, pos, CollisionContext.empty());
        if (shape.isEmpty()) {
            return true;
        }
        VoxelShape at = shape.move(pos);
        double top = at.max(Direction.Axis.Y);
        List<Entity> inTheWay = visible.getEntities((Entity) null, at.bounds(), e -> !e.isRemoved() && e.blocksBuilding
                && !e.isSpectator() && !e.isPassenger()
                && Shapes.joinIsNotEmpty(at, Shapes.create(e.getBoundingBox()), BooleanOp.AND));
        for (Entity e : inTheWay) {
            AABB lifted = e.getBoundingBox().move(0, top - e.getBoundingBox().minY, 0);
            if (e.getBoundingBox().minY < at.min(Direction.Axis.Y) - 1.0E-7
                    || !clearWayUp(visible, pos, e, e.getBoundingBox().minmax(lifted))
                    || !visible.noEntityCollision(e, lifted) || !visible.noBorderCollision(e, lifted)) {
                return false;
            }
        }
        for (Entity e : inTheWay) {
            double up = top - e.getBoundingBox().minY;
            if (up > 0) {
                e.teleportRelative(0, up, 0);
                e.resetFallDistance();
            }
        }
        return true;
    }

    /**
     * Whether a held block, put back at pos now, would not stand there but drop as an item: what holds it up is not
     * there (a sign whose wall is still held).
     */
    static boolean wouldDrop(ServerLevel visible, BlockPos pos, Held held) {
        return Block.updateFromNeighbourShapes(held.state(), visible, pos).isAir();
    }

    /** Puts a held block back at pos, or, if it cannot (or a claim refuses), drops it there as an item with its contents. */
    public static void putBackOrDrop(ServerLevel visible, BlockPos pos, Held held, MachineRights rights) {
        if (!putBack(visible, pos, held, rights)) {
            drop(visible, pos, held);
        }
    }

    /**
     * Drops a held block at pos as an item, as if it had stood there and been broken with no tool: its loot, and
     * then what its block entity lets go of when it is removed (vanilla's own {@code preRemoveSideEffects}: a
     * container's items, a lectern's book, a jukebox's record, a campfire's food, a furnace's experience).
     */
    private static void drop(ServerLevel visible, BlockPos pos, Held held) {
        BlockEntity contents = null;
        if (held.data() != null && held.state().getBlock() instanceof EntityBlock block) {
            contents = block.newBlockEntity(pos, held.state());
            if (contents != null) {
                contents.setLevel(visible);
                contents.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING, visible.registryAccess(), held.data()));
            }
        }
        Block.dropResources(held.state(), visible, pos, contents);
        if (contents != null) {
            contents.preRemoveSideEffects(pos, held.state());
        }
    }
}
