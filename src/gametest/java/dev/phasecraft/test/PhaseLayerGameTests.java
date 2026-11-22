package dev.phasecraft.test;

import dev.phasecraft.layer.PhaseLayerData;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.SavedDataStorage;

/**
 * In-game tests for the hidden layer, run by {@code ./gradlew runGameTest}.
 *
 * <p>Each test works inside its own 8x8x8 test area. Hidden cells are keyed by
 * absolute position, so every test clears the cells it set before finishing.</p>
 */
public class PhaseLayerGameTests {
    private static final String REDSTONE_WIRE = "minecraft:redstone_wire";
    private static final String PISTON = "minecraft:piston";

    /** A hidden-layer block and an overworld block can coexist at the same XYZ. */
    @GameTest
    public void hiddenAndVisibleBlocksCoexist(GameTestHelper helper) {
        BlockPos relative = new BlockPos(2, 1, 2);
        BlockPos absolute = helper.absolutePos(relative);
        PhaseLayerData layer = PhaseLayerData.get(helper.getLevel());

        helper.setBlock(relative, Blocks.STONE);
        layer.setBlock(absolute, REDSTONE_WIRE);

        helper.assertBlockPresent(Blocks.STONE, relative);
        helper.assertValueEqual(layer.getBlock(absolute), REDSTONE_WIRE, "hidden block");

        layer.removeBlock(absolute);
        helper.assertBlockPresent(Blocks.STONE, relative);
        helper.succeed();
    }

    /** Breaking an overworld block does not destroy hidden machinery. */
    @GameTest
    public void breakingVisibleBlockKeepsHiddenCell(GameTestHelper helper) {
        BlockPos relative = new BlockPos(3, 1, 3);
        BlockPos absolute = helper.absolutePos(relative);
        PhaseLayerData layer = PhaseLayerData.get(helper.getLevel());

        helper.setBlock(relative, Blocks.STONE);
        layer.setBlock(absolute, PISTON);
        helper.destroyBlock(relative);

        helper.assertBlockPresent(Blocks.AIR, relative);
        helper.assertValueEqual(layer.getBlock(absolute), PISTON, "hidden block after break");

        layer.removeBlock(absolute);
        helper.succeed();
    }

    /**
     * Hidden cells written to disk are read back by a brand-new storage.
     *
     * <p>This exercises the real save path (codec, NBT file, Fabric's null
     * DataFixTypes handling) but not a full server restart, which GameTests
     * cannot perform.</p>
     */
    @GameTest
    public void hiddenLayerSurvivesSaveAndFreshLoad(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        BlockPos wire = new BlockPos(10, 64, 10);
        BlockPos piston = new BlockPos(-5, -32, 7);
        Path folder = createTempFolder();
        try {
            SavedDataStorage first = new SavedDataStorage(folder, server.getFixerUpper(), server.registryAccess());
            PhaseLayerData written = PhaseLayerData.get(first);
            written.setBlock(wire, REDSTONE_WIRE);
            written.setBlock(piston, PISTON);
            first.close();

            helper.assertTrue(Files.isRegularFile(folder.resolve("phasecraft").resolve("layer_1.dat")),
                    "layer_1.dat was not written");

            SavedDataStorage second = new SavedDataStorage(folder, server.getFixerUpper(), server.registryAccess());
            PhaseLayerData reloaded = PhaseLayerData.get(second);
            helper.assertFalse(reloaded == written, "reload returned the in-memory instance");
            helper.assertValueEqual(reloaded.size(), 2, "reloaded cell count");
            helper.assertValueEqual(reloaded.getBlock(wire), REDSTONE_WIRE, "reloaded wire cell");
            helper.assertValueEqual(reloaded.getBlock(piston), PISTON, "reloaded piston cell");
            second.close();
        } finally {
            deleteRecursively(folder);
        }
        helper.succeed();
    }

    private static Path createTempFolder() {
        try {
            return Files.createTempDirectory("phasecraft-gametest");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void deleteRecursively(Path folder) {
        try (Stream<Path> paths = Files.walk(folder)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
