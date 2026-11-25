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
import net.minecraft.world.level.block.state.BlockState;

/**
 * The castle tower (the person's showcase list, 2026-11-24: "a castle tower built and unbuilt block by block"): a
 * 5 x 5 stone tower, eight courses with battlements, a doorway and arrow slits, 112 blocks. Its lever takes it down
 * block by block from the top; flipped back, it builds itself up again from the ground, a block every redstone tick,
 * each course clockwise (seen from above) from its north-west corner. Every block is held by a hidden phase shifter
 * at its own spot; all the wiring is hidden (layout: contraptions/castle_tower.txt, written by tools/gen_tower.py).
 */
public class CastleTowerGameTests {
    static final int TOP = 7;
    static final int BLOCKS = 112;
    /** Game ticks from the lever to the first block (the generator's simulator says so too). */
    static final int FIRST_TICKS = 42;
    private static ContraptionLayout layout;

    static ContraptionLayout layout() {
        if (layout == null) {
            layout = ContraptionLayout.load("castle_tower");
        }
        return layout;
    }

    /** The tower's blocks: where the layout puts a hidden phase shifter. */
    static List<BlockPos> tower() {
        return layout().hidden(PhaseCraftBlocks.PHASE_SHIFTER);
    }

    /**
     * A block's turn in redstone ticks after the first, going up (lever off) or coming down (lever on): course by
     * course, and in a course by face (north, east, south, west, each corner the first block of a face) and along it
     * clockwise seen from above. The generator's rule (gen_tower.turn).
     */
    static int turn(BlockPos p, BlockPos middle, boolean up) {
        int x = p.getX() - middle.getX();
        int y = p.getY() - middle.getY();
        int z = p.getZ() - middle.getZ();
        int f;
        int c;
        if (z == -2 && x >= -2 && x <= 1) {
            f = 0;
            c = x + 2;
        } else if (x == 2 && z >= -2 && z <= 1) {
            f = 1;
            c = z + 2;
        } else if (z == 2 && x >= -1 && x <= 2) {
            f = 2;
            c = 2 - x;
        } else if (x == -2 && z >= -1 && z <= 2) {
            f = 3;
            c = 2 - z;
        } else {
            throw new IllegalStateException("not a wall block: " + p.toShortString());
        }
        return 16 * (up ? y : TOP - y) + 4 * f + c;
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

    /** Every block at its turn: the first FIRST_TICKS after the lever, each next one a redstone tick later. */
    private static void assertTurns(GameTestHelper helper, List<BlockPos> tower, Watch watch, boolean up) {
        BlockPos middle = middle(tower);
        long first = watch.at.values().stream().mapToLong(Long::longValue).min().orElseThrow();
        List<String> wrong = new ArrayList<>();
        for (BlockPos p : tower) {
            long want = first + 2L * turn(p, middle, up);
            if (watch.at.get(p) != want) {
                wrong.add(p.toShortString().replace(" ", "") + "@" + (watch.at.get(p) - first) + "!=" + (want - first));
            }
        }
        helper.assertTrue(wrong.isEmpty(), (up ? "going up" : "coming down") + ": " + wrong.size()
                + " blocks off their turn (game ticks after the first): " + wrong.subList(0, Math.min(8, wrong.size())));
        helper.assertValueEqual(first - watch.lever, (long) FIRST_TICKS, "game ticks from the lever to the first block");
    }

    private static boolean standing(GameTestHelper helper, Map<BlockPos, BlockState> built) {
        return built.entrySet().stream().allMatch(e -> helper.getBlockState(e.getKey()).equals(e.getValue()));
    }

    /** Builds it and checks it stands, untouched, every tick while the hidden wiring settles. */
    private static GameTestSequence settled(GameTestHelper helper, List<BlockPos> tower, Map<BlockPos, BlockState> built) {
        layout().build(helper);
        GameTestSequence sequence = helper.startSequence().thenExecute(() -> {
            helper.assertValueEqual(tower.size(), BLOCKS, "tower blocks");
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
    @GameTest(structure = "phasecraft-test:castle_tower", maxTicks = 1800)
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
     * Flipped back while it comes down, the tower first finishes coming down, at the same turns, then builds itself
     * up again: the lever is read again only once a move has ended (no second wave, nothing lost).
     */
    @GameTest(structure = "phasecraft-test:castle_tower", maxTicks = 1600)
    public void aFlipDuringAMoveWaitsForItsEnd(GameTestHelper helper) {
        List<BlockPos> tower = tower();
        Map<BlockPos, BlockState> built = new HashMap<>();
        BlockPos lever = layout().lever();
        Watch down = new Watch();
        Watch up = new Watch();
        GameTestSequence sequence = settled(helper, tower, built).thenExecute(() -> {
            down.lever = helper.getTick();
            helper.pullLever(lever);
        });
        for (int i = 0; i < 120; i++) {
            sequence = sequence.thenIdle(1).thenExecute(() -> down.look(helper, tower, true));
        }
        sequence
                .thenExecute(() -> {
                    long gone = tower.stream().filter(p -> helper.getBlockState(p).isAir()).count();
                    helper.assertTrue(gone > 0 && gone < tower.size(), "halfway: " + gone + " gone");
                    helper.pullLever(lever);
                })
                .thenWaitUntil(() -> {
                    down.look(helper, tower, true);
                    helper.assertTrue(down.at.size() == tower.size(), "still standing: " + (tower.size() - down.at.size()));
                })
                .thenExecute(() -> {
                    assertTurns(helper, tower, down, false);
                    up.lever = down.lever;
                })
                .thenWaitUntil(() -> {
                    up.look(helper, tower, false);
                    helper.assertTrue(up.at.size() == tower.size(), "still missing: " + (tower.size() - up.at.size()));
                })
                .thenExecute(() -> {
                    long first = up.at.values().stream().mapToLong(Long::longValue).min().orElseThrow();
                    long last = down.at.values().stream().mapToLong(Long::longValue).max().orElseThrow();
                    helper.assertTrue(first > last, "it started going up before it was down");
                    up.lever = first - FIRST_TICKS;          // turns only: the queued flip has no lever moment
                    assertTurns(helper, tower, up, true);
                })
                .thenIdle(40)
                .thenExecute(() -> helper.assertTrue(standing(helper, built),
                        "the tower is not as it was (a block missing, moved or changed)"))
                .thenSucceed();
    }
}
