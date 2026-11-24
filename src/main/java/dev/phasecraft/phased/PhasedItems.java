package dev.phasecraft.phased;

import dev.phasecraft.layer.HiddenDimensions;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * Phased hoppers, droppers and dispensers (the agent's default, open to
 * change, following the phase block's rule that a phased block works with
 * the visible world at its spot):
 *
 * <ul>
 *   <li>A phased <b>hopper</b> pulls from the visible world above it: the
 *       visible container there, or visible items lying on top of its spot,
 *       by vanilla's rules ({@code HopperBlockEntityMixin} runs vanilla's
 *       pull against the visible level). It passes items on into hidden
 *       machinery as usual.</li>
 *   <li>A phased <b>dropper</b> or <b>dispenser</b> puts out into the visible
 *       world in front of it: into the visible container there, or out into
 *       the world (an item, an arrow, water...) by vanilla's dispense
 *       behaviours, given the visible level ({@code DispenserBlockMixin},
 *       {@code DropperBlockMixin}). Its click and smoke are not heard or seen
 *       in the visible world: hidden blocks are silent there (the person,
 *       2026-11-23), so sounds and level events in the visible level are
 *       dropped while it dispenses ({@code ServerLevelSilenceMixin}).</li>
 * </ul>
 * A sorter can then take from a visible chest, sort in the hidden layer, and
 * fill visible chests, with nothing showing.
 */
public final class PhasedItems {
    /** Set while a phased dropper or dispenser acts on the visible level: what it would make heard there is dropped. */
    private static @Nullable ServerLevel silenced;

    private PhasedItems() {}

    /** The visible level a block at pos works with, if it is in a hidden layer and phased; otherwise null. */
    public static @Nullable ServerLevel visibleFor(Level level, BlockPos pos, BlockState state) {
        if (!(level instanceof ServerLevel hidden) || !HiddenDimensions.isHidden(hidden.dimension())
                || !Phased.isOnPhaseBlock(hidden, pos, state)) {
            return null;
        }
        return HiddenDimensions.visibleOf(hidden.dimension()).map(hidden.getServer()::getLevel).orElse(null);
    }

    /** Runs a phased dispense into the visible level, silencing that level meanwhile. */
    public static <T> T silently(ServerLevel visible, java.util.function.Supplier<T> action) {
        ServerLevel before = silenced;
        silenced = visible;
        try {
            return action.get();
        } finally {
            silenced = before;
        }
    }

    /** Whether sounds and level events in this level are being dropped (a phased dispense is under way there). */
    public static boolean isSilenced(Level level) {
        return silenced != null && silenced == level;
    }
}
