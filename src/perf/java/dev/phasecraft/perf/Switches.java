package dev.phasecraft.perf;

/**
 * MEASUREMENT ONLY (the phasecraft-perf mod, never shipped). Turns parts of PhaseCraft off at run time, so the
 * same scenario can be measured with and without them in one server process. All true: PhaseCraft as shipped.
 */
public final class Switches {
    /** PhaseLink.onBlockChanged: queueing port refreshes for the other layer on signal-relevant block changes. */
    public static boolean linkHook = true;
    /** PhaseLink.upAt: every visible signal read also looking for a hidden port at that spot. */
    public static boolean bridge = true;
    /** PhaseLink.followTicking: hidden chunks loaded and ticking under every ticking visible chunk. */
    public static boolean followTicking = true;
    /** OverlaySync.onBlockChanged and tick. */
    public static boolean overlay = true;
    /** PhasedInput.onBlockChanged. */
    public static boolean inputHook = true;
    /** PhasedInput.tick: the every-tick check of released phased plates and tripwire. */
    public static boolean inputTick = true;
    /** PhasedLights.onBlockChanged. */
    public static boolean lightsHook = true;
    /** PhasedWatchers.onBlockChanged. */
    public static boolean watchersHook = true;

    /** Block changes seen by PhaseCraft's hook (counted whatever the switches say). */
    public static long visibleChanges;
    public static long hiddenChanges;

    private Switches() {}

    public static void reset() {
        linkHook = bridge = followTicking = overlay = inputHook = inputTick = lightsHook = watchersHook = true;
    }

    /** All five block-change hooks. */
    public static void hooksOff() {
        linkHook = overlay = inputHook = lightsHook = watchersHook = false;
    }
}
