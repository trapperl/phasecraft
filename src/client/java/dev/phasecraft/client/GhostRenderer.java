package dev.phasecraft.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.VertexConsumer;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.util.ARGB;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Vector3f;

/**
 * Draws the hidden blocks the client holds as translucent ghosts over the
 * visible world: their own block models at the chosen opacity, full-bright so
 * they read as an overlay. Ghosts behind or inside visible blocks (machinery in
 * a wall, wiring under the floor) still show, fainter: a second pass draws
 * every ghost without depth test at {@link #SEE_THROUGH_SHARE} of the opacity.
 *
 * <p>Blocks drawn by special renderers from block entity data (chests, signs,
 * beds, banners, heads) are shown as their shape in their particle texture;
 * water and lava as a box of their height in their own texture and tint.</p>
 *
 * <p>Each ghost is drawn very slightly larger than a block, so where a hidden
 * block shares its position with a visible one, the ghost lies on the visible
 * block's surface as a tint instead of flickering against it. Faces between
 * two hidden full blocks are left out, as in the normal world.</p>
 *
 * <p>Performance: the geometry of each chunk's ghosts is built once into a
 * {@link Mesh} (positions relative to the chunk, colours without the pass's
 * alpha) and kept until the hidden blocks in or next to that chunk change
 * ({@link GhostLayer#takeChangedChunks}) or the block models are reloaded.
 * Each frame only replays the meshes of chunks in range, with the camera
 * transform and the pass's alpha applied: no model lookups, tints or face
 * checks per frame. {@link #drawDirect} is the uncached way, kept as the
 * reference the cache is tested against.</p>
 */
public final class GhostRenderer {
    /** How much larger than a block a ghost is drawn, per side. */
    static final float INFLATE = 0.003F;
    /** How strong the see-through pass is, relative to the chosen opacity. */
    static final float SEE_THROUGH_SHARE = 0.4F;
    /** Ghosts further than this from the camera are not drawn. */
    static final double MAX_DISTANCE = 96.0;

    private static final Direction[] DIRECTIONS = Direction.values();

    private GhostRenderer() {}

    /** Cached geometry per chunk (packed ChunkPos), for one GhostLayer. */
    private static final Long2ObjectOpenHashMap<Mesh> MESHES = new Long2ObjectOpenHashMap<>();
    private static @Nullable GhostLayer cachedLayer;
    private static @Nullable BlockStateModelSet cachedModels;

    /** Draws the ghosts if the overlay is on, or always while editing (you need to see what you edit). */
    public static void collect(LevelRenderContext context, GhostLayer layer, OverlaySettings settings, boolean editing) {
        if (!(settings.enabled() || editing) || layer.isEmpty()) {
            return;
        }
        Vec3 camera = context.levelState().cameraRenderState.pos;
        List<Mesh> meshes = meshesInRange(layer, camera);
        if (meshes.isEmpty()) {
            return;
        }
        float opacity = settings.opacity();
        int seeThroughAlpha = ARGB.as8BitChannel(opacity * SEE_THROUGH_SHARE);
        int inFrontAlpha = ARGB.as8BitChannel(opacity);
        context.submitNodeCollector().submitCustomGeometry(context.poseStack(), GhostRenderTypes.seeThrough(),
                (pose, buffer) -> replay(pose, buffer, meshes, camera, seeThroughAlpha));
        context.submitNodeCollector().submitCustomGeometry(context.poseStack(), GhostRenderTypes.inFront(),
                (pose, buffer) -> replay(pose, buffer, meshes, camera, inFrontAlpha));
    }

    /** The cached meshes of chunks in range, rebuilding those whose ghosts changed. */
    static List<Mesh> meshesInRange(GhostLayer layer, Vec3 camera) {
        BlockStateModelSet models = Minecraft.getInstance().getModelManager().getBlockStateModelSet();
        LongOpenHashSet changed = layer.takeChangedChunks();
        if (layer != cachedLayer || models != cachedModels || changed == null) {
            MESHES.clear();
            cachedLayer = layer;
            cachedModels = models;
        } else {
            changed.forEach(MESHES::remove);
        }
        List<Mesh> meshes = new ArrayList<>();
        double reach = MAX_DISTANCE + 8;
        for (long chunk : layer.chunkKeys()) {
            int originX = SectionPos.sectionToBlockCoord(ChunkPos.getX(chunk));
            int originZ = SectionPos.sectionToBlockCoord(ChunkPos.getZ(chunk));
            double dx = originX + 8 - camera.x;
            double dz = originZ + 8 - camera.z;
            if (dx * dx + dz * dz > reach * reach) {
                continue;
            }
            Mesh mesh = MESHES.get(chunk);
            if (mesh == null) {
                mesh = build(layer, chunk, originX, originZ);
                MESHES.put(chunk, mesh);
            }
            if (mesh.vertices > 0) {
                meshes.add(mesh);
            }
        }
        return meshes;
    }

