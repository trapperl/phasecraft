package dev.phasecraft.test;

import dev.phasecraft.PhaseCraftBlocks;
import dev.phasecraft.edit.HiddenEditing;
import dev.phasecraft.layer.HiddenLayer;
import java.util.List;
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
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Editing mode against a modified client: the server handlers (HiddenEditing) are called as a
 * forged edit_use / edit_start_break / edit_break payload would call them, and must apply the
 * protections vanilla applies to the same click on the visible world.
 */
public class EditingSecurityGameTests {
    private static final BlockPos SPOT = new BlockPos(3, 2, 3);

    /** A player standing on a floor two blocks south of SPOT. */
    private static ServerPlayer player(GameTestHelper helper, GameType mode) {
        for (int x = 0; x <= 7; x++) {
            for (int z = 0; z <= 7; z++) {
                helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
            }
        }
        ServerPlayer player = TestPlayers.join(helper, mode);
        Vec3 spot = Vec3.atBottomCenterOf(helper.absolutePos(SPOT.south(2)));
        player.teleport(new TeleportTransition(helper.getLevel(), spot, Vec3.ZERO, 180, 0, TeleportTransition.DO_NOTHING));
        player.setOnGround(true); // no client moves a test player; mining in the air is five times slower
        return player;
    }

    private static BlockHitResult hit(BlockPos pos) {
        return new BlockHitResult(Vec3.atCenterOf(pos).relative(Direction.SOUTH, 0.5), Direction.SOUTH, pos, false);
    }

    private static void protect(BlockPos... spots) {
        TestSpawnProtection.PROTECTED.addAll(List.of(spots));
    }

    private static void finish(GameTestHelper helper, ServerPlayer player, BlockPos... spots) {
        List.of(spots).forEach(TestSpawnProtection.PROTECTED::remove);
        TestPlayers.leave(helper, player);
    }

    /**
     * Spawn protection holds for the hidden layer at the same spots: a Survival player cannot
     * place hidden blocks there. Else two forged clicks take a protected visible block away:
     * a phase shifter hidden in it, a redstone block hidden beside it.
     */
    @GameTest(maxTicks = 60)
    public void spawnProtectionRefusesHiddenPlacing(GameTestHelper helper) {
        ServerPlayer player = player(helper, GameType.SURVIVAL);
        HiddenLayer hidden = HiddenLayer.of(helper.getLevel());
        BlockPos wall = helper.absolutePos(SPOT);
        BlockPos beside = wall.east();
        helper.setBlock(SPOT, Blocks.STONE);
        protect(wall, beside);

        helper.startSequence()
                .thenExecute(() -> {
                    player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(PhaseCraftBlocks.PHASE_SHIFTER_ITEM));
                    HiddenEditing.use(player, InteractionHand.MAIN_HAND, hit(wall));
                    player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.REDSTONE_BLOCK));
                    HiddenEditing.use(player, InteractionHand.MAIN_HAND, hit(beside));
                })
                .thenIdle(10)
                .thenExecute(() -> {
                    helper.assertValueEqual(helper.getLevel().getBlockState(wall).getBlock(), Blocks.STONE,
                            "the spawn-protected visible block");
                    helper.assertValueEqual(hidden.getBlock(wall).getBlock(), Blocks.AIR, "hidden block in the protected spot");
                    helper.assertValueEqual(hidden.getBlock(beside).getBlock(), Blocks.AIR, "hidden block beside it");
                    helper.assertValueEqual(player.getMainHandItem().getCount(), 1, "redstone blocks still in hand");
                    finish(helper, player, wall, beside);
                })
                .thenSucceed();
    }

    /** Nor break hidden blocks there, however long they mine. */
    @GameTest(maxTicks = 60)
    public void spawnProtectionRefusesHiddenBreaking(GameTestHelper helper) {
        ServerPlayer player = player(helper, GameType.SURVIVAL);
        HiddenLayer hidden = HiddenLayer.of(helper.getLevel());
        BlockPos pos = helper.absolutePos(SPOT);
        hidden.setBlock(pos, Blocks.DIRT.defaultBlockState());
        protect(pos);

        helper.startSequence()
                .thenExecute(() -> HiddenEditing.startBreaking(player, pos))
                .thenIdle(30) // dirt by hand: 15 ticks
                .thenExecute(() -> {
                    helper.assertFalse(HiddenEditing.breakBlock(player, pos), "broke a hidden block under spawn protection");
                    helper.assertValueEqual(hidden.getBlock(pos).getBlock(), Blocks.DIRT, "hidden block in the protected spot");
                    finish(helper, player, pos);
                })
                .thenSucceed();
    }

    /** Nor pour a bucket there. */
    @GameTest(maxTicks = 20)
    public void spawnProtectionRefusesHiddenBuckets(GameTestHelper helper) {
        ServerPlayer player = player(helper, GameType.SURVIVAL);
        BlockPos pos = helper.absolutePos(SPOT);
        protect(pos);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.WATER_BUCKET));
        HiddenEditing.use(player, InteractionHand.MAIN_HAND, hit(pos));
        helper.assertValueEqual(HiddenLayer.of(helper.getLevel()).getBlock(pos).getBlock(), Blocks.AIR,
                "hidden block in the protected spot");
        helper.assertTrue(player.getMainHandItem().is(Items.WATER_BUCKET), "the bucket is still full");
        finish(helper, player, pos);
        helper.succeed();
    }

    /**
     * A click whose hit point is not a number is refused, as vanilla refuses it (it passes any
     * "too far from the block" test written as a comparison that NaN fails).
     */
    @GameTest(maxTicks = 20)
    public void hitPointThatIsNotANumberIsRefused(GameTestHelper helper) {
        ServerPlayer player = player(helper, GameType.SURVIVAL);
        BlockPos pos = helper.absolutePos(SPOT);
        Vec3 centre = Vec3.atCenterOf(pos);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STONE, 2));
        for (Vec3 forged : List.of(new Vec3(Double.NaN, centre.y, centre.z), new Vec3(centre.x, Double.NaN, centre.z),
                new Vec3(centre.x, centre.y, Double.NaN))) {
            HiddenEditing.use(player, InteractionHand.MAIN_HAND, new BlockHitResult(forged, Direction.SOUTH, pos, false));
        }
        helper.assertValueEqual(HiddenLayer.of(helper.getLevel()).getBlock(pos).getBlock(), Blocks.AIR, "hidden block placed");
        helper.assertValueEqual(player.getMainHandItem().getCount(), 2, "stones left in hand");
        finish(helper, player);
        helper.succeed();
    }

    /** A Creative player who is not an operator cannot break a hidden command block, as in the visible world. */
    @GameTest(maxTicks = 20)
    public void creativeNonOperatorCannotBreakHiddenCommandBlock(GameTestHelper helper) {
        ServerPlayer player = player(helper, GameType.CREATIVE);
        HiddenLayer hidden = HiddenLayer.of(helper.getLevel());
        BlockPos pos = helper.absolutePos(SPOT);
        hidden.setBlock(pos, Blocks.COMMAND_BLOCK.defaultBlockState());
        helper.assertFalse(player.canUseGameMasterBlocks(), "the test player is an operator");
        helper.assertFalse(HiddenEditing.breakBlock(player, pos), "broke a hidden command block");
        helper.assertValueEqual(hidden.getBlock(pos).getBlock(), Blocks.COMMAND_BLOCK, "hidden block");
        finish(helper, player);
        helper.succeed();
    }
}
