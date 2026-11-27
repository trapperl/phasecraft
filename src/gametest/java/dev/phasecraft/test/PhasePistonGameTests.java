package dev.phasecraft.test;

import dev.phasecraft.PhaseCraftBlocks;
import dev.phasecraft.layer.HiddenLayer;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * Phase pistons: a piston in the hidden layer that moves visible blocks, by
 * vanilla push rules. Its body stays in the hidden layer; where a vanilla
 * piston's arm would be, the visible space is left empty.
 */
public class PhasePistonGameTests {
    private static final BlockState REDSTONE_BLOCK = Blocks.REDSTONE_BLOCK.defaultBlockState();

    private static BlockState phasePiston(boolean sticky, Direction facing) {
        Block block = sticky ? PhaseCraftBlocks.STICKY_PHASE_PISTON : PhaseCraftBlocks.PHASE_PISTON;
        return block.defaultBlockState().setValue(BlockStateProperties.FACING, facing);
    }

    private static boolean extended(BlockState state) {
        return state.hasProperty(BlockStateProperties.EXTENDED) && state.getValue(BlockStateProperties.EXTENDED);
    }

    /** README: a hidden-layer piston can push an overworld block. */
    @GameTest(maxTicks = 60)
    public void pushesVisibleBlock(GameTestHelper helper) {
        BlockPos pistonRel = new BlockPos(1, 2, 2);
        BlockPos piston = helper.absolutePos(pistonRel);
        HiddenLayer layer = HiddenLayer.of(helper.getLevel());
        helper.setBlock(pistonRel, Blocks.GLASS);
        helper.setBlock(pistonRel.east(), Blocks.STONE);
        layer.setBlock(piston, phasePiston(false, Direction.EAST));

        helper.startSequence()
                .thenExecute(() -> layer.setBlock(piston.below(), REDSTONE_BLOCK))
                .thenWaitUntil(() -> helper.assertBlockPresent(Blocks.STONE, pistonRel.east(2)))
                .thenExecute(() -> {
                    helper.assertBlockPresent(Blocks.AIR, pistonRel.east());
                    helper.assertBlockPresent(Blocks.GLASS, pistonRel);
                    helper.assertTrue(extended(layer.getBlock(piston)), "phase piston not extended");
                    helper.assertValueEqual(layer.getBlock(piston.east()), Blocks.AIR.defaultBlockState(),
                            "hidden space in front of the phase piston");
                    layer.removeBlock(piston.below());
                })
                .thenWaitUntil(() -> helper.assertFalse(extended(layer.getBlock(piston)), "phase piston did not retract"))
                .thenExecute(() -> {
                    helper.assertBlockPresent(Blocks.STONE, pistonRel.east(2));
                    layer.removeBlock(piston);
                })
                .thenSucceed();
    }

    /** A row of 13 blocks and the spot past it need more room than the default 8x8x8 area. */
    static final String LONG_ROW = "phasecraft-test:empty_24x7x24";

    /** README: a piston cannot push 13 ordinary blocks (12 is the limit). */
    @GameTest(maxTicks = 40, structure = LONG_ROW)
    public void cannotPushThirteenBlocks(GameTestHelper helper) {
        BlockPos pistonRel = new BlockPos(0, 2, 1);
        BlockPos piston = helper.absolutePos(pistonRel);
        HiddenLayer layer = HiddenLayer.of(helper.getLevel());
        for (int i = 1; i <= 13; i++) {
            helper.setBlock(pistonRel.east(i), Blocks.STONE);
        }
        layer.setBlock(piston, phasePiston(false, Direction.EAST));

        helper.startSequence()
                .thenExecute(() -> layer.setBlock(piston.below(), REDSTONE_BLOCK))
                .thenIdle(10)
                .thenExecute(() -> {
                    helper.assertFalse(extended(layer.getBlock(piston)), "phase piston extended against 13 blocks");
                    for (int i = 1; i <= 13; i++) {
                        helper.assertBlockPresent(Blocks.STONE, pistonRel.east(i));
                    }
                    helper.assertBlockPresent(Blocks.AIR, pistonRel.east(14));
                    for (int i = 1; i <= 13; i++) {
                        helper.setBlock(pistonRel.east(i), Blocks.AIR);
                    }
                    layer.removeBlock(piston.below());
                    layer.removeBlock(piston);
                })
                .thenSucceed();
    }