    /** One chunk's ghost geometry: per vertex x, y, z (relative to the chunk's origin), u, v, normal; colour (alpha 255). */
    static final class Mesh {
        final int originX;
        final int originZ;
        float[] floats = new float[8 * 64];
        int[] colors = new int[64];
        int vertices;

        Mesh(int originX, int originZ) {
            this.originX = originX;
            this.originZ = originZ;
        }
    }

    private static Mesh build(GhostLayer layer, long chunk, int originX, int originZ) {
        Mesh mesh = new Mesh(originX, originZ);
        MeshRecorder recorder = new MeshRecorder(mesh);
        Emitter emitter = new Emitter(layer);
        PoseStack stack = new PoseStack();
        layer.forEachInChunk(chunk, (packed, state) -> {
            BlockPos pos = BlockPos.of(packed);
            if (shown(state)) {
                emitter.emit(stack, recorder, pos, state, pos.getX() - originX, pos.getY(), pos.getZ() - originZ, 255);
            }
        });
        return mesh;
    }

    /** Replays cached meshes into a pass: camera transform applied, colours given the pass's alpha. */
    static void replay(PoseStack.Pose cameraPose, VertexConsumer buffer, List<Mesh> meshes, Vec3 camera, int alpha) {
        Matrix4f matrix = cameraPose.pose();
        Matrix3f normalMatrix = cameraPose.normal();
        Vector3f position = new Vector3f();
        Vector3f normal = new Vector3f();
        int alphaBits = alpha << 24;
        for (Mesh mesh : meshes) {
            float tx = (float) (mesh.originX - camera.x);
            float ty = (float) -camera.y;
            float tz = (float) (mesh.originZ - camera.z);
            float[] f = mesh.floats;
            for (int i = 0; i < mesh.vertices; i++) {
                int o = i * 8;
                matrix.transformPosition(f[o] + tx, f[o + 1] + ty, f[o + 2] + tz, position);
                normalMatrix.transform(f[o + 5], f[o + 6], f[o + 7], normal);
                buffer.addVertex(position.x(), position.y(), position.z(), alphaBits | (mesh.colors[i] & 0xFFFFFF), f[o + 3], f[o + 4],
                        OverlayTexture.NO_OVERLAY, LightCoordsUtil.FULL_BRIGHT, normal.x(), normal.y(), normal.z());
            }
        }
    }

    /** Records vertices into a Mesh: everything vanilla's quad and box code sets, except light and overlay (fixed). */
    private static final class MeshRecorder implements VertexConsumer {
        private final Mesh mesh;

        MeshRecorder(Mesh mesh) {
            this.mesh = mesh;
        }

        private int current() {
            return mesh.vertices - 1;
        }

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            if (mesh.vertices == mesh.colors.length) {
                mesh.colors = java.util.Arrays.copyOf(mesh.colors, mesh.colors.length * 2);
                mesh.floats = java.util.Arrays.copyOf(mesh.floats, mesh.floats.length * 2);
            }
            int o = mesh.vertices * 8;
            mesh.floats[o] = x;
            mesh.floats[o + 1] = y;
            mesh.floats[o + 2] = z;
            mesh.colors[mesh.vertices] = 0xFFFFFFFF;
            mesh.vertices++;
            return this;
        }

        @Override
        public VertexConsumer setColor(int r, int g, int b, int a) {
            mesh.colors[current()] = ARGB.color(a, r, g, b);
            return this;
        }

        @Override
        public VertexConsumer setColor(int color) {
            mesh.colors[current()] = color;
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            mesh.floats[current() * 8 + 3] = u;
            mesh.floats[current() * 8 + 4] = v;
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            int o = current() * 8;
            mesh.floats[o + 5] = x;
            mesh.floats[o + 6] = y;
            mesh.floats[o + 7] = z;
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
        public VertexConsumer setLineWidth(float width) {
            return this;
        }
    }

    private static boolean shown(BlockState state) {
        // Air, barriers, light blocks: nothing to show (water and lava are shown).
        return state.getRenderShape() == RenderShape.MODEL || !state.getFluidState().isEmpty();
    }

    private record Ghost(BlockPos pos, BlockState state, float x, float y, float z) {}

    /** The uncached way: every ghost in range from its model, each frame. The reference the cache is tested against. */
    static void drawDirect(PoseStack.Pose cameraPose, VertexConsumer buffer, GhostLayer layer, Vec3 camera, int alpha) {
        draw(cameraPose, buffer, visibleGhosts(layer, camera), layer, alpha);
    }

