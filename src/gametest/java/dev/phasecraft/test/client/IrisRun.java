package dev.phasecraft.test.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;

/**
 * The client GameTest runs with the Iris shaders mod (build.gradle: runClientGameTestIris, shaders off, and
 * runClientGameTestIrisShaders, with a shader pack on).
 *
 * <p>Under a shader pack the pictures are the pack's: its own light, shadows and colours. So the tests keep
 * checking that each thing is drawn (compared with the same view without it), with thresholds for the pack
 * where vanilla's colours were assumed ({@link #SHADER_PACK}).</p>
 */
final class IrisRun {
    /** This run has Iris loaded. */
    static final boolean IRIS = Boolean.getBoolean("phasecraft.test.iris");
    /** This run has a shader pack on. */
    static final boolean SHADER_PACK = Boolean.getBoolean("phasecraft.test.shaderPack");

    private IrisRun() {}

    /**
     * Every client test starts here. Checks the run has the mods it is named for; with Iris, takes Iris's keys
     * away, so no key a test presses can also turn the shader pack off or on (its "Toggle Shaders", K, was
     * PhaseCraft's layer key until 2026-11-27).
     */
    static void prepare(ClientGameTestContext context) {
        boolean loaded = FabricLoader.getInstance().isModLoaded("iris");
        if (loaded != IRIS) {
            throw new AssertionError(IRIS ? "Iris was asked for but is not loaded" : "Iris is loaded in a run without it");
        }
        if (!IRIS) {
            if (SHADER_PACK) {
                throw new AssertionError("a shader pack was asked for in a run without Iris");
            }
            return;
        }
        context.runOnClient(minecraft -> {
            for (KeyMapping key : minecraft.options.keyMappings) {
                if (key.getName().startsWith("iris.keybind.")) {
                    key.setKey(InputConstants.UNKNOWN);
                }
            }
            KeyMapping.resetMapping();
        });
        checkPack(context, "at the start of a test");
    }

    /** The shader pack is on in the run with one, and only there: a picture taken now is what it is said to be. */
    static void checkPack(ClientGameTestContext context, String when) {
        if (!IRIS) {
            return;
        }
        boolean inUse = context.computeOnClient(minecraft -> IrisPack.inUse());
        if (inUse != SHADER_PACK) {
            throw new AssertionError(when + ": " + (SHADER_PACK ? "the shader pack is not on" : "a shader pack is on in the run without one"));
        }
    }

    /** A threshold: the usual one, or the one for the run with a shader pack. */
    static double threshold(double usual, double withShaderPack) {
        return SHADER_PACK ? withShaderPack : usual;
    }
}
