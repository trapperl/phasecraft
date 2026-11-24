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

/**
 * Screenshots of the examples area with the overlay on and off. Runs only
 * when PHASECRAFT_EXAMPLES_COMMANDS names a file of commands (one per line,
 * as tools/examples.py builds them).
 */
public class ExamplesTourClientGameTest implements FabricClientGameTest {
    private record View(String name, String tp) {}

    private static final List<View> VIEWS = List.of(
            new View("overview", "24.5 -52 -14 0 30"),
            new View("stations_1_to_4", "9.5 -58 -5 0 25"),
            new View("stations_5_to_9", "40.5 -58 -5 0 25"),
            new View("door_corridor", "61.5 -59 6.5 180 10"),
            new View("door_north", "61.5 -59 -9.5 0 10"),
            new View("door_above", "61.5 -53 4.5 180 50"),
            new View("door_side", "70.5 -57.5 -1 90 15"),
            new View("stairs_above", "80.0 -48 -10 0 45"),
            new View("stairs_down", "80.0 -55 -3.5 0 40"),
            new View("stairs_cellar", "80.0 -59 2.5 180 -25"),
            new View("door3_tunnel", "97.5 -60 0.5 180 5"),
            new View("door3_room", "97.5 -60 -15.5 0 5"),
            new View("room_corridor", "150.5 -60 -1.5 180 5"),
            new View("room_inside", "150.5 -60 -11.5 0 5"),
            new View("trap_corridor", "162.5 -58 -4.5 0 30"),
            new View("trap_above", "162.5 -54 -2.5 0 60"),
            new View("shifter_wall", "172.0 -59 -5.5 0 10"),
            new View("watchers", "186.0 -58 -8.5 0 15"),
            new View("mail", "202.5 -58 -7.5 0 25"),
            new View("iris", "228.5 -54 -9.5 0 0"),
            new View("iris_side", "240.5 -50 -12.5 40 20"));

    @Override
    public void runTest(ClientGameTestContext context) {
        String file = System.getenv("PHASECRAFT_EXAMPLES_COMMANDS");
        if (file == null || file.isBlank()) {
            return;
        }
        List<String> commands;
        try {
            commands = Files.readAllLines(Path.of(file)).stream().filter(line -> !line.isBlank()).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            TestServerContext server = world.getServer();
            server.runCommand("gamemode spectator @a");
            server.runCommand("tp @a 24.5 -52 -14 0 30");
            world.getConnection().waitForChunksRender();
            commands.forEach(server::runCommand);
            context.runOnClient(minecraft -> {
                PhaseCraftClient.settings().setEnabled(true);
                PhaseCraftClient.settings().setOpacity(0.45F);
            });
            context.getInput().pressKey(options -> options.keyToggleGui);
            context.runOnClient(minecraft -> minecraft.gui.hud.getChat().clearMessages(false));
            for (View view : VIEWS) {
                server.runCommand("tp @a " + view.tp());
                context.waitTicks(40);
                world.getConnection().waitForChunksRender();
                context.takeScreenshot("tour_" + view.name() + "_on");
                context.runOnClient(minecraft -> PhaseCraftClient.settings().setEnabled(false));
                context.waitTicks(2);
                context.takeScreenshot("tour_" + view.name() + "_off");
                context.runOnClient(minecraft -> PhaseCraftClient.settings().setEnabled(true));
            }
        }
    }
}
