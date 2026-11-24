package dev.phasecraft.test;

import dev.phasecraft.PhaseCraftBlocks;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The parting waterfall (the person's idea, 2026-11-24: "Waterfall opening in the middle"): a pool on a cliff spills
 * in five ribbons of water between rock ribs. One visible lever stops them from the middle outward, each draining
 * from the top, uncovering a cave; flipped back, they pour again from the outside in. A hidden phase shifter at each
 * ribbon's gate, on the cliff's edge, puts a stone back to stop it (layout: contraptions/waterfall.txt, written by
 * tools/gen_waterfall.py).
 */
public class WaterfallGameTests {
    /** Game ticks between rings (the generator's STEP, 3 redstone ticks). */
    static final int STEP_TICKS = 6;
    private static ContraptionLayout layout;

    static ContraptionLayout layout() {
        if (layout == null) {
            layout = ContraptionLayout.load("waterfall");
        }
        return layout;
    }

    /** The gates (where the hidden shifters are), by ring: the middle 0, the next two 1, the outer two 2. */
    static Map<Integer, List<BlockPos>> rings() {
        List<BlockPos> gates = layout().hidden(PhaseCraftBlocks.PHASE_SHIFTER);
        int cx = gates.stream().mapToInt(BlockPos::getX).sum() / gates.size();
        Map<Integer, List<BlockPos>> rings = new java.util.TreeMap<>();
        for (BlockPos g : gates) {
            rings.computeIfAbsent(Math.abs(g.getX() - cx) / 2, r -> new java.util.ArrayList<>()).add(g);
        }
        return rings;
    }

    /** Halfway down a ribbon: water while it runs, air once it has drained. */
    private static BlockPos fall(BlockPos gate) {
        return gate.offset(0, -7, -1);
    }

    private static boolean water(GameTestHelper helper, BlockPos p) {
        return helper.getBlockState(p).is(Blocks.WATER);
    }

    /** Each ring's gates switch together, STEP_TICKS after the ring before (middle first opening, outer first closing). */
    private static void assertOrder(GameTestHelper helper, Map<Integer, List<BlockPos>> rings, Map<BlockPos, Long> at, boolean opening) {
        Map<Integer, Long> tick = new HashMap<>();
        StringBuilder seen = new StringBuilder();
        rings.forEach((ring, gates) -> {
            long first = gates.stream().mapToLong(at::get).min().orElseThrow();
            long last = gates.stream().mapToLong(at::get).max().orElseThrow();
            seen.append(" ring ").append(ring).append(": ").append(first).append(first == last ? "" : ".." + last);
            helper.assertTrue(first == last, "a ring's gates did not switch together:" + seen);
            tick.put(ring, first);
        });
        for (int ring = 1; ring <= 2; ring++) {
            long gap = opening ? tick.get(ring) - tick.get(ring - 1) : tick.get(ring - 1) - tick.get(ring);
            helper.assertTrue(gap == STEP_TICKS, (opening ? "opening" : "closing") + ": ring " + ring + " out of step:" + seen);
        }
    }

    /** The lever parts the waterfall from the middle outward and closes it from the outside in, twice. */
    @GameTest(structure = "phasecraft-test:waterfall", maxTicks = 1600)
    public void leverPartsTheWaterfallFromTheMiddle(GameTestHelper helper) {
        layout().build(helper);
        Map<Integer, List<BlockPos>> rings = rings();
        List<BlockPos> gates = rings.values().stream().flatMap(List::stream).toList();
        BlockPos lever = layout().lever();
        var sequence = helper.startSequence()
                .thenExecute(() -> helper.assertValueEqual(gates.size(), 5, "gates"))
                // the shifters took their stones as they were placed: the ribbons run
                .thenWaitUntil(() -> helper.assertTrue(gates.stream().allMatch(g -> water(helper, fall(g))),
                        "the ribbons are not all running"));
        for (int cycle = 0; cycle < 2; cycle++) {
            Map<BlockPos, Long> stopped = new HashMap<>();
            Map<BlockPos, Long> started = new HashMap<>();
            sequence = sequence
                    .thenIdle(10)
                    .thenExecute(() -> helper.pullLever(lever))
                    .thenWaitUntil(() -> {
                        for (BlockPos g : gates) {
                            if (helper.getBlockState(g).is(Blocks.STONE)) {
                                stopped.putIfAbsent(g, helper.getTick());
                            }
                        }
                        helper.assertTrue(stopped.size() == gates.size(), "gates closed: " + stopped.size());
                    })
                    .thenExecute(() -> assertOrder(helper, rings, stopped, true))
                    .thenWaitUntil(() -> helper.assertTrue(gates.stream().noneMatch(g -> water(helper, fall(g))),
                            "the ribbons did not all drain"))
                    .thenExecute(() -> helper.pullLever(lever))
                    .thenWaitUntil(() -> {
                        for (BlockPos g : gates) {
                            if (!helper.getBlockState(g).is(Blocks.STONE)) {
                                started.putIfAbsent(g, helper.getTick());
                            }
                        }
                        helper.assertTrue(started.size() == gates.size(), "gates open: " + started.size());
                    })
                    .thenExecute(() -> assertOrder(helper, rings, started, false))
                    .thenWaitUntil(() -> helper.assertTrue(gates.stream().allMatch(g -> water(helper, fall(g))),
                            "the ribbons did not all run again"));
        }
        sequence.thenSucceed();
    }
}
