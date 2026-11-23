package dev.phasecraft.test;

import dev.phasecraft.PhaseCraftBlocks;
import dev.phasecraft.layer.HiddenLayer;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The phase block: a hidden block standing on it, or hanging on its side, is
 * phased and reacts to the visible world at its own spot. A phased button or
 * lever is used by right-clicking the visible block at its spot; a phased
 * pressure plate is pressed by visible players, mobs and items standing on
 * its spot. Without a phase block, hidden buttons, levers and plates only
 * react to the hidden layer, as before.
 */
public class PhaseBlockGameTests {
    /** The visible "secret brick" a player clicks, and the hidden block at the same spot. */
    private static final BlockPos SECRET = new BlockPos(3, 2, 3);

    private static void floor(GameTestHelper helper) {
        for (int x = 0; x <= 7; x++) {
            for (int z = 0; z <= 7; z++) {
                helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
            }
        }
    }

    private static BlockState floorButton() {
        return Blocks.STONE_BUTTON.defaultBlockState().setValue(ButtonBlock.FACE, AttachFace.FLOOR)
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH);
    }

    /** A Survival player standing two blocks south of the secret brick, facing it. */
    private static ServerPlayer survivalPlayerFacingSecret(GameTestHelper helper) {
        ServerPlayer player = TestPlayers.join(helper, GameType.SURVIVAL);
        Vec3 spot = Vec3.atBottomCenterOf(helper.absolutePos(SECRET.south(2)));
        player.teleport(new TeleportTransition(helper.getLevel(), spot, Vec3.ZERO, 180, 0, TeleportTransition.DO_NOTHING));
        return player;
    }

    /** Right-clicks the south face of a visible block, as the server does for a client's click. */
    private static InteractionResult click(GameTestHelper helper, ServerPlayer player, BlockPos rel) {
        BlockPos pos = helper.absolutePos(rel);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos).relative(Direction.SOUTH, 0.5), Direction.SOUTH, pos, false);
        return player.gameMode.useItemOn(player, helper.getLevel(), player.getItemInHand(InteractionHand.MAIN_HAND),
                InteractionHand.MAIN_HAND, hit);
    }

    private static boolean powered(HiddenLayer hidden, GameTestHelper helper, BlockPos rel) {
        BlockState state = hidden.getBlock(helper.absolutePos(rel));
        return state.hasProperty(BlockStateProperties.POWERED) && state.getValue(BlockStateProperties.POWERED);
    }

    /** A Survival player right-clicks a plain visible brick: the phased button hidden there is pressed, and released by vanilla timing. */
    @GameTest(maxTicks = 80)
    public void clickingVisibleBlockPressesPhasedButton(GameTestHelper helper) {
        floor(helper);
        helper.setBlock(SECRET, Blocks.STONE_BRICKS);
        HiddenLayer hidden = HiddenLayer.of(helper.getLevel());
        hidden.setBlock(helper.absolutePos(SECRET.below()), PhaseCraftBlocks.PHASE_BLOCK.defaultBlockState());
        hidden.setBlock(helper.absolutePos(SECRET), floorButton());
        ServerPlayer player = survivalPlayerFacingSecret(helper);

        helper.startSequence()
                .thenExecute(() -> {
                    InteractionResult result = click(helper, player, SECRET);
                    helper.assertTrue(result.consumesAction(), "the click was not taken: " + result);
                    helper.assertTrue(powered(hidden, helper, SECRET), "phased button not pressed");
                    helper.assertBlockPresent(Blocks.STONE_BRICKS, SECRET);
                })
                .thenWaitUntil(() -> helper.assertFalse(powered(hidden, helper, SECRET), "phased button never released"))
                .thenExecute(() -> TestPlayers.leave(helper, player))
                .thenSucceed();
    }

    /** The same hidden button on ordinary stone is not phased: clicking the visible brick does nothing to it. */
    @GameTest(maxTicks = 40)
    public void hiddenButtonWithoutPhaseBlockIgnoresClicks(GameTestHelper helper) {
        floor(helper);
        helper.setBlock(SECRET, Blocks.STONE_BRICKS);
        HiddenLayer hidden = HiddenLayer.of(helper.getLevel());
        hidden.setBlock(helper.absolutePos(SECRET.below()), Blocks.STONE.defaultBlockState());
        hidden.setBlock(helper.absolutePos(SECRET), floorButton());
        ServerPlayer player = survivalPlayerFacingSecret(helper);

        helper.startSequence()
                .thenExecute(() -> click(helper, player, SECRET))
                .thenIdle(2)
                .thenExecute(() -> helper.assertFalse(powered(hidden, helper, SECRET), "unphased hidden button was pressed"))
                .thenExecute(() -> TestPlayers.leave(helper, player))
                .thenSucceed();
    }

    /** A lever hanging on the side of a phase block toggles with each click on the visible block at its spot. */
    @GameTest(maxTicks = 40)
    public void clickingVisibleBlockTogglesPhasedLever(GameTestHelper helper) {
        floor(helper);
        helper.setBlock(SECRET, Blocks.STONE_BRICKS);
        HiddenLayer hidden = HiddenLayer.of(helper.getLevel());
        hidden.setBlock(helper.absolutePos(SECRET.north()), PhaseCraftBlocks.PHASE_BLOCK.defaultBlockState());
        hidden.setBlock(helper.absolutePos(SECRET), Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, AttachFace.WALL)
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH));
        ServerPlayer player = survivalPlayerFacingSecret(helper);

        helper.startSequence()
                .thenExecute(() -> click(helper, player, SECRET))
                .thenExecute(() -> helper.assertTrue(powered(hidden, helper, SECRET), "phased lever not switched on"))
                .thenIdle(2)
                .thenExecute(() -> click(helper, player, SECRET))
                .thenExecute(() -> helper.assertFalse(powered(hidden, helper, SECRET), "phased lever not switched off"))
                .thenExecute(() -> TestPlayers.leave(helper, player))
                .thenSucceed();
    }

    /** Sneaking with a block in hand still places it against the secret brick, as with any vanilla button. */
    @GameTest(maxTicks = 40)
    public void sneakingWithBlockPlacesNormally(GameTestHelper helper) {
        floor(helper);
        helper.setBlock(SECRET, Blocks.STONE_BRICKS);
        HiddenLayer hidden = HiddenLayer.of(helper.getLevel());
        hidden.setBlock(helper.absolutePos(SECRET.below()), PhaseCraftBlocks.PHASE_BLOCK.defaultBlockState());
        hidden.setBlock(helper.absolutePos(SECRET), floorButton());
        ServerPlayer player = survivalPlayerFacingSecret(helper);

        helper.startSequence()
                .thenExecute(() -> {
                    player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.OAK_PLANKS));
                    player.setShiftKeyDown(true);
                    click(helper, player, SECRET);
                    helper.assertBlockPresent(Blocks.OAK_PLANKS, SECRET.south());
                    helper.assertFalse(powered(hidden, helper, SECRET), "sneak-placing pressed the phased button");
                })
                .thenExecute(() -> TestPlayers.leave(helper, player))
                .thenSucceed();
    }

    /** A pig standing in the visible world on the spot of a phased pressure plate presses it; it releases when the pig leaves. */
    @GameTest(maxTicks = 100)
    public void visibleMobPressesPhasedPressurePlate(GameTestHelper helper) {
        floor(helper);
        HiddenLayer hidden = HiddenLayer.of(helper.getLevel());
        hidden.setBlock(helper.absolutePos(SECRET.below()), PhaseCraftBlocks.PHASE_BLOCK.defaultBlockState());
        hidden.setBlock(helper.absolutePos(SECRET), Blocks.STONE_PRESSURE_PLATE.defaultBlockState());

        helper.startSequence()
                .thenIdle(5)
                .thenExecute(() -> helper.assertFalse(powered(hidden, helper, SECRET), "plate pressed with nobody on it"))
                .thenExecute(() -> helper.spawnWithNoFreeWill(EntityTypes.PIG, SECRET))
                .thenWaitUntil(() -> helper.assertTrue(powered(hidden, helper, SECRET), "phased plate not pressed by the pig"))
                .thenExecute(() -> helper.getLevel().getEntitiesOfClass(Pig.class, helper.getBounds()).forEach(Pig::discard))
                .thenWaitUntil(() -> helper.assertFalse(powered(hidden, helper, SECRET), "phased plate not released"))
                .thenSucceed();
    }

    /** Without a phase block under it, a hidden plate ignores visible mobs on its spot. */
    @GameTest(maxTicks = 60)
    public void hiddenPlateWithoutPhaseBlockIgnoresVisibleMobs(GameTestHelper helper) {
        floor(helper);
        HiddenLayer hidden = HiddenLayer.of(helper.getLevel());
        hidden.setBlock(helper.absolutePos(SECRET.below()), Blocks.STONE.defaultBlockState());
        hidden.setBlock(helper.absolutePos(SECRET), Blocks.STONE_PRESSURE_PLATE.defaultBlockState());

        helper.startSequence()
                .thenExecute(() -> helper.spawnWithNoFreeWill(EntityTypes.PIG, SECRET))
                .thenIdle(20)
                .thenExecute(() -> helper.assertFalse(powered(hidden, helper, SECRET), "unphased hidden plate pressed by a visible pig"))
                .thenSucceed();
    }

    /** Pistons cannot move a phase block, so a moved block can never change what is phased. */
    @GameTest(maxTicks = 40)
    public void pistonsCannotMovePhaseBlock(GameTestHelper helper) {
        HiddenLayer hidden = HiddenLayer.of(helper.getLevel());
        BlockPos piston = new BlockPos(1, 2, 3);
        hidden.setBlock(helper.absolutePos(piston.below()), Blocks.STONE.defaultBlockState());
        hidden.setBlock(helper.absolutePos(piston), Blocks.PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING, Direction.EAST));
        hidden.setBlock(helper.absolutePos(piston.east()), PhaseCraftBlocks.PHASE_BLOCK.defaultBlockState());

        helper.startSequence()
                .thenExecute(() -> hidden.setBlock(helper.absolutePos(piston.west()), Blocks.REDSTONE_BLOCK.defaultBlockState()))
                .thenIdle(5)
                .thenExecute(() -> {
                    helper.assertValueEqual(hidden.getBlock(helper.absolutePos(piston.east())).getBlock(), PhaseCraftBlocks.PHASE_BLOCK,
                            "phase block in front of the piston");
                    helper.assertValueEqual(hidden.getBlock(helper.absolutePos(piston.east(2))).getBlock(), Blocks.AIR,
                            "the cell beyond the phase block");
                })
                .thenSucceed();
    }
}
