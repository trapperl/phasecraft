package dev.phasecraft.test;

import java.util.ArrayDeque;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;

/**
 * TEST ONLY. Holds back the hidden layer's chunk tickets by a number of server ticks
 * ({@code -Dphasecraft.test.hiddenChunkDelay=N}, off by default), as a busy machine does by chance: see
 * HiddenChunkDelayMixin.
 */
public final class HiddenChunkDelay {
    public static final int TICKS = Integer.getInteger("phasecraft.test.hiddenChunkDelay", 0);

    private record Held(int due, Runnable action) {}

    private static final ArrayDeque<Held> HELD = new ArrayDeque<>();
    private static boolean registered;

    private HiddenChunkDelay() {}

    /** Runs the action TICKS server ticks from now, in the order given. */
    public static void hold(MinecraftServer server, Runnable action) {
        if (!registered) {
            registered = true;
            ServerTickEvents.END_SERVER_TICK.register(HiddenChunkDelay::release);
        }
        HELD.add(new Held(server.getTickCount() + TICKS, action));
    }

    private static void release(MinecraftServer server) {
        while (!HELD.isEmpty() && HELD.peek().due() <= server.getTickCount()) {
            HELD.poll().action().run();
        }
    }
}