    /** Twelve blocks is the vanilla limit, and is pushed. */
    @GameTest(maxTicks = 60, structure = LONG_ROW)
    public void pushesTwelveBlocks(GameTestHelper helper) {
        BlockPos pistonRel = new BlockPos(0, 2, 3);
        BlockPos piston = helper.absolutePos(pistonRel);
        HiddenLayer layer = HiddenLayer.of(helper.getLevel());
        for (int i = 1; i <= 12; i++) {
            helper.setBlock(pistonRel.east(i), Blocks.STONE);
        }
        layer.setBlock(piston, phasePiston(false, Direction.EAST));

        helper.startSequence()
                .thenExecute(() -> layer.setBlock(piston.below(), REDSTONE_BLOCK))
                .thenWaitUntil(() -> helper.assertBlockPresent(Blocks.STONE, pistonRel.east(13)))
                .thenExecute(() -> {
                    helper.assertBlockPresent(Blocks.AIR, pistonRel.east());
                    for (int i = 2; i <= 13; i++) {
                        helper.assertBlockPresent(Blocks.STONE, pistonRel.east(i));
                        helper.setBlock(pistonRel.east(i), Blocks.AIR);
                    }
                    layer.removeBlock(piston.below());
                    layer.removeBlock(piston);
                })
                .thenSucceed();
    }

    /** README: obsidian remains immovable. */
    @GameTest(maxTicks = 40)
    public void obsidianIsImmovable(GameTestHelper helper) {
        BlockPos pistonRel = new BlockPos(1, 2, 5);
        BlockPos piston = helper.absolutePos(pistonRel);
        HiddenLayer layer = HiddenLayer.of(helper.getLevel());
        helper.setBlock(pistonRel.east(), Blocks.OBSIDIAN);
        layer.setBlock(piston, phasePiston(false, Direction.EAST));

        helper.startSequence()
                .thenExecute(() -> layer.setBlock(piston.below(), REDSTONE_BLOCK))
                .thenIdle(10)
                .thenExecute(() -> {
                    helper.assertFalse(extended(layer.getBlock(piston)), "phase piston extended against obsidian");
                    helper.assertBlockPresent(Blocks.OBSIDIAN, pistonRel.east());
                    helper.assertBlockPresent(Blocks.AIR, pistonRel.east(2));
                    layer.removeBlock(piston.below());
                    layer.removeBlock(piston);
                })
                .thenSucceed();
    }

    /** A sticky phase piston pulls the visible block back when it loses power. */
    @GameTest(maxTicks = 80)
    public void stickyPhasePistonPullsBack(GameTestHelper helper) {
        BlockPos pistonRel = new BlockPos(1, 2, 7);
        BlockPos piston = helper.absolutePos(pistonRel);
        HiddenLayer layer = HiddenLayer.of(helper.getLevel());
        helper.setBlock(pistonRel.east(), Blocks.STONE);
        layer.setBlock(piston, phasePiston(true, Direction.EAST));

        helper.startSequence()
                .thenExecute(() -> layer.setBlock(piston.below(), REDSTONE_BLOCK))
                .thenWaitUntil(() -> helper.assertBlockPresent(Blocks.STONE, pistonRel.east(2)))
                .thenExecute(() -> layer.removeBlock(piston.below()))
                .thenWaitUntil(() -> helper.assertBlockPresent(Blocks.STONE, pistonRel.east()))
                .thenExecute(() -> {
                    helper.assertBlockPresent(Blocks.AIR, pistonRel.east(2));
                    helper.assertFalse(extended(layer.getBlock(piston)), "sticky phase piston still extended");
                    layer.removeBlock(piston);
                })
                .thenSucceed();
    }

