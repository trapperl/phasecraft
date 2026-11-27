package dev.phasecraft.test.client;

import javax.imageio.ImageIO;
import java.util.UUID;
import java.util.Set;
import java.nio.file.Path;
import java.io.UncheckedIOException;
import java.io.IOException;
import java.awt.image.BufferedImage;
import dev.phasecraft.test.TestClaims;
import static dev.phasecraft.test.client.OverlayClientGameTest.assertTrue;
import static dev.phasecraft.test.client.OverlayClientGameTest.waitForGhost;
import static dev.phasecraft.test.client.OverlayClientGameTest.waitUntil;

import dev.phasecraft.client.ClientEditing;
import dev.phasecraft.client.PhaseCraftClient;
import dev.phasecraft.layer.HiddenLayer;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * Editing mode with a real client: J, then ordinary clicks act on the hidden
 * layer. A visible stone block stands in front of the player.
 */
public class EditingClientGameTest implements FabricClientGameTest {
    private static final BlockPos STONE = new BlockPos(0, -60, 3);

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            TestServerContext server = world.getServer();
            server.runCommand("gamemode creative @a");
            server.runCommand("time set noon");
            server.runCommand("gamerule advance_time false");
            server.runCommand("tp @a 0.5 -60 0.5 0 0");
            server.runCommand("setblock 0 -60 3 minecraft:stone");
            server.runOnServer(minecraft -> minecraft.getPlayerList().getPlayers().getFirst()
                    .setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.REDSTONE_LAMP)));
            world.getConnection().waitForChunksRender();
            waitUntil(context, "client holds the lamp", minecraft -> minecraft.player.getMainHandItem().is(Items.REDSTONE_LAMP));
            context.getInput().lookAt(STONE);
            if (context.computeOnClient(minecraft -> minecraft.gui.hud.isHidden())) {
                context.getInput().pressKey(options -> options.keyToggleGui); // show the HUD label
            }
            context.waitTicks(2);

            // J: editing on. The visible stone is the target: "a hidden block at this spot".
            context.getInput().pressKey(PhaseCraftClient.TOGGLE_EDITING);
            waitUntil(context, "J turns editing on", minecraft -> ClientEditing.editing());
            waitUntil(context, "the visible stone is targeted", minecraft -> isTarget(STONE, false));
            assertTrue(readout(context) == null, "a readout for a visible block: " + readout(context));
            context.takeScreenshot("editing_target_visible");

            // Right click: a hidden lamp at the stone's own spot; the stone stays.
            context.getInput().pressKey(options -> options.keyUse);
            waitForGhost(context, STONE, Blocks.REDSTONE_LAMP);
            assertVisible(server, STONE, Blocks.STONE);
            assertHidden(server, STONE, Blocks.REDSTONE_LAMP);

            // Now the ghost is the target; right click places next to it, toward the player.
            waitUntil(context, "the hidden lamp is targeted", minecraft -> isTarget(STONE, true));
            assertTrue("Redstone Lamp".equals(readout(context)), "readout of the hidden lamp: " + readout(context));
            Path purple = context.takeScreenshot("editing_target_hidden");
            // A claim refuses breaking the lamp here: the spot shows it before any click, red outline and readout.
            UUID stranger = UUID.randomUUID();
            server.runOnServer(minecraft -> TestClaims.claim(stranger, Set.of(TestClaims.Action.BREAK), STONE));
            waitUntil(context, "the readout says the lamp is protected",
                    minecraft -> readoutIs("Redstone Lamp · Protected by a claim"));
            Path red = context.takeScreenshot("editing_protected");
            int redBefore = redPixels(purple);
            int redAfter = redPixels(red);
            assertTrue(redAfter > redBefore + 200, "the protected spot's outline is not red: " + redBefore + " red pixels before, "
                    + redAfter + " after");
            server.runOnServer(minecraft -> TestClaims.unclaim(STONE));
            waitUntil(context, "the readout no longer says protected", minecraft -> readoutIs("Redstone Lamp"));
            // The readout names the hidden block aimed at and the parts of its state a builder needs.
            server.runCommand("phasecraft set 0 -60 3 phasecraft:line_shifter[delay=3,facing=up]");
            waitUntil(context, "the readout shows the shifter piston",
                    minecraft -> readoutIs("Shifter Piston · delay 3 · facing up · holding 0"));
            // Powered (a hidden redstone block beside it), it takes the visible block above it: the readout counts it.
            server.runCommand("setblock 0 -59 3 minecraft:gold_block");
            server.runCommand("phasecraft set 1 -60 3 minecraft:redstone_block");
            waitUntil(context, "the readout counts the held block",
                    minecraft -> readoutIs("Shifter Piston · delay 3 · facing up · powered · holding 1"));
            context.takeScreenshot("editing_readout");
            // Out of range and back: the chunk is sent again, with what its shifters hold.
            server.runCommand("tp @a 1000.5 -60 0.5");
            waitUntil(context, "the hidden blocks around the shifter forgotten", minecraft -> PhaseCraftClient.layer().get(STONE).isAir());
            server.runCommand("tp @a 0.5 -60 0.5 0 0");
            world.getConnection().waitForChunksRender();
            context.getInput().lookAt(STONE);
            waitUntil(context, "the readout counts the held block after the chunk came back",
                    minecraft -> readoutIs("Shifter Piston · delay 3 · facing up · powered · holding 1"));
            server.runCommand("phasecraft set 1 -60 3 minecraft:air");
            waitUntil(context, "the shifter puts the block back",
                    minecraft -> readoutIs("Shifter Piston · delay 3 · facing up · holding 0"));
            server.runCommand("setblock 0 -59 3 minecraft:air");
            server.runCommand("phasecraft set 0 -60 3 minecraft:redstone_lamp");
            waitForGhost(context, STONE, Blocks.REDSTONE_LAMP);
            // Middle click picks the hidden block, not the visible stone at the same spot.
            server.runOnServer(minecraft -> minecraft.getPlayerList().getPlayers().getFirst()
                    .setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.OAK_PLANKS)));
            waitUntil(context, "client holds planks", minecraft -> minecraft.player.getMainHandItem().is(Items.OAK_PLANKS));
            context.getInput().pressKey(options -> options.keyPickItem);
            waitUntil(context, "middle click picks the hidden lamp",
                    minecraft -> minecraft.player.getMainHandItem().is(Items.REDSTONE_LAMP));
            Direction face = context.computeOnClient(minecraft -> ClientEditing.target().hit().getDirection());
            BlockPos next = STONE.relative(face);
            context.getInput().pressKey(options -> options.keyUse);
            waitForGhost(context, next, Blocks.REDSTONE_LAMP);
            assertVisible(server, next, Blocks.AIR);

            // Left click breaks hidden blocks only, nearest first.
            waitUntil(context, "the new hidden lamp is targeted", minecraft -> isTarget(next, true));
            context.getInput().pressKey(options -> options.keyAttack);
            waitUntil(context, "first hidden lamp broken", minecraft -> PhaseCraftClient.layer().get(next).isAir());
            context.waitTicks(5);
            context.getInput().pressKey(options -> options.keyAttack);
            waitUntil(context, "second hidden lamp broken", minecraft -> PhaseCraftClient.layer().get(STONE).isAir());
            context.waitTicks(5);
            context.getInput().pressKey(options -> options.keyAttack);
            context.waitTicks(10);
            assertVisible(server, STONE, Blocks.STONE);
            assertHidden(server, STONE, Blocks.AIR);

            // J again: back to the visible world; the same right click places a visible lamp.
            context.getInput().pressKey(PhaseCraftClient.TOGGLE_EDITING);
            waitUntil(context, "J turns editing off", minecraft -> !ClientEditing.editing());
            context.waitTicks(2);
            Direction visibleFace = context.computeOnClient(minecraft ->
                    ((net.minecraft.world.phys.BlockHitResult) minecraft.hitResult).getDirection());
            context.getInput().pressKey(options -> options.keyUse);
            context.waitTicks(10);
            assertVisible(server, STONE.relative(visibleFace), Blocks.REDSTONE_LAMP);
            assertHidden(server, STONE.relative(visibleFace), Blocks.AIR);

            // Survival players can turn editing on while the rule is on, not with it off.
            server.runCommand("gamemode survival @a");
            context.waitTicks(5);
            context.getInput().pressKey(PhaseCraftClient.TOGGLE_EDITING);
            context.waitTicks(5);
            assertTrue(context.computeOnClient(minecraft -> ClientEditing.editing()), "survival player (rule on) could not turn editing on");
            context.getInput().pressKey(PhaseCraftClient.TOGGLE_EDITING);
            server.runCommand("gamerule phasecraft:survival_access false");
            context.waitTicks(5);
            context.getInput().pressKey(PhaseCraftClient.TOGGLE_EDITING);
            context.waitTicks(5);
            assertTrue(!context.computeOnClient(minecraft -> ClientEditing.editing()), "survival player (rule off) turned editing on");
            server.runCommand("gamerule phasecraft:survival_access true");
        }
    }

    /** Strongly red pixels (the protected outline's colour, 0xFF4040) in a screenshot, below the HUD's readout (also red). */
    private static int redPixels(Path screenshot) {
        BufferedImage image;
        try {
            image = ImageIO.read(screenshot.toFile());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        int count = 0;
        for (int y = image.getHeight() / 6; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int rgb = image.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                if (r > 200 && g < 110 && b < 110) {
                    count++;
                }
            }
        }
        return count;
    }

    private static boolean readoutIs(String expected) {
        return ClientEditing.readout() != null && expected.equals(ClientEditing.readout().getString());
    }

    private static String readout(ClientGameTestContext context) {
        return context.computeOnClient(minecraft -> {
            var line = ClientEditing.readout();
            return line == null ? null : line.getString();
        });
    }

    private static boolean isTarget(BlockPos pos, boolean hidden) {
        ClientEditing.Target target = ClientEditing.target();
        return target != null && target.hidden() == hidden && target.hit().getBlockPos().equals(pos);
    }

    private static void assertVisible(TestServerContext server, BlockPos pos, Block block) {
        boolean ok = server.computeOnServer(minecraft -> minecraft.overworld().getBlockState(pos).is(block));
        assertTrue(ok, "visible block at " + pos.toShortString() + " is not " + block);
    }

    private static void assertHidden(TestServerContext server, BlockPos pos, Block block) {
        boolean ok = server.computeOnServer(minecraft -> HiddenLayer.of(minecraft.overworld()).getBlock(pos).is(block));
        assertTrue(ok, "hidden block at " + pos.toShortString() + " is not " + block);
    }
}
