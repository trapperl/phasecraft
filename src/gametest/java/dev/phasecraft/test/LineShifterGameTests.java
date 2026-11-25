package dev.phasecraft.test;

import dev.phasecraft.PhaseCraftBlocks;
import dev.phasecraft.edit.HiddenEditing;
import dev.phasecraft.layer.HiddenLayer;
import dev.phasecraft.shifter.LineShifterBlock;
import dev.phasecraft.shifter.LineShifterBlockEntity;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The line shifter (the person's idea, 2026-11-25: "'pushing' phase blocks in a row or column would be an intuitive
 * fix"): a hidden block facing a way, as a piston does. Powered, it takes the line of visible blocks in front of it
 * (the line a piston there could push: up to the first air or fragile block, at most twelve, none if a thirteenth or
 * an immovable block is in it) one at a time from the far end; unpowered, it puts them back one at a time from the
 * near end. One block every 1 to 4 redstone ticks, set by clicking it. Turned around mid-move, it goes back the other
 * way, keeping the rhythm.
 */
public class LineShifterGameTests {
    static final String STRUCTURE = "phasecraft-test:empty_8x17x8";

    private static HiddenLayer hidden(GameTestHelper helper) {
        return HiddenLayer.of(helper.getLevel());
    }

    /** A line shifter in the hidden layer at rel, facing that way, with that delay (redstone ticks a block). */
    private static void lineShifter(GameTestHelper helper, BlockPos rel, Direction facing, int delay) {
        hidden(helper).setBlock(helper.absolutePos(rel), PhaseCraftBlocks.LINE_SHIFTER.defaultBlockState()
                .setValue(LineShifterBlock.FACING, facing).setValue(LineShifterBlock.DELAY, delay));
    }

    /** Powers a line shifter, or stops, with a hidden redstone block behind it. */
    private static void power(GameTestHelper helper, BlockPos rel, Direction facing, boolean on) {
        hidden(helper).setBlock(helper.absolutePos(rel.relative(facing.getOpposite())),
                on ? Blocks.REDSTONE_BLOCK.defaultBlockState() : Blocks.AIR.defaultBlockState());
    }

    private static List<BlockPos> line(BlockPos rel, Direction facing, int length) {
        List<BlockPos> line = new ArrayList<>();
        for (int d = 1; d <= length; d++) {
            line.add(rel.relative(facing, d));
        }
        return line;
    }

    private static LineShifterBlockEntity entity(GameTestHelper helper, BlockPos rel) {
        return (LineShifterBlockEntity) hidden(helper).level().getBlockEntity(helper.absolutePos(rel));
    }

    /**
     * Every change of the watched visible spots, as "tick:-d" (the block at distance d went) or "tick:+d" (it came
     * back), ticks counted from the first scripted moment. Actions run at their tick before the look.
     */
    private static final class Script {
        final List<BlockPos> line;
        final Map<BlockPos, BlockState> last = new HashMap<>();
        final List<String> events = new ArrayList<>();
        final Map<Integer, Runnable> actions = new HashMap<>();
        long start;

        Script(List<BlockPos> line) {
            this.line = line;
        }

        Script at(int tick, Runnable action) {
            actions.merge(tick, action, (a, b) -> () -> {
                a.run();
                b.run();
            });
            return this;
        }

        void look(GameTestHelper helper) {
            for (int i = 0; i < line.size(); i++) {
                BlockState now = helper.getBlockState(line.get(i));
                BlockState before = last.put(line.get(i), now);
                if (before != null && before.isAir() != now.isAir()) {
                    events.add((helper.getTick() - start) + ":" + (now.isAir() ? "-" : "+") + (i + 1));
                }
            }
        }

        GameTestSequence run(GameTestHelper helper, GameTestSequence sequence, int ticks) {
            sequence = sequence.thenExecute(() -> look(helper));
            for (int i = 0; i <= ticks; i++) {
                int tick = i;
                sequence = sequence.thenExecute(() -> {
                    if (tick == 0) {
                        start = helper.getTick();
                    }
                    helper.assertValueEqual(helper.getTick() - start, (long) tick, "script tick");
                    Runnable action = actions.get(tick);
                    if (action != null) {
                        action.run();
                    }
                    look(helper);
                }).thenIdle(1);
            }
            return sequence;
        }

        void assertEvents(GameTestHelper helper, String... expected) {
            helper.assertValueEqual(String.join(" ", events), String.join(" ", expected), "moves (tick:-distance went, +distance came back)");
        }
    }

    /** The whole test structure, to look for dropped items. */
    private static AABB area(GameTestHelper helper) {
        return new AABB(Vec3.atCenterOf(helper.absolutePos(BlockPos.ZERO)), Vec3.atCenterOf(helper.absolutePos(new BlockPos(7, 16, 7)))).inflate(1);
    }

    private static void assertAll(GameTestHelper helper, Map<BlockPos, BlockState> want, String what) {
        for (Map.Entry<BlockPos, BlockState> e : want.entrySet()) {
            helper.assertValueEqual(helper.getBlockState(e.getKey()), e.getValue(), what + " at " + e.getKey().toShortString());
        }
    }

    /**
     * A column of five different blocks over a line shifter facing up: powered, the top block goes at once and the
     * next one down every redstone tick (delay 1); unpowered, the bottom one comes back at once and the next one up
     * every redstone tick, each exactly as it was (the stairs still face east).
     */
    @GameTest(structure = STRUCTURE, maxTicks = 200)
    public void takesTheLineFromTheFarEndAndPutsItBackFromTheNearEnd(GameTestHelper helper) {
        BlockPos at = new BlockPos(3, 1, 3);
        List<BlockPos> line = line(at, Direction.UP, 5);
        BlockState[] blocks = {Blocks.STONE.defaultBlockState(),
                Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.EAST),
                Blocks.GOLD_BLOCK.defaultBlockState(), Blocks.BOOKSHELF.defaultBlockState(), Blocks.GLASS.defaultBlockState()};
        Map<BlockPos, BlockState> built = new HashMap<>();
        for (int i = 0; i < 5; i++) {
            helper.setBlock(line.get(i), blocks[i]);
            built.put(line.get(i), blocks[i]);
        }
        lineShifter(helper, at, Direction.UP, 1);
        Script script = new Script(line)
                .at(0, () -> power(helper, at, Direction.UP, true))
                .at(20, () -> {
                    helper.assertValueEqual(entity(helper, at).holding(), 5, "blocks held");
                    power(helper, at, Direction.UP, false);
                });
        script.run(helper, helper.startSequence().thenIdle(3), 40)
                .thenExecute(() -> {
                    script.assertEvents(helper, "0:-5", "2:-4", "4:-3", "6:-2", "8:-1", "20:+1", "22:+2", "24:+3", "26:+4", "28:+5");
                    assertAll(helper, built, "the column as it was");
                    helper.assertValueEqual(entity(helper, at).holding(), 0, "blocks still held");
                    helper.assertValueEqual(hidden(helper).getBlock(helper.absolutePos(line.get(0))), Blocks.AIR.defaultBlockState(),
                            "hidden block in the line (the blocks are held, not placed in the hidden layer)");
                })
                .thenSucceed();
    }

    /**
     * Placed in editing mode, it faces the player, as a piston does; each click sets its delay one higher, as on a
     * repeater; at delay 4 a block moves every 4 redstone ticks.
     */
    @GameTest(structure = STRUCTURE, maxTicks = 200)
    public void placedFacingThePlayerAndClickedForItsSpeed(GameTestHelper helper) {
        BlockPos ground = new BlockPos(5, 2, 3);
        BlockPos at = ground.above();
        hidden(helper).setBlock(helper.absolutePos(ground), Blocks.STONE.defaultBlockState());
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        try {
            Vec3 stand = Vec3.atBottomCenterOf(helper.absolutePos(ground.west(3)));
            player.snapTo(stand.x, stand.y, stand.z, -90.0F, 30.0F); // facing east, towards the stone
            player.setYHeadRot(-90.0F); // placing goes by where the head looks
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(PhaseCraftBlocks.LINE_SHIFTER_ITEM));
            BlockPos abs = helper.absolutePos(ground);
            HiddenEditing.use(player, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(abs).relative(Direction.UP, 0.5), Direction.UP, abs, false));
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            BlockPos shifter = helper.absolutePos(at);
            for (int i = 0; i < 3; i++) {
                HiddenEditing.use(player, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(shifter).relative(Direction.UP, 0.5), Direction.UP, shifter, false));
            }
        } finally {
            helper.getLevel().getServer().getPlayerList().remove(player);
        }
        BlockState placed = hidden(helper).getBlock(helper.absolutePos(at));
        helper.assertTrue(placed.is(PhaseCraftBlocks.LINE_SHIFTER), "no line shifter placed: " + placed);
        helper.assertValueEqual(placed.getValue(LineShifterBlock.FACING), Direction.WEST, "facing (towards the player)");
        helper.assertValueEqual(placed.getValue(LineShifterBlock.DELAY), 4, "delay after three clicks");
        List<BlockPos> line = line(at, Direction.WEST, 3);
        for (BlockPos p : line) {
            helper.setBlock(p, Blocks.OAK_PLANKS);
        }
        Script script = new Script(line)
                .at(0, () -> power(helper, at, Direction.WEST, true))
                .at(30, () -> power(helper, at, Direction.WEST, false));
        script.run(helper, helper.startSequence().thenIdle(3), 50)
                .thenExecute(() -> script.assertEvents(helper, "0:-3", "8:-2", "16:-1", "30:+1", "38:+2", "46:+3"))
                .thenSucceed();
    }

    /**
     * Turned around mid-move, it goes back the other way at the same rhythm (delay 2: a block every 4 game ticks):
     * three of six taken, it puts back the nearest first; two back, it takes the line again from its new far end.
     */
    @GameTest(structure = STRUCTURE, maxTicks = 300)
    public void turnedAroundMidMoveItGoesBackTheOtherWay(GameTestHelper helper) {
        BlockPos at = new BlockPos(3, 1, 3);
        List<BlockPos> line = line(at, Direction.UP, 6);
        Map<BlockPos, BlockState> built = new HashMap<>();
        Block[] blocks = {Blocks.STONE, Blocks.DIRT, Blocks.OAK_PLANKS, Blocks.GOLD_BLOCK, Blocks.BRICKS, Blocks.GLASS};
        for (int i = 0; i < 6; i++) {
            helper.setBlock(line.get(i), blocks[i]);
            built.put(line.get(i), blocks[i].defaultBlockState());
        }
        lineShifter(helper, at, Direction.UP, 2);
        Script script = new Script(line)
                .at(0, () -> power(helper, at, Direction.UP, true))
                .at(9, () -> power(helper, at, Direction.UP, false))
                .at(17, () -> power(helper, at, Direction.UP, true))
                .at(40, () -> power(helper, at, Direction.UP, false));
        script.run(helper, helper.startSequence().thenIdle(3), 70)
                .thenExecute(() -> {
                    script.assertEvents(helper, "0:-6", "4:-5", "8:-4", "12:+4", "16:+5", "20:-5", "24:-4", "28:-3", "32:-2",
                            "36:-1", "40:+1", "44:+2", "48:+3", "52:+4", "56:+5", "60:+6");
                    assertAll(helper, built, "the column as it was");
                })
                .thenSucceed();
    }

    /**
     * The line a piston would push, and only that: it ends at the first air or before a block a piston would break
     * (a cobweb, which stays); twelve blocks go, thirteen stay, and so does a line with obsidian in it.
     */
    @GameTest(structure = STRUCTURE, maxTicks = 200)
    public void takesTheLineAPistonWouldPush(GameTestHelper helper) {
        BlockPos twelve = new BlockPos(1, 1, 1);
        BlockPos thirteen = new BlockPos(3, 1, 1);
        BlockPos obsidian = new BlockPos(5, 1, 1);
        BlockPos gap = new BlockPos(1, 1, 4);
        BlockPos cobweb = new BlockPos(1, 2, 6);
        Map<BlockPos, BlockState> stays = new HashMap<>();
        List<BlockPos> goes = new ArrayList<>();
        for (BlockPos p : line(twelve, Direction.UP, 12)) {
            helper.setBlock(p, Blocks.STONE);
            goes.add(p);
        }
        for (BlockPos p : line(thirteen, Direction.UP, 13)) {
            helper.setBlock(p, Blocks.STONE);
            stays.put(p, Blocks.STONE.defaultBlockState());
        }
        List<BlockPos> withObsidian = line(obsidian, Direction.UP, 5);
        for (int i = 0; i < 5; i++) {
            Block block = i == 3 ? Blocks.OBSIDIAN : Blocks.STONE;
            helper.setBlock(withObsidian.get(i), block);
            stays.put(withObsidian.get(i), block.defaultBlockState());
        }
        List<BlockPos> withGap = line(gap, Direction.UP, 6);
        for (int i = 0; i < 6; i++) {
            if (i < 3) {
                helper.setBlock(withGap.get(i), Blocks.STONE);
                goes.add(withGap.get(i));
            } else if (i > 3) {
                helper.setBlock(withGap.get(i), Blocks.STONE);
                stays.put(withGap.get(i), Blocks.STONE.defaultBlockState());
            }
        }
        List<BlockPos> withCobweb = line(cobweb, Direction.EAST, 4);
        Block[] across = {Blocks.STONE, Blocks.STONE, Blocks.COBWEB, Blocks.STONE};
        for (int i = 0; i < 4; i++) {
            helper.setBlock(withCobweb.get(i), across[i]);
            if (i < 2) {
                goes.add(withCobweb.get(i));
            } else {
                stays.put(withCobweb.get(i), across[i].defaultBlockState());
            }
        }
        BlockPos[] shifters = {twelve, thirteen, obsidian, gap};
        for (BlockPos p : shifters) {
            lineShifter(helper, p, Direction.UP, 1);
        }
        lineShifter(helper, cobweb, Direction.EAST, 1);
        helper.startSequence()
                .thenIdle(3)
                .thenExecute(() -> {
                    for (BlockPos p : shifters) {
                        power(helper, p, Direction.UP, true);
                    }
                    power(helper, cobweb, Direction.EAST, true);
                })
                .thenIdle(40)
                .thenExecute(() -> {
                    for (BlockPos p : goes) {
                        helper.assertBlockPresent(Blocks.AIR, p);
                    }
                    assertAll(helper, stays, "a block a piston would not have moved");
                    helper.assertValueEqual(entity(helper, twelve).holding(), 12, "blocks held from the line of twelve");
                    for (BlockPos p : shifters) {
                        power(helper, p, Direction.UP, false);
                    }
                    power(helper, cobweb, Direction.EAST, false);
                })
                .thenIdle(40)
                .thenExecute(() -> {
                    for (BlockPos p : goes) {
                        helper.assertBlockPresent(Blocks.STONE, p);
                    }
                    assertAll(helper, stays, "a block a piston would not have moved");
                })
                .thenSucceed();
    }

    /** A chest in the line goes and comes back with what is in it; nothing spills. */
    @GameTest(structure = STRUCTURE, maxTicks = 100)
    public void takesAChestWithItsContents(GameTestHelper helper) {
        BlockPos at = new BlockPos(3, 1, 3);
        List<BlockPos> line = line(at, Direction.UP, 3);
        helper.setBlock(line.get(0), Blocks.STONE);
        helper.setBlock(line.get(1), Blocks.CHEST);
        ((ChestBlockEntity) helper.getBlockEntity(line.get(1), ChestBlockEntity.class)).setItem(4, new ItemStack(Items.DIAMOND, 7));
        helper.setBlock(line.get(2), Blocks.GOLD_BLOCK);
        lineShifter(helper, at, Direction.UP, 1);
        AABB area = area(helper);
        helper.startSequence()
                .thenIdle(3)
                .thenExecute(() -> power(helper, at, Direction.UP, true))
                .thenIdle(10)
                .thenExecute(() -> {
                    for (BlockPos p : line) {
                        helper.assertBlockPresent(Blocks.AIR, p);
                    }
                    helper.assertTrue(helper.getLevel().getEntitiesOfClass(ItemEntity.class, area).isEmpty(), "something spilled");
                    power(helper, at, Direction.UP, false);
                })
                .thenIdle(10)
                .thenExecute(() -> {
                    helper.assertBlockPresent(Blocks.CHEST, line.get(1));
                    ChestBlockEntity chest = helper.getBlockEntity(line.get(1), ChestBlockEntity.class);
                    helper.assertTrue(chest.getItem(4).is(Items.DIAMOND) && chest.getItem(4).getCount() == 7, "the seven diamonds");
                    helper.assertTrue(helper.getLevel().getEntitiesOfClass(ItemEntity.class, area).isEmpty(), "something spilled");
                })
                .thenSucceed();
    }

    /**
     * Putting back waits while the nearest held spot holds an unbreakable block (bedrock), and the blocks behind it
     * wait their turn; once it is gone they come back, the next one breaking the dirt placed in its spot, which drops.
     */
    @GameTest(structure = STRUCTURE, maxTicks = 200)
    public void puttingBackWaitsItsTurnAndBreaksWhatIsInTheWay(GameTestHelper helper) {
        BlockPos at = new BlockPos(3, 1, 3);
        List<BlockPos> line = line(at, Direction.UP, 2);
        helper.setBlock(line.get(0), Blocks.GOLD_BLOCK);
        helper.setBlock(line.get(1), Blocks.BRICKS);
        lineShifter(helper, at, Direction.UP, 1);
        AABB area = area(helper);
        helper.startSequence()
                .thenIdle(3)
                .thenExecute(() -> power(helper, at, Direction.UP, true))
                .thenIdle(6)
                .thenExecute(() -> {
                    helper.setBlock(line.get(0), Blocks.BEDROCK);
                    helper.setBlock(line.get(1), Blocks.DIRT);
                    power(helper, at, Direction.UP, false);
                })
                .thenIdle(20)
                .thenExecute(() -> {
                    helper.assertBlockPresent(Blocks.BEDROCK, line.get(0));
                    helper.assertBlockPresent(Blocks.DIRT, line.get(1));
                    helper.assertValueEqual(entity(helper, at).holding(), 2, "blocks held while the bedrock is there");
                    helper.setBlock(line.get(0), Blocks.AIR);
                })
                .thenIdle(10)
                .thenExecute(() -> {
                    helper.assertBlockPresent(Blocks.GOLD_BLOCK, line.get(0));
                    helper.assertBlockPresent(Blocks.BRICKS, line.get(1));
                    helper.assertTrue(helper.getLevel().getEntitiesOfClass(ItemEntity.class, area).stream()
                            .anyMatch(item -> item.getItem().is(Items.DIRT)), "the dirt was not dropped");
                })
                .thenSucceed();
    }

    /** Removed while it holds blocks, it puts them back; one that cannot go back (bedrock in its spot) drops. */
    @GameTest(structure = STRUCTURE, maxTicks = 100)
    public void removedItPutsBackWhatItHolds(GameTestHelper helper) {
        BlockPos at = new BlockPos(3, 1, 3);
        List<BlockPos> line = line(at, Direction.UP, 3);
        for (BlockPos p : line) {
            helper.setBlock(p, Blocks.GOLD_BLOCK);
        }
        lineShifter(helper, at, Direction.UP, 1);
        AABB area = area(helper);
        helper.startSequence()
                .thenIdle(3)
                .thenExecute(() -> power(helper, at, Direction.UP, true))
                .thenIdle(8)
                .thenExecute(() -> {
                    helper.setBlock(line.get(1), Blocks.BEDROCK);
                    hidden(helper).setBlock(helper.absolutePos(at), Blocks.AIR.defaultBlockState());
                })
                .thenIdle(3)
                .thenExecute(() -> {
                    helper.assertBlockPresent(Blocks.GOLD_BLOCK, line.get(0));
                    helper.assertBlockPresent(Blocks.BEDROCK, line.get(1));
                    helper.assertBlockPresent(Blocks.GOLD_BLOCK, line.get(2));
                    helper.assertValueEqual(helper.getLevel().getEntitiesOfClass(ItemEntity.class, area).stream()
                            .filter(item -> item.getItem().is(Items.GOLD_BLOCK)).mapToInt(item -> item.getItem().getCount()).sum(),
                            1, "gold blocks dropped");
                    helper.killAllEntitiesOfClass(ItemEntity.class);
                })
                .thenSucceed();
    }
}
