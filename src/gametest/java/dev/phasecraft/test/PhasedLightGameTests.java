package dev.phasecraft.test;

import dev.phasecraft.PhaseCraftBlocks;
import dev.phasecraft.layer.HiddenLayer;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * Phased light: a hidden light source standing on a phase block (or hanging on
 * its side) lights the visible world at its own spot, by vanilla's light rules.
 * Without a phase block, hidden light stays in the hidden layer.
 *
 * <p>Light is computed on the server's light thread, at its own pace, while the
 * GameTest server runs ticks as fast as it can (80 ticks can pass in a second),
 * so these tests allow many ticks. (Vanilla also starts queued light work only
 * in a server's spare time between ticks, which a GameTest server has none of;
 * PhasedLights starts it itself.)</p>
 */
public class PhasedLightGameTests {
    /** The spot of the hidden light; visible air. */
    private static final BlockPos SPOT = new BlockPos(3, 2, 3);

    private static void floor(GameTestHelper helper) {
        for (int x = 0; x <= 7; x++) {
            for (int z = 0; z <= 7; z++) {
                helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
            }
        }
    }

    private static int visibleBlockLight(GameTestHelper helper, BlockPos rel) {
        return helper.getLevel().getBrightness(LightLayer.BLOCK, helper.absolutePos(rel));
    }

    private static void assertLight(GameTestHelper helper, BlockPos rel, int expected, String what) {
        helper.assertValueEqual(visibleBlockLight(helper, rel), expected, what);
    }

    /** Hidden glowstone on a phase block lights the visible world: 15 at its spot, fading by vanilla's rules. */
    @GameTest(maxTicks = 600)
    public void phasedGlowstoneLightsVisibleWorld(GameTestHelper helper) {
        floor(helper);
        HiddenLayer hidden = HiddenLayer.of(helper.getLevel());
        hidden.setBlock(helper.absolutePos(SPOT.below()), PhaseCraftBlocks.PHASE_BLOCK.defaultBlockState());
        hidden.setBlock(helper.absolutePos(SPOT), Blocks.GLOWSTONE.defaultBlockState());

        helper.startSequence()
                .thenWaitUntil(() -> assertLight(helper, SPOT, 15, "visible block light at the phased glowstone's spot"))
                .thenWaitUntil(() -> assertLight(helper, SPOT.east(2), 13, "visible block light two blocks away"))
                .thenSucceed();
    }

    /** Hidden glowstone on ordinary stone gives no visible light. */
    @GameTest(maxTicks = 600)
    public void hiddenGlowstoneWithoutPhaseBlockGivesNoVisibleLight(GameTestHelper helper) {
        floor(helper);
        HiddenLayer hidden = HiddenLayer.of(helper.getLevel());
        hidden.setBlock(helper.absolutePos(SPOT.below()), Blocks.STONE.defaultBlockState());
        hidden.setBlock(helper.absolutePos(SPOT), Blocks.GLOWSTONE.defaultBlockState());

        helper.startSequence()
                .thenIdle(200)
                .thenExecute(() -> assertLight(helper, SPOT, 0, "visible block light next to unphased hidden glowstone"))
                .thenSucceed();
    }

    /** A phased redstone lamp switches the visible light on and off with hidden redstone. */
    @GameTest(maxTicks = 600)
    public void phasedLampFollowsRedstone(GameTestHelper helper) {
        floor(helper);
        HiddenLayer hidden = HiddenLayer.of(helper.getLevel());
        hidden.setBlock(helper.absolutePos(SPOT.below()), PhaseCraftBlocks.PHASE_BLOCK.defaultBlockState());
        hidden.setBlock(helper.absolutePos(SPOT), Blocks.REDSTONE_LAMP.defaultBlockState());
        BlockPos power = helper.absolutePos(SPOT.east());

        helper.startSequence()
                .thenIdle(5)
                .thenExecute(() -> assertLight(helper, SPOT, 0, "visible light from the unlit phased lamp"))
                .thenExecute(() -> hidden.setBlock(power, Blocks.REDSTONE_BLOCK.defaultBlockState()))
                .thenWaitUntil(() -> assertLight(helper, SPOT, 15, "visible light from the lit phased lamp"))
                .thenExecute(() -> hidden.removeBlock(power))
                .thenWaitUntil(() -> assertLight(helper, SPOT, 0, "visible light after the phased lamp went out (hidden lamp: "
                        + hidden.getBlock(helper.absolutePos(SPOT)) + ")"))
                .thenSucceed();
    }

    /** Taking the phase block away takes the light out of the visible world. */
    @GameTest(maxTicks = 600)
    public void removingPhaseBlockTakesLightAway(GameTestHelper helper) {
        floor(helper);
        HiddenLayer hidden = HiddenLayer.of(helper.getLevel());
        hidden.setBlock(helper.absolutePos(SPOT.below()), PhaseCraftBlocks.PHASE_BLOCK.defaultBlockState());
        hidden.setBlock(helper.absolutePos(SPOT), Blocks.GLOWSTONE.defaultBlockState());

        helper.startSequence()
                .thenWaitUntil(() -> assertLight(helper, SPOT, 15, "visible light from phased glowstone"))
                .thenExecute(() -> hidden.setBlock(helper.absolutePos(SPOT.below()), Blocks.STONE.defaultBlockState()))
                .thenWaitUntil(() -> assertLight(helper, SPOT, 0, "visible light after the phase block was replaced"))
                .thenSucceed();
    }

    /** A wall torch hanging on the side of a phase block is phased too (support = the block it hangs on). */
    @GameTest(maxTicks = 600)
    public void wallTorchOnPhaseBlockLights(GameTestHelper helper) {
        floor(helper);
        HiddenLayer hidden = HiddenLayer.of(helper.getLevel());
        hidden.setBlock(helper.absolutePos(SPOT.north()), PhaseCraftBlocks.PHASE_BLOCK.defaultBlockState());
        hidden.setBlock(helper.absolutePos(SPOT), Blocks.WALL_TORCH.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH));

        helper.startSequence()
                .thenWaitUntil(() -> assertLight(helper, SPOT, 14, "visible light from a wall torch on a phase block"))
                .thenSucceed();
    }

    /** A phased light high in empty sky, far from any visible block, still lights the air around it. */
    @GameTest(maxTicks = 600)
    public void phasedLightInEmptySkyLights(GameTestHelper helper) {
        BlockPos high = SPOT.above(40);
        HiddenLayer hidden = HiddenLayer.of(helper.getLevel());
        hidden.setBlock(helper.absolutePos(high.below()), PhaseCraftBlocks.PHASE_BLOCK.defaultBlockState());
        hidden.setBlock(helper.absolutePos(high), Blocks.GLOWSTONE.defaultBlockState());

        helper.startSequence()
                .thenWaitUntil(() -> assertLight(helper, high, 15, "visible light at a phased glowstone in empty sky"))
                .thenWaitUntil(() -> assertLight(helper, high.east(), 14, "visible light next to it"))
                .thenSucceed();
    }
}
