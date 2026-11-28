package dev.phasecraft.test.client;

import dev.phasecraft.client.PhaseCraftClient;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;

/**
 * Renders the trailer's frames (tools/trailer.py writes the script, then turns the frames into a video), the world frozen
 * ({@code /tick freeze}) and stepped one game tick a frame, so the video runs at the game's own speed however long a
 * frame takes to draw. Runs only when
 * PHASECRAFT_TRAILER names a script and PHASECRAFT_EXAMPLES_COMMANDS the examples' commands (as for the tour). The
 * script, a line each:
 * <ul>
 *   <li>{@code goto x y z yaw pitch}: the camera there, every chunk around drawn, no frame;</li>
 *   <li>{@code run <command>}: a server command, now;</li>
 *   <li>{@code overlay on|off}: the hidden layer's overlay;</li>
 *   <li>{@code frame x y z yaw pitch}: the world a game tick on, the camera there, a frame (frames/NNNNN.png,
 *       numbered from 0);</li>
 *   <li>{@code framecmd <command>}: as frame, the command placing the camera (to follow something moving:
 *       {@code execute at <entity> run tp @a ~ ~5 ~-8 0 30});</li>
 *   <li>{@code wait <ticks>}: the world game ticks on, no frame.</li>
 * </ul>
 */
public class TrailerClientGameTest implements FabricClientGameTest {
    static final int WIDTH = 1280;
    static final int HEIGHT = 720;

    @Override
    public void runTest(ClientGameTestContext context) {
        IrisRun.prepare(context);
        String script = System.getenv("PHASECRAFT_TRAILER");
        String examples = System.getenv("PHASECRAFT_EXAMPLES_COMMANDS");
        if (script == null || script.isBlank() || examples == null || examples.isBlank()) {
            return;
        }
        List<String> lines;
        List<String> commands;
        try {
            lines = Files.readAllLines(Path.of(script)).stream().filter(line -> !line.isBlank() && !line.startsWith("#")).toList();
            commands = Files.readAllLines(Path.of(examples)).stream().filter(line -> !line.isBlank()).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Path frames = Path.of(script).toAbsolutePath().getParent().resolve("frames");
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            TestServerContext server = world.getServer();
            server.runCommand("gamemode spectator @a");
            server.runCommand("time set 6000");
            server.runCommand("tp @a 24.5 -52 -14 0 30");
            world.getConnection().waitForChunksRender();
            commands.forEach(server::runCommand);
            context.waitTicks(100);                       // the examples settle (their shifters take what they hold)
            context.runOnClient(minecraft -> {
                PhaseCraftClient.settings().setEnabled(false);
                PhaseCraftClient.settings().setOpacity(0.45F);
            });
            context.getInput().pressKey(options -> options.keyToggleGui);
            context.runOnClient(minecraft -> minecraft.gui.hud.getChat().clearMessages(false));
            server.runCommand("tick freeze");
            int frame = 0;
            for (String line : lines) {
                String[] words = line.split(" ", 2);
                String rest = words.length > 1 ? words[1] : "";
                switch (words[0]) {
                    case "goto" -> {
                        server.runCommand("tp @a " + rest);
                        context.waitTicks(2);
                        world.getConnection().waitForChunksRender();
                    }
                    case "run" -> server.runCommand(rest);
                    case "overlay" -> {
                        boolean on = rest.equals("on");
                        context.runOnClient(minecraft -> PhaseCraftClient.settings().setEnabled(on));
                    }
                    case "wait" -> {
                        server.runCommand("tick step " + rest);
                        context.waitTicks(Integer.parseInt(rest) + 1);
                    }
                    case "frame", "framecmd" -> {
                        server.runCommand("tick step 1");
                        context.waitTick();
                        server.runCommand(words[0].equals("frame") ? "tp @a " + rest : rest);
                        context.waitTicks(2);
                        context.takeScreenshot(TestScreenshotOptions.of(String.format("%05d", frame++))
                                .disableCounterPrefix().withSize(WIDTH, HEIGHT).withDestinationDir(frames));
                    }
                    default -> throw new IllegalArgumentException("trailer script: " + line);
                }
            }
            server.runCommand("tick unfreeze");
        }
    }
}
