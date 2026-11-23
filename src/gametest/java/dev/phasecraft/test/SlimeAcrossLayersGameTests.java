package dev.phasecraft.test;

import dev.phasecraft.PhaseCraftBlocks;
import dev.phasecraft.layer.HiddenLayer;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * Groundwork for a flying machine with a hidden engine: what phase pistons
 * and slime do across the two layers today.
 *
 * <ul>
 *   <li>A phase piston pushing a visible slime block drags the visible blocks
 *       stuck to it, by vanilla's rules.</li>
 *   <li>Hidden blocks at the same spots stay where they are: the layers move
 *       independently. (A structure spanning both layers needs a new rule;
 *       see docs/ARCHITECTURE.md, flying machine.)</li>
 *   <li>A phase piston moved by a vanilla piston in the hidden layer keeps
 *       working where it lands: a hidden engine can carry phase pistons.</li>
 * </ul>
 */
public class SlimeAcrossLayersGameTests {
    private static BlockState phasePiston(boolean sticky, Direction facing) {
        return (sticky ? PhaseCraftBlocks.STICKY_PHASE_PISTON : PhaseCraftBlocks.PHASE_PISTON).defaultBlockState()
                .setValue(BlockStateProperties.FACING, facing);
    }

    /** A phase piston pushes a visible slime block east; the visible stone stuck on top of it comes along. */
    @GameTest(maxTicks = 40)
    public void phasePistonDragsVisibleSlimeStructure(GameTestHelper helper) {
        HiddenLayer hidden = HiddenLayer.of(helper.getLevel());
        BlockPos piston = new BlockPos(1, 2, 3);
        helper.setBlock(piston.east(), Blocks.SLIME_BLOCK);
        helper.setBlock(piston.east().above(), Blocks.STONE);
        hidden.setBlock(helper.absolutePos(piston), phasePiston(false, Direction.EAST));

        helper.startSequence()
                .thenExecute(() -> hidden.setBlock(helper.absolutePos(piston.below()), Blocks.REDSTONE_BLOCK.defaultBlockState()))
                .thenWaitUntil(() -> helper.assertBlockPresent(Blocks.SLIME_BLOCK, piston.east(2)))
                .thenExecute(() -> {
                    helper.assertBlockPresent(Blocks.STONE, piston.east(2).above());
                    helper.assertBlockPresent(Blocks.AIR, piston.east().above());
                })
                .thenSucceed();
    }

    /** The same push leaves hidden blocks at the slime's spots where they were: the layers move independently. */
    @GameTest(maxTicks = 40)
    public void hiddenBlocksDoNotMoveWithVisibleSlime(GameTestHelper helper) {
        HiddenLayer hidden = HiddenLayer.of(helper.getLevel());
        BlockPos piston = new BlockPos(1, 2, 3);
        helper.setBlock(piston.east(), Blocks.SLIME_BLOCK);
        hidden.setBlock(helper.absolutePos(piston.east()), Blocks.GOLD_BLOCK.defaultBlockState());
        hidden.setBlock(helper.absolutePos(piston.east().above()), Blocks.SLIME_BLOCK.defaultBlockState());
        hidden.setBlock(helper.absolutePos(piston), phasePiston(false, Direction.EAST));

        helper.startSequence()
                .thenExecute(() -> hidden.setBlock(helper.absolutePos(piston.below()), Blocks.REDSTONE_BLOCK.defaultBlockState()))
                .thenWaitUntil(() -> helper.assertBlockPresent(Blocks.SLIME_BLOCK, piston.east(2)))
                .thenExecute(() -> {
                    helper.assertValueEqual(hidden.getBlock(helper.absolutePos(piston.east())).getBlock(), Blocks.GOLD_BLOCK,
                            "hidden gold at the visible slime's old spot");
                    helper.assertValueEqual(hidden.getBlock(helper.absolutePos(piston.east().above())).getBlock(), Blocks.SLIME_BLOCK,
                            "hidden slime above it");
                    helper.assertValueEqual(hidden.getBlock(helper.absolutePos(piston.east(2))).getBlock(), Blocks.AIR,
                            "hidden spot the visible slime moved to");
                })
                .thenSucceed();
    }

    /**
     * A vanilla piston in the hidden layer pushes a phase piston one block east; powered there,
     * the phase piston pushes the visible block in front of its new spot.
     */
    @GameTest(maxTicks = 60)
    public void movedPhasePistonWorksWhereItLands(GameTestHelper helper) {
        HiddenLayer hidden = HiddenLayer.of(helper.getLevel());
        BlockPos pusher = new BlockPos(1, 2, 3);
        BlockPos phase = pusher.east();
        BlockPos landed = phase.east();
        // The phase piston faces north; the visible block in front of where it will land.
        helper.setBlock(landed.north(), Blocks.STONE);
        hidden.setBlock(helper.absolutePos(phase), phasePiston(false, Direction.NORTH));
        hidden.setBlock(helper.absolutePos(pusher), Blocks.PISTON.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.EAST));

        helper.startSequence()
                .thenExecute(() -> hidden.setBlock(helper.absolutePos(pusher.below()), Blocks.REDSTONE_BLOCK.defaultBlockState()))
                .thenWaitUntil(() -> helper.assertValueEqual(hidden.getBlock(helper.absolutePos(landed)).getBlock(),
                        PhaseCraftBlocks.PHASE_PISTON, "phase piston moved by the hidden piston"))
                .thenIdle(3)
                .thenExecute(() -> helper.assertBlockPresent(Blocks.STONE, landed.north()))
                .thenExecute(() -> hidden.setBlock(helper.absolutePos(landed.below()), Blocks.REDSTONE_BLOCK.defaultBlockState()))
                .thenWaitUntil(() -> helper.assertBlockPresent(Blocks.STONE, landed.north(2)))
                .thenSucceed();
    }
}
