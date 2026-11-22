package dev.phasecraft.test;

import dev.phasecraft.layer.HiddenDimensions;
import dev.phasecraft.layer.HiddenLayer;
import dev.phasecraft.layer.LegacyLayerMigration;
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
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.storage.SavedDataStorage;

/**
 * In-game tests for the hidden layer, run by {@code ./gradlew runGameTest}.
 *
 * <p>The hidden layer is a real dimension per visible dimension, at the same
 * coordinates. Each test works at positions inside its own 8x8x8 test area and
 * clears the hidden cells it set before finishing.</p>
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
        HiddenLayer layer = HiddenLayer.of(helper.getLevel());

        helper.setBlock(relative, Blocks.STONE);
        layer.setBlock(absolute.below(), Blocks.STONE.defaultBlockState());
        layer.setBlock(absolute, REDSTONE_WIRE);

        helper.assertBlockPresent(Blocks.STONE, relative);
        helper.assertValueEqual(layer.getBlock(absolute), REDSTONE_WIRE, "hidden block");

        layer.removeBlock(absolute);
        layer.removeBlock(absolute.below());
        helper.assertBlockPresent(Blocks.STONE, relative);
        helper.succeed();
    }

    /** Breaking an overworld block does not destroy hidden machinery. */
    @GameTest
    public void breakingVisibleBlockKeepsHiddenCell(GameTestHelper helper) {
        BlockPos relative = new BlockPos(3, 1, 3);
        BlockPos absolute = helper.absolutePos(relative);
        HiddenLayer layer = HiddenLayer.of(helper.getLevel());

        helper.setBlock(relative, Blocks.STONE);
        layer.setBlock(absolute, PISTON);
        helper.destroyBlock(relative);

        helper.assertBlockPresent(Blocks.AIR, relative);
        helper.assertValueEqual(layer.getBlock(absolute), PISTON, "hidden block after break");

        layer.removeBlock(absolute);
        helper.succeed();
    }

    /** Block-state properties (facing, delay, ...) are stored, not just the block type. */
    @GameTest
    public void blockStatePropertiesAreKept(GameTestHelper helper) {
        BlockPos pos = helper.absolutePos(new BlockPos(5, 1, 5));
        HiddenLayer layer = HiddenLayer.of(helper.getLevel());

        layer.setBlock(pos.below(), Blocks.STONE.defaultBlockState());
        layer.setBlock(pos, REPEATER);
        helper.assertValueEqual(layer.getBlock(pos), REPEATER, "hidden repeater");

        layer.removeBlock(pos);
        layer.removeBlock(pos.below());
        helper.succeed();
    }

    /** The hidden layer is its own dimension, and vanilla redstone runs in it. */
    @GameTest
    public void vanillaRedstoneRunsInHiddenLayer(GameTestHelper helper) {
        ServerLevel visible = helper.getLevel();
        HiddenLayer layer = HiddenLayer.of(visible);
        helper.assertValueEqual(layer.level().dimension(), HiddenDimensions.HIDDEN_OVERWORLD, "hidden dimension");
        helper.assertFalse(layer.level() == visible, "hidden layer is the visible level");

        BlockPos lamp = helper.absolutePos(new BlockPos(2, 2, 5));
        layer.setBlock(lamp, Blocks.REDSTONE_LAMP.defaultBlockState());
        helper.assertFalse(layer.getBlock(lamp).getValue(RedstoneLampBlock.LIT), "lamp lit before power");

        layer.setBlock(lamp.east(), Blocks.REDSTONE_BLOCK.defaultBlockState());
        helper.assertTrue(layer.getBlock(lamp).getValue(RedstoneLampBlock.LIT), "hidden lamp not lit by hidden power");
        helper.assertValueEqual(visible.getBlockState(lamp), AIR, "visible block at lamp position");

        layer.removeBlock(lamp.east());
        layer.removeBlock(lamp);
        helper.succeed();
    }

    /** Vanilla placement rules apply in the hidden layer: wire with nothing under it does not stay. */
    @GameTest
    public void unsupportedHiddenWireFollowsVanillaRules(GameTestHelper helper) {
        BlockPos pos = helper.absolutePos(new BlockPos(6, 3, 6));
        HiddenLayer layer = HiddenLayer.of(helper.getLevel());

        layer.setBlock(pos, REDSTONE_WIRE);
        helper.assertValueEqual(layer.getBlock(pos), AIR, "floating hidden wire");
        helper.succeed();
    }

    /** The Overworld, Nether and End each have their own hidden layer at the same XYZ. */
    @GameTest
    public void dimensionsHaveIndependentLayers(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        BlockPos pos = helper.absolutePos(new BlockPos(4, 2, 4));
        HiddenLayer overworld = HiddenLayer.of(server.getLevel(Level.OVERWORLD));
        HiddenLayer nether = HiddenLayer.of(server.getLevel(Level.NETHER));
        HiddenLayer end = HiddenLayer.of(server.getLevel(Level.END));
        helper.assertValueEqual(nether.level().dimension(), HiddenDimensions.HIDDEN_NETHER, "nether's hidden dimension");
        helper.assertValueEqual(end.level().dimension(), HiddenDimensions.HIDDEN_END, "end's hidden dimension");

        overworld.setBlock(pos.below(), Blocks.STONE.defaultBlockState());
        overworld.setBlock(pos, REDSTONE_WIRE);
        helper.assertValueEqual(nether.getBlock(pos), AIR, "nether cell after overworld write");
        helper.assertValueEqual(end.getBlock(pos), AIR, "end cell after overworld write");

        nether.setBlock(pos, PISTON);
        end.setBlock(pos, OBSIDIAN);
        helper.assertValueEqual(overworld.getBlock(pos), REDSTONE_WIRE, "overworld cell");
        helper.assertValueEqual(nether.getBlock(pos), PISTON, "nether cell");
        helper.assertValueEqual(end.getBlock(pos), OBSIDIAN, "end cell");

        overworld.removeBlock(pos);
        overworld.removeBlock(pos.below());
        nether.removeBlock(pos);
        end.removeBlock(pos);
        helper.succeed();
    }

    /** Using the hidden layer far away does not load the visible world's chunk there. */
    @GameTest
    public void hiddenAccessDoesNotLoadVisibleChunks(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos far = helper.absolutePos(new BlockPos(0, 1, 0)).offset(1_000_000, 0, 1_000_000);
        int chunkX = far.getX() >> 4;
        int chunkZ = far.getZ() >> 4;
        helper.assertFalse(level.getChunkSource().hasChunk(chunkX, chunkZ), "far chunk was already loaded");

        HiddenLayer layer = HiddenLayer.of(level);
        layer.setBlock(far, OBSIDIAN);
        helper.assertValueEqual(layer.getBlock(far), OBSIDIAN, "far hidden cell");
        helper.assertFalse(level.getChunkSource().hasChunk(chunkX, chunkZ), "hidden-layer access loaded the visible chunk");

        layer.removeBlock(far);
        helper.succeed();
    }

    /**
     * Hidden blocks stored by earlier builds (a block list per dimension) move
     * into the hidden dimension with their exact states, and the old list is emptied.
     */
    @GameTest
    public void legacyLayerMigratesIntoHiddenDimension(GameTestHelper helper) {
        ServerLevel visible = helper.getLevel();
        BlockPos wire = helper.absolutePos(new BlockPos(1, 2, 1));
        BlockPos repeater = helper.absolutePos(new BlockPos(1, 2, 3));
        PhaseLayerData legacy = PhaseLayerData.get(visible);
        legacy.setBlock(wire, REDSTONE_WIRE);
        legacy.setBlock(repeater, REPEATER);

        int moved = LegacyLayerMigration.migrate(visible);

        HiddenLayer layer = HiddenLayer.of(visible);
        helper.assertValueEqual(moved, 2, "cells migrated");
        helper.assertValueEqual(layer.getBlock(wire), REDSTONE_WIRE, "migrated wire");
        helper.assertValueEqual(layer.getBlock(repeater), REPEATER, "migrated repeater (east, delay 3)");
        helper.assertValueEqual(legacy.size(), 0, "legacy cells left after migration");
        helper.assertValueEqual(LegacyLayerMigration.migrate(visible), 0, "cells migrated on a second run");

        layer.removeBlock(wire);
        layer.removeBlock(repeater);
        helper.succeed();
    }

    /**
     * Old-style cells outside the hidden dimension's height range (the old
     * block list accepted any Y; the Nether starts at Y=0) cannot be placed:
     * they are kept in the old list, not silently dropped.
     */
    @GameTest
    public void legacyCellsOutsideWorldHeightAreKept(GameTestHelper helper) {
        ServerLevel nether = helper.getLevel().getServer().getLevel(Level.NETHER);
        BlockPos below = new BlockPos(24, -60, 0);
        BlockPos inside = new BlockPos(24, 64, 0);
        PhaseLayerData legacy = PhaseLayerData.get(nether);
        legacy.setBlock(below, OBSIDIAN);
        legacy.setBlock(inside, OBSIDIAN);

        helper.assertValueEqual(LegacyLayerMigration.migrate(nether), 1, "cells migrated");
        helper.assertValueEqual(HiddenLayer.of(nether).getBlock(inside), OBSIDIAN, "migrated cell inside the world");
        helper.assertValueEqual(legacy.size(), 1, "old-style cells kept");
        helper.assertValueEqual(legacy.getBlock(below), OBSIDIAN, "out-of-range cell kept in the old list");

        legacy.removeBlock(below);
        HiddenLayer.of(nether).removeBlock(inside);
        helper.succeed();
    }

    /**
     * A legacy file in the first prototype's {@code {pos, block}} format can
     * still be read (so it can be migrated), each cell as the default state.
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
