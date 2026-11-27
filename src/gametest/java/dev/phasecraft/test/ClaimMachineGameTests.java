package dev.phasecraft.test;

import dev.phasecraft.PhaseCraftBlocks;
import dev.phasecraft.edit.HiddenEditing;
import dev.phasecraft.edit.MachineOwners;
import dev.phasecraft.layer.HiddenLayer;
import dev.phasecraft.piston.PhasePistonBlock;
import dev.phasecraft.shifter.AreaShifterBlock;
import dev.phasecraft.shifter.AreaShifterBlockEntity;
import dev.phasecraft.shifter.LineShifterBlock;
import dev.phasecraft.shifter.LineShifterBlockEntity;
import dev.phasecraft.shifter.PhaseShifterBlock;
import dev.phasecraft.shifter.PhaseShifterBlockEntity;
import dev.phasecraft.test.TestClaims.Action;
import eu.pb4.common.protection.api.CommonProtection;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

/**
 * Claims hold against hidden machines (the person, 2026-11-27: "Let's close that gap"): a shifter, a Shifter
 * Piston, an area shifter, a phase piston, or a phased hopper, dropper or dispenser changes the visible world with
 * no player acting, so it acts with the rights of whoever placed it. Each test builds the same machine four times
 * in rows (see {@link #ROWS}): placed by a stranger, it cannot reach into the claim; placed by the claim's owner,
 * it can; with no known owner (placed by a command), it cannot from outside, and can if its own spot is protected
 * too. A refused spot is an immovable block to the machine. {@link TestClaims} is the claim mod; each claim refuses
 * one action only, so every test shows its own question is the one asked.
 */
public class ClaimMachineGameTests {
    private static final NameAndId OWNER = new NameAndId(UUID.randomUUID(), "claim-owner");
    private static final NameAndId STRANGER = new NameAndId(UUID.randomUUID(), "stranger");
    /** The four rows, by z: a stranger's machine, the owner's, nobody's outside the claim, nobody's inside it. */
    private static final int[] ROWS = {1, 3, 5, 7};
    private static final int STRANGERS = 1;
    private static final int OWNERS = 3;
    private static final int NOBODYS_OUTSIDE = 5;
    private static final int NOBODYS_INSIDE = 7;

    private static HiddenLayer hidden(GameTestHelper helper) {
        return HiddenLayer.of(helper.getLevel());
    }

    private static @Nullable NameAndId ownerOfRow(int z) {
        return z == STRANGERS ? STRANGER : z == OWNERS ? OWNER : null;
    }

    /** A hidden machine with its owner (null: nobody, as placed by a command). */
    private static void machine(GameTestHelper helper, BlockPos rel, BlockState state, @Nullable NameAndId owner) {
        hidden(helper).setBlock(helper.absolutePos(rel), state);
        MachineOwners.setOwner(hidden(helper).level(), helper.absolutePos(rel), owner);
    }

    /** The owner kept for an absolute spot of the hidden layer (null: nobody). */
    static void assertOwner(GameTestHelper helper, BlockPos abs, @Nullable NameAndId expected, String what) {
        NameAndId owner = MachineOwners.owner(HiddenLayer.of(helper.getLevel()).level(), abs);
        helper.assertTrue(java.util.Objects.equals(owner, expected), "Expected " + what + " to be " + expected + ": was " + owner);
    }

    private static void setHidden(GameTestHelper helper, BlockPos rel, Block block) {
        hidden(helper).setBlock(helper.absolutePos(rel), block.defaultBlockState());
    }

    /** The owner's claim on these spots: everyone else is refused the action there. */
    private static void claim(GameTestHelper helper, List<BlockPos> claimed, Action action, BlockPos... rel) {
        for (BlockPos spot : rel) {
            BlockPos abs = helper.absolutePos(spot);
            TestClaims.claim(OWNER.id(), Set.of(action), abs);
            claimed.add(abs);
        }
    }

    private static void unclaim(List<BlockPos> claimed) {
        TestClaims.unclaim(claimed.toArray(BlockPos[]::new));
        claimed.clear();
    }

    private static void finish(GameTestHelper helper, List<BlockPos> claimed) {
        unclaim(claimed);
        AABB area = new AABB(helper.absolutePos(BlockPos.ZERO)).expandTowards(8, 8, 8).inflate(2);
        helper.getLevel().getEntitiesOfClass(ItemEntity.class, area).forEach(ItemEntity::discard);
        helper.succeed();
    }

