package dev.phasecraft.test;

import dev.phasecraft.PhaseCraftBlocks;
import dev.phasecraft.layer.HiddenLayer;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Phased hoppers, droppers and dispensers (the agent's default, open to
 * change, following the phase block's rule):
 * <ul>
 *   <li>a phased hopper pulls from the visible world above it: the visible
 *       container there, or visible items lying on top of it, and passes them
 *       into hidden machinery as usual;</li>
 *   <li>a phased dropper or dispenser puts out into the visible world in
 *       front of it: into the visible container there, or out into the world
 *       (an item, an arrow, water...), silently.</li>
 * </ul>
 * Not on a phase block, they work in the hidden layer only, as before.
 */
public class PhasedItemGameTests {
    /** The hidden hopper's spot, inside the visible floor; a visible chest or items stand on it. */
    private static final BlockPos HOPPER = new BlockPos(3, 1, 3);
    /** The hidden dropper's or dispenser's spot, above the floor; it faces north. */
    private static final BlockPos SHOOTER = new BlockPos(3, 2, 3);

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

    private static void setHidden(GameTestHelper helper, BlockPos rel, BlockState state) {
        hidden(helper).setBlock(helper.absolutePos(rel), state);
    }

    private static Container hiddenContainer(GameTestHelper helper, BlockPos rel) {
        return (Container) hidden(helper).level().getBlockEntity(helper.absolutePos(rel));
    }

    private static Container visibleContainer(GameTestHelper helper, BlockPos rel) {
        return (Container) helper.getLevel().getBlockEntity(helper.absolutePos(rel));
    }

    private static int count(Container container, Item item) {
        return container.countItem(item);
    }

    private static BlockState support(boolean phased) {
        return phased ? PhaseCraftBlocks.PHASE_BLOCK.defaultBlockState() : Blocks.STONE.defaultBlockState();
    }

    /** A hidden hopper in the floor, on a phase block (or stone) below it, passing east into a hidden chest. */
    private static void hopper(GameTestHelper helper, boolean phased) {
        setHidden(helper, HOPPER.below(), support(phased));
        setHidden(helper, HOPPER.east(), Blocks.CHEST.defaultBlockState());
        setHidden(helper, HOPPER, Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, Direction.EAST));
    }

    /** A visible chest stands on the floor over a phased hopper: its items go down into the hidden chain. */
    @GameTest(maxTicks = 200)
    public void phasedHopperEmptiesVisibleChestAbove(GameTestHelper helper) {
        floor(helper);
        helper.setBlock(HOPPER.above(), Blocks.CHEST);
        visibleContainer(helper, HOPPER.above()).setItem(0, new ItemStack(Items.COBBLESTONE, 5));
        hopper(helper, true);
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertValueEqual(count(hiddenContainer(helper, HOPPER.east()), Items.COBBLESTONE), 5,
                        "cobblestone in the hidden chest"))
                .thenExecute(() -> helper.assertValueEqual(count(visibleContainer(helper, HOPPER.above()), Items.COBBLESTONE), 0,
                        "cobblestone left in the visible chest"))
                .thenSucceed();
    }

    /** Items dropped on the floor over a phased hopper are collected into the hidden chain. */
    @GameTest(maxTicks = 200)
    public void phasedHopperCollectsVisibleItemsOnTop(GameTestHelper helper) {
        floor(helper);
        hopper(helper, true);
        helper.startSequence()
                .thenIdle(2)
                .thenExecute(() -> helper.spawnItem(Items.DIAMOND, Vec3.atBottomCenterOf(HOPPER.above()).add(0, 0.3, 0)))
                .thenWaitUntil(() -> helper.assertValueEqual(count(hiddenContainer(helper, HOPPER.east()), Items.DIAMOND), 1,
                        "diamonds in the hidden chest"))
                .thenExecute(() -> helper.assertTrue(helper.getLevel().getEntitiesOfClass(ItemEntity.class,
                        new AABB(helper.absolutePos(HOPPER)).inflate(3)).isEmpty(), "the diamond is still lying in the visible world"))
                .thenSucceed();
    }

    /** The same hopper on stone is not phased: the visible chest above it keeps its items. */
    @GameTest(maxTicks = 60)
    public void unphasedHopperLeavesVisibleChest(GameTestHelper helper) {
        floor(helper);
        helper.setBlock(HOPPER.above(), Blocks.CHEST);
        visibleContainer(helper, HOPPER.above()).setItem(0, new ItemStack(Items.COBBLESTONE, 5));
        hopper(helper, false);
        helper.startSequence()
                .thenIdle(40)
                .thenExecute(() -> {
                    helper.assertValueEqual(count(visibleContainer(helper, HOPPER.above()), Items.COBBLESTONE), 5, "cobblestone in the visible chest");
                    helper.assertValueEqual(count(hiddenContainer(helper, HOPPER.east()), Items.COBBLESTONE), 0, "cobblestone in the hidden chest");
                })
                .thenSucceed();
    }

    /** A hidden dropper or dispenser at SHOOTER facing north, on a phase block (or stone), holding the stack. */
    private static void shooter(GameTestHelper helper, boolean phased, boolean dispenser, ItemStack stack) {
        setHidden(helper, SHOOTER.below(), support(phased));
        setHidden(helper, SHOOTER, (dispenser ? Blocks.DISPENSER : Blocks.DROPPER).defaultBlockState()
                .setValue(DispenserBlock.FACING, Direction.NORTH));
        hiddenContainer(helper, SHOOTER).setItem(0, stack);
    }

    /** Powers the shooter for a moment (a hidden redstone block beside it, then gone). */
    private static void fire(GameTestHelper helper) {
        setHidden(helper, SHOOTER.east(), Blocks.REDSTONE_BLOCK.defaultBlockState());
    }

    private static <T extends net.minecraft.world.entity.Entity> int entitiesNear(ServerLevel level, GameTestHelper helper, Class<T> type) {
        return level.getEntitiesOfClass(type, new AABB(helper.absolutePos(SHOOTER)).inflate(4)).size();
    }

    /** A phased dropper facing a visible chest drops into it. */
    @GameTest(maxTicks = 60)
    public void phasedDropperFillsVisibleChest(GameTestHelper helper) {
        floor(helper);
        helper.setBlock(SHOOTER.north(), Blocks.CHEST);
        shooter(helper, true, false, new ItemStack(Items.DIAMOND, 3));
        helper.startSequence()
                .thenIdle(2)
                .thenExecute(() -> fire(helper))
                .thenWaitUntil(() -> helper.assertValueEqual(count(visibleContainer(helper, SHOOTER.north()), Items.DIAMOND), 1,
                        "diamonds in the visible chest"))
                .thenExecute(() -> helper.assertValueEqual(count(hiddenContainer(helper, SHOOTER), Items.DIAMOND), 2, "diamonds left in the dropper"))
                .thenSucceed();
    }

    /** With nothing in front, a phased dropper drops the item into the visible world, silently; nothing lands in the hidden layer. */
    @GameTest(maxTicks = 60)
    public void phasedDropperDropsIntoVisibleWorld(GameTestHelper helper) {
        floor(helper);
        shooter(helper, true, false, new ItemStack(Items.DIAMOND, 3));
        helper.startSequence()
                .thenIdle(2)
                .thenExecute(() -> fire(helper))
                .thenWaitUntil(() -> helper.assertValueEqual(entitiesNear(helper.getLevel(), helper, ItemEntity.class), 1, "visible items"))
                .thenExecute(() -> {
                    helper.assertValueEqual(entitiesNear(hidden(helper).level(), helper, ItemEntity.class), 0, "hidden items");
                    var heard = SoundRecorder.in(helper.getLevel().dimension(), new AABB(helper.absolutePos(SHOOTER)).inflate(3));
                    helper.assertTrue(heard.isEmpty(), "heard or seen in the visible world: " + heard);
                })
                .thenSucceed();
    }

    /** The same dropper on stone is not phased: the item stays in the hidden layer. */
    @GameTest(maxTicks = 60)
    public void unphasedDropperDropsIntoHiddenLayer(GameTestHelper helper) {
        floor(helper);
        shooter(helper, false, false, new ItemStack(Items.DIAMOND, 3));
        helper.startSequence()
                .thenIdle(2)
                .thenExecute(() -> fire(helper))
                .thenWaitUntil(() -> helper.assertValueEqual(count(hiddenContainer(helper, SHOOTER), Items.DIAMOND), 2, "diamonds left in the dropper"))
                .thenExecute(() -> helper.assertValueEqual(entitiesNear(helper.getLevel(), helper, ItemEntity.class), 0, "visible items"))
                .thenSucceed();
    }

    /** A phased dispenser shoots arrows into the visible world, silently. */
    @GameTest(maxTicks = 60)
    public void phasedDispenserShootsVisibleArrow(GameTestHelper helper) {
        floor(helper);
        shooter(helper, true, true, new ItemStack(Items.ARROW, 3));
        helper.startSequence()
                .thenIdle(2)
                .thenExecute(() -> fire(helper))
                .thenWaitUntil(() -> helper.assertValueEqual(entitiesNear(helper.getLevel(), helper, AbstractArrow.class), 1, "visible arrows"))
                .thenExecute(() -> {
                    helper.assertValueEqual(entitiesNear(hidden(helper).level(), helper, AbstractArrow.class), 0, "hidden arrows");
                    var heard = SoundRecorder.in(helper.getLevel().dimension(), new AABB(helper.absolutePos(SHOOTER)).inflate(3));
                    helper.assertTrue(heard.isEmpty(), "heard or seen in the visible world: " + heard);
                })
                .thenSucceed();
    }

    /** A phased dispenser empties a water bucket into the visible world; the empty bucket stays in the dispenser. */
    @GameTest(maxTicks = 60)
    public void phasedDispenserPlacesVisibleWater(GameTestHelper helper) {
        floor(helper);
        shooter(helper, true, true, new ItemStack(Items.WATER_BUCKET));
        helper.startSequence()
                .thenIdle(2)
                .thenExecute(() -> fire(helper))
                .thenWaitUntil(() -> helper.assertBlockPresent(Blocks.WATER, SHOOTER.north()))
                .thenExecute(() -> {
                    helper.assertTrue(hidden(helper).getBlock(helper.absolutePos(SHOOTER.north())).isAir(), "water placed in the hidden layer");
                    helper.assertValueEqual(count(hiddenContainer(helper, SHOOTER), Items.BUCKET), 1, "empty buckets in the dispenser");
                })
                .thenSucceed();
    }
}
