package dev.phasecraft.test.client;

import dev.phasecraft.PhaseCraftBlocks;
import dev.phasecraft.layer.HiddenLayer;
import dev.phasecraft.test.NoMojangTrafficGameTests;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * A block moved by a phase piston slides on the player's screen, as one moved by
 * a vanilla piston does, instead of blinking out and back in: at every tick of
 * the move the client shows the block either in place or sliding (a moving-block
 * entity carrying it), and it does show it sliding.
 */
public class PhasePistonAnimationClientGameTest implements FabricClientGameTest {
    private static final BlockPos PISTON = new BlockPos(0, -60, 8);
    private static final BlockPos START = PISTON.east();
    private static final BlockPos END = START.east();

    private record Seen(boolean shown, boolean sliding, String describe) {}

    /** What the client shows of the gold block at the two cells it moves between. */
    private static Seen look(ClientGameTestContext context) {
        return context.computeOnClient(minecraft -> {
            boolean shown = false;
            boolean sliding = false;
            StringBuilder out = new StringBuilder();
            for (BlockPos pos : new BlockPos[] {START, END}) {
                var state = minecraft.level.getBlockState(pos);
                boolean gold = state.is(Blocks.GOLD_BLOCK);
                boolean moving = minecraft.level.getBlockEntity(pos) instanceof PistonMovingBlockEntity entity
                        && entity.getMovedState().is(Blocks.GOLD_BLOCK);
                shown |= gold || moving;
                sliding |= moving;
                out.append(' ').append(pos.toShortString()).append('=').append(state.getBlock().getDescriptionId().replaceAll(".*\\.", ""))
                        .append(moving ? "+sliding" : "");
            }
            return new Seen(shown, sliding, out.toString());
        });
    }

    /** Watches the move tick by tick until the client shows the gold block at done; fails on a tick with no gold shown. */
    private static void watchMove(ClientGameTestContext context, BlockPos done, String what) {
        boolean slid = false;
        for (int tick = 0; tick < 40; tick++) {
            Seen seen = look(context);
            if (!seen.shown()) {
                throw new AssertionError(what + ": the block blinked out on the client at tick " + tick + ":" + seen.describe());
            }
            if (seen.sliding()) {
                context.takeScreenshot("phase_piston_" + what.replace(' ', '_') + "_tick" + tick);
            }
            slid |= seen.sliding();
            boolean arrived = context.computeOnClient(minecraft -> minecraft.level.getBlockState(done).is(Blocks.GOLD_BLOCK));
            if (arrived && tick > 0) {
                if (!slid) {
                    throw new AssertionError(what + ": the client never showed the block sliding");
                }
                return;
            }
            context.waitTicks(1);
        }
        throw new AssertionError(what + ": the block never arrived on the client:" + look(context).describe());
    }

    @Override
    public void runTest(ClientGameTestContext context) {
        String mojang = NoMojangTrafficGameTests.problem();
        if (mojang != null) {
            throw new AssertionError("test client would contact Mojang: " + mojang);
        }
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            TestServerContext server = world.getServer();
            server.runCommand("gamemode spectator @a");
            server.runCommand("tp @a 1.5 -59 12.5 180 20");
            server.runCommand("setblock " + START.getX() + " " + START.getY() + " " + START.getZ() + " minecraft:gold_block");
            server.runOnServer(minecraft -> HiddenLayer.of(minecraft.overworld()).setBlock(PISTON,
                    PhaseCraftBlocks.STICKY_PHASE_PISTON.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.EAST)));
            world.getConnection().waitForChunksRender();
            context.getInput().pressKey(options -> options.keyToggleGui);
            context.runOnClient(minecraft -> minecraft.gui.hud.getChat().clearMessages(false));
            context.waitTicks(5);
            context.takeScreenshot("phase_piston_before");

            server.runOnServer(minecraft -> HiddenLayer.of(minecraft.overworld()).setBlock(PISTON.above(),
                    Blocks.REDSTONE_BLOCK.defaultBlockState()));
            watchMove(context, END, "pushed");
            context.waitTicks(5);
            server.runOnServer(minecraft -> HiddenLayer.of(minecraft.overworld()).removeBlock(PISTON.above()));
            watchMove(context, START, "pulled back");
        }
    }
}
