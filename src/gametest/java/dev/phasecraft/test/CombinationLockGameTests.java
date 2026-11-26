package dev.phasecraft.test;

import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

/**
 * The combination lock (layout: contraptions/combination_lock.txt, written by tools/gen_lock.py; examples station 32):
 * four levers on a stone-brick wall, and a doorway in it that looks like the rest of the wall. Only on, off, on, on
 * (west to east) opens it onto the vault behind; every other setting keeps it, or puts it back, shut. Nothing but
 * the levers shows: phase ports bring their power into the hidden layer, where torches check it and phase shifters
 * take the door's blocks.
 */
public class CombinationLockGameTests {
    static final String STRUCTURE = "phasecraft-test:combination_lock";
    static final int[] LEVERS = {1, 4, 11, 14};
    static final boolean[] COMBINATION = {true, false, true, true};
    static final int WALL_Z = 13;
    static final List<BlockPos> DOOR = List.of(new BlockPos(8, 1, WALL_Z), new BlockPos(8, 2, WALL_Z));
    static final BlockPos CHEST = new BlockPos(8, 1, WALL_Z - 4);
    private static ContraptionLayout layout;

    static ContraptionLayout layout() {
        if (layout == null) {
            layout = ContraptionLayout.load("combination_lock");
        }
        return layout;
    }

    static BlockPos lever(int i) {
        return new BlockPos(LEVERS[i], 2, WALL_Z + 1);
    }

    static String door(GameTestHelper helper) {
        boolean shut = DOOR.stream().allMatch(p -> helper.getBlockState(p).is(Blocks.STONE_BRICKS));
        boolean open = DOOR.stream().allMatch(p -> helper.getBlockState(p).isAir());
        return shut ? "shut" : open ? "open"
                : "half (lower " + helper.getBlockState(DOOR.get(0)).getBlock().getName().getString()
                + ", upper " + helper.getBlockState(DOOR.get(1)).getBlock().getName().getString() + ")";
    }

    /** Sets the levers to `setting`, flipping only those that differ, as a player would. */
    static void set(GameTestHelper helper, boolean[] setting) {
        for (int i = 0; i < LEVERS.length; i++) {
            if (helper.getBlockState(lever(i)).getValue(LeverBlock.POWERED) != setting[i]) {
                helper.pullLever(lever(i));
            }
        }
    }

    /**
     * Every one of the 16 settings, each from the one before (a Gray code, one lever at a time, so every lever is
     * flipped both ways, the right one into the wrong and back): the door is open for the combination only, and
     * shut, stone bricks as the wall, for all the others. The vault's chest keeps its treasure.
     */
    @GameTest(structure = STRUCTURE, maxTicks = 1500)
    public void onlyTheCombinationOpensTheDoor(GameTestHelper helper) {
        layout().build(helper);
        layout().fillContainers(helper);
        List<String> wrong = new ArrayList<>();
        GameTestSequence sequence = helper.startSequence().thenIdle(20)
                .thenExecute(() -> helper.assertValueEqual(door(helper), "shut", "the door with every lever off"));
        for (int n = 1; n <= 16; n++) {
            int gray = (n % 16) ^ ((n % 16) >> 1);
            boolean[] setting = new boolean[4];
            for (int i = 0; i < 4; i++) {
                setting[i] = (gray >> i & 1) == 1;
            }
            boolean right = java.util.Arrays.equals(setting, COMBINATION);
            String name = java.util.Arrays.toString(setting);
            sequence = sequence
                    .thenExecute(() -> set(helper, setting))
                    .thenIdle(40)          // the door opens about 24 game ticks after the last lever (simulated)
                    .thenExecute(() -> {
                        String seen = door(helper);
                        if (!seen.equals(right ? "open" : "shut")) {
                            wrong.add(name + ": " + seen);
                        }
                    });
        }
        sequence.thenExecute(() -> {
            helper.assertValueEqual(wrong, List.of(), "settings where the door was wrong");
            ChestBlockEntity chest = helper.getBlockEntity(CHEST, ChestBlockEntity.class);
            helper.assertTrue(chest.getItem(0).is(Items.DIAMOND) && chest.getItem(0).getCount() == 9, "the vault's diamonds");
        }).thenSucceed();
    }
}