    private static Container hiddenContainer(GameTestHelper helper, BlockPos rel) {
        return (Container) hidden(helper).level().getBlockEntity(helper.absolutePos(rel));
    }

    private static Container visibleContainer(GameTestHelper helper, BlockPos rel) {
        return (Container) helper.getLevel().getBlockEntity(helper.absolutePos(rel));
    }

    // ---- owners ----

    /**
     * A machine belongs to whoever places it in editing mode; other blocks get no owner. The owner stays while the
     * block does (powered or not) and is forgotten when the block goes.
     */
    @GameTest(maxTicks = 20)
    public void aMachineBelongsToWhoPlacedIt(GameTestHelper helper) {
        ServerPlayer player = ClaimProtectionGameTests.player(helper, GameType.SURVIVAL);
        HiddenLayer hidden = hidden(helper);
        BlockPos shifter = helper.absolutePos(new BlockPos(3, 2, 3));
        BlockPos stone = helper.absolutePos(new BlockPos(5, 2, 3));

        ClaimProtectionGameTests.hold(player, new ItemStack(PhaseCraftBlocks.PHASE_SHIFTER_ITEM));
        HiddenEditing.use(player, InteractionHand.MAIN_HAND, ClaimProtectionGameTests.hit(shifter, Direction.SOUTH));
        helper.assertValueEqual(hidden.getBlock(shifter).getBlock(), PhaseCraftBlocks.PHASE_SHIFTER, "hidden block placed");
        assertOwner(helper, shifter, player.nameAndId(), "the shifter's owner");

        ClaimProtectionGameTests.hold(player, new ItemStack(Items.STONE));
        HiddenEditing.use(player, InteractionHand.MAIN_HAND, ClaimProtectionGameTests.hit(stone, Direction.SOUTH));
        helper.assertValueEqual(hidden.getBlock(stone).getBlock(), Blocks.STONE, "hidden block placed");
        assertOwner(helper, stone, null, "a hidden stone's owner");

        hidden.setBlock(shifter.above(), Blocks.REDSTONE_BLOCK.defaultBlockState());
        helper.assertTrue(hidden.getBlock(shifter).getValue(PhaseShifterBlock.POWERED), "the shifter is not powered");
        assertOwner(helper, shifter, player.nameAndId(), "the powered shifter's owner");

        hidden.removeBlock(shifter);
        assertOwner(helper, shifter, null, "a removed shifter's owner");
        hidden.removeBlock(shifter.above());
        hidden.removeBlock(stone);
        TestPlayers.leave(helper, player);
        helper.succeed();
    }

    /**
     * From a player's own hand to the claim: a stranger hides a shifter in a wall whose claim lets anyone place but
     * only its owner break. Powered, it does not take the wall. The same shifter, once the claim is the player's,
     * does.
     */
    @GameTest(maxTicks = 40)
    public void aStrangersPlacedShifterDoesNotTakeAClaimedBlock(GameTestHelper helper) {
        ServerPlayer player = ClaimProtectionGameTests.player(helper, GameType.SURVIVAL);
        HiddenLayer hidden = hidden(helper);
        BlockPos rel = new BlockPos(3, 2, 3);
        BlockPos spot = helper.absolutePos(rel);
        helper.setBlock(rel, Blocks.STONE);
        TestClaims.claim(OWNER.id(), Set.of(Action.BREAK), spot);

        ClaimProtectionGameTests.hold(player, new ItemStack(PhaseCraftBlocks.PHASE_SHIFTER_ITEM));
        HiddenEditing.use(player, InteractionHand.MAIN_HAND, ClaimProtectionGameTests.hit(spot, Direction.SOUTH));
        helper.assertValueEqual(hidden.getBlock(spot).getBlock(), PhaseCraftBlocks.PHASE_SHIFTER, "hidden block placed");
        hidden.setBlock(spot.above(), Blocks.REDSTONE_BLOCK.defaultBlockState());
        helper.startSequence()
                .thenIdle(5)
                .thenExecute(() -> {
                    helper.assertBlockPresent(Blocks.STONE, rel);
                    hidden.removeBlock(spot.above());
                    TestClaims.claim(player.getUUID(), Set.of(Action.BREAK), spot);
                    hidden.setBlock(spot.above(), Blocks.REDSTONE_BLOCK.defaultBlockState());
                })
                .thenIdle(2)
                .thenExecute(() -> {
                    helper.assertBlockPresent(Blocks.AIR, rel);
                    TestClaims.unclaim(spot);
                    hidden.removeBlock(spot.above());
                    hidden.removeBlock(spot);
                    TestPlayers.leave(helper, player);
                    helper.succeed();
                });
    }

