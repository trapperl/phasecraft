package dev.phasecraft.test;

import dev.phasecraft.PhaseCraftBlocks;
import dev.phasecraft.layer.HiddenLayer;
import dev.phasecraft.shifter.AreaShifterBlock;
import dev.phasecraft.shifter.AreaShifterBlockEntity;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The area shifter (the person's idea, 2026-11-25: two corners make a box "and then you could turn the whole cube
 * off and on"; "We could keep it as an option"): two hidden area shifters at opposite corners take everything in
 * the box out of the visible world while both are powered, layer by layer from the top, and put it back from the
 * bottom. Only while the game rule phasecraft:area_shifters is on (off by default); these tests run where it is on,
 * but for {@link #offByDefault}.
 */
public class AreaShifterGameTests {
    /** Mostly empty around the box (only 8 x 17 x 8 is used): other tests sit more than 15 blocks away, out of
     *  reach of pairing. */
    static final String STRUCTURE = "phasecraft-test:empty_24x17x24";
    static final String RULE_ON = "phasecraft-test:area_shifters_on";
    /** The box: corners at LOW and HIGH (5 x 4 x 5), a small hut. */
    static final BlockPos LOW = new BlockPos(1, 1, 1);
    static final BlockPos HIGH = new BlockPos(5, 4, 5);

    private static HiddenLayer hidden(GameTestHelper helper) {
        return HiddenLayer.of(helper.getLevel());
    }

    private static void corner(GameTestHelper helper, BlockPos rel, int delay) {
        hidden(helper).setBlock(helper.absolutePos(rel), PhaseCraftBlocks.AREA_SHIFTER.defaultBlockState()
                .setValue(AreaShifterBlock.DELAY, delay));
    }

    /** Powers a corner, or stops, with a hidden redstone block beside it (outside the box, to the west or east). */
    private static void power(GameTestHelper helper, BlockPos rel, boolean on) {
        BlockPos beside = rel.getX() <= LOW.getX() ? rel.west() : rel.east();
        hidden(helper).setBlock(helper.absolutePos(beside),
                on ? Blocks.REDSTONE_BLOCK.defaultBlockState() : Blocks.AIR.defaultBlockState());
    }

    private static AreaShifterBlockEntity entity(GameTestHelper helper, BlockPos rel) {
        return (AreaShifterBlockEntity) hidden(helper).level().getBlockEntity(helper.absolutePos(rel));
    }

    /** A cobblestone floor, plank walls with a doorway, a chest with seven diamonds, stairs, and a slab roof. */
    private static Map<BlockPos, BlockState> hut(GameTestHelper helper) {
        Map<BlockPos, BlockState> built = new HashMap<>();
        for (BlockPos p : BlockPos.betweenClosed(LOW, HIGH)) {
            int x = p.getX();
            int y = p.getY();
            int z = p.getZ();
            boolean wall = x == LOW.getX() || x == HIGH.getX() || z == LOW.getZ() || z == HIGH.getZ();
            BlockState state = null;
            if (y == 1) {
                state = Blocks.COBBLESTONE.defaultBlockState();
            } else if (y == 4) {
                state = Blocks.OAK_SLAB.defaultBlockState();
            } else if (wall && !(x == 3 && z == LOW.getZ())) {
                state = y == 3 && x == 3 ? Blocks.GLASS.defaultBlockState() : Blocks.OAK_PLANKS.defaultBlockState();
            } else if (y == 2 && x == 3 && z == 3) {
                state = Blocks.CHEST.defaultBlockState();
            } else if (y == 2 && x == 2 && z == 4) {
                state = Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.EAST);
            }
            if (state != null) {
                helper.setBlock(p.immutable(), state);
            }
        }
        helper.getBlockEntity(new BlockPos(3, 2, 3), ChestBlockEntity.class).setItem(4, new ItemStack(Items.DIAMOND, 7));
        for (BlockPos p : BlockPos.betweenClosed(LOW, HIGH)) {
            BlockState state = helper.getBlockState(p);
            if (!state.isAir()) {
                built.put(p.immutable(), state);
            }
        }
        return built;
    }

    private static AABB area(GameTestHelper helper) {
        return new AABB(Vec3.atCenterOf(helper.absolutePos(BlockPos.ZERO)), Vec3.atCenterOf(helper.absolutePos(new BlockPos(7, 16, 7)))).inflate(1);
    }

    /** "y@tick" for each layer of the box when it was all gone (or all back), ticks from the first change. */
    private static final class Layers {
        final Map<BlockPos, BlockState> built;
        final Map<Integer, Long> at = new HashMap<>();
        long start;

        Layers(Map<BlockPos, BlockState> built) {
            this.built = built;
        }

        void look(GameTestHelper helper, boolean gone) {
            for (int y = LOW.getY(); y <= HIGH.getY(); y++) {
                int layer = y;
                boolean done = built.keySet().stream().filter(p -> p.getY() == layer)
                        .allMatch(p -> helper.getBlockState(p).isAir() == gone);
                if (done && !at.containsKey(y)) {
                    at.put(y, helper.getTick() - start);
                }
            }
        }

        List<String> moves() {
            List<String> out = new ArrayList<>();
            at.entrySet().stream().sorted(Map.Entry.comparingByValue()).forEach(e -> out.add(e.getKey() + "@" + e.getValue()));
            return out;
        }
    }

    private static GameTestSequence watch(GameTestHelper helper, GameTestSequence sequence, Layers layers, boolean gone, int ticks) {
        sequence = sequence.thenExecute(() -> {
            layers.start = helper.getTick();
            layers.look(helper, gone);
        });
        for (int i = 0; i < ticks; i++) {
            sequence = sequence.thenIdle(1).thenExecute(() -> layers.look(helper, gone));
        }
        return sequence;
    }

    private static void assertAll(GameTestHelper helper, Map<BlockPos, BlockState> built) {
        for (Map.Entry<BlockPos, BlockState> e : built.entrySet()) {
            helper.assertValueEqual(helper.getBlockState(e.getKey()), e.getValue(), "the hut at " + e.getKey().toShortString());
        }
    }

    /**
     * With both corners powered, the hut goes a layer every redstone tick from the roof down; when one corner loses
     * its power it comes back a layer every redstone tick from the floor up, exactly as it was: the chest with its
     * seven diamonds, the stairs facing east. Nothing spills.
     */
    @GameTest(structure = STRUCTURE, environment = RULE_ON, maxTicks = 200)
    public void bothCornersTakeTheBoxLayerByLayerAndPutItBack(GameTestHelper helper) {
        Map<BlockPos, BlockState> built = hut(helper);
        corner(helper, LOW, 1);
        corner(helper, HIGH, 1);
        Layers down = new Layers(built);
        Layers up = new Layers(built);
        GameTestSequence sequence = helper.startSequence().thenIdle(3)
                .thenExecute(() -> power(helper, LOW, true))
                .thenIdle(10)
                .thenExecute(() -> {
                    assertAll(helper, built);                       // one corner is not enough
                    power(helper, HIGH, true);
                });
        sequence = watch(helper, sequence, down, true, 12)
                .thenExecute(() -> {
                    helper.assertValueEqual(down.moves(), List.of("4@0", "3@2", "2@4", "1@6"), "layers gone (y@game tick)");
                    helper.assertValueEqual(entity(helper, LOW).holding(), built.size(), "blocks held by the leader corner");
                    helper.assertTrue(helper.getLevel().getEntitiesOfClass(ItemEntity.class, area(helper)).isEmpty(), "something spilled");
                    power(helper, HIGH, false);
                });
        watch(helper, sequence, up, false, 12)
                .thenExecute(() -> {
                    helper.assertValueEqual(up.moves(), List.of("1@0", "2@2", "3@4", "4@6"), "layers back (y@game tick)");
                    assertAll(helper, built);
                    ChestBlockEntity chest = helper.getBlockEntity(new BlockPos(3, 2, 3), ChestBlockEntity.class);
                    helper.assertTrue(chest.getItem(4).is(Items.DIAMOND) && chest.getItem(4).getCount() == 7, "the seven diamonds");
                    helper.assertTrue(helper.getLevel().getEntitiesOfClass(ItemEntity.class, area(helper)).isEmpty(), "something spilled");
                })
                .thenSucceed();
    }

    /**
     * The same as {@link PhaseLayerGameTests#hiddenChunksTickWhenATestStarts}, in this class's own batch and with
     * its structure, which lies over several chunks: the turned-around test here once saw every step after the first
     * a tick late, as its hidden chunk started ticking eight ticks into the test (issue #3).
     */
    @GameTest(structure = STRUCTURE, environment = RULE_ON)
    public void hiddenChunksTickWhenATestStarts(GameTestHelper helper) {
        PhaseLayerGameTests.assertHiddenChunksTick(helper);
        helper.succeed();
    }

    /** The game rule is off by default: both corners powered, nothing moves. */
    @GameTest(structure = STRUCTURE, maxTicks = 100)
    public void offByDefault(GameTestHelper helper) {
        helper.assertTrue(!helper.getLevel().getGameRules().get(AreaShifterBlock.RULE), "the rule is on by default");
        Map<BlockPos, BlockState> built = hut(helper);
        corner(helper, LOW, 1);
        corner(helper, HIGH, 1);
        helper.startSequence().thenIdle(3)
                .thenExecute(() -> {
                    power(helper, LOW, true);
                    power(helper, HIGH, true);
                })
                .thenIdle(20)
                .thenExecute(() -> assertAll(helper, built))
                .thenSucceed();
    }

    /** At delay 0 (set by clicking: 1, 2, 3, 4, 0) the whole box goes at once, and comes back at once. */
    @GameTest(structure = STRUCTURE, environment = RULE_ON, maxTicks = 100)
    public void delayZeroIsAllAtOnce(GameTestHelper helper) {
        Map<BlockPos, BlockState> built = hut(helper);
        corner(helper, LOW, 4);
        corner(helper, HIGH, 1);
        BlockPos leader = helper.absolutePos(LOW);
        BlockState state = hidden(helper).getBlock(leader);
        hidden(helper).setBlock(leader, state.cycle(AreaShifterBlock.DELAY));
        helper.assertValueEqual(hidden(helper).getBlock(leader).getValue(AreaShifterBlock.DELAY), 0, "delay after 4");
        helper.startSequence().thenIdle(3)
                .thenExecute(() -> {
                    power(helper, LOW, true);
                    power(helper, HIGH, true);
                    for (BlockPos p : built.keySet()) {
                        helper.assertBlockPresent(Blocks.AIR, p);
                    }
                    power(helper, LOW, false);
                    assertAll(helper, built);
                })
                .thenSucceed();
    }

    /** Piston rules for each block: obsidian and a cobweb in the box stay where they are; the rest goes. */
    @GameTest(structure = STRUCTURE, environment = RULE_ON, maxTicks = 100)
    public void whatAPistonCannotMoveStays(GameTestHelper helper) {
        Map<BlockPos, BlockState> built = hut(helper);
        BlockPos obsidian = new BlockPos(2, 2, 2);
        BlockPos cobweb = new BlockPos(4, 2, 2);
        helper.setBlock(obsidian, Blocks.OBSIDIAN);
        helper.setBlock(cobweb, Blocks.COBWEB);
        corner(helper, LOW, 0);
        corner(helper, HIGH, 0);
        helper.startSequence().thenIdle(3)
                .thenExecute(() -> {
                    power(helper, LOW, true);
                    power(helper, HIGH, true);
                    helper.assertBlockPresent(Blocks.OBSIDIAN, obsidian);
                    helper.assertBlockPresent(Blocks.COBWEB, cobweb);
                    for (BlockPos p : built.keySet()) {
                        helper.assertBlockPresent(Blocks.AIR, p);
                    }
                })
                .thenSucceed();
    }

    /**
     * Corners too far apart (a box of 17: more than 16) do not pair, and nothing moves; nor do three corners where
     * one has two nearest (a tie).
     */
    @GameTest(structure = STRUCTURE, environment = RULE_ON, maxTicks = 100)
    public void cornersPairOnlyNearAndUnambiguously(GameTestHelper helper) {
        Map<BlockPos, BlockState> built = hut(helper);
        // a tie: HIGH has two nearest corners, both 4 blocks away (and each of them has HIGH as its nearest)
        BlockPos twinA = new BlockPos(5, 4, 1);
        BlockPos twinB = new BlockPos(5, 0, 5);
        corner(helper, HIGH, 0);
        corner(helper, twinA, 0);
        corner(helper, twinB, 0);
        // too far: 16 apart on y (a box of 17); each is nearer the twins than to the other, but not their nearest
        BlockPos farLow = new BlockPos(7, 0, 0);
        BlockPos farHigh = new BlockPos(7, 16, 0);
        for (BlockPos p : BlockPos.betweenClosed(farLow, farHigh)) {
            helper.setBlock(p.immutable(), Blocks.STONE);
        }
        corner(helper, farLow, 0);
        corner(helper, farHigh, 0);
        helper.startSequence().thenIdle(3)
                .thenExecute(() -> {
                    for (BlockPos p : List.of(farLow, farHigh)) {
                        hidden(helper).setBlock(helper.absolutePos(p.west()), Blocks.REDSTONE_BLOCK.defaultBlockState());
                    }
                    for (BlockPos p : List.of(HIGH, twinA, twinB)) {
                        hidden(helper).setBlock(helper.absolutePos(p.east()), Blocks.REDSTONE_BLOCK.defaultBlockState());
                    }
                })
                .thenIdle(20)
                .thenExecute(() -> {
                    for (BlockPos p : BlockPos.betweenClosed(farLow, farHigh)) {
                        helper.assertBlockPresent(Blocks.STONE, p);
                    }
                    assertAll(helper, built);
                })
                .thenSucceed();
    }

    /**
     * The size cap: corners 16 blocks apart (a box 17 tall) do not pair, so the stone column between them stays;
     * moved one block closer (a box of 16), they pair and take it.
     */
    @GameTest(structure = STRUCTURE, environment = RULE_ON, maxTicks = 100)
    public void aBoxIsAtMostSixteenBlocks(GameTestHelper helper) {
        BlockPos low = new BlockPos(6, 0, 6);
        BlockPos tooFar = new BlockPos(6, 16, 6);
        BlockPos justRight = new BlockPos(6, 15, 6);
        for (int y = 1; y <= 14; y++) {
            helper.setBlock(new BlockPos(6, y, 6), Blocks.STONE);
        }
        corner(helper, low, 0);
        corner(helper, tooFar, 0);
        helper.startSequence().thenIdle(1)
                .thenExecute(() -> {
                    power(helper, low, true);
                    power(helper, tooFar, true);
                })
                .thenIdle(10)
                .thenExecute(() -> {
                    for (int y = 1; y <= 14; y++) {
                        helper.assertBlockPresent(Blocks.STONE, new BlockPos(6, y, 6));
                    }
                    power(helper, tooFar, false);
                    hidden(helper).setBlock(helper.absolutePos(tooFar), Blocks.AIR.defaultBlockState());
                    corner(helper, justRight, 0);
                    power(helper, justRight, true);
                })
                .thenIdle(10)
                .thenExecute(() -> {
                    for (int y = 1; y <= 14; y++) {
                        helper.assertBlockNotPresent(Blocks.STONE, new BlockPos(6, y, 6));
                    }
                    helper.assertValueEqual(entity(helper, low).holding(), 14, "blocks held");
                })
                .thenSucceed();
    }

    /**
     * Turned around mid-move (delay 2, a layer every 4 game ticks): two layers gone, one corner loses its power, and
     * the box comes back from its lowest held layer; powered again, it goes again from the top.
     */
    @GameTest(structure = STRUCTURE, environment = RULE_ON, maxTicks = 200)
    public void turnedAroundMidMoveItGoesBackTheOtherWay(GameTestHelper helper) {
        Map<BlockPos, BlockState> built = hut(helper);
        corner(helper, LOW, 2);
        corner(helper, HIGH, 2);
        List<String> log = new ArrayList<>();
        Map<Integer, Boolean> was = new HashMap<>();
        long[] start = {0};
        Runnable look = () -> {
            for (int y = LOW.getY(); y <= HIGH.getY(); y++) {
                int layer = y;
                boolean gone = built.keySet().stream().filter(p -> p.getY() == layer).allMatch(p -> helper.getBlockState(p).isAir());
                boolean there = built.keySet().stream().filter(p -> p.getY() == layer).noneMatch(p -> helper.getBlockState(p).isAir());
                if (gone && !Boolean.TRUE.equals(was.get(y)) || there && !Boolean.FALSE.equals(was.get(y))) {
                    if (was.containsKey(y) || gone) {
                        log.add((gone ? "-" : "+") + y + "@" + (helper.getTick() - start[0]));
                    }
                    was.put(y, gone);
                }
            }
        };
        GameTestSequence sequence = helper.startSequence().thenIdle(3).thenExecute(() -> {
            start[0] = helper.getTick();
            power(helper, LOW, true);
            power(helper, HIGH, true);
            look.run();
        });
        for (int i = 1; i <= 40; i++) {
            int tick = i;
            sequence = sequence.thenIdle(1).thenExecute(() -> {
                if (tick == 5) {
                    power(helper, HIGH, false);
                } else if (tick == 13) {
                    power(helper, HIGH, true);
                }
                look.run();
            });
        }
        sequence.thenExecute(() -> {
                    helper.assertValueEqual(log, List.of("-4@0", "-3@4", "+3@8", "+4@12", "-4@16", "-3@20", "-2@24", "-1@28"),
                            "layers (-gone/+back y@game tick)");
                    power(helper, HIGH, false);
                })
                .thenIdle(30)
                .thenExecute(() -> assertAll(helper, built))
                .thenSucceed();
    }

    /**
     * Removing the other corner while the box is out makes the leader put it back, layer by layer; removing the
     * leader puts it all back at once.
     */
    @GameTest(structure = STRUCTURE, environment = RULE_ON, maxTicks = 200)
    public void removingACornerPutsTheBoxBack(GameTestHelper helper) {
        Map<BlockPos, BlockState> built = hut(helper);
        corner(helper, LOW, 1);
        corner(helper, HIGH, 1);
        helper.startSequence().thenIdle(3)
                .thenExecute(() -> {
                    power(helper, LOW, true);
                    power(helper, HIGH, true);
                })
                .thenIdle(12)
                .thenExecute(() -> hidden(helper).setBlock(helper.absolutePos(HIGH), Blocks.AIR.defaultBlockState()))
                .thenIdle(12)
                .thenExecute(() -> {
                    assertAll(helper, built);
                    corner(helper, HIGH, 1);
                    power(helper, HIGH, false);
                    power(helper, HIGH, true);
                })
                .thenIdle(12)
                .thenExecute(() -> {
                    helper.assertValueEqual(entity(helper, LOW).holding(), built.size(), "blocks held again");
                    hidden(helper).setBlock(helper.absolutePos(LOW), Blocks.AIR.defaultBlockState());
                    assertAll(helper, built);
                    helper.assertTrue(helper.getLevel().getEntitiesOfClass(ItemEntity.class, area(helper)).isEmpty(), "something dropped");
                })
                .thenSucceed();
    }

    /** Takes the hut (both corners powered) and waits until all of it is held. */
    private static GameTestSequence takeAll(GameTestHelper helper, Map<BlockPos, BlockState> built) {
        corner(helper, LOW, 1);
        corner(helper, HIGH, 1);
        return helper.startSequence().thenIdle(3)
                .thenExecute(() -> {
                    power(helper, LOW, true);
                    power(helper, HIGH, true);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(entity(helper, LOW).holding(), built.size(), "blocks held"));
    }

    /**
     * The leader (LOW, which sorts first) is removed while it holds the hut and bedrock stands in one of its spots:
     * the rest comes back at once and the block that cannot drops as an item there. Nothing is lost or doubled.
     */
    @GameTest(structure = STRUCTURE, environment = RULE_ON, maxTicks = 200)
    public void removingTheLeaderDropsWhatCannotGoBack(GameTestHelper helper) {
        Map<BlockPos, BlockState> built = hut(helper);
        BlockPos blocked = new BlockPos(1, 2, 2);                  // a plank in the west wall
        helper.assertValueEqual(built.get(blocked), Blocks.OAK_PLANKS.defaultBlockState(), "the hut's west wall");
        takeAll(helper, built)
                .thenExecute(() -> {
                    helper.setBlock(blocked, Blocks.BEDROCK);
                    hidden(helper).setBlock(helper.absolutePos(LOW), Blocks.AIR.defaultBlockState());
                })
                .thenIdle(1)
                .thenExecute(() -> {
                    for (Map.Entry<BlockPos, BlockState> e : built.entrySet()) {
                        BlockState want = e.getKey().equals(blocked) ? Blocks.BEDROCK.defaultBlockState() : e.getValue();
                        helper.assertValueEqual(helper.getBlockState(e.getKey()), want, "at " + e.getKey().toShortString());
                    }
                    var items = helper.getLevel().getEntitiesOfClass(ItemEntity.class, area(helper));
                    helper.assertValueEqual(items.stream().map(i -> i.getItem().getItem() + " x" + i.getItem().getCount()).toList(),
                            List.of(Items.OAK_PLANKS + " x1"), "what dropped");
                    helper.assertTrue(helper.getBlockEntity(new BlockPos(3, 2, 3), ChestBlockEntity.class).getItem(4)
                            .is(Items.DIAMOND), "the chest's diamonds");
                })
                .thenSucceed();
    }

    /**
     * Taking the hut and putting it back is silent in the visible world, a block broken in a held spot too (the
     * dirt put in the doorway's floor spot while the hut is away).
     */
    @GameTest(structure = STRUCTURE, environment = RULE_ON, maxTicks = 200)
    public void silentInTheVisibleWorld(GameTestHelper helper) {
        Map<BlockPos, BlockState> built = hut(helper);
        BlockPos spot = new BlockPos(3, 1, 1);
        takeAll(helper, built)
                .thenExecute(() -> {
                    helper.setBlock(spot, Blocks.DIRT);
                    power(helper, LOW, false);
                })
                .thenWaitUntil(() -> assertAll(helper, built))
                .thenExecute(() -> {
                    var heard = SoundRecorder.in(helper.getLevel().dimension(), area(helper));
                    helper.assertTrue(heard.isEmpty(), "heard in the visible world: " + heard);
                })
                .thenSucceed();
    }

    /**
     * A pig stands in a floor spot, under the chest, when the hut comes back: the floor lifts it, then the chest
     * coming back lifts it again, to stand on the chest; the hut comes back whole around it.
     */
    @GameTest(structure = STRUCTURE, environment = RULE_ON, maxTicks = 200)
    public void puttingBackLiftsAMobOntoTheFloor(GameTestHelper helper) {
        for (BlockPos p : BlockPos.betweenClosed(LOW.below(), new BlockPos(HIGH.getX(), 0, HIGH.getZ()))) {
            helper.setBlock(p.immutable(), Blocks.STONE);         // under the box: the pig stands on it
        }
        Map<BlockPos, BlockState> built = hut(helper);
        BlockPos spot = new BlockPos(3, 1, 3);
        Pig[] pig = new Pig[1];
        takeAll(helper, built)
                .thenExecute(() -> {
                    pig[0] = helper.spawnWithNoFreeWill(EntityTypes.PIG, spot);
                    power(helper, LOW, false);
                })
                .thenWaitUntil(() -> assertAll(helper, built))
                .thenExecute(() -> {
                    helper.assertValueEqual(pig[0].getY(), helper.absolutePos(spot).getY() + 1.875, "the pig's feet, on the chest");
                    helper.assertTrue(pig[0].isAlive(), "the pig died");
                })
                .thenSucceed();
    }
}
