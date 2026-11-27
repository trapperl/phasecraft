package dev.phasecraft.test.mixin;

import dev.phasecraft.layer.HiddenDimensions;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.TestInstanceBlockEntity;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * TEST ONLY (lives in the phasecraft-test mod, never shipped).
 *
 * <p>A GameTest starts only once the hidden chunks under its structure tick too. Vanilla waits for the test's own
 * (visible) chunks to be loaded and ticking, entities included, and knows nothing of the hidden layer. A hidden
 * chunk gets its ticket only when its visible twin has become ticking (PhaseLink.followTicking), and is then loaded
 * on worker threads, its entities read by the storage thread: it starts ticking some server ticks after the visible
 * one, how many depending on how busy the machine is. A test that started in that gap could place hidden blocks
 * (setBlock loads the chunk for the moment) and see what they do at once, while everything they scheduled (a
 * shifter's next step, a repeater) waited for the chunk to tick: steps a tick or more late, or a move that seemed
 * to stall after its first block (issue #3).</p>
 *
 * <p>{@code -Dphasecraft.test.waitForHiddenChunks=false} turns the wait off, and logs for each test how many ticks
 * it ran before its hidden chunks ticked, to show what goes wrong without it; see HiddenChunkDelayMixin.</p>
 */
@Mixin(GameTestInfo.class)
abstract class GameTestInfoHiddenChunksMixin {
    @Unique
    private static final Logger PHASECRAFT_TEST$LOGGER = LoggerFactory.getLogger("phasecraft-test");
    @Unique
    private static final boolean PHASECRAFT_TEST$WAIT = !"false".equals(System.getProperty("phasecraft.test.waitForHiddenChunks"));

    @Shadow private boolean chunksLoaded;
    @Shadow private int tickCount;
    @Shadow @Final private ServerLevel level;
    @Shadow private @Nullable TestInstanceBlockEntity testInstanceBlockEntity;
    /** Without the wait: whether the hidden chunks have been seen ticking. */
    @Unique private boolean phasecraftTest$seenTicking;

    @Shadow public abstract boolean isDone();

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void phasecraftTest$waitForHiddenChunks(GameTestRunner runner, CallbackInfo ci) {
        if (testInstanceBlockEntity == null || isDone() || chunksLoaded && (PHASECRAFT_TEST$WAIT || phasecraftTest$seenTicking)) {
            return;
        }
        ServerLevel hidden = HiddenDimensions.hiddenOf(level.dimension()).map(key -> level.getServer().getLevel(key)).orElse(null);
        if (hidden == null) {
            return;
        }
        boolean ticking = testInstanceBlockEntity.getStructureBoundingBox().intersectingChunks()
                .allMatch(chunk -> phasecraftTest$ticks(hidden, chunk));
        if (PHASECRAFT_TEST$WAIT) {
            if (!ticking) {
                ci.cancel();
            }
        } else if (ticking) {
            // tickCount is -1 before the tick the test function runs in.
            phasecraftTest$seenTicking = true;
            if (chunksLoaded && tickCount >= 0) {
                PHASECRAFT_TEST$LOGGER.warn("{}: not waiting (phasecraft.test.waitForHiddenChunks=false): its hidden chunks"
                        + " started ticking {} tick(s) after the test started", ((GameTestInfo) (Object) this).id(), tickCount + 1);
            }
        }
    }

    /** Blocks, scheduled ticks and entities all tick in this chunk. */
    @Unique
    private static boolean phasecraftTest$ticks(ServerLevel hidden, ChunkPos chunk) {
        return hidden.areEntitiesActuallyLoadedAndTicking(chunk) && hidden.isPositionTickingWithEntitiesLoaded(chunk.pack());
    }
}
