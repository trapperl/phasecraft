package dev.phasecraft.test;

import dev.phasecraft.PhaseCraftBlocks;
import dev.phasecraft.edit.HiddenEditing;
import dev.phasecraft.layer.HiddenLayer;
import dev.phasecraft.shifter.AreaShifterBlock;
import dev.phasecraft.shifter.LineShifterBlock;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Nothing is doubled and nothing is lost (a hunt for the ways a Survival player could duplicate or lose items or
 * blocks through PhaseCraft): each test counts what there is, in both layers, before and after.
 *
 * <ul>
 *   <li>Editing mode in Survival: what a break or a click spills in the hidden layer (a chest's contents, the torch
 *       that stood on the block, a jukebox's record) comes out in the visible world, where the player is; pick
 *       copies no contents.</li>
 *   <li>Shifters: a held block that cannot stand where it goes back (a sign over nothing) drops; an area shifter
 *       puts a wall back before the sign on it; a held lectern dropped as an item drops its book; an explosion in
 *       the hidden layer, a second shifter on the spot, a shifter broken mid-move or by a Survival player, an open
 *       chest taken away: all counted.</li>
 *   <li>Phased hoppers and droppers working on a chest that a shifter takes away meanwhile; a phase piston against
 *       a chest.</li>
 * </ul>
 */
public class ItemConservationGameTests {
    private static final BlockPos SPOT = new BlockPos(3, 2, 3);
    static final String RULE_ON = "phasecraft-test:area_shifters_on";

    private static void floor(GameTestHelper helper) {
        for (int x = 0; x <= 7; x++) {
            for (int z = 0; z <= 7; z++) {
                helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
            }
        }
    }

    private static HiddenLayer hidden(GameTestHelper helper) {
        return HiddenLayer.of(helper.getLevel());
    }

    private static void setHidden(GameTestHelper helper, BlockPos rel, BlockState state) {
        hidden(helper).setBlock(helper.absolutePos(rel), state);
    }

    private static void shifter(GameTestHelper helper, BlockPos rel) {
        setHidden(helper, rel, PhaseCraftBlocks.PHASE_SHIFTER.defaultBlockState());
    }

    /** A hidden redstone block at rel, or none. */
    private static void redstone(GameTestHelper helper, BlockPos rel, boolean on) {
        setHidden(helper, rel, on ? Blocks.REDSTONE_BLOCK.defaultBlockState() : Blocks.AIR.defaultBlockState());
    }

    /** The whole test structure and a little around it. */
    private static AABB area(GameTestHelper helper) {
        return new AABB(Vec3.atCenterOf(helper.absolutePos(BlockPos.ZERO)), Vec3.atCenterOf(helper.absolutePos(new BlockPos(7, 7, 7)))).inflate(2);
    }

    /** How many of an item lie in a level as dropped items, in the test's area. */
    private static int lying(GameTestHelper helper, ServerLevel level, Item item) {
        return level.getEntitiesOfClass(ItemEntity.class, area(helper), e -> e.getItem().is(item)).stream()
                .mapToInt(e -> e.getItem().getCount()).sum();
    }

    /** How many of an item the containers of a level hold, in the test's area. */
    private static int stored(GameTestHelper helper, ServerLevel level, Item item) {
        int n = 0;
        for (BlockPos rel : BlockPos.betweenClosed(BlockPos.ZERO, new BlockPos(7, 7, 7))) {
            BlockEntity entity = level.getBlockEntity(helper.absolutePos(rel));
            if (entity instanceof Container container) {
                n += container.countItem(item);
            }
        }
        return n;
    }

    /** Everything of an item there is in the test's area, in both layers: lying about or in containers. */
    private static int all(GameTestHelper helper, Item item) {
        ServerLevel below = hidden(helper).level();
        return lying(helper, helper.getLevel(), item) + stored(helper, helper.getLevel(), item)
                + lying(helper, below, item) + stored(helper, below, item);
    }

    private static ChestBlockEntity chest(GameTestHelper helper, BlockPos rel, Item item, int count) {
        helper.setBlock(rel, Blocks.CHEST);
        ChestBlockEntity chest = helper.getBlockEntity(rel, ChestBlockEntity.class);
        chest.setItem(0, new ItemStack(item, count));
        return chest;
    }

    /** A Survival player standing on a floor two blocks south of SPOT. */
    private static ServerPlayer survivalPlayer(GameTestHelper helper) {
        floor(helper);
        ServerPlayer player = TestPlayers.join(helper, GameType.SURVIVAL);
        Vec3 spot = Vec3.atBottomCenterOf(helper.absolutePos(SPOT.south(2)));
        player.teleport(new TeleportTransition(helper.getLevel(), spot, Vec3.ZERO, 180, 0, TeleportTransition.DO_NOTHING));
        player.setOnGround(true); // no client moves it; mining in the air is five times slower
        return player;
    }

    private static BlockHitResult hit(GameTestHelper helper, BlockPos rel) {
        BlockPos pos = helper.absolutePos(rel);
        return new BlockHitResult(Vec3.atCenterOf(pos).relative(Direction.SOUTH, 0.5), Direction.SOUTH, pos, false);
    }

    /** Starts mining the hidden block at rel; the returned check passes once the break has gone through. */
    private static Runnable mine(GameTestHelper helper, ServerPlayer player, BlockPos rel) {
        BlockPos pos = helper.absolutePos(rel);
        HiddenEditing.startBreaking(player, pos);
        return () -> helper.assertTrue(HiddenEditing.breakBlock(player, pos), "the hidden block is not mined yet");
    }

    // ---- Editing mode, Survival ----

    /**
     * A Survival player mines a hidden chest: the chest and what was in it come out in the visible world, where the
     * player is (they cannot go into the hidden layer to fetch them), and nothing is left lying in the hidden layer.
     */
    @GameTest(maxTicks = 200)
    public void survivalBreakingAHiddenChestGivesItsContents(GameTestHelper helper) {
        ServerPlayer player = survivalPlayer(helper);
        setHidden(helper, SPOT, Blocks.CHEST.defaultBlockState());
        ((Container) hidden(helper).level().getBlockEntity(helper.absolutePos(SPOT))).setItem(0, new ItemStack(Items.DIAMOND, 5));
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_AXE));
        Runnable[] mined = new Runnable[1];
        helper.startSequence()
                .thenExecute(() -> mined[0] = mine(helper, player, SPOT))
                .thenWaitUntil(() -> mined[0].run())
                .thenExecute(() -> {
                    helper.assertValueEqual(lying(helper, hidden(helper).level(), Items.DIAMOND), 0, "diamonds left lying in the hidden layer");
                    helper.assertValueEqual(lying(helper, helper.getLevel(), Items.DIAMOND), 5, "diamonds dropped in the visible world");
                    helper.assertValueEqual(lying(helper, helper.getLevel(), Items.CHEST), 1, "chests dropped in the visible world");
                    helper.assertValueEqual(all(helper, Items.DIAMOND), 5, "diamonds in all");
                    helper.assertValueEqual(all(helper, Items.CHEST), 1, "chests in all");
                    TestPlayers.leave(helper, player);
                })
                .thenSucceed();
    }

    /** The torch standing on a hidden block a Survival player mines pops off into the visible world too. */
    @GameTest(maxTicks = 200)
    public void survivalBreakingDropsWhatStoodOnTheBlock(GameTestHelper helper) {
        ServerPlayer player = survivalPlayer(helper);
        setHidden(helper, SPOT, Blocks.STONE.defaultBlockState());
        setHidden(helper, SPOT.above(), Blocks.TORCH.defaultBlockState());
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_PICKAXE));
        Runnable[] mined = new Runnable[1];
        helper.startSequence()
                .thenExecute(() -> mined[0] = mine(helper, player, SPOT))
                .thenWaitUntil(() -> mined[0].run())
                .thenExecute(() -> {
                    helper.assertValueEqual(hidden(helper).getBlock(helper.absolutePos(SPOT.above())).getBlock(), Blocks.AIR, "the hidden torch");
                    helper.assertValueEqual(lying(helper, hidden(helper).level(), Items.TORCH), 0, "torches left lying in the hidden layer");
                    helper.assertValueEqual(lying(helper, helper.getLevel(), Items.TORCH), 1, "torches dropped in the visible world");
                    helper.assertValueEqual(lying(helper, helper.getLevel(), Items.COBBLESTONE), 1, "cobblestone dropped in the visible world");
                    TestPlayers.leave(helper, player);
                })
                .thenSucceed();
    }

    /** A Survival player clicks a hidden jukebox: the record it gives back comes out in the visible world. */
    @GameTest(maxTicks = 40)
    public void survivalClickGetsTheRecordOutOfAHiddenJukebox(GameTestHelper helper) {
        ServerPlayer player = survivalPlayer(helper);
        setHidden(helper, SPOT, Blocks.JUKEBOX.defaultBlockState());
        ((JukeboxBlockEntity) hidden(helper).level().getBlockEntity(helper.absolutePos(SPOT))).setTheItem(new ItemStack(Items.MUSIC_DISC_CAT));
        helper.startSequence()
                .thenIdle(2)
                .thenExecute(() -> {
                    helper.assertTrue(hidden(helper).getBlock(helper.absolutePos(SPOT)).getValue(BlockStateProperties.HAS_RECORD), "no record in the jukebox");
                    HiddenEditing.use(player, InteractionHand.MAIN_HAND, hit(helper, SPOT));
                    helper.assertValueEqual(lying(helper, hidden(helper).level(), Items.MUSIC_DISC_CAT), 0, "records left lying in the hidden layer");
                    helper.assertValueEqual(lying(helper, helper.getLevel(), Items.MUSIC_DISC_CAT), 1, "records out in the visible world");
                    helper.assertValueEqual(all(helper, Items.MUSIC_DISC_CAT), 1, "records in all");
                    TestPlayers.leave(helper, player);
                })
                .thenSucceed();
    }

    /** Ctrl + middle click in Survival selects the chest carried, and copies nothing of the hidden chest's contents. */
    @GameTest(maxTicks = 20)
    public void survivalPickCopiesNoContents(GameTestHelper helper) {
        ServerPlayer player = survivalPlayer(helper);
        BlockPos pos = helper.absolutePos(SPOT);
        setHidden(helper, SPOT, Blocks.CHEST.defaultBlockState());
        ((Container) hidden(helper).level().getBlockEntity(pos)).setItem(0, new ItemStack(Items.DIAMOND, 5));
        HiddenEditing.pick(player, pos, true);
        helper.assertTrue(player.getInventory().isEmpty(), "Survival pick made something: " + player.getInventory().getItem(0));
        player.getInventory().setItem(5, new ItemStack(Items.CHEST));
        HiddenEditing.pick(player, pos, true);
        ItemStack carried = player.getInventory().getItem(5);
        helper.assertTrue(carried.getCount() == 1 && ItemStack.isSameItemSameComponents(carried, new ItemStack(Items.CHEST)), "the carried chest was changed: " + carried);
        helper.assertValueEqual(player.getInventory().countItem(Items.CHEST), 1, "chests carried");
        helper.assertValueEqual(player.getInventory().countItem(Items.DIAMOND), 0, "diamonds carried");
        TestPlayers.leave(helper, player);
        helper.succeed();
    }

    /**
     * A Survival player mines a shifter that holds a chest: the chest goes back to its spot with its contents, the
     * shifter drops once, in the visible world.
     */
    @GameTest(maxTicks = 300)
    public void survivalBreakingAShifterGivesTheHeldChestBackOnce(GameTestHelper helper) {
        ServerPlayer player = survivalPlayer(helper);
        chest(helper, SPOT, Items.DIAMOND, 5);
        shifter(helper, SPOT);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_PICKAXE));
        Runnable[] mined = new Runnable[1];
        helper.startSequence()
                .thenIdle(2)
                .thenExecute(() -> redstone(helper, SPOT.above(), true))
                .thenWaitUntil(() -> helper.assertBlockPresent(Blocks.AIR, SPOT))
                .thenExecute(() -> mined[0] = mine(helper, player, SPOT))
                .thenWaitUntil(() -> mined[0].run())
                .thenExecute(() -> {
                    helper.assertBlockPresent(Blocks.CHEST, SPOT);
                    helper.assertValueEqual(stored(helper, helper.getLevel(), Items.DIAMOND), 5, "diamonds back in the chest");
                    helper.assertValueEqual(all(helper, Items.DIAMOND), 5, "diamonds in all");
                    helper.assertValueEqual(all(helper, Items.CHEST), 0, "chests lying about");
                    helper.assertValueEqual(lying(helper, helper.getLevel(), PhaseCraftBlocks.PHASE_SHIFTER.asItem()), 1, "shifters dropped in the visible world");
                    helper.assertValueEqual(lying(helper, hidden(helper).level(), PhaseCraftBlocks.PHASE_SHIFTER.asItem()), 0, "shifters dropped in the hidden layer");
                    TestPlayers.leave(helper, player);
                })
                .thenSucceed();
    }

    // ---- Shifters ----

    /**
     * A sign standing on a block is taken, and the block goes meanwhile: put back, the sign cannot stand there, and
     * drops as an item, as a sign does when its floor goes. It is not lost, and it comes once.
     */
    @GameTest(maxTicks = 80)
    public void aSignPutBackOverNothingDrops(GameTestHelper helper) {
        floor(helper);
        BlockPos sign = SPOT.above();
        helper.setBlock(SPOT, Blocks.STONE);
        helper.setBlock(sign, Blocks.OAK_SIGN);
        shifter(helper, sign);
        helper.startSequence()
                .thenIdle(2)
                .thenExecute(() -> redstone(helper, sign.above(), true))
                .thenWaitUntil(() -> helper.assertBlockPresent(Blocks.AIR, sign))
                .thenExecute(() -> helper.assertValueEqual(lying(helper, helper.getLevel(), Items.OAK_SIGN), 0, "signs dropped when taken"))
                .thenExecute(() -> helper.setBlock(SPOT, Blocks.AIR))
                .thenExecute(() -> redstone(helper, sign.above(), false))
                .thenIdle(5)
                .thenExecute(() -> {
                    helper.assertBlockPresent(Blocks.AIR, sign);
                    helper.assertValueEqual(lying(helper, helper.getLevel(), Items.OAK_SIGN), 1, "signs dropped");
                })
                // And it was let go of: powered again and released, no second sign comes.
                .thenExecute(() -> redstone(helper, sign.above(), true))
                .thenIdle(3)
                .thenExecute(() -> redstone(helper, sign.above(), false))
                .thenIdle(5)
                .thenExecute(() -> helper.assertValueEqual(lying(helper, helper.getLevel(), Items.OAK_SIGN), 1, "signs dropped in the end"))
                .thenSucceed();
    }

    /**
     * An area shifter takes a wall with a sign on it, and puts both back: the wall first, though the sign's spot
     * comes first in its order, so the sign hangs again instead of finding no wall.
     */
    @GameTest(environment = RULE_ON, maxTicks = 100)
    public void anAreaShifterPutsTheWallBackBeforeItsSign(GameTestHelper helper) {
        BlockPos low = new BlockPos(1, 2, 1);
        BlockPos high = new BlockPos(4, 3, 4);
        BlockPos wall = new BlockPos(2, 2, 3);
        BlockPos sign = wall.north();
        BlockState hanging = Blocks.OAK_WALL_SIGN.defaultBlockState().setValue(WallSignBlock.FACING, Direction.NORTH);
        helper.setBlock(wall, Blocks.STONE);
        helper.setBlock(sign, hanging);
        setHidden(helper, low, PhaseCraftBlocks.AREA_SHIFTER.defaultBlockState().setValue(AreaShifterBlock.DELAY, 1));
        setHidden(helper, high, PhaseCraftBlocks.AREA_SHIFTER.defaultBlockState().setValue(AreaShifterBlock.DELAY, 1));
        helper.startSequence()
                .thenIdle(3)
                .thenExecute(() -> helper.assertBlockState(sign, hanging))
                .thenExecute(() -> {
                    redstone(helper, low.west(), true);
                    redstone(helper, high.east(), true);
                })
                .thenWaitUntil(() -> {
                    helper.assertBlockPresent(Blocks.AIR, wall);
                    helper.assertBlockPresent(Blocks.AIR, sign);
                })
                .thenExecute(() -> helper.assertValueEqual(lying(helper, helper.getLevel(), Items.OAK_SIGN), 0, "signs dropped when taken"))
                .thenExecute(() -> redstone(helper, high.east(), false))
                .thenWaitUntil(() -> helper.assertBlockPresent(Blocks.STONE, wall))
                .thenIdle(3)
                .thenExecute(() -> {
                    helper.assertBlockState(sign, hanging);
                    helper.assertValueEqual(lying(helper, helper.getLevel(), Items.OAK_SIGN), 0, "signs dropped");
                })
                .thenSucceed();
    }

    /**
     * A shifter holding a lectern with a book on it is removed while bedrock holds the spot: the lectern drops, and
     * so does its book (a lectern is no container; what it holds drops as it does when the lectern is broken).
     */
    @GameTest(maxTicks = 80)
    public void aHeldLecternDroppedDropsItsBook(GameTestHelper helper) {
        floor(helper);
        helper.setBlock(SPOT, Blocks.LECTERN);
        helper.assertTrue(LecternBlock.tryPlaceBook(null, helper.getLevel(), helper.absolutePos(SPOT), helper.getBlockState(SPOT),
                new ItemStack(Items.WRITABLE_BOOK)), "the book did not go on the lectern");
        shifter(helper, SPOT);
        helper.startSequence()
                .thenIdle(2)
                .thenExecute(() -> redstone(helper, SPOT.above(), true))
                .thenWaitUntil(() -> helper.assertBlockPresent(Blocks.AIR, SPOT))
                .thenExecute(() -> helper.assertValueEqual(lying(helper, helper.getLevel(), Items.WRITABLE_BOOK), 0, "books dropped when taken"))
                .thenExecute(() -> {
                    helper.setBlock(SPOT, Blocks.BEDROCK);
                    hidden(helper).removeBlock(helper.absolutePos(SPOT));
                })
                .thenIdle(2)
                .thenExecute(() -> {
                    helper.assertValueEqual(lying(helper, helper.getLevel(), Items.LECTERN), 1, "lecterns dropped");
                    helper.assertValueEqual(lying(helper, helper.getLevel(), Items.WRITABLE_BOOK), 1, "books dropped");
                })
                .thenSucceed();
    }

    /**
     * An explosion in the hidden layer takes out a shifter that holds a chest (or its power): the chest is back in
     * its spot with its contents, once.
     */
    @GameTest(maxTicks = 80)
    public void anExplosionInTheHiddenLayerGivesTheHeldChestBack(GameTestHelper helper) {
        floor(helper);
        chest(helper, SPOT, Items.DIAMOND, 5);
        shifter(helper, SPOT);
        helper.startSequence()
                .thenIdle(2)
                .thenExecute(() -> redstone(helper, SPOT.above(), true))
                .thenWaitUntil(() -> helper.assertBlockPresent(Blocks.AIR, SPOT))
                .thenExecute(() -> {
                    Vec3 at = Vec3.atCenterOf(helper.absolutePos(SPOT.above()));
                    hidden(helper).level().explode(null, at.x, at.y, at.z, 4.0F, Level.ExplosionInteraction.TNT);
                })
                .thenWaitUntil(() -> helper.assertBlockPresent(Blocks.CHEST, SPOT))
                .thenIdle(3)
                .thenExecute(() -> {
                    helper.assertValueEqual(stored(helper, helper.getLevel(), Items.DIAMOND), 5, "diamonds back in the chest");
                    helper.assertValueEqual(all(helper, Items.DIAMOND), 5, "diamonds in all");
                    helper.assertValueEqual(all(helper, Items.CHEST), 0, "chests lying about");
                    helper.assertValueEqual(lying(helper, helper.getLevel(), PhaseCraftBlocks.PHASE_SHIFTER.asItem()), 0, "shifters in the visible world");
                })
                .thenSucceed();
    }

    /**
     * Two shifters on one spot: a phase shifter takes a chest, another chest is put there and a line shifter takes
     * that one; both give theirs back. The second one back breaks the first, which spills: every chest and every
     * item is still there, once.
     */
    @GameTest(maxTicks = 120)
    public void twoShiftersOnOneSpotDoubleNothing(GameTestHelper helper) {
        floor(helper);
        BlockPos line = SPOT.west();
        chest(helper, SPOT, Items.DIAMOND, 5);
        shifter(helper, SPOT);
        setHidden(helper, line, PhaseCraftBlocks.LINE_SHIFTER.defaultBlockState().setValue(LineShifterBlock.FACING, Direction.EAST));
        helper.startSequence()
                .thenIdle(2)
                .thenExecute(() -> redstone(helper, SPOT.above(), true))
                .thenWaitUntil(() -> helper.assertBlockPresent(Blocks.AIR, SPOT))
                .thenExecute(() -> chest(helper, SPOT, Items.EMERALD, 3))
                .thenExecute(() -> redstone(helper, line.west(), true))
                .thenWaitUntil(() -> helper.assertBlockPresent(Blocks.AIR, SPOT))
                .thenExecute(() -> redstone(helper, SPOT.above(), false))
                .thenWaitUntil(() -> helper.assertValueEqual(stored(helper, helper.getLevel(), Items.DIAMOND), 5, "diamonds back in the first chest"))
                .thenExecute(() -> redstone(helper, line.west(), false))
                .thenWaitUntil(() -> helper.assertValueEqual(stored(helper, helper.getLevel(), Items.EMERALD), 3, "emeralds back in the second chest"))
                .thenIdle(3)
                .thenExecute(() -> {
                    helper.assertBlockPresent(Blocks.CHEST, SPOT);
                    helper.assertValueEqual(lying(helper, helper.getLevel(), Items.DIAMOND), 5, "diamonds spilled from the broken chest");
                    helper.assertValueEqual(all(helper, Items.DIAMOND), 5, "diamonds in all");
                    helper.assertValueEqual(all(helper, Items.EMERALD), 3, "emeralds in all");
                    helper.assertValueEqual(lying(helper, helper.getLevel(), Items.CHEST), 1, "chests dropped");
                })
                .thenSucceed();
    }

    /** A line shifter broken halfway through taking its line gives back what it took: every block, the chest with its contents. */
    @GameTest(maxTicks = 100)
    public void aLineShifterBrokenMidMoveGivesEverythingBack(GameTestHelper helper) {
        floor(helper);
        BlockPos at = new BlockPos(1, 2, 3);
        for (int d = 1; d <= 4; d++) {
            helper.setBlock(at.east(d), Blocks.GOLD_BLOCK);
        }
        chest(helper, at.east(3), Items.DIAMOND, 5);
        setHidden(helper, at, PhaseCraftBlocks.LINE_SHIFTER.defaultBlockState().setValue(LineShifterBlock.FACING, Direction.EAST)
                .setValue(LineShifterBlock.DELAY, 4));
        helper.startSequence()
                .thenIdle(2)
                .thenExecute(() -> redstone(helper, at.west(), true))
                .thenWaitUntil(() -> helper.assertBlockPresent(Blocks.AIR, at.east(3)))
                .thenExecute(() -> {
                    helper.assertBlockPresent(Blocks.GOLD_BLOCK, at.east(1));
                    hidden(helper).removeBlock(helper.absolutePos(at));
                })
                .thenIdle(3)
                .thenExecute(() -> {
                    helper.assertBlockPresent(Blocks.GOLD_BLOCK, at.east(1));
                    helper.assertBlockPresent(Blocks.GOLD_BLOCK, at.east(2));
                    helper.assertBlockPresent(Blocks.CHEST, at.east(3));
                    helper.assertBlockPresent(Blocks.GOLD_BLOCK, at.east(4));
                    helper.assertValueEqual(stored(helper, helper.getLevel(), Items.DIAMOND), 5, "diamonds back in the chest");
                    helper.assertValueEqual(all(helper, Items.DIAMOND), 5, "diamonds in all");
                    helper.assertValueEqual(all(helper, Items.GOLD_BLOCK) + all(helper, Items.CHEST), 0, "blocks lying about");
                })
                .thenSucceed();
    }

    /**
     * A player has a chest open when a shifter takes it: the open chest is no longer theirs to click in (the game
     * refuses clicks in a menu that is not valid), so nothing can be taken from a chest that is also held.
     */
    @GameTest(maxTicks = 80)
    public void anOpenChestTakenAwayIsClosedToThePlayer(GameTestHelper helper) {
        ServerPlayer player = survivalPlayer(helper);
        ChestBlockEntity chest = chest(helper, SPOT, Items.DIAMOND, 5);
        shifter(helper, SPOT);
        helper.startSequence()
                .thenIdle(2)
                .thenExecute(() -> {
                    player.openMenu(chest);
                    helper.assertTrue(player.containerMenu != player.inventoryMenu && player.containerMenu.stillValid(player), "the chest did not open");
                })
                .thenExecute(() -> redstone(helper, SPOT.above(), true))
                .thenWaitUntil(() -> helper.assertBlockPresent(Blocks.AIR, SPOT))
                .thenExecute(() -> helper.assertTrue(player.containerMenu == player.inventoryMenu || !player.containerMenu.stillValid(player),
                        "the chest is held by the shifter and still open to the player"))
                .thenExecute(() -> redstone(helper, SPOT.above(), false))
                .thenWaitUntil(() -> helper.assertBlockPresent(Blocks.CHEST, SPOT))
                .thenExecute(() -> {
                    helper.assertTrue(player.containerMenu == player.inventoryMenu || !player.containerMenu.stillValid(player),
                            "the old chest is open to the player again");
                    helper.assertValueEqual(all(helper, Items.DIAMOND), 5, "diamonds in all");
                    TestPlayers.leave(helper, player);
                })
                .thenSucceed();
    }

    // ---- Phased hoppers and droppers, phase pistons ----

    /**
     * A phased hopper empties a visible chest while a shifter keeps taking the chest away and putting it back:
     * what is in the chest, the hopper and the hidden chest behind it always adds up.
     */
    @GameTest(maxTicks = 200)
    public void aHopperUnderAChestThatComesAndGoesLosesNothing(GameTestHelper helper) {
        BlockPos box = new BlockPos(3, 3, 3);
        chest(helper, box, Items.COBBLESTONE, 20);
        shifter(helper, box);
        setHidden(helper, box.below(2), PhaseCraftBlocks.PHASE_BLOCK.defaultBlockState());
        setHidden(helper, box.below().east(), Blocks.CHEST.defaultBlockState());
        setHidden(helper, box.below(), Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, Direction.EAST));
        var sequence = helper.startSequence().thenIdle(3);
        for (int i = 0; i < 12; i++) {
            boolean on = i % 2 == 0;
            sequence = sequence.thenExecute(() -> redstone(helper, box.above(), on)).thenIdle(on ? 3 : 7);
        }
        sequence.thenWaitUntil(() -> helper.assertBlockPresent(Blocks.CHEST, box))
                .thenExecute(() -> {
                    helper.assertValueEqual(all(helper, Items.COBBLESTONE), 20, "cobblestone in all");
                    helper.assertTrue(stored(helper, hidden(helper).level(), Items.COBBLESTONE) > 0, "the hopper pulled nothing");
                    helper.assertValueEqual(lying(helper, helper.getLevel(), Items.COBBLESTONE) + lying(helper, hidden(helper).level(), Items.COBBLESTONE),
                            0, "cobblestone lying about");
                })
                .thenSucceed();
    }

    /**
     * A phased dropper fills a visible chest; a shifter takes the chest away and the dropper drops into the world
     * instead; the chest comes back: every item is in the dropper, the chest or on the ground, once.
     */
    @GameTest(maxTicks = 200)
    public void aDropperIntoAChestThatIsTakenAwayLosesNothing(GameTestHelper helper) {
        floor(helper);
        BlockPos dropper = SPOT;
        BlockPos box = dropper.north();
        helper.setBlock(box, Blocks.CHEST);
        shifter(helper, box);
        setHidden(helper, dropper.below(), PhaseCraftBlocks.PHASE_BLOCK.defaultBlockState());
        setHidden(helper, dropper, Blocks.DROPPER.defaultBlockState().setValue(DispenserBlock.FACING, Direction.NORTH));
        ((Container) hidden(helper).level().getBlockEntity(helper.absolutePos(dropper))).setItem(0, new ItemStack(Items.DIAMOND, 6));
        helper.startSequence()
                .thenIdle(2)
                .thenExecute(() -> redstone(helper, dropper.east(), true))
                .thenWaitUntil(() -> helper.assertValueEqual(stored(helper, helper.getLevel(), Items.DIAMOND), 1, "diamonds in the visible chest"))
                .thenExecute(() -> redstone(helper, dropper.east(), false))
                .thenIdle(2)
                .thenExecute(() -> redstone(helper, dropper.east(), true))
                .thenWaitUntil(() -> helper.assertValueEqual(stored(helper, helper.getLevel(), Items.DIAMOND), 2, "diamonds in the visible chest"))
                .thenExecute(() -> redstone(helper, dropper.east(), false))
                .thenExecute(() -> redstone(helper, box.north(), true))
                .thenWaitUntil(() -> helper.assertBlockPresent(Blocks.AIR, box))
                .thenExecute(() -> redstone(helper, dropper.east(), true))
                .thenWaitUntil(() -> helper.assertValueEqual(lying(helper, helper.getLevel(), Items.DIAMOND), 1, "diamonds dropped in the visible world"))
                .thenExecute(() -> redstone(helper, dropper.east(), false))
                .thenIdle(2)
                .thenExecute(() -> redstone(helper, dropper.east(), true))
                .thenWaitUntil(() -> helper.assertValueEqual(lying(helper, helper.getLevel(), Items.DIAMOND), 2, "diamonds dropped in the visible world"))
                .thenExecute(() -> redstone(helper, box.north(), false))
                .thenWaitUntil(() -> helper.assertBlockPresent(Blocks.CHEST, box))
                .thenExecute(() -> {
                    helper.assertValueEqual(stored(helper, helper.getLevel(), Items.DIAMOND), 2, "diamonds in the chest that came back");
                    helper.assertValueEqual(stored(helper, hidden(helper).level(), Items.DIAMOND), 2, "diamonds left in the dropper");
                    helper.assertValueEqual(all(helper, Items.DIAMOND), 6, "diamonds in all");
                })
                .thenSucceed();
    }

    /** A phase piston does not move a chest, as a piston does not: it stays, with its contents. */
    @GameTest(maxTicks = 60)
    public void aPhasePistonLeavesAChestAlone(GameTestHelper helper) {
        floor(helper);
        BlockPos piston = SPOT.west();
        chest(helper, SPOT, Items.DIAMOND, 5);
        setHidden(helper, piston, PhaseCraftBlocks.STICKY_PHASE_PISTON.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.EAST));
        helper.startSequence()
                .thenIdle(2)
                .thenExecute(() -> redstone(helper, piston.west(), true))
                .thenIdle(10)
                .thenExecute(() -> redstone(helper, piston.west(), false))
                .thenIdle(10)
                .thenExecute(() -> {
                    helper.assertBlockPresent(Blocks.CHEST, SPOT);
                    helper.assertBlockPresent(Blocks.AIR, SPOT.east());
                    helper.assertValueEqual(stored(helper, helper.getLevel(), Items.DIAMOND), 5, "diamonds in the chest");
                    helper.assertValueEqual(all(helper, Items.DIAMOND), 5, "diamonds in all");
                })
                .thenSucceed();
    }
}
