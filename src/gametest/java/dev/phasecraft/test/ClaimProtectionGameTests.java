package dev.phasecraft.test;

import dev.phasecraft.PhaseCraftBlocks;
import dev.phasecraft.edit.HiddenEditing;
import dev.phasecraft.layer.HiddenLayer;
import dev.phasecraft.test.TestClaims.Action;
import java.util.Set;
import java.util.UUID;
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
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Claims hold for the hidden layer: where a claim mod (here {@link TestClaims}, answering the Common
 * Protection API as real ones do) refuses a player an action on the visible block at a spot, editing
 * mode refuses that action on the hidden layer at that spot, and allows it everywhere else. Each claim
 * here refuses one action only, so every test shows its own question is the one asked.
 */
public class ClaimProtectionGameTests {
    /** In the claim. */
    private static final BlockPos CLAIMED = new BlockPos(3, 2, 3);
    /** Outside it, with a free spot between the two. */
    private static final BlockPos FREE = new BlockPos(5, 2, 3);
    private static final UUID SOMEONE_ELSE = UUID.randomUUID();

    /** A player standing on a floor, two blocks south of the spot between CLAIMED and FREE. */
    static ServerPlayer player(GameTestHelper helper, GameType mode) {
        for (int x = 0; x <= 7; x++) {
            for (int z = 0; z <= 7; z++) {
                helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
            }
        }
        ServerPlayer player = TestPlayers.join(helper, mode);
        Vec3 spot = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(4, 2, 5)));
        player.teleport(new TeleportTransition(helper.getLevel(), spot, Vec3.ZERO, 180, 0, TeleportTransition.DO_NOTHING));
        player.setOnGround(true); // no client moves a test player; mining in the air is five times slower
        return player;
    }

    static BlockHitResult hit(BlockPos pos, Direction face) {
        return new BlockHitResult(Vec3.atCenterOf(pos).relative(face, 0.5), face, pos, false);
    }

    static void hold(ServerPlayer player, ItemStack stack) {
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
    }

    private static void finish(GameTestHelper helper, ServerPlayer player, BlockPos... spots) {
        HiddenLayer hidden = HiddenLayer.of(helper.getLevel());
        TestClaims.unclaim(spots);
        for (BlockPos spot : spots) {
            hidden.removeBlock(spot);
            hidden.removeBlock(spot.below());
        }
        TestPlayers.leave(helper, player);
        helper.succeed();
    }

    /**
     * A Survival player cannot hide a phase shifter in someone's claimed wall (powered, it would take
     * the wall), by clicking the wall or by clicking a hidden block outside the claim on the side
     * facing it; the item stays in hand. Outside the claim the same click places. The claim's owner
     * places in it.
     */
    @GameTest(maxTicks = 20)
    public void claimRefusesHiddenPlacing(GameTestHelper helper) {
        ServerPlayer player = player(helper, GameType.SURVIVAL);
        HiddenLayer hidden = HiddenLayer.of(helper.getLevel());
        BlockPos claimed = helper.absolutePos(CLAIMED);
        BlockPos beside = claimed.east();
        BlockPos free = helper.absolutePos(FREE);
        helper.setBlock(CLAIMED, Blocks.STONE);
        TestClaims.claim(SOMEONE_ELSE, Set.of(Action.PLACE), claimed);

        hold(player, new ItemStack(PhaseCraftBlocks.PHASE_SHIFTER_ITEM, 3));
        HiddenEditing.use(player, InteractionHand.MAIN_HAND, hit(claimed, Direction.SOUTH));
        helper.assertValueEqual(hidden.getBlock(claimed).getBlock(), Blocks.AIR, "hidden block in the claim");
        helper.assertValueEqual(player.getMainHandItem().getCount(), 3, "shifters in hand after the refused click");

        HiddenEditing.use(player, InteractionHand.MAIN_HAND, hit(free, Direction.SOUTH));
        helper.assertValueEqual(hidden.getBlock(free).getBlock(), PhaseCraftBlocks.PHASE_SHIFTER, "hidden block outside the claim");
        helper.assertValueEqual(player.getMainHandItem().getCount(), 2, "shifters in hand after placing outside");

        // Against a hidden block outside the claim, on the face towards it: the block would go into the claim.
        hidden.setBlock(beside, Blocks.STONE.defaultBlockState());
        HiddenEditing.use(player, InteractionHand.MAIN_HAND, hit(beside, Direction.WEST));
        helper.assertValueEqual(hidden.getBlock(claimed).getBlock(), Blocks.AIR, "hidden block in the claim, placed from beside it");
        helper.assertValueEqual(player.getMainHandItem().getCount(), 2, "shifters in hand after the second refused click");

        helper.assertValueEqual(helper.getLevel().getBlockState(claimed).getBlock(), Blocks.STONE, "the claimed visible block");

        TestClaims.claim(player.getUUID(), Set.of(Action.PLACE), claimed);
        HiddenEditing.use(player, InteractionHand.MAIN_HAND, hit(claimed, Direction.SOUTH));
        helper.assertValueEqual(hidden.getBlock(claimed).getBlock(), PhaseCraftBlocks.PHASE_SHIFTER, "the owner's hidden block in the claim");
        finish(helper, player, claimed, beside, free);
    }

    /** Nor pour a bucket into the claim's hidden layer, or scoop hidden water out of it. */
    @GameTest(maxTicks = 20)
    public void claimRefusesHiddenBuckets(GameTestHelper helper) {
        ServerPlayer player = player(helper, GameType.SURVIVAL);
        HiddenLayer hidden = HiddenLayer.of(helper.getLevel());
        BlockPos claimed = helper.absolutePos(CLAIMED);
        BlockPos free = helper.absolutePos(FREE);
        TestClaims.claim(SOMEONE_ELSE, Set.of(Action.PLACE), claimed);

        hold(player, new ItemStack(Items.WATER_BUCKET));
        HiddenEditing.use(player, InteractionHand.MAIN_HAND, hit(claimed, Direction.SOUTH));
        helper.assertValueEqual(hidden.getBlock(claimed).getBlock(), Blocks.AIR, "hidden block in the claim");
        helper.assertTrue(player.getMainHandItem().is(Items.WATER_BUCKET), "the bucket is still full");
        HiddenEditing.use(player, InteractionHand.MAIN_HAND, hit(free, Direction.SOUTH));
        helper.assertValueEqual(hidden.getBlock(free).getBlock(), Blocks.WATER, "hidden block outside the claim");
        helper.assertTrue(player.getMainHandItem().is(Items.BUCKET), "the bucket was emptied outside the claim");

        TestClaims.claim(SOMEONE_ELSE, Set.of(Action.BREAK), claimed);
        hidden.setBlock(claimed, Blocks.WATER.defaultBlockState());
        HiddenEditing.use(player, InteractionHand.MAIN_HAND, hit(claimed, Direction.SOUTH));
        helper.assertValueEqual(hidden.getBlock(claimed).getBlock(), Blocks.WATER, "hidden water in the claim");
        helper.assertTrue(player.getMainHandItem().is(Items.BUCKET), "the bucket is still empty");
        HiddenEditing.use(player, InteractionHand.MAIN_HAND, hit(free, Direction.SOUTH));
        helper.assertValueEqual(hidden.getBlock(free).getBlock(), Blocks.AIR, "hidden water outside the claim, scooped up");
        finish(helper, player, claimed, free);
    }

    /**
     * Nor break hidden blocks in the claim: not in Creative, not in Survival however long they mine.
     * Outside it both break them.
     */
    @GameTest(maxTicks = 100)
    public void claimRefusesHiddenBreaking(GameTestHelper helper) {
        ServerPlayer player = player(helper, GameType.CREATIVE);
        HiddenLayer hidden = HiddenLayer.of(helper.getLevel());
        BlockPos claimed = helper.absolutePos(CLAIMED);
        BlockPos free = helper.absolutePos(FREE);
        hidden.setBlock(claimed, Blocks.DIRT.defaultBlockState());
        hidden.setBlock(free, Blocks.DIRT.defaultBlockState());
        TestClaims.claim(SOMEONE_ELSE, Set.of(Action.BREAK), claimed);

        helper.assertFalse(HiddenEditing.breakBlock(player, claimed), "a Creative player broke a hidden block in a claim");
        helper.assertValueEqual(hidden.getBlock(claimed).getBlock(), Blocks.DIRT, "hidden block in the claim");
        helper.assertTrue(HiddenEditing.breakBlock(player, free), "a Creative player could not break a hidden block outside the claim");

        hidden.setBlock(free, Blocks.DIRT.defaultBlockState());
        player.setGameMode(GameType.SURVIVAL);
        helper.startSequence()
                .thenExecute(() -> HiddenEditing.startBreaking(player, claimed))
                .thenIdle(30) // dirt by hand: 15 ticks
                .thenExecute(() -> {
                    helper.assertFalse(HiddenEditing.breakBlock(player, claimed), "a Survival player broke a hidden block in a claim");
                    helper.assertValueEqual(hidden.getBlock(claimed).getBlock(), Blocks.DIRT, "hidden block in the claim");
                    HiddenEditing.startBreaking(player, free);
                })
                .thenIdle(30)
                .thenExecute(() -> {
                    helper.assertTrue(HiddenEditing.breakBlock(player, free), "a Survival player could not break a hidden block outside the claim");
                    finish(helper, player, claimed, free);
                });
    }

    /** Nor use them: a hidden lever in the claim is not flipped, one outside it is. */
    @GameTest(maxTicks = 20)
    public void claimRefusesUsingHiddenBlocks(GameTestHelper helper) {
        ServerPlayer player = player(helper, GameType.SURVIVAL);
        HiddenLayer hidden = HiddenLayer.of(helper.getLevel());
        BlockPos claimed = helper.absolutePos(CLAIMED);
        BlockPos free = helper.absolutePos(FREE);
        BlockState lever = Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, AttachFace.FLOOR)
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH);
        for (BlockPos spot : new BlockPos[] {claimed, free}) {
            hidden.setBlock(spot.below(), Blocks.STONE.defaultBlockState());
            hidden.setBlock(spot, lever);
        }
        TestClaims.claim(SOMEONE_ELSE, Set.of(Action.USE), claimed);

        hold(player, ItemStack.EMPTY);
        HiddenEditing.use(player, InteractionHand.MAIN_HAND, hit(claimed, Direction.UP));
        helper.assertFalse(hidden.getBlock(claimed).getValue(LeverBlock.POWERED), "the hidden lever in the claim was flipped");
        HiddenEditing.use(player, InteractionHand.MAIN_HAND, hit(free, Direction.UP));
        helper.assertTrue(hidden.getBlock(free).getValue(LeverBlock.POWERED), "the hidden lever outside the claim was not flipped");

        // With an item in hand too: the whole click is refused, nothing is placed against the lever.
        hold(player, new ItemStack(Items.STONE, 2));
        HiddenEditing.use(player, InteractionHand.MAIN_HAND, hit(claimed, Direction.UP));
        helper.assertFalse(hidden.getBlock(claimed).getValue(LeverBlock.POWERED), "the hidden lever in the claim was flipped, item in hand");
        helper.assertValueEqual(player.getMainHandItem().getCount(), 2, "stones in hand");
        finish(helper, player, claimed, free);
    }
}
