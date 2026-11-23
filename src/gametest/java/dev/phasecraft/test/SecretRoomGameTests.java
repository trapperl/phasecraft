package dev.phasecraft.test;

import dev.phasecraft.layer.HiddenLayer;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * A secret room behind a bookshelf wall (layout: contraptions/secret_room.txt).
 * Nothing redstone is visible: the door is two bookshelves like the rest of the
 * wall, and the switch is one bookshelf on each side of the wall, each hiding a
 * phased button. A hidden copper bulb remembers whether the door is open, so
 * either bookshelf opens a closed door and closes an open one. The door itself
 * is the 2x2 hidden door's mechanism, one block wide: phase pistons push the
 * door back into the wall, then slide it sideways into a pocket.
 */
public class SecretRoomGameTests {
    static final String STRUCTURE = "phasecraft-test:empty_11x11x14";
    private static ContraptionLayout layout;

    /** Loaded on first use, not with the class: test classes load before the mod's blocks are registered. */
    static ContraptionLayout layout() {
        if (layout == null) {
            layout = ContraptionLayout.load("secret_room");
        }
        return layout;
    }

    private static boolean door(GameTestHelper helper, int x, int y, int z) {
        return helper.getBlockState(new BlockPos(x, y, z)).is(layout().door());
    }

    private static boolean air(GameTestHelper helper, int x, int y, int z) {
        return helper.getBlockState(new BlockPos(x, y, z)).isAir();
    }

    /** The two door bookshelves flush in the corridor face (z = 5), the passage behind and the pocket empty. */
    static boolean closed(GameTestHelper helper) {
        for (int y = 1; y <= 2; y++) {
            if (!door(helper, 4, y, 5) || !air(helper, 4, y, 4) || !air(helper, 5, y, 4)) {
                return false;
            }
        }
        return true;
    }

    /** The door bookshelves in the pocket (x = 5, z = 4), the doorway clear through the wall. */
    static boolean open(GameTestHelper helper) {
        for (int y = 1; y <= 2; y++) {
            if (!air(helper, 4, y, 5) || !air(helper, 4, y, 4) || !air(helper, 4, y, 3) || !door(helper, 5, y, 4)) {
                return false;
            }
        }
        return true;
    }

    private static String describe(GameTestHelper helper) {
        StringBuilder out = new StringBuilder();
        for (int y = 2; y >= 1; y--) {
            out.append(" y").append(y).append(':');
            for (int z = 5; z >= 3; z--) {
                for (int x = 3; x <= 5; x++) {
                    BlockState s = helper.getBlockState(new BlockPos(x, y, z));
                    out.append(s.is(layout().door()) ? 'D' : s.isAir() ? '.' : s.is(Blocks.MOVING_PISTON) ? 'm' : '#');
                }
                out.append(z > 3 ? "|" : "");
            }
        }
        // The hidden side: pistons, wires, the bulb.
        HiddenLayer hidden = HiddenLayer.of(helper.getLevel());
        int[][] probes = {{4, 1, 6}, {4, 2, 6}, {3, 1, 4}, {3, 2, 4}, {4, 3, 6}, {3, 3, 6}, {3, 3, 4}, {6, 3, 5}, {8, 3, 5}};
        for (int[] p : probes) {
            BlockState s = hidden.getBlock(helper.absolutePos(new BlockPos(p[0], p[1], p[2])));
            String v = s.hasProperty(BlockStateProperties.POWER) ? "p" + s.getValue(BlockStateProperties.POWER)
                    : s.hasProperty(BlockStateProperties.EXTENDED) ? (s.getValue(BlockStateProperties.EXTENDED) ? "ext" : "ret")
                    : s.hasProperty(BlockStateProperties.LIT) ? (s.getValue(BlockStateProperties.LIT) ? "lit" : "dark") : "";
            out.append(" H").append(p[0]).append(p[1]).append(p[2]).append('=')
                    .append(BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath()).append(v);
        }
        return out.toString();
    }

    private static ServerPlayer playerAt(GameTestHelper helper, BlockPos rel, float yaw) {
        ServerPlayer player = TestPlayers.join(helper, GameType.SURVIVAL);
        teleport(helper, player, rel, yaw);
        return player;
    }

    private static void teleport(GameTestHelper helper, ServerPlayer player, BlockPos rel, float yaw) {
        Vec3 spot = Vec3.atBottomCenterOf(helper.absolutePos(rel));
        player.teleport(new TeleportTransition(helper.getLevel(), spot, Vec3.ZERO, yaw, 0, TeleportTransition.DO_NOTHING));
    }

