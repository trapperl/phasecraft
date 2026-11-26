package dev.phasecraft.test;

import dev.phasecraft.PhaseCraftBlocks;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The vanishing cottage (layout: contraptions/vanishing_cottage.txt, examples station 29): two hidden area shifters
 * at opposite corners of a small cottage. Its lever takes the whole cottage out of the visible world, roof first, a
 * layer every 3 redstone ticks, and flipped back builds it again from the floor, chest contents and all. Nothing
 * of it shows in the visible world but the cottage and the lever. Runs with phasecraft:area_shifters on.
 */
public class VanishingCottageGameTests {
    static final String STRUCTURE = "phasecraft-test:empty_24x7x24";
    /** The cottage's box. */
    static final BlockPos LOW = new BlockPos(3, 1, 3);
    static final BlockPos HIGH = new BlockPos(9, 5, 9);
    static final BlockPos CHEST = new BlockPos(7, 2, 8);
    /** Game ticks between layers: the corners' delay of 3 redstone ticks. */
    static final int LAYER_TICKS = 6;
    private static ContraptionLayout layout;

    static ContraptionLayout layout() {
        if (layout == null) {
            layout = ContraptionLayout.load("vanishing_cottage");
        }
        return layout;
    }

    /** When each layer of the cottage was all gone (or all back), in game ticks from the lever. */
    private static final class Watch {
        final Map<Integer, Long> at = new HashMap<>();
        long lever;

        void look(GameTestHelper helper, Map<BlockPos, BlockState> built, boolean gone) {
            for (int y = LOW.getY(); y <= HIGH.getY(); y++) {
                int layer = y;
                if (!at.containsKey(y) && built.keySet().stream().filter(p -> p.getY() == layer)
                        .allMatch(p -> helper.getBlockState(p).isAir() == gone)) {
                    at.put(y, helper.getTick() - lever);
                }
            }
        }

        /** "y@ticks" in the order the layers went, ticks from the lever. */
        List<String> moves() {
            List<String> out = new ArrayList<>();
            at.entrySet().stream().sorted(Map.Entry.comparingByValue()).forEach(e -> out.add(e.getKey() + "@" + e.getValue()));
            return out;
        }

        /**
         * The layers went in this order, the first at most 4 game ticks after the lever, then one every LAYER_TICKS.
         * The first gap may be a tick short: a move the lever starts comes with the lever's power crossing into the
         * hidden layer, which happens at another point of the game tick than the scheduled steps after it.
         */
        void assertMoves(GameTestHelper helper, List<Integer> order, String what) {
            List<Integer> ys = at.entrySet().stream().sorted(Map.Entry.comparingByValue()).map(Map.Entry::getKey).toList();
            helper.assertValueEqual(ys, order, what + ": layers in order (" + moves() + ")");
            long first = at.get(order.get(0));
            helper.assertTrue(first <= 4, what + ": the first layer " + first + " game ticks after the lever (" + moves() + ")");
            for (int i = 1; i < order.size(); i++) {
                long gap = at.get(order.get(i)) - at.get(order.get(i - 1));
                helper.assertTrue(gap == LAYER_TICKS || i == 1 && gap == LAYER_TICKS - 1,
                        what + ": " + gap + " game ticks before layer " + order.get(i) + " (" + moves() + ")");
            }
        }
    }

    private static boolean standing(GameTestHelper helper, Map<BlockPos, BlockState> built) {
        return built.entrySet().stream().allMatch(e -> helper.getBlockState(e.getKey()).equals(e.getValue()));
    }

    /**
     * The lever takes the cottage out from the roof down, a layer every 3 redstone ticks, and back from the floor
     * up; twice. It comes back as it was: every block in its state, the chest with its bread, apples and iron. Nothing
     * drops, and the two corners never show.
     */
    @GameTest(structure = STRUCTURE, environment = AreaShifterGameTests.RULE_ON, maxTicks = 600)
    public void leverTakesTheCottageAwayAndBuildsItBack(GameTestHelper helper) {
        layout().build(helper);
        layout().fillContainers(helper);
        Map<BlockPos, BlockState> built = new HashMap<>();
        BlockPos lever = layout().lever();
        GameTestSequence sequence = helper.startSequence().thenIdle(10).thenExecute(() -> {
            helper.assertValueEqual(layout().hidden(PhaseCraftBlocks.AREA_SHIFTER), List.of(LOW, HIGH), "the corners");
            for (BlockPos p : BlockPos.betweenClosed(LOW, HIGH)) {
                if (!helper.getBlockState(p).isAir()) {
                    built.put(p.immutable(), helper.getBlockState(p));
                }
            }
            helper.assertTrue(built.size() > 150, "a cottage of " + built.size() + " blocks");
        });
        List<Integer> down = List.of(5, 4, 3, 2, 1);
        List<Integer> up = List.of(1, 2, 3, 4, 5);
        for (int cycle = 0; cycle < 2; cycle++) {
            Watch gone = new Watch();
            Watch back = new Watch();
            sequence = sequence
                    .thenExecute(() -> {
                        gone.lever = helper.getTick();
                        helper.pullLever(lever);
                    })
                    .thenWaitUntil(() -> {
                        gone.look(helper, built, true);
                        helper.assertTrue(gone.at.size() == 5, "layers still there: " + (5 - gone.at.size()));
                    })
                    .thenExecute(() -> gone.assertMoves(helper, down, "going"))
                    .thenIdle(20)
                    .thenExecute(() -> helper.assertTrue(built.keySet().stream().allMatch(p -> helper.getBlockState(p).isAir()),
                            "a block came back while the lever stayed on"))
                    .thenExecute(() -> {
                        back.lever = helper.getTick();
                        helper.pullLever(lever);
                    })
                    .thenWaitUntil(() -> {
                        back.look(helper, built, false);
                        helper.assertTrue(back.at.size() == 5, "layers still missing: " + (5 - back.at.size()));
                    })
                    .thenExecute(() -> {
                        back.assertMoves(helper, up, "coming back");
                        helper.assertTrue(standing(helper, built), "the cottage is not as it was");
                        ChestBlockEntity chest = helper.getBlockEntity(CHEST, ChestBlockEntity.class);
                        helper.assertTrue(chest.getItem(0).is(Items.BREAD) && chest.getItem(0).getCount() == 6
                                && chest.getItem(1).is(Items.APPLE) && chest.getItem(1).getCount() == 3
                                && chest.getItem(2).is(Items.IRON_INGOT) && chest.getItem(2).getCount() == 5,
                                "the chest's contents after coming back");
                        AABB area = new AABB(Vec3.atLowerCornerOf(helper.absolutePos(BlockPos.ZERO)),
                                Vec3.atLowerCornerOf(helper.absolutePos(new BlockPos(13, 7, 12))));
                        helper.assertTrue(helper.getLevel().getEntitiesOfClass(ItemEntity.class, area).isEmpty(), "items dropped");
                    })
                    .thenIdle(20);     // a moment before the next flip (right away, the next move keeps the rhythm)
        }
        sequence.thenSucceed();
    }
}
