package dev.phasecraft.test.mixin;

import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.util.RandomSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * TEST ONLY (lives in the phasecraft-test mod, never shipped), and off unless asked for.
 *
 * <p>The GameTest server puts its tests at a spot it picks by chance on every run (logged: "N tests are now
 * running at position x, 4, z"), so where chunk borders fall in a test's structure differs from run to run. A
 * test that works by chunks (claims cover whole chunks: OpacClaimChecks) can then pass or fail by where it stood:
 * issue #36, one run in 16. {@code -Dphasecraft.test.startX=X -Dphasecraft.test.startZ=Z} puts the tests where a
 * failed run had them, to see it again:</p>
 *
 * <pre>./gradlew runGameTestOpac -PopacTestFilter='phasecraft-test:opac_claim_checks_opac_claim_stops*'
 *     -PgameTestVmArgs="-Dphasecraft.test.startX=12049140 -Dphasecraft.test.startZ=-13963796"</pre>
 *
 * <p>The tests of a run stand in a row east of that spot in the order they run, which is also by chance: a test
 * stands where it did only if the same tests ran before it (run it alone, if it was the first).</p>
 */
@Mixin(GameTestServer.class)
abstract class GameTestStartPosMixin {
    @Redirect(method = "startTests", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/util/RandomSource;nextIntBetweenInclusive(II)I", ordinal = 0))
    private int phasecraftTest$startX(RandomSource random, int min, int max) {
        int chosen = random.nextIntBetweenInclusive(min, max);
        return Integer.getInteger("phasecraft.test.startX", chosen);
    }

    @Redirect(method = "startTests", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/util/RandomSource;nextIntBetweenInclusive(II)I", ordinal = 1))
    private int phasecraftTest$startZ(RandomSource random, int min, int max) {
        int chosen = random.nextIntBetweenInclusive(min, max);
        return Integer.getInteger("phasecraft.test.startZ", chosen);
    }
}