    private static List<Ghost> visibleGhosts(GhostLayer layer, Vec3 camera) {
        List<Ghost> ghosts = new ArrayList<>();
        double maxSq = MAX_DISTANCE * MAX_DISTANCE;
        layer.forEach((packed, state) -> {
            if (!shown(state)) {
                return;
            }
            BlockPos pos = BlockPos.of(packed);
            double dx = pos.getX() + 0.5 - camera.x;
            double dy = pos.getY() + 0.5 - camera.y;
            double dz = pos.getZ() + 0.5 - camera.z;
            if (dx * dx + dy * dy + dz * dz <= maxSq) {
                ghosts.add(new Ghost(pos, state, (float) (pos.getX() - camera.x), (float) (pos.getY() - camera.y),
                        (float) (pos.getZ() - camera.z)));
            }
        });
        return ghosts;
    }

    private static void draw(PoseStack.Pose cameraPose, VertexConsumer buffer, List<Ghost> ghosts, GhostLayer layer, int alpha) {
        Emitter emitter = new Emitter(layer);
        PoseStack stack = new PoseStack();
        stack.last().set(cameraPose);
        for (Ghost ghost : ghosts) {
            emitter.emit(stack, buffer, ghost.pos(), ghost.state(), ghost.x(), ghost.y(), ghost.z(), alpha);
        }
    }

    /** Draws single ghosts: model quads, the shape of model-less blocks, fluid boxes. */
    private static final class Emitter {
        private final GhostLayer layer;
        private final BlockStateModelSet models;
        private final FluidStateModelSet fluidModels;
        private final BlockColors colors;
        private final ClientEditing.GhostGetter getter;
        private final RandomSource random = RandomSource.create();
        private final List<BlockStateModelPart> parts = new ArrayList<>();
        private final QuadInstance quad = new QuadInstance();
        private final BlockPos.MutableBlockPos neighbour = new BlockPos.MutableBlockPos();

        Emitter(GhostLayer layer) {
            Minecraft minecraft = Minecraft.getInstance();
            this.layer = layer;
            this.models = minecraft.getModelManager().getBlockStateModelSet();
            this.fluidModels = minecraft.getModelManager().getFluidStateModelSet();
            this.colors = minecraft.getBlockColors();
            this.getter = new ClientEditing.GhostGetter(layer, minecraft.level);
            quad.setLightCoords(LightCoordsUtil.FULL_BRIGHT);
        }

        /** One ghost at (x, y, z) relative to the stack's current origin. */
        void emit(PoseStack stack, VertexConsumer buffer, BlockPos pos, BlockState state, float x, float y, float z, int alpha) {
            stack.pushPose();
            stack.translate(x + 0.5F, y + 0.5F, z + 0.5F);
            stack.scale(1 + 2 * INFLATE, 1 + 2 * INFLATE, 1 + 2 * INFLATE);
            stack.translate(-0.5F, -0.5F, -0.5F);
            stack.pushPose();
            stack.translate(state.getOffset(pos));
            PoseStack.Pose pose = stack.last();

            boolean drewModel = false;
            if (state.getRenderShape() == RenderShape.MODEL) {
                random.setSeed(state.getSeed(pos));
                models.get(state).collectParts(random, parts);
                List<BlockTintSource> tints = colors.getTintSources(state);
                for (BlockStateModelPart part : parts) {
                    for (Direction direction : DIRECTIONS) {
                        List<BakedQuad> quads = part.getQuads(direction);
                        drewModel |= !quads.isEmpty();
                        if (quads.isEmpty()) {
                            continue;
                        }
                        neighbour.setWithOffset(pos, direction);
                        if (!Block.shouldRenderFace(state, layer.get(neighbour), direction)) {
                            continue;
                        }
                        for (BakedQuad baked : quads) {
                            put(pose, buffer, quad, baked, state, tints, alpha);
                        }
                    }
                    for (BakedQuad baked : part.getQuads(null)) {
                        drewModel = true;
                        put(pose, buffer, quad, baked, state, tints, alpha);
                    }
                }
                parts.clear();
                if (!drewModel) {
                    // Chests, signs, beds, banners, heads: drawn by special renderers from
                    // block entity data the client does not have. Show their shape instead,
                    // in their own particle texture.
                    VoxelShape shape = state.getShape(getter, pos);
                    TextureAtlasSprite sprite = models.get(state).particleMaterial().sprite();
                    for (AABB box : (shape.isEmpty() ? Shapes.block() : shape).toAabbs()) {
                        putBox(pose, buffer, box, sprite, 0xFFFFFF, alpha, ALL_FACES);
                    }
                }
            }
            stack.popPose();

            FluidState fluid = state.getFluidState();
            if (!fluid.isEmpty()) {
                FluidModel fluidModel = fluidModels.get(fluid);
                int rgb = fluidModel.tintSource() != null ? fluidModel.tintSource().color(state) : 0xFFFFFF;
                boolean fullBelowSurface = sameFluid(fluid, layer.get(neighbour.setWithOffset(pos, Direction.UP)));
                float height = fullBelowSurface ? 1.0F : fluid.getOwnHeight();
                int faces = 0;
                for (Direction direction : DIRECTIONS) {
                    if (!sameFluid(fluid, layer.get(neighbour.setWithOffset(pos, direction)))) {
                        faces |= 1 << direction.ordinal();
                    }
                }
                putBox(stack.last(), buffer, new AABB(0, 0, 0, 1, height, 1), fluidModel.stillMaterial().sprite(), rgb, alpha, faces);
            }
            stack.popPose();
        }
    }

