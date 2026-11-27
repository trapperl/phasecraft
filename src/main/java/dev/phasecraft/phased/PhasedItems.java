package dev.phasecraft.phased;

import dev.phasecraft.edit.MachineRights;
import dev.phasecraft.layer.HiddenDimensions;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.DropperBlock;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
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
 * Where claims guard the visible world they act with the rights of whoever
 * placed them ({@link #mayPull}, {@link #mayPutOut}).
 *
 * <p>A sorter can then take from a visible chest, sort in the hidden layer, and
 * fill visible chests, with nothing showing.</p>
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

    /**
     * Whether claims let the phased hopper at pos pull from the visible spot above it (a container's items, or
     * items lying there): what whoever placed it may use there (see {@link MachineRights}). If not, it pulls
     * nothing.
     */
    public static boolean mayPull(ServerLevel hidden, BlockPos pos, ServerLevel visible) {
        return !MachineRights.anyProtection() || MachineRights.of(hidden, pos).mayUse(visible, pos.above());
    }

    /**
     * Whether claims let the phased dropper or dispenser at pos put out into the visible spot in front of it, by the
     * rights of whoever placed it: a dropper facing a container may use it, otherwise it may place there; a
     * dispenser may place and break there (it pours and scoops up water, plants, shears, lights...). If not, it
     * does nothing, and keeps its items.
     */
    public static boolean mayPutOut(ServerLevel hidden, BlockPos pos, BlockState state, ServerLevel visible) {
        if (!MachineRights.anyProtection()) {
            return true;
        }
        MachineRights rights = MachineRights.of(hidden, pos);
        BlockPos front = pos.relative(state.getValue(DispenserBlock.FACING));
        if (state.getBlock() instanceof DropperBlock) {
            return HopperBlockEntity.getContainerAt(visible, front) != null ? rights.mayUse(visible, front) : rights.mayPlace(visible, front);
        }
        return rights.mayPlace(visible, front) && rights.mayBreak(visible, front);
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
