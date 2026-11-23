package dev.phasecraft.client;

import dev.phasecraft.phased.PhasedLights;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

/**
 * Keeps the client's copy of the phased lights of its dimension
 * ({@link PhasedLights#CLIENT}) and has the client's own light engine look at
 * every spot that changed, so light it computes itself matches the server's.
 */
final class PhasedLightsClient {
    private PhasedLightsClient() {}

    static void apply(PhasedLights.Sync sync) {
        Set<Long> changed = new HashSet<>();
        if (sync.reset()) {
            changed.addAll(PhasedLights.CLIENT.keySet());
            PhasedLights.CLIENT.clear();
        }
        for (PhasedLights.Light light : sync.lights()) {
            long pos = light.pos().asLong();
            if (light.light() > 0) {
                PhasedLights.CLIENT.put(pos, light.light());
            } else {
                PhasedLights.CLIENT.remove(pos);
            }
            changed.add(pos);
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != null) {
            for (long pos : changed) {
                PhasedLights.recheck(minecraft.level.getLightEngine(), BlockPos.of(pos), PhasedLights.CLIENT.getOrDefault(pos, 0));
            }
        }
    }
}
