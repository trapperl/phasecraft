package dev.phasecraft.test;

import dev.phasecraft.PhaseCraftBlocks;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.vehicle.minecart.Minecart;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * The minecart track (the person's list, 2026-11-24: "a track built ahead of a minecart"; layout:
 * contraptions/minecart_track.txt, written by tools/gen_track.py; examples station 35): a track across a chasm that is
 * there only around a minecart. Each column, a deck block and the rail on it, is held by a hidden line shifter under
 * it while no cart is within {@link #REACH}; hidden strings at rail height sense the cart.
 */
public class MinecartTrackGameTests {
    static final int REACH = 5;
    static final int COLUMNS = 20;
    /** From the first column (generator x = 0) to each cliff's start rail, by the wall. */
    static final int WEST_START = -12;
    static final int EAST_START = 31;
    private static ContraptionLayout layout;

    static ContraptionLayout layout() {
        if (layout == null) {
            layout = ContraptionLayout.load("minecart_track");
        }
        return layout;
    }

    /** The line shifters under the columns, west to east. */
    static List<BlockPos> shifters() {
        return layout().hidden(PhaseCraftBlocks.LINE_SHIFTER).stream().sorted(Comparator.comparingInt(BlockPos::getX)).toList();
    }

    /** A position on the track's line (the rails' height), x columns east of the first column. */
    static BlockPos track(int x) {
        return shifters().getFirst().offset(x, 2, 0);
    }

    /**
     * The columns there: deck and rail both. A column with one but not the other shows as -1 - its index: for a game
     * tick or two a column is half there, the deck back before its rail, or its rail gone before the deck.
     */
    static TreeSet<Integer> there(GameTestHelper helper) {
        TreeSet<Integer> out = new TreeSet<>();
        for (int k = 0; k < COLUMNS; k++) {
            boolean deck = helper.getBlockState(track(k).below()).is(Blocks.SPRUCE_PLANKS);
            boolean rail = helper.getBlockState(track(k)).is(Blocks.RAIL);
            if (deck && rail) {
                out.add(k);
            } else if (deck || rail) {
                out.add(-1 - k);
            }
        }
        return out;
    }

    /** The column (generator x) the cart is over. */
    static int column(GameTestHelper helper, Minecart cart) {
        return (int) Math.floor(helper.relativeVec(cart.position()).x) - track(0).getX();
    }

    /**
     * A ride from one start rail to the other: the button sends the cart off, the boosters speed it up, and the cart
     * crosses on the track as it appears. Every tick: the column under the cart and the next one are there while it
     * is over the chasm, and no column more than REACH + 1 ahead of it is. It ends stopped on the far start rail.
     */
    private static GameTestSequence ride(GameTestHelper helper, GameTestSequence sequence, Minecart[] cart, int from, int to,
            List<String> wrong) {
        int dir = Integer.signum(to - from);
        String name = dir > 0 ? "eastwards" : "westwards";
        double[] fastest = {0};
        return sequence
                .thenExecute(() -> helper.pressButton(track(from).north()))
                .thenWaitUntil(() -> {
                    Minecart c = cart[0];
                    int at = column(helper, c);
                    fastest[0] = Math.max(fastest[0], c.getDeltaMovement().horizontalDistance());
                    double y = helper.relativeVec(c.position()).y;
                    if (y < track(0).getY() - 0.5) {
                        wrong.add(name + ": the cart fell at column " + at);
                        return;
                    }
                    TreeSet<Integer> there = there(helper);
                    for (int k : there) {
                        int column = k < 0 ? -1 - k : k;          // half there: on its way (deck first) or going (rail first)
                        if ((column - at) * dir > REACH + 1) {
                            wrong.add(name + ": column " + column + " there, cart at " + at);
                        }
                    }
                    for (int k : new int[] {at, at + dir}) {
                        if (k >= 0 && k < COLUMNS && !there.contains(k)) {
                            wrong.add(name + ": column " + k + " missing, cart at " + at);
                        }
                    }
                    helper.assertValueEqual(at, to, "the cart's column");
                    helper.assertTrue(c.getDeltaMovement().length() < 1.0E-3, "the cart still moving");
                })
                .thenIdle(40)
                .thenExecute(() -> {
                    helper.assertValueEqual(there(helper), new TreeSet<Integer>(), "columns there " + name + ", after the ride");
                    helper.assertValueEqual(column(helper, cart[0]), to, "where the cart stopped " + name);
                    // the boosters bring it to a cart's top speed, 0.4 blocks a game tick: the hardest case for REACH
                    helper.assertTrue(fastest[0] > 0.39, "the cart's top speed " + name + ": " + fastest[0]);
                });
    }

    /**
     * With no cart near, the chasm is empty. A cart with a rider (a pig) crosses from the west start rail to the
     * east one and back, the track there around it all the way, and nothing of it left after.
     */
    @GameTest(structure = "phasecraft-test:minecart_track", maxTicks = 1200)
    public void theTrackIsThereOnlyAroundTheCart(GameTestHelper helper) {
        layout().build(helper);
        Minecart[] cart = new Minecart[1];
        List<String> wrong = new ArrayList<>();
        GameTestSequence sequence = helper.startSequence()
                .thenIdle(40)
                .thenExecute(() -> {
                    helper.assertValueEqual(shifters().size(), COLUMNS, "columns");
                    helper.assertValueEqual(there(helper), new TreeSet<Integer>(), "columns there with no cart near");
                    cart[0] = helper.spawn(EntityTypes.MINECART, Vec3.atBottomCenterOf(track(WEST_START)));
                    Pig pig = helper.spawnWithNoFreeWill(EntityTypes.PIG, track(WEST_START));
                    helper.assertTrue(pig.startRiding(cart[0]), "the pig got in");
                })
                .thenIdle(10)
                .thenExecute(() -> helper.assertValueEqual(there(helper), new TreeSet<Integer>(), "columns there, cart at the start"));
        sequence = ride(helper, sequence, cart, WEST_START, EAST_START, wrong);
        sequence = ride(helper, sequence, cart, EAST_START, WEST_START, wrong);
        sequence.thenExecute(() -> helper.assertValueEqual(wrong, List.of(), "what went wrong on the rides"))
                .thenSucceed();
    }

    /** Within REACH of column k, both ways, clipped to the track. */
    static TreeSet<Integer> within(int k) {
        return IntStream.range(0, COLUMNS).filter(j -> Math.abs(j - k) <= REACH).boxed()
                .collect(Collectors.toCollection(TreeSet::new));
    }

    /** A cart standing still over a column (on its rail) keeps exactly the columns within REACH there. */
    @GameTest(structure = "phasecraft-test:minecart_track", maxTicks = 400)
    public void aCartStandingOnTheTrackKeepsItThere(GameTestHelper helper) {
        layout().build(helper);
        Minecart[] cart = new Minecart[1];
        helper.startSequence()
                .thenIdle(40)
                .thenExecute(() -> cart[0] = helper.spawn(EntityTypes.MINECART, Vec3.atBottomCenterOf(track(-2))))
                .thenWaitUntil(() -> helper.assertValueEqual(there(helper), within(-2), "columns there, cart at -2"))
                .thenExecute(() -> {
                    cart[0].setDeltaMovement(0.3, 0, 0);          // an empty cart slows fast: this carries it about 7 blocks
                })
                .thenWaitUntil(() -> helper.assertTrue(column(helper, cart[0]) >= 3, "the cart rolled on"))
                .thenExecute(() -> cart[0].setDeltaMovement(Vec3.ZERO))
                .thenIdle(40)
                .thenExecute(() -> {
                    int at = column(helper, cart[0]);
                    helper.assertValueEqual(there(helper), within(at), "columns there, cart standing at " + at);
                    cart[0].discard();
                })
                .thenIdle(40)
                .thenExecute(() -> helper.assertValueEqual(there(helper), new TreeSet<Integer>(), "columns there, cart gone"))
                .thenSucceed();
    }
}
