package dev.phasecraft.test;

import dev.phasecraft.PhaseCraftBlocks;
import dev.phasecraft.layer.HiddenLayer;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * A hidden staircase: a piece of solid floor that, at the flip of one visible
 * lever, turns into a staircase down into a cellar, and back into flat floor.
 * Every piston and wire is in the hidden layer, inside the solid ground.
 *
 * <p>Layout (test-relative). A platform x = 0..10, z = 0..8, walking surface
 * y = 5. The stairs are two wide (x = 3, 4) and run down towards +z: three
 * floor blocks per column at z = 2, 3, 4 cover a pit, and a cellar
 * (z = 5..7, floor y = 1) lies beyond. Two fixed steps are already built in
 * the pit: (x, 3, 3) and (x, 2, 4). Opening is two stages, like a vanilla
 * flush door:</p>
 * <ol>
 *   <li>Stage A: up-facing sticky phase pistons at (x, 3, z), extended and
 *       holding the floor blocks at y = 5, retract: all six floor blocks drop
 *       one. The front pair at z = 2 is now the first step.</li>
 *   <li>Stage B: sideways sticky phase pistons at (x, 4, z), z = 3, 4, push
 *       the blocks that dropped there into pockets inside the side walls
 *       (x = 2 and x = 5, under the floor), uncovering the fixed steps.</li>
 * </ol>
 * <p>Closing runs backwards. Stage A is powered while the stairs are closed
 * (through a torch, from the lever's hold signal S1); stage B is powered
 * while they are open. The timing is the door's: B follows the lever after a
 * delay; S1 follows the lever at once and holds until B has pulled back.</p>
 *
 * <p>Quasi-connectivity is the trap with stacked pistons: a phase piston,
 * like a vanilla one, is also powered by anything powering the block above
 * it. Stage A pistons sit right under stage B pistons, so stage B is powered
 * only through its own quasi-connectivity (repeaters pointing into the empty
 * cell above each B piston), and nothing ever points into a B piston itself.</p>
 */
public class HiddenStaircaseGameTests {
    static final String STRUCTURE = "phasecraft-test:empty_12x8x10";

    static final Block BODY = Blocks.STONE_BRICKS;
    static final Block STEP = Blocks.POLISHED_ANDESITE;
    private static final BlockState STONE = Blocks.STONE.defaultBlockState();
    private static final BlockState WIRE = Blocks.REDSTONE_WIRE.defaultBlockState();

    static final BlockPos LEVER = new BlockPos(9, 6, 2);
    /** The stair columns. */
    static final int[] XS = {3, 4};

    private static BlockState sticky(Direction facing, boolean extended) {
        return PhaseCraftBlocks.STICKY_PHASE_PISTON.defaultBlockState()
                .setValue(BlockStateProperties.FACING, facing).setValue(BlockStateProperties.EXTENDED, extended);
    }

    /** A repeater whose input is on the {@code from} side, already powered if {@code on}. */
    private static BlockState repeater(Direction from, int delay, boolean on) {
        return Blocks.REPEATER.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, from)
                .setValue(RepeaterBlock.DELAY, delay).setValue(BlockStateProperties.POWERED, on);
    }

    private static boolean visibleAir(int x, int y, int z) {
        return (x == 3 || x == 4) && (
                (z == 2 && y == 4)
                || (z == 3 && y == 4)
                || (z == 4 && (y == 3 || y == 4))
                || (z >= 5 && z <= 7 && y >= 2 && y <= 4))
                || (y == 4 && (z == 3 || z == 4) && (x == 2 || x == 5));
    }

    /** Builds the staircase, closed, with the lever off, relative to the helper's test area. */
    static void build(GameTestHelper helper) {
        // Visible: ground, the platform with pit, cellar and wall pockets, fixed steps, the lever.
        for (int x = 0; x <= 10; x++) {
            for (int z = 0; z <= 8; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
                for (int y = 1; y <= 5; y++) {
                    Block block = visibleAir(x, y, z) ? Blocks.AIR : y == 5 ? STEP : BODY;
                    helper.setBlock(new BlockPos(x, y, z), block);
                }
            }
        }
        for (int x : XS) {
            helper.setBlock(new BlockPos(x, 3, 3), STEP);
            helper.setBlock(new BlockPos(x, 2, 4), STEP);
            for (int z = 5; z <= 7; z++) {
                helper.setBlock(new BlockPos(x, 1, z), STEP);
            }
        }
        helper.setBlock(LEVER, Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, AttachFace.FLOOR)
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH));

        HiddenLayer hidden = HiddenLayer.of(helper.getLevel());
        java.util.function.BiConsumer<BlockPos, BlockState> h = (rel, state) -> hidden.setBlock(helper.absolutePos(rel), state);
        // Wire on a block of stone; the stone goes first.
        java.util.function.BiConsumer<BlockPos, BlockState> on = (rel, state) -> {
            h.accept(rel.below(), STONE);
            h.accept(rel, state);
        };

        // The lever's signal, and the timing (the door's): R2 delays the lever into
        // stage B's bus; S1 = lever (R1) or stage B's bus delayed (R3).
        h.accept(new BlockPos(9, 5, 2), PhaseCraftBlocks.PHASE_PORT.defaultBlockState()); // under the lever
        on.accept(new BlockPos(9, 5, 3), repeater(Direction.NORTH, 1, false));             // R1
        on.accept(new BlockPos(8, 5, 2), repeater(Direction.EAST, 4, false));              // R2
        on.accept(new BlockPos(8, 5, 4), repeater(Direction.WEST, 4, false));              // R3
        h.accept(new BlockPos(9, 5, 4), STONE);                                              // S1

        // Stage B's bus at y = 5, around the front: east line x = 7, front row z = 0, west line x = 0.
        for (int z = 0; z <= 4; z++) {
            on.accept(new BlockPos(7, 5, z), WIRE);
        }
        for (int x = 6; x >= 0; x--) {
            on.accept(new BlockPos(x, 5, 0), WIRE);
        }
        for (int z = 1; z <= 4; z++) {
            on.accept(new BlockPos(0, 5, z), WIRE);
        }
        // Into the empty cell above each B piston (quasi-connectivity), from the walls' sides.
        for (int z = 3; z <= 4; z++) {
            on.accept(new BlockPos(1, 5, z), repeater(Direction.WEST, 1, false));
            on.accept(new BlockPos(2, 5, z), repeater(Direction.WEST, 3, false));
            on.accept(new BlockPos(6, 5, z), repeater(Direction.EAST, 1, false));
            on.accept(new BlockPos(5, 5, z), repeater(Direction.EAST, 3, false));
        }

        // Stage A's signal: a torch on S1, lit while the lever is off, then down to y = 3.
        h.accept(new BlockPos(9, 5, 5), Blocks.REDSTONE_WALL_TORCH.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH));
        on.accept(new BlockPos(9, 4, 5), WIRE);
        on.accept(new BlockPos(8, 3, 5), WIRE);
        on.accept(new BlockPos(7, 3, 5), repeater(Direction.EAST, 1, true));
        // Stage A's bus at y = 3: east line x = 6, front row z = 1, west line x = 1.
        for (int z = 5; z >= 1; z--) {
            on.accept(new BlockPos(6, 3, z), WIRE);
        }
        for (int x = 5; x >= 1; x--) {
            on.accept(new BlockPos(x, 3, 1), WIRE);
        }
        for (int z = 2; z <= 4; z++) {
            on.accept(new BlockPos(1, 3, z), WIRE);
        }
        // Into the side of each A piston.
        for (int z = 2; z <= 4; z++) {
            on.accept(new BlockPos(2, 3, z), repeater(Direction.WEST, 1, true));
            on.accept(new BlockPos(5, 3, z), repeater(Direction.EAST, 1, true));
        }

        // The pistons last, each already in its closed state.
        for (int z = 2; z <= 4; z++) {
            for (int x : XS) {
                h.accept(new BlockPos(x, 3, z), sticky(Direction.UP, true));
            }
        }
        for (int z = 3; z <= 4; z++) {
            h.accept(new BlockPos(3, 4, z), sticky(Direction.EAST, false));
            h.accept(new BlockPos(4, 4, z), sticky(Direction.WEST, false));
        }
    }

    private static boolean is(GameTestHelper helper, int x, int y, int z, Block block) {
        return helper.getBlockState(new BlockPos(x, y, z)).is(block);
    }

    static boolean closed(GameTestHelper helper) {
        for (int x : XS) {
            for (int z = 2; z <= 4; z++) {
                if (!is(helper, x, 5, z, STEP) || !is(helper, x, 4, z, Blocks.AIR)) {
                    return false;
                }
            }
        }
        for (int z = 3; z <= 4; z++) {
            if (!is(helper, 2, 4, z, Blocks.AIR) || !is(helper, 5, 4, z, Blocks.AIR)) {
                return false;
            }
        }
        return true;
    }

    static boolean open(GameTestHelper helper) {
        for (int x : XS) {
            for (int z = 2; z <= 4; z++) {
                if (!is(helper, x, 5, z, Blocks.AIR)) {
                    return false;
                }
            }
            if (!is(helper, x, 4, 2, STEP) || !is(helper, x, 4, 3, Blocks.AIR) || !is(helper, x, 4, 4, Blocks.AIR)) {
                return false;
            }
        }
        for (int z = 3; z <= 4; z++) {
            if (!is(helper, 2, 4, z, STEP) || !is(helper, 5, 4, z, STEP)) {
                return false;
            }
        }
        return true;
    }

    /** The moving blocks, a slice per z: x = 2..5 at y = 5 then y = 4 (S step, . air, # other, m moving). */
    static String describe(GameTestHelper helper) {
        StringBuilder out = new StringBuilder();
        for (int z = 2; z <= 4; z++) {
            out.append(" z").append(z).append(':');
            for (int y = 5; y >= 4; y--) {
                for (int x = 2; x <= 5; x++) {
                    BlockState s = helper.getBlockState(new BlockPos(x, y, z));
                    out.append(s.is(STEP) ? 'S' : s.isAir() ? '.' : s.is(Blocks.MOVING_PISTON) ? 'm' : '#');
                }
                out.append(y == 5 ? "/" : "");
            }
        }
        HiddenLayer hidden = HiddenLayer.of(helper.getLevel());
        int[][] probes = {{9, 5, 2}, {9, 5, 5}, {7, 5, 2}, {0, 5, 4}, {2, 5, 3}, {6, 3, 5}, {1, 3, 4}, {2, 3, 2},
                {3, 3, 2}, {3, 3, 4}, {3, 4, 3}, {4, 4, 4}};
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

    /** One visible lever turns flat floor into stairs down to a cellar, and back, through hidden machinery only. */
    @GameTest(structure = STRUCTURE, maxTicks = 300)
    public void leverOpensAndClosesStaircase(GameTestHelper helper) {
        build(helper);
        helper.startSequence()
                .thenIdle(10)
                .thenExecute(() -> helper.assertTrue(closed(helper), "stairs not closed at start:" + describe(helper)))
                .thenExecute(() -> helper.pullLever(LEVER))
                .thenWaitUntil(() -> helper.assertTrue(open(helper), "stairs did not open:" + describe(helper)))
                .thenIdle(20)
                .thenExecute(() -> helper.assertTrue(open(helper), "stairs did not stay open:" + describe(helper)))
                .thenExecute(() -> helper.pullLever(LEVER))
                .thenWaitUntil(() -> helper.assertTrue(closed(helper), "stairs did not close:" + describe(helper)))
                .thenIdle(20)
                .thenExecute(() -> helper.assertTrue(closed(helper), "stairs did not stay closed:" + describe(helper)))
                .thenExecute(() -> helper.pullLever(LEVER))
                .thenWaitUntil(() -> helper.assertTrue(open(helper), "stairs did not open a second time:" + describe(helper)))
                .thenSucceed();
    }
}