    // ---- the phase shifter ----

    /**
     * A phase shifter takes the visible block at its own spot only if the claim there lets its owner break it. One
     * with no owner in a claim was built inside it, and takes. (The fourth row is a stranger's with no claim.)
     */
    @GameTest(maxTicks = 40)
    public void phaseShifterTakesOnlyWhatItsOwnerMayBreak(GameTestHelper helper) {
        List<BlockPos> claimed = new ArrayList<>();
        for (int z : ROWS) {
            BlockPos spot = new BlockPos(2, 2, z);
            helper.setBlock(spot, Blocks.STONE);
            machine(helper, spot, PhaseCraftBlocks.PHASE_SHIFTER.defaultBlockState(),
                    z == NOBODYS_OUTSIDE ? null : z == NOBODYS_INSIDE ? STRANGER : ownerOfRow(z));
            if (z != NOBODYS_INSIDE) {
                claim(helper, claimed, Action.BREAK, spot);
            }
            setHidden(helper, spot.west(), Blocks.REDSTONE_BLOCK);
        }
        helper.startSequence()
                .thenIdle(5)
                .thenExecute(() -> {
                    helper.assertBlockPresent(Blocks.STONE, new BlockPos(2, 2, STRANGERS));
                    helper.assertBlockPresent(Blocks.AIR, new BlockPos(2, 2, OWNERS));
                    helper.assertBlockPresent(Blocks.AIR, new BlockPos(2, 2, NOBODYS_OUTSIDE)); // nobody's, in the claim
                    helper.assertBlockPresent(Blocks.AIR, new BlockPos(2, 2, NOBODYS_INSIDE));  // a stranger's, no claim
                    finish(helper, claimed);
                });
    }

    /**
     * A shifter holding a block waits to put it back while a claim (made since) refuses its owner placing there, as
     * it waits for an unbreakable block; and puts it back once it may.
     */
    @GameTest(maxTicks = 60)
    public void phaseShifterWaitsToPutBackWhereItsOwnerMayNotPlace(GameTestHelper helper) {
        List<BlockPos> claimed = new ArrayList<>();
        BlockPos spot = new BlockPos(2, 2, 2);
        helper.setBlock(spot, Blocks.GOLD_BLOCK);
        machine(helper, spot, PhaseCraftBlocks.PHASE_SHIFTER.defaultBlockState(), STRANGER);
        setHidden(helper, spot.west(), Blocks.REDSTONE_BLOCK);
        helper.startSequence()
                .thenIdle(2)
                .thenExecute(() -> {
                    helper.assertBlockPresent(Blocks.AIR, spot);
                    claim(helper, claimed, Action.PLACE, spot);
                    setHidden(helper, spot.west(), Blocks.AIR);
                })
                .thenIdle(10)
                .thenExecute(() -> {
                    helper.assertBlockPresent(Blocks.AIR, spot);
                    helper.assertTrue(hidden(helper).level().getBlockEntity(helper.absolutePos(spot)) instanceof PhaseShifterBlockEntity e
                            && e.heldCount() == 1, "the shifter no longer holds its block");
                    unclaim(claimed);
                })
                .thenWaitUntil(() -> helper.assertBlockPresent(Blocks.GOLD_BLOCK, spot))
                .thenExecute(() -> finish(helper, claimed));
    }

