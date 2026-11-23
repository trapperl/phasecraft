package dev.phasecraft.test;

import dev.phasecraft.edit.HiddenEditing;
import dev.phasecraft.layer.Access;
import dev.phasecraft.layer.HiddenLayer;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Survival play: with the game rule phasecraft:survival_access (on by default),
 * Survival players may see the overlay and use editing mode, by Survival rules:
 * placing uses up the item, breaking takes the block's real mining time and
 * drops it in the visible world, middle-click only selects what is already in
 * the inventory. Adventure players never may. With the rule off, only Creative
 * (and Spectator, for the overlay) may.
 */
public class SurvivalPlayGameTests {
    private static final BlockPos SPOT = new BlockPos(3, 2, 3);

    /** A Survival player standing on a floor two blocks south of SPOT. */
    private static ServerPlayer survivalPlayer(GameTestHelper helper) {
        for (int x = 0; x <= 7; x++) {
            for (int z = 0; z <= 7; z++) {
                helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
            }
        }
        ServerPlayer player = TestPlayers.join(helper, GameType.SURVIVAL);
        Vec3 spot = Vec3.atBottomCenterOf(helper.absolutePos(SPOT.south(2)));
        player.teleport(new TeleportTransition(helper.getLevel(), spot, Vec3.ZERO, 180, 0, TeleportTransition.DO_NOTHING));
        // A test player has no client moving it, so vanilla never marks it on the ground;
        // mining in the air is five times slower.
        player.setOnGround(true);
        return player;
    }

    private static BlockHitResult hit(GameTestHelper helper, BlockPos rel) {
        BlockPos pos = helper.absolutePos(rel);
        return new BlockHitResult(Vec3.atCenterOf(pos).relative(Direction.SOUTH, 0.5), Direction.SOUTH, pos, false);
    }

    private static void setRule(GameTestHelper helper, boolean value) {
        helper.getLevel().getGameRules().set(Access.SURVIVAL_ACCESS, value, helper.getLevel().getServer());
    }

    /** Who may see and edit: Creative always; Survival only with the rule on; Adventure never. */
    @GameTest(maxTicks = 20)
    public void accessFollowsGameModeAndRule(GameTestHelper helper) {
        ServerPlayer survival = TestPlayers.join(helper, GameType.SURVIVAL);
        ServerPlayer adventure = TestPlayers.join(helper, GameType.ADVENTURE);
        ServerPlayer creative = TestPlayers.join(helper, GameType.CREATIVE);
        try {
            helper.assertTrue(helper.getLevel().getGameRules().get(Access.SURVIVAL_ACCESS), "the rule is on by default");
            helper.assertTrue(Access.mayEdit(survival) && Access.maySeeOverlay(survival), "Survival with the rule on");
            helper.assertFalse(Access.mayEdit(adventure) || Access.maySeeOverlay(adventure), "Adventure");
            setRule(helper, false);
            helper.assertFalse(Access.mayEdit(survival) || Access.maySeeOverlay(survival), "Survival with the rule off");
            helper.assertTrue(Access.mayEdit(creative) && Access.maySeeOverlay(creative), "Creative with the rule off");
        } finally {
            setRule(helper, true);
            TestPlayers.leave(helper, survival);
            TestPlayers.leave(helper, adventure);
            TestPlayers.leave(helper, creative);
        }
        helper.succeed();
    }

