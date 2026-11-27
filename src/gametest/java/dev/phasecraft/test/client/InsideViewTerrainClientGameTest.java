package dev.phasecraft.test.client;

import static dev.phasecraft.test.client.OverlayClientGameTest.assertTrue;
import static dev.phasecraft.test.client.OverlayClientGameTest.waitUntil;

import dev.phasecraft.client.GhostRendererAccess;
import dev.phasecraft.client.PhaseCraftClient;
import dev.phasecraft.layer.HiddenDimensions;
import dev.phasecraft.overlay.InsideView;
import dev.phasecraft.overlay.OverlayPayloads;
import io.netty.buffer.Unpooled;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.Deflater;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The inside view on ordinary terrain (a default world, not a flat one): what a chunk costs to send and to draw.
 * The numbers go to the log ("Inside view on terrain: ...") and are the ones in docs/ARCHITECTURE.md; the test
 * holds them inside loose bounds, so a change that sends solid ground again, or nothing, fails. A screenshot of
 * the terrain from inside is kept (inside_terrain_*.png).
 */
public class InsideViewTerrainClientGameTest implements FabricClientGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("phasecraft-test");

    private record Cost(int ghosts, int blocks, int bytes, int deflated, double scanMillis) {}

    @Override
    public void runTest(ClientGameTestContext context) {
        // Not the test worlds' usual settings: those make a flat world.
        try (TestSingleplayerContext world = context.worldBuilder().setUseConsistentSettings(false).adjustSettings(state -> {
            state.setWorldType(state.getNormalPresetList().stream()
                    .filter(entry -> entry.preset().is(WorldPresets.NORMAL)).findFirst().orElseThrow());
            state.setSeed("phasecraft");
            state.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
        }).create()) {
            TestServerContext server = world.getServer();
            server.runCommand("gamemode creative @a");
            server.runCommand("time set noon");
            server.runCommand("gamerule advance_time false");
            server.runCommand("gamerule advance_weather false");
            server.runCommand("gamerule spawn_mobs false");
            context.runOnClient(minecraft -> {
                PhaseCraftClient.settings().setEnabled(true);
                PhaseCraftClient.settings().setOpacity(0.45F);
            });
            world.getConnection().waitForChunksRender();
            assertTrue(server.computeOnServer(minecraft ->
                    !(minecraft.overworld().getChunkSource().getGenerator() instanceof FlatLevelSource)), "this is a flat world");
            // Up a little, looking down at the land.
            server.runOnServer(minecraft -> {
                ServerPlayer player = minecraft.getPlayerList().getPlayers().getFirst();
                player.getAbilities().flying = true;
                player.onUpdateAbilities();
                player.teleportTo(player.level(), player.getX(), player.getY() + 12, player.getZ(), java.util.Set.of(), 30, 35, false);
            });
            context.waitTicks(20);
            world.getConnection().waitForChunksRender();
            if (!context.computeOnClient(minecraft -> minecraft.gui.hud.isHidden())) {
                context.getInput().pressKey(options -> options.keyToggleGui);
            }
            context.waitTicks(5);
            InsideViewClientGameTest.screenshot(context, "inside_terrain_before");

            context.getInput().pressKey(PhaseCraftClient.TOGGLE_LAYER);
            waitUntil(context, "K takes the player into the hidden layer", minecraft -> minecraft.level != null
                    && minecraft.level.dimension() == HiddenDimensions.HIDDEN_OVERWORLD && minecraft.player != null);
            int all = (2 * InsideView.RADIUS_CHUNKS + 1) * (2 * InsideView.RADIUS_CHUNKS + 1);
            long started = System.nanoTime();
            server.waitFor(minecraft -> InsideView.chunksSentTo(minecraft.getPlayerList().getPlayers().getFirst()) == all, 1200);
            double seconds = (System.nanoTime() - started) / 1e9;
            context.waitTicks(20);

            // What each of those chunks costs, measured on the server as it sends them.
            List<Cost> costs = server.computeOnServer(minecraft -> {
                ServerPlayer player = minecraft.getPlayerList().getPlayers().getFirst();
                ServerLevel visible = minecraft.overworld();
                ChunkPos centre = player.chunkPosition();
                List<Cost> list = new ArrayList<>();
                for (int x = centre.x() - InsideView.RADIUS_CHUNKS; x <= centre.x() + InsideView.RADIUS_CHUNKS; x++) {
                    for (int z = centre.z() - InsideView.RADIUS_CHUNKS; z <= centre.z() + InsideView.RADIUS_CHUNKS; z++) {
                        LevelChunk chunk = visible.getChunk(x, z);
                        InsideView.surface(visible, chunk); // once to warm up
                        long t0 = System.nanoTime();
                        List<OverlayPayloads.Surface> ghosts = InsideView.surface(visible, chunk);
                        double millis = (System.nanoTime() - t0) / 1e6;
                        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), minecraft.registryAccess());
                        OverlayPayloads.VisibleChunk.STREAM_CODEC.encode(buffer, new OverlayPayloads.VisibleChunk(chunk.getPos(), ghosts));
                        byte[] raw = new byte[buffer.readableBytes()];
                        buffer.readBytes(raw);
                        Deflater deflater = new Deflater();
                        deflater.setInput(raw);
                        deflater.finish();
                        byte[] out = new byte[raw.length + 64];
                        int deflated = deflater.deflate(out);
                        deflater.end();
                        int blocks = 0;
                        for (LevelChunkSection section : chunk.getSections()) {
                            if (!section.hasOnlyAir()) {
                                for (int i = 0; i < 4096; i++) {
                                    if (!section.getBlockState(i & 15, (i >> 8) & 15, (i >> 4) & 15).isAir()) {
                                        blocks++;
                                    }
                                }
                            }
                        }
                        list.add(new Cost(ghosts.size(), blocks, raw.length, deflated, millis));
                    }
                }
                return list;
            });
            List<Integer> ghosts = new ArrayList<>(costs.stream().map(Cost::ghosts).toList());
            Collections.sort(ghosts);
            double meanGhosts = costs.stream().mapToInt(Cost::ghosts).average().orElseThrow();
            double meanBlocks = costs.stream().mapToInt(Cost::blocks).average().orElseThrow();
            double meanBytes = costs.stream().mapToInt(Cost::bytes).average().orElseThrow();
            int maxBytes = costs.stream().mapToInt(Cost::bytes).max().orElseThrow();
            double meanDeflated = costs.stream().mapToInt(Cost::deflated).average().orElseThrow();
            long totalBytes = costs.stream().mapToLong(Cost::bytes).sum();
            long totalDeflated = costs.stream().mapToLong(Cost::deflated).sum();
            double meanScan = costs.stream().mapToDouble(Cost::scanMillis).average().orElseThrow();
            double maxScan = costs.stream().mapToDouble(Cost::scanMillis).max().orElseThrow();

            // What the client holds and draws.
            waitUntil(context, "the client holds the terrain", minecraft -> PhaseCraftClient.visibleLayer().size() > 1000);
            context.waitTicks(40);
            int held = context.computeOnClient(minecraft -> PhaseCraftClient.visibleLayer().size());
            int vertices = context.computeOnClient(minecraft -> GhostRendererAccess.insideVertices());
            double replay = 0;
            int frames = 20;
            for (int i = 0; i < frames; i++) {
                context.waitTick();
                replay += context.computeOnClient(minecraft -> GhostRendererAccess.insideNanos()) / 1e6;
            }
            replay /= frames;
            int fps = context.computeOnClient(minecraft -> minecraft.getFps());
            LOGGER.info(String.format("Inside view on terrain: %d chunks in %.1f s; surface blocks per chunk min %d, median %d, mean %.0f, max %d"
                    + " (of %.0f blocks other than air: %.1f%%); payload per chunk mean %.0f bytes, max %d, deflated mean %.0f;"
                    + " all %d chunks %d bytes, deflated %d; scan per chunk mean %.2f ms, max %.2f ms;"
                    + " client holds %d blocks, draws %d vertices within %d blocks, replay %.2f ms a frame, %d fps",
                    all, seconds, ghosts.getFirst(), ghosts.get(ghosts.size() / 2), meanGhosts, ghosts.getLast(), meanBlocks,
                    100 * meanGhosts / meanBlocks, meanBytes, maxBytes, meanDeflated, all, totalBytes, totalDeflated, meanScan, maxScan,
                    held, vertices, 48, replay, fps));

            assertTrue(meanGhosts > 256, "less than the top of the land was sent: " + meanGhosts + " blocks a chunk");
            assertTrue(meanGhosts < meanBlocks * 0.15, "solid ground was sent: " + meanGhosts + " of " + meanBlocks + " blocks a chunk");
            assertTrue(held > 0.9 * costs.stream().mapToInt(Cost::ghosts).sum(), "the client holds " + held + " of what was sent");
            assertTrue(vertices > 0, "nothing of the terrain is drawn inside");

            BufferedImage shown = InsideViewClientGameTest.screenshot(context, "inside_terrain_shown");
            context.getInput().pressKey(PhaseCraftClient.TOGGLE_OVERLAY);
            context.waitTicks(3);
            BufferedImage off = InsideViewClientGameTest.screenshot(context, "inside_terrain_off");
            context.getInput().pressKey(PhaseCraftClient.TOGGLE_OVERLAY);
            // Looking down at the land: the lower half of the screen shows it, faintly, and nothing with H off.
            InsideViewClientGameTest.Box land = new InsideViewClientGameTest.Box(shown.getWidth() / 2, shown.getHeight() * 3 / 4, shown.getHeight() / 8);
            double there = land.distance(shown, off);
            assertTrue(there > 8, "the terrain is not drawn inside the hidden layer: differs by " + there);

            context.getInput().pressKey(PhaseCraftClient.TOGGLE_LAYER);
            waitUntil(context, "K brings the player back", minecraft -> minecraft.level != null
                    && minecraft.level.dimension() == net.minecraft.world.level.Level.OVERWORLD && minecraft.player != null);
            waitUntil(context, "the view is dropped outside", minecraft -> PhaseCraftClient.visibleLayer().isEmpty());
        }
    }
}
