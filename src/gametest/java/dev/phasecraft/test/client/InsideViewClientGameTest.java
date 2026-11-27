package dev.phasecraft.test.client;

import static dev.phasecraft.test.client.OverlayClientGameTest.assertTrue;
import static dev.phasecraft.test.client.OverlayClientGameTest.waitUntil;

import dev.phasecraft.client.PhaseCraftClient;
import dev.phasecraft.layer.HiddenDimensions;
import dev.phasecraft.layer.HiddenLayer;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CloudStatus;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * Building from inside the hidden layer, seen by a real client: K goes in (Creative), the visible world is drawn
 * faint around the real hidden blocks, H hides it, K comes back.
 *
 * <p>Scene in a flat world. The camera stands at (0.5, -60, 0.5) looking south (+Z), eyes at Y -58.38:</p>
 * <ul>
 *   <li>a visible 3x3 stone wall around (0, -59, 5), straight ahead;</li>
 *   <li>a hidden gold block behind its middle, at (0, -59, 8): a real block once inside;</li>
 *   <li>the flat world's grass all around.</li>
 * </ul>
 * <p>Inside, a screenshot with the visible world shown is compared with one with H off: the wall and the ground must
 * be there in the first and not in the second, and the gold block must stay gold behind the faint wall. The
 * screenshots are kept in build/run/clientGameTest/screenshots.</p>
 */
public class InsideViewClientGameTest implements FabricClientGameTest {
    private static final BlockPos WALL = new BlockPos(0, -59, 5);
    private static final BlockPos WALL_SIDE = WALL.east();
    private static final BlockPos GOLD = new BlockPos(0, -59, 8);
    private static final BlockPos GROUND = new BlockPos(0, -61, 3);
    private static final BlockPos LATER = new BlockPos(-3, -59, 5);
    private static final double EYE_X = 0.5;
    private static final double EYE_Y = -60 + 1.62;
    private static final double EYE_Z = 0.5;

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            TestServerContext server = world.getServer();
            server.runCommand("gamemode creative @a");
            server.runCommand("time set noon");
            server.runCommand("gamerule advance_time false");
            server.runCommand("tp @a 0.5 -60 0.5 0 0");
            server.runOnServer(minecraft -> {
                ServerLevel overworld = minecraft.overworld();
                for (BlockPos brick : BlockPos.betweenClosed(WALL.offset(-1, -1, 0), WALL.offset(1, 1, 0))) {
                    overworld.setBlockAndUpdate(brick, Blocks.STONE.defaultBlockState());
                }
                HiddenLayer.of(overworld).setBlock(GOLD, Blocks.GOLD_BLOCK.defaultBlockState());
            });
            context.runOnClient(minecraft -> {
                PhaseCraftClient.settings().setEnabled(true);
                PhaseCraftClient.settings().setOpacity(0.45F);
                minecraft.options.cloudStatus().set(CloudStatus.OFF);
                // Flying widens the view a little; inside, the player flies (no ground), and the screenshots are compared.
                minecraft.options.fovEffectScale().set(0.0);
            });
            world.getConnection().waitForChunksRender();
            if (!context.computeOnClient(minecraft -> minecraft.gui.hud.isHidden())) {
                context.getInput().pressKey(options -> options.keyToggleGui);
            }
            context.waitTicks(5);
            assertTrue(context.computeOnClient(minecraft -> PhaseCraftClient.visibleLayer().isEmpty()),
                    "the client holds visible blocks while in the visible world");
            BufferedImage outside = screenshot(context, "inside_before");

            // Survival: K is refused, the player stays.
            server.runCommand("gamemode survival @a");
            waitUntil(context, "survival", minecraft -> !minecraft.player.isCreative());
            context.getInput().pressKey(PhaseCraftClient.TOGGLE_LAYER);
            context.waitTicks(10);
            assertTrue(dimension(context) == Level.OVERWORLD, "K let a Survival player into the hidden layer");
            assertTrue(dimension(server) == Level.OVERWORLD, "K let a Survival player into the hidden layer (server)");
            server.runCommand("gamemode creative @a");
            waitUntil(context, "creative", minecraft -> minecraft.player.isCreative());

