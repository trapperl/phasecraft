package dev.phasecraft.test.client;

import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.KeyMapping;

/**
 * No PhaseCraft key's default is a key that vanilla or another loaded mod uses by default (the person,
 * 2026-11-27: "we could pick another key that doesn't clash", after Iris's "Toggle Shaders" turned out to be K,
 * PhaseCraft's key into the hidden layer). Each client run checks what it loads: vanilla, Sodium, and in the Iris
 * runs Iris.
 */
public class KeyDefaultsClientGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        IrisRun.prepare(context);
        List<String> clashes = context.computeOnClient(minecraft -> {
            List<String> found = new ArrayList<>();
            KeyMapping[] all = minecraft.options.keyMappings;
            for (KeyMapping ours : all) {
                if (!ours.getName().startsWith("key.phasecraft.") || ours.getDefaultKey().getValue() == -1) {
                    continue;
                }
                for (KeyMapping other : all) {
                    // key.debug.* are F3 combinations (F3+H...): they act only while F3 is held.
                    if (other != ours && !other.getName().startsWith("key.debug.") && other.getDefaultKey().equals(ours.getDefaultKey())) {
                        found.add(ours.getName() + " and " + other.getName() + " both default to " + ours.getDefaultKey().getName());
                    }
                }
            }
            return found;
        });
        if (!clashes.isEmpty()) {
            throw new AssertionError("PhaseCraft key defaults clash: " + clashes);
        }
    }
}
