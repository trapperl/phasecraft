package dev.phasecraft.test;

import dev.phasecraft.layer.HiddenDimensions;
import dev.phasecraft.layer.HiddenLayer;
import dev.phasecraft.layer.LayerSwitch;
import dev.phasecraft.overlay.InsideView;
import dev.phasecraft.overlay.OverlayPayloads;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;

/**
 * The inside view (InsideView) and the key that goes into the hidden layer (LayerSwitch.Toggle): what the server
 * sends a player who is inside the hidden layer, and who may go in.
 *
 * <p>The scene: a 3x3x3 cube of visible stone. Its 26 outer blocks are surface; the one in the middle is not.</p>
 */
public class InsideViewGameTests {
    private static final BlockPos CENTRE = new BlockPos(3, 3, 3);
    private static final List<CustomPacketPayload.Type<?>> INSIDE_TYPES = List.of(OverlayPayloads.VisibleChunk.TYPE,
            OverlayPayloads.VisibleBlocks.TYPE, OverlayPayloads.VisibleForget.TYPE, OverlayPayloads.VisibleReset.TYPE);

    private static void buildCube(GameTestHelper helper) {
        for (BlockPos pos : BlockPos.betweenClosed(CENTRE.offset(-1, -1, -1), CENTRE.offset(1, 1, 1))) {
            helper.setBlock(pos, Blocks.STONE);
        }
    }

    /** What a client would hold after these payloads, as its GhostLayer does: chunks replaced, blocks set, air removed. */
    private static final class Client {
        final Map<BlockPos, BlockState> blocks = new HashMap<>();
        /** The open faces sent with each block: a bit per Direction ordinal. */
        final Map<BlockPos, Integer> faces = new HashMap<>();
        final List<ChunkPos> chunks = new ArrayList<>();
        int resets;

        void take(List<CustomPacketPayload> payloads) {
            for (CustomPacketPayload payload : payloads) {
                switch (payload) {
                    case OverlayPayloads.VisibleChunk chunk -> {
                        chunks.add(chunk.chunk());
                        blocks.keySet().removeIf(pos -> ChunkPos.pack(pos) == chunk.chunk().pack());
                        apply(chunk.blocks());
                    }
                    case OverlayPayloads.VisibleBlocks changed -> apply(changed.blocks());
                    case OverlayPayloads.VisibleForget forget -> {
                        chunks.remove(forget.chunk());
                        blocks.keySet().removeIf(pos -> ChunkPos.pack(pos) == forget.chunk().pack());
                    }
                    case OverlayPayloads.VisibleReset reset -> {
                        resets++;
                        chunks.clear();
                        blocks.clear();
                    }
                    default -> { }
                }
            }
        }

        private void apply(List<OverlayPayloads.Surface> sent) {
            for (OverlayPayloads.Surface block : sent) {
                if (block.state().isAir()) {
                    blocks.remove(block.pos());
                    faces.remove(block.pos());
                } else {
                    blocks.put(block.pos(), block.state());
                    faces.put(block.pos(), (int) block.faces());
                }
            }
        }

        boolean has(BlockPos pos) {
            return blocks.containsKey(pos);
        }
    }

    private static void moveTo(ServerPlayer player, ServerLevel level, Vec3 spot) {
        player.teleport(new TeleportTransition(level, spot, Vec3.ZERO, 0, 0, TeleportTransition.DO_NOTHING));
    }