            // Creative: K goes in, at the same spot, flying (there is no ground).
            context.getInput().pressKey(PhaseCraftClient.TOGGLE_LAYER);
            waitUntil(context, "K takes a Creative player into the hidden layer", minecraft -> minecraft.level != null
                    && minecraft.level.dimension() == HiddenDimensions.HIDDEN_OVERWORLD && minecraft.player != null);
            assertTrue(dimension(server) == HiddenDimensions.HIDDEN_OVERWORLD, "the server did not move the player");
            waitForVisible(context, WALL, Blocks.STONE);
            waitForVisible(context, GROUND, Blocks.GRASS_BLOCK);
            waitUntil(context, "the real hidden gold block arrives",
                    minecraft -> minecraft.level.getBlockState(GOLD).is(Blocks.GOLD_BLOCK));
            assertTrue(context.computeOnClient(minecraft -> PhaseCraftClient.layer().isEmpty()), "the overlay's ghosts inside the hidden layer");
            assertTrue(context.computeOnClient(minecraft -> minecraft.level.getBlockState(WALL).isAir()), "the visible wall is a real block inside");
            // Solid ground is hollow: the dirt under the grass is not sent, the bedrock's underside is not either.
            assertTrue(context.computeOnClient(minecraft -> PhaseCraftClient.visibleLayer().get(GROUND.below()).isAir()),
                    "a block under the surface was sent");
            world.getConnection().waitForChunksRender();
            context.waitTicks(10);
            double fell = context.computeOnClient(minecraft -> minecraft.player.position().distanceTo(
                    new net.minecraft.world.phys.Vec3(EYE_X, -60, EYE_Z)));
            assertTrue(fell < 0.05, "the player did not stay at the same spot inside (no ground: should be flying): moved " + fell);

            BufferedImage shown = screenshot(context, "inside_visible_world_shown");
            context.getInput().pressKey(PhaseCraftClient.TOGGLE_OVERLAY);
            context.waitTicks(3);
            assertTrue(!context.computeOnClient(minecraft -> PhaseCraftClient.settings().enabled()), "H did not turn the view off");
            BufferedImage hiddenOnly = screenshot(context, "inside_visible_world_off");
            context.getInput().pressKey(PhaseCraftClient.TOGGLE_OVERLAY);
            context.waitTicks(3);

            Box wall = box(shown, WALL_SIDE);
            Box gold = box(shown, GOLD);
            Box ground = project(shown, GROUND.getX() + 0.5, GROUND.getY() + 1, GROUND.getZ() + 0.5, 3);
            // The wall, where nothing hidden is behind it: there with the view on, gone with it off.
            double wallThere = wall.distance(shown, hiddenOnly);
            assertTrue(wallThere > 12, "the visible wall is not drawn inside the hidden layer: differs by " + wallThere + " at " + wall);
            double groundThere = ground.distance(shown, hiddenOnly);
            assertTrue(groundThere > 12, "the visible ground is not drawn inside the hidden layer: differs by " + groundThere + " at " + ground);
            // Faint: the wall is nearer to the empty hidden layer behind it than to the solid stone it is.
            double wallFromSolid = wall.distance(shown, outside);
            assertTrue(wallThere < wallFromSolid, "the visible wall is not faint: " + wallThere + " from nothing, " + wallFromSolid + " from solid");
            // The real hidden gold block behind the wall stands out: gold with the view off, still gold with it on.
            double goldOff = gold.mean(hiddenOnly, true);
            double goldOn = gold.mean(shown, true);
            assertTrue(goldOff > 60, "the hidden gold block is not there: red minus blue " + goldOff + " at " + gold);
            assertTrue(goldOn > goldOff * 0.6, "the hidden gold block does not stand out behind the faint wall: " + goldOn + " of " + goldOff);
            // In the visible world it was a ghost seen through the solid wall, much fainter.
            assertTrue(gold.mean(outside, true) < goldOn * 0.6, "the gold block was as clear from the visible world: "
                    + gold.mean(outside, true) + " against " + goldOn);

            // Improved transparency (order-independent) draws the visible world too.
            context.runOnClient(minecraft -> minecraft.options.improvedTransparency().set(true));
            context.waitTicks(5);
            BufferedImage improved = screenshot(context, "inside_improved_transparency");
            double improvedWall = wall.distance(improved, hiddenOnly);
            assertTrue(improvedWall > 12, "the visible wall is not drawn with improved transparency: differs by " + improvedWall);
            assertTrue(gold.mean(improved, true) > goldOff * 0.6, "the hidden gold block does not stand out with improved transparency: "
                    + gold.mean(improved, true) + " of " + goldOff);
            context.runOnClient(minecraft -> minecraft.options.improvedTransparency().set(false));
            context.waitTicks(5);

