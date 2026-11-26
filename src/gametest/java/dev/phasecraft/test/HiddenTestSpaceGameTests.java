package dev.phasecraft.test;

import dev.phasecraft.PhaseCraftBlocks;
import dev.phasecraft.layer.HiddenLayer;
import dev.phasecraft.shifter.AreaShifterBlock;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.StructureUtils;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * The test harness clears the hidden layer with a test's box (ClearHiddenTestSpaceMixin): vanilla clears only the
 * visible blocks, before a test is built and between batches, which reuse the same places; a hidden block a test left
 * behind would otherwise be in the next test built there.
 */
public class HiddenTestSpaceGameTests {
    private static BoundingBox box(GameTestHelper helper, BlockPos from, BlockPos to) {
        return BoundingBox.fromCorners(helper.absolutePos(from), helper.absolutePos(to));
    }

    /** Hidden blocks in a cleared box are gone, as are the visible ones; one just outside it stays. */
    @GameTest(structure = AreaShifterGameTests.STRUCTURE)
    public void clearingATestBoxClearsItsHiddenLayerToo(GameTestHelper helper) {
        HiddenLayer hidden = HiddenLayer.of(helper.getLevel());
        BlockPos in = new BlockPos(3, 2, 3);
        BlockPos out = new BlockPos(7, 2, 3);
        hidden.setBlock(helper.absolutePos(in), Blocks.REDSTONE_BLOCK.defaultBlockState());
        hidden.setBlock(helper.absolutePos(in.above()), Blocks.STONE.defaultBlockState());
        hidden.setBlock(helper.absolutePos(out), Blocks.STONE.defaultBlockState());
        helper.setBlock(in, Blocks.STONE);
        StructureUtils.clearSpaceForStructure(box(helper, new BlockPos(1, 1, 1), new BlockPos(5, 5, 5)), helper.getLevel());
        helper.assertTrue(hidden.getBlock(helper.absolutePos(in)).isAir(), "the hidden redstone block left in the box");
        helper.assertTrue(hidden.getBlock(helper.absolutePos(in.above())).isAir(), "the hidden stone left in the box");
        helper.assertTrue(helper.getBlockState(in).isAir(), "the visible stone left in the box");
        helper.assertValueEqual(hidden.getBlock(helper.absolutePos(out)), Blocks.STONE.defaultBlockState(),
                "the hidden stone outside the box");
        helper.succeed();
    }

    /**
     * An area shifter pair holding its box, cleared with it, puts nothing back into the visible world (cleared with
     * vanilla's flags: no block entity side effects).
     */
    @GameTest(structure = AreaShifterGameTests.STRUCTURE, environment = AreaShifterGameTests.RULE_ON)
    public void aClearedAreaShifterPutsNothingBack(GameTestHelper helper) {
        HiddenLayer hidden = HiddenLayer.of(helper.getLevel());
        BlockPos low = new BlockPos(2, 1, 2);
        BlockPos high = new BlockPos(4, 3, 4);
        for (BlockPos p : BlockPos.betweenClosed(low, high)) {
            helper.setBlock(p.immutable(), Blocks.OAK_PLANKS);
        }
        for (BlockPos c : new BlockPos[] {low, high}) {
            hidden.setBlock(helper.absolutePos(c), PhaseCraftBlocks.AREA_SHIFTER.defaultBlockState().setValue(AreaShifterBlock.DELAY, 0));
        }
        hidden.setBlock(helper.absolutePos(low.west()), Blocks.REDSTONE_BLOCK.defaultBlockState());
        hidden.setBlock(helper.absolutePos(high.east()), Blocks.REDSTONE_BLOCK.defaultBlockState());
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(helper.getBlockState(low).isAir() && helper.getBlockState(high).isAir(),
                        "the box not taken yet"))
                .thenExecute(() -> StructureUtils.clearSpaceForStructure(box(helper, new BlockPos(1, 1, 1), new BlockPos(5, 5, 5)),
                        helper.getLevel()))
                .thenIdle(5)
                .thenExecute(() -> {
                    for (BlockPos p : BlockPos.betweenClosed(low, high)) {
                        helper.assertTrue(helper.getBlockState(p).isAir(), "put back after clearing: " + p.toShortString());
                    }
                    helper.assertTrue(hidden.getBlock(helper.absolutePos(low)).isAir(), "the corner left after clearing");
                })
                .thenSucceed();
    }
}
