package dev.phasecraft.test;

import dev.phasecraft.PhaseCraftBlocks;
import dev.phasecraft.compat.ProtectionAdapters;
import dev.phasecraft.edit.HiddenEditing;
import dev.phasecraft.edit.MachineOwners;
import dev.phasecraft.layer.HiddenLayer;
import dev.phasecraft.shifter.LineShifterBlock;
import dev.phasecraft.shifter.LineShifterBlockEntity;
import eu.pb4.common.protection.api.CommonProtection;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Open Parties and Claims' claims hold for the hidden layer, with the real mod: run by
 * {@code ./gradlew runGameTestOpac} only (part of build), which loads OPAC; the class is not named
 * *GameTests so that the ordinary runs, without OPAC, leave it out. Each test makes a real OPAC claim of
 * one chunk for a player ({@link OpacClaims}), and compares a spot in it with a spot in the unclaimed chunk
 * east of it. OPAC's settings are its defaults: a claim keeps everyone out but its owner and the owner's
 * party.
 *
 * <p>Claims cover whole chunks, which tests standing side by side share, so each test has a test
 * environment of its own: they run one after the other.</p>
 */
public class OpacClaimChecks {
    private static final BlockPos SPOT = new BlockPos(3, 2, 3);

    /** The claimed spot, and one at the same height two blocks into the chunk east of it. */
    private record Spots(BlockPos claimed, BlockPos free) {
        static Spots of(GameTestHelper helper) {
            BlockPos claimed = helper.absolutePos(SPOT);
            int east = SectionPos.sectionToBlockCoord(SectionPos.blockToSectionCoord(claimed.getX()) + 1);
            return new Spots(claimed, new BlockPos(east + 2, claimed.getY(), claimed.getZ()));
        }
    }

    private static void needsOpac(GameTestHelper helper) {
        helper.assertTrue(FabricLoader.getInstance().isModLoaded(ProtectionAdapters.OPAC),
                "this test needs Open Parties and Claims: run it with ./gradlew runGameTestOpac");
    }

    /** Two blocks south of the spot, looking at it, within reach. */
    private static void standAt(GameTestHelper helper, ServerPlayer player, BlockPos spot) {
        Vec3 feet = Vec3.atBottomCenterOf(spot.south(2));
        player.teleport(new TeleportTransition(helper.getLevel(), feet, Vec3.ZERO, 180, 0, TeleportTransition.DO_NOTHING));
        player.setOnGround(true);
    }

    private static BlockHitResult hit(BlockPos pos, Direction face) {
        return new BlockHitResult(Vec3.atCenterOf(pos).relative(face, 0.5), face, pos, false);
    }

    private static void finish(GameTestHelper helper, Spots spots, ServerPlayer... players) {
        ServerLevel level = helper.getLevel();
        HiddenLayer hidden = HiddenLayer.of(level);
        OpacClaims.unclaim(level, spots.claimed());
        for (BlockPos spot : new BlockPos[] {spots.claimed(), spots.free()}) {
            hidden.removeBlock(spot);
            hidden.removeBlock(spot.below());
            level.setBlockAndUpdate(spot, Blocks.AIR.defaultBlockState());
        }
        for (ServerPlayer player : players) {
            TestPlayers.leave(helper, player);
        }
        helper.succeed();
    }

