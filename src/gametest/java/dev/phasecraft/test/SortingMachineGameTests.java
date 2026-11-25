package dev.phasecraft.test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.phys.AABB;

/**
 * The sorting machine (the person's showcase list, 2026-11-24: "a sorting machine"): items put in a chest on top
 * of a wall come out sorted into the chests below it, one kind per chest (cobblestone, dirt, oak logs, iron
 * ingots), everything else in the last one. Only the wall and the chests are visible: a hidden hopper item sorter
 * fed by a phased hopper, emptied by phased droppers (layout: contraptions/sorting_machine.txt, written by
 * tools/gen_sorter.py).
 */
public class SortingMachineGameTests {
    private static final List<Item> KINDS = List.of(Items.COBBLESTONE, Items.DIRT, Items.OAK_LOG, Items.IRON_INGOT);
    /** Game ticks to settle after building: the locks come on before the filters are filled. */
    private static final int SETTLE = 20;
    private static ContraptionLayout layout;

    static ContraptionLayout layout() {
        if (layout == null) {
            layout = ContraptionLayout.load("sorting_machine");
        }
        return layout;
    }

    /** The visible input chest (on the wall's top) is the highest chest; the outputs are the rest, west to east. */
    private static BlockPos input() {
        return layout().visible(Blocks.CHEST).stream().max(Comparator.comparingInt(BlockPos::getY)).orElseThrow();
    }

    private static List<BlockPos> outputs() {
        BlockPos in = input();
        return layout().visible(Blocks.CHEST).stream().filter(p -> !p.equals(in))
                .sorted(Comparator.comparingInt(BlockPos::getX)).toList();
    }

    private static Container visible(GameTestHelper helper, BlockPos rel) {
        return (Container) helper.getLevel().getBlockEntity(helper.absolutePos(rel));
    }

    private static Container hidden(GameTestHelper helper, BlockPos rel) {
        return (Container) dev.phasecraft.layer.HiddenLayer.of(helper.getLevel()).level().getBlockEntity(helper.absolutePos(rel));
    }

    /** What a container holds, by item, in a stable order (for messages). */
    private static Map<Item, Integer> held(Container c) {
        Map<Item, Integer> out = new LinkedHashMap<>();
        for (int i = 0; i < c.getContainerSize(); i++) {
            ItemStack s = c.getItem(i);
            if (!s.isEmpty()) {
                out.merge(s.getItem(), s.getCount(), Integer::sum);
            }
        }
        return out;
    }

    private static void put(Container c, Item item, int count) {
        for (int i = 0; i < c.getContainerSize() && count > 0; i++) {
            if (c.getItem(i).isEmpty()) {
                int n = Math.min(count, item.getDefaultMaxStackSize());
                c.setItem(i, new ItemStack(item, n));
                count -= n;
            }
        }
        c.setChanged();
    }

    /** Builds the machine, lets it settle (every gate locked) and fills the filters. */
    private static void build(GameTestHelper helper) {
        layout().build(helper);
        helper.runAfterDelay(SETTLE, () -> {
            for (BlockPos gate : gates()) {
                helper.assertFalse(hiddenState(helper, gate).getValue(HopperBlock.ENABLED), "gate at " + gate.toShortString()
                        + " is not locked after settling");
            }
            layout().fillContainers(helper);
        });
    }

    private static net.minecraft.world.level.block.state.BlockState hiddenState(GameTestHelper helper, BlockPos rel) {
        return dev.phasecraft.layer.HiddenLayer.of(helper.getLevel()).level().getBlockState(helper.absolutePos(rel));
    }

    /** The gates: the hidden hoppers right under the filters. */
    private static List<BlockPos> gates() {
        return layout().filledHidden().stream().map(BlockPos::below).toList();
    }