    /** Placing a hidden block in Survival uses up the item. */
    @GameTest(maxTicks = 20)
    public void survivalPlacingUsesTheItem(GameTestHelper helper) {
        ServerPlayer player = survivalPlayer(helper);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STONE, 2));
        HiddenEditing.use(player, InteractionHand.MAIN_HAND, hit(helper, SPOT));
        helper.assertValueEqual(HiddenLayer.of(helper.getLevel()).getBlock(helper.absolutePos(SPOT)).getBlock(), Blocks.STONE,
                "hidden block placed");
        helper.assertValueEqual(player.getMainHandItem().getCount(), 1, "stones left in hand");
        TestPlayers.leave(helper, player);
        helper.succeed();
    }

    /**
     * Breaking a hidden block in Survival takes its mining time: a break sent at once, or too
     * soon after starting, is refused; after the time, the block is mined, its drop lands in
     * the visible world and the tool wears.
     */
    @GameTest(maxTicks = 200)
    public void survivalBreakingTakesMiningTimeAndDropsVisibly(GameTestHelper helper) {
        ServerPlayer player = survivalPlayer(helper);
        HiddenLayer hidden = HiddenLayer.of(helper.getLevel());
        BlockPos pos = helper.absolutePos(SPOT);
        hidden.setBlock(pos, Blocks.STONE.defaultBlockState());
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_PICKAXE));

        helper.startSequence()
                .thenExecute(() -> {
                    helper.assertFalse(HiddenEditing.breakBlock(player, pos), "break without starting to mine");
                    HiddenEditing.startBreaking(player, pos);
                    helper.assertFalse(HiddenEditing.breakBlock(player, pos), "break at once after starting");
                    helper.assertValueEqual(hidden.getBlock(pos).getBlock(), Blocks.STONE, "hidden stone still there");
                })
                .thenIdle(12) // stone with an iron pickaxe: 11 ticks, by vanilla's mining speed
                .thenExecute(() -> {
                    helper.assertTrue(HiddenEditing.breakBlock(player, pos), "break after the mining time");
                    helper.assertValueEqual(hidden.getBlock(pos).getBlock(), Blocks.AIR, "hidden stone mined");
                    helper.assertValueEqual(player.getMainHandItem().getDamageValue(), 1, "pickaxe wear");
                })
                .thenWaitUntil(() -> helper.assertFalse(helper.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(1),
                        item -> item.getItem().is(Items.COBBLESTONE)).isEmpty(), "cobblestone dropped in the visible world"))
                .thenExecute(() -> TestPlayers.leave(helper, player))
                .thenSucceed();
    }

    /**
     * With the rule off, a Survival player cannot place or break in the hidden layer. The rule
     * is world-wide and tests run side by side, so it is only ever off within one step.
     */
    @GameTest(maxTicks = 60)
    public void ruleOffRefusesSurvivalEditing(GameTestHelper helper) {
        ServerPlayer player = survivalPlayer(helper);
        HiddenLayer hidden = HiddenLayer.of(helper.getLevel());
        BlockPos pos = helper.absolutePos(SPOT);
        hidden.setBlock(pos, Blocks.DIRT.defaultBlockState());
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STONE, 2));

        helper.startSequence()
                .thenExecute(() -> HiddenEditing.startBreaking(player, pos))
                .thenIdle(30) // dirt by hand: 15 ticks
                .thenExecute(() -> {
                    setRule(helper, false);
                    try {
                        HiddenEditing.use(player, InteractionHand.MAIN_HAND, hit(helper, SPOT.east()));
                        helper.assertValueEqual(hidden.getBlock(helper.absolutePos(SPOT.east())).getBlock(), Blocks.AIR,
                                "placed with the rule off");
                        helper.assertFalse(HiddenEditing.breakBlock(player, pos), "broke with the rule off");
                    } finally {
                        setRule(helper, true);
                    }
                    helper.assertTrue(HiddenEditing.breakBlock(player, pos), "the same break with the rule back on");
                    TestPlayers.leave(helper, player);
                })
                .thenSucceed();
    }

    /** Middle-click in Survival selects a matching item already carried, and never makes one. */
    @GameTest(maxTicks = 20)
    public void survivalPickNeverMakesItems(GameTestHelper helper) {
        ServerPlayer player = survivalPlayer(helper);
        BlockPos pos = helper.absolutePos(SPOT);
        HiddenLayer.of(helper.getLevel()).setBlock(pos, Blocks.GOLD_BLOCK.defaultBlockState());
        HiddenEditing.pick(player, pos, true);
        helper.assertFalse(player.getInventory().contains(new ItemStack(Items.GOLD_BLOCK)), "Survival pick created a gold block");
        player.getInventory().setItem(5, new ItemStack(Items.GOLD_BLOCK));
        HiddenEditing.pick(player, pos, true);
        helper.assertValueEqual(player.getInventory().getSelectedSlot(), 5, "selected hotbar slot after picking");
        TestPlayers.leave(helper, player);
        helper.succeed();
    }
}
