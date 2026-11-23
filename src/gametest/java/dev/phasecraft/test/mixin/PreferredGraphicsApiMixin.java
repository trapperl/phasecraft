package dev.phasecraft.test.mixin;

import com.mojang.renderpearl.api.device.GpuBackend;
import com.mojang.renderpearl.backend.opengl.GlBackend;
import com.mojang.renderpearl.backend.vulkan.VulkanBackend;
import net.minecraft.client.PreferredGraphicsApi;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Client GameTests run once per graphics backend. The system property
 * {@code phasecraft.test.graphicsApi} (opengl or vulkan) makes the client use
 * only that one, so a backend that fails to start fails the run instead of
 * quietly falling back to the other.
 */
@Mixin(PreferredGraphicsApi.class)
abstract class PreferredGraphicsApiMixin {
    @Inject(method = "getBackendsToTry", at = @At("HEAD"), cancellable = true)
    private void phasecraftTest$onlyRequestedBackend(CallbackInfoReturnable<GpuBackend[]> cir) {
        String requested = System.getProperty("phasecraft.test.graphicsApi", "");
        switch (requested) {
            case "opengl" -> cir.setReturnValue(new GpuBackend[]{new GlBackend()});
            case "vulkan" -> cir.setReturnValue(new GpuBackend[]{new VulkanBackend()});
            case "" -> {}
            default -> throw new IllegalArgumentException("phasecraft.test.graphicsApi must be opengl or vulkan, not " + requested);
        }
    }
}