    /** Every hidden hopper and dropper but the filters is empty; each filter holds its 18 and 4 sticks, as built. */
    private static void assertNothingLeftInside(GameTestHelper helper) {
        List<BlockPos> filters = layout().filledHidden();
        List<BlockPos> holders = new ArrayList<>(layout().hidden(Blocks.HOPPER));
        holders.addAll(layout().hidden(Blocks.DROPPER));
        for (BlockPos p : holders) {
            Map<Item, Integer> in = held(hidden(helper, p));
            if (filters.contains(p)) {
                Item kind = KINDS.get(filters.indexOf(p));
                helper.assertValueEqual(in, Map.of(kind, 18, Items.STICK, 4), "filter at " + p.toShortString());
            } else {
                helper.assertTrue(in.isEmpty(), "left inside the hidden " + hiddenState(helper, p).getBlock().getName().getString()
                        + " at " + p.toShortString() + ": " + in);
            }
        }
        helper.assertTrue(helper.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(helper.absolutePos(BlockPos.ZERO))
                .inflate(24)).isEmpty(), "items lying in the visible world");
    }

    /** A mixed load comes out sorted: each kind in its chest, everything else in the last one; nothing stays inside. */
    @GameTest(structure = "phasecraft-test:sorting_machine", maxTicks = 1400)
    public void sortsEachKindIntoItsChest(GameTestHelper helper) {
        build(helper);
        List<BlockPos> out = outputs();
        helper.startSequence()
                .thenIdle(SETTLE + 2)
                .thenExecute(() -> {
                    Container in = visible(helper, input());
                    put(in, Items.COBBLESTONE, 20);
                    put(in, Items.FLINT, 4);
                    put(in, Items.DIRT, 9);
                    put(in, Items.OAK_LOG, 5);
                    put(in, Items.DIAMOND, 2);
                    put(in, Items.IRON_INGOT, 3);
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(held(visible(helper, out.get(0))), Map.of(Items.COBBLESTONE, 20), "cobblestone chest");
                    helper.assertValueEqual(held(visible(helper, out.get(1))), Map.of(Items.DIRT, 9), "dirt chest");
                    helper.assertValueEqual(held(visible(helper, out.get(2))), Map.of(Items.OAK_LOG, 5), "oak log chest");
                    helper.assertValueEqual(held(visible(helper, out.get(3))), Map.of(Items.IRON_INGOT, 3), "iron chest");
                    helper.assertValueEqual(held(visible(helper, out.get(4))), Map.of(Items.FLINT, 4, Items.DIAMOND, 2),
                            "everything else chest");
                })
                .thenExecute(() -> {
                    helper.assertTrue(held(visible(helper, input())).isEmpty(), "left in the input chest");
                    assertNothingLeftInside(helper);
                })
                .thenSucceed();
    }

    /** A full stack of one kind goes through (64 lock toggles in 30 seconds: no torch to burn out). */
    @GameTest(structure = "phasecraft-test:sorting_machine", maxTicks = 1400)
    public void aFullStackGoesThrough(GameTestHelper helper) {
        build(helper);
        List<BlockPos> out = outputs();
        helper.startSequence()
                .thenIdle(SETTLE + 2)
                .thenExecute(() -> put(visible(helper, input()), Items.IRON_INGOT, 64))
                .thenWaitUntil(() -> helper.assertValueEqual(held(visible(helper, out.get(3))), Map.of(Items.IRON_INGOT, 64),
                        "iron chest"))
                .thenExecute(() -> {
                    for (int i = 0; i < out.size(); i++) {
                        if (i != 3) {
                            helper.assertTrue(held(visible(helper, out.get(i))).isEmpty(), "chest " + i + " got something");
                        }
                    }
                    assertNothingLeftInside(helper);
                })
                .thenSucceed();
    }

    /** While a chest is full its items wait inside (nothing dropped or lost); emptied, it gets them. */
    @GameTest(structure = "phasecraft-test:sorting_machine", maxTicks = 1400)
    public void itemsWaitWhileTheChestIsFull(GameTestHelper helper) {
        build(helper);
        List<BlockPos> out = outputs();
        helper.startSequence()
                .thenIdle(SETTLE + 2)
                .thenExecute(() -> {
                    put(visible(helper, out.get(1)), Items.DIRT, 27 * 64);
                    put(visible(helper, input()), Items.DIRT, 6);
                })
                .thenIdle(300)
                .thenExecute(() -> {
                    helper.assertValueEqual(held(visible(helper, out.get(1))), Map.of(Items.DIRT, 27 * 64), "dirt chest");
                    helper.assertTrue(held(visible(helper, out.get(4))).isEmpty(), "the dirt went to the last chest");
                    helper.assertTrue(helper.getLevel().getEntitiesOfClass(ItemEntity.class,
                            new AABB(helper.absolutePos(BlockPos.ZERO)).inflate(24)).isEmpty(), "items lying in the visible world");
                    visible(helper, out.get(1)).clearContent();
                })
                .thenWaitUntil(() -> helper.assertValueEqual(held(visible(helper, out.get(1))), Map.of(Items.DIRT, 6), "dirt chest"))
                .thenExecute(() -> assertNothingLeftInside(helper))
                .thenSucceed();
    }
}
