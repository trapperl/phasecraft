package dev.phasecraft.phased;

import net.minecraft.world.level.block.Block;

/**
 * The phase block. A hidden block standing on it, or hanging on its side, is
 * phased: it reacts to the visible world at its own spot (see {@link Phased}).
 * In the visible layer it is an ordinary block. Pistons cannot move it (its
 * properties set PushReaction.IMMOVEABLE), so moving blocks around can never
 * quietly change what is phased.
 */
public class PhaseBlock extends Block {
    public PhaseBlock(Properties properties) {
        super(properties);
    }
}
