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
 * The acceptance target: a 2x2 flush door in a visible wall, opened and closed
 * by one visible lever, with every piston and wire in the hidden layer.
 *
 * <p>Layout (test-relative). The corridor is z >= 5, the wall is three blocks
 * thick (z = 4 face, z = 3 inner with side pockets, z = 2 back), and the
 * doorway is x = 3..4, y = 1..2. Two stages, as in a vanilla flush door:
 * stage 1 (sticky phase pistons hidden in the corridor at z = 5) pushes the
 * door blocks back to z = 3; stage 2 (sticky phase pistons hidden at z = 3)
 * slides them sideways into the pockets at x = 2 and x = 5. Timing uses only
 * vanilla repeaters: stage 2 follows the lever after a delay; stage 1 follows
 * the lever, and stays on after it until stage 2 has finished pulling back.</p>
 */
public class HiddenDoorGameTests {
    private static final BlockState STONE = Blocks.STONE.defaultBlockState();
    private static final BlockState WIRE = Blocks.REDSTONE_WIRE.defaultBlockState();
    private static final Block DOOR = Blocks.SMOOTH_STONE;

    static final BlockPos LEVER = new BlockPos(7, 3, 6);

    private static BlockState sticky(Direction facing) {
        return PhaseCraftBlocks.STICKY_PHASE_PISTON.defaultBlockState().setValue(BlockStateProperties.FACING, facing);
    }

