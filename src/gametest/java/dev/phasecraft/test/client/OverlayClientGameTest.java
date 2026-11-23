package dev.phasecraft.test.client;

import dev.phasecraft.client.PhaseCraftClient;
import dev.phasecraft.layer.HiddenLayer;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.function.Predicate;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * The hidden-layer overlay, seen by a real client.
 *
 * <p>Scene in a flat world. The camera stands at (0.5, -60, 0.5) looking
 * south (+Z), eyes at Y -58.38:</p>
 * <ul>
 *   <li>a hidden gold block floating in the air at (0, -59, 4), straight ahead;</li>
 *   <li>a visible stone block at (-3, -59, 5), to the right, with a hidden
 *       lapis block at the same position;</li>
 *   <li>a hidden redstone block inside the visible grass at (1, -61, 3), just
 *       ahead and to the left;</li>
 *   <li>a visible 3x3 stone wall around (3, -59, 5), to the left, with a hidden
 *       emerald block behind its middle, which must show through it, fainter;</li>
 *   <li>up in the sky, a hidden chest (no ordinary model: shown as its shape),
 *       and later, briefly, hidden lava (it spreads by vanilla rules).</li>
 * </ul>
 * <p>Screenshots with the overlay on and off are compared where each block
 * appears on screen: the ghosts must tint those pixels toward their own
 * colour, more strongly at higher opacity, and not at all when the overlay is
 * off. The screenshots are kept in build/run/clientGameTest/screenshots.</p>
 *
 * <p>Then sync: hidden changes reach the client; out-of-range chunks are
 * dropped and come back; Survival players and players inside the hidden
 * layer are sent nothing.</p>
 */