    /**
     * A Survival player cannot hide a phase shifter in a wall in someone else's OPAC claim (powered, it
     * would take the wall): the item stays in hand, the wall stands. In the unclaimed chunk beside it the
     * same click places. The claim's owner places in the claim.
     */
    @GameTest(environment = "phasecraft-test:opac_placing", maxTicks = 20)
    public void opacClaimRefusesHiddenPlacing(GameTestHelper helper) {
        needsOpac(helper);
        ServerLevel level = helper.getLevel();
        HiddenLayer hidden = HiddenLayer.of(level);
        Spots spots = Spots.of(helper);
        ServerPlayer owner = TestPlayers.join(helper, GameType.SURVIVAL);
        ServerPlayer stranger = TestPlayers.join(helper, GameType.SURVIVAL);
        level.setBlockAndUpdate(spots.claimed(), Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(spots.free(), Blocks.STONE.defaultBlockState());
        OpacClaims.claim(level, spots.claimed(), owner.getUUID());

        stranger.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(PhaseCraftBlocks.PHASE_SHIFTER_ITEM, 3));
        standAt(helper, stranger, spots.claimed());
        HiddenEditing.use(stranger, InteractionHand.MAIN_HAND, hit(spots.claimed(), Direction.SOUTH));
        helper.assertValueEqual(hidden.getBlock(spots.claimed()).getBlock(), Blocks.AIR, "hidden block in the claim");
        helper.assertValueEqual(stranger.getMainHandItem().getCount(), 3, "shifters in hand after the refused click");
        helper.assertValueEqual(level.getBlockState(spots.claimed()).getBlock(), Blocks.STONE, "the claimed visible block");

        standAt(helper, stranger, spots.free());
        HiddenEditing.use(stranger, InteractionHand.MAIN_HAND, hit(spots.free(), Direction.SOUTH));
        helper.assertValueEqual(hidden.getBlock(spots.free()).getBlock(), PhaseCraftBlocks.PHASE_SHIFTER, "hidden block outside the claim");
        helper.assertValueEqual(stranger.getMainHandItem().getCount(), 2, "shifters in hand after placing outside");

        owner.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(PhaseCraftBlocks.PHASE_SHIFTER_ITEM, 3));
        standAt(helper, owner, spots.claimed());
        HiddenEditing.use(owner, InteractionHand.MAIN_HAND, hit(spots.claimed(), Direction.SOUTH));
        helper.assertValueEqual(hidden.getBlock(spots.claimed()).getBlock(), PhaseCraftBlocks.PHASE_SHIFTER, "the owner's hidden block in the claim");

