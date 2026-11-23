package dev.phasecraft.test;

import dev.phasecraft.edit.HiddenEditing;
import dev.phasecraft.layer.HiddenLayer;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Editing mode on the server: a Creative player in the visible world clicks,
 * and the click happens in the hidden layer (HiddenEditing).
 */
public class HiddenEditingGameTests {
    private static final BlockPos TARGET = new BlockPos(3, 2, 3);

    /**
     * Runs a check with a player standing near the target, then removes the player.
     * (makeMockServerPlayerInLevel always reports Creative; makeMockServerPlayer takes a mode.)
     */
    private static void withPlayer(GameTestHelper helper, GameType mode, Consumer<ServerPlayer> check) {
        ServerPlayer player = mode == GameType.CREATIVE
                ? helper.makeMockServerPlayerInLevel()
                : (ServerPlayer) helper.makeMockServerPlayer(mode);
        try {
            helper.assertValueEqual(player.gameMode(), mode, "test player's game mode");
            Vec3 stand = Vec3.atBottomCenterOf(helper.absolutePos(TARGET.west(2)));
            player.snapTo(stand.x, stand.y, stand.z, -90.0F, 30.0F); // facing east, toward the target
            check.accept(player);
        } finally {
            helper.getLevel().getServer().getPlayerList().remove(player);
        }
    }

    private static BlockHitResult click(BlockPos pos, Direction face) {
        return new BlockHitResult(Vec3.atCenterOf(pos).relative(face, 0.5), face, pos, false);
    }

    private static void hold(ServerPlayer player, net.minecraft.world.item.Item item) {
        player.setItemInHand(InteractionHand.MAIN_HAND, item == Items.AIR ? ItemStack.EMPTY : new ItemStack(item));
    }

    /** Aimed at a visible block with nothing hidden there: the hidden block goes at that same spot. */
    @GameTest
    public void clickOnVisibleBlockPlacesHiddenBlockAtTheSameSpot(GameTestHelper helper) {
        BlockPos at = helper.absolutePos(TARGET);
        HiddenLayer layer = HiddenLayer.of(helper.getLevel());
        helper.setBlock(TARGET, Blocks.STONE);
        withPlayer(helper, GameType.CREATIVE, player -> {
            hold(player, Items.REDSTONE_LAMP);
            HiddenEditing.use(player, InteractionHand.MAIN_HAND, click(at, Direction.UP));
        });
        helper.assertTrue(layer.getBlock(at).is(Blocks.REDSTONE_LAMP), "no hidden lamp at the visible block's spot: " + layer.getBlock(at));
        helper.assertBlockPresent(Blocks.STONE, TARGET);
        helper.assertTrue(layer.getBlock(at.above()).isAir(), "hidden block placed above instead");
        helper.assertBlockPresent(Blocks.AIR, TARGET.above());
        layer.removeBlock(at);
        helper.succeed();
    }

    /** Aimed at a hidden block: placed next to it, in the hidden layer only. */
    @GameTest
    public void clickOnHiddenBlockPlacesNextToIt(GameTestHelper helper) {
        BlockPos at = helper.absolutePos(TARGET);
        HiddenLayer layer = HiddenLayer.of(helper.getLevel());
        layer.setBlock(at, Blocks.STONE.defaultBlockState());
        withPlayer(helper, GameType.CREATIVE, player -> {
            hold(player, Items.OAK_PLANKS);
            HiddenEditing.use(player, InteractionHand.MAIN_HAND, click(at, Direction.UP));
        });
        helper.assertTrue(layer.getBlock(at.above()).is(Blocks.OAK_PLANKS), "no hidden planks on the hidden stone: " + layer.getBlock(at.above()));
        helper.assertTrue(layer.getBlock(at).is(Blocks.STONE), "hidden stone replaced");
        helper.assertBlockPresent(Blocks.AIR, TARGET.above());
        layer.removeBlock(at.above());
        layer.removeBlock(at);
        helper.succeed();
    }

    /** Placement follows vanilla rules with the player's own facing: a repeater points as in vanilla. */
    @GameTest
    public void placedBlockFacesLikeVanilla(GameTestHelper helper) {
        BlockPos at = helper.absolutePos(TARGET);
        HiddenLayer layer = HiddenLayer.of(helper.getLevel());
        layer.setBlock(at, Blocks.STONE.defaultBlockState());
        Direction[] expected = new Direction[1];
        withPlayer(helper, GameType.CREATIVE, player -> {
            hold(player, Items.REPEATER);
            expected[0] = player.getDirection().getOpposite();
            HiddenEditing.use(player, InteractionHand.MAIN_HAND, click(at, Direction.UP));
        });
        BlockState repeater = layer.getBlock(at.above());
        helper.assertTrue(repeater.is(Blocks.REPEATER), "no hidden repeater: " + repeater);
        helper.assertValueEqual(repeater.getValue(BlockStateProperties.HORIZONTAL_FACING), expected[0], "repeater facing");
        layer.removeBlock(at.above());
        layer.removeBlock(at);
        helper.succeed();
    }

