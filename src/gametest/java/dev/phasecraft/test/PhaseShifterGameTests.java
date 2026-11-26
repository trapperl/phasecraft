package dev.phasecraft.test;

import dev.phasecraft.PhaseCraftBlocks;
import dev.phasecraft.layer.HiddenLayer;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.network.chat.Component;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * The phase shifter (the person's idea, 2026-11-23: "pulling blocks from the
 * main world into the sub world and then putting them back"): a hidden block
 * that, when hidden power reaches it, takes the visible block at its own spot
 * out of the visible world, and puts it back when the power goes. It takes what
 * a piston could push without breaking (block entities allowed), nothing else;
 * putting the block back breaks a block in its spot (an unbreakable one, or a
 * mob there, makes it wait); nothing is lost or duplicated, and nothing is
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

    /**
     * A shifter passes no power on, as a piston does not (the person, 2026-11-25: "Let's make the change, I think it
     * opens up more building opportunities"): in a 3x3 wall of shifters, a repeater pointing into the middle one
     * takes only the middle block, so any free side of a wall block can feed it.
     */
    @GameTest(maxTicks = 60)
    public void aPoweredShifterPowersNoOtherShifter(GameTestHelper helper) {
        floor(helper);
        BlockPos middle = new BlockPos(3, 3, 3);
        java.util.List<BlockPos> wall = new java.util.ArrayList<>();
        for (int x = 2; x <= 4; x++) {
            for (int y = 2; y <= 4; y++) {
                BlockPos at = new BlockPos(x, y, 3);
                wall.add(at);
                helper.setBlock(at, Blocks.STONE_BRICKS);
                shifter(helper, at);
            }
        }
        BlockPos repeater = middle.south();
        hidden(helper).setBlock(helper.absolutePos(repeater.below()), Blocks.GLASS.defaultBlockState());
        helper.startSequence()
                .thenIdle(3)
                .thenExecute(() -> {
                    hidden(helper).setBlock(helper.absolutePos(repeater), Blocks.REPEATER.defaultBlockState()
                            .setValue(net.minecraft.world.level.block.RepeaterBlock.FACING, Direction.SOUTH));
                    hidden(helper).setBlock(helper.absolutePos(repeater.south()), Blocks.REDSTONE_BLOCK.defaultBlockState());
                })
                .thenWaitUntil(() -> helper.assertBlockPresent(Blocks.AIR, middle))
                .thenIdle(10)
                .thenExecute(() -> {
                    for (BlockPos at : wall) {
                        if (!at.equals(middle)) {
                            helper.assertBlockPresent(Blocks.STONE_BRICKS, at);
                        }
                    }
                })
                .thenSucceed();
    }

    /** What a piston cannot push, or would break, stays: obsidian, bedrock, a torch. Nothing to take (air) is fine. */
    @GameTest(maxTicks = 40)
    public void leavesWhatAPistonCannotMove(GameTestHelper helper) {
        floor(helper);
        BlockPos[] spots = {SPOT.west(2), SPOT, SPOT.east(2), SPOT.south(2)};
        helper.setBlock(spots[0], Blocks.OBSIDIAN);
        helper.setBlock(spots[1], Blocks.BEDROCK);
        helper.setBlock(spots[2], Blocks.TORCH);
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
                    helper.assertBlockPresent(Blocks.TORCH, spots[2]);
                    helper.assertBlockPresent(Blocks.AIR, spots[3]);
                })
                .thenSucceed();
    }

    /**
     * While the block is away, a stone is placed in its spot: when the block comes back it breaks the stone
     * (the person, 2026-11-24: "I think it should break the main world block"), which drops as if broken
     * with no tool, silently.
     */
    @GameTest(maxTicks = 60)
    public void breaksTheBlockInTheSpot(GameTestHelper helper) {
        floor(helper);
        helper.setBlock(SPOT, Blocks.GOLD_BLOCK);
        shifter(helper, SPOT);
        helper.startSequence()
                .thenExecute(() -> power(helper, SPOT, true))
                .thenWaitUntil(() -> helper.assertBlockPresent(Blocks.AIR, SPOT))
                .thenExecute(() -> helper.setBlock(SPOT, Blocks.STONE))
                .thenIdle(2)
                .thenExecute(() -> power(helper, SPOT, false))
                .thenWaitUntil(() -> helper.assertBlockPresent(Blocks.GOLD_BLOCK, SPOT))
                .thenWaitUntil(() -> helper.assertFalse(helper.getLevel().getEntitiesOfClass(ItemEntity.class,
                        new AABB(helper.absolutePos(SPOT)).inflate(2), item -> item.getItem().is(Items.COBBLESTONE)).isEmpty(),
                        "the broken stone dropped nothing"))
                .thenExecute(() -> {
                    var heard = SoundRecorder.in(helper.getLevel().dimension(), new AABB(helper.absolutePos(SPOT)).inflate(2));
                    helper.assertTrue(heard.isEmpty(), "heard or seen in the visible world: " + heard);
                })
                .thenSucceed();
    }

    /** A chest full of diamonds in the spot is broken like any block: the chest and its diamonds drop. */
    @GameTest(maxTicks = 60)
    public void breaksAChestInTheSpotSpillingItsContents(GameTestHelper helper) {
        floor(helper);
        helper.setBlock(SPOT, Blocks.GOLD_BLOCK);
        shifter(helper, SPOT);
        helper.startSequence()
                .thenExecute(() -> power(helper, SPOT, true))
                .thenWaitUntil(() -> helper.assertBlockPresent(Blocks.AIR, SPOT))
                .thenExecute(() -> {
                    helper.setBlock(SPOT, Blocks.CHEST);
                    helper.getBlockEntity(SPOT, ChestBlockEntity.class).setItem(0, new ItemStack(Items.DIAMOND, 4));
                })
                .thenIdle(2)
                .thenExecute(() -> power(helper, SPOT, false))
                .thenWaitUntil(() -> helper.assertBlockPresent(Blocks.GOLD_BLOCK, SPOT))
                .thenWaitUntil(() -> {
                    var items = helper.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(helper.absolutePos(SPOT)).inflate(2));
                    helper.assertTrue(items.stream().anyMatch(item -> item.getItem().is(Items.CHEST)), "the chest did not drop");
                    helper.assertValueEqual(items.stream().filter(item -> item.getItem().is(Items.DIAMOND))
                            .mapToInt(item -> item.getItem().getCount()).sum(), 4, "diamonds dropped");
                })
                .thenSucceed();
    }

    /** Bedrock (nothing breaks it) in the spot: the block waits, and comes back once the spot is free. */
    @GameTest(maxTicks = 100)
    public void waitsForAnUnbreakableBlockInTheSpot(GameTestHelper helper) {
        floor(helper);
        helper.setBlock(SPOT, Blocks.GOLD_BLOCK);
        shifter(helper, SPOT);
        helper.startSequence()
                .thenExecute(() -> power(helper, SPOT, true))
                .thenWaitUntil(() -> helper.assertBlockPresent(Blocks.AIR, SPOT))
                .thenExecute(() -> helper.setBlock(SPOT, Blocks.BEDROCK))
                .thenExecute(() -> power(helper, SPOT, false))
                .thenIdle(20)
                .thenExecute(() -> helper.assertBlockPresent(Blocks.BEDROCK, SPOT))
                .thenExecute(() -> helper.setBlock(SPOT, Blocks.AIR))
                .thenWaitUntil(() -> helper.assertBlockPresent(Blocks.GOLD_BLOCK, SPOT))
                .thenSucceed();
    }

    /**
     * A pig standing in the spot when the block comes back is lifted to stand on it, as a piston pushing up would
     * move it (the person, 2026-11-26, choosing this over waiting).
     */
    @GameTest(maxTicks = 100)
    public void liftsAMobInTheSpotOntoTheBlock(GameTestHelper helper) {
        floor(helper);
        helper.setBlock(SPOT, Blocks.GOLD_BLOCK);
        shifter(helper, SPOT);
        Pig[] pig = new Pig[1];
        helper.startSequence()
                .thenExecute(() -> power(helper, SPOT, true))
                .thenWaitUntil(() -> helper.assertBlockPresent(Blocks.AIR, SPOT))
                .thenExecute(() -> pig[0] = helper.spawnWithNoFreeWill(EntityTypes.PIG, SPOT))
                .thenExecute(() -> power(helper, SPOT, false))
                .thenWaitUntil(() -> helper.assertBlockPresent(Blocks.GOLD_BLOCK, SPOT))
                .thenExecute(() -> {
                    helper.assertValueEqual(pig[0].getY(), (double) helper.absolutePos(SPOT).getY() + 1, "the pig's feet");
                    helper.assertTrue(pig[0].isAlive(), "the pig died");
                })
                .thenSucceed();
    }

    /** No room above the pig (a ceiling right over it): the block waits, and comes back once there is room. */
    @GameTest(maxTicks = 100)
    public void waitsForAMobWithNoRoomAbove(GameTestHelper helper) {
        floor(helper);
        helper.setBlock(SPOT, Blocks.GOLD_BLOCK);
        shifter(helper, SPOT);
        Pig[] pig = new Pig[1];
        helper.startSequence()
                .thenExecute(() -> power(helper, SPOT, true))
                .thenWaitUntil(() -> helper.assertBlockPresent(Blocks.AIR, SPOT))
                .thenExecute(() -> {
                    helper.setBlock(SPOT.above(), Blocks.STONE);
                    pig[0] = helper.spawnWithNoFreeWill(EntityTypes.PIG, SPOT);
                    power(helper, SPOT, false);
                })
                .thenIdle(20)
                .thenExecute(() -> {
                    helper.assertBlockPresent(Blocks.AIR, SPOT);
                    helper.assertValueEqual(pig[0].getY(), (double) helper.absolutePos(SPOT).getY(), "the pig's feet");
                    helper.setBlock(SPOT.above(), Blocks.AIR);
                })
                .thenWaitUntil(() -> helper.assertBlockPresent(Blocks.GOLD_BLOCK, SPOT))
                .thenExecute(() -> helper.assertValueEqual(pig[0].getY(), (double) helper.absolutePos(SPOT).getY() + 1,
                        "the pig's feet"))
                .thenSucceed();
    }

    /**
     * An iron golem standing below the spot, its head in it: it is not lifted through the block; the block waits
     * until it has gone.
     */
    @GameTest(maxTicks = 100)
    public void neverLiftsAMobThroughTheBlock(GameTestHelper helper) {
        floor(helper);
        BlockPos high = SPOT.above();
        helper.setBlock(high, Blocks.GOLD_BLOCK);
        shifter(helper, high);
        IronGolem[] golem = new IronGolem[1];
        helper.startSequence()
                .thenExecute(() -> power(helper, high, true))
                .thenWaitUntil(() -> helper.assertBlockPresent(Blocks.AIR, high))
                .thenExecute(() -> {
                    golem[0] = helper.spawnWithNoFreeWill(EntityTypes.IRON_GOLEM, SPOT);
                    power(helper, high, false);
                })
                .thenIdle(20)
                .thenExecute(() -> {
                    helper.assertBlockPresent(Blocks.AIR, high);
                    helper.assertValueEqual(golem[0].getY(), (double) helper.absolutePos(SPOT).getY(), "the golem's feet");
                    golem[0].discard();
                })
                .thenWaitUntil(() -> helper.assertBlockPresent(Blocks.GOLD_BLOCK, high))
                .thenSucceed();
    }

    /**
     * The shifter is removed while it holds a block: the block comes back if its spot is free;
     * if something unbreakable holds it, it drops as an item in the visible world. Never lost, never doubled.
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
                    helper.setBlock(other, Blocks.BEDROCK);
                    hidden(helper).removeBlock(helper.absolutePos(SPOT));
                    hidden(helper).removeBlock(helper.absolutePos(other));
                })
                .thenWaitUntil(() -> helper.assertBlockPresent(Blocks.GOLD_BLOCK, SPOT))
                .thenWaitUntil(() -> helper.assertFalse(helper.getLevel().getEntitiesOfClass(ItemEntity.class,
                        new AABB(helper.absolutePos(other)).inflate(2), item -> item.getItem().is(Items.DIAMOND_BLOCK)).isEmpty(),
                        "the diamond block did not drop"))
                .thenExecute(() -> helper.assertBlockPresent(Blocks.BEDROCK, other))
                .thenSucceed();
    }

    /**
     * A chest goes with everything in it, and its name: nothing spills while it is away,
     * and it comes back exactly as it was (the person's list, 2026-11-24: "the shifter taking chests").
     */
    @GameTest(maxTicks = 60)
    public void takesAChestWithItsContents(GameTestHelper helper) {
        floor(helper);
        helper.setBlock(SPOT, Blocks.CHEST);
        ChestBlockEntity chest = helper.getBlockEntity(SPOT, ChestBlockEntity.class);
        // Named as an anvil-named chest is when placed (the name lives in the chest's own data). Before
        // filling it: applying components also sets the chest's items from them.
        chest.applyComponents(DataComponentMap.builder().set(DataComponents.CUSTOM_NAME, Component.literal("Loot")).build(),
                DataComponentPatch.EMPTY);
        chest.setItem(3, new ItemStack(Items.DIAMOND, 5));
        shifter(helper, SPOT);
        helper.startSequence()
                .thenIdle(2)
                .thenExecute(() -> power(helper, SPOT, true))
                .thenWaitUntil(() -> helper.assertBlockPresent(Blocks.AIR, SPOT))
                .thenIdle(5)
                .thenExecute(() -> helper.assertTrue(helper.getLevel().getEntitiesOfClass(ItemEntity.class,
                        new AABB(helper.absolutePos(SPOT)).inflate(3)).isEmpty(), "something spilled when the chest was taken"))
                .thenExecute(() -> power(helper, SPOT, false))
                .thenWaitUntil(() -> helper.assertBlockPresent(Blocks.CHEST, SPOT))
                .thenExecute(() -> {
                    ChestBlockEntity back = helper.getBlockEntity(SPOT, ChestBlockEntity.class);
                    helper.assertTrue(back.getItem(3).is(Items.DIAMOND) && back.getItem(3).getCount() == 5,
                            "the diamonds did not come back: " + back.getItem(3));
                    helper.assertValueEqual(back.getCustomName(), Component.literal("Loot"), "the chest's name");
                })
                .thenSucceed();
    }

    /** One half of a double chest goes and comes back; the two halves join up again (or it stands alone if the other half is gone). */
    @GameTest(maxTicks = 80)
    public void doubleChestHalfComesBackJoined(GameTestHelper helper) {
        floor(helper);
        BlockState left = Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH).setValue(ChestBlock.TYPE, ChestType.LEFT);
        BlockState right = left.setValue(ChestBlock.TYPE, ChestType.RIGHT);
        helper.setBlock(SPOT, left);
        helper.setBlock(SPOT.east(), right);
        helper.getBlockEntity(SPOT, ChestBlockEntity.class).setItem(0, new ItemStack(Items.EMERALD, 2));
        shifter(helper, SPOT);
        helper.startSequence()
                .thenIdle(2)
                .thenExecute(() -> power(helper, SPOT, true))
                .thenWaitUntil(() -> helper.assertBlockPresent(Blocks.AIR, SPOT))
                .thenExecute(() -> helper.assertBlockProperty(SPOT.east(), ChestBlock.TYPE, ChestType.SINGLE))
                .thenExecute(() -> power(helper, SPOT, false))
                .thenWaitUntil(() -> helper.assertBlockPresent(Blocks.CHEST, SPOT))
                .thenExecute(() -> {
                    helper.assertBlockProperty(SPOT, ChestBlock.TYPE, ChestType.LEFT);
                    helper.assertBlockProperty(SPOT.east(), ChestBlock.TYPE, ChestType.RIGHT);
                    helper.assertValueEqual(helper.getBlockEntity(SPOT, ChestBlockEntity.class).getItem(0).getCount(), 2, "emeralds back");
                })
                // Again, but the other half is gone by the time it comes back: it stands alone.
                .thenExecute(() -> power(helper, SPOT, true))
                .thenWaitUntil(() -> helper.assertBlockPresent(Blocks.AIR, SPOT))
                .thenExecute(() -> helper.setBlock(SPOT.east(), Blocks.AIR))
                .thenExecute(() -> power(helper, SPOT, false))
                .thenWaitUntil(() -> helper.assertBlockPresent(Blocks.CHEST, SPOT))
                .thenExecute(() -> helper.assertBlockProperty(SPOT, ChestBlock.TYPE, ChestType.SINGLE))
                .thenSucceed();
    }

    /** Removed while holding a chest whose spot holds bedrock: the chest and everything in it drop as items. */
    @GameTest(maxTicks = 60)
    public void removedShifterDropsChestAndContents(GameTestHelper helper) {
        floor(helper);
        helper.setBlock(SPOT, Blocks.CHEST);
        helper.getBlockEntity(SPOT, ChestBlockEntity.class).setItem(0, new ItemStack(Items.DIAMOND, 4));
        shifter(helper, SPOT);
        helper.startSequence()
                .thenIdle(2)
                .thenExecute(() -> power(helper, SPOT, true))
                .thenWaitUntil(() -> helper.assertBlockPresent(Blocks.AIR, SPOT))
                .thenExecute(() -> {
                    helper.setBlock(SPOT, Blocks.BEDROCK);
                    hidden(helper).removeBlock(helper.absolutePos(SPOT));
                })
                .thenWaitUntil(() -> {
                    var items = helper.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(helper.absolutePos(SPOT)).inflate(2));
                    helper.assertTrue(items.stream().anyMatch(item -> item.getItem().is(Items.CHEST)), "the chest did not drop");
                    helper.assertValueEqual(items.stream().filter(item -> item.getItem().is(Items.DIAMOND))
                            .mapToInt(item -> item.getItem().getCount()).sum(), 4, "diamonds dropped");
                })
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
