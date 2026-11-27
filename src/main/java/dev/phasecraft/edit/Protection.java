package dev.phasecraft.edit;

import eu.pb4.common.protection.api.CommonProtection;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * Claims and other protection mods, for editing mode. Such mods guard the
 * visible world: they listen to vanilla's clicks there and never see an edit
 * of the hidden layer. Yet a hidden block acts on the visible one at its spot
 * (a shifter takes it, a phased hopper empties the chest above), so a hidden
 * edit is refused wherever the same action on the visible block at that spot
 * would be: PhaseCraft asks, for the player's own (visible) level and the
 * spot, through Patbox's Common Protection API, which claim mods answer (Flan,
 * Get Off My Lawn, FTB Chunks with FTB XMod Compat...). The questions only
 * ask: nothing is clicked, placed or broken in the visible world. With no such
 * mod installed every answer is yes.
 *
 * <p>Asked for every player, Creative and operators too: who may pass is the
 * protection mod's decision. Not covered: mods that do not answer the API
 * (see docs/ARCHITECTURE.md), and hidden blocks acting with no player.</p>
 */
public final class Protection {
    public static final String REFUSED = "phasecraft.edit.protected";

    private Protection() {}

    /** Whether the player may put a hidden block (or fluid) at this spot. */
    public static boolean mayPlace(ServerPlayer player, BlockPos pos) {
        return allowed(player, CommonProtection.canPlaceBlock(player.level(), pos, player.nameAndId(), player));
    }

    /** Whether the player may break (or scoop up) what is hidden at this spot. */
    public static boolean mayBreak(ServerPlayer player, BlockPos pos) {
        return allowed(player, CommonProtection.canBreakBlock(player.level(), pos, player.nameAndId(), player));
    }

    /** Whether the player may use (click) the hidden block at this spot. */
    public static boolean mayUse(ServerPlayer player, BlockPos pos) {
        return allowed(player, CommonProtection.canInteractBlock(player.level(), pos, player.nameAndId(), player));
    }

    /** Tells the player when not, in the action bar: the hidden layer shows no reason of its own. */
    private static boolean allowed(ServerPlayer player, boolean allowed) {
        if (!allowed) {
            player.sendSystemMessage(Component.translatable(REFUSED), true);
        }
        return allowed;
    }
}
