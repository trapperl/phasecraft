package dev.phasecraft.test.mixin;

import dev.phasecraft.test.client.TrailerState;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL43C;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * The trailer on a graphics card under WSL (Mesa's D3D12 driver): at the end of every frame Iris copies each of the
 * shader pack's pictures back with glCopyTexSubImage2D, which that driver does on the processor, a second or two a
 * frame. For the trailer's run the same copy is asked for as glCopyImageSubData, texture to texture, which stays
 * on the card. Nothing changes in any other run ({@link TrailerState#RENDERING}).
 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.FinalPassRenderer", remap = false)
abstract class TrailerIrisSwapMixin {
    @Redirect(method = "renderFinalPass", remap = false, require = 0,
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL46C;glCopyTexSubImage2D(IIIIIIII)V", remap = false))
    private void phasecraftTest$copyOnTheCard(int target, int level, int xOffset, int yOffset, int x, int y, int width, int height) {
        if (!TrailerState.RENDERING) {
            GL11C.glCopyTexSubImage2D(target, level, xOffset, yOffset, x, y, width, height);
            return;
        }
        int from = GL30C.glGetFramebufferAttachmentParameteri(GL30C.GL_READ_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0,
                GL30C.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
        int to = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
        GL43C.glCopyImageSubData(from, GL11C.GL_TEXTURE_2D, 0, x, y, 0, to, GL11C.GL_TEXTURE_2D, level, xOffset, yOffset, 0, width, height, 1);
    }
}
