package dev.phasecraft.test;

import dev.phasecraft.layer.HiddenLayer;
import dev.phasecraft.test.TestClaims.Action;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.arguments.EntityAnchorArgument;
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
 * Claims and spawn protection hold for a player standing inside the hidden layer (InsideProtection): the key K
 * lets any Creative player in, and what they break, place and click in there, with vanilla's own clicks, is
 * refused where the visible world at that spot is protected from them. {@link TestClaims} knows claims in the
 * Overworld only, as real claim mods do; each claim here refuses one action, so each test shows its own question
 * is asked. Clicks go through the server's own handlers (ServerPlayerGameMode), as a real client's do.
 */
public class InsideProtectionGameTests {
    private static final BlockPos CLAIMED = new BlockPos(3, 2, 3);
    private static final BlockPos FREE = new BlockPos(5, 2, 3);
    private static final UUID SOMEONE_ELSE = UUID.randomUUID();

    /** A Creative player inside the hidden layer, over a hidden stone floor. */
    private static ServerPlayer playerInside(GameTestHelper helper) {
        HiddenLayer hidden = HiddenLayer.of(helper.getLevel());
        for (BlockPos spot : new BlockPos[] {CLAIMED, FREE}) {
            hidden.setBlock(helper.absolutePos(spot).below(), Blocks.STONE.defaultBlockState());
        }
        ServerPlayer player = TestPlayers.join(helper, GameType.CREATIVE);
        Vec3 spot = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(4, 2, 5)));
        player.teleport(new TeleportTransition(hidden.level(), spot, Vec3.ZERO, 180, 0, TeleportTransition.DO_NOTHING));
        return player;
    }

    private static void click(ServerPlayer player, BlockPos pos, Direction face) {
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos).relative(face, 0.5), face, pos, false);
        player.gameMode.useItemOn(player, player.level(), player.getMainHandItem(), InteractionHand.MAIN_HAND, hit);
    }

    private static void finish(GameTestHelper helper, ServerPlayer player) {
        HiddenLayer hidden = HiddenLayer.of(helper.getLevel());
        for (BlockPos spot : new BlockPos[] {CLAIMED, FREE}) {
            BlockPos at = helper.absolutePos(spot);
            TestClaims.unclaim(at);
            TestSpawnProtection.PROTECTED.remove(at);
            hidden.removeBlock(at);
            hidden.removeBlock(at.below());
        }
        TestPlayers.leave(helper, player);
        helper.succeed();
    }

    /** Breaking a hidden block from inside is refused in someone's claim and under spawn protection, and works elsewhere. */
    @GameTest(maxTicks = 20)
    public void protectionRefusesBreakingFromInside(GameTestHelper helper) {
        ServerPlayer player = playerInside(helper);
        HiddenLayer hidden = HiddenLayer.of(helper.getLevel());
        BlockPos claimed = helper.absolutePos(CLAIMED);
        BlockPos free = helper.absolutePos(FREE);
        hidden.setBlock(claimed, Blocks.GOLD_BLOCK.defaultBlockState());
        hidden.setBlock(free, Blocks.GOLD_BLOCK.defaultBlockState());
        TestClaims.claim(SOMEONE_ELSE, Set.of(Action.BREAK), claimed);

        helper.assertFalse(player.gameMode.destroyBlock(claimed), "broke a hidden block in someone's claim from inside");
        helper.assertValueEqual(hidden.getBlock(claimed).getBlock(), Blocks.GOLD_BLOCK, "hidden block in the claim");
        helper.assertTrue(player.gameMode.destroyBlock(free), "could not break a hidden block outside the claim");
        helper.assertValueEqual(hidden.getBlock(free).getBlock(), Blocks.AIR, "hidden block outside the claim");

        // The owner may.
        TestClaims.claim(player.getUUID(), Set.of(Action.BREAK), claimed);
        helper.assertTrue(player.gameMode.destroyBlock(claimed), "the owner could not break in their own claim");

        // Spawn protection, which vanilla keeps for the Overworld only.
        TestClaims.unclaim(claimed);
        hidden.setBlock(claimed, Blocks.GOLD_BLOCK.defaultBlockState());
        TestSpawnProtection.PROTECTED.add(claimed);
        helper.assertFalse(player.gameMode.destroyBlock(claimed), "broke a hidden block under spawn protection from inside");
        helper.assertValueEqual(hidden.getBlock(claimed).getBlock(), Blocks.GOLD_BLOCK, "hidden block under spawn protection");
        finish(helper, player);
    }

    /** Placing from inside is refused in someone's claim, also against a block outside it on the face towards it. */
    @GameTest(maxTicks = 20)
    public void claimRefusesPlacingFromInside(GameTestHelper helper) {
        ServerPlayer player = playerInside(helper);
        HiddenLayer hidden = HiddenLayer.of(helper.getLevel());
        BlockPos claimed = helper.absolutePos(CLAIMED);
        BlockPos free = helper.absolutePos(FREE);
        TestClaims.claim(SOMEONE_ELSE, Set.of(Action.PLACE), claimed);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.REDSTONE_LAMP));

        click(player, claimed.below(), Direction.UP);
        helper.assertValueEqual(hidden.getBlock(claimed).getBlock(), Blocks.AIR, "hidden block placed in the claim from inside");
        click(player, free.below(), Direction.UP);
        helper.assertValueEqual(hidden.getBlock(free).getBlock(), Blocks.REDSTONE_LAMP, "hidden block placed outside the claim");

        // Against a hidden block beside the claim, on the face towards it.
        hidden.setBlock(claimed.east(), Blocks.STONE.defaultBlockState());
        click(player, claimed.east(), Direction.WEST);
        helper.assertValueEqual(hidden.getBlock(claimed).getBlock(), Blocks.AIR, "hidden block placed into the claim from beside it");
        hidden.removeBlock(claimed.east());

        TestClaims.claim(player.getUUID(), Set.of(Action.PLACE), claimed);
        click(player, claimed.below(), Direction.UP);
        helper.assertValueEqual(hidden.getBlock(claimed).getBlock(), Blocks.REDSTONE_LAMP, "the owner's hidden block in their claim");
        finish(helper, player);
    }

    /** A bucket poured from inside is refused in someone's claim (the player's aim decides where, as in vanilla). */
    @GameTest(maxTicks = 20)
    public void claimRefusesBucketsFromInside(GameTestHelper helper) {
        ServerPlayer player = playerInside(helper);
        HiddenLayer hidden = HiddenLayer.of(helper.getLevel());
        BlockPos claimed = helper.absolutePos(CLAIMED);
        BlockPos free = helper.absolutePos(FREE);
        TestClaims.claim(SOMEONE_ELSE, Set.of(Action.PLACE), claimed);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.WATER_BUCKET));

        // Aiming at the top of the hidden floor block under each spot: the water would go on top of it.
        player.lookAt(EntityAnchorArgument.Anchor.EYES, Vec3.atCenterOf(claimed.below()).add(0, 0.5, 0));
        player.gameMode.useItem(player, player.level(), player.getMainHandItem(), InteractionHand.MAIN_HAND);
        helper.assertValueEqual(hidden.getBlock(claimed).getBlock(), Blocks.AIR, "water poured into the claim from inside");
        player.lookAt(EntityAnchorArgument.Anchor.EYES, Vec3.atCenterOf(free.below()).add(0, 0.5, 0));
        player.gameMode.useItem(player, player.level(), player.getMainHandItem(), InteractionHand.MAIN_HAND);
        helper.assertValueEqual(hidden.getBlock(free).getBlock(), Blocks.WATER, "water poured outside the claim");
        finish(helper, player);
    }

    /** Using a hidden block from inside (a lever) is refused in someone's claim. */
    @GameTest(maxTicks = 20)
    public void claimRefusesUsingFromInside(GameTestHelper helper) {
        ServerPlayer player = playerInside(helper);
        HiddenLayer hidden = HiddenLayer.of(helper.getLevel());
        BlockPos claimed = helper.absolutePos(CLAIMED);
        BlockPos free = helper.absolutePos(FREE);
        BlockState lever = Blocks.LEVER.defaultBlockState().setValue(BlockStateProperties.ATTACH_FACE, AttachFace.FLOOR);
        hidden.setBlock(claimed, lever);
        hidden.setBlock(free, lever);
        TestClaims.claim(SOMEONE_ELSE, Set.of(Action.USE), claimed);

        click(player, claimed, Direction.UP);
        helper.assertFalse(hidden.getBlock(claimed).getValue(LeverBlock.POWERED), "flipped a hidden lever in someone's claim from inside");
        click(player, free, Direction.UP);
        helper.assertTrue(hidden.getBlock(free).getValue(LeverBlock.POWERED), "could not flip a hidden lever outside the claim");
        finish(helper, player);
    }
}