public class OverlayClientGameTest implements FabricClientGameTest {
    private static final BlockPos GOLD = new BlockPos(0, -59, 4);
    private static final BlockPos STONE = new BlockPos(-3, -59, 5);
    private static final BlockPos BURIED = new BlockPos(1, -61, 3);
    private static final BlockPos WALL = new BlockPos(3, -59, 5);
    private static final BlockPos BEHIND_WALL = WALL.south();
    private static final BlockPos CHEST = new BlockPos(0, -57, 4);
    private static final BlockPos LAVA = new BlockPos(2, -57, 4);
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
                HiddenLayer layer = HiddenLayer.of(overworld);
                overworld.setBlockAndUpdate(STONE, Blocks.STONE.defaultBlockState());
                for (BlockPos brick : BlockPos.betweenClosed(WALL.offset(-1, -1, 0), WALL.offset(1, 1, 0))) {
                    overworld.setBlockAndUpdate(brick, Blocks.STONE.defaultBlockState());
                }
                layer.setBlock(BEHIND_WALL, Blocks.EMERALD_BLOCK.defaultBlockState());
                layer.setBlock(CHEST, Blocks.CHEST.defaultBlockState());
                layer.setBlock(GOLD, Blocks.GOLD_BLOCK.defaultBlockState());
                layer.setBlock(STONE, Blocks.LAPIS_BLOCK.defaultBlockState());
                layer.setBlock(BURIED, Blocks.REDSTONE_BLOCK.defaultBlockState());
            });
            context.runOnClient(minecraft -> {
                PhaseCraftClient.settings().setEnabled(true);
                PhaseCraftClient.settings().setOpacity(0.45F);
                // Clouds drift even with time stopped and would change the sky
                // pixels between screenshots.
                minecraft.options.cloudStatus().set(CloudStatus.OFF);
            });
            world.getConnection().waitForChunksRender();
            waitForGhost(context, GOLD, Blocks.GOLD_BLOCK);
            waitForGhost(context, STONE, Blocks.LAPIS_BLOCK);
            waitForGhost(context, BURIED, Blocks.REDSTONE_BLOCK);
            waitForGhost(context, BEHIND_WALL, Blocks.EMERALD_BLOCK);
            waitForGhost(context, CHEST, Blocks.CHEST);
            context.getInput().pressKey(options -> options.keyToggleGui);
            context.waitTicks(5);

            // Rendering: on at 45%, off, on at 65%.
            BufferedImage on45 = screenshot(context, "overlay_on_45");
            context.getInput().pressKey(PhaseCraftClient.TOGGLE_OVERLAY);
            context.waitTicks(2);
            assertTrue(!context.computeOnClient(minecraft -> PhaseCraftClient.settings().enabled()), "H did not turn the overlay off");
            BufferedImage off = screenshot(context, "overlay_off");
            context.getInput().pressKey(PhaseCraftClient.TOGGLE_OVERLAY);
            context.getInput().pressKey(PhaseCraftClient.MORE_OPAQUE);
            context.getInput().pressKey(PhaseCraftClient.MORE_OPAQUE);
            context.waitTicks(2);
            int percent = context.computeOnClient(minecraft -> PhaseCraftClient.settings().opacityPercent());
            assertTrue(percent == 65, "two presses of ] should give 65% opacity, got " + percent);
            BufferedImage on65 = screenshot(context, "overlay_on_65");

            Box gold = box(on45, GOLD);
            Box stone = box(on45, STONE);
            Box buried = topBox(on45, BURIED);
            Box wall = box(on45, BEHIND_WALL);

            // Gold over the sky: redder, less blue; stronger at 65%.
            double goldTint45 = gold.mean(on45, Channel.RED_MINUS_BLUE) - gold.mean(off, Channel.RED_MINUS_BLUE);
            double goldTint65 = gold.mean(on65, Channel.RED_MINUS_BLUE) - gold.mean(off, Channel.RED_MINUS_BLUE);
            assertTrue(goldTint45 > 40, "gold ghost in the air not drawn: tint " + goldTint45 + " at " + gold);
            assertTrue(goldTint65 > goldTint45 + 15, "higher opacity not stronger: " + goldTint45 + " -> " + goldTint65);
            // Lapis over the visible stone at the same position: bluer.
            double stoneTint = stone.mean(on45, Channel.BLUE_MINUS_RED) - stone.mean(off, Channel.BLUE_MINUS_RED);
            assertTrue(stoneTint > 25, "lapis ghost on the visible stone not drawn: tint " + stoneTint + " at " + stone);
            // Redstone block inside the grass: its top shows on the grass, redder.
            double buriedTint = buried.mean(on45, Channel.RED_MINUS_GREEN) - buried.mean(off, Channel.RED_MINUS_GREEN);
            assertTrue(buriedTint > 25, "buried redstone ghost not drawn: tint " + buriedTint + " at " + buried);
            // Emerald hidden behind a visible stone block: shows through it, faintly.
            double throughTint = wall.mean(on45, Channel.GREEN_MINUS_RED) - wall.mean(off, Channel.GREEN_MINUS_RED);
            assertTrue(throughTint > 10, "emerald ghost behind the wall does not show through: tint " + throughTint + " at " + wall);
            assertTrue(throughTint < stoneTint, "see-through ghost as strong as one in front: " + throughTint + " vs " + stoneTint);
            // Overlay off: the visible stone blocks look like plain stone.
            double offStone = stone.mean(off, Channel.BLUE_MINUS_RED);
            assertTrue(Math.abs(offStone) < 12, "stone not grey with the overlay off: " + offStone);
            double offWall = wall.mean(off, Channel.GREEN_MINUS_RED);
            assertTrue(Math.abs(offWall) < 12, "wall not grey with the overlay off: " + offWall);

            // A chest (drawn by a special renderer, so shown as its shape), up in the sky.
            Box chest = box(on45, CHEST);
            double chestTint = chest.mean(on45, Channel.RED_MINUS_BLUE) - chest.mean(off, Channel.RED_MINUS_BLUE);
            assertTrue(chestTint > 40, "hidden chest not drawn: tint " + chestTint + " at " + chest);

            // Improved transparency (order-independent) draws ghosts too.
            context.runOnClient(minecraft -> {
                minecraft.options.improvedTransparency().set(true);
                PhaseCraftClient.settings().setOpacity(0.45F);
            });
            context.waitTicks(5);
            BufferedImage improved = screenshot(context, "overlay_improved_transparency");
            double improvedGold = gold.mean(improved, Channel.RED_MINUS_BLUE) - gold.mean(off, Channel.RED_MINUS_BLUE);
            double improvedWall = wall.mean(improved, Channel.GREEN_MINUS_RED) - wall.mean(off, Channel.GREEN_MINUS_RED);
            assertTrue(improvedGold > 40, "gold ghost not drawn with improved transparency: " + improvedGold);
            assertTrue(improvedWall > 10, "see-through ghost not drawn with improved transparency: " + improvedWall);
            context.runOnClient(minecraft -> minecraft.options.improvedTransparency().set(false));
            context.waitTicks(5);

            // Hidden lava, up in the sky. It is real lava and spreads after 30 ticks,
            // so it is only there for two quick screenshots.
            server.runOnServer(minecraft -> HiddenLayer.of(minecraft.overworld()).setBlock(LAVA, Blocks.LAVA.defaultBlockState()));
            waitForGhost(context, LAVA, Blocks.LAVA);
            BufferedImage lavaOn = screenshot(context, "lava_on");
            context.runOnClient(minecraft -> PhaseCraftClient.settings().setEnabled(false));
            context.waitTicks(1);
            BufferedImage lavaOff = screenshot(context, "lava_off");
            context.runOnClient(minecraft -> PhaseCraftClient.settings().setEnabled(true));
            server.runOnServer(minecraft -> HiddenLayer.of(minecraft.overworld()).removeBlock(LAVA));
            Box lava = project(lavaOn, LAVA.getX() + 0.5, LAVA.getY() + 0.4, LAVA.getZ(), 4);
            double lavaTint = lava.mean(lavaOn, Channel.RED_MINUS_BLUE) - lava.mean(lavaOff, Channel.RED_MINUS_BLUE);
            assertTrue(lavaTint > 40, "hidden lava not drawn: tint " + lavaTint + " at " + lava);

            // Sync: removing a hidden block removes the ghost.
            server.runOnServer(minecraft -> HiddenLayer.of(minecraft.overworld()).removeBlock(GOLD));
            waitUntil(context, "client drops the removed hidden block", minecraft -> PhaseCraftClient.layer().get(GOLD).isAir());
            BufferedImage removed = screenshot(context, "gold_removed");
            // Compared with the overlay switched off right now, not with the earlier
            // screenshot: the view can drift a little over a long test.
            context.runOnClient(minecraft -> PhaseCraftClient.settings().setEnabled(false));
            context.waitTicks(2);
            BufferedImage removedOff = screenshot(context, "gold_removed_overlay_off");
            context.runOnClient(minecraft -> PhaseCraftClient.settings().setEnabled(true));
            double removedTint = gold.mean(removed, Channel.RED_MINUS_BLUE) - gold.mean(removedOff, Channel.RED_MINUS_BLUE);
            assertTrue(Math.abs(removedTint) < 8, "gold ghost still drawn after removal: " + removedTint);
            server.runOnServer(minecraft -> HiddenLayer.of(minecraft.overworld()).setBlock(GOLD, Blocks.GOLD_BLOCK.defaultBlockState()));
            waitForGhost(context, GOLD, Blocks.GOLD_BLOCK);

            // Far away, the chunks are dropped; back again, they return.
            server.runCommand("tp @a 1000.5 -60 0.5 0 0");
            waitUntil(context, "client forgets chunks out of range", minecraft -> PhaseCraftClient.layer().isEmpty());
            server.runCommand("tp @a 0.5 -60 0.5 0 0");
            waitForGhost(context, STONE, Blocks.LAPIS_BLOCK);

            // Survival players are sent nothing, and lose what they had.
            server.runCommand("gamemode survival @a");
            waitUntil(context, "survival player loses the overlay", minecraft -> PhaseCraftClient.layer().isEmpty());
            context.waitTicks(10);
            assertTrue(context.computeOnClient(minecraft -> PhaseCraftClient.layer().isEmpty()), "survival player was sent hidden blocks");
            server.runCommand("gamemode creative @a");
            waitForGhost(context, GOLD, Blocks.GOLD_BLOCK);

            // Inside the hidden layer the real blocks are there; no overlay.
            server.runCommand("execute as @a run phasecraft layer hidden");
            waitUntil(context, "no overlay inside the hidden layer", minecraft -> PhaseCraftClient.layer().isEmpty());
            server.runCommand("execute as @a run phasecraft layer visible");
            waitForGhost(context, GOLD, Blocks.GOLD_BLOCK);
            screenshot(context, "overlay_back");
        }
    }

    static void waitForGhost(ClientGameTestContext context, BlockPos pos, Block block) {
        waitUntil(context, "client receives hidden " + block + " at " + pos.toShortString(),
                minecraft -> PhaseCraftClient.layer().get(pos).is(block));
    }

    /** waitFor, failing with what was being waited for. */
    static void waitUntil(ClientGameTestContext context, String what, Predicate<Minecraft> condition) {
        try {
            context.waitFor(condition);
        } catch (AssertionError e) {
            throw new AssertionError("Timed out: " + what, e);
        }
    }

    private static BufferedImage screenshot(ClientGameTestContext context, String name) {
        Path path = context.takeScreenshot(name);
        try {
            return ImageIO.read(path.toFile());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private enum Channel {
        RED_MINUS_BLUE, BLUE_MINUS_RED, RED_MINUS_GREEN, GREEN_MINUS_RED;

        int of(int rgb) {
            int red = (rgb >> 16) & 0xFF;
            int green = (rgb >> 8) & 0xFF;
            int blue = rgb & 0xFF;
            return switch (this) {
                case RED_MINUS_BLUE -> red - blue;
                case BLUE_MINUS_RED -> blue - red;
                case RED_MINUS_GREEN -> red - green;
                case GREEN_MINUS_RED -> green - red;
            };
        }
    }

    /** A small square of screen pixels. */
    private record Box(int x, int y, int half) {
        double mean(BufferedImage image, Channel channel) {
            long sum = 0;
            int count = 0;
            for (int px = x - half; px <= x + half; px++) {
                for (int py = y - half; py <= y + half; py++) {
                    sum += channel.of(image.getRGB(px, py));
                    count++;
                }
            }
            return (double) sum / count;
        }
    }

    /** Pixels around where a world point appears, looking south (+Z) with yaw 0 and pitch 0. */
    private static Box project(BufferedImage image, double worldX, double worldY, double worldZ, int half) {
        double fov = Math.toRadians(70);
        double focal = (image.getHeight() / 2.0) / Math.tan(fov / 2);
        double depth = worldZ - EYE_Z;
        // Facing +Z, screen right is -X.
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

    /** The middle of a block's top face. */
    private static Box topBox(BufferedImage image, BlockPos pos) {
        return project(image, pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5, 2);
    }
}
