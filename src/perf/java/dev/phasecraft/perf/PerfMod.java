package dev.phasecraft.perf;

import net.fabricmc.api.ModInitializer;

/** MEASUREMENT ONLY: the phasecraft-perf mod (./gradlew runPerf, see src/perf/README.md). */
public final class PerfMod implements ModInitializer {
    @Override
    public void onInitialize() {
        Runner.register();
    }
}
