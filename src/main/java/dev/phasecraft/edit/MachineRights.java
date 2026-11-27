package dev.phasecraft.edit;

import eu.pb4.common.protection.api.CommonProtection;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.players.NameAndId;
import org.jspecify.annotations.Nullable;

/**
 * Claims hold against hidden machines: what a hidden machine (a shifter, a phase piston, a phased hopper, dropper
 * or dispenser) may change in the visible world, where a claim mod guards it. No player acts when a machine does,
 * and claim mods' own piston and hopper rules do not know PhaseCraft's blocks, so every machine asks here for each
 * visible spot it is about to change ({@link Protection} does the same for a player's edits).
 *
 * <ul>
 *   <li>A machine acts with the rights of the player who placed it ({@link MachineOwners}): the claim mod is asked,
 *       through the Common Protection API, with that player's profile and no player (the owner may be offline).</li>
 *   <li>A machine with no known owner (placed by a command or a structure, or from before owners were kept) may
 *       change a protected spot only if its own spot is protected too: it was built inside. This is the nearest
 *       thing to claim mods' "a piston does not push across a claim's border" the API allows: it cannot say whether
 *       two spots are in the same claim, so an ownerless machine in one claim can reach into the claim next to
 *       it.</li>
 *   <li>A refused spot is to the machine what an immovable block is: see each machine. Nobody is told: there is
 *       nobody to tell.</li>
 *   <li>With no protection mod installed nothing is looked up: every answer is yes.</li>
 * </ul>
 * A new kind of machine must ask here for every visible spot it changes.
 */
public final class MachineRights {
    private static final MachineRights FREE = new MachineRights(null, new BlockPos[0]);

    private final @Nullable ServerLevel hidden;
    private final BlockPos[] machines;
    private final @Nullable NameAndId[] owners;
    /** For a machine with no owner: whether its own spot is protected (looked up when first needed). */
    private final @Nullable Boolean[] inside;

    private MachineRights(@Nullable ServerLevel hidden, BlockPos[] machines) {
        this.hidden = hidden;
        this.machines = machines;
        this.owners = new NameAndId[machines.length];
        this.inside = new Boolean[machines.length];
        for (int i = 0; i < machines.length; i++) {
            owners[i] = MachineOwners.owner(hidden, machines[i]);
        }
    }

    /** Whether any protection mod answers: if none, nothing is refused and nothing needs looking up. */
    public static boolean anyProtection() {
        return !CommonProtection.getProviderIds().isEmpty();
    }

    /**
     * The rights of the machine at a spot of a hidden level; given several (an area shifter's two corners), what
     * all of them may do.
     */
    public static MachineRights of(ServerLevel hidden, BlockPos... machines) {
        return anyProtection() ? new MachineRights(hidden, machines) : FREE;
    }

    private enum Question { BREAK, PLACE, USE }

    private boolean may(Question question, ServerLevel visible, BlockPos spot) {
        for (int i = 0; i < machines.length; i++) {
            NameAndId owner = owners[i];
            if (owner != null) {
                boolean allowed = switch (question) {
                    case BREAK -> CommonProtection.canBreakBlock(visible, spot, owner, null);
                    case PLACE -> CommonProtection.canPlaceBlock(visible, spot, owner, null);
                    case USE -> CommonProtection.canInteractBlock(visible, spot, owner, null);
                };
                if (!allowed) {
                    return false;
                }
            } else if (CommonProtection.isProtected(visible, spot)) {
                if (inside[i] == null) {
                    inside[i] = CommonProtection.isProtected(visible, machines[i]);
                }
                if (!inside[i]) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Whether the machine may take the visible block at a spot away (take it, push it on, break it). */
    public boolean mayBreak(ServerLevel visible, BlockPos spot) {
        return may(Question.BREAK, visible, spot);
    }

    /** Whether the machine may put a block (or an item, an arrow, water...) into a visible spot. */
    public boolean mayPlace(ServerLevel visible, BlockPos spot) {
        return may(Question.PLACE, visible, spot);
    }

    /** Whether the machine may use the visible block at a spot: take items from a container or put them in. */
    public boolean mayUse(ServerLevel visible, BlockPos spot) {
        return may(Question.USE, visible, spot);
    }
}
