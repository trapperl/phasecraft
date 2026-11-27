package dev.phasecraft.test.client;

import static dev.phasecraft.test.client.OverlayClientGameTest.assertTrue;
import static dev.phasecraft.test.client.OverlayClientGameTest.waitForGhost;

import dev.phasecraft.client.PhaseCraftClient;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.util.Properties;
import dev.phasecraft.layer.Access;
import dev.phasecraft.layer.HiddenLayer;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

/**
 * The overlay over a real network connection to a dedicated server, as when
 * playing on the dev server: a Survival player (the server default) is sent
 * nothing while the game rule phasecraft:survival_access is off, which it is
 * on a dedicated server that was just set up, without anyone setting it; in
 * Creative, or in Survival with the rule on, the hidden block in front of them
 * arrives.
 */
public class OverlayDedicatedServerClientGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        Properties properties = new Properties();
        properties.setProperty("server-port", Integer.toString(freePort()));
        // No Mojang login check: test games never talk to Mojang (see NoMojangTrafficGameTests).
        properties.setProperty("online-mode", "false");
        try (TestDedicatedServerContext server = context.worldBuilder().createServer(properties);
                TestDedicatedServerConnection connection = server.connect()) {
            // Nobody set the rule: a dedicated server starts a new world with it off (Access.applyServerDefault).
            assertTrue(!server.computeOnServer(minecraft -> minecraft.getGameRules().get(Access.SURVIVAL_ACCESS)),
                    "a new dedicated server started with the game rule phasecraft:survival_access on");
            connection.waitForChunksRender();
            BlockPos ahead = server.computeOnServer(minecraft ->
                    minecraft.getPlayerList().getPlayers().getFirst().blockPosition().above().south(3));
            server.runOnServer(minecraft -> HiddenLayer.of(minecraft.overworld())
                    .setBlock(ahead, Blocks.DIAMOND_BLOCK.defaultBlockState()));
            context.waitTicks(20);
            String held = context.computeOnClient(minecraft -> {
                StringBuilder out = new StringBuilder();
                PhaseCraftClient.layer().forEach((pos, state) -> out.append(BlockPos.of(pos).toShortString()).append('=').append(state).append("; "));
                return out.toString();
            });
            String mode = server.computeOnServer(minecraft -> minecraft.getPlayerList().getPlayers().getFirst().gameMode().toString());
            assertTrue(held.isEmpty(), "survival player (rule off) on a dedicated server was sent hidden blocks (mode " + mode + "): " + held);
            server.runCommand("gamemode creative @a");
            waitForGhost(context, ahead, Blocks.DIAMOND_BLOCK);
            server.runCommand("gamemode survival @a");
            server.runCommand("gamerule phasecraft:survival_access true");
            context.waitTicks(20);
            waitForGhost(context, ahead, Blocks.DIAMOND_BLOCK);
        }
    }

    /** A port nothing listens on, so a dev server already on 25565 does not get in the way. */
    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
