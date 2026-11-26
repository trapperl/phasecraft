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
    private static final String TOWER_LEVER = "setblock 543 -60 1 minecraft:lever[face=floor,facing=north,powered=true]";
    private static final String COTTAGE_LEVER = "setblock 572 -60 0 minecraft:lever[face=floor,facing=north,powered=true]";
    private static final String OCEAN_LEVER = "setblock 589 -60 0 minecraft:lever[face=floor,facing=south,powered=true]";

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
                    + "{NoAI:1b,NoGravity:1b,Silent:1b,Invulnerable:1b}", 40),
            new View("sorter", "463.5 -56.5 4.5 180 20"),
            new View("tower", "545.5 -57 -9.5 0 -12"),
            // the first block goes 42 game ticks after the lever, then one every 2: at 130, courses 7 and 6 and most of 5 gone
            new View("tower_coming_down", "545.5 -57 -9.5 0 -12", TOWER_LEVER, 44),
            new View("cottage", "572.5 -55 -8.5 0 18"),
            // the roof goes about 2 game ticks after the lever, then a layer every 6: at 11, the roof and the top of the walls gone
            new View("cottage_going", "572.5 -55 -8.5 0 18", COTTAGE_LEVER, 11),
            new View("cottage_gone", "572.5 -55 -8.5 0 18", null, 40),
            // from above the pool's north rim, looking down into it (the sea fills the shaft's box once built)
            new View("ocean", "593.5 -48 -1.5 0 50", null, 120),
            // the walls and filler come back from the seabed (16 game ticks), then the filler goes from the top
            new View("ocean_opening", "593.5 -48 -1.5 0 50", OCEAN_LEVER, 26),
            new View("ocean_open", "593.5 -48 -1.5 0 50", null, 80),
            new View("ocean_closed_again", "593.5 -48 -1.5 0 50",
                    "setblock 589 -60 0 minecraft:lever[face=floor,facing=south,powered=false]", 200),
            new View("dance", "616.5 -56 -1.5 0 40"),
            // chickens that stay put (small: the lamp under each shows): the lamps under them light
            new View("dance_one", "616.5 -56 -1.5 0 40", "summon minecraft:chicken 615.5 -60 3.5 {NoAI:1b,Silent:1b}", 20),
            new View("dance_two", "616.5 -56 -1.5 0 40", "summon minecraft:chicken 618.5 -60 5.5 {NoAI:1b,Silent:1b}", 20),
            new View("lock", "634.5 -57.5 7.5 180 10"),
            // on, off, on, on: the first three levers flipped, the door still shut; the fourth opens it
            new View("lock_two_set", "634.5 -57.5 7.5 180 10",
                    "setblock 637 -59 0 minecraft:lever[face=wall,facing=south,powered=true]", 10),
            new View("lock_three_set", "634.5 -57.5 7.5 180 10",
                    "setblock 627 -59 0 minecraft:lever[face=wall,facing=south,powered=true]", 40),
            new View("lock_open", "634.5 -57.5 7.5 180 10",
                    "setblock 640 -59 0 minecraft:lever[face=wall,facing=south,powered=true]", 50));

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