    /**
     * Nor does it break a block the claim's owner has since built in its spot: it waits. Removed while it may not
     * put its block back, it drops it as an item and leaves the owner's block alone.
     */
    @GameTest(maxTicks = 60)
    public void phaseShifterDoesNotBreakAClaimedBlockInItsSpot(GameTestHelper helper) {
        List<BlockPos> claimed = new ArrayList<>();
        BlockPos spot = new BlockPos(2, 2, 2);
        helper.setBlock(spot, Blocks.GOLD_BLOCK);
        machine(helper, spot, PhaseCraftBlocks.PHASE_SHIFTER.defaultBlockState(), STRANGER);
        setHidden(helper, spot.west(), Blocks.REDSTONE_BLOCK);
        helper.startSequence()
                .thenIdle(2)
                .thenExecute(() -> {
                    helper.assertBlockPresent(Blocks.AIR, spot);
                    helper.setBlock(spot, Blocks.BRICKS);
                    claim(helper, claimed, Action.BREAK, spot);
                    setHidden(helper, spot.west(), Blocks.AIR);
                })
                .thenIdle(10)
                .thenExecute(() -> {
                    helper.assertBlockPresent(Blocks.BRICKS, spot);
                    setHidden(helper, spot, Blocks.AIR);
                    helper.assertBlockPresent(Blocks.BRICKS, spot);
                    AABB near = new AABB(helper.absolutePos(spot)).inflate(2);
                    helper.assertTrue(helper.getLevel().getEntitiesOfClass(ItemEntity.class, near).stream()
                            .anyMatch(item -> item.getItem().is(Items.GOLD_BLOCK)), "the held gold block was not dropped");
                    finish(helper, claimed);
                });
    }

    // ---- the line shifter (Shifter Piston) ----

    private static BlockState lineShifter() {
        return PhaseCraftBlocks.LINE_SHIFTER.defaultBlockState().setValue(LineShifterBlock.FACING, Direction.EAST);
    }

    private static LineShifterBlockEntity lineEntity(GameTestHelper helper, BlockPos rel) {
        return (LineShifterBlockEntity) hidden(helper).level().getBlockEntity(helper.absolutePos(rel));
    }

    /**
     * A line shifter outside a claim faces a line of three blocks whose last is claimed. A block it may not take is
     * an immovable block to it: nothing moves. The claim owner's takes all three; nobody's does only if its own
     * spot is protected too.
     */
    @GameTest(maxTicks = 60)
    public void lineShifterStopsAtAClaimItsOwnerMayNotBreakIn(GameTestHelper helper) {
        List<BlockPos> claimed = new ArrayList<>();
        for (int z : ROWS) {
            for (int x = 2; x <= 4; x++) {
                helper.setBlock(new BlockPos(x, 2, z), Blocks.STONE);
            }
            claim(helper, claimed, Action.BREAK, new BlockPos(4, 2, z));
            if (z == NOBODYS_INSIDE) {
                claim(helper, claimed, Action.BREAK, new BlockPos(1, 2, z));
            }
            machine(helper, new BlockPos(1, 2, z), lineShifter(), ownerOfRow(z));
            setHidden(helper, new BlockPos(0, 2, z), Blocks.REDSTONE_BLOCK);
        }
        helper.startSequence()
                .thenIdle(30)
                .thenExecute(() -> {
                    for (int z : ROWS) {
                        boolean taken = z == OWNERS || z == NOBODYS_INSIDE;
                        for (int x = 2; x <= 4; x++) {
                            helper.assertBlockPresent(taken ? Blocks.AIR : Blocks.STONE, new BlockPos(x, 2, z));
                        }
                        helper.assertValueEqual(lineEntity(helper, new BlockPos(1, 2, z)).holding(), taken ? 3 : 0,
                                "blocks held in row " + z);
                    }
                    finish(helper, claimed);
                });
    }

