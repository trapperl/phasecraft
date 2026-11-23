package dev.phasecraft.test;

import dev.phasecraft.layer.PhaseLayerData;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/**
 * A full server restart, split across two separate server processes that
 * share one world folder ({@code ./gradlew restartTest}):
 *
 * <ol>
 *   <li>{@code writeBeforeRestart} stores hidden cells; the server then shuts
 *       down and saves normally.</li>
 *   <li>{@code verifyAfterRestart} runs in a brand-new server process and
 *       checks every cell was loaded back from disk.</li>
 * </ol>
 *
 * <p>The class name deliberately does not end in {@code GameTests}, so the
 * ordinary {@code runGameTest} filter skips these phases.</p>
 */
public class PhaseLayerRestartPhases {
    private record Expected(ResourceKey<Level> dimension, BlockPos pos, String block) {}

    /** Same XYZ in every dimension, plus a cell far outside any loaded chunk. */
    private static final List<Expected> CELLS = List.of(
            new Expected(Level.OVERWORLD, new BlockPos(10, 64, 10), "minecraft:redstone_wire"),
            new Expected(Level.OVERWORLD, new BlockPos(1_000_000, -40, -1_000_000), "minecraft:piston"),
            new Expected(Level.NETHER, new BlockPos(10, 64, 10), "minecraft:sticky_piston"),
            new Expected(Level.END, new BlockPos(10, 64, 10), "minecraft:obsidian"));

    @GameTest
    public void writeBeforeRestart(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        // A newly created layer is always saved once, so first flush every
        // layer to disk: the edits below then only survive if PhaseLayerData
        // itself marks them as needing a save, as in a long-running world.
        for (Expected cell : CELLS) {
            ServerLevel level = server.getLevel(cell.dimension());
            PhaseLayerData.get(level);
            level.getDataStorage().saveAndJoin();
        }
        for (Expected cell : CELLS) {
            helper.assertFalse(PhaseLayerData.get(server.getLevel(cell.dimension())).isDirty(),
                    "layer in " + cell.dimension().identifier() + " still unsaved before the edits");
        }
        for (Expected cell : CELLS) {
            PhaseLayerData layer = PhaseLayerData.get(server.getLevel(cell.dimension()));
            helper.assertValueEqual(layer.getBlock(cell.pos()), "minecraft:air",
                    "cell before write in " + cell.dimension().identifier() + " (stale world folder?)");
            layer.setBlock(cell.pos(), cell.block());
        }
        helper.succeed();
    }

    @GameTest
    public void verifyAfterRestart(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        for (Expected cell : CELLS) {
            PhaseLayerData layer = PhaseLayerData.get(server.getLevel(cell.dimension()));
            helper.assertValueEqual(layer.getBlock(cell.pos()), cell.block(),
                    "cell " + cell.pos().toShortString() + " in " + cell.dimension().identifier() + " after restart");
        }
        PhaseLayerData overworld = PhaseLayerData.get(server.getLevel(Level.OVERWORLD));
        helper.assertValueEqual(overworld.size(), 2, "overworld cell count after restart");
        helper.succeed();
    }
}
