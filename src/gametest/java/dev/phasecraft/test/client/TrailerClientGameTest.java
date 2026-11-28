package dev.phasecraft.test.client;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.phasecraft.client.PhaseCraftClient;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldBuilder;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.InactivityFpsLimit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.tags.BlockTags;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Renders the trailer's frames (tools/trailer.py writes the script, then turns the frames into a video). Runs only
 * when PHASECRAFT_TRAILER names a script; tools/trailer.py starts it in a run of its own (runClientGameTestTrailer:
 * Iris and the trailer's shader pack, this test alone).
 *
 * <p>The world is frozen ({@code /tick freeze}) and stepped by the script, so the video runs at the game's own
 * speed however long a frame takes to draw. A game tick is drawn as several frames (three for 60 frames a second),
 * each at its own place between the tick before and this one ({@code delta}), so carts and piston arms move every
 * frame; the camera is the player's, put in place on the client for every frame, with its own field of view
 * (TrailerFovMixin), and the shader pack's clock is the video's (TrailerIrisClockMixin). The test draws each
 * frame of the video itself; the game's own frames, one a client tick while the test waits, draw no world
 * meanwhile (TrailerSkipLevelMixin).</p>
 *
 * <p>The script, a line each (positions are the camera's eye):</p>
 * <ul>
 *   <li>{@code world flat} or {@code world normal <seed>}: first line, the world (flat: the test worlds');</li>
 *   <li>{@code view <chunks>}: the render distance;</li>
 *   <li>{@code size <width> <height>}: the frames' size;</li>
 *   <li>{@code commands <file>}: a file of server commands, a line each, run now;</li>
 *   <li>{@code run <command>}: a server command, now;</li>
 *   <li>{@code wait <ticks>}: the world that many game ticks on, no frame;</li>
 *   <li>{@code goto x y z yaw pitch}: the camera there, every chunk around drawn, no frame;</li>
 *   <li>{@code overlay on|off}, {@code opacity <0..1>}: the hidden layer's overlay;</li>
 *   <li>{@code tick}: the world a game tick on;</li>
 *   <li>{@code frame <folder> <number> <delta> <clock> x y z yaw pitch fov [follow|look <entity type> dx dy dz]}:
 *       a frame, {@code <folder>/NNNNN.png} beside the script: the world drawn {@code delta} (0..1] of the way from
 *       the tick before to this one, the shader pack's clock at {@code clock} seconds, the camera there; with
 *       {@code follow} the position is added to that of the nearest entity of the type (a cart), with {@code look}
 *       the camera turns to that entity (plus dx dy dz) from where it is;</li>
 *   <li>{@code load x0 z0 x1 z1}: the chunks there, of the visible and the hidden overworld, loaded now (a
 *       forceload asks for them and goes on: a setblock just after it would miss);</li>
 *   <li>{@code pad x0 z0 x1 z1 y skirt}: level ground at height y for a build, sloped into the land around;</li>
 *   <li>{@code heights x0 z0 x1 z1 <file>}: the land's heights there (a line of surface heights, of heights
 *       without leaves, of sea floor heights and of top blocks for every z), for choosing where to build.</li>
 * </ul>
 */
public class TrailerClientGameTest implements FabricClientGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("phasecraft-test");

    private int width = 1920;
    private int height = 1080;
    private long drawNanos;
    private int draws;

    @Override
    public void runTest(ClientGameTestContext context) {
        IrisRun.prepare(context);
        String script = System.getenv("PHASECRAFT_TRAILER");
        if (script == null || script.isBlank()) {
            return;
        }
        Path folder = Path.of(script).toAbsolutePath().getParent();
        List<String> lines = new ArrayList<>(readLines(Path.of(script)));
        TestWorldBuilder builder = context.worldBuilder();
        if (!lines.isEmpty() && lines.getFirst().startsWith("world ")) {
            String[] words = lines.removeFirst().split(" ");
            if (words[1].equals("normal")) {
                // Not the test worlds' usual settings: those make a flat world.
                builder = builder.setUseConsistentSettings(false).adjustSettings(state -> {
                    state.setWorldType(state.getNormalPresetList().stream()
                            .filter(entry -> entry.preset().is(WorldPresets.NORMAL)).findFirst().orElseThrow());
                    state.setSeed(words[2]);
                    state.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
                    state.setAllowCommands(true);
                });
            }
        }
        try (TestSingleplayerContext world = builder.create()) {
            TestServerContext server = world.getServer();
            server.runCommand("gamemode spectator @a");
            server.runCommand("gamerule advance_time false");
            server.runCommand("gamerule advance_weather false");
            server.runCommand("gamerule spawn_mobs false");
            server.runCommand("gamerule spawn_wandering_traders false");
            server.runCommand("gamerule spawn_patrols false");
            server.runCommand("time set 6000");
            context.waitFor(TrailerClientGameTest::landArrived, 20 * 60 * 20);
            context.runOnClient(minecraft -> {
                PhaseCraftClient.settings().setEnabled(false);
                PhaseCraftClient.settings().setOpacity(0.45F);
                // The test waits in client ticks, one a frame: a high frame rate limit, and none for "nobody is
                // at the keyboard" (10 frames a second after ten minutes) either.
                minecraft.options.framerateLimit().set(120);
                minecraft.options.enableVsync().set(false);
                minecraft.options.inactivityFpsLimit().set(InactivityFpsLimit.MINIMIZED);
            });
            if (!context.computeOnClient(minecraft -> minecraft.gui.hud.isHidden())) {
                context.getInput().pressKey(options -> options.keyToggleGui);
            }
            server.runCommand("gamerule send_command_feedback false");     // thousands of lines of chat otherwise
            server.runCommand("tick freeze");
            context.waitTicks(2);
            TrailerState.skipLevel = true;
            long started = System.nanoTime();
            int frames = 0;
            try {
                for (String line : lines) {
                    String[] words = line.split(" ", 2);
                    String rest = words.length > 1 ? words[1] : "";
                    switch (words[0]) {
                        case "view" -> {
                            int chunks = Integer.parseInt(rest);
                            context.runOnClient(minecraft -> {
                                minecraft.options.renderDistance().set(chunks);
                                minecraft.options.broadcastOptions();      // the server sends what the client says it wants
                            });
                            context.waitTicks(5);
                        }
                        case "size" -> {
                            String[] size = rest.split(" ");
                            this.width = Integer.parseInt(size[0]);
                            this.height = Integer.parseInt(size[1]);
                            // The window itself, once: a screenshot of another size than the window's makes the
                            // game (and the shader pack) set everything up again, twice a frame.
                            context.runOnClient(minecraft -> {
                                minecraft.getWindow().setWidth(this.width);
                                minecraft.getWindow().setHeight(this.height);
                                minecraft.gameRenderer.resize(this.width, this.height);
                            });
                            context.waitTicks(2);
                        }
                        case "commands" -> {
                            readLines(folder.resolve(rest)).forEach(server::runCommand);
                            context.runOnClient(minecraft -> minecraft.gui.hud.getChat().clearMessages(false));
                        }
                        case "run" -> server.runCommand(rest);
                        case "wait" -> {
                            server.runCommand("tick step " + rest);
                            context.waitTicks(Integer.parseInt(rest) + 2);
                            context.runOnClient(minecraft -> minecraft.gui.hud.getChat().clearMessages(false));
                        }
                        case "goto" -> {
                            double[] v = numbers(rest.split(" "), 0, 5);
                            TrailerState.fov = 0;
                            TrailerState.skipLevel = false;
                            double feet = v[1] - context.computeOnClient(minecraft -> (double) minecraft.player.getEyeHeight());
                            // Unfrozen while it travels: a frozen server sends no new land.
                            server.runCommand("tick unfreeze");
                            server.runCommand(String.format("tp @a %.3f %.3f %.3f %.3f %.3f", v[0], feet, v[2], v[3], v[4]));
                            context.waitTicks(3);
                            // New land takes its time to be made: no test's usual patience.
                            long from = System.nanoTime();
                            context.waitFor(TrailerClientGameTest::landArrived, 20 * 60 * 20);
                            long downloaded = System.nanoTime();
                            world.getConnection().waitForChunksRender(false, 20 * 60 * 20);
                            context.waitTicks(2);
                            world.getConnection().waitForChunksRender(false, 20 * 60 * 20);
                            LOGGER.info("Trailer: at {}: land in {} s, drawn in {} s", rest, (downloaded - from) / 1_000_000_000L,
                                    (System.nanoTime() - downloaded) / 1_000_000_000L);
                            server.runCommand("tick freeze");
                            context.waitTicks(2);
                            context.runOnClient(minecraft -> minecraft.gui.hud.getChat().clearMessages(false));
                            TrailerState.skipLevel = true;
                        }
                        case "overlay" -> {
                            boolean on = rest.equals("on");
                            context.runOnClient(minecraft -> PhaseCraftClient.settings().setEnabled(on));
                            context.waitTicks(3);
                        }
                        case "opacity" -> {
                            float opacity = Float.parseFloat(rest);
                            context.runOnClient(minecraft -> PhaseCraftClient.settings().setOpacity(opacity));
                        }
                        case "tick" -> {
                            server.runCommand("tick step 1");
                            context.waitTicks(3);
                        }
                        case "frame" -> {
                            frame(context, folder, rest.split(" "));
                            if (++frames % 100 == 0) {
                                LOGGER.info("Trailer: {} frames, {} s a frame; drawn {} times, {} s each", frames,
                                        String.format("%.2f", (System.nanoTime() - started) / 1e9 / frames), this.draws,
                                        String.format("%.2f", this.drawNanos / 1e9 / this.draws));
                            }
                        }
                        case "heights" -> heights(server, folder, rest.split(" "));
                        case "pad" -> pad(server, rest.split(" "));
                        case "load" -> {
                            int[] box = java.util.Arrays.stream(rest.split(" ")).mapToInt(Integer::parseInt).toArray();
                            server.runOnServer(minecraft -> {
                                for (ServerLevel level : minecraft.getAllLevels()) {
                                    String name = level.dimension().identifier().toString();
                                    if (name.equals("minecraft:overworld") || name.equals("phasecraft:hidden_overworld")) {
                                        for (int cz = box[1] >> 4; cz <= box[3] >> 4; cz++) {
                                            for (int cx = box[0] >> 4; cx <= box[2] >> 4; cx++) {
                                                level.getChunk(cx, cz);
                                            }
                                        }
                                    }
                                }
                            });
                        }
                        default -> throw new IllegalArgumentException("trailer script: " + line);
                    }
                }
            } finally {
                TrailerState.skipLevel = false;
                TrailerState.fov = 0;
                TrailerState.clock = -1;
            }
            LOGGER.info("Trailer: {} frames in {} s; drawn {} times, {} s each", frames, (System.nanoTime() - started) / 1_000_000_000L,
                    this.draws, String.format("%.2f", this.drawNanos / 1e9 / Math.max(1, this.draws)));
            server.runCommand("tick unfreeze");
        }
    }

    private void frame(ClientGameTestContext context, Path folder, String[] words) {
        float delta = Float.parseFloat(words[2]);
        double[] v = numbers(words, 4, 6);
        String mode = words.length > 10 ? words[10] : "";
        String type = words.length > 11 ? words[11] : "";
        double[] offset = words.length > 12 ? numbers(words, 12, 3) : new double[3];
        TrailerState.clock = Float.parseFloat(words[3]);
        TrailerState.fov = (float) v[5];
        Path file = folder.resolve(words[0]).resolve(String.format("%05d.png", Integer.parseInt(words[1])));
        DeltaTracker tracker = new DeltaTracker() {
            @Override
            public float getGameTimeDeltaTicks() {
                return delta;
            }

            @Override
            public float getGameTimeDeltaPartialTick(boolean ignoreFrozenGame) {
                return delta;
            }

            @Override
            public float getRealtimeDeltaTicks() {
                return delta;
            }
        };
        // The game's own frames draw no world while the trailer is rendered (TrailerSkipLevelMixin): a frame of
        // the video is drawn here, once. Drawing it asks for the parts of the land that came into view; when
        // some were still being built, it is drawn again a tick later.
        for (int attempt = 0; ; attempt++) {
            boolean last = attempt >= 40;
            long start = System.nanoTime();
            CompletableFuture<Void> saved = context.computeOnClient(minecraft -> {
                place(minecraft, v, delta, mode, type, offset);
                TrailerState.skipLevel = false;
                try {
                    minecraft.gameRenderer.update(tracker);
                    minecraft.gameRenderer.extract(tracker, true);
                    minecraft.gameRenderer.render();
                } finally {
                    TrailerState.skipLevel = true;
                }
                RenderSystem.getDevice().createCommandEncoder().submit();
                if (!last && !minecraft.levelRenderer.hasRenderedAllSections()) {
                    return null;
                }
                CompletableFuture<Void> future = new CompletableFuture<>();
                Screenshot.takeScreenshot(minecraft.gameRenderer.mainRenderTarget(), image -> {
                    try (image) {
                        Files.createDirectories(file.getParent());
                        image.writeToFile(file);
                        future.complete(null);
                    } catch (Throwable e) {
                        future.completeExceptionally(e);
                    }
                });
                return future;
            });
            this.drawNanos += System.nanoTime() - start;
            this.draws++;
            context.waitTick();
            if (saved != null) {
                while (!saved.isDone()) {
                    context.waitTick();
                }
                saved.join();
                return;
            }
        }
    }

    /**
     * Every chunk the server sends a player there is here. (Fabric's own wait asks for the whole square of the
     * render distance, but the server leaves the corners out: past a distance of 6 it would wait for ever.)
     */
    private static boolean landArrived(Minecraft minecraft) {
        int distance = minecraft.options.getEffectiveRenderDistance();
        ChunkPos centre = minecraft.player.chunkPosition();
        for (int dz = -distance; dz <= distance; dz++) {
            for (int dx = -distance; dx <= distance; dx++) {
                if (ChunkTrackingView.isInViewDistance(centre.x(), centre.z(), distance - 1, centre.x() + dx, centre.z() + dz)
                        && minecraft.level.getChunk(centre.x() + dx, centre.z() + dz, ChunkStatus.FULL, false) == null) {
                    return false;
                }
            }
        }
        return true;
    }

    /** The camera (the player's eye) at v: x y z yaw pitch. */
    private static void place(Minecraft minecraft, double[] v, float delta, String mode, String type, double[] offset) {
        LocalPlayer player = minecraft.player;
        double x = v[0];
        double y = v[1];
        double z = v[2];
        float yaw = (float) v[3];
        float pitch = (float) v[4];
        if (!mode.isEmpty()) {
            Entity target = null;
            for (Entity entity : minecraft.level.entitiesForRendering()) {
                if (BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString().equals(type)
                        && (target == null || entity.distanceToSqr(player) < target.distanceToSqr(player))) {
                    target = entity;
                }
            }
            if (target != null) {
                Vec3 at = target.getPosition(delta);
                if (mode.equals("follow")) {
                    x += at.x;
                    y += at.y;
                    z += at.z;
                } else {
                    double dx = at.x + offset[0] - x;
                    double dy = at.y + offset[1] - y;
                    double dz = at.z + offset[2] - z;
                    yaw = (float) (Mth.atan2(dz, dx) * 180 / Math.PI) - 90;
                    pitch = (float) -(Mth.atan2(dy, Math.sqrt(dx * dx + dz * dz)) * 180 / Math.PI);
                }
            }
        }
        player.snapTo(x, y - player.getEyeHeight(), z, yaw, pitch);
        player.setOldPosAndRot();
        player.setYHeadRot(yaw);
        player.yHeadRotO = yaw;
        player.setDeltaMovement(Vec3.ZERO);
    }

    private static void heights(TestServerContext server, Path folder, String[] words) {
        int x0 = Integer.parseInt(words[0]);
        int z0 = Integer.parseInt(words[1]);
        int x1 = Integer.parseInt(words[2]);
        int z1 = Integer.parseInt(words[3]);
        List<String> out = server.computeOnServer(minecraft -> {
            ServerLevel level = minecraft.overworld();
            List<String> rows = new ArrayList<>();
            rows.add(x0 + " " + z0 + " " + x1 + " " + z1);
            for (int z = z0; z <= z1; z++) {
                StringBuilder surface = new StringBuilder("S");
                StringBuilder bare = new StringBuilder("N");
                StringBuilder floor = new StringBuilder("F");
                StringBuilder block = new StringBuilder("B");
                for (int x = x0; x <= x1; x++) {
                    LevelChunk chunk = level.getChunk(x >> 4, z >> 4);
                    int top = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x & 15, z & 15);
                    surface.append(' ').append(top);
                    bare.append(' ').append(chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x & 15, z & 15));
                    floor.append(' ').append(chunk.getHeight(Heightmap.Types.OCEAN_FLOOR, x & 15, z & 15));
                    block.append(' ').append(BuiltInRegistries.BLOCK.getKey(chunk.getBlockState(new BlockPos(x, top, z)).getBlock()).getPath());
                }
                rows.add(surface.toString());
                rows.add(bare.toString());
                rows.add(floor.toString());
                rows.add(block.toString());
            }
            return rows;
        });
        try {
            Files.write(folder.resolve(words[4]), out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Level ground for a build on ordinary land: the box x0 z0 .. x1 z1 gets grass at height y on earth, with
     * nothing above it; around it, for {@code skirt} blocks, the land is cut or filled to a slope of one in one
     * towards the pad, so it sits in the land instead of in a pit or on a table.
     */
    private static void pad(TestServerContext server, String[] words) {
        int x0 = Integer.parseInt(words[0]);
        int z0 = Integer.parseInt(words[1]);
        int x1 = Integer.parseInt(words[2]);
        int z1 = Integer.parseInt(words[3]);
        int y = Integer.parseInt(words[4]);
        int skirt = Integer.parseInt(words[5]);
        server.runOnServer(minecraft -> {
            ServerLevel level = minecraft.overworld();
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            for (int z = z0 - skirt; z <= z1 + skirt; z++) {
                for (int x = x0 - skirt; x <= x1 + skirt; x++) {
                    int out = (int) Math.ceil(Math.hypot(Math.max(0, Math.max(x0 - x, x - x1)), Math.max(0, Math.max(z0 - z, z - z1))));
                    if (out > skirt) {
                        continue;
                    }
                    LevelChunk chunk = level.getChunk(x >> 4, z >> 4);
                    int ground = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x & 15, z & 15);
                    int top = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x & 15, z & 15);
                    // Tree trunks count as ground to the height map: find the earth under them.
                    while (ground > level.getMinY() && !level.getBlockState(pos.set(x, ground, z)).is(BlockTags.DIRT)
                            && !level.getBlockState(pos).is(BlockTags.BASE_STONE_OVERWORLD)
                            && !level.getBlockState(pos).is(BlockTags.SAND) && !level.getBlockState(pos).is(Blocks.SNOW_BLOCK)
                            && !level.getBlockState(pos).is(Blocks.GRAVEL) && level.getFluidState(pos).isEmpty()) {
                        ground--;
                    }
                    int target = out == 0 ? y : Mth.clamp(ground, y - out, y + out);
                    if (out > 0 && target == ground) {
                        continue;
                    }
                    BlockState surface = level.getBlockState(pos.set(x, ground, z));
                    boolean snowy = level.getBlockState(pos.set(x, ground + 1, z)).is(Blocks.SNOW);
                    for (int yy = Math.max(top, ground); yy > target; yy--) {
                        level.setBlock(pos.set(x, yy, z), Blocks.AIR.defaultBlockState(), 18);
                    }
                    for (int yy = Math.min(ground, target - 4); yy < target; yy++) {
                        level.setBlock(pos.set(x, yy, z), Blocks.DIRT.defaultBlockState(), 18);
                    }
                    boolean keep = out > 0 && (surface.is(BlockTags.SAND) || surface.is(BlockTags.BASE_STONE_OVERWORLD)
                            || surface.is(Blocks.SNOW_BLOCK) || surface.is(Blocks.GRAVEL));
                    level.setBlock(pos.set(x, target, z), keep ? surface : Blocks.GRASS_BLOCK.defaultBlockState(), 18);
                    if (snowy && out > 0) {
                        level.setBlock(pos.set(x, target + 1, z), Blocks.SNOW.defaultBlockState(), 18);
                    }
                }
            }
        });
    }

    private static double[] numbers(String[] words, int from, int count) {
        double[] out = new double[count];
        for (int i = 0; i < count; i++) {
            out[i] = Double.parseDouble(words[from + i]);
        }
        return out;
    }

    private static List<String> readLines(Path file) {
        try {
            return Files.readAllLines(file).stream().filter(line -> !line.isBlank() && !line.startsWith("#")).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