    /** The scan of a chunk gives the cube's 26 outer blocks and not its middle; water inside water is not surface. */
    @GameTest(maxTicks = 20)
    public void onlySurfaceBlocksAreScanned(GameTestHelper helper) {
        buildCube(helper);
        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockState water = Blocks.WATER.defaultBlockState();
        helper.assertFalse(InsideView.open(stone, stone), "stone against stone is open");
        helper.assertTrue(InsideView.open(stone, Blocks.AIR.defaultBlockState()), "stone against air is closed");
        helper.assertTrue(InsideView.open(stone, Blocks.GLASS.defaultBlockState()), "stone against glass is closed");
        helper.assertTrue(InsideView.open(stone, water), "stone under water is closed");
        helper.assertFalse(InsideView.open(water, water), "water inside water is open");
        helper.assertTrue(InsideView.open(water, Blocks.AIR.defaultBlockState()), "the top of water is closed");
        // A block on its own, with air all around.
        BlockPos gold = new BlockPos(3, 3, 6);
        helper.setBlock(gold, Blocks.GOLD_BLOCK);
        ServerLevel level = helper.getLevel();
        Map<BlockPos, BlockState> surface = new HashMap<>();
        Map<BlockPos, Integer> faces = new HashMap<>();
        for (BlockPos corner : List.of(new BlockPos(0, 0, 0), new BlockPos(7, 0, 0), new BlockPos(0, 0, 7), new BlockPos(7, 0, 7))) {
            for (OverlayPayloads.Surface block : InsideView.surface(level, level.getChunkAt(helper.absolutePos(corner)))) {
                surface.put(block.pos(), block.state());
                faces.put(block.pos(), (int) block.faces());
            }
        }
        int outer = 0;
        for (BlockPos pos : BlockPos.betweenClosed(CENTRE.offset(-1, -1, -1), CENTRE.offset(1, 1, 1))) {
            BlockPos at = helper.absolutePos(pos);
            if (pos.equals(CENTRE)) {
                helper.assertFalse(surface.containsKey(at), "the cube's middle block was scanned as surface");
                helper.assertTrue(InsideView.shownAt(level, at).state().isAir(), "the cube's middle block is shown");
            } else {
                helper.assertTrue(surface.get(at) == Blocks.STONE.defaultBlockState(), "outer block missing at " + pos.toShortString());
                helper.assertTrue(InsideView.shownAt(level, at).state().is(Blocks.STONE), "outer block not shown at " + pos.toShortString());
                helper.assertValueEqual((int) InsideView.shownAt(level, at).faces(), faces.get(at), "faces from the scan and for a change at " + pos.toShortString());
                outer++;
            }
        }
        helper.assertValueEqual(outer, 26, "outer blocks");
        // Only the open faces are drawn: one in the middle of a side, two on an edge, three at a corner, all six alone.
        helper.assertValueEqual(faces.get(helper.absolutePos(CENTRE.above())), 1 << Direction.UP.ordinal(), "faces of the top middle block");
        helper.assertValueEqual(faces.get(helper.absolutePos(CENTRE.above().east())),
                1 << Direction.UP.ordinal() | 1 << Direction.EAST.ordinal(), "faces of a top edge block");
        helper.assertValueEqual(Integer.bitCount(faces.get(helper.absolutePos(CENTRE.offset(1, 1, 1)))), 3, "faces of a corner block");
        helper.assertValueEqual(faces.get(helper.absolutePos(gold)), 0b111111, "faces of a block with air around it");
        for (BlockPos scanned : surface.keySet()) {
            helper.assertFalse(level.getBlockState(scanned).isAir(), "air was scanned at " + scanned.toShortString());
        }
        helper.succeed();
    }

