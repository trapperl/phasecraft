package dev.phasecraft.test;

import dev.phasecraft.PhaseCraftBlocks;
import dev.phasecraft.layer.HiddenLayer;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * Power crossing between layers through phase ports.
 *
 * <p>Rule under test: power crosses only at a phase port. A port gives out,
 * in its own layer, the power that reaches its position in the other layer
 * (from a source there, or from that position's neighbours there). Ports
 * ignore other ports, so they cannot hold each other on. Crossing takes up
 * to one game tick.</p>
 */
public class PhaseCraftCrossLayerGameTests {
    private static final BlockState STONE = Blocks.STONE.defaultBlockState();
    private static final BlockState FLOOR_LEVER = Blocks.LEVER.defaultBlockState()
            .setValue(LeverBlock.FACE, AttachFace.FLOOR)
            .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH);

    private static BlockState port() {
        return PhaseCraftBlocks.PHASE_PORT.defaultBlockState();
    }

    private static boolean lit(BlockState lamp) {
        return lamp.is(Blocks.REDSTONE_LAMP) && lamp.getValue(RedstoneLampBlock.LIT);
    }

    /** A piston base, extended. (While moving, the position holds minecraft:moving_piston instead.) */
    private static boolean extendedPiston(BlockState state) {
        return state.is(Blocks.PISTON) && state.getValue(BlockStateProperties.EXTENDED);
    }

    private static boolean retractedPiston(BlockState state) {
        return state.is(Blocks.PISTON) && !state.getValue(BlockStateProperties.EXTENDED);
    }

    private static int power(BlockState state) {
        return state.getValue(BlockStateProperties.POWER);
    }

    /** Without a port, nothing crosses: a visible lever does not light a hidden lamp at the same spot. */
    @GameTest(maxTicks = 40)
    public void nothingCrossesWithoutAPort(GameTestHelper helper) {
        BlockPos lever = new BlockPos(2, 2, 2);
        BlockPos at = helper.absolutePos(lever);
        HiddenLayer layer = HiddenLayer.of(helper.getLevel());
        helper.setBlock(lever.below(), STONE);
        helper.setBlock(lever, FLOOR_LEVER.setValue(LeverBlock.POWERED, true));
        layer.setBlock(at, Blocks.REDSTONE_LAMP.defaultBlockState());
        layer.setBlock(at.east(), Blocks.REDSTONE_LAMP.defaultBlockState());

        helper.startSequence()
                .thenIdle(10)
                .thenExecute(() -> {
                    helper.assertFalse(lit(layer.getBlock(at)), "hidden lamp at the lever's spot lit without a port");
                    helper.assertFalse(lit(layer.getBlock(at.east())), "hidden lamp next to it lit without a port");
                    layer.removeBlock(at);
                    layer.removeBlock(at.east());
                })
                .thenSucceed();
    }

    /** A visible lever, through a hidden port at the same spot, lights the hidden lamp next to the port. */
    @GameTest(maxTicks = 100)
    public void visibleLeverThroughHiddenPortLightsHiddenLamp(GameTestHelper helper) {
        BlockPos lever = new BlockPos(2, 2, 2);
        BlockPos at = helper.absolutePos(lever);
        BlockPos lamp = at.east();
        HiddenLayer layer = HiddenLayer.of(helper.getLevel());
        helper.setBlock(lever.below(), STONE);
        helper.setBlock(lever, FLOOR_LEVER);
        layer.setBlock(at, port());
        layer.setBlock(lamp, Blocks.REDSTONE_LAMP.defaultBlockState());

        helper.startSequence()
                .thenExecute(() -> helper.assertFalse(lit(layer.getBlock(lamp)), "hidden lamp lit before the lever"))
                .thenExecute(() -> helper.pullLever(lever))
                .thenWaitUntil(() -> helper.assertValueEqual(power(layer.getBlock(at)), 15, "hidden port power"))
                .thenWaitUntil(() -> helper.assertTrue(lit(layer.getBlock(lamp)), "hidden lamp not lit through the port"))
                .thenExecute(() -> helper.pullLever(lever))
                .thenWaitUntil(() -> helper.assertFalse(lit(layer.getBlock(lamp)), "hidden lamp still lit after lever off"))
                .thenExecute(() -> {
                    helper.assertValueEqual(power(layer.getBlock(at)), 0, "hidden port power after lever off");
                    layer.removeBlock(lamp);
                    layer.removeBlock(at);
                })
                .thenSucceed();
    }

    /** A hidden lever, through a visible port at the same spot, lights the visible lamp next to the port. */
    @GameTest(maxTicks = 100)
    public void hiddenLeverThroughVisiblePortLightsVisibleLamp(GameTestHelper helper) {
        BlockPos portRel = new BlockPos(3, 2, 3);
        BlockPos lampRel = portRel.east();
        BlockPos at = helper.absolutePos(portRel);
        HiddenLayer layer = HiddenLayer.of(helper.getLevel());
        helper.setBlock(portRel, port());
        helper.setBlock(lampRel, Blocks.REDSTONE_LAMP);
        layer.setBlock(at.below(), STONE);
        layer.setBlock(at, FLOOR_LEVER);

        helper.startSequence()
                .thenExecute(() -> layer.setBlock(at, FLOOR_LEVER.setValue(LeverBlock.POWERED, true)))
                .thenWaitUntil(() -> helper.assertTrue(lit(helper.getBlockState(lampRel)), "visible lamp not lit through the port"))
                .thenExecute(() -> layer.setBlock(at, FLOOR_LEVER))
                .thenWaitUntil(() -> helper.assertFalse(lit(helper.getBlockState(lampRel)), "visible lamp still lit after hidden lever off"))
                .thenExecute(() -> {
                    layer.removeBlock(at);
                    layer.removeBlock(at.below());
                })
                .thenSucceed();
    }

    /**
     * First half of the hidden piston door: a visible lever, through a hidden
     * port, drives a hidden piston, which extends in the hidden layer and
     * retracts again. The visible world is untouched.
     */
    @GameTest(maxTicks = 120)
    public void visibleLeverDrivesHiddenPiston(GameTestHelper helper) {
        BlockPos lever = new BlockPos(2, 2, 4);
        BlockPos at = helper.absolutePos(lever);
        BlockPos piston = at.east();
        BlockPos head = piston.east();
        HiddenLayer layer = HiddenLayer.of(helper.getLevel());
        helper.setBlock(lever.below(), STONE);
        helper.setBlock(lever, FLOOR_LEVER);
        layer.setBlock(at, port());
        layer.setBlock(piston, Blocks.PISTON.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.EAST));

        helper.startSequence()
                .thenExecute(() -> helper.pullLever(lever))
                .thenWaitUntil(() -> helper.assertTrue(extendedPiston(layer.getBlock(piston)),
                        "hidden piston did not extend: " + layer.getBlock(piston)))
                .thenWaitUntil(() -> helper.assertTrue(layer.getBlock(head).is(Blocks.PISTON_HEAD),
                        "no piston head in the hidden layer: " + layer.getBlock(head)))
                .thenExecute(() -> helper.assertBlockPresent(Blocks.AIR, lever.east().east()))
                .thenExecute(() -> helper.pullLever(lever))
                .thenWaitUntil(() -> helper.assertTrue(retractedPiston(layer.getBlock(piston)),
                        "hidden piston did not retract: " + layer.getBlock(piston)))
                .thenExecute(() -> {
                    layer.removeBlock(head);
                    layer.removeBlock(piston);
                    layer.removeBlock(at);
                })
                .thenSucceed();
    }

    /**
     * Ports ignore other ports. Under a powered visible lever sits hidden port
     * A; beside it (one east) is visible port B, which must not take power from
     * A; one further east, a visible and a hidden port share a spot (C) and
     * must not power each other either. Nothing stays on after the lever is off.
     */
    @GameTest(maxTicks = 100)
    public void portsDoNotPowerEachOther(GameTestHelper helper) {
        BlockPos lever = new BlockPos(2, 2, 6);
        BlockPos a = helper.absolutePos(lever);
        BlockPos bRel = lever.east();
        BlockPos cRel = lever.east().east();
        BlockPos c = helper.absolutePos(cRel);
        HiddenLayer layer = HiddenLayer.of(helper.getLevel());
        helper.setBlock(lever.below(), STONE);
        helper.setBlock(lever, FLOOR_LEVER);
        layer.setBlock(a, port());
        helper.setBlock(bRel, port());
        helper.setBlock(cRel, port());
        layer.setBlock(c, port());

        helper.startSequence()
                .thenExecute(() -> helper.pullLever(lever))
                .thenWaitUntil(() -> helper.assertValueEqual(power(layer.getBlock(a)), 15, "hidden port A under the lever"))
                .thenIdle(5)
                .thenExecute(() -> {
                    helper.assertValueEqual(power(helper.getBlockState(bRel)), 0, "visible port B next to hidden port A");
                    helper.assertValueEqual(power(helper.getBlockState(cRel)), 0, "visible port at shared spot C");
                    helper.assertValueEqual(power(layer.getBlock(c)), 0, "hidden port at shared spot C");
                })
                .thenExecute(() -> helper.pullLever(lever))
                .thenWaitUntil(() -> helper.assertValueEqual(power(layer.getBlock(a)), 0, "hidden port A after lever off"))
                .thenExecute(() -> {
                    layer.removeBlock(a);
                    layer.removeBlock(c);
                })
                .thenSucceed();
    }

    /** Hidden chunks tick where the visible world ticks, and not where it does not. */
    @GameTest(maxTicks = 100)
    public void hiddenChunksTickWithVisibleChunks(GameTestHelper helper) {
        HiddenLayer layer = HiddenLayer.of(helper.getLevel());
        BlockPos here = helper.absolutePos(new BlockPos(1, 1, 1));
        BlockPos far = here.offset(2_000_000, 0, 2_000_000);
        helper.succeedWhen(() -> {
            helper.assertTrue(layer.level().shouldTickBlocksAt(here), "hidden chunk under the test does not tick");
            helper.assertFalse(layer.level().shouldTickBlocksAt(far), "hidden chunk ticks where the visible world is not loaded");
        });
    }
}
