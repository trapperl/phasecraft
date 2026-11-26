package dev.phasecraft.test;

import dev.phasecraft.PhaseCraftBlocks;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.world.level.block.Blocks;

/**
 * The pixel wall (layout: contraptions/pixel_wall.txt, written by tools/gen_pixels.py; examples station 33): a shifter
 * display. One lever draws a creeper's face on a lime wall: a hidden phase shifter at each of its 20 pixels takes the
 * lime block there and the black wall behind shows through; eyes, nose, mouth and the mouth's corners, a step apart.
 * The lever back takes the face away in the opposite order.
 */
public class PixelWallGameTests {
    /** Game ticks between steps (the generator's STEP, in redstone ticks, times two). */
    static final int STEP_TICKS = 8;
    /** The face, top row first, as tools/gen_pixels.py draws it. */
    static final String[] PICTURE = {
        "........",
        "........",
        ".XX..XX.",
        ".XX..XX.",
        "...XX...",
        "..XXXX..",
        "..XXXX..",
        "..X..X..",
    };
    private static ContraptionLayout layout;

    static ContraptionLayout layout() {
        if (layout == null) {
            layout = ContraptionLayout.load("pixel_wall");
        }
        return layout;
    }

    /** The pixels (where the layout puts a phase shifter), by step: eyes 0, nose 1, mouth 2, the mouth's corners 3. */
    static Map<Integer, List<BlockPos>> steps() {
        List<BlockPos> pixels = layout().hidden(PhaseCraftBlocks.PHASE_SHIFTER);
        int bottom = pixels.stream().mapToInt(BlockPos::getY).min().orElseThrow();
        Map<Integer, List<BlockPos>> steps = new TreeMap<>();
        for (BlockPos p : pixels) {
            int y = p.getY() - bottom;
            int step = y >= 4 ? 0 : y == 3 ? 1 : y >= 1 ? 2 : 3;
            steps.computeIfAbsent(step, k -> new ArrayList<>()).add(p);
        }
        return steps;
    }

    /** The face as drawn on the wall now: X where the lime is gone and the black shows, . where it is lime. */
    static List<String> drawn(GameTestHelper helper, List<BlockPos> pixels) {
        int left = pixels.stream().mapToInt(BlockPos::getX).min().orElseThrow() - 1;    // the picture's column 0
        int top = pixels.stream().mapToInt(BlockPos::getY).max().orElseThrow() + 2;     // its row 0
        int z = pixels.get(0).getZ();
        List<String> rows = new ArrayList<>();
        for (int row = 0; row < 8; row++) {
            StringBuilder line = new StringBuilder();
            for (int col = 0; col < 8; col++) {
                BlockPos p = new BlockPos(left + col, top - row, z);
                boolean gone = helper.getBlockState(p).isAir() && helper.getBlockState(p.south()).is(Blocks.CONCRETE.black());
                line.append(gone ? 'X' : helper.getBlockState(p).is(Blocks.CONCRETE.lime()) ? '.' : '?');
            }
            rows.add(line.toString());
        }
        return rows;
    }

    /** When each pixel changed, game ticks after the lever. */
    private static final class Watch {
        final Map<BlockPos, Long> at = new HashMap<>();
        long lever;

        void look(GameTestHelper helper, List<BlockPos> pixels, boolean drawing) {
            for (BlockPos p : pixels) {
                if (helper.getBlockState(p).isAir() == drawing && !at.containsKey(p)) {
                    at.put(p, helper.getTick() - lever);
                }
            }
        }

        /** Each step's pixels at one tick, the steps STEP_TICKS apart, in order (reversed when wiping). */
        void assertSteps(GameTestHelper helper, Map<Integer, List<BlockPos>> steps, boolean drawing) {
            Map<Integer, Long> tick = new TreeMap<>();
            steps.forEach((step, pixels) -> {
                long first = pixels.stream().mapToLong(at::get).min().orElseThrow();
                long last = pixels.stream().mapToLong(at::get).max().orElseThrow();
                helper.assertTrue(first == last, "step " + step + " not at one tick: " + first + ".." + last);
                tick.put(step, first);
            });
            for (int step = 1; step <= 3; step++) {
                long gap = drawing ? tick.get(step) - tick.get(step - 1) : tick.get(step - 1) - tick.get(step);
                helper.assertTrue(gap == STEP_TICKS, (drawing ? "drawing" : "wiping") + ": steps at " + tick);
            }
        }
    }

    /**
     * The lever draws the creeper's face (the exact picture: the black shows through its 20 pixels, lime elsewhere),
     * eyes first, a step apart; the lever back wipes it, the mouth's corners first; twice.
     */
    @GameTest(structure = "phasecraft-test:pixel_wall", maxTicks = 800)
    public void leverDrawsTheFaceStepByStepAndWipesIt(GameTestHelper helper) {
        layout().build(helper);
        Map<Integer, List<BlockPos>> steps = steps();
        List<BlockPos> pixels = steps.values().stream().flatMap(List::stream).toList();
        List<String> blank = java.util.Collections.nCopies(8, "........");
        BlockPos lever = layout().lever();
        GameTestSequence sequence = helper.startSequence()
                .thenExecute(() -> helper.assertValueEqual(pixels.size(), 20, "pixels"))
                .thenIdle(20)
                .thenExecute(() -> helper.assertValueEqual(drawn(helper, pixels), blank, "the wall before the lever"));
        for (int cycle = 0; cycle < 2; cycle++) {
            Watch draw = new Watch();
            Watch wipe = new Watch();
            sequence = sequence
                    .thenExecute(() -> {
                        draw.lever = helper.getTick();
                        helper.pullLever(lever);
                    })
                    .thenWaitUntil(() -> {
                        draw.look(helper, pixels, true);
                        helper.assertTrue(draw.at.size() == pixels.size(), "pixels still lime: " + pixels.stream()
                                .filter(p -> !draw.at.containsKey(p)).map(p -> p.toShortString().replace(" ", "")).toList());
                    })
                    .thenExecute(() -> {
                        draw.assertSteps(helper, steps, true);
                        helper.assertValueEqual(drawn(helper, pixels), List.of(PICTURE), "the face drawn");
                    })
                    .thenIdle(20)
                    .thenExecute(() -> {
                        wipe.lever = helper.getTick();
                        helper.pullLever(lever);
                    })
                    .thenWaitUntil(() -> {
                        wipe.look(helper, pixels, false);
                        helper.assertTrue(wipe.at.size() == pixels.size(), "pixels still gone: " + (pixels.size() - wipe.at.size()));
                    })
                    .thenExecute(() -> {
                        wipe.assertSteps(helper, steps, false);
                        helper.assertValueEqual(drawn(helper, pixels), blank, "the wall wiped");
                    })
                    .thenIdle(20);
        }
        sequence.thenSucceed();
    }
}