    /**
     * A Creative player with PhaseCraft goes in with the key's payload and is sent the visible world near them:
     * the cube's outside, not its middle, nothing from further than the overlay's radius; a change arrives
     * (breaking an outer block uncovers the middle); back out, the client is reset and nothing more is sent.
     */
    @GameTest(maxTicks = 600)
    public void creativePlayerInsideIsSentTheNearbySurface(GameTestHelper helper) {
        buildCube(helper);
        ServerLevel visible = helper.getLevel();
        BlockPos centre = helper.absolutePos(CENTRE);
        BlockPos face = centre.above();
        ServerPlayer player = TestPlayers.joinWithMod(helper, GameType.CREATIVE, INSIDE_TYPES);
        Vec3 spot = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(1, 5, 1)));
        moveTo(player, visible, spot);
        ChunkPos home = chunkOf(BlockPos.containing(spot));
        // Six chunks away, beyond the radius of four, high up where no test builds.
        BlockPos far = new BlockPos(home.getMinBlockX() + 6 * 16 + 8, visible.getMaxY() - 8, home.getMinBlockZ() + 8);
        Client client = new Client();
        int all = (2 * InsideView.RADIUS_CHUNKS + 1) * (2 * InsideView.RADIUS_CHUNKS + 1);
        helper.startSequence()
                .thenIdle(5)
                .thenExecute(() -> {
                    client.take(TestPlayers.sent(player));
                    helper.assertTrue(client.chunks.isEmpty() && client.blocks.isEmpty(),
                            "sent the visible world while in the visible world: " + client.chunks);
                    helper.assertValueEqual(InsideView.chunksSentTo(player), 0, "chunks held while in the visible world");
                    TestPlayers.sendToServer(player, new LayerSwitch.Toggle());
                    helper.assertValueEqual(player.level().dimension(), HiddenDimensions.HIDDEN_OVERWORLD, "dimension after the key");
                    helper.assertTrue(player.position().distanceTo(spot) < 0.01, "the key moved the player: " + player.position());
                    visible.setBlockAndUpdate(far, Blocks.GOLD_BLOCK.defaultBlockState());
                })
                .thenWaitUntil(() -> helper.assertValueEqual(InsideView.chunksSentTo(player), all, "chunks sent"))
                .thenExecute(() -> {
                    client.take(TestPlayers.sent(player));
                    helper.assertValueEqual(client.chunks.size(), all, "chunks the client was sent");
                    for (ChunkPos chunk : client.chunks) {
                        helper.assertTrue(Math.abs(chunk.x() - home.x()) <= InsideView.RADIUS_CHUNKS
                                && Math.abs(chunk.z() - home.z()) <= InsideView.RADIUS_CHUNKS, "a chunk out of range was sent: " + chunk);
                    }
                    for (BlockPos pos : BlockPos.betweenClosed(centre.offset(-1, -1, -1), centre.offset(1, 1, 1))) {
                        helper.assertTrue(client.has(pos) != pos.equals(centre),
                                (pos.equals(centre) ? "the middle of the cube was sent" : "an outer block was not sent") + ": " + pos.toShortString());
                    }
                    helper.assertFalse(client.has(far), "a block six chunks away was sent");
                    for (BlockPos pos : client.blocks.keySet()) {
                        helper.assertTrue(Math.abs(chunkOf(pos).x() - home.x()) <= InsideView.RADIUS_CHUNKS
                                && Math.abs(chunkOf(pos).z() - home.z()) <= InsideView.RADIUS_CHUNKS,
                                "a block out of range was sent: " + pos.toShortString());
                    }
                    // Nothing new to send: no chunk is scanned again while the player stands still.
                    helper.assertValueEqual(InsideView.scansFor(player), all, "chunk scans for the first view");
                })
                .thenIdle(10)
                .thenExecute(() -> {
                    helper.assertValueEqual(InsideView.scansFor(player), all, "chunk scans while nothing changed");
                    helper.assertTrue(TestPlayers.sent(player).stream().noneMatch(payload -> payload instanceof OverlayPayloads.VisibleChunk),
                            "chunks sent again while nothing changed");
                    // Breaking the top middle block uncovers the cube's middle.
                    visible.setBlockAndUpdate(face, Blocks.AIR.defaultBlockState());
                })
                .thenIdle(2)
                .thenExecute(() -> {
                    List<CustomPacketPayload> sent = TestPlayers.sent(player);
                    client.take(sent);
                    helper.assertTrue(sent.stream().noneMatch(payload -> payload instanceof OverlayPayloads.VisibleChunk),
                            "a block change sent a whole chunk again");
                    helper.assertValueEqual(InsideView.scansFor(player), all, "chunk scans for a block change");
                    helper.assertFalse(client.has(face), "the broken block is still held");
                    helper.assertTrue(client.has(centre), "the uncovered middle block was not sent");
                    helper.assertValueEqual(client.faces.get(centre), 1 << Direction.UP.ordinal(), "faces of the uncovered middle block");
                    // Its neighbours in the top layer now show a side into the hole as well.
                    helper.assertValueEqual(client.faces.get(face.east()), 1 << Direction.UP.ordinal() | 1 << Direction.EAST.ordinal()
                            | 1 << Direction.WEST.ordinal(), "faces of the block beside the hole");
                    // Put back: the middle is covered again.
                    visible.setBlockAndUpdate(face, Blocks.STONE.defaultBlockState());
                })
                .thenIdle(2)
                .thenExecute(() -> {
                    client.take(TestPlayers.sent(player));
                    helper.assertTrue(client.has(face), "the block put back was not sent");
                    helper.assertFalse(client.has(centre), "the covered middle block is still held");
                    // The key again: back out.
                    TestPlayers.sendToServer(player, new LayerSwitch.Toggle());
                    helper.assertValueEqual(player.level().dimension(), Level.OVERWORLD, "dimension after the key again");
                    helper.assertTrue(player.position().distanceTo(spot) < 0.01, "the key moved the player: " + player.position());
                })
                .thenIdle(2)
                .thenExecute(() -> {
                    client.take(TestPlayers.sent(player));
                    helper.assertValueEqual(client.resets, 1, "resets on leaving the hidden layer");
                    helper.assertValueEqual(InsideView.chunksSentTo(player), 0, "chunks held after leaving");
                    visible.setBlockAndUpdate(face, Blocks.AIR.defaultBlockState());
                })
                .thenIdle(5)
                .thenExecute(() -> {
                    client.take(TestPlayers.sent(player));
                    helper.assertTrue(client.blocks.isEmpty() && client.chunks.isEmpty(),
                            "sent the visible world after leaving the hidden layer: " + client.blocks.size() + " blocks");
                    visible.setBlockAndUpdate(far, Blocks.AIR.defaultBlockState());
                    TestPlayers.leave(helper, player);
                })
                .thenSucceed();
    }

    private static ChunkPos chunkOf(BlockPos pos) {
        return new ChunkPos(pos.getX() >> 4, pos.getZ() >> 4);
    }

    /**
     * Survival and Adventure players are sent nothing of the inside view, even for the moment they are in the hidden
     * layer before the guard moves them out; a Spectator is.
     */
    @GameTest(maxTicks = 200)
    public void onlyCreativeAndSpectatorPlayersAreSentIt(GameTestHelper helper) {
        buildCube(helper);
        ServerLevel hidden = HiddenLayer.of(helper.getLevel()).level();
        Vec3 spot = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(1, 5, 1)));
        ServerPlayer survival = TestPlayers.joinWithMod(helper, GameType.SURVIVAL, INSIDE_TYPES);
        ServerPlayer adventure = TestPlayers.joinWithMod(helper, GameType.ADVENTURE, INSIDE_TYPES);
        ServerPlayer spectator = TestPlayers.joinWithMod(helper, GameType.SPECTATOR, INSIDE_TYPES);
        for (ServerPlayer player : List.of(survival, adventure, spectator)) {
            moveTo(player, hidden, spot);
        }
        helper.assertFalse(InsideView.shows(survival), "a Survival player in the hidden layer is shown the inside view");
        helper.assertFalse(InsideView.shows(adventure), "an Adventure player in the hidden layer is shown the inside view");
        helper.assertTrue(InsideView.shows(spectator), "a Spectator in the hidden layer is not shown the inside view");
        Client seen = new Client();
        helper.startSequence()
                .thenWaitUntil(() -> {
                    seen.take(TestPlayers.sent(spectator));
                    helper.assertTrue(seen.has(helper.absolutePos(CENTRE.above())), "the Spectator was not sent the cube");
                })
                .thenExecute(() -> {
                    for (ServerPlayer player : List.of(survival, adventure)) {
                        Client client = new Client();
                        List<CustomPacketPayload> sent = TestPlayers.sent(player);
                        client.take(sent);
                        helper.assertTrue(client.blocks.isEmpty() && client.chunks.isEmpty() && sent.stream().noneMatch(
                                payload -> payload instanceof OverlayPayloads.VisibleChunk || payload instanceof OverlayPayloads.VisibleBlocks),
                                player.gameMode() + " player was sent the visible world: " + client.chunks.size() + " chunks");
                        helper.assertValueEqual(InsideView.chunksSentTo(player), 0, "chunks held by the " + player.gameMode() + " player");
                        helper.assertValueEqual(player.level().dimension(), Level.OVERWORLD, "where the guard put the " + player.gameMode() + " player");
                    }
                    for (ServerPlayer player : List.of(survival, adventure, spectator)) {
                        TestPlayers.leave(helper, player);
                    }
                })
                .thenSucceed();
    }

    /** The key's payload: Creative and Spectator go in and come back at the same spot; Survival and Adventure are refused. */
    @GameTest(maxTicks = 40)
    public void theKeyLetsOnlyCreativeAndSpectatorIn(GameTestHelper helper) {
        Vec3 spot = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(2, 4, 2)));
        for (GameType mode : GameType.values()) {
            ServerPlayer player = TestPlayers.join(helper, mode);
            try {
                moveTo(player, helper.getLevel(), spot);
                boolean allowed = mode == GameType.CREATIVE || mode == GameType.SPECTATOR;
                helper.assertValueEqual(LayerSwitch.mayEnter(player), allowed, mode + " may enter");
                TestPlayers.sendToServer(player, new LayerSwitch.Toggle());
                helper.assertValueEqual(player.level().dimension(), allowed ? HiddenDimensions.HIDDEN_OVERWORLD : Level.OVERWORLD,
                        "dimension of the " + mode + " player after the key");
                helper.assertTrue(player.position().distanceTo(spot) < 0.01, mode + " player moved: " + player.position());
                helper.assertValueEqual(LayerSwitch.toggle(player), allowed ? LayerSwitch.Result.LEFT : LayerSwitch.Result.WRONG_GAME_MODE,
                        "the key again, " + mode);
                helper.assertValueEqual(player.level().dimension(), Level.OVERWORLD, "dimension of the " + mode + " player in the end");
                helper.assertTrue(player.position().distanceTo(spot) < 0.01, mode + " player moved: " + player.position());
            } finally {
                TestPlayers.leave(helper, player);
            }
        }
        helper.succeed();
    }
}
