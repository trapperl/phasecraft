package dev.phasecraft.test.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.phasecraft.client.GhostLayer;
import dev.phasecraft.client.GhostRendererAccess;
import dev.phasecraft.overlay.OverlayPayloads;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The overlay's per-chunk geometry cache draws exactly what drawing every ghost
 * from its model each frame draws, also after changes (including a change on
 * the other side of a chunk border, which uncovers a face); and on a large
 * hidden build it is much faster. Timings go to the log.
 */
public class OverlayCacheClientGameTest implements FabricClientGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("phasecraft-test");
    private static final Vec3 CAMERA = new Vec3(5.3, -58.4, -3.7);

    /** One vertex, quantized: position and normal to 1/100, texture coordinates to 1/10000. */
    private record Vertex(int x, int y, int z, int color, int u, int v, int nx, int ny, int nz) {
        /** Equal up to one quantum per number: the two ways compute the same floats in a different order. */
        boolean near(Vertex o) {
            return color == o.color && Math.abs(x - o.x) <= 1 && Math.abs(y - o.y) <= 1 && Math.abs(z - o.z) <= 1
                    && Math.abs(u - o.u) <= 1 && Math.abs(v - o.v) <= 1
                    && Math.abs(nx - o.nx) <= 1 && Math.abs(ny - o.ny) <= 1 && Math.abs(nz - o.nz) <= 1;
        }
    }

    /** Records every vertex, so the two ways can be compared regardless of order. */
    private static class Recorder implements VertexConsumer {
        final List<Vertex> vertices = new ArrayList<>();
        private float x, y, z, u, v, nx, ny, nz;
        private int color;
        private boolean open;

        private void finish() {
            if (open) {
                vertices.add(new Vertex(Math.round(x * 100), Math.round(y * 100), Math.round(z * 100), color,
                        Math.round(u * 10000), Math.round(v * 10000), Math.round(nx * 100), Math.round(ny * 100), Math.round(nz * 100)));
            }
        }

        List<Vertex> done() {
            finish();
            open = false;
            return vertices;
        }

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            finish();
            this.x = x;
            this.y = y;
            this.z = z;
            open = true;
            return this;
        }

        @Override
        public VertexConsumer setColor(int r, int g, int b, int a) {
            color = (a << 24) | (r << 16) | (g << 8) | b;
            return this;
        }

        @Override
        public VertexConsumer setColor(int color) {
            this.color = color;
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            this.u = u;
            this.v = v;
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setUv3(float u, float v) {
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            nx = x;
            ny = y;
            nz = z;
            return this;
        }

        @Override
        public VertexConsumer setLineWidth(float width) {
            return this;
        }
    }

    private static PoseStack.Pose cameraPose() {
        PoseStack stack = new PoseStack();
        stack.rotateDegrees(Axis.XP, 20);
        stack.rotateDegrees(Axis.YP, 135);
        return stack.last();
    }

    private static void put(List<OverlayPayloads.Ghost> ghosts, int x, int y, int z, BlockState state) {
        ghosts.add(new OverlayPayloads.Ghost(new BlockPos(x, y, z), state));
    }

    /** The cached and the direct way agree on every vertex. */
    private static void assertSame(ClientGameTestContext context, GhostLayer layer, String when) {
        String problem = context.computeOnClient(minecraft -> {
            Recorder direct = new Recorder();
            Recorder cached = new Recorder();
            GhostRendererAccess.drawDirect(cameraPose(), direct, layer, CAMERA, 200);
            GhostRendererAccess.drawCached(cameraPose(), cached, layer, CAMERA, 200);
            List<Vertex> a = direct.done();
            List<Vertex> b = cached.done();
            if (a.isEmpty()) {
                return "nothing drawn";
            }
            // Match exact vertices first, then what is left within one quantum.
            Map<Vertex, Integer> unmatched = new HashMap<>();
            b.forEach(vertex -> unmatched.merge(vertex, 1, Integer::sum));
            List<Vertex> onlyDirect = new ArrayList<>();
            for (Vertex vertex : a) {
                if (unmatched.getOrDefault(vertex, 0) > 0) {
                    unmatched.merge(vertex, -1, Integer::sum);
                } else {
                    onlyDirect.add(vertex);
                }
            }
            List<Vertex> onlyCached = new ArrayList<>();
            unmatched.forEach((vertex, count) -> {
                for (int i = 0; i < count; i++) {
                    onlyCached.add(vertex);
                }
            });
            onlyDirect.removeIf(vertex -> {
                for (int i = 0; i < onlyCached.size(); i++) {
                    if (vertex.near(onlyCached.get(i))) {
                        onlyCached.remove(i);
                        return true;
                    }
                }
                return false;
            });
            if (!onlyDirect.isEmpty() || !onlyCached.isEmpty()) {
                return a.size() + " direct vs " + b.size() + " cached vertices; only direct: "
                        + onlyDirect.subList(0, Math.min(4, onlyDirect.size())) + "; only cached: "
                        + onlyCached.subList(0, Math.min(4, onlyCached.size()));
            }
            return null;
        });
        if (problem != null) {
            throw new AssertionError("cache differs " + when + ": " + problem);
        }
    }

    @Override
    public void runTest(ClientGameTestContext context) {
        IrisRun.prepare(context);
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            world.getConnection().waitForChunksRender();
            GhostLayer layer = new GhostLayer();
            List<OverlayPayloads.Ghost> ghosts = new ArrayList<>();
            // Across the chunk border at x = 15 / 16: stone against stone hides the faces between.
            put(ghosts, 15, -60, 2, Blocks.STONE.defaultBlockState());
            put(ghosts, 16, -60, 2, Blocks.STONE.defaultBlockState());
            put(ghosts, 14, -60, 2, Blocks.GLASS.defaultBlockState());
            put(ghosts, 3, -60, 3, Blocks.GRASS_BLOCK.defaultBlockState());       // tinted
            put(ghosts, 4, -60, 3, Blocks.SHORT_GRASS.defaultBlockState());       // random offset
            put(ghosts, 5, -60, 3, Blocks.REDSTONE_LAMP.defaultBlockState());
            put(ghosts, 6, -60, 3, Blocks.CHEST.defaultBlockState());             // no model: its shape
            put(ghosts, 7, -60, 3, Blocks.WATER.defaultBlockState());             // a fluid box
            put(ghosts, 7, -59, 3, Blocks.WATER.defaultBlockState());
            put(ghosts, -2, -60, -5, Blocks.OAK_STAIRS.defaultBlockState());      // another chunk
            layer.apply(ghosts);
            assertSame(context, layer, "at first");

            // A change in place.
            layer.apply(List.of(new OverlayPayloads.Ghost(new BlockPos(5, -60, 3), Blocks.GOLD_BLOCK.defaultBlockState())));
            assertSame(context, layer, "after a change");
            // Alone, so nothing else rebuilds chunk 0: a removal in chunk 1, at x = 16, uncovers the
            // east face of the stone at x = 15 in chunk 0.
            layer.apply(List.of(new OverlayPayloads.Ghost(new BlockPos(16, -60, 2), Blocks.AIR.defaultBlockState())));
            assertSame(context, layer, "after a change across a chunk border");

            // A large hidden build: a 48 x 8 x 48 block of stone and lamps around the camera.
            GhostLayer big = new GhostLayer();
            List<OverlayPayloads.Ghost> many = new ArrayList<>();
            for (int x = -24; x < 24; x++) {
                for (int z = -24; z < 24; z++) {
                    for (int y = -64; y < -56; y++) {
                        put(many, x, y, z, ((x + y + z) & 7) == 0 ? Blocks.REDSTONE_LAMP.defaultBlockState() : Blocks.STONE.defaultBlockState());
                    }
                }
            }
            big.apply(many);
            assertSame(context, big, "on a large build");
            String timings = context.computeOnClient(minecraft -> {
                VertexConsumer sink = new Recorder() {
                    @Override
                    public VertexConsumer addVertex(float x, float y, float z) {
                        return this;
                    }
                };
                int frames = 30;
                GhostRendererAccess.drawCached(cameraPose(), sink, big, CAMERA, 200); // build the cache first
                long t0 = System.nanoTime();
                for (int i = 0; i < frames; i++) {
                    GhostRendererAccess.drawDirect(cameraPose(), sink, big, CAMERA, 200);
                    GhostRendererAccess.drawDirect(cameraPose(), sink, big, CAMERA, 100);
                }
                long t1 = System.nanoTime();
                for (int i = 0; i < frames; i++) {
                    GhostRendererAccess.drawCached(cameraPose(), sink, big, CAMERA, 200);
                    GhostRendererAccess.drawCached(cameraPose(), sink, big, CAMERA, 100);
                }
                long t2 = System.nanoTime();
                return String.format("%d ghosts: direct %.2f ms/frame, cached %.2f ms/frame",
                        big.size(), (t1 - t0) / 1e6 / frames, (t2 - t1) / 1e6 / frames);
            });
            LOGGER.info("Overlay cache timing: {}", timings);
        }
    }
}