        // The question itself, as any mod asks it.
        helper.assertFalse(CommonProtection.canPlaceBlock(level, spots.claimed(), stranger.nameAndId(), stranger),
                "OPAC lets a stranger place a block in the claim");
        helper.assertTrue(CommonProtection.canPlaceBlock(level, spots.free(), stranger.nameAndId(), stranger),
                "OPAC refuses a stranger placing a block outside the claim");
        helper.assertTrue(CommonProtection.canPlaceBlock(level, spots.claimed(), owner.nameAndId(), owner),
                "OPAC refuses the owner placing a block in the claim");
        finish(helper, spots, owner, stranger);
    }

    /**
     * Nor break a hidden block in the claim, nor use one (a hidden lever there is not flipped); outside
     * the claim both work, and in it for the owner.
     */
    @GameTest(environment = "phasecraft-test:opac_breaking_and_using", maxTicks = 20)
    public void opacClaimRefusesBreakingAndUsingHiddenBlocks(GameTestHelper helper) {
        needsOpac(helper);
        ServerLevel level = helper.getLevel();
        HiddenLayer hidden = HiddenLayer.of(level);
        Spots spots = Spots.of(helper);
        ServerPlayer owner = TestPlayers.join(helper, GameType.CREATIVE);
        ServerPlayer stranger = TestPlayers.join(helper, GameType.CREATIVE);
        BlockState lever = Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, AttachFace.FLOOR)
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH);
        for (BlockPos spot : new BlockPos[] {spots.claimed(), spots.free()}) {
            hidden.setBlock(spot.below(), Blocks.STONE.defaultBlockState());
            hidden.setBlock(spot, lever);
        }
        OpacClaims.claim(level, spots.claimed(), owner.getUUID());

        standAt(helper, stranger, spots.claimed());
        HiddenEditing.use(stranger, InteractionHand.MAIN_HAND, hit(spots.claimed(), Direction.UP));
        helper.assertFalse(hidden.getBlock(spots.claimed()).getValue(LeverBlock.POWERED), "a stranger flipped the hidden lever in the claim");
        helper.assertFalse(HiddenEditing.breakBlock(stranger, spots.claimed()), "a stranger broke the hidden lever in the claim");
        helper.assertValueEqual(hidden.getBlock(spots.claimed()).getBlock(), Blocks.LEVER, "hidden block in the claim");

        standAt(helper, stranger, spots.free());
        HiddenEditing.use(stranger, InteractionHand.MAIN_HAND, hit(spots.free(), Direction.UP));
        helper.assertTrue(hidden.getBlock(spots.free()).getValue(LeverBlock.POWERED), "a stranger could not flip the hidden lever outside the claim");
        helper.assertTrue(HiddenEditing.breakBlock(stranger, spots.free()), "a stranger could not break the hidden lever outside the claim");

        standAt(helper, owner, spots.claimed());
        HiddenEditing.use(owner, InteractionHand.MAIN_HAND, hit(spots.claimed(), Direction.UP));
        helper.assertTrue(hidden.getBlock(spots.claimed()).getValue(LeverBlock.POWERED), "the owner could not flip the hidden lever in the claim");
        helper.assertTrue(HiddenEditing.breakBlock(owner, spots.claimed()), "the owner could not break the hidden lever in the claim");

        // The questions themselves, as any mod asks them.
        for (boolean breaking : new boolean[] {false, true}) {
            String what = breaking ? "breaking" : "using";
            helper.assertFalse(ask(level, spots.claimed(), stranger, breaking), "OPAC lets a stranger in the claim: " + what);
            helper.assertTrue(ask(level, spots.free(), stranger, breaking), "OPAC refuses a stranger outside the claim: " + what);
            helper.assertTrue(ask(level, spots.claimed(), owner, breaking), "OPAC refuses the owner in the claim: " + what);
        }
        finish(helper, spots, owner, stranger);
    }

    private static boolean ask(ServerLevel level, BlockPos pos, ServerPlayer player, boolean breaking) {
        return breaking ? CommonProtection.canBreakBlock(level, pos, player.nameAndId(), player)
                : CommonProtection.canInteractBlock(level, pos, player.nameAndId(), player);
    }

    /**
     * Asked for a profile with no player (a hidden machine acting for whoever built it, who may be
     * offline, as these three are), OPAC's claims answer by standing: in the claim the owner and a member
     * of the owner's party may place, break and use, a stranger may not; outside it all may. The claim
     * counts as protected, alone and as part of an area; the chunk beside it does not.
     */
    @GameTest(environment = "phasecraft-test:opac_profile", maxTicks = 20)
    public void opacAnswersForProfilesWithNoPlayer(GameTestHelper helper) {
        needsOpac(helper);
        ServerLevel level = helper.getLevel();
        Spots spots = Spots.of(helper);
        NameAndId owner = new NameAndId(UUID.randomUUID(), "phasecraft-owner");
        NameAndId member = new NameAndId(UUID.randomUUID(), "phasecraft-member");
        NameAndId stranger = new NameAndId(UUID.randomUUID(), "phasecraft-stranger");
        OpacClaims.claim(level, spots.claimed(), owner.id());
        OpacClaims.party(level, owner, member);

        for (NameAndId profile : new NameAndId[] {owner, member, stranger}) {
            boolean trusted = profile != stranger;
            String who = profile.name();
            helper.assertValueEqual(CommonProtection.canPlaceBlock(level, spots.claimed(), profile, null), trusted, "placing in the claim, " + who);
            helper.assertValueEqual(CommonProtection.canBreakBlock(level, spots.claimed(), profile, null), trusted, "breaking in the claim, " + who);
            helper.assertValueEqual(CommonProtection.canInteractBlock(level, spots.claimed(), profile, null), trusted, "using in the claim, " + who);
            helper.assertTrue(CommonProtection.canPlaceBlock(level, spots.free(), profile, null), "placing outside the claim refused, " + who);
            helper.assertTrue(CommonProtection.canBreakBlock(level, spots.free(), profile, null), "breaking outside the claim refused, " + who);
            helper.assertTrue(CommonProtection.canInteractBlock(level, spots.free(), profile, null), "using outside the claim refused, " + who);
        }
        helper.assertTrue(CommonProtection.isProtected(level, spots.claimed()), "the claim is not protected");
        helper.assertFalse(CommonProtection.isProtected(level, spots.free()), "the chunk beside the claim is protected");
        helper.assertTrue(CommonProtection.isAreaProtected(level, AABB.encapsulatingFullBlocks(spots.claimed(), spots.free())),
                "an area reaching into the claim is not protected");
        helper.assertFalse(CommonProtection.isAreaProtected(level, new AABB(spots.free())), "an area beside the claim is protected");

        // Out of the party again, the member is a stranger.
        OpacClaims.disband(level, owner.id());
        helper.assertFalse(CommonProtection.canBreakBlock(level, spots.claimed(), member, null), "breaking in the claim, after the party was disbanded");
        finish(helper, spots);
    }

    /**
     * A hidden machine with the real mod (see ClaimMachineGameTests for every kind of machine): a line shifter
     * in the unclaimed chunk faces west across the border into two blocks of the claim. A stranger's takes
     * nothing; once it is the claim owner's, who is not online, it takes both.
     */
    @GameTest(environment = "phasecraft-test:opac_machine", maxTicks = 200)
    public void opacClaimStopsAStrangersLineShifterAtItsBorder(GameTestHelper helper) {
        needsOpac(helper);
        ServerLevel level = helper.getLevel();
        HiddenLayer hidden = HiddenLayer.of(level);
        Spots spots = Spots.of(helper);
        NameAndId owner = new NameAndId(UUID.randomUUID(), "phasecraft-owner");
        NameAndId stranger = new NameAndId(UUID.randomUUID(), "phasecraft-stranger");
        // The first block of the unclaimed chunk; west of it the claim.
        BlockPos shifter = spots.free().west(2);
        BlockPos[] line = {shifter.west(), shifter.west(2)};
        for (BlockPos at : line) {
            level.setBlockAndUpdate(at, Blocks.STONE.defaultBlockState());
        }
        OpacClaims.claim(level, spots.claimed(), owner.id());
        helper.assertTrue(CommonProtection.isProtected(level, line[0]) && !CommonProtection.isProtected(level, shifter),
                "the line is not in the claim, or the shifter is");
        hidden.setBlock(shifter, PhaseCraftBlocks.LINE_SHIFTER.defaultBlockState().setValue(LineShifterBlock.FACING, Direction.WEST));
        MachineOwners.setOwner(hidden.level(), shifter, stranger);
        helper.startSequence()
                .thenIdle(20) // the hidden chunk east of the test's own starts ticking
                .thenExecute(() -> hidden.setBlock(shifter.east(), Blocks.REDSTONE_BLOCK.defaultBlockState()))
                .thenIdle(20)
                .thenExecute(() -> {
                    helper.assertTrue(hidden.getBlock(shifter).getValue(LineShifterBlock.POWERED), "the line shifter is not powered");
                    for (BlockPos at : line) {
                        helper.assertValueEqual(level.getBlockState(at).getBlock(), Blocks.STONE, "the claimed block at " + at.toShortString());
                    }
                    hidden.removeBlock(shifter.east());
                    MachineOwners.setOwner(hidden.level(), shifter, owner);
                })
                .thenIdle(4)
                .thenExecute(() -> hidden.setBlock(shifter.east(), Blocks.REDSTONE_BLOCK.defaultBlockState()))
                .thenWaitUntil(() -> helper.assertTrue(hidden.level().getBlockEntity(shifter) instanceof LineShifterBlockEntity entity
                        && entity.holding() == 2, "the owner's line shifter did not take the line"))
                .thenExecute(() -> {
                    hidden.removeBlock(shifter.east());
                    hidden.removeBlock(shifter); // puts the line back
                    for (BlockPos at : line) {
                        level.setBlockAndUpdate(at, Blocks.AIR.defaultBlockState());
                    }
                    finish(helper, spots);
                });
    }
}
