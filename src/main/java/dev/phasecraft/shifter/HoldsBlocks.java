package dev.phasecraft.shifter;

/** A shifter's block entity: how many visible blocks it holds (sent to editing clients for the readout). */
public interface HoldsBlocks {
    int heldCount();

    /** Whether a block is one of the shifters (a phase shifter, a Shifter Piston, an area shifter). */
    static boolean isShifter(net.minecraft.world.level.block.Block block) {
        return block instanceof PhaseShifterBlock || block instanceof LineShifterBlock || block instanceof AreaShifterBlock;
    }
}
