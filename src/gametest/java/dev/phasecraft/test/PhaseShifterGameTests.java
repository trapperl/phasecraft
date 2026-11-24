package dev.phasecraft.test;

import dev.phasecraft.PhaseCraftBlocks;
import dev.phasecraft.layer.HiddenLayer;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * The phase shifter (the person's idea, 2026-11-23: "pulling blocks from the
 * main world into the sub world and then putting them back"): a hidden block
 * that, when hidden power reaches it, takes the visible block at its own spot
 * out of the visible world, and puts it back when the power goes. It takes what
 * a piston could push without breaking, nothing else; it waits to put the block
 * back while the spot is taken; nothing is lost or duplicated, and nothing is
 * heard in the visible world.
 */
public class PhaseShifterGameTests {
    private static final BlockPos SPOT = new BlockPos(3, 2, 3);

    private static void floor(GameTestHelper helper) {
        for (int x = 0; x <= 7; x++) {
            for (int z = 0; z <= 7; z++) {
                helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
            }
        }
    }

    private static HiddenLayer hidden(GameTestHelper helper) {
        return HiddenLayer.of(helper.getLevel());
    }

    private static void shifter(GameTestHelper helper, BlockPos rel) {
        hidden(helper).setBlock(helper.absolutePos(rel), PhaseCraftBlocks.PHASE_SHIFTER.defaultBlockState());
    }

    private static void power(GameTestHelper helper, BlockPos rel, boolean on) {
        hidden(helper).setBlock(helper.absolutePos(rel.above()), on ? Blocks.REDSTONE_BLOCK.defaultBlockState() : Blocks.AIR.defaultBlockState());
    }

    /** Powered, the shifter takes the visible block (stairs, facing east) away; unpowered, it puts back exactly that block. */
    @GameTest(maxTicks = 60)
    public void takesTheVisibleBlockAndPutsItBack(GameTestHelper helper) {
        floor(helper);
        BlockState stairs = Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.EAST);
        helper.setBlock(SPOT, stairs);
        shifter(helper, SPOT);
        helper.startSequence()
                .thenIdle(3)
                .thenExecute(() -> helper.assertBlockState(SPOT, stairs))
                .thenExecute(() -> power(helper, SPOT, true))
                .thenWaitUntil(() -> helper.assertBlockPresent(Blocks.AIR, SPOT))
                .thenExecute(() -> helper.assertValueEqual(hidden(helper).getBlock(helper.absolutePos(SPOT)).getBlock(),
                        PhaseCraftBlocks.PHASE_SHIFTER, "hidden block at the spot (the stairs are held, not placed there)"))
                .thenIdle(10)
                .thenExecute(() -> helper.assertBlockPresent(Blocks.AIR, SPOT))
                .thenExecute(() -> power(helper, SPOT, false))
                .thenWaitUntil(() -> helper.assertBlockState(SPOT, stairs))
                .thenSucceed();
    }

    /** What a piston cannot push stays: obsidian, bedrock, a chest. Nothing to take (air) is fine. */
    @GameTest(maxTicks = 40)
    public void leavesWhatAPistonCannotMove(GameTestHelper helper) {
        floor(helper);
        BlockPos[] spots = {SPOT.west(2), SPOT, SPOT.east(2), SPOT.south(2)};
        helper.setBlock(spots[0], Blocks.OBSIDIAN);
        helper.setBlock(spots[1], Blocks.BEDROCK);
        helper.setBlock(spots[2], Blocks.CHEST);
        for (BlockPos spot : spots) {
            shifter(helper, spot);
        }
        helper.startSequence()
                .thenIdle(3)
                .thenExecute(() -> {
                    for (BlockPos spot : spots) {
                        power(helper, spot, true);
                    }
                })
                .thenIdle(20)
                .thenExecute(() -> {
                    helper.assertBlockPresent(Blocks.OBSIDIAN, spots[0]);
                    helper.assertBlockPresent(Blocks.BEDROCK, spots[1]);
                    helper.assertBlockPresent(Blocks.CHEST, spots[2]);
                    helper.assertBlockPresent(Blocks.AIR, spots[3]);
                })
                .thenSucceed();
    }

    /** While the block is away, a stone is placed in its spot: the block waits, and comes back once the spot is free. */
    @GameTest(maxTicks = 100)
    public void waitsWhileTheSpotIsTaken(GameTestHelper helper) {
        floor(helper);
        helper.setBlock(SPOT, Blocks.GOLD_BLOCK);
        shifter(helper, SPOT);
        helper.startSequence()
                .thenExecute(() -> power(helper, SPOT, true))
                .thenWaitUntil(() -> helper.assertBlockPresent(Blocks.AIR, SPOT))
                .thenExecute(() -> helper.setBlock(SPOT, Blocks.STONE))
                .thenExecute(() -> power(helper, SPOT, false))
                .thenIdle(20)
                .thenExecute(() -> helper.assertBlockPresent(Blocks.STONE, SPOT))
                .thenExecute(() -> helper.setBlock(SPOT, Blocks.AIR))
                .thenWaitUntil(() -> helper.assertBlockPresent(Blocks.GOLD_BLOCK, SPOT))
                .thenSucceed();
    }

    /** A pig standing in the spot: the block waits for it to leave rather than trapping it. */
    @GameTest(maxTicks = 100)
    public void waitsForAMobInTheSpot(GameTestHelper helper) {
        floor(helper);
        helper.setBlock(SPOT, Blocks.GOLD_BLOCK);
        shifter(helper, SPOT);
        Pig[] pig = new Pig[1];
        helper.startSequence()
                .thenExecute(() -> power(helper, SPOT, true))
                .thenWaitUntil(() -> helper.assertBlockPresent(Blocks.AIR, SPOT))
                .thenExecute(() -> pig[0] = helper.spawnWithNoFreeWill(EntityTypes.PIG, SPOT))
                .thenExecute(() -> power(helper, SPOT, false))
                .thenIdle(20)
                .thenExecute(() -> helper.assertBlockPresent(Blocks.AIR, SPOT))
                .thenExecute(() -> pig[0].discard())
                .thenWaitUntil(() -> helper.assertBlockPresent(Blocks.GOLD_BLOCK, SPOT))
                .thenSucceed();
    }

    /**
     * The shifter is removed while it holds a block: the block comes back if its spot is free;
     * if not, it drops as an item in the visible world. Never lost, never doubled.
     */
    @GameTest(maxTicks = 80)
    public void removedShifterGivesTheBlockBack(GameTestHelper helper) {
        floor(helper);
        BlockPos other = SPOT.east(3);
        helper.setBlock(SPOT, Blocks.GOLD_BLOCK);
        helper.setBlock(other, Blocks.DIAMOND_BLOCK);
        shifter(helper, SPOT);
        shifter(helper, other);
        helper.startSequence()
                .thenExecute(() -> {
                    power(helper, SPOT, true);
                    power(helper, other, true);
                })
                .thenWaitUntil(() -> {
                    helper.assertBlockPresent(Blocks.AIR, SPOT);
                    helper.assertBlockPresent(Blocks.AIR, other);
                })
                .thenExecute(() -> {
                    helper.setBlock(other, Blocks.STONE);
                    hidden(helper).removeBlock(helper.absolutePos(SPOT));
                    hidden(helper).removeBlock(helper.absolutePos(other));
                })
                .thenWaitUntil(() -> helper.assertBlockPresent(Blocks.GOLD_BLOCK, SPOT))
                .thenWaitUntil(() -> helper.assertFalse(helper.getLevel().getEntitiesOfClass(ItemEntity.class,
                        new AABB(helper.absolutePos(other)).inflate(2), item -> item.getItem().is(Items.DIAMOND_BLOCK)).isEmpty(),
                        "the diamond block did not drop"))
                .thenExecute(() -> helper.assertBlockPresent(Blocks.STONE, other))
                .thenSucceed();
    }

    /** Taking and putting back is silent in the visible world. */
    @GameTest(maxTicks = 60)
    public void silentInTheVisibleWorld(GameTestHelper helper) {
        floor(helper);
        helper.setBlock(SPOT, Blocks.GOLD_BLOCK);
        shifter(helper, SPOT);
        helper.startSequence()
                .thenExecute(() -> power(helper, SPOT, true))
                .thenWaitUntil(() -> helper.assertBlockPresent(Blocks.AIR, SPOT))
                .thenExecute(() -> power(helper, SPOT, false))
                .thenWaitUntil(() -> helper.assertBlockPresent(Blocks.GOLD_BLOCK, SPOT))
                .thenExecute(() -> {
                    var heard = SoundRecorder.in(helper.getLevel().dimension(), new AABB(helper.absolutePos(SPOT)).inflate(2));
                    helper.assertTrue(heard.isEmpty(), "heard in the visible world: " + heard);
                })
                .thenSucceed();
    }
}
