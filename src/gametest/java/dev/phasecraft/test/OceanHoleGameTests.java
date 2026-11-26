package dev.phasecraft.test;

import dev.phasecraft.PhaseCraftBlocks;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The hole in the ocean (layout: contraptions/ocean_hole.txt, written by tools/gen_ocean.py; examples station 30):
 * a lever opens a dry, glass-walled 3 x 3 shaft to the seabed of a pool 8 deep, and closes it again, the sea
 * flowing back as still water. Nothing of it shows while it is closed. Two hidden area shifters hold the shaft's
 * glass and a filler; hidden line shifters under the seabed take the filler out of the shaft once the walls stand,
 * and put it back before the walls go. Runs with phasecraft:area_shifters on.
 */
public class OceanHoleGameTests {
    static final String STRUCTURE = "phasecraft-test:ocean_hole";
    /** The shaft's box: its glass ring x 7..11, z 16..20, the inside x 8..10, z 17..19; 8 layers of water. */
    static final BlockPos LOW = new BlockPos(7, 1, 16);
    static final BlockPos HIGH = new BlockPos(11, 8, 20);
    private static ContraptionLayout layout;

    static ContraptionLayout layout() {
        if (layout == null) {
            layout = ContraptionLayout.load("ocean_hole");
        }
        return layout;
    }

    static boolean inside(BlockPos p) {
        return p.getX() > LOW.getX() && p.getX() < HIGH.getX() && p.getZ() > LOW.getZ() && p.getZ() < HIGH.getZ();
    }

    static List<BlockPos> box() {
        List<BlockPos> out = new ArrayList<>();
        BlockPos.betweenClosed(LOW, HIGH).forEach(p -> out.add(p.immutable()));
        return out;
    }

    private static boolean stillWater(BlockState state) {
        return state.is(Blocks.WATER) && state.getFluidState().isSource();
    }

    /** Closed: the whole box is still water, as the sea around it. */
    static String closedWrong(GameTestHelper helper) {
        for (BlockPos p : box()) {
            if (!stillWater(helper.getBlockState(p))) {
                return p.toShortString() + " is " + helper.getBlockState(p);
            }
        }
        return null;
    }

    /** Open: the ring glass, the inside dry air. */
    static String openWrong(GameTestHelper helper) {
        for (BlockPos p : box()) {
            BlockState state = helper.getBlockState(p);
            if (inside(p) ? !state.isAir() : !state.is(Blocks.GLASS)) {
                return p.toShortString() + " is " + state;
            }
        }
        return null;
    }

    /** Every water cell's sky light, by layer, the same in the shaft's box as beside it in the pool. */
    static String lightWrong(GameTestHelper helper) {
        for (BlockPos p : box()) {
            BlockPos beside = new BlockPos(5, p.getY(), p.getZ());        // in the pool, west of the box
            int in = helper.getLevel().getBrightness(LightLayer.SKY, helper.absolutePos(p));
            int out = helper.getLevel().getBrightness(LightLayer.SKY, helper.absolutePos(beside));
            if (in != out) {
                return "sky light " + in + " at " + p.toShortString() + ", " + out + " beside it";
            }
        }
        return null;
    }

    private static void assertNothingDropped(GameTestHelper helper) {
        AABB area = new AABB(Vec3.atLowerCornerOf(helper.absolutePos(BlockPos.ZERO)),
                Vec3.atLowerCornerOf(helper.absolutePos(new BlockPos(24, 13, 28))));
        helper.assertTrue(helper.getLevel().getEntitiesOfClass(ItemEntity.class, area).isEmpty(), "items dropped");
    }

