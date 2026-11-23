package dev.phasecraft.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.VertexConsumer;
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
import net.minecraft.util.ARGB;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
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

    /** Draws the ghosts if the overlay is on, or always while editing (you need to see what you edit). */
    public static void collect(LevelRenderContext context, GhostLayer layer, OverlaySettings settings, boolean editing) {
        if (!(settings.enabled() || editing) || layer.isEmpty()) {
            return;
        }
        Vec3 camera = context.levelState().cameraRenderState.pos;
        List<Ghost> ghosts = visibleGhosts(layer, camera);
        if (ghosts.isEmpty()) {
            return;
        }
        float opacity = settings.opacity();
        int seeThroughAlpha = ARGB.as8BitChannel(opacity * SEE_THROUGH_SHARE);
        int inFrontAlpha = ARGB.as8BitChannel(opacity);
        context.submitNodeCollector().submitCustomGeometry(context.poseStack(), GhostRenderTypes.seeThrough(),
                (pose, buffer) -> draw(pose, buffer, ghosts, layer, seeThroughAlpha));
        context.submitNodeCollector().submitCustomGeometry(context.poseStack(), GhostRenderTypes.inFront(),
                (pose, buffer) -> draw(pose, buffer, ghosts, layer, inFrontAlpha));
    }

    private record Ghost(BlockPos pos, BlockState state, float x, float y, float z) {}

    private static List<Ghost> visibleGhosts(GhostLayer layer, Vec3 camera) {
        List<Ghost> ghosts = new ArrayList<>();
        double maxSq = MAX_DISTANCE * MAX_DISTANCE;
        layer.forEach((packed, state) -> {
            if (state.getRenderShape() != RenderShape.MODEL && state.getFluidState().isEmpty()) {
                return; // air, barriers, light blocks: nothing to show (water and lava are shown)
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
        Minecraft minecraft = Minecraft.getInstance();
        BlockStateModelSet models = minecraft.getModelManager().getBlockStateModelSet();
        FluidStateModelSet fluidModels = minecraft.getModelManager().getFluidStateModelSet();
        BlockColors colors = minecraft.getBlockColors();
        ClientEditing.GhostGetter getter = new ClientEditing.GhostGetter(layer, minecraft.level);
        RandomSource random = RandomSource.create();
        List<BlockStateModelPart> parts = new ArrayList<>();
        QuadInstance quad = new QuadInstance();
        quad.setLightCoords(LightCoordsUtil.FULL_BRIGHT);
        PoseStack stack = new PoseStack();
        stack.last().set(cameraPose);
        BlockPos.MutableBlockPos neighbour = new BlockPos.MutableBlockPos();

        for (Ghost ghost : ghosts) {
            BlockState state = ghost.state();
            stack.pushPose();
            stack.translate(ghost.x() + 0.5F, ghost.y() + 0.5F, ghost.z() + 0.5F);
            stack.scale(1 + 2 * INFLATE, 1 + 2 * INFLATE, 1 + 2 * INFLATE);
            stack.translate(-0.5F, -0.5F, -0.5F);
            stack.pushPose();
            stack.translate(state.getOffset(ghost.pos()));
            PoseStack.Pose pose = stack.last();

            boolean drewModel = false;
            if (state.getRenderShape() == RenderShape.MODEL) {
                random.setSeed(state.getSeed(ghost.pos()));
                models.get(state).collectParts(random, parts);
                List<BlockTintSource> tints = colors.getTintSources(state);
                for (BlockStateModelPart part : parts) {
                    for (Direction direction : DIRECTIONS) {
                        List<BakedQuad> quads = part.getQuads(direction);
                        drewModel |= !quads.isEmpty();
                        if (quads.isEmpty()) {
                            continue;
                        }
                        neighbour.setWithOffset(ghost.pos(), direction);
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
                    VoxelShape shape = state.getShape(getter, ghost.pos());
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
                boolean fullBelowSurface = sameFluid(fluid, layer.get(neighbour.setWithOffset(ghost.pos(), Direction.UP)));
                float height = fullBelowSurface ? 1.0F : fluid.getOwnHeight();
                int faces = 0;
                for (Direction direction : DIRECTIONS) {
                    if (!sameFluid(fluid, layer.get(neighbour.setWithOffset(ghost.pos(), direction)))) {
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
