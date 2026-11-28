package dev.phasecraft.test;

import dev.phasecraft.PhaseCraftBlocks;
import dev.phasecraft.layer.HiddenLayer;
import dev.phasecraft.link.PhasePortBlock;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * A phase port in the hidden layer carries power both ways at its spot, with
 * nothing showing in the visible world (the person, 2026-11-23: no block from
 * the hidden layer may show in the main world; the phase block only phases,
 * the port carries power, both hidden).
 *
 * <ul>
 *   <li>Up: hidden power reaching the port powers the visible world at its
 *       spot, like an invisible redstone block there.</li>
 *   <li>Down: visible power at its spot comes out of the port in the hidden
 *       layer (as before).</li>
 *   <li>It never reads back what a port put across, so it cannot hold itself
 *       or another port on; nothing crosses without a port, and a phase block
 *       carries no power.</li>
 * </ul>
 */
public class HiddenPortGameTests {
    /** The port's spot. Visible floor at y = 1. */
    private static final BlockPos SPOT = new BlockPos(3, 2, 3);

    private static void floor(GameTestHelper helper) {
        for (int x = 0; x <= 7; x++) {
            for (int z = 0; z <= 7; z++) {
                helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
            }
        }
    }

    private static boolean visibleLit(GameTestHelper helper, BlockPos rel) {
        return helper.getBlockState(rel).getValue(RedstoneLampBlock.LIT);
    }

    private static boolean hiddenLit(GameTestHelper helper, BlockPos rel) {
        BlockState state = HiddenLayer.of(helper.getLevel()).getBlock(helper.absolutePos(rel));
        return state.hasProperty(RedstoneLampBlock.LIT) && state.getValue(RedstoneLampBlock.LIT);
    }

    private static void hidden(GameTestHelper helper, BlockPos rel, BlockState state) {
        HiddenLayer.of(helper.getLevel()).setBlock(helper.absolutePos(rel), state);
    }

    private static BlockState port() {
        return PhaseCraftBlocks.PHASE_PORT.defaultBlockState();
    }

