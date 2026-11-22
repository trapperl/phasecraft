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
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.storage.SavedDataStorage;

/**
 * In-game tests for the hidden layer, run by {@code ./gradlew runGameTest}.
 *
 * <p>Each test works inside its own 8x8x8 test area. Hidden cells are keyed by
 * absolute position, so every test clears the cells it set before finishing.</p>
 */
public class PhaseLayerGameTests {
    private static final BlockState REDSTONE_WIRE = Blocks.REDSTONE_WIRE.defaultBlockState();
    private static final BlockState PISTON = Blocks.PISTON.defaultBlockState()
            .setValue(BlockStateProperties.FACING, Direction.UP);
    private static final BlockState REPEATER = Blocks.REPEATER.defaultBlockState()
            .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST)
            .setValue(BlockStateProperties.DELAY, 3);
    private static final BlockState OBSIDIAN = Blocks.OBSIDIAN.defaultBlockState();
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

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
        BlockPos repeater = new BlockPos(3, 70, -3);
        Path folder = createTempFolder();
        try {
            SavedDataStorage first = new SavedDataStorage(folder, server.getFixerUpper(), server.registryAccess());
            PhaseLayerData written = PhaseLayerData.get(first);
            written.setBlock(wire, REDSTONE_WIRE);
            written.setBlock(piston, PISTON);
            written.setBlock(repeater, REPEATER);
            first.close();

            helper.assertTrue(Files.isRegularFile(folder.resolve("phasecraft").resolve("layer_1.dat")),
                    "layer_1.dat was not written");

            SavedDataStorage second = new SavedDataStorage(folder, server.getFixerUpper(), server.registryAccess());
            PhaseLayerData reloaded = PhaseLayerData.get(second);
            helper.assertFalse(reloaded == written, "reload returned the in-memory instance");
            helper.assertValueEqual(reloaded.size(), 3, "reloaded cell count");
            helper.assertValueEqual(reloaded.getBlock(wire), REDSTONE_WIRE, "reloaded wire cell");
            helper.assertValueEqual(reloaded.getBlock(piston), PISTON, "reloaded piston cell (facing up)");
            helper.assertValueEqual(reloaded.getBlock(repeater), REPEATER, "reloaded repeater cell (east, delay 3)");
            second.close();
        } finally {
            deleteRecursively(folder);
        }
        helper.succeed();
    }

    /** Block-state properties (facing, delay, ...) are stored, not just the block type. */
    @GameTest
    public void blockStatePropertiesAreKept(GameTestHelper helper) {
        BlockPos pos = helper.absolutePos(new BlockPos(5, 1, 5));
        PhaseLayerData layer = PhaseLayerData.get(helper.getLevel());

        layer.setBlock(pos, REPEATER);
        helper.assertValueEqual(layer.getBlock(pos), REPEATER, "hidden repeater");
        helper.assertFalse(layer.getBlock(pos).equals(Blocks.REPEATER.defaultBlockState()),
                "repeater lost its properties");

        layer.removeBlock(pos);
        helper.succeed();
    }

    /**
     * A layer file saved before block states were stored ({@code block} IDs
     * only) still loads, each cell becoming that block's default state.
     */
    @GameTest
    public void legacyBlockIdFilesStillLoad(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        BlockPos wire = new BlockPos(10, 64, 10);
        BlockPos piston = new BlockPos(-5, -32, 7);
        Path folder = createTempFolder();
        try {
            ListTag cells = new ListTag();
            cells.add(legacyCell(wire, "minecraft:redstone_wire"));
            cells.add(legacyCell(piston, "minecraft:piston"));
            CompoundTag root = new CompoundTag();
            root.put("data", cells);
            NbtUtils.addCurrentDataVersion(root);
            Path file = folder.resolve("phasecraft").resolve("layer_1.dat");
            Files.createDirectories(file.getParent());
            NbtIo.writeCompressed(root, file);

            SavedDataStorage storage = new SavedDataStorage(folder, server.getFixerUpper(), server.registryAccess());
            PhaseLayerData loaded = PhaseLayerData.get(storage);
            helper.assertValueEqual(loaded.size(), 2, "legacy cell count");
            helper.assertValueEqual(loaded.getBlock(wire), REDSTONE_WIRE, "legacy wire cell");
            helper.assertValueEqual(loaded.getBlock(piston), Blocks.PISTON.defaultBlockState(), "legacy piston cell");
            storage.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            deleteRecursively(folder);
        }
        helper.succeed();
    }

    private static CompoundTag legacyCell(BlockPos pos, String blockId) {
        CompoundTag cell = new CompoundTag();
        cell.putLong("pos", pos.asLong());
        cell.putString("block", blockId);
        return cell;
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
