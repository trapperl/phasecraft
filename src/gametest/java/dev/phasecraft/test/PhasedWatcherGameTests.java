package dev.phasecraft.test;

import dev.phasecraft.PhaseCraftBlocks;
import dev.phasecraft.layer.HiddenLayer;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ComparatorBlock;
import net.minecraft.world.level.block.CopperBulbBlock;
import net.minecraft.world.level.block.ObserverBlock;
import net.minecraft.world.level.block.TripWireHookBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.ComparatorBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Phase block, third step (the person approved, 2026-11-23): phased blocks that
 * watch the visible world.
 *
 * <ul>
 *   <li>A phased observer watches the visible block in front of it.</li>
 *   <li>A phased comparator reads the visible block behind it (a chest's fill
 *       level, a cake...), also through one visible solid block as vanilla
 *       does. It reads what blocks hold, never visible redstone power: the
 *       phase block carries no power.</li>
 *   <li>Hidden tripwire hooks on phase blocks, with hidden string between
 *       them, are tripped by visible players and mobs at the string's spots.</li>
 * </ul>
 * Without a phase block, each of them reacts to the hidden layer only, as before.
 */
public class PhasedWatcherGameTests {
    /** The hidden watcher's spot. */
    private static final BlockPos AT = new BlockPos(3, 2, 3);

    private static void floor(GameTestHelper helper) {
        for (int x = 0; x <= 8; x++) {
            for (int z = 0; z <= 8; z++) {
                helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
            }
        }
    }

    private static HiddenLayer hidden(GameTestHelper helper) {
        return HiddenLayer.of(helper.getLevel());
    }

    private static void setHidden(GameTestHelper helper, BlockPos rel, BlockState state) {
        hidden(helper).setBlock(helper.absolutePos(rel), state);
    }

    private static BlockState getHidden(GameTestHelper helper, BlockPos rel) {
        return hidden(helper).getBlock(helper.absolutePos(rel));
    }

    private static boolean bulbLit(GameTestHelper helper, BlockPos rel) {
        BlockState bulb = getHidden(helper, rel);
        return bulb.is(Blocks.COPPER_BULB.waxed().unaffected()) && bulb.getValue(CopperBulbBlock.LIT);
    }

    /**
     * A hidden observer at AT facing north, on a phase block (or stone), and a hidden
     * copper bulb behind it: every pulse of the observer toggles the bulb.
     */
    private static void observer(GameTestHelper helper, boolean phased) {
        setHidden(helper, AT.below(), phased ? PhaseCraftBlocks.PHASE_BLOCK.defaultBlockState() : Blocks.STONE.defaultBlockState());
        setHidden(helper, AT, Blocks.OBSERVER.defaultBlockState().setValue(ObserverBlock.FACING, Direction.NORTH));
        setHidden(helper, AT.south(), Blocks.COPPER_BULB.waxed().unaffected().defaultBlockState());
    }

    /** A visible block appears in front of a phased observer: it pulses (and the hidden bulb behind it lights). */
    @GameTest(maxTicks = 60)
    public void phasedObserverSeesVisibleBlockInFront(GameTestHelper helper) {
        floor(helper);
        observer(helper, true);
        helper.startSequence()
                .thenIdle(5)
                .thenExecute(() -> helper.assertFalse(bulbLit(helper, AT.south()), "bulb lit before anything changed"))
                .thenExecute(() -> helper.setBlock(AT.north(), Blocks.OAK_PLANKS))
                .thenWaitUntil(() -> helper.assertTrue(bulbLit(helper, AT.south()), "phased observer did not pulse"))
                .thenWaitUntil(() -> helper.assertFalse(getHidden(helper, AT).getValue(ObserverBlock.POWERED), "observer stuck on"))
                .thenExecute(() -> helper.setBlock(AT.north(), Blocks.AIR))
                .thenWaitUntil(() -> helper.assertFalse(bulbLit(helper, AT.south()), "phased observer missed the block going"))
                .thenSucceed();
    }

    /** A visible change beside the phased observer, or behind it, is not in front of it: no pulse. */
    @GameTest(maxTicks = 60)
    public void phasedObserverWatchesOnlyTheBlockInFront(GameTestHelper helper) {
        floor(helper);
        observer(helper, true);
        helper.startSequence()
                .thenIdle(5)
                .thenExecute(() -> {
                    helper.setBlock(AT.east(), Blocks.OAK_PLANKS);
                    helper.setBlock(AT.west(), Blocks.OAK_PLANKS);
                    helper.setBlock(AT.above(), Blocks.OAK_PLANKS);
                    helper.setBlock(AT.south().south(), Blocks.OAK_PLANKS);
                })
                .thenIdle(10)
                .thenExecute(() -> helper.assertFalse(bulbLit(helper, AT.south()), "observer pulsed for a block not in front of it"))
                .thenSucceed();
    }

