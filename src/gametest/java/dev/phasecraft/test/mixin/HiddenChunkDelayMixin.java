package dev.phasecraft.test.mixin;

import dev.phasecraft.link.PhaseLink;
import dev.phasecraft.test.HiddenChunkDelay;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * TEST ONLY (lives in the phasecraft-test mod, never shipped), and off unless asked for.
 *
 * <p>With {@code -Dphasecraft.test.hiddenChunkDelay=N} a hidden chunk hears N server ticks late that its visible
 * twin started (or stopped) ticking. That makes the gap between the two, which is there by chance on every run
 * (the hidden chunk loads on other threads while the test server ticks as fast as it can), as long as wanted: the
 * way to reproduce issue #3. With GameTestInfoHiddenChunksMixin's wait every test still passes; with
 * {@code -Dphasecraft.test.waitForHiddenChunks=false} as well, tests whose hidden blocks schedule ticks fail:</p>
 *
 * <pre>./gradlew runGameTest -PgameTestVmArgs="-Dphasecraft.test.hiddenChunkDelay=40 -Dphasecraft.test.waitForHiddenChunks=false"</pre>
 */
@Mixin(PhaseLink.class)
abstract class HiddenChunkDelayMixin {
    @Unique
    private static boolean phasecraftTest$releasing;

    @Shadow
    private static void followTicking(ServerLevel level, LevelChunk chunk, FullChunkStatus oldStatus, FullChunkStatus newStatus) {
        throw new AssertionError();
    }

    @Inject(method = "followTicking", at = @At("HEAD"), cancellable = true)
    private static void phasecraftTest$holdBack(ServerLevel level, LevelChunk chunk, FullChunkStatus oldStatus,
            FullChunkStatus newStatus, CallbackInfo ci) {
        if (HiddenChunkDelay.TICKS <= 0 || phasecraftTest$releasing) {
            return;
        }
        HiddenChunkDelay.hold(level.getServer(), () -> {
            phasecraftTest$releasing = true;
            try {
                followTicking(level, chunk, oldStatus, newStatus);
            } finally {
                phasecraftTest$releasing = false;
            }
        });
        ci.cancel();
    }
}
