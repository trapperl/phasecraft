package dev.phasecraft.test;

import dev.phasecraft.PhaseCraftBlocks;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.phys.Vec3;

/**
 * The appearing bridge (the person's idea, 2026-11-24: "Staircase appearing, maybe just in front of the player"; a
 * bridge first): a bridge across a chasm whose blocks are there only within {@link #REACH} steps of someone, so it
 * appears ahead and vanishes behind. Each block is held by a hidden phase shifter; hidden strings at head height sense
 * who is near (layout: contraptions/appearing_bridge.txt, written by tools/gen_path.py).
 */
public class AppearingBridgeGameTests {
    static final int REACH = 3;
    /** Game ticks to wait after moving: a string is let go only every 10 ticks, then 4 redstone ticks of wiring. */
    static final int SETTLE = 24;
    private static ContraptionLayout layout;

    static ContraptionLayout layout() {
        if (layout == null) {
            layout = ContraptionLayout.load("appearing_bridge");
        }
        return layout;
    }

    /** The bridge's blocks, in order across (where the hidden shifters are). */
    static List<BlockPos> steps() {
        return layout().hidden(PhaseCraftBlocks.PHASE_SHIFTER).stream()
                .sorted(Comparator.comparingInt(BlockPos::getX)).toList();
    }

    private static TreeSet<Integer> there(GameTestHelper helper, List<BlockPos> steps) {
        return IntStream.range(0, steps.size()).filter(k -> !helper.getBlockState(steps.get(k)).isAir())
                .boxed().collect(Collectors.toCollection(TreeSet::new));
    }

    private static TreeSet<Integer> within(int at, int count) {
        return IntStream.range(0, count).filter(k -> Math.abs(k - at) <= REACH)
                .boxed().collect(Collectors.toCollection(TreeSet::new));
    }

    /** Where someone standing over step j stands, relative to the test (j may be off the bridge, on the approaches). */
    private static Vec3 over(List<BlockPos> steps, int j, double lift) {
        BlockPos first = steps.getFirst();
        return new Vec3(first.getX() + j + 0.5, first.getY() + 1 + lift, first.getZ() + 0.5);
    }

    private static void move(GameTestHelper helper, Villager v, List<BlockPos> steps, int j, double lift) {
        Vec3 p = helper.absoluteVec(over(steps, j, lift));
        v.teleportTo(p.x, p.y, p.z);
    }

    private static Villager walker(GameTestHelper helper, List<BlockPos> steps, int j) {
        Villager v = helper.spawn(EntityTypes.VILLAGER, over(steps, j, 0));
        v.setNoAi(true);
        v.setNoGravity(true);
        v.setSilent(true);
        return v;
    }

    /** Someone walking across, from before one end to past the other, finds exactly the steps within REACH there. */
    @GameTest(structure = "phasecraft-test:appearing_bridge", maxTicks = 1400)
    public void blocksAreThereOnlyNearSomeone(GameTestHelper helper) {
        layout().build(helper);
        List<BlockPos> steps = steps();
        int n = steps.size();
        Villager[] v = new Villager[1];
        GameTestSequence sequence = helper.startSequence()
                .thenIdle(SETTLE)
                .thenExecute(() -> {
                    helper.assertValueEqual(n, 14, "steps");
                    helper.assertValueEqual(there(helper, steps), new TreeSet<Integer>(), "steps there with nobody near");
                    v[0] = walker(helper, steps, -REACH - 2);
                });
        for (int j = -REACH - 2; j <= n + REACH + 1; j++) {
            int at = j;
            sequence = sequence
                    .thenExecute(() -> move(helper, v[0], steps, at, 0))
                    .thenIdle(SETTLE)
                    .thenExecute(() -> helper.assertValueEqual(there(helper, steps), within(at, n),
                            "steps there with someone over step " + at));
        }
        sequence.thenExecute(() -> v[0].discard())
                .thenIdle(SETTLE)
                .thenExecute(() -> helper.assertValueEqual(there(helper, steps), new TreeSet<Integer>(),
                        "steps there after everyone left"))
                .thenSucceed();
    }

    /** Jumping on the bridge does not drop the step underfoot: the head leaves the string only at the top of a jump. */
    @GameTest(structure = "phasecraft-test:appearing_bridge", maxTicks = 800)
    public void jumpingKeepsTheStepUnderfoot(GameTestHelper helper) {
        layout().build(helper);
        List<BlockPos> steps = steps();
        int mid = steps.size() / 2;
        Villager[] v = new Villager[1];
        GameTestSequence sequence = helper.startSequence()
                .thenIdle(SETTLE)
                .thenExecute(() -> v[0] = walker(helper, steps, mid))
                .thenIdle(SETTLE)
                .thenExecute(() -> helper.assertValueEqual(there(helper, steps), within(mid, steps.size()), "steps there"));
        // a vanilla jump: the feet are more than 1.16 blocks up (head clear of the string) for at most 4 ticks
        double[] heights = {0.42, 0.75, 1.0, 1.17, 1.25, 1.25, 1.17, 1.01, 0.77, 0.46, 0.08, 0};
        // a string pressed is rechecked 10 ticks later: pauses of 0 to 11 ticks between jumps bring that recheck to
        // every tick of a jump, the top included
        for (int pause = 0; pause < 12; pause++) {
            sequence = sequence.thenIdle(pause + 1);
            for (double h : heights) {
                sequence = sequence
                        .thenExecute(() -> move(helper, v[0], steps, mid, h))
                        .thenIdle(1)
                        .thenExecute(() -> helper.assertFalse(helper.getBlockState(steps.get(mid)).isAir(),
                                "the step underfoot went during a jump"));
            }
        }
        sequence.thenSucceed();
    }
}