    private static final int ALL_FACES = 0b111111;

    private static boolean sameFluid(FluidState fluid, BlockState neighbour) {
        return fluid.getType().isSame(neighbour.getFluidState().getType());
    }

    /** A textured box (block coordinates 0..1), faces chosen by a bit per Direction ordinal. */
    private static void putBox(PoseStack.Pose pose, VertexConsumer buffer, AABB box, TextureAtlasSprite sprite, int rgb,
            int alpha, int faces) {
        float x0 = (float) box.minX, y0 = (float) box.minY, z0 = (float) box.minZ;
        float x1 = (float) box.maxX, y1 = (float) box.maxY, z1 = (float) box.maxZ;
        for (Direction direction : DIRECTIONS) {
            if ((faces & (1 << direction.ordinal())) == 0) {
                continue;
            }
            int color = ARGB.color(alpha, ARGB.scaleRGB(rgb, shade(direction)));
            // Corners counter-clockwise seen from outside, as (x, y, z, u, v) in block units.
            float[][] corners = switch (direction) {
                case DOWN -> new float[][] {{x0, y0, z1, x0, z1}, {x0, y0, z0, x0, z0}, {x1, y0, z0, x1, z0}, {x1, y0, z1, x1, z1}};
                case UP -> new float[][] {{x0, y1, z0, x0, z0}, {x0, y1, z1, x0, z1}, {x1, y1, z1, x1, z1}, {x1, y1, z0, x1, z0}};
                case NORTH -> new float[][] {{x1, y1, z0, 1 - x1, 1 - y1}, {x1, y0, z0, 1 - x1, 1 - y0}, {x0, y0, z0, 1 - x0, 1 - y0}, {x0, y1, z0, 1 - x0, 1 - y1}};
                case SOUTH -> new float[][] {{x0, y1, z1, x0, 1 - y1}, {x0, y0, z1, x0, 1 - y0}, {x1, y0, z1, x1, 1 - y0}, {x1, y1, z1, x1, 1 - y1}};
                case WEST -> new float[][] {{x0, y1, z0, z0, 1 - y1}, {x0, y0, z0, z0, 1 - y0}, {x0, y0, z1, z1, 1 - y0}, {x0, y1, z1, z1, 1 - y1}};
                case EAST -> new float[][] {{x1, y1, z1, 1 - z1, 1 - y1}, {x1, y0, z1, 1 - z1, 1 - y0}, {x1, y0, z0, 1 - z0, 1 - y0}, {x1, y1, z0, 1 - z0, 1 - y1}};
            };
            Vector3f normal = pose.transformNormal(direction.getUnitVec3f(), new Vector3f());
            for (float[] c : corners) {
                Vector3f position = pose.pose().transformPosition(c[0], c[1], c[2], new Vector3f());
                buffer.addVertex(position.x(), position.y(), position.z(), color, sprite.getU(c[3]), sprite.getV(c[4]),
                        OverlayTexture.NO_OVERLAY, LightCoordsUtil.FULL_BRIGHT, normal.x(), normal.y(), normal.z());
            }
        }
    }

    private static void put(PoseStack.Pose pose, VertexConsumer buffer, QuadInstance quad, BakedQuad baked, BlockState state,
            List<BlockTintSource> tints, int alpha) {
        int tintIndex = baked.materialInfo().tintIndex();
        int rgb = tintIndex >= 0 && tintIndex < tints.size() ? tints.get(tintIndex).color(state) : 0xFFFFFF;
        Direction shadeDirection = baked.materialInfo().shadeDirectionOverride() != null
                ? baked.materialInfo().shadeDirectionOverride() : baked.direction();
        quad.setColor(ARGB.color(alpha, ARGB.scaleRGB(rgb, shade(shadeDirection))));
        buffer.putBakedQuad(pose, baked, quad);
    }

    /** Vanilla's fixed face shading in the Overworld, so ghosts keep their shape. */
    private static float shade(Direction direction) {
        return switch (direction) {
            case UP -> 1.0F;
            case DOWN -> 0.5F;
            case NORTH, SOUTH -> 0.8F;
            case EAST, WEST -> 0.6F;
        };
    }
}