    /** Right click with an empty hand uses the hidden block: a hidden lever flips. */
    @GameTest
    public void clickUsesHiddenBlock(GameTestHelper helper) {
        BlockPos at = helper.absolutePos(TARGET);
        HiddenLayer layer = HiddenLayer.of(helper.getLevel());
        layer.setBlock(at.below(), Blocks.STONE.defaultBlockState());
        layer.setBlock(at, Blocks.LEVER.defaultBlockState()
                .setValue(LeverBlock.FACE, AttachFace.FLOOR)
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH));
        withPlayer(helper, GameType.CREATIVE, player -> {
            hold(player, Items.AIR);
            HiddenEditing.use(player, InteractionHand.MAIN_HAND, click(at, Direction.UP));
        });
        helper.assertTrue(layer.getBlock(at).getValue(LeverBlock.POWERED), "hidden lever not flipped");
        layer.removeBlock(at);
        layer.removeBlock(at.below());
        helper.succeed();
    }

    /** Left click removes the hidden block and leaves the visible block at the same spot alone. */
    @GameTest
    public void breakRemovesOnlyTheHiddenBlock(GameTestHelper helper) {
        BlockPos at = helper.absolutePos(TARGET);
        HiddenLayer layer = HiddenLayer.of(helper.getLevel());
        helper.setBlock(TARGET, Blocks.STONE);
        layer.setBlock(at, Blocks.REDSTONE_LAMP.defaultBlockState());
        boolean[] removed = new boolean[1];
        withPlayer(helper, GameType.CREATIVE, player -> removed[0] = HiddenEditing.breakBlock(player, at));
        helper.assertTrue(removed[0], "break reported nothing removed");
        helper.assertTrue(layer.getBlock(at).isAir(), "hidden lamp still there");
        helper.assertBlockPresent(Blocks.STONE, TARGET);
        helper.succeed();
    }

    /** Survival players cannot edit the hidden layer. */
    @GameTest
    public void survivalPlayersCannotEdit(GameTestHelper helper) {
        BlockPos at = helper.absolutePos(TARGET);
        HiddenLayer layer = HiddenLayer.of(helper.getLevel());
        helper.setBlock(TARGET, Blocks.STONE);
        layer.setBlock(at.east(), Blocks.REDSTONE_LAMP.defaultBlockState());
        withPlayer(helper, GameType.SURVIVAL, player -> {
            hold(player, Items.OAK_PLANKS);
            HiddenEditing.use(player, InteractionHand.MAIN_HAND, click(at, Direction.UP));
            HiddenEditing.breakBlock(player, at.east());
        });
        helper.assertTrue(layer.getBlock(at).isAir(), "survival player placed a hidden block");
        helper.assertTrue(layer.getBlock(at.east()).is(Blocks.REDSTONE_LAMP), "survival player broke a hidden block");
        layer.removeBlock(at.east());
        helper.succeed();
    }

    /** Clicks beyond reach are refused, as vanilla refuses them. */
    @GameTest
    public void clicksOutOfReachAreRefused(GameTestHelper helper) {
        BlockPos far = helper.absolutePos(TARGET).east(20);
        HiddenLayer layer = HiddenLayer.of(helper.getLevel());
        layer.setBlock(far, Blocks.STONE.defaultBlockState());
        withPlayer(helper, GameType.CREATIVE, player -> {
            hold(player, Items.OAK_PLANKS);
            HiddenEditing.use(player, InteractionHand.MAIN_HAND, click(far, Direction.UP));
            HiddenEditing.breakBlock(player, far);
        });
        helper.assertTrue(layer.getBlock(far.above()).isAir(), "placed out of reach");
        helper.assertTrue(layer.getBlock(far).is(Blocks.STONE), "broke out of reach");
        layer.removeBlock(far);
        helper.succeed();
    }

    /** Middle click on a hidden block puts that block in the hand; Survival players and far clicks get nothing. */
    @GameTest
    public void pickPutsTheHiddenBlockInHand(GameTestHelper helper) {
        BlockPos at = helper.absolutePos(TARGET);
        HiddenLayer layer = HiddenLayer.of(helper.getLevel());
        helper.setBlock(TARGET, Blocks.STONE);
        layer.setBlock(at, Blocks.REDSTONE_LAMP.defaultBlockState());
        layer.setBlock(at.east(20), Blocks.REDSTONE_LAMP.defaultBlockState());
        withPlayer(helper, GameType.CREATIVE, player -> {
            hold(player, Items.OAK_PLANKS);
            helper.assertFalse(HiddenEditing.pick(player, at.east(20)), "picked out of reach");
            helper.assertTrue(HiddenEditing.pick(player, at), "pick reported nothing picked");
            helper.assertTrue(player.getMainHandItem().is(Items.REDSTONE_LAMP),
                    "hand holds " + player.getMainHandItem() + ", not the hidden lamp (the visible block is stone)");
        });
        withPlayer(helper, GameType.SURVIVAL, player -> {
            hold(player, Items.OAK_PLANKS);
            helper.assertFalse(HiddenEditing.pick(player, at), "survival player picked a hidden block");
        });
        layer.removeBlock(at);
        layer.removeBlock(at.east(20));
        helper.succeed();
    }
}
