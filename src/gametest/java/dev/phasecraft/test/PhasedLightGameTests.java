package dev.phasecraft.test;

import dev.phasecraft.PhaseCraftBlocks;
import dev.phasecraft.layer.HiddenLayer;
import dev.phasecraft.phased.PhasedLights;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * Phased light: a hidden light source standing on a phase block (or hanging on
 * its side) lights the visible world at its own spot, by vanilla's light rules.
 * Without a phase block, hidden light stays in the hidden layer.
 *
 * <p>Light is computed on the server's light thread, at its own pace, while the
 * GameTest server runs ticks as fast as it can (80 ticks can pass in a second),
 * so these tests allow many ticks: 2400 since 2026-11-23, after two CI runs
 * (full suite, small machine) ran out of 600 with the light not yet settled;
 * neither reproduced locally, on one core or on two with the full suite. A
 * passing check ends as soon as the light is right. If one fails even with
 * 2400, suspect a light check that is lost, not slow. (Vanilla also starts queued light work only
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
    @GameTest(maxTicks = 2400)
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
    @GameTest(maxTicks = 2400)
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
    @GameTest(maxTicks = 2400)
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
                        + hidden.getBlock(helper.absolutePos(SPOT)) + "; phased light recorded there: "
                        + PhasedLights.emission(helper.getLevel(), helper.absolutePos(SPOT).asLong())
                        + "; visible light east: " + visibleBlockLight(helper, SPOT.east())
                        + ", two east: " + visibleBlockLight(helper, SPOT.east(2)) + ")"))
                .thenSucceed();
    }

    /** Taking the phase block away takes the light out of the visible world. */
    @GameTest(maxTicks = 2400)
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
    @GameTest(maxTicks = 2400)
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
    @GameTest(maxTicks = 2400)
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

    /**
     * A phased light's position goes only to players tracking its chunk (the chunks vanilla sends them anyway),
     * whatever their game mode: a player next to it is to hold it, a player beyond view distance is not. (Sent to
     * everyone in the dimension, a modified client could list every hidden lamp on the server.)
     */
    @GameTest(maxTicks = 400)
    public void lightIsSentOnlyToPlayersTrackingItsChunk(GameTestHelper helper) {
        floor(helper);
        HiddenLayer hidden = HiddenLayer.of(helper.getLevel());
        BlockPos at = helper.absolutePos(SPOT);
        hidden.setBlock(at.below(), PhaseCraftBlocks.PHASE_BLOCK.defaultBlockState());
        hidden.setBlock(at, Blocks.GLOWSTONE.defaultBlockState());
        PhasedLights.Light light = new PhasedLights.Light(at, 15);
        ServerPlayer near = TestPlayers.join(helper, GameType.ADVENTURE);
        ServerPlayer far = TestPlayers.join(helper, GameType.ADVENTURE);
        // Past what the far player tracks: the view distance (at least 2) and vanilla's margin around it.
        int blocksAway = (Math.max(2, helper.getLevel().getServer().getPlayerList().getViewDistance()) + 4) * 16;
        near.teleport(new TeleportTransition(helper.getLevel(), Vec3.atBottomCenterOf(at.south(2)), Vec3.ZERO, 180, 0,
                TeleportTransition.DO_NOTHING));
        far.teleport(new TeleportTransition(helper.getLevel(), Vec3.atBottomCenterOf(at.east(blocksAway)), Vec3.ZERO, 180, 0,
                TeleportTransition.DO_NOTHING));

        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(PhasedLights.lightsFor(near).contains(light),
                        "the player next to the phased light is not sent it: " + PhasedLights.lightsFor(near)))
                .thenIdle(20)
                .thenExecute(() -> {
                    helper.assertTrue(far.getChunkTrackingView().contains(far.chunkPosition()), "the far player tracks no chunks");
                    helper.assertFalse(PhasedLights.lightsFor(far).contains(light),
                            "a player " + blocksAway + " blocks away is sent the phased light's position");
                    // Walking up to it brings it; walking away takes it back.
                    far.teleport(new TeleportTransition(helper.getLevel(), Vec3.atBottomCenterOf(at.north(2)), Vec3.ZERO, 0, 0,
                            TeleportTransition.DO_NOTHING));
                    near.teleport(new TeleportTransition(helper.getLevel(), Vec3.atBottomCenterOf(at.east(blocksAway)), Vec3.ZERO,
                            180, 0, TeleportTransition.DO_NOTHING));
                })
                .thenWaitUntil(() -> {
                    helper.assertTrue(PhasedLights.lightsFor(far).contains(light), "the player who came close is not sent the light");
                    helper.assertFalse(PhasedLights.lightsFor(near).contains(light), "the player who left is still sent the light");
                })
                .thenExecute(() -> {
                    TestPlayers.leave(helper, near);
                    TestPlayers.leave(helper, far);
                })
                .thenSucceed();
    }
}
