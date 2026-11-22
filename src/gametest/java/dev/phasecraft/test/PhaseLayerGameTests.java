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
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
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
    private static final String OBSIDIAN = "minecraft:obsidian";
    private static final String AIR = "minecraft:air";

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

    /** The Overworld, Nether and End each keep their own hidden layer at the same XYZ. */
    @GameTest
    public void dimensionsHaveIndependentLayers(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        BlockPos pos = helper.absolutePos(new BlockPos(4, 2, 4));
        PhaseLayerData overworld = PhaseLayerData.get(server.getLevel(Level.OVERWORLD));
        PhaseLayerData nether = PhaseLayerData.get(server.getLevel(Level.NETHER));
        PhaseLayerData end = PhaseLayerData.get(server.getLevel(Level.END));
        helper.assertFalse(overworld == nether || overworld == end || nether == end,
                "dimensions share one layer object");

        overworld.setBlock(pos, REDSTONE_WIRE);
        helper.assertValueEqual(nether.getBlock(pos), AIR, "nether cell after overworld write");
        helper.assertValueEqual(end.getBlock(pos), AIR, "end cell after overworld write");

        nether.setBlock(pos, PISTON);
        end.setBlock(pos, OBSIDIAN);
        helper.assertValueEqual(overworld.getBlock(pos), REDSTONE_WIRE, "overworld cell");
        helper.assertValueEqual(nether.getBlock(pos), PISTON, "nether cell");
        helper.assertValueEqual(end.getBlock(pos), OBSIDIAN, "end cell");

        overworld.removeBlock(pos);
        nether.removeBlock(pos);
        end.removeBlock(pos);
        helper.succeed();
    }

    /** Hidden cells can be set and read in chunks that are not loaded, without loading them. */
    @GameTest
    public void unloadedChunksCanHoldHiddenCells(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos far = helper.absolutePos(new BlockPos(0, 1, 0)).offset(1_000_000, 0, 1_000_000);
        int chunkX = far.getX() >> 4;
        int chunkZ = far.getZ() >> 4;
        helper.assertFalse(level.getChunkSource().hasChunk(chunkX, chunkZ), "far chunk was already loaded");

        PhaseLayerData layer = PhaseLayerData.get(level);
        layer.setBlock(far, REDSTONE_WIRE);
        helper.assertValueEqual(layer.getBlock(far), REDSTONE_WIRE, "far hidden cell");
        helper.assertFalse(level.getChunkSource().hasChunk(chunkX, chunkZ), "hidden-layer access loaded the chunk");

        layer.removeBlock(far);
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