    private static BlockState repeater(Direction facing, int delay) {
        return Blocks.REPEATER.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, facing).setValue(RepeaterBlock.DELAY, delay);
    }

    /** Builds the door, visible and hidden, relative to the helper's test area. */
    static void build(GameTestHelper helper) {
        HiddenLayer hidden = HiddenLayer.of(helper.getLevel());
        java.util.function.BiConsumer<BlockPos, BlockState> h = (rel, state) -> hidden.setBlock(helper.absolutePos(rel), state);

        // Visible: floor, three-thick wall with doorway, pockets and passage, the door blocks, the lever on a pillar.
        for (int x = 0; x <= 7; x++) {
            for (int z = 0; z <= 7; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            }
            for (int y = 1; y <= 3; y++) {
                for (int z = 2; z <= 4; z++) {
                    boolean passage = (x == 3 || x == 4) && y <= 2;
                    boolean pocket = z == 3 && y <= 2 && (x == 2 || x == 5);
                    helper.setBlock(new BlockPos(x, y, z), passage || pocket ? Blocks.AIR : Blocks.STONE_BRICKS);
                }
            }
        }
        for (int x = 3; x <= 4; x++) {
            for (int y = 1; y <= 2; y++) {
                helper.setBlock(new BlockPos(x, y, 4), DOOR);
            }
        }
        helper.setBlock(LEVER.below(), Blocks.STONE);
        helper.setBlock(LEVER, Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, AttachFace.FLOOR)
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH));

        // Hidden: the port under the lever, and the lever's signal bus along x = 7.
        h.accept(LEVER, PhaseCraftBlocks.PHASE_PORT.defaultBlockState());
        for (int z = 3; z <= 5; z++) {
            h.accept(new BlockPos(7, 2, z), STONE);
            h.accept(new BlockPos(7, 3, z), WIRE);
        }
        // Stage 1: sticky phase pistons in the corridor, facing the door; wire on top powers both rows.
        for (int x = 3; x <= 4; x++) {
            for (int y = 1; y <= 2; y++) {
                h.accept(new BlockPos(x, y, 5), sticky(Direction.NORTH));
            }
            h.accept(new BlockPos(x, 3, 5), WIRE);
        }
        // Stage 2: sticky phase pistons behind the face, pushing outward into the pockets.
        for (int y = 1; y <= 2; y++) {
            h.accept(new BlockPos(3, y, 3), sticky(Direction.EAST));
            h.accept(new BlockPos(4, y, 3), sticky(Direction.WEST));
        }
        h.accept(new BlockPos(3, 3, 3), WIRE);
        h.accept(new BlockPos(4, 3, 3), WIRE);
        // Timing. B1 (5,3,5) drives stage 1; B2 (5,3,3) drives stage 2.
        h.accept(new BlockPos(5, 3, 5), STONE);                        // B1
        h.accept(new BlockPos(5, 3, 3), STONE);                        // B2
        h.accept(new BlockPos(6, 2, 5), STONE);
        h.accept(new BlockPos(6, 3, 5), repeater(Direction.EAST, 1));  // lever -> B1 at once (facing = input side)
        h.accept(new BlockPos(6, 2, 3), STONE);
        h.accept(new BlockPos(6, 3, 3), repeater(Direction.EAST, 4));  // lever -> B2 after 4
        h.accept(new BlockPos(5, 2, 4), STONE);
        h.accept(new BlockPos(5, 3, 4), repeater(Direction.NORTH, 4)); // B2 -> B1 after 4 more: holds stage 1 on
    }

    private static boolean open(GameTestHelper helper) {
        for (int x = 3; x <= 4; x++) {
            for (int y = 1; y <= 2; y++) {
                if (!helper.getBlockState(new BlockPos(x, y, 4)).isAir() || !helper.getBlockState(new BlockPos(x, y, 3)).isAir()) {
                    return false;
                }
            }
        }
        for (int y = 1; y <= 2; y++) {
            if (!helper.getBlockState(new BlockPos(2, y, 3)).is(DOOR) || !helper.getBlockState(new BlockPos(5, y, 3)).is(DOOR)) {
                return false;
            }
        }
        return true;
    }

    private static boolean closed(GameTestHelper helper) {
        for (int x = 3; x <= 4; x++) {
            for (int y = 1; y <= 2; y++) {
                if (!helper.getBlockState(new BlockPos(x, y, 4)).is(DOOR) || !helper.getBlockState(new BlockPos(x, y, 3)).isAir()) {
                    return false;
                }
            }
        }
        for (int y = 1; y <= 2; y++) {
            if (!helper.getBlockState(new BlockPos(2, y, 3)).isAir() || !helper.getBlockState(new BlockPos(5, y, 3)).isAir()) {
                return false;
            }
        }
        return true;
    }

    private static String describe(GameTestHelper helper) {
        StringBuilder out = new StringBuilder();
        for (int z = 3; z <= 4; z++) {
            for (int y = 2; y >= 1; y--) {
                out.append(" z").append(z).append("y").append(y).append(":");
                for (int x = 2; x <= 5; x++) {
                    BlockState s = helper.getBlockState(new BlockPos(x, y, z));
                    out.append(s.is(DOOR) ? 'D' : s.isAir() ? '.' : s.is(Blocks.MOVING_PISTON) ? 'm' : '#');
                }
            }
        }
        HiddenLayer hidden = HiddenLayer.of(helper.getLevel());
        int[][] probes = {{7, 3, 6}, {7, 3, 5}, {7, 3, 3}, {6, 3, 5}, {6, 3, 3}, {5, 3, 4}, {4, 3, 5}, {3, 3, 5}, {4, 3, 3},
                {3, 2, 5}, {3, 1, 5}, {3, 2, 3}, {4, 2, 3}};
        for (int[] p : probes) {
            BlockState s = hidden.getBlock(helper.absolutePos(new BlockPos(p[0], p[1], p[2])));
            String v = s.hasProperty(BlockStateProperties.POWER) ? "p" + s.getValue(BlockStateProperties.POWER)
                    : s.hasProperty(BlockStateProperties.POWERED) ? (s.getValue(BlockStateProperties.POWERED) ? "on" : "off")
                    : s.hasProperty(BlockStateProperties.EXTENDED) ? (s.getValue(BlockStateProperties.EXTENDED) ? "ext" : "ret") : "";
            out.append(" H").append(p[0]).append(p[1]).append(p[2]).append('=')
                    .append(s.getBlock().getDescriptionId().replaceAll(".*\\.", "")).append(v);
        }
        return out.toString();
    }

    /** One visible lever opens the flush 2x2 door, and closes it again, through hidden machinery only. */
    @GameTest(maxTicks = 200)
    public void leverOpensAndClosesFlushDoor(GameTestHelper helper) {
        build(helper);
        helper.startSequence()
                .thenIdle(5)
                .thenExecute(() -> helper.assertTrue(closed(helper), "door not closed at start:" + describe(helper)))
                .thenExecute(() -> helper.pullLever(LEVER))
                .thenWaitUntil(() -> helper.assertTrue(open(helper), "door did not open:" + describe(helper)))
                .thenIdle(10)
                .thenExecute(() -> helper.assertTrue(open(helper), "door did not stay open:" + describe(helper)))
                .thenExecute(() -> helper.pullLever(LEVER))
                .thenWaitUntil(() -> helper.assertTrue(closed(helper), "door did not close:" + describe(helper)))
                .thenIdle(10)
                .thenExecute(() -> helper.assertTrue(closed(helper), "door did not stay closed:" + describe(helper)))
                .thenSucceed();
    }
}
