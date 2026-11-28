package dev.phasecraft.test.mixin;

import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A client GameTest run of one test class: the system property {@code phasecraft.test.only} names it (its simple
 * name). Fabric can pick tests by mod only; the trailer's run (runClientGameTestTrailer) renders for a long time
 * under a shader pack the other tests were not written for, and wants none of them.
 */
@Pseudo
@Mixin(targets = "net.fabricmc.fabric.impl.client.gametest.FabricClientGameTestRunner", remap = false)
abstract class ClientTestOnlyMixin {
    @Inject(method = "getTestToRun", at = @At("RETURN"), cancellable = true, remap = false)
    private static void phasecraftTest$only(CallbackInfoReturnable<List<EntrypointContainer<FabricClientGameTest>>> cir) {
        String only = System.getProperty("phasecraft.test.only", "");
        if (only.isEmpty()) {
            return;
        }
        List<EntrypointContainer<FabricClientGameTest>> kept = cir.getReturnValue().stream()
                .filter(test -> test.getEntrypoint().getClass().getSimpleName().equals(only)).toList();
        if (kept.isEmpty()) {
            throw new IllegalArgumentException("phasecraft.test.only: no client GameTest named " + only);
        }
        cir.setReturnValue(kept);
    }
}