    /** Right-clicks a face of a visible block, as the server does for a client's click. */
    private static InteractionResult click(GameTestHelper helper, ServerPlayer player, BlockPos rel, Direction face) {
        BlockPos pos = helper.absolutePos(rel);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos).relative(face, 0.5), face, pos, false);
        return player.gameMode.useItemOn(player, helper.getLevel(), player.getItemInHand(InteractionHand.MAIN_HAND),
                InteractionHand.MAIN_HAND, hit);
    }

    private static void click(GameTestHelper helper, ServerPlayer player, ContraptionLayout.Click c) {
        click(helper, player, c.pos(), c.face());
    }

    /** Nothing redstone in the visible world: the room gives nothing away. */
    private static void assertNothingRedstoneVisible(GameTestHelper helper) {
        for (BlockPos rel : BlockPos.betweenClosed(0, 0, 0, 8, 4, 9)) {
            String id = BuiltInRegistries.BLOCK.getKey(helper.getBlockState(rel).getBlock()).getPath();
            helper.assertFalse(id.contains("redstone") || id.contains("button") || id.contains("repeater")
                    || id.contains("comparator") || id.contains("piston") || id.contains("bulb") || id.contains("lever"),
                    "visible " + id + " at " + rel);
        }
    }

    /**
     * The bookshelf in the corridor opens the door; from inside the room, the bookshelf on the
     * other side of the wall closes it; the corridor one opens it again.
     */
    @GameTest(structure = STRUCTURE, maxTicks = 400)
    public void eitherSecretBookshelfOpensAndClosesTheDoor(GameTestHelper helper) {
        layout().build(helper);
        ContraptionLayout.Click outside = layout().clicks().get(0);
        ContraptionLayout.Click inside = layout().clicks().get(1);
        BlockPos corridor = new BlockPos(outside.pos().getX(), 1, 7);
        BlockPos room = new BlockPos(inside.pos().getX(), 1, 1);
        ServerPlayer player = playerAt(helper, corridor, 180);
        helper.startSequence()
                .thenIdle(5)
                .thenExecute(() -> {
                    helper.assertTrue(closed(helper), "door not closed at start:" + describe(helper));
                    assertNothingRedstoneVisible(helper);
                })
                .thenExecute(() -> click(helper, player, outside))
                .thenWaitUntil(() -> helper.assertTrue(open(helper), "door did not open:" + describe(helper)))
                .thenIdle(30) // the button lets go after 20 ticks; the door stays open
                .thenExecute(() -> {
                    helper.assertTrue(open(helper), "door did not stay open:" + describe(helper));
                    assertNothingRedstoneVisible(helper);
                    teleport(helper, player, room, 0);
                })
                .thenExecute(() -> click(helper, player, inside))
                .thenWaitUntil(() -> helper.assertTrue(closed(helper), "door did not close from inside:" + describe(helper)))
                .thenIdle(30)
                .thenExecute(() -> {
                    helper.assertTrue(closed(helper), "door did not stay closed:" + describe(helper));
                    teleport(helper, player, corridor, 180);
                })
                .thenExecute(() -> click(helper, player, outside))
                .thenWaitUntil(() -> helper.assertTrue(open(helper), "door did not open a second time:" + describe(helper)))
                .thenExecute(() -> TestPlayers.leave(helper, player))
                .thenSucceed();
    }

    /** Any other bookshelf of the wall is just a bookshelf: the ones next to the secret one, on both sides. */
    @GameTest(structure = STRUCTURE, maxTicks = 200)
    public void otherBookshelvesDoNothing(GameTestHelper helper) {
        layout().build(helper);
        ContraptionLayout.Click outside = layout().clicks().get(0);
        ContraptionLayout.Click inside = layout().clicks().get(1);
        ServerPlayer player = playerAt(helper, new BlockPos(outside.pos().getX(), 1, 7), 180);
        ContraptionLayout.Click[] others = {
                new ContraptionLayout.Click(outside.pos().west(), outside.face()),
                new ContraptionLayout.Click(outside.pos().below(), outside.face()),
                new ContraptionLayout.Click(outside.pos().above(), outside.face()),
                new ContraptionLayout.Click(inside.pos().west(), inside.face())};
        var sequence = helper.startSequence().thenIdle(5);
        for (ContraptionLayout.Click other : others) {
            sequence = sequence
                    .thenExecute(() -> {
                        teleport(helper, player, new BlockPos(other.pos().getX(), 1, other.face() == Direction.SOUTH ? 7 : 1),
                                other.face() == Direction.SOUTH ? 180 : 0);
                        click(helper, player, other);
                    })
                    .thenIdle(30) // longer than the door takes to open
                    .thenExecute(() -> helper.assertTrue(closed(helper), "door moved after a click on " + other.pos() + ":" + describe(helper)));
        }
        sequence.thenExecute(() -> TestPlayers.leave(helper, player)).thenSucceed();
    }
}
