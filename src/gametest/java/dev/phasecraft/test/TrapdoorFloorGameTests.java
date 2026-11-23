package dev.phasecraft.test;

import dev.phasecraft.layer.HiddenLayer;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * The trapdoor floor (layout: contraptions/trapdoor_floor.txt): step on one
 * floor tile of a corridor and the two tiles after it drop away into a pit,
 * then close again once nobody stands there. The trigger is a phased pressure
 * plate, hidden at the tile's spot; nothing redstone is visible. Stage 1
 * (up-facing sticky phase pistons under the tiles) lets them drop one block,
 * stage 2 (sideways sticky phase pistons) pushes them into pockets under the
 * wall; closing runs the other way round.
 */
public class TrapdoorFloorGameTests {
    static final String STRUCTURE = "phasecraft-test:empty_11x11x14";
    private static final int[] TRAP_Z = {5, 6};
    private static ContraptionLayout layout;

    /** Loaded on first use, not with the class: test classes load before the mod's blocks are registered. */
    static ContraptionLayout layout() {
        if (layout == null) {
            layout = ContraptionLayout.load("trapdoor_floor");
        }
        return layout;
    }

    private static boolean tile(GameTestHelper helper, int x, int y, int z) {
        return helper.getBlockState(new BlockPos(x, y, z)).is(layout().door());
    }

    private static boolean air(GameTestHelper helper, int x, int y, int z) {
        return helper.getBlockState(new BlockPos(x, y, z)).isAir();
    }

    /** Both trap tiles in the floor (y = 5), nothing under them or in the pockets. */
    static boolean closed(GameTestHelper helper) {
        for (int z : TRAP_Z) {
            if (!tile(helper, 4, 5, z) || !air(helper, 4, 4, z) || !air(helper, 5, 4, z)) {
                return false;
            }
        }
        return true;
    }

    /** Both trap tiles in the pockets under the wall (x = 5, y = 4), the pit open. */
    static boolean open(GameTestHelper helper) {
        for (int z : TRAP_Z) {
            if (!air(helper, 4, 5, z) || !air(helper, 4, 4, z) || !tile(helper, 5, 4, z)) {
                return false;
            }
        }
        return true;
    }

    private static String describe(GameTestHelper helper) {
        StringBuilder out = new StringBuilder();
        for (int z : TRAP_Z) {
            out.append(" z").append(z).append(':');
            for (int y = 5; y >= 4; y--) {
                for (int x = 4; x <= 5; x++) {
                    BlockState s = helper.getBlockState(new BlockPos(x, y, z));
                    out.append(s.is(layout().door()) ? 'T' : s.isAir() ? '.' : s.is(Blocks.MOVING_PISTON) ? 'm' : '#');
                }
                out.append(y == 5 ? "/" : "");
            }
        }
        HiddenLayer hidden = HiddenLayer.of(helper.getLevel());
        int[][] probes = {{4, 6, 4}, {4, 3, 5}, {4, 3, 6}, {3, 4, 5}, {3, 4, 6}, {5, 3, 5}, {6, 5, 4}, {3, 5, 5}};
        for (int[] p : probes) {
            BlockState s = hidden.getBlock(helper.absolutePos(new BlockPos(p[0], p[1], p[2])));
            String v = s.hasProperty(BlockStateProperties.POWER) ? "p" + s.getValue(BlockStateProperties.POWER)
                    : s.hasProperty(BlockStateProperties.EXTENDED) ? (s.getValue(BlockStateProperties.EXTENDED) ? "ext" : "ret")
                    : s.hasProperty(BlockStateProperties.LIT) ? (s.getValue(BlockStateProperties.LIT) ? "lit" : "dark")
                    : s.hasProperty(BlockStateProperties.POWERED) ? (s.getValue(BlockStateProperties.POWERED) ? "on" : "off") : "";
            out.append(" H").append(p[0]).append(p[1]).append(p[2]).append('=')
                    .append(BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath()).append(v);
        }
        return out.toString();
    }

    /** Nothing redstone in the visible world: the corridor gives nothing away. */
    private static void assertNothingRedstoneVisible(GameTestHelper helper) {
        for (BlockPos rel : BlockPos.betweenClosed(0, 0, 0, 8, 8, 9)) {
            String id = BuiltInRegistries.BLOCK.getKey(helper.getBlockState(rel).getBlock()).getPath();
            helper.assertFalse(id.contains("redstone") || id.contains("plate") || id.contains("repeater")
                    || id.contains("piston") || id.contains("torch") || id.contains("lever") || id.contains("button"),
                    "visible " + id + " at " + rel);
        }
    }

    private static double relativeY(GameTestHelper helper, Pig pig) {
        return helper.relativeVec(pig.position()).y;
    }

    /**
     * A pig stands on the first trap tile. A second pig on the trigger tile opens the trap: the
     * first one falls into the pit. When the second one is gone, the floor closes over the pit.
     */
    @GameTest(structure = STRUCTURE, maxTicks = 300)
    public void steppingOnTheTriggerDropsTheFloorAhead(GameTestHelper helper) {
        layout().build(helper);
        Pig victim = helper.spawnWithNoFreeWill(EntityTypes.PIG, new BlockPos(4, 6, TRAP_Z[0]));
        Pig[] walker = new Pig[1];
        helper.startSequence()
                .thenIdle(5)
                .thenExecute(() -> {
                    helper.assertTrue(closed(helper), "trap not closed at start:" + describe(helper));
                    assertNothingRedstoneVisible(helper);
                })
                .thenIdle(20)
                .thenExecute(() -> helper.assertTrue(closed(helper), "a pig on the trap tiles opened it:" + describe(helper)))
                .thenExecute(() -> walker[0] = helper.spawnWithNoFreeWill(EntityTypes.PIG, layout().step()))
                .thenWaitUntil(() -> helper.assertTrue(open(helper), "trap did not open:" + describe(helper)))
                .thenWaitUntil(() -> helper.assertTrue(relativeY(helper, victim) < 4,
                        "the pig on the trap did not fall: y=" + relativeY(helper, victim)))
                .thenExecute(() -> {
                    helper.assertTrue(open(helper), "trap closed while the trigger was still stood on:" + describe(helper));
                    assertNothingRedstoneVisible(helper);
                    walker[0].discard();
                })
                .thenWaitUntil(() -> helper.assertTrue(closed(helper), "trap did not close:" + describe(helper)))
                .thenIdle(10)
                .thenExecute(() -> {
                    helper.assertTrue(closed(helper), "trap did not stay closed:" + describe(helper));
                    helper.assertTrue(relativeY(helper, victim) < 4, "the pig is out of the pit: y=" + relativeY(helper, victim));
                })
                .thenSucceed();
    }
}