    /**
     * Built, the shaft's glass and filler are taken and the sea fills the box. The lever opens a dry shaft, which
     * stays dry; flipped back, the sea closes over it again, still water with no glass or filler left; twice.
     */
    @GameTest(structure = STRUCTURE, environment = AreaShifterGameTests.RULE_ON, maxTicks = 1200)
    public void leverOpensADryShaftAndTheSeaClosesOverIt(GameTestHelper helper) {
        layout().build(helper);
        BlockPos lever = layout().lever();
        GameTestSequence sequence = helper.startSequence()
                .thenExecute(() -> helper.assertValueEqual(layout().hidden(PhaseCraftBlocks.AREA_SHIFTER), List.of(LOW, HIGH),
                        "the corners"))
                .thenWaitUntil(() -> helper.assertTrue(closedWrong(helper) == null, "not yet sea: " + closedWrong(helper)));
        for (int cycle = 0; cycle < 2; cycle++) {
            sequence = sequence
                    .thenIdle(20)
                    .thenExecute(() -> helper.pullLever(lever))
                    .thenWaitUntil(() -> helper.assertTrue(openWrong(helper) == null, "not yet open: " + openWrong(helper)))
                    .thenIdle(60)
                    .thenExecute(() -> {
                        helper.assertTrue(openWrong(helper) == null, "the open shaft changed: " + openWrong(helper));
                        assertNothingDropped(helper);
                    })
                    .thenExecute(() -> helper.pullLever(lever))
                    .thenWaitUntil(() -> helper.assertTrue(closedWrong(helper) == null, "not yet sea: " + closedWrong(helper)))
                    .thenExecute(() -> assertNothingDropped(helper))
                    // light is computed on its own thread: give it real time (see PhasedLightGameTests)
                    .thenWaitUntil(() -> helper.assertTrue(lightWrong(helper) == null, "" + lightWrong(helper)));
        }
        sequence.thenSucceed();
    }

    /**
     * A pig stands on the seabed in the shaft when the lever closes it (BUILD_RULES B3, leaving halfway): the filler
     * coming back lifts it up its column to the surface, and the sea closes as ever, with no filler left standing
     * in it and nothing dropped; the shaft opens and closes as before.
     */
    @GameTest(structure = STRUCTURE, environment = AreaShifterGameTests.RULE_ON, maxTicks = 1200)
    public void someoneInTheShaftWhenItClosesIsLiftedOut(GameTestHelper helper) {
        layout().build(helper);
        BlockPos lever = layout().lever();
        Pig[] pig = new Pig[1];
        double[] highest = {Double.NEGATIVE_INFINITY};
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(closedWrong(helper) == null, "not yet sea: " + closedWrong(helper)))
                .thenIdle(20)
                .thenExecute(() -> helper.pullLever(lever))
                .thenWaitUntil(() -> helper.assertTrue(openWrong(helper) == null, "not yet open: " + openWrong(helper)))
                .thenExecute(() -> {
                    pig[0] = helper.spawnWithNoFreeWill(EntityTypes.PIG, new BlockPos(9, 1, 18));
                    helper.pullLever(lever);
                })
                .thenWaitUntil(() -> {
                    highest[0] = Math.max(highest[0], pig[0].getY() - helper.absolutePos(BlockPos.ZERO).getY());
                    helper.assertTrue(closedWrong(helper) == null, "not yet sea: " + closedWrong(helper));
                })
                .thenIdle(60)
                .thenExecute(() -> {
                    helper.assertTrue(closedWrong(helper) == null, "the sea changed: " + closedWrong(helper));
                    helper.assertValueEqual(highest[0], (double) HIGH.getY() + 1, "the highest the pig was lifted");
                    assertNothingDropped(helper);
                    pig[0].discard();
                    helper.pullLever(lever);
                })
                .thenWaitUntil(() -> helper.assertTrue(openWrong(helper) == null, "not yet open again: " + openWrong(helper)))
                .thenExecute(() -> helper.pullLever(lever))
                .thenWaitUntil(() -> helper.assertTrue(closedWrong(helper) == null, "not yet sea again: " + closedWrong(helper)))
                .thenExecute(() -> assertNothingDropped(helper))
                .thenSucceed();
    }
}
