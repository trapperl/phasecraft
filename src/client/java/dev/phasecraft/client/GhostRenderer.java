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

/**
 * Draws the hidden blocks the client holds as translucent ghosts over the
 * visible world: their own block models at the chosen opacity, full-bright so
 * they read as an overlay. Ghosts behind or inside visible blocks (machinery in
 * a wall, wiring under the floor) still show, fainter: a second pass draws
 * every ghost without depth test at {@link #SEE_THROUGH_SHARE} of the opacity.
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
            if (state.getRenderShape() != RenderShape.MODEL) {
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
        Minecraft minecraft = Minecraft.getInstance();
        BlockStateModelSet models = minecraft.getModelManager().getBlockStateModelSet();
        BlockColors colors = minecraft.getBlockColors();
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
            stack.translate(state.getOffset(ghost.pos()));
            PoseStack.Pose pose = stack.last();

            random.setSeed(state.getSeed(ghost.pos()));
            models.get(state).collectParts(random, parts);
            List<BlockTintSource> tints = colors.getTintSources(state);
            for (BlockStateModelPart part : parts) {
                for (Direction direction : DIRECTIONS) {
                    List<BakedQuad> quads = part.getQuads(direction);
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
                    put(pose, buffer, quad, baked, state, tints, alpha);
                }
            }
            parts.clear();
            stack.popPose();
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
