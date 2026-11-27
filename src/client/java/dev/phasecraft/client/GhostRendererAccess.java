package dev.phasecraft.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.world.phys.Vec3;

/** For tests: the overlay's two ways of drawing a ghost layer (client thread only). */
public final class GhostRendererAccess {
    private GhostRendererAccess() {}

    /** Every ghost from its model, as each frame did before the cache. */
    public static void drawDirect(PoseStack.Pose cameraPose, VertexConsumer buffer, GhostLayer layer, Vec3 camera, int alpha) {
        GhostRenderer.drawDirect(cameraPose, buffer, layer, camera, alpha);
    }

    /** The inside view's last frame: vertices replayed (chunks within its draw distance). */
    public static int insideVertices() {
        return GhostRenderer.lastInsideVertices;
    }

    /** The inside view's last frame: CPU time of replaying them, in nanoseconds. */
    public static long insideNanos() {
        return GhostRenderer.lastInsideNanos;
    }

    /** The way frames draw: cached chunk meshes, rebuilt where ghosts changed. */
    public static void drawCached(PoseStack.Pose cameraPose, VertexConsumer buffer, GhostLayer layer, Vec3 camera, int alpha) {
        GhostRenderer.replay(cameraPose, buffer, GhostRenderer.meshesInRange(layer, camera), camera, alpha);
    }
}
