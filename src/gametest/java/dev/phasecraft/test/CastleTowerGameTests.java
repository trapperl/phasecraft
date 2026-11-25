package dev.phasecraft.test;

import dev.phasecraft.PhaseCraftBlocks;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The castle tower (the person's showcase list, 2026-11-24: "a castle tower built and unbuilt block by block"): a
 * 5 x 5 stone tower, eight courses with battlements, a doorway and barred windows, 118 blocks. Its lever takes it
 * down block by block from the top, in a helix (the top comes off in a spiral, a course lower every face); flipped
 * back, it builds itself up again from the ground the same way. A line shifter under each of its 16 columns does it
 * (the person, 2026-11-25: "'pushing' phase blocks in a row or column would be an intuitive fix"); all the wiring is
 * hidden (layout: contraptions/castle_tower.txt, written by tools/gen_tower.py).
 */
public class CastleTowerGameTests {
    static final int TOP = 7;
    static final int BLOCKS = 118;
    static final int COLUMNS = 16;
    /** Redstone ticks a block in a column: the line shifters' delay. */
    static final int STEP = 4;
    /** Game ticks from the lever to the first block coming down. */
    static final int FIRST_TICKS = 4;
    static final Block[] WALL = {Blocks.STONE_BRICKS, Blocks.MOSSY_STONE_BRICKS, Blocks.CRACKED_STONE_BRICKS, Blocks.IRON_BARS};
    private static ContraptionLayout layout;

    static ContraptionLayout layout() {
        if (layout == null) {
            layout = ContraptionLayout.load("castle_tower");
        }
        return layout;
    }

    /** The tower's blocks, as the layout places them. */
    static List<BlockPos> tower() {
        List<BlockPos> tower = new ArrayList<>();
        for (Block block : WALL) {
            tower.addAll(layout().visible(block));
        }
        return tower;
    }

    /** The tower's middle at its lowest course. */
    static BlockPos middle(List<BlockPos> tower) {
        int minX = tower.stream().mapToInt(BlockPos::getX).min().orElseThrow();
        int maxX = tower.stream().mapToInt(BlockPos::getX).max().orElseThrow();
        int minZ = tower.stream().mapToInt(BlockPos::getZ).min().orElseThrow();
        int maxZ = tower.stream().mapToInt(BlockPos::getZ).max().orElseThrow();
        int minY = tower.stream().mapToInt(BlockPos::getY).min().orElseThrow();
        return new BlockPos((minX + maxX) / 2, minY, (minZ + maxZ) / 2);
    }

    /**
     * A block's column in turn: clockwise seen from above from the north-west corner, face by face (north, east,
     * south, west, each corner the first block of a face), 0 to 15.
     */
    static int column(BlockPos p, BlockPos middle) {
        int x = p.getX() - middle.getX();
        int z = p.getZ() - middle.getZ();
        if (z == -2 && x >= -2 && x <= 1) {
            return x + 2;
        } else if (x == 2 && z >= -2 && z <= 1) {
            return 4 + z + 2;
        } else if (z == 2 && x >= -1 && x <= 2) {
            return 8 + 2 - x;
        } else if (x == -2 && z >= -1 && z <= 2) {
            return 12 + 2 - z;
        }
        throw new IllegalStateException("not a wall block: " + p.toShortString());
    }

    /**
     * A block's turn in redstone ticks: coming down (lever on) after the first block, k + STEP (7 - y); going up
     * (lever off) after the moment the first block would have come down, k + STEP y + STEP (the generator's rule,
     * gen_tower.turn). k is its column, y its course.
     */
    static int turn(BlockPos p, BlockPos middle, boolean up) {
        int k = column(p, middle);
        int y = p.getY() - middle.getY();
        return up ? k + STEP * y + STEP : k + STEP * (TOP - y);
    }

    /** When each block went (coming down) or came back (going up), filled in while waiting. */
    private static final class Watch {
        final Map<BlockPos, Long> at = new HashMap<>();
        long lever;

        void look(GameTestHelper helper, List<BlockPos> tower, boolean gone) {
            for (BlockPos p : tower) {
                if (helper.getBlockState(p).isAir() == gone && !at.containsKey(p)) {
                    at.put(p, helper.getTick());
                }
            }
        }
    }

    /** Every block at its turn: each 2 game ticks a redstone tick after the lever plus FIRST_TICKS. */
    private static void assertTurns(GameTestHelper helper, List<BlockPos> tower, Watch watch, boolean up) {
        BlockPos middle = middle(tower);
        long start = watch.lever + FIRST_TICKS;
        List<String> wrong = new ArrayList<>();
        for (BlockPos p : tower) {
            long want = start + 2L * turn(p, middle, up);
            if (watch.at.get(p) != want) {
                wrong.add(p.toShortString().replace(" ", "") + "@" + (watch.at.get(p) - watch.lever) + "!=" + (want - watch.lever));
            }
        }
        helper.assertTrue(wrong.isEmpty(), (up ? "going up" : "coming down") + ": " + wrong.size()
                + " blocks off their turn (game ticks after the lever): " + wrong.subList(0, Math.min(8, wrong.size())));
    }

    private static boolean standing(GameTestHelper helper, Map<BlockPos, BlockState> built) {
        return built.entrySet().stream().allMatch(e -> helper.getBlockState(e.getKey()).equals(e.getValue()));
    }

    /**
     * No block hangs in the air: in every column, the blocks there form one run up from the column's lowest block
     * (a line shifter takes from the top and puts back from the bottom).
     */
    private static void assertNoGaps(GameTestHelper helper, List<BlockPos> tower) {
        BlockPos middle = middle(tower);
        Map<Integer, List<BlockPos>> columns = new HashMap<>();
        for (BlockPos p : tower) {
            columns.computeIfAbsent(column(p, middle), k -> new ArrayList<>()).add(p);
        }
        for (List<BlockPos> blocks : columns.values()) {
            blocks.sort((a, b) -> Integer.compare(a.getY(), b.getY()));
            boolean gap = false;
            for (BlockPos p : blocks) {
                boolean there = !helper.getBlockState(p).isAir();
                helper.assertTrue(!(gap && there), "a block hangs over a gap at " + p.toShortString());
                gap |= !there;
            }
        }
    }

    /** Builds it and checks it stands, untouched, every tick while the hidden wiring settles. */
    private static GameTestSequence settled(GameTestHelper helper, List<BlockPos> tower, Map<BlockPos, BlockState> built) {
        layout().build(helper);
        GameTestSequence sequence = helper.startSequence().thenExecute(() -> {
            helper.assertValueEqual(tower.size(), BLOCKS, "tower blocks");
            helper.assertValueEqual(layout().hidden(PhaseCraftBlocks.LINE_SHIFTER).size(), COLUMNS, "line shifters");
            for (BlockPos p : tower) {
                built.put(p, helper.getBlockState(p));
            }
            helper.assertTrue(built.values().stream().noneMatch(BlockState::isAir), "a tower block is missing");
        });
        for (int i = 0; i < 30; i++) {
            sequence = sequence.thenIdle(1).thenExecute(() -> helper.assertTrue(standing(helper, built),
                    "a tower block moved while the wiring settled"));
        }
        return sequence;
    }

    /** The lever takes the tower down from the top and builds it up from the ground, block by block, twice. */
    @GameTest(structure = "phasecraft-test:castle_tower", maxTicks = 1000)
    public void leverTakesTheTowerDownAndBuildsItUpAgain(GameTestHelper helper) {
        List<BlockPos> tower = tower();
        Map<BlockPos, BlockState> built = new HashMap<>();
        GameTestSequence sequence = settled(helper, tower, built);
        BlockPos lever = layout().lever();
        for (int cycle = 0; cycle < 2; cycle++) {
            Watch down = new Watch();
            Watch up = new Watch();
            sequence = sequence
                    .thenExecute(() -> {
                        down.lever = helper.getTick();
                        helper.pullLever(lever);
                    })
                    .thenWaitUntil(() -> {
                        down.look(helper, tower, true);
                        helper.assertTrue(down.at.size() == tower.size(), "still standing: " + (tower.size() - down.at.size()));
                    })
                    .thenExecute(() -> assertTurns(helper, tower, down, false))
                    .thenIdle(20)
                    .thenExecute(() -> helper.assertTrue(tower.stream().allMatch(p -> helper.getBlockState(p).isAir()),
                            "a block came back while the lever stayed on"))
                    .thenExecute(() -> {
                        up.lever = helper.getTick();
                        helper.pullLever(lever);
                    })
                    .thenWaitUntil(() -> {
                        up.look(helper, tower, false);
                        helper.assertTrue(up.at.size() == tower.size(), "still missing: " + (tower.size() - up.at.size()));
                    })
                    .thenExecute(() -> assertTurns(helper, tower, up, true))
                    .thenIdle(20)
                    .thenExecute(() -> helper.assertTrue(standing(helper, built),
                            "the tower is not as it was (a block missing, moved or changed)"));
        }
        sequence.thenSucceed();
    }

    /**
     * Flipped back while it comes down, the tower turns around: each column puts back what it took, from the bottom,
     * and the tower stands again as it was. No block ever hangs over a gap, and it never got all the way down.
     */
    @GameTest(structure = "phasecraft-test:castle_tower", maxTicks = 1000)
    public void flippedBackMidwayItTurnsAround(GameTestHelper helper) {
        List<BlockPos> tower = tower();
        Map<BlockPos, BlockState> built = new HashMap<>();
        BlockPos lever = layout().lever();
        int[] fewest = {Integer.MAX_VALUE};
        Runnable look = () -> {
            assertNoGaps(helper, tower);
            fewest[0] = Math.min(fewest[0], (int) tower.stream().filter(p -> !helper.getBlockState(p).isAir()).count());
        };
        GameTestSequence sequence = settled(helper, tower, built).thenExecute(() -> helper.pullLever(lever));
        for (int i = 0; i < 200; i++) {
            int tick = i;
            sequence = sequence.thenIdle(1).thenExecute(() -> {
                if (tick == 40) {
                    long gone = tower.stream().filter(p -> helper.getBlockState(p).isAir()).count();
                    helper.assertTrue(gone > 10 && gone < tower.size() - 10, "midway: " + gone + " gone");
                    helper.pullLever(lever);
                }
                look.run();
            });
        }
        sequence
                .thenExecute(() -> {
                    helper.assertTrue(fewest[0] > 0, "it came all the way down");
                    helper.assertTrue(standing(helper, built), "the tower is not as it was (a block missing, moved or changed)");
                })
                .thenSucceed();
    }
}
