package dev.phasecraft.test.client;

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
            context.takeScreenshot("editing_target_visible");

            // Right click: a hidden lamp at the stone's own spot; the stone stays.
            context.getInput().pressKey(options -> options.keyUse);
            waitForGhost(context, STONE, Blocks.REDSTONE_LAMP);
            assertVisible(server, STONE, Blocks.STONE);
            assertHidden(server, STONE, Blocks.REDSTONE_LAMP);

            // Now the ghost is the target; right click places next to it, toward the player.
            waitUntil(context, "the hidden lamp is targeted", minecraft -> isTarget(STONE, true));
            context.takeScreenshot("editing_target_hidden");
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

            // Survival players cannot turn editing on.
            server.runCommand("gamemode survival @a");
            context.waitTicks(5);
            context.getInput().pressKey(PhaseCraftClient.TOGGLE_EDITING);
            context.waitTicks(5);
            assertTrue(!context.computeOnClient(minecraft -> ClientEditing.editing()), "survival player turned editing on");
        }
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