    /** A visible block at the phased observer's own spot is not in front of it either. */
    @GameTest(maxTicks = 60)
    public void phasedObserverIgnoresItsOwnSpot(GameTestHelper helper) {
        floor(helper);
        observer(helper, true);
        helper.startSequence()
                .thenIdle(5)
                .thenExecute(() -> helper.setBlock(AT, Blocks.OAK_PLANKS))
                .thenIdle(10)
                .thenExecute(() -> helper.assertFalse(bulbLit(helper, AT.south()), "observer pulsed for the block at its own spot"))
                .thenSucceed();
    }

    /** The same hidden observer on stone is not phased: visible blocks in front of it are not seen. */
    @GameTest(maxTicks = 60)
    public void unphasedObserverIgnoresVisibleBlocks(GameTestHelper helper) {
        floor(helper);
        observer(helper, false);
        helper.startSequence()
                .thenIdle(5)
                .thenExecute(() -> helper.setBlock(AT.north(), Blocks.OAK_PLANKS))
                .thenIdle(10)
                .thenExecute(() -> helper.assertFalse(bulbLit(helper, AT.south()), "unphased observer saw a visible block"))
                .thenSucceed();
    }

    /** A hidden comparator at AT reading north (its back faces north), on a phase block or on stone. */
    private static void comparator(GameTestHelper helper, boolean phased) {
        setHidden(helper, AT.below(), phased ? PhaseCraftBlocks.PHASE_BLOCK.defaultBlockState() : Blocks.STONE.defaultBlockState());
        setHidden(helper, AT, Blocks.COMPARATOR.defaultBlockState().setValue(ComparatorBlock.FACING, Direction.NORTH));
    }

    private static int comparatorOutput(GameTestHelper helper) {
        return hidden(helper).level().getBlockEntity(helper.absolutePos(AT)) instanceof ComparatorBlockEntity entity
                ? entity.getOutputSignal() : -1;
    }

    private static void fillChest(GameTestHelper helper, BlockPos rel, int stacks) {
        ChestBlockEntity chest = helper.getBlockEntity(rel, ChestBlockEntity.class);
        for (int slot = 0; slot < chest.getContainerSize(); slot++) {
            chest.setItem(slot, slot < stacks ? new ItemStack(Items.COBBLESTONE, 64) : ItemStack.EMPTY);
        }
        chest.setChanged();
    }

    /** A phased comparator reads the visible chest behind it as it fills and empties. */
    @GameTest(maxTicks = 80)
    public void phasedComparatorReadsVisibleChest(GameTestHelper helper) {
        floor(helper);
        helper.setBlock(AT.north(), Blocks.CHEST);
        comparator(helper, true);
        helper.startSequence()
                .thenIdle(5)
                .thenExecute(() -> helper.assertValueEqual(comparatorOutput(helper), 0, "output with the chest empty"))
                .thenExecute(() -> fillChest(helper, AT.north(), 27))
                .thenWaitUntil(() -> helper.assertValueEqual(comparatorOutput(helper), 15, "output with the chest full"))
                .thenExecute(() -> helper.assertTrue(getHidden(helper, AT).getValue(ComparatorBlock.POWERED), "comparator not powered"))
                .thenExecute(() -> fillChest(helper, AT.north(), 0))
                .thenWaitUntil(() -> helper.assertValueEqual(comparatorOutput(helper), 0, "output after emptying the chest"))
                .thenSucceed();
    }

    /** As in vanilla, a phased comparator reads through one visible solid block: a cake placed behind a wall. */
    @GameTest(maxTicks = 60)
    public void phasedComparatorReadsThroughVisibleWall(GameTestHelper helper) {
        floor(helper);
        helper.setBlock(AT.north(), Blocks.STONE_BRICKS);
        comparator(helper, true);
        helper.startSequence()
                .thenIdle(5)
                .thenExecute(() -> helper.setBlock(AT.north(2), Blocks.CAKE))
                .thenWaitUntil(() -> helper.assertValueEqual(comparatorOutput(helper), 14, "output reading a full cake through the wall"))
                .thenExecute(() -> helper.setBlock(AT.north(2), Blocks.AIR))
                .thenWaitUntil(() -> helper.assertValueEqual(comparatorOutput(helper), 0, "output after the cake went"))
                .thenSucceed();
    }