    private static BlockState floorLever() {
        return Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, AttachFace.FLOOR)
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH);
    }

    /** A hidden redstone block next to a hidden port lights a visible lamp next to the port's spot; nothing visible is added. */
    @GameTest(maxTicks = 40)
    public void hiddenPowerLightsVisibleLampThroughHiddenPort(GameTestHelper helper) {
        floor(helper);
        helper.setBlock(SPOT.west(), Blocks.REDSTONE_LAMP);
        hidden(helper, SPOT, port());
        helper.startSequence()
                .thenIdle(3)
                .thenExecute(() -> helper.assertFalse(visibleLit(helper, SPOT.west()), "visible lamp lit before any power"))
                .thenExecute(() -> hidden(helper, SPOT.east(), Blocks.REDSTONE_BLOCK.defaultBlockState()))
                .thenWaitUntil(() -> helper.assertTrue(visibleLit(helper, SPOT.west()), "visible lamp not lit by hidden power"))
                .thenExecute(() -> helper.assertBlockPresent(Blocks.AIR, SPOT))
                .thenExecute(() -> hidden(helper, SPOT.east(), Blocks.AIR.defaultBlockState()))
                .thenWaitUntil(() -> helper.assertFalse(visibleLit(helper, SPOT.west()), "visible lamp stayed lit"))
                .thenSucceed();
    }

    /**
     * A visible redstone wire beside the port's spot is powered by it, as beside a redstone block, and unpowered when
     * the hidden power goes. (With the Lithium mod it was not: Lithium's wire reads its neighbours' blocks itself,
     * past the level's signal read where the port's power is added; PhaseCraft turns that Lithium option off.)
     */
    @GameTest(maxTicks = 40)
    public void hiddenPowerPowersVisibleWireThroughHiddenPort(GameTestHelper helper) {
        floor(helper);
        helper.setBlock(SPOT.west(), Blocks.REDSTONE_WIRE);
        helper.setBlock(SPOT.west(2), Blocks.REDSTONE_WIRE);
        hidden(helper, SPOT, port());
        helper.startSequence()
                .thenIdle(3)
                .thenExecute(() -> helper.assertValueEqual(helper.getBlockState(SPOT.west()).getValue(BlockStateProperties.POWER), 0,
                        "the visible wire's power before any hidden power"))
                .thenExecute(() -> hidden(helper, SPOT.east(), Blocks.REDSTONE_BLOCK.defaultBlockState()))
                .thenIdle(3)
                .thenExecute(() -> {
                    helper.assertValueEqual(helper.getBlockState(SPOT.west()).getValue(BlockStateProperties.POWER), 15,
                            "the power of the visible wire beside the port's spot");
                    helper.assertValueEqual(helper.getBlockState(SPOT.west(2)).getValue(BlockStateProperties.POWER), 14,
                            "the power of the next visible wire");
                    hidden(helper, SPOT.east(), Blocks.AIR.defaultBlockState());
                })
                .thenIdle(3)
                .thenExecute(() -> helper.assertValueEqual(helper.getBlockState(SPOT.west()).getValue(BlockStateProperties.POWER), 0,
                        "the visible wire's power once the hidden power is gone"))
                .thenSucceed();
    }

    /** Without a port, the same hidden power stays hidden; a phase block in its place carries none either. */
    @GameTest(maxTicks = 40)
    public void nothingCrossesWithoutPortAndPhaseBlockCarriesNoPower(GameTestHelper helper) {
        floor(helper);
        BlockPos other = SPOT.south(3);
        helper.setBlock(SPOT.west(), Blocks.REDSTONE_LAMP);
        helper.setBlock(other.west(), Blocks.REDSTONE_LAMP);
        hidden(helper, SPOT, Blocks.STONE.defaultBlockState());
        hidden(helper, SPOT.east(), Blocks.REDSTONE_BLOCK.defaultBlockState());
        hidden(helper, other, PhaseCraftBlocks.PHASE_BLOCK.defaultBlockState());
        hidden(helper, other.east(), Blocks.REDSTONE_BLOCK.defaultBlockState());
        helper.startSequence()
                .thenIdle(20)
                .thenExecute(() -> helper.assertFalse(visibleLit(helper, SPOT.west()), "hidden power crossed without a port"))
                .thenExecute(() -> helper.assertFalse(visibleLit(helper, other.west()), "a phase block carried power up"))
                .thenSucceed();
    }

    /** A visible lever next to a hidden port's spot lights a hidden lamp next to the port, and turns it off again. */
    @GameTest(maxTicks = 60)
    public void visibleLeverLightsHiddenLampThroughHiddenPort(GameTestHelper helper) {
        floor(helper);
        BlockPos lever = SPOT.east();
        helper.setBlock(lever, floorLever());
        hidden(helper, SPOT, port());
        hidden(helper, SPOT.west(), Blocks.REDSTONE_LAMP.defaultBlockState());
        helper.startSequence()
                .thenIdle(3)
                .thenExecute(() -> helper.assertFalse(hiddenLit(helper, SPOT.west()), "hidden lamp lit before the lever"))
                .thenExecute(() -> helper.pullLever(lever))
                .thenWaitUntil(() -> helper.assertTrue(hiddenLit(helper, SPOT.west()), "hidden lamp not lit by the visible lever"))
                .thenExecute(() -> helper.pullLever(lever))
                .thenWaitUntil(() -> helper.assertFalse(hiddenLit(helper, SPOT.west()), "hidden lamp stayed lit"))
                .thenSucceed();
    }

    /**
     * No latch: hidden wire carries the port's own output back into it, and a visible lamp next to
     * its spot shows whatever goes up. With the lever off again, everything goes dark.
     */
    @GameTest(maxTicks = 80)
    public void portDoesNotHoldItselfOn(GameTestHelper helper) {
        floor(helper);
        BlockPos lever = SPOT.east();
        helper.setBlock(lever, floorLever());
        helper.setBlock(SPOT.west(), Blocks.REDSTONE_LAMP);
        hidden(helper, SPOT, port());
        for (BlockPos rel : new BlockPos[] {SPOT.north(), SPOT.north().east(), SPOT.north().west()}) {
            hidden(helper, rel.below(), Blocks.STONE.defaultBlockState());
            hidden(helper, rel, Blocks.REDSTONE_WIRE.defaultBlockState());
        }
        hidden(helper, SPOT.south(), Blocks.REDSTONE_LAMP.defaultBlockState());
        helper.startSequence()
                .thenIdle(3)
                .thenExecute(() -> helper.pullLever(lever))
                .thenWaitUntil(() -> helper.assertTrue(hiddenLit(helper, SPOT.south()), "hidden lamp not lit by the lever"))
                .thenIdle(10)
                .thenExecute(() -> helper.pullLever(lever))
                .thenWaitUntil(() -> helper.assertFalse(hiddenLit(helper, SPOT.south()), "hidden side held itself on"))
                .thenWaitUntil(() -> helper.assertFalse(visibleLit(helper, SPOT.west()), "visible side held itself on"))
                .thenIdle(20)
                .thenExecute(() -> helper.assertFalse(hiddenLit(helper, SPOT.south()) || visibleLit(helper, SPOT.west()), "lit again"))
                .thenSucceed();
    }

    /**
     * Two hidden ports side by side do not hold each other on: what the first puts up is not read
     * down by the second, whose output hidden wire would otherwise carry round into the first.
     */
    @GameTest(maxTicks = 60)
    public void adjacentPortsDoNotHoldEachOtherOn(GameTestHelper helper) {
        floor(helper);
        helper.setBlock(SPOT.north(), Blocks.REDSTONE_LAMP);
        hidden(helper, SPOT, port());
        hidden(helper, SPOT.east(), port());
        // Hidden wire from beside the second port round to the first one's south side.
        for (BlockPos rel : new BlockPos[] {SPOT.east(2), SPOT.east(2).south(), SPOT.east().south(), SPOT.south()}) {
            hidden(helper, rel.below(), Blocks.STONE.defaultBlockState());
            hidden(helper, rel, Blocks.REDSTONE_WIRE.defaultBlockState());
        }
        helper.startSequence()
                .thenIdle(3)
                .thenExecute(() -> hidden(helper, SPOT.west(), Blocks.REDSTONE_BLOCK.defaultBlockState()))
                .thenWaitUntil(() -> helper.assertTrue(visibleLit(helper, SPOT.north()), "visible lamp not lit"))
                .thenIdle(5)
                .thenExecute(() -> hidden(helper, SPOT.west(), Blocks.AIR.defaultBlockState()))
                .thenWaitUntil(() -> helper.assertFalse(visibleLit(helper, SPOT.north()), "the ports held each other on"))
                .thenIdle(20)
                .thenExecute(() -> helper.assertFalse(visibleLit(helper, SPOT.north()), "lit again"))
                .thenSucceed();
    }

    /**
     * Station 13's secret brick: a Survival player clicks a plain brick, the phased button hidden
     * there powers its phase block, a hidden port next to the phase block puts that up, and a
     * visible lamp next to the port's spot lights.
     */
    @GameTest(maxTicks = 80)
    public void secretBrickLightsVisibleLampThroughHiddenPort(GameTestHelper helper) {
        floor(helper);
        BlockPos brick = SPOT;
        BlockPos phase = SPOT.south();
        BlockPos portSpot = phase.south();
        helper.setBlock(brick, Blocks.STONE_BRICKS);
        helper.setBlock(portSpot, Blocks.STONE_BRICKS);
        helper.setBlock(portSpot.above(), Blocks.REDSTONE_LAMP);
        hidden(helper, phase, PhaseCraftBlocks.PHASE_BLOCK.defaultBlockState());
        hidden(helper, portSpot, port());
        hidden(helper, brick, Blocks.STONE_BUTTON.defaultBlockState().setValue(ButtonBlock.FACE, AttachFace.WALL)
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH));
        ServerPlayer player = TestPlayers.join(helper, GameType.SURVIVAL);
        player.teleport(new TeleportTransition(helper.getLevel(), Vec3.atBottomCenterOf(helper.absolutePos(brick.north(2))),
                Vec3.ZERO, 0, 0, TeleportTransition.DO_NOTHING));
        helper.startSequence()
                .thenIdle(3)
                .thenExecute(() -> {
                    BlockPos pos = helper.absolutePos(brick);
                    player.gameMode.useItemOn(player, helper.getLevel(), player.getItemInHand(InteractionHand.MAIN_HAND),
                            InteractionHand.MAIN_HAND,
                            new BlockHitResult(Vec3.atCenterOf(pos).relative(Direction.NORTH, 0.5), Direction.NORTH, pos, false));
                })
                .thenWaitUntil(() -> helper.assertTrue(visibleLit(helper, portSpot.above()), "visible lamp not lit by the secret brick"))
                .thenWaitUntil(() -> helper.assertFalse(visibleLit(helper, portSpot.above()), "visible lamp stayed lit after the button let go"))
                .thenExecute(() -> TestPlayers.leave(helper, player))
                .thenSucceed();
    }

    /**
     * A port hears a change two blocks away in the neighbouring chunk. (A change queues port refreshes only where
     * the other layer's chunk sections near it can hold a port: the sections across a chunk border count too.)
     * The port is the first block of a chunk; a visible repeater two blocks west of it, in the chunk before,
     * powers the visible stone between them, and that is the port's input.
     */
    @GameTest(structure = "phasecraft-test:empty_24x7x24", maxTicks = 80)
    public void portHearsAChangeAcrossAChunkBorder(GameTestHelper helper) {
        int x = 4;
        while ((helper.absolutePos(new BlockPos(x, 2, 5)).getX() & 15) != 0) {
            x++;
        }
        helper.assertTrue(x <= 20, "no chunk border in the test area");
        BlockPos spot = new BlockPos(x, 2, 5);
        for (int floorX = x - 4; floorX <= x + 2; floorX++) {
            helper.setBlock(new BlockPos(floorX, 1, 5), Blocks.STONE);
        }
        hidden(helper, spot.below(), Blocks.STONE.defaultBlockState());
        hidden(helper, spot, port());
        hidden(helper, spot.east(), Blocks.REDSTONE_LAMP.defaultBlockState());
        helper.setBlock(spot.west(), Blocks.STONE);
        helper.setBlock(spot.west(2), Blocks.REPEATER.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST));
        helper.startSequence()
                .thenIdle(5)
                .thenExecute(() -> helper.assertFalse(hiddenLit(helper, spot.east()), "hidden lamp lit before any power"))
                .thenExecute(() -> helper.setBlock(spot.west(3), Blocks.REDSTONE_BLOCK))
                .thenWaitUntil(() -> helper.assertTrue(hiddenLit(helper, spot.east()),
                        "hidden lamp not lit: the port did not hear the repeater in the next chunk"))
                .thenExecute(() -> helper.setBlock(spot.west(3), Blocks.AIR))
                .thenWaitUntil(() -> helper.assertFalse(hiddenLit(helper, spot.east()), "hidden lamp stayed lit"))
                .thenSucceed();
    }

    /**
     * The same reach where no other test's port is near. Whether a change queues refreshes is asked of the chunk
     * sections' palettes, and those of the test area list the ports of the tests around (so the test above would
     * pass even if only the change's own chunk were asked). Far from everything: a port in the first block of a
     * chunk, a visible lever two blocks west of it, in the chunk before, on the visible stone between them.
     * Nothing ticks there, and nothing needs to: the port is refreshed at the end of the tick of the change.
     */
    @GameTest(maxTicks = 40)
    public void portHearsAChangeAcrossAChunkBorderFarFromOtherPorts(GameTestHelper helper) {
        ServerLevel visible = helper.getLevel();
        HiddenLayer hidden = HiddenLayer.of(visible);
        BlockPos near = helper.absolutePos(SPOT);
        BlockPos spot = new BlockPos((near.getX() + 160_000) & ~15, near.getY(), near.getZ() + 160_000);
        BlockState lever = Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, AttachFace.WALL)
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST);
        hidden.setBlock(spot, port());
        visible.setBlock(spot.west(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        visible.setBlock(spot.west(2), lever, Block.UPDATE_ALL);
        helper.startSequence()
                .thenIdle(2)
                .thenExecute(() -> {
                    // Reading the port's spot in both layers brings its chunks into memory, as they are where the world ticks.
                    helper.assertValueEqual(hidden.getBlock(spot).getValue(PhasePortBlock.POWER), 0, "port power before the lever");
                    helper.assertTrue(visible.getBlockState(spot).isAir(), "something visible at the port's spot");
                    visible.setBlock(spot.west(2), lever.setValue(LeverBlock.POWERED, true), Block.UPDATE_ALL);
                })
                .thenIdle(1)
                .thenExecute(() -> {
                    helper.assertValueEqual(hidden.getBlock(spot).getValue(PhasePortBlock.POWER), 15,
                            "port power: it did not hear the lever in the next chunk");
                    helper.assertTrue(visible.getBlockState(spot).isAir(), "something visible at the port's spot");
                    visible.setBlock(spot.west(2), lever, Block.UPDATE_ALL);
                })
                .thenIdle(1)
                .thenExecute(() -> helper.assertValueEqual(hidden.getBlock(spot).getValue(PhasePortBlock.POWER), 0,
                        "port power after the lever went off"))
                .thenSucceed();
    }
}