    /**
     * Putting back, it waits at a spot a claim (made since) refuses its owner, and the blocks behind wait their
     * turn; all go back once it may.
     */
    @GameTest(maxTicks = 120)
    public void lineShifterWaitsToPutBackWhereItsOwnerMayNotPlace(GameTestHelper helper) {
        List<BlockPos> claimed = new ArrayList<>();
        int z = 3;
        for (int x = 2; x <= 4; x++) {
            helper.setBlock(new BlockPos(x, 2, z), Blocks.STONE);
        }
        machine(helper, new BlockPos(1, 2, z), lineShifter(), STRANGER);
        setHidden(helper, new BlockPos(0, 2, z), Blocks.REDSTONE_BLOCK);
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertValueEqual(lineEntity(helper, new BlockPos(1, 2, z)).holding(), 3, "blocks held"))
                .thenExecute(() -> {
                    claim(helper, claimed, Action.PLACE, new BlockPos(3, 2, z));
                    setHidden(helper, new BlockPos(0, 2, z), Blocks.AIR);
                })
                .thenIdle(30)
                .thenExecute(() -> {
                    helper.assertBlockPresent(Blocks.STONE, new BlockPos(2, 2, z));
                    helper.assertBlockPresent(Blocks.AIR, new BlockPos(3, 2, z));
                    helper.assertBlockPresent(Blocks.AIR, new BlockPos(4, 2, z));
                    helper.assertValueEqual(lineEntity(helper, new BlockPos(1, 2, z)).holding(), 2, "blocks still held");
                    unclaim(claimed);
                })
                .thenWaitUntil(() -> helper.assertBlockPresent(Blocks.STONE, new BlockPos(4, 2, z)))
                .thenExecute(() -> {
                    helper.assertBlockPresent(Blocks.STONE, new BlockPos(3, 2, z));
                    finish(helper, claimed);
                });
    }

    // ---- the area shifter ----

    private static final BlockPos LOW = new BlockPos(1, 1, 1);
    private static final BlockPos HIGH = new BlockPos(3, 2, 3);
    /** The one claimed block of the box. */
    private static final BlockPos KEPT = new BlockPos(2, 1, 2);

    /**
     * An area shifter pair around a 3 x 2 x 3 box of stone, one block of it claimed: whether that block goes too.
     * Every other block of the box goes either way.
     */
    private static void areaShifter(GameTestHelper helper, @Nullable NameAndId low, @Nullable NameAndId high, boolean cornersClaimed,
            boolean taken) {
        List<BlockPos> claimed = new ArrayList<>();
        for (BlockPos p : BlockPos.betweenClosed(LOW, HIGH)) {
            helper.setBlock(p.immutable(), Blocks.STONE);
        }
        claim(helper, claimed, Action.BREAK, KEPT);
        if (cornersClaimed) {
            claim(helper, claimed, Action.BREAK, LOW, HIGH);
        }
        BlockState corner = PhaseCraftBlocks.AREA_SHIFTER.defaultBlockState().setValue(AreaShifterBlock.DELAY, 1);
        machine(helper, LOW, corner, low);
        machine(helper, HIGH, corner, high);
        setHidden(helper, LOW.west(), Blocks.REDSTONE_BLOCK);
        setHidden(helper, HIGH.east(), Blocks.REDSTONE_BLOCK);
        helper.startSequence()
                .thenIdle(30)
                .thenExecute(() -> {
                    for (BlockPos p : BlockPos.betweenClosed(LOW, HIGH)) {
                        helper.assertBlockPresent(p.equals(KEPT) && !taken ? Blocks.STONE : Blocks.AIR, p.immutable());
                    }
                    AreaShifterBlockEntity leader = (AreaShifterBlockEntity) hidden(helper).level().getBlockEntity(helper.absolutePos(LOW));
                    helper.assertValueEqual(leader.holding(), taken ? 18 : 17, "blocks held");
                    setHidden(helper, LOW.west(), Blocks.AIR);
                })
                .thenWaitUntil(() -> {
                    for (BlockPos p : BlockPos.betweenClosed(LOW, HIGH)) {
                        helper.assertBlockPresent(Blocks.STONE, p.immutable());
                    }
                })
                .thenExecute(() -> finish(helper, claimed));
    }

    /** Strangers' corners leave the claimed block of their box where it is, as they leave an immovable one. */
    @GameTest(structure = AreaShifterGameTests.STRUCTURE, environment = AreaShifterGameTests.RULE_ON, maxTicks = 100)
    public void areaShifterLeavesWhatItsOwnersMayNotBreak(GameTestHelper helper) {
        areaShifter(helper, STRANGER, STRANGER, false, false);
    }

    /** The claim owner's corners take it. */
    @GameTest(structure = AreaShifterGameTests.STRUCTURE, environment = AreaShifterGameTests.RULE_ON, maxTicks = 100)
    public void areaShifterOfTheClaimsOwnerTakesIt(GameTestHelper helper) {
        areaShifter(helper, OWNER, OWNER, false, true);
    }

    /** Both corners' owners must be allowed: the owner's corner paired with a stranger's leaves it. */
    @GameTest(structure = AreaShifterGameTests.STRUCTURE, environment = AreaShifterGameTests.RULE_ON, maxTicks = 100)
    public void areaShifterNeedsBothCornersOwnersAllowed(GameTestHelper helper) {
        areaShifter(helper, OWNER, STRANGER, false, false);
    }

    /** Nobody's corners outside the claim leave it. */
    @GameTest(structure = AreaShifterGameTests.STRUCTURE, environment = AreaShifterGameTests.RULE_ON, maxTicks = 100)
    public void areaShifterOfNobodyOutsideLeavesIt(GameTestHelper helper) {
        areaShifter(helper, null, null, false, false);
    }

    /** Nobody's corners on protected spots were built inside: they take it. */
    @GameTest(structure = AreaShifterGameTests.STRUCTURE, environment = AreaShifterGameTests.RULE_ON, maxTicks = 100)
    public void areaShifterOfNobodyInsideTakesIt(GameTestHelper helper) {
        areaShifter(helper, null, null, true, true);
    }

    // ---- phase pistons ----

    private static BlockState phasePiston(boolean sticky, Direction facing) {
        return (sticky ? PhaseCraftBlocks.STICKY_PHASE_PISTON : PhaseCraftBlocks.PHASE_PISTON).defaultBlockState()
                .setValue(BlockStateProperties.FACING, facing);
    }

    /**
     * Four phase pistons facing east, a stone in front of each, and beyond it air or a block the push would break;
     * one spot of each row is claimed.
     */
    private static void phasePistons(GameTestHelper helper, Action action, int claimedX, Block beyond) {
        List<BlockPos> claimed = new ArrayList<>();
        for (int z : ROWS) {
            helper.setBlock(new BlockPos(2, 2, z), Blocks.STONE);
            helper.setBlock(new BlockPos(3, 1, z), Blocks.STONE);
            helper.setBlock(new BlockPos(3, 2, z), beyond);
            claim(helper, claimed, action, new BlockPos(claimedX, 2, z));
            if (z == NOBODYS_INSIDE) {
                claim(helper, claimed, action, new BlockPos(1, 2, z));
            }
            machine(helper, new BlockPos(1, 2, z), phasePiston(false, Direction.EAST), ownerOfRow(z));
            setHidden(helper, new BlockPos(0, 2, z), Blocks.REDSTONE_BLOCK);
        }
        helper.startSequence()
                .thenIdle(20)
                .thenExecute(() -> {
                    for (int z : ROWS) {
                        boolean moved = z == OWNERS || z == NOBODYS_INSIDE;
                        helper.assertBlockPresent(moved ? Blocks.AIR : Blocks.STONE, new BlockPos(2, 2, z));
                        helper.assertBlockPresent(moved ? Blocks.STONE : beyond, new BlockPos(3, 2, z));
                        helper.assertValueEqual(hidden(helper).getBlock(helper.absolutePos(new BlockPos(1, 2, z))).getValue(PhasePistonBlock.EXTENDED),
                                moved, "the phase piston is extended, row " + z);
                    }
                    finish(helper, claimed);
                });
    }

    /** A phase piston does not push a block into a spot where its owner may not place: it does not extend. */
    @GameTest(maxTicks = 40)
    public void phasePistonDoesNotPushIntoAClaim(GameTestHelper helper) {
        phasePistons(helper, Action.PLACE, 3, Blocks.AIR);
    }

    /** Nor push a block out of a spot where its owner may not break. */
    @GameTest(maxTicks = 40)
    public void phasePistonDoesNotPushAClaimedBlock(GameTestHelper helper) {
        phasePistons(helper, Action.BREAK, 2, Blocks.AIR);
    }

    /** Nor break a claimed torch by pushing a block into it. */
    @GameTest(maxTicks = 40)
    public void phasePistonDoesNotBreakAClaimedFragileBlock(GameTestHelper helper) {
        phasePistons(helper, Action.BREAK, 3, Blocks.TORCH);
    }

    /** A sticky phase piston does not pull a block its owner may not break (the claim made since it pushed it). */
    @GameTest(maxTicks = 60)
    public void stickyPhasePistonDoesNotPullAClaimedBlock(GameTestHelper helper) {
        List<BlockPos> claimed = new ArrayList<>();
        for (int z : new int[] {STRANGERS, OWNERS}) {
            helper.setBlock(new BlockPos(2, 2, z), Blocks.STONE);
            machine(helper, new BlockPos(1, 2, z), phasePiston(true, Direction.EAST), ownerOfRow(z));
            setHidden(helper, new BlockPos(0, 2, z), Blocks.REDSTONE_BLOCK);
        }
        helper.startSequence()
                .thenWaitUntil(() -> {
                    helper.assertBlockPresent(Blocks.STONE, new BlockPos(3, 2, STRANGERS));
                    helper.assertBlockPresent(Blocks.STONE, new BlockPos(3, 2, OWNERS));
                })
                .thenExecute(() -> {
                    for (int z : new int[] {STRANGERS, OWNERS}) {
                        claim(helper, claimed, Action.BREAK, new BlockPos(3, 2, z));
                        setHidden(helper, new BlockPos(0, 2, z), Blocks.AIR);
                    }
                })
                .thenIdle(20)
                .thenExecute(() -> {
                    helper.assertBlockPresent(Blocks.STONE, new BlockPos(3, 2, STRANGERS));
                    helper.assertBlockPresent(Blocks.AIR, new BlockPos(2, 2, STRANGERS));
                    helper.assertBlockPresent(Blocks.STONE, new BlockPos(2, 2, OWNERS));
                    for (int z : new int[] {STRANGERS, OWNERS}) {
                        helper.assertFalse(hidden(helper).getBlock(helper.absolutePos(new BlockPos(1, 2, z))).getValue(PhasePistonBlock.EXTENDED),
                                "the sticky phase piston is still extended, row " + z);
                    }
                    finish(helper, claimed);
                });
    }

    /**
     * A phase piston moved by a piston in the hidden layer (a flying machine's engine carries one) keeps its owner:
     * a stranger's, pushed to face a claimed block, does not push it.
     */
    @GameTest(maxTicks = 60)
    public void aMovedPhasePistonKeepsItsOwner(GameTestHelper helper) {
        movedPhasePiston(helper, STRANGER, STRANGER);
    }

    /**
     * And one with no owner, pushed from outside onto a protected spot, was not built inside the claim: it becomes
     * nobody's (the API's UNKNOWN), whom the claim refuses.
     */
    @GameTest(maxTicks = 60)
    public void aPhasePistonPushedIntoAClaimIsNotAnInsider(GameTestHelper helper) {
        movedPhasePiston(helper, null, CommonProtection.UNKNOWN);
    }

    private static void movedPhasePiston(GameTestHelper helper, @Nullable NameAndId owner, NameAndId landedOwner) {
        List<BlockPos> claimed = new ArrayList<>();
        HiddenLayer hidden = hidden(helper);
        BlockPos pusher = new BlockPos(1, 2, 3);
        BlockPos phase = pusher.east();
        BlockPos landed = phase.east();
        helper.setBlock(landed.north(), Blocks.STONE);
        // The claim: where the phase piston lands, and the block it faces there.
        claim(helper, claimed, Action.BREAK, landed, landed.north());
        machine(helper, phase, phasePiston(false, Direction.NORTH), owner);
        hidden.setBlock(helper.absolutePos(pusher), Blocks.PISTON.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.EAST));
        setHidden(helper, pusher.below(), Blocks.REDSTONE_BLOCK);
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertValueEqual(hidden.getBlock(helper.absolutePos(landed)).getBlock(),
                        PhaseCraftBlocks.PHASE_PISTON, "phase piston moved by the hidden piston"))
                .thenExecute(() -> {
                    assertOwner(helper, helper.absolutePos(landed), landedOwner, "the moved piston's owner");
                    assertOwner(helper, helper.absolutePos(phase), null, "the owner left where the piston was");
                    setHidden(helper, landed.below(), Blocks.REDSTONE_BLOCK);
                })
                .thenIdle(10)
                .thenExecute(() -> {
                    helper.assertBlockPresent(Blocks.STONE, landed.north());
                    finish(helper, claimed);
                });
    }

    // ---- phased hoppers, droppers and dispensers ----

    /**
     * A phased hopper under a claimed chest pulls from it only if its owner may use the chest; nobody's only if its
     * own spot is protected too.
     */
    @GameTest(maxTicks = 80)
    public void phasedHopperPullsOnlyWhereItsOwnerMayUse(GameTestHelper helper) {
        List<BlockPos> claimed = new ArrayList<>();
        for (int z : ROWS) {
            BlockPos hopper = new BlockPos(3, 1, z);
            helper.setBlock(hopper.above(), Blocks.CHEST);
            visibleContainer(helper, hopper.above()).setItem(0, new ItemStack(Items.COBBLESTONE, 5));
            claim(helper, claimed, Action.USE, hopper.above());
            if (z == NOBODYS_INSIDE) {
                claim(helper, claimed, Action.USE, hopper);
            }
            setHidden(helper, hopper.below(), PhaseCraftBlocks.PHASE_BLOCK);
            setHidden(helper, hopper.east(), Blocks.CHEST);
            machine(helper, hopper, Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, Direction.EAST), ownerOfRow(z));
        }
        helper.startSequence()
                .thenIdle(40)
                .thenExecute(() -> {
                    for (int z : ROWS) {
                        int left = visibleContainer(helper, new BlockPos(3, 2, z)).countItem(Items.COBBLESTONE);
                        if (z == OWNERS || z == NOBODYS_INSIDE) {
                            helper.assertTrue(left < 5, "nothing was pulled from the chest in row " + z);
                        } else {
                            helper.assertValueEqual(left, 5, "cobblestone left in the claimed chest, row " + z);
                        }
                    }
                    finish(helper, claimed);
                });
    }

    /**
     * Four phased droppers or dispensers facing east, three diamonds in each, fired once; the spot in front of each
     * is claimed. Those that may put out have two diamonds left.
     */
    private static void shooters(GameTestHelper helper, Block shooter, boolean intoChest, Action action) {
        List<BlockPos> claimed = new ArrayList<>();
        for (int z : ROWS) {
            for (int x = 3; x <= 7; x++) {
                helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE); // a floor for what is put out
            }
            BlockPos at = new BlockPos(2, 2, z);
            if (intoChest) {
                helper.setBlock(at.east(), Blocks.CHEST);
            }
            claim(helper, claimed, action, at.east());
            if (z == NOBODYS_INSIDE) {
                claim(helper, claimed, action, at);
            }
            setHidden(helper, at.below(), PhaseCraftBlocks.PHASE_BLOCK);
            machine(helper, at, shooter.defaultBlockState().setValue(DispenserBlock.FACING, Direction.EAST), ownerOfRow(z));
            hiddenContainer(helper, at).setItem(0, new ItemStack(Items.DIAMOND, 3));
        }
        helper.startSequence()
                .thenIdle(2)
                .thenExecute(() -> {
                    for (int z : ROWS) {
                        setHidden(helper, new BlockPos(1, 2, z), Blocks.REDSTONE_BLOCK);
                    }
                })
                .thenIdle(20)
                .thenExecute(() -> {
                    for (int z : ROWS) {
                        boolean out = z == OWNERS || z == NOBODYS_INSIDE;
                        helper.assertValueEqual(hiddenContainer(helper, new BlockPos(2, 2, z)).countItem(Items.DIAMOND), out ? 2 : 3,
                                "diamonds left in row " + z);
                        if (intoChest) {
                            helper.assertValueEqual(visibleContainer(helper, new BlockPos(3, 2, z)).countItem(Items.DIAMOND), out ? 1 : 0,
                                    "diamonds in the claimed chest, row " + z);
                        }
                    }
                    finish(helper, claimed);
                });
    }

    /** A phased dropper puts into a claimed chest only if its owner may use it. */
    @GameTest(maxTicks = 60)
    public void phasedDropperFillsOnlyChestsItsOwnerMayUse(GameTestHelper helper) {
        shooters(helper, Blocks.DROPPER, true, Action.USE);
    }

    /** With no container in front, it drops into the spot only if its owner may place there. */
    @GameTest(maxTicks = 60)
    public void phasedDropperDropsOnlyWhereItsOwnerMayPlace(GameTestHelper helper) {
        shooters(helper, Blocks.DROPPER, false, Action.PLACE);
    }

    /** A phased dispenser dispenses into the spot in front only if its owner may place there... */
    @GameTest(maxTicks = 60)
    public void phasedDispenserDispensesOnlyWhereItsOwnerMayPlace(GameTestHelper helper) {
        shooters(helper, Blocks.DISPENSER, false, Action.PLACE);
    }

    /** ...and break there: it can scoop up water or shear as well as put out. */
    @GameTest(maxTicks = 60)
    public void phasedDispenserDispensesOnlyWhereItsOwnerMayBreak(GameTestHelper helper) {
        shooters(helper, Blocks.DISPENSER, false, Action.BREAK);
    }
}
