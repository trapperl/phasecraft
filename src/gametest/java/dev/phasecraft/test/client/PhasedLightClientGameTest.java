package dev.phasecraft.test.client;

import dev.phasecraft.PhaseCraftBlocks;
import dev.phasecraft.client.PhaseCraftClient;
import dev.phasecraft.layer.HiddenLayer;
import dev.phasecraft.test.NoMojangTrafficGameTests;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CloudStatus;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;

/**
 * Phased light, seen by a real client: a hidden glowstone inside the far wall of
 * a closed stone room, standing on a hidden phase block, lights the room on the
 * player's screen. Clients recompute light themselves when nearby blocks change;
 * when the visible wall block at the light's own spot is swapped, the server's
 * light does not change (so it sends no correction), and the client must still
 * know the light is there.
 */
public class PhasedLightClientGameTest implements FabricClientGameTest {
    /** The room's inside: x -2..2, y -60..-58, z 6..10; walls and roof of stone around it. */
    private static final BlockPos LIGHT = new BlockPos(0, -59, 11);   // inside the far wall
    private static final BlockPos IN_FRONT = LIGHT.north();           // the room cell in front of it

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static int clientLight(ClientGameTestContext context, BlockPos pos) {
        return context.computeOnClient(minecraft -> minecraft.level.getBrightness(LightLayer.BLOCK, pos));
    }

    /** Waits up to ~10 seconds for the client's block light at pos to reach the expected value. */
    private static void awaitClientLight(ClientGameTestContext context, BlockPos pos, int expected, String what) {
        int seen = -1;
        for (int i = 0; i < 200; i++) {
            seen = clientLight(context, pos);
            if (seen == expected) {
                return;
            }
            context.waitTicks(1);
        }
        throw new AssertionError(what + ": client block light " + seen + ", expected " + expected);
    }

    @Override
    public void runTest(ClientGameTestContext context) {
        String mojang = NoMojangTrafficGameTests.problem();
        check(mojang == null, "test client would contact Mojang: " + mojang);
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            TestServerContext server = world.getServer();
            server.runCommand("gamemode spectator @a");
            server.runCommand("time set midnight");
            server.runCommand("gamerule advance_time false");
            server.runCommand("fill -3 -61 5 3 -57 11 minecraft:stone");
            server.runCommand("fill -2 -60 6 2 -58 10 minecraft:air");
            server.runCommand("tp @a 0.5 -60 7.5 0 0");
            context.runOnClient(minecraft -> minecraft.options.cloudStatus().set(CloudStatus.OFF));
            world.getConnection().waitForChunksRender();
            awaitClientLight(context, IN_FRONT, 0, "dark room before the hidden light");

            server.runOnServer(minecraft -> {
                ServerLevel overworld = minecraft.overworld();
                HiddenLayer layer = HiddenLayer.of(overworld);
                layer.setBlock(LIGHT.below(), PhaseCraftBlocks.PHASE_BLOCK.defaultBlockState());
                layer.setBlock(LIGHT, Blocks.GLOWSTONE.defaultBlockState());
            });
            awaitClientLight(context, IN_FRONT, 14, "room lit by the phased glowstone in the wall");
            context.getInput().pressKey(options -> options.keyToggleGui);
            context.runOnClient(minecraft -> PhaseCraftClient.settings().setEnabled(false));
            context.waitTicks(5);
            context.takeScreenshot("phased_light_room");
            context.runOnClient(minecraft -> PhaseCraftClient.settings().setEnabled(true));
            context.waitTicks(5);
            context.takeScreenshot("phased_light_room_overlay");
            context.runOnClient(minecraft -> PhaseCraftClient.settings().setEnabled(false));

            // Swap the visible wall block at the light's own spot: the client relights it itself.
            server.runCommand("setblock " + LIGHT.getX() + " " + LIGHT.getY() + " " + LIGHT.getZ() + " minecraft:cobblestone");
            context.waitTicks(40);
            awaitClientLight(context, IN_FRONT, 14, "room still lit after the wall block at the light's spot was swapped");

            // Hidden light off: the room goes dark on the client too.
            server.runOnServer(minecraft -> HiddenLayer.of(minecraft.overworld()).removeBlock(LIGHT));
            awaitClientLight(context, IN_FRONT, 0, "room dark again after the hidden glowstone was removed");
        }
    }
}