    /** The phase block carries no power: a visible redstone block behind a phased comparator gives it nothing. */
    @GameTest(maxTicks = 40)
    public void phasedComparatorIgnoresVisiblePower(GameTestHelper helper) {
        floor(helper);
        comparator(helper, true);
        helper.startSequence()
                .thenIdle(3)
                .thenExecute(() -> helper.setBlock(AT.north(), Blocks.REDSTONE_BLOCK))
                .thenIdle(10)
                .thenExecute(() -> helper.assertValueEqual(comparatorOutput(helper), 0, "output from visible power"))
                .thenSucceed();
    }

    /** The same hidden comparator on stone is not phased: a full visible chest behind it reads nothing. */
    @GameTest(maxTicks = 40)
    public void unphasedComparatorIgnoresVisibleChest(GameTestHelper helper) {
        floor(helper);
        helper.setBlock(AT.north(), Blocks.CHEST);
        comparator(helper, false);
        helper.startSequence()
                .thenIdle(3)
                .thenExecute(() -> fillChest(helper, AT.north(), 27))
                .thenIdle(10)
                .thenExecute(() -> helper.assertValueEqual(comparatorOutput(helper), 0, "unphased comparator read a visible chest"))
                .thenSucceed();
    }

    /**
     * A hidden tripwire along z = 3 from x = 1 to x = 6: hooks at x = 1 (facing east, on the block at x = 0)
     * and x = 6 (facing west, on the block at x = 7), string from x = 2 to x = 5.
     */
    private static void tripwire(GameTestHelper helper, boolean phased) {
        BlockState support = phased ? PhaseCraftBlocks.PHASE_BLOCK.defaultBlockState() : Blocks.STONE.defaultBlockState();
        setHidden(helper, new BlockPos(0, 2, 3), support);
        setHidden(helper, new BlockPos(7, 2, 3), support);
        setHidden(helper, new BlockPos(1, 2, 3), Blocks.TRIPWIRE_HOOK.defaultBlockState().setValue(TripWireHookBlock.FACING, Direction.EAST));
        setHidden(helper, new BlockPos(6, 2, 3), Blocks.TRIPWIRE_HOOK.defaultBlockState().setValue(TripWireHookBlock.FACING, Direction.WEST));
        for (int x = 2; x <= 5; x++) {
            setHidden(helper, new BlockPos(x, 2, 3), Blocks.TRIPWIRE.defaultBlockState());
        }
    }

    private static boolean hookPowered(GameTestHelper helper, BlockPos rel) {
        BlockState hook = getHidden(helper, rel);
        return hook.is(Blocks.TRIPWIRE_HOOK) && hook.getValue(TripWireHookBlock.POWERED);
    }

    /** A visible pig walks into a phased string's spot: the hidden hooks click on, and off once it is gone. */
    @GameTest(maxTicks = 100)
    public void visibleMobTripsPhasedTripwire(GameTestHelper helper) {
        floor(helper);
        tripwire(helper, true);
        Pig[] pig = new Pig[1];
        helper.startSequence()
                .thenIdle(5)
                .thenExecute(() -> helper.assertTrue(getHidden(helper, new BlockPos(1, 2, 3)).getValue(TripWireHookBlock.ATTACHED),
                        "hidden hooks not attached to the string"))
                .thenExecute(() -> helper.assertFalse(hookPowered(helper, new BlockPos(1, 2, 3)), "tripped before anyone came"))
                .thenExecute(() -> pig[0] = helper.spawnWithNoFreeWill(EntityTypes.PIG, new BlockPos(4, 2, 3)))
                .thenWaitUntil(() -> {
                    helper.assertTrue(hookPowered(helper, new BlockPos(1, 2, 3)), "west hook not tripped");
                    helper.assertTrue(hookPowered(helper, new BlockPos(6, 2, 3)), "east hook not tripped");
                })
                .thenIdle(15)
                .thenExecute(() -> helper.assertTrue(hookPowered(helper, new BlockPos(1, 2, 3)), "released while the pig still stands there"))
                .thenExecute(() -> pig[0].discard())
                .thenWaitUntil(() -> helper.assertFalse(hookPowered(helper, new BlockPos(1, 2, 3)), "hooks never released"))
                .thenSucceed();
    }

    /** The same hidden tripwire with its hooks on stone is not phased: a visible pig does not trip it. */
    @GameTest(maxTicks = 60)
    public void unphasedTripwireIgnoresVisibleMobs(GameTestHelper helper) {
        floor(helper);
        tripwire(helper, false);
        helper.startSequence()
                .thenIdle(5)
                .thenExecute(() -> helper.spawnWithNoFreeWill(EntityTypes.PIG, new BlockPos(4, 2, 3)))
                .thenIdle(20)
                .thenExecute(() -> helper.assertFalse(hookPowered(helper, new BlockPos(1, 2, 3)), "unphased tripwire tripped by a visible pig"))
                .thenSucceed();
    }
}