            // A visible change arrives while inside; so does its removal.
            server.runCommand("setblock " + LATER.getX() + " " + LATER.getY() + " " + LATER.getZ() + " minecraft:emerald_block");
            waitForVisible(context, LATER, Blocks.EMERALD_BLOCK);
            server.runCommand("setblock " + LATER.getX() + " " + LATER.getY() + " " + LATER.getZ() + " minecraft:air");
            waitUntil(context, "the removed visible block goes", minecraft -> PhaseCraftClient.visibleLayer().get(LATER).isAir());
            // Far away the chunks are dropped; back, they return.
            // (A bare /tp from the console would take the player to the console's dimension, the overworld.)
            server.runCommand("execute in phasecraft:hidden_overworld run tp @a 1000.5 -60 0.5 0 0");
            waitUntil(context, "the client forgets visible chunks out of range", minecraft -> PhaseCraftClient.visibleLayer().get(WALL).isAir());
            waitForVisible(context, new BlockPos(1000, -61, 0), Blocks.GRASS_BLOCK);
            assertTrue(dimension(server) == HiddenDimensions.HIDDEN_OVERWORLD, "the player left the hidden layer");
            server.runCommand("execute in phasecraft:hidden_overworld run tp @a 0.5 -60 0.5 0 0");
            waitForVisible(context, WALL, Blocks.STONE);
            waitUntil(context, "the far chunks are forgotten", minecraft -> PhaseCraftClient.visibleLayer().get(new BlockPos(1000, -61, 0)).isAir());

            // K again: back out, the view gone, the world as it was.
            context.getInput().pressKey(PhaseCraftClient.TOGGLE_LAYER);
            waitUntil(context, "K brings the player back", minecraft -> minecraft.level != null
                    && minecraft.level.dimension() == Level.OVERWORLD && minecraft.player != null);
            waitUntil(context, "the view is dropped outside", minecraft -> PhaseCraftClient.visibleLayer().isEmpty());
            OverlayClientGameTest.waitForGhost(context, GOLD, Blocks.GOLD_BLOCK);
            world.getConnection().waitForChunksRender();
            context.waitTicks(10);
            BufferedImage back = screenshot(context, "inside_back_outside");
            double wallBack = wall.distance(back, outside);
            assertTrue(wallBack < 6, "back outside the wall does not look as before: differs by " + wallBack);
        }
    }

    private static ResourceKey<Level> dimension(ClientGameTestContext context) {
        return context.computeOnClient(minecraft -> minecraft.level.dimension());
    }

    private static ResourceKey<Level> dimension(TestServerContext server) {
        return server.computeOnServer(minecraft -> minecraft.getPlayerList().getPlayers().getFirst().level().dimension());
    }

    static void waitForVisible(ClientGameTestContext context, BlockPos pos, Block block) {
        waitUntil(context, "client receives visible " + block + " at " + pos.toShortString(),
                minecraft -> PhaseCraftClient.visibleLayer().get(pos).is(block));
    }

    static BufferedImage screenshot(ClientGameTestContext context, String name) {
        try {
            return ImageIO.read(context.takeScreenshot(name).toFile());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A small square of screen pixels. */
    record Box(int x, int y, int half) {
        /** Mean of red minus blue (gold is far above 0, grey and sky are not), or of brightness. */
        double mean(BufferedImage image, boolean redMinusBlue) {
            long sum = 0;
            int count = 0;
            for (int px = x - half; px <= x + half; px++) {
                for (int py = y - half; py <= y + half; py++) {
                    int rgb = image.getRGB(px, py);
                    int red = (rgb >> 16) & 0xFF;
                    int green = (rgb >> 8) & 0xFF;
                    int blue = rgb & 0xFF;
                    sum += redMinusBlue ? red - blue : red + green + blue;
                    count++;
                }
            }
            return (double) sum / count;
        }

        /** Mean difference per pixel between two screenshots here, over the three colours (0 to 255). */
        double distance(BufferedImage a, BufferedImage b) {
            long sum = 0;
            int count = 0;
            for (int px = x - half; px <= x + half; px++) {
                for (int py = y - half; py <= y + half; py++) {
                    int p = a.getRGB(px, py);
                    int q = b.getRGB(px, py);
                    sum += Math.abs(((p >> 16) & 0xFF) - ((q >> 16) & 0xFF)) + Math.abs(((p >> 8) & 0xFF) - ((q >> 8) & 0xFF))
                            + Math.abs((p & 0xFF) - (q & 0xFF));
                    count += 3;
                }
            }
            return (double) sum / count;
        }
    }

    /** Pixels around where a world point appears, looking south (+Z) with yaw 0 and pitch 0. */
    private static Box project(BufferedImage image, double worldX, double worldY, double worldZ, int half) {
        double focal = (image.getHeight() / 2.0) / Math.tan(Math.toRadians(35));
        double depth = worldZ - EYE_Z;
        int x = (int) Math.round(image.getWidth() / 2.0 - (worldX - EYE_X) / depth * focal);
        int y = (int) Math.round(image.getHeight() / 2.0 - (worldY - EYE_Y) / depth * focal);
        return new Box(x, y, half);
    }

    /** The middle of a block's north face (the face toward the camera), staying inside it. */
    private static Box box(BufferedImage image, BlockPos pos) {
        double depth = pos.getZ() - EYE_Z;
        double focal = (image.getHeight() / 2.0) / Math.tan(Math.toRadians(35));
        int half = (int) Math.max(2, 0.25 / depth * focal);
        return project(image, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ(), half);
    }
}
