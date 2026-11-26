package dev.phasecraft.test;

import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;

/**
 * The treasure reveal (layout: contraptions/treasure_reveal.txt, written by tools/gen_treasure.py; examples station
 * 34): put anything in the altar's barrel and the treasure appears on the pedestals behind it, a gold, a diamond and
 * an emerald block and two sea lanterns; take it out and the treasure goes. A hidden comparator on a phase block
 * reads the barrel; hidden phase shifters hold the treasure while it is empty.
 */
public class TreasureRevealGameTests {
    static final BlockPos BARREL = new BlockPos(9, 1, 4);
    static final Map<BlockPos, Block> TREASURE = Map.of(
            new BlockPos(6, 2, 12), Blocks.GOLD_BLOCK, new BlockPos(9, 2, 13), Blocks.DIAMOND_BLOCK,
            new BlockPos(12, 2, 12), Blocks.EMERALD_BLOCK, new BlockPos(4, 3, 10), Blocks.SEA_LANTERN,
            new BlockPos(14, 3, 10), Blocks.SEA_LANTERN);
    private static ContraptionLayout layout;

    static ContraptionLayout layout() {
        if (layout == null) {
            layout = ContraptionLayout.load("treasure_reveal");
        }
        return layout;
    }

    /** "there", "gone", or which blocks are where. */
    static String treasure(GameTestHelper helper) {
        boolean there = TREASURE.entrySet().stream().allMatch(e -> helper.getBlockState(e.getKey()).is(e.getValue()));
        boolean gone = TREASURE.keySet().stream().allMatch(p -> helper.getBlockState(p).isAir());
        return there ? "there" : gone ? "gone" : "mixed: " + TREASURE.keySet().stream()
                .map(p -> p.toShortString() + "=" + helper.getBlockState(p).getBlock().getName().getString()).toList();
    }

    private static void offer(GameTestHelper helper, ItemStack stack) {
        BarrelBlockEntity barrel = helper.getBlockEntity(BARREL, BarrelBlockEntity.class);
        barrel.setItem(13, stack);
        barrel.setChanged();
    }

    /** Hidden while the barrel is empty; there with one gold nugget in it; gone again when it is taken out; twice. */
    @GameTest(structure = "phasecraft-test:treasure_reveal", maxTicks = 400)
    public void anOfferingRevealsTheTreasure(GameTestHelper helper) {
        layout().build(helper);
        var sequence = helper.startSequence()
                .thenWaitUntil(() -> helper.assertValueEqual(treasure(helper), "gone", "the treasure with the barrel empty"))
                .thenIdle(20)
                .thenExecute(() -> helper.assertValueEqual(treasure(helper), "gone", "the treasure, settled"));
        for (int round = 0; round < 2; round++) {
            sequence = sequence
                    .thenExecute(() -> offer(helper, new ItemStack(Items.GOLD_NUGGET)))
                    .thenWaitUntil(() -> helper.assertValueEqual(treasure(helper), "there", "the treasure after an offering"))
                    .thenIdle(20)
                    .thenExecute(() -> helper.assertValueEqual(treasure(helper), "there", "the treasure, offering still in"))
                    .thenExecute(() -> offer(helper, ItemStack.EMPTY))
                    .thenWaitUntil(() -> helper.assertValueEqual(treasure(helper), "gone", "the treasure once the offering is out"));
        }
        sequence.thenExecute(() -> {
            for (BlockPos p : List.copyOf(TREASURE.keySet())) {
                helper.assertTrue(helper.getBlockState(p.below()).isSolid(), "the pedestal under " + p.toShortString());
            }
        }).thenSucceed();
    }
}
