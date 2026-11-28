package dev.phasecraft.test.client;

import net.irisshaders.iris.api.v0.IrisApi;

/** The only test class that names Iris's classes; loaded only in the runs with Iris (see {@link IrisRun}). */
final class IrisPack {
    private IrisPack() {}

    static boolean inUse() {
        return IrisApi.getInstance().isShaderPackInUse();
    }
}
