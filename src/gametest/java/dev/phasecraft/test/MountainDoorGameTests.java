package dev.phasecraft.test;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The mountain door (the person's idea, 2026-11-24: "Door into a mountain that opens/closes with a pattern"): an
 * arched door, 7 wide and 9 high (57 blocks), in a mountain's face. One visible lever opens it like a clock hand
 * sweeping round, from twelve o'clock clockwise as you face it, an eighth at a time; flipped back, it winds shut the
 * other way. Every door block is held by a hidden phase shifter at its own spot; all the wiring is hidden (layout:
 * contraptions/mountain_door.txt, written by tools/gen_mountain.py).
 */
public class MountainDoorGameTests {
    /** Game ticks between eighths (the generator's STEP, in redstone ticks, times two). */
    static final int STEP_TICKS = 4;
    static final int BLOCKS = 57;
    static final int SECTORS = 8;
    private static ContraptionLayout layout;

    /** Loaded on first use, not with the class: test classes load before the mod's blocks are registered. */
    static ContraptionLayout layout() {
        if (layout == null) {
            layout = ContraptionLayout.load("mountain_door");
        }
        return layout;
    }

    /**
     * The eighth of the clock a door block is in: 0 from twelve o'clock, clockwise as seen from the front, where the
     * viewer's right is -x (u = -dx, v = dy from the centre). The generator's rule, in whole numbers.
     */
    static int sector(int u, int v) {
        if (u == 0 && v == 0) {
            return 0;
        }
        if (u >= 0 && v > 0) {
            return u < v ? 0 : 1;
        }
        if (u > 0) {
            return -v < u ? 2 : 3;
        }
        if (v < 0) {
            return -u < -v ? 4 : 5;
        }
        return v < -u ? 6 : 7;
    }

    /** The door blocks, found where the layout put them, grouped by eighth around the door's centre. */
    static Map<Integer, List<BlockPos>> rings(GameTestHelper helper) {
        // Relative positions straight from the area's size (GameTestHelper.relativePos turns an unrotated test
        // half round: its inverse of no rotation is CLOCKWISE_180).
        List<BlockPos> door = new ArrayList<>();
        var bounds = helper.getBounds();
        for (BlockPos rel : BlockPos.betweenClosed(0, 0, 0, (int) bounds.getXsize() - 1, (int) bounds.getYsize() - 1,
                (int) bounds.getZsize() - 1)) {
            if (helper.getBlockState(rel).is(layout().door())) {
                door.add(rel.immutable());
            }
        }
        int minX = door.stream().mapToInt(BlockPos::getX).min().orElseThrow();
        int maxX = door.stream().mapToInt(BlockPos::getX).max().orElseThrow();
        int cx = (minX + maxX) / 2;
        int cy = door.stream().mapToInt(BlockPos::getY).min().orElseThrow() + 4;
        Map<Integer, List<BlockPos>> rings = new TreeMap<>();
        for (BlockPos p : door) {
            rings.computeIfAbsent(sector(cx - p.getX(), p.getY() - cy), r -> new ArrayList<>()).add(p);
        }
        return rings;
    }

    /** Ticks at which each door block went (open) or came back (closed), filled in while waiting. */
    private static final class Watch {
        final Map<BlockPos, Long> at = new java.util.HashMap<>();

        void look(GameTestHelper helper, List<BlockPos> blocks, boolean open) {
            for (BlockPos p : blocks) {
                boolean there = helper.getBlockState(p).is(layout().door());
                if (there != open && !at.containsKey(p)) {
                    at.put(p, helper.getTick());
                }
            }
        }

        boolean all(List<BlockPos> blocks) {
            return at.keySet().containsAll(blocks);
        }
    }

    private static List<BlockPos> all(Map<Integer, List<BlockPos>> rings) {
        return rings.values().stream().flatMap(List::stream).toList();
    }

    /** Each eighth at one tick; one after another, STEP_TICKS apart, clockwise when opening, back when closing. */
    private static void assertOrder(GameTestHelper helper, Map<Integer, List<BlockPos>> rings, Watch watch, boolean opening) {
        Map<Integer, Long> tick = new TreeMap<>();
        StringBuilder seen = new StringBuilder();
        rings.forEach((ring, blocks) -> {
            long first = blocks.stream().mapToLong(watch.at::get).min().orElseThrow();
            long last = blocks.stream().mapToLong(watch.at::get).max().orElseThrow();
            seen.append(" ring ").append(ring).append(": ").append(first).append(first == last ? "" : ".." + last);
            if (first != last) {
                StringBuilder each = new StringBuilder();
                blocks.forEach(p -> each.append(' ').append(p.toShortString().replace(" ", "")).append('@').append(watch.at.get(p)));
                throw helper.assertionException("ring " + ring + " did not move as one:" + seen + ";" + each);
            }
            tick.put(ring, first);
        });
        for (int ring = 1; ring < SECTORS; ring++) {
            long gap = opening ? tick.get(ring) - tick.get(ring - 1) : tick.get(ring - 1) - tick.get(ring);
            helper.assertTrue(gap == STEP_TICKS, (opening ? "opening" : "closing") + ": ring " + ring + " not "
                    + STEP_TICKS + " ticks " + (opening ? "after" : "before") + " ring " + (ring - 1) + ":" + seen);
        }
    }

    /** One lever sweeps the door open clockwise and winds it shut the other way, twice. */
    @GameTest(structure = "phasecraft-test:mountain_door", maxTicks = 1000)
    public void leverSweepsTheDoorOpenLikeAClockHand(GameTestHelper helper) {
        layout().build(helper);
        Map<Integer, List<BlockPos>> rings = rings(helper);
        List<BlockPos> door = all(rings);
        BlockPos lever = layout().lever();
        Watch[] watch = new Watch[4];
        for (int i = 0; i < 4; i++) {
            watch[i] = new Watch();
        }
        var sequence = helper.startSequence()
                .thenExecute(() -> {
                    helper.assertValueEqual(door.size(), BLOCKS, "door blocks");
                    helper.assertValueEqual(rings.keySet().size(), SECTORS, "eighths");
                })
                .thenIdle(1);
        // closed, every tick, while the hidden wiring settles after building
        for (int i = 0; i < 20; i++) {
            sequence = sequence
                    .thenExecute(() -> helper.assertTrue(door.stream().allMatch(p -> helper.getBlockState(p).is(layout().door())),
                            "a door block went while the wiring settled"))
                    .thenIdle(1);
        }
        for (int cycle = 0; cycle < 2; cycle++) {
            Watch opened = watch[2 * cycle];
            Watch closed = watch[2 * cycle + 1];
            sequence = sequence
                    .thenExecute(() -> helper.pullLever(lever))
                    .thenWaitUntil(() -> {
                        opened.look(helper, door, true);
                        helper.assertTrue(opened.all(door), "not open yet: " + opened.at.size() + " of " + BLOCKS + " gone");
                    })
                    .thenExecute(() -> assertOrder(helper, rings, opened, true))
                    .thenIdle(20)
                    .thenExecute(() -> helper.assertTrue(door.stream().allMatch(p -> helper.getBlockState(p).isAir()),
                            "the door did not stay open"))
                    .thenExecute(() -> helper.pullLever(lever))
                    .thenWaitUntil(() -> {
                        closed.look(helper, door, false);
                        helper.assertTrue(closed.all(door), "not closed yet: " + closed.at.size() + " of " + BLOCKS + " back");
                    })
                    .thenExecute(() -> assertOrder(helper, rings, closed, false))
                    .thenIdle(20)
                    .thenExecute(() -> helper.assertTrue(door.stream().allMatch(p -> helper.getBlockState(p).is(layout().door())),
                            "the door did not stay closed"));
        }
        sequence.thenSucceed();
    }
}
