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
    /** A place to look from; first, optionally, a command, then a wait in game ticks (to catch something moving). */
    private record View(String name, String tp, String command, int ticks) {
        View(String name, String tp) {
            this(name, tp, null, 40);
        }
    }

    /** The mountain door's lever (station 24), flipped on. */
    private static final String MOUNTAIN_LEVER = "setblock 326 -60 10 minecraft:lever[face=floor,facing=north,powered=true]";

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
            new View("iris_side", "240.5 -50 -12.5 40 20"),
            new View("waterfall", "262.5 -56 -13.5 0 -5"),
            new View("mountain", "321.5 -52 -15.5 0 5"),
            new View("mountain_door", "321.5 -59 3.5 0 -10"),
            // the door goes an eighth every 4 game ticks from 16 after the lever: about half of it at 30
            new View("mountain_sweep", "321.5 -59 3.5 0 -10", MOUNTAIN_LEVER, 30),
            new View("mountain_open", "321.5 -59 3.5 0 -10", null, 60),
            new View("bridge", "380.5 -50 14.5 180 15"),
            // someone over step 5: the steps within 3 of them appear (a villager that cannot move or fall)
            new View("bridge_walker", "380.5 -50 14.5 180 15", "summon minecraft:villager 379.5 -54 0.5 "
                    + "{NoAI:1b,NoGravity:1b,Silent:1b,Invulnerable:1b}", 40),
            new View("stairs", "428.5 -50 17.5 180 10"),
            // someone on step 6: the steps within 3 of them appear
            new View("stairs_walker", "428.5 -50 17.5 180 10", "summon minecraft:villager 428.5 -53 0.5 "
                    + "{NoAI:1b,NoGravity:1b,Silent:1b,Invulnerable:1b}", 40));

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
                if (view.command() != null) {
                    world.getConnection().waitForChunksRender();
                    server.runCommand(view.command());
                }
                context.waitTicks(view.ticks());
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
