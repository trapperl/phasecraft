package dev.phasecraft.test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.level.block.RedstoneLampBlock;

/**
 * The light-up dance floor (layout: contraptions/dance_floor.txt, examples station 31): a 6 x 6 floor of redstone
 * lamps, each lit while someone stands on it. Nothing shows but the lamps: under each tile a hidden pressure plate on
 * a phase block feels the dancer's feet, and a hidden phase port gives the power to the lamp.
 */
public class DanceFloorGameTests {
    static final String STRUCTURE = "phasecraft-test:empty_10x4x10";
    private static ContraptionLayout layout;

    static ContraptionLayout layout() {
        if (layout == null) {
            layout = ContraptionLayout.load("dance_floor");
        }
        return layout;
    }

    static List<BlockPos> tiles() {
        List<BlockPos> out = new ArrayList<>();
        BlockPos.betweenClosed(new BlockPos(2, 1, 2), new BlockPos(7, 1, 7)).forEach(p -> out.add(p.immutable()));
        return out;
    }

    /** The lamps lit, as "x,z" in tile order. */
    static List<String> lit(GameTestHelper helper) {
        List<String> out = new ArrayList<>();
        for (BlockPos p : tiles()) {
            if (helper.getBlockState(p).getValue(RedstoneLampBlock.LIT)) {
                out.add(p.getX() + "," + p.getZ());
            }
        }
        return out;
    }

    /** A pig that stays where it is put (no free will), on the tile at x, z. */
    private static Pig dancer(GameTestHelper helper, int x, int z) {
        return helper.spawnWithNoFreeWill(EntityTypes.PIG, new BlockPos(x, 2, z));
    }

    /**
     * Dark with nobody on it; a dancer lights the lamp under them and no other; a second dancer lights theirs too;
     * the first leaves and only their lamp goes out. Nothing of the hidden layer is in the visible world over it.
     */
    @GameTest(structure = STRUCTURE, maxTicks = 200)
    public void eachLampLightsWhileSomeoneStandsOnIt(GameTestHelper helper) {
        layout().build(helper);
        Pig[] first = new Pig[1];
        helper.startSequence()
                .thenIdle(10)
                .thenExecute(() -> {
                    helper.assertValueEqual(lit(helper), List.of(), "lamps lit with nobody on the floor");
                    for (BlockPos p : tiles()) {
                        helper.assertTrue(helper.getBlockState(p.above()).isAir(), "something visible over " + p.toShortString());
                    }
                    first[0] = dancer(helper, 3, 4);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(lit(helper), List.of("3,4"), "lamps lit under one dancer"))
                .thenExecute(() -> dancer(helper, 6, 6))
                .thenWaitUntil(() -> helper.assertValueEqual(Set.copyOf(lit(helper)), Set.of("3,4", "6,6"),
                        "lamps lit under two dancers"))
                .thenExecute(() -> first[0].discard())
                .thenWaitUntil(() -> helper.assertValueEqual(lit(helper), List.of("6,6"), "lamps lit once the first left"))
                .thenIdle(30)
                .thenExecute(() -> helper.assertValueEqual(lit(helper), List.of("6,6"), "the second dancer's lamp stays lit"))
                .thenSucceed();
    }
}
