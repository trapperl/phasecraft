package dev.phasecraft.client.compat;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.irisshaders.iris.api.v0.IrisApi;
import net.irisshaders.iris.api.v0.IrisProgram;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Iris shaders mod. While a shader pack is on, Iris draws everything with the pack's programs, and picks
 * the program by render pipeline. It knows vanilla's pipelines; one it does not know is drawn with a program
 * it guesses, which for PhaseCraft's two (the see-through pass of the overlay, and the inside view) drew
 * nothing at all: hidden blocks behind walls and the faint visible world inside the hidden layer were gone.
 * Iris's API lets a mod say which program a pipeline of its own is drawn with: the pack's translucent blocks
 * program (gbuffers_block_translucent), the one vanilla's translucent moving blocks, and so the overlay's
 * other pass, get.
 *
 * <p>Compiled against Iris, never shipped; loaded only when Iris is installed (the only class that names
 * Iris's). Without a shader pack Iris changes nothing and this does nothing.</p>
 */
public final class IrisCompat {
    private static final Logger LOGGER = LoggerFactory.getLogger("phasecraft");

    private IrisCompat() {}

    public static void drawAsTranslucentBlocks(RenderPipeline... pipelines) {
        try {
            for (RenderPipeline pipeline : pipelines) {
                IrisApi.getInstance().assignPipeline(pipeline, IrisProgram.BLOCK_TRANSLUCENT);
            }
        } catch (LinkageError | RuntimeException e) {
            // An Iris without this part of its API: the game still runs.
            LOGGER.warn("PhaseCraft could not tell Iris how to draw the hidden-layer overlay; with a shader pack on, "
                    + "hidden blocks behind visible ones and the inside view may not show", e);
        }
    }
}