    /** A phase piston placed in the visible layer does nothing, even when powered. */
    @GameTest(maxTicks = 40)
    public void doesNothingInTheVisibleLayer(GameTestHelper helper) {
        BlockPos pistonRel = new BlockPos(6, 2, 7);
        helper.setBlock(pistonRel.north(), Blocks.STONE);
        helper.setBlock(pistonRel, phasePiston(false, Direction.NORTH));

        helper.startSequence()
                .thenExecute(() -> helper.setBlock(pistonRel.below(), Blocks.REDSTONE_BLOCK))
                .thenIdle(10)
                .thenExecute(() -> {
                    helper.assertBlockPresent(Blocks.STONE, pistonRel.north());
                    helper.assertFalse(extended(helper.getBlockState(pistonRel)), "visible phase piston extended");
                })
                .thenSucceed();
    }

    /** An ordinary piston in the hidden layer stays in its layer: the visible block in front does not move. */
    @GameTest(maxTicks = 40)
    public void ordinaryHiddenPistonStaysInItsLayer(GameTestHelper helper) {
        BlockPos pistonRel = new BlockPos(5, 2, 1);
        BlockPos piston = helper.absolutePos(pistonRel);
        HiddenLayer layer = HiddenLayer.of(helper.getLevel());
        helper.setBlock(pistonRel.south(), Blocks.STONE);
        layer.setBlock(piston, Blocks.PISTON.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.SOUTH));

        helper.startSequence()
                .thenExecute(() -> layer.setBlock(piston.below(), REDSTONE_BLOCK))
                .thenWaitUntil(() -> helper.assertTrue(layer.getBlock(piston.south()).is(Blocks.PISTON_HEAD),
                        "ordinary hidden piston did not extend in the hidden layer"))
                .thenExecute(() -> {
                    helper.assertBlockPresent(Blocks.STONE, pistonRel.south());
                    helper.assertBlockPresent(Blocks.AIR, pistonRel.south(2));
                    layer.removeBlock(piston.below());
                })
                .thenWaitUntil(() -> helper.assertFalse(layer.getBlock(piston.south()).is(Blocks.PISTON_HEAD), "head not gone"))
                .thenExecute(() -> layer.removeBlock(piston))
                .thenSucceed();
    }

    /**
     * The whole chain: a visible lever, through a hidden phase port, powers a
     * hidden sticky phase piston that moves a visible block out of a doorway
     * and back.
     */
    @GameTest(maxTicks = 120)
    public void visibleLeverMovesVisibleBlockThroughHiddenMachinery(GameTestHelper helper) {
        BlockPos leverRel = new BlockPos(5, 2, 5);
        BlockPos lever = helper.absolutePos(leverRel);
        BlockPos piston = lever.east();
        BlockPos doorRel = leverRel.east().south();
        HiddenLayer layer = HiddenLayer.of(helper.getLevel());
        helper.setBlock(leverRel.below(), Blocks.STONE);
        helper.setBlock(leverRel, Blocks.LEVER.defaultBlockState()
                .setValue(LeverBlock.FACE, AttachFace.FLOOR)
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH));
        helper.setBlock(doorRel, Blocks.STONE_BRICKS);
        layer.setBlock(lever, PhaseCraftBlocks.PHASE_PORT.defaultBlockState());
        layer.setBlock(piston, phasePiston(true, Direction.SOUTH));

        helper.startSequence()
                .thenExecute(() -> helper.pullLever(leverRel))
                .thenWaitUntil(() -> helper.assertBlockPresent(Blocks.STONE_BRICKS, doorRel.south()))
                .thenExecute(() -> helper.assertBlockPresent(Blocks.AIR, doorRel))
                .thenExecute(() -> helper.pullLever(leverRel))
                .thenWaitUntil(() -> helper.assertBlockPresent(Blocks.STONE_BRICKS, doorRel))
                .thenExecute(() -> {
                    helper.assertBlockPresent(Blocks.AIR, doorRel.south());
                    layer.removeBlock(piston);
                    layer.removeBlock(lever);
                })
                .thenSucceed();
    }
}
