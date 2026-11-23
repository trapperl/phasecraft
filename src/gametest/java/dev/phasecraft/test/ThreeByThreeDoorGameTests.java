package dev.phasecraft.test;

import dev.phasecraft.layer.HiddenLayer;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * The 3x3 flush piston door, the famously hard one in vanilla, PhaseCraft-style:
 * a 3x3 door flush with a wall at the end of a tunnel, opened and closed by one
 * visible lever, every piston and wire hidden (layout:
 * contraptions/flush_door_3x3.txt).
 *
 * <p>The hard part in vanilla is the centre block: it is surrounded by doorway on
 * every side, so it has to move twice. Here: stage 1 pushes all nine blocks back
 * into the wall (z = 5); stage 2 pulls the side columns sideways into pockets,
 * drops the bottom-middle block into the floor, and lifts the centre and
 * top-middle blocks as a line into a two-high pocket above the doorway (two
 * pushes from below). Closing pushes them back down from above, returns the
 * sides and the floor block, and pulls all nine forward again.</p>
 */
public class ThreeByThreeDoorGameTests {
    static final String STRUCTURE = "phasecraft-test:empty_11x11x14";
    private static ContraptionLayout layout;

    /** Loaded on first use, not with the class: test classes load before the mod's blocks are registered. */
    static ContraptionLayout layout() {
        if (layout == null) {
            layout = ContraptionLayout.load("flush_door_3x3");
        }
        return layout;
    }

    private static boolean door(GameTestHelper helper, int x, int y, int z) {
        return helper.getBlockState(new BlockPos(x, y, z)).is(layout().door());
    }

    private static boolean air(GameTestHelper helper, int x, int y, int z) {
        return helper.getBlockState(new BlockPos(x, y, z)).isAir();
    }

    /** All nine door blocks flush in the wall face (z = 6), nothing in the doorway or the pockets. */
    static boolean closed(GameTestHelper helper) {
        for (int x = 4; x <= 6; x++) {
            for (int y = 3; y <= 5; y++) {
                if (!door(helper, x, y, 6) || !air(helper, x, y, 5)) {
                    return false;
                }
            }
        }
        for (int y = 3; y <= 5; y++) {
            if (!air(helper, 3, y, 5) || !air(helper, 7, y, 5)) {
                return false;
            }
        }
        return air(helper, 5, 2, 5) && air(helper, 5, 6, 5) && air(helper, 5, 7, 5);
    }

    /** The doorway clear all the way through, the nine blocks in their pockets. */
    static boolean open(GameTestHelper helper) {
        for (int x = 4; x <= 6; x++) {
            for (int y = 3; y <= 5; y++) {
                for (int z = 3; z <= 6; z++) {
                    if (!air(helper, x, y, z)) {
                        return false;
                    }
                }
            }
        }
        for (int y = 3; y <= 5; y++) {
            if (!door(helper, 3, y, 5) || !door(helper, 7, y, 5)) {
                return false;
            }
        }
        return door(helper, 5, 2, 5) && door(helper, 5, 6, 5) && door(helper, 5, 7, 5);
    }

    /** Door blocks seen face-on, z = 6 then z = 5, rows top to bottom, x = 3..7 (D door, . air, # other, m moving). */
    static String describe(GameTestHelper helper) {
        StringBuilder out = new StringBuilder();
        for (int z = 6; z >= 5; z--) {
            out.append(" z").append(z).append(':');
            for (int y = 7; y >= 2; y--) {
                for (int x = 3; x <= 7; x++) {
                    BlockState s = helper.getBlockState(new BlockPos(x, y, z));
                    out.append(s.is(layout().door()) ? 'D' : s.isAir() ? '.' : s.is(Blocks.MOVING_PISTON) ? 'm' : '#');
                }
                out.append(y > 2 ? "/" : "");
            }
        }
        HiddenLayer hidden = HiddenLayer.of(helper.getLevel());
        int[][] probes = {{5, 2, 11}, {1, 2, 5}, {3, 2, 9}, {8, 2, 11}, {1, 4, 5}, {9, 4, 5}, {4, 1, 5}, {7, 4, 3},
                {5, 9, 7}, {3, 9, 7}, {5, 3, 7}, {2, 4, 5}, {5, 1, 5}, {5, 3, 5}, {5, 4, 5}, {5, 7, 5}, {5, 8, 5}};
        for (int[] p : probes) {
            BlockState s = hidden.getBlock(helper.absolutePos(new BlockPos(p[0], p[1], p[2])));
            String v = s.hasProperty(BlockStateProperties.POWER) ? "p" + s.getValue(BlockStateProperties.POWER)
                    : s.hasProperty(BlockStateProperties.EXTENDED) ? (s.getValue(BlockStateProperties.EXTENDED) ? "ext" : "ret")
                    : s.hasProperty(BlockStateProperties.POWERED) ? (s.getValue(BlockStateProperties.POWERED) ? "on" : "off")
                    : s.hasProperty(BlockStateProperties.LIT) ? (s.getValue(BlockStateProperties.LIT) ? "lit" : "out") : "";
            out.append(" H").append(p[0]).append(',').append(p[1]).append(',').append(p[2]).append('=')
                    .append(s.getBlock().getDescriptionId().replaceAll(".*\\.", "")).append(v);
        }
        return out.toString();
    }

    /** One visible lever opens the 3x3 flush door all the way and closes it flush again, twice. */
    @GameTest(structure = STRUCTURE, maxTicks = 400)
    public void leverOpensAndClosesFlushDoor3x3(GameTestHelper helper) {
        layout().build(helper);
        BlockPos lever = layout().lever();
        helper.startSequence()
                .thenIdle(10)
                .thenExecute(() -> helper.assertTrue(closed(helper), "door not closed at start:" + describe(helper)))
                .thenExecute(() -> helper.pullLever(lever))
                .thenWaitUntil(() -> helper.assertTrue(open(helper), "door did not open:" + describe(helper)))
                .thenIdle(20)
                .thenExecute(() -> helper.assertTrue(open(helper), "door did not stay open:" + describe(helper)))
                .thenExecute(() -> helper.pullLever(lever))
                .thenWaitUntil(() -> helper.assertTrue(closed(helper), "door did not close:" + describe(helper)))
                .thenIdle(20)
                .thenExecute(() -> helper.assertTrue(closed(helper), "door did not stay closed:" + describe(helper)))
                .thenExecute(() -> helper.pullLever(lever))
                .thenWaitUntil(() -> helper.assertTrue(open(helper), "door did not open a second time:" + describe(helper)))
                .thenExecute(() -> helper.pullLever(lever))
                .thenWaitUntil(() -> helper.assertTrue(closed(helper), "door did not close a second time:" + describe(helper)))
                .thenSucceed();
    }
}
