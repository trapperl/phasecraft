package dev.phasecraft.test;

import dev.phasecraft.PhaseCraftBlocks;
import dev.phasecraft.layer.HiddenLayer;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Hidden blocks make no sound in the visible world (the person, 2026-11-23:
 * "Let's make them silent in the overworld"): not a phase piston moving a
 * visible block, not a phased button clicked or a phased plate pressed from
 * the visible world. Their sounds still play in the hidden layer, which also
 * shows the recorder works.
 */
public class SilentHiddenGameTests {
    private static final BlockPos SPOT = new BlockPos(3, 2, 3);

    private static void floor(GameTestHelper helper) {
        for (int x = 0; x <= 7; x++) {
            for (int z = 0; z <= 7; z++) {
                helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
            }
        }
    }

    private static void hidden(GameTestHelper helper, BlockPos rel, BlockState state) {
        HiddenLayer.of(helper.getLevel()).setBlock(helper.absolutePos(rel), state);
    }

    private static AABB around(GameTestHelper helper, BlockPos rel) {
        return new AABB(helper.absolutePos(rel)).inflate(2);
    }

    private static List<SoundRecorder.Played> visibleSounds(GameTestHelper helper, BlockPos rel) {
        return SoundRecorder.in(helper.getLevel().dimension(), around(helper, rel));
    }

    private static List<SoundRecorder.Played> hiddenSounds(GameTestHelper helper, BlockPos rel) {
        return SoundRecorder.in(HiddenLayer.of(helper.getLevel()).level().dimension(), around(helper, rel));
    }

    /** A phase piston pushes a visible block out and pulls it back: nothing is heard in the visible world. */
    @GameTest(maxTicks = 60)
    public void phasePistonIsSilentInVisibleWorld(GameTestHelper helper) {
        floor(helper);
        helper.setBlock(SPOT.east(), Blocks.GOLD_BLOCK);
        hidden(helper, SPOT, PhaseCraftBlocks.STICKY_PHASE_PISTON.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.EAST));
        helper.startSequence()
                .thenExecute(() -> hidden(helper, SPOT.above(), Blocks.REDSTONE_BLOCK.defaultBlockState()))
                .thenWaitUntil(() -> helper.assertBlockPresent(Blocks.GOLD_BLOCK, SPOT.east(2)))
                .thenExecute(() -> hidden(helper, SPOT.above(), Blocks.AIR.defaultBlockState()))
                .thenWaitUntil(() -> helper.assertBlockPresent(Blocks.GOLD_BLOCK, SPOT.east()))
                .thenIdle(3)
                .thenExecute(() -> {
                    helper.assertTrue(visibleSounds(helper, SPOT).isEmpty(), "heard in the visible world: " + visibleSounds(helper, SPOT));
                    helper.assertTrue(hiddenSounds(helper, SPOT).stream().anyMatch(p -> p.sound().contains("piston")),
                            "no piston sound in the hidden layer either: " + hiddenSounds(helper, SPOT));
                })
                .thenSucceed();
    }

    /** A Survival player clicks a secret brick: the phased button is pressed, and nothing is heard in the visible world. */
    @GameTest(maxTicks = 60)
    public void phasedButtonIsSilentInVisibleWorld(GameTestHelper helper) {
        floor(helper);
        helper.setBlock(SPOT, Blocks.STONE_BRICKS);
        hidden(helper, SPOT.south(), PhaseCraftBlocks.PHASE_BLOCK.defaultBlockState());
        hidden(helper, SPOT, Blocks.STONE_BUTTON.defaultBlockState().setValue(ButtonBlock.FACE, AttachFace.WALL)
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH));
        ServerPlayer player = TestPlayers.join(helper, GameType.SURVIVAL);
        player.teleport(new TeleportTransition(helper.getLevel(), Vec3.atBottomCenterOf(helper.absolutePos(SPOT.north(2))),
                Vec3.ZERO, 0, 0, TeleportTransition.DO_NOTHING));
        helper.startSequence()
                .thenExecute(() -> {
                    BlockPos pos = helper.absolutePos(SPOT);
                    player.gameMode.useItemOn(player, helper.getLevel(), player.getItemInHand(InteractionHand.MAIN_HAND),
                            InteractionHand.MAIN_HAND,
                            new BlockHitResult(Vec3.atCenterOf(pos).relative(Direction.NORTH, 0.5), Direction.NORTH, pos, false));
                })
                .thenWaitUntil(() -> helper.assertTrue(HiddenLayer.of(helper.getLevel()).getBlock(helper.absolutePos(SPOT))
                        .getValue(ButtonBlock.POWERED), "phased button not pressed"))
                .thenWaitUntil(() -> helper.assertFalse(HiddenLayer.of(helper.getLevel()).getBlock(helper.absolutePos(SPOT))
                        .getValue(ButtonBlock.POWERED), "phased button not released"))
                .thenExecute(() -> {
                    helper.assertTrue(visibleSounds(helper, SPOT).isEmpty(), "heard in the visible world: " + visibleSounds(helper, SPOT));
                    helper.assertFalse(hiddenSounds(helper, SPOT).isEmpty(), "no button sound in the hidden layer either");
                    TestPlayers.leave(helper, player);
                })
                .thenSucceed();
    }

    /** A pig steps on a phased plate's spot and off again: nothing is heard in the visible world. */
    @GameTest(maxTicks = 100)
    public void phasedPlateIsSilentInVisibleWorld(GameTestHelper helper) {
        floor(helper);
        hidden(helper, SPOT.below(), PhaseCraftBlocks.PHASE_BLOCK.defaultBlockState());
        hidden(helper, SPOT, Blocks.STONE_PRESSURE_PLATE.defaultBlockState());
        Pig[] pig = new Pig[1];
        helper.startSequence()
                .thenExecute(() -> pig[0] = helper.spawnWithNoFreeWill(EntityTypes.PIG, SPOT))
                .thenWaitUntil(() -> helper.assertTrue(HiddenLayer.of(helper.getLevel()).getBlock(helper.absolutePos(SPOT))
                        .getValue(BlockStateProperties.POWERED), "phased plate not pressed"))
                .thenExecute(() -> pig[0].discard())
                .thenWaitUntil(() -> helper.assertFalse(HiddenLayer.of(helper.getLevel()).getBlock(helper.absolutePos(SPOT))
                        .getValue(BlockStateProperties.POWERED), "phased plate not released"))
                .thenExecute(() -> {
                    helper.assertTrue(visibleSounds(helper, SPOT).stream().noneMatch(p -> p.sound().contains("pressure_plate")),
                            "heard in the visible world: " + visibleSounds(helper, SPOT));
                    helper.assertTrue(hiddenSounds(helper, SPOT).stream().anyMatch(p -> p.sound().contains("pressure_plate")),
                            "no plate sound in the hidden layer either: " + hiddenSounds(helper, SPOT));
                })
                .thenSucceed();
    }
}
