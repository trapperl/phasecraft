package dev.phasecraft.test;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/**
 * Test games must never talk to Mojang or Microsoft: dozens of test runs got
 * this network's address blocked by Mojang's front door once. build.gradle
 * points service discovery at a closed local port and Realms at its local
 * setting; this fails if a test run is started without that.
 */
public class NoMojangTrafficGameTests {
    public static String problem() {
        String discovery = System.getProperty("minecraft.api.discovery.host", "");
        if (!discovery.startsWith("http://127.0.0.1:")) {
            return "minecraft.api.discovery.host is '" + discovery + "', not a local address (see offlineTestArgs in build.gradle)";
        }
        if (!"local".equals(System.getProperty("realms.environment"))) {
            return "realms.environment is not 'local' (see offlineTestArgs in build.gradle)";
        }
        return null;
    }

    @GameTest
    public void testServersDoNotContactMojang(GameTestHelper helper) {
        String problem = problem();
        helper.assertTrue(problem == null, String.valueOf(problem));
        helper.succeed();
    }
}
