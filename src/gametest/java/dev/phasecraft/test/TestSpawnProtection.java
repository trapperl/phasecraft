package dev.phasecraft.test;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;

/**
 * TEST ONLY: spots a test declares spawn-protected. Only a dedicated server has
 * spawn protection (MinecraftServer.isUnderSpawnProtection is false elsewhere,
 * the GameTest server included), so SpawnProtectionMixin answers for these
 * spots as a dedicated server would near spawn: protected in the Overworld,
 * and in no other dimension.
 */
public final class TestSpawnProtection {
    public static final Set<BlockPos> PROTECTED = ConcurrentHashMap.newKeySet();

    private TestSpawnProtection() {}
}
